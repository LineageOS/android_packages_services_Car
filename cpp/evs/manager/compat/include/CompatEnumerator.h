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

#include <aidl/android/hardware/automotive/evs/BnEvsEnumerator.h>
#include <aidl/android/hardware/automotive/evs/CameraDesc.h>
#include <aidl/android/hardware/automotive/evs/DisplayState.h>
#include <aidl/android/hardware/automotive/evs/IEvsCamera.h>
#include <aidl/android/hardware/automotive/evs/IEvsDisplay.h>
#include <aidl/android/hardware/automotive/evs/IEvsEnumeratorStatusCallback.h>
#include <aidl/android/hardware/automotive/evs/IEvsUltrasonicsArray.h>
#include <aidl/android/hardware/automotive/evs/Stream.h>
#include <aidl/android/hardware/automotive/evs/UltrasonicsArrayDesc.h>

namespace android::hardware::automotive::evs::compat {

namespace aidlevs = ::aidl::android::hardware::automotive::evs;

class CompatEnumerator final : public aidlevs::BnEvsEnumerator {
public:
    CompatEnumerator();
    ~CompatEnumerator() override;

    ::ndk::ScopedAStatus closeCamera(
            const std::shared_ptr<aidlevs::IEvsCamera>& carCamera) override;
    ::ndk::ScopedAStatus closeDisplay(
            const std::shared_ptr<aidlevs::IEvsDisplay>& display) override;
    ::ndk::ScopedAStatus closeUltrasonicsArray(
            const std::shared_ptr<aidlevs::IEvsUltrasonicsArray>& evsUltrasonicsArray) override;
    ::ndk::ScopedAStatus getCameraList(std::vector<aidlevs::CameraDesc>* _aidl_return) override;
    ::ndk::ScopedAStatus getDisplayIdList(std::vector<uint8_t>* _aidl_return) override;
    ::ndk::ScopedAStatus getDisplayState(aidlevs::DisplayState* _aidl_return) override;
    ::ndk::ScopedAStatus getStreamList(const aidlevs::CameraDesc& description,
                                     std::vector<aidlevs::Stream>* _aidl_return) override;
    ::ndk::ScopedAStatus getUltrasonicsArrayList(
            std::vector<aidlevs::UltrasonicsArrayDesc>* _aidl_return) override;
    ::ndk::ScopedAStatus isHardware(bool* _aidl_return) override;
    ::ndk::ScopedAStatus openCamera(const std::string& cameraId, const aidlevs::Stream& streamCfg,
                                  std::shared_ptr<aidlevs::IEvsCamera>* _aidl_return) override;
    ::ndk::ScopedAStatus openDisplay(int32_t id,
                                   std::shared_ptr<aidlevs::IEvsDisplay>* _aidl_return) override;
    ::ndk::ScopedAStatus openUltrasonicsArray(
            const std::string& ultrasonicsArrayId,
            std::shared_ptr<aidlevs::IEvsUltrasonicsArray>* _aidl_return) override;
    ::ndk::ScopedAStatus registerStatusCallback(
            const std::shared_ptr<aidlevs::IEvsEnumeratorStatusCallback>& callback) override;
    ::ndk::ScopedAStatus getDisplayStateById(int32_t id,
                                           aidlevs::DisplayState* _aidl_return) override;
};

}  // namespace android::hardware::automotive::evs::compat
