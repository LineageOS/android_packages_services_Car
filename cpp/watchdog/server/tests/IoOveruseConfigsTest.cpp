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
#include <utils/String8.h>

#include <inttypes.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::String16;
using ::android::String8;
using ::android::automotive::watchdog::internal::ApplicationCategoryType;
using ::android::automotive::watchdog::internal::ComponentType;
using ::android::automotive::watchdog::internal::IoOveruseAlertThreshold;
using ::android::automotive::watchdog::internal::IoOveruseConfiguration;
using ::android::automotive::watchdog::internal::PackageInfo;
using ::android::automotive::watchdog::internal::PerStateBytes;
using ::android::automotive::watchdog::internal::PerStateIoOveruseThreshold;
using ::android::base::StringPrintf;
using ::testing::AnyOf;
using ::testing::IsEmpty;
using ::testing::UnorderedElementsAre;
using ::testing::Value;

namespace {

PerStateIoOveruseThreshold toPerStateIoOveruseThreshold(const std::string& name,
                                                        const int64_t fgBytes,
                                                        const int64_t bgBytes,
                                                        const int64_t garageModeBytes) {
    PerStateIoOveruseThreshold threshold;
    threshold.name = String16(String8(name.c_str()));
    threshold.perStateWriteBytes.foregroundBytes = fgBytes;
    threshold.perStateWriteBytes.backgroundBytes = bgBytes;
    threshold.perStateWriteBytes.garageModeBytes = garageModeBytes;
    return threshold;
}

PerStateIoOveruseThreshold toPerStateIoOveruseThreshold(const ComponentType type,
                                                        const int64_t fgBytes,
                                                        const int64_t bgBytes,
                                                        const int64_t garageModeBytes) {
    return toPerStateIoOveruseThreshold(toString(type), fgBytes, bgBytes, garageModeBytes);
}

IoOveruseAlertThreshold toIoOveruseAlertThreshold(const int64_t durationInSeconds,
                                                  const int64_t writtenBytesPerSecond) {
    IoOveruseAlertThreshold threshold;
    threshold.durationInSeconds = durationInSeconds;
    threshold.writtenBytesPerSecond = writtenBytesPerSecond;
    return threshold;
}

std::vector<String16> toString16Vector(const std::vector<std::string>& values) {
    std::vector<String16> output;
    for (const auto v : values) {
        output.emplace_back(String16(String8(v.c_str())));
    }
    return output;
}

PackageInfo constructPackageInfo(
        const char* packageName, const ComponentType componentType,
        const ApplicationCategoryType appCategoryType = ApplicationCategoryType::OTHERS) {
    PackageInfo packageInfo;
    packageInfo.packageIdentifier.name = String16(packageName);
    packageInfo.componentType = componentType;
    packageInfo.appCategoryType = appCategoryType;
    return packageInfo;
}

struct PackageConfigTest {
    PackageInfo packageInfo;
    PerStateBytes expectedThreshold;
    bool expectedIsSafeToKill;
};

}  // namespace

TEST(IoOveruseConfigsTest, TestUpdateWithValidConfigs) {
    IoOveruseConfiguration systemComponentConfig;
    systemComponentConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::SYSTEM, 200, 100, 500);
    systemComponentConfig.packageSpecificThresholds =
            {toPerStateIoOveruseThreshold("systemPackageA", 600, 400, 1000),
             toPerStateIoOveruseThreshold("systemPackageB", 1200, 800, 1500)};
    systemComponentConfig.safeToKillPackages = toString16Vector({"systemPackageA"});
    systemComponentConfig.systemWideThresholds = {toIoOveruseAlertThreshold(5, 200),
                                                  toIoOveruseAlertThreshold(30, 40000)};

    IoOveruseConfiguration vendorComponentConfig;
    vendorComponentConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::VENDOR, 100, 50, 900);
    vendorComponentConfig.packageSpecificThresholds =
            {toPerStateIoOveruseThreshold("vendorPackageA", 800, 300, 500),
             toPerStateIoOveruseThreshold("vendorPkgB", 1600, 600, 1000)};
    vendorComponentConfig.safeToKillPackages =
            toString16Vector({"vendorPackageGeneric", "vendorPackageA"});
    vendorComponentConfig.vendorPackagePrefixes = toString16Vector({"vendorPackage", "vendorPkg"});
    vendorComponentConfig.categorySpecificThresholds = {toPerStateIoOveruseThreshold("MAPS", 600,
                                                                                     400, 1000),
                                                        toPerStateIoOveruseThreshold("MEDIA", 1200,
                                                                                     800, 1500)};

    IoOveruseConfiguration thirdPartyComponentConfig;
    thirdPartyComponentConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 300, 150, 1900);

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update(ComponentType::SYSTEM, systemComponentConfig));
    ASSERT_RESULT_OK(ioOveruseConfigs.update(ComponentType::VENDOR, vendorComponentConfig));
    ASSERT_RESULT_OK(
            ioOveruseConfigs.update(ComponentType::THIRD_PARTY, thirdPartyComponentConfig));

    std::vector<PackageConfigTest> packageConfigTests = {
            {.packageInfo = constructPackageInfo("systemPackageGeneric", ComponentType::SYSTEM),
             .expectedThreshold = systemComponentConfig.componentLevelThresholds.perStateWriteBytes,
             .expectedIsSafeToKill = false},
            {.packageInfo = constructPackageInfo("systemPackageA", ComponentType::SYSTEM),
             .expectedThreshold =
                     systemComponentConfig.packageSpecificThresholds[0].perStateWriteBytes,
             .expectedIsSafeToKill = true},
            {.packageInfo = constructPackageInfo("systemPackageB", ComponentType::SYSTEM,
                                                 ApplicationCategoryType::MEDIA),
             .expectedThreshold =
                     systemComponentConfig.packageSpecificThresholds[1].perStateWriteBytes,
             .expectedIsSafeToKill = false},
            {.packageInfo = constructPackageInfo("systemPackageC", ComponentType::SYSTEM,
                                                 ApplicationCategoryType::MAPS),
             .expectedThreshold =
                     vendorComponentConfig.categorySpecificThresholds[0].perStateWriteBytes,
             .expectedIsSafeToKill = false},
            {.packageInfo = constructPackageInfo("vendorPackageGeneric", ComponentType::VENDOR),
             .expectedThreshold = vendorComponentConfig.componentLevelThresholds.perStateWriteBytes,
             .expectedIsSafeToKill = true},
            {.packageInfo = constructPackageInfo("vendorPackageA", ComponentType::VENDOR),
             .expectedThreshold =
                     vendorComponentConfig.packageSpecificThresholds[0].perStateWriteBytes,
             .expectedIsSafeToKill = true},
            {.packageInfo = constructPackageInfo("vendorPkgB", ComponentType::VENDOR,
                                                 ApplicationCategoryType::MAPS),
             .expectedThreshold =
                     vendorComponentConfig.packageSpecificThresholds[1].perStateWriteBytes,
             .expectedIsSafeToKill = false},
            {.packageInfo = constructPackageInfo("vendorPackageC", ComponentType::VENDOR,
                                                 ApplicationCategoryType::MEDIA),
             .expectedThreshold =
                     vendorComponentConfig.categorySpecificThresholds[1].perStateWriteBytes,
             .expectedIsSafeToKill = false},
            {.packageInfo = constructPackageInfo("vendorPkgB", ComponentType::THIRD_PARTY),
             .expectedThreshold =
                     thirdPartyComponentConfig.componentLevelThresholds.perStateWriteBytes,
             .expectedIsSafeToKill = true},
            {.packageInfo = constructPackageInfo("3pMapsPackage", ComponentType::THIRD_PARTY,
                                                 ApplicationCategoryType::MAPS),
             .expectedThreshold =
                     vendorComponentConfig.categorySpecificThresholds[0].perStateWriteBytes,
             .expectedIsSafeToKill = true},
            {.packageInfo = constructPackageInfo("3pMediaPackage", ComponentType::THIRD_PARTY,
                                                 ApplicationCategoryType::MEDIA),
             .expectedThreshold =
                     vendorComponentConfig.categorySpecificThresholds[1].perStateWriteBytes,
             .expectedIsSafeToKill = true},
    };

    for (const auto& test : packageConfigTests) {
        const auto actualThreshold = ioOveruseConfigs.fetchThreshold(test.packageInfo);
        EXPECT_THAT(actualThreshold, test.expectedThreshold)
                << test.packageInfo.toString()
                << ": \nExpected threshold: " << test.expectedThreshold.toString()
                << "\nActual threshold: " << actualThreshold.toString();
        EXPECT_THAT(ioOveruseConfigs.isSafeToKill(test.packageInfo), test.expectedIsSafeToKill)
                << test.packageInfo.toString() << ": doesn't match expected safe-to-kill value";
    }

    EXPECT_THAT(ioOveruseConfigs.systemWideAlertThresholds(),
                UnorderedElementsAre(systemComponentConfig.systemWideThresholds[0],
                                     systemComponentConfig.systemWideThresholds[1]));

    EXPECT_THAT(ioOveruseConfigs.vendorPackagePrefixes(),
                UnorderedElementsAre(std::string(String8(
                                             vendorComponentConfig.vendorPackagePrefixes[0])),
                                     std::string(String8(
                                             vendorComponentConfig.vendorPackagePrefixes[1]))));

    // Check whether previous configs are overwritten.
    systemComponentConfig = {};
    systemComponentConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::SYSTEM, 300, 400, 600);
    systemComponentConfig.packageSpecificThresholds =
            {toPerStateIoOveruseThreshold("systemPackageC", 700, 100, 200),
             toPerStateIoOveruseThreshold("systemPackageC", 300, 200, 300)};
    systemComponentConfig.safeToKillPackages = toString16Vector({"systemPackageC"});
    systemComponentConfig.systemWideThresholds = {toIoOveruseAlertThreshold(6, 4),
                                                  toIoOveruseAlertThreshold(6, 10)};

    vendorComponentConfig = {};
    vendorComponentConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::VENDOR, 10, 90, 300);
    /*
     * Not adding any safe to kill packages list or package specific thresholds should clear
     * previous entries after update.
     */
    vendorComponentConfig.vendorPackagePrefixes = toString16Vector({"vendorPackage", "vendorPkg"});
    vendorComponentConfig
            .categorySpecificThresholds = {toPerStateIoOveruseThreshold("MAPS", 800, 900, 2000),
                                           toPerStateIoOveruseThreshold("MEDIA", 1200, 800, 1500),
                                           toPerStateIoOveruseThreshold("MEDIA", 1400, 1600, 2000)};

    thirdPartyComponentConfig = {};
    thirdPartyComponentConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 600, 300, 2300);

    ASSERT_RESULT_OK(ioOveruseConfigs.update(ComponentType::SYSTEM, systemComponentConfig));
    ASSERT_RESULT_OK(ioOveruseConfigs.update(ComponentType::VENDOR, vendorComponentConfig));
    ASSERT_RESULT_OK(
            ioOveruseConfigs.update(ComponentType::THIRD_PARTY, thirdPartyComponentConfig));

    packageConfigTests = {
            {.packageInfo = constructPackageInfo("systemPackageA", ComponentType::SYSTEM),
             .expectedThreshold = systemComponentConfig.componentLevelThresholds.perStateWriteBytes,
             .expectedIsSafeToKill = false},
            {.packageInfo = constructPackageInfo("systemPackageC", ComponentType::SYSTEM),
             .expectedThreshold =
                     systemComponentConfig.packageSpecificThresholds[1].perStateWriteBytes,
             .expectedIsSafeToKill = true},
            {.packageInfo = constructPackageInfo("systemMapsPackage", ComponentType::SYSTEM,
                                                 ApplicationCategoryType::MAPS),
             .expectedThreshold =
                     vendorComponentConfig.categorySpecificThresholds[0].perStateWriteBytes,
             .expectedIsSafeToKill = false},
            {.packageInfo = constructPackageInfo("vendorPackageA", ComponentType::VENDOR),
             .expectedThreshold = vendorComponentConfig.componentLevelThresholds.perStateWriteBytes,
             .expectedIsSafeToKill = false},
            {.packageInfo = constructPackageInfo("vendorMediaPackage", ComponentType::VENDOR,
                                                 ApplicationCategoryType::MEDIA),
             .expectedThreshold =
                     vendorComponentConfig.categorySpecificThresholds[2].perStateWriteBytes,
             .expectedIsSafeToKill = false},
            {.packageInfo = constructPackageInfo("3pPackage", ComponentType::THIRD_PARTY),
             .expectedThreshold =
                     thirdPartyComponentConfig.componentLevelThresholds.perStateWriteBytes,
             .expectedIsSafeToKill = true},
    };

    for (const auto& test : packageConfigTests) {
        const auto actualThreshold = ioOveruseConfigs.fetchThreshold(test.packageInfo);
        EXPECT_THAT(actualThreshold, test.expectedThreshold)
                << test.packageInfo.toString()
                << ": \nExpected threshold: " << test.expectedThreshold.toString()
                << "\nActual threshold: " << actualThreshold.toString();
        EXPECT_THAT(ioOveruseConfigs.isSafeToKill(test.packageInfo), test.expectedIsSafeToKill)
                << test.packageInfo.toString() << ": doesn't match expected safe-to-kill value";
    }

    EXPECT_THAT(ioOveruseConfigs.systemWideAlertThresholds(),
                UnorderedElementsAre(AnyOf(systemComponentConfig.systemWideThresholds[0],
                                           systemComponentConfig.systemWideThresholds[1])));

    EXPECT_THAT(ioOveruseConfigs.vendorPackagePrefixes(),
                UnorderedElementsAre(std::string(String8(
                                             vendorComponentConfig.vendorPackagePrefixes[0])),
                                     std::string(String8(
                                             vendorComponentConfig.vendorPackagePrefixes[1]))));
}

TEST(IoOveruseConfigsTest, TestDefaultConfigWithoutUpdate) {
    std::vector<PackageConfigTest> packageConfigTests =
            {{.packageInfo = constructPackageInfo("systemPackage", ComponentType::SYSTEM),
              .expectedThreshold = defaultThreshold().perStateWriteBytes,
              .expectedIsSafeToKill = false},
             {.packageInfo = constructPackageInfo("vendorPackage", ComponentType::VENDOR,
                                                  ApplicationCategoryType::MEDIA),
              .expectedThreshold = defaultThreshold().perStateWriteBytes,
              .expectedIsSafeToKill = false},
             {.packageInfo = constructPackageInfo("3pPackage", ComponentType::THIRD_PARTY,
                                                  ApplicationCategoryType::MAPS),
              .expectedThreshold = defaultThreshold().perStateWriteBytes,
              .expectedIsSafeToKill = true}};
    IoOveruseConfigs ioOveruseConfigs;

    for (const auto& test : packageConfigTests) {
        const auto actualThreshold = ioOveruseConfigs.fetchThreshold(test.packageInfo);
        EXPECT_THAT(actualThreshold, test.expectedThreshold)
                << test.packageInfo.toString()
                << ": \nExpected threshold: " << test.expectedThreshold.toString()
                << "\nActual threshold: " << actualThreshold.toString();
        EXPECT_THAT(ioOveruseConfigs.isSafeToKill(test.packageInfo), test.expectedIsSafeToKill)
                << test.packageInfo.toString() << ": doesn't match expected safe-to-kill value";
    }

    EXPECT_THAT(ioOveruseConfigs.systemWideAlertThresholds(), IsEmpty());
    EXPECT_THAT(ioOveruseConfigs.vendorPackagePrefixes(), IsEmpty());
}

TEST(IoOveruseConfigsTest, TestFailsUpdateOnInvalidComponentName) {
    IoOveruseConfiguration updateConfig;
    updateConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold("random name", 200, 100, 500);

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs.update(ComponentType::SYSTEM, updateConfig).ok());

    EXPECT_FALSE(ioOveruseConfigs.update(ComponentType::VENDOR, updateConfig).ok());

    EXPECT_FALSE(ioOveruseConfigs.update(ComponentType::THIRD_PARTY, updateConfig).ok());
}

TEST(IoOveruseConfigsTest, TestFailsUpdateOnInvalidConfigs) {
    IoOveruseConfiguration updateConfig;
    updateConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 0, 0, 0);
    const IoOveruseConfigs expected = {};

    IoOveruseConfigs ioOveruseConfigs;

    EXPECT_FALSE(ioOveruseConfigs.update(ComponentType::THIRD_PARTY, updateConfig).ok())
            << "Should error on invalid component level thresholds";

    updateConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::SYSTEM, 100, 200, 300);
    updateConfig.systemWideThresholds = {toIoOveruseAlertThreshold(0, 0)};

    EXPECT_FALSE(ioOveruseConfigs.update(ComponentType::SYSTEM, updateConfig).ok())
            << "Should error on invalid system-wide thresholds";
}

TEST(IoOveruseConfigsTest, TestIgnoresNonUpdatableConfigsBySystemComponent) {
    IoOveruseConfiguration updateConfig;
    updateConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::SYSTEM, 200, 100, 500);
    updateConfig.packageSpecificThresholds = {toPerStateIoOveruseThreshold("systemPackageA", 600,
                                                                           400, 1000),
                                              toPerStateIoOveruseThreshold("systemPackageB", 1200,
                                                                           800, 1500)};
    updateConfig.safeToKillPackages = toString16Vector({"systemPackageA"});
    updateConfig.vendorPackagePrefixes = toString16Vector({"vendorPackage"});
    updateConfig.categorySpecificThresholds = {toPerStateIoOveruseThreshold("MAPS", 600, 400, 1000),
                                               toPerStateIoOveruseThreshold("MEDIA", 1200, 800,
                                                                            1500)};
    updateConfig.systemWideThresholds = {toIoOveruseAlertThreshold(5, 200),
                                         toIoOveruseAlertThreshold(30, 40000)};

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update(ComponentType::SYSTEM, updateConfig));

    std::vector<PackageConfigTest> packageConfigTests =
            {{.packageInfo = constructPackageInfo("systemMapsPackage", ComponentType::SYSTEM,
                                                  ApplicationCategoryType::MAPS),
              .expectedThreshold = updateConfig.componentLevelThresholds.perStateWriteBytes,
              .expectedIsSafeToKill = false},
             {.packageInfo = constructPackageInfo("systemPackageA", ComponentType::SYSTEM),
              .expectedThreshold = updateConfig.packageSpecificThresholds[0].perStateWriteBytes,
              .expectedIsSafeToKill = true},
             {.packageInfo = constructPackageInfo("systemPackageB", ComponentType::SYSTEM,
                                                  ApplicationCategoryType::MEDIA),
              .expectedThreshold = updateConfig.packageSpecificThresholds[1].perStateWriteBytes,
              .expectedIsSafeToKill = false}};

    for (const auto& test : packageConfigTests) {
        const auto actualThreshold = ioOveruseConfigs.fetchThreshold(test.packageInfo);
        EXPECT_THAT(actualThreshold, test.expectedThreshold)
                << test.packageInfo.toString()
                << ": \nExpected threshold: " << test.expectedThreshold.toString()
                << "\nActual threshold: " << actualThreshold.toString();
        EXPECT_THAT(ioOveruseConfigs.isSafeToKill(test.packageInfo), test.expectedIsSafeToKill)
                << test.packageInfo.toString() << ": doesn't match expected safe-to-kill value";
    }

    EXPECT_THAT(ioOveruseConfigs.systemWideAlertThresholds(),
                UnorderedElementsAre(updateConfig.systemWideThresholds[0],
                                     updateConfig.systemWideThresholds[1]));

    EXPECT_THAT(ioOveruseConfigs.vendorPackagePrefixes(), IsEmpty())
            << "System component config shouldn't update vendor package prefixes. Only vendor "
            << "component config should update this";
}

TEST(IoOveruseConfigsTest, TestIgnoresNonUpdatableConfigsByVendorComponent) {
    IoOveruseConfiguration updateConfig;
    updateConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::VENDOR, 100, 50, 900);
    updateConfig.packageSpecificThresholds = {toPerStateIoOveruseThreshold("vendorPackageA", 800,
                                                                           300, 500),
                                              toPerStateIoOveruseThreshold("systemPackageB", 1600,
                                                                           600, 1000)};
    updateConfig.safeToKillPackages = toString16Vector({"vendorPackageA"});
    updateConfig.vendorPackagePrefixes = toString16Vector({"vendorPackage"});
    updateConfig.categorySpecificThresholds = {toPerStateIoOveruseThreshold("MAPS", 600, 400, 1000),
                                               toPerStateIoOveruseThreshold("MEDIA", 1200, 800,
                                                                            1500)};
    updateConfig.systemWideThresholds = {toIoOveruseAlertThreshold(5, 200),
                                         toIoOveruseAlertThreshold(30, 40000)};

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update(ComponentType::VENDOR, updateConfig));

    std::vector<PackageConfigTest> packageConfigTests =
            {{.packageInfo = constructPackageInfo("vendorPackageGeneric", ComponentType::VENDOR),
              .expectedThreshold = updateConfig.componentLevelThresholds.perStateWriteBytes,
              .expectedIsSafeToKill = false},
             {.packageInfo = constructPackageInfo("vendorPackageA", ComponentType::VENDOR),
              .expectedThreshold = updateConfig.packageSpecificThresholds[0].perStateWriteBytes,
              .expectedIsSafeToKill = true},
             {.packageInfo = constructPackageInfo("systemPackageB", ComponentType::VENDOR,
                                                  ApplicationCategoryType::MEDIA),
              .expectedThreshold = updateConfig.packageSpecificThresholds[1].perStateWriteBytes,
              .expectedIsSafeToKill = false},
             {.packageInfo = constructPackageInfo("vendorMapsPackage", ComponentType::VENDOR,
                                                  ApplicationCategoryType::MAPS),
              .expectedThreshold = updateConfig.categorySpecificThresholds[0].perStateWriteBytes,
              .expectedIsSafeToKill = false}};

    for (const auto& test : packageConfigTests) {
        const auto actualThreshold = ioOveruseConfigs.fetchThreshold(test.packageInfo);
        EXPECT_THAT(actualThreshold, test.expectedThreshold)
                << test.packageInfo.toString()
                << ": \nExpected threshold: " << test.expectedThreshold.toString()
                << "\nActual threshold: " << actualThreshold.toString();
        EXPECT_THAT(ioOveruseConfigs.isSafeToKill(test.packageInfo), test.expectedIsSafeToKill)
                << test.packageInfo.toString() << ": doesn't match expected safe-to-kill value";
    }

    EXPECT_THAT(ioOveruseConfigs.systemWideAlertThresholds(), IsEmpty())
            << "Vendor component config shouldn't update system wide alert thresholds. Only system "
            << "component config should update this";

    EXPECT_THAT(ioOveruseConfigs.vendorPackagePrefixes(),
                UnorderedElementsAre("vendorPackage", "systemPackageB"));
}

TEST(IoOveruseConfigsTest, TestIgnoresNonUpdatableConfigsByThirdPartyComponent) {
    IoOveruseConfiguration updateConfig;
    updateConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 300, 150, 1900);
    updateConfig.packageSpecificThresholds = {toPerStateIoOveruseThreshold("vendorPackageA", 800,
                                                                           300, 500),
                                              toPerStateIoOveruseThreshold("systemPackageB", 1600,
                                                                           600, 1000)};
    updateConfig.safeToKillPackages = toString16Vector({"vendorPackageA", "systemPackageB"});
    updateConfig.vendorPackagePrefixes = toString16Vector({"vendorPackage"});
    updateConfig.categorySpecificThresholds = {toPerStateIoOveruseThreshold("MAPS", 600, 400, 1000),
                                               toPerStateIoOveruseThreshold("MEDIA", 1200, 800,
                                                                            1500)};
    updateConfig.systemWideThresholds = {toIoOveruseAlertThreshold(5, 200),
                                         toIoOveruseAlertThreshold(30, 40000)};

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update(ComponentType::THIRD_PARTY, updateConfig));

    std::vector<PackageConfigTest> packageConfigTests =
            {{.packageInfo = constructPackageInfo("vendorPackageA", ComponentType::THIRD_PARTY,
                                                  ApplicationCategoryType::MAPS),
              .expectedThreshold = updateConfig.componentLevelThresholds.perStateWriteBytes,
              .expectedIsSafeToKill = true},
             {.packageInfo = constructPackageInfo("systemPackageB", ComponentType::SYSTEM,
                                                  ApplicationCategoryType::MAPS),
              .expectedThreshold = defaultThreshold().perStateWriteBytes,
              .expectedIsSafeToKill = false}};

    for (const auto& test : packageConfigTests) {
        const auto actualThreshold = ioOveruseConfigs.fetchThreshold(test.packageInfo);
        EXPECT_THAT(actualThreshold, test.expectedThreshold)
                << test.packageInfo.toString()
                << ": \nExpected threshold: " << test.expectedThreshold.toString()
                << "\nActual threshold: " << actualThreshold.toString();
        EXPECT_THAT(ioOveruseConfigs.isSafeToKill(test.packageInfo), test.expectedIsSafeToKill)
                << test.packageInfo.toString() << ": doesn't match expected safe-to-kill value";
    }

    EXPECT_THAT(ioOveruseConfigs.systemWideAlertThresholds(), IsEmpty())
            << "Third-party component config shouldn't update system wide alert thresholds. "
            << "Only system component config should update this";
    EXPECT_THAT(ioOveruseConfigs.vendorPackagePrefixes(), IsEmpty())
            << "Third-party component config shouldn't update vendor package prefixes. Only vendor "
            << "component config should update this";
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
