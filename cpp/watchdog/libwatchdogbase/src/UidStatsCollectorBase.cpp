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

#ifdef CARWATCHDOGD_BINARY
#define LOG_TAG "carwatchdogd"
#else
#define LOG_TAG "iowatchdogd"
#endif

#include "UidStatsCollectorBase.h"

#include <algorithm>
#include <unordered_map>
#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::base::Error;
using ::android::base::Result;

uid_t UidBaseStats::uid() const {
    return static_cast<uid_t>(packageInfo.packageIdentifier.uid);
}

bool UidBaseStats::hasPackageInfo() const {
    return !packageInfo.packageIdentifier.name.empty();
}

std::string UidBaseStats::genericPackageName() const {
    if (hasPackageInfo()) {
        return packageInfo.packageIdentifier.name;
    }
    return std::to_string(packageInfo.packageIdentifier.uid);
}

Result<void> UidStatsCollectorBase::collect() {
    Mutex::Autolock lock(mMutex);
    if (mUidIoStatsCollector->enabled()) {
        if (const auto& result = mUidIoStatsCollector->collect(); !result.ok()) {
            return Error() << "Failed to collect per-uid I/O stats: " << result.error();
        }
    }

    mLatestBaseStats = process(mUidIoStatsCollector->latestStats());
    mDeltaBaseStats = process(mUidIoStatsCollector->deltaStats());
    return {};
}

std::vector<UidBaseStats> UidStatsCollectorBase::process(
        const std::unordered_map<uid_t, UidIoStats>& uidIoStatsByUid) const {
    if (uidIoStatsByUid.empty()) {
        return std::vector<UidBaseStats>();
    }
    std::unordered_set<uid_t> uidSet;
    for (const auto& [uid, _] : uidIoStatsByUid) {
        uidSet.insert(uid);
    }
    std::vector<uid_t> uids;
    for (const auto& uid : uidSet) {
        uids.push_back(uid);
    }
    const auto packageInfoByUid = mPackageInfoResolver->getPackageInfosForUids(uids);
    std::vector<UidBaseStats> uidBaseStats;
    for (const auto& uid : uids) {
        UidBaseStats curUidStats;
        if (const auto it = packageInfoByUid.find(uid); it != packageInfoByUid.end()) {
            curUidStats.packageInfo = it->second;
        } else {
            curUidStats.packageInfo.packageIdentifier.uid = uid;
        }
        if (const auto it = uidIoStatsByUid.find(uid); it != uidIoStatsByUid.end()) {
            curUidStats.ioStats = it->second;
        }
        uidBaseStats.emplace_back(std::move(curUidStats));
    }
    return uidBaseStats;
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
