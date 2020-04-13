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
#include <android/hidl/memory/1.0/IMemory.h>
#include <hidlmemory/mapping.h>
#include <set>
#include <utils/SystemClock.h>

#include "SurroundView3dSession.h"
#include "sv_3d_params.h"

using ::android::hidl::memory::V1_0::IMemory;
using ::android::hardware::hidl_memory;

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace V1_0 {
namespace implementation {

static const uint8_t kGrayColor = 128;
static const int kNumChannels = 4;

SurroundView3dSession::SurroundView3dSession() :
    mStreamState(STOPPED){
    mEvsCameraIds = {"0" , "1", "2", "3"};
}

// Methods from ::android::hardware::automotive::sv::V1_0::ISurroundViewSession.
Return<SvResult> SurroundView3dSession::startStream(
    const sp<ISurroundViewStream>& stream) {
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock<mutex> lock(mAccessLock);

    if (!mIsInitialized && !initialize()) {
        LOG(ERROR) << "There is an error while initializing the use case. "
                   << "Exiting";
        return SvResult::INTERNAL_ERROR;
    }

    if (mStreamState != STOPPED) {
        LOG(ERROR) << "Ignoring startVideoStream call when a stream is "
                   << "already running.";
        return SvResult::INTERNAL_ERROR;
    }

    if (mViews.empty()) {
        LOG(ERROR) << "No views have been set for current Surround View"
                   << "3d Session. Please call setViews before starting"
                   << "the stream.";
        return SvResult::VIEW_NOT_SET;
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

Return<void> SurroundView3dSession::stopStream() {
    LOG(DEBUG) << __FUNCTION__;
    unique_lock <mutex> lock(mAccessLock);

    if (mStreamState == RUNNING) {
        // Tell the GenerateFrames loop we want it to stop
        mStreamState = STOPPING;

        // Block outside the mutex until the "stop" flag has been acknowledged
        // We won't send any more frames, but the client might still get some
        // already in flight
        LOG(DEBUG) << __FUNCTION__ << ": Waiting for stream thread to end...";
        lock.unlock();
        mCaptureThread.join();
        lock.lock();

        mStreamState = STOPPED;
        mStream = nullptr;
        LOG(DEBUG) << "Stream marked STOPPED.";
    }

    return {};
}

Return<void> SurroundView3dSession::doneWithFrames(
    const SvFramesDesc& svFramesDesc){
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock <mutex> lock(mAccessLock);

    framesRecord.inUse = false;

    (void)svFramesDesc;
    return {};
}

// Methods from ISurroundView3dSession follow.
Return<SvResult> SurroundView3dSession::setViews(
    const hidl_vec<View3d>& views) {
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock <mutex> lock(mAccessLock);

    mViews.resize(views.size());
    for (int i=0; i<views.size(); i++) {
        mViews[i] = views[i];
    }

    return SvResult::OK;
}

Return<SvResult> SurroundView3dSession::set3dConfig(const Sv3dConfig& sv3dConfig) {
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock <mutex> lock(mAccessLock);

    if (sv3dConfig.width <=0 || sv3dConfig.width > 4096) {
        LOG(WARNING) << "The width of 3d config is out of the range (0, 4096]"
                     << "Ignored!";
        return SvResult::INVALID_ARG;
    }

    if (sv3dConfig.height <=0 || sv3dConfig.height > 4096) {
        LOG(WARNING) << "The height of 3d config is out of the range (0, 4096]"
                     << "Ignored!";
        return SvResult::INVALID_ARG;
    }

    mConfig.width = sv3dConfig.width;
    mConfig.height = sv3dConfig.height;
    mConfig.carDetails = sv3dConfig.carDetails;

    if (mStream != nullptr) {
        LOG(DEBUG) << "Notify SvEvent::CONFIG_UPDATED";
        mStream->notify(SvEvent::CONFIG_UPDATED);
    }

    return SvResult::OK;
}

Return<void> SurroundView3dSession::get3dConfig(get3dConfig_cb _hidl_cb) {
    LOG(DEBUG) << __FUNCTION__;

    _hidl_cb(mConfig);
    return {};
}

bool VerifyOverlayData(const OverlaysData& overlaysData) {
    // Check size of shared memory matches overlaysMemoryDesc.
    const int kVertexSize = 16;
    const int kIdSize = 2;
    int memDescSize = 0;
    for (auto& overlayMemDesc : overlaysData.overlaysMemoryDesc) {
        memDescSize += kIdSize + kVertexSize * overlayMemDesc.verticesCount;
    }
    if (memDescSize != overlaysData.overlaysMemory.size()) {
        LOG(ERROR) << "shared memory and overlaysMemoryDesc size mismatch.";
        return false;
    }

    // Map memory.
    sp<IMemory> pSharedMemory = mapMemory(overlaysData.overlaysMemory);
    if(pSharedMemory == nullptr) {
        LOG(ERROR) << "mapMemory failed.";
        return false;
    }

    // Get Data pointer.
    uint8_t* pData = static_cast<uint8_t*>(
        static_cast<void*>(pSharedMemory->getPointer()));
    if (pData == nullptr) {
        LOG(ERROR) << "Shared memory getPointer() failed.";
        return false;
    }

    int idOffset = 0;
    set<uint16_t> overlayIdSet;
    for (auto& overlayMemDesc : overlaysData.overlaysMemoryDesc) {

        if (overlayIdSet.find(overlayMemDesc.id) != overlayIdSet.end()) {
            LOG(ERROR) << "Duplicate id within memory descriptor.";
            return false;
        }
        overlayIdSet.insert(overlayMemDesc.id);

        if(overlayMemDesc.verticesCount < 3) {
            LOG(ERROR) << "Less than 3 vertices.";
            return false;
        }

        if (overlayMemDesc.overlayPrimitive == OverlayPrimitive::TRIANGLES &&
                overlayMemDesc.verticesCount % 3 != 0) {
            LOG(ERROR) << "Triangles primitive does not have vertices "
                       << "multiple of 3.";
            return false;
        }

        const uint16_t overlayId = *((uint16_t*)(pData + idOffset));

        if (overlayId != overlayMemDesc.id) {
            LOG(ERROR) << "Overlay id mismatch "
                       << overlayId
                       << ", "
                       << overlayMemDesc.id;
            return false;
        }

        idOffset += kIdSize + (kVertexSize * overlayMemDesc.verticesCount);
    }

    return true;
}

// TODO(b/150412555): the overlay related methods are incomplete.
Return<SvResult>  SurroundView3dSession::updateOverlays(
        const OverlaysData& overlaysData) {

    if(!VerifyOverlayData(overlaysData)) {
        LOG(ERROR) << "VerifyOverlayData failed.";
        return SvResult::INVALID_ARG;
    }

    return SvResult::OK;
}

Return<void> SurroundView3dSession::projectCameraPointsTo3dSurface(
    const hidl_vec<Point2dInt>& cameraPoints,
    const hidl_string& cameraId,
    projectCameraPointsTo3dSurface_cb _hidl_cb) {

    vector<Point3dFloat> points3d;
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
        _hidl_cb(points3d);
        return {};
    }

    for (const auto& cameraPoint : cameraPoints) {
        Point3dFloat point3d;
        point3d.isValid = (cameraPoint.x >= 0
                           && cameraPoint.x < mConfig.width
                           && cameraPoint.y >= 0
                           && cameraPoint.y < mConfig.height);
        if (!point3d.isValid) {
            LOG(WARNING) << "Camera point out of bounds.";
        }
        points3d.push_back(point3d);
    }
    _hidl_cb(points3d);
    return {};
}

void SurroundView3dSession::generateFrames() {
    int sequenceId = 0;

    // TODO(b/150412555): do not use the setViews for frames generation
    // since there is a discrepancy between the HIDL APIs and core lib APIs.
    vector<vector<float>> matrix;
    matrix.resize(4);
    for (int i=0; i<4; i++) {
        matrix[i].resize(4);
    }

    while(true) {
        {
            scoped_lock<mutex> lock(mAccessLock);

            if (mStreamState != RUNNING) {
                // Break out of our main thread loop
                LOG(INFO) << "StreamState does not equal to RUNNING. "
                          << "Exiting the loop";
                break;
            }

            if (mOutputWidth != mConfig.width
                || mOutputHeight != mConfig.height) {
                LOG(DEBUG) << "Config changed. Re-allocate memory. "
                           << "Old width: "
                           << mOutputWidth
                           << ", old height: "
                           << mOutputHeight
                           << "; New width: "
                           << mConfig.width
                           << ", new height: "
                           << mConfig.height;
                delete[] static_cast<char*>(mOutputPointer.data_pointer);
                mOutputWidth = mConfig.width;
                mOutputHeight = mConfig.height;
                mOutputPointer.height = mOutputHeight;
                mOutputPointer.width = mOutputWidth;
                mOutputPointer.format = Format::RGBA;
                mOutputPointer.data_pointer =
                    new char[mOutputHeight * mOutputWidth * kNumChannels];

                if (!mOutputPointer.data_pointer) {
                    LOG(ERROR) << "Memory allocation failed. Exiting.";
                    break;
                }

                Size2dInteger size = Size2dInteger(mOutputWidth, mOutputHeight);
                mSurroundView->Update3dOutputResolution(size);

                mSvTexture = new GraphicBuffer(mOutputWidth,
                                               mOutputHeight,
                                               HAL_PIXEL_FORMAT_RGBA_8888,
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

        // TODO(b/150412555): use hard-coded views for now. Change view every 10
        // frames.
        int recViewId = sequenceId / 10 % 16;
        for (int i=0; i<4; i++)
            for (int j=0; j<4; j++) {
                matrix[i][j] = kRecViews[recViewId][i*4+j];
        }

        if (mSurroundView->Get3dSurroundView(
            mInputPointers, matrix, &mOutputPointer)) {
            LOG(INFO) << "Get3dSurroundView succeeded";
        } else {
            LOG(ERROR) << "Get3dSurroundView failed. "
                       << "Using memset to initialize to gray.";
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

        // Note: there is a chance that the stride of the texture is not the
        // same as the width. For example, when the input frame is 1920 * 1080,
        // the width is 1080, but the stride is 2048. So we'd better copy the
        // data line by line, instead of single memcpy.
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
        LOG(INFO) << "memcpy finished!";
        mSvTexture->unlock();

        ANativeWindowBuffer* buffer = mSvTexture->getNativeBuffer();
        LOG(DEBUG) << "ANativeWindowBuffer->handle: " << buffer->handle;

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
        pDesc->format = HAL_PIXEL_FORMAT_RGBA_8888;
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
    }

    // If we've been asked to stop, send an event to signal the actual end of stream
    LOG(DEBUG) << "Notify SvEvent::STREAM_STOPPED";
    mStream->notify(SvEvent::STREAM_STOPPED);
}

bool SurroundView3dSession::initialize() {
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

    mOutputWidth = Get3dParams().resolution.width;
    mOutputHeight = Get3dParams().resolution.height;

    mConfig.width = mOutputWidth;
    mConfig.height = mOutputHeight;
    mConfig.carDetails = SvQuality::HIGH;

    mOutputPointer.height = mOutputHeight;
    mOutputPointer.width = mOutputWidth;
    mOutputPointer.format = Format::RGBA;
    mOutputPointer.data_pointer = new char[
        mOutputHeight * mOutputWidth * kNumChannels];

    if (!mOutputPointer.data_pointer) {
        LOG(ERROR) << "Memory allocation failed. Exiting.";
        return false;
    }

    mSvTexture = new GraphicBuffer(mOutputWidth,
                                   mOutputHeight,
                                   HAL_PIXEL_FORMAT_RGBA_8888,
                                   1,
                                   GRALLOC_USAGE_HW_TEXTURE,
                                   "SvTexture");

    if (mSvTexture->initCheck() == OK) {
        LOG(INFO) << "Successfully allocated Graphic Buffer";
    } else {
        LOG(ERROR) << "Failed to allocate Graphic Buffer";
        return false;
    }

    if (mSurroundView->Start3dPipeline()) {
        LOG(INFO) << "Start3dPipeline succeeded";
    } else {
        LOG(ERROR) << "Start3dPipeline failed";
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

