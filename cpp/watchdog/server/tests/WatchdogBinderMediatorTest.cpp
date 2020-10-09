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

#include "WatchdogBinderMediator.h"

#include <android/automotive/watchdog/BootPhase.h>
#include <android/automotive/watchdog/ComponentType.h>
#include <android/automotive/watchdog/IoOveruseConfiguration.h>
#include <android/automotive/watchdog/PowerCycle.h>
#include <android/automotive/watchdog/UserState.h>
#include <binder/IBinder.h>
#include <binder/IPCThreadState.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <private/android_filesystem_config.h>
#include <utils/RefBase.h>

#include <errno.h>

namespace android {
namespace automotive {
namespace watchdog {

using android::sp;
using android::base::Result;
using binder::Status;
using ::testing::_;
using ::testing::Return;

namespace {

class MockWatchdogProcessService : public WatchdogProcessService {
public:
    MockWatchdogProcessService() : WatchdogProcessService(nullptr) {}
    MOCK_METHOD(Result<void>, dump, (int fd, const Vector<String16>& args), (override));

    MOCK_METHOD(Status, registerClient,
                (const sp<ICarWatchdogClient>& client, TimeoutLength timeout), (override));
    MOCK_METHOD(Status, unregisterClient, (const sp<ICarWatchdogClient>& client), (override));
    MOCK_METHOD(Status, registerMediator, (const sp<ICarWatchdogClient>& mediator), (override));
    MOCK_METHOD(Status, unregisterMediator, (const sp<ICarWatchdogClient>& mediator), (override));
    MOCK_METHOD(Status, registerMonitor, (const sp<ICarWatchdogMonitor>& monitor), (override));
    MOCK_METHOD(Status, unregisterMonitor, (const sp<ICarWatchdogMonitor>& monitor), (override));
    MOCK_METHOD(Status, tellClientAlive, (const sp<ICarWatchdogClient>& client, int32_t sessionId),
                (override));
    MOCK_METHOD(Status, tellMediatorAlive,
                (const sp<ICarWatchdogClient>& mediator,
                 const std::vector<int32_t>& clientsNotResponding, int32_t sessionId),
                (override));
    MOCK_METHOD(Status, tellDumpFinished,
                (const android::sp<ICarWatchdogMonitor>& monitor, int32_t pid), (override));
    MOCK_METHOD(Status, notifyPowerCycleChange, (PowerCycle cycle), (override));
    MOCK_METHOD(Status, notifyUserStateChange, (userid_t userId, UserState state), (override));
};

class MockWatchdogPerfService : public WatchdogPerfService {
public:
    MockWatchdogPerfService() {}
    ~MockWatchdogPerfService() {}
    MOCK_METHOD(Result<void>, start, (), (override));
    MOCK_METHOD(void, terminate, (), (override));
    MOCK_METHOD(Result<void>, onBootFinished, (), (override));
    MOCK_METHOD(Result<void>, onCustomCollection, (int fd, const Vector<String16>& args),
                (override));
    MOCK_METHOD(Result<void>, onDump, (int fd), (override));
};

class MockIoOveruseMonitor : public IoOveruseMonitor {
public:
    MockIoOveruseMonitor() {}
    ~MockIoOveruseMonitor() {}
    MOCK_METHOD(Result<void>, updateIoOveruseConfiguration,
                (ComponentType type, const IoOveruseConfiguration& config), (override));
};

class MockICarWatchdogClient : public ICarWatchdogClient {
public:
    MOCK_METHOD(Status, checkIfAlive, (int32_t sessionId, TimeoutLength timeout), (override));
    MOCK_METHOD(Status, prepareProcessTermination, (), (override));
    MOCK_METHOD(IBinder*, onAsBinder, (), (override));
    MOCK_METHOD(int32_t, getInterfaceVersion, (), (override));
    MOCK_METHOD(std::string, getInterfaceHash, (), (override));
};

class MockICarWatchdogMonitor : public ICarWatchdogMonitor {
public:
    MOCK_METHOD(Status, onClientsNotResponding, (const std::vector<int32_t>& pids), (override));
    MOCK_METHOD(IBinder*, onAsBinder, (), (override));
    MOCK_METHOD(int32_t, getInterfaceVersion, (), (override));
    MOCK_METHOD(std::string, getInterfaceHash, (), (override));
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

class WatchdogBinderMediatorTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mMockWatchdogProcessService = new MockWatchdogProcessService();
        mMockWatchdogPerfService = new MockWatchdogPerfService();
        mMockIoOveruseMonitor = new MockIoOveruseMonitor();
        mWatchdogBinderMediator = new WatchdogBinderMediator();
        mWatchdogBinderMediator->init(mMockWatchdogProcessService, mMockWatchdogPerfService,
                                      mMockIoOveruseMonitor);
    }
    virtual void TearDown() {
        mWatchdogBinderMediator->terminate();
        ASSERT_EQ(mWatchdogBinderMediator->mWatchdogProcessService, nullptr);
        ASSERT_EQ(mWatchdogBinderMediator->mWatchdogPerfService, nullptr);
        ASSERT_EQ(mWatchdogBinderMediator->mIoOveruseMonitor, nullptr);
        mMockWatchdogProcessService.clear();
        mMockWatchdogPerfService.clear();
        mMockIoOveruseMonitor.clear();
        mWatchdogBinderMediator.clear();
        mScopedChangeCallingUid.clear();
    }
    // Sets calling UID to imitate System's process.
    void setSystemCallingUid() { mScopedChangeCallingUid = new ScopedChangeCallingUid(AID_SYSTEM); }
    sp<MockWatchdogProcessService> mMockWatchdogProcessService;
    sp<MockWatchdogPerfService> mMockWatchdogPerfService;
    sp<MockIoOveruseMonitor> mMockIoOveruseMonitor;
    sp<WatchdogBinderMediator> mWatchdogBinderMediator;
    sp<ScopedChangeCallingUid> mScopedChangeCallingUid;
};

TEST_F(WatchdogBinderMediatorTest, TestErrorOnNullptrDuringInit) {
    sp<WatchdogBinderMediator> mediator = new WatchdogBinderMediator();
    ASSERT_FALSE(
            mediator->init(nullptr, new MockWatchdogPerfService(), new MockIoOveruseMonitor()).ok())
            << "No error returned on nullptr watchdog process service";
    ASSERT_FALSE(
            mediator->init(new MockWatchdogProcessService(), nullptr, new MockIoOveruseMonitor())
                    .ok())
            << "No error returned on nullptr watchdog perf service";
    ASSERT_FALSE(
            mediator->init(new MockWatchdogProcessService(), new MockWatchdogPerfService(), nullptr)
                    .ok())
            << "No error returned on nullptr I/O overuse monitor";
    ASSERT_FALSE(mediator->init(nullptr, nullptr, nullptr).ok()) << "No error returned on nullptr";
}

TEST_F(WatchdogBinderMediatorTest, TestHandlesEmptyDumpArgs) {
    EXPECT_CALL(*mMockWatchdogProcessService, dump(-1, _)).WillOnce(Return(Result<void>()));
    EXPECT_CALL(*mMockWatchdogPerfService, onDump(-1)).WillOnce(Return(Result<void>()));
    mWatchdogBinderMediator->dump(-1, Vector<String16>());
}

TEST_F(WatchdogBinderMediatorTest, TestHandlesStartCustomPerfCollection) {
    EXPECT_CALL(*mMockWatchdogPerfService, onCustomCollection(-1, _))
            .WillOnce(Return(Result<void>()));

    Vector<String16> args;
    args.push_back(String16(kStartCustomCollectionFlag));
    ASSERT_EQ(mWatchdogBinderMediator->dump(-1, args), OK);
}

TEST_F(WatchdogBinderMediatorTest, TestHandlesStopCustomPerfCollection) {
    EXPECT_CALL(*mMockWatchdogPerfService, onCustomCollection(-1, _))
            .WillOnce(Return(Result<void>()));

    Vector<String16> args;
    args.push_back(String16(kEndCustomCollectionFlag));
    ASSERT_EQ(mWatchdogBinderMediator->dump(-1, args), OK);
}

TEST_F(WatchdogBinderMediatorTest, TestErrorOnInvalidDumpArgs) {
    Vector<String16> args;
    args.push_back(String16("--invalid_option"));
    ASSERT_EQ(mWatchdogBinderMediator->dump(-1, args), OK) << "Error returned on invalid args";
}

TEST_F(WatchdogBinderMediatorTest, TestRegisterClient) {
    sp<ICarWatchdogClient> client = new MockICarWatchdogClient();
    TimeoutLength timeout = TimeoutLength::TIMEOUT_MODERATE;
    EXPECT_CALL(*mMockWatchdogProcessService, registerClient(client, timeout))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->registerClient(client, timeout);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestUnregisterClient) {
    sp<ICarWatchdogClient> client = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterClient(client))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->unregisterClient(client);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestRegisterMediator) {
    setSystemCallingUid();
    sp<ICarWatchdogClient> mediator = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, registerMediator(mediator))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->registerMediator(mediator);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestErrorOnRegisterMediatorWithNonSystemCallingUid) {
    sp<ICarWatchdogClient> mediator = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, registerMediator(mediator)).Times(0);
    Status status = mWatchdogBinderMediator->registerMediator(mediator);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestUnregisterMediator) {
    setSystemCallingUid();
    sp<ICarWatchdogClient> mediator = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterMediator(mediator))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->unregisterMediator(mediator);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestErrorOnUnegisterMediatorWithNonSystemCallingUid) {
    sp<ICarWatchdogClient> mediator = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterMediator(mediator)).Times(0);
    Status status = mWatchdogBinderMediator->unregisterMediator(mediator);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestRegisterMonitor) {
    setSystemCallingUid();
    sp<ICarWatchdogMonitor> monitor = new MockICarWatchdogMonitor();
    EXPECT_CALL(*mMockWatchdogProcessService, registerMonitor(monitor))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->registerMonitor(monitor);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestErrorOnRegisterMonitorWithNonSystemCallingUid) {
    sp<ICarWatchdogMonitor> monitor = new MockICarWatchdogMonitor();
    EXPECT_CALL(*mMockWatchdogProcessService, registerMonitor(monitor)).Times(0);
    Status status = mWatchdogBinderMediator->registerMonitor(monitor);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestUnregisterMonitor) {
    setSystemCallingUid();
    sp<ICarWatchdogMonitor> monitor = new MockICarWatchdogMonitor();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterMonitor(monitor))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->unregisterMonitor(monitor);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestErrorOnUnregisterMonitorWithNonSystemCallingUid) {
    sp<ICarWatchdogMonitor> monitor = new MockICarWatchdogMonitor();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterMonitor(monitor)).Times(0);
    Status status = mWatchdogBinderMediator->unregisterMonitor(monitor);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestTellClientAlive) {
    sp<ICarWatchdogClient> client = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, tellClientAlive(client, 456))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->tellClientAlive(client, 456);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestTellMediatorAlive) {
    sp<ICarWatchdogClient> mediator = new MockICarWatchdogClient();
    std::vector clientsNotResponding = {123};
    EXPECT_CALL(*mMockWatchdogProcessService,
                tellMediatorAlive(mediator, clientsNotResponding, 456))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->tellMediatorAlive(mediator, clientsNotResponding, 456);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestTellDumpFinished) {
    sp<ICarWatchdogMonitor> monitor = new MockICarWatchdogMonitor();
    EXPECT_CALL(*mMockWatchdogProcessService, tellDumpFinished(monitor, 456))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->tellDumpFinished(monitor, 456);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestErrorOnNotifyStateChangeWithNonSystemCallingUid) {
    StateType type = StateType::POWER_CYCLE;
    EXPECT_CALL(*mMockWatchdogProcessService, notifyPowerCycleChange(_)).Times(0);
    Status status =
            mWatchdogBinderMediator
                    ->notifySystemStateChange(type,
                                              static_cast<int32_t>(PowerCycle::POWER_CYCLE_SUSPEND),
                                              -1);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestNotifyPowerCycleChange) {
    setSystemCallingUid();
    StateType type = StateType::POWER_CYCLE;
    EXPECT_CALL(*mMockWatchdogProcessService,
                notifyPowerCycleChange(PowerCycle::POWER_CYCLE_SUSPEND))
            .WillOnce(Return(Status::ok()));
    Status status =
            mWatchdogBinderMediator
                    ->notifySystemStateChange(type,
                                              static_cast<int32_t>(PowerCycle::POWER_CYCLE_SUSPEND),
                                              -1);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestErrorOnNotifyPowerCycleChangeWithInvalidArgs) {
    EXPECT_CALL(*mMockWatchdogProcessService, notifyPowerCycleChange(_)).Times(0);
    StateType type = StateType::POWER_CYCLE;

    Status status = mWatchdogBinderMediator->notifySystemStateChange(type, -1, -1);
    ASSERT_FALSE(status.isOk()) << status;

    status = mWatchdogBinderMediator->notifySystemStateChange(type, 3000, -1);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestNotifyUserStateChange) {
    setSystemCallingUid();
    StateType type = StateType::USER_STATE;
    EXPECT_CALL(*mMockWatchdogProcessService,
                notifyUserStateChange(234567, UserState::USER_STATE_STOPPED))
            .WillOnce(Return(Status::ok()));
    Status status =
            mWatchdogBinderMediator
                    ->notifySystemStateChange(type, 234567,
                                              static_cast<int32_t>(UserState::USER_STATE_STOPPED));
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestErrorOnNotifyUserStateChangeWithInvalidArgs) {
    EXPECT_CALL(*mMockWatchdogProcessService, notifyUserStateChange(_, _)).Times(0);
    StateType type = StateType::USER_STATE;

    Status status = mWatchdogBinderMediator->notifySystemStateChange(type, 234567, -1);
    ASSERT_FALSE(status.isOk()) << status;

    status = mWatchdogBinderMediator->notifySystemStateChange(type, 234567, 3000);
    ASSERT_FALSE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestNotifyBootPhaseChange) {
    setSystemCallingUid();
    StateType type = StateType::BOOT_PHASE;
    EXPECT_CALL(*mMockWatchdogPerfService, onBootFinished()).WillOnce(Return(Result<void>()));
    Status status = mWatchdogBinderMediator->notifySystemStateChange(
        type, static_cast<int32_t>(BootPhase::BOOT_COMPLETED), -1);
    ASSERT_TRUE(status.isOk()) << status;
}


TEST_F(WatchdogBinderMediatorTest, TestNotifyBootPhaseChangeWithNonBootCompletedPhase) {
    setSystemCallingUid();
    StateType type = StateType::BOOT_PHASE;
    EXPECT_CALL(*mMockWatchdogPerfService, onBootFinished()).Times(0);
    Status status = mWatchdogBinderMediator->notifySystemStateChange(type, 0, -1);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestUpdateIoOveruseConfiguration) {
    setSystemCallingUid();
    EXPECT_CALL(*mMockIoOveruseMonitor, updateIoOveruseConfiguration(ComponentType::SYSTEM, _))
            .WillOnce(Return(Result<void>()));
    Status status = mWatchdogBinderMediator->updateIoOveruseConfiguration(ComponentType::SYSTEM,
                                                                          IoOveruseConfiguration{});
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestErrorOnUpdateIoOveruseConfigurationWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockIoOveruseMonitor, updateIoOveruseConfiguration(_, _)).Times(0);
    Status status = mWatchdogBinderMediator->updateIoOveruseConfiguration(ComponentType::SYSTEM,
                                                                          IoOveruseConfiguration{});
    ASSERT_FALSE(status.isOk()) << status;
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
