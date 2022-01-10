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

#ifndef CPP_POWERPOLICY_SERVER_SRC_CARPOWERPOLICYSERVER_H_
#define CPP_POWERPOLICY_SERVER_SRC_CARPOWERPOLICYSERVER_H_

#include "PolicyManager.h"
#include "PowerComponentHandler.h"
#include "SilentModeHandler.h"

#include <aidl/android/frameworks/automotive/powerpolicy/BnCarPowerPolicyServer.h>
#include <aidl/android/frameworks/automotive/powerpolicy/internal/BnCarPowerPolicySystemNotification.h>
#include <android-base/result.h>
#include <android/hardware/automotive/vehicle/2.0/IVehicle.h>
#include <binder/IBinder.h>
#include <binder/Status.h>
#include <utils/Looper.h>
#include <utils/Mutex.h>
#include <utils/String16.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

#include <optional>
#include <unordered_set>

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

struct CallbackInfo {
    CallbackInfo(::ndk::SpAIBinder binder,
                 const ::aidl::android::frameworks::automotive::powerpolicy::CarPowerPolicyFilter&
                         filter,
                 int32_t pid) :
          binder(binder), filter(filter), pid(pid) {}

    ::ndk::SpAIBinder binder;
    ::aidl::android::frameworks::automotive::powerpolicy::CarPowerPolicyFilter filter;
    pid_t pid;
};

// Forward declaration for testing use only.
namespace internal {

class CarPowerPolicyServerPeer;

}  // namespace internal

// Forward declaration for defining binder death handler and property change listener.
class CarPowerPolicyServer;

class HidlDeathRecipient : public android::hardware::hidl_death_recipient {
public:
    explicit HidlDeathRecipient(const std::shared_ptr<CarPowerPolicyServer>& service);

    void serviceDied(uint64_t cookie,
                     const android::wp<android::hidl::base::V1_0::IBase>& who) override;

private:
    std::shared_ptr<CarPowerPolicyServer> mService;
};

class PropertyChangeListener :
      public android::hardware::automotive::vehicle::V2_0::IVehicleCallback {
public:
    explicit PropertyChangeListener(const std::shared_ptr<CarPowerPolicyServer>& service);

    android::hardware::Return<void> onPropertyEvent(
            const android::hardware::hidl_vec<
                    hardware::automotive::vehicle::V2_0::VehiclePropValue>& propValues) override;
    android::hardware::Return<void> onPropertySet(
            const android::hardware::automotive::vehicle::V2_0::VehiclePropValue& propValue);
    android::hardware::Return<void> onPropertySetError(
            android::hardware::automotive::vehicle::V2_0::StatusCode status, int32_t propId,
            int32_t areaId);

private:
    std::shared_ptr<CarPowerPolicyServer> mService;
};

class MessageHandlerImpl : public android::MessageHandler {
public:
    explicit MessageHandlerImpl(const std::shared_ptr<CarPowerPolicyServer>& service);

    void handleMessage(const android::Message& message) override;

private:
    std::shared_ptr<CarPowerPolicyServer> mService;
};

class CarServiceNotificationHandler :
      public ::aidl::android::frameworks::automotive::powerpolicy::internal::
              BnCarPowerPolicySystemNotification {
public:
    explicit CarServiceNotificationHandler(const std::shared_ptr<CarPowerPolicyServer>& server);

    binder_status_t dump(int fd, const char** args, uint32_t numArgs) override;
    ::ndk::ScopedAStatus notifyCarServiceReady(
            ::aidl::android::frameworks::automotive::powerpolicy::internal::PolicyState*
                    policyState) override;
    ::ndk::ScopedAStatus notifyPowerPolicyChange(const std::string& policyId, bool force) override;
    ::ndk::ScopedAStatus notifyPowerPolicyDefinition(
            const std::string& policyId, const std::vector<std::string>& enabledComponents,
            const std::vector<std::string>& disabledComponents) override;

private:
    std::shared_ptr<CarPowerPolicyServer> mService;
};

/**
 * ISilentModeChangeHandler defines a method which is called when a Silent Mode hw state is changed.
 */
class ISilentModeChangeHandler {
public:
    virtual ~ISilentModeChangeHandler() = 0;

    // Called when Silent Mode is changed.
    virtual void notifySilentModeChange(const bool isSilent) = 0;
};

/**
 * CarPowerPolicyServer implements ISilentModeChangeHandler and ICarPowerPolicyServer.aidl.
 * It handles power policy requests and Silent Mode before Android framework takes control of the
 * device.
 */
class CarPowerPolicyServer final :
      public ISilentModeChangeHandler,
      public ::aidl::android::frameworks::automotive::powerpolicy::BnCarPowerPolicyServer {
public:
    static base::Result<std::shared_ptr<CarPowerPolicyServer>> startService(
            const sp<android::Looper>& looper);
    static void terminateService();

    // Implements ICarPowerPolicyServer.aidl.
    status_t dump(int fd, const char** args, uint32_t numArgs) override;
    ::ndk::ScopedAStatus getCurrentPowerPolicy(
            ::aidl::android::frameworks::automotive::powerpolicy::CarPowerPolicy* aidlReturn)
            override;
    ::ndk::ScopedAStatus getPowerComponentState(
            ::aidl::android::frameworks::automotive::powerpolicy::PowerComponent componentId,
            bool* aidlReturn) override;
    ::ndk::ScopedAStatus registerPowerPolicyChangeCallback(
            const std::shared_ptr<::aidl::android::frameworks::automotive::powerpolicy::
                                          ICarPowerPolicyChangeCallback>& callback,
            const ::aidl::android::frameworks::automotive::powerpolicy::CarPowerPolicyFilter&
                    filter) override;
    ::ndk::ScopedAStatus unregisterPowerPolicyChangeCallback(
            const std::shared_ptr<::aidl::android::frameworks::automotive::powerpolicy::
                                          ICarPowerPolicyChangeCallback>& callback) override;

    void connectToVhalHelper();
    void handleBinderDeath(const AIBinder* client);
    void handleBinderUnlinked(const AIBinder* clientId);
    void handleHidlDeath(const android::wp<android::hidl::base::V1_0::IBase>& who);

    // Implements ICarPowerPolicySystemNotification.aidl.
    ::ndk::ScopedAStatus notifyCarServiceReady(
            ::aidl::android::frameworks::automotive::powerpolicy::internal::PolicyState*
                    policyState);
    ::ndk::ScopedAStatus notifyPowerPolicyChange(const std::string& policyId, bool force);
    ::ndk::ScopedAStatus notifyPowerPolicyDefinition(
            const std::string& policyId, const std::vector<std::string>& enabledComponents,
            const std::vector<std::string>& disabledComponents);

    /**
     * Applies the given power policy.
     *
     * @param carServiceInOperation expected Car Service running state.
     * @param force whether to apply the policy even when the current policy is a system
     *        power policy.
     */
    android::base::Result<void> applyPowerPolicy(const std::string& policyId,
                                                 const bool carServiceInOperation,
                                                 const bool force);
    /**
     * Sets the power policy group which contains rules to map a power state to a default power
     * policy to apply.
     */
    android::base::Result<void> setPowerPolicyGroup(const std::string& groupId);

    // Implements ISilentModeChangeHandler.
    void notifySilentModeChange(const bool isSilent);

private:
    friend class ::ndk::SharedRefBase;

    // OnBinderDiedContext is a type used as a cookie passed deathRecipient. The deathRecipient's
    // onBinderDied function takes only a cookie as input and we have to store all the contexts
    // as the cookie.
    struct OnBinderDiedContext {
        CarPowerPolicyServer* server;
        const AIBinder* clientId;
    };

    class LinkUnlinkImpl {
    public:
        virtual ~LinkUnlinkImpl() = default;

        virtual binder_status_t linkToDeath(AIBinder* binder, AIBinder_DeathRecipient* recipient,
                                            void* cookie) = 0;
        virtual binder_status_t unlinkToDeath(AIBinder* binder, AIBinder_DeathRecipient* recipient,
                                              void* cookie) = 0;
    };

    class AIBinderLinkUnlinkImpl final : public LinkUnlinkImpl {
    public:
        binder_status_t linkToDeath(AIBinder* binder, AIBinder_DeathRecipient* recipient,
                                    void* cookie) override;
        binder_status_t unlinkToDeath(AIBinder* binder, AIBinder_DeathRecipient* recipient,
                                      void* cookie) override;
    };

    CarPowerPolicyServer();

    android::base::Result<void> init(const sp<android::Looper>& looper);
    void terminate();
    bool isRegisteredLocked(const AIBinder* binder);
    void connectToVhal();
    void applyInitialPowerPolicy();
    void subscribeToVhal();
    void subscribeToProperty(
            int32_t prop,
            std::function<
                    void(const android::hardware::automotive::vehicle::V2_0::VehiclePropValue&)>
                    processor);
    android::base::Result<void> notifyVhalNewPowerPolicy(const std::string& policyId);
    bool isPropertySupported(const int32_t prop);
    bool isPowerPolicyAppliedLocked() const;

    static void onBinderDied(void* cookie);
    static void onBinderUnlinked(void* cookie);
    static std::string callbackToString(const CallbackInfo& callback);

    // For test-only.
    void setLinkUnlinkImpl(std::unique_ptr<LinkUnlinkImpl> impl);
    std::vector<CallbackInfo> getPolicyChangeCallbacks();
    size_t countOnBinderDiedContexts();

private:
    static std::shared_ptr<CarPowerPolicyServer> sCarPowerPolicyServer;

    sp<android::Looper> mHandlerLooper;
    sp<MessageHandlerImpl> mMessageHandler;
    PowerComponentHandler mComponentHandler;
    PolicyManager mPolicyManager;
    SilentModeHandler mSilentModeHandler;
    android::Mutex mMutex;
    CarPowerPolicyMeta mCurrentPowerPolicyMeta GUARDED_BY(mMutex);
    std::string mCurrentPolicyGroupId GUARDED_BY(mMutex);
    std::string mPendingPowerPolicyId GUARDED_BY(mMutex);
    bool mIsPowerPolicyLocked GUARDED_BY(mMutex);
    std::vector<CallbackInfo> mPolicyChangeCallbacks GUARDED_BY(mMutex);
    sp<android::hardware::automotive::vehicle::V2_0::IVehicle> mVhalService GUARDED_BY(mMutex);
    std::optional<int64_t> mLastApplyPowerPolicyUptimeMs GUARDED_BY(mMutex);
    std::optional<int64_t> mLastSetDefaultPowerPolicyGroupUptimeMs GUARDED_BY(mMutex);
    bool mIsCarServiceInOperation GUARDED_BY(mMutex);
    // No thread-safety guard is needed because only accessed through main thread handler.
    bool mIsFirstConnectionToVhal;
    std::unordered_map<int32_t, bool> mSupportedProperties;
    ::ndk::ScopedAIBinder_DeathRecipient mDeathRecipient;
    android::sp<HidlDeathRecipient> mHidlDeathRecipient;
    android::sp<PropertyChangeListener> mPropertyChangeListener;
    std::shared_ptr<CarServiceNotificationHandler> mCarServiceNotificationHandler;
    int32_t mRemainingConnectionRetryCount;
    // A stub for link/unlink operation. Can be replaced with mock implementation for testing.
    std::unique_ptr<LinkUnlinkImpl> mLinkUnlinkImpl;

    // A map of callback ptr to context that is required for handleBinderDeath.
    std::unordered_map<const AIBinder*, std::unique_ptr<OnBinderDiedContext>> mOnBinderDiedContexts
            GUARDED_BY(mMutex);

    // For unit tests.
    friend class android::frameworks::automotive::powerpolicy::internal::CarPowerPolicyServerPeer;
};

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android

#endif  // CPP_POWERPOLICY_SERVER_SRC_CARPOWERPOLICYSERVER_H_
