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

#include "MockProcStatCollector.h"
#include "MockUidStatsCollector.h"
#include "MockWatchdogServiceHelper.h"
#include "PackageInfoTestUtils.h"
#include "PerformanceProfiler.h"

#include <WatchdogProperties.sysprop.h>
#include <android-base/file.h>
#include <gmock/gmock.h>
#include <utils/RefBase.h>

#include <sys/types.h>
#include <unistd.h>

#include <string>
#include <type_traits>
#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

namespace {

using ::aidl::android::automotive::watchdog::internal::ResourceStats;
using ::aidl::android::automotive::watchdog::internal::ResourceUsageStats;
using ::aidl::android::automotive::watchdog::internal::UidResourceUsageStats;
using ::android::RefBase;
using ::android::sp;
using ::android::base::ReadFdToString;
using ::android::base::Result;
using ::testing::_;
using ::testing::AllOf;
using ::testing::ElementsAreArray;
using ::testing::Eq;
using ::testing::ExplainMatchResult;
using ::testing::Field;
using ::testing::IsSubsetOf;
using ::testing::Matcher;
using ::testing::Return;
using ::testing::Test;
using ::testing::UnorderedElementsAreArray;
using ::testing::VariantWith;

constexpr int kTestTopNStatsPerCategory = 5;
constexpr int kTestTopNStatsPerSubcategory = 5;
constexpr int kTestMaxUserSwitchEvents = 3;
constexpr std::chrono::seconds kTestSystemEventDataCacheDurationSec = 60s;
const auto kTestNow = std::chrono::time_point_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::from_time_t(1'683'270'000));

int64_t getTestElapsedRealtimeSinceBootMs() {
    return 20'000;
}

MATCHER_P(IoStatsViewEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("bytes", &UserPackageStats::IoStatsView::bytes,
                                          ElementsAreArray(expected.bytes)),
                                    Field("fsync", &UserPackageStats::IoStatsView::fsync,
                                          ElementsAreArray(expected.fsync))),
                              arg, result_listener);
}

MATCHER_P(ProcessValueEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("comm",
                                          &UserPackageStats::ProcSingleStatsView::ProcessValue::
                                                  comm,
                                          Eq(expected.comm)),
                                    Field("value",
                                          &UserPackageStats::ProcSingleStatsView::ProcessValue::
                                                  value,
                                          Eq(expected.value))),
                              arg, result_listener);
}

MATCHER_P(ProcSingleStatsViewEq, expected, "") {
    std::vector<Matcher<const UserPackageStats::ProcSingleStatsView::ProcessValue&>>
            processValueMatchers;
    processValueMatchers.reserve(expected.topNProcesses.size());
    for (const auto& processValue : expected.topNProcesses) {
        processValueMatchers.push_back(ProcessValueEq(processValue));
    }
    return ExplainMatchResult(AllOf(Field("value", &UserPackageStats::ProcSingleStatsView::value,
                                          Eq(expected.value)),
                                    Field("topNProcesses",
                                          &UserPackageStats::ProcSingleStatsView::topNProcesses,
                                          ElementsAreArray(processValueMatchers))),
                              arg, result_listener);
}

MATCHER_P(ProcessCpuValueEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("pid",
                                          &UserPackageStats::ProcCpuStatsView::ProcessCpuValue::pid,
                                          Eq(expected.pid)),
                                    Field("comm",
                                          &UserPackageStats::ProcCpuStatsView::ProcessCpuValue::
                                                  comm,
                                          Eq(expected.comm)),
                                    Field("cpuTimeMs",
                                          &UserPackageStats::ProcCpuStatsView::ProcessCpuValue::
                                                  cpuTimeMs,
                                          Eq(expected.cpuTimeMs)),
                                    Field("cpuCycles",
                                          &UserPackageStats::ProcCpuStatsView::ProcessCpuValue::
                                                  cpuCycles,
                                          Eq(expected.cpuCycles))),
                              arg, result_listener);
}

MATCHER_P(ProcCpuStatsViewEq, expected, "") {
    std::vector<Matcher<const UserPackageStats::ProcCpuStatsView::ProcessCpuValue&>>
            processValueMatchers;
    processValueMatchers.reserve(expected.topNProcesses.size());
    for (const auto& processValue : expected.topNProcesses) {
        processValueMatchers.push_back(ProcessCpuValueEq(processValue));
    }
    return ExplainMatchResult(AllOf(Field("cpuTimeMs",
                                          &UserPackageStats::ProcCpuStatsView::cpuTimeMs,
                                          Eq(expected.cpuTimeMs)),
                                    Field("cpuCycles",
                                          &UserPackageStats::ProcCpuStatsView::cpuCycles,
                                          Eq(expected.cpuCycles)),
                                    Field("topNProcesses",
                                          &UserPackageStats::ProcCpuStatsView::topNProcesses,
                                          ElementsAreArray(processValueMatchers))),
                              arg, result_listener);
}

MATCHER_P(UserPackageStatsEq, expected, "") {
    const auto uidMatcher = Field("uid", &UserPackageStats::uid, Eq(expected.uid));
    const auto packageNameMatcher =
            Field("genericPackageName", &UserPackageStats::genericPackageName,
                  Eq(expected.genericPackageName));
    return std::visit(
            [&](const auto& statsView) -> bool {
                using T = std::decay_t<decltype(statsView)>;
                if constexpr (std::is_same_v<T, UserPackageStats::IoStatsView>) {
                    return ExplainMatchResult(AllOf(uidMatcher, packageNameMatcher,
                                                    Field("statsView:IoStatsView",
                                                          &UserPackageStats::statsView,
                                                          VariantWith<
                                                                  UserPackageStats::IoStatsView>(
                                                                  IoStatsViewEq(statsView)))),
                                              arg, result_listener);
                } else if constexpr (std::is_same_v<T, UserPackageStats::ProcSingleStatsView>) {
                    return ExplainMatchResult(AllOf(uidMatcher, packageNameMatcher,
                                                    Field("statsView:ProcSingleStatsView",
                                                          &UserPackageStats::statsView,
                                                          VariantWith<UserPackageStats::
                                                                              ProcSingleStatsView>(
                                                                  ProcSingleStatsViewEq(
                                                                          statsView)))),
                                              arg, result_listener);
                } else if constexpr (std::is_same_v<T, UserPackageStats::ProcCpuStatsView>) {
                    return ExplainMatchResult(AllOf(uidMatcher, packageNameMatcher,
                                                    Field("statsView:ProcCpuStatsView",
                                                          &UserPackageStats::statsView,
                                                          VariantWith<UserPackageStats::
                                                                              ProcCpuStatsView>(
                                                                  ProcCpuStatsViewEq(statsView)))),
                                              arg, result_listener);
                }
                *result_listener << "Unexpected variant in UserPackageStats::stats";
                return false;
            },
            expected.statsView);
}

MATCHER_P(UserPackageSummaryStatsEq, expected, "") {
    const auto& userPackageStatsMatchers = [&](const std::vector<UserPackageStats>& stats) {
        std::vector<Matcher<const UserPackageStats&>> matchers;
        for (const auto& curStats : stats) {
            matchers.push_back(UserPackageStatsEq(curStats));
        }
        return ElementsAreArray(matchers);
    };
    const auto& totalIoStatsArrayMatcher = [&](const int64_t expected[][UID_STATES]) {
        std::vector<Matcher<const int64_t[UID_STATES]>> matchers;
        for (int i = 0; i < METRIC_TYPES; ++i) {
            matchers.push_back(ElementsAreArray(expected[i], UID_STATES));
        }
        return ElementsAreArray(matchers);
    };
    return ExplainMatchResult(AllOf(Field("topNCpuTimes", &UserPackageSummaryStats::topNCpuTimes,
                                          userPackageStatsMatchers(expected.topNCpuTimes)),
                                    Field("topNIoReads", &UserPackageSummaryStats::topNIoReads,
                                          userPackageStatsMatchers(expected.topNIoReads)),
                                    Field("topNIoWrites", &UserPackageSummaryStats::topNIoWrites,
                                          userPackageStatsMatchers(expected.topNIoWrites)),
                                    Field("topNIoBlocked", &UserPackageSummaryStats::topNIoBlocked,
                                          userPackageStatsMatchers(expected.topNIoBlocked)),
                                    Field("topNMajorFaults",
                                          &UserPackageSummaryStats::topNMajorFaults,
                                          userPackageStatsMatchers(expected.topNMajorFaults)),
                                    Field("totalIoStats", &UserPackageSummaryStats::totalIoStats,
                                          totalIoStatsArrayMatcher(expected.totalIoStats)),
                                    Field("taskCountByUid",
                                          &UserPackageSummaryStats::taskCountByUid,
                                          IsSubsetOf(expected.taskCountByUid)),
                                    Field("totalCpuTimeMillis",
                                          &UserPackageSummaryStats::totalCpuTimeMillis,
                                          Eq(expected.totalCpuTimeMillis)),
                                    Field("totalCpuCycles",
                                          &UserPackageSummaryStats::totalCpuCycles,
                                          Eq(expected.totalCpuCycles)),
                                    Field("totalMajorFaults",
                                          &UserPackageSummaryStats::totalMajorFaults,
                                          Eq(expected.totalMajorFaults)),
                                    Field("majorFaultsPercentChange",
                                          &UserPackageSummaryStats::majorFaultsPercentChange,
                                          Eq(expected.majorFaultsPercentChange))),
                              arg, result_listener);
}

MATCHER_P(SystemSummaryStatsEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("cpuIoWaitTimeMillis",
                                          &SystemSummaryStats::cpuIoWaitTimeMillis,
                                          Eq(expected.cpuIoWaitTimeMillis)),
                                    Field("cpuIdleTimeMillis",
                                          &SystemSummaryStats::cpuIdleTimeMillis,
                                          Eq(expected.cpuIdleTimeMillis)),
                                    Field("totalCpuTimeMillis",
                                          &SystemSummaryStats::totalCpuTimeMillis,
                                          Eq(expected.totalCpuTimeMillis)),
                                    Field("totalCpuCycles", &SystemSummaryStats::totalCpuCycles,
                                          Eq(expected.totalCpuCycles)),
                                    Field("contextSwitchesCount",
                                          &SystemSummaryStats::contextSwitchesCount,
                                          Eq(expected.contextSwitchesCount)),
                                    Field("ioBlockedProcessCount",
                                          &SystemSummaryStats::ioBlockedProcessCount,
                                          Eq(expected.ioBlockedProcessCount)),
                                    Field("totalProcessCount",
                                          &SystemSummaryStats::totalProcessCount,
                                          Eq(expected.totalProcessCount))),
                              arg, result_listener);
}

MATCHER_P(PerfStatsRecordEq, expected, "") {
    return ExplainMatchResult(AllOf(Field(&PerfStatsRecord::systemSummaryStats,
                                          SystemSummaryStatsEq(expected.systemSummaryStats)),
                                    Field(&PerfStatsRecord::userPackageSummaryStats,
                                          UserPackageSummaryStatsEq(
                                                  expected.userPackageSummaryStats))),
                              arg, result_listener);
}

const std::vector<Matcher<const PerfStatsRecord&>> constructPerfStatsRecordMatchers(
        const std::vector<PerfStatsRecord>& records) {
    std::vector<Matcher<const PerfStatsRecord&>> matchers;
    for (const auto& record : records) {
        matchers.push_back(PerfStatsRecordEq(record));
    }
    return matchers;
}

MATCHER_P(CollectionInfoEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("maxCacheSize", &CollectionInfo::maxCacheSize,
                                          Eq(expected.maxCacheSize)),
                                    Field("records", &CollectionInfo::records,
                                          ElementsAreArray(constructPerfStatsRecordMatchers(
                                                  expected.records)))),
                              arg, result_listener);
}

MATCHER_P(UserSwitchCollectionInfoEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("from", &UserSwitchCollectionInfo::from,
                                          Eq(expected.from)),
                                    Field("to", &UserSwitchCollectionInfo::to, Eq(expected.to)),
                                    Field("maxCacheSize", &UserSwitchCollectionInfo::maxCacheSize,
                                          Eq(expected.maxCacheSize)),
                                    Field("records", &UserSwitchCollectionInfo::records,
                                          ElementsAreArray(constructPerfStatsRecordMatchers(
                                                  expected.records)))),
                              arg, result_listener);
}

MATCHER_P(UserSwitchCollectionsEq, expected, "") {
    std::vector<Matcher<const UserSwitchCollectionInfo&>> userSwitchCollectionMatchers;
    for (const auto& curCollection : expected) {
        userSwitchCollectionMatchers.push_back(UserSwitchCollectionInfoEq(curCollection));
    }
    return ExplainMatchResult(ElementsAreArray(userSwitchCollectionMatchers), arg, result_listener);
}

int countOccurrences(std::string str, std::string subStr) {
    size_t pos = 0;
    int occurrences = 0;
    while ((pos = str.find(subStr, pos)) != std::string::npos) {
        ++occurrences;
        pos += subStr.length();
    }
    return occurrences;
}

std::tuple<std::vector<UidStats>, UserPackageSummaryStats> sampleUidStats(int multiplier = 1) {
    /* The number of returned sample stats are less that the top N stats per category/sub-category.
     * The top N stats per category/sub-category is set to % during test setup. Thus, the default
     * testing behavior is # reported stats < top N stats.
     */
    const auto int64Multiplier = [&](int64_t bytes) -> int64_t {
        return static_cast<int64_t>(bytes * multiplier);
    };
    const auto uint64Multiplier = [&](uint64_t count) -> uint64_t {
        return static_cast<uint64_t>(count * multiplier);
    };
    std::vector<UidStats>
            uidStats{{.packageInfo = constructPackageInfo("mount", 1009),
                      .cpuTimeMillis = int64Multiplier(50),
                      .ioStats = {/*fgRdBytes=*/0,
                                  /*bgRdBytes=*/int64Multiplier(14'000),
                                  /*fgWrBytes=*/0,
                                  /*bgWrBytes=*/int64Multiplier(16'000),
                                  /*fgFsync=*/0, /*bgFsync=*/int64Multiplier(100)},
                      .procStats = {.cpuTimeMillis = int64Multiplier(50),
                                    .cpuCycles = 4000,
                                    .totalMajorFaults = uint64Multiplier(11'000),
                                    .totalTasksCount = 1,
                                    .ioBlockedTasksCount = 1,
                                    .processStatsByPid =
                                            {{/*pid=*/100,
                                              {/*comm=*/"disk I/O", /*startTime=*/234,
                                               /*cpuTimeMillis=*/int64Multiplier(50),
                                               /*totalCpuCycles=*/4000,
                                               /*totalMajorFaults=*/uint64Multiplier(11'000),
                                               /*totalTasksCount=*/1,
                                               /*ioBlockedTasksCount=*/1,
                                               /*cpuCyclesByTid=*/{{100, 4000}}}}}}},
                     {.packageInfo =
                              constructPackageInfo("com.google.android.car.kitchensink", 1002001),
                      .cpuTimeMillis = int64Multiplier(60),
                      .ioStats = {/*fgRdBytes=*/0,
                                  /*bgRdBytes=*/int64Multiplier(3'400),
                                  /*fgWrBytes=*/0,
                                  /*bgWrBytes=*/int64Multiplier(6'700),
                                  /*fgFsync=*/0,
                                  /*bgFsync=*/int64Multiplier(200)},
                      .procStats = {.cpuTimeMillis = int64Multiplier(50),
                                    .cpuCycles = 10'000,
                                    .totalMajorFaults = uint64Multiplier(22'445),
                                    .totalTasksCount = 5,
                                    .ioBlockedTasksCount = 3,
                                    .processStatsByPid =
                                            {{/*pid=*/1001,
                                              {/*comm=*/"CTS", /*startTime=*/789,
                                               /*cpuTimeMillis=*/int64Multiplier(25),
                                               /*totalCpuCycles=*/5000,
                                               /*totalMajorFaults=*/uint64Multiplier(10'100),
                                               /*totalTasksCount=*/3,
                                               /*ioBlockedTasksCount=*/2,
                                               /*cpuCyclesByTid=*/{{1001, 3000}, {1002, 2000}}}},
                                             {/*pid=*/1000,
                                              {/*comm=*/"KitchenSinkApp", /*startTime=*/467,
                                               /*cpuTimeMillis=*/int64Multiplier(25),
                                               /*totalCpuCycles=*/4000,
                                               /*totalMajorFaults=*/uint64Multiplier(12'345),
                                               /*totalTasksCount=*/2,
                                               /*ioBlockedTasksCount=*/1,
                                               /*cpuCyclesByTid=*/{{1000, 4000}}}}}}},
                     {.packageInfo = constructPackageInfo("", 1012345),
                      .cpuTimeMillis = int64Multiplier(100),
                      .ioStats = {/*fgRdBytes=*/int64Multiplier(1'000),
                                  /*bgRdBytes=*/int64Multiplier(4'200),
                                  /*fgWrBytes=*/int64Multiplier(300),
                                  /*bgWrBytes=*/int64Multiplier(5'600),
                                  /*fgFsync=*/int64Multiplier(600),
                                  /*bgFsync=*/int64Multiplier(300)},
                      .procStats = {.cpuTimeMillis = int64Multiplier(100),
                                    .cpuCycles = 50'000,
                                    .totalMajorFaults = uint64Multiplier(50'900),
                                    .totalTasksCount = 4,
                                    .ioBlockedTasksCount = 2,
                                    .processStatsByPid =
                                            {{/*pid=*/2345,
                                              {/*comm=*/"MapsApp", /*startTime=*/6789,
                                               /*cpuTimeMillis=*/int64Multiplier(100),
                                               /*totalCpuCycles=*/50'000,
                                               /*totalMajorFaults=*/uint64Multiplier(50'900),
                                               /*totalTasksCount=*/4,
                                               /*ioBlockedTasksCount=*/2,
                                               /*cpuCyclesByTid=*/{{2345, 50'000}}}}}}},
                     {.packageInfo = constructPackageInfo("com.google.radio", 1015678),
                      .cpuTimeMillis = 0,
                      .ioStats = {/*fgRdBytes=*/0,
                                  /*bgRdBytes=*/0,
                                  /*fgWrBytes=*/0,
                                  /*bgWrBytes=*/0,
                                  /*fgFsync=*/0, /*bgFsync=*/0},
                      .procStats = {.cpuTimeMillis = 0,
                                    .cpuCycles = 0,
                                    .totalMajorFaults = 0,
                                    .totalTasksCount = 4,
                                    .ioBlockedTasksCount = 0,
                                    .processStatsByPid = {
                                            {/*pid=*/2345,
                                             {/*comm=*/"RadioApp", /*startTime=*/19789,
                                              /*cpuTimeMillis=*/0,
                                              /*totalCpuCycles=*/0,
                                              /*totalMajorFaults=*/0,
                                              /*totalTasksCount=*/4,
                                              /*ioBlockedTasksCount=*/0,
                                              /*cpuCyclesByTid=*/{}}}}}}};

    UserPackageSummaryStats userPackageSummaryStats{
            .topNCpuTimes = {{1012345, "1012345",
                              UserPackageStats::ProcCpuStatsView{int64Multiplier(100),
                                                                 50'000,
                                                                 {{2345, "MapsApp",
                                                                   int64Multiplier(100), 50'000}}}},
                             {1002001, "com.google.android.car.kitchensink",
                              UserPackageStats::ProcCpuStatsView{int64Multiplier(60),
                                                                 10'000,
                                                                 {{1001, "CTS", int64Multiplier(25),
                                                                   5000},
                                                                  {1000, "KitchenSinkApp",
                                                                   int64Multiplier(25), 4000}}}},
                             {1009, "mount",
                              UserPackageStats::ProcCpuStatsView{int64Multiplier(50),
                                                                 4000,
                                                                 {{100, "disk I/O",
                                                                   int64Multiplier(50), 4000}}}}},
            .topNIoReads = {{1009, "mount",
                             UserPackageStats::IoStatsView{{0, int64Multiplier(14'000)},
                                                           {0, int64Multiplier(100)}}},
                            {1012345, "1012345",
                             UserPackageStats::IoStatsView{{int64Multiplier(1'000),
                                                            int64Multiplier(4'200)},
                                                           {int64Multiplier(600),
                                                            int64Multiplier(300)}}},
                            {1002001, "com.google.android.car.kitchensink",
                             UserPackageStats::IoStatsView{{0, int64Multiplier(3'400)},
                                                           {0, int64Multiplier(200)}}}},
            .topNIoWrites =
                    {{1009, "mount",
                      UserPackageStats::IoStatsView{{0, int64Multiplier(16'000)},
                                                    {0, int64Multiplier(100)}}},
                     {1002001, "com.google.android.car.kitchensink",
                      UserPackageStats::IoStatsView{{0, int64Multiplier(6'700)},
                                                    {0, int64Multiplier(200)}}},
                     {1012345, "1012345",
                      UserPackageStats::IoStatsView{{int64Multiplier(300), int64Multiplier(5'600)},
                                                    {int64Multiplier(600), int64Multiplier(300)}}}},
            .topNIoBlocked =
                    {{1002001, "com.google.android.car.kitchensink",
                      UserPackageStats::ProcSingleStatsView{3,
                                                            {{"CTS", 2}, {"KitchenSinkApp", 1}}}},
                     {1012345, "1012345",
                      UserPackageStats::ProcSingleStatsView{2, {{"MapsApp", 2}}}},
                     {1009, "mount", UserPackageStats::ProcSingleStatsView{1, {{"disk I/O", 1}}}}},
            .topNMajorFaults =
                    {{1012345, "1012345",
                      UserPackageStats::ProcSingleStatsView{uint64Multiplier(50'900),
                                                            {{"MapsApp",
                                                              uint64Multiplier(50'900)}}}},
                     {1002001, "com.google.android.car.kitchensink",
                      UserPackageStats::ProcSingleStatsView{uint64Multiplier(22'445),
                                                            {{"KitchenSinkApp",
                                                              uint64Multiplier(12'345)},
                                                             {"CTS", uint64Multiplier(10'100)}}}},
                     {1009, "mount",
                      UserPackageStats::ProcSingleStatsView{uint64Multiplier(11'000),
                                                            {{"disk I/O",
                                                              uint64Multiplier(11'000)}}}}},
            .totalIoStats = {{int64Multiplier(1'000), int64Multiplier(21'600)},
                             {int64Multiplier(300), int64Multiplier(28'300)},
                             {int64Multiplier(600), int64Multiplier(600)}},
            .taskCountByUid = {{1009, 1}, {1002001, 5}, {1012345, 4}},
            .totalCpuTimeMillis = int64Multiplier(48'376),
            .totalCpuCycles = 64'000,
            .totalMajorFaults = uint64Multiplier(84'345),
            .majorFaultsPercentChange = 0.0,
    };
    return std::make_tuple(uidStats, userPackageSummaryStats);
}

std::tuple<ProcStatInfo, SystemSummaryStats> sampleProcStat(int multiplier = 1) {
    const auto int64Multiplier = [&](int64_t bytes) -> int64_t {
        return static_cast<int64_t>(bytes * multiplier);
    };
    const auto uint64Multiplier = [&](uint64_t bytes) -> uint64_t {
        return static_cast<uint64_t>(bytes * multiplier);
    };
    const auto uint32Multiplier = [&](uint32_t bytes) -> uint32_t {
        return static_cast<uint32_t>(bytes * multiplier);
    };
    ProcStatInfo procStatInfo{/*stats=*/{int64Multiplier(2'900), int64Multiplier(7'900),
                                         int64Multiplier(4'900), int64Multiplier(8'900),
                                         /*ioWaitTimeMillis=*/int64Multiplier(5'900),
                                         int64Multiplier(6'966), int64Multiplier(7'980), 0, 0,
                                         int64Multiplier(2'930)},
                              /*ctxtSwitches=*/uint64Multiplier(500),
                              /*runnableCnt=*/uint32Multiplier(100),
                              /*ioBlockedCnt=*/uint32Multiplier(57)};
    SystemSummaryStats systemSummaryStats{/*cpuIoWaitTimeMillis=*/int64Multiplier(5'900),
                                          /*cpuIdleTimeMillis=*/int64Multiplier(8'900),
                                          /*totalCpuTimeMillis=*/int64Multiplier(48'376),
                                          /*totalCpuCycles=*/64'000,
                                          /*contextSwitchesCount=*/uint64Multiplier(500),
                                          /*ioBlockedProcessCount=*/uint32Multiplier(57),
                                          /*totalProcessCount=*/uint32Multiplier(157)};
    return std::make_tuple(procStatInfo, systemSummaryStats);
}

// TODO(b/286942359): The methods: sampleUidStats, sampleProcStat and
// getResourceStatsForSampledStats are called together most times.
// Implement a method that calls the three methods and returns their
// results in a single value.
ResourceStats getResourceStatsForSampledStats(int multiplier = 1) {
    const auto int32Multiplier = [&](int32_t bytes) -> int32_t {
        return static_cast<int32_t>(bytes * multiplier);
    };
    const auto int64Multiplier = [&](int64_t bytes) -> int64_t {
        return static_cast<int64_t>(bytes * multiplier);
    };

    // clang-format off
    return {
        .resourceUsageStats = std::make_optional<ResourceUsageStats>({
            .startTimeEpochMillis = 1'683'270'000'000,
            // Set durationInMillis to zero since this field is set by WatchdogPerfService.
            .durationInMillis = 0,
            .systemSummaryUsageStats = {
                .cpuNonIdleCycles = 64'000,
                .cpuNonIdleTimeMillis = int32Multiplier(39'476),
                .cpuIdleTimeMillis = int32Multiplier(8'900),
                .contextSwitchesCount = int32Multiplier(500),
                .ioBlockedProcessCount = int32Multiplier(57),
                .totalProcessCount = int32Multiplier(157),
                .totalMajorPageFaults = int32Multiplier(84'345),
                .totalIoReads = {
                    .foregroundBytes = int32Multiplier(1'000),
                    .backgroundBytes = int32Multiplier(21'600),
                    .garageModeBytes = 0,
                },
                .totalIoWrites = {
                    .foregroundBytes = int32Multiplier(300),
                    .backgroundBytes = int32Multiplier(28'300),
                    .garageModeBytes = 0,
                },
            },
            .uidResourceUsageStats = {
                {
                    .packageIdentifier = {
                        .name = "mount",
                        .uid = 1009,
                    },
                    .uidUptimeMillis = 19'766,
                    .cpuUsageStats = {
                        .cpuTimeMillis = int64Multiplier(50),
                        .cpuCycles = 4'000,
                        .cpuTimePercentage = (50. / 48'376.) * 100.0,
                    },
                    .processCpuUsageStats = {
                        {
                            .pid = 100,
                            .name = "disk I/O",
                            .cpuTimeMillis = int64Multiplier(50),
                            .cpuCycles = 4'000,
                        },
                    },
                    .ioUsageStats = {
                        .writtenBytes = {
                            .foregroundBytes = 0,
                            .backgroundBytes = int32Multiplier(16'000),
                            .garageModeBytes = 0,
                        },
                        .readBytes = {
                            .foregroundBytes = 0,
                            .backgroundBytes = int32Multiplier(14'000),
                            .garageModeBytes = 0,
                        },
                    },
                },
                {
                    .packageIdentifier = {
                        .name = "com.google.android.car.kitchensink",
                        .uid = 1002001,
                    },
                    .uidUptimeMillis = 19'533,
                    .cpuUsageStats = {
                        .cpuTimeMillis = int64Multiplier(60),
                        .cpuCycles = 10'000,
                        .cpuTimePercentage = (60. / 48'376.) * 100.0,
                    },
                    .processCpuUsageStats = {
                        {
                            .pid = 1001,
                            .name = "CTS",
                            .cpuTimeMillis = int64Multiplier(25),
                            .cpuCycles = 5'000,
                        },
                        {
                            .pid = 1000,
                            .name = "KitchenSinkApp",
                            .cpuTimeMillis = int64Multiplier(25),
                            .cpuCycles = 4'000,
                        },
                    },
                    .ioUsageStats = {
                        .writtenBytes = {
                            .foregroundBytes = 0,
                            .backgroundBytes = int32Multiplier(6'700),
                            .garageModeBytes = 0,
                        },
                        .readBytes = {
                            .foregroundBytes = 0,
                            .backgroundBytes = int32Multiplier(3'400),
                            .garageModeBytes = 0,
                        },
                    },
                },
                {
                    .packageIdentifier = {
                        .name = "1012345",
                        .uid = 1012345,
                    },
                    .uidUptimeMillis = 13'211,
                    .cpuUsageStats = {
                        .cpuTimeMillis = int64Multiplier(100),
                        .cpuCycles = 50'000,
                        .cpuTimePercentage = (100. / 48'376.) * 100.0,
                    },
                    .processCpuUsageStats = {
                        {
                            .pid = 2345,
                            .name = "MapsApp",
                            .cpuTimeMillis = int64Multiplier(100),
                            .cpuCycles = 50'000,
                        },
                    },
                    .ioUsageStats = {
                        .writtenBytes = {
                            .foregroundBytes = int32Multiplier(300),
                            .backgroundBytes = int32Multiplier(5'600),
                            .garageModeBytes = 0,
                        },
                        .readBytes = {
                            .foregroundBytes = int32Multiplier(1'000),
                            .backgroundBytes = int32Multiplier(4'200),
                            .garageModeBytes = 0,
                        },
                    },
                },
                {
                    .packageIdentifier = {
                        .name = "com.google.radio",
                        .uid = 1015678,
                    },
                    .uidUptimeMillis = 211,
                    .cpuUsageStats = {
                        .cpuTimeMillis = 0,
                        .cpuCycles = 0,
                        .cpuTimePercentage = 0,
                    },
                    .ioUsageStats = {
                        .writtenBytes = {
                            .foregroundBytes = 0,
                            .backgroundBytes = 0,
                            .garageModeBytes = 0,
                        },
                        .readBytes = {
                            .foregroundBytes = 0,
                            .backgroundBytes = 0,
                            .garageModeBytes = 0,
                        },
                    },
                },
            },
        }),
    };
    // clang-format on
}

}  // namespace

namespace internal {

// TODO(b/289396065): Refactor class such that variable fields are initialized directly in the
// constructor and remove the setter methods.
class PerformanceProfilerPeer final : public RefBase {
public:
    explicit PerformanceProfilerPeer(sp<PerformanceProfiler> collector) : mCollector(collector) {}

    PerformanceProfilerPeer() = delete;
    ~PerformanceProfilerPeer() {
        mCollector->terminate();
        mCollector.clear();
    }

    Result<void> init() { return mCollector->init(); }

    void setTopNStatsPerCategory(int value) { mCollector->mTopNStatsPerCategory = value; }

    void setTopNStatsPerSubcategory(int value) { mCollector->mTopNStatsPerSubcategory = value; }

    void setMaxUserSwitchEvents(int value) { mCollector->mMaxUserSwitchEvents = value; }

    void setSystemEventDataCacheDuration(std::chrono::seconds value) {
        mCollector->mSystemEventDataCacheDurationSec = value;
    }

    void setSendResourceUsageStatsEnabled(bool enable) {
        mCollector->mDoSendResourceUsageStats = enable;
    }

    void setGetElapsedTimeSinceBootMillisFunc(const std::function<int64_t()>& func) {
        mCollector->kGetElapsedTimeSinceBootMillisFunc = func;
    }

    const CollectionInfo& getBoottimeCollectionInfo() {
        Mutex::Autolock lock(mCollector->mMutex);
        return mCollector->mBoottimeCollection;
    }

    const CollectionInfo& getPeriodicCollectionInfo() {
        Mutex::Autolock lock(mCollector->mMutex);
        return mCollector->mPeriodicCollection;
    }

    const std::vector<UserSwitchCollectionInfo>& getUserSwitchCollectionInfos() {
        Mutex::Autolock lock(mCollector->mMutex);
        return mCollector->mUserSwitchCollections;
    }

    const CollectionInfo& getWakeUpCollectionInfo() {
        Mutex::Autolock lock(mCollector->mMutex);
        return mCollector->mWakeUpCollection;
    }

    const CollectionInfo& getCustomCollectionInfo() {
        Mutex::Autolock lock(mCollector->mMutex);
        return mCollector->mCustomCollection;
    }

private:
    sp<PerformanceProfiler> mCollector;
};

}  // namespace internal

class PerformanceProfilerTest : public Test {
protected:
    void SetUp() override {
        mMockUidStatsCollector = sp<MockUidStatsCollector>::make();
        mMockProcStatCollector = sp<MockProcStatCollector>::make();
        mCollector = sp<PerformanceProfiler>::make();
        mCollectorPeer = sp<internal::PerformanceProfilerPeer>::make(mCollector);
        ASSERT_RESULT_OK(mCollectorPeer->init());
        mCollectorPeer->setTopNStatsPerCategory(kTestTopNStatsPerCategory);
        mCollectorPeer->setTopNStatsPerSubcategory(kTestTopNStatsPerSubcategory);
        mCollectorPeer->setMaxUserSwitchEvents(kTestMaxUserSwitchEvents);
        mCollectorPeer->setSystemEventDataCacheDuration(kTestSystemEventDataCacheDurationSec);
        mCollectorPeer->setSendResourceUsageStatsEnabled(true);
        mCollectorPeer->setGetElapsedTimeSinceBootMillisFunc(getTestElapsedRealtimeSinceBootMs);
    }

    void TearDown() override {
        mMockUidStatsCollector.clear();
        mMockProcStatCollector.clear();
        mCollector.clear();
        mCollectorPeer.clear();
    }

    void checkDumpContents(int wantedEmptyCollectionInstances) {
        TemporaryFile dump;
        ASSERT_RESULT_OK(mCollector->onDump(dump.fd));

        checkDumpFd(wantedEmptyCollectionInstances, dump.fd);
    }

    void checkCustomDumpContents() {
        TemporaryFile dump;
        ASSERT_RESULT_OK(mCollector->onCustomCollectionDump(dump.fd));

        checkDumpFd(/*wantedEmptyCollectionInstances=*/0, dump.fd);
    }

private:
    void checkDumpFd(int wantedEmptyCollectionInstances, int fd) {
        lseek(fd, 0, SEEK_SET);
        std::string dumpContents;
        ASSERT_TRUE(ReadFdToString(fd, &dumpContents));
        ASSERT_FALSE(dumpContents.empty());

        ASSERT_EQ(countOccurrences(dumpContents, kEmptyCollectionMessage),
                  wantedEmptyCollectionInstances)
                << "Dump contents: " << dumpContents;
    }

protected:
    sp<MockUidStatsCollector> mMockUidStatsCollector;
    sp<MockProcStatCollector> mMockProcStatCollector;
    sp<PerformanceProfiler> mCollector;
    sp<internal::PerformanceProfilerPeer> mCollectorPeer;
};

TEST_F(PerformanceProfilerTest, TestOnBoottimeCollection) {
    const auto [uidStats, userPackageSummaryStats] = sampleUidStats();
    const auto [procStatInfo, systemSummaryStats] = sampleProcStat();
    const auto expectedResourceStats = getResourceStatsForSampledStats();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillOnce(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillOnce(Return(procStatInfo));

    ResourceStats actualResourceStats = {};
    ASSERT_RESULT_OK(mCollector->onBoottimeCollection(kTestNow, mMockUidStatsCollector,
                                                      mMockProcStatCollector,
                                                      &actualResourceStats));

    const auto actual = mCollectorPeer->getBoottimeCollectionInfo();

    const CollectionInfo expected{
            .maxCacheSize = std::numeric_limits<std::size_t>::max(),
            .records = {{
                    .systemSummaryStats = systemSummaryStats,
                    .userPackageSummaryStats = userPackageSummaryStats,
            }},
    };

    EXPECT_THAT(actual, CollectionInfoEq(expected))
            << "Boottime collection info doesn't match.\nExpected:\n"
            << expected.toString() << "\nActual:\n"
            << actual.toString();

    ASSERT_EQ(actualResourceStats, expectedResourceStats)
            << "Expected: " << expectedResourceStats.toString()
            << "\nActual: " << actualResourceStats.toString();

    ASSERT_NO_FATAL_FAILURE(checkDumpContents(/*wantedEmptyCollectionInstances=*/3))
            << "Periodic, wake-up and user-switch collections shouldn't be reported";
}

TEST_F(PerformanceProfilerTest, TestOnWakeUpCollection) {
    const auto [uidStats, userPackageSummaryStats] = sampleUidStats();
    const auto [procStatInfo, systemSummaryStats] = sampleProcStat();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillOnce(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillOnce(Return(procStatInfo));

    ASSERT_RESULT_OK(mCollector->onWakeUpCollection(kTestNow, mMockUidStatsCollector,
                                                    mMockProcStatCollector));

    const auto actual = mCollectorPeer->getWakeUpCollectionInfo();

    const CollectionInfo expected{
            .maxCacheSize = std::numeric_limits<std::size_t>::max(),
            .records = {{
                    .systemSummaryStats = systemSummaryStats,
                    .userPackageSummaryStats = userPackageSummaryStats,
            }},
    };

    EXPECT_THAT(actual, CollectionInfoEq(expected))
            << "Wake-up collection info doesn't match.\nExpected:\n"
            << expected.toString() << "\nActual:\n"
            << actual.toString();

    ASSERT_NO_FATAL_FAILURE(checkDumpContents(/*wantedEmptyCollectionInstances=*/3))
            << "Boot-time, periodic, and user-switch collections shouldn't be reported";
}

TEST_F(PerformanceProfilerTest, TestOnSystemStartup) {
    const auto [uidStats, userPackageSummaryStats] = sampleUidStats();
    const auto [procStatInfo, systemSummaryStats] = sampleProcStat();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillRepeatedly(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillRepeatedly(Return(procStatInfo));

    ResourceStats resourceStats = {};
    ASSERT_RESULT_OK(mCollector->onBoottimeCollection(kTestNow, mMockUidStatsCollector,
                                                      mMockProcStatCollector, &resourceStats));
    ASSERT_RESULT_OK(mCollector->onWakeUpCollection(kTestNow, mMockUidStatsCollector,
                                                    mMockProcStatCollector));

    auto actualBoottimeCollection = mCollectorPeer->getBoottimeCollectionInfo();
    auto actualWakeUpCollection = mCollectorPeer->getWakeUpCollectionInfo();

    EXPECT_THAT(actualBoottimeCollection.records.size(), 1)
            << "Boot-time collection records is empty.";
    EXPECT_THAT(actualWakeUpCollection.records.size(), 1) << "Wake-up collection records is empty.";

    ASSERT_RESULT_OK(mCollector->onSystemStartup());

    actualBoottimeCollection = mCollectorPeer->getBoottimeCollectionInfo();
    actualWakeUpCollection = mCollectorPeer->getWakeUpCollectionInfo();

    EXPECT_THAT(actualBoottimeCollection.records.size(), 0)
            << "Boot-time collection records is not empty.";
    EXPECT_THAT(actualWakeUpCollection.records.size(), 0)
            << "Wake-up collection records is not empty.";
}

TEST_F(PerformanceProfilerTest, TestOnUserSwitchCollection) {
    auto [uidStats, userPackageSummaryStats] = sampleUidStats();
    auto [procStatInfo, systemSummaryStats] = sampleProcStat();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillOnce(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillOnce(Return(procStatInfo));

    ASSERT_RESULT_OK(mCollector->onUserSwitchCollection(kTestNow, 100, 101, mMockUidStatsCollector,
                                                        mMockProcStatCollector));

    const auto& actualInfos = mCollectorPeer->getUserSwitchCollectionInfos();
    const auto& actual = actualInfos[0];

    UserSwitchCollectionInfo expected{
            {
                    .maxCacheSize = std::numeric_limits<std::size_t>::max(),
                    .records = {{
                            .systemSummaryStats = systemSummaryStats,
                            .userPackageSummaryStats = userPackageSummaryStats,
                    }},
            },
            .from = 100,
            .to = 101,
    };

    EXPECT_THAT(actualInfos.size(), 1);

    EXPECT_THAT(actual, UserSwitchCollectionInfoEq(expected))
            << "User switch collection info doesn't match.\nExpected:\n"
            << expected.toString() << "\nActual:\n"
            << actual.toString();

    // Continuation of the previous user switch collection
    std::vector<UidStats> nextUidStats = {
            {.packageInfo = constructPackageInfo("mount", 1009),
             .cpuTimeMillis = 0,  // No TopNCpuTimes will be registered
             .ioStats = {/*fgRdBytes=*/0,
                         /*bgRdBytes=*/5'000,
                         /*fgWrBytes=*/0,
                         /*bgWrBytes=*/3'000,
                         /*fgFsync=*/0, /*bgFsync=*/50},
             .procStats = {.cpuTimeMillis = 50,
                           .cpuCycles = 3'500,
                           .totalMajorFaults = 6'000,
                           .totalTasksCount = 1,
                           .ioBlockedTasksCount = 2,
                           .processStatsByPid = {{/*pid=*/100,
                                                  {/*comm=*/"disk I/O", /*startTime=*/234,
                                                   /*cpuTimeMillis=*/50,
                                                   /*totalCpuCycle=*/3'500,
                                                   /*totalMajorFaults=*/6'000,
                                                   /*totalTasksCount=*/1,
                                                   /*ioBlockedTasksCount=*/2,
                                                   /*cpuCyclesByTid=*/{{100, 3'500}}}}}}}};

    UserPackageSummaryStats nextUserPackageSummaryStats = {
            .topNIoReads = {{1009, "mount", UserPackageStats::IoStatsView{{0, 5'000}, {0, 50}}}},
            .topNIoWrites = {{1009, "mount", UserPackageStats::IoStatsView{{0, 3'000}, {0, 50}}}},
            .topNIoBlocked = {{1009, "mount",
                               UserPackageStats::ProcSingleStatsView{2, {{"disk I/O", 2}}}}},
            .topNMajorFaults = {{1009, "mount",
                                 UserPackageStats::ProcSingleStatsView{6'000,
                                                                       {{"disk I/O", 6'000}}}}},
            .totalIoStats = {{0, 5'000}, {0, 3'000}, {0, 50}},
            .taskCountByUid = {{1009, 1}},
            .totalCpuTimeMillis = 48'376,
            .totalCpuCycles = 3'500,
            .totalMajorFaults = 6'000,
            .majorFaultsPercentChange = (6'000.0 - 84'345.0) / 84'345.0 * 100.0,
    };

    ProcStatInfo nextProcStatInfo = procStatInfo;
    SystemSummaryStats nextSystemSummaryStats = systemSummaryStats;

    nextProcStatInfo.contextSwitchesCount = 300;
    nextSystemSummaryStats.totalCpuCycles = 3'500;
    nextSystemSummaryStats.contextSwitchesCount = 300;

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillOnce(Return(nextUidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillOnce(Return(nextProcStatInfo));

    ASSERT_RESULT_OK(mCollector->onUserSwitchCollection(kTestNow + std::chrono::seconds(2), 100,
                                                        101, mMockUidStatsCollector,
                                                        mMockProcStatCollector));

    auto& continuationActualInfos = mCollectorPeer->getUserSwitchCollectionInfos();
    auto& continuationActual = continuationActualInfos[0];

    expected = {
            {
                    .maxCacheSize = std::numeric_limits<std::size_t>::max(),
                    .records = {{.systemSummaryStats = systemSummaryStats,
                                 .userPackageSummaryStats = userPackageSummaryStats},
                                {.systemSummaryStats = nextSystemSummaryStats,
                                 .userPackageSummaryStats = nextUserPackageSummaryStats}},
            },
            .from = 100,
            .to = 101,
    };

    EXPECT_THAT(continuationActualInfos.size(), 1);

    EXPECT_THAT(continuationActual, UserSwitchCollectionInfoEq(expected))
            << "User switch collection info after continuation doesn't match.\nExpected:\n"
            << expected.toString() << "\nActual:\n"
            << actual.toString();

    ASSERT_NO_FATAL_FAILURE(checkDumpContents(/*wantedEmptyCollectionInstances=*/3))
            << "Boot-time, wake-up and periodic collections shouldn't be reported";
}

TEST_F(PerformanceProfilerTest, TestUserSwitchCollectionsMaxCacheSize) {
    auto [uidStats, userPackageSummaryStats] = sampleUidStats();
    auto [procStatInfo, systemSummaryStats] = sampleProcStat();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillRepeatedly(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillRepeatedly(Return(procStatInfo));

    std::vector<UserSwitchCollectionInfo> expectedEvents;
    for (userid_t userId = 100; userId < 100 + kTestMaxUserSwitchEvents; ++userId) {
        expectedEvents.push_back({
                {
                        .maxCacheSize = std::numeric_limits<std::size_t>::max(),
                        .records = {{
                                .systemSummaryStats = systemSummaryStats,
                                .userPackageSummaryStats = userPackageSummaryStats,
                        }},
                },
                .from = userId,
                .to = userId + 1,
        });
    }

    for (userid_t userId = 100; userId < 100 + kTestMaxUserSwitchEvents; ++userId) {
        ASSERT_RESULT_OK(mCollector->onUserSwitchCollection(kTestNow, userId, userId + 1,
                                                            mMockUidStatsCollector,
                                                            mMockProcStatCollector));
    }

    const auto& actual = mCollectorPeer->getUserSwitchCollectionInfos();

    EXPECT_THAT(actual.size(), kTestMaxUserSwitchEvents);

    EXPECT_THAT(actual, UserSwitchCollectionsEq(expectedEvents))
            << "User switch collection infos don't match.";

    // Add new user switch event with max cache size. The oldest user switch event should be dropped
    // and the new one added to the cache.
    userid_t userId = 100 + kTestMaxUserSwitchEvents;

    expectedEvents.push_back({
            {
                    .maxCacheSize = std::numeric_limits<std::size_t>::max(),
                    .records = {{
                            .systemSummaryStats = systemSummaryStats,
                            .userPackageSummaryStats = userPackageSummaryStats,
                    }},
            },
            .from = userId,
            .to = userId + 1,
    });
    expectedEvents.erase(expectedEvents.begin());

    ASSERT_RESULT_OK(mCollector->onUserSwitchCollection(kTestNow, userId, userId + 1,
                                                        mMockUidStatsCollector,
                                                        mMockProcStatCollector));

    const auto& actualInfos = mCollectorPeer->getUserSwitchCollectionInfos();

    EXPECT_THAT(actualInfos.size(), kTestMaxUserSwitchEvents);

    EXPECT_THAT(actualInfos, UserSwitchCollectionsEq(expectedEvents))
            << "User switch collection infos don't match.";
}

TEST_F(PerformanceProfilerTest, TestOnPeriodicCollection) {
    const auto [uidStats, userPackageSummaryStats] = sampleUidStats();
    const auto [procStatInfo, systemSummaryStats] = sampleProcStat();
    const auto expectedResourceStats = getResourceStatsForSampledStats();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillOnce(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillOnce(Return(procStatInfo));

    ResourceStats actualResourceStats = {};
    ASSERT_RESULT_OK(mCollector->onPeriodicCollection(kTestNow, SystemState::NORMAL_MODE,
                                                      mMockUidStatsCollector,
                                                      mMockProcStatCollector,
                                                      &actualResourceStats));

    const auto actual = mCollectorPeer->getPeriodicCollectionInfo();

    const CollectionInfo expected{
            .maxCacheSize = static_cast<size_t>(sysprop::periodicCollectionBufferSize().value_or(
                    kDefaultPeriodicCollectionBufferSize)),
            .records = {{
                    .systemSummaryStats = systemSummaryStats,
                    .userPackageSummaryStats = userPackageSummaryStats,
            }},
    };

    EXPECT_THAT(actual, CollectionInfoEq(expected))
            << "Periodic collection info doesn't match.\nExpected:\n"
            << expected.toString() << "\nActual:\n"
            << actual.toString();

    ASSERT_EQ(actualResourceStats, expectedResourceStats)
            << "Expected: " << expectedResourceStats.toString()
            << "\nActual: " << actualResourceStats.toString();

    ASSERT_NO_FATAL_FAILURE(checkDumpContents(/*wantedEmptyCollectionInstances=*/3))
            << "Boot-time, wake-up and user-switch collections shouldn't be reported";
}

TEST_F(PerformanceProfilerTest, TestOnPeriodicCollectionWithSendingUsageStatsDisabled) {
    mCollectorPeer->setSendResourceUsageStatsEnabled(false);
    const auto [uidStats, userPackageSummaryStats] = sampleUidStats();
    const auto [procStatInfo, systemSummaryStats] = sampleProcStat();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillOnce(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillOnce(Return(procStatInfo));

    ResourceStats actualResourceStats = {};
    ASSERT_RESULT_OK(mCollector->onPeriodicCollection(kTestNow, SystemState::NORMAL_MODE,
                                                      mMockUidStatsCollector,
                                                      mMockProcStatCollector,
                                                      &actualResourceStats));

    const auto actual = mCollectorPeer->getPeriodicCollectionInfo();

    const CollectionInfo expected{
            .maxCacheSize = static_cast<size_t>(sysprop::periodicCollectionBufferSize().value_or(
                    kDefaultPeriodicCollectionBufferSize)),
            .records = {{
                    .systemSummaryStats = systemSummaryStats,
                    .userPackageSummaryStats = userPackageSummaryStats,
            }},
    };
    const ResourceStats expectedResourceStats = {};

    EXPECT_THAT(actual, CollectionInfoEq(expected))
            << "Periodic collection info doesn't match.\nExpected:\n"
            << expected.toString() << "\nActual:\n"
            << actual.toString();

    ASSERT_EQ(actualResourceStats, expectedResourceStats)
            << "Expected: " << expectedResourceStats.toString()
            << "\nActual: " << actualResourceStats.toString();

    ASSERT_NO_FATAL_FAILURE(checkDumpContents(/*wantedEmptyCollectionInstances=*/3))
            << "Boot-time, wake-up and user-switch collections shouldn't be reported";
}

TEST_F(PerformanceProfilerTest, TestOnCustomCollectionWithoutPackageFilter) {
    const auto [uidStats, userPackageSummaryStats] = sampleUidStats();
    const auto [procStatInfo, systemSummaryStats] = sampleProcStat();
    const auto expectedResourceStats = getResourceStatsForSampledStats();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillOnce(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillOnce(Return(procStatInfo));

    ResourceStats actualResourceStats = {};
    ASSERT_RESULT_OK(mCollector->onCustomCollection(kTestNow, SystemState::NORMAL_MODE, {},
                                                    mMockUidStatsCollector, mMockProcStatCollector,
                                                    &actualResourceStats));

    const auto actual = mCollectorPeer->getCustomCollectionInfo();

    CollectionInfo expected{
            .maxCacheSize = std::numeric_limits<std::size_t>::max(),
            .records = {{
                    .systemSummaryStats = systemSummaryStats,
                    .userPackageSummaryStats = userPackageSummaryStats,
            }},
    };

    EXPECT_THAT(actual, CollectionInfoEq(expected))
            << "Custom collection info doesn't match.\nExpected:\n"
            << expected.toString() << "\nActual:\n"
            << actual.toString();

    ASSERT_EQ(actualResourceStats, expectedResourceStats)
            << "Expected: " << expectedResourceStats.toString()
            << "\nActual: " << actualResourceStats.toString();

    ASSERT_NO_FATAL_FAILURE(checkCustomDumpContents()) << "Custom collection should be reported";

    TemporaryFile customDump;
    ASSERT_RESULT_OK(mCollector->onCustomCollectionDump(customDump.fd));

    // Should clear the cache.
    ASSERT_RESULT_OK(mCollector->onCustomCollectionDump(-1));

    expected.records.clear();
    const CollectionInfo& emptyCollectionInfo = mCollectorPeer->getCustomCollectionInfo();
    EXPECT_THAT(emptyCollectionInfo, CollectionInfoEq(expected))
            << "Custom collection should be cleared.";
}

TEST_F(PerformanceProfilerTest, TestOnCustomCollectionWithPackageFilter) {
    // Filter by package name should ignore this limit with package filter.
    mCollectorPeer->setTopNStatsPerCategory(1);

    const auto [uidStats, _] = sampleUidStats();
    const auto [procStatInfo, systemSummaryStats] = sampleProcStat();
    const auto expectedResourceStats = getResourceStatsForSampledStats();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillOnce(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillOnce(Return(procStatInfo));

    ResourceStats actualResourceStats = {};
    ASSERT_RESULT_OK(mCollector->onCustomCollection(kTestNow, SystemState::NORMAL_MODE,
                                                    {"mount", "com.google.android.car.kitchensink"},
                                                    mMockUidStatsCollector, mMockProcStatCollector,
                                                    &actualResourceStats));

    const auto actual = mCollectorPeer->getCustomCollectionInfo();

    UserPackageSummaryStats userPackageSummaryStats{
            .topNCpuTimes = {{1009, "mount",
                              UserPackageStats::ProcCpuStatsView{50,
                                                                 4'000,
                                                                 {{100, "disk I/O", 50, 4'000}}}},
                             {1002001, "com.google.android.car.kitchensink",
                              UserPackageStats::ProcCpuStatsView{60,
                                                                 10'000,
                                                                 {{1001, "CTS", 25, 5'000},
                                                                  {1000, "KitchenSinkApp", 25,
                                                                   4'000}}}}},
            .topNIoReads = {{1009, "mount", UserPackageStats::IoStatsView{{0, 14'000}, {0, 100}}},
                            {1002001, "com.google.android.car.kitchensink",
                             UserPackageStats::IoStatsView{{0, 3'400}, {0, 200}}}},
            .topNIoWrites = {{1009, "mount", UserPackageStats::IoStatsView{{0, 16'000}, {0, 100}}},
                             {1002001, "com.google.android.car.kitchensink",
                              UserPackageStats::IoStatsView{{0, 6'700}, {0, 200}}}},
            .topNIoBlocked =
                    {{1009, "mount", UserPackageStats::ProcSingleStatsView{1, {{"disk I/O", 1}}}},
                     {1002001, "com.google.android.car.kitchensink",
                      UserPackageStats::ProcSingleStatsView{3,
                                                            {{"CTS", 2}, {"KitchenSinkApp", 1}}}}},
            .topNMajorFaults = {{1009, "mount",
                                 UserPackageStats::ProcSingleStatsView{11'000,
                                                                       {{"disk I/O", 11'000}}}},
                                {1002001, "com.google.android.car.kitchensink",
                                 UserPackageStats::ProcSingleStatsView{22'445,
                                                                       {{"KitchenSinkApp", 12'345},
                                                                        {"CTS", 10'100}}}}},
            .totalIoStats = {{1000, 21'600}, {300, 28'300}, {600, 600}},
            .taskCountByUid = {{1009, 1}, {1002001, 5}},
            .totalCpuTimeMillis = 48'376,
            .totalCpuCycles = 64'000,
            .totalMajorFaults = 84'345,
            .majorFaultsPercentChange = 0.0,
    };

    CollectionInfo expected{
            .maxCacheSize = std::numeric_limits<std::size_t>::max(),
            .records = {{
                    .systemSummaryStats = systemSummaryStats,
                    .userPackageSummaryStats = userPackageSummaryStats,
            }},
    };

    EXPECT_THAT(actual, CollectionInfoEq(expected))
            << "Custom collection info doesn't match.\nExpected:\n"
            << expected.toString() << "\nActual:\n"
            << actual.toString();

    ASSERT_EQ(actualResourceStats, expectedResourceStats)
            << "Expected: " << expectedResourceStats.toString()
            << "\nActual: " << actualResourceStats.toString();

    ASSERT_NO_FATAL_FAILURE(checkCustomDumpContents()) << "Custom collection should be reported";

    TemporaryFile customDump;
    ASSERT_RESULT_OK(mCollector->onCustomCollectionDump(customDump.fd));

    // Should clear the cache.
    ASSERT_RESULT_OK(mCollector->onCustomCollectionDump(-1));

    expected.records.clear();
    const CollectionInfo& emptyCollectionInfo = mCollectorPeer->getCustomCollectionInfo();
    EXPECT_THAT(emptyCollectionInfo, CollectionInfoEq(expected))
            << "Custom collection should be cleared.";
}

TEST_F(PerformanceProfilerTest, TestOnPeriodicCollectionWithTrimmingStatsAfterTopN) {
    mCollectorPeer->setTopNStatsPerCategory(1);
    mCollectorPeer->setTopNStatsPerSubcategory(1);

    const auto [uidStats, _] = sampleUidStats();
    const auto [procStatInfo, systemSummaryStats] = sampleProcStat();
    auto expectedResourceStats = getResourceStatsForSampledStats();

    // Top N stats per category/sub-category is set to 1, so remove entries in the
    // expected value to match this.
    ASSERT_FALSE(expectedResourceStats.resourceUsageStats->uidResourceUsageStats.empty());
    UidResourceUsageStats& kitchenSinkStats =
            expectedResourceStats.resourceUsageStats->uidResourceUsageStats.at(1);
    ASSERT_FALSE(kitchenSinkStats.processCpuUsageStats.empty());
    kitchenSinkStats.processCpuUsageStats.pop_back();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillOnce(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillOnce(Return(procStatInfo));

    ResourceStats actualResourceStats = {};
    ASSERT_RESULT_OK(mCollector->onPeriodicCollection(kTestNow, SystemState::NORMAL_MODE,
                                                      mMockUidStatsCollector,
                                                      mMockProcStatCollector,
                                                      &actualResourceStats));

    const auto actual = mCollectorPeer->getPeriodicCollectionInfo();

    UserPackageSummaryStats userPackageSummaryStats{
            .topNCpuTimes = {{1012345, "1012345",
                              UserPackageStats::ProcCpuStatsView{100,
                                                                 50'000,
                                                                 {{2345, "MapsApp", 100,
                                                                   50'000}}}}},
            .topNIoReads = {{1009, "mount", UserPackageStats::IoStatsView{{0, 14'000}, {0, 100}}}},
            .topNIoWrites = {{1009, "mount", UserPackageStats::IoStatsView{{0, 16'000}, {0, 100}}}},
            .topNIoBlocked = {{1002001, "com.google.android.car.kitchensink",
                               UserPackageStats::ProcSingleStatsView{3, {{"CTS", 2}}}}},
            .topNMajorFaults = {{1012345, "1012345",
                                 UserPackageStats::ProcSingleStatsView{50'900,
                                                                       {{"MapsApp", 50'900}}}}},
            .totalIoStats = {{1000, 21'600}, {300, 28'300}, {600, 600}},
            .taskCountByUid = {{1009, 1}, {1002001, 5}, {1012345, 4}},
            .totalCpuTimeMillis = 48'376,
            .totalCpuCycles = 64'000,
            .totalMajorFaults = 84'345,
            .majorFaultsPercentChange = 0.0,
    };

    const CollectionInfo expected{
            .maxCacheSize = static_cast<size_t>(sysprop::periodicCollectionBufferSize().value_or(
                    kDefaultPeriodicCollectionBufferSize)),
            .records = {{
                    .systemSummaryStats = systemSummaryStats,
                    .userPackageSummaryStats = userPackageSummaryStats,
            }},
    };

    EXPECT_THAT(actual, CollectionInfoEq(expected))
            << "Periodic collection info doesn't match.\nExpected:\n"
            << expected.toString() << "\nActual:\n"
            << actual.toString();

    ASSERT_EQ(actualResourceStats, expectedResourceStats)
            << "Expected: " << expectedResourceStats.toString()
            << "\nActual: " << actualResourceStats.toString();

    ASSERT_NO_FATAL_FAILURE(checkDumpContents(/*wantedEmptyCollectionInstances=*/3))
            << "Boot-time, wake-up and user-switch collections shouldn't be reported";
}

TEST_F(PerformanceProfilerTest, TestConsecutiveOnPeriodicCollection) {
    const auto [firstUidStats, firstUserPackageSummaryStats] = sampleUidStats();
    const auto [firstProcStatInfo, firstSystemSummaryStats] = sampleProcStat();
    auto expectedResourceStats = getResourceStatsForSampledStats();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillOnce(Return(firstUidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillOnce(Return(firstProcStatInfo));

    ResourceStats actualResourceStats = {};
    ASSERT_RESULT_OK(mCollector->onPeriodicCollection(kTestNow, SystemState::NORMAL_MODE,
                                                      mMockUidStatsCollector,
                                                      mMockProcStatCollector,
                                                      &actualResourceStats));

    auto [secondUidStats, secondUserPackageSummaryStats] = sampleUidStats(/*multiplier=*/2);
    const auto [secondProcStatInfo, secondSystemSummaryStats] = sampleProcStat(/*multiplier=*/2);
    expectedResourceStats = getResourceStatsForSampledStats(/*multiplier=*/2);

    secondUserPackageSummaryStats.majorFaultsPercentChange =
            (static_cast<double>(secondUserPackageSummaryStats.totalMajorFaults -
                                 firstUserPackageSummaryStats.totalMajorFaults) /
             static_cast<double>(firstUserPackageSummaryStats.totalMajorFaults)) *
            100.0;

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillOnce(Return(secondUidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillOnce(Return(secondProcStatInfo));

    ASSERT_RESULT_OK(mCollector->onPeriodicCollection(kTestNow, SystemState::NORMAL_MODE,
                                                      mMockUidStatsCollector,
                                                      mMockProcStatCollector,
                                                      &actualResourceStats));

    const auto actual = mCollectorPeer->getPeriodicCollectionInfo();

    const CollectionInfo expected{
            .maxCacheSize = static_cast<size_t>(sysprop::periodicCollectionBufferSize().value_or(
                    kDefaultPeriodicCollectionBufferSize)),
            .records = {{.systemSummaryStats = firstSystemSummaryStats,
                         .userPackageSummaryStats = firstUserPackageSummaryStats},
                        {.systemSummaryStats = secondSystemSummaryStats,
                         .userPackageSummaryStats = secondUserPackageSummaryStats}},
    };

    EXPECT_THAT(actual, CollectionInfoEq(expected))
            << "Periodic collection info doesn't match.\nExpected:\n"
            << expected.toString() << "\nActual:\n"
            << actual.toString();

    ASSERT_EQ(actualResourceStats, expectedResourceStats)
            << "Expected: " << expectedResourceStats.toString()
            << "\nActual: " << actualResourceStats.toString();

    ASSERT_NO_FATAL_FAILURE(checkDumpContents(/*wantedEmptyCollectionInstances=*/3))
            << "Boot-time, wake-up and user-switch collection shouldn't be reported";
}

TEST_F(PerformanceProfilerTest, TestBoottimeCollectionCacheEviction) {
    const auto [uidStats, userPackageSummaryStats] = sampleUidStats();
    const auto [procStatInfo, systemSummaryStats] = sampleProcStat();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillRepeatedly(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillRepeatedly(Return(procStatInfo));

    ResourceStats resourceStats = {};
    ASSERT_RESULT_OK(mCollector->onBoottimeCollection(kTestNow, mMockUidStatsCollector,
                                                      mMockProcStatCollector, &resourceStats));

    auto actual = mCollectorPeer->getBoottimeCollectionInfo();

    EXPECT_THAT(actual.records.size(), 1) << "Boot-time collection info doesn't have size 1";

    // Call |onPeriodicCollection| 1 hour past the last boot-time collection event.
    ASSERT_RESULT_OK(
            mCollector->onPeriodicCollection(kTestNow + kTestSystemEventDataCacheDurationSec,
                                             SystemState::NORMAL_MODE, mMockUidStatsCollector,
                                             mMockProcStatCollector, &resourceStats));

    actual = mCollectorPeer->getBoottimeCollectionInfo();

    EXPECT_THAT(actual.records.empty(), true) << "Boot-time collection info records are not empty";
}

TEST_F(PerformanceProfilerTest, TestWakeUpCollectionCacheEviction) {
    const auto [uidStats, userPackageSummaryStats] = sampleUidStats();
    const auto [procStatInfo, systemSummaryStats] = sampleProcStat();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillRepeatedly(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillRepeatedly(Return(procStatInfo));

    ASSERT_RESULT_OK(mCollector->onWakeUpCollection(kTestNow, mMockUidStatsCollector,
                                                    mMockProcStatCollector));

    auto actual = mCollectorPeer->getWakeUpCollectionInfo();

    EXPECT_THAT(actual.records.size(), 1) << "Wake-up collection info doesn't have size 1";

    ResourceStats resourceStats = {};

    // Call |onPeriodicCollection| 1 hour past the last wake-up collection event.
    ASSERT_RESULT_OK(
            mCollector->onPeriodicCollection(kTestNow + kTestSystemEventDataCacheDurationSec,
                                             SystemState::NORMAL_MODE, mMockUidStatsCollector,
                                             mMockProcStatCollector, &resourceStats));

    actual = mCollectorPeer->getWakeUpCollectionInfo();

    EXPECT_THAT(actual.records.empty(), true) << "Wake-up collection info records are not empty";
}

TEST_F(PerformanceProfilerTest, TestUserSwitchCollectionCacheEviction) {
    auto [uidStats, userPackageSummaryStats] = sampleUidStats();
    auto [procStatInfo, systemSummaryStats] = sampleProcStat();

    EXPECT_CALL(*mMockUidStatsCollector, deltaStats()).WillRepeatedly(Return(uidStats));
    EXPECT_CALL(*mMockProcStatCollector, deltaStats()).WillRepeatedly(Return(procStatInfo));

    auto updatedNow = kTestNow;

    for (userid_t userId = 100; userId < 100 + kTestMaxUserSwitchEvents; ++userId) {
        ASSERT_RESULT_OK(mCollector->onUserSwitchCollection(updatedNow, userId, userId + 1,
                                                            mMockUidStatsCollector,
                                                            mMockProcStatCollector));
        updatedNow += kTestSystemEventDataCacheDurationSec;
    }

    const auto& actual = mCollectorPeer->getUserSwitchCollectionInfos();

    EXPECT_THAT(actual.size(), kTestMaxUserSwitchEvents);

    updatedNow = kTestNow + kTestSystemEventDataCacheDurationSec;
    ResourceStats resourceStats = {};
    for (int i = 1; i <= kTestMaxUserSwitchEvents; ++i) {
        ASSERT_RESULT_OK(mCollector->onPeriodicCollection(updatedNow, SystemState::NORMAL_MODE,
                                                          mMockUidStatsCollector,
                                                          mMockProcStatCollector, &resourceStats));

        const auto& actual = mCollectorPeer->getUserSwitchCollectionInfos();

        EXPECT_THAT(actual.size(), kTestMaxUserSwitchEvents - i)
                << "User-switch collection size is incorrect";

        updatedNow += kTestSystemEventDataCacheDurationSec;
    }
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
