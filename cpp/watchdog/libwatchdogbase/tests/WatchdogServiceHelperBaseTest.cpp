/**
 * Copyright (c) 2025, The Android Open Source Project
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

#include "MockAIBinderDeathRegistrationWrapper.h"
#include "MockCarWatchdogServiceForSystem.h"
#include "PackageInfoTestUtils.h"
#include "WatchdogServiceHelperBase.h"

#include <binder/IBinder.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <utils/RefBase.h>

namespace android {
namespace automotive {
namespace watchdog {

namespace {

using ::aidl::android::automotive::watchdog::internal::ApplicationCategoryType;
using ::aidl::android::automotive::watchdog::internal::ComponentType;
using ::aidl::android::automotive::watchdog::internal::PackageInfo;
using ::aidl::android::automotive::watchdog::internal::PackageIoOveruseStats;
using ::aidl::android::automotive::watchdog::internal::ResourceOveruseStats;
using ::aidl::android::automotive::watchdog::internal::ResourceStats;
using ::aidl::android::automotive::watchdog::internal::UidType;
using ::android::RefBase;
using ::android::sp;
using ::ndk::ScopedAStatus;
using ::ndk::SharedRefBase;
using ::testing::_;
using ::testing::ByMove;
using ::testing::DoAll;
using ::testing::Eq;
using ::testing::IsEmpty;
using ::testing::Return;
using ::testing::SetArgPointee;
using ::testing::UnorderedElementsAreArray;

constexpr const char kFailOnNoCarWatchdogServiceMessage[] =
        "should fail when no car watchdog service registered with the helper";
constexpr const char kFailOnCarWatchdogServiceErrMessage[] =
        "should fail when car watchdog service API return error";

}  // namespace

namespace internal {

class WatchdogServiceHelperBasePeer : public RefBase {
public:
    explicit WatchdogServiceHelperBasePeer(const sp<WatchdogServiceHelperBase>& helper) :
          mWatchdogServiceHelperBase(helper) {}
    ~WatchdogServiceHelperBasePeer() { mWatchdogServiceHelperBase.clear(); }

    void init(const sp<AIBinderDeathRegistrationWrapperInterface>& deathRegistrationWrapper) {
        mWatchdogServiceHelperBase->mDeathRegistrationWrapper = deathRegistrationWrapper;
    }

    void terminate() { mWatchdogServiceHelperBase->terminate(); }

private:
    sp<WatchdogServiceHelperBase> mWatchdogServiceHelperBase;
};

}  // namespace internal

class WatchdogServiceHelperBaseTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mMockDeathRegistrationWrapper = sp<MockAIBinderDeathRegistrationWrapper>::make();
        mWatchdogServiceHelperBase = sp<WatchdogServiceHelperBase>::make();
        mWatchdogServiceHelperBasePeer =
                sp<internal::WatchdogServiceHelperBasePeer>::make(mWatchdogServiceHelperBase);
        mMockCarWatchdogServiceForSystem = SharedRefBase::make<MockCarWatchdogServiceForSystem>();

        mWatchdogServiceHelperBasePeer->init(mMockDeathRegistrationWrapper);
    }

    virtual void TearDown() {
        if (mWatchdogServiceHelperBase->isServiceConnected()) {
            expectUnlinkToDeath(mMockCarWatchdogServiceForSystem->asBinder().get(),
                                ScopedAStatus::ok());
        }
        mWatchdogServiceHelperBasePeer->terminate();
        mWatchdogServiceHelperBasePeer.clear();
        mWatchdogServiceHelperBase.clear();

        mMockDeathRegistrationWrapper.clear();
        mMockCarWatchdogServiceForSystem.reset();
        mWatchdogServiceHelperBasePeer.clear();
    }

    void registerCarWatchdogService() {
        expectLinkToDeath(mMockCarWatchdogServiceForSystem->asBinder().get(), ScopedAStatus::ok());

        auto status = mWatchdogServiceHelperBase->registerService(mMockCarWatchdogServiceForSystem);

        ASSERT_TRUE(status.isOk()) << status.getMessage();
        ASSERT_TRUE(mWatchdogServiceHelperBase->isServiceConnected());
    }

    void expectLinkToDeath(AIBinder* aiBinder, ndk::ScopedAStatus expectedStatus) {
        EXPECT_CALL(*mMockDeathRegistrationWrapper,
                    linkToDeath(Eq(aiBinder), _, static_cast<void*>(aiBinder)))
                .WillOnce(Return(ByMove(std::move(expectedStatus))));
    }

    void expectUnlinkToDeath(AIBinder* aiBinder, ndk::ScopedAStatus expectedStatus) {
        EXPECT_CALL(*mMockDeathRegistrationWrapper,
                    unlinkToDeath(Eq(aiBinder), _, static_cast<void*>(aiBinder)))
                .WillOnce(Return(ByMove(std::move(expectedStatus))));
    }

    void expectNoLinkToDeath(AIBinder* aiBinder) {
        EXPECT_CALL(*mMockDeathRegistrationWrapper,
                    linkToDeath(Eq(aiBinder), _, static_cast<void*>(aiBinder)))
                .Times(0);
    }

    void expectNoUnlinkToDeath(AIBinder* aiBinder) {
        EXPECT_CALL(*mMockDeathRegistrationWrapper,
                    unlinkToDeath(Eq(aiBinder), _, static_cast<void*>(aiBinder)))
                .Times(0);
    }

    sp<WatchdogServiceHelperBase> mWatchdogServiceHelperBase;
    sp<MockAIBinderDeathRegistrationWrapper> mMockDeathRegistrationWrapper;
    std::shared_ptr<MockCarWatchdogServiceForSystem> mMockCarWatchdogServiceForSystem;
    sp<internal::WatchdogServiceHelperBasePeer> mWatchdogServiceHelperBasePeer;
};

TEST_F(WatchdogServiceHelperBaseTest, TestRegisterService) {
    auto binder = mMockCarWatchdogServiceForSystem->asBinder();

    expectLinkToDeath(binder.get(), ScopedAStatus::ok());

    auto status = mWatchdogServiceHelperBase->registerService(mMockCarWatchdogServiceForSystem);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_TRUE(mWatchdogServiceHelperBase->isServiceConnected());

    expectNoLinkToDeath(binder.get());

    status = mWatchdogServiceHelperBase->registerService(mMockCarWatchdogServiceForSystem);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_TRUE(mWatchdogServiceHelperBase->isServiceConnected());
}

TEST_F(WatchdogServiceHelperBaseTest, TestErrorOnRegisterServiceWithDeadBinder) {
    auto binder = mMockCarWatchdogServiceForSystem->asBinder();
    expectLinkToDeath(binder.get(), ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED));

    ASSERT_FALSE(
            mWatchdogServiceHelperBase->registerService(mMockCarWatchdogServiceForSystem).isOk())
            << "Failed to return error on register service with dead binder";
    ASSERT_FALSE(mWatchdogServiceHelperBase->isServiceConnected());
}

TEST_F(WatchdogServiceHelperBaseTest, TestUnregisterService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    auto binder = mMockCarWatchdogServiceForSystem->asBinder();
    expectUnlinkToDeath(binder.get(), ScopedAStatus::ok());

    auto status = mWatchdogServiceHelperBase->unregisterService(mMockCarWatchdogServiceForSystem);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_FALSE(mWatchdogServiceHelperBase->isServiceConnected());

    expectNoUnlinkToDeath(binder.get());

    ASSERT_FALSE(
            mWatchdogServiceHelperBase->unregisterService(mMockCarWatchdogServiceForSystem).isOk())
            << "Unregistering an unregistered service should return an error";
}

TEST_F(WatchdogServiceHelperBaseTest, TestHandleBinderDeath) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    auto binder = mMockCarWatchdogServiceForSystem->asBinder();

    mWatchdogServiceHelperBase->handleBinderDeath(static_cast<void*>(binder.get()));

    ASSERT_FALSE(mWatchdogServiceHelperBase->isServiceConnected());

    ASSERT_FALSE(
            mWatchdogServiceHelperBase->unregisterService(mMockCarWatchdogServiceForSystem).isOk())
            << "Unregistering a dead service should return an error";
}

TEST_F(WatchdogServiceHelperBaseTest, TestGetPackageInfosForUids) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    std::vector<int32_t> uids = {1000};
    std::vector<std::string> prefixesStr = {"vendor.package"};
    std::vector<PackageInfo> expectedPackageInfo{
            constructPackageInfo("vendor.package.A", 120000, UidType::NATIVE, ComponentType::VENDOR,
                                 ApplicationCategoryType::OTHERS),
            constructPackageInfo("third_party.package.B", 130000, UidType::APPLICATION,
                                 ComponentType::THIRD_PARTY, ApplicationCategoryType::OTHERS),
    };

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, getPackageInfosForUids(uids, prefixesStr, _))
            .WillOnce(DoAll(SetArgPointee<2>(expectedPackageInfo),
                            Return(ByMove(ScopedAStatus::ok()))));

    std::vector<PackageInfo> actualPackageInfo;
    auto status = mWatchdogServiceHelperBase->getPackageInfosForUids(uids, prefixesStr,
                                                                     &actualPackageInfo);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    EXPECT_THAT(actualPackageInfo, UnorderedElementsAreArray(expectedPackageInfo));
}

TEST_F(WatchdogServiceHelperBaseTest,
       TestErrorOnGetPackageInfosForUidsWithNoCarWatchdogServiceRegistered) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, getPackageInfosForUids(_, _, _)).Times(0);

    std::vector<int32_t> uids;
    std::vector<std::string> prefixes;
    std::vector<PackageInfo> actualPackageInfo;
    auto status =
            mWatchdogServiceHelperBase->getPackageInfosForUids(uids, prefixes, &actualPackageInfo);

    ASSERT_FALSE(status.isOk()) << "getPackageInfosForUids " << kFailOnNoCarWatchdogServiceMessage;
    EXPECT_THAT(actualPackageInfo, IsEmpty());
}

TEST_F(WatchdogServiceHelperBaseTest,
       TestErrorOnGetPackageInfosForUidsWithErrorStatusFromCarWatchdogService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, getPackageInfosForUids(_, _, _))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    std::vector<int32_t> uids;
    std::vector<std::string> prefixes;
    std::vector<PackageInfo> actualPackageInfo;
    auto status =
            mWatchdogServiceHelperBase->getPackageInfosForUids(uids, prefixes, &actualPackageInfo);

    ASSERT_FALSE(status.isOk()) << "getPackageInfosForUids " << kFailOnCarWatchdogServiceErrMessage;
    ASSERT_TRUE(actualPackageInfo.empty());
}

TEST_F(WatchdogServiceHelperBaseTest, TestResetResourceOveruseStats) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    std::vector<std::string> packageNames = {"system.daemon"};
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, resetResourceOveruseStats(packageNames))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogServiceHelperBase->resetResourceOveruseStats(packageNames);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogServiceHelperBaseTest,
       TestErrorsOnResetResourceOveruseStatsWithNoCarWatchdogServiceRegistered) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, resetResourceOveruseStats(_)).Times(0);

    ASSERT_FALSE(mWatchdogServiceHelperBase->resetResourceOveruseStats({}).isOk())
            << "resetResourceOveruseStats " << kFailOnNoCarWatchdogServiceMessage;
}

TEST_F(WatchdogServiceHelperBaseTest,
       TestErrorsOnResetResourceOveruseStatsWithErrorStatusFromCarWatchdogService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, resetResourceOveruseStats(_))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    ASSERT_FALSE(mWatchdogServiceHelperBase->resetResourceOveruseStats({}).isOk())
            << "resetResourceOveruseStats " << kFailOnCarWatchdogServiceErrMessage;
}

TEST_F(WatchdogServiceHelperBaseTest, TestRequestTodayIoUsageStats) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, requestTodayIoUsageStats())
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogServiceHelperBase->requestTodayIoUsageStats();

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogServiceHelperBaseTest,
       TestErrorOnRequestTodayIoUsageStatsWithNoCarWatchdogServiceRegistered) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, requestTodayIoUsageStats()).Times(0);

    ASSERT_FALSE(mWatchdogServiceHelperBase->requestTodayIoUsageStats().isOk())
            << "requestTodayIoUsageStats " << kFailOnNoCarWatchdogServiceMessage;
}

TEST_F(WatchdogServiceHelperBaseTest,
       TestErrorOnRequestTodayIoUsageStatsWithErrorStatusFromCarWatchdogService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, requestTodayIoUsageStats())
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    ASSERT_FALSE(mWatchdogServiceHelperBase->requestTodayIoUsageStats().isOk())
            << "requestTodayIoUsageStats " << kFailOnCarWatchdogServiceErrMessage;
}

TEST_F(WatchdogServiceHelperBaseTest, TestOnLatestResourceStats) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    PackageIoOveruseStats stats;
    stats.uid = 101000;
    stats.ioOveruseStats.killableOnOveruse = true;
    stats.ioOveruseStats.startTime = 99898;
    stats.ioOveruseStats.durationInSeconds = 12345;
    stats.ioOveruseStats.totalOveruses = 10;
    stats.shouldNotify = true;
    std::vector<PackageIoOveruseStats> expectedIoOveruseStats = {stats};

    std::vector<ResourceStats> expectedResourceStats;
    expectedResourceStats.push_back({
            .resourceOveruseStats = std::make_optional<ResourceOveruseStats>({
                    .packageIoOveruseStats = expectedIoOveruseStats,
            }),
    });

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, onLatestResourceStats(expectedResourceStats))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogServiceHelperBase->onLatestResourceStats(expectedResourceStats);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogServiceHelperBaseTest,
       TestErrorsOnLatestResourceStatsWithNoCarWatchdogServiceRegistered) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, onLatestResourceStats(_)).Times(0);

    ASSERT_FALSE(mWatchdogServiceHelperBase->onLatestResourceStats({}).isOk())
            << "onLatestResourceStats " << kFailOnNoCarWatchdogServiceMessage;
}

TEST_F(WatchdogServiceHelperBaseTest,
       TestErrorsOnLatestResourceStatsWithErrorStatusFromCarWatchdogService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, onLatestResourceStats(_))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    ASSERT_FALSE(mWatchdogServiceHelperBase->onLatestResourceStats({}).isOk())
            << "onLatestResourceStats " << kFailOnCarWatchdogServiceErrMessage;
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
