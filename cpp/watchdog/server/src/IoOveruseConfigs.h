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
#include <android/automotive/watchdog/PerStateBytes.h>
#include <android/automotive/watchdog/internal/ApplicationCategoryType.h>
#include <android/automotive/watchdog/internal/ComponentType.h>
#include <android/automotive/watchdog/internal/IoOveruseAlertThreshold.h>
#include <android/automotive/watchdog/internal/IoOveruseConfiguration.h>
#include <android/automotive/watchdog/internal/PackageInfo.h>
#include <android/automotive/watchdog/internal/PerStateIoOveruseThreshold.h>
#include <utils/String16.h>

#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

inline const android::automotive::watchdog::internal::PerStateIoOveruseThreshold
defaultThreshold() {
    android::automotive::watchdog::internal::PerStateIoOveruseThreshold threshold;
    threshold.name = android::String16("default");
    threshold.perStateWriteBytes.foregroundBytes = std::numeric_limits<uint64_t>::max();
    threshold.perStateWriteBytes.backgroundBytes = std::numeric_limits<uint64_t>::max();
    threshold.perStateWriteBytes.garageModeBytes = std::numeric_limits<uint64_t>::max();
    return threshold;
}

/*
 * Defines the methods that the I/O overuse configs module should implement.
 */
class IIoOveruseConfigs : public android::RefBase {
public:
    // Overwrites the existing configuration for the given |componentType|.
    virtual android::base::Result<void> update(
            const android::automotive::watchdog::internal::ComponentType componentType,
            const android::automotive::watchdog::internal::IoOveruseConfiguration& config) = 0;

    /*
     * Returns the list of vendor package prefixes. Any pre-installed package matching one of these
     * prefixes should be classified as a vendor package.
     */
    virtual const std::unordered_set<std::string>& vendorPackagePrefixes() = 0;

    // Fetches the I/O overuse thresholds for the given package.
    virtual PerStateBytes fetchThreshold(
            const android::automotive::watchdog::internal::PackageInfo& packageInfo) const = 0;

    // Returns whether or not the package is safe to kill on I/O overuse.
    virtual bool isSafeToKill(
            const android::automotive::watchdog::internal::PackageInfo& packageInfo) const = 0;

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

    using IoOveruseAlertThresholdSet =
            ::std::unordered_set<android::automotive::watchdog::internal::IoOveruseAlertThreshold,
                                 AlertThresholdHashByDuration, AlertThresholdEqualByDuration>;

    // Returns system-wide disk I/O overuse thresholds.
    virtual const IoOveruseAlertThresholdSet& systemWideAlertThresholds() = 0;
};

class IoOveruseConfigs;

/*
 * ComponentSpecificConfig represents the I/O overuse config defined per component.
 */
class ComponentSpecificConfig final {
protected:
    ComponentSpecificConfig() : mGeneric(defaultThreshold()) {}

    ~ComponentSpecificConfig() {
        mPerPackageThresholds.clear();
        mSafeToKillPackages.clear();
    }

    /*
     * Updates |mPerPackageThresholds|.
     */
    android::base::Result<void> updatePerPackageThresholds(
            const std::vector<android::automotive::watchdog::internal::PerStateIoOveruseThreshold>&
                    thresholds,
            const std::function<void(const std::string&)>& maybeAppendVendorPackagePrefixes);
    /*
     * Updates |mSafeToKillPackages|.
     */
    android::base::Result<void> updateSafeToKillPackages(
            const std::vector<android::String16>& packages,
            const std::function<void(const std::string&)>& maybeAppendVendorPackagePrefixes);

    /*
     * I/O overuse configurations for all packages under the component that are not covered by
     * |mPerPackageThresholds| or |IoOveruseConfigs.mPerCategoryThresholds|.
     */
    android::automotive::watchdog::internal::PerStateIoOveruseThreshold mGeneric;
    /*
     * I/O overuse configurations for specific packages under the component.
     */
    std::unordered_map<std::string,
                       android::automotive::watchdog::internal::PerStateIoOveruseThreshold>
            mPerPackageThresholds;
    /*
     * List of safe to kill packages under the component in the event of I/O overuse.
     */
    std::unordered_set<std::string> mSafeToKillPackages;

private:
    friend class IoOveruseConfigs;
};

/*
 * IoOveruseConfigs represents the I/O overuse configuration defined by system and vendor
 * applications. This class is not thread safe for performance purposes. The caller is responsible
 * for calling the methods in a thread safe manner.
 */
class IoOveruseConfigs final : public IIoOveruseConfigs {
public:
    IoOveruseConfigs() {}
    ~IoOveruseConfigs() {
        mPerCategoryThresholds.clear();
        mVendorPackagePrefixes.clear();
        mAlertThresholds.clear();
    }

    // Overwrites the existing configuration for the given |componentType|.
    android::base::Result<void> update(
            const android::automotive::watchdog::internal::ComponentType componentType,
            const android::automotive::watchdog::internal::IoOveruseConfiguration& config);

    PerStateBytes fetchThreshold(
            const android::automotive::watchdog::internal::PackageInfo& packageInfo) const;

    bool isSafeToKill(
            const android::automotive::watchdog::internal::PackageInfo& packageInfo) const;

    const IoOveruseAlertThresholdSet& systemWideAlertThresholds() { return mAlertThresholds; }

    const std::unordered_set<std::string>& vendorPackagePrefixes() {
        return mVendorPackagePrefixes;
    }

private:
    // System component specific configuration.
    ComponentSpecificConfig mSystemConfig;
    // Vendor component specific configuration.
    ComponentSpecificConfig mVendorConfig;
    // Third-party component specific configuration.
    ComponentSpecificConfig mThirdPartyConfig;
    // I/O overuse thresholds per category.
    std::unordered_map<android::automotive::watchdog::internal::ApplicationCategoryType,
                       android::automotive::watchdog::internal::PerStateIoOveruseThreshold>
            mPerCategoryThresholds;
    // List of vendor package prefixes.
    std::unordered_set<std::string> mVendorPackagePrefixes;
    // System-wide disk I/O overuse alert thresholds.
    IoOveruseAlertThresholdSet mAlertThresholds;

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
