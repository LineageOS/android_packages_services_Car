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

#include <aidl/android/hardware/automotive/evs/BnEvsCamera.h>
#include <aidl/android/hardware/automotive/evs/BufferDesc.h>
#include <aidl/android/hardware/automotive/evs/CameraDesc.h>
#include <aidl/android/hardware/automotive/evs/CameraParam.h>
#include <aidl/android/hardware/automotive/evs/EvsResult.h>
#include <aidl/android/hardware/automotive/evs/IEvsCameraStream.h>
#include <aidl/android/hardware/automotive/evs/IEvsDisplay.h>
#include <aidl/android/hardware/automotive/evs/ParameterRange.h>
#include <utils/Mutex.h>

#include <deque>
#include <set>
#include <thread>
#include <vector>

namespace android::hardware::automotive::evs::compat {

namespace aidlevs = ::aidl::android::hardware::automotive::evs;

class CompatHalCamera;  // Forward declaration to avoid circular dependency.

class CompatVirtualCamera : public aidlevs::BnEvsCamera {
#ifdef EVS_COMPAT_TEST
    // Grant access to private members for testing.
    friend class CompatVirtualCameraTest_deliverFrame_StreamStopped_Test;
    friend class CompatVirtualCameraTest_deliverFrame_FrameQuotaExceeded_Test;
    friend class CompatVirtualCameraTest_deliverFrame_FrameQuotaExceededClientStreamNotSet_Test;
    friend class CompatVirtualCameraTest_deliverFrame_Success_Test;
    friend class CompatVirtualCameraTest_doneWithFrame_BufferNotFound_Test;
    friend class CompatVirtualCameraTest_doneWithFrame_Success_Test;
    friend class CompatVirtualCameraTest_startVideoStream_StreamAlreadyRunning_Test;
    friend class CompatVirtualCameraTest_startVideoStream_Success_Test;
#endif

public:
    explicit CompatVirtualCamera(const std::vector<std::shared_ptr<CompatHalCamera>>& halCameras);
    ~CompatVirtualCamera() override;

    ::ndk::ScopedAStatus doneWithFrame(const std::vector<aidlevs::BufferDesc>& buffer) override;
    ::ndk::ScopedAStatus forcePrimaryClient(
            const std::shared_ptr<aidlevs::IEvsDisplay>& display) override;
    ::ndk::ScopedAStatus getCameraInfo(aidlevs::CameraDesc* _aidl_return) override;
    ::ndk::ScopedAStatus getExtendedInfo(int32_t opaqueIdentifier,
                                         std::vector<uint8_t>* _aidl_return) override;
    ::ndk::ScopedAStatus getIntParameter(aidlevs::CameraParam id,
                                         std::vector<int32_t>* _aidl_return) override;
    ::ndk::ScopedAStatus getIntParameterRange(aidlevs::CameraParam id,
                                              aidlevs::ParameterRange* _aidl_return) override;
    ::ndk::ScopedAStatus getParameterList(std::vector<aidlevs::CameraParam>* _aidl_return) override;
    ::ndk::ScopedAStatus getPhysicalCameraInfo(const std::string& deviceId,
                                               aidlevs::CameraDesc* _aidl_return) override;
    ::ndk::ScopedAStatus importExternalBuffers(const std::vector<aidlevs::BufferDesc>& buffers,
                                               int32_t* _aidl_return) override;
    ::ndk::ScopedAStatus pauseVideoStream() override;
    ::ndk::ScopedAStatus resumeVideoStream() override;
    ::ndk::ScopedAStatus setExtendedInfo(int32_t opaqueIdentifier,
                                         const std::vector<uint8_t>& opaqueValue) override;
    ::ndk::ScopedAStatus setIntParameter(aidlevs::CameraParam id, int32_t value,
                                         std::vector<int32_t>* _aidl_return) override;
    ::ndk::ScopedAStatus setPrimaryClient() override;
    /**
     * This function should be called prior to calling startVideoStream. Any subsequent calls after
     * startVideoStream will be ignored. When virtual cameras share a physical camera, ensure all
     * virtual cameras have their frames set before calling startVideoStream on any of them.
     *
     * @param bufferCount The maximum number of images the user will want to access simultaneously.
     * @return A status object indicating the result of the operation.
     */
    ::ndk::ScopedAStatus setMaxFramesInFlight(int32_t bufferCount) override;
    ::ndk::ScopedAStatus startVideoStream(
            const std::shared_ptr<aidlevs::IEvsCameraStream>& receiver) override;
    ::ndk::ScopedAStatus stopVideoStream() override;
    ::ndk::ScopedAStatus unsetPrimaryClient() override;

    virtual bool deliverFrame(const aidlevs::BufferDesc& bufferDesc);

    virtual bool isStreaming() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mStreamState == RUNNING;
    }

    unsigned int getMaxFramesInFlight() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mMaxFramesInFlight;
    }

    void setDescriptor(aidlevs::CameraDesc* desc) { mDesc = desc; }

private:
    void shutdown();

    std::unordered_map<std::string, std::weak_ptr<CompatHalCamera>> mHalCameras;
    unsigned int mMaxFramesInFlight GUARDED_BY(mMutex) = 1;
    enum {
        STOPPED,
        RUNNING,
        STOPPING,
    } mStreamState GUARDED_BY(mMutex) = STOPPED;
    mutable std::mutex mMutex;

    std::shared_ptr<aidlevs::IEvsCameraStream> mStream GUARDED_BY(mMutex);
    std::unordered_map<std::string, std::deque<aidlevs::BufferDesc>> mFramesHeld GUARDED_BY(mMutex);
    std::unordered_map<std::string, std::deque<aidlevs::BufferDesc>> mFramesUsed GUARDED_BY(mMutex);
    std::set<std::string> mSourceCameras GUARDED_BY(mMutex);
    std::condition_variable mFramesReadySignal;
    std::condition_variable mReturnFramesSignal;
    std::thread mCaptureThread;
    std::thread mReturnThread;

    aidlevs::CameraDesc* mDesc = nullptr;
};

}  // namespace android::hardware::automotive::evs::compat
