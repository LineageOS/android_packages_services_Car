/*
 * Copyright 2025 The Android Open Source Project
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

#include "UidStatsCollectorBaseTestUtils.h"

#include <android-base/stringprintf.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::internal::PackageInfo;
using ::aidl::android::automotive::watchdog::internal::UidType;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::testing::AllOf;
using ::testing::Eq;
using ::testing::ExplainMatchResult;
using ::testing::Field;
using ::testing::Matcher;
using ::testing::Return;
using ::testing::UnorderedElementsAre;

namespace {

MATCHER_P(UidBaseStatsEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("packageInfo", &UidBaseStats::packageInfo,
                                          PackageInfoEq(expected.packageInfo)),
                                    Field("ioStats", &UidBaseStats::ioStats, Eq(expected.ioStats))),
                              arg, result_listener);
}

}  // namespace

std::string toString(const UidBaseStats& uidBaseStats) {
    return StringPrintf("UidBaseStats{packageInfo: %s, ioStats: %s}",
                        uidBaseStats.packageInfo.toString().c_str(),
                        uidBaseStats.ioStats.toString().c_str());
}

std::string toString(const std::vector<UidBaseStats>& uidBaseStats) {
    std::string buffer;
    StringAppendF(&buffer, "{");
    for (const auto& stats : uidBaseStats) {
        StringAppendF(&buffer, "%s\n", toString(stats).c_str());
    }
    StringAppendF(&buffer, "}");
    return buffer;
}

std::vector<Matcher<const UidBaseStats&>> UidBaseStatsMatchers(
        const std::vector<UidBaseStats>& uidBaseStats) {
    std::vector<Matcher<const UidBaseStats&>> matchers;
    for (const auto& stats : uidBaseStats) {
        matchers.push_back(UidBaseStatsEq(stats));
    }
    return matchers;
}

std::unordered_map<uid_t, PackageInfo> samplePackageInfoByUid() {
    return {{1001234, constructPackageInfo("system.daemon", 1001234, UidType::NATIVE)},
            {1005678, constructPackageInfo("kitchensink.app", 1005678, UidType::APPLICATION)}};
}

std::unordered_map<uid_t, UidIoStats> sampleUidIoStatsByUid() {
    return {{1001234,
             UidIoStats{/*fgRdBytes=*/3'000, /*bgRdBytes=*/0,
                        /*fgWrBytes=*/500,
                        /*bgWrBytes=*/0, /*fgFsync=*/20,
                        /*bgFsync=*/0}},
            {1005678,
             UidIoStats{/*fgRdBytes=*/30, /*bgRdBytes=*/100,
                        /*fgWrBytes=*/50, /*bgWrBytes=*/200,
                        /*fgFsync=*/45, /*bgFsync=*/60}}};
}

std::vector<UidBaseStats> sampleUidBaseStats() {
    return {{.packageInfo = constructPackageInfo("system.daemon", 1001234, UidType::NATIVE),
             .ioStats = UidIoStats{/*fgRdBytes=*/3'000, /*bgRdBytes=*/0, /*fgWrBytes=*/500,
                                   /*bgWrBytes=*/0, /*fgFsync=*/20, /*bgFsync=*/0}},
            {.packageInfo = constructPackageInfo("kitchensink.app", 1005678, UidType::APPLICATION),
             .ioStats = UidIoStats{/*fgRdBytes=*/30, /*bgRdBytes=*/100, /*fgWrBytes=*/50,
                                   /*bgWrBytes=*/200,
                                   /*fgFsync=*/45, /*bgFsync=*/60}}};
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
