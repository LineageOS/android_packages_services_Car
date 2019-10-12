/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef ANDROID_AUTOMOTIVE_EVS_V1_1_EVSCAMERAENUMERATOR_H
#define ANDROID_AUTOMOTIVE_EVS_V1_1_EVSCAMERAENUMERATOR_H

#include <list>
#include <unordered_map>

#include "HalCamera.h"
#include "VirtualCamera.h"

#include <android/hardware/automotive/evs/1.1/IEvsEnumerator.h>
#include <android/hardware/automotive/evs/1.0/IEvsDisplay.h>
#include <android/hardware/camera/device/3.2/ICameraDevice.h>

using namespace ::android::hardware::automotive::evs::V1_1;
using ::android::hardware::Return;
using ::android::hardware::hidl_string;
using ::android::hardware::camera::device::V3_2::Stream;

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

using IEvsCamera_1_0     = ::android::hardware::automotive::evs::V1_0::IEvsCamera;
using IEvsCamera_1_1     = ::android::hardware::automotive::evs::V1_1::IEvsCamera;
using IEvsEnumerator_1_0 = ::android::hardware::automotive::evs::V1_0::IEvsEnumerator;
using IEvsEnumerator_1_1 = ::android::hardware::automotive::evs::V1_1::IEvsEnumerator;
using EvsDisplayState    = ::android::hardware::automotive::evs::V1_0::DisplayState;
using ::android::hardware::automotive::evs::V1_0::IEvsDisplay;

class Enumerator : public IEvsEnumerator {
public:
    // Methods from ::android::hardware::automotive::evs::V1_0::IEvsEnumerator follow.
    Return<void>                getCameraList(getCameraList_cb _hidl_cb)  override;
    Return<sp<IEvsCamera_1_0>>  openCamera(const hidl_string& cameraId)  override;
    Return<void>                closeCamera(const ::android::sp<IEvsCamera_1_0>& virtualCamera)  override;
    Return<sp<IEvsDisplay>>     openDisplay()  override;
    Return<void>                closeDisplay(const ::android::sp<IEvsDisplay>& display)  override;
    Return<EvsDisplayState>     getDisplayState()  override;

    // Methods from ::android::hardware::automotive::evs::V1_1::IEvsEnumerator follow.
    Return<void>                getCameraList_1_1(getCameraList_1_1_cb _hidl_cb) override;
    Return<sp<IEvsCamera_1_1>>  openCamera_1_1(const hidl_string& cameraId,
                                               const Stream& streamCfg) override;

    // Implementation details
    bool init(const char* hardwareServiceName);

private:
    bool checkPermission();
    sp<IEvsEnumerator_1_1>      mHwEnumerator;      // Hardware enumerator
    wp<IEvsDisplay>             mActiveDisplay;     // Display proxy object warpping hw display
    std::list<sp<HalCamera>>    mCameras;           // Camera proxy objects wrapping hw cameras
    std::unordered_map<std::string,                 // Active stream configuration of
                       Stream>  mStreamConfigs;     // a camera device.
};

} // namespace implementation
} // namespace V1_1
} // namespace evs
} // namespace automotive
} // namespace android

#endif  // ANDROID_AUTOMOTIVE_EVS_V1_1_EVSCAMERAENUMERATOR_H
