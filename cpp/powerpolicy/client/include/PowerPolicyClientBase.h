/*
 * Copyright (C) 2020 The Android Open Source Project
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

#ifndef CPP_POWERPOLICY_CLIENT_INCLUDE_POWERPOLICYCLIENTBASE_H_
#define CPP_POWERPOLICY_CLIENT_INCLUDE_POWERPOLICYCLIENTBASE_H_

#include <aidl/android/frameworks/automotive/powerpolicy/BnCarPowerPolicyChangeCallback.h>
#include <aidl/android/frameworks/automotive/powerpolicy/BnCarPowerPolicyServer.h>
#include <aidl/android/frameworks/automotive/powerpolicy/CarPowerPolicyFilter.h>
#include <android-base/result.h>
#include <android-base/thread_annotations.h>

#include <condition_variable>  // NOLINT
#include <shared_mutex>
#include <thread>  // NOLINT(build/c++11)
#include <vector>

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

// Utility function to test if a PowerComponent list has the given component.
bool hasComponent(
        const std::vector<::aidl::android::frameworks::automotive::powerpolicy::PowerComponent>&
                components,
        ::aidl::android::frameworks::automotive::powerpolicy::PowerComponent component);

/**
 * PowerPolicyClientBase handles the connection to car power policy daemon and wraps
 * ICarPowerPolicyChangeCallback in order to help HALs handle the policy change easier.
 *
 * In the inheriting class, the change notifiction can be handled as follows:
 *   1. Implement getComponentsOfInterest() so that it returns the vector of components of interest.
 *   2. Override ICarPowerPolicyChangeCallbackk::onPolicyChanged callback.
 *   3. Check if the component of interest is in enabled or disabled components.
 *   4. Handle each case.
 *
 * ScopedAStatus PowerPolicyClient::onPolicyChanged(const CarPowerPolicy& powerPolicy) {
 *     if (hasComponent(powerPolicy.enabledComponents, PowerComponent::AUDIO)) {
 *         // Do something when AUDIO is enabled.
 *     } else if (hasComponent(powerPolicy.disabledComponents, PowerComponent::AUDIO)) {
 *         // Do something when AUDIO is disabled.
 *     }
 *     return ScopedAStatus::ok();
 * }
 */
class PowerPolicyClientBase :
      public ::aidl::android::frameworks::automotive::powerpolicy::BnCarPowerPolicyChangeCallback {
public:
    static void onBinderDied(void* cookie);

    // When initialization fails, this callback is invoked from a (connection) thread other than the
    // main thread.
    virtual void onInitFailed() {}

    // Implement this method to specify components of interest.
    virtual std::vector<::aidl::android::frameworks::automotive::powerpolicy::PowerComponent>
    getComponentsOfInterest() = 0;

    // Override this method to specify custom components of interest.
    virtual std::vector<int> getCustomComponentsOfInterest() { return {}; }

    // init makes connection to power policy daemon and registers to policy change in the
    // background. Call this method one time when you want to listen to power policy changes.
    void init();
    // release unregisters client callback from power policy daemon.
    // Call this method one time when you do not want to listen to power policy changes.
    // It blocks caller's thread by awaiting for connection thread join.
    void release();

    void handleBinderDeath();

protected:
    PowerPolicyClientBase();
    virtual ~PowerPolicyClientBase();

private:
    android::base::Result<void> connectToDaemon();

    std::mutex mLock;
    std::thread mConnectionThread GUARDED_BY(mLock);
    std::shared_ptr<::aidl::android::frameworks::automotive::powerpolicy::ICarPowerPolicyServer>
            mPolicyServer;
    std::shared_ptr<ICarPowerPolicyChangeCallback> mPolicyChangeCallback;
    ::ndk::ScopedAIBinder_DeathRecipient mDeathRecipient;
    std::atomic<bool> mConnecting;
    bool mDisconnecting GUARDED_BY(mLock);
    std::condition_variable mDeathRecipientLinkedCv;
    bool mDeathRecipientLinked GUARDED_BY(mLock) = false;

    static void onDeathRecipientUnlinked(void* cookie);

    void handleDeathRecipientUnlinked();
};

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android

#endif  // CPP_POWERPOLICY_CLIENT_INCLUDE_POWERPOLICYCLIENTBASE_H_
