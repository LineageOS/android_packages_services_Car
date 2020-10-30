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

#include "MockIoOveruseMonitor.h"
#include "MockWatchdogPerfService.h"
#include "MockWatchdogProcessService.h"
#include "WatchdogBinderMediator.h"
#include "WatchdogInternalHandler.h"

#include <android-base/result.h>
#include <android/automotive/watchdog/internal/BootPhase.h>
#include <android/automotive/watchdog/internal/PowerCycle.h>
#include <android/automotive/watchdog/internal/UserState.h>
#include <binder/IBinder.h>
#include <binder/IPCThreadState.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>
#include <private/android_filesystem_config.h>
#include <utils/RefBase.h>

#include <errno.h>

namespace android {
namespace automotive {
namespace watchdog {

namespace aawi = android::automotive::watchdog::internal;

using android::sp;
using android::automotive::watchdog::internal::ComponentType;
using android::automotive::watchdog::internal::IoOveruseConfiguration;
using android::base::Result;
using android::binder::Status;
using ::testing::_;
using ::testing::Return;

namespace {

class MockWatchdogBinderMediator : public WatchdogBinderMediator {
public:
    MockWatchdogBinderMediator() {}
    ~MockWatchdogBinderMediator() {}

    MOCK_METHOD(status_t, dump, (int fd, const Vector<String16>& args), (override));
};

class MockICarWatchdogClient : public aawi::ICarWatchdogClient {
public:
    MOCK_METHOD(Status, checkIfAlive, (int32_t sessionId, aawi::TimeoutLength timeout), (override));
    MOCK_METHOD(Status, prepareProcessTermination, (), (override));
    MOCK_METHOD(IBinder*, onAsBinder, (), (override));
};

class MockICarWatchdogMonitor : public aawi::ICarWatchdogMonitor {
public:
    MOCK_METHOD(Status, onClientsNotResponding, (const std::vector<int32_t>& pids), (override));
    MOCK_METHOD(IBinder*, onAsBinder, (), (override));
};

class ScopedChangeCallingUid : public RefBase {
public:
    explicit ScopedChangeCallingUid(uid_t uid) {
        mCallingUid = IPCThreadState::self()->getCallingUid();
        mCallingPid = IPCThreadState::self()->getCallingPid();
        if (mCallingUid == uid) {
            return;
        }
        mChangedUid = uid;
        int64_t token = ((int64_t)mChangedUid << 32) | mCallingPid;
        IPCThreadState::self()->restoreCallingIdentity(token);
    }
    ~ScopedChangeCallingUid() {
        if (mCallingUid == mChangedUid) {
            return;
        }
        int64_t token = ((int64_t)mCallingUid << 32) | mCallingPid;
        IPCThreadState::self()->restoreCallingIdentity(token);
    }

private:
    uid_t mCallingUid;
    uid_t mChangedUid;
    pid_t mCallingPid;
};

}  // namespace

class WatchdogInternalHandlerTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mMockWatchdogProcessService = new MockWatchdogProcessService();
        mMockWatchdogPerfService = new MockWatchdogPerfService();
        mMockIoOveruseMonitor = new MockIoOveruseMonitor();
        mMockWatchdogBinderMediator = new MockWatchdogBinderMediator();
        mWatchdogInternalHandler =
                new WatchdogInternalHandler(mMockWatchdogBinderMediator,
                                            mMockWatchdogProcessService, mMockWatchdogPerfService,
                                            mMockIoOveruseMonitor);
    }
    virtual void TearDown() {
        mWatchdogInternalHandler->terminate();
        ASSERT_EQ(mWatchdogInternalHandler->mWatchdogProcessService, nullptr);
        ASSERT_EQ(mWatchdogInternalHandler->mWatchdogPerfService, nullptr);
        ASSERT_EQ(mWatchdogInternalHandler->mIoOveruseMonitor, nullptr);
        ASSERT_EQ(mWatchdogInternalHandler->mBinderMediator, nullptr);

        mMockWatchdogProcessService.clear();
        mMockWatchdogPerfService.clear();
        mMockIoOveruseMonitor.clear();
        mMockWatchdogBinderMediator.clear();
        mWatchdogInternalHandler.clear();
        mScopedChangeCallingUid.clear();
    }
    // Sets calling UID to imitate System's process.
    void setSystemCallingUid() { mScopedChangeCallingUid = new ScopedChangeCallingUid(AID_SYSTEM); }
    sp<MockWatchdogProcessService> mMockWatchdogProcessService;
    sp<MockWatchdogPerfService> mMockWatchdogPerfService;
    sp<MockIoOveruseMonitor> mMockIoOveruseMonitor;
    sp<MockWatchdogBinderMediator> mMockWatchdogBinderMediator;
    sp<WatchdogInternalHandler> mWatchdogInternalHandler;
    sp<ScopedChangeCallingUid> mScopedChangeCallingUid;
};

TEST_F(WatchdogInternalHandlerTest, TestDump) {
    EXPECT_CALL(*mMockWatchdogBinderMediator, dump(-1, _)).WillOnce(Return(OK));
    ASSERT_EQ(mWatchdogInternalHandler->dump(-1, Vector<String16>()), OK);
}

TEST_F(WatchdogInternalHandlerTest, TestRegisterMediator) {
    setSystemCallingUid();
    sp<aawi::ICarWatchdogClient> mediator = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, registerMediator(mediator))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogInternalHandler->registerMediator(mediator);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnRegisterMediatorWithNonSystemCallingUid) {
    sp<aawi::ICarWatchdogClient> mediator = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, registerMediator(mediator)).Times(0);
    Status status = mWatchdogInternalHandler->registerMediator(mediator);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestUnregisterMediator) {
    setSystemCallingUid();
    sp<aawi::ICarWatchdogClient> mediator = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterMediator(mediator))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogInternalHandler->unregisterMediator(mediator);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnUnregisterMediatorWithNonSystemCallingUid) {
    sp<aawi::ICarWatchdogClient> mediator = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterMediator(mediator)).Times(0);
    Status status = mWatchdogInternalHandler->unregisterMediator(mediator);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestRegisterMonitor) {
    setSystemCallingUid();
    sp<aawi::ICarWatchdogMonitor> monitor = new MockICarWatchdogMonitor();
    EXPECT_CALL(*mMockWatchdogProcessService, registerMonitor(monitor))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogInternalHandler->registerMonitor(monitor);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnRegisterMonitorWithNonSystemCallingUid) {
    sp<aawi::ICarWatchdogMonitor> monitor = new MockICarWatchdogMonitor();
    EXPECT_CALL(*mMockWatchdogProcessService, registerMonitor(monitor)).Times(0);
    Status status = mWatchdogInternalHandler->registerMonitor(monitor);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestUnregisterMonitor) {
    setSystemCallingUid();
    sp<aawi::ICarWatchdogMonitor> monitor = new MockICarWatchdogMonitor();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterMonitor(monitor))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogInternalHandler->unregisterMonitor(monitor);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnUnregisterMonitorWithNonSystemCallingUid) {
    sp<aawi::ICarWatchdogMonitor> monitor = new MockICarWatchdogMonitor();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterMonitor(monitor)).Times(0);
    Status status = mWatchdogInternalHandler->unregisterMonitor(monitor);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestTellMediatorAlive) {
    setSystemCallingUid();
    sp<aawi::ICarWatchdogClient> mediator = new MockICarWatchdogClient();
    std::vector clientsNotResponding = {123};
    EXPECT_CALL(*mMockWatchdogProcessService,
                tellMediatorAlive(mediator, clientsNotResponding, 456))
            .WillOnce(Return(Status::ok()));
    Status status =
            mWatchdogInternalHandler->tellMediatorAlive(mediator, clientsNotResponding, 456);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnTellMediatorAliveWithNonSystemCallingUid) {
    sp<aawi::ICarWatchdogClient> mediator = new MockICarWatchdogClient();
    std::vector clientsNotResponding = {123};
    EXPECT_CALL(*mMockWatchdogProcessService, tellMediatorAlive(_, _, _)).Times(0);
    Status status =
            mWatchdogInternalHandler->tellMediatorAlive(mediator, clientsNotResponding, 456);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestTellDumpFinished) {
    setSystemCallingUid();
    sp<aawi::ICarWatchdogMonitor> monitor = new MockICarWatchdogMonitor();
    EXPECT_CALL(*mMockWatchdogProcessService, tellDumpFinished(monitor, 456))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogInternalHandler->tellDumpFinished(monitor, 456);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnTellDumpFinishedWithNonSystemCallingUid) {
    sp<aawi::ICarWatchdogMonitor> monitor = new MockICarWatchdogMonitor();
    EXPECT_CALL(*mMockWatchdogProcessService, tellDumpFinished(_, _)).Times(0);
    Status status = mWatchdogInternalHandler->tellDumpFinished(monitor, 456);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyPowerCycleChange) {
    setSystemCallingUid();
    aawi::StateType type = aawi::StateType::POWER_CYCLE;
    EXPECT_CALL(*mMockWatchdogProcessService,
                notifyPowerCycleChange(aawi::PowerCycle::POWER_CYCLE_SUSPEND))
            .WillOnce(Return(Status::ok()));
    Status status =
            mWatchdogInternalHandler
                    ->notifySystemStateChange(type,
                                              static_cast<int32_t>(
                                                      aawi::PowerCycle::POWER_CYCLE_SUSPEND),
                                              -1);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnNotifyPowerCycleChangeWithInvalidArgs) {
    EXPECT_CALL(*mMockWatchdogProcessService, notifyPowerCycleChange(_)).Times(0);
    aawi::StateType type = aawi::StateType::POWER_CYCLE;

    Status status = mWatchdogInternalHandler->notifySystemStateChange(type, -1, -1);
    ASSERT_FALSE(status.isOk()) << status;

    status = mWatchdogInternalHandler->notifySystemStateChange(type, 3000, -1);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyUserStateChange) {
    setSystemCallingUid();
    aawi::StateType type = aawi::StateType::USER_STATE;
    EXPECT_CALL(*mMockWatchdogProcessService,
                notifyUserStateChange(234567, aawi::UserState::USER_STATE_STOPPED))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogInternalHandler
                            ->notifySystemStateChange(type, 234567,
                                                      static_cast<int32_t>(
                                                              aawi::UserState::USER_STATE_STOPPED));
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnNotifyUserStateChangeWithInvalidArgs) {
    EXPECT_CALL(*mMockWatchdogProcessService, notifyUserStateChange(_, _)).Times(0);
    aawi::StateType type = aawi::StateType::USER_STATE;

    Status status = mWatchdogInternalHandler->notifySystemStateChange(type, 234567, -1);
    ASSERT_FALSE(status.isOk()) << status;

    status = mWatchdogInternalHandler->notifySystemStateChange(type, 234567, 3000);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyBootPhaseChange) {
    setSystemCallingUid();
    aawi::StateType type = aawi::StateType::BOOT_PHASE;
    EXPECT_CALL(*mMockWatchdogPerfService, onBootFinished()).WillOnce(Return(Result<void>()));
    Status status =
            mWatchdogInternalHandler
                    ->notifySystemStateChange(type,
                                              static_cast<int32_t>(aawi::BootPhase::BOOT_COMPLETED),
                                              -1);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyBootPhaseChangeWithNonBootCompletedPhase) {
    setSystemCallingUid();
    aawi::StateType type = aawi::StateType::BOOT_PHASE;
    EXPECT_CALL(*mMockWatchdogPerfService, onBootFinished()).Times(0);
    Status status = mWatchdogInternalHandler->notifySystemStateChange(type, 0, -1);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnNotifySystemStateChangeWithNonSystemCallingUid) {
    aawi::StateType type = aawi::StateType::POWER_CYCLE;
    EXPECT_CALL(*mMockWatchdogProcessService, notifyPowerCycleChange(_)).Times(0);
    Status status =
            mWatchdogInternalHandler
                    ->notifySystemStateChange(type,
                                              static_cast<int32_t>(
                                                      aawi::PowerCycle::POWER_CYCLE_SUSPEND),
                                              -1);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest, TestUpdateIoOveruseConfiguration) {
    setSystemCallingUid();
    EXPECT_CALL(*mMockIoOveruseMonitor, updateIoOveruseConfiguration(ComponentType::SYSTEM, _))
            .WillOnce(Return(Result<void>()));
    Status status =
            mWatchdogInternalHandler->updateIoOveruseConfiguration(ComponentType::SYSTEM,
                                                                   IoOveruseConfiguration{});
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogInternalHandlerTest,
       TestErrorOnUpdateIoOveruseConfigurationWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockIoOveruseMonitor, updateIoOveruseConfiguration(_, _)).Times(0);
    Status status =
            mWatchdogInternalHandler->updateIoOveruseConfiguration(ComponentType::SYSTEM,
                                                                   IoOveruseConfiguration{});
    ASSERT_FALSE(status.isOk()) << status;
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
