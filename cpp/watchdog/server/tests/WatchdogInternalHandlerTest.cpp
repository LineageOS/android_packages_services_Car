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
#include "MockWatchdogServiceHelper.h"
#include "ThreadPriorityController.h"
#include "WatchdogBinderMediator.h"
#include "WatchdogInternalHandler.h"
#include "WatchdogServiceHelper.h"

#include <aidl/android/automotive/watchdog/internal/BootPhase.h>
#include <aidl/android/automotive/watchdog/internal/GarageMode.h>
#include <aidl/android/automotive/watchdog/internal/PowerCycle.h>
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

using ::aidl::android::automotive::watchdog::internal::BootPhase;
using ::aidl::android::automotive::watchdog::internal::GarageMode;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogMonitorDefault;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystemDefault;
using ::aidl::android::automotive::watchdog::internal::PowerCycle;
using ::aidl::android::automotive::watchdog::internal::ProcessIdentifier;
using ::aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration;
using ::aidl::android::automotive::watchdog::internal::StateType;
using ::aidl::android::automotive::watchdog::internal::ThreadPolicyWithPriority;
using ::aidl::android::automotive::watchdog::internal::UserState;
using ::android::sp;
using ::android::String16;
using ::android::base::Result;
using ::ndk::ScopedAStatus;
using ::ndk::SharedRefBase;
using ::ndk::SpAIBinder;
using ::testing::_;
using ::testing::ByMove;
using ::testing::Eq;
using ::testing::Pointer;
using ::testing::Return;

class WatchdogInternalHandlerTestPeer final {
public:
    explicit WatchdogInternalHandlerTestPeer(WatchdogInternalHandler* handler) :
          mHandler(handler) {}

    void setThreadPriorityController(std::unique_ptr<ThreadPriorityController> controller) {
        mHandler->setThreadPriorityController(std::move(controller));
    }

private:
    WatchdogInternalHandler* mHandler;
};

namespace {

class MockSystemCalls : public ThreadPriorityController::SystemCallsInterface {
public:
    MockSystemCalls(int tid, int uid, int pid) {
        ON_CALL(*this, readPidStatusFileForPid(tid))
                .WillByDefault(Return(std::make_tuple(uid, pid)));
    }

    MOCK_METHOD(int, setScheduler, (pid_t tid, int policy, const sched_param* param), (override));
    MOCK_METHOD(int, getScheduler, (pid_t tid), (override));
    MOCK_METHOD(int, getParam, (pid_t tid, sched_param* param), (override));
    MOCK_METHOD((Result<std::tuple<uid_t, pid_t>>), readPidStatusFileForPid, (pid_t pid),
                (override));
};

class ScopedChangeCallingUid final : public RefBase {
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

MATCHER_P(PriorityEq, priority, "") {
    return (arg->sched_priority) == priority;
}

}  // namespace

class WatchdogInternalHandlerTest : public ::testing::Test {
protected:
    static constexpr pid_t TEST_PID = 1;
    static constexpr pid_t TEST_TID = 2;
    static constexpr uid_t TEST_UID = 3;

    virtual void SetUp() {
        mMockWatchdogProcessService = sp<MockWatchdogProcessService>::make();
        mMockWatchdogPerfService = sp<MockWatchdogPerfService>::make();
        mMockWatchdogServiceHelper = sp<MockWatchdogServiceHelper>::make();
        mMockIoOveruseMonitor = sp<MockIoOveruseMonitor>::make();
        mWatchdogInternalHandler =
                SharedRefBase::make<WatchdogInternalHandler>(mMockWatchdogServiceHelper,
                                                             mMockWatchdogProcessService,
                                                             mMockWatchdogPerfService,
                                                             mMockIoOveruseMonitor);
        WatchdogInternalHandlerTestPeer peer(mWatchdogInternalHandler.get());
        std::unique_ptr<MockSystemCalls> mockSystemCalls =
                std::make_unique<MockSystemCalls>(TEST_TID, TEST_UID, TEST_PID);
        mMockSystemCalls = mockSystemCalls.get();
        peer.setThreadPriorityController(
                std::make_unique<ThreadPriorityController>(std::move(mockSystemCalls)));
    }
    virtual void TearDown() {
        mMockWatchdogServiceHelper.clear();
        mMockWatchdogProcessService.clear();
        mMockWatchdogPerfService.clear();
        mMockIoOveruseMonitor.clear();
        mWatchdogInternalHandler.reset();
        mScopedChangeCallingUid.clear();
    }

    // Sets calling UID to imitate System's process.
    void setSystemCallingUid() {
        mScopedChangeCallingUid = sp<ScopedChangeCallingUid>::make(AID_SYSTEM);
    }

    sp<MockWatchdogServiceHelper> mMockWatchdogServiceHelper;
    sp<MockWatchdogProcessService> mMockWatchdogProcessService;
    sp<MockWatchdogPerfService> mMockWatchdogPerfService;
    sp<MockIoOveruseMonitor> mMockIoOveruseMonitor;
    std::shared_ptr<WatchdogInternalHandler> mWatchdogInternalHandler;
    sp<ScopedChangeCallingUid> mScopedChangeCallingUid;
    MockSystemCalls* mMockSystemCalls;
};

TEST_F(WatchdogInternalHandlerTest, TestTerminate) {
    ASSERT_NE(mWatchdogInternalHandler->mWatchdogServiceHelper, nullptr);
    ASSERT_NE(mWatchdogInternalHandler->mWatchdogProcessService, nullptr);
    ASSERT_NE(mWatchdogInternalHandler->mWatchdogPerfService, nullptr);
    ASSERT_NE(mWatchdogInternalHandler->mIoOveruseMonitor, nullptr);

    mWatchdogInternalHandler->terminate();

    ASSERT_EQ(mWatchdogInternalHandler->mWatchdogServiceHelper, nullptr);
    ASSERT_EQ(mWatchdogInternalHandler->mWatchdogProcessService, nullptr);
    ASSERT_EQ(mWatchdogInternalHandler->mWatchdogPerfService, nullptr);
    ASSERT_EQ(mWatchdogInternalHandler->mIoOveruseMonitor, nullptr);
}

TEST_F(WatchdogInternalHandlerTest, TestDump) {
    ASSERT_EQ(mWatchdogInternalHandler->dump(-1, /*args=*/nullptr, /*numArgs=*/0), OK);
}

TEST_F(WatchdogInternalHandlerTest, TestRegisterCarWatchdogService) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockIoOveruseMonitor, isInitialized()).WillOnce(Return(false));
    EXPECT_CALL(*mMockWatchdogPerfService, registerDataProcessor(Eq(mMockIoOveruseMonitor)))
            .WillOnce(Return(Result<void>()));

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    EXPECT_CALL(*mMockWatchdogServiceHelper, registerService(service))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogInternalHandler->registerCarWatchdogService(service);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnRegisterCarWatchdogServiceWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockWatchdogServiceHelper, registerService(_)).Times(0);

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    auto status = mWatchdogInternalHandler->registerCarWatchdogService(service);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest,
       TestErrorOnRegisterCarWatchdogServiceWithWatchdogServiceHelperError) {
    setSystemCallingUid();

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    EXPECT_CALL(*mMockWatchdogServiceHelper, registerService(service))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                                                "Illegal state"))));

    auto status = mWatchdogInternalHandler->registerCarWatchdogService(service);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestUnregisterCarWatchdogService) {
    setSystemCallingUid();

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    EXPECT_CALL(*mMockWatchdogServiceHelper, unregisterService(service))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogInternalHandler->unregisterCarWatchdogService(service);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest,
       TestErrorOnUnregisterCarWatchdogServiceWithNonSystemCallingUid) {
    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    EXPECT_CALL(*mMockWatchdogServiceHelper, unregisterService(service)).Times(0);

    auto status = mWatchdogInternalHandler->unregisterCarWatchdogService(service);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}
TEST_F(WatchdogInternalHandlerTest,
       TestErrorOnUnregisterCarWatchdogServiceWithWatchdogServiceHelperError) {
    setSystemCallingUid();

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    EXPECT_CALL(*mMockWatchdogServiceHelper, unregisterService(service))
            .WillOnce(Return(
                    ByMove(ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                                                       "Illegal argument"))));

    auto status = mWatchdogInternalHandler->unregisterCarWatchdogService(service);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestRegisterMonitor) {
    setSystemCallingUid();

    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    EXPECT_CALL(*mMockWatchdogProcessService, registerMonitor(monitor))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogInternalHandler->registerMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnRegisterMonitorWithNonSystemCallingUid) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    EXPECT_CALL(*mMockWatchdogProcessService, registerMonitor(monitor)).Times(0);

    auto status = mWatchdogInternalHandler->registerMonitor(monitor);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestUnregisterMonitor) {
    setSystemCallingUid();

    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterMonitor(monitor))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogInternalHandler->unregisterMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnUnregisterMonitorWithNonSystemCallingUid) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterMonitor(monitor)).Times(0);

    auto status = mWatchdogInternalHandler->unregisterMonitor(monitor);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestCarWatchdogServiceAlive) {
    setSystemCallingUid();

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    std::vector<ProcessIdentifier> clientsNotResponding;
    ProcessIdentifier processIdentifier;
    processIdentifier.pid = 123;
    clientsNotResponding.push_back(processIdentifier);
    EXPECT_CALL(*mMockWatchdogProcessService,
                tellCarWatchdogServiceAlive(service, clientsNotResponding, 456))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogInternalHandler->tellCarWatchdogServiceAlive(service,
                                                                        clientsNotResponding, 456);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnCarWatchdogServiceWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockWatchdogProcessService, tellCarWatchdogServiceAlive(_, _, _)).Times(0);

    std::shared_ptr<ICarWatchdogServiceForSystem> service =
            SharedRefBase::make<ICarWatchdogServiceForSystemDefault>();
    std::vector<ProcessIdentifier> clientsNotResponding;
    ProcessIdentifier processIdentifier;
    processIdentifier.pid = 123;
    clientsNotResponding.push_back(processIdentifier);
    auto status = mWatchdogInternalHandler->tellCarWatchdogServiceAlive(service,
                                                                        clientsNotResponding, 456);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestTellDumpFinished) {
    setSystemCallingUid();

    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    ProcessIdentifier processIdentifier;
    processIdentifier.pid = 456;
    EXPECT_CALL(*mMockWatchdogProcessService, tellDumpFinished(monitor, processIdentifier))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogInternalHandler->tellDumpFinished(monitor, processIdentifier);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnTellDumpFinishedWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockWatchdogProcessService, tellDumpFinished(_, _)).Times(0);

    ProcessIdentifier processIdentifier;
    processIdentifier.pid = 456;
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    auto status = mWatchdogInternalHandler->tellDumpFinished(monitor, processIdentifier);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyPowerCycleChangeToShutdownPrepare) {
    setSystemCallingUid();
    EXPECT_CALL(*mMockWatchdogProcessService, setEnabled(/*isEnabled=*/false)).Times(1);

    auto status =
            mWatchdogInternalHandler
                    ->notifySystemStateChange(StateType::POWER_CYCLE,
                                              static_cast<int32_t>(
                                                      PowerCycle::POWER_CYCLE_SHUTDOWN_PREPARE),
                                              -1);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyPowerCycleChangeToShutdownEnter) {
    setSystemCallingUid();
    EXPECT_CALL(*mMockWatchdogProcessService, setEnabled(/*isEnabled=*/false)).Times(1);

    auto status = mWatchdogInternalHandler
                          ->notifySystemStateChange(StateType::POWER_CYCLE,
                                                    static_cast<int32_t>(
                                                            PowerCycle::POWER_CYCLE_SHUTDOWN_ENTER),
                                                    -1);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyPowerCycleChangeToResume) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockWatchdogProcessService, setEnabled(/*isEnabled=*/true)).Times(1);

    auto status =
            mWatchdogInternalHandler
                    ->notifySystemStateChange(StateType::POWER_CYCLE,
                                              static_cast<int32_t>(PowerCycle::POWER_CYCLE_RESUME),
                                              -1);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnNotifyPowerCycleChangeWithInvalidArgs) {
    EXPECT_CALL(*mMockWatchdogProcessService, setEnabled(_)).Times(0);
    EXPECT_CALL(*mMockWatchdogPerfService, setSystemState(_)).Times(0);

    StateType type = StateType::POWER_CYCLE;
    auto status = mWatchdogInternalHandler->notifySystemStateChange(type, -1, -1);

    ASSERT_FALSE(status.isOk()) << status.getMessage();

    status = mWatchdogInternalHandler->notifySystemStateChange(type, 3000, -1);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyGarageModeOn) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockWatchdogPerfService, setSystemState(SystemState::GARAGE_MODE)).Times(1);

    auto status =
            mWatchdogInternalHandler->notifySystemStateChange(StateType::GARAGE_MODE,
                                                              static_cast<int32_t>(
                                                                      GarageMode::GARAGE_MODE_ON),
                                                              -1);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyGarageModeOff) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockWatchdogPerfService, setSystemState(SystemState::NORMAL_MODE)).Times(1);

    auto status =
            mWatchdogInternalHandler->notifySystemStateChange(StateType::GARAGE_MODE,
                                                              static_cast<int32_t>(
                                                                      GarageMode::GARAGE_MODE_OFF),
                                                              -1);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyUserStateChangeWithStartedUser) {
    setSystemCallingUid();
    StateType type = StateType::USER_STATE;

    EXPECT_CALL(*mMockWatchdogProcessService, notifyUserStateChange(234567, /*isStarted=*/true));

    auto status =
            mWatchdogInternalHandler
                    ->notifySystemStateChange(type, 234567,
                                              static_cast<int32_t>(UserState::USER_STATE_STARTED));

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyUserStateChangeWithStoppedUser) {
    setSystemCallingUid();
    StateType type = StateType::USER_STATE;

    EXPECT_CALL(*mMockWatchdogProcessService, notifyUserStateChange(234567, /*isStarted=*/false));

    auto status =
            mWatchdogInternalHandler
                    ->notifySystemStateChange(type, 234567,
                                              static_cast<int32_t>(UserState::USER_STATE_STOPPED));

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyUserStateChangeWithRemovedUser) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockIoOveruseMonitor, removeStatsForUser(/*userId=*/234567));

    StateType type = StateType::USER_STATE;
    auto status =
            mWatchdogInternalHandler
                    ->notifySystemStateChange(type, 234567,
                                              static_cast<int32_t>(UserState::USER_STATE_REMOVED));

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnNotifyUserStateChangeWithInvalidArgs) {
    EXPECT_CALL(*mMockWatchdogProcessService, notifyUserStateChange(_, _)).Times(0);

    StateType type = StateType::USER_STATE;
    auto status = mWatchdogInternalHandler->notifySystemStateChange(type, 234567, -1);

    ASSERT_FALSE(status.isOk()) << status.getMessage();

    status = mWatchdogInternalHandler->notifySystemStateChange(type, 234567, 3000);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyBootPhaseChange) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockWatchdogPerfService, onBootFinished()).WillOnce(Return(Result<void>()));

    StateType type = StateType::BOOT_PHASE;
    auto status =
            mWatchdogInternalHandler->notifySystemStateChange(type,
                                                              static_cast<int32_t>(
                                                                      BootPhase::BOOT_COMPLETED),
                                                              -1);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestNotifyBootPhaseChangeWithNonBootCompletedPhase) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockWatchdogPerfService, onBootFinished()).Times(0);

    StateType type = StateType::BOOT_PHASE;
    auto status = mWatchdogInternalHandler->notifySystemStateChange(type, 0, -1);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnNotifySystemStateChangeWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockWatchdogProcessService, setEnabled(_)).Times(0);
    EXPECT_CALL(*mMockWatchdogPerfService, setSystemState(_)).Times(0);

    StateType type = StateType::POWER_CYCLE;
    auto status =
            mWatchdogInternalHandler
                    ->notifySystemStateChange(type,
                                              static_cast<int32_t>(
                                                      PowerCycle::POWER_CYCLE_SHUTDOWN_PREPARE),
                                              -1);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestUpdateResourceOveruseConfigurations) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockIoOveruseMonitor, updateResourceOveruseConfigurations(_))
            .WillOnce(Return(Result<void>()));

    auto status = mWatchdogInternalHandler->updateResourceOveruseConfigurations(
            std::vector<ResourceOveruseConfiguration>{});

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest,
       TestErrorOnUpdateResourceOveruseConfigurationsWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockIoOveruseMonitor, updateResourceOveruseConfigurations(_)).Times(0);

    auto status = mWatchdogInternalHandler->updateResourceOveruseConfigurations(
            std::vector<ResourceOveruseConfiguration>{});

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestGetResourceOveruseConfigurations) {
    setSystemCallingUid();

    std::vector<ResourceOveruseConfiguration> configs;
    EXPECT_CALL(*mMockIoOveruseMonitor, getResourceOveruseConfigurations(Pointer(&configs)))
            .WillOnce(Return(Result<void>()));

    auto status = mWatchdogInternalHandler->getResourceOveruseConfigurations(&configs);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest,
       TestErrorOnGetResourceOveruseConfigurationsWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockIoOveruseMonitor, getResourceOveruseConfigurations(_)).Times(0);

    std::vector<ResourceOveruseConfiguration> configs;
    auto status = mWatchdogInternalHandler->getResourceOveruseConfigurations(&configs);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestControlProcessHealthCheck) {
    setSystemCallingUid();

    EXPECT_CALL(*mMockWatchdogProcessService, setEnabled(/*isEnabled=*/true)).Times(1);

    auto status = mWatchdogInternalHandler->controlProcessHealthCheck(/*enable=*/true);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestErrorOnControlProcessHealthCheckWithNonSystemCallingUid) {
    EXPECT_CALL(*mMockWatchdogProcessService, setEnabled(_)).Times(0);

    auto status = mWatchdogInternalHandler->controlProcessHealthCheck(/*enable=*/true);

    ASSERT_FALSE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestSetThreadPriority) {
    setSystemCallingUid();
    int policy = SCHED_FIFO;
    int priority = 1;
    EXPECT_CALL(*mMockSystemCalls, setScheduler(TEST_TID, policy, PriorityEq(priority)))
            .WillOnce(Return(0));

    auto status = mWatchdogInternalHandler->setThreadPriority(TEST_PID, TEST_TID, TEST_UID, policy,
                                                              priority);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestSetThreadPriorityDefaultPolicy) {
    setSystemCallingUid();
    int policy = SCHED_OTHER;
    int setPriority = 1;
    // Default policy should ignore the provided priority.
    int expectedPriority = 0;
    EXPECT_CALL(*mMockSystemCalls, setScheduler(TEST_TID, policy, PriorityEq(expectedPriority)))
            .WillOnce(Return(0));

    auto status = mWatchdogInternalHandler->setThreadPriority(TEST_PID, TEST_TID, TEST_UID, policy,
                                                              setPriority);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogInternalHandlerTest, TestSetThreadPriorityInvalidPid) {
    setSystemCallingUid();

    auto status = mWatchdogInternalHandler->setThreadPriority(TEST_PID + 1, TEST_TID, TEST_UID,
                                                              SCHED_FIFO, 1);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_STATE);
}

TEST_F(WatchdogInternalHandlerTest, TestSetThreadPriorityInvalidTid) {
    setSystemCallingUid();

    auto status = mWatchdogInternalHandler->setThreadPriority(TEST_PID, TEST_TID + 1, TEST_UID,
                                                              SCHED_FIFO, 1);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_STATE);
}

TEST_F(WatchdogInternalHandlerTest, TestSetThreadPriorityInvalidUid) {
    setSystemCallingUid();

    auto status = mWatchdogInternalHandler->setThreadPriority(TEST_PID, TEST_TID, TEST_UID + 1,
                                                              SCHED_FIFO, 1);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_STATE);
}

TEST_F(WatchdogInternalHandlerTest, TestSetThreadPriorityInvalidPolicy) {
    setSystemCallingUid();

    auto status = mWatchdogInternalHandler->setThreadPriority(TEST_PID, TEST_TID, TEST_UID, -1, 1);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_ARGUMENT);
}

TEST_F(WatchdogInternalHandlerTest, TestSetThreadPriorityInvalidPriority) {
    setSystemCallingUid();

    auto status = mWatchdogInternalHandler->setThreadPriority(TEST_PID, TEST_TID, TEST_UID,
                                                              SCHED_FIFO, 0);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_ARGUMENT);
}

TEST_F(WatchdogInternalHandlerTest, TestSetThreadPriorityFailed) {
    setSystemCallingUid();
    int expectedPolicy = SCHED_FIFO;
    int expectedPriority = 1;
    EXPECT_CALL(*mMockSystemCalls,
                setScheduler(TEST_TID, expectedPolicy, PriorityEq(expectedPriority)))
            .WillOnce(Return(-1));

    auto status = mWatchdogInternalHandler->setThreadPriority(TEST_PID, TEST_TID, TEST_UID,
                                                              expectedPolicy, expectedPriority);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
}

TEST_F(WatchdogInternalHandlerTest, TestGetThreadPriority) {
    setSystemCallingUid();
    int expectedPolicy = SCHED_FIFO;
    int expectedPriority = 1;
    EXPECT_CALL(*mMockSystemCalls, getScheduler(TEST_TID)).WillOnce(Return(expectedPolicy));
    EXPECT_CALL(*mMockSystemCalls, getParam(TEST_TID, _))
            .WillOnce([expectedPriority](pid_t, sched_param* param) {
                param->sched_priority = expectedPriority;
                return 0;
            });

    ThreadPolicyWithPriority actual;
    auto status =
            mWatchdogInternalHandler->getThreadPriority(TEST_PID, TEST_TID, TEST_UID, &actual);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    EXPECT_EQ(actual.policy, expectedPolicy);
    EXPECT_EQ(actual.priority, expectedPriority);
}

TEST_F(WatchdogInternalHandlerTest, TestGetThreadPriorityInvalidPid) {
    setSystemCallingUid();

    ThreadPolicyWithPriority actual;
    auto status =
            mWatchdogInternalHandler->getThreadPriority(TEST_PID + 1, TEST_TID, TEST_UID, &actual);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_STATE);
}

TEST_F(WatchdogInternalHandlerTest, TestGetThreadPriorityGetSchedulerFailed) {
    setSystemCallingUid();
    EXPECT_CALL(*mMockSystemCalls, getScheduler(TEST_TID)).WillOnce(Return(-1));

    ThreadPolicyWithPriority actual;
    auto status =
            mWatchdogInternalHandler->getThreadPriority(TEST_PID, TEST_TID, TEST_UID, &actual);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
}

TEST_F(WatchdogInternalHandlerTest, TestGetThreadPriorityGetParamFailed) {
    setSystemCallingUid();
    EXPECT_CALL(*mMockSystemCalls, getScheduler(TEST_TID)).WillOnce(Return(0));
    EXPECT_CALL(*mMockSystemCalls, getParam(TEST_TID, _)).WillOnce(Return(-1));

    ThreadPolicyWithPriority actual;
    auto status =
            mWatchdogInternalHandler->getThreadPriority(TEST_PID, TEST_TID, TEST_UID, &actual);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
