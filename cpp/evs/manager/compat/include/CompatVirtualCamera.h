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
#include <aidl/android/hardware/automotive/evs/IEvsCameraStream.h>
#include <aidl/android/hardware/automotive/evs/IEvsDisplay.h>
#include <aidl/android/hardware/automotive/evs/ParameterRange.h>

namespace android::hardware::automotive::evs::compat {

namespace aidlevs = ::aidl::android::hardware::automotive::evs;

class CompatVirtualCamera final : public aidlevs::BnEvsCamera {
public:
    CompatVirtualCamera();
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
    ::ndk::ScopedAStatus setMaxFramesInFlight(int32_t bufferCount) override;
    ::ndk::ScopedAStatus startVideoStream(
            const std::shared_ptr<aidlevs::IEvsCameraStream>& receiver) override;
    ::ndk::ScopedAStatus stopVideoStream() override;
    ::ndk::ScopedAStatus unsetPrimaryClient() override;
};

}  // namespace android::hardware::automotive::evs::compat
