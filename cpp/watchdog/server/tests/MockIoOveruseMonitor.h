/**
 * Copyright (c) 2020, The Android Open Source Project
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

#ifndef CPP_WATCHDOG_SERVER_TESTS_MOCKIOOVERUSEMONITOR_H_
#define CPP_WATCHDOG_SERVER_TESTS_MOCKIOOVERUSEMONITOR_H_

#include "IoOveruseMonitor.h"

#include <android-base/result.h>
#include <android/automotive/watchdog/internal/ComponentType.h>
#include <android/automotive/watchdog/internal/IoOveruseConfiguration.h>
#include <gmock/gmock.h>

namespace android {
namespace automotive {
namespace watchdog {

class MockIoOveruseMonitor : public IoOveruseMonitor {
public:
    MockIoOveruseMonitor(
            const android::sp<IWatchdogServiceHelperInterface>& watchdogServiceHelper) :
          IoOveruseMonitor(watchdogServiceHelper) {}
    ~MockIoOveruseMonitor() {}
    MOCK_METHOD(android::base::Result<void>, updateIoOveruseConfiguration,
                (android::automotive::watchdog::internal::ComponentType,
                 const android::automotive::watchdog::internal::IoOveruseConfiguration&),
                (override));
    MOCK_METHOD(android::base::Result<void>, onDump, (int fd), (override));
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_TESTS_MOCKIOOVERUSEMONITOR_H_
