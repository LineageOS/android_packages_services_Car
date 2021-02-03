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

#define LOG_TAG "carwatchdogd"

#include "IoOveruseConfigs.h"

#include "PackageInfoResolver.h"

#include <android-base/strings.h>

#include <inttypes.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::automotive::watchdog::internal::ApplicationCategoryType;
using ::android::automotive::watchdog::internal::ComponentType;
using ::android::automotive::watchdog::internal::IoOveruseAlertThreshold;
using ::android::automotive::watchdog::internal::IoOveruseConfiguration;
using ::android::automotive::watchdog::internal::PerStateIoOveruseThreshold;
using ::android::base::Error;
using ::android::base::Result;
using ::android::base::StartsWith;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::android::binder::Status;

namespace {

// Enum to filter the updatable I/O overuse configs by each component.
enum IoOveruseConfigEnum {
    COMPONENT_SPECIFIC_GENERIC_THRESHOLDS = 1 << 0,
    COMPONENT_SPECIFIC_PER_PACKAGE_THRESHOLDS = 1 << 1,
    COMPONENT_SPECIFIC_SAFE_TO_KILL_PACKAGES = 1 << 2,
    PER_CATEGORY_THRESHOLDS = 1 << 3,
    VENDOR_PACKAGES_REGEX = 1 << 4,
    SYSTEM_WIDE_ALERT_THRESHOLDS = 1 << 5,
};

const int32_t kSystemComponentUpdatableConfigs = COMPONENT_SPECIFIC_GENERIC_THRESHOLDS |
        COMPONENT_SPECIFIC_PER_PACKAGE_THRESHOLDS | COMPONENT_SPECIFIC_SAFE_TO_KILL_PACKAGES |
        SYSTEM_WIDE_ALERT_THRESHOLDS;
const int32_t kVendorComponentUpdatableConfigs = COMPONENT_SPECIFIC_GENERIC_THRESHOLDS |
        COMPONENT_SPECIFIC_PER_PACKAGE_THRESHOLDS | COMPONENT_SPECIFIC_SAFE_TO_KILL_PACKAGES |
        PER_CATEGORY_THRESHOLDS | VENDOR_PACKAGES_REGEX;
const int32_t kThirdPartyComponentUpdatableConfigs = COMPONENT_SPECIFIC_GENERIC_THRESHOLDS;

bool isZeroValueThresholds(const PerStateIoOveruseThreshold& thresholds) {
    return thresholds.perStateWriteBytes.applicationForegroundBytes == 0 &&
            thresholds.perStateWriteBytes.applicationBackgroundBytes == 0 &&
            thresholds.perStateWriteBytes.systemGarageModeBytes == 0;
}

std::string toString(const PerStateIoOveruseThreshold& thresholds) {
    return StringPrintf("name=%s, foregroundBytes=%" PRId64 ", backgroundBytes=%" PRId64
                        ", garageModeBytes=%" PRId64,
                        String8(thresholds.name).c_str(),
                        thresholds.perStateWriteBytes.applicationForegroundBytes,
                        thresholds.perStateWriteBytes.applicationBackgroundBytes,
                        thresholds.perStateWriteBytes.systemGarageModeBytes);
}

Result<void> containsValidThresholds(const PerStateIoOveruseThreshold& thresholds) {
    if (thresholds.name.size() == 0) {
        return Error() << "Doesn't contain threshold name";
    }

    if (isZeroValueThresholds(thresholds)) {
        return Error() << "Zero value thresholds for " << thresholds.name;
    }

    if (thresholds.perStateWriteBytes.applicationForegroundBytes == 0 ||
        thresholds.perStateWriteBytes.applicationBackgroundBytes == 0 ||
        thresholds.perStateWriteBytes.systemGarageModeBytes == 0) {
        return Error() << "Some thresholds are zero: " << toString(thresholds);
    }
    return {};
}

Result<void> containsValidThreshold(const IoOveruseAlertThreshold& threshold) {
    if (threshold.aggregateDurationSecs == 0) {
        return Error() << "Aggregate duration must be greater than zero";
    }
    if (threshold.writtenBytes == 0) {
        return Error() << "Written bytes must be greater than zero";
    }
    return {};
}

ApplicationCategoryType toApplicationCategoryType(const std::string& value) {
    if (value == "MAPS") {
        return ApplicationCategoryType::MAPS;
    }
    if (value == "MEDIA") {
        return ApplicationCategoryType::MEDIA;
    }
    return ApplicationCategoryType::OTHERS;
}

std::string uniqueStr(const IoOveruseAlertThreshold& threshold) {
    return StringPrintf("ad_%" PRId64 "s_td_%" PRId64 "s_wr_%" PRId64 "b",
                        threshold.aggregateDurationSecs, threshold.triggerDurationSecs,
                        threshold.writtenBytes);
}

Result<void> filterThresholdsByPackageName(const std::unordered_set<std::string>& prefixes,
                                           std::vector<PerStateIoOveruseThreshold>* thresholds) {
    std::string errorMsgs;
    for (auto it = thresholds->begin(); it != thresholds->end();) {
        std::string packageName(String8(it->name));
        bool isVendor = false;
        for (const auto& prefix : prefixes) {
            if (StartsWith(packageName, prefix)) {
                ++it;
                isVendor = true;
                break;
            }
        }
        if (!isVendor) {
            StringAppendF(&errorMsgs, "\t\t%s\n", packageName.c_str());
            it = thresholds->erase(it);
        }
    }
    if (!errorMsgs.empty()) {
        return Error() << "Thresholds that don't match packages prefixes:\n" << errorMsgs;
    }
    return {};
}

Result<void> filterPackageNames(const std::unordered_set<std::string>& prefixes,
                                std::vector<std::string>* packageNames) {
    std::string errorMsgs;
    for (auto it = packageNames->begin(); it != packageNames->end();) {
        bool isVendor = false;
        for (const auto& prefix : prefixes) {
            if (StartsWith(*it, prefix)) {
                ++it;
                isVendor = true;
                break;
            }
        }
        if (!isVendor) {
            StringAppendF(&errorMsgs, "\t\t%s\n", it->c_str());
            it = packageNames->erase(it);
        }
    }
    if (!errorMsgs.empty()) {
        return Error() << "Packages that don't match packages regex:\n" << errorMsgs;
    }
    return {};
}

}  // namespace

Result<void> ComponentSpecificConfig::updatePerPackageThresholds(
        const std::vector<PerStateIoOveruseThreshold>& thresholds) {
    std::string errorMsgs;
    for (const auto& packageThreshold : thresholds) {
        auto result = containsValidThresholds(packageThreshold);
        if (!result.ok()) {
            StringAppendF(&errorMsgs, "\tInvalid package specific thresholds: %s\n",
                          result.error().message().c_str());
            continue;
        }
        perPackageThresholds[std::string(String8(packageThreshold.name))] = packageThreshold;
    }
    return errorMsgs.empty() ? Result<void>{} : Error() << errorMsgs;
}

size_t IoOveruseConfigs::IoOveruseAlertThresholdHash::operator()(
        const IoOveruseAlertThreshold& threshold) const {
    return std::hash<std::string>{}(uniqueStr(threshold));
}

bool IoOveruseConfigs::IoOveruseAlertThresholdEqual::operator()(
        const IoOveruseAlertThreshold& l, const IoOveruseAlertThreshold& r) const {
    return l.aggregateDurationSecs == r.aggregateDurationSecs &&
            l.triggerDurationSecs == r.triggerDurationSecs && l.writtenBytes == r.writtenBytes;
}

Result<void> IoOveruseConfigs::updatePerCategoryThresholds(
        const std::vector<PerStateIoOveruseThreshold>& thresholds) {
    std::string errorMsgs;
    for (const auto& categoryThreshold : thresholds) {
        auto result = containsValidThresholds(categoryThreshold);
        if (!result.ok()) {
            StringAppendF(&errorMsgs, "\tInvalid category specific thresholds: %s\n",
                          result.error().message().c_str());
            continue;
        }
        std::string name = std::string(String8(categoryThreshold.name));
        ApplicationCategoryType category = toApplicationCategoryType(name);
        if (category == ApplicationCategoryType::OTHERS) {
            StringAppendF(&errorMsgs, "\tInvalid application category %s\n", name.c_str());
            continue;
        }
        perCategoryThresholds[category] = categoryThreshold;
    }
    return errorMsgs.empty() ? Result<void>{} : Error() << errorMsgs;
}

Result<void> IoOveruseConfigs::updateAlertThresholds(
        const std::vector<IoOveruseAlertThreshold>& thresholds) {
    std::string errorMsgs;
    for (const auto& alertThreshold : thresholds) {
        auto result = containsValidThreshold(alertThreshold);
        if (!result.ok()) {
            StringAppendF(&errorMsgs, "\tInvalid system-wide alert threshold: %s\n",
                          result.error().message().c_str());
            continue;
        }
        alertThresholds.emplace(alertThreshold);
    }
    return errorMsgs.empty() ? Result<void>{} : Error() << errorMsgs;
}

Result<void> IoOveruseConfigs::update(ComponentType type,
                                      const IoOveruseConfiguration& updateConfig) {
    // TODO(b/177616658): Update the implementation to overwrite the existing configs rather than
    //  append to them.
    if (String8(updateConfig.componentLevelThresholds.name).string() != toString(type)) {
        return Error(Status::EX_ILLEGAL_ARGUMENT)
                << "Invalid config. Config's component name "
                << updateConfig.componentLevelThresholds.name << " != " << toString(type);
    }
    ComponentSpecificConfig* targetComponentConfig;
    int32_t updatableConfigsFilter = 0;
    switch (type) {
        case ComponentType::SYSTEM:
            targetComponentConfig = &systemConfig;
            updatableConfigsFilter = kSystemComponentUpdatableConfigs;
            break;
        case ComponentType::VENDOR:
            targetComponentConfig = &vendorConfig;
            updatableConfigsFilter = kVendorComponentUpdatableConfigs;
            break;
        case ComponentType::THIRD_PARTY:
            targetComponentConfig = &thirdPartyConfig;
            updatableConfigsFilter = kThirdPartyComponentUpdatableConfigs;
            break;
        default:
            return Error(Status::EX_ILLEGAL_ARGUMENT)
                    << "Invalid component type " << static_cast<int32_t>(type);
    }

    std::string nonUpdatableConfigMsgs;
    std::string errorMsgs;

    if ((updatableConfigsFilter & IoOveruseConfigEnum::COMPONENT_SPECIFIC_GENERIC_THRESHOLDS) &&
        !isZeroValueThresholds(updateConfig.componentLevelThresholds)) {
        auto result = containsValidThresholds(updateConfig.componentLevelThresholds);
        if (!result.ok()) {
            StringAppendF(&errorMsgs, "\tInvalid '%s' component level thresholds: %s\n",
                          toString(type).c_str(), result.error().message().c_str());
        } else {
            targetComponentConfig->generic = updateConfig.componentLevelThresholds;
        }
    }

    if (updatableConfigsFilter & IoOveruseConfigEnum::VENDOR_PACKAGES_REGEX) {
        for (const auto& prefix : updateConfig.vendorPackagePrefixes) {
            vendorPackagePrefixes.insert(std::string(String8(prefix)));
        }
        if (!updateConfig.vendorPackagePrefixes.empty()) {
            PackageInfoResolver::getInstance()->setVendorPackagePrefixes(vendorPackagePrefixes);
        }
    } else if (!updateConfig.vendorPackagePrefixes.empty()) {
        StringAppendF(&nonUpdatableConfigMsgs, "%svendor packages prefixes",
                      !nonUpdatableConfigMsgs.empty() ? ", " : "");
    }

    std::vector<PerStateIoOveruseThreshold> packageSpecificThresholds =
            updateConfig.packageSpecificThresholds;
    std::vector<std::string> safeToKillPackages;
    for (const auto& package : updateConfig.safeToKillPackages) {
        safeToKillPackages.emplace_back(std::string(String8(package)));
    }
    if (type == ComponentType::VENDOR) {
        auto result =
                filterThresholdsByPackageName(vendorPackagePrefixes, &packageSpecificThresholds);
        if (!result.ok()) {
            StringAppendF(&errorMsgs, "\tVendor per-package threshold filtering error: %s",
                          result.error().message().c_str());
        }
        result = filterPackageNames(vendorPackagePrefixes, &safeToKillPackages);
        if (!result.ok()) {
            StringAppendF(&errorMsgs, "\tVendor safe-to-kill package filtering error: %s",
                          result.error().message().c_str());
        }
    }

    if (updatableConfigsFilter & IoOveruseConfigEnum::COMPONENT_SPECIFIC_PER_PACKAGE_THRESHOLDS) {
        auto result = targetComponentConfig->updatePerPackageThresholds(packageSpecificThresholds);
        if (!result.ok()) {
            StringAppendF(&errorMsgs, "%s", result.error().message().c_str());
        }
    } else if (!updateConfig.packageSpecificThresholds.empty()) {
        StringAppendF(&nonUpdatableConfigMsgs, "%sper-package thresholds",
                      !nonUpdatableConfigMsgs.empty() ? ", " : "");
    }

    if (updatableConfigsFilter & IoOveruseConfigEnum::COMPONENT_SPECIFIC_SAFE_TO_KILL_PACKAGES) {
        targetComponentConfig->safeToKillPackages.insert(safeToKillPackages.begin(),
                                                         safeToKillPackages.end());
    } else if (!updateConfig.safeToKillPackages.empty()) {
        StringAppendF(&nonUpdatableConfigMsgs, "%ssafe-to-kill list",
                      !nonUpdatableConfigMsgs.empty() ? ", " : "");
    }

    if (updatableConfigsFilter & IoOveruseConfigEnum::PER_CATEGORY_THRESHOLDS) {
        auto result = updatePerCategoryThresholds(updateConfig.categorySpecificThresholds);
        if (!result.ok()) {
            StringAppendF(&errorMsgs, "%s", result.error().message().c_str());
        }
    } else if (!updateConfig.categorySpecificThresholds.empty()) {
        StringAppendF(&nonUpdatableConfigMsgs, "%scategory specific thresholds",
                      !nonUpdatableConfigMsgs.empty() ? ", " : "");
    }

    if (updatableConfigsFilter & IoOveruseConfigEnum::SYSTEM_WIDE_ALERT_THRESHOLDS) {
        auto result = updateAlertThresholds(updateConfig.systemWideThresholds);
        if (!result.ok()) {
            StringAppendF(&errorMsgs, "%s", result.error().message().c_str());
        }
    } else if (!updateConfig.systemWideThresholds.empty()) {
        StringAppendF(&nonUpdatableConfigMsgs, "%ssystem-wide alert thresholds",
                      !nonUpdatableConfigMsgs.empty() ? ", " : "");
    }

    if (!nonUpdatableConfigMsgs.empty()) {
        StringAppendF(&errorMsgs, "\tReceived values for non-updatable configs: %s\n",
                      nonUpdatableConfigMsgs.c_str());
    }
    if (!errorMsgs.empty()) {
        ALOGE("Invalid I/O overuse configs received for %s component:\n%s", toString(type).c_str(),
              errorMsgs.c_str());
    }
    return {};
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
