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

#include "MockAIBinderDeathRegistrationWrapper.h"
#include "MockCarWatchdogServiceForSystem.h"
#include "MockHidlServiceManager.h"
#include "MockVhalClient.h"
#include "MockWatchdogServiceHelper.h"
#include "WatchdogProcessService.h"
#include "WatchdogServiceHelper.h"

#include <android/binder_interface_utils.h>
#include <android/hidl/manager/1.0/IServiceManager.h>
#include <gmock/gmock.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::ICarWatchdogClient;
using ::aidl::android::automotive::watchdog::ICarWatchdogClientDefault;
using ::aidl::android::automotive::watchdog::TimeoutLength;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogMonitorDefault;
using ::aidl::android::automotive::watchdog::internal::ProcessIdentifier;
using ::aidl::android::hardware::automotive::vehicle::VehicleProperty;
using ::android::IBinder;
using ::android::sp;
using ::android::frameworks::automotive::vhal::IVhalClient;
using ::android::hidl::base::V1_0::DebugInfo;
using ::android::hidl::manager::V1_0::IServiceManager;
using ::ndk::ScopedAStatus;
using ::ndk::SharedRefBase;
using ::ndk::SpAIBinder;
using ::testing::_;
using ::testing::ByMove;
using ::testing::Eq;
using ::testing::Invoke;
using ::testing::Return;

namespace {

ProcessIdentifier constructProcessIdentifier(int32_t pid, int64_t startTimeMillis) {
    ProcessIdentifier processIdentifier;
    processIdentifier.pid = pid;
    processIdentifier.startTimeMillis = startTimeMillis;
    return processIdentifier;
}

}  // namespace

namespace internal {

class WatchdogProcessServicePeer final {
public:
    explicit WatchdogProcessServicePeer(const sp<WatchdogProcessService>& watchdogProcessService) :
          mWatchdogProcessService(watchdogProcessService) {
        mWatchdogProcessService->mGetStartTimeForPidFunc = [](pid_t) -> uint64_t { return 12356; };
    }

    void setVhalService(std::shared_ptr<IVhalClient> service) {
        mWatchdogProcessService->mVhalService = service;
    }

    void setNotSupportedVhalProperties(const std::unordered_set<VehicleProperty>& properties) {
        mWatchdogProcessService->mNotSupportedVhalProperties = properties;
    }

    void setDeathRegistrationWrapper(const sp<AIBinderDeathRegistrationWrapperInterface>& wrapper) {
        mWatchdogProcessService->mDeathRegistrationWrapper = wrapper;
    }

    void setHidlServiceManager(const sp<IServiceManager>& hidlServiceManager) {
        WatchdogProcessService::sHidlServiceManager = hidlServiceManager;
    }

    std::optional<ProcessIdentifier> cacheVhalProcessIdentifier() {
        return mWatchdogProcessService->cacheVhalProcessIdentifier();
    }

private:
    sp<WatchdogProcessService> mWatchdogProcessService;
};

}  // namespace internal

class WatchdogProcessServiceTest : public ::testing::Test {
protected:
    void SetUp() override {
        sp<Looper> looper(Looper::prepare(/*opts=*/0));
        mWatchdogProcessService = sp<WatchdogProcessService>::make(looper);
        mMockVehicle = SharedRefBase::make<MockVehicle>();
        mMockVhalClient = std::make_shared<MockVhalClient>(mMockVehicle);
        mMockHidlServiceManager = sp<MockHidlServiceManager>::make();
        mMockDeathRegistrationWrapper = sp<MockAIBinderDeathRegistrationWrapper>::make();
        mWatchdogProcessServicePeer =
                std::make_unique<internal::WatchdogProcessServicePeer>(mWatchdogProcessService);
        mWatchdogProcessServicePeer->setVhalService(mMockVhalClient);
        mWatchdogProcessServicePeer->setNotSupportedVhalProperties(
                {VehicleProperty::WATCHDOG_ALIVE, VehicleProperty::WATCHDOG_TERMINATED_PROCESS});
        mWatchdogProcessServicePeer->setDeathRegistrationWrapper(mMockDeathRegistrationWrapper);
        mWatchdogProcessServicePeer->setHidlServiceManager(mMockHidlServiceManager);
        mWatchdogProcessService->start();
    }

    void TearDown() override {
        mWatchdogProcessServicePeer.reset();
        mWatchdogProcessService->terminate();
        mWatchdogProcessService.clear();
        mMockHidlServiceManager.clear();
        mMockVhalClient.reset();
        mMockVehicle.reset();
        mMockDeathRegistrationWrapper.clear();
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

    void expectNoUnlinkToDeath(AIBinder* aiBinder) {
        EXPECT_CALL(*mMockDeathRegistrationWrapper,
                    unlinkToDeath(Eq(aiBinder), _, static_cast<void*>(aiBinder)))
                .Times(0);
    }

    sp<WatchdogProcessService> mWatchdogProcessService;
    std::shared_ptr<MockVhalClient> mMockVhalClient;
    std::shared_ptr<MockVehicle> mMockVehicle;
    sp<MockHidlServiceManager> mMockHidlServiceManager;
    sp<MockAIBinderDeathRegistrationWrapper> mMockDeathRegistrationWrapper;
    std::unique_ptr<internal::WatchdogProcessServicePeer> mWatchdogProcessServicePeer;
};

TEST_F(WatchdogProcessServiceTest, TestTerminate) {
    std::vector<int32_t> propIds = {static_cast<int32_t>(VehicleProperty::VHAL_HEARTBEAT)};
    EXPECT_CALL(*mMockVhalClient, removeOnBinderDiedCallback(_)).Times(1);
    EXPECT_CALL(*mMockVehicle, unsubscribe(_, propIds))
            .WillOnce(Return(ByMove(std::move(ndk::ScopedAStatus::ok()))));
    mWatchdogProcessService->terminate();
    // TODO(b/217405065): Verify looper removes all MSG_VHAL_HEALTH_CHECK messages.
}

// TODO(b/217405065): Add test to verify the handleVhalDeath method.

TEST_F(WatchdogProcessServiceTest, TestRegisterClient) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    expectLinkToDeath(client->asBinder().get(), std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogProcessServiceTest, TestUnregisterClient) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    AIBinder* aiBinder = client->asBinder().get();
    expectLinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    expectUnlinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    status = mWatchdogProcessService->unregisterClient(client);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_FALSE(mWatchdogProcessService->unregisterClient(client).isOk())
            << "Unregistering an unregistered client should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestErrorOnRegisterClientWithDeadBinder) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    expectLinkToDeath(client->asBinder().get(),
                      std::move(ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED)));

    ASSERT_FALSE(
            mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL).isOk())
            << "When linkToDeath fails, registerClient should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestHandleClientBinderDeath) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    AIBinder* aiBinder = client->asBinder().get();
    expectLinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    mWatchdogProcessService->handleBinderDeath(static_cast<void*>(aiBinder));

    expectNoUnlinkToDeath(aiBinder);

    ASSERT_FALSE(mWatchdogProcessService->unregisterClient(client).isOk())
            << "Unregistering a dead client should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestRegisterCarWatchdogService) {
    sp<MockWatchdogServiceHelper> mockServiceHelper = sp<MockWatchdogServiceHelper>::make();

    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    const auto binder = mockService->asBinder();

    auto status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();

    status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogProcessServiceTest,
       TestErrorOnRegisterCarWatchdogServiceWithNullWatchdogServiceHelper) {
    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    const auto binder = mockService->asBinder();

    ASSERT_FALSE(mWatchdogProcessService->registerCarWatchdogService(binder, nullptr).isOk())
            << "Registering car watchdog service should fail when watchdog service helper is null";
}

TEST_F(WatchdogProcessServiceTest, TestRegisterMonitor) {
    std::shared_ptr<ICarWatchdogMonitor> monitorOne =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    expectLinkToDeath(monitorOne->asBinder().get(), std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerMonitor(monitorOne);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    status = mWatchdogProcessService->registerMonitor(monitorOne);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    std::shared_ptr<ICarWatchdogMonitor> monitorTwo =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    status = mWatchdogProcessService->registerMonitor(monitorTwo);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogProcessServiceTest, TestErrorOnRegisterMonitorWithDeadBinder) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    expectLinkToDeath(monitor->asBinder().get(),
                      std::move(ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED)));

    ASSERT_FALSE(mWatchdogProcessService->registerMonitor(monitor).isOk())
            << "When linkToDeath fails, registerMonitor should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestUnregisterMonitor) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    AIBinder* aiBinder = monitor->asBinder().get();
    expectLinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    expectUnlinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    status = mWatchdogProcessService->unregisterMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_FALSE(mWatchdogProcessService->unregisterMonitor(monitor).isOk())
            << "Unregistering an unregistered monitor should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestHandleMonitorBinderDeath) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    AIBinder* aiBinder = monitor->asBinder().get();
    expectLinkToDeath(aiBinder, std::move(ScopedAStatus::ok()));

    auto status = mWatchdogProcessService->registerMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    mWatchdogProcessService->handleBinderDeath(static_cast<void*>(aiBinder));

    expectNoUnlinkToDeath(aiBinder);

    ASSERT_FALSE(mWatchdogProcessService->unregisterMonitor(monitor).isOk())
            << "Unregistering a dead monitor should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestTellClientAlive) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    expectLinkToDeath(client->asBinder().get(), std::move(ScopedAStatus::ok()));

    mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_FALSE(mWatchdogProcessService->tellClientAlive(client, 1234).isOk())
            << "tellClientAlive not synced with checkIfAlive should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestTellCarWatchdogServiceAlive) {
    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();

    std::vector<ProcessIdentifier> processIdentifiers;
    processIdentifiers.push_back(
            constructProcessIdentifier(/* pid= */ 111, /* startTimeMillis= */ 0));
    processIdentifiers.push_back(
            constructProcessIdentifier(/* pid= */ 222, /* startTimeMillis= */ 0));
    ASSERT_FALSE(mWatchdogProcessService
                         ->tellCarWatchdogServiceAlive(mockService, processIdentifiers, 1234)
                         .isOk())
            << "tellCarWatchdogServiceAlive not synced with checkIfAlive should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestTellDumpFinished) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    ASSERT_FALSE(mWatchdogProcessService
                         ->tellDumpFinished(monitor,
                                            constructProcessIdentifier(/* pid= */ 1234,
                                                                       /* startTimeMillis= */ 0))
                         .isOk())
            << "Unregistered monitor cannot call tellDumpFinished";

    expectLinkToDeath(monitor->asBinder().get(), std::move(ScopedAStatus::ok()));

    mWatchdogProcessService->registerMonitor(monitor);
    auto status = mWatchdogProcessService
                          ->tellDumpFinished(monitor,
                                             constructProcessIdentifier(/* pid= */ 1234,
                                                                        /* startTimeMillis= */ 0));

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogProcessServiceTest, TestCacheVhalProcessIdentifierFromHidlServiceManager) {
    using InstanceDebugInfo = IServiceManager::InstanceDebugInfo;
    EXPECT_CALL(*mMockVhalClient, isAidlVhal()).WillOnce(Return(false));
    EXPECT_CALL(*mMockHidlServiceManager, debugDump(_))
            .WillOnce(Invoke([](IServiceManager::debugDump_cb cb) {
                cb({InstanceDebugInfo{"android.hardware.automotive.evs@1.0::IEvsCamera",
                                      "vehicle_hal_insts",
                                      8058,
                                      {},
                                      DebugInfo::Architecture::IS_64BIT},
                    InstanceDebugInfo{"android.hardware.automotive.vehicle@2.0::IVehicle",
                                      "vehicle_hal_insts",
                                      static_cast<int>(IServiceManager::PidConstant::NO_PID),
                                      {},
                                      DebugInfo::Architecture::IS_64BIT},
                    InstanceDebugInfo{"android.hardware.automotive.vehicle@2.0::IVehicle",
                                      "vehicle_hal_insts",
                                      2034,
                                      {},
                                      DebugInfo::Architecture::IS_64BIT}});
                return android::hardware::Void();
            }));

    auto processIdentifier = mWatchdogProcessServicePeer->cacheVhalProcessIdentifier();

    ASSERT_TRUE(processIdentifier.has_value());
    EXPECT_EQ(2034, processIdentifier->pid);
    EXPECT_EQ(12356, processIdentifier->startTimeMillis);
}

TEST_F(WatchdogProcessServiceTest, TestFailsCacheVhalProcessIdentifierWithHidlVhal) {
    using InstanceDebugInfo = IServiceManager::InstanceDebugInfo;
    EXPECT_CALL(*mMockVhalClient, isAidlVhal()).WillOnce(Return(false));
    EXPECT_CALL(*mMockHidlServiceManager, debugDump(_))
            .WillOnce(Invoke([](IServiceManager::debugDump_cb cb) {
                cb({InstanceDebugInfo{"android.hardware.automotive.evs@1.0::IEvsCamera",
                                      "vehicle_hal_insts",
                                      8058,
                                      {},
                                      DebugInfo::Architecture::IS_64BIT}});
                return android::hardware::Void();
            }));

    EXPECT_FALSE(mWatchdogProcessServicePeer->cacheVhalProcessIdentifier().has_value());
}

TEST_F(WatchdogProcessServiceTest, TestFailsCacheVhalProcessIdentifierWithAidlVhal) {
    EXPECT_CALL(*mMockVhalClient, isAidlVhal()).WillOnce(Return(true));
    EXPECT_CALL(*mMockHidlServiceManager, debugDump(_)).Times(0);

    EXPECT_FALSE(mWatchdogProcessServicePeer->cacheVhalProcessIdentifier().has_value());
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
