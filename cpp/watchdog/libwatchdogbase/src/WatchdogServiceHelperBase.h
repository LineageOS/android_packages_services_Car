/**
 * Copyright (c) 2025, The Android Open Source Project
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

#pragma once

#include "AIBinderDeathRegistrationWrapper.h"

#include <aidl/android/automotive/watchdog/TimeoutLength.h>
#include <aidl/android/automotive/watchdog/internal/ICarWatchdogServiceForSystem.h>
#include <aidl/android/automotive/watchdog/internal/PackageInfo.h>
#include <aidl/android/automotive/watchdog/internal/ResourceStats.h>
#include <android-base/result.h>
#include <android/binder_auto_utils.h>
#include <utils/Mutex.h>
#include <utils/StrongPointer.h>

#include <shared_mutex>

namespace android {
namespace automotive {
namespace watchdog {

class IoServiceManager;

// Forward declaration for testing use only.
namespace internal {

class WatchdogServiceHelperBasePeer;

}  // namespace internal

class WatchdogServiceHelperBaseInterface : virtual public android::RefBase {
public:
    virtual bool isServiceConnected() = 0;
    virtual ndk::ScopedAStatus registerService(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service) = 0;
    virtual ndk::ScopedAStatus unregisterService(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service) = 0;
    virtual void handleBinderDeath(void* cookie) = 0;

    // Helper methods for APIs in ICarWatchdogServiceForSystem.aidl.
    virtual ndk::ScopedAStatus getPackageInfosForUids(
            const std::vector<int32_t>& uids, const std::vector<std::string>& vendorPackagePrefixes,
            std::vector<aidl::android::automotive::watchdog::internal::PackageInfo>* packageInfos)
            const = 0;
    virtual ndk::ScopedAStatus resetResourceOveruseStats(
            const std::vector<std::string>& packageNames) const = 0;
    virtual ndk::ScopedAStatus onLatestResourceStats(
            const std::vector<aidl::android::automotive::watchdog::internal::ResourceStats>&
                    resourceStats) const = 0;
    virtual ndk::ScopedAStatus requestTodayIoUsageStats() const = 0;

protected:
    virtual void terminate() = 0;

private:
    friend class IoServiceManager;
};

// WatchdogServiceHelperBase implements the helper functions for the outbound API requests to
// the CarWatchdogService. This class doesn't handle the inbound APIs requests from
// CarWatchdogService except the registration APIs.
class WatchdogServiceHelperBase : public WatchdogServiceHelperBaseInterface {
public:
    WatchdogServiceHelperBase();

    bool isServiceConnected() {
        std::shared_lock readLock(mRWMutex);
        return mService != nullptr;
    }
    ndk::ScopedAStatus registerService(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service) override;
    ndk::ScopedAStatus unregisterService(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service) override;
    void handleBinderDeath(void* cookie) override;

    // Helper methods for ICarWatchdogServiceForSystem.aidl.
    ndk::ScopedAStatus getPackageInfosForUids(
            const std::vector<int32_t>& uids, const std::vector<std::string>& vendorPackagePrefixes,
            std::vector<aidl::android::automotive::watchdog::internal::PackageInfo>* packageInfos)
            const override;
    ndk::ScopedAStatus resetResourceOveruseStats(
            const std::vector<std::string>& packageNames) const override;
    ndk::ScopedAStatus onLatestResourceStats(
            const std::vector<aidl::android::automotive::watchdog::internal::ResourceStats>&
                    resourceStats) const override;
    ndk::ScopedAStatus requestTodayIoUsageStats() const override;

protected:
    // Called by the derived class to redirect handling of binder death events to the derived class
    // implementation.
    explicit WatchdogServiceHelperBase(AIBinder_DeathRecipient_onBinderDied onBinderDiedFunc);
    void terminate();

    ndk::ScopedAStatus registerServiceInternal(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service,
            const std::function<ndk::ScopedAStatus(const ndk::SpAIBinder&)>&
                    onRegisterServiceLocked,
            const std::function<ndk::ScopedAStatus(const ndk::SpAIBinder&)>&
                    onUnregisterServiceLocked);
    ndk::ScopedAStatus unregisterServiceInternal(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service,
            const std::function<ndk::ScopedAStatus(const ndk::SpAIBinder&)>&
                    onUnregisterServiceLocked);
    void handleBinderDeathInternal(void* cookie,
                                   const std::function<ndk::ScopedAStatus(const ndk::SpAIBinder&)>&
                                           onUnregisterServiceLocked);
    void terminateInternal(const std::function<ndk::ScopedAStatus(const ndk::SpAIBinder&)>&
                                   onUnregisterServiceLocked);

    std::shared_ptr<aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>
    checkServiceAndGetService(const ndk::SpAIBinder& who) const;

    std::shared_ptr<aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>
    getService() const;

    // Marked as protected so that WatchdogServiceHelperTest can mock it.
    android::sp<AIBinderDeathRegistrationWrapperInterface> mDeathRegistrationWrapper;

private:
    ndk::ScopedAStatus unregisterServiceLocked(
            const std::function<ndk::ScopedAStatus(const ndk::SpAIBinder&)>&
                    onUnregisterServiceLocked);

    ndk::ScopedAIBinder_DeathRecipient mWatchdogServiceDeathRecipient;

    mutable std::shared_mutex mRWMutex;
    std::shared_ptr<aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>
            mService GUARDED_BY(mRWMutex);

    friend class IoServiceManager;

    // For unit tests.
    friend class internal::WatchdogServiceHelperBasePeer;
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
