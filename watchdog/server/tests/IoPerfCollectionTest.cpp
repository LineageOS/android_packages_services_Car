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

#include "IoPerfCollection.h"

#include <android-base/file.h>
#include <cutils/android_filesystem_config.h>

#include <algorithm>

#include "ProcPidDir.h"
#include "UidIoStats.h"
#include "gmock/gmock.h"

namespace android {
namespace automotive {
namespace watchdog {

using android::base::WriteStringToFile;
using testing::populateProcPidDir;

namespace {
bool isEqual(const UidIoPerfData& lhs, const UidIoPerfData& rhs) {
    if (lhs.topNReads.size() != rhs.topNReads.size() ||
        lhs.topNWrites.size() != rhs.topNWrites.size()) {
        return false;
    }
    for (int i = 0; i < METRIC_TYPES; ++i) {
        for (int j = 0; j < UID_STATES; ++j) {
            if (lhs.total[i][j] != rhs.total[i][j]) {
                return false;
            }
        }
    }
    auto comp = [&](const UidIoPerfData::Stats& l, const UidIoPerfData::Stats& r) -> bool {
        bool isEqual = l.userId == r.userId && l.packageName == r.packageName;
        for (int i = 0; i < UID_STATES; ++i) {
            isEqual &= l.bytes[i] == r.bytes[i] && l.fsync[i] == r.fsync[i];
        }
        return isEqual;
    };
    return std::equal(lhs.topNReads.begin(), lhs.topNReads.end(), rhs.topNReads.begin(), comp) &&
            std::equal(lhs.topNWrites.begin(), lhs.topNWrites.end(), rhs.topNWrites.begin(), comp);
}

bool isEqual(const SystemIoPerfData& lhs, const SystemIoPerfData& rhs) {
    return lhs.cpuIoWaitTime == rhs.cpuIoWaitTime && lhs.totalCpuTime == rhs.totalCpuTime &&
            lhs.ioBlockedProcessesCnt == rhs.ioBlockedProcessesCnt &&
            lhs.totalProcessesCnt == rhs.totalProcessesCnt;
}

bool isEqual(const ProcessIoPerfData& lhs, const ProcessIoPerfData& rhs) {
    if (lhs.topNIoBlockedUids.size() != rhs.topNIoBlockedUids.size() ||
        lhs.topNMajorFaults.size() != rhs.topNMajorFaults.size() ||
        lhs.totalMajorFaults != rhs.totalMajorFaults ||
        lhs.majorFaultsPercentChange != rhs.majorFaultsPercentChange) {
        return false;
    }
    auto comp = [&](const ProcessIoPerfData::Stats& l, const ProcessIoPerfData::Stats& r) -> bool {
        return l.userId == r.userId && l.packageName == r.packageName && l.count == r.count;
    };
    return std::equal(lhs.topNIoBlockedUids.begin(), lhs.topNIoBlockedUids.end(),
                      rhs.topNIoBlockedUids.begin(), comp) &&
            std::equal(lhs.topNIoBlockedUidsTotalTaskCnt.begin(),
                       lhs.topNIoBlockedUidsTotalTaskCnt.end(),
                       rhs.topNIoBlockedUidsTotalTaskCnt.begin()) &&
            std::equal(lhs.topNMajorFaults.begin(), lhs.topNMajorFaults.end(),
                       rhs.topNMajorFaults.begin(), comp);
}

}  // namespace

TEST(IoPerfCollectionTest, TestValidUidIoStatFile) {
    // Format: uid fgRdChar fgWrChar fgRdBytes fgWrBytes bgRdChar bgWrChar bgRdBytes bgWrBytes
    // fgFsync bgFsync
    constexpr char firstSnapshot[] =
        "1001234 5000 1000 3000 500 0 0 0 0 20 0\n"
        "1005678 500 100 30 50 300 400 100 200 45 60\n"
        "1009 0 0 0 0 40000 50000 20000 30000 0 300\n"
        "1001000 4000 3000 2000 1000 400 300 200 100 50 10\n";

    struct UidIoPerfData expectedUidIoPerfData = {};
    expectedUidIoPerfData.total[READ_BYTES][FOREGROUND] = 5030;
    expectedUidIoPerfData.total[READ_BYTES][BACKGROUND] = 20300;
    expectedUidIoPerfData.total[WRITE_BYTES][FOREGROUND] = 1550;
    expectedUidIoPerfData.total[WRITE_BYTES][BACKGROUND] = 30300;
    expectedUidIoPerfData.total[FSYNC_COUNT][FOREGROUND] = 115;
    expectedUidIoPerfData.total[FSYNC_COUNT][BACKGROUND] = 370;
    expectedUidIoPerfData.topNReads.push_back({
            // uid: 1009
            .userId = 0,
            .packageName = "mount",
            .bytes = {0, 20000},
            .fsync = {0, 300},
    });
    expectedUidIoPerfData.topNReads.push_back({
            // uid: 1001234
            .userId = 10,
            .packageName = "1001234",
            .bytes = {3000, 0},
            .fsync = {20, 0},
    });
    expectedUidIoPerfData.topNWrites.push_back({
            // uid: 1009
            .userId = 0,
            .packageName = "mount",
            .bytes = {0, 30000},
            .fsync = {0, 300},
    });
    expectedUidIoPerfData.topNWrites.push_back({
            // uid: 1001000
            .userId = 10,
            .packageName = "shared:android.uid.system",
            .bytes = {1000, 100},
            .fsync = {50, 10},
    });

    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile(firstSnapshot, tf.path));

    IoPerfCollection collector(tf.path);
    collector.mTopNStatsPerCategory = 2;
    ASSERT_TRUE(collector.mUidIoStats.enabled()) << "Temporary file is inaccessible";

    struct UidIoPerfData actualUidIoPerfData = {};
    auto ret = collector.collectUidIoPerfDataLocked(&actualUidIoPerfData);
    ASSERT_RESULT_OK(ret);
    EXPECT_TRUE(isEqual(expectedUidIoPerfData, actualUidIoPerfData))
        << "First snapshot doesn't match.\nExpected:\n"
        << toString(expectedUidIoPerfData) << "\nActual:\n"
        << toString(actualUidIoPerfData);

    constexpr char secondSnapshot[] =
        "1001234 10000 2000 7000 950 0 0 0 0 45 0\n"
        "1005678 600 100 40 50 1000 1000 1000 600 50 70\n"
        "1003456 300 500 200 300 0 0 0 0 50 0\n"
        "1001000 400 300 200 100 40 30 20 10 5 1\n";

    expectedUidIoPerfData = {};
    expectedUidIoPerfData.total[READ_BYTES][FOREGROUND] = 4210;
    expectedUidIoPerfData.total[READ_BYTES][BACKGROUND] = 900;
    expectedUidIoPerfData.total[WRITE_BYTES][FOREGROUND] = 750;
    expectedUidIoPerfData.total[WRITE_BYTES][BACKGROUND] = 400;
    expectedUidIoPerfData.total[FSYNC_COUNT][FOREGROUND] = 80;
    expectedUidIoPerfData.total[FSYNC_COUNT][BACKGROUND] = 10;
    expectedUidIoPerfData.topNReads.push_back({
            // uid: 1001234
            .userId = 10,
            .packageName = "1001234",
            .bytes = {4000, 0},
            .fsync = {25, 0},
    });
    expectedUidIoPerfData.topNReads.push_back({
            // uid: 1005678
            .userId = 10,
            .packageName = "1005678",
            .bytes = {10, 900},
            .fsync = {5, 10},
    });
    expectedUidIoPerfData.topNWrites.push_back({
            // uid: 1001234
            .userId = 10,
            .packageName = "1001234",
            .bytes = {450, 0},
            .fsync = {25, 0},
    });
    expectedUidIoPerfData.topNWrites.push_back({
            // uid: 1005678
            .userId = 10,
            .packageName = "1005678",
            .bytes = {0, 400},
            .fsync = {5, 10},
    });
    ASSERT_TRUE(WriteStringToFile(secondSnapshot, tf.path));
    actualUidIoPerfData = {};
    ret = collector.collectUidIoPerfDataLocked(&actualUidIoPerfData);
    ASSERT_RESULT_OK(ret);
    EXPECT_TRUE(isEqual(expectedUidIoPerfData, actualUidIoPerfData))
        << "Second snapshot doesn't match.\nExpected:\n"
        << toString(expectedUidIoPerfData) << "\nActual:\n"
        << toString(actualUidIoPerfData);
}

TEST(IoPerfCollectionTest, TestUidIOStatsLessThanTopNStatsLimit) {
    // Format: uid fgRdChar fgWrChar fgRdBytes fgWrBytes bgRdChar bgWrChar bgRdBytes bgWrBytes
    // fgFsync bgFsync
    constexpr char contents[] = "1001234 5000 1000 3000 500 0 0 0 0 20 0\n";

    struct UidIoPerfData expectedUidIoPerfData = {};
    expectedUidIoPerfData.total[READ_BYTES][FOREGROUND] = 3000;
    expectedUidIoPerfData.total[READ_BYTES][BACKGROUND] = 0;
    expectedUidIoPerfData.total[WRITE_BYTES][FOREGROUND] = 500;
    expectedUidIoPerfData.total[WRITE_BYTES][BACKGROUND] = 0;
    expectedUidIoPerfData.total[FSYNC_COUNT][FOREGROUND] = 20;
    expectedUidIoPerfData.total[FSYNC_COUNT][BACKGROUND] = 0;
    expectedUidIoPerfData.topNReads.push_back({
            // uid: 1001234
            .userId = 10,
            .packageName = "1001234",
            .bytes = {3000, 0},
            .fsync = {20, 0},
    });
    expectedUidIoPerfData.topNWrites.push_back({
            // uid: 1001234
            .userId = 10,
            .packageName = "1001234",
            .bytes = {500, 0},
            .fsync = {20, 0},
    });

    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile(contents, tf.path));

    IoPerfCollection collector(tf.path);
    collector.mTopNStatsPerCategory = 10;
    ASSERT_TRUE(collector.mUidIoStats.enabled()) << "Temporary file is inaccessible";

    struct UidIoPerfData actualUidIoPerfData = {};
    const auto& ret = collector.collectUidIoPerfDataLocked(&actualUidIoPerfData);
    ASSERT_RESULT_OK(ret);
    EXPECT_TRUE(isEqual(expectedUidIoPerfData, actualUidIoPerfData))
        << "Collected data doesn't match.\nExpected:\n"
        << toString(expectedUidIoPerfData) << "\nActual:\n"
        << toString(actualUidIoPerfData);
}

TEST(IoPerfCollectionTest, TestProcUidIoStatsContentsFromDevice) {
    // TODO(b/148486340): Enable the test after appropriate SELinux privileges are available to
    // read the proc file.
    /*IoPerfCollection collector;
    ASSERT_TRUE(collector.mUidIoStats.enabled()) << "/proc/uid_io/stats file is inaccessible";

    struct UidIoPerfData perfData = {};
    const auto& ret = collector.collectUidIoPerfDataLocked(&perfData);
    ASSERT_RESULT_OK(ret);
    // The below check should pass because the /proc/uid_io/stats file should have at least
    // |mTopNStatsPerCategory| entries since bootup.
    EXPECT_EQ(perfData.topNReads.size(), collector.mTopNStatsPerCategory);
    EXPECT_EQ(perfData.topNWrites.size(), collector.mTopNStatsPerCategory);

    int numMappedAppUid = 0;
    int numMappedSysUid = 0;
    for (const auto& it : collector.mUidToPackageNameMapping)  {
        if (it.first >= AID_APP_START) {
            ++numMappedAppUid;
        } else {
            ++numMappedSysUid;
        }
    }
    EXPECT_GT(numMappedAppUid, 0);
    EXPECT_GT(numMappedSysUid, 0);*/
}

TEST(IoPerfCollectionTest, TestValidProcStatFile) {
    constexpr char firstSnapshot[] =
            "cpu  6200 5700 1700 3100 1100 5200 3900 0 0 0\n"
            "cpu0 2400 2900 600 690 340 4300 2100 0 0 0\n"
            "cpu1 1900 2380 510 760 51 370 1500 0 0 0\n"
            "cpu2 900 400 400 1000 600 400 160 0 0 0\n"
            "cpu3 1000 20 190 650 109 130 140 0 0 0\n"
            "intr 694351583 0 0 0 297062868 0 5922464 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 "
            "0 0\n"
            // Skipped most of the intr line as it is not important for testing the ProcStat parsing
            // logic.
            "ctxt 579020168\n"
            "btime 1579718450\n"
            "processes 113804\n"
            "procs_running 17\n"
            "procs_blocked 5\n"
            "softirq 33275060 934664 11958403 5111 516325 200333 0 341482 10651335 0 8667407\n";
    struct SystemIoPerfData expectedSystemIoPerfData = {
            .cpuIoWaitTime = 1100,
            .totalCpuTime = 26900,
            .ioBlockedProcessesCnt = 5,
            .totalProcessesCnt = 22,
    };

    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile(firstSnapshot, tf.path));

    IoPerfCollection collector("", tf.path);
    ASSERT_TRUE(collector.mProcStat.enabled()) << "Temporary file is inaccessible";

    struct SystemIoPerfData actualSystemIoPerfData = {};
    auto ret = collector.collectSystemIoPerfDataLocked(&actualSystemIoPerfData);
    ASSERT_RESULT_OK(ret);
    EXPECT_TRUE(isEqual(expectedSystemIoPerfData, actualSystemIoPerfData))
            << "First snapshot doesn't match.\nExpected:\n"
            << toString(expectedSystemIoPerfData) << "\nActual:\n"
            << toString(actualSystemIoPerfData);

    constexpr char secondSnapshot[] =
            "cpu  16200 8700 2000 4100 2200 6200 5900 0 0 0\n"
            "cpu0 4400 3400 700 890 800 4500 3100 0 0 0\n"
            "cpu1 5900 3380 610 960 100 670 2000 0 0 0\n"
            "cpu2 2900 1000 450 1400 800 600 460 0 0 0\n"
            "cpu3 3000 920 240 850 500 430 340 0 0 0\n"
            "intr 694351583 0 0 0 297062868 0 5922464 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 "
            "0 0\n"
            "ctxt 579020168\n"
            "btime 1579718450\n"
            "processes 113804\n"
            "procs_running 10\n"
            "procs_blocked 2\n"
            "softirq 33275060 934664 11958403 5111 516325 200333 0 341482 10651335 0 8667407\n";
    expectedSystemIoPerfData = {
            .cpuIoWaitTime = 1100,
            .totalCpuTime = 18400,
            .ioBlockedProcessesCnt = 2,
            .totalProcessesCnt = 12,
    };

    ASSERT_TRUE(WriteStringToFile(secondSnapshot, tf.path));
    actualSystemIoPerfData = {};
    ret = collector.collectSystemIoPerfDataLocked(&actualSystemIoPerfData);
    ASSERT_RESULT_OK(ret);
    EXPECT_TRUE(isEqual(expectedSystemIoPerfData, actualSystemIoPerfData))
            << "Second snapshot doesn't match.\nExpected:\n"
            << toString(expectedSystemIoPerfData) << "\nActual:\n"
            << toString(actualSystemIoPerfData);
}

TEST(IoPerfCollectionTest, TestValidProcPidContents) {
    std::unordered_map<uint32_t, std::vector<uint32_t>> pidToTids = {
            {1, {1, 453}},
            {2546, {2546, 3456, 4789}},
            {7890, {7890, 8978, 12890}},
            {18902, {18902, 21345, 32452}},
            {28900, {28900}},
    };
    std::unordered_map<uint32_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 220 0 0 0 0 0 0 0 2 0 0\n"},
            {2546, "2546 (system_server) R 1 0 0 0 0 0 0 0 6000 0 0 0 0 0 0 0 3 0 1000\n"},
            {7890, "7890 (logd) D 1 0 0 0 0 0 0 0 15000 0 0 0 0 0 0 0 3 0 2345\n"},
            {18902, "18902 (disk I/O) D 1 0 0 0 0 0 0 0 45678 0 0 0 0 0 0 0 3 0 897654\n"},
            {28900, "28900 (tombstoned) D 1 0 0 0 0 0 0 0 89765 0 0 0 0 0 0 0 3 0 2345671\n"},
    };
    std::unordered_map<uint32_t, std::string> perProcessStatus = {
            {1, "Pid:\t1\nTgid:\t1\nUid:\t0\t0\t0\t0\n"},
            {2546, "Pid:\t2546\nTgid:\t2546\nUid:\t1001000\t1001000\t1001000\t1001000\n"},
            {7890, "Pid:\t7890\nTgid:\t7890\nUid:\t1001000\t1001000\t1001000\t1001000\n"},
            {18902, "Pid:\t18902\nTgid:\t18902\nUid:\t1009\t1009\t1009\t1009\n"},
            {28900, "Pid:\t28900\nTgid:\t28900\nUid:\t1001234\t1001234\t1001234\t1001234\n"},
    };
    std::unordered_map<uint32_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0 2 0 0\n"},
            {453, "453 (init) S 0 0 0 0 0 0 0 0 20 0 0 0 0 0 0 0 2 0 275\n"},
            {2546, "2546 (system_server) R 1 0 0 0 0 0 0 0 1000 0 0 0 0 0 0 0 3 0 1000\n"},
            {3456, "3456 (system_server) S 1 0 0 0 0 0 0 0 3000 0 0 0 0 0 0 0 3 0 2300\n"},
            {4789, "4789 (system_server) D 1 0 0 0 0 0 0 0 2000 0 0 0 0 0 0 0 3 0 4500\n"},
            {7890, "7890 (logd) D 1 0 0 0 0 0 0 0 10000 0 0 0 0 0 0 0 3 0 2345\n"},
            {8978, "8978 (logd) D 1 0 0 0 0 0 0 0 1000 0 0 0 0 0 0 0 3 0 2500\n"},
            {12890, "12890 (logd) D 1 0 0 0 0 0 0 0 500 0 0 0 0 0 0 0 3 0 2900\n"},
            {18902, "18902 (disk I/O) D 1 0 0 0 0 0 0 0 30000 0 0 0 0 0 0 0 3 0 897654\n"},
            {21345, "21345 (disk I/O) D 1 0 0 0 0 0 0 0 15000 0 0 0 0 0 0 0 3 0 904000\n"},
            {32452, "32452 (disk I/O) D 1 0 0 0 0 0 0 0 678 0 0 0 0 0 0 0 3 0 1007000\n"},
            {28900, "28900 (tombstoned) D 1 0 0 0 0 0 0 0 89765 0 0 0 0 0 0 0 3 0 2345671\n"},
    };
    struct ProcessIoPerfData expectedProcessIoPerfData = {};
    expectedProcessIoPerfData.topNIoBlockedUids.push_back({
            // uid: 1001000
            .userId = 10,
            .packageName = "shared:android.uid.system",
            .count = 4,
    });
    expectedProcessIoPerfData.topNIoBlockedUidsTotalTaskCnt.push_back(6);
    expectedProcessIoPerfData.topNIoBlockedUids.push_back({
            // uid: 1009
            .userId = 0,
            .packageName = "mount",
            .count = 3,
    });
    expectedProcessIoPerfData.topNIoBlockedUidsTotalTaskCnt.push_back(3);
    expectedProcessIoPerfData.topNMajorFaults.push_back({
            // uid: 1001234
            .userId = 10,
            .packageName = "1001234",
            .count = 89765,
    });
    expectedProcessIoPerfData.topNMajorFaults.push_back({
            // uid: 1009
            .userId = 0,
            .packageName = "mount",
            .count = 45678,
    });
    expectedProcessIoPerfData.totalMajorFaults = 156663;
    expectedProcessIoPerfData.majorFaultsPercentChange = 0;

    TemporaryDir firstSnapshot;
    auto ret = populateProcPidDir(firstSnapshot.path, pidToTids, perProcessStat, perProcessStatus,
                                  perThreadStat);
    ASSERT_TRUE(ret) << "Failed to populate proc pid dir: " << ret.error();

    IoPerfCollection collector("", "", firstSnapshot.path);
    collector.mTopNStatsPerCategory = 2;
    ASSERT_TRUE(collector.mProcPidStat.enabled())
            << "Files under the temporary proc directory are inaccessible";

    struct ProcessIoPerfData actualProcessIoPerfData = {};
    ret = collector.collectProcessIoPerfDataLocked(&actualProcessIoPerfData);
    ASSERT_TRUE(ret) << "Failed to collect first snapshot: " << ret.error();
    EXPECT_TRUE(isEqual(expectedProcessIoPerfData, actualProcessIoPerfData))
            << "First snapshot doesn't match.\nExpected:\n"
            << toString(expectedProcessIoPerfData) << "\nActual:\n"
            << toString(actualProcessIoPerfData);

    pidToTids = {
            {1, {1, 453}},
            {2546, {2546, 3456, 4789}},
    };
    perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 880 0 0 0 0 0 0 0 2 0 0\n"},
            {2546, "2546 (system_server) R 1 0 0 0 0 0 0 0 18000 0 0 0 0 0 0 0 3 0 1000\n"},
    };
    perProcessStatus = {
            {1, "Pid:\t1\nTgid:\t1\nUid:\t0\t0\t0\t0\n"},
            {2546, "Pid:\t2546\nTgid:\t2546\nUid:\t1001000\t1001000\t1001000\t1001000\n"},
    };
    perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 800 0 0 0 0 0 0 0 2 0 0\n"},
            {453, "453 (init) S 0 0 0 0 0 0 0 0 80 0 0 0 0 0 0 0 2 0 275\n"},
            {2546, "2546 (system_server) R 1 0 0 0 0 0 0 0 3000 0 0 0 0 0 0 0 3 0 1000\n"},
            {3456, "3456 (system_server) S 1 0 0 0 0 0 0 0 9000 0 0 0 0 0 0 0 3 0 2300\n"},
            {4789, "4789 (system_server) D 1 0 0 0 0 0 0 0 6000 0 0 0 0 0 0 0 3 0 4500\n"},
    };
    expectedProcessIoPerfData = {};
    expectedProcessIoPerfData.topNIoBlockedUids.push_back({
            // uid: 1001000
            .userId = 10,
            .packageName = "shared:android.uid.system",
            .count = 1,
    });
    expectedProcessIoPerfData.topNIoBlockedUidsTotalTaskCnt.push_back(3);
    expectedProcessIoPerfData.topNMajorFaults.push_back({
            // uid: 1001000
            .userId = 10,
            .packageName = "shared:android.uid.system",
            .count = 12000,
    });
    expectedProcessIoPerfData.topNMajorFaults.push_back({
            // uid: 0
            .userId = 0,
            .packageName = "root",
            .count = 660,
    });
    expectedProcessIoPerfData.totalMajorFaults = 12660;
    expectedProcessIoPerfData.majorFaultsPercentChange = ((12660.0 - 156663.0) / 156663.0) * 100;

    TemporaryDir secondSnapshot;
    ret = populateProcPidDir(secondSnapshot.path, pidToTids, perProcessStat, perProcessStatus,
                             perThreadStat);
    ASSERT_TRUE(ret) << "Failed to populate proc pid dir: " << ret.error();

    collector.mProcPidStat.mPath = secondSnapshot.path;

    actualProcessIoPerfData = {};
    ret = collector.collectProcessIoPerfDataLocked(&actualProcessIoPerfData);
    ASSERT_TRUE(ret) << "Failed to collect second snapshot: " << ret.error();
    EXPECT_TRUE(isEqual(expectedProcessIoPerfData, actualProcessIoPerfData))
            << "Second snapshot doesn't match.\nExpected:\n"
            << toString(expectedProcessIoPerfData) << "\nActual:\n"
            << toString(actualProcessIoPerfData);
}

TEST(IoPerfCollectionTest, TestProcPidContentsLessThanTopNStatsLimit) {
    std::unordered_map<uint32_t, std::vector<uint32_t>> pidToTids = {
            {1, {1, 453}},
    };
    std::unordered_map<uint32_t, std::string> perProcessStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 880 0 0 0 0 0 0 0 2 0 0\n"},
    };
    std::unordered_map<uint32_t, std::string> perProcessStatus = {
            {1, "Pid:\t1\nTgid:\t1\nUid:\t0\t0\t0\t0\n"},
    };
    std::unordered_map<uint32_t, std::string> perThreadStat = {
            {1, "1 (init) S 0 0 0 0 0 0 0 0 800 0 0 0 0 0 0 0 2 0 0\n"},
            {453, "453 (init) S 0 0 0 0 0 0 0 0 80 0 0 0 0 0 0 0 2 0 275\n"},
    };
    struct ProcessIoPerfData expectedProcessIoPerfData = {};
    expectedProcessIoPerfData.topNMajorFaults.push_back({
            // uid: 0
            .userId = 0,
            .packageName = "root",
            .count = 880,
    });
    expectedProcessIoPerfData.totalMajorFaults = 880;
    expectedProcessIoPerfData.majorFaultsPercentChange = 0.0;

    TemporaryDir prodDir;
    auto ret = populateProcPidDir(prodDir.path, pidToTids, perProcessStat, perProcessStatus,
                                  perThreadStat);
    ASSERT_TRUE(ret) << "Failed to populate proc pid dir: " << ret.error();

    IoPerfCollection collector("", "", prodDir.path);
    struct ProcessIoPerfData actualProcessIoPerfData = {};
    ret = collector.collectProcessIoPerfDataLocked(&actualProcessIoPerfData);
    ASSERT_TRUE(ret) << "Failed to collect proc pid contents: " << ret.error();
    EXPECT_TRUE(isEqual(expectedProcessIoPerfData, actualProcessIoPerfData))
            << "proc pid contents don't match.\nExpected:\n"
            << toString(expectedProcessIoPerfData) << "\nActual:\n"
            << toString(actualProcessIoPerfData);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
