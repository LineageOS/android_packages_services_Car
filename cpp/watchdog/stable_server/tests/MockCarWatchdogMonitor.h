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

#include <aidl/android/automotive/watchdog/internal/ClientsNotRespondingInfo.h>
#include <aidl/android/automotive/watchdog/internal/ICarWatchdogMonitor.h>
#include <aidl/android/automotive/watchdog/internal/ProcessIdentifier.h>
#include <android/binder_interface_utils.h>
#include <gmock/gmock.h>

#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::internal::ClientsNotRespondingInfo;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor;
using ::aidl::android::automotive::watchdog::internal::ProcessIdentifier;

class MockCarWatchdogMonitor : public ICarWatchdogMonitor {
public:
    MockCarWatchdogMonitor() {}

    MOCK_METHOD(ndk::ScopedAStatus, onClientsNotResponding, (const std::vector<ProcessIdentifier>&),
                (override));
    MOCK_METHOD(ndk::ScopedAStatus, onClientsNotRespondingWithSystemState,
                (const ClientsNotRespondingInfo&), (override));
    MOCK_METHOD(ndk::SpAIBinder, asBinder, (), (override));
    MOCK_METHOD(bool, isRemote, (), (override));
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
