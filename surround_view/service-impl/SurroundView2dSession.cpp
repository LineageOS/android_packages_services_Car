/*
 * Copyright 2020 The Android Open Source Project
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
#define LOG_TAG "SurroundViewService"

#include <android-base/logging.h>
#include <android/hardware_buffer.h>
#include <utils/SystemClock.h>

#include "SurroundView2dSession.h"
#include "CoreLibSetupHelper.h"

using namespace android_auto::surround_view;

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace V1_0 {
namespace implementation {

static const char kGrayColor = 128;
static const int kNumChannels = 3;
static const int kFrameDelayInMilliseconds = 30;

SurroundView2dSession::SurroundView2dSession() :
    mStreamState(STOPPED) {
    mEvsCameraIds = {"0", "1", "2", "3"};
}

// Methods from ::android::hardware::automotive::sv::V1_0::ISurroundViewSession
Return<SvResult> SurroundView2dSession::startStream(
    const sp<ISurroundViewStream>& stream) {
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock<mutex> lock(mAccessLock);

    if (!mIsInitialized && !initialize()) {
        LOG(ERROR) << "There is an error while initializing the use case. "
                   << "Exiting";
        return SvResult::INTERNAL_ERROR;
    }

    if (mStreamState != STOPPED) {
        LOG(ERROR) << "Ignoring startVideoStream call"
                   << "when a stream is already running.";
        return SvResult::INTERNAL_ERROR;
    }

    if (stream == nullptr) {
        LOG(ERROR) << "The input stream is invalid";
        return SvResult::INTERNAL_ERROR;
    }
    mStream = stream;

    LOG(DEBUG) << "Notify SvEvent::STREAM_STARTED";
    mStream->notify(SvEvent::STREAM_STARTED);

    // Start the frame generation thread
    mStreamState = RUNNING;
    mCaptureThread = thread([this](){
        generateFrames();
    });

    return SvResult::OK;
}

Return<void> SurroundView2dSession::stopStream() {
    LOG(DEBUG) << __FUNCTION__;
    unique_lock<mutex> lock(mAccessLock);

    if (mStreamState == RUNNING) {
        // Tell the GenerateFrames loop we want it to stop
        mStreamState = STOPPING;

        // Block outside the mutex until the "stop" flag has been acknowledged
        // We won't send any more frames, but the client might still get some
        // already in flight
        LOG(DEBUG) << __FUNCTION__ << "Waiting for stream thread to end...";
        lock.unlock();
        mCaptureThread.join();
        lock.lock();

        mStreamState = STOPPED;
        mStream = nullptr;
        LOG(DEBUG) << "Stream marked STOPPED.";
    }

    return {};
}

Return<void> SurroundView2dSession::doneWithFrames(
    const SvFramesDesc& svFramesDesc){
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock <mutex> lock(mAccessLock);

    framesRecord.inUse = false;

    (void)svFramesDesc;
    return {};
}

// Methods from ISurroundView2dSession follow.
Return<void> SurroundView2dSession::get2dMappingInfo(
    get2dMappingInfo_cb _hidl_cb) {
    LOG(DEBUG) << __FUNCTION__;

    _hidl_cb(mInfo);
    return {};
}

Return<SvResult> SurroundView2dSession::set2dConfig(
    const Sv2dConfig& sv2dConfig) {
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock <mutex> lock(mAccessLock);

    if (sv2dConfig.width <=0 || sv2dConfig.width > 4096) {
        LOG(WARNING) << "The width of 2d config is out of the range (0, 4096]"
                     << "Ignored!";
        return SvResult::INVALID_ARG;
    }

    mConfig.width = sv2dConfig.width;
    mConfig.blending = sv2dConfig.blending;
    mHeight = mConfig.width * mInfo.height / mInfo.width;

    if (mStream != nullptr) {
        LOG(DEBUG) << "Notify SvEvent::CONFIG_UPDATED";
        mStream->notify(SvEvent::CONFIG_UPDATED);
    }

    return SvResult::OK;
}

Return<void> SurroundView2dSession::get2dConfig(get2dConfig_cb _hidl_cb) {
    LOG(DEBUG) << __FUNCTION__;

    _hidl_cb(mConfig);
    return {};
}

Return<void> SurroundView2dSession::projectCameraPoints(
        const hidl_vec<Point2dInt>& points2dCamera,
        const hidl_string& cameraId,
        projectCameraPoints_cb _hidl_cb) {
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock <mutex> lock(mAccessLock);

    bool cameraIdFound = false;
    for (auto& evsCameraId : mEvsCameraIds) {
      if (cameraId == evsCameraId) {
          cameraIdFound = true;
          LOG(INFO) << "Camera id found.";
          break;
      }
    }

    if (!cameraIdFound) {
        LOG(ERROR) << "Camera id not found.";
        _hidl_cb({});
        return {};
    }

    hidl_vec<Point2dFloat> outPoints;
    outPoints.resize(points2dCamera.size());

    int width = mConfig.width;
    int height = mHeight;
    for (int i=0; i<points2dCamera.size(); i++) {
        // Assuming all the points in the image frame can be projected into 2d
        // Surround View space. Otherwise cannot.
        if (points2dCamera[i].x < 0 || points2dCamera[i].x > width-1 ||
            points2dCamera[i].y < 0 || points2dCamera[i].y > height-1) {
            LOG(WARNING) << __FUNCTION__
                         << ": gets invalid 2d camera points. Ignored";
            outPoints[i].isValid = false;
            outPoints[i].x = 10000;
            outPoints[i].y = 10000;
        } else {
            outPoints[i].isValid = true;
            outPoints[i].x = 0;
            outPoints[i].y = 0;
        }
    }

    _hidl_cb(outPoints);
    return {};
}

void SurroundView2dSession::generateFrames() {
    int sequenceId = 0;

    while(true) {
        {
            scoped_lock<mutex> lock(mAccessLock);

            if (mStreamState != RUNNING) {
                // Break out of our main thread loop
                LOG(INFO) << "StreamState does not equal to RUNNING. "
                          << "Exiting the loop";
                break;
            }

            if (mOutputWidth != mConfig.width || mOutputHeight != mHeight) {
                LOG(DEBUG) << "Config changed. Re-allocate memory."
                           << " Old width: "
                           << mOutputWidth
                           << " Old height: "
                           << mOutputHeight
                           << " New width: "
                           << mConfig.width
                           << " New height: "
                           << mHeight;
                delete[] static_cast<char*>(mOutputPointer.data_pointer);
                mOutputWidth = mConfig.width;
                mOutputHeight = mHeight;
                mOutputPointer.height = mOutputHeight;
                mOutputPointer.width = mOutputWidth;
                mOutputPointer.format = Format::RGB;
                mOutputPointer.data_pointer =
                    new char[mOutputHeight * mOutputWidth * kNumChannels];

                if (!mOutputPointer.data_pointer) {
                    LOG(ERROR) << "Memory allocation failed. Exiting.";
                    break;
                }

                Size2dInteger size = Size2dInteger(mOutputWidth, mOutputHeight);
                mSurroundView->Update2dOutputResolution(size);

                mSvTexture = new GraphicBuffer(mOutputWidth,
                                               mOutputHeight,
                                               HAL_PIXEL_FORMAT_RGB_888,
                                               1,
                                               GRALLOC_USAGE_HW_TEXTURE,
                                               "SvTexture");
                if (mSvTexture->initCheck() == OK) {
                    LOG(INFO) << "Successfully allocated Graphic Buffer";
                } else {
                    LOG(ERROR) << "Failed to allocate Graphic Buffer";
                    break;
                }
            }
        }

        if (mSurroundView->Get2dSurroundView(mInputPointers, &mOutputPointer)) {
            LOG(INFO) << "Get2dSurroundView succeeded";
        } else {
            LOG(ERROR) << "Get2dSurroundView failed. "
                       << "Using memset to initialize to gray";
            memset(mOutputPointer.data_pointer, kGrayColor,
                   mOutputHeight * mOutputWidth * kNumChannels);
        }

        void* textureDataPtr = nullptr;
        mSvTexture->lock(GRALLOC_USAGE_SW_WRITE_OFTEN
                         | GRALLOC_USAGE_SW_READ_NEVER,
                         &textureDataPtr);
        if (!textureDataPtr) {
            LOG(ERROR) << "Failed to gain write access to GraphicBuffer!";
            break;
        }

        // Note: there is a chance that the stride of the texture is not the same
        // as the width. For example, when the input frame is 1920 * 1080, the
        // width is 1080, but the stride is 2048. So we'd better copy the data line
        // by line, instead of single memcpy.
        uint8_t* writePtr = static_cast<uint8_t*>(textureDataPtr);
        uint8_t* readPtr = static_cast<uint8_t*>(mOutputPointer.data_pointer);
        const int readStride = mOutputWidth * kNumChannels;
        const int writeStride = mSvTexture->getStride() * kNumChannels;
        if (readStride == writeStride) {
            memcpy(writePtr, readPtr, readStride * mSvTexture->getHeight());
        } else {
            for (int i=0; i<mSvTexture->getHeight(); i++) {
                memcpy(writePtr, readPtr, readStride);
                writePtr = writePtr + writeStride;
                readPtr = readPtr + readStride;
            }
        }
        LOG(INFO) << "memcpy finished";
        mSvTexture->unlock();

        ANativeWindowBuffer* buffer = mSvTexture->getNativeBuffer();
        LOG(DEBUG) << "ANativeWindowBuffer->handle: "
                   << buffer->handle;

        framesRecord.frames.svBuffers.resize(1);
        SvBuffer& svBuffer = framesRecord.frames.svBuffers[0];
        svBuffer.viewId = 0;
        svBuffer.hardwareBuffer.nativeHandle = buffer->handle;
        AHardwareBuffer_Desc* pDesc =
            reinterpret_cast<AHardwareBuffer_Desc *>(
                &svBuffer.hardwareBuffer.description);
        pDesc->width = mOutputWidth;
        pDesc->height = mOutputHeight;
        pDesc->layers = 1;
        pDesc->usage = GRALLOC_USAGE_HW_TEXTURE;
        pDesc->stride = mSvTexture->getStride();
        pDesc->format = HAL_PIXEL_FORMAT_RGB_888;
        framesRecord.frames.timestampNs = elapsedRealtimeNano();
        framesRecord.frames.sequenceId = sequenceId++;

        {
            scoped_lock<mutex> lock(mAccessLock);

            if (framesRecord.inUse) {
                LOG(DEBUG) << "Notify SvEvent::FRAME_DROPPED";
                mStream->notify(SvEvent::FRAME_DROPPED);
            } else {
                framesRecord.inUse = true;
                mStream->receiveFrames(framesRecord.frames);
            }
        }

        // TODO(b/150412555): adding delays explicitly. This delay should be
        // removed when EVS camera is used.
        this_thread::sleep_for(chrono::milliseconds(
            kFrameDelayInMilliseconds));
    }

    // If we've been asked to stop, send an event to signal the actual
    // end of stream
    LOG(DEBUG) << "Notify SvEvent::STREAM_STOPPED";
    mStream->notify(SvEvent::STREAM_STOPPED);
}

bool SurroundView2dSession::initialize() {
    lock_guard<mutex> lock(mAccessLock, adopt_lock);

    // TODO(b/150412555): ask core-lib team to add API description for "create"
    // method in the .h file.
    // The create method will never return a null pointer based the API
    // description.
    mSurroundView = unique_ptr<SurroundView>(Create());

    mSurroundView->SetStaticData(GetCameras(), Get2dParams(), Get3dParams(),
                                 GetUndistortionScales(), GetBoundingBox());

    // TODO(b/150412555): remove after EVS camera is used
    mInputPointers = mSurroundView->ReadImages(
        "/etc/automotive/sv/cam0.png",
        "/etc/automotive/sv/cam1.png",
        "/etc/automotive/sv/cam2.png",
        "/etc/automotive/sv/cam3.png");
    if (mInputPointers.size() == 4
        && mInputPointers[0].cpu_data_pointer != nullptr) {
        LOG(INFO) << "ReadImages succeeded";
    } else {
        LOG(ERROR) << "Failed to read images";
        return false;
    }

    mOutputWidth = Get2dParams().resolution.width;
    mOutputHeight = Get2dParams().resolution.height;

    mConfig.width = mOutputWidth;
    mConfig.blending = SvQuality::HIGH;
    mHeight = mOutputHeight;

    mOutputPointer.height = mOutputHeight;
    mOutputPointer.width = mOutputWidth;
    mOutputPointer.format = mInputPointers[0].format;
    mOutputPointer.data_pointer = new char[
        mOutputHeight * mOutputWidth * kNumChannels];

    if (!mOutputPointer.data_pointer) {
        LOG(ERROR) << "Memory allocation failed. Exiting.";
        return false;
    }

    mSvTexture = new GraphicBuffer(mOutputWidth,
                                   mOutputHeight,
                                   HAL_PIXEL_FORMAT_RGB_888,
                                   1,
                                   GRALLOC_USAGE_HW_TEXTURE,
                                   "SvTexture");

    //TODO(b/150412555): the 2d mapping info should be read from config file.
    mInfo.width = 8;
    mInfo.height = 6;
    mInfo.center.isValid = true;
    mInfo.center.x = 0;
    mInfo.center.y = 0;

    if (mSvTexture->initCheck() == OK) {
        LOG(INFO) << "Successfully allocated Graphic Buffer";
    } else {
        LOG(ERROR) << "Failed to allocate Graphic Buffer";
        return false;
    }

    if (mSurroundView->Start2dPipeline()) {
        LOG(INFO) << "Start2dPipeline succeeded";
    } else {
        LOG(ERROR) << "Start2dPipeline failed";
        return false;
    }

    mIsInitialized = true;
    return true;
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android

