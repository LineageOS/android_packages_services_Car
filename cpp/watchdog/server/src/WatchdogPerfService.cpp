/*
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

#include "WatchdogPerfService.h"

#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android/util/ProtoOutputStream.h>
#include <log/log.h>
#include <processgroup/sched_policy.h>

#include <pthread.h>

#include <packages/services/Car/service/proto/android/car/watchdog/carwatchdog_daemon_dump.proto.h>
#include <packages/services/Car/service/proto/android/car/watchdog/health_check_client_info.proto.h>
#include <packages/services/Car/service/proto/android/car/watchdog/performance_stats.proto.h>

namespace android {
namespace automotive {
namespace watchdog {

namespace {

using ::aidl::android::automotive::watchdog::internal::ResourceStats;
using ::aidl::android::automotive::watchdog::internal::UserState;
using ::android::sp;
using ::android::base::Error;
using ::android::base::Join;
using ::android::base::Result;
using ::android::base::Split;
using ::android::base::StringPrintf;
using ::android::base::WriteStringToFd;
using ::android::util::ProtoOutputStream;

// Minimum required collection polling interval between subsequent collections.
const std::chrono::seconds kDefaultSystemEventCollectionInterval = 1s;
const std::chrono::seconds kDefaultPeriodicCollectionInterval = 20s;
const std::chrono::seconds kDefaultPeriodicMonitorInterval = 5s;

constexpr const char* kServiceName = "WatchdogPerfService";
constexpr const char* kCustomCollectionFilterFlagText =
        "\t%s <package name>,<package name>,...: Comma-separated value containing package names. "
        "When provided, the results are filtered only to the provided package names. Default "
        "behavior is to list the results for the top N packages.\n";
constexpr const char* kCustomCollectionStopText =
        "%s: Stops custom performance data collection and generates a dump of the collection "
        "report.\n\n"
        "When no options are specified, the car watchdog report contains the performance data "
        "collected during boot-time and over the last few minutes before the report generation.\n";

constexpr const char* toString(std::variant<EventType, SwitchMessage> what) {
    return std::visit(
            [&](const auto& v) -> const char* {
                switch (static_cast<int>(v)) {
                    case EventType::INIT:
                        return "INIT";
                    case EventType::TERMINATED:
                        return "TERMINATED";
                    case EventType::BOOT_TIME_COLLECTION:
                        return "BOOT_TIME_COLLECTION";
                    case EventType::PERIODIC_COLLECTION:
                        return "PERIODIC_COLLECTION";
                    case EventType::USER_SWITCH_COLLECTION:
                        return "USER_SWITCH_COLLECTION";
                    case EventType::WAKE_UP_COLLECTION:
                        return "WAKE_UP_COLLECTION";
                    case EventType::CUSTOM_COLLECTION:
                        return "CUSTOM_COLLECTION";
                    case EventType::PERIODIC_MONITOR:
                        return "PERIODIC_MONITOR";
                    case EventType::LAST_EVENT:
                        return "LAST_EVENT";
                    case SwitchMessage::END_BOOTTIME_COLLECTION:
                        return "END_BOOTTIME_COLLECTION";
                    case SwitchMessage::END_USER_SWITCH_COLLECTION:
                        return "END_USER_SWITCH_COLLECTION";
                    case SwitchMessage::END_WAKE_UP_COLLECTION:
                        return "END_WAKE_UP_COLLECTION";
                    case SwitchMessage::END_CUSTOM_COLLECTION:
                        return "END_CUSTOM_COLLECTION";
                    default:
                        return "INVALID_EVENT_OR_SWITCH_MESSAGE";
                }
            },
            what);
}

constexpr int toProtoEventType(EventType eventType) {
    switch (eventType) {
        case EventType::INIT:
            return PerformanceProfilerDump::INIT;
        case EventType::TERMINATED:
            return PerformanceProfilerDump::TERMINATED;
        case EventType::BOOT_TIME_COLLECTION:
            return PerformanceProfilerDump::BOOT_TIME_COLLECTION;
        case EventType::PERIODIC_COLLECTION:
            return PerformanceProfilerDump::PERIODIC_COLLECTION;
        case EventType::USER_SWITCH_COLLECTION:
            return PerformanceProfilerDump::USER_SWITCH_COLLECTION;
        case EventType::WAKE_UP_COLLECTION:
            return PerformanceProfilerDump::WAKE_UP_COLLECTION;
        case EventType::CUSTOM_COLLECTION:
            return PerformanceProfilerDump::CUSTOM_COLLECTION;
        default:
            return PerformanceProfilerDump::EVENT_TYPE_UNSPECIFIED;
    }
}

bool isEmpty(const ResourceStats& resourceStats) {
    return !resourceStats.resourceUsageStats.has_value() &&
            !resourceStats.resourceOveruseStats.has_value();
}

}  // namespace

Result<void> WatchdogPerfService::registerDataProcessor(sp<DataProcessorInterface> processor) {
    if (processor == nullptr) {
        return Error() << "Must provide a valid data processor";
    }
    if (const auto result = processor->init(); !result.ok()) {
        return Error() << "Failed to initialize " << processor->name().c_str() << ": "
                       << result.error().message();
    }
    Mutex::Autolock lock(mMutex);
    mDataProcessors.push_back(processor);
    if (DEBUG) {
        ALOGD("Successfully registered %s to %s", processor->name().c_str(), kServiceName);
    }
    return {};
}

// TODO(b/435747622): Add an init method
Result<void> WatchdogPerfService::start() {
    {
        Mutex::Autolock lock(mMutex);
        if (mCurrCollectionEvent != EventType::INIT || mCollectionThread.joinable()) {
            return Error(INVALID_OPERATION) << "Cannot start " << kServiceName << " more than once";
        }
        // All methods called by WatchdogPerfService are present in
        // WatchdogServiceHelperBase, so its derived class is not needed.
        if (mWatchdogServiceHelperBase == nullptr) {
            return Error(INVALID_OPERATION) << "No watchdog service helper is registered";
        }
        std::chrono::nanoseconds systemEventCollectionInterval =
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::seconds(sysprop::systemEventCollectionInterval().value_or(
                                kDefaultSystemEventCollectionInterval.count())));
        std::chrono::nanoseconds periodicCollectionInterval =
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::seconds(sysprop::periodicCollectionInterval().value_or(
                                kDefaultPeriodicCollectionInterval.count())));
        std::chrono::nanoseconds periodicMonitorInterval =
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::seconds(sysprop::periodicMonitorInterval().value_or(
                                kDefaultPeriodicMonitorInterval.count())));
        mBoottimeCollection = {
                .eventType = EventType::BOOT_TIME_COLLECTION,
                .pollingIntervalNs = systemEventCollectionInterval,
        };
        mPeriodicCollection = {
                .eventType = EventType::PERIODIC_COLLECTION,
                .pollingIntervalNs = periodicCollectionInterval,
        };
        mUserSwitchCollection = {{
                .eventType = EventType::USER_SWITCH_COLLECTION,
                .pollingIntervalNs = systemEventCollectionInterval,
        }};
        mWakeUpCollection = {
                .eventType = EventType::WAKE_UP_COLLECTION,
                .pollingIntervalNs = systemEventCollectionInterval,
        };
        mPeriodicMonitor = {
                .eventType = EventType::PERIODIC_MONITOR,
                .pollingIntervalNs = periodicMonitorInterval,
        };
        if (mDataProcessors.empty()) {
            ALOGE("Terminating %s: No data processor is registered", kServiceName);
            mCurrCollectionEvent = EventType::TERMINATED;
            return Error() << "No data processor is registered";
        }
        mUidStatsCollector->init();
        mProcStatCollector->init();
        mProcDiskStatsCollector->init();
    }

    mCollectionThread = std::thread([&]() {
        {
            Mutex::Autolock lock(mMutex);
            if (EventType expected = EventType::INIT; mCurrCollectionEvent != expected) {
                ALOGE("Skipping performance data collection as the current collection event "
                      "%s != %s",
                      toString(mCurrCollectionEvent), toString(expected));
                return;
            }
            notifySystemStartUpLocked();
            mCurrCollectionEvent = EventType::BOOT_TIME_COLLECTION;
            mBoottimeCollection.lastPollElapsedRealTimeNs = mHandlerLooper->now();
            mHandlerLooper->setLooper(Looper::prepare(/*opts=*/0));
            mHandlerLooper->sendMessage(sp<WatchdogPerfService>::fromExisting(this),
                                        EventType::BOOT_TIME_COLLECTION);
        }
        if (set_sched_policy(0, SP_BACKGROUND) != 0) {
            ALOGW("Failed to set background scheduling priority to %s thread", kServiceName);
        }
        if (int result = pthread_setname_np(pthread_self(), "WatchdogPerfSvc"); result != 0) {
            ALOGE("Failed to set %s thread name: %d", kServiceName, result);
        }
        ALOGI("Starting %s performance data collection", toString(mCurrCollectionEvent));
        bool isCollectionActive = true;
        /*
         * Loop until the collection is not active -- performance collection runs on this thread in
         * a handler.
         */
        while (isCollectionActive) {
            mHandlerLooper->pollAll(/*timeoutMillis=*/-1);
            Mutex::Autolock lock(mMutex);
            isCollectionActive = mCurrCollectionEvent != EventType::TERMINATED;
        }
    });
    return {};
}

void WatchdogPerfService::onDataProcessorTerminateLocked() {
    for (const auto& processor : mDataProcessors) {
        processor->terminate();
    }
}

void WatchdogPerfService::onDataProcessorCarWatchdogServiceRegisteredLocked() {
    for (const auto& processor : mDataProcessors) {
        processor->onCarWatchdogServiceRegistered();
    }
}

Result<void> WatchdogPerfService::onBootFinished() {
    Mutex::Autolock lock(mMutex);

    if (mBootCompletedTimeEpochSeconds <= 0) {
        mBootCompletedTimeEpochSeconds =
                std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
    }

    if (EventType expected = EventType::BOOT_TIME_COLLECTION; mCurrCollectionEvent != expected) {
        /*
         * This case happens when either the WatchdogPerfService has prematurely terminated before
         * boot complete notification is received or multiple boot complete notifications are
         * received. In either case don't return error as this will lead to runtime exception and
         * cause system to boot loop.
         */
        ALOGE("Current performance data collection event %s != %s", toString(mCurrCollectionEvent),
              toString(expected));
        return {};
    }

    mHandlerLooper->sendMessageAtTime(mHandlerLooper->now() + mPostSystemEventDurationNs.count(),
                                      sp<WatchdogPerfService>::fromExisting(this),
                                      SwitchMessage::END_BOOTTIME_COLLECTION);
    if (DEBUG) {
        ALOGD("Boot complete signal received.");
    }
    return {};
}

Result<void> WatchdogPerfService::onUserStateChange(userid_t userId, const UserState& userState) {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent == EventType::BOOT_TIME_COLLECTION ||
        mCurrCollectionEvent == EventType::CUSTOM_COLLECTION) {
        mUserSwitchCollection.from = mUserSwitchCollection.to;
        mUserSwitchCollection.to = userId;
        ALOGI("Current collection: %s. Ignoring user switch from userId = %d to userId = %d)",
              toString(mCurrCollectionEvent), mUserSwitchCollection.from, mUserSwitchCollection.to);
        // Ignoring the user switch events because the boot-time and custom collections take
        // precedence over other collections.
        if (mCurrCollectionEvent == EventType::CUSTOM_COLLECTION) {
            ALOGW("Unable to start %s. Current performance data collection event: %s",
                  toString(EventType::USER_SWITCH_COLLECTION), toString(mCurrCollectionEvent));
        }
        return {};
    }
    switch (static_cast<int>(userState)) {
        case static_cast<int>(UserState::USER_STATE_SWITCHING):
            // TODO(b/243984863): Handle multi-user switching scenario.
            mUserSwitchCollection.from = mUserSwitchCollection.to;
            mUserSwitchCollection.to = userId;
            if (mCurrCollectionEvent != EventType::PERIODIC_COLLECTION &&
                mCurrCollectionEvent != EventType::USER_SWITCH_COLLECTION) {
                ALOGE("Unable to start %s. Current performance data collection event: %s",
                      toString(EventType::USER_SWITCH_COLLECTION), toString(mCurrCollectionEvent));
                return {};
            }
            startUserSwitchCollection();
            ALOGI("Switching to %s (userIds: from = %d, to = %d)", toString(mCurrCollectionEvent),
                  mUserSwitchCollection.from, mUserSwitchCollection.to);
            break;
        case static_cast<int>(UserState::USER_STATE_UNLOCKING):
            if (mCurrCollectionEvent != EventType::PERIODIC_COLLECTION) {
                if (mCurrCollectionEvent != EventType::USER_SWITCH_COLLECTION) {
                    ALOGE("Unable to start %s. Current performance data collection event: %s",
                          toString(EventType::USER_SWITCH_COLLECTION),
                          toString(mCurrCollectionEvent));
                }
                return {};
            }
            if (mUserSwitchCollection.to != userId) {
                return {};
            }
            startUserSwitchCollection();
            ALOGI("Switching to %s (userId: %d)", toString(mCurrCollectionEvent), userId);
            break;
        case static_cast<int>(UserState::USER_STATE_POST_UNLOCKED): {
            if (mCurrCollectionEvent != EventType::USER_SWITCH_COLLECTION) {
                ALOGE("Ignoring USER_STATE_POST_UNLOCKED because no user switch collection in "
                      "progress. Current performance data collection event: %s.",
                      toString(mCurrCollectionEvent));
                return {};
            }
            if (mUserSwitchCollection.to != userId) {
                ALOGE("Ignoring USER_STATE_POST_UNLOCKED signal for user id: %d. "
                      "Current user being switched to: %d",
                      userId, mUserSwitchCollection.to);
                return {};
            }
            auto thiz = sp<WatchdogPerfService>::fromExisting(this);
            mHandlerLooper->removeMessages(thiz, SwitchMessage::END_USER_SWITCH_COLLECTION);
            nsecs_t endUserSwitchCollectionTime =
                    mHandlerLooper->now() + mPostSystemEventDurationNs.count();
            mHandlerLooper->sendMessageAtTime(endUserSwitchCollectionTime, thiz,
                                              SwitchMessage::END_USER_SWITCH_COLLECTION);
            break;
        }
        default:
            ALOGE("Unsupported user state: %d", static_cast<int>(userState));
            return {};
    }
    if (DEBUG) {
        ALOGD("Handled user state change: userId = %d, userState = %d", userId,
              static_cast<int>(userState));
    }
    return {};
}

Result<void> WatchdogPerfService::startUserSwitchCollection() {
    auto thiz = sp<WatchdogPerfService>::fromExisting(this);
    mHandlerLooper->removeMessages(thiz);
    mUserSwitchCollection.lastPollElapsedRealTimeNs = mHandlerLooper->now();
    // End |EventType::USER_SWITCH_COLLECTION| after a timeout because the user switch end
    // signal won't be received within a few seconds when the switch is blocked due to a
    // keyguard event. Otherwise, polling beyond a few seconds will lead to unnecessary data
    // collection.
    mHandlerLooper->sendMessageAtTime(mHandlerLooper->now() + mUserSwitchTimeoutNs.count(), thiz,
                                      SwitchMessage::END_USER_SWITCH_COLLECTION);
    mCurrCollectionEvent = EventType::USER_SWITCH_COLLECTION;
    mHandlerLooper->sendMessage(thiz, EventType::USER_SWITCH_COLLECTION);
    return {};
}

Result<void> WatchdogPerfService::onSuspendExit() {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent == EventType::CUSTOM_COLLECTION) {
        // Ignoring the suspend exit event because the custom collection takes
        // precedence over other collections.
        ALOGE("Unable to start %s. Current performance data collection event: %s",
              toString(EventType::WAKE_UP_COLLECTION), toString(mCurrCollectionEvent));
        return {};
    }
    if (mCurrCollectionEvent == EventType::WAKE_UP_COLLECTION) {
        ALOGE("The current performance data collection event is already %s",
              toString(EventType::WAKE_UP_COLLECTION));
        return {};
    }
    notifySystemStartUpLocked();
    auto thiz = sp<WatchdogPerfService>::fromExisting(this);
    mHandlerLooper->removeMessages(thiz);
    nsecs_t now = mHandlerLooper->now();
    mWakeUpCollection.lastPollElapsedRealTimeNs = now;
    mHandlerLooper->sendMessageAtTime(now + mWakeUpDurationNs.count(), thiz,
                                      SwitchMessage::END_WAKE_UP_COLLECTION);
    mCurrCollectionEvent = EventType::WAKE_UP_COLLECTION;
    mHandlerLooper->sendMessage(thiz, EventType::WAKE_UP_COLLECTION);
    ALOGI("Switching to %s", toString(mCurrCollectionEvent));
    return {};
}

Result<void> WatchdogPerfService::onShutdownEnter() {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent == EventType::CUSTOM_COLLECTION) {
        ALOGI("Unable to switch to %s during shutdown enter. Current performance data collection "
              "event: %s",
              toString(EventType::PERIODIC_COLLECTION), toString(mCurrCollectionEvent));
        return {};
    }
    switchToPeriodicLocked(/*startNow=*/true);
    return {};
}

Result<void> WatchdogPerfService::onDump(int fd) const {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent == EventType::TERMINATED) {
        ALOGW("%s not active. Dumping cached data", kServiceName);
        if (!WriteStringToFd(StringPrintf("%s not active. Dumping cached data.", kServiceName),
                             fd)) {
            return Error(FAILED_TRANSACTION) << "Failed to write " << kServiceName << " status";
        }
    }

    if (const auto& result = dumpCollectorsStatusLocked(fd); !result.ok()) {
        return Error(FAILED_TRANSACTION) << result.error();
    }

    std::stringstream kernelStartTimestamp;
    if (mKernelStartTimeEpochSeconds != 0) {
        kernelStartTimestamp << std::put_time(std::localtime(&mKernelStartTimeEpochSeconds),
                                              "%c %Z");
    } else {
        kernelStartTimestamp << "Missing";
    }

    std::stringstream bootCompletedTimestamp;
    if (mBootCompletedTimeEpochSeconds != 0) {
        bootCompletedTimestamp << std::put_time(std::localtime(&mBootCompletedTimeEpochSeconds),
                                                "%c %Z");
    } else {
        bootCompletedTimestamp << "Missing";
    }
    if (!WriteStringToFd(StringPrintf("\n%s%s report:\n%sSystem information:\n%s\n"
                                      "Kernel start time: <%s>\n"
                                      "Boot completed time: <%s>\n",
                                      kDumpMajorDelimiter.c_str(), kServiceName,
                                      kDumpMajorDelimiter.c_str(), std::string(33, '=').c_str(),
                                      kernelStartTimestamp.str().c_str(),
                                      bootCompletedTimestamp.str().c_str()),
                         fd) ||
        !WriteStringToFd(StringPrintf("\nBoot-time collection "
                                      "information:\n%s\n",
                                      std::string(33, '=').c_str()),
                         fd) ||
        !WriteStringToFd(mBoottimeCollection.toString(), fd) ||
        !WriteStringToFd(StringPrintf("\nWake-up collection information:\n%s\n",
                                      std::string(31, '=').c_str()),
                         fd) ||
        !WriteStringToFd(mWakeUpCollection.toString(), fd) ||
        !WriteStringToFd(StringPrintf("\nUser-switch collection information:\n%s\n",
                                      std::string(35, '=').c_str()),
                         fd) ||
        !WriteStringToFd(mUserSwitchCollection.toString(), fd) ||
        !WriteStringToFd(StringPrintf("\nPeriodic collection information:\n%s\n",
                                      std::string(32, '=').c_str()),
                         fd) ||
        !WriteStringToFd(mPeriodicCollection.toString(), fd)) {
        return Error(FAILED_TRANSACTION)
                << "Failed to dump the boot-time and periodic collection reports.";
    }

    for (const auto& processor : mDataProcessors) {
        if (const auto result = processor->onDump(fd); !result.ok()) {
            return result;
        }
    }

    WriteStringToFd(kDumpMajorDelimiter, fd);
    return {};
}

Result<void> WatchdogPerfService::onDumpProto(ProtoOutputStream& outProto) const {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent == EventType::TERMINATED) {
        ALOGW("%s not active. Dumping cached data", kServiceName);
    }

    uint64_t performanceProfilerDumpToken =
            outProto.start(CarWatchdogDaemonDump::PERFORMANCE_PROFILER_DUMP);

    outProto.write(PerformanceProfilerDump::CURRENT_EVENT, toProtoEventType(mCurrCollectionEvent));
    outProto.write(PerformanceProfilerDump::BOOT_COMPLETED_TIME_EPOCH_SECONDS,
                   mBootCompletedTimeEpochSeconds);
    outProto.write(PerformanceProfilerDump::KERNEL_START_TIME_EPOCH_SECONDS,
                   mKernelStartTimeEpochSeconds);

    DataProcessorInterface::CollectionIntervals collectionIntervals =
            {.mBoottimeIntervalMillis = std::chrono::duration_cast<std::chrono::milliseconds>(
                     mBoottimeCollection.pollingIntervalNs),
             .mPeriodicIntervalMillis = std::chrono::duration_cast<std::chrono::milliseconds>(
                     mPeriodicCollection.pollingIntervalNs),
             .mUserSwitchIntervalMillis = std::chrono::duration_cast<std::chrono::milliseconds>(
                     mUserSwitchCollection.pollingIntervalNs),
             .mWakeUpIntervalMillis = std::chrono::duration_cast<std::chrono::milliseconds>(
                     mWakeUpCollection.pollingIntervalNs),
             .mCustomIntervalMillis = std::chrono::duration_cast<std::chrono::milliseconds>(
                     mCustomCollection.pollingIntervalNs)};

    // Populate Performance Stats
    for (const auto& processor : mDataProcessors) {
        processor->onDumpProto(collectionIntervals, outProto);
    }

    outProto.end(performanceProfilerDumpToken);

    return {};
}

bool WatchdogPerfService::dumpHelpText(int fd) const {
    return WriteStringToFd(StringPrintf(kDumpHelpTextBase, kServiceName, kStartCustomCollectionFlag,
                                        kIntervalFlag,
                                        std::chrono::duration_cast<std::chrono::seconds>(
                                                kCustomCollectionInterval)
                                                .count(),
                                        kMaxDurationFlag,
                                        std::chrono::duration_cast<std::chrono::minutes>(
                                                kCustomCollectionDuration)
                                                .count(),
                                        StringPrintf(kCustomCollectionFilterFlagText,
                                                     kFilterPackagesFlag)
                                                .c_str(),
                                        StringPrintf(kCustomCollectionStopText,
                                                     kEndCustomCollectionFlag)
                                                .c_str()),
                           fd);
}

Result<void> WatchdogPerfService::dumpCollectorsStatusLocked(int fd) const {
    if (!mUidStatsCollector->enabled() &&
        !WriteStringToFd(StringPrintf("UidStatsCollector failed to access proc and I/O files"),
                         fd)) {
        return Error() << "Failed to write UidStatsCollector status";
    }
    if (!mProcStatCollector->enabled() &&
        !WriteStringToFd(StringPrintf("ProcStat collector failed to access the file %s",
                                      mProcStatCollector->filePath().c_str()),
                         fd)) {
        return Error() << "Failed to write ProcStat collector status";
    }
    return {};
}

Result<void> WatchdogPerfService::onDataProcessorCustomCollectionDumpLocked(int fd) {
    for (const auto& processor : mDataProcessors) {
        if (const auto result = processor->onCustomCollectionDump(fd); !result.ok()) {
            return Error(FAILED_TRANSACTION)
                    << processor->name() << " failed on " << toString(mCurrCollectionEvent)
                    << " collection: " << result.error();
        }
    }

    return {};
}

Result<std::unordered_set<std::string>> WatchdogPerfService::onFilterPackagesFlag(
        const char** args, uint32_t valuePos, uint32_t numArgs) {
    if (numArgs <= valuePos) {
        return Error(BAD_VALUE) << "Must provide value for '" << kFilterPackagesFlag << "' flag";
    }
    std::unordered_set<std::string> filterPackages;
    std::vector<std::string> packages = Split(std::string(args[valuePos]), ",");
    std::copy(packages.begin(), packages.end(),
              std::inserter(filterPackages, filterPackages.end()));
    return filterPackages;
}

void WatchdogPerfService::handleMessage(const Message& message) {
    Result<void> result;

    switch (message.what) {
        case static_cast<int>(EventType::BOOT_TIME_COLLECTION):
            result = processCollectionEvent(&mBoottimeCollection);
            break;
        case static_cast<int>(SwitchMessage::END_BOOTTIME_COLLECTION):
            mHandlerLooper->removeMessages(sp<WatchdogPerfService>::fromExisting(this));
            if (result = processCollectionEvent(&mBoottimeCollection); result.ok()) {
                Mutex::Autolock lock(mMutex);
                switchToPeriodicLocked(/*startNow=*/false);
            }
            break;
        case static_cast<int>(EventType::PERIODIC_COLLECTION):
            result = processCollectionEvent(&mPeriodicCollection);
            break;
        case static_cast<int>(EventType::USER_SWITCH_COLLECTION):
            result = processCollectionEvent(&mUserSwitchCollection);
            break;
        case static_cast<int>(EventType::WAKE_UP_COLLECTION):
            result = processCollectionEvent(&mWakeUpCollection);
            break;
        case static_cast<int>(SwitchMessage::END_USER_SWITCH_COLLECTION):
        case static_cast<int>(SwitchMessage::END_WAKE_UP_COLLECTION): {
            mHandlerLooper->removeMessages(sp<WatchdogPerfService>::fromExisting(this));
            EventMetadata* eventMetadata =
                    message.what == static_cast<int>(SwitchMessage::END_USER_SWITCH_COLLECTION)
                    ? &mUserSwitchCollection
                    : &mWakeUpCollection;
            if (result = processCollectionEvent(eventMetadata); result.ok()) {
                Mutex::Autolock lock(mMutex);
                switchToPeriodicLocked(/*startNow=*/false);
            }
            break;
        }
        case static_cast<int>(EventType::CUSTOM_COLLECTION):
            result = processCollectionEvent(&mCustomCollection);
            break;
        case static_cast<int>(EventType::PERIODIC_MONITOR):
            result = processMonitorEvent(&mPeriodicMonitor);
            break;
        case static_cast<int>(SwitchMessage::END_CUSTOM_COLLECTION): {
            Mutex::Autolock lock(mMutex);
            if (EventType expected = EventType::CUSTOM_COLLECTION;
                mCurrCollectionEvent != expected) {
                ALOGW("Skipping END_CUSTOM_COLLECTION message as the current collection %s != %s",
                      toString(mCurrCollectionEvent), toString(expected));
                return;
            }
            mCustomCollection = {};
            for (const auto& processor : mDataProcessors) {
                /*
                 * Clear custom collection cache on the data processors when the custom collection
                 * ends.
                 */
                processor->onCustomCollectionDump(-1);
            }
            switchToPeriodicLocked(/*startNow=*/true);
            return;
        }
        case static_cast<int>(TaskMessage::SEND_RESOURCE_STATS):
            result = sendResourceStats();
            break;
        default:
            result = Error() << "Unknown message: " << message.what;
    }

    if (!result.ok()) {
        Mutex::Autolock lock(mMutex);
        ALOGE("Terminating %s: %s", kServiceName, result.error().message().c_str());
        /*
         * DO NOT CALL terminate() as it tries to join the collection thread but this code is
         * executed on the collection thread. Thus it will result in a deadlock.
         */
        mCurrCollectionEvent = EventType::TERMINATED;
        mHandlerLooper->removeMessages(sp<WatchdogPerfService>::fromExisting(this));
        mHandlerLooper->wake();
    }
}

Result<void> WatchdogPerfService::collectLocked(EventMetadata* metadata) {
    if (!mUidStatsCollector->enabled() && !mProcStatCollector->enabled()) {
        return Error() << "No collectors enabled";
    }

    auto now = std::chrono::time_point_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now());
    int64_t timeSinceBootMillis = kGetElapsedTimeSinceBootMillisFunc();

    if (mUidStatsCollector->enabled()) {
        if (const auto result = mUidStatsCollector->collect(); !result.ok()) {
            return Error() << "Failed to collect per-uid proc and I/O stats: " << result.error();
        }
    }

    if (mProcStatCollector->enabled()) {
        if (const auto result = mProcStatCollector->collect(); !result.ok()) {
            return Error() << "Failed to collect proc stats: " << result.error();
        }

        if (mKernelStartTimeEpochSeconds <= 0) {
            mKernelStartTimeEpochSeconds = mProcStatCollector->getKernelStartTimeEpochSeconds();
        }
    }

    ResourceStats resourceStats = {};

    for (const auto& processor : mDataProcessors) {
        Result<void> result;
        switch (mCurrCollectionEvent) {
            case EventType::BOOT_TIME_COLLECTION:
                result = processor->onBoottimeCollection(now, mUidStatsCollector,
                                                         mProcStatCollector, &resourceStats);
                break;
            case EventType::PERIODIC_COLLECTION:
                result = processor->onPeriodicCollection(now, mSystemState, mUidStatsCollector,
                                                         mProcStatCollector, &resourceStats);
                break;
            case EventType::USER_SWITCH_COLLECTION: {
                WatchdogPerfService::UserSwitchEventMetadata* userSwitchMetadata =
                        static_cast<WatchdogPerfService::UserSwitchEventMetadata*>(metadata);
                result = processor->onUserSwitchCollection(now, userSwitchMetadata->from,
                                                           userSwitchMetadata->to,
                                                           mUidStatsCollector, mProcStatCollector);
                break;
            }
            case EventType::WAKE_UP_COLLECTION:
                result = processor->onWakeUpCollection(now, mUidStatsCollector, mProcStatCollector);
                break;
            case EventType::CUSTOM_COLLECTION:
                result = processor->onCustomCollection(now, mSystemState, metadata->filterPackages,
                                                       mUidStatsCollector, mProcStatCollector,
                                                       &resourceStats);
                break;
            default:
                result = Error() << "Invalid collection event " << toString(mCurrCollectionEvent);
        }
        if (!result.ok()) {
            return Error() << processor->name() << " failed on " << toString(mCurrCollectionEvent)
                           << " collection: " << result.error();
        }
    }

    if (!isEmpty(resourceStats)) {
        if (resourceStats.resourceUsageStats.has_value()) {
            resourceStats.resourceUsageStats->durationInMillis =
                    timeSinceBootMillis - mLastCollectionTimeMillis;
        }
        cacheUnsentResourceStatsLocked(std::move(resourceStats));
    }

    mLastCollectionTimeMillis = timeSinceBootMillis;

    if (mUnsentResourceStats.empty() || !mWatchdogServiceHelperBase->isServiceConnected()) {
        if (DEBUG && !mUnsentResourceStats.empty() &&
            !mWatchdogServiceHelperBase->isServiceConnected()) {
            ALOGD("Cannot send resource stats since CarWatchdogService not connected.");
        }
        return {};
    }

    // Send message to send resource stats
    mHandlerLooper->sendMessage(sp<WatchdogPerfService>::fromExisting(this),
                                TaskMessage::SEND_RESOURCE_STATS);

    return {};
}

Result<void> WatchdogPerfService::onDataProcessorPeriodicMonitorLocked(
        time_t now, const std::function<void()>& requestCollection, const char* eventTypeString) {
    for (const auto& processor : mDataProcessors) {
        if (const auto result =
                    processor->onPeriodicMonitor(now, mProcDiskStatsCollector, requestCollection);
            !result.ok()) {
            return Error() << processor->name() << " failed on " << eventTypeString << ": "
                           << result.error();
        }
    }
    return {};
}

Result<void> WatchdogPerfService::notifySystemStartUpLocked() {
    for (const auto& processor : mDataProcessors) {
        if (const auto result = processor->onSystemStartup(); !result.ok()) {
            ALOGE("%s failed to process system startup event", processor->name().c_str());
            return Error() << processor->name() << " failed to process system startup event";
        }
    }
    return {};
}

WatchdogPerfServiceBase::EventMetadata* WatchdogPerfService::getCurrentCollectionMetadataLocked() {
    switch (mCurrCollectionEvent) {
        case EventType::BOOT_TIME_COLLECTION:
            return &mBoottimeCollection;
        case EventType::PERIODIC_COLLECTION:
            return &mPeriodicCollection;
        case EventType::USER_SWITCH_COLLECTION:
            return &mUserSwitchCollection;
        case EventType::WAKE_UP_COLLECTION:
            return &mWakeUpCollection;
        case EventType::CUSTOM_COLLECTION:
            return &mCustomCollection;
        default:
            return nullptr;
    }
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
