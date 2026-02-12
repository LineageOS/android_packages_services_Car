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

#include "CarPowerPolicyServer.h"

#include <aidl/android/automotive/power/internal/BnCarPowerManagementDelegateCallback.h>
#include <aidl/android/automotive/power/internal/PowerPolicyFailureReason.h>
#include <aidl/android/automotive/power/internal/PowerPolicyInitData.h>
#include <aidl/android/frameworks/automotive/power/BnCarPowerStateChangeListener.h>
#include <aidl/android/frameworks/automotive/power/BnCarPowerStateChangeListenerWithCompletion.h>
#include <aidl/android/frameworks/automotive/power/BnCompletablePowerStateChangeFuture.h>
#include <aidl/android/frameworks/automotive/power/ICarPowerStateChangeListener.h>
#include <aidl/android/frameworks/automotive/power/ICarPowerStateChangeListenerWithCompletion.h>
#include <aidl/android/frameworks/automotive/power/ICompletablePowerStateChangeFuture.h>
#include <aidl/android/frameworks/automotive/powerpolicy/BnCarPowerPolicyChangeCallback.h>
#include <aidl/android/frameworks/automotive/powerpolicy/CarPowerPolicy.h>
#include <aidl/android/frameworks/automotive/powerpolicy/CarPowerPolicyFilter.h>
#include <aidl/android/frameworks/automotive/powerpolicy/ICarPowerPolicyChangeCallback.h>
#include <aidl/android/frameworks/automotive/powerpolicy/ICarPowerPolicyServer.h>
#include <aidl/android/frameworks/automotive/powerpolicy/PowerComponent.h>
#include <aidl/android/hardware/automotive/vehicle/IVehicle.h>
#include <android-base/file.h>
#include <android-base/thread_annotations.h>
#include <android/binder_status.h>
#include <android/hardware/automotive/vehicle/2.0/IVehicleCallback.h>
#include <binder/IPCThreadState.h>
#include <gmock/gmock.h>
#include <private/android_filesystem_config.h>
#include <utils/Looper.h>
#include <utils/Mutex.h>
#include <utils/StrongPointer.h>
#include <utils/SystemClock.h>

#include <AidlHalPropValue.h>
#include <IVhalClient.h>
#include <android_car_feature.h>
#include <tinyxml2.h>

#include <chrono>  // NOLINT(build/c++11)
#include <functional>
#include <mutex>   // NOLINT(build/c++11)
#include <semaphore.h>
#include <thread>  // NOLINT(build/c++11)
#include <unordered_set>
#include <utility>

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

using android::IBinder;

using ::aidl::android::automotive::power::internal::BnCarPowerManagementDelegateCallback;
using ::aidl::android::automotive::power::internal::ICarPowerManagementDelegate;
using ::aidl::android::automotive::power::internal::ICarPowerManagementDelegateCallback;
using ::aidl::android::automotive::power::internal::PowerPolicyFailureReason;
using ::aidl::android::automotive::power::internal::PowerPolicyInitData;
using ::aidl::android::frameworks::automotive::power::BnCarPowerStateChangeListener;
using ::aidl::android::frameworks::automotive::power::BnCarPowerStateChangeListenerWithCompletion;
using ::aidl::android::frameworks::automotive::power::BnCompletablePowerStateChangeFuture;
using ::aidl::android::frameworks::automotive::power::CarPowerState;
using ::aidl::android::frameworks::automotive::power::ICarPowerStateChangeListener;
using ::aidl::android::frameworks::automotive::power::ICarPowerStateChangeListenerWithCompletion;
using ::aidl::android::frameworks::automotive::power::ICompletablePowerStateChangeFuture;
using ::aidl::android::frameworks::automotive::powerpolicy::BnCarPowerPolicyChangeCallback;
using ::aidl::android::frameworks::automotive::powerpolicy::CarPowerPolicy;
using ::aidl::android::frameworks::automotive::powerpolicy::CarPowerPolicyFilter;
using ::aidl::android::frameworks::automotive::powerpolicy::ICarPowerPolicyChangeCallback;
using ::aidl::android::frameworks::automotive::powerpolicy::ICarPowerPolicyServer;
using ::aidl::android::frameworks::automotive::powerpolicy::PowerComponent;
using ::aidl::android::hardware::automotive::vehicle::GetValueRequest;
using ::aidl::android::hardware::automotive::vehicle::MinMaxSupportedValueResults;
using ::aidl::android::hardware::automotive::vehicle::SetValueRequest;
using ::aidl::android::hardware::automotive::vehicle::SubscribeOptions;
using ::aidl::android::hardware::automotive::vehicle::SupportedValuesListResults;
using ::aidl::android::hardware::automotive::vehicle::VehiclePropConfigs;
using ::aidl::android::hardware::automotive::vehicle::VehiclePropertyStatus;

using ::android::car::feature::car_power_policy_refactoring;
using ::android::frameworks::automotive::vhal::IHalPropConfig;
using ::android::frameworks::automotive::vhal::IHalPropValue;
using ::android::frameworks::automotive::vhal::ISubscriptionCallback;
using ::android::frameworks::automotive::vhal::ISubscriptionClient;
using ::android::frameworks::automotive::vhal::IVhalClient;
using ::android::frameworks::automotive::vhal::VhalClientResult;

using ::ndk::ScopedAStatus;
using ::ndk::SpAIBinder;

using vhal::AidlHalPropValue;

using ::std::chrono_literals::operator""ms;

using ::testing::_;
using ::testing::Eq;
using ::testing::HasSubstr;
using ::testing::Invoke;
using ::testing::Mock;
using ::testing::MockFunction;
using ::testing::NotNull;
using ::testing::Return;

using ::tinyxml2::XML_SUCCESS;
using ::tinyxml2::XMLDocument;

namespace {

constexpr const char* kDirPrefix = "/tests/data/";
constexpr char kTestVin[] = "test_VIN";
constexpr const char* kValidPowerPolicyXmlFile = "valid_power_policy.xml";
constexpr const char* kTestLooperThreadName = "LooperThread";
constexpr std::chrono::duration kCallbackWaitTime = 5000ms;
constexpr std::chrono::duration kGeneralWaitTime = 2000ms;

class MockPowerPolicyChangeCallback : public BnCarPowerPolicyChangeCallback {
public:
    ScopedAStatus onPolicyChanged(const CarPowerPolicy& /*policy*/) override {
        return ScopedAStatus::ok();
    }
};

class MockPowerStateChangeListener : public BnCarPowerStateChangeListener {
public:
    MOCK_METHOD(ScopedAStatus, onStateChanged, (CarPowerState), (override));
};

class MockPowerStateChangeListenerWithCompletion :
      public BnCarPowerStateChangeListenerWithCompletion {
public:
    MOCK_METHOD(ScopedAStatus, onStateChanged,
                (CarPowerState, int64_t,
                 const std::shared_ptr<ICompletablePowerStateChangeFuture>&),
                (override));
};

class MockPowerManagementDelegateCallback : public BnCarPowerManagementDelegateCallback {
public:
    MOCK_METHOD(ScopedAStatus, updatePowerComponents, (const CarPowerPolicy&), (override));
    MOCK_METHOD(ScopedAStatus, onApplyPowerPolicySucceeded, (int32_t, const CarPowerPolicy&, bool),
                (override));
    MOCK_METHOD(ScopedAStatus, onApplyPowerPolicyFailed, (int32_t, PowerPolicyFailureReason),
                (override));
    MOCK_METHOD(ScopedAStatus, onPowerPolicyChanged, (const CarPowerPolicy&), (override));
    MOCK_METHOD(ScopedAStatus, onAllPowerStateChangeListenersComplete, (int32_t changeId),
                (override));
};

class MockSubscriptionClient : public android::frameworks::automotive::vhal::ISubscriptionClient {
public:
    MockSubscriptionClient() {}
    ~MockSubscriptionClient() {}

    MOCK_METHOD(VhalClientResult<void>, subscribe,
                (const std::vector<aidl::android::hardware::automotive::vehicle::SubscribeOptions>&
                         options),
                (override));
    MOCK_METHOD(VhalClientResult<void>, unsubscribe, (const std::vector<int32_t>& propIds),
                (override));
    MOCK_METHOD(void, unsubscribeAll, (), (override));
};

class FakeVhal : public IVhalClient {
public:
    explicit FakeVhal() {}
    ~FakeVhal() {}

    bool isAidlVhal() override { return true; }

    std::unique_ptr<IHalPropValue> createHalPropValue(int32_t propId) override {
        return std::make_unique<AidlHalPropValue>(propId);
    }

    std::unique_ptr<IHalPropValue> createHalPropValue([[maybe_unused]] int32_t propId,
                                                      [[maybe_unused]] int32_t areaId) override {
        return nullptr;
    }

    void getValue([[maybe_unused]] const IHalPropValue&,
                  std::shared_ptr<GetValueCallbackFunc>) override {
        return;
    }

    VhalClientResult<std::unique_ptr<IHalPropValue>> getValueSync(
            const IHalPropValue& requestValue) override {
        auto propValue = std::make_unique<AidlHalPropValue>(requestValue.getPropId());
        propValue->setStringValue(kTestVin);
        return propValue;
    }

    void setValue([[maybe_unused]] const IHalPropValue&,
                  std::shared_ptr<SetValueCallbackFunc>) override {
        return;
    }

    VhalClientResult<void> setValueSync([[maybe_unused]] const IHalPropValue&) override {
        return {};
    }

    VhalClientResult<void> addOnBinderDiedCallback(
            [[maybe_unused]] std::shared_ptr<OnBinderDiedCallbackFunc> callback) override {
        return {};
    }

    VhalClientResult<void> removeOnBinderDiedCallback(
            [[maybe_unused]] std::shared_ptr<OnBinderDiedCallbackFunc>) override {
        return {};
    }

    VhalClientResult<std::vector<std::unique_ptr<IHalPropConfig>>> getAllPropConfigs() override {
        return {};
    }

    VhalClientResult<std::vector<std::unique_ptr<IHalPropConfig>>> getPropConfigs(
            [[maybe_unused]] std::vector<int32_t>) override {
        return {};
    }

    std::unique_ptr<ISubscriptionClient> getSubscriptionClient(
            [[maybe_unused]] std::shared_ptr<ISubscriptionCallback> callback) override {
        return std::make_unique<MockSubscriptionClient>();
    }
};

std::string getTestDataPath(const char* filename) {
    static std::string baseDir = android::base::GetExecutableDirectory();
    return baseDir + kDirPrefix + filename;
}

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

namespace internal {

class CarPowerPolicyServerPeer : public RefBase {
public:
    CarPowerPolicyServerPeer() {
        std::unique_ptr<MockLinkUnlinkImpl> impl = std::make_unique<MockLinkUnlinkImpl>();
        // We know this would be alive as long as server is alive.
        mLinkUnlinkImpl = impl.get();
        mServer = ndk::SharedRefBase::make<CarPowerPolicyServer>();
        mServer->setLinkUnlinkImpl(std::move(impl));
        mBinder = mServer->asBinder();
        mServerProxy = ICarPowerPolicyServer::fromBinder(mBinder);
    }

    explicit CarPowerPolicyServerPeer(uint64_t connectToVhalTimeoutMillis) {
        mServer = ndk::SharedRefBase::make<CarPowerPolicyServer>(connectToVhalTimeoutMillis);
    }

    explicit CarPowerPolicyServerPeer(
            const std::function<std::unique_ptr<IVhalClient>()>& vhalCreationFn) {
        std::unique_ptr<MockLinkUnlinkImpl> impl = std::make_unique<MockLinkUnlinkImpl>();
        // We know this would be alive as long as server is alive.
        mLinkUnlinkImpl = impl.get();
        mServer = ndk::SharedRefBase::make<CarPowerPolicyServer>(vhalCreationFn);
        mServer->setLinkUnlinkImpl(std::move(impl));
    }

    ~CarPowerPolicyServerPeer() {
        if (mServer->mHandlerLooper != nullptr) {
            release();
        }
    }

    ScopedAStatus getCurrentPowerPolicy(CarPowerPolicy* aidlReturn) {
        return mServerProxy->getCurrentPowerPolicy(aidlReturn);
    }

    ScopedAStatus registerPowerPolicyChangeCallback(
            const std::shared_ptr<ICarPowerPolicyChangeCallback>& callback,
            const CarPowerPolicyFilter& filter) {
        return mServerProxy->registerPowerPolicyChangeCallback(callback, filter);
    }

    ScopedAStatus unregisterPowerPolicyChangeCallback(
            const std::shared_ptr<ICarPowerPolicyChangeCallback>& callback) {
        return mServerProxy->unregisterPowerPolicyChangeCallback(callback);
    }

    ScopedAStatus applyPowerPolicy(const std::string& policyId) {
        return mServerProxy->applyPowerPolicy(policyId);
    }

    ScopedAStatus notifyCarServiceReady(
            const std::shared_ptr<ICarPowerManagementDelegateCallback>& callback,
            PowerPolicyInitData* aidlReturn) {
        return mServer->notifyCarServiceReadyInternal(callback, aidlReturn);
    }

    ScopedAStatus applyPowerPolicyAsync(int32_t requestId, const std::string& policyId,
                                        bool force) {
        return mServer->applyPowerPolicyAsync(requestId, policyId, force);
    }

    ScopedAStatus applyPowerPolicyPerPowerStateChangeAsync(
            int32_t requestId, ICarPowerManagementDelegate::PowerState state) {
        return mServer->applyPowerPolicyPerPowerStateChangeAsync(requestId, state);
    }

    ScopedAStatus notifyPowerStateChange(int32_t changeId, CarPowerState newState,
                                         int64_t expirationTimeMs) {
        return mServer->notifyPowerStateChange(changeId, newState, expirationTimeMs);
    }

    ScopedAStatus setPowerPolicyGroup(const std::string& policyGroupId) {
        return mServer->setPowerPolicyGroup(policyGroupId);
    }

    ScopedAStatus registerPowerStateListener(
            const std::shared_ptr<ICarPowerStateChangeListener>& listener) {
        return mServer->registerPowerStateListener(listener);
    }

    ScopedAStatus registerPowerStateListenerWithCompletion(
            const std::shared_ptr<ICarPowerStateChangeListenerWithCompletion>& listener) {
        return mServer->registerPowerStateListenerWithCompletion(listener);
    }

    ScopedAStatus unregisterPowerStateListener(
            const std::shared_ptr<ICarPowerStateChangeListener>& listener) {
        return mServer->unregisterPowerStateListener(listener);
    }

    ScopedAStatus unregisterPowerStateListenerWithCompletion(
            const std::shared_ptr<ICarPowerStateChangeListenerWithCompletion>& listener) {
        return mServer->unregisterPowerStateListenerWithCompletion(listener);
    }

    void init() { init(/* initializePowerPolicy= */ true); }

    void init(bool initializePowerPolicy) {
        initializeLooper();
        ASSERT_NO_FATAL_FAILURE(initializePolicyManager());
        initializePowerComponentHandler();
        if (initializePowerPolicy) {
            ASSERT_NO_FATAL_FAILURE(applyInitialPolicy());
        } else {
            mServer->connectToVhal();
        }
    }

    void release() { finalizeLooper(); }

    void onPowerPolicyClientBinderDied(void* cookie) {
        mServer->onPowerPolicyChangeClientBinderDied(cookie);
    }

    void onPowerStateClientBinderDied(void* cookie) {
        mServer->onPowerStateChangeClientBinderDied(cookie);
    }

    void onPowerStateClientWithCompletionBinderDied(void* cookie) {
        mServer->onPowerStateChangeClientWithCompletionBinderDied(cookie);
    }

    void onClientDeathRecipientUnlinked(void* cookie) {
        mServer->onClientDeathRecipientUnlinked(cookie);
    }

    std::vector<CallbackInfo> getPolicyChangeCallbacks() {
        return mServer->getPolicyChangeCallbacks();
    }

    std::vector<std::shared_ptr<ICarPowerStateChangeListener>> getPowerStateListeners() {
        return mServer->getPowerStateListeners();
    }

    std::vector<std::shared_ptr<ICarPowerStateChangeListenerWithCompletion>>
    getPowerStateListenersWithCompletion() {
        return mServer->getPowerStateListenersWithCompletion();
    }

    size_t countOnClientBinderDiedContexts() { return mServer->countOnClientBinderDiedContexts(); }

    std::unordered_set<void*> getCookies() { return mLinkUnlinkImpl->getCookies(); }

    void expectLinkToDeathStatus(AIBinder* binder, status_t linkToDeathResult) {
        mLinkUnlinkImpl->expectLinkToDeathStatus(binder, linkToDeathResult);
    }

    size_t getMaxConnectToVhalRetryCount() { return mServer->getMaxConnectToVhalRetryCount(); }

    void resetLinkUnlinkImpl() {
        mServer->setLinkUnlinkImpl(nullptr);
        mLinkUnlinkImpl = nullptr;
    }

private:
    void initializeLooper() {
        sp<Looper> looper = Looper::prepare(/*opts=*/0);
        mServer->mHandlerLooper = looper;
        std::mutex mutex;
        std::condition_variable cv;
        bool looperReady = false;
        mHandlerLooperThread = std::thread([looper, &cv, &mutex, &looperReady, this]() {
            Looper::setForThread(looper);
            if (int result = pthread_setname_np(pthread_self(), kTestLooperThreadName);
                result != 0) {
                ALOGE("Failed to set test looper thread name: %s", strerror(result));
            }
            mShouldTerminateLooper.store(false);
            {
                std::unique_lock lock(mutex);
                looperReady = true;
                cv.notify_all();
            }
            while (!mShouldTerminateLooper.load()) {
                looper->pollOnce(/*timeoutMillis=*/-1);
            }
        });
        std::unique_lock lock(mutex);
        // Wait until thread looper is ready.
        cv.wait(lock, [&looperReady] { return looperReady; });
    }

    void finalizeLooper() {
        mShouldTerminateLooper.store(true);
        mServer->mHandlerLooper->wake();
        if (mHandlerLooperThread.joinable()) {
            mHandlerLooperThread.join();
        }
    }

    void initializePolicyManager() {
        PolicyManager& policyManager = mServer->mPolicyManager;
        policyManager.initRegularPowerPolicy(/*override=*/true);
        policyManager.initPreemptivePowerPolicy();

        XMLDocument xmlDoc;
        std::string path = getTestDataPath(kValidPowerPolicyXmlFile);
        xmlDoc.LoadFile(path.c_str());
        ASSERT_TRUE(xmlDoc.ErrorID() == XML_SUCCESS);
        policyManager.readPowerPolicyFromXml(xmlDoc);
    }

    void initializePowerComponentHandler() {
        PowerComponentHandler& componentHandler = mServer->mComponentHandler;
        componentHandler.init();
    }

    void applyInitialPolicy() {
        auto policyMeta = mServer->mPolicyManager.getPowerPolicy(kSystemPolicyIdInitialOn);
        ASSERT_TRUE(policyMeta.ok());
        mServer->mCurrentPowerPolicyMeta = *policyMeta;
    }

    class MockLinkUnlinkImpl : public CarPowerPolicyServer::LinkUnlinkImpl {
    public:
        MOCK_METHOD(binder_status_t, linkToDeath, (AIBinder*, AIBinder_DeathRecipient*, void*),
                    (override));
        MOCK_METHOD(binder_status_t, unlinkToDeath, (AIBinder*, AIBinder_DeathRecipient*, void*),
                    (override));
        MOCK_METHOD(void, setOnUnlinked,
                    (AIBinder_DeathRecipient*, AIBinder_DeathRecipient_onBinderUnlinked),
                    (override));
        MOCK_METHOD(void, deleteDeathRecipient, (AIBinder_DeathRecipient*), (override));

        MockLinkUnlinkImpl() {
            ON_CALL(*this, setOnUnlinked(_, _))
                    .WillByDefault(
                            Invoke([this](AIBinder_DeathRecipient* recipient,
                                          AIBinder_DeathRecipient_onBinderUnlinked onUnlinked) {
                                Mutex::Autolock lock(mMutex);
                                mOnUnlinked[recipient] = onUnlinked;
                            }));
            ON_CALL(*this, deleteDeathRecipient(_))
                    .WillByDefault(Invoke([this](AIBinder_DeathRecipient* recipient) {
                        // Call onUnlinked for all the death recipients that are still registered.
                        std::unordered_set<void*> cookies;
                        AIBinder_DeathRecipient_onBinderUnlinked onUnlinked;
                        {
                            Mutex::Autolock lock(mMutex);
                            cookies = mCookies[recipient];
                            onUnlinked = *mOnUnlinked[recipient];
                        }

                        for (const auto& cookie : cookies) {
                            (*onUnlinked)(cookie);
                        }
                    }));
        }

        void expectLinkToDeathStatus(AIBinder* binder, binder_status_t linkToDeathResult) {
            EXPECT_CALL(*this, linkToDeath(NotNull(), _, _))
                    .WillRepeatedly(Invoke(
                            [this, linkToDeathResult](AIBinder*, AIBinder_DeathRecipient* recipient,
                                                      void* cookie) {
                                // If success, store the cookie with the death recipient, otherwise,
                                // call onUnlinked.
                                if (linkToDeathResult != STATUS_OK) {
                                    (*getOnUnlinked(recipient))(cookie);
                                    return linkToDeathResult;
                                }
                                Mutex::Autolock lock(mMutex);
                                mCookies[recipient].insert(cookie);
                                return linkToDeathResult;
                            }));
            EXPECT_CALL(*this, unlinkToDeath(binder, _, _))
                    .WillRepeatedly(Invoke(
                            [this](AIBinder*, AIBinder_DeathRecipient* recipient, void* cookie) {
                                // Remove the cookie and call onUnlinked.
                                {
                                    Mutex::Autolock lock(mMutex);
                                    mCookies[recipient].erase(cookie);
                                }
                                (*getOnUnlinked(recipient))(cookie);
                                return STATUS_OK;
                            }));
        }

        std::unordered_set<void*> getCookies() {
            Mutex::Autolock lock(mMutex);
            std::unordered_set<void*> allCookies;
            for (const auto& [recipient, cookies] : mCookies) {
                for (const auto& cookie : cookies) {
                    allCookies.insert(cookie);
                }
            }
            return allCookies;
        }

    private:
        android::Mutex mMutex;
        std::unordered_map<AIBinder_DeathRecipient*, std::unordered_set<void*>> mCookies
                GUARDED_BY(mMutex);
        std::unordered_map<AIBinder_DeathRecipient*, AIBinder_DeathRecipient_onBinderUnlinked>
                mOnUnlinked GUARDED_BY(mMutex);

        AIBinder_DeathRecipient_onBinderUnlinked getOnUnlinked(AIBinder_DeathRecipient* recipient) {
            Mutex::Autolock lock(mMutex);
            return mOnUnlinked[recipient];
        }
    };

    MockLinkUnlinkImpl* mLinkUnlinkImpl;
    std::shared_ptr<CarPowerPolicyServer> mServer;
    std::shared_ptr<ICarPowerPolicyServer> mServerProxy;
    std::thread mHandlerLooperThread;
    SpAIBinder mBinder;
    std::atomic<bool> mShouldTerminateLooper;
};

}  // namespace internal

class CarPowerPolicyServerTest : public ::testing::Test {
public:
    std::shared_ptr<ICarPowerPolicyChangeCallback> getPowerPolicyChangeCallback() {
        std::shared_ptr<MockPowerPolicyChangeCallback> callback =
                ndk::SharedRefBase::make<MockPowerPolicyChangeCallback>();
        return ICarPowerPolicyChangeCallback::fromBinder(callback->asBinder());
    }

    std::shared_ptr<ICarPowerStateChangeListener> getPowerStateChangeListener() {
        std::shared_ptr<MockPowerStateChangeListener> listener =
                ndk::SharedRefBase::make<MockPowerStateChangeListener>();
        return ICarPowerStateChangeListener::fromBinder(listener->asBinder());
    }

    std::shared_ptr<ICarPowerStateChangeListenerWithCompletion>
    getPowerStateChangeListenerWithCompletion() {
        std::shared_ptr<MockPowerStateChangeListenerWithCompletion> listener =
                ndk::SharedRefBase::make<MockPowerStateChangeListenerWithCompletion>();
        return ICarPowerStateChangeListenerWithCompletion::fromBinder(listener->asBinder());
    }

    std::shared_ptr<MockPowerStateChangeListener> getMockPowerStateChangeListener() {
        return ndk::SharedRefBase::make<MockPowerStateChangeListener>();
    }

    std::shared_ptr<MockPowerStateChangeListenerWithCompletion>
    getMockPowerStateChangeListenerWithCompletion() {
        return ndk::SharedRefBase::make<MockPowerStateChangeListenerWithCompletion>();
    }

    // Sets calling UID to imitate System's process.
    void setSystemCallingUid() {
        mScopedChangeCallingUid = sp<ScopedChangeCallingUid>::make(AID_SYSTEM);
    }

    void setUpServerWithCallback(sp<internal::CarPowerPolicyServerPeer> server,
                                 std::shared_ptr<MockPowerManagementDelegateCallback> callback) {
        server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
        server->init();
        setSystemCallingUid();
        PowerPolicyInitData initData;
        server->notifyCarServiceReady(callback, &initData);
    }

    void setUpServerWithPowerStateListener(
            sp<internal::CarPowerPolicyServerPeer> server,
            std::shared_ptr<MockPowerManagementDelegateCallback> callback,
            std::shared_ptr<MockPowerStateChangeListener> listener) {
        setUpServerWithCallback(server, callback);
        server->expectLinkToDeathStatus(listener->asBinder().get(), STATUS_OK);
        ScopedAStatus status = server->registerPowerStateListener(listener);
        ASSERT_TRUE(status.isOk()) << status.getMessage();
    }

    void setUpServerWithPowerStateListenerWithCompletion(
            sp<internal::CarPowerPolicyServerPeer> server,
            std::shared_ptr<MockPowerManagementDelegateCallback> callback,
            std::shared_ptr<MockPowerStateChangeListenerWithCompletion> listener) {
        setUpServerWithCallback(server, callback);
        server->expectLinkToDeathStatus(listener->asBinder().get(), STATUS_OK);
        ScopedAStatus status = server->registerPowerStateListenerWithCompletion(listener);
        ASSERT_TRUE(status.isOk()) << status.getMessage();
    }

    void testApplyPowerPolicyPerPowerStateChangeAsyncInternal(const std::string& policyGroupId,
                                                              const std::string& expectedPolicyId) {
        sp<internal::CarPowerPolicyServerPeer> server =
                sp<internal::CarPowerPolicyServerPeer>::make();
        std::shared_ptr<MockPowerManagementDelegateCallback> callback =
                ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
        server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
        server->init();
        setSystemCallingUid();

        int32_t requestId = 9999;
        int32_t calledRequestId = -1;
        std::string policyIdForUpdate;
        std::string policyIdForNotification;
        std::mutex mutex;
        std::condition_variable cv;
        EXPECT_CALL(*callback, updatePowerComponents)
                .WillRepeatedly(
                        Invoke([&policyIdForUpdate](const CarPowerPolicy& policy) -> ScopedAStatus {
                            policyIdForUpdate = policy.policyId;
                            return ScopedAStatus::ok();
                        }));
        EXPECT_CALL(*callback, onApplyPowerPolicySucceeded)
                .WillRepeatedly(
                        Invoke([&calledRequestId, &policyIdForNotification, &cv,
                                &mutex](int32_t requestId, const CarPowerPolicy& accumulatedPolicy,
                                        [[maybe_unused]] bool deferred) -> ScopedAStatus {
                            calledRequestId = requestId;
                            policyIdForNotification = accumulatedPolicy.policyId;
                            std::unique_lock lock(mutex);
                            cv.notify_all();
                            return ScopedAStatus::ok();
                        }));
        PowerPolicyInitData initData;
        server->notifyCarServiceReady(callback, &initData);
        server->setPowerPolicyGroup(policyGroupId);

        ScopedAStatus status =
                server->applyPowerPolicyPerPowerStateChangeAsync(requestId,
                                                                 ICarPowerManagementDelegate::
                                                                         PowerState::ON);

        ASSERT_TRUE(status.isOk()) << "applyPowerPolicyPerPowerStateChangeAsync should return OK";

        std::unique_lock lock(mutex);
        bool waitResult =
                cv.wait_for(lock, kCallbackWaitTime, [&policyIdForNotification, &expectedPolicyId] {
                    return policyIdForNotification.compare(expectedPolicyId) == 0;
                });
        EXPECT_TRUE(waitResult)
                << "onApplyPowerPolicySucceeded() should be called with the same power policy ID";
        EXPECT_EQ(policyIdForUpdate, expectedPolicyId)
                << "updatePowerComponents should be called with " << expectedPolicyId;
    }

private:
    sp<ScopedChangeCallingUid> mScopedChangeCallingUid;
};

TEST_F(CarPowerPolicyServerTest, TestRegisterCallback) {
    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerPolicyChangeCallback> callbackOne = getPowerPolicyChangeCallback();
    server->expectLinkToDeathStatus(callbackOne->asBinder().get(), STATUS_OK);

    CarPowerPolicyFilter filter;
    ScopedAStatus status = server->registerPowerPolicyChangeCallback(callbackOne, filter);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    status = server->registerPowerPolicyChangeCallback(callbackOne, filter);
    ASSERT_FALSE(status.isOk()) << "Duplicated registration is not allowed";
    filter.components = {PowerComponent::BLUETOOTH, PowerComponent::AUDIO};
    status = server->registerPowerPolicyChangeCallback(callbackOne, filter);
    ASSERT_FALSE(status.isOk()) << "Duplicated registration is not allowed";

    std::shared_ptr<ICarPowerPolicyChangeCallback> callbackTwo = getPowerPolicyChangeCallback();
    server->expectLinkToDeathStatus(callbackTwo->asBinder().get(), STATUS_OK);

    status = server->registerPowerPolicyChangeCallback(callbackTwo, filter);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(CarPowerPolicyServerTest, TestRegisterCallback_BinderDied) {
    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerPolicyChangeCallback> callback = getPowerPolicyChangeCallback();
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_DEAD_OBJECT);
    CarPowerPolicyFilter filter;

    ASSERT_FALSE(server->registerPowerPolicyChangeCallback(callback, filter).isOk())
            << "When linkToDeath fails, registerPowerPolicyChangeCallback should return an error";
}

TEST_F(CarPowerPolicyServerTest, TestOnBinderDied) {
    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerPolicyChangeCallback> callback = getPowerPolicyChangeCallback();
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);

    CarPowerPolicyFilter filter;
    ScopedAStatus status = server->registerPowerPolicyChangeCallback(callback, filter);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_EQ(server->getPolicyChangeCallbacks().size(), static_cast<size_t>(1));
    ASSERT_EQ(server->countOnClientBinderDiedContexts(), static_cast<size_t>(1));
    ASSERT_EQ(server->getCookies().size(), static_cast<size_t>(1));

    void* cookie = *(server->getCookies().begin());
    server->onPowerPolicyClientBinderDied(cookie);
    ASSERT_TRUE(server->getPolicyChangeCallbacks().empty());

    server->onClientDeathRecipientUnlinked(cookie);

    ASSERT_EQ(server->countOnClientBinderDiedContexts(), static_cast<size_t>(0));
}

TEST_F(CarPowerPolicyServerTest, TestUnregisterCallback) {
    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerPolicyChangeCallback> callback = getPowerPolicyChangeCallback();
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
    CarPowerPolicyFilter filter;

    server->registerPowerPolicyChangeCallback(callback, filter);
    ScopedAStatus status = server->unregisterPowerPolicyChangeCallback(callback);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_FALSE(server->unregisterPowerPolicyChangeCallback(callback).isOk())
            << "Unregistering an unregistered powerpolicy change callback should return an error";
}

TEST_F(CarPowerPolicyServerTest, TestGetCurrentPowerPolicy) {
    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    CarPowerPolicy currentPolicy;

    ScopedAStatus status = server->getCurrentPowerPolicy(&currentPolicy);
    ASSERT_FALSE(status.isOk()) << "The current policy at creation should be null";
    // TODO(b/168545262): Add more test cases after VHAL integration is complete.
}

TEST_F(CarPowerPolicyServerTest, TestApplyPowerPolicyFromNativeClients) {
    if (!car_power_policy_refactoring()) {
        GTEST_SKIP() << "car_power_policy_refactoring feature flag is not enabled";
    }

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
    server->init();
    PowerPolicyInitData initData;
    server->notifyCarServiceReady(callback, &initData);
    const std::string powerPolicyId = "policy_id_other_off";

    ScopedAStatus status = server->applyPowerPolicy(powerPolicyId);
    ASSERT_TRUE(status.isOk()) << "applyPowerPolicy should return OK";
    CarPowerPolicy policy;
    status = server->getCurrentPowerPolicy(&policy);
    ASSERT_TRUE(status.isOk()) << "getCurrentPowerPolicy should return OK";
    ASSERT_EQ(policy.policyId, powerPolicyId.c_str())
            << "The current power policy should be the applied one";
}

TEST_F(CarPowerPolicyServerTest, TestApplyPowerPolicyFromNativeClients_carServiceNotRegistered) {
    if (!car_power_policy_refactoring()) {
        GTEST_SKIP() << "car_power_policy_refactoring feature flag is not enabled";
    }

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    server->init();
    const std::string powerPolicyId = "policy_id_other_off";

    ScopedAStatus status = server->applyPowerPolicy(powerPolicyId);
    ASSERT_TRUE(status.isOk()) << "applyPowerPolicy should return OK";
    CarPowerPolicy policy;
    status = server->getCurrentPowerPolicy(&policy);
    ASSERT_TRUE(status.isOk()) << "getCurrentPowerPolicy should return OK";
    ASSERT_EQ(policy.policyId, powerPolicyId.c_str())
            << "The current power policy should be the applied one";
}

TEST_F(CarPowerPolicyServerTest, TestApplyPowerPolicyFromNativeClients_invalidPolicyId) {
    if (!car_power_policy_refactoring()) {
        GTEST_SKIP() << "car_power_policy_refactoring feature flag is not enabled";
    }

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    server->init();

    ScopedAStatus status = server->applyPowerPolicy("policy_not_exist");
    ASSERT_FALSE(status.isOk()) << "applyPowerPolicy should return an error";
}

TEST_F(CarPowerPolicyServerTest, TestApplyPowerPolicyFromCarService) {
    if (!car_power_policy_refactoring()) {
        GTEST_SKIP() << "car_power_policy_refactoring feature flag is not enabled";
    }

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
    server->init();
    setSystemCallingUid();
    PowerPolicyInitData initData;
    server->notifyCarServiceReady(callback, &initData);
    std::string policyId;
    std::mutex mutex;
    std::condition_variable cv;
    EXPECT_CALL(*callback, updatePowerComponents)
            .WillRepeatedly(
                    Invoke([&policyId, &cv, &mutex](const CarPowerPolicy& policy) -> ScopedAStatus {
                        std::unique_lock lock(mutex);
                        policyId = policy.policyId;
                        cv.notify_all();
                        return ScopedAStatus::ok();
                    }));
    EXPECT_CALL(*callback, onApplyPowerPolicySucceeded)
            .WillRepeatedly(Invoke([]([[maybe_unused]] int32_t requestId,
                                      [[maybe_unused]] const CarPowerPolicy& accumulatedPolicy,
                                      [[maybe_unused]] bool deferred) -> ScopedAStatus {
                return ScopedAStatus::ok();
            }));

    ScopedAStatus status = server->applyPowerPolicyAsync(/*requestId=*/9999, "policy_id_other_off",
                                                         /*force=*/false);
    ASSERT_TRUE(status.isOk()) << "applyPowerPolicyAsync should return OK";
    std::unique_lock lock(mutex);
    bool waitResult = cv.wait_for(lock, kCallbackWaitTime, [&policyId] {
        return policyId.compare("policy_id_other_off") == 0;
    });
    ASSERT_TRUE(waitResult)
            << "updatePowerComponents() should be called with the same power policy ID";
}

TEST_F(CarPowerPolicyServerTest, TestApplyPowerPolicyFromCarService_nonSystemUid) {
    if (!car_power_policy_refactoring()) {
        GTEST_SKIP() << "car_power_policy_refactoring feature flag is not enabled";
    }

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
    server->init();
    PowerPolicyInitData initData;
    server->notifyCarServiceReady(callback, &initData);

    ScopedAStatus status = server->applyPowerPolicyAsync(/*requestId=*/9999, "policy_id_other_off",
                                                         /*force=*/false);
    ASSERT_FALSE(status.isOk())
            << "applyPowerPolicyAsync should fail when the caller doesn't have system UID";
}

TEST_F(CarPowerPolicyServerTest, TestApplyPowerPolicyFromCarService_invalidPolicyId) {
    if (!car_power_policy_refactoring()) {
        GTEST_SKIP() << "car_power_policy_refactoring feature flag is not enabled";
    }

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
    server->init();
    setSystemCallingUid();
    PowerPolicyInitData initData;
    server->notifyCarServiceReady(callback, &initData);
    std::mutex mutex;
    std::condition_variable cv;
    int32_t requestIdLocal;
    bool methodCalled = false;
    PowerPolicyFailureReason failureReason;
    EXPECT_CALL(*callback, onApplyPowerPolicyFailed)
            .WillRepeatedly(Invoke(
                    [&requestIdLocal, &failureReason, &methodCalled, &cv,
                     &mutex](int32_t requestId, PowerPolicyFailureReason reason) -> ScopedAStatus {
                        std::unique_lock lock(mutex);
                        requestIdLocal = requestId;
                        failureReason = reason;
                        methodCalled = true;
                        cv.notify_all();
                        return ScopedAStatus::ok();
                    }));

    ScopedAStatus status = server->applyPowerPolicyAsync(/*requestId=*/9999, "policy_not_exist",
                                                         /*force=*/false);
    ASSERT_TRUE(status.isOk());
    std::unique_lock lock(mutex);
    bool waitResult =
            cv.wait_for(lock, kCallbackWaitTime, [&methodCalled] { return methodCalled; });
    ASSERT_TRUE(waitResult) << "onApplyPowerPolicyFailed should be called";
    EXPECT_EQ(requestIdLocal, 9999);
    ASSERT_EQ(failureReason, PowerPolicyFailureReason::POWER_POLICY_FAILURE_NOT_REGISTERED_ID);
}

TEST_F(CarPowerPolicyServerTest, TestApplyPowerPolicyFromCarService_duplicatedRequestId) {
    if (!car_power_policy_refactoring()) {
        GTEST_SKIP() << "car_power_policy_refactoring feature flag is not enabled";
    }

    sem_t blocker_started;
    sem_t should_unblock;
    sem_t callback_finished;
    sem_init(&blocker_started, 0, 0);
    sem_init(&should_unblock, 0, 0);
    sem_init(&callback_finished, 0, 0);

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
    server->init();
    setSystemCallingUid();
    EXPECT_CALL(*callback, updatePowerComponents)
            .WillRepeatedly(Invoke([&](const CarPowerPolicy& policy) -> ScopedAStatus {
                if (policy.policyId == "policy_id_other_off") {
                    sem_post(&blocker_started);
                    struct timespec ts;
                    clock_gettime(CLOCK_REALTIME, &ts);
                    ts.tv_sec += 5;
                    sem_timedwait(&should_unblock, &ts);
                    sem_post(&callback_finished);
                }
                return ScopedAStatus::ok();
            }));
    EXPECT_CALL(*callback, onApplyPowerPolicySucceeded)
            .WillRepeatedly(Invoke([]([[maybe_unused]] int32_t requestId,
                                      [[maybe_unused]] const CarPowerPolicy& accumulatedPolicy,
                                      [[maybe_unused]] bool deferred) -> ScopedAStatus {
                return ScopedAStatus::ok();
            }));

    PowerPolicyInitData initData;
    server->notifyCarServiceReady(callback, &initData);

    ScopedAStatus status = server->applyPowerPolicyAsync(/*requestId=*/9999, "policy_id_other_off",
                                                         /*force=*/false);
    ASSERT_TRUE(status.isOk()) << "applyPowerPolicyAsync should return OK";

    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += 5;
    if (sem_timedwait(&blocker_started, &ts) != 0) {
        sem_post(&should_unblock);
        FAIL() << "Timeout: Callback never started";
    }

    status = server->applyPowerPolicyAsync(/*requestId=*/9999, "policy_id_other_untouched",
                                           /*force=*/false);
    ASSERT_FALSE(status.isOk())
            << "applyPowerPolicyAsync should return an error when request ID is duplicated";

    sem_post(&should_unblock);

    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += 5;
    if (sem_timedwait(&callback_finished, &ts) != 0) {
        FAIL() << "Timeout: Callback failed to finish after unblocking";
    }

    sem_destroy(&blocker_started);
    sem_destroy(&should_unblock);
    sem_destroy(&callback_finished);
}

TEST_F(CarPowerPolicyServerTest, TestApplyPowerPolicyPerPowerStateChangeAsync) {
    if (!car_power_policy_refactoring()) {
        GTEST_SKIP() << "car_power_policy_refactoring feature flag is not enabled";
    }

    testApplyPowerPolicyPerPowerStateChangeAsyncInternal("", "system_power_policy_all_on");
}

TEST_F(CarPowerPolicyServerTest, TestApplyPowerPolicyPerPowerStateChangeAsync_nonSystemUid) {
    if (!car_power_policy_refactoring()) {
        GTEST_SKIP() << "car_power_policy_refactoring feature flag is not enabled";
    }

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
    server->init();
    PowerPolicyInitData initData;
    server->notifyCarServiceReady(callback, &initData);

    ScopedAStatus status =
            server->applyPowerPolicyPerPowerStateChangeAsync(/*requestId=*/9999,
                                                             ICarPowerManagementDelegate::
                                                                     PowerState::ON);

    ASSERT_FALSE(status.isOk()) << "applyPowerPolicyPerPowerStateChangeAsync should fail when the "
                                   "caller doesn't have system UID";
}

TEST_F(CarPowerPolicyServerTest,
       TestApplyPowerPolicyPerPowerStateChangeAsync_notSupportedPowerState) {
    if (!car_power_policy_refactoring()) {
        GTEST_SKIP() << "car_power_policy_refactoring feature flag is not enabled";
    }

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
    server->init();
    setSystemCallingUid();
    EXPECT_CALL(*callback, updatePowerComponents).Times(0);
    EXPECT_CALL(*callback, onPowerPolicyChanged).Times(0);
    PowerPolicyInitData initData;
    server->notifyCarServiceReady(callback, &initData);

    // We don't have default power policy for SHUTDOWN_PREPARE.
    ScopedAStatus status =
            server->applyPowerPolicyPerPowerStateChangeAsync(/*requestId=*/9999,
                                                             ICarPowerManagementDelegate::
                                                                     PowerState::SHUTDOWN_PREPARE);

    EXPECT_FALSE(status.isOk())
            << "applyPowerPolicyPerPowerStateChangeAsync should return an error";
    EXPECT_EQ(status.getServiceSpecificError(), EX_ILLEGAL_ARGUMENT) << "Error code should be set";

    // Wait for some time to verify that no callback is made to CPMS.
    std::this_thread::sleep_for(kGeneralWaitTime);
}

TEST_F(CarPowerPolicyServerTest, TestApplyPowerPolicyPerPowerStateChangeAsync_withNewGroup) {
    if (!car_power_policy_refactoring()) {
        GTEST_SKIP() << "car_power_policy_refactoring feature flag is not enabled";
    }

    testApplyPowerPolicyPerPowerStateChangeAsyncInternal("basic_policy_group",
                                                         "policy_id_other_untouched");
}

TEST_F(CarPowerPolicyServerTest, TestRegisterPowerStateChangeListener) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerStateChangeListener> listenerOne = getPowerStateChangeListener();
    server->expectLinkToDeathStatus(listenerOne->asBinder().get(), STATUS_OK);

    ScopedAStatus status = server->registerPowerStateListener(listenerOne);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    status = server->registerPowerStateListener(listenerOne);
    ASSERT_FALSE(status.isOk()) << "Double registration is not allowed";

    std::shared_ptr<ICarPowerStateChangeListener> listenerTwo = getPowerStateChangeListener();
    server->expectLinkToDeathStatus(listenerTwo->asBinder().get(), STATUS_OK);

    status = server->registerPowerStateListener(listenerTwo);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(CarPowerPolicyServerTest, TestRegisterPowerStateChangeListener_binderDied) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerStateChangeListener> listener = getPowerStateChangeListener();
    server->expectLinkToDeathStatus(listener->asBinder().get(), STATUS_DEAD_OBJECT);

    ASSERT_FALSE(server->registerPowerStateListener(listener).isOk())
            << "When linkToDeath fails, registerPowerStateListener should return an error";
}

TEST_F(CarPowerPolicyServerTest, TestOnBinderDied_powerStateListener) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerStateChangeListener> listener = getPowerStateChangeListener();
    server->expectLinkToDeathStatus(listener->asBinder().get(), STATUS_OK);

    ScopedAStatus status = server->registerPowerStateListener(listener);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_EQ(server->getPowerStateListeners().size(), static_cast<size_t>(1));
    ASSERT_EQ(server->countOnClientBinderDiedContexts(), static_cast<size_t>(1));
    ASSERT_EQ(server->getCookies().size(), static_cast<size_t>(1));

    void* cookie = *(server->getCookies().begin());
    server->onPowerStateClientBinderDied(cookie);
    ASSERT_TRUE(server->getPowerStateListeners().empty());

    server->onClientDeathRecipientUnlinked(cookie);

    ASSERT_EQ(server->countOnClientBinderDiedContexts(), static_cast<size_t>(0));
}

TEST_F(CarPowerPolicyServerTest, TestUnregisterPowerStateChangeListener) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerStateChangeListener> listener = getPowerStateChangeListener();
    server->expectLinkToDeathStatus(listener->asBinder().get(), STATUS_OK);

    server->registerPowerStateListener(listener);
    ScopedAStatus status = server->unregisterPowerStateListener(listener);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_FALSE(server->unregisterPowerStateListener(listener).isOk())
            << "Unregistering an unregistered power state change listener should return an error";
}

TEST_F(CarPowerPolicyServerTest, TestRegisterPowerStateChangeListenerWithCompletion) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerStateChangeListenerWithCompletion> listenerOne =
            getPowerStateChangeListenerWithCompletion();
    server->expectLinkToDeathStatus(listenerOne->asBinder().get(), STATUS_OK);

    ScopedAStatus status = server->registerPowerStateListenerWithCompletion(listenerOne);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    status = server->registerPowerStateListenerWithCompletion(listenerOne);
    ASSERT_FALSE(status.isOk()) << "Double registration is not allowed";

    std::shared_ptr<ICarPowerStateChangeListenerWithCompletion> listenerTwo =
            getPowerStateChangeListenerWithCompletion();
    server->expectLinkToDeathStatus(listenerTwo->asBinder().get(), STATUS_OK);

    status = server->registerPowerStateListenerWithCompletion(listenerTwo);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(CarPowerPolicyServerTest, TestRegisterPowerStateChangeListenerWithCompletion_binderDied) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerStateChangeListenerWithCompletion> listener =
            getPowerStateChangeListenerWithCompletion();
    server->expectLinkToDeathStatus(listener->asBinder().get(), STATUS_DEAD_OBJECT);

    ASSERT_FALSE(server->registerPowerStateListenerWithCompletion(listener).isOk())
            << "When linkToDeath fails, registerPowerStateListenerWithCompletion should return an "
               "error";
}

TEST_F(CarPowerPolicyServerTest, TestOnBinderDied_powerStateListenerWithCompletion) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerStateChangeListenerWithCompletion> listener =
            getPowerStateChangeListenerWithCompletion();
    server->expectLinkToDeathStatus(listener->asBinder().get(), STATUS_OK);

    ScopedAStatus status = server->registerPowerStateListenerWithCompletion(listener);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_EQ(server->getPowerStateListenersWithCompletion().size(), static_cast<size_t>(1));
    ASSERT_EQ(server->countOnClientBinderDiedContexts(), static_cast<size_t>(1));
    ASSERT_EQ(server->getCookies().size(), static_cast<size_t>(1));

    void* cookie = *(server->getCookies().begin());
    server->onPowerStateClientWithCompletionBinderDied(cookie);
    ASSERT_TRUE(server->getPowerStateListenersWithCompletion().empty());

    server->onClientDeathRecipientUnlinked(cookie);

    ASSERT_EQ(server->countOnClientBinderDiedContexts(), static_cast<size_t>(0));
}

TEST_F(CarPowerPolicyServerTest, TestUnregisterPowerStateChangeListenerWithCompletion) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<ICarPowerStateChangeListenerWithCompletion> listener =
            getPowerStateChangeListenerWithCompletion();
    server->expectLinkToDeathStatus(listener->asBinder().get(), STATUS_OK);

    server->registerPowerStateListenerWithCompletion(listener);
    ScopedAStatus status = server->unregisterPowerStateListenerWithCompletion(listener);
    ASSERT_TRUE(status.isOk()) << status.getMessage();
    ASSERT_FALSE(server->unregisterPowerStateListenerWithCompletion(listener).isOk())
            << "Unregistering an unregistered power state change listener with completion should "
               "return an error";
}

TEST_F(CarPowerPolicyServerTest, TestNotifyPowerStateChange_noListeners) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    setUpServerWithCallback(server, callback);

    const int32_t changeId = 321;
    int32_t calledChangeId = -1;
    const CarPowerState state = CarPowerState::SHUTDOWN_PREPARE;
    const int64_t expirationTimeMs = 5000;
    EXPECT_CALL(*callback, onAllPowerStateChangeListenersComplete)
            .WillRepeatedly(Invoke([&calledChangeId](int32_t changeId) -> ScopedAStatus {
                calledChangeId = changeId;
                return ScopedAStatus::ok();
            }));

    ScopedAStatus status = server->notifyPowerStateChange(changeId, state, expirationTimeMs);

    ASSERT_TRUE(status.isOk()) << "notifyPowerStateChange should return OK";

    ASSERT_EQ(calledChangeId, changeId)
            << "Power state change ID passed to onAllPowerStateChangeListenersComplete should "
               "match the one passed to notifyPowerStateChange";
}

TEST_F(CarPowerPolicyServerTest, TestNotifyPowerStateChange_listenerWithoutCompletion) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    std::shared_ptr<MockPowerStateChangeListener> listener = getMockPowerStateChangeListener();
    setUpServerWithPowerStateListener(server, callback, listener);

    const int32_t changeId = 777;
    int32_t calledChangeId = -1;
    const CarPowerState state = CarPowerState::HIBERNATION_ENTER;
    std::mutex mutex;
    std::condition_variable cv;
    EXPECT_CALL(*callback, onAllPowerStateChangeListenersComplete)
            .WillRepeatedly(
                    Invoke([&calledChangeId, &cv, &mutex](int32_t changeId) -> ScopedAStatus {
                        calledChangeId = changeId;
                        std::unique_lock lock(mutex);
                        cv.notify_all();
                        return ScopedAStatus::ok();
                    }));
    CarPowerState notifiedState;
    EXPECT_CALL(*listener, onStateChanged)
            .WillRepeatedly(Invoke([&notifiedState](const CarPowerState state) -> ScopedAStatus {
                notifiedState = state;
                return ScopedAStatus::ok();
            }));
    const int64_t expirationTimeMs = 5000;

    ScopedAStatus status = server->notifyPowerStateChange(changeId, state, expirationTimeMs);

    ASSERT_TRUE(status.isOk()) << "notifyPowerStateChange should return OK";

    EXPECT_EQ(notifiedState, state) << "State notified to listener is incorrect";

    std::unique_lock lock(mutex);
    const bool waitResult = cv.wait_for(lock, kCallbackWaitTime,
                                        [&calledChangeId] { return calledChangeId == changeId; });
    ASSERT_TRUE(waitResult)
            << "notifyPowerStateChange should be called with the same power state change ID";
}

TEST_F(CarPowerPolicyServerTest, TestNotifyPowerStateChange_listenerWithCompletion) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    std::shared_ptr<MockPowerStateChangeListenerWithCompletion> listener =
            getMockPowerStateChangeListenerWithCompletion();
    setUpServerWithPowerStateListenerWithCompletion(server, callback, listener);

    const int32_t changeId = 234;
    int32_t calledChangeId = -1;
    const CarPowerState state = CarPowerState::SUSPEND_ENTER;
    std::mutex mutex;
    std::condition_variable cv;
    EXPECT_CALL(*callback, onAllPowerStateChangeListenersComplete)
            .WillRepeatedly(
                    Invoke([&calledChangeId, &cv, &mutex](int32_t changeId) -> ScopedAStatus {
                        calledChangeId = changeId;
                        std::unique_lock lock(mutex);
                        cv.notify_all();
                        return ScopedAStatus::ok();
                    }));
    CarPowerState notifiedState;
    int64_t notifiedExpirationTimestamp;
    std::thread listenerThread;
    EXPECT_CALL(*listener, onStateChanged)
            .WillRepeatedly(Invoke(
                    [&notifiedState, &notifiedExpirationTimestamp,
                     &listenerThread](const CarPowerState state, const int64_t expirationTimeMs,
                                      const std::shared_ptr<ICompletablePowerStateChangeFuture>&
                                              future) -> ScopedAStatus {
                        notifiedState = state;
                        notifiedExpirationTimestamp = expirationTimeMs;
                        // Simulate listener executing work on another thread to better represent
                        // real scenario where listener will execute onStateChanged over binder
                        listenerThread = std::thread([&future]() {
                            std::this_thread::sleep_for(std::chrono::milliseconds(400));
                            future->complete();
                        });
                        return ScopedAStatus::ok();
                    }));
    const int64_t expirationTimeMs = 500;
    const int64_t beforeNotifyTimestamp = android::elapsedRealtime();

    ScopedAStatus status = server->notifyPowerStateChange(changeId, state, expirationTimeMs);
    ASSERT_TRUE(status.isOk()) << "notifyPowerStateChange should return OK";

    const int64_t afterNotifyTimestamp = android::elapsedRealtime();
    EXPECT_LT(afterNotifyTimestamp, beforeNotifyTimestamp + expirationTimeMs)
            << "Power state notification (and therefore listener) should complete before time out";

    EXPECT_EQ(notifiedState, state) << "State notified to listener is incorrect";
    EXPECT_GE(abs(notifiedExpirationTimestamp - beforeNotifyTimestamp), expirationTimeMs)
            << "Expiration time supplied to listener with completion is too early";

    std::unique_lock lock(mutex);
    const bool waitResult = cv.wait_for(lock, std::chrono::milliseconds(expirationTimeMs),
                                        [&calledChangeId] { return calledChangeId == changeId; });
    ASSERT_TRUE(waitResult)
            << "notifyPowerStateChange should be called with the same power state change ID";

    if (listenerThread.joinable()) {
        listenerThread.join();
    }
}

TEST_F(CarPowerPolicyServerTest, TestNotifyPowerStateChange_listenerWithCompletionTimesOut) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    std::shared_ptr<MockPowerStateChangeListenerWithCompletion> listener =
            getMockPowerStateChangeListenerWithCompletion();
    setUpServerWithPowerStateListenerWithCompletion(server, callback, listener);

    const int32_t changeId = 345;
    int32_t calledChangeId = -1;
    const CarPowerState state = CarPowerState::PRE_SHUTDOWN_PREPARE;
    std::mutex listenersCompleteMutex;
    std::condition_variable listenersCompleteCv;
    EXPECT_CALL(*callback, onAllPowerStateChangeListenersComplete)
            .WillRepeatedly(Invoke([&calledChangeId, &listenersCompleteCv,
                                    &listenersCompleteMutex](int32_t changeId) -> ScopedAStatus {
                calledChangeId = changeId;
                std::unique_lock lock(listenersCompleteMutex);
                listenersCompleteCv.notify_all();
                return ScopedAStatus::ok();
            }));
    std::shared_ptr<ICompletablePowerStateChangeFuture> notifiedFuture;
    EXPECT_CALL(*listener, onStateChanged)
            .WillRepeatedly(Invoke(
                    [&notifiedFuture]([[maybe_unused]] const CarPowerState state,
                                      [[maybe_unused]] const int64_t expirationTimeMs,
                                      const std::shared_ptr<ICompletablePowerStateChangeFuture>&
                                              future) -> ScopedAStatus {
                        notifiedFuture = future;
                        return ScopedAStatus::ok();
                    }));
    const int64_t expirationTimeMs = 200;
    const int64_t beforeNotifyTimestamp = android::elapsedRealtime();

    ScopedAStatus status = server->notifyPowerStateChange(changeId, state, expirationTimeMs);
    ASSERT_TRUE(status.isOk()) << "notifyPowerStateChange should return OK";

    const int64_t afterNotifyTimestamp = android::elapsedRealtime();
    ASSERT_GE(afterNotifyTimestamp, beforeNotifyTimestamp + expirationTimeMs)
            << "notifyPowerStateChange should time out before notifying car service that listeners "
               "completed or timed out";
    // Future doesn't complete until after the timeout is reached, no error should occur
    notifiedFuture->complete();

    std::unique_lock lock(listenersCompleteMutex);
    const bool waitResult =
            listenersCompleteCv.wait_for(lock, std::chrono::milliseconds(expirationTimeMs),
                                         [&calledChangeId] { return calledChangeId == changeId; });
    ASSERT_TRUE(waitResult)
            << "notifyPowerStateChange should be called with the same power state change ID";
}

TEST_F(CarPowerPolicyServerTest, TestNotifyPowerStateChange_nonCompletableState) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    std::shared_ptr<MockPowerStateChangeListenerWithCompletion> listener =
            getMockPowerStateChangeListenerWithCompletion();
    setUpServerWithPowerStateListenerWithCompletion(server, callback, listener);

    const int32_t changeId = 999;
    int32_t calledChangeId = -1;
    const CarPowerState state = CarPowerState::SHUTDOWN_CANCELLED;
    std::mutex mutex;
    std::condition_variable cv;
    EXPECT_CALL(*callback, onAllPowerStateChangeListenersComplete)
            .WillRepeatedly(
                    Invoke([&calledChangeId, &cv, &mutex](int32_t changeId) -> ScopedAStatus {
                        calledChangeId = changeId;
                        std::unique_lock lock(mutex);
                        cv.notify_all();
                        return ScopedAStatus::ok();
                    }));
    CarPowerState notifiedState;
    int64_t notifiedExpirationTimestamp;
    std::shared_ptr<ICompletablePowerStateChangeFuture> notifiedFuture;
    EXPECT_CALL(*listener, onStateChanged)
            .WillRepeatedly(Invoke(
                    [&notifiedState, &notifiedExpirationTimestamp,
                     &notifiedFuture](const CarPowerState state, const int64_t expirationTimeMs,
                                      const std::shared_ptr<ICompletablePowerStateChangeFuture>&
                                              future) -> ScopedAStatus {
                        notifiedState = state;
                        notifiedExpirationTimestamp = expirationTimeMs;
                        notifiedFuture = future;
                        return ScopedAStatus::ok();
                    }));
    const int64_t expirationTimeMs = 500;
    const int64_t beforeNotifyTimestamp = android::elapsedRealtime();

    ScopedAStatus status = server->notifyPowerStateChange(changeId, state, expirationTimeMs);
    ASSERT_TRUE(status.isOk()) << "notifyPowerStateChange should return OK";

    const int64_t afterNotifyTimestamp = android::elapsedRealtime();
    EXPECT_LT(afterNotifyTimestamp, beforeNotifyTimestamp + (expirationTimeMs * 0.5))
            << "notifyPowerStateChange should not block on futures completing and should not time "
               "out before notifying car service";

    EXPECT_EQ(notifiedState, state) << "State notified to listener with completion is incorrect";
    EXPECT_TRUE(abs(notifiedExpirationTimestamp - beforeNotifyTimestamp) < expirationTimeMs)
            << "Expiration time supplied to listener with completion is too late";
    EXPECT_EQ(notifiedFuture, nullptr)
            << "Future supplied to listener with completion should be null";

    std::unique_lock lock(mutex);
    const bool waitResult = cv.wait_for(lock, kCallbackWaitTime,
                                        [&calledChangeId] { return calledChangeId == changeId; });
    ASSERT_TRUE(waitResult)
            << "notifyPowerStateChange should be called with the same power state change ID";
}

TEST_F(CarPowerPolicyServerTest, TestNotifyPowerStateChange_serverDies) {
#ifndef LAUNCH_CAR_POWER_SERVER
    GTEST_SKIP() << "native_power_notifications feature flag is not enabled";
#endif  // LAUNCH_CAR_POWER_SERVER

    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make();
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    std::shared_ptr<MockPowerStateChangeListenerWithCompletion> listener =
            getMockPowerStateChangeListenerWithCompletion();
    setUpServerWithPowerStateListenerWithCompletion(server, callback, listener);

    const int32_t changeId = 432;
    int32_t calledChangeId = -1;
    const CarPowerState state = CarPowerState::POST_SUSPEND_ENTER;
    std::mutex mutex;
    std::condition_variable cv;
    EXPECT_CALL(*callback, onAllPowerStateChangeListenersComplete)
            .WillRepeatedly(
                    Invoke([&calledChangeId, &cv, &mutex](int32_t changeId) -> ScopedAStatus {
                        calledChangeId = changeId;
                        std::unique_lock lock(mutex);
                        cv.notify_all();
                        return ScopedAStatus::ok();
                    }));
    std::shared_ptr<ICompletablePowerStateChangeFuture> notifiedFuture;
    EXPECT_CALL(*listener, onStateChanged)
            .WillRepeatedly(Invoke(
                    [&notifiedFuture]([[maybe_unused]] const CarPowerState state,
                                      [[maybe_unused]] const int64_t expirationTimeMs,
                                      const std::shared_ptr<ICompletablePowerStateChangeFuture>&
                                              future) -> ScopedAStatus {
                        notifiedFuture = future;
                        return ScopedAStatus::ok();
                    }));
    const int64_t expirationTimeMs = 500;

    ScopedAStatus status = server->notifyPowerStateChange(changeId, state, expirationTimeMs);
    ASSERT_TRUE(status.isOk()) << "notifyPowerStateChange should return OK";

    server.clear();

    std::unique_lock lock(mutex);
    const bool waitResult = cv.wait_for(lock, std::chrono::milliseconds(expirationTimeMs),
                                        [&calledChangeId] { return calledChangeId == changeId; });
    ASSERT_TRUE(waitResult)
            << "notifyPowerStateChange should be called with the same power state change ID";

    status = notifiedFuture->complete();
    ASSERT_TRUE(status.isOk()) << "Calling future complete() after car power server destroyed "
                                  "should return OK";
}

TEST_F(CarPowerPolicyServerTest, TestSetMaxConnectToVhalRetryCount) {
    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make(
            /*connectToVhalTimeoutMillis=*/5000);

    EXPECT_EQ(server->getMaxConnectToVhalRetryCount(), 25u);
}

TEST_F(CarPowerPolicyServerTest, TestSetMaxConnectToVhalRetryCount_roundUp) {
    sp<internal::CarPowerPolicyServerPeer> server = sp<internal::CarPowerPolicyServerPeer>::make(
            /*connectToVhalTimeoutMillis=*/1);

    EXPECT_EQ(server->getMaxConnectToVhalRetryCount(), 1u)
            << "The max connectToVhal retry count must be rounded up";
}

TEST_F(CarPowerPolicyServerTest, TestVhalDelayedConnection) {
    MockFunction<std::unique_ptr<android::frameworks::automotive::vhal::IVhalClient>()>
            fakeVhalCreationFn;
    std::function<std::unique_ptr<android::frameworks::automotive::vhal::IVhalClient>()>
            vhalCreationFn = fakeVhalCreationFn.AsStdFunction();
    EXPECT_CALL(fakeVhalCreationFn, Call())
            .WillOnce(Return(nullptr))
            .WillOnce(Return(nullptr))
            .WillOnce(Return(nullptr))
            .WillOnce(Return(nullptr))
            .WillOnce(Return(nullptr))
            .WillRepeatedly(Invoke(
                    []() -> std::unique_ptr<FakeVhal> { return std::make_unique<FakeVhal>(); }));
    sp<internal::CarPowerPolicyServerPeer> server =
            sp<internal::CarPowerPolicyServerPeer>::make(vhalCreationFn);
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    EXPECT_CALL(*callback, updatePowerComponents)
            .WillRepeatedly(
                    Invoke([]([[maybe_unused]] const CarPowerPolicy& policy) -> ScopedAStatus {
                        return ScopedAStatus::ok();
                    }));
    EXPECT_CALL(*callback, onPowerPolicyChanged(_)).WillRepeatedly([] {
        return ScopedAStatus::ok();
    });
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
    server->init(/* initializePowerPolicy= */ false);
    setSystemCallingUid();
    PowerPolicyInitData initData;

    ScopedAStatus status = server->notifyCarServiceReady(callback, &initData);

    EXPECT_TRUE(status.isOk()) << "Notifying car service ready should be successful";
}

TEST_F(CarPowerPolicyServerTest, TestVhalConnectionTimesOut) {
    MockFunction<std::unique_ptr<android::frameworks::automotive::vhal::IVhalClient>()>
            fakeVhalCreationFn;
    std::function<std::unique_ptr<android::frameworks::automotive::vhal::IVhalClient>()>
            vhalCreationFn = fakeVhalCreationFn.AsStdFunction();
    EXPECT_CALL(fakeVhalCreationFn, Call())
            .WillRepeatedly(Invoke([]() -> std::unique_ptr<FakeVhal> {
                // exceeds VHAL connection timeout
                std::this_thread::sleep_for(std::chrono::milliseconds(10000));
                return std::make_unique<FakeVhal>();
            }));
    sp<internal::CarPowerPolicyServerPeer> server =
            sp<internal::CarPowerPolicyServerPeer>::make(vhalCreationFn);
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    EXPECT_CALL(*callback, updatePowerComponents)
            .WillRepeatedly(
                    Invoke([]([[maybe_unused]] const CarPowerPolicy& policy) -> ScopedAStatus {
                        return ScopedAStatus::ok();
                    }));
    EXPECT_CALL(*callback, onPowerPolicyChanged(_)).WillRepeatedly([] {
        return ScopedAStatus::ok();
    });
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
    server->init(/* initializePowerPolicy= */ false);
    setSystemCallingUid();
    PowerPolicyInitData initData;

    EXPECT_DEATH(server->notifyCarServiceReady(callback, &initData), HasSubstr(""))
            << "Notifying car service ready should fail";
}

TEST_F(CarPowerPolicyServerTest, TestVhalConnectionTooManyRetries) {
    MockFunction<std::unique_ptr<android::frameworks::automotive::vhal::IVhalClient>()>
            fakeVhalCreationFn;
    std::function<std::unique_ptr<android::frameworks::automotive::vhal::IVhalClient>()>
            vhalCreationFn = fakeVhalCreationFn.AsStdFunction();
    EXPECT_CALL(fakeVhalCreationFn, Call()).WillRepeatedly([]() {
        return std::unique_ptr<android::frameworks::automotive::vhal::IVhalClient>(nullptr);
    });
    sp<internal::CarPowerPolicyServerPeer> server =
            sp<internal::CarPowerPolicyServerPeer>::make(vhalCreationFn);
    std::shared_ptr<MockPowerManagementDelegateCallback> callback =
            ndk::SharedRefBase::make<MockPowerManagementDelegateCallback>();
    EXPECT_CALL(*callback, updatePowerComponents)
            .WillRepeatedly(
                    Invoke([]([[maybe_unused]] const CarPowerPolicy& policy) -> ScopedAStatus {
                        return ScopedAStatus::ok();
                    }));
    EXPECT_CALL(*callback, onPowerPolicyChanged(_)).WillRepeatedly([] {
        return ScopedAStatus::ok();
    });
    server->expectLinkToDeathStatus(callback->asBinder().get(), STATUS_OK);
    server->init(/* initializePowerPolicy= */ false);
    setSystemCallingUid();
    PowerPolicyInitData initData;

    EXPECT_DEATH(server->notifyCarServiceReady(callback, &initData), HasSubstr(""))
            << "Notifying car service ready should fail";
}

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android
