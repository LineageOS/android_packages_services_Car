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

#define LOG_TAG "carwatchdogd"

#include "OveruseConfigurationXmlHelper.h"

#include <android-base/parseint.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android/automotive/watchdog/PerStateBytes.h>
#include <android/automotive/watchdog/internal/ApplicationCategoryType.h>
#include <android/automotive/watchdog/internal/ComponentType.h>
#include <android/automotive/watchdog/internal/IoOveruseAlertThreshold.h>
#include <android/automotive/watchdog/internal/IoOveruseConfiguration.h>
#include <android/automotive/watchdog/internal/PackageMetadata.h>
#include <android/automotive/watchdog/internal/PerStateIoOveruseThreshold.h>
#include <android/automotive/watchdog/internal/ResourceSpecificConfiguration.h>

#include <tinyxml2.h>

#include <unordered_set>
#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::automotive::watchdog::PerStateBytes;
using ::android::automotive::watchdog::internal::ApplicationCategoryType;
using ::android::automotive::watchdog::internal::ComponentType;
using ::android::automotive::watchdog::internal::IoOveruseAlertThreshold;
using ::android::automotive::watchdog::internal::IoOveruseConfiguration;
using ::android::automotive::watchdog::internal::PackageMetadata;
using ::android::automotive::watchdog::internal::PerStateIoOveruseThreshold;
using ::android::automotive::watchdog::internal::ResourceOveruseConfiguration;
using ::android::automotive::watchdog::internal::ResourceSpecificConfiguration;
using ::android::base::EqualsIgnoreCase;
using ::android::base::Error;
using ::android::base::Join;
using ::android::base::ParseInt;
using ::android::base::Result;
using ::android::base::StartsWith;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::android::base::Trim;
using ::android::binder::Status;
using ::tinyxml2::XML_SUCCESS;
using ::tinyxml2::XMLDocument;
using ::tinyxml2::XMLElement;

namespace {
constexpr const char kTagResourceOveruseConfiguration[] = "resourceOveruseConfiguration";
constexpr const char kTagComponentType[] = "componentType";

constexpr const char kTagSafeToKillPackages[] = "safeToKillPackages";
constexpr const char kTagPackage[] = "package";

constexpr const char kTagVendorPackagePrefixes[] = "vendorPackagePrefixes";
constexpr const char kTagPackagePrefix[] = "packagePrefix";

constexpr const char kTagPackageToAppCategoryTypes[] = "packagesToAppCategoryTypes";
constexpr const char kTagPackageAppCategory[] = "packageAppCategory";

constexpr const char kTagIoOveruseConfiguration[] = "ioOveruseConfiguration";
constexpr const char kTagComponentLevelThresholds[] = "componentLevelThresholds";
constexpr const char kTagPackageSpecificThresholds[] = "packageSpecificThresholds";
constexpr const char kTagState[] = "state";
constexpr const char kStateIdForegroundMode[] = "foreground_mode";
constexpr const char kStateIdBackgroundMode[] = "background_mode";
constexpr const char kStateIdGarageMode[] = "garage_mode";
constexpr int kNumStates = 3;

constexpr const char kTagAppCategorySpecificThresholds[] = "appCategorySpecificThresholds";
constexpr const char kTagAppCategoryThreshold[] = "appCategoryThreshold";

constexpr const char kTagSystemWideThresholds[] = "systemWideThresholds";
constexpr const char kTagParam[] = "param";
constexpr const char kParamIdDurationSeconds[] = "duration_seconds";
constexpr const char kParamIdWrittenBytesPerSecond[] = "written_bytes_per_second";
constexpr int kNumParams = 2;

constexpr const char kAttrId[] = "id";
constexpr const char kAttrType[] = "type";

Result<const XMLElement*> readExactlyOneElement(const char* tag, const XMLElement* rootElement) {
    const XMLElement* element = rootElement->FirstChildElement(tag);
    if (element == nullptr) {
        return Error() << "Must specify value for the tag '" << tag << "'";
    }
    if (element->NextSiblingElement(tag) != nullptr) {
        return Error() << "Must specify only one entry for the tag '" << tag << "'";
    }
    return element;
}

Result<ComponentType> readComponentType(const XMLElement* rootElement) {
    const XMLElement* componentTypeElement;
    if (const auto result = readExactlyOneElement(kTagComponentType, rootElement); result.ok()) {
        componentTypeElement = *result;
    } else {
        return Error() << "Failed to read tag '" << kTagComponentType << "': " << result.error();
    }
    std::string componentTypeStr;
    if (const auto text = componentTypeElement->GetText(); text == nullptr) {
        return Error() << "Must specify non-empty component type";
    } else if (componentTypeStr = Trim(text); componentTypeStr.empty()) {
        return Error() << "Must specify non-empty component type";
    }
    static const std::string* const kSystemComponent =
            new std::string(toString(ComponentType::SYSTEM));
    static const std::string* const kVendorComponent =
            new std::string(toString(ComponentType::VENDOR));
    static const std::string* const kThirdPartyComponent =
            new std::string(toString(ComponentType::THIRD_PARTY));
    if (EqualsIgnoreCase(componentTypeStr, *kSystemComponent)) {
        return ComponentType::SYSTEM;
    } else if (EqualsIgnoreCase(componentTypeStr, *kVendorComponent)) {
        return ComponentType::VENDOR;
    } else if (EqualsIgnoreCase(componentTypeStr, *kThirdPartyComponent)) {
        return ComponentType::THIRD_PARTY;
    }
    return Error() << "Must specify valid component type. Received " << componentTypeStr;
}

Result<std::vector<std::string>> readSafeToKillPackages(const XMLElement* rootElement) {
    std::vector<std::string> safeToKillPackages;
    for (const XMLElement* outerElement = rootElement->FirstChildElement(kTagSafeToKillPackages);
         outerElement != nullptr;
         outerElement = outerElement->NextSiblingElement(kTagSafeToKillPackages)) {
        for (const XMLElement* innerElement = outerElement->FirstChildElement(kTagPackage);
             innerElement != nullptr;
             innerElement = innerElement->NextSiblingElement(kTagPackage)) {
            std::string packageName;
            if (const auto text = innerElement->GetText(); text == nullptr) {
                return Error() << "Must specify non-empty safe-to-kill package name";
            } else if (packageName = Trim(text); packageName.empty()) {
                return Error() << "Must specify non-empty safe-to-kill package name";
            }
            safeToKillPackages.push_back(std::string(packageName));
        }
    }
    return safeToKillPackages;
}

Result<std::vector<std::string>> readVendorPackagePrefixes(const XMLElement* rootElement) {
    std::vector<std::string> vendorPackagePrefixes;
    for (const XMLElement* outerElement = rootElement->FirstChildElement(kTagVendorPackagePrefixes);
         outerElement != nullptr;
         outerElement = outerElement->NextSiblingElement(kTagVendorPackagePrefixes)) {
        for (const XMLElement* innerElement = outerElement->FirstChildElement(kTagPackagePrefix);
             innerElement != nullptr;
             innerElement = innerElement->NextSiblingElement(kTagPackagePrefix)) {
            std::string packagePrefix;
            if (const auto text = innerElement->GetText(); text == nullptr) {
                return Error() << "Must specify non-empty vendor package prefix";
            } else if (packagePrefix = Trim(text); packagePrefix.empty()) {
                return Error() << "Must specify non-empty vendor package prefix";
            }
            vendorPackagePrefixes.push_back(std::string(packagePrefix));
        }
    }
    return vendorPackagePrefixes;
}

ApplicationCategoryType toApplicationCategoryType(std::string_view value) {
    static const std::string* const kMapsAppCategory =
            new std::string(toString(ApplicationCategoryType::MAPS));
    static const std::string* const kMediaAppCategory =
            new std::string(toString(ApplicationCategoryType::MEDIA));
    if (EqualsIgnoreCase(value, *kMapsAppCategory)) {
        return ApplicationCategoryType::MAPS;
    } else if (EqualsIgnoreCase(value, *kMediaAppCategory)) {
        return ApplicationCategoryType::MEDIA;
    }
    return ApplicationCategoryType::OTHERS;
}

Result<std::vector<PackageMetadata>> readPackageToAppCategoryTypes(const XMLElement* rootElement) {
    std::vector<PackageMetadata> packageMetadata;
    for (const XMLElement* outerElement =
                 rootElement->FirstChildElement(kTagPackageToAppCategoryTypes);
         outerElement != nullptr;
         outerElement = outerElement->NextSiblingElement(kTagPackageToAppCategoryTypes)) {
        for (const XMLElement* innerElement =
                     outerElement->FirstChildElement(kTagPackageAppCategory);
             innerElement != nullptr;
             innerElement = innerElement->NextSiblingElement(kTagPackageAppCategory)) {
            const char* type = nullptr;
            if (innerElement->QueryStringAttribute(kAttrType, &type) != XML_SUCCESS) {
                return Error() << "Failed to read '" << kAttrType << "' attribute in '"
                               << kTagPackageAppCategory << "' tag";
            }
            PackageMetadata meta;
            if (meta.appCategoryType = toApplicationCategoryType(type);
                meta.appCategoryType == ApplicationCategoryType::OTHERS) {
                return Error() << "Must specify valid app category type. Received " << type;
            }
            if (const auto text = innerElement->GetText(); text == nullptr) {
                return Error() << "Must specify non-empty package name";
            } else if (meta.packageName = Trim(text); meta.packageName.empty()) {
                return Error() << "Must specify non-empty package name";
            }
            packageMetadata.push_back(meta);
        }
    }
    return packageMetadata;
}

Result<PerStateBytes> readPerStateBytes(const XMLElement* rootElement) {
    PerStateBytes perStateBytes;
    std::unordered_set<std::string> seenStates;
    for (const XMLElement* childElement = rootElement->FirstChildElement(kTagState);
         childElement != nullptr; childElement = childElement->NextSiblingElement(kTagState)) {
        const char* state = nullptr;
        if (childElement->QueryStringAttribute(kAttrId, &state) != XML_SUCCESS) {
            return Error() << "Failed to read '" << kAttrId << "' attribute in '" << kTagState
                           << "' tag";
        }
        if (seenStates.find(state) != seenStates.end()) {
            return Error() << "Duplicate threshold specified for state '" << state << "'";
        }
        int64_t bytes = 0;
        if (const auto text = childElement->GetText(); text == nullptr) {
            return Error() << "Must specify non-empty threshold for state '" << state << "'";
        } else if (const auto bytesStr = Trim(text); !ParseInt(bytesStr.c_str(), &bytes)) {
            return Error() << "Failed to parse threshold for the state '" << state
                           << "': Received threshold value '" << bytesStr << "'";
        }
        if (!strcmp(state, kStateIdForegroundMode)) {
            seenStates.insert(kStateIdForegroundMode);
            perStateBytes.foregroundBytes = bytes;
        } else if (!strcmp(state, kStateIdBackgroundMode)) {
            seenStates.insert(kStateIdBackgroundMode);
            perStateBytes.backgroundBytes = bytes;
        } else if (!strcmp(state, kStateIdGarageMode)) {
            seenStates.insert(kStateIdGarageMode);
            perStateBytes.garageModeBytes = bytes;
        } else {
            return Error() << "Invalid state '" << state << "' in per-state bytes";
        }
    }
    if (seenStates.size() != kNumStates) {
        return Error() << "Thresholds not specified for all states. Specified only for ["
                       << Join(seenStates, ", ") << "] states";
    }
    return perStateBytes;
}

Result<PerStateIoOveruseThreshold> readComponentLevelThresholds(ComponentType componentType,
                                                                const XMLElement* rootElement) {
    const XMLElement* componentLevelThresholdElement = nullptr;
    if (const auto result = readExactlyOneElement(kTagComponentLevelThresholds, rootElement);
        result.ok()) {
        componentLevelThresholdElement = *result;
    } else {
        return Error() << "Failed to read tag '" << kTagComponentLevelThresholds
                       << "': " << result.error();
    }
    PerStateIoOveruseThreshold thresholds;
    thresholds.name = toString(componentType);
    if (const auto result = readPerStateBytes(componentLevelThresholdElement); result.ok()) {
        thresholds.perStateWriteBytes = *result;
    } else {
        return Error() << "Failed to read component level thresholds for component '"
                       << thresholds.name << "': " << result.error();
    }
    return thresholds;
}

Result<std::vector<PerStateIoOveruseThreshold>> readPackageSpecificThresholds(
        const XMLElement* rootElement) {
    std::vector<PerStateIoOveruseThreshold> thresholds;
    for (const XMLElement* childElement =
                 rootElement->FirstChildElement(kTagPackageSpecificThresholds);
         childElement != nullptr;
         childElement = childElement->NextSiblingElement(kTagPackageSpecificThresholds)) {
        PerStateIoOveruseThreshold threshold;
        if (const char* name = nullptr;
            childElement->QueryStringAttribute(kAttrId, &name) != XML_SUCCESS) {
            return Error() << "Failed to read '" << kAttrId << "' attribute in '"
                           << kTagPackageSpecificThresholds << "' tag";
        } else if (threshold.name = name; threshold.name.empty()) {
            return Error() << "Must provide non-empty package name in '" << kAttrId
                           << "attribute in '" << kTagPackageSpecificThresholds << "' tag";
        }
        if (const auto result = readPerStateBytes(childElement); result.ok()) {
            threshold.perStateWriteBytes = *result;
        } else {
            return Error() << "Failed to read package specific thresholds for package '"
                           << threshold.name << "': " << result.error();
        }
        thresholds.push_back(threshold);
    }
    return thresholds;
}

Result<std::vector<PerStateIoOveruseThreshold>> readAppCategorySpecificThresholds(
        const XMLElement* rootElement) {
    std::vector<PerStateIoOveruseThreshold> thresholds;
    for (const XMLElement* outerElement =
                 rootElement->FirstChildElement(kTagAppCategorySpecificThresholds);
         outerElement != nullptr;
         outerElement = outerElement->NextSiblingElement(kTagAppCategorySpecificThresholds)) {
        for (const XMLElement* innerElement =
                     outerElement->FirstChildElement(kTagAppCategoryThreshold);
             innerElement != nullptr;
             innerElement = innerElement->NextSiblingElement(kTagAppCategoryThreshold)) {
            const char* name = nullptr;
            if (innerElement->QueryStringAttribute(kAttrId, &name) != XML_SUCCESS) {
                return Error() << "Failed to read '" << kAttrId << "' attribute in '"
                               << kTagAppCategoryThreshold << "' tag";
            }
            PerStateIoOveruseThreshold threshold;
            threshold.name = name;
            if (const auto result = readPerStateBytes(innerElement); result.ok()) {
                threshold.perStateWriteBytes = *result;
            } else {
                return Error() << "Failed to read app category specific thresholds for application "
                               << "category '" << threshold.name << "': " << result.error();
            }
            thresholds.push_back(threshold);
        }
    }
    return thresholds;
}

Result<std::vector<IoOveruseAlertThreshold>> readSystemWideThresholds(
        const XMLElement* rootElement) {
    std::vector<IoOveruseAlertThreshold> alertThresholds;
    for (const XMLElement* outerElement = rootElement->FirstChildElement(kTagSystemWideThresholds);
         outerElement != nullptr;
         outerElement = outerElement->NextSiblingElement(kTagSystemWideThresholds)) {
        IoOveruseAlertThreshold alertThreshold;
        std::unordered_set<std::string> seenParams;
        for (const XMLElement* innerElement = outerElement->FirstChildElement(kTagParam);
             innerElement != nullptr; innerElement = innerElement->NextSiblingElement(kTagParam)) {
            const char* param = nullptr;
            if (innerElement->QueryStringAttribute(kAttrId, &param) != XML_SUCCESS) {
                return Error() << "Failed to read '" << kAttrId << "' attribute in '" << kTagParam
                               << "' tag";
            }
            if (seenParams.find(param) != seenParams.end()) {
                return Error() << "Duplicate threshold specified for param '" << param << "'";
            }
            int64_t value = 0;
            if (const auto text = innerElement->GetText(); text == nullptr) {
                return Error() << "Must specify non-empty threshold for param '" << param << "'";
            } else if (const auto valueStr = Trim(text); !ParseInt(valueStr.c_str(), &value)) {
                return Error() << "Failed to parse threshold for the param '" << param
                               << "': Received threshold value '" << valueStr << "'";
            }
            if (!strcmp(param, kParamIdDurationSeconds)) {
                seenParams.insert(kParamIdDurationSeconds);
                alertThreshold.durationInSeconds = value;
            } else if (!strcmp(param, kParamIdWrittenBytesPerSecond)) {
                seenParams.insert(kParamIdWrittenBytesPerSecond);
                alertThreshold.writtenBytesPerSecond = value;
            } else {
                return Error() << "Invalid param '" << param << "' in I/O overuse alert thresholds";
            }
        }
        if (seenParams.size() != kNumParams) {
            return Error() << "Thresholds not specified for all params. Specified only for ["
                           << Join(seenParams, ", ") << "] params";
        }
        alertThresholds.push_back(alertThreshold);
    }
    return alertThresholds;
}

Result<IoOveruseConfiguration> readIoOveruseConfiguration(ComponentType componentType,
                                                          const XMLElement* rootElement) {
    const XMLElement* childElement = nullptr;
    if (const auto result = readExactlyOneElement(kTagIoOveruseConfiguration, rootElement);
        result.ok()) {
        childElement = *result;
    } else {
        return Error() << "Failed to read tag '" << kTagIoOveruseConfiguration
                       << "': " << result.error();
    }
    IoOveruseConfiguration configuration;
    if (const auto result = readComponentLevelThresholds(componentType, childElement);
        result.ok()) {
        configuration.componentLevelThresholds = *result;
    } else {
        return Error() << "Failed to read component-level thresholds: " << result.error();
    }
    if (const auto result = readPackageSpecificThresholds(childElement); result.ok()) {
        configuration.packageSpecificThresholds = *result;
    } else {
        return Error() << "Failed to read package specific thresholds: " << result.error();
    }
    if (const auto result = readAppCategorySpecificThresholds(childElement); result.ok()) {
        configuration.categorySpecificThresholds = *result;
    } else {
        return Error() << "Failed to read category specific thresholds: " << result.error();
    }
    if (const auto result = readSystemWideThresholds(childElement); result.ok()) {
        configuration.systemWideThresholds = *result;
    } else {
        return Error() << "Failed to read system-wide thresholds: " << result.error();
    }
    return configuration;
}

}  // namespace

Result<ResourceOveruseConfiguration> OveruseConfigurationXmlHelper::parseXmlFile(
        const char* filePath) {
    XMLDocument xmlDoc;
    xmlDoc.LoadFile(filePath);
    if (xmlDoc.ErrorID() != XML_SUCCESS) {
        return Error() << "Failed to read and/or parse '" << filePath << "'";
    }
    ResourceOveruseConfiguration configuration;
    const XMLElement* rootElement = xmlDoc.RootElement();
    if (!rootElement || strcmp(rootElement->Name(), kTagResourceOveruseConfiguration)) {
        return Error() << "XML file doesn't have the root element '"
                       << kTagResourceOveruseConfiguration << "'";
    }
    if (const auto result = readComponentType(rootElement); result.ok()) {
        configuration.componentType = *result;
    } else {
        return Error() << "Failed to read component type: " << result.error();
    }
    if (const auto result = readSafeToKillPackages(rootElement); result.ok()) {
        configuration.safeToKillPackages = *result;
    } else {
        return Error() << "Failed to read safe-to-kill packages: " << result.error();
    }
    if (const auto result = readVendorPackagePrefixes(rootElement); result.ok()) {
        configuration.vendorPackagePrefixes = *result;
    } else {
        return Error() << "Failed to read vendor package prefixes: " << result.error();
    }
    if (const auto result = readPackageToAppCategoryTypes(rootElement); result.ok()) {
        configuration.packageMetadata = *result;
    } else {
        return Error() << "Failed to read package to app category types: " << result.error();
    }
    if (const auto result = readIoOveruseConfiguration(configuration.componentType, rootElement);
        result.ok()) {
        configuration.resourceSpecificConfigurations.emplace_back(
                ResourceSpecificConfiguration(*result));
    } else {
        return Error() << "Failed to read I/O overuse configuration: " << result.error();
    }
    return configuration;
}

Result<void> OveruseConfigurationXmlHelper::writeXmlFile(
        [[maybe_unused]] const ResourceOveruseConfiguration& configuration,
        [[maybe_unused]] const char* filePath) {
    // TODO(b/185287136): Write the configuration to file.
    return {};
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
