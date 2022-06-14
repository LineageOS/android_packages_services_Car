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

#include "WatchdogInternalHandler.h"

#include "UidProcStatsCollector.h"

#include <aidl/android/automotive/watchdog/internal/BootPhase.h>
#include <aidl/android/automotive/watchdog/internal/GarageMode.h>
#include <binder/IPCThreadState.h>
#include <private/android_filesystem_config.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::internal::BootPhase;
using ::aidl::android::automotive::watchdog::internal::ComponentType;
using ::aidl::android::automotive::watchdog::internal::GarageMode;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor;
using ::aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem;
using ::aidl::android::automotive::watchdog::internal::PowerCycle;
using ::aidl::android::automotive::watchdog::internal::ProcessIdentifier;
using ::aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration;
using ::aidl::android::automotive::watchdog::internal::StateType;
using ::aidl::android::automotive::watchdog::internal::ThreadPolicyWithPriority;
using ::aidl::android::automotive::watchdog::internal::UserState;
using ::android::sp;
using ::android::String16;
using ::android::base::Result;
using ::ndk::ScopedAStatus;

namespace {

constexpr const char* kNullCarWatchdogServiceError =
        "Must provide a non-null car watchdog service instance";
constexpr const char* kNullCarWatchdogMonitorError =
        "Must provide a non-null car watchdog monitor instance";

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

binder_status_t WatchdogInternalHandler::dump([[maybe_unused]] int fd,
                                              [[maybe_unused]] const char** args,
                                              [[maybe_unused]] uint32_t numArgs) {
    // TODO(b/203809044): Move the dump processing from WatchdogBinderMediator to here, so
    //  the cyclic dependency between the WatchdogBinderMediator and WatchdogInternalHandler
    //  can be removed.
    return OK;
}

void WatchdogInternalHandler::checkAndRegisterIoOveruseMonitor() {
    if (mIoOveruseMonitor->isInitialized()) {
        return;
    }
    if (const auto result = mWatchdogPerfService->registerDataProcessor(mIoOveruseMonitor);
        !result.ok()) {
        ALOGE("Failed to register I/O overuse monitor to watchdog performance service: %s",
              result.error().message().c_str());
    }
    return;
}

ScopedAStatus WatchdogInternalHandler::registerCarWatchdogService(
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
    return mWatchdogServiceHelper->registerService(service);
}

ScopedAStatus WatchdogInternalHandler::unregisterCarWatchdogService(
        const std::shared_ptr<ICarWatchdogServiceForSystem>& service) {
    if (auto status = checkSystemUser(/*methodName=*/"unregisterCarWatchdogService");
        !status.isOk()) {
        return status;
    }
    if (service == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogServiceError);
    }
    return mWatchdogServiceHelper->unregisterService(service);
}

ScopedAStatus WatchdogInternalHandler::registerMonitor(
        const std::shared_ptr<ICarWatchdogMonitor>& monitor) {
    if (auto status = checkSystemUser(/*methodName=*/"registerMonitor"); !status.isOk()) {
        return status;
    }
    if (monitor == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogMonitorError);
    }
    return mWatchdogProcessService->registerMonitor(monitor);
}

ScopedAStatus WatchdogInternalHandler::unregisterMonitor(
        const std::shared_ptr<ICarWatchdogMonitor>& monitor) {
    if (auto status = checkSystemUser(/*methodName=*/"unregisterMonitor"); !status.isOk()) {
        return status;
    }
    if (monitor == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogMonitorError);
    }
    return mWatchdogProcessService->unregisterMonitor(monitor);
}

ScopedAStatus WatchdogInternalHandler::tellCarWatchdogServiceAlive(
        const std::shared_ptr<ICarWatchdogServiceForSystem>& service,
        const std::vector<ProcessIdentifier>& clientsNotResponding, int32_t sessionId) {
    if (auto status = checkSystemUser(/*methodName=*/"tellCarWatchdogServiceAlive");
        !status.isOk()) {
        return status;
    }
    if (service == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogServiceError);
    }
    return mWatchdogProcessService->tellCarWatchdogServiceAlive(service, clientsNotResponding,
                                                                sessionId);
}

ScopedAStatus WatchdogInternalHandler::tellDumpFinished(
        const std::shared_ptr<ICarWatchdogMonitor>& monitor,
        const ProcessIdentifier& processIdentifier) {
    if (auto status = checkSystemUser(/*methodName=*/"tellDumpFinished"); !status.isOk()) {
        return status;
    }
    if (monitor == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogMonitorError);
    }
    return mWatchdogProcessService->tellDumpFinished(monitor, processIdentifier);
}

ScopedAStatus WatchdogInternalHandler::notifySystemStateChange(StateType type, int32_t arg1,
                                                               int32_t arg2) {
    if (auto status = checkSystemUser(/*methodName=*/"notifySystemStateChange"); !status.isOk()) {
        return status;
    }
    switch (type) {
        case StateType::POWER_CYCLE: {
            PowerCycle powerCycle = static_cast<PowerCycle>(static_cast<uint32_t>(arg1));
            if (powerCycle >= PowerCycle::NUM_POWER_CYLES) {
                return toScopedAStatus(EX_ILLEGAL_ARGUMENT,
                                       StringPrintf("Invalid power cycle %d", powerCycle));
            }
            return handlePowerCycleChange(powerCycle);
        }
        case StateType::GARAGE_MODE: {
            GarageMode garageMode = static_cast<GarageMode>(static_cast<uint32_t>(arg1));
            mWatchdogPerfService->setSystemState(garageMode == GarageMode::GARAGE_MODE_OFF
                                                         ? SystemState::NORMAL_MODE
                                                         : SystemState::GARAGE_MODE);
            return ScopedAStatus::ok();
        }
        case StateType::USER_STATE: {
            userid_t userId = static_cast<userid_t>(arg1);
            UserState userState = static_cast<UserState>(static_cast<uint32_t>(arg2));
            if (userState >= UserState::NUM_USER_STATES) {
                return toScopedAStatus(EX_ILLEGAL_ARGUMENT,
                                       StringPrintf("Invalid user state %d", userState));
            }
            return handleUserStateChange(userId, userState);
        }
        case StateType::BOOT_PHASE: {
            BootPhase phase = static_cast<BootPhase>(static_cast<uint32_t>(arg1));
            if (phase >= BootPhase::BOOT_COMPLETED) {
                if (const auto result = mWatchdogPerfService->onBootFinished(); !result.ok()) {
                    return toScopedAStatus(result);
                }
            }
            return ScopedAStatus::ok();
        }
    }
    return toScopedAStatus(EX_ILLEGAL_ARGUMENT, StringPrintf("Invalid state change type %d", type));
}

ScopedAStatus WatchdogInternalHandler::handlePowerCycleChange(PowerCycle powerCycle) {
    switch (powerCycle) {
        case PowerCycle::POWER_CYCLE_SHUTDOWN_PREPARE:
            ALOGI("Received SHUTDOWN_PREPARE power cycle");
            mWatchdogProcessService->setEnabled(/*isEnabled=*/false);
            break;
        case PowerCycle::POWER_CYCLE_SHUTDOWN_ENTER:
            ALOGI("Received SHUTDOWN_ENTER power cycle");
            mWatchdogProcessService->setEnabled(/*isEnabled=*/false);
            break;
        case PowerCycle::POWER_CYCLE_RESUME:
            ALOGI("Received RESUME power cycle");
            mWatchdogProcessService->setEnabled(/*isEnabled=*/true);
            break;
        default:
            return toScopedAStatus(EX_ILLEGAL_ARGUMENT,
                                   StringPrintf("Unsupported power cycle: %d", powerCycle));
    }
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogInternalHandler::handleUserStateChange(userid_t userId, UserState userState) {
    std::string stateDesc;
    switch (userState) {
        case UserState::USER_STATE_STARTED:
            stateDesc = "started";
            mWatchdogProcessService->notifyUserStateChange(userId, /*isStarted=*/true);
            break;
        case UserState::USER_STATE_STOPPED:
            stateDesc = "stopped";
            mWatchdogProcessService->notifyUserStateChange(userId, /*isStarted=*/false);
            break;
        case UserState::USER_STATE_REMOVED:
            stateDesc = "removed";
            mIoOveruseMonitor->removeStatsForUser(userId);
            break;
        default:
            return toScopedAStatus(EX_ILLEGAL_ARGUMENT,
                                   StringPrintf("Unsupported user state: %d", userState));
    }
    ALOGI("Received user state change: user(%" PRId32 ") is %s", userId, stateDesc.c_str());
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogInternalHandler::updateResourceOveruseConfigurations(
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

ScopedAStatus WatchdogInternalHandler::getResourceOveruseConfigurations(
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

ScopedAStatus WatchdogInternalHandler::controlProcessHealthCheck(bool enable) {
    if (auto status = checkSystemUser(/*methodName=*/"controlProcessHealthCheck"); !status.isOk()) {
        return status;
    }
    mWatchdogProcessService->setEnabled(enable);
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogInternalHandler::setThreadPriority(int pid, int tid, int uid, int policy,
                                                         int priority) {
    if (auto status = checkSystemUser(/*methodName=*/"setThreadPriority"); !status.isOk()) {
        return status;
    }
    if (auto result = mThreadPriorityController->setThreadPriority(pid, tid, uid, policy, priority);
        !result.ok()) {
        return toScopedAStatus(result);
    }
    return ScopedAStatus::ok();
}

ScopedAStatus WatchdogInternalHandler::getThreadPriority(
        int pid, int tid, int uid, ThreadPolicyWithPriority* threadPolicyWithPriority) {
    if (auto status = checkSystemUser(/*methodName=*/"getThreadPriority"); !status.isOk()) {
        return status;
    }
    if (auto result = mThreadPriorityController->getThreadPriority(pid, tid, uid,
                                                                   threadPolicyWithPriority);
        !result.ok()) {
        return toScopedAStatus(result);
    }
    return ScopedAStatus::ok();
}

void WatchdogInternalHandler::setThreadPriorityController(
        std::unique_ptr<ThreadPriorityController> threadPriorityController) {
    mThreadPriorityController = std::move(threadPriorityController);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
