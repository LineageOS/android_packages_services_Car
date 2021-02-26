/*
 * Copyright (c) 2021, The Android Open Source Project
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

#ifndef CPP_WATCHDOG_SERVER_TESTS_MOCKIOOVERUSECONFIGS_H_
#define CPP_WATCHDOG_SERVER_TESTS_MOCKIOOVERUSECONFIGS_H_

#include "IoOveruseConfigs.h"

#include <android-base/result.h>
#include <android/automotive/watchdog/internal/ComponentType.h>
#include <android/automotive/watchdog/internal/IoOveruseConfiguration.h>
#include <android/automotive/watchdog/internal/PerStateBytes.h>
#include <gmock/gmock.h>

namespace android {
namespace automotive {
namespace watchdog {

class MockIoOveruseConfigs : public IIoOveruseConfigs {
public:
    MockIoOveruseConfigs() {}
    ~MockIoOveruseConfigs() {}
    MOCK_METHOD(android::base::Result<void>, update,
                (const android::automotive::watchdog::internal::ComponentType,
                 const android::automotive::watchdog::internal::IoOveruseConfiguration&),
                (override));

    MOCK_METHOD((const std::unordered_set<std::string>&), vendorPackagePrefixes, (), (override));

    MOCK_METHOD(android::automotive::watchdog::internal::PerStateBytes, fetchThreshold,
                (const android::automotive::watchdog::internal::PackageInfo&), (const, override));

    MOCK_METHOD(bool, isSafeToKill, (const android::automotive::watchdog::internal::PackageInfo&),
                (const, override));

    MOCK_METHOD((const IoOveruseAlertThresholdSet&), systemWideAlertThresholds, (), (override));

    void injectThresholds(const std::unordered_map<
                          std::string, android::automotive::watchdog::internal::PerStateBytes>&
                                  perPackageThreshold) {
        ON_CALL(*this, fetchThreshold(::testing::_))
                .WillByDefault([perPackageThreshold = perPackageThreshold](
                                       const android::automotive::watchdog::internal::PackageInfo&
                                               packageInfo) {
                    const std::string packageName =
                            std::string(String8(packageInfo.packageIdentifier.name));
                    if (const auto it = perPackageThreshold.find(packageName);
                        it != perPackageThreshold.end()) {
                        return it->second;
                    }
                    return defaultThreshold().perStateWriteBytes;
                });
        ON_CALL(*this, isSafeToKill(::testing::_)).WillByDefault(::testing::Return(true));
    }
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_TESTS_MOCKIOOVERUSECONFIGS_H_
