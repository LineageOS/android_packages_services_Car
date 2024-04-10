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

#ifndef CPP_WATCHDOG_SERVER_SRC_UIDPROCSTATSCOLLECTOR_H_
#define CPP_WATCHDOG_SERVER_SRC_UIDPROCSTATSCOLLECTOR_H_

#include <android-base/result.h>
#include <android-base/stringprintf.h>
#include <gtest/gtest_prod.h>
#include <meminfo/procmeminfo.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>

#include <android_car_feature.h>
#include <inttypes.h>
#include <stdint.h>
#include <unistd.h>

#include <string>
#include <unordered_map>
#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::base::StringPrintf;

#define PID_FOR_INIT 1

constexpr const char kProcDirPath[] = "/proc";
constexpr const char kStatFileFormat[] = "/%" PRIu32 "/stat";
constexpr const char kTaskDirFormat[] = "/%" PRIu32 "/task";
constexpr const char kStatusFileFormat[] = "/%" PRIu32 "/status";
constexpr const char kSmapsRollupFileFormat[] = "/%" PRIu32 "/smaps_rollup";
constexpr const char kStatmFileFormat[] = "/%" PRIu32 "/statm";
constexpr const char kTimeInStateFileFormat[] = "/%" PRIu32 "/time_in_state";

/**
 * Per-pid/tid stats.
 *
 * The int64_t type is used due to AIDL limitations representing long field values.
 */
struct PidStat {
    std::string comm = "";
    std::string state = "";
    int64_t startTimeMillis = 0;
    int64_t cpuTimeMillis = 0;
    uint64_t majorFaults = 0;
};

// Per-process stats.
struct ProcessStats {
    std::string comm = "";
    int64_t startTimeMillis = 0;  // Useful when identifying PID reuse
    int64_t cpuTimeMillis = 0;
    // Stats in below fields are aggregated across all threads
    uint64_t totalCpuCycles = 0;
    uint64_t totalMajorFaults = 0;
    int totalTasksCount = 0;
    int ioBlockedTasksCount = 0;
    std::unordered_map<pid_t, uint64_t> cpuCyclesByTid = {};
    uint64_t rssKb = 0;
    /**
     * PSS/SwapPss will be missing when the smaps_rollup file is not supported or missing for
     * a process. In such cases, use RSS to rank the processes by memory usage.
     */
    uint64_t pssKb = 0;
    /**
     * Unique set size is the portion of memory unique (private) to the process. Unshared memory
     * is reported as USS.
     *
     * PSS - USS = Proportional portion of memory shared with one or more process.
     * RSS - USS = Total portion of memory shared with one or more process.
     */
    uint64_t ussKb = 0;
    uint64_t swapPssKb = 0;
    std::string toString() const;
};

// Per-UID stats.
struct UidProcStats {
    int64_t cpuTimeMillis = 0;
    uint64_t cpuCycles = 0;
    uint64_t totalMajorFaults = 0;
    int totalTasksCount = 0;
    int ioBlockedTasksCount = 0;
    /**
     * When smaps_rollup is supported by the Kernel, totalPssKb will be populated. When this
     * feature is not supported, use totalRssKb to rank the UIDs.
     *
     * totalRssKb counts total shared memory from each of the processes. Thus leading to counting
     * the same portion of memory more than once:
     *
     * For example, if N processes share X amount of memory and a subset of the processes (say M)
     * belong to same UID, then
     * 1. totalRssKb across all UIDs += Unique memory for N processes + (N * X).
     * 2. totalRssKb for the UID += Unique memory for M processes + (M * X).
     */
    uint64_t totalRssKb = 0;
    uint64_t totalPssKb = 0;
    // TODO(b/333212872): Handles totalUssKb, totalSwapPssKb calculation logic here.
    std::unordered_map<pid_t, ProcessStats> processStatsByPid = {};
    std::string toString() const;
};

/**
 * Collector/parser for `/proc/[pid]/stat`, `/proc/[pid]/task/[tid]/stat` and /proc/[pid]/status`
 * files.
 */
class UidProcStatsCollectorInterface : public RefBase {
public:
    // Initializes the collector.
    virtual void init() = 0;
    // Collects the per-uid stats from /proc directory.
    virtual android::base::Result<void> collect() = 0;
    // Returns the latest per-uid process stats.
    virtual const std::unordered_map<uid_t, UidProcStats> latestStats() const = 0;
    // Returns the delta of per-uid process stats since the last before collection.
    virtual const std::unordered_map<uid_t, UidProcStats> deltaStats() const = 0;
    // Returns true only when the /proc files for the init process are accessible.
    virtual bool enabled() const = 0;
    // Returns the /proc files common ancestor directory path.
    virtual const std::string dirPath() const = 0;
};

class UidProcStatsCollector final : public UidProcStatsCollectorInterface {
public:
    // TODO(b/333722043): Once carwatchdogd has sys_ptrace capability, set mIsSmapsRollupSupported
    // field from `android::meminfo::IsSmapsRollupSupported()`.
    // Disabling smaps_rollup support because this file cannot be read without sys_ptrace
    // capability.
    UidProcStatsCollector() :
          UidProcStatsCollector(kProcDirPath, /*isSmapsRollupSupported=*/false) {}
    // Used by tests.
    UidProcStatsCollector(const std::string& path, bool isSmapsRollupSupported);

    ~UidProcStatsCollector() {}

    void init() override;

    android::base::Result<void> collect() override;

    const std::unordered_map<uid_t, UidProcStats> latestStats() const {
        Mutex::Autolock lock(mMutex);
        return mLatestStats;
    }

    const std::unordered_map<uid_t, UidProcStats> deltaStats() const {
        Mutex::Autolock lock(mMutex);
        return mDeltaStats;
    }

    bool enabled() const {
        Mutex::Autolock lock(mMutex);
        return mIsEnabled;
    }

    const std::string dirPath() const { return mPath; }

    static android::base::Result<PidStat> readStatFileForPid(pid_t pid);

    static android::base::Result<std::tuple<uid_t, pid_t>> readPidStatusFileForPid(pid_t pid);

private:
    android::base::Result<std::unordered_map<uid_t, UidProcStats>> readUidProcStatsLocked() const;

    /**
     * Reads the contents of the below files:
     * 1. Pid stat file at |mPath| + |kStatFileFormat|
     * 2. Aggregated per-process status at |mPath| + |kStatusFileFormat|
     * 3. Tid stat file at |mPath| + |kTaskDirFormat| + |kStatFileFormat|
     */
    android::base::Result<std::tuple<uid_t, ProcessStats>> readProcessStatsLocked(pid_t pid) const;

    /**
     * Reads the smaps rollup file and populates the ProcessStats pointer with info for the given
     * pid.
     * Returns true and updates the out pointer only when the read is successful.
     * Returns false when either the smaps_rollup file is not supported or not available for
     * the process. When the process terminates while reading, the file won't be available.
     */
    bool readSmapsRollup(pid_t pid, ProcessStats* processStatsOut) const;

    size_t mPageSizeKb;

    // Tracks memory profiling feature flag.
    bool mIsMemoryProfilingEnabled;

    // Tracks smaps rollup support in the Kernel.
    bool mIsSmapsRollupSupported;

    // Number of milliseconds per clock cycle.
    int32_t mMillisPerClockTick;

    /**
     * Proc directory path. Default value is |kProcDirPath|.
     *
     * Updated by tests to point to a different location when needed.
     */
    std::string mPath;

    // Makes sure only one collection is running at any given time.
    mutable Mutex mMutex;

    /**
     * True if the below files are accessible:
     * 1. Pid stat file at |mPath| + |kStatFileFormat|
     * 2. Tid stat file at |mPath| + |kTaskDirFormat| + |kStatFileFormat|
     * 3. Pid status file at |mPath| + |kStatusFileFormat|
     * 4. Pid statm file at |mPath| + |kStatmFileFormat|
     *
     * Otherwise, set to false.
     */
    bool mIsEnabled GUARDED_BY(mMutex);

    /**
     * True if the tid time_in_state file at |mPath| + |kTaskDirFormat| + |kTimeInStateFileFormat|
     * is available.
     */
    bool mIsTimeInStateEnabled GUARDED_BY(mMutex);

    // Latest dump of per-UID stats.
    std::unordered_map<uid_t, UidProcStats> mLatestStats GUARDED_BY(mMutex);

    // Latest delta of per-uid stat
    std::unordered_map<uid_t, UidProcStats> mDeltaStats GUARDED_BY(mMutex);

    FRIEND_TEST(PerformanceProfilerTest, TestValidProcPidContents);
    FRIEND_TEST(UidProcStatsCollectorTest, TestValidStatFiles);
    FRIEND_TEST(UidProcStatsCollectorTest, TestHandlesProcessTerminationBetweenScanningAndParsing);
    FRIEND_TEST(UidProcStatsCollectorTest, TestHandlesPidTidReuse);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_UIDPROCSTATSCOLLECTOR_H_
