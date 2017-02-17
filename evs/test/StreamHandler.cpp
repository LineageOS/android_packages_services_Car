/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "EvsTest"

#include "StreamHandler.h"

#include <stdio.h>
#include <string.h>

#include <android/log.h>
#include <cutils/native_handle.h>
#include <ui/GraphicBufferMapper.h>

#include <algorithm>    // std::min


// For the moment, we're assuming that the underlying EVS driver we're working with
// is providing 4 byte RGBx data.  This is fine for loopback testing, although
// real hardware is expected to provide YUV data -- most likly formatted as YV12
static const unsigned kBytesPerPixel = 4;   // assuming 4 byte RGBx pixels


StreamHandler::StreamHandler(android::sp <IEvsCamera>  pCamera,  CameraDesc  cameraInfo,
                             android::sp <IEvsDisplay> pDisplay, DisplayDesc displayInfo) :
    mCamera(pCamera),
    mCameraInfo(cameraInfo),
    mDisplay(pDisplay),
    mDisplayInfo(displayInfo) {

    // Post a warning message if resolutions don't match since we handle it, but only
    // with simple/ugly results in copyBufferContents below.
    if ((mDisplayInfo.defaultHorResolution != cameraInfo.defaultHorResolution) ||
        (mDisplayInfo.defaultVerResolution != cameraInfo.defaultVerResolution)) {
        ALOGW("Camera and Display resolutions don't match -- images will be clipped");
    }
}


void StreamHandler::startStream() {
    // Mark ourselves as running
    mLock.lock();
    mRunning = true;
    mLock.unlock();

    // Tell the camera to start streaming
    mCamera->startVideoStream(this);
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
    mSignal.wait(lock, [this](){ return !mRunning; });
}


bool StreamHandler::isRunning() {
    std::unique_lock<std::mutex> lock(mLock);
    return mRunning;
}


unsigned StreamHandler::getFramesReceived() {
    std::unique_lock<std::mutex> lock(mLock);
    return mFramesReceived;
};


unsigned StreamHandler::getFramesCompleted() {
    std::unique_lock<std::mutex> lock(mLock);
    return mFramesCompleted;
};


Return<void> StreamHandler::deliverFrame(const BufferDesc& bufferArg) {
    ALOGD("Received a frame from the camera (%p)", bufferArg.memHandle.getNativeHandle());

    // TODO:  Why do we get a gralloc crash if we don't clone the buffer here?
    BufferDesc buffer(bufferArg);
    ALOGD("Clone the received frame as %p", buffer.memHandle.getNativeHandle());

    if (buffer.memHandle.getNativeHandle() == nullptr) {
        printf("Got end of stream notification\n");

        // Signal that the last frame has been received and the stream is stopped
        mLock.lock();
        mRunning = false;
        mLock.unlock();
        mSignal.notify_all();

        ALOGI("End of stream signaled");
    } else {
        // Quick and dirty so that we can monitor frame delivery for testing
        mLock.lock();
        mFramesReceived++;
        mLock.unlock();

        // Get the output buffer we'll use to display the imagery
        BufferDesc tgtBuffer = {};
        mDisplay->getTargetBuffer([&tgtBuffer]
                                  (const BufferDesc& buff) {
                                      tgtBuffer = buff;
                                      ALOGD("Got output buffer (%p) with id %d cloned as (%p)",
                                            buff.memHandle.getNativeHandle(),
                                            tgtBuffer.bufferId,
                                            tgtBuffer.memHandle.getNativeHandle());
                                  }
        );

        if (tgtBuffer.memHandle == nullptr) {
            printf("Didn't get target buffer - frame lost\n");
            ALOGE("Didn't get requested output buffer -- skipping this frame.");
        } else {
            // In order for the handles passed through HIDL and stored in the BufferDesc to
            // be lockable, we must register them with GraphicBufferMapper
            registerBufferHelper(tgtBuffer);
            registerBufferHelper(buffer);

            // Copy the contents of the of buffer.memHandle into tgtBuffer
            copyBufferContents(tgtBuffer, buffer);

            // TODO:  Add a bit of overlay graphics?
            // TODO:  Use OpenGL to render from texture?
            // NOTE:  If we mess with the frame contents, we'll need to update the frame inspection
            //        logic in the default (test) display driver.

            // Send the target buffer back for display
            ALOGD("Calling returnTargetBufferForDisplay (%p)",
                  tgtBuffer.memHandle.getNativeHandle());
            Return<EvsResult> result = mDisplay->returnTargetBufferForDisplay(tgtBuffer);
            if (!result.isOk()) {
                printf("HIDL error on display buffer (%s)- frame lost\n",
                       result.description().c_str());
                ALOGE("Error making the remote function call.  HIDL said %s",
                      result.description().c_str());
            } else if (result != EvsResult::OK) {
                printf("Display reported error - frame lost\n");
                ALOGE("We encountered error %d when returning a buffer to the display!",
                      (EvsResult)result);
            } else {
                // Everything looks good!  Keep track so tests or watch dogs can monitor progress
                mLock.lock();
                mFramesCompleted++;
                mLock.unlock();
                printf("frame OK\n");
            }

            // Now tell GraphicBufferMapper we won't be using these handles anymore
            unregisterBufferHelper(tgtBuffer);
            unregisterBufferHelper(buffer);
        }

        // Send the camera buffer back now that we're done with it
        ALOGD("Calling doneWithFrame");
        // TODO:  Why is it that we get a HIDL crash if we pass back the cloned buffer?
        mCamera->doneWithFrame(bufferArg);

        ALOGD("Frame handling complete");
    }

    return Void();
}


bool StreamHandler::copyBufferContents(const BufferDesc& tgtBuffer,
                                       const BufferDesc& srcBuffer) {
    bool success = true;

    // Make sure we don't run off the end of either buffer
    const unsigned width     = std::min(tgtBuffer.width,
                                        srcBuffer.width);
    const unsigned height    = std::min(tgtBuffer.height,
                                        srcBuffer.height);

    android::GraphicBufferMapper &mapper = android::GraphicBufferMapper::get();


    // Lock our source buffer for reading
    unsigned char* srcPixels = nullptr;
    mapper.registerBuffer(srcBuffer.memHandle);
    mapper.lock(srcBuffer.memHandle,
                GRALLOC_USAGE_SW_READ_OFTEN,
                android::Rect(width, height),
                (void **) &srcPixels);

    // Lock our target buffer for writing
    unsigned char* tgtPixels = nullptr;
    mapper.registerBuffer(tgtBuffer.memHandle);
    mapper.lock(tgtBuffer.memHandle,
                GRALLOC_USAGE_SW_WRITE_OFTEN,
                android::Rect(width, height),
                (void **) &tgtPixels);

    if (srcPixels && tgtPixels) {
        for (unsigned row = 0; row < height; row++) {
            // Copy the entire row of pixel data
            memcpy(tgtPixels, srcPixels, width * kBytesPerPixel);

            // Advance to the next row (keeping in mind that stride here is in units of pixels)
            tgtPixels += tgtBuffer.stride * kBytesPerPixel;
            srcPixels += srcBuffer.stride * kBytesPerPixel;
        }
    } else {
        ALOGE("Failed to copy buffer contents");
        success = false;
    }

    if (srcPixels) {
        mapper.unlock(srcBuffer.memHandle);
    }
    if (tgtPixels) {
        mapper.unlock(tgtBuffer.memHandle);
    }
    mapper.unregisterBuffer(srcBuffer.memHandle);
    mapper.unregisterBuffer(tgtBuffer.memHandle);

    return success;
}


void StreamHandler::registerBufferHelper(const BufferDesc& buffer)
{
    // In order for the handles passed through HIDL and stored in the BufferDesc to
    // be lockable, we must register them with GraphicBufferMapper.
    // If the device upon which we're running supports gralloc1, we could just call
    // registerBuffer directly with the handle.  But that call  is broken for gralloc0 devices
    // (which we care about, at least for now).  As a result, we have to synthesize a GraphicBuffer
    // object around the buffer handle in order to make a call to the overloaded alternate
    // version of the registerBuffer call that does happen to work on gralloc0 devices.
#if REGISTER_BUFFER_ALWAYS_WORKS
    android::GraphicBufferMapper::get().registerBuffer(buffer.memHandle);
#else
    android::sp<android::GraphicBuffer> pGfxBuff = new android::GraphicBuffer(
            buffer.width, buffer.height, buffer.format,
            1, /* we always use exactly one layer */
            buffer.usage, buffer.stride,
            const_cast<native_handle_t*>(buffer.memHandle.getNativeHandle()),
            false /* GraphicBuffer should not try to free the handle */
    );

    android::GraphicBufferMapper::get().registerBuffer(pGfxBuff.get());
#endif
}


void StreamHandler::unregisterBufferHelper(const BufferDesc& buffer)
{
    // Now tell GraphicBufferMapper we won't be using these handles anymore
    android::GraphicBufferMapper::get().unregisterBuffer(buffer.memHandle);
}
