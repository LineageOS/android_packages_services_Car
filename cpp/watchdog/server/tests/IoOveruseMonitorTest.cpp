/*
 * Copyright 2021 The Android Open Source Project
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

#include "IoOveruseMonitor.h"
#include "MockIoOveruseConfigs.h"
#include "MockPackageInfoResolver.h"
#include "MockProcDiskStats.h"
#include "MockUidIoStats.h"
#include "MockWatchdogServiceHelper.h"

namespace android {
namespace automotive {
namespace watchdog {

constexpr size_t kTestMonitorBufferSize = 3;
constexpr double kTestIoOveruseWarnPercentage = 80;
constexpr std::chrono::seconds kTestMonitorInterval = 5s;
constexpr std::chrono::seconds kTestCollectionInterval = 60s;

using ::android::String16;
using ::android::automotive::watchdog::internal::ApplicationCategoryType;
using ::android::automotive::watchdog::internal::ComponentType;
using ::android::automotive::watchdog::internal::IoOveruseAlertThreshold;
using ::android::automotive::watchdog::internal::PackageIdentifier;
using ::android::automotive::watchdog::internal::PackageInfo;
using ::android::automotive::watchdog::internal::PackageIoOveruseStats;
using ::android::automotive::watchdog::internal::PerStateBytes;
using ::android::automotive::watchdog::internal::UidType;
using ::android::base::Result;
using ::android::binder::Status;
using ::testing::_;
using ::testing::AllOf;
using ::testing::DoAll;
using ::testing::Field;
using ::testing::Return;
using ::testing::ReturnRef;
using ::testing::SaveArg;
using ::testing::UnorderedElementsAre;
using ::testing::Value;

namespace {

IoOveruseAlertThreshold toIoOveruseAlertThreshold(const int64_t durationInSeconds,
                                                  const int64_t writtenBytesPerSecond) {
    IoOveruseAlertThreshold threshold;
    threshold.durationInSeconds = durationInSeconds;
    threshold.writtenBytesPerSecond = writtenBytesPerSecond;
    return threshold;
}

PackageIdentifier constructPackageIdentifier(const char* packageName, const int32_t uid) {
    PackageIdentifier packageIdentifier;
    packageIdentifier.name = String16(String8(packageName));
    packageIdentifier.uid = uid;
    return packageIdentifier;
}

PackageInfo constructPackageInfo(const char* packageName, const int32_t uid,
                                 const UidType uidType) {
    PackageInfo packageInfo;
    packageInfo.packageIdentifier = constructPackageIdentifier(packageName, uid);
    packageInfo.uidType = uidType;
    return packageInfo;
}

PerStateBytes constructPerStateBytes(const int64_t fgBytes, const int64_t bgBytes,
                                     const int64_t gmBytes) {
    PerStateBytes perStateBytes;
    perStateBytes.foregroundBytes = fgBytes;
    perStateBytes.backgroundBytes = bgBytes;
    perStateBytes.garageModeBytes = gmBytes;
    return perStateBytes;
}

PackageIoOveruseStats constructPackageIoOveruseStats(const PackageIdentifier& packageIdentifier,
                                                     bool maybeKilled,
                                                     const PerStateBytes& remaining,
                                                     const PerStateBytes& written,
                                                     const int numOveruses,
                                                     const int periodInDays = 1) {
    PackageIoOveruseStats stats;
    stats.packageIdentifier = packageIdentifier;
    stats.maybeKilledOnOveruse = maybeKilled;
    stats.remainingWriteBytes = remaining;
    stats.periodInDays = periodInDays;
    stats.writtenBytes = written;
    stats.numOveruses = numOveruses;

    return stats;
}

}  // namespace

namespace internal {

class IoOveruseMonitorPeer {
public:
    explicit IoOveruseMonitorPeer(IoOveruseMonitor* ioOveruseMonitor) :
          mIoOveruseMonitor(ioOveruseMonitor) {}

    Result<void> init(const sp<IIoOveruseConfigs>& ioOveruseConfigs,
                      const sp<IPackageInfoResolverInterface>& packageInfoResolver) {
        if (const auto result = mIoOveruseMonitor->init(); !result.ok()) {
            return result;
        }
        mIoOveruseMonitor->mPeriodicMonitorBufferSize = kTestMonitorBufferSize;
        mIoOveruseMonitor->mIoOveruseWarnPercentage = kTestIoOveruseWarnPercentage;
        mIoOveruseMonitor->mIoOveruseConfigs = ioOveruseConfigs;
        mIoOveruseMonitor->mPackageInfoResolver = packageInfoResolver;
        return {};
    }

private:
    IoOveruseMonitor* mIoOveruseMonitor;
};

}  // namespace internal

TEST(IoOveruseMonitorTest, TestOnPeriodicCollection) {
    sp<MockWatchdogServiceHelper> mockWatchdogServiceHelper = new MockWatchdogServiceHelper();
    IoOveruseMonitor ioOveruseMonitor(mockWatchdogServiceHelper);
    internal::IoOveruseMonitorPeer ioOveruseMonitorPeer(&ioOveruseMonitor);
    sp<MockPackageInfoResolver> mockPackageInfoResolver = new MockPackageInfoResolver();
    sp<MockIoOveruseConfigs> mockIoOveruseConfigs = new MockIoOveruseConfigs();
    ioOveruseMonitorPeer.init(mockIoOveruseConfigs, mockPackageInfoResolver);
    std::unordered_map<uid_t, android::automotive::watchdog::internal::PackageInfo>
            packageInfoMapping = {
                    {1001000,
                     constructPackageInfo(
                             /*packageName=*/"system.daemon", /*uid=*/1001000, UidType::NATIVE)},
                    {1112345,
                     constructPackageInfo(
                             /*packageName=*/"com.android.google.package", /*uid=*/1112345,
                             UidType::APPLICATION)},
                    {1212345,
                     constructPackageInfo(
                             /*packageName=*/"com.android.google.package", /*uid=*/1212345,
                             UidType::APPLICATION)},
                    {1113999,
                     constructPackageInfo(
                             /*packageName=*/"com.android.google.package", /*uid=*/1113999,
                             UidType::APPLICATION)},
            };
    ON_CALL(*mockPackageInfoResolver, getPackageInfosForUids(_))
            .WillByDefault(Return(packageInfoMapping));
    mockIoOveruseConfigs->injectThresholds({
            {"system.daemon",
             constructPerStateBytes(/*fgBytes=*/80'000, /*bgBytes=*/40'000, /*gmBytes=*/100'000)},
            {"com.android.google.package",
             constructPerStateBytes(/*fgBytes=*/70'000, /*bgBytes=*/30'000, /*gmBytes=*/100'000)},
    });

    /*
     * Package "system.daemon" (UID: 1001000) exceeds warn threshold percentage of 80% but no
     * warning is issued as it is a native UID.
     */
    sp<MockUidIoStats> mockUidIoStats = new MockUidIoStats();
    mockUidIoStats->expectDeltaStats(
            {{1001000, IoUsage(0, 0, /*fgWrBytes=*/70'000, /*bgWrBytes=*/20'000, 0, 0)},
             {1112345, IoUsage(0, 0, /*fgWrBytes=*/35'000, /*bgWrBytes=*/15'000, 0, 0)},
             {1212345, IoUsage(0, 0, /*fgWrBytes=*/70'000, /*bgWrBytes=*/20'000, 0, 0)}});

    std::vector<PackageIoOveruseStats> actualOverusingAppStats;
    EXPECT_CALL(*mockWatchdogServiceHelper, notifyIoOveruse(_))
            .WillOnce(DoAll(SaveArg<0>(&actualOverusingAppStats), Return(Status::ok())));

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicCollection(0, mockUidIoStats, nullptr, nullptr));
    const auto expectedFirstMatcher = UnorderedElementsAre(
            // Exceeds threshold.
            constructPackageIoOveruseStats(constructPackageIdentifier("com.android.google.package",
                                                                      1212345),
                                           /*maybeKilled=*/true,
                                           /*remaining=*/constructPerStateBytes(0, 10'000, 100'000),
                                           /*written=*/constructPerStateBytes(70'000, 20'000, 0),
                                           /*numOveruses=*/1));
    EXPECT_THAT(actualOverusingAppStats, expectedFirstMatcher);

    // Package "com.android.google.package" for user 11 changed uid from 1112345 to 1113999.
    mockUidIoStats->expectDeltaStats(
            {{1001000, IoUsage(0, 0, /*fgWrBytes=*/40'000, /*bgWrBytes=*/0, 0, 0)},
             {1113999, IoUsage(0, 0, /*fgWrBytes=*/25'000, /*bgWrBytes=*/10'000, 0, 0)},
             {1212345, IoUsage(0, 0, /*fgWrBytes=*/20'000, /*bgWrBytes=*/30'000, 0, 0)}});
    actualOverusingAppStats.clear();
    EXPECT_CALL(*mockWatchdogServiceHelper, notifyIoOveruse(_))
            .WillOnce(DoAll(SaveArg<0>(&actualOverusingAppStats), Return(Status::ok())));

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicCollection(0, mockUidIoStats, nullptr, nullptr));
    const auto expectedSecondMatcher = UnorderedElementsAre(
            // Exceeds warn threshold percentage.
            constructPackageIoOveruseStats(constructPackageIdentifier("com.android.google.package",
                                                                      1113999),
                                           /*maybeKilled=*/true,
                                           /*remaining=*/
                                           constructPerStateBytes(10'000, 5'000, 100'000),
                                           /*written=*/constructPerStateBytes(60'000, 25'000, 0),
                                           /*numOveruses=*/0),
            /*
             * Exceeds threshold.
             * The package was forgiven on previous overuse so the remaining bytes should only
             * reflect the bytes written after the forgiven bytes.
             */
            constructPackageIoOveruseStats(constructPackageIdentifier("com.android.google.package",
                                                                      1212345),
                                           /*maybeKilled=*/true,
                                           /*remaining=*/constructPerStateBytes(50'000, 0, 100'000),
                                           /*written=*/constructPerStateBytes(90'000, 50'000, 0),
                                           /*numOveruses=*/2));
    EXPECT_THAT(actualOverusingAppStats, expectedSecondMatcher);
    /*
     * TODO(b/167240592): Once |IoOveruseMonitor::notifyNativePackages| is implemented, check
     * whether ICarWatchdog is notified.
     */

    /*
     * Current date changed so the daily I/O usage stats should be reset and the below usage
     * shouldn't trigger I/O overuse.
     */
    mockUidIoStats->expectDeltaStats(
            {{1001000, IoUsage(0, 0, /*fgWrBytes=*/78'000, /*bgWrBytes=*/38'000, 0, 0)},
             {1113999, IoUsage(0, 0, /*fgWrBytes=*/55'000, /*bgWrBytes=*/23'000, 0, 0)},
             {1212345, IoUsage(0, 0, /*fgWrBytes=*/55'000, /*bgWrBytes=*/23'000, 0, 0)}});
    actualOverusingAppStats.clear();
    EXPECT_CALL(*mockWatchdogServiceHelper, notifyIoOveruse(_)).Times(0);

    ASSERT_RESULT_OK(
            ioOveruseMonitor.onPeriodicCollection(time(0), mockUidIoStats, nullptr, nullptr));
}

TEST(IoOveruseMonitorTest, TestOnPeriodicMonitor) {
    IoOveruseMonitor ioOveruseMonitor(new MockWatchdogServiceHelper());
    internal::IoOveruseMonitorPeer ioOveruseMonitorPeer(&ioOveruseMonitor);
    sp<MockIoOveruseConfigs> mockIoOveruseConfigs = new MockIoOveruseConfigs();
    ioOveruseMonitorPeer.init(mockIoOveruseConfigs, new MockPackageInfoResolver());

    IIoOveruseConfigs::IoOveruseAlertThresholdSet alertThresholds = {
            toIoOveruseAlertThreshold(
                    /*durationInSeconds=*/10, /*writtenBytesPerSecond=*/15'360),
            toIoOveruseAlertThreshold(
                    /*durationInSeconds=*/17, /*writtenBytesPerSecond=*/10'240),
            toIoOveruseAlertThreshold(
                    /*durationInSeconds=*/23, /*writtenBytesPerSecond=*/7'168),
    };
    ON_CALL(*mockIoOveruseConfigs, systemWideAlertThresholds())
            .WillByDefault(ReturnRef(alertThresholds));

    time_t time = 0;
    const auto nextCollectionTime = [&]() -> time_t {
        time += kTestMonitorInterval.count();
        return time;
    };
    bool isAlertReceived = false;
    const auto alertHandler = [&]() { isAlertReceived = true; };

    // 1st polling is ignored
    sp<MockProcDiskStats> mockProcDiskStats = new MockProcDiskStats();
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).Times(0);

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    EXPECT_FALSE(isAlertReceived) << "Triggered spurious alert because first polling is ignored";

    // 2nd polling - guarded by the heuristic to handle spurious alerting on partially filled buffer
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 70};
    });

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    EXPECT_FALSE(isAlertReceived) << "Triggered spurious alert when not exceeding the threshold";

    // 3rd polling exceeds first threshold
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 90};
    });

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    EXPECT_TRUE(isAlertReceived) << "Failed to trigger alert when exceeding the threshold";

    isAlertReceived = false;

    // 4th polling - guarded by the heuristic to handle spurious alerting on partially filled buffer
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 10};
    });

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    EXPECT_FALSE(isAlertReceived) << "Triggered spurious alert when not exceeding the threshold";

    // 5th polling exceeds second threshold
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 80};
    });

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    EXPECT_TRUE(isAlertReceived) << "Failed to trigger alert when exceeding the threshold";

    isAlertReceived = false;

    // 6th polling exceeds third threshold
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 10};
    });

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    EXPECT_TRUE(isAlertReceived) << "Failed to trigger alert when exceeding the threshold";
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
