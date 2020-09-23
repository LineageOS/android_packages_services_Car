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

#ifndef CPP_WATCHDOG_SERVER_SRC_WATCHDOGPERFSERVICE_H_
#define CPP_WATCHDOG_SERVER_SRC_WATCHDOGPERFSERVICE_H_

#include "LooperWrapper.h"
#include "ProcPidStat.h"
#include "ProcStat.h"
#include "UidIoStats.h"

#include <android-base/chrono_utils.h>
#include <android-base/result.h>
#include <cutils/multiuser.h>
#include <gtest/gtest_prod.h>
#include <time.h>
#include <utils/Errors.h>
#include <utils/Looper.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>
#include <utils/String16.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

#include <string>
#include <thread>  // NOLINT(build/c++11)
#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

// Forward declaration for testing use only.
namespace internal {

class WatchdogPerfServicePeer;

}  // namespace internal

constexpr const char* kStartCustomCollectionFlag = "--start_perf";
constexpr const char* kEndCustomCollectionFlag = "--stop_perf";
constexpr const char* kIntervalFlag = "--interval";
constexpr const char* kMaxDurationFlag = "--max_duration";
constexpr const char* kFilterPackagesFlag = "--filter_packages";

class DataProcessor : public RefBase {
public:
    DataProcessor() {}
    virtual ~DataProcessor() {}
    virtual std::string name() = 0;
    virtual android::base::Result<void> start() = 0;
    virtual void terminate() = 0;
    virtual android::base::Result<void> onBoottimeCollection(
            time_t time, const android::wp<UidIoStats>& uidIoStats,
            const android::wp<ProcStat>& procStat, const android::wp<ProcPidStat>& procPidStat) = 0;
    virtual android::base::Result<void> onPeriodicCollection(
            time_t time, const android::wp<UidIoStats>& uidIoStats,
            const android::wp<ProcStat>& procStat, const android::wp<ProcPidStat>& procPidStat) = 0;
    virtual android::base::Result<void> onCustomCollection(
            time_t time, const std::unordered_set<std::string>& filterPackages,
            const android::wp<UidIoStats>& uidIoStats, const android::wp<ProcStat>& procStat,
            const android::wp<ProcPidStat>& procPidStat) = 0;
    virtual android::base::Result<void> onDump(int fd) = 0;
    virtual android::base::Result<void> onCustomCollectionDump(int fd) = 0;
};

struct CollectionMetadata {
    std::chrono::nanoseconds interval = 0ns;  // Collection interval between subsequent collections.
    nsecs_t lastCollectionUptime = 0;         // Used to calculate the uptime for next collection.
    // Filter the results only to the specified packages.
    std::unordered_set<std::string> filterPackages;
};

std::string toString(const CollectionMetadata& metadata);

enum CollectionEvent {
    INIT = 0,
    BOOT_TIME,
    PERIODIC,
    CUSTOM,
    TERMINATED,
    LAST_EVENT,
};

enum SwitchEvent {
    // Ends boot-time collection by collecting the last boot-time record and switching the
    // collection event to periodic collection.
    END_BOOTTIME_COLLECTION = CollectionEvent::LAST_EVENT + 1,
    // Ends custom collection, discards collected data and starts periodic collection.
    END_CUSTOM_COLLECTION
};

static inline std::string toString(CollectionEvent event) {
    switch (event) {
        case CollectionEvent::INIT:
            return "INIT";
        case CollectionEvent::BOOT_TIME:
            return "BOOT_TIME";
        case CollectionEvent::PERIODIC:
            return "PERIODIC";
        case CollectionEvent::CUSTOM:
            return "CUSTOM";
        case CollectionEvent::TERMINATED:
            return "TERMINATED";
        default:
            return "INVALID";
    }
}

// WatchdogPerfService collects performance data during boot-time and periodically post boot
// complete. It exposes APIs that the main thread and binder service can call to start a collection,
// switch the collection type, and generate collection dumps.
class WatchdogPerfService : public MessageHandler {
public:
    WatchdogPerfService() :
          mHandlerLooper(new LooperWrapper()),
          mBoottimeCollection({}),
          mPeriodicCollection({}),
          mCustomCollection({}),
          mCurrCollectionEvent(CollectionEvent::INIT),
          mUidIoStats(new UidIoStats()),
          mProcStat(new ProcStat()),
          mProcPidStat(new ProcPidStat()),
          mDataProcessors({}) {}

    ~WatchdogPerfService() { terminate(); }

    void registerDataProcessor(android::sp<DataProcessor> processor) {
        mDataProcessors.emplace_back(processor);
    }

    // Starts the boot-time collection in the looper handler on a new thread and returns
    // immediately. Must be called only once. Otherwise, returns an error.
    virtual android::base::Result<void> start();

    // Terminates the collection thread and returns.
    virtual void terminate();

    // Ends the boot-time collection by switching to periodic collection and returns immediately.
    virtual android::base::Result<void> onBootFinished();

    // Depending on the arguments, it either:
    // 1. Starts a custom collection.
    // 2. Or ends the current custom collection and dumps the collected data.
    // Returns any error observed during the dump generation.
    virtual android::base::Result<void> onCustomCollection(int fd, const Vector<String16>& args);

    // Generates a dump from the boot-time and periodic collection events.
    virtual android::base::Result<void> onDump(int fd);

    // Dumps the help text.
    bool dumpHelpText(int fd);

private:
    // Dumps the collectors' status when they are disabled.
    android::base::Result<void> dumpCollectorsStatusLocked(int fd);

    // Starts a custom collection on the looper handler, temporarily stops the periodic collection
    // (won't discard the collected data), and returns immediately. Returns any error observed
    // during this process.
    // The custom collection happens once every |interval| seconds. When the |maxDuration| is
    // reached, the looper receives a message to end the collection, discards the collected data,
    // and starts the periodic collection. This is needed to ensure the custom collection doesn't
    // run forever when a subsequent |endCustomCollection| call is not received.
    // When |kFilterPackagesFlag| value specified, the results are filtered only to the specified
    // package names.
    android::base::Result<void> startCustomCollection(
            std::chrono::nanoseconds interval, std::chrono::nanoseconds maxDuration,
            const std::unordered_set<std::string>& filterPackages);

    // Ends the current custom collection, generates a dump, sends a looper message to start the
    // periodic collection, and returns immediately. Returns an error when there is no custom
    // collection running or when a dump couldn't be generated from the custom collection.
    android::base::Result<void> endCustomCollection(int fd);

    // Handles the messages received by the lopper.
    void handleMessage(const Message& message) override;

    // Processes the events received by |handleMessage|.
    android::base::Result<void> processCollectionEvent(CollectionEvent event,
                                                       CollectionMetadata* metadata);

    // Collects/processes the performance data for the current collection event.
    android::base::Result<void> collectLocked(CollectionMetadata* metadata);

    // Thread on which the actual collection happens.
    std::thread mCollectionThread;

    // Makes sure only one collection is running at any given time.
    Mutex mMutex;

    // Handler lopper to execute different collection events on the collection thread.
    android::sp<LooperWrapper> mHandlerLooper GUARDED_BY(mMutex);

    // Info for the |CollectionEvent::BOOT_TIME| collection event.
    CollectionMetadata mBoottimeCollection GUARDED_BY(mMutex);

    // Info for the |CollectionEvent::PERIODIC| collection event.
    CollectionMetadata mPeriodicCollection GUARDED_BY(mMutex);

    // Info for the |CollectionEvent::CUSTOM| collection event. The info is cleared at the end of
    // every custom collection.
    CollectionMetadata mCustomCollection GUARDED_BY(mMutex);

    // Tracks the current collection event. Updated on |start|, |onBootComplete|,
    // |startCustomCollection|, |endCustomCollection|, and |terminate|.
    CollectionEvent mCurrCollectionEvent GUARDED_BY(mMutex);

    // Collector/parser for `/proc/uid_io/stats`.
    android::sp<UidIoStats> mUidIoStats GUARDED_BY(mMutex);

    // Collector/parser for `/proc/stat`.
    android::sp<ProcStat> mProcStat GUARDED_BY(mMutex);

    // Collector/parser for `/proc/PID/*` stat files.
    android::sp<ProcPidStat> mProcPidStat GUARDED_BY(mMutex);

    // Data processors for the collected performance data.
    std::vector<android::sp<DataProcessor>> mDataProcessors GUARDED_BY(mMutex);

    // For unit tests.
    friend class internal::WatchdogPerfServicePeer;
    FRIEND_TEST(WatchdogPerfServiceTest, TestServiceStartAndTerminate);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_WATCHDOGPERFSERVICE_H_
