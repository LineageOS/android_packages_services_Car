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

#include "WatchdogPerfService.h"

#include "utils/PackageNameResolver.h"

#include <WatchdogProperties.sysprop.h>
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

using android::String16;
using android::base::Error;
using android::base::Join;
using android::base::ParseUint;
using android::base::Result;
using android::base::Split;
using android::base::StringAppendF;
using android::base::StringPrintf;
using android::base::WriteStringToFd;

namespace {

// Minimum required collection interval between subsequent collections.
const std::chrono::nanoseconds kMinCollectionInterval = 1s;
const std::chrono::seconds kDefaultBoottimeCollectionInterval = 1s;
const std::chrono::seconds kDefaultPeriodicCollectionInterval = 10s;
const std::chrono::nanoseconds kCustomCollectionInterval = 10s;
const std::chrono::nanoseconds kCustomCollectionDuration = 30min;

constexpr const char* kServiceName = "WatchdogPerfService";
static const std::string kDumpMajorDelimiter = std::string(100, '-') + "\n";  // NOLINT
constexpr const char* kHelpText =
        "\n%s dump options:\n"
        "%s: Starts custom performance data collection. Customize the collection behavior with "
        "the following optional arguments:\n"
        "\t%s <seconds>: Modifies the collection interval. Default behavior is to collect once "
        "every %lld seconds.\n"
        "\t%s <seconds>: Modifies the maximum collection duration. Default behavior is to collect "
        "until %ld minutes before automatically stopping the custom collection and discarding "
        "the collected data.\n"
        "\t%s <package name>,<package, name>,...: Comma-separated value containing package names. "
        "When provided, the results are filtered only to the provided package names. Default "
        "behavior is to list the results for the top N packages.\n"
        "%s: Stops custom performance data collection and generates a dump of "
        "the collection report.\n\n"
        "When no options are specified, the carwatchdog report contains the performance data "
        "collected during boot-time and over the last few minutes before the report generation.\n";

Result<std::chrono::seconds> parseSecondsFlag(const Vector<String16>& args, size_t pos) {
    if (args.size() <= pos) {
        return Error() << "Value not provided";
    }

    uint64_t value;
    std::string strValue = std::string(String8(args[pos]).string());
    if (!ParseUint(strValue, &value)) {
        return Error() << "Invalid value " << strValue << ", must be an integer";
    }
    return std::chrono::seconds(value);
}

}  // namespace

std::string toString(const CollectionMetadata& metadata) {
    std::string buffer;
    auto interval = std::chrono::duration_cast<std::chrono::seconds>(metadata.interval).count();
    StringAppendF(&buffer, "Collection interval: %lld second%s\n", interval,
                  ((interval > 1) ? "s" : ""));
    if (!metadata.filterPackages.empty()) {
        std::vector<std::string> packages(metadata.filterPackages.begin(),
                                          metadata.filterPackages.end());
        StringAppendF(&buffer, "Filtered results to packages: %s\n", Join(packages, ", ").c_str());
    }
    return buffer;
}

Result<void> WatchdogPerfService::start() {
    {
        Mutex::Autolock lock(mMutex);
        if (mCurrCollectionEvent != CollectionEvent::INIT || mCollectionThread.joinable()) {
            return Error(INVALID_OPERATION) << "Cannot start " << kServiceName << " more than once";
        }
        std::chrono::nanoseconds boottimeCollectionInterval =
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::seconds(sysprop::boottimeCollectionInterval().value_or(
                                kDefaultBoottimeCollectionInterval.count())));
        std::chrono::nanoseconds periodicCollectionInterval =
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::seconds(sysprop::periodicCollectionInterval().value_or(
                                kDefaultPeriodicCollectionInterval.count())));
        mBoottimeCollection = {
                .interval = boottimeCollectionInterval,
                .lastCollectionUptime = 0,
        };
        mPeriodicCollection = {
                .interval = periodicCollectionInterval,
                .lastCollectionUptime = 0,
        };
        for (const auto& processor : mDataProcessors) {
            const auto& result = processor->start();
            if (!result.ok()) {
                std::string errorMsg =
                        StringPrintf("Failed to start %s: %s", processor->name().c_str(),
                                     result.error().message().c_str());
                ALOGE("Terminating %s: %s", kServiceName, errorMsg.c_str());
                mCurrCollectionEvent = CollectionEvent::TERMINATED;
                return Error() << errorMsg;
            }
        }
    }

    mCollectionThread = std::thread([&]() {
        {
            Mutex::Autolock lock(mMutex);
            if (mCurrCollectionEvent != CollectionEvent::INIT) {
                ALOGE("Skipping performance data collection as the current collection event "
                      "%s != %s",
                      toString(mCurrCollectionEvent).c_str(),
                      toString(CollectionEvent::INIT).c_str());
                return;
            }
            mCurrCollectionEvent = CollectionEvent::BOOT_TIME;
            mBoottimeCollection.lastCollectionUptime = mHandlerLooper->now();
            mHandlerLooper->setLooper(Looper::prepare(/*opts=*/0));
            mHandlerLooper->sendMessage(this, CollectionEvent::BOOT_TIME);
        }
        if (set_sched_policy(0, SP_BACKGROUND) != 0) {
            ALOGW("Failed to set background scheduling priority to %s thread", kServiceName);
        }
        int result = pthread_setname_np(pthread_self(), "WatchdogPerfSvc");
        if (result != 0) {
            ALOGE("Failed to set %s thread name: %d", kServiceName, result);
        }
        ALOGI("Starting %s performance data collection", toString(mCurrCollectionEvent).c_str());
        bool isCollectionActive = true;
        // Loop until the collection is not active -- performance collection runs on this thread in
        // a handler.
        while (isCollectionActive) {
            mHandlerLooper->pollAll(/*timeoutMillis=*/-1);
            Mutex::Autolock lock(mMutex);
            isCollectionActive = mCurrCollectionEvent != CollectionEvent::TERMINATED;
        }
    });
    return {};
}

void WatchdogPerfService::terminate() {
    {
        Mutex::Autolock lock(mMutex);
        if (mCurrCollectionEvent == CollectionEvent::TERMINATED) {
            ALOGE("%s was terminated already", kServiceName);
            return;
        }
        ALOGE("Terminating %s as carwatchdog is terminating", kServiceName);
        if (mCurrCollectionEvent != CollectionEvent::INIT) {
            // Looper runs only after the INIT collection has completed so remove looper messages
            // and wake the looper only when the current collection has changed from INIT.
            mHandlerLooper->removeMessages(this);
            mHandlerLooper->wake();
        }
        for (const auto& processor : mDataProcessors) {
            processor->terminate();
        }
        mCurrCollectionEvent = CollectionEvent::TERMINATED;
    }
    if (mCollectionThread.joinable()) {
        mCollectionThread.join();
    }
}

Result<void> WatchdogPerfService::onBootFinished() {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != CollectionEvent::BOOT_TIME) {
        // This case happens when either the WatchdogPerfService has prematurely terminated before
        // boot complete notification is received or multiple boot complete notifications are
        // received. In either case don't return error as this will lead to runtime exception and
        // cause system to boot loop.
        ALOGE("Current performance data collection event %s != %s",
              toString(mCurrCollectionEvent).c_str(), toString(CollectionEvent::BOOT_TIME).c_str());
        return {};
    }
    mBoottimeCollection.lastCollectionUptime = mHandlerLooper->now();
    mHandlerLooper->removeMessages(this);
    mHandlerLooper->sendMessage(this, SwitchEvent::END_BOOTTIME_COLLECTION);
    return {};
}

Result<void> WatchdogPerfService::onCustomCollection(int fd, const Vector<String16>& args) {
    if (args.empty()) {
        return Error(BAD_VALUE) << "No custom collection dump arguments";
    }

    if (args[0] == String16(kStartCustomCollectionFlag)) {
        if (args.size() > 7) {
            return Error(BAD_VALUE) << "Number of arguments to start custom performance data "
                                    << "collection cannot exceed 7";
        }
        std::chrono::nanoseconds interval = kCustomCollectionInterval;
        std::chrono::nanoseconds maxDuration = kCustomCollectionDuration;
        std::unordered_set<std::string> filterPackages;
        for (size_t i = 1; i < args.size(); ++i) {
            if (args[i] == String16(kIntervalFlag)) {
                const auto& result = parseSecondsFlag(args, i + 1);
                if (!result.ok()) {
                    return Error(BAD_VALUE)
                            << "Failed to parse " << kIntervalFlag << ": " << result.error();
                }
                interval = std::chrono::duration_cast<std::chrono::nanoseconds>(*result);
                ++i;
                continue;
            }
            if (args[i] == String16(kMaxDurationFlag)) {
                const auto& result = parseSecondsFlag(args, i + 1);
                if (!result.ok()) {
                    return Error(BAD_VALUE)
                            << "Failed to parse " << kMaxDurationFlag << ": " << result.error();
                }
                maxDuration = std::chrono::duration_cast<std::chrono::nanoseconds>(*result);
                ++i;
                continue;
            }
            if (args[i] == String16(kFilterPackagesFlag)) {
                if (args.size() < i + 1) {
                    return Error(BAD_VALUE)
                            << "Must provide value for '" << kFilterPackagesFlag << "' flag";
                }
                std::vector<std::string> packages =
                        Split(std::string(String8(args[i + 1]).string()), ",");
                std::copy(packages.begin(), packages.end(),
                          std::inserter(filterPackages, filterPackages.end()));
                ++i;
                continue;
            }
            ALOGW("Unknown flag %s provided to start custom performance data collection",
                  String8(args[i]).string());
            return Error(BAD_VALUE) << "Unknown flag " << String8(args[i]).string()
                                    << " provided to start custom performance data collection";
        }
        const auto& result = startCustomCollection(interval, maxDuration, filterPackages);
        if (!result.ok()) {
            WriteStringToFd(result.error().message(), fd);
            return result;
        }
        return {};
    }

    if (args[0] == String16(kEndCustomCollectionFlag)) {
        if (args.size() != 1) {
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

Result<void> WatchdogPerfService::onDump(int fd) {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent == CollectionEvent::TERMINATED) {
        ALOGW("%s not active. Dumping cached data", kServiceName);
        if (!WriteStringToFd(StringPrintf("%s not active. Dumping cached data.", kServiceName),
                             fd)) {
            return Error(FAILED_TRANSACTION) << "Failed to write " << kServiceName << " status";
        }
    }

    const auto& result = dumpCollectorsStatusLocked(fd);
    if (!result.ok()) {
        return Error(FAILED_TRANSACTION) << result.error();
    }

    if (!WriteStringToFd(StringPrintf("\n%s%s report:\n%sBoot-time collection information:\n%s\n",
                                      kDumpMajorDelimiter.c_str(), kServiceName,
                                      kDumpMajorDelimiter.c_str(), std::string(33, '=').c_str()),
                         fd) ||
        !WriteStringToFd(toString(mBoottimeCollection), fd) ||
        !WriteStringToFd(StringPrintf("\nPeriodic collection information:\n%s\n",
                                      std::string(32, '=').c_str()),
                         fd) ||
        !WriteStringToFd(toString(mPeriodicCollection), fd)) {
        return Error(FAILED_TRANSACTION)
                << "Failed to dump the boot-time and periodic collection reports.";
    }

    for (const auto& processor : mDataProcessors) {
        auto result = processor->onDump(fd);
        if (!result.ok()) {
            return result;
        }
    }

    WriteStringToFd(kDumpMajorDelimiter, fd);
    return {};
}

bool WatchdogPerfService::dumpHelpText(int fd) {
    return WriteStringToFd(StringPrintf(kHelpText, kServiceName, kStartCustomCollectionFlag,
                                        kIntervalFlag,
                                        std::chrono::duration_cast<std::chrono::seconds>(
                                                kCustomCollectionInterval)
                                                .count(),
                                        kMaxDurationFlag,
                                        std::chrono::duration_cast<std::chrono::minutes>(
                                                kCustomCollectionDuration)
                                                .count(),
                                        kFilterPackagesFlag, kEndCustomCollectionFlag),
                           fd);
}

Result<void> WatchdogPerfService::dumpCollectorsStatusLocked(int fd) {
    if (!mUidIoStats->enabled() &&
        !WriteStringToFd(StringPrintf("UidIoStats collector failed to access the file %s",
                                      mUidIoStats->filePath().c_str()),
                         fd)) {
        return Error() << "Failed to write UidIoStats collector status";
    }
    if (!mProcStat->enabled() &&
        !WriteStringToFd(StringPrintf("ProcStat collector failed to access the file %s",
                                      mProcStat->filePath().c_str()),
                         fd)) {
        return Error() << "Failed to write ProcStat collector status";
    }
    if (!mProcPidStat->enabled() &&
        !WriteStringToFd(StringPrintf("ProcPidStat collector failed to access the directory %s",
                                      mProcPidStat->dirPath().c_str()),
                         fd)) {
        return Error() << "Failed to write ProcPidStat collector status";
    }
    return {};
}

Result<void> WatchdogPerfService::startCustomCollection(
        std::chrono::nanoseconds interval, std::chrono::nanoseconds maxDuration,
        const std::unordered_set<std::string>& filterPackages) {
    if (interval < kMinCollectionInterval || maxDuration < kMinCollectionInterval) {
        return Error(INVALID_OPERATION)
                << "Collection interval and maximum duration must be >= "
                << std::chrono::duration_cast<std::chrono::milliseconds>(kMinCollectionInterval)
                           .count()
                << " milliseconds.";
    }
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != CollectionEvent::PERIODIC) {
        return Error(INVALID_OPERATION)
                << "Cannot start a custom collection when the current collection event "
                << toString(mCurrCollectionEvent) << " != " << toString(CollectionEvent::PERIODIC)
                << " collection event";
    }

    mCustomCollection = {
            .interval = interval,
            .lastCollectionUptime = mHandlerLooper->now(),
            .filterPackages = filterPackages,
    };

    mHandlerLooper->removeMessages(this);
    nsecs_t uptime = mHandlerLooper->now() + maxDuration.count();
    mHandlerLooper->sendMessageAtTime(uptime, this, SwitchEvent::END_CUSTOM_COLLECTION);
    mCurrCollectionEvent = CollectionEvent::CUSTOM;
    mHandlerLooper->sendMessage(this, CollectionEvent::CUSTOM);
    ALOGI("Starting %s performance data collection", toString(mCurrCollectionEvent).c_str());
    return {};
}

Result<void> WatchdogPerfService::endCustomCollection(int fd) {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != CollectionEvent::CUSTOM) {
        return Error(INVALID_OPERATION) << "No custom collection is running";
    }

    mHandlerLooper->removeMessages(this);
    mHandlerLooper->sendMessage(this, SwitchEvent::END_CUSTOM_COLLECTION);

    const auto& result = dumpCollectorsStatusLocked(fd);
    if (!result.ok()) {
        return Error(FAILED_TRANSACTION) << result.error();
    }

    if (!WriteStringToFd(StringPrintf("%sPerformance data report for custom collection:\n%s",
                                      kDumpMajorDelimiter.c_str(), kDumpMajorDelimiter.c_str()),
                         fd) ||
        !WriteStringToFd(toString(mCustomCollection), fd)) {
        return Error(FAILED_TRANSACTION) << "Failed to write custom collection report.";
    }

    for (const auto& processor : mDataProcessors) {
        auto result = processor->onCustomCollectionDump(fd);
        if (!result.ok()) {
            return Error() << processor->name() << " failed on " << toString(mCurrCollectionEvent)
                           << " collection: " << result.error();
        }
    }

    WriteStringToFd(kDumpMajorDelimiter, fd);
    return {};
}

void WatchdogPerfService::handleMessage(const Message& message) {
    Result<void> result;

    switch (message.what) {
        case static_cast<int>(CollectionEvent::BOOT_TIME):
            result = processCollectionEvent(CollectionEvent::BOOT_TIME, &mBoottimeCollection);
            break;
        case static_cast<int>(SwitchEvent::END_BOOTTIME_COLLECTION):
            result = processCollectionEvent(CollectionEvent::BOOT_TIME, &mBoottimeCollection);
            if (result.ok()) {
                mHandlerLooper->removeMessages(this);
                mCurrCollectionEvent = CollectionEvent::PERIODIC;
                mPeriodicCollection.lastCollectionUptime =
                        mHandlerLooper->now() + mPeriodicCollection.interval.count();
                mHandlerLooper->sendMessageAtTime(mPeriodicCollection.lastCollectionUptime, this,
                                                  CollectionEvent::PERIODIC);
                ALOGI("Switching to %s performance data collection",
                      toString(mCurrCollectionEvent).c_str());
            }
            break;
        case static_cast<int>(CollectionEvent::PERIODIC):
            result = processCollectionEvent(CollectionEvent::PERIODIC, &mPeriodicCollection);
            break;
        case static_cast<int>(CollectionEvent::CUSTOM):
            result = processCollectionEvent(CollectionEvent::CUSTOM, &mCustomCollection);
            break;
        case static_cast<int>(SwitchEvent::END_CUSTOM_COLLECTION): {
            Mutex::Autolock lock(mMutex);
            if (mCurrCollectionEvent != CollectionEvent::CUSTOM) {
                ALOGW("Skipping END_CUSTOM_COLLECTION message as the current collection %s != %s",
                      toString(mCurrCollectionEvent).c_str(),
                      toString(CollectionEvent::CUSTOM).c_str());
                return;
            }
            mCustomCollection = {};
            for (const auto& processor : mDataProcessors) {
                // Clear custom collection cache on the data processors when the custom collection
                // auto-terminates.
                processor->onCustomCollectionDump(-1);
            }
            mHandlerLooper->removeMessages(this);
            mCurrCollectionEvent = CollectionEvent::PERIODIC;
            mPeriodicCollection.lastCollectionUptime = mHandlerLooper->now();
            mHandlerLooper->sendMessage(this, CollectionEvent::PERIODIC);
            ALOGI("Switching to %s performance data collection",
                  toString(mCurrCollectionEvent).c_str());
            return;
        }
        default:
            result = Error() << "Unknown message: " << message.what;
    }

    if (!result.ok()) {
        Mutex::Autolock lock(mMutex);
        ALOGE("Terminating %s: %s", kServiceName, result.error().message().c_str());
        // DO NOT CALL terminate() as it tries to join the collection thread but this code is
        // executed on the collection thread. Thus it will result in a deadlock.
        mCurrCollectionEvent = CollectionEvent::TERMINATED;
        mHandlerLooper->removeMessages(this);
        mHandlerLooper->wake();
    }
}

Result<void> WatchdogPerfService::processCollectionEvent(CollectionEvent event,
                                                         CollectionMetadata* metadata) {
    Mutex::Autolock lock(mMutex);
    // Messages sent to the looper are intrinsically racy such that a message from the previous
    // collection event may land in the looper after the current collection has already begun. Thus
    // verify the current collection event before starting the collection.
    if (mCurrCollectionEvent != event) {
        ALOGW("Skipping %s collection message on collection event %s", toString(event).c_str(),
              toString(mCurrCollectionEvent).c_str());
        return {};
    }
    if (metadata->interval < kMinCollectionInterval) {
        return Error()
                << "Collection interval of "
                << std::chrono::duration_cast<std::chrono::seconds>(metadata->interval).count()
                << " seconds for " << toString(event) << " collection cannot be less than "
                << std::chrono::duration_cast<std::chrono::seconds>(kMinCollectionInterval).count()
                << " seconds";
    }
    auto result = collectLocked(metadata);
    if (!result.ok()) {
        return Error() << toString(event) << " collection failed: " << result.error();
    }
    metadata->lastCollectionUptime += metadata->interval.count();
    mHandlerLooper->sendMessageAtTime(metadata->lastCollectionUptime, this, event);
    return {};
}

Result<void> WatchdogPerfService::collectLocked(CollectionMetadata* metadata) {
    if (!mUidIoStats->enabled() && !mProcStat->enabled() && !mProcPidStat->enabled()) {
        return Error() << "No collectors enabled";
    }

    time_t now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());

    if (mUidIoStats->enabled()) {
        const auto result = mUidIoStats->collect();
        if (!result.ok()) {
            return Error() << "Failed to collect per-uid I/O usage: " << result.error();
        }
    }

    if (mProcStat->enabled()) {
        const auto result = mProcStat->collect();
        if (!result.ok()) {
            return Error() << "Failed to collect proc stats: " << result.error();
        }
    }

    if (mProcPidStat->enabled()) {
        const auto result = mProcPidStat->collect();
        if (!result.ok()) {
            return Error() << "Failed to collect process stats: " << result.error();
        }
    }

    for (const auto& processor : mDataProcessors) {
        Result<void> result;
        switch (mCurrCollectionEvent) {
            case CollectionEvent::BOOT_TIME:
                result = processor->onBoottimeCollection(now, mUidIoStats, mProcStat, mProcPidStat);
                break;
            case CollectionEvent::PERIODIC:
                result = processor->onPeriodicCollection(now, mUidIoStats, mProcStat, mProcPidStat);
                break;
            case CollectionEvent::CUSTOM:
                result = processor->onCustomCollection(now, metadata->filterPackages, mUidIoStats,
                                                       mProcStat, mProcPidStat);
                break;
            default:
                result = Error() << "Invalid collection event " << toString(mCurrCollectionEvent);
        }
        if (!result.ok()) {
            return Error() << processor->name() << " failed on " << toString(mCurrCollectionEvent)
                           << " collection: " << result.error();
        }
    }

    return {};
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
