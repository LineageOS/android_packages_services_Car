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

#include "MockAIBinderDeathRegistrationWrapper.h"
#include "MockCarWatchdogServiceForSystem.h"
#include "MockWatchdogProcessService.h"
#include "PackageInfoTestUtils.h"
#include "WatchdogServiceHelper.h"

#include <binder/IBinder.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <utils/RefBase.h>

namespace android {
namespace automotive {
namespace watchdog {

namespace {

using ::aidl::android::automotive::watchdog::TimeoutLength;
using ::aidl::android::automotive::watchdog::internal::ApplicationCategoryType;
using ::aidl::android::automotive::watchdog::internal::ComponentType;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem;
using ::aidl::android::automotive::watchdog::internal::PackageInfo;
using ::aidl::android::automotive::watchdog::internal::PackageIoOveruseStats;
using ::aidl::android::automotive::watchdog::internal::UidType;
using ::aidl::android::automotive::watchdog::internal::UserPackageIoUsageStats;
using ::android::RefBase;
using ::android::sp;
using ::android::base::Error;
using ::android::base::Result;
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

using InternalTimeoutLength = ::aidl::android::automotive::watchdog::internal::TimeoutLength;

UserPackageIoUsageStats sampleUserPackageIoUsageStats(userid_t userId,
                                                      const std::string& packageName) {
    UserPackageIoUsageStats stats;
    stats.userId = userId;
    stats.packageName = packageName;
    stats.ioUsageStats.writtenBytes.foregroundBytes = 100;
    stats.ioUsageStats.writtenBytes.backgroundBytes = 200;
    stats.ioUsageStats.writtenBytes.garageModeBytes = 300;
    stats.ioUsageStats.forgivenWriteBytes.foregroundBytes = 1100;
    stats.ioUsageStats.forgivenWriteBytes.backgroundBytes = 1200;
    stats.ioUsageStats.forgivenWriteBytes.garageModeBytes = 1300;
    stats.ioUsageStats.totalOveruses = 10;
    return stats;
}

}  // namespace

namespace internal {

class WatchdogServiceHelperPeer : public RefBase {
public:
    explicit WatchdogServiceHelperPeer(const sp<WatchdogServiceHelper>& helper) : mHelper(helper) {}
    ~WatchdogServiceHelperPeer() { mHelper.clear(); }

    Result<void> init(
            const sp<WatchdogProcessServiceInterface>& watchdogProcessService,
            const sp<AIBinderDeathRegistrationWrapperInterface>& deathRegistrationWrapper) {
        mHelper->mDeathRegistrationWrapper = deathRegistrationWrapper;
        return mHelper->init(watchdogProcessService);
    }

    void terminate() { mHelper->terminate(); }

private:
    sp<WatchdogServiceHelper> mHelper;
};

}  // namespace internal

class WatchdogServiceHelperTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mMockWatchdogProcessService = sp<MockWatchdogProcessService>::make();
        mMockDeathRegistrationWrapper = sp<MockAIBinderDeathRegistrationWrapper>::make();
        mWatchdogServiceHelper = sp<WatchdogServiceHelper>::make();
        mWatchdogServiceHelperPeer =
                sp<internal::WatchdogServiceHelperPeer>::make(mWatchdogServiceHelper);
        mMockCarWatchdogServiceForSystem = SharedRefBase::make<MockCarWatchdogServiceForSystem>();

        EXPECT_CALL(*mMockWatchdogProcessService, registerWatchdogServiceHelper(_))
                .WillOnce(Return(Result<void>()));
        auto result = mWatchdogServiceHelperPeer->init(mMockWatchdogProcessService,
                                                       mMockDeathRegistrationWrapper);
        ASSERT_RESULT_OK(result);
    }

    virtual void TearDown() {
        if (mWatchdogServiceHelper->isServiceConnected()) {
            expectUnlinkToDeath(mMockCarWatchdogServiceForSystem->asBinder().get(),
                                std::move(ScopedAStatus::ok()));
            EXPECT_CALL(*mMockWatchdogProcessService, unregisterCarWatchdogService(_)).Times(1);
        }
        mWatchdogServiceHelperPeer->terminate();
        mWatchdogServiceHelperPeer.clear();
        mWatchdogServiceHelper.clear();

        mMockWatchdogProcessService.clear();
        mMockDeathRegistrationWrapper.clear();
        mMockCarWatchdogServiceForSystem.reset();
        mWatchdogServiceHelperPeer.clear();
    }

    void registerCarWatchdogService() {
        expectLinkToDeath(mMockCarWatchdogServiceForSystem->asBinder().get(),
                          std::move(ScopedAStatus::ok()));
        EXPECT_CALL(*mMockWatchdogProcessService, registerCarWatchdogService(_))
                .WillOnce(Return(ByMove(ScopedAStatus::ok())));

        auto status = mWatchdogServiceHelper->registerService(mMockCarWatchdogServiceForSystem);

        ASSERT_TRUE(status.isOk()) << status.getMessage();
        ASSERT_TRUE(mWatchdogServiceHelper->isServiceConnected());
    }

    void* getCarWatchdogServiceForSystemCookie() {
        return static_cast<void*>(mMockCarWatchdogServiceForSystem->asBinder().get());
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

    sp<WatchdogServiceHelper> mWatchdogServiceHelper;
    sp<MockWatchdogProcessService> mMockWatchdogProcessService;
    sp<MockAIBinderDeathRegistrationWrapper> mMockDeathRegistrationWrapper;
    std::shared_ptr<MockCarWatchdogServiceForSystem> mMockCarWatchdogServiceForSystem;
    sp<internal::WatchdogServiceHelperPeer> mWatchdogServiceHelperPeer;
};

TEST_F(WatchdogServiceHelperTest, TestInit) {
    sp<WatchdogServiceHelper> helper = sp<WatchdogServiceHelper>::make();
    sp<MockWatchdogProcessService> mockWatchdogProcessService =
            sp<MockWatchdogProcessService>::make();

    EXPECT_CALL(*mockWatchdogProcessService, registerWatchdogServiceHelper(_))
            .WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(helper->init(mockWatchdogProcessService));
}

TEST_F(WatchdogServiceHelperTest, TestErrorOnInitWithErrorFromWatchdogProcessServiceRegistration) {
    sp<WatchdogServiceHelper> helper = sp<WatchdogServiceHelper>::make();
    sp<MockWatchdogProcessService> mockWatchdogProcessService =
            sp<MockWatchdogProcessService>::make();

    EXPECT_CALL(*mockWatchdogProcessService, registerWatchdogServiceHelper(_))
            .WillOnce([](const sp<WatchdogServiceHelperInterface>&) -> Result<void> {
                return Error() << "Failed to register";
            });

    auto result = helper->init(mockWatchdogProcessService);

    ASSERT_FALSE(result.ok()) << "Watchdog service helper init should fail on error from "
                              << "watchdog process service registration error";
}

TEST_F(WatchdogServiceHelperTest, TestErrorOnInitWithNullWatchdogProcessServiceInstance) {
    sp<WatchdogServiceHelper> helper = sp<WatchdogServiceHelper>::make();

    auto result = helper->init(nullptr);

    ASSERT_FALSE(result.ok())
            << "Watchdog service helper init should fail on null watchdog process service instance";
}

TEST_F(WatchdogServiceHelperTest, TestTerminate) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());
    expectUnlinkToDeath(mMockCarWatchdogServiceForSystem->asBinder().get(),
                        std::move(ScopedAStatus::ok()));

    mWatchdogServiceHelper->terminate();

    ASSERT_EQ(mWatchdogServiceHelper->mService, nullptr);
}

TEST_F(WatchdogServiceHelperTest, TestRegisterService) {
    auto binder = mMockCarWatchdogServiceForSystem->asBinder();

    expectLinkToDeath(binder.get(), std::move(ScopedAStatus::ok()));
    EXPECT_CALL(*mMockWatchdogProcessService, registerCarWatchdogService(binder))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogServiceHelper->registerService(mMockCarWatchdogServiceForSystem);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_TRUE(mWatchdogServiceHelper->isServiceConnected());

    expectNoLinkToDeath(binder.get());
    EXPECT_CALL(*mMockWatchdogProcessService, registerCarWatchdogService(_)).Times(0);

    status = mWatchdogServiceHelper->registerService(mMockCarWatchdogServiceForSystem);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_TRUE(mWatchdogServiceHelper->isServiceConnected());
}

TEST_F(WatchdogServiceHelperTest, TestErrorOnRegisterServiceWithBinderDied) {
    auto binder = mMockCarWatchdogServiceForSystem->asBinder();
    expectLinkToDeath(binder.get(),
                      std::move(ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED)));
    EXPECT_CALL(*mMockWatchdogProcessService, registerCarWatchdogService(binder))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterCarWatchdogService(binder)).Times(1);

    ASSERT_FALSE(mWatchdogServiceHelper->registerService(mMockCarWatchdogServiceForSystem).isOk())
            << "Failed to return error on register service with dead binder";
    ASSERT_FALSE(mWatchdogServiceHelper->isServiceConnected());
}

TEST_F(WatchdogServiceHelperTest, TestErrorOnRegisterServiceWithWatchdogProcessServiceError) {
    auto binder = mMockCarWatchdogServiceForSystem->asBinder();
    expectNoLinkToDeath(binder.get());
    expectNoUnlinkToDeath(binder.get());
    EXPECT_CALL(*mMockWatchdogProcessService, registerCarWatchdogService(binder))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE))));

    ASSERT_FALSE(mWatchdogServiceHelper->registerService(mMockCarWatchdogServiceForSystem).isOk())
            << "Failed to return error on error from watchdog process service";
    ASSERT_FALSE(mWatchdogServiceHelper->isServiceConnected());
}

TEST_F(WatchdogServiceHelperTest, TestErrorOnRegisterServiceWithDeadBinder) {
    auto binder = mMockCarWatchdogServiceForSystem->asBinder();
    expectLinkToDeath(binder.get(),
                      std::move(ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED)));
    EXPECT_CALL(*mMockWatchdogProcessService, registerCarWatchdogService(binder))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterCarWatchdogService(binder)).Times(1);

    ASSERT_FALSE(mWatchdogServiceHelper->registerService(mMockCarWatchdogServiceForSystem).isOk())
            << "Failed to return error on register service with dead binder";
    ASSERT_FALSE(mWatchdogServiceHelper->isServiceConnected());
}

TEST_F(WatchdogServiceHelperTest, TestUnregisterService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    auto binder = mMockCarWatchdogServiceForSystem->asBinder();
    expectUnlinkToDeath(binder.get(), std::move(ScopedAStatus::ok()));
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterCarWatchdogService(binder)).Times(1);

    auto status = mWatchdogServiceHelper->unregisterService(mMockCarWatchdogServiceForSystem);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_FALSE(mWatchdogServiceHelper->isServiceConnected());

    expectNoUnlinkToDeath(binder.get());
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterCarWatchdogService(_)).Times(0);

    status = mWatchdogServiceHelper->unregisterService(mMockCarWatchdogServiceForSystem);
    ASSERT_FALSE(status.isOk()) << "Unregistering an unregistered service should return an error:"
                                << status.getMessage();
}

TEST_F(WatchdogServiceHelperTest, TestHandleBinderDeath) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    auto binder = mMockCarWatchdogServiceForSystem->asBinder();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterCarWatchdogService(binder)).Times(1);

    mWatchdogServiceHelper->handleBinderDeath(static_cast<void*>(binder.get()));

    ASSERT_FALSE(mWatchdogServiceHelper->isServiceConnected());

    EXPECT_CALL(*mMockWatchdogProcessService, unregisterCarWatchdogService(_)).Times(0);

    auto status = mWatchdogServiceHelper->unregisterService(mMockCarWatchdogServiceForSystem);
    ASSERT_FALSE(status.isOk()) << "Unregistering a dead service should return an error:"
                                << status.getMessage();
}

TEST_F(WatchdogServiceHelperTest, TestCheckIfAlive) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem,
                checkIfAlive(0, InternalTimeoutLength::TIMEOUT_CRITICAL))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogServiceHelper->checkIfAlive(mMockCarWatchdogServiceForSystem->asBinder(),
                                                       0, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorOnCheckIfAliveWithNotRegisteredCarWatchdogServiceBinder) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, checkIfAlive(_, _)).Times(0);

    auto notRegisteredService = SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    auto status = mWatchdogServiceHelper->checkIfAlive(notRegisteredService->asBinder(), 0,
                                                       TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_FALSE(status.isOk()) << "checkIfAlive should fail when the given car watchdog service"
                                   "binder is not registered with the helper";
}

TEST_F(WatchdogServiceHelperTest, TestErrorOnCheckIfAliveWithNoCarWatchdogServiceRegistered) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, checkIfAlive(_, _)).Times(0);

    auto status = mWatchdogServiceHelper->checkIfAlive(mMockCarWatchdogServiceForSystem->asBinder(),
                                                       0, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_FALSE(status.isOk())
            << "checkIfAlive should fail when no car watchdog service registered with the helper";
}

TEST_F(WatchdogServiceHelperTest, TestErrorOnCheckIfAliveWithErrorStatusFromCarWatchdogService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem,
                checkIfAlive(0, InternalTimeoutLength::TIMEOUT_CRITICAL))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    auto status = mWatchdogServiceHelper->checkIfAlive(mMockCarWatchdogServiceForSystem->asBinder(),
                                                       0, TimeoutLength::TIMEOUT_CRITICAL);
    ASSERT_FALSE(status.isOk())
            << "checkIfAlive should fail when car watchdog service API returns error";
}

TEST_F(WatchdogServiceHelperTest, TestPrepareProcessTermination) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, prepareProcessTermination())
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogServiceHelper->prepareProcessTermination(
            mMockCarWatchdogServiceForSystem->asBinder());

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    ASSERT_FALSE(mWatchdogServiceHelper->isServiceConnected());
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorOnPrepareProcessTerminationWithNotRegisteredCarWatchdogServiceBinder) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, prepareProcessTermination()).Times(0);

    auto notRegisteredService = SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    auto status =
            mWatchdogServiceHelper->prepareProcessTermination(notRegisteredService->asBinder());

    ASSERT_FALSE(status.isOk()) << "prepareProcessTermination should fail when the given car "
                                   "watchdog service binder is not registered with the helper";
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorOnPrepareProcessTerminationWithNoCarWatchdogServiceRegistered) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, prepareProcessTermination()).Times(0);

    auto status = mWatchdogServiceHelper->prepareProcessTermination(
            mMockCarWatchdogServiceForSystem->asBinder());

    ASSERT_FALSE(status.isOk()) << "prepareProcessTermination should fail when no car watchdog "
                                   "service registered with the helper";
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorOnPrepareProcessTerminationWithErrorStatusFromCarWatchdogService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, prepareProcessTermination())
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    auto status = mWatchdogServiceHelper->prepareProcessTermination(
            mMockCarWatchdogServiceForSystem->asBinder());

    ASSERT_FALSE(status.isOk())
            << "prepareProcessTermination should fail when car watchdog service API returns error";
}

TEST_F(WatchdogServiceHelperTest, TestGetPackageInfosForUids) {
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
    auto status =
            mWatchdogServiceHelper->getPackageInfosForUids(uids, prefixesStr, &actualPackageInfo);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    EXPECT_THAT(actualPackageInfo, UnorderedElementsAreArray(expectedPackageInfo));
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorOnGetPackageInfosForUidsWithNoCarWatchdogServiceRegistered) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, getPackageInfosForUids(_, _, _)).Times(0);

    std::vector<int32_t> uids;
    std::vector<std::string> prefixes;
    std::vector<PackageInfo> actualPackageInfo;
    auto status =
            mWatchdogServiceHelper->getPackageInfosForUids(uids, prefixes, &actualPackageInfo);

    ASSERT_FALSE(status.isOk()) << "getPackageInfosForUids should fail when no "
                                   "car watchdog service registered with the helper";
    EXPECT_THAT(actualPackageInfo, IsEmpty());
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorOnGetPackageInfosForUidsWithErrorStatusFromCarWatchdogService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, getPackageInfosForUids(_, _, _))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    std::vector<int32_t> uids;
    std::vector<std::string> prefixes;
    std::vector<PackageInfo> actualPackageInfo;
    auto status =
            mWatchdogServiceHelper->getPackageInfosForUids(uids, prefixes, &actualPackageInfo);

    ASSERT_FALSE(status.isOk()) << "getPackageInfosForUids should fail when car watchdog "
                                   "service API returns error";
    ASSERT_TRUE(actualPackageInfo.empty());
}

TEST_F(WatchdogServiceHelperTest, TestLatestIoOveruseStats) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    PackageIoOveruseStats stats;
    stats.uid = 101000;
    stats.ioOveruseStats.killableOnOveruse = true;
    stats.ioOveruseStats.startTime = 99898;
    stats.ioOveruseStats.durationInSeconds = 12345;
    stats.ioOveruseStats.totalOveruses = 10;
    stats.shouldNotify = true;
    std::vector<PackageIoOveruseStats> expectedIoOveruseStats = {stats};

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, latestIoOveruseStats(expectedIoOveruseStats))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogServiceHelper->latestIoOveruseStats(expectedIoOveruseStats);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorsOnLatestIoOveruseStatsWithNoCarWatchdogServiceRegistered) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, latestIoOveruseStats(_)).Times(0);

    auto status = mWatchdogServiceHelper->latestIoOveruseStats({});

    ASSERT_FALSE(status.isOk()) << "latetstIoOveruseStats should fail when no "
                                   "car watchdog service registered with the helper";
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorsOnLatestIoOveruseStatsWithErrorStatusFromCarWatchdogService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, latestIoOveruseStats(_))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    auto status = mWatchdogServiceHelper->latestIoOveruseStats({});

    ASSERT_FALSE(status.isOk()) << "latetstIoOveruseStats should fail when car watchdog "
                                   "service API returns error";
}

TEST_F(WatchdogServiceHelperTest, TestResetResourceOveruseStats) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    std::vector<std::string> packageNames = {"system.daemon"};
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, resetResourceOveruseStats(packageNames))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogServiceHelper->resetResourceOveruseStats(packageNames);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorsOnResetResourceOveruseStatsWithNoCarWatchdogServiceRegistered) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, resetResourceOveruseStats(_)).Times(0);

    auto status = mWatchdogServiceHelper->resetResourceOveruseStats({});

    ASSERT_FALSE(status.isOk()) << "resetResourceOveruseStats should fail when no "
                                   "car watchdog service registered with the helper";
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorsOnResetResourceOveruseStatsWithErrorStatusFromCarWatchdogService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, resetResourceOveruseStats(_))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    auto status = mWatchdogServiceHelper->resetResourceOveruseStats({});

    ASSERT_FALSE(status.isOk()) << "resetResourceOveruseStats should fail when car watchdog "
                                   "service API returns error";
}

TEST_F(WatchdogServiceHelperTest, TestGetTodayIoUsageStats) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    std::vector<UserPackageIoUsageStats>
            expectedStats{sampleUserPackageIoUsageStats(10, "vendor.package"),
                          sampleUserPackageIoUsageStats(11, "third_party.package")};

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, getTodayIoUsageStats(_))
            .WillOnce(DoAll(SetArgPointee<0>(expectedStats), Return(ByMove(ScopedAStatus::ok()))));

    std::vector<UserPackageIoUsageStats> actualStats;
    auto status = mWatchdogServiceHelper->getTodayIoUsageStats(&actualStats);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    EXPECT_THAT(actualStats, UnorderedElementsAreArray(expectedStats));
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorOnGetTodayIoUsageStatsWithNoCarWatchdogServiceRegistered) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, getTodayIoUsageStats(_)).Times(0);

    std::vector<UserPackageIoUsageStats> actualStats;
    auto status = mWatchdogServiceHelper->getTodayIoUsageStats(&actualStats);

    ASSERT_FALSE(status.isOk()) << "getTodayIoUsageStats should fail when no "
                                   "car watchdog service registered with the helper";
    EXPECT_THAT(actualStats, IsEmpty());
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorOnGetTodayIoUsageStatsWithErrorStatusFromCarWatchdogService) {
    ASSERT_NO_FATAL_FAILURE(registerCarWatchdogService());

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, getTodayIoUsageStats(_))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    std::vector<UserPackageIoUsageStats> actualStats;
    auto status = mWatchdogServiceHelper->getTodayIoUsageStats(&actualStats);

    ASSERT_FALSE(status.isOk()) << "getTodayIoUsageStats should fail when car watchdog "
                                   "service API returns error";
    ASSERT_TRUE(actualStats.empty());
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
