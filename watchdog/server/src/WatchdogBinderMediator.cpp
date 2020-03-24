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

#include <android-base/parseint.h>
#include <android-base/stringprintf.h>
#include <android/automotive/watchdog/BootPhase.h>
#include <android/automotive/watchdog/PowerCycle.h>
#include <android/automotive/watchdog/UserState.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <cutils/multiuser.h>
#include <log/log.h>
#include <private/android_filesystem_config.h>

namespace android {
namespace automotive {
namespace watchdog {

using android::defaultServiceManager;
using android::base::Error;
using android::base::ParseUint;
using android::base::Result;
using android::base::StringPrintf;
using android::binder::Status;

namespace {

Status checkSystemPermission() {
    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Calling process does not have proper privilege");
    }
    return Status::ok();
}

Status fromExceptionCode(int32_t exceptionCode, std::string message) {
    ALOGW("%s", message.c_str());
    return Status::fromExceptionCode(exceptionCode, message.c_str());
}

}  // namespace

Result<void> WatchdogBinderMediator::init(sp<WatchdogProcessService> watchdogProcessService,
                                          sp<IoPerfCollection> ioPerfCollection) {
    // TODO(b/148486340): Uncomment mIoPerfCollection after it is enabled in ServiceManager.
    if (watchdogProcessService == nullptr /*|| ioPerfCollection == nullptr*/) {
        return Error(INVALID_OPERATION)
                << "Must initialize both process and I/O perf collection service before starting "
                << "carwatchdog binder mediator";
    }
    if (mWatchdogProcessService != nullptr || mIoPerfCollection != nullptr) {
        return Error(INVALID_OPERATION)
                << "Cannot initialize carwatchdog binder mediator more than once";
    }
    mWatchdogProcessService = watchdogProcessService;
    mIoPerfCollection = ioPerfCollection;
    status_t status =
            defaultServiceManager()
                    ->addService(String16("android.automotive.watchdog.ICarWatchdog/default"),
                                 this);
    if (status != OK) {
        return Error(status) << "Failed to start carwatchdog binder mediator";
    }
    return {};
}

status_t WatchdogBinderMediator::dump(int fd, const Vector<String16>& args) {
    if (args.empty()) {
        auto ret = mWatchdogProcessService->dump(fd, args);
        if (!ret.ok()) {
            ALOGW("Failed to dump carwatchdog process service: %s", ret.error().message().c_str());
            return ret.error().code();
        }
        /*ret = mIoPerfCollection->dump(fd, args);
        if (!ret.ok()) {
            ALOGW("Failed to dump I/O perf collection: %s", ret.error().message().c_str());
            return ret.error().code();
        }*/
        return OK;
    }
    /*if (args[0] == String16(kStartCustomCollectionFlag) ||
        args[0] == String16(kEndCustomCollectionFlag)) {
        auto ret = mIoPerfCollection->dump(fd, args);
        std::string mode = args[0] == String16(kStartCustomCollectionFlag) ? "start" : "end";
        if (!ret.ok()) {
            ALOGW("Failed to %s custom I/O perf collection: %s", mode.c_str(),
                  ret.error().message().c_str());
            return ret.error().code();
        }
        return OK;
    }*/
    ALOGW("Invalid dump arguments");
    return INVALID_OPERATION;
}

Status WatchdogBinderMediator::registerMediator(const sp<ICarWatchdogClient>& mediator) {
    Status status = checkSystemPermission();
    if (!status.isOk()) {
        return status;
    }
    return mWatchdogProcessService->registerMediator(mediator);
}

Status WatchdogBinderMediator::unregisterMediator(const sp<ICarWatchdogClient>& mediator) {
    Status status = checkSystemPermission();
    if (!status.isOk()) {
        return status;
    }
    return mWatchdogProcessService->unregisterMediator(mediator);
}
Status WatchdogBinderMediator::registerMonitor(const sp<ICarWatchdogMonitor>& monitor) {
    Status status = checkSystemPermission();
    if (!status.isOk()) {
        return status;
    }
    return mWatchdogProcessService->registerMonitor(monitor);
}
Status WatchdogBinderMediator::unregisterMonitor(const sp<ICarWatchdogMonitor>& monitor) {
    Status status = checkSystemPermission();
    if (!status.isOk()) {
        return status;
    }
    return mWatchdogProcessService->unregisterMonitor(monitor);
}

Status WatchdogBinderMediator::notifySystemStateChange(StateType type,
                                                       const std::vector<std::string>& args) {
    Status status = checkSystemPermission();
    if (!status.isOk()) {
        return status;
    }
    switch (type) {
        case StateType::POWER_CYCLE: {
            if (args.size() != 1) {
                return fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         StringPrintf("Expected exactly one argument for %s "
                                                      "change, got %zu",
                                                      toString(StateType::POWER_CYCLE).c_str(),
                                                      args.size()));
            }
            uint32_t powerCycleArg = 0;
            if (!ParseUint(args[0], &powerCycleArg)) {
                return fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         StringPrintf("Failed to parse power cycle argument %s",
                                                      args[0].c_str()));
            }
            auto powerCycle = static_cast<PowerCycle>(powerCycleArg);
            if (powerCycle >= PowerCycle::NUM_POWER_CYLES) {
                return fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         StringPrintf("Invalid power cycle %d", powerCycle));
            }
            return mWatchdogProcessService->notifyPowerCycleChange(powerCycle);
        }
        case StateType::USER_STATE: {
            if (args.size() != 2) {
                return fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         StringPrintf("Expected exactly two arguments for %s "
                                                      "change, got %zu",
                                                      toString(StateType::USER_STATE).c_str(),
                                                      args.size()));
            }
            userid_t userId = 0;
            if (!ParseUint(args[0], &userId)) {
                return fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         StringPrintf("Failed to parse user ID argument %s",
                                                      args[0].c_str()));
            }
            uint32_t userStateArg = 0;
            if (!ParseUint(args[1], &userStateArg)) {
                return fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         StringPrintf("Failed to parse user state argument %s",
                                                      args[0].c_str()));
            }
            auto userState = static_cast<UserState>(userStateArg);
            if (userState >= UserState::NUM_USER_STATES) {
                return fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         StringPrintf("Invalid user state %d", userState));
            }
            return mWatchdogProcessService->notifyUserStateChange(userId, userState);
        }
        case StateType::BOOT_PHASE: {
            if (args.size() != 1) {
                return fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         StringPrintf("Expacted exactly one argument for %s "
                                                      "change, got %zu",
                                                      toString(StateType::BOOT_PHASE).c_str(),
                                                      args.size()));
            }
            uint32_t phase = 0;
            if (!ParseUint(args[0], &phase)) {
                return fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         StringPrintf("Failed to parse boot phase argument %s",
                                                      args[0].c_str()));
            }
            if (static_cast<BootPhase>(phase) >= BootPhase::BOOT_COMPLETED) {
                /*auto ret = mIoPerfCollection->onBootFinished();
                if (!ret.ok()) {
                    return fromExceptionCode(ret.error().code(), ret.error().message());
                }*/
            }
            return Status::ok();
        }
    }
    return fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                             StringPrintf("Invalid state change type %d", type));
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
