/**
 * Copyright (c) 2025, The Android Open Source Project
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

#include "WatchdogServiceHelperBase.h"

#include <aidl/android/automotive/watchdog/internal/ICarWatchdogServiceForSystem.h>
#include <android-base/result.h>
#include <binder/Status.h>
#include <gmock/gmock.h>
#include <utils/StrongPointer.h>

namespace android {
namespace automotive {
namespace watchdog {

class MockWatchdogServiceHelperBase : public WatchdogServiceHelperBaseInterface {
public:
    MockWatchdogServiceHelperBase() {
        ON_CALL(*this, isServiceConnected()).WillByDefault(::testing::Return(false));
    }
    ~MockWatchdogServiceHelperBase() {}

    MOCK_METHOD(bool, isServiceConnected, (), (override));
    MOCK_METHOD(
            ndk::ScopedAStatus, registerService,
            (const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&),
            (override));
    MOCK_METHOD(
            ndk::ScopedAStatus, unregisterService,
            (const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&),
            (override));
    MOCK_METHOD(void, handleBinderDeath, (void*), (override));
    MOCK_METHOD(ndk::ScopedAStatus, getPackageInfosForUids,
                (const std::vector<int32_t>&, const std::vector<std::string>&,
                 std::vector<aidl::android::automotive::watchdog::internal::PackageInfo>*),
                (const, override));
    MOCK_METHOD(ndk::ScopedAStatus, resetResourceOveruseStats, (const std::vector<std::string>&),
                (const, override));
    MOCK_METHOD(ndk::ScopedAStatus, onLatestResourceStats,
                (const std::vector<aidl::android::automotive::watchdog::internal::ResourceStats>&),
                (const, override));
    MOCK_METHOD(ndk::ScopedAStatus, requestTodayIoUsageStats, (), (const, override));
    MOCK_METHOD(void, terminate, (), (override));
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
