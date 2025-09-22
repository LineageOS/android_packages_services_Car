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

#include "IoOveruseMonitorBase.h"
#include "WatchdogPerfServiceBase.h"
#include "WatchdogServiceHelperBase.h"

#include <aidl/android/automotive/watchdog/internal/BnCarWatchdog.h>
#include <aidl/android/automotive/watchdog/internal/ICarWatchdogMonitor.h>
#include <aidl/android/automotive/watchdog/internal/ICarWatchdogServiceForSystem.h>
#include <aidl/android/automotive/watchdog/internal/ProcessIdentifier.h>
#include <aidl/android/automotive/watchdog/internal/ResourceOveruseConfiguration.h>
#include <aidl/android/automotive/watchdog/internal/StateType.h>
#include <aidl/android/automotive/watchdog/internal/UserPackageIoUsageStats.h>
#include <aidl/android/automotive/watchdog/internal/UserState.h>
#include <android/binder_auto_utils.h>
#include <gtest/gtest_prod.h>
#include <utils/Errors.h>
#include <utils/Vector.h>

namespace android {
namespace automotive {
namespace watchdog {

constexpr const char* kNullCarWatchdogServiceError =
        "Must provide a non-null car watchdog service instance";
constexpr const char* kDumpAllFlag = "-a";
constexpr const char* kHelpFlag = "--help";
constexpr const char* kHelpShortFlag = "-h";
constexpr const char* kDumpProtoFlag = "--proto";
constexpr const char* kHelpTextBase =
        "Car watchdog daemon dumpsys help page:\n"
        "Format: dumpsys android.automotive.watchdog.ICarWatchdog/default [options]\n\n"
        "%s or %s: Displays this help text.\n";
constexpr const char* kNoOptionsHelpText =
        "When no options are specified, car watchdog report is generated as text.\n";

class WatchdogInternalHandlerInterface :
      public aidl::android::automotive::watchdog::internal::BnCarWatchdog {
public:
    virtual android::base::Result<void> init() = 0;
    virtual void terminate() = 0;
};

class WatchdogInternalHandlerBase : public WatchdogInternalHandlerInterface {
public:
    WatchdogInternalHandlerBase(
            const android::sp<WatchdogServiceHelperBaseInterface>& watchdogServiceHelperBase,
            const android::sp<WatchdogPerfServiceBaseInterface>& watchdogPerfServiceBase,
            const android::sp<IoOveruseMonitorBaseInterface>& ioOveruseMonitorBase) :
          mWatchdogServiceHelperBase(watchdogServiceHelperBase),
          mIoOveruseMonitorBase(ioOveruseMonitorBase),
          mWatchdogPerfServiceBase(watchdogPerfServiceBase) {}
    ~WatchdogInternalHandlerBase() { terminate(); }

    android::base::Result<void> init() override;
    binder_status_t dump(int fd, const char** args, uint32_t numArgs) override;
    ndk::ScopedAStatus registerCarWatchdogService(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service) override;
    ndk::ScopedAStatus unregisterCarWatchdogService(
            const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service) override;
    ndk::ScopedAStatus registerMonitor(
            [[maybe_unused]] const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor>& monitor)
            override {
        return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                                                "Unused in base implementation.");
    }
    ndk::ScopedAStatus unregisterMonitor(
            [[maybe_unused]] const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor>& monitor)
            override {
        return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                                                "Unused in base implementation.");
    }
    ndk::ScopedAStatus tellCarWatchdogServiceAlive(
            [[maybe_unused]] const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service,
            [[maybe_unused]] const std::vector<
                    aidl::android::automotive::watchdog::internal::ProcessIdentifier>&
                    clientsNotResponding,
            [[maybe_unused]] int32_t sessionId) override {
        return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                                                "Unused in base implementation.");
    }
    ndk::ScopedAStatus tellDumpFinished(
            [[maybe_unused]] const std::shared_ptr<
                    aidl::android::automotive::watchdog::internal::ICarWatchdogMonitor>& monitor,
            [[maybe_unused]] const std::vector<
                    aidl::android::automotive::watchdog::internal::ProcessIdentifier>&
                    processIdentifiers) override {
        return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                                                "Unused in base implementation.");
    }
    ndk::ScopedAStatus notifySystemStateChange(
            aidl::android::automotive::watchdog::internal::StateType type, int32_t arg1,
            int32_t arg2) override;
    ndk::ScopedAStatus updateResourceOveruseConfigurations(
            const std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>&
                    configs) override;
    ndk::ScopedAStatus getResourceOveruseConfigurations(
            std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>*
                    configs) override;
    ndk::ScopedAStatus controlProcessHealthCheck([[maybe_unused]] bool enable) override {
        return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                                                "Unused in base implementation.");
    }
    ndk::ScopedAStatus setThreadPriority([[maybe_unused]] int pid, [[maybe_unused]] int tid,
                                         [[maybe_unused]] int uid, [[maybe_unused]] int policy,
                                         [[maybe_unused]] int priority) override {
        return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                                                "Unused in base implementation.");
    }
    ndk::ScopedAStatus getThreadPriority(
            [[maybe_unused]] int pid, [[maybe_unused]] int tid, [[maybe_unused]] int uid,
            [[maybe_unused]] aidl::android::automotive::watchdog::internal::
                    ThreadPolicyWithPriority* threadPolicyWithPriority) override {
        return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                                                "Unused in base implementation.");
    }
    ndk::ScopedAStatus onAidlVhalPidFetched([[maybe_unused]] int pid) override {
        return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_UNSUPPORTED_OPERATION,
                                                                "Unused in base implementation.");
    }
    ndk::ScopedAStatus onTodayIoUsageStatsFetched(
            const std::vector<
                    aidl::android::automotive::watchdog::internal::UserPackageIoUsageStats>&
                    userPackageIoUsageStats) override;

    void terminate() override {
        mWatchdogServiceHelperBase.clear();
        mWatchdogPerfServiceBase.clear();
        mIoOveruseMonitorBase.clear();
    }

protected:
    virtual status_t dumpServices(int fd);
    // TODO(b/433290487): Implement onDumpProto to dump resource overuse configurations,
    // the latest I/O usage stats, and the list of registered I/O overuse listeners.
    virtual status_t dumpProto([[maybe_unused]] int fd) { return BAD_VALUE; }
    virtual status_t dumpHelpText(const int fd, const std::string& errorMsg);
    virtual void checkAndRegisterIoOveruseMonitor();
    virtual ndk::ScopedAStatus handleUserStateChange(
            userid_t userId,
            const aidl::android::automotive::watchdog::internal::UserState& userState);

    android::sp<WatchdogServiceHelperBaseInterface> mWatchdogServiceHelperBase;
    android::sp<IoOveruseMonitorBaseInterface> mIoOveruseMonitorBase;

private:
    android::sp<WatchdogPerfServiceBaseInterface> mWatchdogPerfServiceBase;

    // For unit tests.
    FRIEND_TEST(WatchdogInternalHandlerBaseTest, TestInit);
    FRIEND_TEST(WatchdogInternalHandlerBaseTest, TestTerminate);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
