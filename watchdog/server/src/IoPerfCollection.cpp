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

#define LOG_TAG "carwatchdogd"

#include "IoPerfCollection.h"

#include <android-base/stringprintf.h>
#include <binder/IServiceManager.h>
#include <cutils/android_filesystem_config.h>
#include <inttypes.h>
#include <log/log.h>
#include <pwd.h>

#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

using android::defaultServiceManager;
using android::IBinder;
using android::IServiceManager;
using android::sp;
using android::String16;
using android::base::Error;
using android::base::Result;
using android::base::StringAppendF;
using android::content::pm::IPackageManagerNative;

namespace {

double percentage(uint64_t numer, uint64_t denom) {
    return denom == 0 ? 0.0 : (static_cast<double>(numer) / static_cast<double>(denom)) * 100.0;
}

}  // namespace

std::string toString(const UidIoPerfData& data) {
    std::string buffer;
    StringAppendF(&buffer, "Top N Reads:\n");
    StringAppendF(&buffer,
                  "Android User ID, Package Name, Foreground Bytes, Foreground Bytes %%, "
                  "Foreground Fsync, Foreground Fsync %%, Background Bytes, Background Bytes %%, "
                  "Background Fsync, Background Fsync %%\n");
    for (const auto& stat : data.topNReads) {
        StringAppendF(&buffer, "%" PRIu32 ", %s", stat.userId, stat.packageName.c_str());
        for (int i = 0; i < UID_STATES; ++i) {
            StringAppendF(&buffer, ", %" PRIu64 ", %.2f%%, %" PRIu64 ", %.2f%%", stat.bytes[i],
                          stat.bytesPercent[i], stat.fsync[i], stat.fsyncPercent[i]);
        }
        StringAppendF(&buffer, "\n");
    }
    StringAppendF(&buffer, "Top N Writes:\n");
    StringAppendF(&buffer,
                  "Android User ID, Package Name, Foreground Bytes, Foreground Bytes %%, "
                  "Foreground Fsync, Foreground Fsync %%, Background Bytes, Background Bytes %%, "
                  "Background Fsync, Background Fsync %%\n");
    for (const auto& stat : data.topNWrites) {
        StringAppendF(&buffer, "%" PRIu32 ", %s", stat.userId, stat.packageName.c_str());
        for (int i = 0; i < UID_STATES; ++i) {
            StringAppendF(&buffer, ", %" PRIu64 ", %.2f%%, %" PRIu64 ", %.2f%%", stat.bytes[i],
                          stat.bytesPercent[i], stat.fsync[i], stat.fsyncPercent[i]);
        }
        StringAppendF(&buffer, "\n");
    }
    return buffer;
}

std::string toString(const SystemIoPerfData& data) {
    std::string buffer;
    StringAppendF(&buffer, "CPU I/O wait time/percent: %" PRIu64 " / %.2f%%\n", data.cpuIoWaitTime,
                  data.cpuIoWaitPercent);
    StringAppendF(&buffer, "Number of I/O blocked processes/percent: %" PRIu32 " / %.2f%%\n",
                  data.ioBlockedProcessesCnt, data.ioBlockedProcessesPercent);
    return buffer;
}

Result<void> IoPerfCollection::start() {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != CollectionEvent::NONE) {
        return Error() << "Cannot start I/O performance collection more than once";
    }
    mCurrCollectionEvent = CollectionEvent::BOOT_TIME;

    // TODO(b/148486340): Implement this method.
    return Error() << "Unimplemented method";
}

Result<void> IoPerfCollection::onBootFinished() {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != CollectionEvent::BOOT_TIME) {
        return Error() << "Current collection event " << toEventString(mCurrCollectionEvent)
                       << " != " << toEventString(CollectionEvent::BOOT_TIME)
                       << " collection event";
    }

    // TODO(b/148486340): Implement this method.
    return Error() << "Unimplemented method";
}

status_t IoPerfCollection::dump(int /*fd*/) {
    Mutex::Autolock lock(mMutex);

    // TODO(b/148486340): Implement this method.

    // TODO: Report when any of the proc collectors' enabled() method returns false.

    return INVALID_OPERATION;
}

status_t IoPerfCollection::startCustomCollection(std::chrono::seconds /*interval*/,
                                                 std::chrono::seconds /*maxDuration*/) {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != CollectionEvent::PERIODIC) {
        ALOGE(
            "Cannot start a custom collection when "
            "the current collection event %s != %s collection event",
            toEventString(mCurrCollectionEvent).c_str(),
            toEventString(CollectionEvent::PERIODIC).c_str());
        return INVALID_OPERATION;
    }

    // TODO(b/148486340): Implement this method.
    return INVALID_OPERATION;
}

status_t IoPerfCollection::endCustomCollection(int /*fd*/) {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != CollectionEvent::CUSTOM) {
        ALOGE("No custom collection is running");
        return INVALID_OPERATION;
    }

    // TODO(b/148486340): Implement this method.

    // TODO: Report when any of the proc collectors' enabled() method returns false.

    return INVALID_OPERATION;
}

Result<void> IoPerfCollection::collect() {
    Mutex::Autolock lock(mMutex);

    // TODO(b/148486340): Implement this method.
    return Error() << "Unimplemented method";
}

Result<void> IoPerfCollection::collectUidIoPerfDataLocked(UidIoPerfData* uidIoPerfData) {
    if (!mUidIoStats.enabled()) {
        // Don't return an error to avoid log spamming on every collection. Instead, report this
        // once in the generated dump.
        return {};
    }

    const Result<std::unordered_map<uint32_t, UidIoUsage>>& usage = mUidIoStats.collect();
    if (!usage) {
        return Error() << "Failed to collect uid I/O usage: " << usage.error();
    }

    // Fetch only the top N reads and writes from the usage records.
    UidIoUsage tempUsage = {};
    std::vector<const UidIoUsage*> topNReads(mTopNStatsPerCategory, &tempUsage);
    std::vector<const UidIoUsage*> topNWrites(mTopNStatsPerCategory, &tempUsage);
    uint64_t total[METRIC_TYPES][UID_STATES] = {{0}};
    std::unordered_set<uint32_t> unmappedUids;

    for (const auto& uIt : *usage) {
        const UidIoUsage& curUsage = uIt.second;
        if (curUsage.ios.isZero()) {
            continue;
        }
        if (mUidToPackageNameMapping.find(curUsage.uid) == mUidToPackageNameMapping.end()) {
            unmappedUids.insert(curUsage.uid);
        }
        total[READ_BYTES][FOREGROUND] += curUsage.ios.metrics[READ_BYTES][FOREGROUND];
        total[READ_BYTES][BACKGROUND] += curUsage.ios.metrics[READ_BYTES][BACKGROUND];
        total[WRITE_BYTES][FOREGROUND] += curUsage.ios.metrics[WRITE_BYTES][FOREGROUND];
        total[WRITE_BYTES][BACKGROUND] += curUsage.ios.metrics[WRITE_BYTES][BACKGROUND];
        total[FSYNC_COUNT][FOREGROUND] += curUsage.ios.metrics[FSYNC_COUNT][FOREGROUND];
        total[FSYNC_COUNT][BACKGROUND] += curUsage.ios.metrics[FSYNC_COUNT][BACKGROUND];

        for (auto it = topNReads.begin(); it != topNReads.end(); ++it) {
            const UidIoUsage* curRead = *it;
            if (curRead->ios.sumReadBytes() > curUsage.ios.sumReadBytes()) {
                continue;
            }
            topNReads.erase(topNReads.end() - 1);
            topNReads.emplace(it, &curUsage);
            break;
        }
        for (auto it = topNWrites.begin(); it != topNWrites.end(); ++it) {
            const UidIoUsage* curWrite = *it;
            if (curWrite->ios.sumWriteBytes() > curUsage.ios.sumWriteBytes()) {
                continue;
            }
            topNWrites.erase(topNWrites.end() - 1);
            topNWrites.emplace(it, &curUsage);
            break;
        }
    }

    const auto& ret = updateUidToPackageNameMapping(unmappedUids);
    if (!ret) {
        ALOGW("%s", ret.error().message().c_str());
    }

    // Convert the top N I/O usage to UidIoPerfData.
    for (const auto& usage : topNReads) {
        if (usage->ios.isZero()) {
            // End of non-zero usage records. This case occurs when the number of UIDs with active
            // I/O operations is < |kTopNStatsPerCategory|.
            break;
        }
        struct UidIoPerfData::Stats stats = {
            .userId = multiuser_get_user_id(usage->uid),
            .packageName = std::to_string(usage->uid),
            .bytes = {usage->ios.metrics[READ_BYTES][FOREGROUND],
                      usage->ios.metrics[READ_BYTES][BACKGROUND]},
            .bytesPercent = {percentage(usage->ios.metrics[READ_BYTES][FOREGROUND],
                                        total[READ_BYTES][FOREGROUND]),
                             percentage(usage->ios.metrics[READ_BYTES][BACKGROUND],
                                        total[READ_BYTES][BACKGROUND])},
            .fsync = {usage->ios.metrics[FSYNC_COUNT][FOREGROUND],
                      usage->ios.metrics[FSYNC_COUNT][BACKGROUND]},
            .fsyncPercent = {percentage(usage->ios.metrics[FSYNC_COUNT][FOREGROUND],
                                        total[FSYNC_COUNT][FOREGROUND]),
                             percentage(usage->ios.metrics[FSYNC_COUNT][BACKGROUND],
                                        total[FSYNC_COUNT][BACKGROUND])},
        };

        if (mUidToPackageNameMapping.find(usage->uid) != mUidToPackageNameMapping.end()) {
            stats.packageName = mUidToPackageNameMapping[usage->uid];
        }

        uidIoPerfData->topNReads.emplace_back(stats);
    }

    for (const auto& usage : topNWrites) {
        if (usage->ios.isZero()) {
            // End of non-zero usage records. This case occurs when the number of UIDs with active
            // I/O operations is < |kTopNStatsPerCategory|.
            break;
        }
        struct UidIoPerfData::Stats stats = {
            .userId = multiuser_get_user_id(usage->uid),
            .packageName = std::to_string(usage->uid),
            .bytes = {usage->ios.metrics[WRITE_BYTES][FOREGROUND],
                      usage->ios.metrics[WRITE_BYTES][BACKGROUND]},
            .bytesPercent = {percentage(usage->ios.metrics[WRITE_BYTES][FOREGROUND],
                                        total[WRITE_BYTES][FOREGROUND]),
                             percentage(usage->ios.metrics[WRITE_BYTES][BACKGROUND],
                                        total[WRITE_BYTES][BACKGROUND])},
            .fsync = {usage->ios.metrics[FSYNC_COUNT][FOREGROUND],
                      usage->ios.metrics[FSYNC_COUNT][BACKGROUND]},
            .fsyncPercent = {percentage(usage->ios.metrics[FSYNC_COUNT][FOREGROUND],
                                        total[FSYNC_COUNT][FOREGROUND]),
                             percentage(usage->ios.metrics[FSYNC_COUNT][BACKGROUND],
                                        total[FSYNC_COUNT][BACKGROUND])},
        };

        if (mUidToPackageNameMapping.find(usage->uid) != mUidToPackageNameMapping.end()) {
            stats.packageName = mUidToPackageNameMapping[usage->uid];
        }

        uidIoPerfData->topNWrites.emplace_back(stats);
    }
    return {};
}

Result<void> IoPerfCollection::collectSystemIoPerfDataLocked(SystemIoPerfData* systemIoPerfData) {
    if (!mProcStat.enabled()) {
        // Don't return an error to avoid log spamming on every collection. Instead, report this
        // once in the generated dump.
        return {};
    }

    const Result<ProcStatInfo>& procStatInfo = mProcStat.collect();
    if (!procStatInfo) {
        return Error() << "Failed to collect proc stats: " << procStatInfo.error();
    }

    systemIoPerfData->cpuIoWaitTime = procStatInfo->cpuStats.ioWaitTime;
    systemIoPerfData->cpuIoWaitPercent =
            percentage(procStatInfo->cpuStats.ioWaitTime, procStatInfo->totalCpuTime());
    systemIoPerfData->ioBlockedProcessesCnt = procStatInfo->ioBlockedProcessesCnt;
    systemIoPerfData->ioBlockedProcessesPercent =
            percentage(procStatInfo->ioBlockedProcessesCnt, procStatInfo->totalProcessesCnt());
    return {};
}

Result<void> IoPerfCollection::collectProcessIoPerfDataLocked(
    ProcessIoPerfData* /*processIoPerfData*/) {
    // TODO(b/148486340): Implement this method.
    return Error() << "Unimplemented method";
}

Result<void> IoPerfCollection::updateUidToPackageNameMapping(
    const std::unordered_set<uint32_t>& uids) {
    std::vector<int32_t> appUids;

    for (const auto& uid : uids) {
        if (uid >= AID_APP_START) {
            appUids.emplace_back(static_cast<int32_t>(uid));
            continue;
        }
        // System/native UIDs.
        passwd* usrpwd = getpwuid(uid);
        if (!usrpwd) {
            continue;
        }
        mUidToPackageNameMapping[uid] = std::string(usrpwd->pw_name);
    }

    if (appUids.empty()) {
        return {};
    }

    if (mPackageManager == nullptr) {
        auto ret = retrievePackageManager();
        if (!ret) {
            return Error() << "Failed to retrieve package manager: " << ret.error();
        }
    }

    std::vector<std::string> packageNames;
    const binder::Status& status = mPackageManager->getNamesForUids(appUids, &packageNames);
    if (!status.isOk()) {
        return Error() << "package_native::getNamesForUids failed: " << status.exceptionMessage();
    }

    for (uint32_t i = 0; i < appUids.size(); i++) {
        if (!packageNames[i].empty()) {
            mUidToPackageNameMapping[appUids[i]] = packageNames[i];
        }
    }

    return {};
}

Result<void> IoPerfCollection::retrievePackageManager() {
    const sp<IServiceManager> sm = defaultServiceManager();
    if (sm == nullptr) {
        return Error() << "Failed to retrieve defaultServiceManager";
    }

    sp<IBinder> binder = sm->getService(String16("package_native"));
    if (binder == nullptr) {
        return Error() << "Failed to get service package_native";
    }
    mPackageManager = interface_cast<IPackageManagerNative>(binder);
    return {};
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
