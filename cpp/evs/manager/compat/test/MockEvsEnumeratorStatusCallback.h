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

#ifndef PACKAGES_SERVICES_CAR_CPP_EVS_MANAGER_COMPAT_TEST_MOCKEVSENUMERATORSTATUSCALLBACK_H_
#define PACKAGES_SERVICES_CAR_CPP_EVS_MANAGER_COMPAT_TEST_MOCKEVSENUMERATORSTATUSCALLBACK_H_

#include <aidl/android/hardware/automotive/evs/DeviceStatus.h>
#include <aidl/android/hardware/automotive/evs/IEvsEnumeratorStatusCallback.h>
#include <gmock/gmock.h>

namespace android::hardware::automotive::evs::compat {

namespace aidlevs = ::aidl::android::hardware::automotive::evs;

class MockIEvsEnumeratorStatusCallback : public aidlevs::IEvsEnumeratorStatusCallback {
public:
    MOCK_METHOD(ndk::ScopedAStatus, deviceStatusChanged,
                (const std::vector<aidlevs::DeviceStatus>& status), (override));
    MOCK_METHOD(ndk::ScopedAStatus, getInterfaceVersion, (int32_t* _aidl_return), (override));
    MOCK_METHOD(ndk::ScopedAStatus, getInterfaceHash, (std::string * _aidl_return), (override));
    MOCK_METHOD(ndk::SpAIBinder, asBinder, (), (override));
    MOCK_METHOD(bool, isRemote, (), (override));
};

}  // namespace android::hardware::automotive::evs::compat

#endif  // PACKAGES_SERVICES_CAR_CPP_EVS_MANAGER_COMPAT_TEST_MOCKEVSENUMERATORSTATUSCALLBACK_H_
