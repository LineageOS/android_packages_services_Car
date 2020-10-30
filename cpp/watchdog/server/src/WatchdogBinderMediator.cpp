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

#include <android-base/file.h>
#include <android-base/parseint.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <binder/IServiceManager.h>
#include <log/log.h>

namespace android {
namespace automotive {
namespace watchdog {

using android::defaultServiceManager;
using android::sp;
using android::base::Error;
using android::base::Join;
using android::base::ParseUint;
using android::base::Result;
using android::base::StringPrintf;
using android::base::WriteStringToFd;
using android::binder::Status;

namespace {

constexpr const char* kHelpFlag = "--help";
constexpr const char* kHelpShortFlag = "-h";
constexpr const char* kHelpText =
        "CarWatchdog daemon dumpsys help page:\n"
        "Format: dumpsys android.automotive.watchdog.ICarWatchdog/default [options]\n\n"
        "%s or %s: Displays this help text.\n"
        "When no options are specified, carwatchdog report is generated.\n";
constexpr const char* kCarWatchdogServerInterface =
        "android.automotive.watchdog.ICarWatchdog/default";
constexpr const char* kCarWatchdogInternalServerInterface = "carwatchdogd_system";

Status fromExceptionCode(int32_t exceptionCode, std::string message) {
    ALOGW("%s", message.c_str());
    return Status::fromExceptionCode(exceptionCode, message.c_str());
}

}  // namespace

Result<void> WatchdogBinderMediator::init(const sp<WatchdogProcessService>& watchdogProcessService,
                                          const sp<WatchdogPerfService>& watchdogPerfService,
                                          const sp<IoOveruseMonitor>& ioOveruseMonitor) {
    if (watchdogProcessService == nullptr || watchdogPerfService == nullptr ||
        ioOveruseMonitor == nullptr) {
        return Error(INVALID_OPERATION) << "Must initialize process service, performance service, "
                                        << "I/O overuse monitoring service before starting "
                                        << "carwatchdog binder mediator";
    }
    if (mWatchdogProcessService != nullptr || mWatchdogPerfService != nullptr ||
        mIoOveruseMonitor != nullptr || mWatchdogInternalHandler != nullptr) {
        return Error(INVALID_OPERATION)
                << "Cannot initialize carwatchdog binder mediator more than once";
    }

    sp<WatchdogInternalHandler> watchdogInternalHandler =
            new WatchdogInternalHandler(this, watchdogProcessService, watchdogPerfService,
                                        mIoOveruseMonitor);

    auto result = registerServices(watchdogInternalHandler);
    if (!result.ok()) {
        return result;
    }
    mWatchdogProcessService = watchdogProcessService;
    mWatchdogPerfService = watchdogPerfService;
    mIoOveruseMonitor = ioOveruseMonitor;
    mWatchdogInternalHandler = watchdogInternalHandler;
    return {};
}

Result<void> WatchdogBinderMediator::registerServices(
        const sp<WatchdogInternalHandler>& watchdogInternalHandler) {
    status_t status =
            defaultServiceManager()->addService(String16(kCarWatchdogServerInterface), this);
    if (status != OK) {
        return Error(status) << "Failed to add CarWatchdog server interface to ServiceManager";
    }
    status = defaultServiceManager()->addService(String16(kCarWatchdogInternalServerInterface),
                                                 watchdogInternalHandler);
    if (status != OK) {
        return Error(status)
                << "Failed to add CarWatchdog internal server interface to ServiceManager";
    }
    return {};
}

status_t WatchdogBinderMediator::dump(int fd, const Vector<String16>& args) {
    int numArgs = args.size();
    if (numArgs == 1 && (args[0] == String16(kHelpFlag) || args[0] == String16(kHelpShortFlag))) {
        if (!dumpHelpText(fd, "")) {
            ALOGW("Failed to write help text to fd");
            return FAILED_TRANSACTION;
        }
        return OK;
    }
    if (numArgs >= 1 &&
        (args[0] == String16(kStartCustomCollectionFlag) ||
         args[0] == String16(kEndCustomCollectionFlag))) {
        auto ret = mWatchdogPerfService->onCustomCollection(fd, args);
        if (!ret.ok()) {
            std::string mode = args[0] == String16(kStartCustomCollectionFlag) ? "start" : "end";
            std::string errorMsg = StringPrintf("Failed to %s custom I/O perf collection: %s",
                                                mode.c_str(), ret.error().message().c_str());
            if (ret.error().code() == BAD_VALUE) {
                dumpHelpText(fd, errorMsg);
            } else {
                ALOGW("%s", errorMsg.c_str());
            }
            return ret.error().code();
        }
        return OK;
    }

    if (numArgs > 0) {
        ALOGW("Car watchdog cannot recognize the given option(%s). Dumping the current state...",
              Join(args, " ").c_str());
    }

    auto ret = mWatchdogProcessService->dump(fd, args);
    if (!ret.ok()) {
        ALOGW("Failed to dump carwatchdog process service: %s", ret.error().message().c_str());
        return ret.error().code();
    }
    ret = mWatchdogPerfService->onDump(fd);
    if (!ret.ok()) {
        ALOGW("Failed to dump I/O perf collection: %s", ret.error().message().c_str());
        return ret.error().code();
    }
    // TODO(b/167240592): Add a dump call to I/O overuse monitor and relevant tests.
    return OK;
}

bool WatchdogBinderMediator::dumpHelpText(int fd, std::string errorMsg) {
    if (!errorMsg.empty()) {
        ALOGW("Error: %s", errorMsg.c_str());
        if (!WriteStringToFd(StringPrintf("Error: %s\n\n", errorMsg.c_str()), fd)) {
            ALOGW("Failed to write error message to fd");
            return false;
        }
    }

    return WriteStringToFd(StringPrintf(kHelpText, kHelpFlag, kHelpShortFlag), fd) &&
            mWatchdogPerfService->dumpHelpText(fd);
}

Status WatchdogBinderMediator::registerMediator(
        const android::sp<ICarWatchdogClient>& /*mediator*/) {
    return fromExceptionCode(Status::EX_UNSUPPORTED_OPERATION,
                             "Deprecated method registerMediator");
}

Status WatchdogBinderMediator::unregisterMediator(
        const android::sp<ICarWatchdogClient>& /*mediator*/) {
    return fromExceptionCode(Status::EX_UNSUPPORTED_OPERATION,
                             "Deprecated method unregisterMediator");
}

Status WatchdogBinderMediator::registerMonitor(
        const android::sp<ICarWatchdogMonitor>& /*monitor*/) {
    return fromExceptionCode(Status::EX_UNSUPPORTED_OPERATION, "Deprecated method registerMonitor");
}

Status WatchdogBinderMediator::unregisterMonitor(
        const android::sp<ICarWatchdogMonitor>& /*monitor*/) {
    return fromExceptionCode(Status::EX_UNSUPPORTED_OPERATION,
                             "Deprecated method unregisterMonitor");
}

Status WatchdogBinderMediator::tellMediatorAlive(
        const android::sp<ICarWatchdogClient>& /*mediator*/,
        const std::vector<int32_t>& /*clientsNotResponding*/, int32_t /*sessionId*/) {
    return fromExceptionCode(Status::EX_UNSUPPORTED_OPERATION,
                             "Deprecated method tellMediatorAlive");
}

Status WatchdogBinderMediator::tellDumpFinished(const android::sp<ICarWatchdogMonitor>& /*monitor*/,
                                                int32_t /*pid*/) {
    return fromExceptionCode(Status::EX_UNSUPPORTED_OPERATION,
                             "Deprecated method tellDumpFinished");
}

Status WatchdogBinderMediator::notifySystemStateChange(StateType /*type*/, int32_t /*arg1*/,
                                                       int32_t /*arg2*/) {
    return fromExceptionCode(Status::EX_UNSUPPORTED_OPERATION,
                             "Deprecated method notifySystemStateChange");
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
