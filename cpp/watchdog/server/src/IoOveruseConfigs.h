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

#ifndef CPP_WATCHDOG_SERVER_SRC_IOOVERUSECONFIGS_H_
#define CPP_WATCHDOG_SERVER_SRC_IOOVERUSECONFIGS_H_

#include <android-base/result.h>
#include <android-base/stringprintf.h>
#include <android/automotive/watchdog/internal/ApplicationCategoryType.h>
#include <android/automotive/watchdog/internal/ComponentType.h>
#include <android/automotive/watchdog/internal/IoOveruseAlertThreshold.h>
#include <android/automotive/watchdog/internal/IoOveruseConfiguration.h>
#include <android/automotive/watchdog/internal/PerStateIoOveruseThreshold.h>

#include <regex>  // NOLINT
#include <string>
#include <unordered_map>
#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

struct ComponentSpecificConfig {
    android::automotive::watchdog::internal::PerStateIoOveruseThreshold generic;
    std::unordered_map<std::string,
                       android::automotive::watchdog::internal::PerStateIoOveruseThreshold>
            perPackageThresholds;
    std::unordered_set<std::string> safeToKillPackages;

    android::base::Result<void> updatePerPackageThresholds(
            const std::vector<android::automotive::watchdog::internal::PerStateIoOveruseThreshold>&
                    thresholds);
};

struct IoOveruseConfigs {
    android::base::Result<void> update(
            android::automotive::watchdog::internal::ComponentType type,
            const android::automotive::watchdog::internal::IoOveruseConfiguration& config);

private:
    struct IoOveruseAlertThresholdHash {
    public:
        size_t operator()(const android::automotive::watchdog::internal::IoOveruseAlertThreshold&
                                  threshold) const;
    };

    struct IoOveruseAlertThresholdEqual {
    public:
        bool operator()(
                const android::automotive::watchdog::internal::IoOveruseAlertThreshold& l,
                const android::automotive::watchdog::internal::IoOveruseAlertThreshold& r) const;
    };

public:
    ComponentSpecificConfig systemConfig;
    ComponentSpecificConfig vendorConfig;
    ComponentSpecificConfig thirdPartyConfig;
    std::unordered_map<android::automotive::watchdog::internal::ApplicationCategoryType,
                       android::automotive::watchdog::internal::PerStateIoOveruseThreshold>
            perCategoryThresholds;
    std::unordered_set<std::string> vendorPackagePrefixes;
    std::unordered_set<android::automotive::watchdog::internal::IoOveruseAlertThreshold,
                       IoOveruseAlertThresholdHash, IoOveruseAlertThresholdEqual>
            alertThresholds;

private:
    android::base::Result<void> updatePerCategoryThresholds(
            const std::vector<android::automotive::watchdog::internal::PerStateIoOveruseThreshold>&
                    thresholds);
    android::base::Result<void> updateAlertThresholds(
            const std::vector<android::automotive::watchdog::internal::IoOveruseAlertThreshold>&
                    thresholds);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_IOOVERUSECONFIGS_H_
