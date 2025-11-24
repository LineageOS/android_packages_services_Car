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

#ifdef CARWATCHDOGD_BINARY
#define LOG_TAG "carwatchdogd"
#else
#define LOG_TAG "iowatchdogd"
#endif

#include "WatchdogBinderMediatorBase.h"

#include <aidl/android/automotive/watchdog/IoOveruseStats.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android/binder_interface_utils.h>
#include <binder/IServiceManager.h>
#include <log/log.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::ICarWatchdogClient;
using ::aidl::android::automotive::watchdog::ICarWatchdogMonitor;
using ::aidl::android::automotive::watchdog::IoOveruseStats;
using ::aidl::android::automotive::watchdog::IResourceOveruseListener;
using ::aidl::android::automotive::watchdog::ResourceOveruseStats;
using ::aidl::android::automotive::watchdog::ResourceType;
using ::aidl::android::automotive::watchdog::StateType;
using ::aidl::android::automotive::watchdog::TimeoutLength;
using ::android::defaultServiceManager;
using ::android::sp;
using ::android::String16;
using ::android::base::Error;
using ::android::base::Result;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::ndk::ICInterface;
using ::ndk::ScopedAStatus;
using ::ndk::SharedRefBase;

using AddServiceFunction =
        std::function<android::base::Result<void>(const char*, ICInterface*, bool, int)>;

namespace {

constexpr const char* kCarWatchdogServerInterface =
        "android.automotive.watchdog.ICarWatchdog/default";
constexpr const char* kCarWatchdogInternalServerInterface =
        "android.automotive.watchdog.internal.ICarWatchdog/default";

ScopedAStatus toScopedAStatus(const int32_t exceptionCode, const std::string& message) {
    ALOGW("%s", message.c_str());
    return ScopedAStatus::fromExceptionCodeWithMessage(exceptionCode, message.c_str());
}

Result<void> addToServiceManager(const char* name, ICInterface* service, bool allowIsolated,
                                 int dumpsysFlags) {
    auto serviceStatus = defaultServiceManager()->addService(String16(name),
                                                             AIBinder_toPlatformBinder(
                                                                     service->asBinder().get()),
                                                             allowIsolated, dumpsysFlags);

    if (serviceStatus != android::OK) {
        return Error(FAILED_TRANSACTION) << "Failed to add '" << name << "' to ServiceManager";
    }
    return {};
}

}  // namespace

WatchdogBinderMediatorBase::WatchdogBinderMediatorBase(
        const android::sp<WatchdogPerfServiceBaseInterface>& watchdogPerfServiceBase,
        const android::sp<WatchdogServiceHelperBaseInterface>& watchdogServiceHelperBase,
        const android::sp<IoOveruseMonitorBaseInterface>& ioOveruseMonitorBase,
        const AddServiceFunction& addServiceHandler) :
      mIoOveruseMonitorBase(ioOveruseMonitorBase), mAddServiceHandler(addServiceHandler) {
    if (mAddServiceHandler == nullptr) {
        mAddServiceHandler = &addToServiceManager;
    }
    if (watchdogServiceHelperBase != nullptr) {
        mWatchdogInternalHandler =
                SharedRefBase::make<WatchdogInternalHandlerBase>(watchdogServiceHelperBase,
                                                                 watchdogPerfServiceBase,
                                                                 mIoOveruseMonitorBase);
    }
}

Result<void> WatchdogBinderMediatorBase::init() {
    if (mIoOveruseMonitorBase == nullptr || mWatchdogInternalHandler == nullptr) {
        std::string serviceList;
        if (mIoOveruseMonitorBase == nullptr) {
            StringAppendF(&serviceList, "%s%s", (!serviceList.empty() ? ", " : ""),
                          "I/O overuse monitor service");
        }
        if (mWatchdogInternalHandler == nullptr) {
            StringAppendF(&serviceList, "%s%s", (!serviceList.empty() ? ", " : ""),
                          "Watchdog internal handler");
        }
        return Error(INVALID_OPERATION)
                << serviceList << " must be initialized with non-null instance";
    }
    if (const auto result = mWatchdogInternalHandler->init(); !result.ok()) {
        return result;
    }
    if (const auto result = mAddServiceHandler(kCarWatchdogServerInterface, this,
                                               /* allowIsolated= */ false,
                                               IServiceManager::DUMP_FLAG_PRIORITY_HIGH |
                                                       IServiceManager::DUMP_FLAG_PROTO);
        !result.ok()) {
        return result;
    }
    if (const auto result = mAddServiceHandler(kCarWatchdogInternalServerInterface,
                                               mWatchdogInternalHandler.get(),
                                               /* allowIsolated= */ false,
                                               IServiceManager::DUMP_FLAG_PRIORITY_DEFAULT);
        !result.ok()) {
        return result;
    }
    return {};
}

ScopedAStatus WatchdogBinderMediatorBase::registerClient(
        [[maybe_unused]] const std::shared_ptr<
                aidl::android::automotive::watchdog::ICarWatchdogClient>& client,
        [[maybe_unused]] aidl::android::automotive::watchdog::TimeoutLength timeout) {
    return toScopedAStatus(EX_UNSUPPORTED_OPERATION,
                           "Unsupported operation in I/O watchdog daemon.");
}

ScopedAStatus WatchdogBinderMediatorBase::unregisterClient(
        [[maybe_unused]] const std::shared_ptr<
                aidl::android::automotive::watchdog::ICarWatchdogClient>& client) {
    return toScopedAStatus(EX_UNSUPPORTED_OPERATION,
                           "Unsupported operation in I/O watchdog daemon.");
}

ScopedAStatus WatchdogBinderMediatorBase::tellClientAlive(
        [[maybe_unused]] const std::shared_ptr<
                aidl::android::automotive::watchdog::ICarWatchdogClient>& client,
        [[maybe_unused]] int32_t sessionId) {
    return toScopedAStatus(EX_UNSUPPORTED_OPERATION,
                           "Unsupported operation in I/O watchdog daemon.");
}

binder_status_t WatchdogBinderMediatorBase::dump(int fd, const char** args, uint32_t numArgs) {
    return mWatchdogInternalHandler->dump(fd, args, numArgs);
}

ScopedAStatus WatchdogBinderMediatorBase::addResourceOveruseListener(
        const std::vector<ResourceType>& resourceTypes,
        const std::shared_ptr<IResourceOveruseListener>& listener) {
    if (listener == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT,
                               "Must provide a non-null resource overuse listener");
    }
    if (resourceTypes.size() != 1 || resourceTypes[0] != ResourceType::IO) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, "Must provide exactly one I/O resource type");
    }
    /*
     * When more resource types are added, implement a new module to manage listeners for all
     * resources.
     */
    if (const auto result = mIoOveruseMonitorBase->addIoOveruseListener(listener); !result.ok()) {
        return toScopedAStatus(result.error().code(),
                               StringPrintf("Failed to register resource overuse "
                                            "listener: %s ",
                                            result.error().message().c_str()));
    }
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogBinderMediatorBase::removeResourceOveruseListener(
        const std::shared_ptr<IResourceOveruseListener>& listener) {
    if (listener == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT,
                               "Must provide a non-null resource overuse listener");
    }
    if (const auto result = mIoOveruseMonitorBase->removeIoOveruseListener(listener);
        !result.ok()) {
        return toScopedAStatus(result.error().code(),
                               StringPrintf("Failed to unregister resource overuse "
                                            "listener: %s",
                                            result.error().message().c_str()));
    }
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogBinderMediatorBase::getResourceOveruseStats(
        const std::vector<ResourceType>& resourceTypes,
        std::vector<ResourceOveruseStats>* resourceOveruseStats) {
    if (resourceOveruseStats == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT,
                               "Must provide a non-null resource overuse stats "
                               "parcelable");
    }
    if (resourceTypes.size() != 1 || resourceTypes[0] != ResourceType::IO) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, "Must provide exactly one I/O resource type");
    }
    IoOveruseStats ioOveruseStats;
    if (const auto result = mIoOveruseMonitorBase->getIoOveruseStats(&ioOveruseStats);
        !result.ok()) {
        return toScopedAStatus(result.error().code(),
                               StringPrintf("Failed to get resource overuse stats: %s",
                                            result.error().message().c_str()));
    }
    ResourceOveruseStats stats;
    stats.set<ResourceOveruseStats::ioOveruseStats>(std::move(ioOveruseStats));
    resourceOveruseStats->emplace_back(std::move(stats));
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogBinderMediatorBase::registerMediator(
        const std::shared_ptr<ICarWatchdogClient>& /*mediator*/) {
    return toScopedAStatus(EX_UNSUPPORTED_OPERATION, "Deprecated method registerMediator");
}

ScopedAStatus WatchdogBinderMediatorBase::unregisterMediator(
        const std::shared_ptr<ICarWatchdogClient>& /*mediator*/) {
    return toScopedAStatus(EX_UNSUPPORTED_OPERATION, "Deprecated method unregisterMediator");
}

ScopedAStatus WatchdogBinderMediatorBase::registerMonitor(
        const std::shared_ptr<ICarWatchdogMonitor>& /*monitor*/) {
    return toScopedAStatus(EX_UNSUPPORTED_OPERATION, "Deprecated method registerMonitor");
}

ScopedAStatus WatchdogBinderMediatorBase::unregisterMonitor(
        const std::shared_ptr<ICarWatchdogMonitor>& /*monitor*/) {
    return toScopedAStatus(EX_UNSUPPORTED_OPERATION, "Deprecated method unregisterMonitor");
}

ScopedAStatus WatchdogBinderMediatorBase::tellMediatorAlive(
        const std::shared_ptr<ICarWatchdogClient>& /*mediator*/,
        const std::vector<int32_t>& /*clientsNotResponding*/, int32_t /*sessionId*/) {
    return toScopedAStatus(EX_UNSUPPORTED_OPERATION, "Deprecated method tellMediatorAlive");
}

ScopedAStatus WatchdogBinderMediatorBase::tellDumpFinished(
        const std::shared_ptr<ICarWatchdogMonitor>& /*monitor*/, int32_t /*pid*/) {
    return toScopedAStatus(EX_UNSUPPORTED_OPERATION, "Deprecated method tellDumpFinished");
}

ScopedAStatus WatchdogBinderMediatorBase::notifySystemStateChange(StateType /*type*/,
                                                                  int32_t /*arg1*/,
                                                                  int32_t /*arg2*/) {
    return toScopedAStatus(EX_UNSUPPORTED_OPERATION, "Deprecated method notifySystemStateChange");
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
