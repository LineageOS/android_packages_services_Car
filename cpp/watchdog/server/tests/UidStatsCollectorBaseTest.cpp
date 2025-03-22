/*
 * Copyright 2025 The Android Open Source Project
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

#include "MockPackageInfoResolver.h"
#include "MockUidIoStatsCollector.h"
#include "PackageInfoTestUtils.h"
#include "UidIoStatsCollector.h"
#include "UidStatsCollectorBase.h"

#include <android-base/stringprintf.h>
#include <gmock/gmock.h>
#include <utils/RefBase.h>

#include <inttypes.h>

#include <string>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::internal::PackageInfo;
using ::aidl::android::automotive::watchdog::internal::UidType;
using ::android::base::Error;
using ::android::base::Result;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::testing::AllOf;
using ::testing::Eq;
using ::testing::ExplainMatchResult;
using ::testing::Field;
using ::testing::IsEmpty;
using ::testing::Matcher;
using ::testing::Return;
using ::testing::UnorderedElementsAre;
using ::testing::UnorderedElementsAreArray;

namespace {

std::string toString(const UidBaseStats& uidBaseStats) {
    return StringPrintf("UidBaseStats{packageInfo: %s, ioStats: %s}",
                        uidBaseStats.packageInfo.toString().c_str(),
                        uidBaseStats.ioStats.toString().c_str());
}

std::string toString(const std::vector<UidBaseStats>& uidBaseStats) {
    std::string buffer;
    StringAppendF(&buffer, "{");
    for (const auto& stats : uidBaseStats) {
        StringAppendF(&buffer, "%s\n", toString(stats).c_str());
    }
    StringAppendF(&buffer, "}");
    return buffer;
}

MATCHER_P(UidBaseStatsEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("packageInfo", &UidBaseStats::packageInfo,
                                          PackageInfoEq(expected.packageInfo)),
                                    Field("ioStats", &UidBaseStats::ioStats, Eq(expected.ioStats))),
                              arg, result_listener);
}

std::vector<Matcher<const UidBaseStats&>> UidBaseStatsMatchers(
        const std::vector<UidBaseStats>& uidBaseStats) {
    std::vector<Matcher<const UidBaseStats&>> matchers;
    for (const auto& stats : uidBaseStats) {
        matchers.push_back(UidBaseStatsEq(stats));
    }
    return matchers;
}

std::unordered_map<uid_t, PackageInfo> samplePackageInfoByUid() {
    return {{1001234, constructPackageInfo("system.daemon", 1001234, UidType::NATIVE)},
            {1005678, constructPackageInfo("kitchensink.app", 1005678, UidType::APPLICATION)}};
}

std::unordered_map<uid_t, UidIoStats> sampleUidIoStatsByUid() {
    return {{1001234,
             UidIoStats{/*fgRdBytes=*/3'000, /*bgRdBytes=*/0,
                        /*fgWrBytes=*/500,
                        /*bgWrBytes=*/0, /*fgFsync=*/20,
                        /*bgFsync=*/0}},
            {1005678,
             UidIoStats{/*fgRdBytes=*/30, /*bgRdBytes=*/100,
                        /*fgWrBytes=*/50, /*bgWrBytes=*/200,
                        /*fgFsync=*/45, /*bgFsync=*/60}}};
}

std::vector<UidBaseStats> sampleUidStats() {
    return {{.packageInfo = constructPackageInfo("system.daemon", 1001234, UidType::NATIVE),
             .ioStats = UidIoStats{/*fgRdBytes=*/3'000, /*bgRdBytes=*/0, /*fgWrBytes=*/500,
                                   /*bgWrBytes=*/0, /*fgFsync=*/20, /*bgFsync=*/0}},
            {.packageInfo = constructPackageInfo("kitchensink.app", 1005678, UidType::APPLICATION),
             .ioStats = UidIoStats{/*fgRdBytes=*/30, /*bgRdBytes=*/100, /*fgWrBytes=*/50,
                                   /*bgWrBytes=*/200,
                                   /*fgFsync=*/45, /*bgFsync=*/60}}};
}

}  // namespace

namespace internal {

class UidStatsCollectorBasePeer final : public RefBase {
public:
    explicit UidStatsCollectorBasePeer(sp<UidStatsCollectorBase> collector) :
          mCollectorBase(collector) {}
    ~UidStatsCollectorBasePeer() { mCollectorBase.clear(); }

    void setPackageInfoResolver(
            const std::shared_ptr<PackageInfoResolverInterface>& packageInfoResolver) {
        mCollectorBase->mPackageInfoResolver = packageInfoResolver;
    }

    void setUidIoStatsCollector(const sp<UidIoStatsCollectorInterface>& uidIoStatsCollector) {
        mCollectorBase->mUidIoStatsCollector = uidIoStatsCollector;
    }

private:
    sp<UidStatsCollectorBase> mCollectorBase;
};

}  // namespace internal

class UidStatsCollectorBaseTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mUidStatsCollectorBase = sp<UidStatsCollectorBase>::make();
        mUidStatsCollectorBasePeer =
                sp<internal::UidStatsCollectorBasePeer>::make(mUidStatsCollectorBase);
        mMockPackageInfoResolver = std::make_shared<MockPackageInfoResolver>();
        mMockUidIoStatsCollector = sp<MockUidIoStatsCollector>::make();
        mUidStatsCollectorBasePeer->setPackageInfoResolver(mMockPackageInfoResolver);
        mUidStatsCollectorBasePeer->setUidIoStatsCollector(mMockUidIoStatsCollector);
    }

    virtual void TearDown() {
        mUidStatsCollectorBase.clear();
        mUidStatsCollectorBasePeer.clear();
        mMockPackageInfoResolver.reset();
        mMockUidIoStatsCollector.clear();
    }

    sp<UidStatsCollectorBase> mUidStatsCollectorBase;
    sp<internal::UidStatsCollectorBasePeer> mUidStatsCollectorBasePeer;
    std::shared_ptr<MockPackageInfoResolver> mMockPackageInfoResolver;
    sp<MockUidIoStatsCollector> mMockUidIoStatsCollector;
};

TEST_F(UidStatsCollectorBaseTest, TestInit) {
    EXPECT_CALL(*mMockUidIoStatsCollector, init()).Times(1);

    mUidStatsCollectorBase->init();
}

TEST_F(UidStatsCollectorBaseTest, TestCollect) {
    EXPECT_CALL(*mMockUidIoStatsCollector, enabled()).WillOnce(Return(true));

    EXPECT_CALL(*mMockUidIoStatsCollector, collect()).WillOnce(Return(Result<void>()));

    EXPECT_CALL(*mMockUidIoStatsCollector, latestStats())
            .WillOnce(Return(std::unordered_map<uid_t, UidIoStats>()));

    EXPECT_CALL(*mMockUidIoStatsCollector, deltaStats())
            .WillOnce(Return(std::unordered_map<uid_t, UidIoStats>()));

    ASSERT_RESULT_OK(mUidStatsCollectorBase->collect());
}

TEST_F(UidStatsCollectorBaseTest, TestFailsCollectOnUidIoStatsCollectorError) {
    Result<void> errorResult = Error() << "Failed to collect per-UID I/O stats";
    EXPECT_CALL(*mMockUidIoStatsCollector, collect()).WillOnce(Return(errorResult));

    ASSERT_FALSE(mUidStatsCollectorBase->collect().ok())
            << "Must fail to collect when per-UID I/O stats collector fails";
}

TEST_F(UidStatsCollectorBaseTest, TestCollectLatestStats) {
    const std::unordered_map<uid_t, PackageInfo> packageInfoByUid = samplePackageInfoByUid();
    const std::unordered_map<uid_t, UidIoStats> uidIoStatsByUid = sampleUidIoStatsByUid();

    EXPECT_CALL(*mMockPackageInfoResolver,
                getPackageInfosForUids(UnorderedElementsAre(1001234, 1005678)))
            .WillOnce(Return(packageInfoByUid));
    EXPECT_CALL(*mMockUidIoStatsCollector, latestStats()).WillOnce(Return(uidIoStatsByUid));

    ASSERT_RESULT_OK(mUidStatsCollectorBase->collect());

    const std::vector<UidBaseStats> expected = sampleUidStats();

    auto actual = mUidStatsCollectorBase->latestBaseStats();

    EXPECT_THAT(actual, UnorderedElementsAreArray(UidBaseStatsMatchers(expected)))
            << "Latest UID stats doesn't match.\nExpected: " << toString(expected)
            << "\nActual: " << toString(actual);

    actual = mUidStatsCollectorBase->deltaBaseStats();

    EXPECT_THAT(actual, IsEmpty()) << "Delta UID stats isn't empty.\nActual: " << toString(actual);
}

TEST_F(UidStatsCollectorBaseTest, TestCollectDeltaStats) {
    const std::unordered_map<uid_t, PackageInfo> packageInfoByUid = samplePackageInfoByUid();
    const std::unordered_map<uid_t, UidIoStats> uidIoStatsByUid = sampleUidIoStatsByUid();

    EXPECT_CALL(*mMockPackageInfoResolver,
                getPackageInfosForUids(UnorderedElementsAre(1001234, 1005678)))
            .WillOnce(Return(packageInfoByUid));
    EXPECT_CALL(*mMockUidIoStatsCollector, deltaStats()).WillOnce(Return(uidIoStatsByUid));

    ASSERT_RESULT_OK(mUidStatsCollectorBase->collect());

    const std::vector<UidBaseStats> expected = sampleUidStats();

    auto actual = mUidStatsCollectorBase->deltaBaseStats();

    EXPECT_THAT(actual, UnorderedElementsAreArray(UidBaseStatsMatchers(expected)))
            << "Delta UID stats doesn't match.\nExpected: " << toString(expected)
            << "\nActual: " << toString(actual);

    actual = mUidStatsCollectorBase->latestBaseStats();

    EXPECT_THAT(actual, IsEmpty()) << "Latest UID stats isn't empty.\nActual: " << toString(actual);
}

TEST_F(UidStatsCollectorBaseTest, TestCollectDeltaStatsWithMissingUidIoStats) {
    const std::unordered_map<uid_t, PackageInfo> packageInfoByUid = samplePackageInfoByUid();
    std::unordered_map<uid_t, UidIoStats> uidIoStatsByUid = sampleUidIoStatsByUid();
    uidIoStatsByUid.erase(1001234);

    EXPECT_CALL(*mMockPackageInfoResolver, getPackageInfosForUids(UnorderedElementsAre(1005678)))
            .WillOnce(Return(packageInfoByUid));
    EXPECT_CALL(*mMockUidIoStatsCollector, deltaStats()).WillOnce(Return(uidIoStatsByUid));

    ASSERT_RESULT_OK(mUidStatsCollectorBase->collect());

    std::vector<UidBaseStats> expected = sampleUidStats();
    expected.erase(expected.begin());

    auto actual = mUidStatsCollectorBase->deltaBaseStats();

    EXPECT_THAT(actual, UnorderedElementsAreArray(UidBaseStatsMatchers(expected)))
            << "Delta UID stats doesn't match.\nExpected: " << toString(expected)
            << "\nActual: " << toString(actual);

    actual = mUidStatsCollectorBase->latestBaseStats();

    EXPECT_THAT(actual, IsEmpty()) << "Latest UID stats isn't empty.\nActual: " << toString(actual);
}

TEST_F(UidStatsCollectorBaseTest, TestCollectDeltaStatsWithMissingPackageInfo) {
    std::unordered_map<uid_t, PackageInfo> packageInfoByUid = samplePackageInfoByUid();
    packageInfoByUid.erase(1001234);
    const std::unordered_map<uid_t, UidIoStats> uidIoStatsByUid = sampleUidIoStatsByUid();

    EXPECT_CALL(*mMockPackageInfoResolver,
                getPackageInfosForUids(UnorderedElementsAre(1001234, 1005678)))
            .WillOnce(Return(packageInfoByUid));
    EXPECT_CALL(*mMockUidIoStatsCollector, deltaStats()).WillOnce(Return(uidIoStatsByUid));

    ASSERT_RESULT_OK(mUidStatsCollectorBase->collect());

    std::vector<UidBaseStats> expected = sampleUidStats();
    expected[0].packageInfo = constructPackageInfo("", 1001234);

    auto actual = mUidStatsCollectorBase->deltaBaseStats();

    EXPECT_THAT(actual, UnorderedElementsAreArray(UidBaseStatsMatchers(expected)))
            << "Delta UID stats doesn't match.\nExpected: " << toString(expected)
            << "\nActual: " << toString(actual);

    actual = mUidStatsCollectorBase->latestBaseStats();

    EXPECT_THAT(actual, IsEmpty()) << "Latest UID stats isn't empty.\nActual: " << toString(actual);
}

TEST_F(UidStatsCollectorBaseTest, TestUidStatsHasPackageInfo) {
    std::unordered_map<uid_t, PackageInfo> packageInfoByUid = samplePackageInfoByUid();
    packageInfoByUid.erase(1001234);
    const std::unordered_map<uid_t, UidIoStats> uidIoStatsByUid = sampleUidIoStatsByUid();

    EXPECT_CALL(*mMockPackageInfoResolver,
                getPackageInfosForUids(UnorderedElementsAre(1001234, 1005678)))
            .WillOnce(Return(packageInfoByUid));
    EXPECT_CALL(*mMockUidIoStatsCollector, deltaStats()).WillOnce(Return(uidIoStatsByUid));

    ASSERT_RESULT_OK(mUidStatsCollectorBase->collect());

    const auto actual = mUidStatsCollectorBase->deltaBaseStats();

    EXPECT_EQ(actual.size(), static_cast<size_t>(2));
    for (const auto stats : actual) {
        if (stats.packageInfo.packageIdentifier.uid == 1001234) {
            EXPECT_FALSE(stats.hasPackageInfo())
                    << "Stats without package info should return false";
        } else if (stats.packageInfo.packageIdentifier.uid == 1005678) {
            EXPECT_TRUE(stats.hasPackageInfo()) << "Stats without package info should return true";
        } else {
            FAIL() << "Unexpected uid " << stats.packageInfo.packageIdentifier.uid;
        }
    }
}

TEST_F(UidStatsCollectorBaseTest, TestUidStatsGenericPackageName) {
    std::unordered_map<uid_t, PackageInfo> packageInfoByUid = samplePackageInfoByUid();
    packageInfoByUid.erase(1001234);
    const std::unordered_map<uid_t, UidIoStats> uidIoStatsByUid = sampleUidIoStatsByUid();

    EXPECT_CALL(*mMockPackageInfoResolver,
                getPackageInfosForUids(UnorderedElementsAre(1001234, 1005678)))
            .WillOnce(Return(packageInfoByUid));
    EXPECT_CALL(*mMockUidIoStatsCollector, deltaStats()).WillOnce(Return(uidIoStatsByUid));

    ASSERT_RESULT_OK(mUidStatsCollectorBase->collect());

    const auto actual = mUidStatsCollectorBase->deltaBaseStats();

    EXPECT_EQ(actual.size(), static_cast<size_t>(2));
    for (const auto stats : actual) {
        if (stats.packageInfo.packageIdentifier.uid == 1001234) {
            EXPECT_EQ(stats.genericPackageName(), "1001234")
                    << "Stats without package info should return UID as package name";
        } else if (stats.packageInfo.packageIdentifier.uid == 1005678) {
            EXPECT_EQ(stats.genericPackageName(), "kitchensink.app")
                    << "Stats with package info should return corresponding package name";
        } else {
            FAIL() << "Unexpected uid " << stats.packageInfo.packageIdentifier.uid;
        }
    }
}

TEST_F(UidStatsCollectorBaseTest, TestUidStatsUid) {
    std::unordered_map<uid_t, PackageInfo> packageInfoByUid = samplePackageInfoByUid();
    packageInfoByUid.erase(1001234);
    const std::unordered_map<uid_t, UidIoStats> uidIoStatsByUid = sampleUidIoStatsByUid();

    EXPECT_CALL(*mMockPackageInfoResolver,
                getPackageInfosForUids(UnorderedElementsAre(1001234, 1005678)))
            .WillOnce(Return(packageInfoByUid));
    EXPECT_CALL(*mMockUidIoStatsCollector, deltaStats()).WillOnce(Return(uidIoStatsByUid));

    ASSERT_RESULT_OK(mUidStatsCollectorBase->collect());

    const auto actual = mUidStatsCollectorBase->deltaBaseStats();

    for (const auto stats : actual) {
        EXPECT_EQ(stats.uid(), static_cast<uid_t>(stats.packageInfo.packageIdentifier.uid));
    }
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
