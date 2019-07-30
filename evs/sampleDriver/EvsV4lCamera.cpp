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

#include "EvsV4lCamera.h"
#include "EvsEnumerator.h"
#include "bufferCopy.h"

#include <ui/GraphicBufferAllocator.h>
#include <ui/GraphicBufferMapper.h>
#include <android/hardware_buffer.h>


namespace android {
namespace hardware {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {


// Arbitrary limit on number of graphics buffers allowed to be allocated
// Safeguards against unreasonable resource consumption and provides a testable limit
static const unsigned MAX_BUFFERS_IN_FLIGHT = 100;


EvsV4lCamera::EvsV4lCamera(const char *deviceName) :
        mFramesAllowed(0),
        mFramesInUse(0) {
    ALOGD("EvsV4lCamera instantiated");

    mDescription.cameraId = deviceName;

    // Initialize the video device
    if (!mVideo.open(deviceName)) {
        ALOGE("Failed to open v4l device %s\n", deviceName);
    }

    // Output buffer format.
    // TODO: Does this need to be configurable?
    mFormat = HAL_PIXEL_FORMAT_RGBA_8888;

    // How we expect to use the gralloc buffers we'll exchange with our client
    mUsage  = GRALLOC_USAGE_HW_TEXTURE     |
              GRALLOC_USAGE_SW_READ_RARELY |
              GRALLOC_USAGE_SW_WRITE_OFTEN;
}


EvsV4lCamera::~EvsV4lCamera() {
    ALOGD("EvsV4lCamera being destroyed");
    shutdown();
}


//
// This gets called if another caller "steals" ownership of the camera
//
void EvsV4lCamera::shutdown()
{
    ALOGD("EvsV4lCamera shutdown");

    // Make sure our output stream is cleaned up
    // (It really should be already)
    stopVideoStream();

    // Note:  Since stopVideoStream is blocking, no other threads can now be running

    // Close our video capture device
    mVideo.close();

    // Drop all the graphics buffers we've been using
    if (mBuffers.size() > 0) {
        GraphicBufferAllocator& alloc(GraphicBufferAllocator::get());
        for (auto&& rec : mBuffers) {
            if (rec.inUse) {
                ALOGW("Error - releasing buffer despite remote ownership");
            }
            alloc.free(rec.handle);
            rec.handle = nullptr;
        }
        mBuffers.clear();
    }
}


// Methods from ::android::hardware::automotive::evs::V1_0::IEvsCamera follow.
Return<void> EvsV4lCamera::getCameraInfo(getCameraInfo_cb _hidl_cb) {
    ALOGD("getCameraInfo");

    // Send back our self description
    _hidl_cb(mDescription);
    return Void();
}


Return<EvsResult> EvsV4lCamera::setMaxFramesInFlight(uint32_t bufferCount) {
    ALOGD("setMaxFramesInFlight");
    std::lock_guard<std::mutex> lock(mAccessLock);

    // If we've been displaced by another owner of the camera, then we can't do anything else
    if (!mVideo.isOpen()) {
        ALOGW("ignoring setMaxFramesInFlight call when camera has been lost.");
        return EvsResult::OWNERSHIP_LOST;
    }

    // We cannot function without at least one video buffer to send data
    if (bufferCount < 1) {
        ALOGE("Ignoring setMaxFramesInFlight with less than one buffer requested");
        return EvsResult::INVALID_ARG;
    }

    // Update our internal state
    if (setAvailableFrames_Locked(bufferCount)) {
        return EvsResult::OK;
    } else {
        return EvsResult::BUFFER_NOT_AVAILABLE;
    }
}


Return<EvsResult> EvsV4lCamera::startVideoStream(const ::android::sp<IEvsCameraStream_1_0>& stream)  {
    ALOGD("startVideoStream");
    std::lock_guard<std::mutex> lock(mAccessLock);

    // If we've been displaced by another owner of the camera, then we can't do anything else
    if (!mVideo.isOpen()) {
        ALOGW("ignoring startVideoStream call when camera has been lost.");
        return EvsResult::OWNERSHIP_LOST;
    }
    if (mStream.get() != nullptr) {
        ALOGE("ignoring startVideoStream call when a stream is already running.");
        return EvsResult::STREAM_ALREADY_RUNNING;
    }

    // If the client never indicated otherwise, configure ourselves for a single streaming buffer
    if (mFramesAllowed < 1) {
        if (!setAvailableFrames_Locked(1)) {
            ALOGE("Failed to start stream because we couldn't get a graphics buffer");
            return EvsResult::BUFFER_NOT_AVAILABLE;
        }
    }

    // Choose which image transfer function we need
    // Map from V4L2 to Android graphic buffer format
    const uint32_t videoSrcFormat = mVideo.getV4LFormat();
    ALOGI("Configuring to accept %4.4s camera data and convert to 0x%X",
          (char*)&videoSrcFormat, mFormat);

    switch (mFormat) {
    case HAL_PIXEL_FORMAT_YCRCB_420_SP:
        switch (videoSrcFormat) {
        case V4L2_PIX_FMT_NV21:     mFillBufferFromVideo = fillNV21FromNV21;    break;
        case V4L2_PIX_FMT_YUYV:     mFillBufferFromVideo = fillNV21FromYUYV;    break;
        default:
            ALOGE("Unhandled camera output format %c%c%c%c (0x%8X)\n",
                  ((char*)&videoSrcFormat)[0],
                  ((char*)&videoSrcFormat)[1],
                  ((char*)&videoSrcFormat)[2],
                  ((char*)&videoSrcFormat)[3],
                  videoSrcFormat);
        }
        break;
    case HAL_PIXEL_FORMAT_RGBA_8888:
        switch (videoSrcFormat) {
        case V4L2_PIX_FMT_YUYV:     mFillBufferFromVideo = fillRGBAFromYUYV;    break;
        default:
            ALOGE("Unhandled camera format %4.4s", (char*)&videoSrcFormat);
        }
        break;
    case HAL_PIXEL_FORMAT_YCBCR_422_I:
        switch (videoSrcFormat) {
        case V4L2_PIX_FMT_YUYV:     mFillBufferFromVideo = fillYUYVFromYUYV;    break;
        case V4L2_PIX_FMT_UYVY:     mFillBufferFromVideo = fillYUYVFromUYVY;    break;
        default:
            ALOGE("Unhandled camera format %4.4s", (char*)&videoSrcFormat);
        }
        break;
    default:
        ALOGE("Unhandled output format %4.4s", (char*)&mFormat);
    }


    // Record the user's callback for use when we have a frame ready
    mStream = stream;
    mStream_1_1 = IEvsCameraStream_1_1::castFrom(mStream).withDefault(nullptr);

    // Set up the video stream with a callback to our member function forwardFrame()
    if (!mVideo.startStream([this](VideoCapture*, imageBuffer* tgt, void* data) {
                                this->forwardFrame(tgt, data);
                            })
    ) {
        // No need to hold onto this if we failed to start
        mStream = nullptr;
        mStream_1_1 = nullptr;
        ALOGE("underlying camera start stream failed");
        return EvsResult::UNDERLYING_SERVICE_ERROR;
    }

    return EvsResult::OK;
}


Return<void> EvsV4lCamera::doneWithFrame(const BufferDesc_1_0& buffer)  {
    ALOGD("doneWithFrame");
    doneWithFrame_impl(buffer.bufferId, buffer.memHandle);

    return Void();
}


Return<void> EvsV4lCamera::stopVideoStream()  {
    ALOGD("stopVideoStream");

    // Tell the capture device to stop (and block until it does)
    mVideo.stopStream();

    if (mStream_1_1 != nullptr) {
        // V1.1 client is waiting on STREAM_STOPPED event.
        std::unique_lock <std::mutex> lock(mAccessLock);

        EvsEvent event;
        InfoEventDesc desc = {};
        desc.aType = InfoEventType::STREAM_STOPPED;
        event.info(desc);
        auto result = mStream_1_1->notifyEvent(event);
        if (!result.isOk()) {
            ALOGE("Error delivering end of stream event");
        }

        // Drop our reference to the client's stream receiver
        mStream_1_1 = nullptr;
        mStream     = nullptr;
    } else if (mStream != nullptr) {
        std::unique_lock <std::mutex> lock(mAccessLock);

        // Send one last NULL frame to signal the actual end of stream
        BufferDesc_1_0 nullBuff = {};
        auto result = mStream->deliverFrame(nullBuff);
        if (!result.isOk()) {
            ALOGE("Error delivering end of stream marker");
        }

        // Drop our reference to the client's stream receiver
        mStream = nullptr;
    }

    return Void();
}


Return<int32_t> EvsV4lCamera::getExtendedInfo(uint32_t /*opaqueIdentifier*/)  {
    ALOGD("getExtendedInfo");
    // Return zero by default as required by the spec
    return 0;
}


Return<EvsResult> EvsV4lCamera::setExtendedInfo(uint32_t /*opaqueIdentifier*/,
                                                int32_t  /*opaqueValue*/)  {
    ALOGD("setExtendedInfo");
    std::lock_guard<std::mutex> lock(mAccessLock);

    // If we've been displaced by another owner of the camera, then we can't do anything else
    if (!mVideo.isOpen()) {
        ALOGW("ignoring setExtendedInfo call when camera has been lost.");
        return EvsResult::OWNERSHIP_LOST;
    }

    // We don't store any device specific information in this implementation
    return EvsResult::INVALID_ARG;
}


// Methods from ::android::hardware::automotive::evs::V1_1::IEvsCamera follow.
Return<EvsResult> EvsV4lCamera::doneWithFrame_1_1(const BufferDesc_1_1& desc)  {
    ALOGD("doneWithFrame");

    return doneWithFrame_impl(desc.bufferId, desc.buffer.nativeHandle);
}


Return<EvsResult> EvsV4lCamera::pauseVideoStream() {
    return EvsResult::UNDERLYING_SERVICE_ERROR;
}


Return<EvsResult> EvsV4lCamera::resumeVideoStream() {
    return EvsResult::UNDERLYING_SERVICE_ERROR;
}


Return<EvsResult> EvsV4lCamera::setMaster() {
    /* Because EVS HW module reference implementation expects a single client at
     * a time, this returns a success code always.
     */
    return EvsResult::OK;
}


Return<EvsResult> EvsV4lCamera::unsetMaster() {
    /* Because EVS HW module reference implementation expects a single client at
     * a time, there is no chance that this is called by a non-master client and
     * therefore returns a success code always.
     */
    return EvsResult::OK;
}


Return<void> EvsV4lCamera::setParameter(CameraParam id, int32_t value,
                                        setParameter_cb _hidl_cb) {
    uint32_t v4l2cid = V4L2_CID_BASE;
    if (!convertToV4l2CID(id, v4l2cid)) {
        _hidl_cb(EvsResult::INVALID_ARG, 0);
    } else {
        EvsResult result = EvsResult::OK;
        v4l2_control control = {v4l2cid, value};
        if (mVideo.setParameter(control) < 0 ||
            mVideo.getParameter(control) < 0) {
            result = EvsResult::UNDERLYING_SERVICE_ERROR;
        }

        _hidl_cb(result, control.value);
    }

    return Void();
}


Return<void> EvsV4lCamera::getParameter(CameraParam id,
                                        getParameter_cb _hidl_cb) {
    uint32_t v4l2cid = V4L2_CID_BASE;
    if (!convertToV4l2CID(id, v4l2cid)) {
        _hidl_cb(EvsResult::INVALID_ARG, 0);
    } else {
        EvsResult result = EvsResult::OK;
        v4l2_control control = {v4l2cid, 0};
        if (mVideo.getParameter(control) < 0) {
            result = EvsResult::INVALID_ARG;
        }

        // Report a result
        _hidl_cb(result, control.value);
    }

    return Void();
}


EvsResult EvsV4lCamera::doneWithFrame_impl(const uint32_t bufferId,
                                           const buffer_handle_t memHandle) {
    std::lock_guard <std::mutex> lock(mAccessLock);

    // If we've been displaced by another owner of the camera, then we can't do anything else
    if (!mVideo.isOpen()) {
        ALOGW("ignoring doneWithFrame call when camera has been lost.");
    } else {
        if (memHandle == nullptr) {
            ALOGE("ignoring doneWithFrame called with null handle");
        } else if (bufferId >= mBuffers.size()) {
            ALOGE("ignoring doneWithFrame called with invalid bufferId %d (max is %zu)",
                  bufferId, mBuffers.size()-1);
        } else if (!mBuffers[bufferId].inUse) {
            ALOGE("ignoring doneWithFrame called on frame %d which is already free",
                  bufferId);
        } else {
            // Mark the frame as available
            mBuffers[bufferId].inUse = false;
            mFramesInUse--;

            // If this frame's index is high in the array, try to move it down
            // to improve locality after mFramesAllowed has been reduced.
            if (bufferId >= mFramesAllowed) {
                // Find an empty slot lower in the array (which should always exist in this case)
                for (auto&& rec : mBuffers) {
                    if (rec.handle == nullptr) {
                        rec.handle = mBuffers[bufferId].handle;
                        mBuffers[bufferId].handle = nullptr;
                        break;
                    }
                }
            }
        }
    }

    return EvsResult::OK;
}


bool EvsV4lCamera::setAvailableFrames_Locked(unsigned bufferCount) {
    if (bufferCount < 1) {
        ALOGE("Ignoring request to set buffer count to zero");
        return false;
    }
    if (bufferCount > MAX_BUFFERS_IN_FLIGHT) {
        ALOGE("Rejecting buffer request in excess of internal limit");
        return false;
    }

    // Is an increase required?
    if (mFramesAllowed < bufferCount) {
        // An increase is required
        unsigned needed = bufferCount - mFramesAllowed;
        ALOGI("Allocating %d buffers for camera frames", needed);

        unsigned added = increaseAvailableFrames_Locked(needed);
        if (added != needed) {
            // If we didn't add all the frames we needed, then roll back to the previous state
            ALOGE("Rolling back to previous frame queue size");
            decreaseAvailableFrames_Locked(added);
            return false;
        }
    } else if (mFramesAllowed > bufferCount) {
        // A decrease is required
        unsigned framesToRelease = mFramesAllowed - bufferCount;
        ALOGI("Returning %d camera frame buffers", framesToRelease);

        unsigned released = decreaseAvailableFrames_Locked(framesToRelease);
        if (released != framesToRelease) {
            // This shouldn't happen with a properly behaving client because the client
            // should only make this call after returning sufficient outstanding buffers
            // to allow a clean resize.
            ALOGE("Buffer queue shrink failed -- too many buffers currently in use?");
        }
    }

    return true;
}


unsigned EvsV4lCamera::increaseAvailableFrames_Locked(unsigned numToAdd) {
    // Acquire the graphics buffer allocator
    GraphicBufferAllocator &alloc(GraphicBufferAllocator::get());

    unsigned added = 0;


    while (added < numToAdd) {
        unsigned pixelsPerLine;
        buffer_handle_t memHandle = nullptr;
        status_t result = alloc.allocate(mVideo.getWidth(), mVideo.getHeight(),
                                         mFormat, 1,
                                         mUsage,
                                         &memHandle, &pixelsPerLine, 0, "EvsV4lCamera");
        if (result != NO_ERROR) {
            ALOGE("Error %d allocating %d x %d graphics buffer",
                  result,
                  mVideo.getWidth(),
                  mVideo.getHeight());
            break;
        }
        if (!memHandle) {
            ALOGE("We didn't get a buffer handle back from the allocator");
            break;
        }
        if (mStride) {
            if (mStride != pixelsPerLine) {
                ALOGE("We did not expect to get buffers with different strides!");
            }
        } else {
            // Gralloc defines stride in terms of pixels per line
            mStride = pixelsPerLine;
        }

        // Find a place to store the new buffer
        bool stored = false;
        for (auto&& rec : mBuffers) {
            if (rec.handle == nullptr) {
                // Use this existing entry
                rec.handle = memHandle;
                rec.inUse = false;
                stored = true;
                break;
            }
        }
        if (!stored) {
            // Add a BufferRecord wrapping this handle to our set of available buffers
            mBuffers.emplace_back(memHandle);
        }

        mFramesAllowed++;
        added++;
    }

    return added;
}


unsigned EvsV4lCamera::decreaseAvailableFrames_Locked(unsigned numToRemove) {
    // Acquire the graphics buffer allocator
    GraphicBufferAllocator &alloc(GraphicBufferAllocator::get());

    unsigned removed = 0;

    for (auto&& rec : mBuffers) {
        // Is this record not in use, but holding a buffer that we can free?
        if ((rec.inUse == false) && (rec.handle != nullptr)) {
            // Release buffer and update the record so we can recognize it as "empty"
            alloc.free(rec.handle);
            rec.handle = nullptr;

            mFramesAllowed--;
            removed++;

            if (removed == numToRemove) {
                break;
            }
        }
    }

    return removed;
}


// This is the async callback from the video camera that tells us a frame is ready
void EvsV4lCamera::forwardFrame(imageBuffer* /*pV4lBuff*/, void* pData) {
    bool readyForFrame = false;
    size_t idx = 0;

    // Lock scope for updating shared state
    {
        std::lock_guard<std::mutex> lock(mAccessLock);

        // Are we allowed to issue another buffer?
        if (mFramesInUse >= mFramesAllowed) {
            // Can't do anything right now -- skip this frame
            ALOGW("Skipped a frame because too many are in flight\n");
        } else {
            // Identify an available buffer to fill
            for (idx = 0; idx < mBuffers.size(); idx++) {
                if (!mBuffers[idx].inUse) {
                    if (mBuffers[idx].handle != nullptr) {
                        // Found an available record, so stop looking
                        break;
                    }
                }
            }
            if (idx >= mBuffers.size()) {
                // This shouldn't happen since we already checked mFramesInUse vs mFramesAllowed
                ALOGE("Failed to find an available buffer slot\n");
            } else {
                // We're going to make the frame busy
                mBuffers[idx].inUse = true;
                mFramesInUse++;
                readyForFrame = true;
            }
        }
    }

    if (!readyForFrame) {
        // We need to return the vide buffer so it can capture a new frame
        mVideo.markFrameConsumed();
    } else {
        // Assemble the buffer description we'll transmit below
        BufferDesc_1_1 bufDesc_1_1 = {};
        AHardwareBuffer_Desc* pDesc =
            reinterpret_cast<AHardwareBuffer_Desc *>(&bufDesc_1_1.buffer.description);
        pDesc->width  = mVideo.getWidth();
        pDesc->height = mVideo.getHeight();
        pDesc->layers = 1;
        pDesc->format = mFormat;
        pDesc->usage  = mUsage;
        pDesc->stride = mStride;
        bufDesc_1_1.buffer.nativeHandle = mBuffers[idx].handle;
        bufDesc_1_1.bufferId = idx;

        // Lock our output buffer for writing
        void *targetPixels = nullptr;
        GraphicBufferMapper &mapper = GraphicBufferMapper::get();
        mapper.lock(bufDesc_1_1.buffer.nativeHandle,
                    GRALLOC_USAGE_SW_WRITE_OFTEN | GRALLOC_USAGE_SW_READ_NEVER,
                    android::Rect(pDesc->width, pDesc->height),
                    (void **)&targetPixels);

        // If we failed to lock the pixel buffer, we're about to crash, but log it first
        if (!targetPixels) {
            ALOGE("Camera failed to gain access to image buffer for writing");
        }

        // Transfer the video image into the output buffer, making any needed
        // format conversion along the way
        mFillBufferFromVideo(bufDesc_1_1, (uint8_t *)targetPixels, pData, mVideo.getStride());

        // Unlock the output buffer
        mapper.unlock(bufDesc_1_1.buffer.nativeHandle);

        // Give the video frame back to the underlying device for reuse
        // Note that we do this before making the client callback to give the
        // underlying camera more time to capture the next frame
        mVideo.markFrameConsumed();

        // Issue the (asynchronous) callback to the client -- can't be holding
        // the lock
        bool flag = false;
        if (mStream_1_1 != nullptr) {
            EvsEvent event;
            event.buffer(bufDesc_1_1);
            auto result = mStream_1_1->notifyEvent(event);
            flag = result.isOk();
        } else {
            BufferDesc_1_0 bufDesc_1_0 = {
                pDesc->width,
                pDesc->height,
                pDesc->stride,
                bufDesc_1_1.pixelSize,
                static_cast<uint32_t>(pDesc->format),
                static_cast<uint32_t>(pDesc->usage),
                bufDesc_1_1.bufferId,
                bufDesc_1_1.buffer.nativeHandle
            };

            auto result = mStream->deliverFrame(bufDesc_1_0);
            flag = result.isOk();
        }

        if (flag) {
            ALOGD("Delivered %p as id %d", bufDesc_1_1.buffer.nativeHandle.getNativeHandle(), bufDesc_1_1.bufferId);
        } else {
            // This can happen if the client dies and is likely unrecoverable.
            // To avoid consuming resources generating failing calls, we stop sending
            // frames.  Note, however, that the stream remains in the "STREAMING" state
            // until cleaned up on the main thread.
            ALOGE("Frame delivery call failed in the transport layer.");

            // Since we didn't actually deliver it, mark the frame as available
            std::lock_guard<std::mutex> lock(mAccessLock);
            mBuffers[idx].inUse = false;
            mFramesInUse--;
        }
    }
}


bool EvsV4lCamera::convertToV4l2CID(CameraParam id, uint32_t& v4l2cid) {
    switch (id) {
        case CameraParam::BRIGHTNESS:
            v4l2cid = V4L2_CID_BRIGHTNESS;
            break;
        case CameraParam::CONTRAST:
            v4l2cid = V4L2_CID_CONTRAST;
            break;
        case CameraParam::AUTO_WHITE_BALANCE:
            v4l2cid = V4L2_CID_AUTO_WHITE_BALANCE;
            break;
        case CameraParam::WHITE_BALANCE_TEMPERATURE:
            v4l2cid = V4L2_CID_WHITE_BALANCE_TEMPERATURE;
            break;
        case CameraParam::SHARPNESS:
            v4l2cid = V4L2_CID_SHARPNESS;
            break;
        case CameraParam::AUTO_EXPOSURE:
            v4l2cid = V4L2_CID_EXPOSURE_AUTO;
            break;
        case CameraParam::ABSOLUTE_EXPOSURE:
            v4l2cid = V4L2_CID_EXPOSURE_ABSOLUTE;
            break;
        case CameraParam::ABSOLUTE_ZOOM:
            v4l2cid = V4L2_CID_ZOOM_ABSOLUTE;
            break;
        default:
            ALOGE("Camera parameter %u is not supported.", id);
            return false;
    }

    return true;
}


} // namespace implementation
} // namespace V1_1
} // namespace evs
} // namespace automotive
} // namespace hardware
} // namespace android
