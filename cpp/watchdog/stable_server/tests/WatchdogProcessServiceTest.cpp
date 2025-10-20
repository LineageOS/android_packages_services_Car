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
#include "MockCarWatchdogClient.h"
#include "MockCarWatchdogMonitor.h"
#include "MockCarWatchdogServiceForSystem.h"
#include "MockHidlServiceManager.h"
#include "MockPackageInfoResolver.h"
#include "MockVhalClient.h"
#include "MockWatchdogServiceHelper.h"
#include "PackageInfoResolver.h"
#include "WatchdogProcessService.h"
#include "WatchdogServiceHelper.h"

#include <aidl/android/automotive/watchdog/internal/BnCarWatchdogMonitor.h>
#include <android/binder_interface_utils.h>
#include <android/hidl/manager/1.0/IServiceManager.h>
#include <android/util/ProtoOutputStream.h>
#include <binder/IPCThreadState.h>
#include <gmock/gmock.h>
#include <utils/SystemClock.h>

#include <thread>  // NOLINT(build/c++11)

#include <packages/services/Car/service/proto/android/car/watchdog/carwatchdog_daemon_dump.pb.h>
#include <packages/services/Car/service/proto/android/car/watchdog/health_check_client_info.pb.h>
#include <packages/services/Car/service/proto/android/car/watchdog/performance_stats.pb.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::ICarWatchdogClient;
using ::aidl::android::automotive::watchdog::ICarWatchdogClientDefault;
using ::aidl::android::automotive::watchdog::TimeoutLength;
using ::aidl::android::automotive::watchdog::internal::BnCarWatchdogMonitor;
using ::aidl::android::automotive::watchdog::internal::GarageMode;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogMonitorDefault;
using ::aidl::android::automotive::watchdog::internal::ProcessIdentifier;
using ::aidl::android::hardware::automotive::vehicle::IVehicleCallback;
using ::aidl::android::hardware::automotive::vehicle::RawPropValues;
using ::aidl::android::hardware::automotive::vehicle::SubscribeOptions;
using ::aidl::android::hardware::automotive::vehicle::VehicleProperty;
using ::aidl::android::hardware::automotive::vehicle::VehiclePropValue;
using ::aidl::android::hardware::automotive::vehicle::VehiclePropValues;
using ::android::IBinder;
using ::android::Looper;
using ::android::sp;
using ::android::base::Error;
using ::android::car::feature::car_watchdog_anr_metrics;
using ::android::frameworks::automotive::vhal::ClientStatusError;
using ::android::frameworks::automotive::vhal::ErrorCode;
using ::android::frameworks::automotive::vhal::IHalPropConfig;
using ::android::frameworks::automotive::vhal::IVhalClient;
using ::android::frameworks::automotive::vhal::VhalClientError;
using ::android::frameworks::automotive::vhal::VhalClientResult;
using ::android::hidl::base::V1_0::DebugInfo;
using ::android::hidl::manager::V1_0::IServiceManager;
using ::android::util::ProtoReader;
using ::ndk::ScopedAStatus;
using ::ndk::SharedRefBase;
using ::ndk::SpAIBinder;
using ::testing::_;
using ::testing::ByMove;
using ::testing::Eq;
using ::testing::Field;
using ::testing::Invoke;
using ::testing::Matcher;
using ::testing::NiceMock;
using ::testing::Return;
using ::testing::SizeIs;
using ::testing::UnorderedElementsAreArray;

namespace {

constexpr std::chrono::milliseconds kMaxWaitForLooperExecutionMillis = 5s;
constexpr std::chrono::nanoseconds kTestVhalPidCachingRetryDelayNs = 20ms;
constexpr char kTestLooperThreadName[] = "WdProcSvcTest";
constexpr char kTestPidComm[] = "test_pid_comm";
constexpr const int32_t kTestAidlVhalPid = 564269;
constexpr const int32_t kTestAidlVhalUid = 100124025;
constexpr const int32_t kTestAidlVhalTgid = 564269;
constexpr const int32_t kTestPidStartTime = 12356;
constexpr const int32_t kMaxVhalPidCachingAttempts = 2;
constexpr std::chrono::milliseconds kTestVhalHealthCheckIntervalMillis = 200ms;
constexpr std::chrono::milliseconds kTestVhalHealthCheckDelayMillis = 100ms;
constexpr const int32_t kTestAidlClientUid = 100124036;

enum TestMessage {
    SYNC_LOOPER_MARKER,
    ON_AIDL_VHAL_PID,
    UPDATE_VHAL_HEARTBEAT,
};

ProcessIdentifier constructProcessIdentifier(pid_t pid, uid_t uid, std::string processName,
                                             int64_t startTimeMillis) {
    ProcessIdentifier processIdentifier;
    processIdentifier.pid = pid;
    processIdentifier.startTimeMillis = startTimeMillis;
    processIdentifier.uid = uid;
    processIdentifier.processName = processName;
    return processIdentifier;
}

MATCHER_P(ProcessIdentifierEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("pid", &ProcessIdentifier::pid, Eq(expected.pid)),
                                    Field("startTimeMillis", &ProcessIdentifier::startTimeMillis,
                                          Eq(expected.startTimeMillis)),
                                    Field("processName", &ProcessIdentifier::processName,
                                          Eq(expected.processName)),
                                    Field("uid", &ProcessIdentifier::uid, Eq(expected.uid))),
                              arg, result_listener);
}

std::vector<Matcher<const ProcessIdentifier&>> constructProcessIdentifierMatchers(
        const std::vector<ProcessIdentifier>& processIdentifiers) {
    std::vector<Matcher<const ProcessIdentifier&>> processIdentifierMatchers;
    processIdentifierMatchers.reserve(processIdentifiers.size());
    for (const auto& processIdentifier : processIdentifiers) {
        processIdentifierMatchers.push_back(ProcessIdentifierEq(processIdentifier));
    }
    return processIdentifierMatchers;
}

MATCHER_P(ClientsNotRespondingInfoEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("processIdentifiers",
                                          &ClientsNotRespondingInfo::processIdentifiers,
                                          UnorderedElementsAreArray(
                                                  constructProcessIdentifierMatchers(
                                                          expected.processIdentifiers))),
                                    Field("garageMode", &ClientsNotRespondingInfo::garageMode,
                                          Eq(expected.garageMode))),
                              arg, result_listener);
}

std::shared_ptr<MockCarWatchdogClient> createMockCarWatchdogClient() {
    std::shared_ptr<MockCarWatchdogClient> client = SharedRefBase::make<MockCarWatchdogClient>();
    ON_CALL(*client, checkIfAlive(_, _)).WillByDefault([]() { return ScopedAStatus::ok(); });
    ON_CALL(*client, prepareProcessTermination()).WillByDefault([]() {
        return ScopedAStatus::ok();
    });
    return client;
}

}  // namespace

namespace internal {

class WatchdogProcessServicePeer final {
public:
    explicit WatchdogProcessServicePeer(const sp<WatchdogProcessService>& watchdogProcessService) :
          mWatchdogProcessService(watchdogProcessService) {}

    void expectVhalProcessIdentifier(const Matcher<const ProcessIdentifier&> matcher) {
        Mutex::Autolock lock(mWatchdogProcessService->mMutex);
        EXPECT_TRUE(mWatchdogProcessService->mVhalProcessIdentifier.has_value());
        EXPECT_THAT(mWatchdogProcessService->mVhalProcessIdentifier.value(), matcher);
    }

    void expectNoVhalProcessIdentifier() {
        EXPECT_FALSE(mWatchdogProcessService->mVhalProcessIdentifier.has_value());
    }

    void setWatchdogProcessServiceState(
            bool isEnabled,
            std::shared_ptr<aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor>
                    monitor,
            std::chrono::nanoseconds overriddenClientHealthCheckWindowNs,
            std::unordered_set<userid_t> stoppedUserIds,
            const ProcessIdentifier& processIdentifier) {
        Mutex::Autolock lock(mWatchdogProcessService->mMutex);
        mWatchdogProcessService->mIsEnabled = isEnabled;
        mWatchdogProcessService->mMonitor = monitor;
        mWatchdogProcessService->mOverriddenClientHealthCheckWindowNs =
                overriddenClientHealthCheckWindowNs;
        mWatchdogProcessService->mStoppedUserIds = stoppedUserIds;
        mWatchdogProcessService->mVhalProcessIdentifier = processIdentifier;

        WatchdogProcessService::ClientInfoMap clientInfoMap;
        WatchdogProcessService::ClientInfo clientInfo(
                /*client=*/nullptr,
                /*pid=*/1, kTestAidlClientUid,
                /*processName=*/"",
                /*startTimeMillis=*/1000, WatchdogProcessService(nullptr, nullptr));

        clientInfo.packageName = "shell";
        clientInfoMap.insert({100, clientInfo});
        mWatchdogProcessService->mClientsByTimeout.clear();
        mWatchdogProcessService->mClientsByTimeout.insert(
                {TimeoutLength::TIMEOUT_CRITICAL, clientInfoMap});
    }

    void setOverriddenClientHealthCheckWindowNs(
            std::chrono::nanoseconds overriddenClientHealthCheckWindowNs) {
        mWatchdogProcessService->mOverriddenClientHealthCheckWindowNs =
                overriddenClientHealthCheckWindowNs;
    }

    void clearClientsByTimeout() { mWatchdogProcessService->mClientsByTimeout.clear(); }

    bool hasClientInfoWithPackageName(TimeoutLength timeoutLength, std::string packageName) {
        auto clientInfoMap = mWatchdogProcessService->mClientsByTimeout[timeoutLength];
        for (const auto& [_, clientInfo] : clientInfoMap) {
            if (clientInfo.packageName == packageName) {
                return true;
            }
        }
        return false;
    }

    bool hasClientInfoWithProcessName(TimeoutLength timeoutLength, std::string processName) {
        auto clientInfoMap = mWatchdogProcessService->mClientsByTimeout[timeoutLength];
        for (const auto& [_, clientInfo] : clientInfoMap) {
            if (clientInfo.kProcessName == processName) {
                return true;
            }
        }
        return false;
    }

    base::Result<void> constructAndRegisterClientInfo(
            const std::shared_ptr<ICarWatchdogClient>& client, int32_t pid, int32_t uid,
            std::string processName, int32_t startTimeMillis, TimeoutLength timeoutLength) {
        WatchdogProcessService::ClientInfo clientInfo(client, pid, uid, processName,
                                                      startTimeMillis, *mWatchdogProcessService);
        return mWatchdogProcessService->registerClient(clientInfo, timeoutLength);
    }

private:
    sp<WatchdogProcessService> mWatchdogProcessService;
};

}  // namespace internal

class WatchdogProcessServiceTest : public ::testing::Test {
public:
    WatchdogProcessServiceTest() :
          mMockVhalClient(nullptr),
          mMockHidlServiceManager(nullptr),
          kTryCreateVhalClientFunc([this]() { return mMockVhalClient; }),
          kTryGetHidlServiceManagerFunc([this]() { return mMockHidlServiceManager; }),
          kGetPidStatForPidFunc([](pid_t) {
              return PidStat{
                      .startTimeMillis = kTestPidStartTime,
                      .comm = kTestPidComm,
              };
          }),
          kGetUidForPidFunc([](pid_t) { return kTestAidlVhalUid; }),
          mVhalHeartbeatUpdateIntervalMillis(kTestVhalHealthCheckIntervalMillis) {}

protected:
    void SetUp() override {
        mMessageHandler = sp<MessageHandlerImpl>::make(this);
        mMockVehicle = SharedRefBase::make<NiceMock<MockVehicle>>();
        mMockVhalClient = std::make_shared<NiceMock<MockVhalClient>>(mMockVehicle);
        mMockHidlServiceManager = sp<MockHidlServiceManager>::make();
        mMockDeathRegistrationWrapper = sp<MockAIBinderDeathRegistrationWrapper>::make();
        mSupportedVehicleProperties = {VehicleProperty::VHAL_HEARTBEAT};
        mNotSupportedVehicleProperties = {VehicleProperty::WATCHDOG_ALIVE,
                                          VehicleProperty::WATCHDOG_TERMINATED_PROCESS};
        mMockPackageInfoResolver = std::make_shared<MockPackageInfoResolver>();
        mMockWatchdogMonitor = SharedRefBase::make<MockCarWatchdogMonitor>();
        ON_CALL(*mMockVehicle, subscribe(_, _, _))
                .WillByDefault([this](const std::shared_ptr<IVehicleCallback>& callback,
                                      const std::vector<SubscribeOptions>&, int32_t) {
                    {
                        std::lock_guard<std::mutex> lock(mMutex);
                        mSubscribedCallback = callback;
                    }
                    mLooperCondition.notify_all();
                    return ScopedAStatus::ok();
                });

        startService();
    }

    void TearDown() override {
        terminateService();
        mMockDeathRegistrationWrapper.clear();
        mMockHidlServiceManager.clear();
        mMockVhalClient.reset();
        mMockVehicle.reset();
        mMessageHandler.clear();
        mMockPackageInfoResolver.reset();
    }

    void startService() {
        prepareLooper();
        mWatchdogProcessService =
                sp<WatchdogProcessService>::make(kTryCreateVhalClientFunc,
                                                 kTryGetHidlServiceManagerFunc,
                                                 kGetPidStatForPidFunc, kGetUidForPidFunc,
                                                 kTestVhalPidCachingRetryDelayNs, mHandlerLooper,
                                                 mMockDeathRegistrationWrapper,
                                                 kTestVhalHealthCheckIntervalMillis,
                                                 kTestVhalHealthCheckDelayMillis,
                                                 mMockPackageInfoResolver);
        mWatchdogProcessServicePeer =
                std::make_unique<internal::WatchdogProcessServicePeer>(mWatchdogProcessService);

        expectGetPropConfigs(mSupportedVehicleProperties, mNotSupportedVehicleProperties);

        mWatchdogProcessService->start();
        // Sync with the looper before proceeding to ensure that all startup looper messages are
        // processed before testing the service.
        ASSERT_TRUE(syncLooper())
                << "Looper not finish handling pending tasks before timeout, probably stuck";
    }

    void terminateService() {
        wakeAndJoinLooper();
        mWatchdogProcessServicePeer.reset();
        mWatchdogProcessService->terminate();
        mWatchdogProcessService.clear();
        mHandlerLooper.clear();
    }

    bool waitForSubscribedCallbackSet(std::chrono::milliseconds timeoutMillis) {
        std::unique_lock<std::mutex> lock(mMutex);
        return mLooperCondition.wait_for(lock, timeoutMillis,
                                         [this] { return mSubscribedCallback != nullptr; });
    }

    void scheduleVhalHeartBeatUpdate(std::chrono::milliseconds delayMillis = 0ms) {
        mHandlerLooper->sendMessageDelayed(std::chrono::nanoseconds(delayMillis).count(),
                                           mMessageHandler,
                                           Message(TestMessage::UPDATE_VHAL_HEARTBEAT));
    }

    void setVhalHeartbeatUpdateInterval(
            std::chrono::milliseconds vhalHeartbeatUpdateIntervalMillis) {
        mVhalHeartbeatUpdateIntervalMillis = vhalHeartbeatUpdateIntervalMillis;
    }

    void expectLinkToDeath(AIBinder* aiBinder, ScopedAStatus expectedStatus) {
        EXPECT_CALL(*mMockDeathRegistrationWrapper,
                    linkToDeath(Eq(aiBinder), _, static_cast<void*>(aiBinder)))
                .WillOnce(Return(ByMove(std::move(expectedStatus))));
    }

    void expectUnlinkToDeath(AIBinder* aiBinder, ScopedAStatus expectedStatus) {
        EXPECT_CALL(*mMockDeathRegistrationWrapper,
                    unlinkToDeath(Eq(aiBinder), _, static_cast<void*>(aiBinder)))
                .WillOnce(Return(ByMove(std::move(expectedStatus))));
    }

    void expectNoUnlinkToDeath(AIBinder* aiBinder) {
        EXPECT_CALL(*mMockDeathRegistrationWrapper,
                    unlinkToDeath(Eq(aiBinder), _, static_cast<void*>(aiBinder)))
                .Times(0);
    }

    void expectGetPropConfigs(const std::vector<VehicleProperty>& supportedProperties,
                              const std::vector<VehicleProperty>& notSupportedProperties) {
        for (const auto& propId : supportedProperties) {
            EXPECT_CALL(*mMockVhalClient,
                        getPropConfigs(std::vector<int32_t>{static_cast<int32_t>(propId)}))
                    .WillOnce([]() { return std::vector<std::unique_ptr<IHalPropConfig>>(); });
        }
        for (const auto& propId : notSupportedProperties) {
            EXPECT_CALL(*mMockVhalClient,
                        getPropConfigs(std::vector<int32_t>{static_cast<int32_t>(propId)}))
                    .WillOnce(
                            []() -> VhalClientResult<std::vector<std::unique_ptr<IHalPropConfig>>> {
                                return Error<VhalClientError>(ErrorCode::NOT_AVAILABLE_FROM_VHAL)
                                        << "Not supported";
                            });
        }
    }

    // Expect the requestAidlVhalPid call from the implementation on registering CarWatchdogService
    // and mimic CarWatchdogService response by posting the onAidlVhalPidFetched call on the looper.
    void expectRequestAidlVhalPidAndRespond(
            const sp<MockWatchdogServiceHelper>& mockServiceHelper) {
        EXPECT_CALL(*mockServiceHelper, requestAidlVhalPid()).WillOnce([&]() {
            mHandlerLooper->sendMessageDelayed(kTestVhalPidCachingRetryDelayNs.count() / 2,
                                               mMessageHandler,
                                               Message(TestMessage::ON_AIDL_VHAL_PID));
            return ScopedAStatus::ok();
        });
    }

    // Wait for ON_AIDL_VHAL_PID message is handled. This is only supposed to be called once.
    // If you need to wait for another ON_AIDL_VHAL_PID to be handled, mOnAidlVhalPidHandled must
    // be reset to false.
    bool waitForOnAidlVhalPidHandled(std::chrono::milliseconds timeoutMillis) {
        std::unique_lock lock(mMutex);
        return mLooperCondition.wait_for(lock, timeoutMillis,
                                         [this] { return mOnAidlVhalPidHandled; });
    }

    // Finishes all the posted tasks that are supposed to run inside the handler.
    bool syncLooper(std::chrono::nanoseconds delay = 0ns) {
        // Acquire the lock before sending message to avoid any race condition.
        std::unique_lock lock(mMutex);
        mLooperSynced = false;
        mHandlerLooper->sendMessageDelayed(delay.count(), mMessageHandler,
                                           Message(TestMessage::SYNC_LOOPER_MARKER));
        return mLooperCondition
                .wait_for(lock,
                          kMaxWaitForLooperExecutionMillis +
                                  std::chrono::duration_cast<std::chrono::milliseconds>(delay),
                          [this] { return mLooperSynced; });
    }

    bool waitUntilVhalPidCachingAttemptsExhausted() {
        return syncLooper((kMaxVhalPidCachingAttempts + 1) * kTestVhalPidCachingRetryDelayNs);
    }

    std::string toString(util::ProtoOutputStream* proto) {
        std::string content;
        content.reserve(proto->size());
        sp<ProtoReader> reader = proto->data();
        while (reader->hasNext()) {
            content.push_back(reader->next());
        }
        return content;
    }

    void setupMockCarServiceAndWaitForAidlVhalPidFetched();

    sp<WatchdogProcessService> mWatchdogProcessService;
    std::unique_ptr<internal::WatchdogProcessServicePeer> mWatchdogProcessServicePeer;
    std::shared_ptr<NiceMock<MockVhalClient>> mMockVhalClient;
    std::shared_ptr<NiceMock<MockVehicle>> mMockVehicle;
    sp<MockHidlServiceManager> mMockHidlServiceManager;
    sp<MockAIBinderDeathRegistrationWrapper> mMockDeathRegistrationWrapper;
    std::vector<VehicleProperty> mSupportedVehicleProperties;
    std::vector<VehicleProperty> mNotSupportedVehicleProperties;
    std::shared_ptr<MockPackageInfoResolver> mMockPackageInfoResolver;
    std::shared_ptr<MockCarWatchdogMonitor> mMockWatchdogMonitor;

private:
    class MessageHandlerImpl : public android::MessageHandler {
    public:
        explicit MessageHandlerImpl(WatchdogProcessServiceTest* test) : mTest(test) {}

        void handleMessage(const Message& message) override {
            switch (message.what) {
                case static_cast<int>(TestMessage::SYNC_LOOPER_MARKER):
                    mTest->handleSyncLooperMarker();
                    break;
                case static_cast<int>(TestMessage::ON_AIDL_VHAL_PID):
                    mTest->handleOnAidlVhalPid();
                    break;
                case static_cast<int>(TestMessage::UPDATE_VHAL_HEARTBEAT):
                    mTest->updateVhalHeartBeat();
                    break;
                default:
                    ALOGE("Unknown TestMessage: %d", message.what);
                    return;
            }
            std::lock_guard lock(mTest->mMutex);
            mTest->mLooperCondition.notify_all();
        }

    private:
        WatchdogProcessServiceTest* mTest;
    };

    // Looper runs on the calling thread when it is polled for messages with the poll* calls.
    // The poll* calls are blocking, so they must be executed on a separate thread.
    void prepareLooper() {
        mHandlerLooper = sp<Looper>::make(/*allowNonCallbacks=*/false);
        mHandlerLooperThread = std::thread([this]() {
            Looper::setForThread(mHandlerLooper);
            if (int result = pthread_setname_np(pthread_self(), kTestLooperThreadName);
                result != 0) {
                ALOGE("Failed to set test looper thread name: %s", strerror(result));
            }
            mShouldTerminateLooper.store(false);
            while (!mShouldTerminateLooper.load()) {
                mHandlerLooper->pollAll(/*timeoutMillis=*/-1);
            }
        });
    }

    void wakeAndJoinLooper() {
        // Sync with the looper to make sure all messages for the current time slot are processed
        // before terminating the looper. This will help satisfy any pending EXPECT_CALLs.
        syncLooper();
        mShouldTerminateLooper.store(true);
        mHandlerLooper->wake();
        if (mHandlerLooperThread.joinable()) {
            mHandlerLooperThread.join();
        }
    }

    void updateVhalHeartBeat() {
        std::shared_ptr<IVehicleCallback> callback;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            callback = mSubscribedCallback;
        }
        int64_t now = uptimeNanos();
        callback->onPropertyEvent(
                VehiclePropValues{
                        .payloads = {VehiclePropValue{
                                .timestamp = now,
                                .prop = static_cast<int32_t>(VehicleProperty::VHAL_HEARTBEAT),
                                .areaId = 0,
                                .value =
                                        RawPropValues{
                                                .int64Values = {now},
                                        },
                        }},
                },
                /*sharedMemoryCount=*/0);
        if (mShouldTerminateLooper.load()) {
            return;
        }
        // Schedule the next VHAL heartbeat update unless the test is ending.
        scheduleVhalHeartBeatUpdate(mVhalHeartbeatUpdateIntervalMillis);
    }

    void handleOnAidlVhalPid() {
        mWatchdogProcessService->onAidlVhalPidFetched(kTestAidlVhalPid);
        {
            std::lock_guard<std::mutex> lock(mMutex);
            mOnAidlVhalPidHandled = true;
        }
        mLooperCondition.notify_all();
    }

    void handleSyncLooperMarker() {
        {
            std::lock_guard<std::mutex> lock(mMutex);
            mLooperSynced = true;
        }
        mLooperCondition.notify_all();
    }

    const std::function<std::shared_ptr<IVhalClient>()> kTryCreateVhalClientFunc;
    const std::function<android::sp<android::hidl::manager::V1_0::IServiceManager>()>
            kTryGetHidlServiceManagerFunc;
    const std::function<PidStat(pid_t)> kGetPidStatForPidFunc;
    const std::function<uid_t(pid_t)> kGetUidForPidFunc;

    sp<Looper> mHandlerLooper;
    sp<MessageHandlerImpl> mMessageHandler;
    std::thread mHandlerLooperThread;
    mutable std::mutex mMutex;
    std::atomic<bool> mShouldTerminateLooper;
    std::atomic<std::chrono::milliseconds> mVhalHeartbeatUpdateIntervalMillis;

    std::condition_variable mLooperCondition;
    // The following are variables that notified by mLooperCondition;
    std::shared_ptr<IVehicleCallback> mSubscribedCallback GUARDED_BY(mMutex);
    bool mOnAidlVhalPidHandled GUARDED_BY(mMutex) = false;
    bool mLooperSynced GUARDED_BY(mMutex) = false;
};

void WatchdogProcessServiceTest::setupMockCarServiceAndWaitForAidlVhalPidFetched() {
    sp<MockWatchdogServiceHelper> mockServiceHelper = sp<MockWatchdogServiceHelper>::make();

    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    const auto binder = mockService->asBinder();
    ON_CALL(*mMockPackageInfoResolver, asyncFetchPackageNamesForUids(_, _))
            .WillByDefault([&](const std::vector<uid_t>& uids,
                               const std::function<void(std::unordered_map<uid_t, std::string>)>&
                                       callback) { callback({{uids[0], "shell"}}); });
    // For mock services, if they are checked, they will respond alive to prevent being killed.
    ON_CALL(*mockService, checkIfAlive).WillByDefault([]() { return ScopedAStatus::ok(); });
    ON_CALL(*mockServiceHelper, checkIfAlive).WillByDefault([]() { return ScopedAStatus::ok(); });

    expectRequestAidlVhalPidAndRespond(mockServiceHelper);

    auto status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();

    ASSERT_TRUE(waitForOnAidlVhalPidHandled(kMaxWaitForLooperExecutionMillis))
            << "ON_AIDL_VHAL_PID is not handled before timeout";

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectVhalProcessIdentifier(
            ProcessIdentifierEq(constructProcessIdentifier(kTestAidlVhalPid, kTestAidlVhalUid,
                                                           kTestPidComm, kTestPidStartTime))));
}

TEST_F(WatchdogProcessServiceTest, TestTerminate) {
    std::vector<int32_t> propIds = {static_cast<int32_t>(VehicleProperty::VHAL_HEARTBEAT)};
    EXPECT_CALL(*mMockVhalClient, removeOnBinderDiedCallback(_)).Times(1);
    EXPECT_CALL(*mMockVehicle, unsubscribe(_, propIds))
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));
    mWatchdogProcessService->terminate();
    // TODO(b/217405065): Verify looper removes all MSG_VHAL_HEALTH_CHECK messages.
}

// TODO(b/217405065): Add test to verify the handleVhalDeath method.

TEST_F(WatchdogProcessServiceTest, TestRegisterClient) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    expectLinkToDeath(client->asBinder().get(), ScopedAStatus::ok());

    auto status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogProcessServiceTest, TestUnregisterClient) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    AIBinder* aiBinder = client->asBinder().get();
    expectLinkToDeath(aiBinder, ScopedAStatus::ok());

    auto status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    expectUnlinkToDeath(aiBinder, ScopedAStatus::ok());

    status = mWatchdogProcessService->unregisterClient(client);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_FALSE(mWatchdogProcessService->unregisterClient(client).isOk())
            << "Unregistering an unregistered client should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestErrorOnRegisterClientWithDeadBinder) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    expectLinkToDeath(client->asBinder().get(),
                      ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED));

    ASSERT_FALSE(
            mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL).isOk())
            << "When linkToDeath fails, registerClient should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestHandleClientBinderDeath) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    AIBinder* aiBinder = client->asBinder().get();
    expectLinkToDeath(aiBinder, ScopedAStatus::ok());

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

    EXPECT_CALL(*mockServiceHelper, requestAidlVhalPid())
            .WillOnce(Return(ByMove(ScopedAStatus::ok())));

    auto status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();

    // The implementation posts message on the looper to cache VHAL pid when registering
    // the car watchdog service. So, sync with the looper to ensure the above requestAidlVhalPid
    // EXPECT_CALL is satisfied.
    ASSERT_TRUE(syncLooper())
            << "Looper not finish handling pending tasks before timeout, probably stuck";

    // No new request to fetch AIDL VHAL pid should be sent on duplicate registration.
    EXPECT_CALL(*mockServiceHelper, requestAidlVhalPid()).Times(0);

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
    expectLinkToDeath(monitorOne->asBinder().get(), ScopedAStatus::ok());

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
                      ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED));

    ASSERT_FALSE(mWatchdogProcessService->registerMonitor(monitor).isOk())
            << "When linkToDeath fails, registerMonitor should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestUnregisterMonitor) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    AIBinder* aiBinder = monitor->asBinder().get();
    expectLinkToDeath(aiBinder, ScopedAStatus::ok());

    auto status = mWatchdogProcessService->registerMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    expectUnlinkToDeath(aiBinder, ScopedAStatus::ok());

    status = mWatchdogProcessService->unregisterMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_FALSE(mWatchdogProcessService->unregisterMonitor(monitor).isOk())
            << "Unregistering an unregistered monitor should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestHandleMonitorBinderDeath) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    AIBinder* aiBinder = monitor->asBinder().get();
    expectLinkToDeath(aiBinder, ScopedAStatus::ok());

    auto status = mWatchdogProcessService->registerMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    mWatchdogProcessService->handleBinderDeath(static_cast<void*>(aiBinder));

    expectNoUnlinkToDeath(aiBinder);

    ASSERT_FALSE(mWatchdogProcessService->unregisterMonitor(monitor).isOk())
            << "Unregistering a dead monitor should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestTellClientAlive) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    expectLinkToDeath(client->asBinder().get(), ScopedAStatus::ok());

    mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_FALSE(mWatchdogProcessService->tellClientAlive(client, 1234).isOk())
            << "tellClientAlive not synced with checkIfAlive should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestTellCarWatchdogServiceAlive) {
    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();

    std::vector<ProcessIdentifier> processIdentifiers;
    processIdentifiers.push_back(constructProcessIdentifier(/*pid=*/111, /*uid=*/1,
                                                            /*processName=*/"process1",
                                                            /*startTimeMillis=*/0));
    processIdentifiers.push_back(constructProcessIdentifier(/*pid=*/222, /*uid=*/2,
                                                            /*processName=*/"process2",
                                                            /*startTimeMillis=*/0));
    ASSERT_FALSE(mWatchdogProcessService
                         ->tellCarWatchdogServiceAlive(mockService, processIdentifiers, 1234)
                         .isOk())
            << "tellCarWatchdogServiceAlive not synced with checkIfAlive should return an error";
}

TEST_F(WatchdogProcessServiceTest, TestTellDumpFinished) {
    std::shared_ptr<ICarWatchdogMonitor> monitor =
            SharedRefBase::make<ICarWatchdogMonitorDefault>();
    std::vector<ProcessIdentifier> processIdentifiers;
    processIdentifiers.push_back(constructProcessIdentifier(/*pid=*/1234,
                                                            /*uid=*/1,
                                                            /*processName=*/"process",
                                                            /*startTimeMillis=*/0));
    ASSERT_FALSE(mWatchdogProcessService->tellDumpFinished(monitor, processIdentifiers).isOk())
            << "Unregistered monitor cannot call tellDumpFinished";

    expectLinkToDeath(monitor->asBinder().get(), ScopedAStatus::ok());

    mWatchdogProcessService->registerMonitor(monitor);
    auto status = mWatchdogProcessService->tellDumpFinished(monitor, processIdentifiers);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogProcessServiceTest, TestCacheAidlVhalPidFromCarWatchdogService) {
    sp<MockWatchdogServiceHelper> mockServiceHelper = sp<MockWatchdogServiceHelper>::make();

    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    const auto binder = mockService->asBinder();

    expectRequestAidlVhalPidAndRespond(mockServiceHelper);

    auto status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();

    // On processing the TestMessage::ON_AIDL_VHAL_PID, the looper notifies all waiting threads.
    // Wait for the notification to ensure the VHAL pid caching is satisfied.
    ASSERT_TRUE(waitForOnAidlVhalPidHandled(kMaxWaitForLooperExecutionMillis))
            << "ON_AIDL_VHAL_PID is not handled before timeout";

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectVhalProcessIdentifier(
            ProcessIdentifierEq(constructProcessIdentifier(kTestAidlVhalPid, kTestAidlVhalUid,
                                                           kTestPidComm, kTestPidStartTime))));
}

TEST_F(WatchdogProcessServiceTest, TestFailsCacheAidlVhalPidWithNoCarWatchdogServiceResponse) {
    sp<MockWatchdogServiceHelper> mockServiceHelper = sp<MockWatchdogServiceHelper>::make();

    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    const auto binder = mockService->asBinder();

    EXPECT_CALL(*mockServiceHelper, requestAidlVhalPid())
            .Times(kMaxVhalPidCachingAttempts)
            .WillRepeatedly([&]() {
                // No action taken by CarWatchdogService.
                return ScopedAStatus::ok();
            });

    auto status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();

    // Because CarWatchdogService doesn't respond with the AIDL VHAL pid, wait until all caching
    // attempts are exhausted to ensure the expected number of caching attempts are satisfied.
    ASSERT_TRUE(waitUntilVhalPidCachingAttemptsExhausted())
            << "Looper not finish handling pending tasks before timeout, probably stuck";

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectNoVhalProcessIdentifier());
}

TEST_F(WatchdogProcessServiceTest, TestNoCacheAidlVhalPidWithUnsupportedVhalHeartBeatProperty) {
    // The supported vehicle property list is fetched as soon as VHAL is connected, which happens
    // during the start of the service. So, restart the service for the new VHAL settings to take
    // effect.
    terminateService();

    mSupportedVehicleProperties.clear();
    mNotSupportedVehicleProperties.push_back(VehicleProperty::VHAL_HEARTBEAT);

    startService();

    sp<MockWatchdogServiceHelper> mockServiceHelper = sp<MockWatchdogServiceHelper>::make();
    std::shared_ptr<MockCarWatchdogServiceForSystem> mockService =
            SharedRefBase::make<MockCarWatchdogServiceForSystem>();
    const auto binder = mockService->asBinder();

    EXPECT_CALL(*mockServiceHelper, requestAidlVhalPid()).Times(0);

    auto status = mWatchdogProcessService->registerCarWatchdogService(binder, mockServiceHelper);
    ASSERT_TRUE(status.isOk()) << status.getMessage();

    // VHAL process identifier caching happens on the looper thread. Sync with the looper before
    // proceeding.
    ASSERT_TRUE(syncLooper())
            << "Looper not finish handling pending tasks before timeout, probably stuck";

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectNoVhalProcessIdentifier());
}

TEST_F(WatchdogProcessServiceTest, TestCacheHidlVhalPidFromHidlServiceManager) {
    // VHAL PID caching logic is determined as soon as VHAL is connected, which happens during
    // the start of the service. So, restart the service for the new VHAL settings to take effect.
    terminateService();

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

    startService();

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectVhalProcessIdentifier(
            ProcessIdentifierEq(constructProcessIdentifier(2034, kTestAidlVhalUid, kTestPidComm,
                                                           kTestPidStartTime))));
}

TEST_F(WatchdogProcessServiceTest, TestFailsCacheHidlVhalPidWithNoHidlVhalService) {
    // VHAL PID caching logic is determined as soon as VHAL is connected, which happens during
    // the start of the service. So, restart the service for the new VHAL settings to take effect.
    terminateService();

    using InstanceDebugInfo = IServiceManager::InstanceDebugInfo;
    EXPECT_CALL(*mMockVhalClient, isAidlVhal()).WillRepeatedly(Return(false));
    EXPECT_CALL(*mMockHidlServiceManager, debugDump(_))
            .Times(kMaxVhalPidCachingAttempts)
            .WillRepeatedly(Invoke([](IServiceManager::debugDump_cb cb) {
                cb({InstanceDebugInfo{"android.hardware.automotive.evs@1.0::IEvsCamera",
                                      "vehicle_hal_insts",
                                      8058,
                                      {},
                                      DebugInfo::Architecture::IS_64BIT}});
                return android::hardware::Void();
            }));

    startService();

    // Because HIDL service manager doesn't have the HIDL VHAL pid, wait until all caching
    // attempts are exhausted to ensure the expected number of caching attempts are satisfied.
    ASSERT_TRUE(waitUntilVhalPidCachingAttemptsExhausted())
            << "Looper not finish handling pending tasks before timeout, probably stuck";

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectNoVhalProcessIdentifier());
}

TEST_F(WatchdogProcessServiceTest, TestNoCacheHidlVhalPidWithUnsupportedVhalHeartBeatProperty) {
    // The supported vehicle property list is fetched as soon as VHAL is connected, which happens
    // during the start of the service. So, restart the service for the new VHAL settings to take
    // effect.
    terminateService();

    mSupportedVehicleProperties.clear();
    mNotSupportedVehicleProperties.push_back(VehicleProperty::VHAL_HEARTBEAT);

    EXPECT_CALL(*mMockHidlServiceManager, debugDump(_)).Times(0);

    startService();

    ASSERT_NO_FATAL_FAILURE(mWatchdogProcessServicePeer->expectNoVhalProcessIdentifier());
}

TEST_F(WatchdogProcessServiceTest, TestOnDumpProto) {
    ProcessIdentifier processIdentifier;
    processIdentifier.pid = 1;
    processIdentifier.uid = kTestAidlClientUid;
    processIdentifier.processName = "process";
    processIdentifier.startTimeMillis = 1000;

    mWatchdogProcessServicePeer->setWatchdogProcessServiceState(true, nullptr,
                                                                std::chrono::milliseconds(20000),
                                                                {101, 102}, processIdentifier);

    util::ProtoOutputStream proto;
    mWatchdogProcessService->onDumpProto(proto);

    CarWatchdogDaemonDump carWatchdogDaemonDump;
    ASSERT_TRUE(carWatchdogDaemonDump.ParseFromString(toString(&proto)));
    HealthCheckServiceDump healthCheckServiceDump =
            carWatchdogDaemonDump.health_check_service_dump();
    EXPECT_EQ(healthCheckServiceDump.is_enabled(), true);
    EXPECT_EQ(healthCheckServiceDump.is_monitor_registered(), false);
    EXPECT_EQ(healthCheckServiceDump.is_system_shut_down_in_progress(), false);
    EXPECT_EQ(healthCheckServiceDump.stopped_users_size(), 2);
    EXPECT_EQ(healthCheckServiceDump.critical_health_check_window_millis(), 20000);
    EXPECT_EQ(healthCheckServiceDump.moderate_health_check_window_millis(), 20000);
    EXPECT_EQ(healthCheckServiceDump.normal_health_check_window_millis(), 20000);

    VhalHealthCheckInfo vhalHealthCheckInfo = healthCheckServiceDump.vhal_health_check_info();

    EXPECT_EQ(vhalHealthCheckInfo.is_enabled(), true);
    EXPECT_EQ(vhalHealthCheckInfo.health_check_window_millis(),
              (kTestVhalHealthCheckIntervalMillis + kTestVhalHealthCheckDelayMillis).count());
    EXPECT_EQ(vhalHealthCheckInfo.pid_caching_progress_state(),
              VhalHealthCheckInfo_CachingProgressState_SUCCESS);
    EXPECT_EQ(vhalHealthCheckInfo.pid(), 1);
    EXPECT_EQ(vhalHealthCheckInfo.start_time_millis(), 1000);

    EXPECT_EQ(healthCheckServiceDump.registered_client_infos_size(), 1);
    HealthCheckClientInfo healthCheckClientInfo = healthCheckServiceDump.registered_client_infos(0);
    EXPECT_EQ(healthCheckClientInfo.pid(), 1);

    UserPackageInfo userPackageInfo = healthCheckClientInfo.user_package_info();
    EXPECT_EQ(userPackageInfo.user_id(),
              static_cast<int>(multiuser_get_user_id(kTestAidlClientUid)));
    EXPECT_EQ(userPackageInfo.package_name(), "shell");

    EXPECT_EQ(healthCheckClientInfo.client_type(), HealthCheckClientInfo_ClientType_REGULAR);
    EXPECT_EQ(healthCheckClientInfo.start_time_millis(), 1000);
    EXPECT_EQ(healthCheckClientInfo.health_check_timeout(),
              HealthCheckClientInfo_HealthCheckTimeout_CRITICAL);

    // Clean up test clients before exiting.
    mWatchdogProcessServicePeer->clearClientsByTimeout();
}

TEST_F(WatchdogProcessServiceTest, TestRegisterClientWithPackageName) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    ON_CALL(*mMockPackageInfoResolver, asyncFetchPackageNamesForUids(_, _))
            .WillByDefault([&](const std::vector<uid_t>& uids,
                               const std::function<void(std::unordered_map<uid_t, std::string>)>&
                                       callback) { callback({{uids[0], "shell"}}); });

    ASSERT_FALSE(mWatchdogProcessServicePeer
                         ->hasClientInfoWithPackageName(TimeoutLength::TIMEOUT_CRITICAL, "shell"));
    ASSERT_FALSE(
            mWatchdogProcessServicePeer
                    ->hasClientInfoWithProcessName(TimeoutLength::TIMEOUT_CRITICAL, kTestPidComm));

    auto status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(mWatchdogProcessServicePeer
                        ->hasClientInfoWithPackageName(TimeoutLength::TIMEOUT_CRITICAL, "shell"));
    ASSERT_TRUE(
            mWatchdogProcessServicePeer
                    ->hasClientInfoWithProcessName(TimeoutLength::TIMEOUT_CRITICAL, kTestPidComm));
}

TEST_F(WatchdogProcessServiceTest, TestRegisterClientWithPackageNameAndNonExistentUid) {
    std::shared_ptr<ICarWatchdogClient> client = SharedRefBase::make<ICarWatchdogClientDefault>();
    ON_CALL(*mMockPackageInfoResolver, asyncFetchPackageNamesForUids(_, _))
            .WillByDefault([&](const std::vector<uid_t>& uids,
                               const std::function<void(std::unordered_map<uid_t, std::string>)>&
                                       callback) {
                callback({});
                ALOGI("No corresponding packageName for uid: %i", uids[0]);
            });

    ASSERT_FALSE(mWatchdogProcessServicePeer
                         ->hasClientInfoWithPackageName(TimeoutLength::TIMEOUT_CRITICAL, "shell"));
    ASSERT_FALSE(
            mWatchdogProcessServicePeer
                    ->hasClientInfoWithProcessName(TimeoutLength::TIMEOUT_CRITICAL, kTestPidComm));

    auto status = mWatchdogProcessService->registerClient(client, TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_FALSE(mWatchdogProcessServicePeer
                         ->hasClientInfoWithPackageName(TimeoutLength::TIMEOUT_CRITICAL, "shell"));
    ASSERT_TRUE(
            mWatchdogProcessServicePeer
                    ->hasClientInfoWithProcessName(TimeoutLength::TIMEOUT_CRITICAL, kTestPidComm));
}

TEST_F(WatchdogProcessServiceTest, TestDumpAndKillAllProcessesDuringGarageMode) {
    if (!car_watchdog_anr_metrics()) {
        GTEST_SKIP() << "car_watchdog_anr_metrics feature flag is not enabled";
    }

    auto status = mWatchdogProcessService->registerMonitor(mMockWatchdogMonitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    mWatchdogProcessServicePeer->setOverriddenClientHealthCheckWindowNs(
            std::chrono::nanoseconds(1));

    mWatchdogProcessService->setGarageMode(GarageMode::GARAGE_MODE_ON);

    ClientsNotRespondingInfo clientsNotRespondingInfo = {
            .processIdentifiers = {constructProcessIdentifier(/*pid=*/111, /*uid=*/1,
                                                              /*processName=*/"process1",
                                                              /*startTimeMillis=*/0)},
            .garageMode = GarageMode::GARAGE_MODE_ON,
    };

    EXPECT_CALL(*mMockWatchdogMonitor,
                onClientsNotRespondingWithSystemState(
                        ClientsNotRespondingInfoEq(clientsNotRespondingInfo)))
            .Times(1);

    std::shared_ptr<MockCarWatchdogClient> client1 = createMockCarWatchdogClient();
    expectLinkToDeath(client1->asBinder().get(), ScopedAStatus::ok());

    EXPECT_CALL(*client1, checkIfAlive(_, TimeoutLength::TIMEOUT_CRITICAL)).Times(1);
    EXPECT_CALL(*client1, prepareProcessTermination()).Times(1);

    base::Result<void> result =
            mWatchdogProcessServicePeer
                    ->constructAndRegisterClientInfo(client1, 111, 1, "process1", 0,
                                                     TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(result.ok()) << result.error().message();
    ASSERT_TRUE(syncLooper(2ns))
            << "Looper not finished handling pending tasks before timeout, probably stuck";
}

TEST_F(WatchdogProcessServiceTest, TestDumpAndKillAllProcesses) {
    if (!car_watchdog_anr_metrics()) {
        GTEST_SKIP() << "car_watchdog_anr_metrics feature flag is not enabled";
    }

    auto status = mWatchdogProcessService->registerMonitor(mMockWatchdogMonitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    mWatchdogProcessServicePeer->setOverriddenClientHealthCheckWindowNs(
            std::chrono::nanoseconds(1));

    mWatchdogProcessService->setGarageMode(GarageMode::GARAGE_MODE_OFF);

    ClientsNotRespondingInfo clientsNotRespondingInfo = {
            .processIdentifiers = {constructProcessIdentifier(/*pid=*/111, /*uid=*/1,
                                                              /*processName=*/"process1",
                                                              /*startTimeMillis=*/0)},
            .garageMode = GarageMode::GARAGE_MODE_OFF,
    };

    EXPECT_CALL(*mMockWatchdogMonitor,
                onClientsNotRespondingWithSystemState(
                        ClientsNotRespondingInfoEq(clientsNotRespondingInfo)))
            .Times(1);

    std::shared_ptr<MockCarWatchdogClient> client1 = createMockCarWatchdogClient();
    expectLinkToDeath(client1->asBinder().get(), ScopedAStatus::ok());

    EXPECT_CALL(*client1, checkIfAlive(_, TimeoutLength::TIMEOUT_CRITICAL)).Times(1);
    EXPECT_CALL(*client1, prepareProcessTermination()).Times(1);

    base::Result<void> result =
            mWatchdogProcessServicePeer
                    ->constructAndRegisterClientInfo(client1, 111, 1, "process1", 0,
                                                     TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(result.ok()) << result.error().message();
    ASSERT_TRUE(syncLooper(2ns))
            << "Looper not finished handling pending tasks before timeout, probably stuck";
}

TEST_F(WatchdogProcessServiceTest, TestDumpAndKillAllProcessesWithAnrMetricsFeatureDisabled) {
    if (car_watchdog_anr_metrics()) {
        GTEST_SKIP() << "car_watchdog_anr_metrics feature flag is not disabled";
    }

    auto status = mWatchdogProcessService->registerMonitor(mMockWatchdogMonitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    mWatchdogProcessServicePeer->setOverriddenClientHealthCheckWindowNs(
            std::chrono::nanoseconds(1));

    EXPECT_CALL(*mMockWatchdogMonitor,
                onClientsNotResponding(UnorderedElementsAreArray(constructProcessIdentifierMatchers(
                        {constructProcessIdentifier(/*pid=*/111, /*uid=*/1,
                                                    /*processName=*/"process1",
                                                    /*startTimeMillis=*/0)}))))
            .Times(1);

    std::shared_ptr<MockCarWatchdogClient> client1 = createMockCarWatchdogClient();
    expectLinkToDeath(client1->asBinder().get(), ScopedAStatus::ok());

    EXPECT_CALL(*client1, checkIfAlive(_, TimeoutLength::TIMEOUT_CRITICAL)).Times(1);
    EXPECT_CALL(*client1, prepareProcessTermination()).Times(1);

    base::Result<void> result =
            mWatchdogProcessServicePeer
                    ->constructAndRegisterClientInfo(client1, 111, 1, "process1", 0,
                                                     TimeoutLength::TIMEOUT_CRITICAL);

    ASSERT_TRUE(result.ok()) << result.error().message();
    ASSERT_TRUE(syncLooper(2ns))
            << "Looper not finished handling pending tasks before timeout, probably stuck";
}

class TestCarWatchdogMonitor : public BnCarWatchdogMonitor {
public:
    ScopedAStatus onClientsNotResponding(const std::vector<ProcessIdentifier>& pids) override {
        {
            std::lock_guard lock(mMutex);
            mKilledPids = pids;
        }
        mCv.notify_all();
        return ScopedAStatus::ok();
    }

    ScopedAStatus onClientsNotRespondingWithSystemState(
            [[maybe_unused]] const ClientsNotRespondingInfo& clientsNotRespondingInfo) override {
        {
            std::lock_guard lock(mMutex);
            mKilledPids = clientsNotRespondingInfo.processIdentifiers;
        }
        mCv.notify_all();
        return ScopedAStatus::ok();
    }

    bool waitForPidsKilled(std::chrono::milliseconds timeoutInMs) {
        std::unique_lock lock(mMutex);
        return mCv.wait_for(lock, timeoutInMs, [this] { return mKilledPids.size() != 0; });
    }

    std::vector<ProcessIdentifier> getKilledPids() {
        std::lock_guard<std::mutex> lock(mMutex);
        return mKilledPids;
    }

private:
    std::mutex mMutex;
    std::condition_variable mCv GUARDED_BY(mMutex);
    std::vector<ProcessIdentifier> mKilledPids GUARDED_BY(mMutex);
};

// Verifies that if VHAL updates VHAL_HEARTBEAT property according to requirement, carwatchdog
// will not kill VHAL.
TEST_F(WatchdogProcessServiceTest, TestSuccessfulVhalHeartBeatUpdate) {
    // Restart service to support all properties.
    terminateService();
    mSupportedVehicleProperties = {VehicleProperty::VHAL_HEARTBEAT, VehicleProperty::WATCHDOG_ALIVE,
                                   VehicleProperty::WATCHDOG_TERMINATED_PROCESS};
    mNotSupportedVehicleProperties = {};
    startService();

    ASSERT_NO_FATAL_FAILURE(setupMockCarServiceAndWaitForAidlVhalPidFetched());

    std::shared_ptr<TestCarWatchdogMonitor> monitor = SharedRefBase::make<TestCarWatchdogMonitor>();

    auto status = mWatchdogProcessService->registerMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    ASSERT_TRUE(waitForSubscribedCallbackSet(1s)) << "IVehicle.subscribe is not called";

    scheduleVhalHeartBeatUpdate();

    ASSERT_FALSE(monitor->waitForPidsKilled(1s)) << "VHAL should not be killed";
}

// Verifies that if VHAL does not update VHAL_HEARTBEAT, carwatchdog should kill VHAL.
TEST_F(WatchdogProcessServiceTest, TestKillVhalOnMissingVhalHeartBeatUpdate) {
    // Restart service to support all properties.
    terminateService();
    mSupportedVehicleProperties = {VehicleProperty::VHAL_HEARTBEAT, VehicleProperty::WATCHDOG_ALIVE,
                                   VehicleProperty::WATCHDOG_TERMINATED_PROCESS};
    mNotSupportedVehicleProperties = {};
    startService();

    ASSERT_NO_FATAL_FAILURE(setupMockCarServiceAndWaitForAidlVhalPidFetched());

    std::shared_ptr<TestCarWatchdogMonitor> monitor = SharedRefBase::make<TestCarWatchdogMonitor>();

    auto status = mWatchdogProcessService->registerMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    // The check interval is 200ms, delay is 100ms, so we should expect to see the kill around
    // 300ms, wait for 1s to be safe.
    ASSERT_TRUE(monitor->waitForPidsKilled(1s)) << "Expect not-responding pids to be killed";
    const auto& killedPids = monitor->getKilledPids();
    ASSERT_THAT(killedPids, SizeIs(1));
    ASSERT_THAT(killedPids[0].pid, kTestAidlVhalPid);
}

// Verifies that if VHAL updates VHAL_HEARTBEAT too slow, carwatchdog should kill VHAL.
TEST_F(WatchdogProcessServiceTest, TestKillVhalOnDelayedVhalHeartBeatSecondUpdate) {
    // Restart service to support all properties.
    terminateService();
    mSupportedVehicleProperties = {VehicleProperty::VHAL_HEARTBEAT, VehicleProperty::WATCHDOG_ALIVE,
                                   VehicleProperty::WATCHDOG_TERMINATED_PROCESS};
    mNotSupportedVehicleProperties = {};
    startService();

    ASSERT_NO_FATAL_FAILURE(setupMockCarServiceAndWaitForAidlVhalPidFetched());

    std::shared_ptr<TestCarWatchdogMonitor> monitor = SharedRefBase::make<TestCarWatchdogMonitor>();

    auto status = mWatchdogProcessService->registerMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    ASSERT_TRUE(waitForSubscribedCallbackSet(1s)) << "IVehicle.subscribe is not called";

    // Schedule the first heart beat update to happen immediately. But set the update interval to
    // trigger the second heart beat update to happen outside the allowed health check window
    // (300ms).
    // This should trigger the watchdog killing after the first update and before the  delayed
    // second update.
    setVhalHeartbeatUpdateInterval(400ms);
    scheduleVhalHeartBeatUpdate();

    // We expect the kill to happen around 300ms, set this to 1s to be safe.
    ASSERT_TRUE(monitor->waitForPidsKilled(1s)) << "Expect not-responding pids to be killed";
    const auto& killedPids = monitor->getKilledPids();
    ASSERT_THAT(killedPids, SizeIs(1));
    ASSERT_THAT(killedPids[0].pid, kTestAidlVhalPid);
}

// Verifies that if the first VHAL_HEARTBEAT arrives too late, carwatchdog should kill VHAL.
TEST_F(WatchdogProcessServiceTest, TestKillVhalOnDelayedVhalHeartBeatFirstUpdate) {
    // Restart service to support all properties.
    terminateService();
    mSupportedVehicleProperties = {VehicleProperty::VHAL_HEARTBEAT, VehicleProperty::WATCHDOG_ALIVE,
                                   VehicleProperty::WATCHDOG_TERMINATED_PROCESS};
    mNotSupportedVehicleProperties = {};
    startService();

    ASSERT_NO_FATAL_FAILURE(setupMockCarServiceAndWaitForAidlVhalPidFetched());

    std::shared_ptr<TestCarWatchdogMonitor> monitor = SharedRefBase::make<TestCarWatchdogMonitor>();

    auto status = mWatchdogProcessService->registerMonitor(monitor);

    ASSERT_TRUE(status.isOk()) << status.getMessage();

    ASSERT_TRUE(waitForSubscribedCallbackSet(1s)) << "IVehicle.subscribe is not called";

    // Schedule the first heart beat update to happen outside the allowed health check window
    // (300ms) to trigger the watchdog killing before the first heart beat update.
    scheduleVhalHeartBeatUpdate(400ms);

    ASSERT_TRUE(monitor->waitForPidsKilled(1s)) << "Expect not-responding pids to be killed";
    const auto& killedPids = monitor->getKilledPids();
    ASSERT_THAT(killedPids, SizeIs(1));
    ASSERT_THAT(killedPids[0].pid, kTestAidlVhalPid);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
