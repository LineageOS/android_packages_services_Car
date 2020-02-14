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

#ifndef WATCHDOG_SERVER_SRC_IOPERFCOLLECTION_H_
#define WATCHDOG_SERVER_SRC_IOPERFCOLLECTION_H_

#include <android-base/chrono_utils.h>
#include <android-base/result.h>
#include <android/content/pm/IPackageManagerNative.h>
#include <cutils/multiuser.h>
#include <gtest/gtest_prod.h>
#include <time.h>
#include <utils/Errors.h>
#include <utils/Mutex.h>

#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "ProcStat.h"
#include "UidIoStats.h"

namespace android {
namespace automotive {
namespace watchdog {

// TODO(b/148489461): Replace the below constants (except kCustomCollection* constants) with
// read-only persistent properties.
const int kTopNStatsPerCategory = 5;
const std::chrono::seconds kBoottimeCollectionInterval = 1s;
const std::chrono::seconds kPeriodicCollectionInterval = 10s;
// Number of periodic collection perf data snapshots to cache in memory.
const uint kPeriodicCollectionBufferSize = 180;

// Default values for the custom collection interval and max_duration.
const std::chrono::seconds kCustomCollectionInterval = 10s;
const std::chrono::seconds kCustomCollectionDuration = 30min;

// Performance data collected from the `/proc/uid_io/stats` file.
struct UidIoPerfData {
    struct Stats {
        userid_t userId = 0;
        std::string packageName;
        uint64_t bytes[UID_STATES];
        double bytesPercent[UID_STATES];
        uint64_t fsync[UID_STATES];
        double fsyncPercent[UID_STATES];
    };
    std::vector<Stats> topNReads = {};
    std::vector<Stats> topNWrites = {};
};

std::string toString(const UidIoPerfData& perfData);

// Performance data collected from the `/proc/stats` file.
struct SystemIoPerfData {
    uint64_t cpuIoWaitTime = 0;
    double cpuIoWaitPercent = 0.0;
    uint32_t ioBlockedProcessesCnt = 0;
    double ioBlockedProcessesPercent = 0;
};

std::string toString(const SystemIoPerfData& perfData);

// Performance data collected from the `/proc/[pid]/stat` and `/proc/[pid]/task/[tid]/stat` files.
struct ProcessIoPerfData {
    struct Stats {
        userid_t userId = 0;
        std::string packageName;
        uint64_t count = 0;
        uint64_t percent = 0;
    };
    std::vector<Stats> topNIoBlockedProcesses = {};
    std::vector<Stats> topNMajorPageFaults = {};
    uint64_t totalMajorPageFaults = 0;
    // Percentage of increase in the major page faults since last collection.
    double majorPageFaultsIncrease = 0.0;
};

struct IoPerfRecord {
    int64_t time;  // Collection time.
    UidIoPerfData uidIoPerfData;
    SystemIoPerfData systemIoPerfData;
    ProcessIoPerfData processIoPerfData;
};

enum CollectionEvent {
    BOOT_TIME = 0,
    PERIODIC,
    CUSTOM,
    NONE,
};

static inline std::string toEventString(CollectionEvent event) {
    switch (event) {
        case CollectionEvent::BOOT_TIME:
            return "BOOT_TIME";
        case CollectionEvent::PERIODIC:
            return "PERIODIC";
        case CollectionEvent::CUSTOM:
            return "CUSTOM";
        case CollectionEvent::NONE:
            return "NONE";
        default:
            return "INVALID";
    }
}

// IoPerfCollection implements the I/O performance data collection module of the CarWatchDog
// service. It exposes APIs that the CarWatchDog main thread and binder service can call to start
// a collection, update the collection type, and generate collection dumps.
class IoPerfCollection {
public:
    IoPerfCollection()
        : mTopNStatsPerCategory(kTopNStatsPerCategory),
          mBoottimeRecords({}),
          mPeriodicRecords({}),
          mCustomRecords({}),
          mCurrCollectionEvent(CollectionEvent::NONE),
          mUidToPackageNameMapping({}),
          mUidIoStats() {
    }

    // Starts the boot-time collection on a separate thread and returns immediately. Must be called
    // only once. Otherwise, returns an error.
    android::base::Result<void> start();

    // Stops the boot-time collection thread, caches boot-time perf records, starts the periodic
    // collection on a separate thread, and returns immediately.
    android::base::Result<void> onBootFinished();

    // Generates a dump from the boot-time and periodic collection events.
    // Returns any error observed during the dump generation.
    status_t dump(int fd);

    // Starts a custom collection on a separate thread, stops the periodic collection (won't discard
    // the collected data), and returns immediately. Returns any error observed during this process.
    // The custom collection happens once every |interval| seconds. When the |maxDuration| is
    // reached, stops the collection, discards the collected data, and starts the periodic
    // collection. This is needed to ensure the custom collection doesn't run forever when
    // a subsequent |endCustomCollection| call is not received.
    status_t startCustomCollection(std::chrono::seconds interval = kCustomCollectionInterval,
                                   std::chrono::seconds maxDuration = kCustomCollectionDuration);

    // Stops the current custom collection thread, generates a dump, starts the periodic collection
    // on a separate thread, and returns immediately. Returns an error when there is no custom
    // collection running or when a dump couldn't be generated from the custom collection.
    status_t endCustomCollection(int fd);

private:
    // Only used by tests.
    explicit IoPerfCollection(std::string uidIoStatsPath, std::string procStatPath) :
          mTopNStatsPerCategory(kTopNStatsPerCategory),
          mBoottimeRecords({}),
          mPeriodicRecords({}),
          mCustomRecords({}),
          mCurrCollectionEvent(CollectionEvent::NONE),
          mUidToPackageNameMapping({}),
          mUidIoStats(uidIoStatsPath),
          mProcStat(procStatPath) {}

    // Collects/stores the performance data for the current collection event.
    android::base::Result<void> collect();

    // Collects performance data from the `/proc/uid_io/stats` file.
    android::base::Result<void> collectUidIoPerfDataLocked(UidIoPerfData* uidIoPerfData);

    // Collects performance data from the `/proc/stats` file.
    android::base::Result<void> collectSystemIoPerfDataLocked(SystemIoPerfData* systemIoPerfData);

    // Collects performance data from the `/proc/[pid]/stat` and
    // `/proc/[pid]/task/[tid]/stat` files.
    android::base::Result<void> collectProcessIoPerfDataLocked(
            ProcessIoPerfData* processIoPerfData);

    // Updates the |mUidToPackageNameMapping| for the given |uids|.
    android::base::Result<void> updateUidToPackageNameMapping(
            const std::unordered_set<uint32_t>& uids);

    // Retrieves package manager from the default service manager.
    android::base::Result<void> retrievePackageManager();

    int mTopNStatsPerCategory;

    // Makes sure only one collection is running at any given time.
    Mutex mMutex;

    // Cache of the performance records collected during boot-time collection.
    std::vector<IoPerfRecord> mBoottimeRecords GUARDED_BY(mMutex);

    // Cache of the performance records collected during periodic collection. Size of this cache
    // is limited by |kPeriodicCollectionBufferSize|.
    std::vector<IoPerfRecord> mPeriodicRecords GUARDED_BY(mMutex);

    // Cache of the performance records collected during custom collection. This cache is cleared
    // at the end of every custom collection.
    std::vector<IoPerfRecord> mCustomRecords GUARDED_BY(mMutex);

    // Tracks the current collection event. Updated on |start|, |onBootComplete|,
    // |startCustomCollection| and |endCustomCollection|.
    CollectionEvent mCurrCollectionEvent GUARDED_BY(mMutex);

    // Cache of uid to package name mapping.
    std::unordered_map<uint64_t, std::string> mUidToPackageNameMapping GUARDED_BY(mMutex);

    // Collector/parser for `/proc/uid_io/stats`.
    UidIoStats mUidIoStats GUARDED_BY(mMutex);

    // Collector/parser for `/proc/stat`.
    ProcStat mProcStat GUARDED_BY(mMutex);

    // To get the package names from app uids.
    android::sp<android::content::pm::IPackageManagerNative> mPackageManager GUARDED_BY(mMutex);

    FRIEND_TEST(IoPerfCollectionTest, TestValidUidIoStatFile);
    FRIEND_TEST(IoPerfCollectionTest, TestUidIOStatsLessThanTopNStatsLimit);
    FRIEND_TEST(IoPerfCollectionTest, TestProcUidIoStatsContentsFromDevice);
    FRIEND_TEST(IoPerfCollectionTest, TestValidProcStatFile);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  WATCHDOG_SERVER_SRC_IOPERFCOLLECTION_H_
