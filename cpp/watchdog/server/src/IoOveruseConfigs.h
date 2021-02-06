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

#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

class IoOveruseConfigs;

/*
 * ComponentSpecificConfig represents the I/O overuse config defined per component.
 */
class ComponentSpecificConfig final {
public:
    ComponentSpecificConfig() {}

    // Used in tests
    ComponentSpecificConfig(
            android::automotive::watchdog::internal::PerStateIoOveruseThreshold genericVal,
            std::unordered_map<std::string,
                               android::automotive::watchdog::internal::PerStateIoOveruseThreshold>
                    perPackageThresholdsVal,
            std::unordered_set<std::string> safeToKillPackagesVal) :
          generic(genericVal),
          perPackageThresholds(perPackageThresholdsVal),
          safeToKillPackages(safeToKillPackagesVal) {}

    ~ComponentSpecificConfig() {
        perPackageThresholds.clear();
        safeToKillPackages.clear();
    }

    /*
     * I/O overuse configurations for all packages under the component that are not covered by
     * |perPackageThresholds| or |IoOveruseConfigs.perCategoryThresholds|.
     */
    android::automotive::watchdog::internal::PerStateIoOveruseThreshold generic;
    /*
     * I/O overuse configurations for specific packages under the component.
     */
    std::unordered_map<std::string,
                       android::automotive::watchdog::internal::PerStateIoOveruseThreshold>
            perPackageThresholds;
    /*
     * List of safe to kill packages under the component in the event of I/O overuse.
     */
    std::unordered_set<std::string> safeToKillPackages;

protected:
    /*
     * Updates |perPackageThresholds|.
     */
    android::base::Result<void> updatePerPackageThresholds(
            const std::vector<android::automotive::watchdog::internal::PerStateIoOveruseThreshold>&
                    thresholds,
            const std::function<void(const std::string&)>& maybeAppendVendorPackagePrefixes);
    /*
     * Updates |safeToKillPackages|.
     */
    android::base::Result<void> updateSafeToKillPackages(
            const std::vector<android::String16>& packages,
            const std::function<void(const std::string&)>& maybeAppendVendorPackagePrefixes);

private:
    friend class IoOveruseConfigs;
};

/*
 * IoOveruseConfigs represents the I/O overuse configuration defined by system and vendor
 * applications.
 */
class IoOveruseConfigs final {
public:
    IoOveruseConfigs() {}
    ~IoOveruseConfigs() {
        perCategoryThresholds.clear();
        vendorPackagePrefixes.clear();
        alertThresholds.clear();
    }

    // Overwrites the existing configuration for the given |componentType|.
    android::base::Result<void> update(
            const android::automotive::watchdog::internal::ComponentType componentType,
            const android::automotive::watchdog::internal::IoOveruseConfiguration& config);

private:
    struct AlertThresholdHashByDuration {
    public:
        size_t operator()(const android::automotive::watchdog::internal::IoOveruseAlertThreshold&
                                  threshold) const;
    };

    struct AlertThresholdEqualByDuration {
    public:
        bool operator()(
                const android::automotive::watchdog::internal::IoOveruseAlertThreshold& l,
                const android::automotive::watchdog::internal::IoOveruseAlertThreshold& r) const;
    };

public:
    // System component specific configuration.
    ComponentSpecificConfig systemConfig;
    // Vendor component specific configuration.
    ComponentSpecificConfig vendorConfig;
    // Third-party component specific configuration.
    ComponentSpecificConfig thirdPartyConfig;
    // I/O overuse thresholds per category.
    std::unordered_map<android::automotive::watchdog::internal::ApplicationCategoryType,
                       android::automotive::watchdog::internal::PerStateIoOveruseThreshold>
            perCategoryThresholds;
    // List of vendor package prefixes.
    std::unordered_set<std::string> vendorPackagePrefixes;
    // System-wide disk I/O overuse alert thresholds.
    std::unordered_set<android::automotive::watchdog::internal::IoOveruseAlertThreshold,
                       AlertThresholdHashByDuration, AlertThresholdEqualByDuration>
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
