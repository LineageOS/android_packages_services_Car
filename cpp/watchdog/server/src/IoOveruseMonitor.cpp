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

#include "IoOveruseMonitor.h"

#include "PackageInfoResolver.h"

#include <WatchdogProperties.sysprop.h>
#include <binder/Status.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::automotive::watchdog::internal::ComponentType;
using ::android::automotive::watchdog::internal::IoOveruseConfiguration;
using ::android::base::Error;
using ::android::base::Result;
using ::android::binder::Status;

constexpr size_t kMaxPeriodicMonitorBufferSize = 1000;

Result<void> IoOveruseMonitor::init() {
    Mutex::Autolock lock(mMutex);
    if (mIsInitialized) {
        return Error() << "Cannot initialize " << name() << " more than once";
    }
    mPeriodicMonitorBufferSize = static_cast<size_t>(
            sysprop::periodicMonitorBufferSize().value_or(kDefaultPeriodicMonitorBufferSize));
    if (mPeriodicMonitorBufferSize == 0 ||
        mPeriodicMonitorBufferSize > kMaxPeriodicMonitorBufferSize) {
        return Error() << "Periodic monitor buffer size cannot be zero or above "
                       << kDefaultPeriodicMonitorBufferSize << ". Received "
                       << mPeriodicMonitorBufferSize;
    }
    // TODO(b/167240592): Read the latest I/O overuse config, last per-package I/O usage, and
    //  last N days per-package I/O overuse stats.
    //  The latest I/O overuse config is read in this order:
    //  1. From /data partition as this contains the latest config and any updates received from OEM
    //    and system applications.
    //  2. From /system and /vendor partitions as this contains the default configs shipped with the
    //    the image.

    // TODO(b/167240592): Read the vendor package prefixes from disk before the below call.
    PackageInfoResolver::getInstance()->setVendorPackagePrefixes(
            mIoOveruseConfigs.vendorPackagePrefixes);
    mIsInitialized = true;
    return {};
}

void IoOveruseMonitor::terminate() {
    // TODO(b/167240592): Clear the in-memory cache.
    Mutex::Autolock lock(mMutex);

    ALOGW("Terminating %s", name().c_str());
    mSystemWideWrittenBytes.clear();
    return;
}

Result<void> IoOveruseMonitor::onPeriodicCollection(
        [[maybe_unused]] time_t time, const android::wp<UidIoStats>& uidIoStats,
        [[maybe_unused]] const android::wp<ProcStat>& procStat,
        [[maybe_unused]] const android::wp<ProcPidStat>& procPidStat) {
    if (uidIoStats == nullptr) {
        return Error() << "Per-UID I/O stats collector must not be null";
    }
    // TODO(b/167240592): Aggregate per-package I/O usage and compare against the daily thresholds.
    //  When the date hasn't changed, add the polled data to the in-memory stats.
    //  When the date has changed,
    //      1. Notify CarWatchdogService to re-enable daily disabled apps.
    //      2. Erase the in-memory per-package I/O usage cache as it from the previous day.
    //      3. Use the delta stats to initialize the current day's per-package I/O usage.
    //  On identifying packages that exceed the daily threshold, report and take action.
    return {};
}

Result<void> IoOveruseMonitor::onCustomCollection(
        [[maybe_unused]] time_t time,
        [[maybe_unused]] const std::unordered_set<std::string>& filterPackages,
        const android::wp<UidIoStats>& uidIoStats,
        [[maybe_unused]] const android::wp<ProcStat>& procStat,
        [[maybe_unused]] const android::wp<ProcPidStat>& procPidStat) {
    if (uidIoStats == nullptr) {
        return Error() << "Per-UID I/O stats collector must not be null";
    }
    // TODO(b/167240592): Same as |onPeriodicCollection| because IoOveruseMonitor doesn't do
    //  anything special for custom collection.

    return {};
}

Result<void> IoOveruseMonitor::onPeriodicMonitor(
        time_t time, const android::wp<IProcDiskStatsInterface>& procDiskStats,
        const std::function<void()>& alertHandler) {
    if (procDiskStats == nullptr) {
        return Error() << "Proc disk stats collector must not be null";
    }
    if (mLastPollTime == 0) {
        /*
         * Do not record the first disk stats as it reflects the aggregated disks stats since the
         * system boot up and is not in sync with the polling period. This will lead to spurious
         * I/O overuse alerting.
         */
        mLastPollTime = time;
        return {};
    }
    const auto diskStats = procDiskStats.promote()->deltaSystemWideDiskStats();
    mSystemWideWrittenBytes.push_back({.pollDurationInSecs = difftime(time, mLastPollTime),
                                       .bytesInKib = diskStats.numKibWritten});
    for (const auto& threshold : mIoOveruseConfigs.alertThresholds) {
        uint64_t accountedWrittenKib = 0;
        double accountedDurationInSecs = 0;
        size_t accountedPolls = 0;
        for (auto rit = mSystemWideWrittenBytes.rbegin(); rit != mSystemWideWrittenBytes.rend();
             ++rit) {
            accountedWrittenKib += rit->bytesInKib;
            accountedDurationInSecs += rit->pollDurationInSecs;
            ++accountedPolls;
            if (accountedDurationInSecs >= threshold.durationInSeconds) {
                break;
            }
        }
        // Heuristic to handle spurious alerting when the buffer is partially filled.
        if (const size_t bufferSize = mSystemWideWrittenBytes.size();
            accountedPolls == bufferSize && bufferSize < mPeriodicMonitorBufferSize + 1 &&
            threshold.durationInSeconds > accountedDurationInSecs) {
            continue;
        }
        const double thresholdKbps = threshold.writtenBytesPerSecond / 1024.0;
        if (const auto kbps = accountedWrittenKib / accountedDurationInSecs;
            kbps >= thresholdKbps) {
            alertHandler();
            break;
        }
    }
    if (mSystemWideWrittenBytes.size() > mPeriodicMonitorBufferSize) {
        mSystemWideWrittenBytes.erase(mSystemWideWrittenBytes.begin());  // Erase the oldest entry.
    }
    mLastPollTime = time;
    return {};
}

Result<void> IoOveruseMonitor::onGarageModeCollection(
        [[maybe_unused]] time_t time, const android::wp<UidIoStats>& uidIoStats,
        [[maybe_unused]] const android::wp<ProcStat>& procStat,
        [[maybe_unused]] const android::wp<ProcPidStat>& procPidStat) {
    if (uidIoStats == nullptr) {
        return Error() << "Per-UID I/O stats collector must not be null";
    }
    // TODO(b/167240592): Perform garage mode monitoring.
    //  When this method is called for the first time, the delta stats represents the last I/O usage
    //  stats from the normal mode (aka user interaction mode). Thus add the stats to the in-memory
    //  cache and check for any violation. Then move the normal mode's I/O stats to a separate
    //  cache, which will be written to disk on |onShutdownPrepareComplete|. Then clear the I/O
    //  usage cache so the next call will be initialized fresh.
    //  When the method is called on >= 2nd time, perform the same as |onPeriodicCollection|.
    //  Confirm whether the package_manager service can be used to enable or disable packages in
    //  this mode.

    return {};
}

Result<void> IoOveruseMonitor::onShutdownPrepareComplete() {
    // TODO(b/167240592): Flush in-memory stats to disk.
    return {};
}

Result<void> IoOveruseMonitor::onDump([[maybe_unused]] int fd) {
    // TODO(b/167240592): Dump the list of killed/disabled packages. Dump the list of packages that
    //  exceed xx% of their threshold.
    return {};
}

Result<void> IoOveruseMonitor::updateIoOveruseConfiguration(ComponentType type,
                                                            const IoOveruseConfiguration& config) {
    Mutex::Autolock lock(mMutex);
    if (!mIsInitialized) {
        return Error(Status::EX_ILLEGAL_STATE) << name() << " is not initialized";
    }
    return mIoOveruseConfigs.update(type, config);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
