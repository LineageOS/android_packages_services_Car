/*
 * Copyright (C) 2025 The Android Open Source Project
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

#pragma once

#include "CompatVirtualCamera.h"

#include <aidl/android/hardware/automotive/evs/BnEvsCameraStream.h>
#include <aidl/android/hardware/automotive/evs/BufferDesc.h>
#include <aidl/android/hardware/automotive/evs/CameraDesc.h>
#include <aidl/android/hardware/automotive/evs/EvsEventDesc.h>
#include <aidl/android/hardware/automotive/evs/Stream.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraError.h>
#include <camera/NdkCaptureRequest.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <condition_variable>
#include <deque>
#include <list>
#include <unordered_map>
#include <vector>

namespace android::hardware::automotive::evs::compat {

namespace aidlevs = ::aidl::android::hardware::automotive::evs;

class CompatHalCamera final : public aidlevs::BnEvsCameraStream {
#ifdef EVS_COMPAT_TEST
    // Grant access to private members for testing.
    friend class CompatHalCameraTest_ownVirtualCamera_ValidCamera_Test;
    friend class CompatHalCameraTest_disownVirtualCamera_ValidCamera_Test;
    friend class CompatHalCameraTest_disownVirtualCamera_NotOwnedCamera_Test;
    friend class CompatHalCameraTest_clientStreamStarting_Success_Test;
    friend class CompatHalCameraTest_clientStreamStarting_AlreadyRunning_Test;
    friend class CompatHalCameraTest_clientStreamStarting_StartStreamFail_Test;
    friend class CompatHalCameraTest_doneWithFrame_InvalidBufferId_Test;
    friend class CompatHalCameraTest_doneWithFrame_ValidBufferId_Test;
    friend class CompatHalCameraTest_deliverFrame_NonEmptyBuffer_Test;
    friend class CompatHalCameraTest_clientStreamEnding_NotRunning_Test;
    friend class CompatHalCameraTest_clientStreamEnding_OneClientStops_Test;
    friend class CompatHalCameraTest_clientStreamEnding_ClientStopsWithOthersRunning_Test;
    friend class CompatHalCameraTest_MetadataHandling_Test;
#endif

public:
    CompatHalCamera(ACameraDevice* device, const std::string& cameraId,
                    const aidlevs::CameraDesc* desc, const aidlevs::Stream& streamConfig);
    ~CompatHalCamera() override;

    ::ndk::ScopedAStatus deliverFrame(const std::vector<aidlevs::BufferDesc>& buffer) override;
    ::ndk::ScopedAStatus notify(const aidlevs::EvsEventDesc& event) override;

    ::ndk::ScopedAStatus doneWithFrame(aidlevs::BufferDesc buffer);
    inline aidlevs::Stream getStreamConfig() const { return mStreamConfig; }
    std::string getId() const { return mCameraId; }
    aidlevs::CameraDesc getCameraDesc() const { return mCameraDesc; }
    bool ownVirtualCamera(const std::shared_ptr<CompatVirtualCamera>& virtualCamera);
    void disownVirtualCamera(const CompatVirtualCamera* virtualCamera);
    ::ndk::ScopedAStatus clientStreamStarting();
    void clientStreamEnding(const CompatVirtualCamera* virtualCamera);
    bool tryIsStopped(bool& result) const;
    unsigned getOwnedVirtualCameraCount() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mVirtualCameras.size();
    };
    void requestNewFrame(std::shared_ptr<CompatVirtualCamera> virtualCamera, int64_t timestamp);
    // Closes the underlying ACameraDevice if open and marks it as closed.
    // Returns true if the device was open and closed, false otherwise.
    bool releaseACameraDevice();
    ACameraMetadata* getLatestMetadata() const;

private:
    ::ndk::ScopedAStatus startNdkCameraStream(int32_t maxImages);
    // Cleans up NDK resources related to the camera stream (Session, ImageReader, etc.).
    // This method does NOT close the underlying ACameraDevice.
    void cleanUpNdkStreamResources();
    static void onImageAvailable(void* context, AImageReader* reader);
    static void onSessionClosed(void* context, ACameraCaptureSession* session);
    void handleCaptureCompleted(const ACameraMetadata* result);
    static void onCaptureCompleted(void* context, ACameraCaptureSession* session,
                                   ACaptureRequest* request, const ACameraMetadata* result);

    ACameraDevice* mDevice GUARDED_BY(mMutex);
    std::string mCameraId;
    aidlevs::CameraDesc mCameraDesc;
    aidlevs::Stream mStreamConfig;
    mutable std::mutex mMutex;
    std::list<std::weak_ptr<CompatVirtualCamera>> mVirtualCameras GUARDED_BY(mMutex);

    enum {
        STOPPED,
        RUNNING,
        STOPPING,
    } mStreamState GUARDED_BY(mMutex) = STOPPED;

    AImageReader* mImageReader = nullptr;
    AImageReader_ImageListener mImageListener;
    ANativeWindow* mWindow = nullptr;
    ACameraOutputTarget* mOutputTarget = nullptr;
    ACaptureSessionOutput* mSessionOutput = nullptr;
    ACaptureSessionOutputContainer* mOutputs = nullptr;
    ACameraCaptureSession* mSession = nullptr;
    ACaptureRequest* mCaptureRequest = nullptr;
    ACameraDevice_StateCallbacks mDeviceStateCallbacks;
    ACameraCaptureSession_stateCallbacks mSessionStateCallbacks;
    ACameraCaptureSession_captureCallbacks mCaptureCallbacks;

    std::unordered_map<uint64_t, uint32_t> mBufferIdMap GUARDED_BY(mMutex);

    mutable std::mutex mSessionMutex;
    bool mSessionClosed GUARDED_BY(mSessionMutex) = false;
    std::condition_variable mSessionCondVar;

    struct FrameRecord {
        uint32_t frameId;
        uint32_t refCount;
        FrameRecord() : frameId(0), refCount(0) {}  // needed for resizing mFrameRecords.
        FrameRecord(uint32_t id, uint32_t count) : frameId(id), refCount(count) {}
    };
    std::vector<FrameRecord> mFrameRecords GUARDED_BY(mMutex);

    struct FrameRequest {
        std::weak_ptr<CompatVirtualCamera> client;
        int64_t timestamp = -1;
    };
    std::deque<FrameRequest> mNextRequests GUARDED_BY(mMutex);

    std::unordered_map<uint32_t, AImage*> mLiveImages GUARDED_BY(mMutex);

    bool mFrameOpInProgress GUARDED_BY(mMutex) = false;
    std::condition_variable mFrameOpDone;

    mutable std::mutex mMetadataLock;
    ACameraMetadata* mLatestMetadata GUARDED_BY(mMetadataLock) = nullptr;
};

}  // namespace android::hardware::automotive::evs::compat
