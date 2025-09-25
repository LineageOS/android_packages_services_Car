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

#include "WatchdogInternalHandlerBase.h"

#include <aidl/android/automotive/watchdog/internal/GarageMode.h>
#include <android-base/file.h>
#include <binder/IPCThreadState.h>
#include <private/android_filesystem_config.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::internal::GarageMode;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem;
using ::aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration;
using ::aidl::android::automotive::watchdog::internal::StateType;
using ::aidl::android::automotive::watchdog::internal::UserPackageIoUsageStats;
using ::aidl::android::automotive::watchdog::internal::UserState;
using ::android::sp;
using ::android::base::EqualsIgnoreCase;
using ::android::base::Error;
using ::android::base::Join;
using ::android::base::Result;
using ::android::base::Split;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::android::base::WriteStringToFd;
using ::ndk::ScopedAStatus;

namespace {

ScopedAStatus toScopedAStatus(int32_t exceptionCode, const std::string& message) {
    ALOGW("%s", message.c_str());
    return ScopedAStatus::fromExceptionCodeWithMessage(exceptionCode, message.c_str());
}

ScopedAStatus toScopedAStatus(const Result<void>& result) {
    return toScopedAStatus(result.error().code(), result.error().message());
}

ScopedAStatus checkSystemUser(const std::string& methodName) {
    if (IPCThreadState::self()->getCallingUid() != AID_SYSTEM) {
        return toScopedAStatus(EX_SECURITY,
                               StringPrintf("Calling process does not have proper "
                                            "privilege to call %s",
                                            methodName.c_str()));
    }
    return ScopedAStatus::ok();
}

}  // namespace

Result<void> WatchdogInternalHandlerBase::init() {
    if (mWatchdogPerfServiceBase == nullptr || mIoOveruseMonitor == nullptr ||
        mWatchdogServiceHelperBase == nullptr) {
        std::string serviceList;
        if (mWatchdogPerfServiceBase == nullptr) {
            StringAppendF(&serviceList, "%s%s", (!serviceList.empty() ? ", " : ""),
                          "Watchdog performance service");
        }
        if (mIoOveruseMonitor == nullptr) {
            StringAppendF(&serviceList, "%s%s", (!serviceList.empty() ? ", " : ""),
                          "I/O overuse monitor service");
        }
        if (mWatchdogServiceHelperBase == nullptr) {
            StringAppendF(&serviceList, "%s%s", (!serviceList.empty() ? ", " : ""),
                          "Watchdog service helper");
        }
        return Error(INVALID_OPERATION)
                << serviceList << " must be initialized with non-null instance";
    }
    return {};
}

binder_status_t WatchdogInternalHandlerBase::dump(int fd, const char** args, uint32_t numArgs) {
    if (numArgs == 0 || strcmp(args[0], kDumpAllFlag) == 0) {
        return dumpServices(fd);
    }
    if (numArgs == 1 &&
        (EqualsIgnoreCase(args[0], kHelpFlag) || EqualsIgnoreCase(args[0], kHelpShortFlag))) {
        return dumpHelpText(fd, "");
    }
    if (EqualsIgnoreCase(args[0], kStartCustomCollectionFlag) ||
        EqualsIgnoreCase(args[0], kEndCustomCollectionFlag)) {
        if (auto result = mWatchdogPerfServiceBase->onCustomCollection(fd, args, numArgs);
            !result.ok()) {
            std::string mode =
                    EqualsIgnoreCase(args[0], kStartCustomCollectionFlag) ? "start" : "stop";
            std::string errorMsg = StringPrintf("Failed to %s custom perf collection: %s",
                                                mode.c_str(), result.error().message().c_str());
            if (result.error().code() == BAD_VALUE) {
                dumpHelpText(fd, errorMsg);
            } else {
                ALOGW("%s", errorMsg.c_str());
                WriteStringToFd(StringPrintf("Error: %s\n", errorMsg.c_str()), fd);
            }
            return result.error().code();
        }
        std::string mode =
                EqualsIgnoreCase(args[0], kStartCustomCollectionFlag) ? "started" : "stopped";
        // The message returned on success is used in the integration tests. If this message is
        // updated, the CarWatchdog's integration tests must be updated too.
        WriteStringToFd(StringPrintf("Successfully %s custom perf collection\n", mode.c_str()), fd);
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
        if (EqualsIgnoreCase(args[i], kDumpProtoFlag)) {
            // Base implementation doesn't support dumping in proto format, so it will return
            // failure. But the derived implementation supports dumping proto format, so it will
            // return success. On failure (i.e., only on base implementation), show the help text.
            if (auto result = dumpProto(fd); result == OK) {
                return result;
            }
        }
        argsVector.push_back(args[i]);
    }
    dumpHelpText(fd,
                 StringPrintf("Invalid car watchdog dumpsys options: [%s]\n",
                              Join(argsVector, " ").c_str()));
    return dumpServices(fd);
}

status_t WatchdogInternalHandlerBase::dumpServices(int fd) {
    if (auto result = mWatchdogPerfServiceBase->onDump(fd); !result.ok()) {
        ALOGW("Failed to dump car watchdog perf service: %s", result.error().message().c_str());
        return result.error().code();
    }
    if (auto result = mIoOveruseMonitor->onDump(fd); !result.ok()) {
        ALOGW("Failed to dump I/O overuse monitor: %s", result.error().message().c_str());
        return result.error().code();
    }
    return OK;
}

status_t WatchdogInternalHandlerBase::dumpHelpText(const int fd, const std::string& errorMsg) {
    if (!errorMsg.empty()) {
        ALOGW("Error: %s", errorMsg.c_str());
        if (!WriteStringToFd(StringPrintf("Error: %s\n\n", errorMsg.c_str()), fd)) {
            ALOGW("Failed to write error message to fd");
            return FAILED_TRANSACTION;
        }
    }
    if (!WriteStringToFd(StringPrintf(kHelpTextBase, kHelpFlag, kHelpShortFlag), fd) ||
        !WriteStringToFd(StringPrintf("%s", kNoOptionsHelpText), fd) ||
        !mWatchdogPerfServiceBase->dumpHelpText(fd) || !mIoOveruseMonitor->dumpHelpText(fd)) {
        ALOGW("Failed to write help text to fd");
        return FAILED_TRANSACTION;
    }
    return OK;
}

void WatchdogInternalHandlerBase::checkAndRegisterIoOveruseMonitor() {
    if (mIoOveruseMonitor->isInitialized()) {
        return;
    }
    if (const auto result = mWatchdogPerfServiceBase->registerIoOveruseMonitor(mIoOveruseMonitor);
        !result.ok()) {
        ALOGE("Failed to register I/O overuse monitor to watchdog performance service: %s",
              result.error().message().c_str());
    }
    return;
}

ScopedAStatus WatchdogInternalHandlerBase::registerCarWatchdogService(
        const std::shared_ptr<ICarWatchdogServiceForSystem>& service) {
    if (auto status = checkSystemUser(/*methodName=*/"registerCarWatchdogService");
        !status.isOk()) {
        return status;
    }
    if (service == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogServiceError);
    }
    /*
     * I/O overuse monitor reads from system, vendor, and data partitions during initialization.
     * When CarService is running these partitions are available to read, thus register the I/O
     * overuse monitor on processing the request to register CarService.
     */
    checkAndRegisterIoOveruseMonitor();
    auto status = mWatchdogServiceHelperBase->registerService(service);
    if (status.isOk()) {
        mWatchdogPerfServiceBase->onCarWatchdogServiceRegistered();
    }
    return status;
}

ScopedAStatus WatchdogInternalHandlerBase::unregisterCarWatchdogService(
        const std::shared_ptr<ICarWatchdogServiceForSystem>& service) {
    if (auto status = checkSystemUser(/*methodName=*/"unregisterCarWatchdogService");
        !status.isOk()) {
        return status;
    }
    if (service == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogServiceError);
    }
    return mWatchdogServiceHelperBase->unregisterService(service);
}

ScopedAStatus WatchdogInternalHandlerBase::notifySystemStateChange(StateType type, int32_t arg1,
                                                                   int32_t arg2) {
    if (auto status = checkSystemUser(/*methodName=*/"notifySystemStateChange"); !status.isOk()) {
        return status;
    }
    switch (type) {
        case StateType::GARAGE_MODE: {
            GarageMode garageMode = static_cast<GarageMode>(static_cast<uint32_t>(arg1));
            mWatchdogPerfServiceBase->setSystemState(garageMode == GarageMode::GARAGE_MODE_OFF
                                                             ? SystemState::NORMAL_MODE
                                                             : SystemState::GARAGE_MODE);
            return ScopedAStatus::ok();
        }
        case StateType::USER_STATE: {
            userid_t userId = static_cast<userid_t>(arg1);
            UserState userState = static_cast<UserState>(static_cast<uint32_t>(arg2));
            return handleUserStateChange(userId, userState);
        }
        default: {
            return toScopedAStatus(EX_ILLEGAL_ARGUMENT,
                                   StringPrintf("Invalid state change type %d", type));
        }
    }
    return toScopedAStatus(EX_ILLEGAL_ARGUMENT, StringPrintf("Invalid state change type %d", type));
}

ScopedAStatus WatchdogInternalHandlerBase::handleUserStateChange(userid_t userId,
                                                                 const UserState& userState) {
    std::string stateDesc;
    switch (userState) {
        case UserState::USER_STATE_REMOVED:
            stateDesc = "removed";
            mIoOveruseMonitor->removeStatsForUser(userId);
            break;
        default:
            // UserState::USER_STATE_UNLOCKED is not sent by CarService to the daemon. If signal is
            // received, an exception will be thrown.
            return toScopedAStatus(EX_ILLEGAL_ARGUMENT,
                                   StringPrintf("Unsupported user state: %d", userState));
    }
    ALOGI("Received user state change: user(%" PRId32 ") is %s", userId, stateDesc.c_str());
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogInternalHandlerBase::updateResourceOveruseConfigurations(
        const std::vector<ResourceOveruseConfiguration>& configs) {
    if (auto status = checkSystemUser(/*methodName=*/"updateResourceOveruseConfigurations");
        !status.isOk()) {
        return status;
    }
    // Maybe retry registring I/O overuse monitor if failed to initialize previously.
    checkAndRegisterIoOveruseMonitor();
    if (auto result = mIoOveruseMonitor->updateResourceOveruseConfigurations(configs);
        !result.ok()) {
        return toScopedAStatus(result);
    }
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogInternalHandlerBase::getResourceOveruseConfigurations(
        std::vector<ResourceOveruseConfiguration>* configs) {
    if (auto status = checkSystemUser(/*methodName=*/"getResourceOveruseConfigurations");
        !status.isOk()) {
        return status;
    }
    // Maybe retry registring I/O overuse monitor if failed to initialize previously.
    checkAndRegisterIoOveruseMonitor();
    if (auto result = mIoOveruseMonitor->getResourceOveruseConfigurations(configs); !result.ok()) {
        return toScopedAStatus(result);
    }
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogInternalHandlerBase::onTodayIoUsageStatsFetched(
        const std::vector<UserPackageIoUsageStats>& userPackageIoUsageStats) {
    if (auto status = checkSystemUser(/*methodName=*/"onTodayIoUsageStatsFetched");
        !status.isOk()) {
        return status;
    }
    if (auto result = mIoOveruseMonitor->onTodayIoUsageStatsFetched(userPackageIoUsageStats);
        !result.ok()) {
        return toScopedAStatus(result);
    }
    return ScopedAStatus::ok();
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
