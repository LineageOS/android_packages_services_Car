/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "ProcPidDir.h"
#include "UidProcStatsCollector.h"
#include "UidProcStatsCollectorTestUtils.h"

#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <gmock/gmock.h>

#include <android_car_feature.h>
#include <inttypes.h>

#include <algorithm>
#include <string>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::automotive::watchdog::testing::populateProcPidDir;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::android::car::feature::car_watchdog_memory_profiling;
using ::testing::UnorderedPointwise;

namespace {

constexpr uint64_t kTestPrivateDirtyKb = 100;

MATCHER(UidProcStatsByUidEq, "") {
    const auto& actual = std::get<0>(arg);
    const auto& expected = std::get<1>(arg);
    return actual.first == expected.first &&
            ExplainMatchResult(UidProcStatsEq(expected.second), actual.second, result_listener);
}

std::string pidStatusStr(pid_t pid, uid_t uid) {
    return StringPrintf("Pid:\t%" PRIu32 "\nTgid:\t%" PRIu32 "\nUid:\t%" PRIu32 "\n", pid, pid,
                        uid);
}

std::string smapsRollupStr(uint64_t rssKb, uint64_t pssKb, uint64_t ussKb, uint64_t swapPssKb) {
    std::string buffer;
    StringAppendF(&buffer,
                  "5592470000-7ffc9a9000 ---p 00000000 00:00 0                              "
                  "[rollup]\n");
    // clang-format off
    StringAppendF(&buffer, "Rss: %" PRIu64 " kB\n", rssKb);
    StringAppendF(&buffer, "Pss: %" PRIu64 " kB\n", pssKb);
    StringAppendF(&buffer, "Pss_Anon:           1628 kB\n"
                           "Pss_File:            360 kB\n"
                           "Pss_Shmem:           303 kB\n"
                           "Shared_Clean:       2344 kB\n"
                           "Shared_Dirty:        688 kB\n");
    /**
     * Private_Dirty is 100 KB and ussKB = Private_Dirty + Private_Clean. So, ensure that ussKb
     * is at least 100 KB before proceeding.
     */
    EXPECT_GT(ussKb, kTestPrivateDirtyKb);
    StringAppendF(&buffer, "Private_Clean:      %" PRIu64 " kB\n", ussKb - kTestPrivateDirtyKb);
    StringAppendF(&buffer, "Private_Dirty:      %" PRIu64 " kB\n", kTestPrivateDirtyKb);
    StringAppendF(&buffer, "Referenced:         4908 kB\n"
                           "Anonymous:          1628 kB\n"
                           "LazyFree:              0 kB\n"
                           "AnonHugePages:         0 kB\n"
                           "ShmemPmdMapped:        0 kB\n"
                           "FilePmdMapped:         0 kB\n"
                           "Shared_Hugetlb:        0 kB\n"
                           "Private_Hugetlb:       0 kB\n"
                           "Swap:               5860 kB\n");
    StringAppendF(&buffer, "SwapPss:            %" PRIu64 " kB\n", swapPssKb);
    StringAppendF(&buffer, "Locked:                0 kB");
    // clang-format on
    return buffer;
}

std::string toString(const std::unordered_map<uid_t, UidProcStats>& uidProcStatsByUid) {
    std::string buffer;
    StringAppendF(&buffer, "Number of UIDs: %" PRIi32 "\n",
                  static_cast<int>(uidProcStatsByUid.size()));
    for (const auto& [uid, stats] : uidProcStatsByUid) {
        StringAppendF(&buffer, "{UID: %d, %s}", uid, stats.toString().c_str());
    }
    return buffer;
}

int64_t ticksToMillis(int32_t clockTicks) {
    return (clockTicks * 1000) / sysconf(_SC_CLK_TCK);
}

void applyFeatureFilter(std::unordered_map<uid_t, UidProcStats>* uidProcStatsByUid) {
    if (car_watchdog_memory_profiling()) {
        return;
    }
    for (auto& [uid, uidProcStats] : *uidProcStatsByUid) {
        uidProcStats.totalRssKb = 0;
        uidProcStats.totalPssKb = 0;
        for (auto& [pid, processStats] : uidProcStats.processStatsByPid) {
            processStats.rssKb = 0;
            processStats.pssKb = 0;
            processStats.ussKb = 0;
            processStats.swapPssKb = 0;
        }
    }
}

bool isSmapsRollupSupported(std::string rootPath) {
    std::string path = StringPrintf((rootPath + kSmapsRollupFileFormat).c_str(), 1);
    return access(path.c_str(), R_OK) == 0;
}

}  // namespace

TEST(UidProcStatsCollectorTest, TestValidStatFiles) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1, 453}},
            {1000, {1000, 1100}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 220 0 6 4 0 0 0 0 2 0 19\n"},
            {1000, "1000 (system_server) D 1 0 0 0 0 0 0 0 600 0 8000 4000 0 0 0 0 2 0 13400\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
            {1000, pidStatusStr(1000, 10001234)},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
            {1000,
             smapsRollupStr(/*rssKb=*/2000, /*pssKb=*/1635, /*ussKb=*/1286, /*swapPssKb=*/600)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 3 2 0 0 0 0 2 0 19\n"},
            {453, "453 (init) D 0 0 0 0 0 0 0 0 20 0 3 2 0 0 0 0 2 0 275\n"},
            {1000, "1000 (system_server) D 1 0 0 0 0 0 0 0 250 0 4000 2000 0 0 0 0 2 0 13400\n"},
            {1100, "1100 (system_server) D 1 0 0 0 0 0 0 0 350 0 4000 2000 0 0 0 0 2 0 13900\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 5\n1700000 0\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
            {453, "cpu0\n300000 0\n1700000 0\ncpu4\n710000 0\n1800000 5\ncpu7\n2000000 0"},
            {1000,
             "cpu0\n300000 0\n1700000 1000\ncpu4\n710000 1000\n1800000 3000\ncpu7\n2000000 6000"},
            {1100, "cpu0\n300000 0\n1700000 0\ncpu4\n710000 0\n1800000 1000\ncpu7\n2000000 0"},
    };

    std::unordered_map<uid_t, UidProcStats> expected =
            {{0,
              UidProcStats{.cpuTimeMillis = ticksToMillis(10),
                           .cpuCycles = 105'000'000,
                           .totalMajorFaults = 220,
                           .totalTasksCount = 2,
                           .ioBlockedTasksCount = 1,
                           .totalRssKb = 1000,
                           .totalPssKb = 865,
                           .processStatsByPid =
                                   {{1,
                                     {/*comm=*/"init", /*startTimeMillis=*/ticksToMillis(19),
                                      /*cpuTimeMillis=*/ticksToMillis(10),
                                      /*totalCpuCycles=*/105'000'000, /*totalMajorFaults=*/220,
                                      /*totalTasksCount=*/2, /*ioBlockedTasksCount=*/1,
                                      /*cpuCyclesByTid=*/{{1, 15'000'000}, {453, 90'000'000}},
                                      /*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656,
                                      /*swapPssKb=*/200}}}}},
             {10001234,
              UidProcStats{.cpuTimeMillis = ticksToMillis(12'000),
                           .cpuCycles = 216100000000,
                           .totalMajorFaults = 600,
                           .totalTasksCount = 2,
                           .ioBlockedTasksCount = 2,
                           .totalRssKb = 2000,
                           .totalPssKb = 1635,
                           .processStatsByPid = {{1000,
                                                  {/*comm=*/"system_server",
                                                   /*startTimeMillis=*/ticksToMillis(13'400),
                                                   /*cpuTimeMillis=*/ticksToMillis(12'000),
                                                   /*totalCpuCycles=*/216100000000,
                                                   /*totalMajorFaults=*/600,
                                                   /*totalTasksCount=*/2,
                                                   /*ioBlockedTasksCount=*/2, /*cpuCyclesByTid=*/
                                                   {{1000, 198100000000}, {1100, 18000000000}},
                                                   /*rssKb=*/2000,
                                                   /*pssKb=*/1635,
                                                   /*ussKb=*/1286,
                                                   /*swapPssKb=*/600}}}}}};
    applyFeatureFilter(&expected);

    TemporaryDir firstSnapshot;
    ASSERT_RESULT_OK(populateProcPidDir(firstSnapshot.path, pidToTids, perProcessStat,
                                        perProcessStatus, perProcessSmapsRollup,
                                        /*processStatm=*/{}, perThreadStat, perThreadTimeInState));

    UidProcStatsCollector collector(firstSnapshot.path, isSmapsRollupSupported(firstSnapshot.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << firstSnapshot.path << "` are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    auto actual = collector.deltaStats();

    EXPECT_THAT(actual, UnorderedPointwise(UidProcStatsByUidEq(), expected))
            << "First snapshot doesn't match.\nExpected:\n"
            << toString(expected) << "\nActual:\n"
            << toString(actual);

    pidToTids = {
            {1, {1, 453}}, {1000, {1000, 1400}},  // TID 1100 terminated and 1400 instantiated.
    };

    perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 920 0 10 10 0 0 0 0 2 0 19\n"},
            {1000, "1000 (system_server) R 1 0 0 0 0 0 0 0 1550 0 10000 8000 0 0 0 0 2 0 13400\n"},
    };

    perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/3000, /*pssKb=*/1865, /*ussKb=*/1656, /*swapPssKb=*/900)},
            {1000,
             smapsRollupStr(/*rssKb=*/2010, /*pssKb=*/1645, /*ussKb=*/1296, /*swapPssKb=*/610)},
    };

    perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 600 0 5 5 0 0 0 0 2 0 19\n"},
            {453, "453 (init) S 0 0 0 0 0 0 0 0 320 0 5 5 0 0 0 0 2 0 275\n"},
            {1000, "1000 (system_server) R 1 0 0 0 0 0 0 0 600 0 5000 2000 0 0 0 0 2 0 13400\n"},
            // TID 1100 hits +400 major page faults before terminating. This is counted against
            // PID 1000's perProcessStat.
            {1400, "1400 (system_server) S 1 0 0 0 0 0 0 0 200 0 5000 2000 0 0 0 0 2 0 8977476\n"},
    };

    perThreadTimeInState = {
            {1, "cpu0\n300000 5\n1700000 0\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 10"},
            {453, "cpu0\n300000 0\n1700000 0\ncpu4\n710000 0\n1800000 5\ncpu7\n2000000 0"},
            {1000,
             "cpu0\n300000 0\n1700000 1000\ncpu4\n710000 1000\n1800000 3000\ncpu7\n2000000 6000"},
            {1400, "cpu0\n300000 6000\n1700000 0\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
    };

    expected = {{0,
                 {.cpuTimeMillis = ticksToMillis(10),
                  .cpuCycles = 200'000'000,
                  .totalMajorFaults = 700,
                  .totalTasksCount = 2,
                  .ioBlockedTasksCount = 0,
                  .totalRssKb = 3000,
                  .totalPssKb = 1865,
                  .processStatsByPid = {{1,
                                         {/*comm=*/"init", /*startTimeMillis=*/ticksToMillis(19),
                                          /*cpuTimeMillis=*/ticksToMillis(10),
                                          /*totalCpuCycles=*/200'000'000, /*totalMajorFaults=*/700,
                                          /*totalTasksCount=*/2, /*ioBlockedTasksCount=*/0,
                                          /*cpuCyclesByTid=*/{{1, 200'000'000}, {453, 0}},
                                          /*rssKb=*/3000,
                                          /*pssKb=*/1865,
                                          /*ussKb=*/1656,
                                          /*swapPssKb=*/900}}}}},
                {10001234,
                 {.cpuTimeMillis = ticksToMillis(6'000),
                  .cpuCycles = 18'000'000'000,
                  .totalMajorFaults = 950,
                  .totalTasksCount = 2,
                  .ioBlockedTasksCount = 0,
                  .totalRssKb = 2010,
                  .totalPssKb = 1645,
                  .processStatsByPid = {
                          {1000,
                           {/*comm=*/"system_server", /*startTimeMillis=*/ticksToMillis(13'400),
                            /*cpuTimeMillis=*/ticksToMillis(6'000),
                            /*totalCpuCycles=*/18'000'000'000, /*totalMajorFaults=*/950,
                            /*totalTasksCount=*/2, /*ioBlockedTasksCount=*/0,
                            /*cpuCyclesByTid=*/{{1000, 0}, {1400, 18'000'000'000}},
                            /*rssKb=*/2010,
                            /*pssKb=*/1645,
                            /*ussKb=*/1296,
                            /*swapPssKb=*/610}}}}}};
    applyFeatureFilter(&expected);

    TemporaryDir secondSnapshot;
    ASSERT_RESULT_OK(populateProcPidDir(secondSnapshot.path, pidToTids, perProcessStat,
                                        perProcessStatus, perProcessSmapsRollup,
                                        /*processStatm=*/{}, perThreadStat, perThreadTimeInState));

    collector.mPath = secondSnapshot.path;

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << secondSnapshot.path << "` are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    actual = collector.deltaStats();
    EXPECT_THAT(actual, UnorderedPointwise(UidProcStatsByUidEq(), expected))
            << "Second snapshot doesn't match.\nExpected:\n"
            << toString(expected) << "\nActual:\n"
            << toString(actual);
}

TEST(UidProcStatsCollectorTest, TestHandlesProcessTerminationBetweenScanningAndParsing) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1}},
            {100, {100}},          // Process terminates after scanning PID directory.
            {1000, {1000}},        // Process terminates after reading stat file.
            {2000, {2000}},        // Process terminates after scanning task directory.
            {3000, {3000, 3300}},  // TID 3300 terminates after scanning task directory.
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 220 0 10 10 0 0 0 0 1 0 19\n"},
            // Process 100 terminated.
            {1000, "1000 (system_server) R 1 0 0 0 0 0 0 0 600 0 20 20 0 0 0 0 1 0 1000\n"},
            {2000, "2000 (logd) R 1 0 0 0 0 0 0 0 1200 0 30 30 0 0 0 0 1 0 4567\n"},
            {3000, "3000 (disk I/O) R 1 0 0 0 0 0 0 0 10300 0 40 40 0 0 0 0 2 0 67890\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
            // Process 1000 terminated.
            {2000, pidStatusStr(2000, 10001234)},
            {3000, pidStatusStr(3000, 10001234)},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
            {2000,
             smapsRollupStr(/*rssKb=*/2000, /*pssKb=*/1635, /*ussKb=*/1286, /*swapPssKb=*/600)},
            {3000,
             smapsRollupStr(/*rssKb=*/5642, /*pssKb=*/2312, /*ussKb=*/944, /*swapPssKb=*/500)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
            // Process 2000 terminated.
            {3000, "3000 (disk I/O) R 1 0 0 0 0 0 0 0 2400 0 30 30 0 0 0 0 2 0 67890\n"},
            // TID 3300 terminated.
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 10\n1700000 10\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
            // Process 3000 terminated.
    };

    std::unordered_map<uid_t, UidProcStats> expected =
            {{0,
              UidProcStats{.cpuTimeMillis = ticksToMillis(20),
                           .cpuCycles = 200'000'000,
                           .totalMajorFaults = 220,
                           .totalTasksCount = 1,
                           .ioBlockedTasksCount = 0,
                           .totalRssKb = 1000,
                           .totalPssKb = 865,
                           .processStatsByPid =
                                   {{1,
                                     {/*comm=*/"init", /*startTimeMillis=*/ticksToMillis(19),
                                      /*cpuTimeMillis=*/ticksToMillis(20),
                                      /*totalCpuCycles=*/200'000'000, /*totalMajorFaults=*/220,
                                      /*totalTasksCount=*/1, /*ioBlockedTasksCount=*/0,
                                      /*cpuCyclesByTid=*/{{1, 200'000'000}},
                                      /*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656,
                                      /*swapPssKb=*/200}}}}},
             {10001234,
              UidProcStats{.cpuTimeMillis = ticksToMillis(140),
                           .cpuCycles = 0,
                           .totalMajorFaults = 11500,
                           .totalTasksCount = 2,
                           .ioBlockedTasksCount = 0,
                           .totalRssKb = 7642,
                           .totalPssKb = 3947,
                           .processStatsByPid =
                                   {{2000,
                                     {/*comm=*/"logd", /*startTimeMillis=*/ticksToMillis(4567),
                                      /*cpuTimeMillis=*/ticksToMillis(60), /*totalCpuCycles=*/0,
                                      /*totalMajorFaults=*/1200, /*totalTasksCount=*/1,
                                      /*ioBlockedTasksCount=*/0, /*cpuCyclesByTid=*/{},
                                      /*rssKb=*/2000, /*pssKb=*/1635, /*ussKb=*/1286,
                                      /*swapPssKb=*/600}},
                                    {3000,
                                     {/*comm=*/"disk I/O",
                                      /*startTimeMillis=*/ticksToMillis(67890),
                                      /*cpuTimeMillis=*/ticksToMillis(80), /*totalCpuCycles=*/0,
                                      /*totalMajorFaults=*/10'300, /*totalTasksCount=*/1,
                                      /*ioBlockedTasksCount=*/0, /*cpuCyclesByTid=*/{},
                                      /*rssKb=*/5642, /*pssKb=*/2312, /*ussKb=*/944,
                                      /*swapPssKb=*/500}}}}}};
    applyFeatureFilter(&expected);

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        perThreadTimeInState));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    auto actual = collector.deltaStats();
    EXPECT_THAT(actual, UnorderedPointwise(UidProcStatsByUidEq(), expected))
            << "Proc pid contents doesn't match.\nExpected:\n"
            << toString(expected) << "\nActual:\n"
            << toString(actual);
}

TEST(UidProcStatsCollectorTest, TestHandlesPidTidReuse) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1, 367, 453, 589}},
            {1000, {1000}},
            {2345, {2345}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 1200 0 40 40 0 0 0 0 4 0 19\n"},
            {1000, "1000 (system_server) R 1 0 0 0 0 0 0 0 250 0 10 10 0 0 0 0 1 0 1000\n"},
            {2345, "2345 (logd) R 1 0 0 0 0 0 0 0 54354 0 10 10 0 0 0 0 1 0 456\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
            {1000, pidStatusStr(1000, 10001234)},
            {2345, pidStatusStr(2345, 10001234)},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
            {1000,
             smapsRollupStr(/*rssKb=*/2000, /*pssKb=*/1635, /*ussKb=*/1286, /*swapPssKb=*/600)},
            {2345,
             smapsRollupStr(/*rssKb=*/5642, /*pssKb=*/2312, /*ussKb=*/944, /*swapPssKb=*/500)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 4 0 19\n"},
            {367, "367 (init) S 0 0 0 0 0 0 0 0 400 0 10 10 0 0 0 0 4 0 100\n"},
            {453, "453 (init) S 0 0 0 0 0 0 0 0 100 0 10 10 0 0 0 0 4 0 275\n"},
            {589, "589 (init) D 0 0 0 0 0 0 0 0 500 0 10 10 0 0 0 0 4 0 600\n"},
            {1000, "1000 (system_server) R 1 0 0 0 0 0 0 0 250 0 10 10 0 0 0 0 1 0 1000\n"},
            {2345, "2345 (logd) R 1 0 0 0 0 0 0 0 54354 0 10 10 0 0 0 0 1 0 456\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 20\n1700000 0\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
            {367, "cpu0\n300000 0\n1700000 20\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
            {453, "cpu0\n300000 0\n1700000 0\ncpu4\n710000 0\n1800000 20\ncpu7\n2000000 0"},
            {589, "cpu0\n300000 0\n1700000 0\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 20"},
            {1000, "cpu0\n300000 20\n1700000 0\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
            {2345, "cpu0\n300000 0\n1700000 0\ncpu4\n710000 0\n1800000 20\ncpu7\n2000000 0"},
    };

    std::unordered_map<uid_t, UidProcStats> expected =
            {{0,
              UidProcStats{.cpuTimeMillis = ticksToMillis(80),
                           .cpuCycles = 1'160'000'000,
                           .totalMajorFaults = 1200,
                           .totalTasksCount = 4,
                           .ioBlockedTasksCount = 1,
                           .totalRssKb = 1000,
                           .totalPssKb = 865,
                           .processStatsByPid = {{1,
                                                  {/*comm=*/"init",
                                                   /*startTimeMillis=*/ticksToMillis(19),
                                                   /*cpuTimeMillis=*/ticksToMillis(80),
                                                   /*totalCpuCycles=*/1'160'000'000,
                                                   /*totalMajorFaults=*/1200,
                                                   /*totalTasksCount=*/4,
                                                   /*ioBlockedTasksCount=*/1, /*cpuCyclesByTid=*/
                                                   {{1, 60'000'000},
                                                    {367, 340'000'000},
                                                    {453, 360'000'000},
                                                    {589, 400'000'000}},
                                                   /*rssKb=*/1000,
                                                   /*pssKb=*/865,
                                                   /*ussKb=*/656,
                                                   /*swapPssKb=*/200}}}}},
             {10001234,
              UidProcStats{.cpuTimeMillis = ticksToMillis(40),
                           .cpuCycles = 420'000'000,
                           .totalMajorFaults = 54'604,
                           .totalTasksCount = 2,
                           .ioBlockedTasksCount = 0,
                           .totalRssKb = 7642,
                           .totalPssKb = 3947,
                           .processStatsByPid =
                                   {{1000,
                                     {/*comm=*/"system_server",
                                      /*startTimeMillis=*/ticksToMillis(1000),
                                      /*cpuTimeMillis=*/ticksToMillis(20),
                                      /*totalCpuCycles=*/60'000'000, /*totalMajorFaults=*/250,
                                      /*totalTasksCount=*/1, /*ioBlockedTasksCount=*/0,
                                      /*cpuCyclesByTid=*/{{1000, 60'000'000}},
                                      /*rssKb=*/2000, /*pssKb=*/1635, /*ussKb=*/1286,
                                      /*swapPssKb=*/600}},
                                    {2345,
                                     {/*comm=*/"logd", /*startTimeMillis=*/ticksToMillis(456),
                                      /*cpuTimeMillis=*/ticksToMillis(20),
                                      /*totalCpuCycles=*/360'000'000,
                                      /*totalMajorFaults=*/54'354,
                                      /*totalTasksCount=*/1, /*ioBlockedTasksCount=*/0,
                                      /*cpuCyclesByTid=*/{{2345, 360'000'000}},
                                      /*rssKb=*/5642, /*pssKb=*/2312, /*ussKb=*/944,
                                      /*swapPssKb=*/500}}}}}};
    applyFeatureFilter(&expected);

    TemporaryDir firstSnapshot;
    ASSERT_RESULT_OK(populateProcPidDir(firstSnapshot.path, pidToTids, perProcessStat,
                                        perProcessStatus, perProcessSmapsRollup,
                                        /*processStatm=*/{}, perThreadStat, perThreadTimeInState));

    UidProcStatsCollector collector(firstSnapshot.path, isSmapsRollupSupported(firstSnapshot.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << firstSnapshot.path << "` are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    auto actual = collector.deltaStats();

    EXPECT_THAT(actual, UnorderedPointwise(UidProcStatsByUidEq(), expected))
            << "First snapshot doesn't match.\nExpected:\n"
            << toString(expected) << "\nActual:\n"
            << toString(actual);

    pidToTids = {
            {1, {1, 589}},       // TID 589 reused by the same process.
            {367, {367, 2000}},  // TID 367 reused as a PID. PID 2000 reused as a TID.
            // PID 1000 reused as a new PID. TID 453 reused by a different PID.
            {1000, {1000, 453}},
    };

    perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 1800 0 60 60 0 0 0 0 2 0 19\n"},
            {367, "367 (system_server) R 1 0 0 0 0 0 0 0 100 0 30 30 0 0 0 0 2 0 3450\n"},
            {1000, "1000 (logd) R 1 0 0 0 0 0 0 0 2000 0 20 20 0 0 0 0 2 0 4650\n"},
    };

    perProcessStatus = {
            {1, pidStatusStr(1, 0)},
            {367, pidStatusStr(367, 10001234)},
            {1000, pidStatusStr(1000, 10001234)},
    };

    perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1500, /*pssKb=*/965, /*ussKb=*/756, /*swapPssKb=*/300)},
            {367,
             smapsRollupStr(/*rssKb=*/2000, /*pssKb=*/1635, /*ussKb=*/1286, /*swapPssKb=*/600)},
            {1000,
             smapsRollupStr(/*rssKb=*/5642, /*pssKb=*/2312, /*ussKb=*/944, /*swapPssKb=*/500)},
    };

    perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 500 0 20 20 0 0 0 0 2 0 19\n"},
            {589, "589 (init) S 0 0 0 0 0 0 0 0 300 0 10 10 0 0 0 0 2 0 2345\n"},
            {367, "367 (system_server) R 1 0 0 0 0 0 0 0 50 0 15 15 0 0 0 0 2 0 3450\n"},
            {2000, "2000 (system_server) R 1 0 0 0 0 0 0 0 50 0 15 15 0 0 0 0 2 0 3670\n"},
            {1000, "1000 (logd) R 1 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 2 0 4650\n"},
            {453, "453 (logd) D 1 0 0 0 0 0 0 0 1800 0 10 10 0 0 0 0 2 0 4770\n"},
    };

    perThreadTimeInState = {
            {1, "cpu0\n300000 20\n1700000 20\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
            {589, "cpu0\n300000 20\n1700000 0\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 20"},
            {367, "cpu0\n300000 0\n1700000 0\ncpu4\n710000 30\n1800000 0\ncpu7\n2000000 0"},
            {2000, "cpu0\n300000 0\n1700000 0\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 30"},
            {1000, "cpu0\n300000 0\n1700000 0\ncpu4\n710000 0\n1800000 20\ncpu7\n2000000 0"},
            {453, "cpu0\n300000 20\n1700000 0\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
    };

    expected = {{0,
                 UidProcStats{.cpuTimeMillis = ticksToMillis(40),
                              .cpuCycles = 400'000'000,
                              .totalMajorFaults = 600,
                              .totalTasksCount = 2,
                              .ioBlockedTasksCount = 0,
                              .totalRssKb = 1500,
                              .totalPssKb = 965,
                              .processStatsByPid =
                                      {{1,
                                        {/*comm=*/"init", /*startTimeMillis=*/ticksToMillis(19),
                                         /*cpuTimeMillis=*/ticksToMillis(40),
                                         /*totalCpuCycles=*/400'000'000, /*totalMajorFaults=*/600,
                                         /*totalTasksCount=*/2, /*ioBlockedTasksCount=*/0,
                                         /*cpuCyclesByTid=*/{{1, 340'000'000}, {589, 60'000'000}},
                                         /*rssKb=*/1500, /*pssKb=*/965, /*ussKb=*/756,
                                         /*swapPssKb=*/300}}}}},
                {10001234,
                 UidProcStats{.cpuTimeMillis = ticksToMillis(100),
                              .cpuCycles = 1'233'000'000,
                              .totalMajorFaults = 2100,
                              .totalTasksCount = 4,
                              .ioBlockedTasksCount = 1,
                              .totalRssKb = 7642,
                              .totalPssKb = 3947,
                              .processStatsByPid = {{367,
                                                     {/*comm=*/"system_server",
                                                      /*startTimeMillis=*/ticksToMillis(3450),
                                                      /*cpuTimeMillis=*/ticksToMillis(60),
                                                      /*totalCpuCycles=*/813'000'000,
                                                      /*totalMajorFaults=*/100,
                                                      /*totalTasksCount=*/2,
                                                      /*ioBlockedTasksCount=*/0, /*cpuCyclesByTid=*/
                                                      {{367, 213'000'000}, {2000, 600'000'000}},
                                                      /*rssKb=*/2000,
                                                      /*pssKb=*/1635,
                                                      /*ussKb=*/1286,
                                                      /*swapPssKb=*/600}},
                                                    {1000,
                                                     {/*comm=*/"logd",
                                                      /*startTimeMillis=*/ticksToMillis(4650),
                                                      /*cpuTimeMillis=*/ticksToMillis(40),
                                                      /*totalCpuCycles=*/420'000'000,
                                                      /*totalMajorFaults=*/2000,
                                                      /*totalTasksCount=*/2,
                                                      /*ioBlockedTasksCount=*/1, /*cpuCyclesByTid=*/
                                                      {{1000, 360'000'000}, {453, 60'000'000}},
                                                      /*rssKb=*/5642,
                                                      /*pssKb=*/2312,
                                                      /*ussKb=*/944,
                                                      /*swapPssKb=*/500}}}}}};
    applyFeatureFilter(&expected);

    TemporaryDir secondSnapshot;
    ASSERT_RESULT_OK(populateProcPidDir(secondSnapshot.path, pidToTids, perProcessStat,
                                        perProcessStatus, perProcessSmapsRollup,
                                        /*processStatm=*/{}, perThreadStat, perThreadTimeInState));

    collector.mPath = secondSnapshot.path;

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << secondSnapshot.path << "` are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    actual = collector.deltaStats();

    EXPECT_THAT(actual, UnorderedPointwise(UidProcStatsByUidEq(), expected))
            << "Second snapshot doesn't match.\nExpected:\n"
            << toString(expected) << "\nActual:\n"
            << toString(actual);
}

TEST(UidProcStatsCollectorTest, TestHandlesNoSmapsRollupKernelSupport) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
    };

    std::unordered_map<pid_t, std::string> perProcessStatm = {
            {1, "2969783 1481 938 530 0 5067 0"},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 0\n1700000 20\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
    };

    std::unordered_map<uid_t, UidProcStats> expected = {
            {0,
             UidProcStats{.cpuTimeMillis = ticksToMillis(20),
                          .cpuCycles = 340'000'000,
                          .totalMajorFaults = 200,
                          .totalTasksCount = 1,
                          .ioBlockedTasksCount = 0,
                          .totalRssKb = 5924,
                          .totalPssKb = 0,
                          .processStatsByPid = {
                                  {1,
                                   {/*comm=*/"init", /*startTimeMillis=*/ticksToMillis(19),
                                    /*cpuTimeMillis=*/ticksToMillis(20),
                                    /*totalCpuCycles=*/340'000'000,
                                    /*totalMajorFaults=*/200, /*totalTasksCount=*/1,
                                    /*ioBlockedTasksCount=*/0,
                                    /*cpuCyclesByTid=*/{{1, 340'000'000}},
                                    /*rssKb=*/5924, /*pssKb=*/0, /*ussKb=*/2172,
                                    /*swapPssKb=*/0}}}}}};
    applyFeatureFilter(&expected);

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        /*processSmapsRollup=*/{}, perProcessStatm, perThreadStat,
                                        perThreadTimeInState));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    auto actual = collector.deltaStats();

    EXPECT_THAT(actual, UnorderedPointwise(UidProcStatsByUidEq(), expected))
            << "Proc pid contents doesn't match.\nExpected:\n"
            << toString(expected) << "\nActual:\n"
            << toString(actual);
}

TEST(UidProcStatsCollectorTest, TestErrorOnCorruptedProcessStatFile) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 CORRUPTED DATA\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 0\n1700000 10\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
    };

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        perThreadTimeInState));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_FALSE(collector.collect().ok()) << "No error returned for invalid process stat file";
}

TEST(UidProcStatsCollectorTest, TestErrorOnCorruptedProcessStatusFile) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, "Pid:\t1\nTgid:\t1\nCORRUPTED DATA\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 0\n1700000 10\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
    };

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        perThreadTimeInState));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_FALSE(collector.collect().ok()) << "No error returned for invalid process status file";
}

TEST(UidProcStatsCollectorTest, TestErrorOnProcessStatusFileWithNoUid) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, "Pid:\t1\nTgid:\t1\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 0\n1700000 10\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
    };

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        perThreadTimeInState));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_FALSE(collector.collect().ok())
            << "No error returned for process status file without uid";
}

TEST(UidProcStatsCollectorTest, TestErrorOnProcessStatusFileWithNoTgid) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, "Pid:\t1\nUid:\t1\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 0\n1700000 10\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
    };

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        perThreadTimeInState));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_FALSE(collector.collect().ok())
            << "No error returned for process status file without tgid";
}

TEST(UidProcStatsCollectorTest, TestErrorOnCorruptedThreadStatFile) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1, 234}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 2 0 678\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 2 0 678\n"},
            {234, "234 (init) D 0 0 0 0 0 0 0 0 200 0 0 0 CORRUPTED DATA\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 0\n1700000 10\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
    };

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        perThreadTimeInState));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_FALSE(collector.collect().ok()) << "No error returned for invalid thread stat file";
}

TEST(UidProcStatsCollectorTest, TestErrorOnCorruptedThreadTimeInStateFile) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1, 234}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 2 0 678\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 2 0 678\n"},
            {234, "234 (init) D 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 2 0 500\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 0\n1700000 10\ncpu4\n710000 0\n1800000 0\n"},
            {234, "cpu0\n300000 0\n1700000 10\ncpu4\n710000 0\n1800000 CORRUPTED\n DATA"},
    };

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        perThreadTimeInState));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_FALSE(collector.collect().ok()) << "No error returned for invalid thread stat file";
}

TEST(UidProcStatsCollectorTest, TestHandlesSpaceInCommName) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1,
             "1 (random process name with space) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1,
             "1 (random process name with space) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 0\n1700000 20\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
    };

    std::unordered_map<uid_t, UidProcStats> expected = {
            {0,
             UidProcStats{.cpuTimeMillis = ticksToMillis(20),
                          .cpuCycles = 340'000'000,
                          .totalMajorFaults = 200,
                          .totalTasksCount = 1,
                          .ioBlockedTasksCount = 0,
                          .totalRssKb = 1000,
                          .totalPssKb = 865,
                          .processStatsByPid = {
                                  {1,
                                   {/*comm=*/"random process name with space",
                                    /*startTimeMillis=*/ticksToMillis(19),
                                    /*cpuTimeMillis=*/ticksToMillis(20),
                                    /*totalCpuCycles=*/340'000'000, /*totalMajorFaults=*/200,
                                    /*totalTasksCount=*/1, /*ioBlockedTasksCount=*/0,
                                    /*cpuCyclesByTid=*/{{1, 340'000'000}},
                                    /*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656,
                                    /*swapPssKb=*/200}}}}}};
    applyFeatureFilter(&expected);

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        perThreadTimeInState));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    auto actual = collector.deltaStats();

    EXPECT_THAT(actual, UnorderedPointwise(UidProcStatsByUidEq(), expected))
            << "Proc pid contents doesn't match.\nExpected:\n"
            << toString(expected) << "\nActual:\n"
            << toString(actual);
}

TEST(UidProcStatsCollectorTest, TestHandlesTimeInStateFileDisabledWithNoFile) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    // No time_in_state file present in procfs

    std::unordered_map<uid_t, UidProcStats> expected = {
            {0,
             UidProcStats{.cpuTimeMillis = ticksToMillis(20),
                          .cpuCycles = 0,
                          .totalMajorFaults = 200,
                          .totalTasksCount = 1,
                          .ioBlockedTasksCount = 0,
                          .totalRssKb = 1000,
                          .totalPssKb = 865,
                          .processStatsByPid = {
                                  {1,
                                   {/*comm=*/"init", /*startTimeMillis=*/ticksToMillis(19),
                                    /*cpuTimeMillis=*/ticksToMillis(20), /*totalCpuCycles=*/0,
                                    /*totalMajorFaults=*/200, /*totalTasksCount=*/1,
                                    /*ioBlockedTasksCount=*/0, /*cpuCyclesByTid=*/{},
                                    /*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656,
                                    /*swapPssKb=*/200}}}}}};

    applyFeatureFilter(&expected);

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        {}));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    auto actual = collector.deltaStats();

    EXPECT_THAT(actual, UnorderedPointwise(UidProcStatsByUidEq(), expected))
            << "Proc pid contents doesn't match.\nExpected:\n"
            << toString(expected) << "\nActual:\n"
            << toString(actual);
}

TEST(UidProcStatsCollectorTest, TestHandlesTimeInStateFileDisabledWithEmptyFile) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, ""},
    };

    std::unordered_map<uid_t, UidProcStats> expected = {
            {0,
             UidProcStats{.cpuTimeMillis = ticksToMillis(20),
                          .cpuCycles = 0,
                          .totalMajorFaults = 200,
                          .totalTasksCount = 1,
                          .ioBlockedTasksCount = 0,
                          .totalRssKb = 1000,
                          .totalPssKb = 865,
                          .processStatsByPid = {
                                  {1,
                                   {/*comm=*/"init", /*startTimeMillis=*/ticksToMillis(19),
                                    /*cpuTimeMillis=*/ticksToMillis(20), /*totalCpuCycles=*/0,
                                    /*totalMajorFaults=*/200, /*totalTasksCount=*/1,
                                    /*ioBlockedTasksCount=*/0, /*cpuCyclesByTid=*/{},
                                    /*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656,
                                    /*swapPssKb=*/200}}}}}};
    applyFeatureFilter(&expected);

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        {}));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    auto actual = collector.deltaStats();

    EXPECT_THAT(actual, UnorderedPointwise(UidProcStatsByUidEq(), expected))
            << "Proc pid contents doesn't match.\nExpected:\n"
            << toString(expected) << "\nActual:\n"
            << toString(actual);
}

TEST(UidProcStatsCollectorTest, TestHandlesTimeInStateFileDisabledWithZeroCpuCycles) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 0\n1700000 0\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
    };

    std::unordered_map<uid_t, UidProcStats> expected = {
            {0,
             UidProcStats{.cpuTimeMillis = ticksToMillis(20),
                          .cpuCycles = 0,
                          .totalMajorFaults = 200,
                          .totalTasksCount = 1,
                          .ioBlockedTasksCount = 0,
                          .totalRssKb = 1000,
                          .totalPssKb = 865,
                          .processStatsByPid = {
                                  {1,
                                   {/*comm=*/"init", /*startTimeMillis=*/ticksToMillis(19),
                                    /*cpuTimeMillis=*/ticksToMillis(20), /*totalCpuCycles=*/0,
                                    /*totalMajorFaults=*/200, /*totalTasksCount=*/1,
                                    /*ioBlockedTasksCount=*/0, /*cpuCyclesByTid=*/{},
                                    /*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656,
                                    /*swapPssKb=*/200}}}}}};
    applyFeatureFilter(&expected);

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        {}));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    auto actual = collector.deltaStats();

    EXPECT_THAT(actual, UnorderedPointwise(UidProcStatsByUidEq(), expected))
            << "Proc pid contents doesn't match.\nExpected:\n"
            << toString(expected) << "\nActual:\n"
            << toString(actual);
}

TEST(UidProcStatsCollectorTest, TestHandlesNoTimeInStateFileDuringCollection) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1, 234}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 210 0 15 15 0 0 0 0 2 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
    };

    std::unordered_map<pid_t, std::string> perProcessSmapsRollup = {
            {1, smapsRollupStr(/*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656, /*swapPssKb=*/200)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 2 0 19\n"},
            {234, "1 (init) S 0 0 0 0 0 0 0 0 10 0 5 5 0 0 0 0 2 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 0\n1700000 20\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
            // No time_in_state file present for TID 234
    };

    std::unordered_map<uid_t, UidProcStats> expected = {
            {0,
             UidProcStats{.cpuTimeMillis = ticksToMillis(30),
                          .cpuCycles = 340'000'000,
                          .totalMajorFaults = 210,
                          .totalTasksCount = 2,
                          .ioBlockedTasksCount = 0,
                          .totalRssKb = 1000,
                          .totalPssKb = 865,
                          .processStatsByPid = {
                                  {1,
                                   {/*comm=*/"init", /*startTimeMillis=*/ticksToMillis(19),
                                    /*cpuTimeMillis=*/ticksToMillis(30),
                                    /*totalCpuCycles=*/340'000'000, /*totalMajorFaults=*/210,
                                    /*totalTasksCount=*/2, /*ioBlockedTasksCount=*/0,
                                    /*cpuCyclesByTid=*/{{1, 340'000'000}},
                                    /*rssKb=*/1000, /*pssKb=*/865, /*ussKb=*/656,
                                    /*swapPssKb=*/200}}}}}};
    applyFeatureFilter(&expected);

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        perProcessSmapsRollup, /*processStatm=*/{}, perThreadStat,
                                        perThreadTimeInState));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_TRUE(collector.enabled())
            << "Files under the path `" << procDir.path << "` are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    auto actual = collector.deltaStats();

    EXPECT_THAT(actual, UnorderedPointwise(UidProcStatsByUidEq(), expected))
            << "Proc pid contents doesn't match.\nExpected:\n"
            << toString(expected) << "\nActual:\n"
            << toString(actual);
}

TEST(UidProcStatsCollectorTest, TestCollectorStatusOnMissingSmapsRollupAndStatmFiles) {
    std::unordered_map<pid_t, std::vector<pid_t>> pidToTids = {
            {1, {1}},
    };

    std::unordered_map<pid_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perProcessStatus = {
            {1, pidStatusStr(1, 0)},
    };

    std::unordered_map<pid_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 10 10 0 0 0 0 1 0 19\n"},
    };

    std::unordered_map<pid_t, std::string> perThreadTimeInState = {
            {1, "cpu0\n300000 0\n1700000 20\ncpu4\n710000 0\n1800000 0\ncpu7\n2000000 0"},
    };

    TemporaryDir procDir;
    ASSERT_RESULT_OK(populateProcPidDir(procDir.path, pidToTids, perProcessStat, perProcessStatus,
                                        /*processSmapsRollup=*/{}, /*processStatm=*/{},
                                        perThreadStat, perThreadTimeInState));

    UidProcStatsCollector collector(procDir.path, isSmapsRollupSupported(procDir.path));
    collector.init();

    ASSERT_EQ(!car_watchdog_memory_profiling(), collector.enabled())
            << "Collector status when memory profiling feature is "
            << (car_watchdog_memory_profiling() ? "enabled" : "disabled")
            << " and per-process smaps rollup / statm are missing";
}

TEST(UidProcStatsCollectorTest, TestUidProcStatsCollectorContentsFromDevice) {
    UidProcStatsCollector collector;
    collector.init();

    ASSERT_TRUE(collector.enabled()) << "/proc/[pid]/.* files are inaccessible";
    ASSERT_RESULT_OK(collector.collect());

    const auto& processStats = collector.deltaStats();

    // The below check should pass because there should be at least one process.
    EXPECT_GT(processStats.size(), static_cast<size_t>(0));
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
