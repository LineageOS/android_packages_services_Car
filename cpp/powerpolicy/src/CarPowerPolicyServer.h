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

#ifndef CPP_POWERPOLICY_SRC_CARPOWERPOLICYSERVER_H_
#define CPP_POWERPOLICY_SRC_CARPOWERPOLICYSERVER_H_

#include "PolicyManager.h"
#include "PowerComponentHandler.h"

#include <android-base/result.h>
#include <android/frameworks/automotive/powerpolicy/BnCarPowerPolicyServer.h>
#include <android/hardware/automotive/vehicle/2.0/IVehicle.h>
#include <binder/IBinder.h>
#include <binder/Status.h>
#include <utils/Looper.h>
#include <utils/Mutex.h>
#include <utils/String16.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

#include <unordered_set>

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

struct CallbackInfo {
    CallbackInfo(const sp<ICarPowerPolicyChangeCallback>& callback,
                 const CarPowerPolicyFilter& filter, int32_t pid) :
          callback(callback),
          filter(filter),
          pid(pid) {}

    sp<ICarPowerPolicyChangeCallback> callback;
    CarPowerPolicyFilter filter;
    pid_t pid;
};

// Forward declaration for testing use only.
namespace internal {

class CarPowerPolicyServerPeer;

}  // namespace internal

// Forward declaration for defining binder death handler and property change listener.
class CarPowerPolicyServer;

class BinderDeathRecipient : public IBinder::DeathRecipient {
public:
    explicit BinderDeathRecipient(const sp<CarPowerPolicyServer>& service);

    void binderDied(const wp<IBinder>& who) override;

private:
    sp<CarPowerPolicyServer> mService;
};

class HidlDeathRecipient : public hardware::hidl_death_recipient {
public:
    explicit HidlDeathRecipient(const sp<CarPowerPolicyServer>& service);

    void serviceDied(uint64_t cookie, const wp<hidl::base::V1_0::IBase>& who) override;

private:
    sp<CarPowerPolicyServer> mService;
};

class PropertyChangeListener : public hardware::automotive::vehicle::V2_0::IVehicleCallback {
public:
    explicit PropertyChangeListener(const sp<CarPowerPolicyServer>& service);

    hardware::Return<void> onPropertyEvent(
            const hardware::hidl_vec<hardware::automotive::vehicle::V2_0::VehiclePropValue>&
                    propValues) override;
    hardware::Return<void> onPropertySet(
            const hardware::automotive::vehicle::V2_0::VehiclePropValue& propValue);
    hardware::Return<void> onPropertySetError(
            hardware::automotive::vehicle::V2_0::StatusCode status, int32_t propId, int32_t areaId);

private:
    sp<CarPowerPolicyServer> mService;
};

class MessageHandlerImpl : public MessageHandler {
public:
    explicit MessageHandlerImpl(const sp<CarPowerPolicyServer>& service);

    void handleMessage(const Message& message) override;

private:
    sp<CarPowerPolicyServer> mService;
};

class CarPowerPolicyServer : public BnCarPowerPolicyServer {
public:
    static base::Result<sp<CarPowerPolicyServer>> startService(const sp<Looper>& looper);
    static void terminateService();

    status_t dump(int fd, const Vector<String16>& args) override;
    binder::Status getCurrentPowerPolicy(CarPowerPolicy* aidlReturn) override;
    binder::Status getPowerComponentState(PowerComponent componentId, bool* aidlReturn) override;
    binder::Status registerPowerPolicyChangeCallback(
            const sp<ICarPowerPolicyChangeCallback>& callback,
            const CarPowerPolicyFilter& filter) override;
    binder::Status unregisterPowerPolicyChangeCallback(
            const sp<ICarPowerPolicyChangeCallback>& callback) override;

    void connectToVhalHelper();
    void handleBinderDeath(const wp<IBinder>& who);
    void handleHidlDeath(const wp<hidl::base::V1_0::IBase>& who);
    base::Result<void> applyPowerPolicy(const std::string& policyId);
    base::Result<void> setPowerPolicyGroup(const std::string& groupId);

private:
    CarPowerPolicyServer();

    base::Result<void> init(const sp<Looper>& looper);
    void terminate();
    bool isRegisteredLocked(const sp<ICarPowerPolicyChangeCallback>& callback);
    void checkSilentModeFromKernel();
    void connectToVhal();
    void subscribeToVhal();
    void subscribeToProperty(
            int32_t prop,
            std::function<void(const hardware::automotive::vehicle::V2_0::VehiclePropValue&)>
                    processor);
    base::Result<void> notifyVhalNewPowerPolicy(const std::string& policyId);
    bool isPropertySupported(int32_t prop);

private:
    static sp<CarPowerPolicyServer> sCarPowerPolicyServer;

    sp<Looper> mHandlerLooper;
    sp<MessageHandlerImpl> mMessageHandler;
    PowerComponentHandler mComponentHandler;
    PolicyManager mPolicyManager;
    Mutex mMutex;
    CarPowerPolicyPtr mCurrentPowerPolicy GUARDED_BY(mMutex);
    std::string mCurrentPolicyGroupId GUARDED_BY(mMutex);
    std::vector<CallbackInfo> mPolicyChangeCallbacks GUARDED_BY(mMutex);
    sp<hardware::automotive::vehicle::V2_0::IVehicle> mVhalService GUARDED_BY(mMutex);
    int64_t mLastApplyPowerPolicy GUARDED_BY(mMutex);
    int64_t mLastSetDefaultPowerPolicyGroup GUARDED_BY(mMutex);
    std::unordered_map<int32_t, bool> mSupportedProperties;
    sp<BinderDeathRecipient> mBinderDeathRecipient;
    sp<HidlDeathRecipient> mHidlDeathRecipient;
    sp<PropertyChangeListener> mPropertyChangeListener;
    int32_t mRemainingConnectionRetryCount;

    // For unit tests.
    friend class internal::CarPowerPolicyServerPeer;
};

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android

#endif  // CPP_POWERPOLICY_SRC_CARPOWERPOLICYSERVER_H_
