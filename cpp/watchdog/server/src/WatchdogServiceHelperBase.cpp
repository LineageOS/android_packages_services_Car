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

#define LOG_TAG "carwatchdogd"
#define DEBUG false  // STOPSHIP if true.

#include "WatchdogServiceHelperBase.h"

#include "ServiceManager.h"

#include <android/binder_ibinder.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem;
using ::aidl::android::automotive::watchdog::internal::PackageInfo;
using ::aidl::android::automotive::watchdog::internal::ResourceStats;
using ::android::sp;
using ::ndk::ScopedAIBinder_DeathRecipient;
using ::ndk::ScopedAStatus;
using ::ndk::SpAIBinder;

namespace {

ScopedAStatus fromExceptionCodeWithMessage(binder_exception_t exceptionCode,
                                           const std::string& message) {
    ALOGW("%s.", message.c_str());
    return ScopedAStatus::fromExceptionCodeWithMessage(exceptionCode, message.c_str());
}

void onBinderDied(void* cookie) {
    // TODO(b/398044929): Use IoServiceManager here when it is implemented and
    // return WatchdogServiceHelperBase
    const auto& thiz = ServiceManager::getInstance()->getWatchdogServiceHelper();
    if (thiz == nullptr) {
        return;
    }
    thiz->handleBinderDeath(cookie);
}

}  // namespace

WatchdogServiceHelperBase::WatchdogServiceHelperBase() : WatchdogServiceHelperBase(onBinderDied) {}

WatchdogServiceHelperBase::WatchdogServiceHelperBase(
        AIBinder_DeathRecipient_onBinderDied onBinderDiedFunc) :
      mDeathRegistrationWrapper(sp<AIBinderDeathRegistrationWrapper>::make()),
      mWatchdogServiceDeathRecipient(
              ScopedAIBinder_DeathRecipient(AIBinder_DeathRecipient_new(onBinderDiedFunc))),
      mService(nullptr) {}

ScopedAStatus WatchdogServiceHelperBase::registerService(
        const std::shared_ptr<ICarWatchdogServiceForSystem>& service) {
    return registerServiceInternal(service, /*onRegisterServiceLocked=*/nullptr,
                                   /*onUnregisterServiceLocked=*/nullptr);
}

ScopedAStatus WatchdogServiceHelperBase::registerServiceInternal(
        const std::shared_ptr<ICarWatchdogServiceForSystem>& service,
        const std::function<ScopedAStatus(const SpAIBinder&)>& onRegisterServiceLocked,
        const std::function<ScopedAStatus(const SpAIBinder&)>& onUnregisterServiceLocked) {
    if (service == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT, "Must provide non-null service");
    }
    const auto binder = service->asBinder();
    AIBinder* aiBinder = binder.get();
    {
        std::unique_lock writeLock(mRWMutex);
        if (mService != nullptr && mService->asBinder() == binder) {
            return ScopedAStatus::ok();
        }
        if (auto status = unregisterServiceLocked(onUnregisterServiceLocked); !status.isOk()) {
            return status;
        }
        if (onRegisterServiceLocked) {
            if (auto status = onRegisterServiceLocked(binder); !status.isOk()) {
                return status;
            }
        }
        mService = service;
    }
    auto ret =
            mDeathRegistrationWrapper->linkToDeath(aiBinder, mWatchdogServiceDeathRecipient.get(),
                                                   static_cast<void*>(aiBinder));
    if (!ret.isOk()) {
        std::unique_lock writeLock(mRWMutex);
        if (mService != nullptr && mService->asBinder() == binder) {
            if (onUnregisterServiceLocked) {
                onUnregisterServiceLocked(binder);
            }
            mService.reset();
        }
        return fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                            "Failed to register car watchdog service as it is "
                                            "dead");
    }
    if (DEBUG) {
        ALOGW("CarWatchdogService is registered");
    }
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogServiceHelperBase::unregisterService(
        const std::shared_ptr<ICarWatchdogServiceForSystem>& service) {
    return unregisterServiceInternal(service, /*onUnregisterServiceLocked=*/nullptr);
}

ScopedAStatus WatchdogServiceHelperBase::unregisterServiceInternal(
        const std::shared_ptr<ICarWatchdogServiceForSystem>& service,
        const std::function<ScopedAStatus(const SpAIBinder&)>& onUnregisterServiceLocked) {
    if (service == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT, "Must provide non-null service");
    }
    std::unique_lock writeLock(mRWMutex);
    if (const auto binder = service->asBinder();
        mService == nullptr || binder != mService->asBinder()) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                            "Failed to unregister car watchdog service as it is "
                                            "not registered");
    }
    unregisterServiceLocked(onUnregisterServiceLocked);

    if (DEBUG) {
        ALOGW("CarWatchdogService is unregistered");
    }
    return ScopedAStatus::ok();
}

void WatchdogServiceHelperBase::handleBinderDeath(void* cookie) {
    handleBinderDeathInternal(cookie, /*onUnregisterServiceLocked=*/nullptr);
}

void WatchdogServiceHelperBase::handleBinderDeathInternal(
        void* cookie,
        const std::function<ScopedAStatus(const SpAIBinder&)>& onUnregisterServiceLocked) {
    std::unique_lock writeLock(mRWMutex);
    if (mService == nullptr) {
        return;
    }
    const auto curBinder = mService->asBinder();
    if (reinterpret_cast<uintptr_t>(curBinder.get()) != reinterpret_cast<uintptr_t>(cookie)) {
        return;
    }
    ALOGW("Car watchdog service had died.");
    mService.reset();
    if (onUnregisterServiceLocked) {
        onUnregisterServiceLocked(curBinder);
    }
}

void WatchdogServiceHelperBase::terminate() {
    std::unique_lock writeLock(mRWMutex);
    unregisterServiceLocked(nullptr);
}

void WatchdogServiceHelperBase::terminateInternal(
        const std::function<ScopedAStatus(const SpAIBinder&)>& onUnregisterServiceLocked) {
    std::unique_lock writeLock(mRWMutex);
    unregisterServiceLocked(onUnregisterServiceLocked);
}

std::shared_ptr<ICarWatchdogServiceForSystem> WatchdogServiceHelperBase::checkServiceAndGetService(
        const SpAIBinder& who) const {
    if (std::shared_lock readLock(mRWMutex); mService == nullptr || mService->asBinder() != who) {
        return nullptr;
    } else {
        return mService;
    }
}

std::shared_ptr<ICarWatchdogServiceForSystem> WatchdogServiceHelperBase::getService() const {
    std::shared_lock readLock(mRWMutex);
    return mService;
}

ndk::ScopedAStatus WatchdogServiceHelperBase::unregisterServiceLocked(
        const std::function<ScopedAStatus(const SpAIBinder&)>& onUnregisterServiceLocked) {
    if (mService == nullptr) {
        return ScopedAStatus::ok();
    }
    const auto binder = mService->asBinder();
    AIBinder* aiBinder = binder.get();
    mDeathRegistrationWrapper->unlinkToDeath(aiBinder, mWatchdogServiceDeathRecipient.get(),
                                             static_cast<void*>(aiBinder));
    mService.reset();
    if (onUnregisterServiceLocked) {
        if (auto status = onUnregisterServiceLocked(binder); !status.isOk()) {
            return status;
        }
    }
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogServiceHelperBase::getPackageInfosForUids(
        const std::vector<int32_t>& uids, const std::vector<std::string>& vendorPackagePrefixes,
        std::vector<PackageInfo>* packageInfos) const {
    std::shared_ptr<ICarWatchdogServiceForSystem> service;
    if (std::shared_lock readLock(mRWMutex); mService == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                            "Watchdog service is not initialized");
    } else {
        service = mService;
    }
    /*
     * The expected number of vendor package prefixes is in the order of 10s. Thus the overhead of
     * forwarding these in each get call is very low.
     */
    return service->getPackageInfosForUids(uids, vendorPackagePrefixes, packageInfos);
}

ScopedAStatus WatchdogServiceHelperBase::resetResourceOveruseStats(
        const std::vector<std::string>& packageNames) const {
    std::shared_ptr<ICarWatchdogServiceForSystem> service;
    if (std::shared_lock readLock(mRWMutex); mService == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                            "Watchdog service is not initialized");
    } else {
        service = mService;
    }
    return service->resetResourceOveruseStats(packageNames);
}

ScopedAStatus WatchdogServiceHelperBase::requestTodayIoUsageStats() const {
    std::shared_ptr<ICarWatchdogServiceForSystem> service;
    if (std::shared_lock readLock(mRWMutex); mService == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                            "Watchdog service is not initialized");
    } else {
        service = mService;
    }
    return service->requestTodayIoUsageStats();
}

ScopedAStatus WatchdogServiceHelperBase::onLatestResourceStats(
        const std::vector<ResourceStats>& resourceStats) const {
    std::shared_ptr<ICarWatchdogServiceForSystem> service;
    if (std::shared_lock readLock(mRWMutex); mService == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                            "Watchdog service is not initialized");
    } else {
        service = mService;
    }
    return service->onLatestResourceStats(resourceStats);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
