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

#pragma once

#include "IoOveruseMonitor.h"
#include "LooperWrapper.h"
#include "ProcDiskStatsCollector.h"
#include "UidStatsCollectorBase.h"
#include "WatchdogServiceHelperBase.h"

#include <WatchdogProperties.sysprop.h>
#include <aidl/android/automotive/watchdog/internal/ResourceStats.h>
#include <android-base/chrono_utils.h>
#include <android-base/result.h>
#include <gtest/gtest_prod.h>
#include <utils/Errors.h>
#include <utils/Looper.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

#include <time.h>

#include <string>
#include <thread>  // NOLINT(build/c++11)
#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

// Forward declaration for testing use only.
namespace internal {

class WatchdogPerfServiceBasePeer;

}  // namespace internal

constexpr std::chrono::nanoseconds kPrevUnsentResourceStatsMaxDurationNs = 10min;
constexpr const char* kStartCustomCollectionFlag = "--start_perf";
constexpr const char* kEndCustomCollectionFlag = "--stop_perf";
constexpr const char* kIntervalFlag = "--interval";
constexpr const char* kMaxDurationFlag = "--max_duration";
constexpr const char* kFilterPackagesFlag = "--filter_packages";
const std::chrono::nanoseconds kCustomCollectionInterval = 10s;
const std::chrono::nanoseconds kCustomCollectionDuration = 30min;

enum SystemState {
    NORMAL_MODE = 0,
    GARAGE_MODE = 1,
};

enum EventType {
    // WatchdogPerfServiceBase's state.
    INIT = 0,
    TERMINATED,

    // Collection events used only in automotive form-factors.
    BOOT_TIME_COLLECTION,
    USER_SWITCH_COLLECTION,
    WAKE_UP_COLLECTION,

    // Collection events used in all form-factors.
    PERIODIC_COLLECTION,
    CUSTOM_COLLECTION,

    // Monitor event.
    PERIODIC_MONITOR,

    LAST_EVENT,
};

enum SwitchMessage {
    /**
     * On receiving this message, collect the last boot-time record and start periodic collection
     * and monitor. Only used for automotive form-factors.
     */
    END_BOOTTIME_COLLECTION = EventType::LAST_EVENT + 1,

    /**
     * On receiving this message, collect the last user switch record and start periodic collection
     * and monitor. Only used for automotive form-factors.
     */
    END_USER_SWITCH_COLLECTION,

    /**
     * On receiving this message, collect the last wake up record and start periodic collection and
     * monitor. Only used for automotive form-factors.
     */
    END_WAKE_UP_COLLECTION,

    /**
     * On receiving this message, ends custom collection, discard collected data and start periodic
     * collection and monitor.
     */
    END_CUSTOM_COLLECTION,

    LAST_SWITCH_MSG,
};

enum TaskMessage {
    // On receiving this message, send the cached resource stats to CarWatchdogService.
    SEND_RESOURCE_STATS = SwitchMessage::LAST_SWITCH_MSG + 1,
};

/**
 * WatchdogPerfServiceBaseInterface periodically posts system events. It exposes APIs that the
 * main thread and binder service can call to start a collection, switch the collection type,
 * and generate collection dumps.
 */
class WatchdogPerfServiceBaseInterface : virtual public MessageHandler {
public:
    // Register IoOveruseMonitor to process the data collected by |WatchdogPerfServiceBase|.
    virtual android::base::Result<void> registerIoOveruseMonitor(
            android::sp<IoOveruseMonitorInterface> ioOveruseMonitor) = 0;
    virtual void init() = 0;
    /**
     * Starts the periodic collection in the looper handler on a new thread and returns
     * immediately. Must be called only once. Otherwise, returns an error.
     */
    virtual android::base::Result<void> start() = 0;
    // Terminates the collection thread and returns.
    virtual void terminate() = 0;
    // Sets the system state.
    virtual void setSystemState(SystemState systemState) = 0;
    // Handles unsent resource stats.
    virtual void onCarWatchdogServiceRegistered() = 0;

    /**
     * Depending on the arguments, it either:
     * 1. Starts a custom collection.
     * 2. Or ends the current custom collection and dumps the collected data.
     * Returns any error observed during the dump generation.
     */
    virtual android::base::Result<void> onCustomCollection(int fd, const char** args,
                                                           uint32_t numArgs) = 0;
    // Generates a dump from the system events and periodic collection events.
    virtual android::base::Result<void> onDump(int fd) const = 0;
    // Dumps the help text.
    virtual bool dumpHelpText(int fd) const = 0;
};

class WatchdogPerfServiceBase : public WatchdogPerfServiceBaseInterface {
public:
    WatchdogPerfServiceBase(
            const android::sp<WatchdogServiceHelperBaseInterface>& watchdogServiceHelperBase) :
          mHandlerLooper(android::sp<LooperWrapper>::make()),
          mSystemState(NORMAL_MODE),
          mUnsentResourceStats({}),
          mPeriodicCollection({}),
          mCustomCollection({}),
          mPeriodicMonitor({}),
          mCurrCollectionEvent(EventType::INIT),
          mProcDiskStatsCollector(android::sp<ProcDiskStatsCollector>::make()),
          mWatchdogServiceHelperBase(watchdogServiceHelperBase),
          mUidStatsCollectorBase(android::sp<UidStatsCollectorBase>::make()),
          mIoOveruseMonitor({}) {}

    android::base::Result<void> registerIoOveruseMonitor(
            android::sp<IoOveruseMonitorInterface> ioOveruseMonitor) override;

    void init() override;

    android::base::Result<void> start() override;

    void terminate() override;

    void setSystemState(SystemState systemState) override;

    void onCarWatchdogServiceRegistered() override;

    android::base::Result<void> onCustomCollection(int fd, const char** args,
                                                   uint32_t numArgs) override;

    android::base::Result<void> onDump(int fd) const override;

    bool dumpHelpText(int fd) const override;

protected:
    struct EventMetadata {
        // Collection or monitor event.
        EventType eventType = EventType::LAST_EVENT;
        // Interval between subsequent events.
        std::chrono::nanoseconds pollingIntervalNs = 0ns;
        // Used to calculate the uptime for next event.
        nsecs_t lastPollElapsedRealTimeNs = 0;
        // Filter the results only to the specified packages.
        std::unordered_set<std::string> filterPackages;

        std::string toString() const;
    };

    /**
     * Starts a custom collection on the looper handler, temporarily stops the periodic collection
     * (won't discard the collected data), and returns immediately. Returns any error observed
     * during this process.
     * The custom collection happens once every |interval| seconds. When the |maxDuration| is
     * reached, the looper receives a message to end the collection, discards the collected data,
     * and starts the periodic collection. This is needed to ensure the custom collection doesn't
     * run forever when a subsequent |endCustomCollection| call is not received.
     * When |kFilterPackagesFlag| value specified, the results are filtered only to the specified
     * package names.
     */
    android::base::Result<void> startCustomCollection(
            std::chrono::nanoseconds interval, std::chrono::nanoseconds maxDuration,
            const std::unordered_set<std::string>& filterPackages);

    /**
     * Ends the current custom collection, generates a dump, sends a looper message to start the
     * periodic collection, and returns immediately. Returns an error when there is no custom
     * collection running or when a dump couldn't be generated from the custom collection.
     */
    android::base::Result<void> endCustomCollection(int fd);

    // Switch to periodic collection and periodic monitor.
    void switchToPeriodicLocked(bool startNow);

    // Processes the collection events received by |handleMessage|.
    android::base::Result<void> processCollectionEvent(EventMetadata* metadata);

    // Processes the monitor events received by |handleMessage|.
    android::base::Result<void> processMonitorEvent(EventMetadata* metadata);

    // Sends the unsent resource stats.
    android::base::Result<void> sendResourceStats();

    // Caches resource stats that have not been sent to CarWatchdogService.
    void cacheUnsentResourceStatsLocked(
            aidl::android::automotive::watchdog::internal::ResourceStats resourceStats);

    // Collects/processes the performance data for the current collection event.
    virtual android::base::Result<void> collectLocked(EventMetadata* metadata);

    // Dumps the collectors' status when they are disabled.
    virtual android::base::Result<void> dumpCollectorsStatusLocked(int fd) const;

    /**
     * Returns the metadata for the current collection based on |mCurrCollectionEvent|. Returns
     * nullptr on invalid collection event.
     */
    virtual EventMetadata* getCurrentCollectionMetadataLocked();

    // Invokes periodic monitor methods in data processors.
    virtual android::base::Result<void> onDataProcessorPeriodicMonitorLocked(
            time_t now, const std::function<void()>& requestCollection,
            const char* eventTypeString);

    // Invokes terminate methods in data processors.
    virtual void onDataProcessorTerminateLocked();

    // Invokes onCarWatchdogServiceRegistered methods in data processors.
    virtual void onDataProcessorCarWatchdogServiceRegisteredLocked();

    // TODO(b/433795351): Dump the top 5 UIDs with the most I/O writes during custom collection.
    // Invokes onCustomCollectionDump methods in data processors.
    virtual android::base::Result<void> onDataProcessorCustomCollectionDumpLocked(
            [[maybe_unused]] int fd) {
        return {};
    }

    // Thread on which the actual collection happens.
    std::thread mCollectionThread;

    // Makes sure only one collection is running at any given time.
    mutable Mutex mMutex;

    // Handler looper to execute different collection events on the collection thread.
    android::sp<LooperWrapper> mHandlerLooper GUARDED_BY(mMutex);

    // Current system state.
    SystemState mSystemState GUARDED_BY(mMutex);

    // Cache of resource stats that have not been sent to CarWatchdogService.
    std::vector<std::tuple<nsecs_t, aidl::android::automotive::watchdog::internal::ResourceStats>>
            mUnsentResourceStats GUARDED_BY(mMutex);

    // Info for the |EventType::PERIODIC_COLLECTION| collection event.
    EventMetadata mPeriodicCollection GUARDED_BY(mMutex);

    // Info for the |EventType::CUSTOM_COLLECTION| collection event. The info is cleared at
    // the end of every custom collection.
    EventMetadata mCustomCollection GUARDED_BY(mMutex);

    // Info for the |EventType::PERIODIC_MONITOR| monitor event.
    EventMetadata mPeriodicMonitor GUARDED_BY(mMutex);

    // Tracks either the WatchdogPerfServiceBase's state or current collection event. Updated on
    // |start|, |startCustomCollection|, |endCustomCollection|, and |terminate|.
    EventType mCurrCollectionEvent GUARDED_BY(mMutex);

    // Collector/parser for `/proc/diskstats` file.
    android::sp<ProcDiskStatsCollectorInterface> mProcDiskStatsCollector GUARDED_BY(mMutex);

    // Helper to communicate with the CarWatchdogService.
    android::sp<WatchdogServiceHelperBaseInterface> mWatchdogServiceHelperBase GUARDED_BY(mMutex);

private:
    // Handles the messages received by the looper.
    void handleMessage(const Message& message) override;

    // Collector for UID I/O stats.
    android::sp<UidStatsCollectorBaseInterface> mUidStatsCollectorBase GUARDED_BY(mMutex);

    // Data processor for flash memory data.
    android::sp<IoOveruseMonitorInterface> mIoOveruseMonitor GUARDED_BY(mMutex);

    // For unit tests.
    friend class internal::WatchdogPerfServiceBasePeer;
    FRIEND_TEST(WatchdogPerfServiceBaseTest, TestServiceStartAndTerminate);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
