/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "IoOveruseConfigs.h"

#include <android-base/strings.h>
#include <gmock/gmock.h>

#include <inttypes.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::sp;
using ::android::automotive::watchdog::internal::ApplicationCategoryType;
using ::android::automotive::watchdog::internal::ComponentType;
using ::android::automotive::watchdog::internal::IoOveruseAlertThreshold;
using ::android::automotive::watchdog::internal::IoOveruseConfiguration;
using ::android::automotive::watchdog::internal::PackageInfo;
using ::android::automotive::watchdog::internal::PackageMetadata;
using ::android::automotive::watchdog::internal::PerStateIoOveruseThreshold;
using ::android::automotive::watchdog::internal::ResourceOveruseConfiguration;
using ::android::automotive::watchdog::internal::ResourceSpecificConfiguration;
using ::android::automotive::watchdog::internal::UidType;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::testing::AllOf;
using ::testing::AnyOf;
using ::testing::ExplainMatchResult;
using ::testing::Field;
using ::testing::IsEmpty;
using ::testing::Matcher;
using ::testing::UnorderedElementsAre;
using ::testing::UnorderedElementsAreArray;
using ::testing::Value;

namespace {

PerStateBytes toPerStateBytes(const int64_t fgBytes, const int64_t bgBytes,
                              const int64_t garageModeBytes) {
    PerStateBytes perStateBytes;
    perStateBytes.foregroundBytes = fgBytes;
    perStateBytes.backgroundBytes = bgBytes;
    perStateBytes.garageModeBytes = garageModeBytes;
    return perStateBytes;
}

IoOveruseAlertThreshold toIoOveruseAlertThreshold(const int64_t durationInSeconds,
                                                  const int64_t writtenBytesPerSecond) {
    IoOveruseAlertThreshold threshold;
    threshold.durationInSeconds = durationInSeconds;
    threshold.writtenBytesPerSecond = writtenBytesPerSecond;
    return threshold;
}

const PerStateBytes SYSTEM_COMPONENT_LEVEL_THRESHOLDS = toPerStateBytes(200, 100, 500);
const PerStateBytes SYSTEM_PACKAGE_A_THRESHOLDS = toPerStateBytes(600, 400, 1000);
const PerStateBytes SYSTEM_PACKAGE_B_THRESHOLDS = toPerStateBytes(1200, 800, 1500);
const PerStateBytes VENDOR_COMPONENT_LEVEL_THRESHOLDS = toPerStateBytes(100, 50, 900);
const PerStateBytes VENDOR_PACKAGE_A_THRESHOLDS = toPerStateBytes(800, 300, 500);
const PerStateBytes VENDOR_PKG_B_THRESHOLDS = toPerStateBytes(1600, 600, 1000);
const PerStateBytes MAPS_THRESHOLDS = toPerStateBytes(700, 900, 1300);
const PerStateBytes MEDIA_THRESHOLDS = toPerStateBytes(1800, 1900, 2100);
const PerStateBytes THIRD_PARTY_COMPONENT_LEVEL_THRESHOLDS = toPerStateBytes(300, 150, 1900);
const std::vector<IoOveruseAlertThreshold> ALERT_THRESHOLDS = {toIoOveruseAlertThreshold(5, 200),
                                                               toIoOveruseAlertThreshold(30,
                                                                                         40000)};

PerStateIoOveruseThreshold toPerStateIoOveruseThreshold(const std::string& name,
                                                        const PerStateBytes& perStateBytes) {
    PerStateIoOveruseThreshold threshold;
    threshold.name = name;
    threshold.perStateWriteBytes = perStateBytes;
    return threshold;
}

PerStateIoOveruseThreshold toPerStateIoOveruseThreshold(const ComponentType type,
                                                        const PerStateBytes& perStateBytes) {
    return toPerStateIoOveruseThreshold(toString(type), perStateBytes);
}

PerStateIoOveruseThreshold toPerStateIoOveruseThreshold(const std::string& name,
                                                        const int64_t fgBytes,
                                                        const int64_t bgBytes,
                                                        const int64_t garageModeBytes) {
    PerStateIoOveruseThreshold threshold;
    threshold.name = name;
    threshold.perStateWriteBytes = toPerStateBytes(fgBytes, bgBytes, garageModeBytes);
    return threshold;
}

PerStateIoOveruseThreshold toPerStateIoOveruseThreshold(const ComponentType type,
                                                        const int64_t fgBytes,
                                                        const int64_t bgBytes,
                                                        const int64_t garageModeBytes) {
    return toPerStateIoOveruseThreshold(toString(type), fgBytes, bgBytes, garageModeBytes);
}

PackageMetadata toPackageMetadata(std::string packageName, ApplicationCategoryType type) {
    PackageMetadata meta;
    meta.packageName = packageName;
    meta.appCategoryType = type;
    return meta;
}

std::unordered_map<std::string, ApplicationCategoryType> toPackageToAppCategoryMappings(
        const std::vector<PackageMetadata>& metas) {
    std::unordered_map<std::string, ApplicationCategoryType> mappings;
    for (const auto& meta : metas) {
        mappings[meta.packageName] = meta.appCategoryType;
    }
    return mappings;
}

PackageInfo constructPackageInfo(
        const char* packageName, const ComponentType componentType,
        const ApplicationCategoryType appCategoryType = ApplicationCategoryType::OTHERS) {
    PackageInfo packageInfo;
    packageInfo.packageIdentifier.name = packageName;
    packageInfo.uidType = UidType::APPLICATION;
    packageInfo.componentType = componentType;
    packageInfo.appCategoryType = appCategoryType;
    return packageInfo;
}

ResourceOveruseConfiguration constructResourceOveruseConfig(
        const ComponentType type, const std::vector<std::string>&& safeToKill,
        const std::vector<std::string>&& vendorPrefixes,
        const std::vector<PackageMetadata> packageMetadata,
        const IoOveruseConfiguration& ioOveruseConfiguration) {
    ResourceOveruseConfiguration resourceOveruseConfig;
    resourceOveruseConfig.componentType = type;
    resourceOveruseConfig.safeToKillPackages = safeToKill;
    resourceOveruseConfig.vendorPackagePrefixes = vendorPrefixes;
    resourceOveruseConfig.packageMetadata = packageMetadata;
    ResourceSpecificConfiguration config;
    config.set<ResourceSpecificConfiguration::ioOveruseConfiguration>(ioOveruseConfiguration);
    resourceOveruseConfig.resourceSpecificConfigurations.push_back(config);
    return resourceOveruseConfig;
}

IoOveruseConfiguration constructIoOveruseConfig(
        PerStateIoOveruseThreshold componentLevel,
        const std::vector<PerStateIoOveruseThreshold>& packageSpecific,
        const std::vector<PerStateIoOveruseThreshold>& categorySpecific,
        const std::vector<IoOveruseAlertThreshold>& systemWide) {
    IoOveruseConfiguration config;
    config.componentLevelThresholds = componentLevel;
    config.packageSpecificThresholds = packageSpecific;
    config.categorySpecificThresholds = categorySpecific;
    config.systemWideThresholds = systemWide;
    return config;
}

std::string toString(std::vector<ResourceOveruseConfiguration> configs) {
    std::string buffer;
    StringAppendF(&buffer, "[");
    for (const auto& config : configs) {
        if (buffer.size() > 1) {
            StringAppendF(&buffer, ",\n");
        }
        StringAppendF(&buffer, "%s", config.toString().c_str());
    }
    StringAppendF(&buffer, "]\n");
    return buffer;
}

MATCHER_P(IsIoOveruseConfiguration, config, "") {
    return arg.componentLevelThresholds == config.componentLevelThresholds &&
            ExplainMatchResult(UnorderedElementsAreArray(config.packageSpecificThresholds),
                               arg.packageSpecificThresholds, result_listener) &&
            ExplainMatchResult(UnorderedElementsAreArray(config.categorySpecificThresholds),
                               arg.categorySpecificThresholds, result_listener) &&
            ExplainMatchResult(UnorderedElementsAreArray(config.systemWideThresholds),
                               arg.systemWideThresholds, result_listener);
}

MATCHER_P(IsResourceSpecificConfiguration, config, "") {
    if (arg.getTag() != config.getTag()) {
        return false;
    }
    // Reference with the actual datatype so the templated get method can be called.
    const ResourceSpecificConfiguration& expected = config;
    const ResourceSpecificConfiguration& actual = arg;
    switch (arg.getTag()) {
        case ResourceSpecificConfiguration::ioOveruseConfiguration: {
            const auto& expectedIoConfig =
                    expected.get<ResourceSpecificConfiguration::ioOveruseConfiguration>();
            const auto& actualIoConfig =
                    actual.get<ResourceSpecificConfiguration::ioOveruseConfiguration>();
            return ExplainMatchResult(IsIoOveruseConfiguration(expectedIoConfig), actualIoConfig,
                                      result_listener);
        }
        default:
            return true;
    }
}

Matcher<const ResourceOveruseConfiguration> IsResourceOveruseConfiguration(
        const ResourceOveruseConfiguration& config) {
    std::vector<Matcher<const ResourceSpecificConfiguration>> matchers;
    for (const auto& resourceSpecificConfig : config.resourceSpecificConfigurations) {
        matchers.push_back(IsResourceSpecificConfiguration(resourceSpecificConfig));
    }

    return AllOf(Field(&ResourceOveruseConfiguration::componentType, config.componentType),
                 Field(&ResourceOveruseConfiguration::safeToKillPackages,
                       UnorderedElementsAreArray(config.safeToKillPackages)),
                 Field(&ResourceOveruseConfiguration::vendorPackagePrefixes,
                       UnorderedElementsAreArray(config.vendorPackagePrefixes)),
                 Field(&ResourceOveruseConfiguration::resourceSpecificConfigurations,
                       UnorderedElementsAreArray(matchers)));
}

std::vector<Matcher<const ResourceOveruseConfiguration>> IsResourceOveruseConfigurations(
        const std::vector<ResourceOveruseConfiguration>& configs) {
    std::vector<Matcher<const ResourceOveruseConfiguration>> matchers;
    for (const auto config : configs) {
        matchers.push_back(IsResourceOveruseConfiguration(config));
    }
    return matchers;
}

ResourceOveruseConfiguration sampleSystemConfig() {
    auto systemIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::SYSTEM,
                                                            SYSTEM_COMPONENT_LEVEL_THRESHOLDS),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("systemPackageA", SYSTEM_PACKAGE_A_THRESHOLDS),
             toPerStateIoOveruseThreshold("systemPackageB", SYSTEM_PACKAGE_B_THRESHOLDS)},
            /*categorySpecific=*/{},
            /*systemWide=*/ALERT_THRESHOLDS);
    return constructResourceOveruseConfig(ComponentType::SYSTEM, /*safeToKill=*/{"systemPackageA"},
                                          /*vendorPrefixes=*/{},
                                          /*packageMetadata=*/
                                          {toPackageMetadata("systemPackageA",
                                                             ApplicationCategoryType::MEDIA),
                                           toPackageMetadata("vendorPkgB",
                                                             ApplicationCategoryType::MAPS)},
                                          systemIoConfig);
}

ResourceOveruseConfiguration sampleVendorConfig() {
    auto vendorIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::VENDOR,
                                                            VENDOR_COMPONENT_LEVEL_THRESHOLDS),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("vendorPackageA", VENDOR_PACKAGE_A_THRESHOLDS),
             toPerStateIoOveruseThreshold("vendorPkgB", VENDOR_PKG_B_THRESHOLDS)},
            /*categorySpecific=*/
            {toPerStateIoOveruseThreshold("MAPS", MAPS_THRESHOLDS),
             toPerStateIoOveruseThreshold("MEDIA", MEDIA_THRESHOLDS)},
            /*systemWide=*/{});
    return constructResourceOveruseConfig(ComponentType::VENDOR,
                                          /*safeToKill=*/{"vendorPackageA"},
                                          /*vendorPrefixes=*/{"vendorPackage"},
                                          /*packageMetadata=*/
                                          {toPackageMetadata("systemPackageA",
                                                             ApplicationCategoryType::MEDIA),
                                           toPackageMetadata("vendorPkgB",
                                                             ApplicationCategoryType::MAPS)},
                                          vendorIoConfig);
}

ResourceOveruseConfiguration sampleThirdPartyConfig() {
    auto thirdPartyIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY,
                                         THIRD_PARTY_COMPONENT_LEVEL_THRESHOLDS),
            /*packageSpecific=*/{}, /*categorySpecific=*/{}, /*systemWide=*/{});
    return constructResourceOveruseConfig(ComponentType::THIRD_PARTY, /*safeToKill=*/{},
                                          /*vendorPrefixes=*/{}, /*packageMetadata=*/{},
                                          thirdPartyIoConfig);
}

sp<IoOveruseConfigs> sampleIoOveruseConfigs() {
    sp<IoOveruseConfigs> ioOveruseConfigs = new IoOveruseConfigs();
    EXPECT_RESULT_OK(ioOveruseConfigs->update(
            {sampleSystemConfig(), sampleVendorConfig(), sampleThirdPartyConfig()}));
    return ioOveruseConfigs;
}

}  // namespace

TEST(IoOveruseConfigsTest, TestUpdateWithValidConfigs) {
    auto systemResourceConfig = sampleSystemConfig();
    auto vendorResourceConfig = sampleVendorConfig();
    auto thirdPartyResourceConfig = sampleThirdPartyConfig();

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update(
            {systemResourceConfig, vendorResourceConfig, thirdPartyResourceConfig}));

    vendorResourceConfig.vendorPackagePrefixes.push_back("vendorPkgB");
    std::vector<ResourceOveruseConfiguration> expected = {systemResourceConfig,
                                                          vendorResourceConfig,
                                                          thirdPartyResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(IsResourceOveruseConfigurations(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);

    // Check whether previous configs are overwritten.
    auto systemIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::SYSTEM, 300, 400, 600),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("systemPackageC", 700, 100, 200),
             toPerStateIoOveruseThreshold("systemPackageC", 300, 200, 300)},
            /*categorySpecific=*/{},
            /*systemWide=*/
            {toIoOveruseAlertThreshold(6, 4), toIoOveruseAlertThreshold(6, 10)});
    systemResourceConfig =
            constructResourceOveruseConfig(ComponentType::SYSTEM, /*safeToKill=*/{"systemPackageC"},
                                           /*vendorPrefixes=*/{}, /*packageMetadata=*/{},
                                           systemIoConfig);

    /*
     * Not adding any safe to kill packages list or package specific thresholds should clear
     * previous entries after update.
     */
    auto vendorIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::VENDOR, 10, 90, 300),
            /*packageSpecific=*/{},
            /*categorySpecific=*/
            {toPerStateIoOveruseThreshold("MAPS", 800, 900, 2000),
             toPerStateIoOveruseThreshold("MEDIA", 1800, 1900, 2100),
             toPerStateIoOveruseThreshold("MEDIA", 1400, 1600, 2000)},
            /*systemWide=*/{});
    vendorResourceConfig =
            constructResourceOveruseConfig(ComponentType::VENDOR, /*safeToKill=*/{},
                                           /*vendorPrefixes=*/{"vendorPackage", "vendorPkg"},
                                           /*packageMetadata=*/{}, vendorIoConfig);

    auto thirdPartyIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 600, 300, 2300),
            /*packageSpecific=*/{}, /*categorySpecific=*/{}, /*systemWide=*/{});
    thirdPartyResourceConfig =
            constructResourceOveruseConfig(ComponentType::THIRD_PARTY, /*safeToKill=*/{},
                                           /*vendorPrefixes=*/{}, /*packageMetadata=*/{},
                                           thirdPartyIoConfig);

    ASSERT_RESULT_OK(ioOveruseConfigs.update(
            {systemResourceConfig, vendorResourceConfig, thirdPartyResourceConfig}));

    systemIoConfig.packageSpecificThresholds.erase(
            systemIoConfig.packageSpecificThresholds.begin());
    systemIoConfig.systemWideThresholds.erase(systemIoConfig.systemWideThresholds.begin() + 1);
    systemResourceConfig =
            constructResourceOveruseConfig(ComponentType::SYSTEM, /*safeToKill=*/{"systemPackageC"},
                                           /*vendorPrefixes=*/{}, /*packageMetadata=*/{},
                                           systemIoConfig);

    vendorIoConfig.categorySpecificThresholds.erase(
            vendorIoConfig.categorySpecificThresholds.begin() + 1);
    vendorResourceConfig =
            constructResourceOveruseConfig(ComponentType::VENDOR, /*safeToKill=*/{},
                                           /*vendorPrefixes=*/{"vendorPackage", "vendorPkg"},
                                           /*packageMetadata=*/{}, vendorIoConfig);

    expected = {systemResourceConfig, vendorResourceConfig, thirdPartyResourceConfig};

    actual.clear();
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(IsResourceOveruseConfigurations(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST(IoOveruseConfigsTest, TestDefaultConfigWithoutUpdate) {
    PerStateBytes defaultPerStateBytes = defaultThreshold().perStateWriteBytes;
    IoOveruseConfigs ioOveruseConfigs;

    auto packageInfo = constructPackageInfo("systemPackage", ComponentType::SYSTEM);
    EXPECT_THAT(ioOveruseConfigs.fetchThreshold(packageInfo), defaultPerStateBytes)
            << "System package should have default threshold";
    EXPECT_FALSE(ioOveruseConfigs.isSafeToKill(packageInfo))
            << "System package shouldn't be killed by default";

    packageInfo = constructPackageInfo("vendorPackage", ComponentType::VENDOR,
                                       ApplicationCategoryType::MEDIA);
    EXPECT_THAT(ioOveruseConfigs.fetchThreshold(packageInfo), defaultPerStateBytes)
            << "Vendor package should have default threshold";
    EXPECT_FALSE(ioOveruseConfigs.isSafeToKill(packageInfo))
            << "Vendor package shouldn't be killed by default";

    packageInfo = constructPackageInfo("3pPackage", ComponentType::THIRD_PARTY,
                                       ApplicationCategoryType::MAPS);
    EXPECT_THAT(ioOveruseConfigs.fetchThreshold(packageInfo), defaultPerStateBytes)
            << "Third-party package should have default threshold";
    EXPECT_TRUE(ioOveruseConfigs.isSafeToKill(packageInfo))
            << "Third-party package should be killed by default";

    EXPECT_THAT(ioOveruseConfigs.systemWideAlertThresholds(), IsEmpty());
    EXPECT_THAT(ioOveruseConfigs.vendorPackagePrefixes(), IsEmpty());

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST(IoOveruseConfigsTest, TestFailsUpdateOnInvalidComponentName) {
    IoOveruseConfiguration randomIoConfig;
    randomIoConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold("random name", 200, 100, 500);

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs
                         .update({constructResourceOveruseConfig(ComponentType::SYSTEM, {}, {}, {},
                                                                 randomIoConfig)})
                         .ok());

    EXPECT_FALSE(ioOveruseConfigs
                         .update({constructResourceOveruseConfig(ComponentType::VENDOR, {}, {}, {},
                                                                 randomIoConfig)})
                         .ok());

    EXPECT_FALSE(ioOveruseConfigs
                         .update({constructResourceOveruseConfig(ComponentType::THIRD_PARTY, {}, {},
                                                                 {}, randomIoConfig)})
                         .ok());

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST(IoOveruseConfigsTest, TestFailsUpdateOnInvalidComponentLevelThresholds) {
    IoOveruseConfiguration ioConfig;
    ioConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 0, 0, 0);

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs
                         .update({constructResourceOveruseConfig(ComponentType::THIRD_PARTY, {}, {},
                                                                 {}, ioConfig)})
                         .ok())
            << "Should error on invalid component level thresholds";

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST(IoOveruseConfigsTest, TestFailsUpdateOnInvalidSystemWideAlertThresholds) {
    IoOveruseConfiguration ioConfig;
    ioConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::SYSTEM, 100, 200, 300);
    ioConfig.systemWideThresholds = {toIoOveruseAlertThreshold(0, 0)};

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs
                         .update({constructResourceOveruseConfig(ComponentType::SYSTEM, {}, {}, {},
                                                                 ioConfig)})
                         .ok())
            << "Should error on invalid system-wide thresholds";

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST(IoOveruseConfigsTest, TestFailsUpdateOnDuplicateConfigsForSameComponent) {
    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs.update({sampleThirdPartyConfig(), sampleThirdPartyConfig()}).ok())
            << "Should error on duplicate configs for the same component";

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST(IoOveruseConfigsTest, TestFailsUpdateOnNoIoOveruseConfiguration) {
    ResourceOveruseConfiguration resConfig;
    resConfig.componentType = ComponentType::THIRD_PARTY;

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs.update({resConfig}).ok())
            << "Should error on no I/O overuse configuration";

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST(IoOveruseConfigsTest, TestFailsUpdateOnMultipleIoOveruseConfigurations) {
    IoOveruseConfiguration ioConfig;
    ioConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 100, 200, 300);

    ResourceOveruseConfiguration resConfig;
    resConfig.componentType = ComponentType::THIRD_PARTY;
    ResourceSpecificConfiguration resourceSpecificConfig;
    resourceSpecificConfig.set<ResourceSpecificConfiguration::ioOveruseConfiguration>(ioConfig);
    resConfig.resourceSpecificConfigurations.push_back(resourceSpecificConfig);
    resConfig.resourceSpecificConfigurations.push_back(resourceSpecificConfig);

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs.update({resConfig}).ok())
            << "Should error on multiple I/O overuse configuration";

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST(IoOveruseConfigsTest, TestIgnoresNonUpdatableConfigsBySystemComponent) {
    auto systemIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::SYSTEM, 200, 100, 500),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("systemPackageA", 600, 400, 1000),
             toPerStateIoOveruseThreshold("systemPackageB", 1200, 800, 1500)},
            /*categorySpecific=*/
            {toPerStateIoOveruseThreshold("MAPS", 700, 900, 1300),
             toPerStateIoOveruseThreshold("MEDIA", 1800, 1900, 2100)},
            /*systemWide=*/
            {toIoOveruseAlertThreshold(5, 200), toIoOveruseAlertThreshold(30, 40000)});
    auto systemResourceConfig =
            constructResourceOveruseConfig(ComponentType::SYSTEM, /*safeToKill=*/{"systemPackageA"},
                                           /*vendorPrefixes=*/{"vendorPackage"},
                                           /*packageMetadata=*/{}, systemIoConfig);

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update({systemResourceConfig}));

    // Drop fields that aren't updatable by system component.
    systemIoConfig.categorySpecificThresholds.clear();
    systemResourceConfig =
            constructResourceOveruseConfig(ComponentType::SYSTEM, /*safeToKill=*/{"systemPackageA"},
                                           /*vendorPrefixes=*/{}, /*packageMetadata=*/{},
                                           systemIoConfig);

    std::vector<ResourceOveruseConfiguration> expected = {systemResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(IsResourceOveruseConfigurations(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST(IoOveruseConfigsTest, TestIgnoresNonUpdatableConfigsByVendorComponent) {
    auto vendorIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::VENDOR, 100, 50, 900),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("vendorPackageA", 800, 300, 500),
             toPerStateIoOveruseThreshold("vendorPkgB", 1600, 600, 1000)},
            /*categorySpecific=*/
            {toPerStateIoOveruseThreshold("MAPS", 700, 900, 1300),
             toPerStateIoOveruseThreshold("MEDIA", 1800, 1900, 2100)},
            /*systemWide=*/
            {toIoOveruseAlertThreshold(5, 200), toIoOveruseAlertThreshold(30, 40000)});
    auto vendorResourceConfig =
            constructResourceOveruseConfig(ComponentType::VENDOR,
                                           /*safeToKill=*/
                                           {"vendorPackageA"},
                                           /*vendorPrefixes=*/{"vendorPackage", "vendorPkg"},
                                           /*packageMetadata=*/{}, vendorIoConfig);

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update({vendorResourceConfig}));

    // Drop fields that aren't updatable by vendor component.
    vendorIoConfig.systemWideThresholds.clear();
    vendorResourceConfig =
            constructResourceOveruseConfig(ComponentType::VENDOR,
                                           /*safeToKill=*/
                                           {"vendorPackageA"},
                                           /*vendorPrefixes=*/{"vendorPackage", "vendorPkg"},
                                           /*packageMetadata=*/{}, vendorIoConfig);

    std::vector<ResourceOveruseConfiguration> expected = {vendorResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(IsResourceOveruseConfigurations(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST(IoOveruseConfigsTest, TestIgnoresNonUpdatableConfigsByThirdPartyComponent) {
    auto thirdPartyIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 300, 150, 1900),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("vendorPackageA", 800, 300, 500),
             toPerStateIoOveruseThreshold("systemPackageB", 1600, 600, 1000)},
            /*categorySpecific=*/
            {toPerStateIoOveruseThreshold("MAPS", 700, 900, 1300),
             toPerStateIoOveruseThreshold("MEDIA", 1800, 1900, 2100)},
            /*systemWide=*/
            {toIoOveruseAlertThreshold(5, 200), toIoOveruseAlertThreshold(30, 40000)});
    auto thirdPartyResourceConfig =
            constructResourceOveruseConfig(ComponentType::THIRD_PARTY,
                                           /*safeToKill=*/{"vendorPackageA", "systemPackageB"},
                                           /*vendorPrefixes=*/{"vendorPackage"},
                                           /*packageMetadata=*/{}, thirdPartyIoConfig);

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update({thirdPartyResourceConfig}));

    // Drop fields that aren't updatable by third-party component.
    thirdPartyIoConfig.packageSpecificThresholds.clear();
    thirdPartyIoConfig.categorySpecificThresholds.clear();
    thirdPartyIoConfig.systemWideThresholds.clear();
    thirdPartyResourceConfig =
            constructResourceOveruseConfig(ComponentType::THIRD_PARTY,
                                           /*safeToKill=*/{}, /*vendorPrefixes=*/{},
                                           /*packageMetadata=*/{}, thirdPartyIoConfig);

    std::vector<ResourceOveruseConfiguration> expected = {thirdPartyResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(IsResourceOveruseConfigurations(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST(IoOveruseConfigsTest, TestFetchThresholdForSystemPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    auto actual = ioOveruseConfigs->fetchThreshold(
            constructPackageInfo("systemPackageGeneric", ComponentType::SYSTEM));

    EXPECT_THAT(actual, SYSTEM_COMPONENT_LEVEL_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(
            constructPackageInfo("systemPackageA", ComponentType::SYSTEM));

    EXPECT_THAT(actual, SYSTEM_PACKAGE_A_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(constructPackageInfo("systemPackageB",
                                                                   ComponentType::SYSTEM,
                                                                   ApplicationCategoryType::MEDIA));

    // Package specific thresholds get priority over media category thresholds.
    EXPECT_THAT(actual, SYSTEM_PACKAGE_B_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(constructPackageInfo("systemPackageC",
                                                                   ComponentType::SYSTEM,
                                                                   ApplicationCategoryType::MEDIA));

    // Media category thresholds as there is no package specific thresholds.
    EXPECT_THAT(actual, MEDIA_THRESHOLDS);
}

TEST(IoOveruseConfigsTest, TestFetchThresholdForVendorPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    auto actual = ioOveruseConfigs->fetchThreshold(
            constructPackageInfo("vendorPackageGeneric", ComponentType::VENDOR));

    EXPECT_THAT(actual, VENDOR_COMPONENT_LEVEL_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(
            constructPackageInfo("vendorPkgB", ComponentType::VENDOR));

    EXPECT_THAT(actual, VENDOR_PKG_B_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(constructPackageInfo("vendorPackageC",
                                                                   ComponentType::VENDOR,
                                                                   ApplicationCategoryType::MAPS));

    // Maps category thresholds as there is no package specific thresholds.
    EXPECT_THAT(actual, MAPS_THRESHOLDS);
}

TEST(IoOveruseConfigsTest, TestFetchThresholdForThirdPartyPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    auto actual = ioOveruseConfigs->fetchThreshold(
            constructPackageInfo("vendorPackageGenericImpostor", ComponentType::THIRD_PARTY));

    EXPECT_THAT(actual, THIRD_PARTY_COMPONENT_LEVEL_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(constructPackageInfo("3pMapsPackage",
                                                                   ComponentType::THIRD_PARTY,
                                                                   ApplicationCategoryType::MAPS));

    EXPECT_THAT(actual, MAPS_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(constructPackageInfo("3pMediaPackage",
                                                                   ComponentType::THIRD_PARTY,
                                                                   ApplicationCategoryType::MEDIA));

    EXPECT_THAT(actual, MEDIA_THRESHOLDS);
}

TEST(IoOveruseConfigsTest, TestIsSafeToKillSystemPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();
    EXPECT_FALSE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("systemPackageGeneric", ComponentType::SYSTEM)));

    EXPECT_TRUE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("systemPackageA", ComponentType::SYSTEM)));
}

TEST(IoOveruseConfigsTest, TestIsSafeToKillVendorPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();
    EXPECT_FALSE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("vendorPackageGeneric", ComponentType::VENDOR)));

    EXPECT_TRUE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("vendorPackageA", ComponentType::VENDOR)));
}

TEST(IoOveruseConfigsTest, TestIsSafeToKillThirdPartyPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();
    EXPECT_TRUE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("vendorPackageGenericImpostor", ComponentType::THIRD_PARTY)));

    EXPECT_TRUE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("3pMapsPackage", ComponentType::THIRD_PARTY,
                                 ApplicationCategoryType::MAPS)));
}

TEST(IoOveruseConfigsTest, TestIsSafeToKillNativePackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    PackageInfo packageInfo;
    packageInfo.packageIdentifier.name = "native package";
    packageInfo.uidType = UidType::NATIVE;
    packageInfo.componentType = ComponentType::SYSTEM;

    EXPECT_FALSE(ioOveruseConfigs->isSafeToKill(packageInfo));

    packageInfo.componentType = ComponentType::VENDOR;

    EXPECT_FALSE(ioOveruseConfigs->isSafeToKill(packageInfo));
}

TEST(IoOveruseConfigsTest, TestSystemWideAlertThresholds) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    EXPECT_THAT(ioOveruseConfigs->systemWideAlertThresholds(),
                UnorderedElementsAreArray(ALERT_THRESHOLDS));
}

TEST(IoOveruseConfigsTest, TestVendorPackagePrefixes) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    EXPECT_THAT(ioOveruseConfigs->vendorPackagePrefixes(),
                UnorderedElementsAre("vendorPackage", "vendorPkgB"));
}

TEST(IoOveruseConfigsTest, TestPackagesToAppCategoriesWithSystemConfig) {
    IoOveruseConfigs ioOveruseConfigs;
    const auto resourceOveruseConfig = sampleSystemConfig();

    ASSERT_RESULT_OK(ioOveruseConfigs.update({resourceOveruseConfig}));

    EXPECT_THAT(ioOveruseConfigs.packagesToAppCategories(),
                UnorderedElementsAreArray(
                        toPackageToAppCategoryMappings(resourceOveruseConfig.packageMetadata)));
}

TEST(IoOveruseConfigsTest, TestPackagesToAppCategoriesWithVendorConfig) {
    IoOveruseConfigs ioOveruseConfigs;
    const auto resourceOveruseConfig = sampleVendorConfig();

    ASSERT_RESULT_OK(ioOveruseConfigs.update({resourceOveruseConfig}));

    EXPECT_THAT(ioOveruseConfigs.packagesToAppCategories(),
                UnorderedElementsAreArray(
                        toPackageToAppCategoryMappings(resourceOveruseConfig.packageMetadata)));
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
