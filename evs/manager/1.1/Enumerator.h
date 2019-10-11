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

#include "HalCamera.h"
#include "VirtualCamera.h"

#include <android/hardware/automotive/evs/1.0/IEvsEnumerator.h>
#include <android/hardware/automotive/evs/1.0/IEvsDisplay.h>

using namespace ::android::hardware::automotive::evs::V1_1;
using ::android::hardware::Return;
using ::android::hardware::hidl_string;
using ::android::hardware::automotive::evs::V1_0::IEvsEnumerator;

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

using IEvsCamera_1_0     = ::android::hardware::automotive::evs::V1_0::IEvsCamera;
using IEvsCamera_1_1     = ::android::hardware::automotive::evs::V1_1::IEvsCamera;
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

    // Implementation details
    bool init(const char* hardwareServiceName);

private:
    bool checkPermission();
    sp<IEvsEnumerator>          mHwEnumerator;      // Hardware enumerator
    wp<IEvsDisplay>             mActiveDisplay;     // Display proxy object warpping hw display
    std::list<sp<HalCamera>>    mCameras;           // Camera proxy objects wrapping hw cameras
};

} // namespace implementation
} // namespace V1_1
} // namespace evs
} // namespace automotive
} // namespace android

#endif  // ANDROID_AUTOMOTIVE_EVS_V1_1_EVSCAMERAENUMERATOR_H
