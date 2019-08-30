/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "StreamHandler.h"

#include <stdio.h>
#include <string.h>

#include <log/log.h>
#include <cutils/native_handle.h>

#include <ui/GraphicBufferAllocator.h>
#include <ui/GraphicBufferMapper.h>

#include "Frame.h"

namespace android {
namespace automotive {
namespace evs {
namespace support {

StreamHandler::StreamHandler(android::sp <IEvsCamera> pCamera) :
    mCamera(pCamera)
{
    // We rely on the camera having at least two buffers available since we'll hold one and
    // expect the camera to be able to capture a new image in the background.
    pCamera->setMaxFramesInFlight(2);
}


void StreamHandler::shutdown()
{
    // Make sure we're not still streaming
    blockingStopStream();

    // At this point, the receiver thread is no longer running, so we can safely drop
    // our remote object references so they can be freed
    mCamera = nullptr;
}


bool StreamHandler::startStream() {
    std::unique_lock<std::mutex> lock(mLock);

    if (!mRunning) {
        // Tell the camera to start streaming
        Return <EvsResult> result = mCamera->startVideoStream(this);
        if (result != EvsResult::OK) {
            return false;
        }

        // Mark ourselves as running
        mRunning = true;
    }

    return true;
}


void StreamHandler::asyncStopStream() {
    // Tell the camera to stop streaming.
    // This will result in a null frame being delivered when the stream actually stops.
    mCamera->stopVideoStream();
}


void StreamHandler::blockingStopStream() {
    // Tell the stream to stop
    asyncStopStream();

    // Wait until the stream has actually stopped
    std::unique_lock<std::mutex> lock(mLock);
    if (mRunning) {
        mSignal.wait(lock, [this]() { return !mRunning; });
    }
}


bool StreamHandler::isRunning() {
    std::unique_lock<std::mutex> lock(mLock);
    return mRunning;
}


bool StreamHandler::newDisplayFrameAvailable() {
    std::unique_lock<std::mutex> lock(mLock);
    return (mReadyBuffer >= 0);
}


const BufferDesc& StreamHandler::getNewDisplayFrame() {
    std::unique_lock<std::mutex> lock(mLock);

    if (mHeldBuffer >= 0) {
        ALOGE("Ignored call for new frame while still holding the old one.");
    } else {
        if (mReadyBuffer < 0) {
            ALOGE("Returning invalid buffer because we don't have any. "
                  " Call newDisplayFrameAvailable first?");
            mReadyBuffer = 0;   // This is a lie!
        }

        // Move the ready buffer into the held position, and clear the ready position
        mHeldBuffer = mReadyBuffer;
        mReadyBuffer = -1;
    }

    if (mRenderCallback == nullptr) {
        return mOriginalBuffers[mHeldBuffer];
    } else {
        return mProcessedBuffers[mHeldBuffer];
    }
}


void StreamHandler::doneWithFrame(const BufferDesc& buffer) {
    std::unique_lock<std::mutex> lock(mLock);

    // We better be getting back the buffer we original delivered!
    if ((mHeldBuffer < 0)
        || (buffer.bufferId != mOriginalBuffers[mHeldBuffer].bufferId)) {
        ALOGE("StreamHandler::doneWithFrame got an unexpected buffer!");
        return;
    }

    // Send the buffer back to the underlying camera
    mCamera->doneWithFrame(mOriginalBuffers[mHeldBuffer]);

    // Clear the held position
    mHeldBuffer = -1;
}


Return<void> StreamHandler::deliverFrame(const BufferDesc& buffer) {
    ALOGD("Received a frame from the camera (%p)", buffer.memHandle.getNativeHandle());

    // Take the lock to protect our frame slots and running state variable
    {
        std::unique_lock <std::mutex> lock(mLock);

        if (buffer.memHandle.getNativeHandle() == nullptr) {
            // Signal that the last frame has been received and the stream is stopped
            mRunning = false;
        } else {
            // Do we already have a "ready" frame?
            if (mReadyBuffer >= 0) {
                // Send the previously saved buffer back to the camera unused
                mCamera->doneWithFrame(mOriginalBuffers[mReadyBuffer]);

                // We'll reuse the same ready buffer index
            } else if (mHeldBuffer >= 0) {
                // The client is holding a buffer, so use the other slot for "on deck"
                mReadyBuffer = 1 - mHeldBuffer;
            } else {
                // This is our first buffer, so just pick a slot
                mReadyBuffer = 0;
            }

            // Save this frame until our client is interested in it
            mOriginalBuffers[mReadyBuffer] = buffer;

            if (mRenderCallback != nullptr) {
                processFrame(mOriginalBuffers[mReadyBuffer], mProcessedBuffers[mReadyBuffer]);
            } else {
                ALOGI("Render callback is null in deliverFrame.");
            }
        }
    }

    // Notify anybody who cares that things have changed
    mSignal.notify_all();

    return Void();
}

void StreamHandler::attachRenderCallback(BaseRenderCallback* callback) {
    ALOGD("StreamHandler::attachRenderCallback");
    if (mRenderCallback != nullptr) {
        ALOGW("Ignored! There should only be one render callback");
        return;
    }
    mRenderCallback = callback;
}

void StreamHandler::detachRenderCallback() {
    ALOGD("StreamHandler::detachRenderCallback");
    if (mRenderCallback == nullptr) {
        ALOGW("Ignored! There is no display use case attached");
        return;
    }
    mRenderCallback = nullptr;
}

bool isSameFormat(const BufferDesc& input, const BufferDesc& output) {
    return input.width == output.width
        && input.height == output.height
        && input.format == output.format
        && input.usage == output.usage
        && input.stride == output.stride
        && input.pixelSize == output.pixelSize;
}

bool allocate(BufferDesc& buffer) {
    ALOGD("StreamHandler::allocate");
    buffer_handle_t handle;
    android::GraphicBufferAllocator& alloc(android::GraphicBufferAllocator::get());
    android::status_t result = alloc.allocate(
        buffer.width, buffer.height, buffer.format, 1, buffer.usage,
        &handle, &buffer.stride, 0, "EvsDisplay");
    if (result != android::NO_ERROR) {
        ALOGE("Error %d allocating %d x %d graphics buffer", result, buffer.width,
              buffer.height);
        return false;
    }

    // The reason that we have to check null for "handle" is because that the
    // above "result" might not cover all the failure scenarios.
    // By looking into Gralloc4.cpp (and 3, 2, as well), it turned out that if
    // there is anything that goes wrong in the process of buffer importing (see
    // Ln 385 in Gralloc4.cpp), the error won't be covered by the above "result"
    // we got from "allocate" method. In other words, it means that there is
    // still a chance that the "result" is "NO_ERROR" but the handle is nullptr
    // (that means buffer importing failed).
    if (!handle) {
        ALOGE("We didn't get a buffer handle back from the allocator");
        return false;
    }

    buffer.memHandle = hidl_handle(handle);
    return true;
}

bool StreamHandler::processFrame(const BufferDesc& input,
                                 BufferDesc& output) {
    ALOGD("StreamHandler::processFrame");
    if (!isSameFormat(input, output)
        || output.memHandle.getNativeHandle() == nullptr) {
        output.width = input.width;
        output.height = input.height;
        output.format = input.format;
        output.usage = input.usage;
        output.stride = input.stride;
        output.pixelSize = input.pixelSize;
        output.bufferId = input.bufferId;

        // free the allocated output frame handle if it is not null
        if (output.memHandle.getNativeHandle() != nullptr) {
            GraphicBufferAllocator::get().free(output.memHandle);
        }

        if (!allocate(output)) {
            ALOGE("Error allocating buffer");
            return false;
        }
    }

    // Create a GraphicBuffer from the existing handle
    sp<GraphicBuffer> inputBuffer = new GraphicBuffer(
        input.memHandle, GraphicBuffer::CLONE_HANDLE, input.width,
        input.height, input.format, 1,  // layer count
        GRALLOC_USAGE_HW_TEXTURE, input.stride);

    if (inputBuffer.get() == nullptr) {
        ALOGE("Failed to allocate GraphicBuffer to wrap image handle");
        // Returning "true" in this error condition because we already released
        // the previous image (if any) and so the texture may change in
        // unpredictable ways now!
        return false;
    }

    // Lock the input GraphicBuffer and map it to a pointer. If we failed to
    // lock, return false.
    void* inputDataPtr;
    inputBuffer->lock(
        GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_NEVER,
        &inputDataPtr);

    // Unlock the buffer and return if lock did not succeed.
    if (!inputDataPtr) {
        ALOGE("Failed to gain read access to image buffer");

        // The program reaches at here when it fails to lock the buffer. But
        // it is still safer to unlock it. The reason is as described in "lock"
        // method in  Gralloc.h: "The ownership of acquireFence is always
        // transferred to the callee, even on errors."
        // And even if the buffer was not locked, it does not harm anything
        // given the comment for "unlock" method in IMapper.hal:
        // "`BAD_BUFFER` if the buffer is invalid or not locked."
        inputBuffer->unlock();
        return false;
    }

    // Lock the allocated buffer in output BufferDesc and map it to a pointer
    void* outputDataPtr = nullptr;
    android::GraphicBufferMapper& mapper = android::GraphicBufferMapper::get();
    mapper.lock(output.memHandle,
                GRALLOC_USAGE_SW_WRITE_OFTEN | GRALLOC_USAGE_SW_READ_NEVER,
                android::Rect(output.width, output.height),
                (void**)&outputDataPtr);

    // If we failed to lock the pixel buffer, return false, and unlock both
    // input and output buffers.
    if (!outputDataPtr) {
        ALOGE("Failed to gain write access to image buffer");

        // Please refer to the previous "if" block for why we want to unlock
        // the buffers even if the buffer locking fails.
        inputBuffer->unlock();
        mapper.unlock(output.memHandle);
        return false;
    }

    // Wrap the raw data and copied data, and pass them to the callback.
    Frame inputFrame = {
        .width = input.width,
        .height = input.height,
        .stride = input.stride,
        .data = (uint8_t*)inputDataPtr
    };

    Frame outputFrame = {
        .width = output.width,
        .height = output.height,
        .stride = output.stride,
        .data = (uint8_t*)outputDataPtr
    };

    mRenderCallback->render(inputFrame, outputFrame);

    // Unlock the buffers after all changes to the buffer are completed.
    inputBuffer->unlock();
    mapper.unlock(output.memHandle);

    return true;
}
}  // namespace support
}  // namespace evs
}  // namespace automotive
}  // namespace android
