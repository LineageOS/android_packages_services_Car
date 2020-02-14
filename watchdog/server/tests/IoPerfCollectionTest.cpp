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

#include "UidIoStats.h"
#include "gmock/gmock.h"

namespace android {
namespace automotive {
namespace watchdog {

using ::android::base::WriteStringToFile;

namespace {
bool isEqual(const UidIoPerfData& lhs, const UidIoPerfData& rhs) {
    if (lhs.topNReads.size() != rhs.topNReads.size() ||
        lhs.topNWrites.size() != rhs.topNWrites.size()) {
        return false;
    }
    return std::equal(lhs.topNReads.begin(), lhs.topNReads.end(), rhs.topNReads.begin(),
                      [&](const UidIoPerfData::Stats& l, const UidIoPerfData::Stats& r) -> bool {
                          bool isEqual = l.userId == r.userId && l.packageName == r.packageName;
                          for (int i = 0; i < UID_STATES; ++i) {
                              isEqual &= l.bytes[i] == r.bytes[i] &&
                                         l.bytesPercent[i] == r.bytesPercent[i] &&
                                         l.fsync[i] == r.fsync[i] &&
                                         l.fsyncPercent[i] == r.fsyncPercent[i];
                          }
                          return isEqual;
                      });
}

bool isEqual(const SystemIoPerfData& lhs, const SystemIoPerfData& rhs) {
    return lhs.cpuIoWaitTime == rhs.cpuIoWaitTime && lhs.cpuIoWaitPercent == rhs.cpuIoWaitPercent &&
            lhs.ioBlockedProcessesCnt == rhs.ioBlockedProcessesCnt &&
            lhs.ioBlockedProcessesPercent == rhs.ioBlockedProcessesPercent;
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
    expectedUidIoPerfData.topNReads.push_back({
        // uid: 1009
        .userId = 0,
        .packageName = "mount",
        .bytes = {0, 20000},
        .bytesPercent = {0, (20000.0 / 20300.0) * 100},
        .fsync = {0, 300},
        .fsyncPercent = {0, (300.0 / 370.0) * 100},
    });
    expectedUidIoPerfData.topNReads.push_back({
        // uid: 1001234
        .userId = 10,
        .packageName = "1001234",
        .bytes = {3000, 0},
        .bytesPercent = {(3000.0 / 5030.0) * 100, 0},
        .fsync = {20, 0},
        .fsyncPercent = {(20.0 / 115.0) * 100, 0},
    });
    expectedUidIoPerfData.topNWrites.push_back({
        // uid: 1009
        .userId = 0,
        .packageName = "mount",
        .bytes = {0, 20000},
        .bytesPercent = {0, (30000.0 / 30300.0) * 100},
        .fsync = {0, 300},
        .fsyncPercent = {0, (300.0 / 370.0) * 100},
    });
    expectedUidIoPerfData.topNWrites.push_back({
        // uid: 1001000
        .userId = 10,
        .packageName = "shared:android.uid.system",
        .bytes = {1000, 100},
        .bytesPercent = {(1000.0 / 1550.0) * 100, (100.0 / 30300.0) * 100},
        .fsync = {50, 10},
        .fsyncPercent = {(50.0 / 115.0) * 100, (10.0 / 370.0) * 100},
    });

    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile(firstSnapshot, tf.path));

    IoPerfCollection collector(tf.path, "");
    collector.mTopNStatsPerCategory = 2;
    ASSERT_TRUE(collector.mUidIoStats.enabled()) << "Temporary file is inaccessible";

    struct UidIoPerfData actualUidIoPerfData = {};
    auto ret = collector.collectUidIoPerfDataLocked(&actualUidIoPerfData);
    ASSERT_TRUE(ret) << "Failed to collect first snapshot: " << ret.error();
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
    expectedUidIoPerfData.topNReads.push_back({
        // uid: 1001234
        .userId = 10,
        .packageName = "1001234",
        .bytes = {4000, 0},
        .bytesPercent = {(4000.0 / 4210.0) * 100, 0},
        .fsync = {25, 0},
        .fsyncPercent = {(25.0 / 80.0) * 100, 0},
    });
    expectedUidIoPerfData.topNReads.push_back({
        // uid: 1005678
        .userId = 10,
        .packageName = "1005678",
        .bytes = {10, 900},
        .bytesPercent = {(10.0 / 4210.0) * 100, (900.0 / 900.0) * 100},
        .fsync = {5, 10},
        .fsyncPercent = {(5.0 / 80.0) * 100, (10.0 / 10.0) * 100},
    });
    expectedUidIoPerfData.topNWrites.push_back({
        // uid: 1001234
        .userId = 0,
        .packageName = "1001234",
        .bytes = {450, 0},
        .bytesPercent = {(450.0 / 750.0) * 100, 0},
        .fsync = {25, 0},
        .fsyncPercent = {(25.0 / 80.0) * 100, 0},
    });
    expectedUidIoPerfData.topNWrites.push_back({
        // uid: 1005678
        .userId = 10,
        .packageName = "1005678",
        .bytes = {0, 400},
        .bytesPercent = {0, (400.0 / 400.0) * 100},
        .fsync = {5, 10},
        .fsyncPercent = {(5.0 / 80.0) * 100, (10.0 / 10.0) * 100},
    });
    ASSERT_TRUE(WriteStringToFile(secondSnapshot, tf.path));
    actualUidIoPerfData = {};
    ret = collector.collectUidIoPerfDataLocked(&actualUidIoPerfData);
    ASSERT_TRUE(ret) << "Failed to collect second snapshot: " << ret.error();
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
    expectedUidIoPerfData.topNReads.push_back({
        // uid: 1001234
        .userId = 10,
        .packageName = "1001234",
        .bytes = {3000, 0},
        .bytesPercent = {100, 0},
        .fsync = {20, 0},
        .fsyncPercent = {100, 0},
    });
    expectedUidIoPerfData.topNWrites.push_back({
        // uid: 1001234
        .userId = 10,
        .packageName = "1001234",
        .bytes = {500, 0},
        .bytesPercent = {0, 100},
        .fsync = {20, 0},
        .fsyncPercent = {100, 0},
    });

    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile(contents, tf.path));

    IoPerfCollection collector(tf.path, "");
    collector.mTopNStatsPerCategory = 10;
    ASSERT_TRUE(collector.mUidIoStats.enabled()) << "Temporary file is inaccessible";

    struct UidIoPerfData actualUidIoPerfData = {};
    const auto& ret = collector.collectUidIoPerfDataLocked(&actualUidIoPerfData);
    ASSERT_TRUE(ret) << "Failed to collect uid I/O stats: " << ret.error();
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
    ASSERT_TRUE(ret) << "Failed to collect uid I/O stats: " << ret.error();
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
            .cpuIoWaitPercent = (1100.0 / 26900.0) * 100,
            .ioBlockedProcessesCnt = 5,
            .ioBlockedProcessesPercent = (5.0 / 22.0) * 100,
    };

    TemporaryFile tf;
    ASSERT_NE(tf.fd, -1);
    ASSERT_TRUE(WriteStringToFile(firstSnapshot, tf.path));

    IoPerfCollection collector("", tf.path);
    ASSERT_TRUE(collector.mProcStat.enabled()) << "Temporary file is inaccessible";

    struct SystemIoPerfData actualSystemIoPerfData = {};
    auto ret = collector.collectSystemIoPerfDataLocked(&actualSystemIoPerfData);
    ASSERT_TRUE(ret) << "Failed to collect first snapshot: " << ret.error();
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
            .cpuIoWaitPercent = (1100.0 / 18400.0) * 100,
            .ioBlockedProcessesCnt = 2,
            .ioBlockedProcessesPercent = (2.0 / 12.0) * 100,
    };

    ASSERT_TRUE(WriteStringToFile(secondSnapshot, tf.path));
    actualSystemIoPerfData = {};
    ret = collector.collectSystemIoPerfDataLocked(&actualSystemIoPerfData);
    ASSERT_TRUE(ret) << "Failed to collect second snapshot: " << ret.error();
    EXPECT_TRUE(isEqual(expectedSystemIoPerfData, actualSystemIoPerfData))
            << "Second snapshot doesn't match.\nExpected:\n"
            << toString(expectedSystemIoPerfData) << "\nActual:\n"
            << toString(actualSystemIoPerfData);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
