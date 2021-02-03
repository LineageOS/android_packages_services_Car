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

namespace android {
namespace automotive {
namespace watchdog {

using ::android::automotive::watchdog::internal::ComponentType;
using ::android::automotive::watchdog::internal::IoOveruseConfiguration;
using ::android::base::Error;
using ::android::base::Result;

Result<void> IoOveruseMonitor::start() {
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
    return {};
}

void IoOveruseMonitor::terminate() {
    // TODO(b/167240592): Clear the in-memory cache.
    return;
}

Result<void> IoOveruseMonitor::onPeriodicCollection(
        time_t /*time*/, const android::wp<UidIoStats>& uidIoStats,
        const android::wp<ProcStat>& /*procStat*/,
        const android::wp<ProcPidStat>& /*procPidStat*/) {
    if (uidIoStats == nullptr) {
        return Error() << "Per-UID I/O stats collector must not be empty";
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
        time_t /*time*/, const std::unordered_set<std::string>& /*filterPackages*/,
        const android::wp<UidIoStats>& uidIoStats, const android::wp<ProcStat>& /*procStat*/,
        const android::wp<ProcPidStat>& /*procPidStat*/) {
    if (uidIoStats == nullptr) {
        return Error() << "Per-UID I/O stats collector must not be empty";
    }
    // TODO(b/167240592): Same as |onPeriodicCollection| because IoOveruseMonitor doesn't do
    //  anything special for custom collection.

    return {};
}

Result<void> IoOveruseMonitor::onGarageModeCollection(
        time_t /*time*/, const android::wp<UidIoStats>& uidIoStats,
        const android::wp<ProcStat>& /*procStat*/,
        const android::wp<ProcPidStat>& /*procPidStat*/) {
    if (uidIoStats == nullptr) {
        return Error() << "Per-UID I/O stats collector must not be empty";
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

Result<void> IoOveruseMonitor::onDump(int /*fd*/) {
    // TODO(b/167240592): Dump the list of killed/disabled packages. Dump the list of packages that
    //  exceed xx% of their threshold.
    return {};
}

Result<void> IoOveruseMonitor::updateIoOveruseConfiguration(ComponentType type,
                                                            const IoOveruseConfiguration& config) {
    Mutex::Autolock lock(mMutex);
    return mIoOveruseConfigs.update(type, config);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
