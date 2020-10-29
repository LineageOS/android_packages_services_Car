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
#include <android/automotive/watchdog/ApplicationCategoryType.h>
#include <android/automotive/watchdog/ComponentType.h>
#include <android/automotive/watchdog/IoOveruseAlertThreshold.h>
#include <android/automotive/watchdog/IoOveruseConfiguration.h>
#include <android/automotive/watchdog/PerStateIoOveruseThreshold.h>

#include <regex>  // NOLINT
#include <string>
#include <unordered_map>
#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

struct ComponentSpecificConfig {
    PerStateIoOveruseThreshold generic;
    std::unordered_map<std::string, PerStateIoOveruseThreshold> perPackageThresholds;
    std::unordered_set<std::string> safeToKillPackages;

    android::base::Result<void> updatePerPackageThresholds(
            const std::vector<PerStateIoOveruseThreshold>& thresholds);
};

struct IoOveruseConfigs {
    android::base::Result<void> update(ComponentType type, const IoOveruseConfiguration& config);

private:
    struct IoOveruseAlertThresholdHash {
    public:
        size_t operator()(const IoOveruseAlertThreshold& threshold) const;
    };

    struct IoOveruseAlertThresholdEqual {
    public:
        bool operator()(const IoOveruseAlertThreshold& l, const IoOveruseAlertThreshold& r) const;
    };

public:
    ComponentSpecificConfig systemConfig;
    ComponentSpecificConfig vendorConfig;
    ComponentSpecificConfig thirdPartyConfig;
    std::unordered_map<ApplicationCategoryType, PerStateIoOveruseThreshold> perCategoryThresholds;
    std::unordered_set<std::string> vendorPackagePrefixes;
    std::unordered_set<IoOveruseAlertThreshold, IoOveruseAlertThresholdHash,
                       IoOveruseAlertThresholdEqual>
            alertThresholds;

private:
    android::base::Result<void> updatePerCategoryThresholds(
            const std::vector<PerStateIoOveruseThreshold>& thresholds);
    android::base::Result<void> updateAlertThresholds(
            const std::vector<IoOveruseAlertThreshold>& thresholds);
};

std::string toString(const PerStateIoOveruseThreshold& thresholds);

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_IOOVERUSECONFIGS_H_
