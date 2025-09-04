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

#include "IoOveruseMonitorWrapper.h"
#include "MockDataProcessor.h"

#include <android-base/result.h>
#include <gmock/gmock.h>

namespace android {
namespace automotive {
namespace watchdog {

class MockIoOveruseMonitorWrapper :
      public MockDataProcessor,
      public IoOveruseMonitorWrapperInterface {
public:
    MockIoOveruseMonitorWrapper() {
        ON_CALL(*this, name()).WillByDefault(::testing::Return("MockIoOveruseMonitorWrapper"));
    }
    ~MockIoOveruseMonitorWrapper() {}
    MOCK_METHOD(std::string, name, (), (const, override));
    MOCK_METHOD(android::base::Result<void>, init, (), (override));
    MOCK_METHOD(bool, isInitialized, (), (const, override));
    MOCK_METHOD(bool, dumpHelpText, (int), (const, override));
    MOCK_METHOD(void, onCarWatchdogServiceRegistered, (), (override));
    MOCK_METHOD(
            android::base::Result<void>, updateResourceOveruseConfigurations,
            (const std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>&),
            (override));
    MOCK_METHOD(
            android::base::Result<void>, getResourceOveruseConfigurations,
            (std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>*),
            (const, override));
    MOCK_METHOD(android::base::Result<void>, onTodayIoUsageStatsFetched,
                (const std::vector<
                        aidl::android::automotive::watchdog::internal::UserPackageIoUsageStats>&),
                (override));
    MOCK_METHOD(
            android::base::Result<void>, addIoOveruseListener,
            (const std::shared_ptr<aidl::android::automotive::watchdog::IResourceOveruseListener>&),
            (override));
    MOCK_METHOD(
            android::base::Result<void>, removeIoOveruseListener,
            (const std::shared_ptr<aidl::android::automotive::watchdog::IResourceOveruseListener>&),
            (override));
    MOCK_METHOD(void, handleBinderDeath, (void*), (override));
    MOCK_METHOD(android::base::Result<void>, getIoOveruseStats,
                (aidl::android::automotive::watchdog::IoOveruseStats*), (const, override));
    MOCK_METHOD(android::base::Result<void>, resetIoOveruseStats, (const std::vector<std::string>&),
                (override));
    MOCK_METHOD(android::base::Result<void>, onPeriodicCollection,
                (time_point_millis, bool, const android::wp<UidStatsCollectorBaseInterface>&,
                 aidl::android::automotive::watchdog::internal::ResourceStats*),
                (override));
    MOCK_METHOD(android::base::Result<void>, onPeriodicCollection,
                (time_point_millis, SystemState, const wp<UidStatsCollectorInterface>&,
                 const wp<ProcStatCollectorInterface>&,
                 aidl::android::automotive::watchdog::internal::ResourceStats*),
                (override));
    MOCK_METHOD(android::base::Result<void>, onPeriodicMonitor,
                (time_t, const android::wp<ProcDiskStatsCollectorInterface>&,
                 const std::function<void()>&),
                (override));
    MOCK_METHOD(void, removeStatsForUser, (userid_t), (override));
    MOCK_METHOD(void, terminate, (), (override));
    MOCK_METHOD(android::base::Result<void>, onDump, (int), (const, override));
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
