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

#include "WatchdogBinderMediator.h"

#include <aidl/android/automotive/watchdog/IoOveruseStats.h>
#include <android-base/file.h>
#include <android-base/parseint.h>
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
using ::android::IBinder;
using ::android::sp;
using ::android::String16;
using ::android::base::EqualsIgnoreCase;
using ::android::base::Error;
using ::android::base::Join;
using ::android::base::ParseUint;
using ::android::base::Result;
using ::android::base::Split;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::android::base::WriteStringToFd;
using ::ndk::ICInterface;
using ::ndk::ScopedAStatus;
using ::ndk::SharedRefBase;

using AddServiceFunction = std::function<android::base::Result<void>(ICInterface*, const char*)>;

namespace {

constexpr const char* kHelpFlag = "--help";
constexpr const char* kHelpShortFlag = "-h";
constexpr const char* kHelpText =
        "Car watchdog daemon dumpsys help page:\n"
        "Format: dumpsys android.automotive.watchdog.ICarWatchdog/default [options]\n\n"
        "%s or %s: Displays this help text.\n"
        "When no options are specified, car watchdog report is generated.\n";
constexpr const char* kCarWatchdogServerInterface =
        "android.automotive.watchdog.ICarWatchdog/default";
constexpr const char* kCarWatchdogInternalServerInterface =
        "android.automotive.watchdog.internal.ICarWatchdog/default";
constexpr const char* kNullCarWatchdogClientError =
        "Must provide a non-null car watchdog client instance";

ScopedAStatus fromExceptionCodeWithMessage(const int32_t exceptionCode,
                                           const std::string& message) {
    ALOGW("%s", message.c_str());
    return ScopedAStatus::fromExceptionCodeWithMessage(exceptionCode, message.c_str());
}

Result<void> addToServiceManager(ICInterface* service, const char* instance) {
    if (auto exception = AServiceManager_addService(service->asBinder().get(), instance);
        exception != EX_NONE) {
        return Error(exception) << "Failed to add '" << instance << "' to ServiceManager";
    }
    return {};
}

}  // namespace

WatchdogBinderMediator::WatchdogBinderMediator(
        const android::sp<WatchdogProcessServiceInterface>& watchdogProcessService,
        const android::sp<WatchdogPerfServiceInterface>& watchdogPerfService,
        const android::sp<WatchdogServiceHelperInterface>& watchdogServiceHelper,
        const android::sp<IoOveruseMonitorInterface>& ioOveruseMonitor,
        const AddServiceFunction& addServiceHandler) :
      mWatchdogProcessService(watchdogProcessService),
      mWatchdogPerfService(watchdogPerfService),
      mWatchdogServiceHelper(watchdogServiceHelper),
      mIoOveruseMonitor(ioOveruseMonitor),
      mAddServiceHandler(addServiceHandler) {
    if (mAddServiceHandler == nullptr) {
        mAddServiceHandler = &addToServiceManager;
    }
    if (watchdogServiceHelper != nullptr) {
        mWatchdogInternalHandler =
                SharedRefBase::make<WatchdogInternalHandler>(watchdogServiceHelper,
                                                             mWatchdogProcessService,
                                                             mWatchdogPerfService,
                                                             mIoOveruseMonitor);
    }
}

Result<void> WatchdogBinderMediator::init() {
    if (mWatchdogProcessService == nullptr || mWatchdogPerfService == nullptr ||
        mWatchdogServiceHelper == nullptr || mIoOveruseMonitor == nullptr) {
        std::string serviceList;
        if (mWatchdogProcessService == nullptr) {
            StringAppendF(&serviceList, "%s%s", (!serviceList.empty() ? ", " : ""),
                          "Watchdog process service");
        }
        if (mWatchdogPerfService == nullptr) {
            StringAppendF(&serviceList, "%s%s", (!serviceList.empty() ? ", " : ""),
                          "Watchdog performance service");
        }
        if (mWatchdogServiceHelper == nullptr) {
            StringAppendF(&serviceList, "%s%s", (!serviceList.empty() ? ", " : ""),
                          "Watchdog service helper");
        }
        if (mIoOveruseMonitor == nullptr) {
            StringAppendF(&serviceList, "%s%s", (!serviceList.empty() ? ", " : ""),
                          "I/O overuse monitor service");
        }
        return Error(INVALID_OPERATION)
                << serviceList << " must be initialized with non-null instance";
    }
    if (const auto result = mAddServiceHandler(this, kCarWatchdogServerInterface); !result.ok()) {
        return result;
    }
    if (const auto result = mAddServiceHandler(mWatchdogInternalHandler.get(),
                                               kCarWatchdogInternalServerInterface);
        !result.ok()) {
        return result;
    }
    return {};
}

binder_status_t WatchdogBinderMediator::dump(int fd, const char** args, uint32_t numArgs) {
    if (numArgs == 0) {
        return dumpServices(fd);
    }
    if (numArgs == 1 &&
        (EqualsIgnoreCase(args[0], kHelpFlag) || EqualsIgnoreCase(args[0], kHelpShortFlag))) {
        return dumpHelpText(fd, "");
    }
    if (EqualsIgnoreCase(args[0], kStartCustomCollectionFlag) ||
        EqualsIgnoreCase(args[0], kEndCustomCollectionFlag)) {
        if (auto result = mWatchdogPerfService->onCustomCollection(fd, args, numArgs);
            !result.ok()) {
            std::string mode =
                    EqualsIgnoreCase(args[0], kStartCustomCollectionFlag) ? "start" : "end";
            std::string errorMsg = StringPrintf("Failed to %s custom I/O perf collection: %s",
                                                mode.c_str(), result.error().message().c_str());
            if (result.error().code() == BAD_VALUE) {
                dumpHelpText(fd, errorMsg);
            } else {
                ALOGW("%s", errorMsg.c_str());
            }
            return result.error().code();
        }
        return OK;
    }
    if (numArgs == 2 && EqualsIgnoreCase(args[0], kResetResourceOveruseStatsFlag)) {
        std::string value = std::string(args[1]);
        std::vector<std::string> packageNames = Split(value, ",");
        if (value.empty() || packageNames.empty()) {
            dumpHelpText(fd,
                         StringPrintf("Must provide valid package names: [%s]\n", value.c_str()));
            return BAD_VALUE;
        }
        if (auto result = mIoOveruseMonitor->resetIoOveruseStats(packageNames); !result.ok()) {
            ALOGW("Failed to reset stats for packages: [%s]", value.c_str());
            return FAILED_TRANSACTION;
        }
        return OK;
    }
    std::vector<const char*> argsVector;
    for (uint32_t i = 0; i < numArgs; ++i) {
        argsVector.push_back(args[i]);
    }
    dumpHelpText(fd,
                 StringPrintf("Invalid car watchdog dumpsys options: [%s]\n",
                              Join(argsVector, " ").c_str()));
    return dumpServices(fd);
}

status_t WatchdogBinderMediator::dumpServices(int fd) {
    mWatchdogProcessService->onDump(fd);
    if (auto result = mWatchdogPerfService->onDump(fd); !result.ok()) {
        ALOGW("Failed to dump car watchdog perf service: %s", result.error().message().c_str());
        return result.error().code();
    }
    if (auto result = mIoOveruseMonitor->onDump(fd); !result.ok()) {
        ALOGW("Failed to dump I/O overuse monitor: %s", result.error().message().c_str());
        return result.error().code();
    }
    return OK;
}

status_t WatchdogBinderMediator::dumpHelpText(const int fd, const std::string& errorMsg) {
    if (!errorMsg.empty()) {
        ALOGW("Error: %s", errorMsg.c_str());
        if (!WriteStringToFd(StringPrintf("Error: %s\n\n", errorMsg.c_str()), fd)) {
            ALOGW("Failed to write error message to fd");
            return FAILED_TRANSACTION;
        }
    }
    if (!WriteStringToFd(StringPrintf(kHelpText, kHelpFlag, kHelpShortFlag), fd) ||
        !mWatchdogPerfService->dumpHelpText(fd) || !mIoOveruseMonitor->dumpHelpText(fd)) {
        ALOGW("Failed to write help text to fd");
        return FAILED_TRANSACTION;
    }
    return OK;
}

ScopedAStatus WatchdogBinderMediator::registerClient(
        const std::shared_ptr<ICarWatchdogClient>& client, TimeoutLength timeout) {
    if (client == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogClientError);
    }
    return mWatchdogProcessService->registerClient(client, timeout);
}

ScopedAStatus WatchdogBinderMediator::unregisterClient(
        const std::shared_ptr<ICarWatchdogClient>& client) {
    if (client == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogClientError);
    }
    return mWatchdogProcessService->unregisterClient(client);
}

ScopedAStatus WatchdogBinderMediator::tellClientAlive(
        const std::shared_ptr<ICarWatchdogClient>& client, int32_t sessionId) {
    if (client == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogClientError);
    }
    return mWatchdogProcessService->tellClientAlive(client, sessionId);
}

ScopedAStatus WatchdogBinderMediator::addResourceOveruseListener(
        const std::vector<ResourceType>& resourceTypes,
        const std::shared_ptr<IResourceOveruseListener>& listener) {
    if (listener == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                            "Must provide a non-null resource overuse listener");
    }
    if (resourceTypes.size() != 1 || resourceTypes[0] != ResourceType::IO) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                            "Must provide exactly one I/O resource type");
    }
    /*
     * When more resource types are added, implement a new module to manage listeners for all
     * resources.
     */
    if (const auto result = mIoOveruseMonitor->addIoOveruseListener(listener); !result.ok()) {
        return fromExceptionCodeWithMessage(result.error().code(),
                                            StringPrintf("Failed to register resource overuse "
                                                         "listener: %s ",
                                                         result.error().message().c_str()));
    }
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogBinderMediator::removeResourceOveruseListener(
        const std::shared_ptr<IResourceOveruseListener>& listener) {
    if (listener == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                            "Must provide a non-null resource overuse listener");
    }
    if (const auto result = mIoOveruseMonitor->removeIoOveruseListener(listener); !result.ok()) {
        return fromExceptionCodeWithMessage(result.error().code(),
                                            StringPrintf("Failed to unregister resource overuse "
                                                         "listener: %s",
                                                         result.error().message().c_str()));
    }
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogBinderMediator::getResourceOveruseStats(
        const std::vector<ResourceType>& resourceTypes,
        std::vector<ResourceOveruseStats>* resourceOveruseStats) {
    if (resourceOveruseStats == nullptr) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                            "Must provide a non-null resource overuse stats "
                                            "parcelable");
    }
    if (resourceTypes.size() != 1 || resourceTypes[0] != ResourceType::IO) {
        return fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                            "Must provide exactly one I/O resource type");
    }
    IoOveruseStats ioOveruseStats;
    if (const auto result = mIoOveruseMonitor->getIoOveruseStats(&ioOveruseStats); !result.ok()) {
        return fromExceptionCodeWithMessage(result.error().code(),
                                            StringPrintf("Failed to get resource overuse stats: %s",
                                                         result.error().message().c_str()));
    }
    ResourceOveruseStats stats;
    stats.set<ResourceOveruseStats::ioOveruseStats>(std::move(ioOveruseStats));
    resourceOveruseStats->emplace_back(std::move(stats));
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogBinderMediator::registerMediator(
        const std::shared_ptr<ICarWatchdogClient>& /*mediator*/) {
    return fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                        "Deprecated method registerMediator");
}

ScopedAStatus WatchdogBinderMediator::unregisterMediator(
        const std::shared_ptr<ICarWatchdogClient>& /*mediator*/) {
    return fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                        "Deprecated method unregisterMediator");
}

ScopedAStatus WatchdogBinderMediator::registerMonitor(
        const std::shared_ptr<ICarWatchdogMonitor>& /*monitor*/) {
    return fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                        "Deprecated method registerMonitor");
}

ScopedAStatus WatchdogBinderMediator::unregisterMonitor(
        const std::shared_ptr<ICarWatchdogMonitor>& /*monitor*/) {
    return fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                        "Deprecated method unregisterMonitor");
}

ScopedAStatus WatchdogBinderMediator::tellMediatorAlive(
        const std::shared_ptr<ICarWatchdogClient>& /*mediator*/,
        const std::vector<int32_t>& /*clientsNotResponding*/, int32_t /*sessionId*/) {
    return fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                        "Deprecated method tellMediatorAlive");
}

ScopedAStatus WatchdogBinderMediator::tellDumpFinished(
        const std::shared_ptr<ICarWatchdogMonitor>& /*monitor*/, int32_t /*pid*/) {
    return fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                        "Deprecated method tellDumpFinished");
}

ScopedAStatus WatchdogBinderMediator::notifySystemStateChange(StateType /*type*/, int32_t /*arg1*/,
                                                              int32_t /*arg2*/) {
    return fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                        "Deprecated method notifySystemStateChange");
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
