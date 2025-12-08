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

#include "MockIoOveruseMonitor.h"
#include "MockWatchdogPerfServiceBase.h"
#include "MockWatchdogServiceHelperBase.h"
#include "WatchdogInternalHandlerBase.h"

#include <aidl/android/automotive/watchdog/internal/GarageMode.h>
#include <aidl/android/automotive/watchdog/internal/UserPackageIoUsageStats.h>
#include <aidl/android/automotive/watchdog/internal/UserState.h>
#include <android-base/result.h>
#include <binder/IPCThreadState.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <private/android_filesystem_config.h>
#include <utils/RefBase.h>

#include <errno.h>
#include <sched.h>
#include <unistd.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::internal::GarageMode;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystemDefault;
using ::aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration;
using ::aidl::android::automotive::watchdog::internal::StateType;
using ::aidl::android::automotive::watchdog::internal::UserPackageIoUsageStats;
using ::aidl::android::automotive::watchdog::internal::UserState;
using ::android::sp;
using ::android::base::Result;
using ::ndk::ScopedAStatus;
using ::ndk::SharedRefBase;
using ::testing::_;
using ::testing::ByMove;
using ::testing::Eq;
using ::testing::Pointer;
using ::testing::Return;

namespace {

constexpr const char kFailOnNonSystemCallingUidMessage[] =
        "should fail with non-system calling uid";
constexpr const char kFailOnWatchdogServiceHelperErrMessage[] =
        "should fail on watchdog service helper error";

class ScopedChangeCallingUid final : public RefBase {
public:
    explicit ScopedChangeCallingUid(uid_t uid) {
        mCallingUid = IPCThreadState::self()->getCallingUid();
        mCallingPid = IPCThreadState::self()->getCallingPid();
        if (mCallingUid == uid) {
            return;
        }
        mChangedUid = uid;
        int64_t token = (static_cast<int64_t>(mChangedUid) << 32) | mCallingPid;
        IPCThreadState::self()->restoreCallingIdentity(token);
    }
    ~ScopedChangeCallingUid() {
        if (mCallingUid == mChangedUid) {
            return;
        }
        int64_t token = (static_cast<int64_t>(mCallingUid) << 32) | mCallingPid;
        IPCThreadState::self()->restoreCallingIdentity(token);
    }

private:
    uid_t mCallingUid;
    uid_t mChangedUid;
    pid_t mCallingPid;
};

}  // namespace

class WatchdogInternalHandlerBaseTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mMockWatchdogPerfServiceBase = sp<MockWatchdogPerfServiceBase>::make();
        mMockWatchdogServiceHelperBase = sp<MockWatchdogServiceHelperBase>::make();
        mMockIoOveruseMonitor = sp<MockIoOveruseMonitor>::make();
        mWatchdogInternalHandlerBase =
                SharedRefBase::make<WatchdogInternalHandlerBase>(mMockWatchdogServiceHelperBase,
                                                                 mMockWatchdogPerfServiceBase,
                                                                 mMockIoOveruseMonitor);
    }
    virtual void TearDown() {
        mMockWatchdogServiceHelperBase.clear();
        mMockWatchdogPerfServiceBase.clear();
        mMockIoOveruseMonitor.clear();
        mWatchdogInternalHandlerBase.reset();
        mScopedChangeCallingUid.clear();
    }

    // Sets calling UID to imitate System's process.
    void setSystemCallingUid() {
        mScopedChangeCallingUid = sp<ScopedChangeCallingUid>::make(AID_SYSTEM);
    }

    sp<MockWatchdogServiceHelperBase> mMockWatchdogServiceHelperBase;
    sp<MockWatchdogPerfServiceBase> mMockWatchdogPerfServiceBase;
    sp<MockIoOveruseMonitor> mMockIoOveruseMonitor;
    std::shared_ptr<WatchdogInternalHandlerBase> mWatchdogInternalHandlerBase;
    sp<ScopedChangeCallingUid> mScopedChangeCallingUid;
};

TEST_F(WatchdogInternalHandlerBaseTest, TestInit) {
    std::shared_ptr<WatchdogInternalHandlerBase> internalHandlerBase = SharedRefBase::make<
            WatchdogInternalHandlerBase>(sp<MockWatchdogServiceHelperBase>::make(),
                                         sp<MockWatchdogPerfServiceBase>::make(),
                                         sp<MockIoOveruseMonitor>::make());

    ASSERT_RESULT_OK(internalHandlerBase->init());

    ASSERT_NE(internalHandlerBase->mWatchdogServiceHelperBase, nullptr);
    ASSERT_NE(internalHandlerBase->mIoOveruseMonitor, nullptr);
    ASSERT_NE(internalHandlerBase->mWatchdogPerfServiceBase, nullptr);
}

TEST_F(WatchdogInternalHandlerBaseTest, TestErrorOnInitWithNullServiceInstances) {
    auto mockWatchdogPerfServiceBase = sp<MockWatchdogPerfServiceBase>::make();
    auto mockWatchdogServiceHelperBase = sp<MockWatchdogServiceHelperBase>::make();
    auto mockIoOveruseMonitor = sp<MockIoOveruseMonitor>::make();
    std::shared_ptr<WatchdogInternalHandlerBase> internalHandlerBase =
            SharedRefBase::make<WatchdogInternalHandlerBase>(nullptr, mockWatchdogPerfServiceBase,
                                                             mockIoOveruseMonitor);

    EXPECT_FALSE(internalHandlerBase->init().ok())
            << "No error returned on nullptr watchdog service helper";
    internalHandlerBase.reset();

    internalHandlerBase =
            SharedRefBase::make<WatchdogInternalHandlerBase>(mockWatchdogServiceHelperBase, nullptr,
                                                             mockIoOveruseMonitor);

    EXPECT_FALSE(internalHandlerBase->init().ok())
            << "No error returned on nullptr watchdog performance service";
    internalHandlerBase.reset();

    internalHandlerBase =
            SharedRefBase::make<WatchdogInternalHandlerBase>(mockWatchdogServiceHelperBase,
                                                             mockWatchdogPerfServiceBase, nullptr);

    EXPECT_FALSE(internalHandlerBase->init().ok())
            << "No error returned on nullptr I/O overuse monitor";
    internalHandlerBase.reset();

    internalHandlerBase =
            SharedRefBase::make<WatchdogInternalHandlerBase>(nullptr, nullptr, nullptr);

    EXPECT_FALSE(internalHandlerBase->init().ok()) << "No error returned on null services";
    internalHandlerBase.reset();
}

TEST_F(WatchdogInternalHandlerBaseTest, TestTerminate) {
    ASSERT_NE(mWatchdogInternalHandlerBase->mWatchdogServiceHelperBase, nullptr);
    ASSERT_NE(mWatchdogInternalHandlerBase->mWatchdogPerfServiceBase, nullptr);
    ASSERT_NE(mWatchdogInternalHandlerBase->mIoOveruseMonitor, nullptr);

    mWatchdogInternalHandlerBase->terminate();

    ASSERT_EQ(mWatchdogInternalHandlerBase->mWatchdogServiceHelperBase, nullptr);
    ASSERT_EQ(mWatchdogInternalHandlerBase->mWatchdogPerfServiceBase, nullptr);
    ASSERT_EQ(mWatchdogInternalHandlerBase->mIoOveruseMonitor, nullptr);
}

TEST_F(WatchdogInternalHandlerBaseTest, TestDump) {
    ASSERT_EQ(mWatchdogInternalHandlerBase->dump(-1, /*args=*/nullptr, /*numArgs=*/0), OK);
}

TEST_F(WatchdogInternalHandlerBaseTest, TestRegisterCarWatchdogService) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockIoOveruseMonitor, isInitialized()).WillOnce(Return(false));
    EXPECT_CALL(*mMockWatchdogPerfServiceBase, registerIoOveruseMonitor(Eq(mMockIoOveruseMonitor)))
            .WillOnce(Return(Result<void>()));
    EXPECT_CALL(*mMockWatchdogPerfServiceBase, onCarWatchdogServiceRegistered()).Times(1);

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, registerService(service))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogInternalHandlerBase->registerCarWatchdogService(service);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerBaseTest,
       TestErrorOnRegisterCarWatchdogServiceWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, registerService(_)).Times(0);

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    ASSERT_FALSE(mWatchdogInternalHandlerBase->registerCarWatchdogService(service).isOk())
            << "registerCarWatchdogService " << kFailOnNonSystemCallingUidMessage;
}

TEST_F(WatchdogInternalHandlerBaseTest,
       TestErrorOnRegisterCarWatchdogServiceWithWatchdogServiceHelperError) {
    setSystemCallingUid();

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, registerService(service))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    ASSERT_FALSE(mWatchdogInternalHandlerBase->registerCarWatchdogService(service).isOk())
            << "registerCarWatchdogService " << kFailOnWatchdogServiceHelperErrMessage;
}

TEST_F(WatchdogInternalHandlerBaseTest, TestUnregisterCarWatchdogService) {
    setSystemCallingUid();

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, unregisterService(service))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogInternalHandlerBase->unregisterCarWatchdogService(service);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerBaseTest,
       TestErrorOnUnregisterCarWatchdogServiceWithNonSystemCallingUid) {
    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, unregisterService(service)).Times(0);

    ASSERT_FALSE(mWatchdogInternalHandlerBase->unregisterCarWatchdogService(service).isOk())
            << "unregisterCarWatchdogService " << kFailOnNonSystemCallingUidMessage;
}
TEST_F(WatchdogInternalHandlerBaseTest,
       TestErrorOnUnregisterCarWatchdogServiceWithWatchdogServiceHelperError) {
    setSystemCallingUid();

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, unregisterService(service))
            .WillOnce(Return(
                    ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                                                       "Illegal argument"))));

    ASSERT_FALSE(mWatchdogInternalHandlerBase->unregisterCarWatchdogService(service).isOk())
            << "unregisterCarWatchdogService " << kFailOnWatchdogServiceHelperErrMessage;
}

TEST_F(WatchdogInternalHandlerBaseTest, TestNotifyGarageModeOn) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockWatchdogPerfServiceBase, setSystemState(SystemState::GARAGE_MODE)).Times(1);

    auto status =
            mWatchdogInternalHandlerBase
                    ->notifySystemStateChange(StateType::GARAGE_MODE,
                                              static_cast<int32_t>(GarageMode::GARAGE_MODE_ON), -1);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerBaseTest, TestNotifyGarageModeOff) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockWatchdogPerfServiceBase, setSystemState(SystemState::NORMAL_MODE)).Times(1);

    auto status =
            mWatchdogInternalHandlerBase
                    ->notifySystemStateChange(StateType::GARAGE_MODE,
                                              static_cast<int32_t>(GarageMode::GARAGE_MODE_OFF),
                                              -1);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerBaseTest, TestOnUserStateChangeWithRemovedUser) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockIoOveruseMonitor, removeStatsForUser(/*userId=*/234567));

    StateType type = StateType::USER_STATE;
    auto status =
            mWatchdogInternalHandlerBase
                    ->notifySystemStateChange(type, 234567,
                                              static_cast<int32_t>(UserState::USER_STATE_REMOVED));

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerBaseTest, TestErrorOnOnUserStateChangeWithInvalidArgs) {
    EXPECT_CALL(*mMockIoOveruseMonitor, removeStatsForUser(_)).Times(0);

    StateType type = StateType::USER_STATE;

    ASSERT_FALSE(mWatchdogInternalHandlerBase->notifySystemStateChange(type, 234567, -1).isOk())
            << "notifySystemStateChange should fail with negative user state";

    ASSERT_FALSE(mWatchdogInternalHandlerBase->notifySystemStateChange(type, 234567, 3000).isOk())
            << "notifySystemStateChange should fail with invalid user state";
}

TEST_F(WatchdogInternalHandlerBaseTest, TestErrorOnNotifySystemStateChangeWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockWatchdogPerfServiceBase, setSystemState(_)).Times(0);

    StateType type = StateType::POWER_CYCLE;
    auto status =
            mWatchdogInternalHandlerBase->notifySystemStateChange(type,
                                                                  static_cast<int32_t>(
                                                                          StateType::GARAGE_MODE),
                                                                  -1);

    ASSERT_FALSE(status.isOk()) << "notifySystemStateChange " << kFailOnNonSystemCallingUidMessage;
}

TEST_F(WatchdogInternalHandlerBaseTest, TestUpdateResourceOveruseConfigurations) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockIoOveruseMonitor, updateResourceOveruseConfigurations(_))
            .WillOnce(Return(Result<void>()));

    auto status = mWatchdogInternalHandlerBase->updateResourceOveruseConfigurations(
            std::vector<ResourceOveruseConfiguration>{});

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerBaseTest,
       TestErrorOnUpdateResourceOveruseConfigurationsWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockIoOveruseMonitor, updateResourceOveruseConfigurations(_)).Times(0);

    auto status = mWatchdogInternalHandlerBase->updateResourceOveruseConfigurations(
            std::vector<ResourceOveruseConfiguration>{});

    ASSERT_FALSE(status.isOk()) << "updateResourceOveruseConfigurations "
                                << kFailOnNonSystemCallingUidMessage;
}

TEST_F(WatchdogInternalHandlerBaseTest, TestGetResourceOveruseConfigurations) {
    setSystemCallingUid();

    std::vector<ResourceOveruseConfiguration> configs;
    EXPECT_CALL(*mMockIoOveruseMonitor, getResourceOveruseConfigurations(Pointer(&configs)))
            .WillOnce(Return(Result<void>()));

    auto status = mWatchdogInternalHandlerBase->getResourceOveruseConfigurations(&configs);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerBaseTest,
       TestErrorOnGetResourceOveruseConfigurationsWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockIoOveruseMonitor, getResourceOveruseConfigurations(_)).Times(0);

    std::vector<ResourceOveruseConfiguration> configs;

    ASSERT_FALSE(mWatchdogInternalHandlerBase->getResourceOveruseConfigurations(&configs).isOk())
            << "getResourceOveruseConfigurations " << kFailOnNonSystemCallingUidMessage;
}

TEST_F(WatchdogInternalHandlerBaseTest, TestOnTodayIoUsageStatsFetched) {
    setSystemCallingUid();

    std::vector<UserPackageIoUsageStats> userPackageIoUsageStats = {};
    EXPECT_CALL(*mMockIoOveruseMonitor, onTodayIoUsageStatsFetched(userPackageIoUsageStats))
            .Times(1);

    auto status = mWatchdogInternalHandlerBase->onTodayIoUsageStatsFetched(userPackageIoUsageStats);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerBaseTest,
       TestErrorOnOnTodayIoUsageStatsFetchedWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockIoOveruseMonitor, onTodayIoUsageStatsFetched(_)).Times(0);

    ASSERT_FALSE(mWatchdogInternalHandlerBase->onTodayIoUsageStatsFetched({}).isOk())
            << "onTodayIoUsageStatsFetched " << kFailOnNonSystemCallingUidMessage;
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
