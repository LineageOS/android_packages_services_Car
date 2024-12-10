/*
 * Copyright (c) 2022, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <aidl/android/hardware/automotive/vehicle/BnVehicle.h>
#include <gmock/gmock.h>

namespace android {
namespace automotive {
namespace watchdog {

class MockVehicle final : public aidl::android::hardware::automotive::vehicle::BnVehicle {
public:
    MockVehicle() {
        ON_CALL(*this, unsubscribe(::testing::_, ::testing::_)).WillByDefault([]() {
            return ndk::ScopedAStatus::ok();
        });
    }

    MOCK_METHOD(ndk::ScopedAStatus, getAllPropConfigs,
                (aidl::android::hardware::automotive::vehicle::VehiclePropConfigs*), (override));
    MOCK_METHOD(ndk::ScopedAStatus, getPropConfigs,
                (const std::vector<int32_t>&,
                 aidl::android::hardware::automotive::vehicle::VehiclePropConfigs*),
                (override));
    MOCK_METHOD(
            ndk::ScopedAStatus, getValues,
            (const std::shared_ptr<aidl::android::hardware::automotive::vehicle::IVehicleCallback>&,
             const aidl::android::hardware::automotive::vehicle::GetValueRequests&),
            (override));
    MOCK_METHOD(
            ndk::ScopedAStatus, setValues,
            (const std::shared_ptr<aidl::android::hardware::automotive::vehicle::IVehicleCallback>&,
             const aidl::android::hardware::automotive::vehicle::SetValueRequests&),
            (override));
    MOCK_METHOD(
            ndk::ScopedAStatus, subscribe,
            (const std::shared_ptr<aidl::android::hardware::automotive::vehicle::IVehicleCallback>&,
             const std::vector<aidl::android::hardware::automotive::vehicle::SubscribeOptions>&,
             int32_t),
            (override));
    MOCK_METHOD(
            ndk::ScopedAStatus, unsubscribe,
            (const std::shared_ptr<aidl::android::hardware::automotive::vehicle::IVehicleCallback>&,
             const std::vector<int32_t>&),
            (override));
    MOCK_METHOD(
            ndk::ScopedAStatus, returnSharedMemory,
            (const std::shared_ptr<aidl::android::hardware::automotive::vehicle::IVehicleCallback>&,
             int64_t),
            (override));
    MOCK_METHOD(ndk::ScopedAStatus, getSupportedValuesLists,
                (const std::vector<aidl::android::hardware::automotive::vehicle::PropIdAreaId>&,
                 aidl::android::hardware::automotive::vehicle::SupportedValuesListResults*),
                (override));
    MOCK_METHOD(ndk::ScopedAStatus, getMinMaxSupportedValue,
                (const std::vector<aidl::android::hardware::automotive::vehicle::PropIdAreaId>&,
                 aidl::android::hardware::automotive::vehicle::MinMaxSupportedValueResults*),
                (override));
    MOCK_METHOD(
            ndk::ScopedAStatus, registerSupportedValueChangeCallback,
            (const std::shared_ptr<aidl::android::hardware::automotive::vehicle::IVehicleCallback>&,
             const std::vector<aidl::android::hardware::automotive::vehicle::PropIdAreaId>&),
            (override));
    MOCK_METHOD(
            ndk::ScopedAStatus, unregisterSupportedValueChangeCallback,
            (const std::shared_ptr<aidl::android::hardware::automotive::vehicle::IVehicleCallback>&,
             const std::vector<aidl::android::hardware::automotive::vehicle::PropIdAreaId>&),
            (override));
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
