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

#define LOG_TAG "carwatchdogd"
#define DEBUG false  // STOPSHIP if true.

#include "WatchdogServiceHelper.h"

#include "ServiceManager.h"

#include <android/binder_ibinder.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::TimeoutLength;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem;
using ::android::sp;
using ::android::base::Error;
using ::android::base::Result;
using ::ndk::ScopedAStatus;
using ::ndk::SpAIBinder;

namespace {

ScopedAStatus fromExceptionCodeWithMessage(binder_exception_t exceptionCode,
                                           const std::string& message) {
    ALOGW("%s.", message.c_str());
    return ScopedAStatus::fromExceptionCodeWithMessage(exceptionCode, message.c_str());
}

void onBinderDied(void* cookie) {
    const auto& thiz = ServiceManager::getInstance()->getWatchdogServiceHelper();
    if (thiz == nullptr) {
        return;
    }
    thiz->handleBinderDeath(cookie);
}

}  // namespace

WatchdogServiceHelper::WatchdogServiceHelper() :
      WatchdogServiceHelperBase(onBinderDied), mWatchdogProcessService(nullptr) {
    mOnUnregisterServiceLocked = [this](const SpAIBinder& binder) -> ScopedAStatus {
        if (mWatchdogProcessService == nullptr) {
            return fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                "Must initialize watchdog process service "
                                                "before unregistering car watchdog "
                                                "service");
        }
        mWatchdogProcessService->unregisterCarWatchdogService(binder);
        return ScopedAStatus::ok();
    };
}

Result<void> WatchdogServiceHelper::init(
        const sp<WatchdogProcessServiceInterface>& watchdogProcessService) {
    if (watchdogProcessService == nullptr) {
        return Error() << "Must provide a non-null watchdog process service instance";
    }
    mWatchdogProcessService = watchdogProcessService;
    return {};
}

ScopedAStatus WatchdogServiceHelper::registerService(
        const std::shared_ptr<ICarWatchdogServiceForSystem>& service) {
    std::function<ndk::ScopedAStatus(const SpAIBinder&)> onRegisterServiceLocked =
            [this](const SpAIBinder& binder) -> ScopedAStatus {
        if (mWatchdogProcessService == nullptr) {
            return fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,
                                                "Must initialize watchdog process service "
                                                "before registering car watchdog service");
        }
        return mWatchdogProcessService
                ->registerCarWatchdogService(binder,
                                             sp<WatchdogServiceHelperInterface>::fromExisting(
                                                     this));
    };
    return registerServiceInternal(service, onRegisterServiceLocked, mOnUnregisterServiceLocked);
}

ScopedAStatus WatchdogServiceHelper::unregisterService(
        const std::shared_ptr<ICarWatchdogServiceForSystem>& service) {
    return unregisterServiceInternal(service, mOnUnregisterServiceLocked);
}

void WatchdogServiceHelper::handleBinderDeath(void* cookie) {
    handleBinderDeathInternal(cookie, mOnUnregisterServiceLocked);
}

void WatchdogServiceHelper::terminate() {
    terminateInternal(mOnUnregisterServiceLocked);
    mWatchdogProcessService.clear();
}

ScopedAStatus WatchdogServiceHelper::checkIfAlive(const SpAIBinder& who, int32_t sessionId,
                                                  TimeoutLength timeout) const {
    auto service = checkServiceAndGetService(who);
    if (service == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                            "Dropping checkIfAlive request as the given car "
                                            "watchdog service binder isn't "
                                            "registered");
    }

    return service
            ->checkIfAlive(sessionId,
                           static_cast<
                                   aidl::android::automotive::watchdog::internal::TimeoutLength>(
                                   timeout));
}

ScopedAStatus WatchdogServiceHelper::prepareProcessTermination(const SpAIBinder& who) {
    auto service = checkServiceAndGetService(who);
    if (service == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                            "Dropping prepareProcessTermination request as the "
                                            "given car watchdog service binder isn't "
                                            "registered");
    }

    auto status = service->prepareProcessTermination();
    if (status.isOk()) {
        /*
         * prepareProcessTermination is called by WatchdogProcessService after unregistering the
         * CarWatchdogService. So, there is no need to unregister the CarWatchdogService again.
         * Thus, pass nullptr to |onUnregisterServiceLocked|.
         *
         * prepareProcessTermination callback is called when CarWatchdogService isn't responding,
         * which indicates the CarWatchdogService is stuck, terminating, or restarting.
         *
         * When CarWatchdogService is terminating, it will issue an unregisterService call.
         * If the unregisterService is executed after the |readLock| in
         * WatchdogServiceHelperBase::checkServiceAndGetService is released and
         * before the |writeLock| in WatchdogServiceHelperBase::unregisterServiceInternal is
         * acquired, the |WatchdogServiceHelperBase::mService| will be updated to null.
         * Then it won't match |service|.
         *
         * When CarWatchdogService is restarting, it will issue an registerService call. When the
         * registerService is executed between after the previous |readLock| in
         * WatchdogServiceHelperBase::checkServiceAndGetService is released and before
         * the current |writeLock| in WatchdogServiceHelperBase::unregisterServiceInternal is
         * acquired, the |WatchdogServiceHelperBase::mService| will be overwritten. This will lead
         * to unregistering the new CarWatchdogService.
         *
         * To avoid this race condition, check WatchdogServiceHelperBase::mService. This check is
         * performed in WatchdogServiceHelperBase::unregisterServiceInternal.
         */
        std::function<ScopedAStatus(const SpAIBinder&)> onUnregisterServiceLocked;
        unregisterServiceInternal(service, onUnregisterServiceLocked);
    }
    return status;
}

ScopedAStatus WatchdogServiceHelper::requestAidlVhalPid() const {
    auto service = getService();
    if (service == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                            "Dropping requestAidlVhalPid request as the "
                                            "car watchdog service binder isn't "
                                            "registered");
    }
    return service->requestAidlVhalPid();
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
