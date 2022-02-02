/*
 * Copyright (C) 2022 The Android Open Source Project
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
#ifndef CPP_EVS_MANAGER_1_1_ENUMERATORPROXY_H_
#define CPP_EVS_MANAGER_1_1_ENUMERATORPROXY_H_

#include "IEnumeratorManager.h"

#include <android/hardware/automotive/evs/1.1/IEvsEnumerator.h>
#include <android/hardware/automotive/evs/1.1/IEvsUltrasonicsArray.h>
#include <android/hardware/camera/device/3.2/ICameraDevice.h>

#include <memory>

namespace android::automotive::evs::V1_1::implementation {

// Flyweight proxy that is platform aware (i.e. handles Android symbols) and
// converts them to host/target (i.e. x86 non Android) friendly symbols.
class EnumeratorProxy : public hardware::automotive::evs::V1_1::IEvsEnumerator {
public:
    explicit EnumeratorProxy(std::unique_ptr<IEnumeratorManager> enumeratorManager) :
          mEnumeratorManager(std::move(enumeratorManager)) {}

    // Methods from hardware::automotive::evs::V1_0::IEvsEnumerator follow.
    hardware::Return<void> getCameraList(getCameraList_cb getCameraCallback) override;
    hardware::Return<sp<hardware::automotive::evs::V1_0::IEvsCamera>> openCamera(
            const hardware::hidl_string& cameraId) override;
    hardware::Return<void> closeCamera(
            const ::android::sp<hardware::automotive::evs::V1_0::IEvsCamera>& virtualCamera)
            override;
    hardware::Return<sp<hardware::automotive::evs::V1_0::IEvsDisplay>> openDisplay() override;
    hardware::Return<void> closeDisplay(
            const ::android::sp<hardware::automotive::evs::V1_0::IEvsDisplay>& display) override;
    hardware::Return<hardware::automotive::evs::V1_0::DisplayState> getDisplayState() override;

    // Methods from hardware::automotive::evs::V1_1::IEvsEnumerator follow.
    hardware::Return<void> getCameraList_1_1(getCameraList_1_1_cb _hidl_cb) override;
    hardware::Return<sp<hardware::automotive::evs::V1_1::IEvsCamera>> openCamera_1_1(
            const hardware::hidl_string& cameraId,
            const hardware::camera::device::V3_2::Stream& streamCfg) override;
    hardware::Return<bool> isHardware() override;
    hardware::Return<void> getDisplayIdList(getDisplayIdList_cb _list_cb) override;
    hardware::Return<sp<hardware::automotive::evs::V1_1::IEvsDisplay>> openDisplay_1_1(
            uint8_t id) override;
    hardware::Return<void> getUltrasonicsArrayList(getUltrasonicsArrayList_cb _hidl_cb) override;
    hardware::Return<sp<hardware::automotive::evs::V1_1::IEvsUltrasonicsArray>>
    openUltrasonicsArray(const hardware::hidl_string& ultrasonicsArrayId) override;
    hardware::Return<void> closeUltrasonicsArray(
            const ::android::sp<hardware::automotive::evs::V1_1::IEvsUltrasonicsArray>&
                    evsUltrasonicsArray) override;

    // Methods from ::android.hidl.base::V1_0::IBase follow.
    hardware::Return<void> debug(const hardware::hidl_handle& fd,
                                 const hardware::hidl_vec<hardware::hidl_string>& options) override;

private:
    const std::unique_ptr<IEnumeratorManager> mEnumeratorManager;
};

}  // namespace android::automotive::evs::V1_1::implementation

#endif  // CPP_EVS_MANAGER_1_1_ENUMERATORPROXY_H_
