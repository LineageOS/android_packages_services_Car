/*
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
#define DEBUG false  // STOPSHIP if true.

#include "WatchdogPerfServiceBase.h"

#include <android-base/file.h>
#include <android-base/parseint.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <log/log.h>
#include <processgroup/sched_policy.h>

#include <pthread.h>

#include <iterator>
#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

namespace {

using ::aidl::android::automotive::watchdog::internal::ResourceStats;
using ::android::sp;
using ::android::base::EqualsIgnoreCase;
using ::android::base::Error;
using ::android::base::Join;
using ::android::base::ParseUint;
using ::android::base::Result;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::android::base::WriteStringToFd;

const int32_t kMaxCachedUnsentResourceStats = 10;
// Minimum required collection polling interval between subsequent collections.
const std::chrono::nanoseconds kMinEventInterval = 1s;
const std::chrono::seconds kDefaultPeriodicCollectionInterval = 20s;
const std::chrono::seconds kDefaultPeriodicMonitorInterval = 5s;

constexpr const char* kServiceName = "WatchdogPerfServiceBase";
constexpr const char* kCustomCollectionStopText =
        "%s: Stops custom performance data collection and generates a dump of the collection "
        "report.\n\n"
        "When no options are specified, the car watchdog report contains the performance data "
        "collected over the last few minutes before the report generation.\n";

Result<std::chrono::seconds> parseSecondsFlag(const char** args, uint32_t numArgs, size_t pos) {
    if (numArgs <= pos) {
        return Error() << "Value not provided";
    }
    uint64_t value;
    if (std::string strValue = std::string(args[pos]); !ParseUint(strValue, &value)) {
        return Error() << "Invalid value " << strValue << ", must be an integer";
    }
    return std::chrono::seconds(value);
}

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

constexpr const char* toString(SystemState systemState) {
    switch (systemState) {
        case SystemState::NORMAL_MODE:
            return "NORMAL_MODE";
        case SystemState::GARAGE_MODE:
            return "GARAGE_MODE";
        default:
            return "UNKNOWN MODE";
    }
}

bool isEmpty(const ResourceStats& resourceStats) {
    return !resourceStats.resourceOveruseStats.has_value();
}

}  // namespace

std::string WatchdogPerfServiceBase::EventMetadata::toString() const {
    std::string buffer;
    const auto intervalInSecs =
            std::chrono::duration_cast<std::chrono::seconds>(pollingIntervalNs).count();
    StringAppendF(&buffer, "Event polling interval: %lld second%s\n", intervalInSecs,
                  ((intervalInSecs > 1) ? "s" : ""));
    if (!filterPackages.empty()) {
        std::vector<std::string> packages(filterPackages.begin(), filterPackages.end());
        StringAppendF(&buffer, "Filtered results to packages: %s\n", Join(packages, ", ").c_str());
    }
    return buffer;
}

Result<void> WatchdogPerfServiceBase::registerIoOveruseMonitorBase(
        sp<IoOveruseMonitorBaseInterface> ioOveruseMonitorBase) {
    if (ioOveruseMonitorBase == nullptr) {
        return Error() << "Must provide a non-null IoOveruseMonitorBase";
    }
    if (const auto result = ioOveruseMonitorBase->init(); !result.ok()) {
        return Error() << "Failed to initialize IoOveruseMonitorBase";
    }
    Mutex::Autolock lock(mMutex);
    mIoOveruseMonitorBase = ioOveruseMonitorBase;
    if (DEBUG) {
        ALOGD("Successfully registered IoOveruseMonitorBase to %s", kServiceName);
    }
    return {};
}

void WatchdogPerfServiceBase::init() {
    Mutex::Autolock lock(mMutex);
    std::chrono::nanoseconds periodicCollectionInterval =
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::seconds(sysprop::periodicCollectionInterval().value_or(
                            kDefaultPeriodicCollectionInterval.count())));
    std::chrono::nanoseconds periodicMonitorInterval =
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::seconds(sysprop::periodicMonitorInterval().value_or(
                            kDefaultPeriodicMonitorInterval.count())));
    mPeriodicCollection = {
            .eventType = EventType::PERIODIC_COLLECTION,
            .pollingIntervalNs = periodicCollectionInterval,
    };
    mPeriodicMonitor = {
            .eventType = EventType::PERIODIC_MONITOR,
            .pollingIntervalNs = periodicMonitorInterval,
    };
    mProcDiskStatsCollector->init();
    initInternalLocked();
}

void WatchdogPerfServiceBase::initInternalLocked() {
    mUidStatsCollectorBase->init();
}

Result<void> WatchdogPerfServiceBase::start() {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != EventType::INIT || mCollectionThread.joinable()) {
        return Error(INVALID_OPERATION) << "Cannot start " << kServiceName << " more than once";
    }
    if (mWatchdogServiceHelperBase == nullptr) {
        return Error(INVALID_OPERATION) << "No watchdog service helper is registered";
    }
    if (!isDataProcessorRegisteredLocked()) {
        ALOGE("Terminating %s: No data processor is registered", kServiceName);
        mCurrCollectionEvent = EventType::TERMINATED;
        return Error() << "No data processor is registered";
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
            startFirstCollectionEventLocked();
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

bool WatchdogPerfServiceBase::isDataProcessorRegisteredLocked() {
    return mIoOveruseMonitorBase != nullptr;
}

void WatchdogPerfServiceBase::startFirstCollectionEventLocked() {
    mHandlerLooper->setLooper(Looper::prepare(/*opts=*/0));
    switchToPeriodicLocked(/*startNow=*/true);
}

void WatchdogPerfServiceBase::terminate() {
    {
        Mutex::Autolock lock(mMutex);
        if (mCurrCollectionEvent == EventType::TERMINATED) {
            ALOGE("%s was terminated already", kServiceName);
            return;
        }
        ALOGE("Terminating %s as car watchdog is terminating", kServiceName);
        if (mCurrCollectionEvent != EventType::INIT) {
            /*
             * Looper runs only after EventType::INIT has completed so remove looper
             * messages and wake the looper only when the current collection has changed from
             * INIT.
             */
            mHandlerLooper->removeMessages(sp<WatchdogPerfServiceBase>::fromExisting(this));
            mHandlerLooper->wake();
        }
        onDataProcessorTerminateLocked();
        mCurrCollectionEvent = EventType::TERMINATED;
        mUnsentResourceStats.clear();
    }
    if (mCollectionThread.joinable()) {
        mCollectionThread.join();
        if (DEBUG) {
            ALOGD("%s collection thread terminated", kServiceName);
        }
    }
}

void WatchdogPerfServiceBase::onDataProcessorTerminateLocked() {
    mIoOveruseMonitorBase->terminate();
}

void WatchdogPerfServiceBase::setSystemState(SystemState systemState) {
    Mutex::Autolock lock(mMutex);
    if (mSystemState != systemState) {
        ALOGI("%s switching from %s to %s", kServiceName, toString(mSystemState),
              toString(systemState));
    }
    mSystemState = systemState;
}

void WatchdogPerfServiceBase::onCarWatchdogServiceRegistered() {
    Mutex::Autolock lock(mMutex);
    onDataProcessorCarWatchdogServiceRegisteredLocked();
    if (mUnsentResourceStats.empty()) {
        return;
    }
    mHandlerLooper->sendMessage(sp<WatchdogPerfServiceBase>::fromExisting(this),
                                TaskMessage::SEND_RESOURCE_STATS);
}

void WatchdogPerfServiceBase::onDataProcessorCarWatchdogServiceRegisteredLocked() {
    mIoOveruseMonitorBase->onCarWatchdogServiceRegistered();
}

Result<void> WatchdogPerfServiceBase::onCustomCollection(int fd, const char** args,
                                                         uint32_t numArgs) {
    if (numArgs == 0) {
        return Error(BAD_VALUE) << "No custom collection dump arguments";
    }

    if (EqualsIgnoreCase(args[0], kStartCustomCollectionFlag)) {
        if (numArgs > 7) {
            return Error(BAD_VALUE) << "Number of arguments to start custom performance data "
                                    << "collection cannot exceed 7";
        }
        std::chrono::nanoseconds interval = kCustomCollectionInterval;
        std::chrono::nanoseconds maxDuration = kCustomCollectionDuration;
        std::unordered_set<std::string> filterPackages;
        for (uint32_t i = 1; i < numArgs; ++i) {
            if (EqualsIgnoreCase(args[i], kIntervalFlag)) {
                const auto& result = parseSecondsFlag(args, numArgs, i + 1);
                if (!result.ok()) {
                    return Error(BAD_VALUE)
                            << "Failed to parse " << kIntervalFlag << ": " << result.error();
                }
                interval = std::chrono::duration_cast<std::chrono::nanoseconds>(*result);
                ++i;
                continue;
            }
            if (EqualsIgnoreCase(args[i], kMaxDurationFlag)) {
                const auto& result = parseSecondsFlag(args, numArgs, i + 1);
                if (!result.ok()) {
                    return Error(BAD_VALUE)
                            << "Failed to parse " << kMaxDurationFlag << ": " << result.error();
                }
                maxDuration = std::chrono::duration_cast<std::chrono::nanoseconds>(*result);
                ++i;
                continue;
            }
            if (EqualsIgnoreCase(args[i], kFilterPackagesFlag)) {
                // On derived implementation, the code flow should filter the custom collection
                // results to only the passed packages. On base implementation, the code flow should
                // continue.
                const auto& result = onFilterPackagesFlag(args, /*valuePos=*/i + 1, numArgs);
                if (!result.ok()) {
                    return result.error();
                }
                ++i;
                filterPackages = std::move(*result);
                continue;
            }
            return Error(BAD_VALUE) << "Unknown flag " << args[i]
                                    << " provided to start custom performance data collection";
        }
        if (const auto& result = startCustomCollection(interval, maxDuration, filterPackages);
            !result.ok()) {
            return result;
        }
        return {};
    }
    if (EqualsIgnoreCase(args[0], kEndCustomCollectionFlag)) {
        if (numArgs != 1) {
            ALOGW("Number of arguments to stop custom performance data collection cannot exceed 1. "
                  "Stopping the data collection.");
            WriteStringToFd("Number of arguments to stop custom performance data collection "
                            "cannot exceed 1. Stopping the data collection.",
                            fd);
        }
        return endCustomCollection(fd);
    }
    return Error(BAD_VALUE) << "Custom perf collection dump arguments start neither with "
                            << kStartCustomCollectionFlag << " nor with "
                            << kEndCustomCollectionFlag << " flags";
}

Result<void> WatchdogPerfServiceBase::onDump(int fd) const {
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

    return onDumpInternalLocked(fd);
}

Result<void> WatchdogPerfServiceBase::onDumpInternalLocked(int fd) const {
    if (!WriteStringToFd(StringPrintf("\n%s%s report:\n%s", kDumpMajorDelimiter.c_str(),
                                      kServiceName, kDumpMajorDelimiter.c_str()),
                         fd) ||
        !WriteStringToFd(StringPrintf("\nPeriodic collection information:\n%s\n",
                                      std::string(32, '=').c_str()),
                         fd) ||
        !WriteStringToFd(mPeriodicCollection.toString(), fd)) {
        return Error(FAILED_TRANSACTION) << "Failed to dump the periodic collection report.";
    }

    WriteStringToFd(kDumpMajorDelimiter, fd);
    return {};
}

bool WatchdogPerfServiceBase::dumpHelpText(int fd) const {
    return WriteStringToFd(StringPrintf(kDumpHelpTextBase, kServiceName, kStartCustomCollectionFlag,
                                        kIntervalFlag,
                                        std::chrono::duration_cast<std::chrono::seconds>(
                                                kCustomCollectionInterval)
                                                .count(),
                                        kMaxDurationFlag,
                                        std::chrono::duration_cast<std::chrono::minutes>(
                                                kCustomCollectionDuration)
                                                .count(),
                                        /*No filter packages flag in base implementation*/ "",
                                        StringPrintf(kCustomCollectionStopText,
                                                     kEndCustomCollectionFlag)
                                                .c_str()),
                           fd);
}

Result<void> WatchdogPerfServiceBase::dumpCollectorsStatusLocked(int fd) const {
    if (!mUidStatsCollectorBase->enabled() &&
        !WriteStringToFd(StringPrintf("UidStatsCollectorBase failed to access I/O files"), fd)) {
        return Error() << "Failed to write UidStatsCollectorBase status";
    }
    return {};
}

Result<void> WatchdogPerfServiceBase::startCustomCollection(
        std::chrono::nanoseconds interval, std::chrono::nanoseconds maxDuration,
        const std::unordered_set<std::string>& filterPackages) {
    if (interval < kMinEventInterval || maxDuration < kMinEventInterval) {
        return Error(INVALID_OPERATION)
                << "Collection polling interval and maximum duration must be >= "
                << std::chrono::duration_cast<std::chrono::milliseconds>(kMinEventInterval).count()
                << " milliseconds";
    }
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent == EventType::CUSTOM_COLLECTION) {
        return Error(INVALID_OPERATION) << "Cannot start custom collection more than once";
    }
    nsecs_t now = mHandlerLooper->now();
    mCustomCollection = {
            .eventType = EventType::CUSTOM_COLLECTION,
            .pollingIntervalNs = interval,
            .lastPollElapsedRealTimeNs = now,
            .filterPackages = filterPackages,
    };

    auto thiz = sp<WatchdogPerfServiceBase>::fromExisting(this);
    mHandlerLooper->removeMessages(thiz);
    mHandlerLooper->sendMessageAtTime(now + maxDuration.count(), thiz,
                                      SwitchMessage::END_CUSTOM_COLLECTION);
    mCurrCollectionEvent = EventType::CUSTOM_COLLECTION;
    mHandlerLooper->sendMessage(thiz, EventType::CUSTOM_COLLECTION);
    ALOGI("Starting %s performance data collection", toString(mCurrCollectionEvent));
    return {};
}

Result<void> WatchdogPerfServiceBase::endCustomCollection(int fd) {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != EventType::CUSTOM_COLLECTION) {
        return Error(INVALID_OPERATION) << "No custom collection is running";
    }

    auto thiz = sp<WatchdogPerfServiceBase>::fromExisting(this);
    mHandlerLooper->removeMessages(thiz);
    mHandlerLooper->sendMessage(thiz, SwitchMessage::END_CUSTOM_COLLECTION);

    if (const auto result = dumpCollectorsStatusLocked(fd); !result.ok()) {
        return Error(FAILED_TRANSACTION) << result.error();
    }

    if (!WriteStringToFd(StringPrintf("%sPerformance data report for custom collection:\n%s",
                                      kDumpMajorDelimiter.c_str(), kDumpMajorDelimiter.c_str()),
                         fd) ||
        !WriteStringToFd(mCustomCollection.toString(), fd)) {
        return Error(FAILED_TRANSACTION) << "Failed to write custom collection report.";
    }

    if (const auto result = onDataProcessorCustomCollectionDumpLocked(fd); !result.ok()) {
        return Error() << result.error();
    }

    if (DEBUG) {
        ALOGD("Custom event finished");
    }
    WriteStringToFd(kDumpMajorDelimiter, fd);
    return {};
}

void WatchdogPerfServiceBase::switchToPeriodicLocked(bool startNow) {
    if (mCurrCollectionEvent == EventType::PERIODIC_COLLECTION) {
        ALOGW("The current performance data collection event is already %s",
              toString(mCurrCollectionEvent));
        return;
    }
    auto thiz = sp<WatchdogPerfServiceBase>::fromExisting(this);
    mHandlerLooper->removeMessages(thiz);
    mCurrCollectionEvent = EventType::PERIODIC_COLLECTION;
    mPeriodicCollection.lastPollElapsedRealTimeNs = mHandlerLooper->now();
    if (startNow) {
        mHandlerLooper->sendMessage(thiz, EventType::PERIODIC_COLLECTION);
    } else {
        mPeriodicCollection.lastPollElapsedRealTimeNs +=
                mPeriodicCollection.pollingIntervalNs.count();
        mHandlerLooper->sendMessageAtTime(mPeriodicCollection.lastPollElapsedRealTimeNs, thiz,
                                          EventType::PERIODIC_COLLECTION);
    }
    mPeriodicMonitor.lastPollElapsedRealTimeNs =
            mHandlerLooper->now() + mPeriodicMonitor.pollingIntervalNs.count();
    mHandlerLooper->sendMessageAtTime(mPeriodicMonitor.lastPollElapsedRealTimeNs, thiz,
                                      EventType::PERIODIC_MONITOR);
    ALOGI("Switching to %s and %s", toString(mCurrCollectionEvent),
          toString(EventType::PERIODIC_MONITOR));
}

void WatchdogPerfServiceBase::handleMessage(const Message& message) {
    Result<void> result;

    switch (message.what) {
        case static_cast<int>(EventType::PERIODIC_COLLECTION):
            result = processCollectionEvent(&mPeriodicCollection);
            break;
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
                ALOGW("Skipping END_CUSTOM_COLLECTION message as the current collection %s != "
                      "%s",
                      toString(mCurrCollectionEvent), toString(expected));
                return;
            }
            mCustomCollection = {};
            clearCustomCollectionCacheLocked();
            switchToPeriodicLocked(/*startNow=*/true);
            return;
        }
        case static_cast<int>(TaskMessage::SEND_RESOURCE_STATS):
            result = sendResourceStats();
            break;
        default:
            result = handleMessageExtension(message);
    }

    if (!result.ok()) {
        Mutex::Autolock lock(mMutex);
        ALOGE("Terminating %s: %s", kServiceName, result.error().message().c_str());
        /*
         * DO NOT CALL terminate() as it tries to join the collection thread but this code is
         * executed on the collection thread. Thus it will result in a deadlock.
         */
        mCurrCollectionEvent = EventType::TERMINATED;
        mHandlerLooper->removeMessages(sp<WatchdogPerfServiceBase>::fromExisting(this));
        mHandlerLooper->wake();
    }
}

Result<void> WatchdogPerfServiceBase::handleMessageExtension(const Message& message) {
    return Error() << "Unknown message: " << message.what;
}

Result<void> WatchdogPerfServiceBase::processCollectionEvent(
        WatchdogPerfServiceBase::EventMetadata* metadata) {
    Mutex::Autolock lock(mMutex);
    /*
     * Messages sent to the looper are intrinsically racy such that a message from the previous
     * collection event may land in the looper after the current collection has already begun. Thus
     * verify the current collection event before starting the collection.
     */
    if (mCurrCollectionEvent != metadata->eventType) {
        ALOGW("Skipping %s event on collection event %s", toString(metadata->eventType),
              toString(mCurrCollectionEvent));
        return {};
    }
    if (DEBUG) {
        ALOGD("Processing %s collection event", toString(metadata->eventType));
    }
    if (metadata->pollingIntervalNs < kMinEventInterval) {
        return Error()
                << "Collection polling interval of "
                << std::chrono::duration_cast<std::chrono::seconds>(metadata->pollingIntervalNs)
                           .count()
                << " seconds for " << toString(metadata->eventType)
                << " collection cannot be less than "
                << std::chrono::duration_cast<std::chrono::seconds>(kMinEventInterval).count()
                << " seconds";
    }
    if (const auto result = collectLocked(metadata); !result.ok()) {
        return Error() << toString(metadata->eventType) << " collection failed: " << result.error();
    }
    metadata->lastPollElapsedRealTimeNs += metadata->pollingIntervalNs.count();
    mHandlerLooper->sendMessageAtTime(metadata->lastPollElapsedRealTimeNs,
                                      sp<WatchdogPerfServiceBase>::fromExisting(this),
                                      metadata->eventType);
    return {};
}

Result<void> WatchdogPerfServiceBase::collectLocked(
        [[maybe_unused]] WatchdogPerfServiceBase::EventMetadata* metadata) {
    if (!mUidStatsCollectorBase->enabled()) {
        return Error() << "No collectors enabled";
    }

    auto now = std::chrono::time_point_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now());

    if (mUidStatsCollectorBase->enabled()) {
        if (const auto result = mUidStatsCollectorBase->collect(); !result.ok()) {
            return Error() << "Failed to collect per-uid proc and I/O stats: " << result.error();
        }
    }

    ResourceStats resourceStats = {};

    Result<void> result;
    switch (mCurrCollectionEvent) {
        case EventType::PERIODIC_COLLECTION:
        case EventType::CUSTOM_COLLECTION:
            result = mIoOveruseMonitorBase->onPeriodicCollection(now,
                                                                 mSystemState ==
                                                                         SystemState::GARAGE_MODE,
                                                                 mUidStatsCollectorBase,
                                                                 &resourceStats);
            break;
        default:
            result = Error() << "Invalid collection event " << toString(mCurrCollectionEvent);
    }
    if (!result.ok()) {
        return Error() << "IoOveruseMonitorBase failed on " << toString(mCurrCollectionEvent)
                       << " collection: " << result.error();
    }

    if (!isEmpty(resourceStats)) {
        cacheUnsentResourceStatsLocked(std::move(resourceStats));
    }

    return handleUnsentResourceStatsLocked();
}

Result<void> WatchdogPerfServiceBase::handleUnsentResourceStatsLocked() {
    if (mUnsentResourceStats.empty() || !mWatchdogServiceHelperBase->isServiceConnected()) {
        if (DEBUG && !mUnsentResourceStats.empty() &&
            !mWatchdogServiceHelperBase->isServiceConnected()) {
            ALOGD("Cannot send resource stats since CarWatchdogService not connected.");
        }
        return {};
    }

    // Send message to send resource stats
    mHandlerLooper->sendMessage(sp<WatchdogPerfServiceBase>::fromExisting(this),
                                TaskMessage::SEND_RESOURCE_STATS);

    return {};
}

Result<void> WatchdogPerfServiceBase::processMonitorEvent(
        WatchdogPerfServiceBase::EventMetadata* metadata) {
    if (metadata->eventType != static_cast<int>(EventType::PERIODIC_MONITOR)) {
        return Error() << "Invalid monitor event " << toString(metadata->eventType);
    }
    if (DEBUG) {
        ALOGD("Processing %s monitor event", toString(metadata->eventType));
    }
    if (metadata->pollingIntervalNs < kMinEventInterval) {
        return Error()
                << "Monitor polling interval of "
                << std::chrono::duration_cast<std::chrono::seconds>(metadata->pollingIntervalNs)
                           .count()
                << " seconds for " << toString(metadata->eventType) << " event cannot be less than "
                << std::chrono::duration_cast<std::chrono::seconds>(kMinEventInterval).count()
                << " seconds";
    }
    Mutex::Autolock lock(mMutex);
    if (!mProcDiskStatsCollector->enabled()) {
        return Error() << "Cannot access proc disk stats for monitoring";
    }
    time_t now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
    if (const auto result = mProcDiskStatsCollector->collect(); !result.ok()) {
        return Error() << "Failed to collect disk stats: " << result.error();
    }
    auto* currCollectionMetadata = getCurrentCollectionMetadataLocked();
    if (currCollectionMetadata == nullptr) {
        return Error() << "No metadata available for current collection event: "
                       << toString(mCurrCollectionEvent);
    }
    bool requestedCollection = false;
    auto thiz = sp<WatchdogPerfServiceBase>::fromExisting(this);
    const auto requestCollection = [&]() mutable {
        if (requestedCollection) {
            return;
        }
        const nsecs_t prevLastPollElapsedRealTimeNs =
                currCollectionMetadata->lastPollElapsedRealTimeNs -
                currCollectionMetadata->pollingIntervalNs.count();
        nsecs_t lastPollElapsedRealTimeNs = mHandlerLooper->now();
        if (const auto delta = std::abs(lastPollElapsedRealTimeNs - prevLastPollElapsedRealTimeNs);
            delta < kMinEventInterval.count()) {
            return;
        }
        currCollectionMetadata->lastPollElapsedRealTimeNs = lastPollElapsedRealTimeNs;
        mHandlerLooper->removeMessages(thiz, currCollectionMetadata->eventType);
        mHandlerLooper->sendMessage(thiz, currCollectionMetadata->eventType);
        requestedCollection = true;
    };
    const char* eventTypeString = toString(metadata->eventType);
    if (const auto result =
                onDataProcessorPeriodicMonitorLocked(now, requestCollection, eventTypeString);
        !result.ok()) {
        return result.error();
    }
    metadata->lastPollElapsedRealTimeNs += metadata->pollingIntervalNs.count();
    if (metadata->lastPollElapsedRealTimeNs == currCollectionMetadata->lastPollElapsedRealTimeNs) {
        /*
         * If the |PERIODIC_MONITOR| and  *_COLLECTION events overlap, skip the
         * |PERIODIC_MONITOR| event.
         */
        metadata->lastPollElapsedRealTimeNs += metadata->pollingIntervalNs.count();
    }
    mHandlerLooper->sendMessageAtTime(metadata->lastPollElapsedRealTimeNs, thiz,
                                      metadata->eventType);
    return {};
}

Result<void> WatchdogPerfServiceBase::onDataProcessorPeriodicMonitorLocked(
        time_t now, const std::function<void()>& requestCollection, const char* eventTypeString) {
    if (const auto result = mIoOveruseMonitorBase->onPeriodicMonitor(now, mProcDiskStatsCollector,
                                                                     requestCollection);
        !result.ok()) {
        return Error() << "IoOveruseMonitorBase failed on " << eventTypeString << ": "
                       << result.error();
    }
    return {};
}

Result<void> WatchdogPerfServiceBase::sendResourceStats() {
    std::vector<ResourceStats> unsentResourceStats = {};
    {
        Mutex::Autolock lock(mMutex);
        nsecs_t now = mHandlerLooper->now();
        for (auto it = mUnsentResourceStats.begin(); it != mUnsentResourceStats.end();) {
            if (now - std::get<nsecs_t>(*it) >= kPrevUnsentResourceStatsMaxDurationNs.count()) {
                // Drop the expired stats
                it = mUnsentResourceStats.erase(it);
                continue;
            }
            unsentResourceStats.push_back(std::get<ResourceStats>(*it));
            ++it;
        }
    }
    if (unsentResourceStats.empty()) {
        return {};
    }
    if (auto status = mWatchdogServiceHelperBase->onLatestResourceStats(unsentResourceStats);
        !status.isOk()) {
        ALOGW("Failed to push the unsent resource stats to watchdog service: %s",
              status.getDescription().c_str());
        return {};
    }
    Mutex::Autolock lock(mMutex);
    mUnsentResourceStats.clear();
    if (DEBUG) {
        ALOGD("Pushed latest resource usage and I/O overuse stats to watchdog service");
    }
    return {};
}

void WatchdogPerfServiceBase::cacheUnsentResourceStatsLocked(ResourceStats resourceStats) {
    mUnsentResourceStats.push_back(
            std::make_tuple(mHandlerLooper->now(), std::move(resourceStats)));
    if (mUnsentResourceStats.size() > kMaxCachedUnsentResourceStats) {
        mUnsentResourceStats.erase(mUnsentResourceStats.begin());
    }
}

WatchdogPerfServiceBase::EventMetadata*
WatchdogPerfServiceBase::getCurrentCollectionMetadataLocked() {
    switch (mCurrCollectionEvent) {
        case EventType::PERIODIC_COLLECTION:
            return &mPeriodicCollection;
        case EventType::CUSTOM_COLLECTION:
            return &mCustomCollection;
        default:
            return nullptr;
    }
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
