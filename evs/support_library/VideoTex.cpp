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
#include <vector>
#include <stdio.h>
#include <fcntl.h>
#include <alloca.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <malloc.h>
#include <png.h>

#include "VideoTex.h"
#include "glError.h"
#include "StreamHandlerManager.h"

#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferAllocator.h>
#include <ui/GraphicBufferMapper.h>

namespace android {
namespace automotive {
namespace evs {
namespace support {

// Eventually we shouldn't need this dependency, but for now the
// graphics allocator interface isn't fully supported on all platforms
// and this is our work around.
using ::android::GraphicBuffer;


VideoTex::VideoTex(sp<IEvsEnumerator> pEnum,
                   sp<IEvsCamera> pCamera,
                   sp<StreamHandler> pStreamHandler,
                   EGLDisplay glDisplay)
    : TexWrapper()
    , mEnumerator(pEnum)
    , mCamera(pCamera)
    , mStreamHandler(pStreamHandler)
    , mDisplay(glDisplay) {
    // Nothing but initialization here...
}

VideoTex::~VideoTex() {
    // Tell the stream to stop flowing
    mStreamHandler->asyncStopStream();

    // Close the camera
    mEnumerator->closeCamera(mCamera);

    // Drop our device texture image
    if (mKHRimage != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(mDisplay, mKHRimage);
        mKHRimage = EGL_NO_IMAGE_KHR;
    }
}


// Return true if the texture contents are changed
bool VideoTex::refresh(BaseRenderCallback* callback) {
    if (!mStreamHandler->newFrameAvailable()) {
        // No new image has been delivered, so there's nothing to do here
        return false;
    }

    // If we already have an image backing us, then it's time to return it
    if (mImageBuffer.memHandle.getNativeHandle() != nullptr) {
        // Drop our device texture image
        if (mKHRimage != EGL_NO_IMAGE_KHR) {
            eglDestroyImageKHR(mDisplay, mKHRimage);
            mKHRimage = EGL_NO_IMAGE_KHR;
        }

        // Return it since we're done with it
        mStreamHandler->doneWithFrame(mImageBuffer);
    }

    // Get the new image we want to use as our contents
    mImageBuffer = mStreamHandler->getNewFrame();

    sp<GraphicBuffer> imageGraphicBuffer = nullptr;

    buffer_handle_t inHandle;

    // If callback is not set, use the raw buffer for display.
    // If callback is set, copy the raw buffer to a newly allocated buffer.
    if (!callback) {
        inHandle = mImageBuffer.memHandle;
    } else {
        // create a GraphicBuffer from the existing handle
        sp<GraphicBuffer> rawBuffer = new GraphicBuffer(
            mImageBuffer.memHandle, GraphicBuffer::CLONE_HANDLE, mImageBuffer.width,
            mImageBuffer.height, mImageBuffer.format, 1,  // layer count
            GRALLOC_USAGE_HW_TEXTURE, mImageBuffer.stride);

        if (rawBuffer.get() == nullptr) {
            ALOGE("Failed to allocate GraphicBuffer to wrap image handle");
            // Returning "true" in this error condition because we already released the
            // previous image (if any) and so the texture may change in unpredictable ways now!
            return true;
        }

        // Lock the buffer and map it to a pointer
        void* rawDataPtr;
        rawBuffer->lock(GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_NEVER, &rawDataPtr);
        if (!rawDataPtr) {
            ALOGE("Failed to gain read access to imageGraphicBuffer");
            return false;
        }

        // Start copying the raw buffer. If the destination buffer has not been
        // allocated, use GraphicBufferAllocator to allocate it.
        if (!mHandleCopy) {
            android::GraphicBufferAllocator& alloc(android::GraphicBufferAllocator::get());
            android::status_t result = alloc.allocate(
                mImageBuffer.width, mImageBuffer.height, mImageBuffer.format, 1, mImageBuffer.usage,
                &mHandleCopy, &mImageBuffer.stride, 0, "EvsDisplay");
            if (result != android::NO_ERROR) {
                ALOGE("Error %d allocating %d x %d graphics buffer", result, mImageBuffer.width,
                      mImageBuffer.height);
                return false;
            }
            if (!mHandleCopy) {
                ALOGE("We didn't get a buffer handle back from the allocator");
                return false;
            }
        }

        // Lock the allocated buffer and map it to a pointer
        void* copyDataPtr = nullptr;
        android::GraphicBufferMapper& mapper = android::GraphicBufferMapper::get();
        mapper.lock(mHandleCopy, GRALLOC_USAGE_SW_WRITE_OFTEN | GRALLOC_USAGE_SW_READ_NEVER,
                    android::Rect(mImageBuffer.width, mImageBuffer.height), (void**)&copyDataPtr);

        // If we failed to lock the pixel buffer, we're about to crash, but log it first
        if (!copyDataPtr) {
            ALOGE("Camera failed to gain access to image buffer for writing");
            return false;
        }

        // Wrap the raw data and copied data, and pass them to the callback.
        Frame inputFrame = {
            .width = mImageBuffer.width,
            .height = mImageBuffer.height,
            .stride = mImageBuffer.stride,
            .data = (uint8_t*)rawDataPtr
        };

        Frame outputFrame = {
            .width = mImageBuffer.width,
            .height = mImageBuffer.height,
            .stride = mImageBuffer.stride,
            .data = (uint8_t*)copyDataPtr
        };

        callback->render(inputFrame, outputFrame);

        // Unlock the buffers after all changes to the buffer are completed.
        rawBuffer->unlock();
        mapper.unlock(mHandleCopy);

        inHandle = mHandleCopy;
    }

    // Create the graphic buffer for the dest buffer, and use it for
    // OpenGL rendering.
    imageGraphicBuffer =
        new GraphicBuffer(inHandle, GraphicBuffer::CLONE_HANDLE, mImageBuffer.width,
                          mImageBuffer.height, mImageBuffer.format, 1,  // layer count
                          GRALLOC_USAGE_HW_TEXTURE, mImageBuffer.stride);

    if (imageGraphicBuffer.get() == nullptr) {
        ALOGE("Failed to allocate GraphicBuffer to wrap image handle");
        // Returning "true" in this error condition because we already released the
        // previous image (if any) and so the texture may change in unpredictable ways now!
        return true;
    }


    // Get a GL compatible reference to the graphics buffer we've been given
    EGLint eglImageAttributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLClientBuffer clientBuf = static_cast<EGLClientBuffer>(imageGraphicBuffer->getNativeBuffer());
    mKHRimage = eglCreateImageKHR(mDisplay, EGL_NO_CONTEXT,
                                  EGL_NATIVE_BUFFER_ANDROID, clientBuf,
                                  eglImageAttributes);
    if (mKHRimage == EGL_NO_IMAGE_KHR) {
        const char *msg = getEGLError();
        ALOGE("error creating EGLImage: %s", msg);
        return false;
    } else {
        // Update the texture handle we already created to refer to this gralloc buffer
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, glId());
        glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, static_cast<GLeglImageOES>(mKHRimage));

        // Initialize the sampling properties (it seems the sample may not work if this isn't done)
        // The user of this texture may very well want to set their own filtering, but we're going
        // to pay the (minor) price of setting this up for them to avoid the dreaded "black image"
        // if they forget.
        // TODO:  Can we do this once for the texture ID rather than ever refresh?
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    return true;
}

VideoTex* createVideoTexture(sp<IEvsEnumerator> pEnum,
                             const char* evsCameraId,
                             EGLDisplay glDisplay) {
    // Set up the camera to feed this texture
    sp<IEvsCamera> pCamera = pEnum->openCamera(evsCameraId);
    if (pCamera.get() == nullptr) {
        ALOGE("Failed to allocate new EVS Camera interface for %s", evsCameraId);
        return nullptr;
    }

    // Initialize the stream that will help us update this texture's contents
    sp<StreamHandler> pStreamHandler =
        StreamHandlerManager::getInstance()->getStreamHandler(pCamera);
    if (pStreamHandler.get() == nullptr) {
        ALOGE("failed to allocate FrameHandler");
        return nullptr;
    }

    // Start the video stream
    if (!pStreamHandler->startStream()) {
        printf("Couldn't start the camera stream (%s)\n", evsCameraId);
        ALOGE("start stream failed for %s", evsCameraId);
        return nullptr;
    }

    return new VideoTex(pEnum, pCamera, pStreamHandler, glDisplay);
}

}  // namespace support
}  // namespace evs
}  // namespace automotive
}  // namespace android
