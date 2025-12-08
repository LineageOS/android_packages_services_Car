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

#pragma once

#include "IoOveruseMonitor.h"
#include "IoOveruseMonitorWrapper.h"
#include "ThreadPriorityController.h"
#include "WatchdogInternalHandlerBase.h"
#include "WatchdogPerfService.h"
#include "WatchdogProcessService.h"
#include "WatchdogServiceHelperBase.h"

#include <aidl/android/automotive/watchdog/internal/BnCarWatchdog.h>
#include <aidl/android/automotive/watchdog/internal/ComponentType.h>
#include <aidl/android/automotive/watchdog/internal/ICarWatchdogMonitor.h>
#include <aidl/android/automotive/watchdog/internal/ICarWatchdogServiceForSystem.h>
#include <aidl/android/automotive/watchdog/internal/PowerCycle.h>
#include <aidl/android/automotive/watchdog/internal/ProcessIdentifier.h>
#include <aidl/android/automotive/watchdog/internal/ResourceOveruseConfiguration.h>
#include <aidl/android/automotive/watchdog/internal/StateType.h>
#include <aidl/android/automotive/watchdog/internal/UserPackageIoUsageStats.h>
#include <aidl/android/automotive/watchdog/internal/UserState.h>
#include <android/binder_auto_utils.h>
#include <gtest/gtest_prod.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/String16.h>
#include <utils/Vector.h>

namespace android {
namespace automotive {
namespace watchdog {

// Forward declaration for testing use only.
namespace internal {

class WatchdogInternalHandlerPeer;

}  // namespace internal

class WatchdogInternalHandler final : public WatchdogInternalHandlerBase {
public:
    WatchdogInternalHandler(
            const android::sp<WatchdogServiceHelperBaseInterface>& watchdogServiceHelperBase,
            const android::sp<WatchdogProcessServiceInterface>& watchdogProcessService,
            const android::sp<WatchdogPerfServiceInterface>& watchdogPerfService,
            const android::sp<IoOveruseMonitorWrapperInterface>& ioOveruseMonitorWrapper) :
          WatchdogInternalHandlerBase(watchdogServiceHelperBase, watchdogPerfService,
                                      ioOveruseMonitorWrapper),
          mIoOveruseMonitorWrapper(ioOveruseMonitorWrapper),
          mWatchdogProcessService(watchdogProcessService),
          mWatchdogPerfService(watchdogPerfService),
          mThreadPriorityController(std::make_unique<ThreadPriorityController>()) {}
    ~WatchdogInternalHandler() { terminate(); }

    android::base::Result<void> init() override;
    binder_status_t dump(int fd, const char** args, uint32_t numArgs) override {
        return WatchdogInternalHandlerBase::dump(fd, args, numArgs);
    };
    ndk::ScopedAStatus registerCarWatchdogService(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service) override {
        return WatchdogInternalHandlerBase::registerCarWatchdogService(service);
    };
    ndk::ScopedAStatus unregisterCarWatchdogService(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service) override {
        return WatchdogInternalHandlerBase::unregisterCarWatchdogService(service);
    };
    ndk::ScopedAStatus registerMonitor(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor>& monitor)
            override;
    ndk::ScopedAStatus unregisterMonitor(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor>& monitor)
            override;
    ndk::ScopedAStatus tellCarWatchdogServiceAlive(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service,
            const std::vector<aidl::android::automotive::watchdog::internal::ProcessIdentifier>&
                    clientsNotResponding,
            int32_t sessionId) override;
    ndk::ScopedAStatus tellDumpFinished(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor>& monitor,
            const std::vector<aidl::android::automotive::watchdog::internal::ProcessIdentifier>&
                    processIdentifiers) override;
    ndk::ScopedAStatus notifySystemStateChange(
            aidl::android::automotive::watchdog::internal::StateType type, int32_t arg1,
            int32_t arg2) override;
    ndk::ScopedAStatus updateResourceOveruseConfigurations(
            const std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>&
                    configs) override {
        return WatchdogInternalHandlerBase::updateResourceOveruseConfigurations(configs);
    };
    ndk::ScopedAStatus getResourceOveruseConfigurations(
            std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>*
                    configs) override {
        return WatchdogInternalHandlerBase::getResourceOveruseConfigurations(configs);
    };
    ndk::ScopedAStatus controlProcessHealthCheck(bool enable) override;
    ndk::ScopedAStatus setThreadPriority(int pid, int tid, int uid, int policy,
                                         int priority) override;
    ndk::ScopedAStatus getThreadPriority(
            int pid, int tid, int uid,
            aidl::android::automotive::watchdog::internal::ThreadPolicyWithPriority*
                    threadPolicyWithPriority) override;
    ndk::ScopedAStatus onAidlVhalPidFetched(int pid) override;
    ndk::ScopedAStatus onTodayIoUsageStatsFetched(
            const std::vector<
                    aidl::android::automotive::watchdog::internal::UserPackageIoUsageStats>&
                    userPackageIoUsageStats) override {
        return WatchdogInternalHandlerBase::onTodayIoUsageStatsFetched(userPackageIoUsageStats);
    };

    void terminate() override {
        WatchdogInternalHandlerBase::terminate();
        mWatchdogProcessService.clear();
        mWatchdogPerfService.clear();
        mIoOveruseMonitorWrapper.clear();
    }

private:
    status_t dumpServices(int fd) override {
        mWatchdogProcessService->onDump(fd);
        return WatchdogInternalHandlerBase::dumpServices(fd);
    };
    status_t dumpProto(int fd) override;
    status_t dumpHelpText(const int fd, const std::string& errorMsg) override {
        return WatchdogInternalHandlerBase::dumpHelpText(fd, errorMsg);
    };
    void checkAndRegisterIoOveruseMonitor() override;
    ndk::ScopedAStatus handlePowerCycleChange(
            aidl::android::automotive::watchdog::internal::PowerCycle powerCycle);
    ndk::ScopedAStatus handleUserStateChange(
            userid_t userId,
            const aidl::android::automotive::watchdog::internal::UserState& userState) override;
    void setThreadPriorityController(std::unique_ptr<ThreadPriorityControllerInterface> controller);

    android::sp<IoOveruseMonitorWrapperInterface> mIoOveruseMonitorWrapper;
    android::sp<WatchdogProcessServiceInterface> mWatchdogProcessService;
    android::sp<WatchdogPerfServiceInterface> mWatchdogPerfService;
    std::unique_ptr<ThreadPriorityControllerInterface> mThreadPriorityController;

    // For unit tests.
    friend class internal::WatchdogInternalHandlerPeer;
    FRIEND_TEST(WatchdogInternalHandlerTest, TestInit);
    FRIEND_TEST(WatchdogInternalHandlerTest, TestTerminate);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
