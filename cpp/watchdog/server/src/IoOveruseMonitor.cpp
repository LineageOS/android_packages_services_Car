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
#include <android/automotive/watchdog/internal/PackageIdentifier.h>
#include <android/automotive/watchdog/internal/UidType.h>
#include <binder/Status.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::automotive::watchdog::internal::ComponentType;
using ::android::automotive::watchdog::internal::IoOveruseConfiguration;
using ::android::automotive::watchdog::internal::PackageIdentifier;
using ::android::automotive::watchdog::internal::PackageInfo;
using ::android::automotive::watchdog::internal::PackageIoOveruseStats;
using ::android::automotive::watchdog::internal::PerStateBytes;
using ::android::automotive::watchdog::internal::UidType;
using ::android::base::Error;
using ::android::base::Result;
using ::android::binder::Status;

constexpr double kDefaultIoOveruseWarnPercentage = 80;
constexpr size_t kMaxPeriodicMonitorBufferSize = 1000;
constexpr int kMonitoringPeriodInDays = 1;

Result<void> IoOveruseMonitor::init() {
    Mutex::Autolock lock(mMutex);
    if (isInitializedLocked()) {
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
    mIoOveruseWarnPercentage = static_cast<double>(
            sysprop::ioOveruseWarnPercentage().value_or(kDefaultIoOveruseWarnPercentage));
    /*
     * TODO(b/167240592): Read the latest I/O overuse config, last per-package I/O usage, and
     *  last N days per-package I/O overuse stats.
     *  The latest I/O overuse config is read in this order:
     *  1. From /data partition as this contains the latest config and any updates received from OEM
     *    and system applications.
     *  2. From /system and /vendor partitions as this contains the default configs shipped with the
     *    the image.
     */

    mIoOveruseConfigs = new IoOveruseConfigs();
    // TODO(b/167240592): Read the vendor package prefixes from disk before the below call.
    mPackageInfoResolver = PackageInfoResolver::getInstance();
    mPackageInfoResolver->setVendorPackagePrefixes(mIoOveruseConfigs->vendorPackagePrefixes());
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
        time_t time, const android::wp<UidIoStats>& uidIoStats,
        [[maybe_unused]] const android::wp<ProcStat>& procStat,
        [[maybe_unused]] const android::wp<ProcPidStat>& procPidStat) {
    if (uidIoStats == nullptr) {
        return Error() << "Per-UID I/O stats collector must not be null";
    }

    Mutex::Autolock lock(mMutex);
    struct tm prevGmt, curGmt;
    gmtime_r(&mLastUserPackageIoMonitorTime, &prevGmt);
    gmtime_r(&time, &curGmt);
    if (prevGmt.tm_yday != curGmt.tm_yday || prevGmt.tm_year != curGmt.tm_year) {
        /*
         * Date changed so reset the daily I/O usage cache.
         *
         * TODO(b/170741935): Ping CarWatchdogService on date change so it can re-enable the daily
         *  disabled packages. Also sync prev day's stats with CarWatchdogService.
         */
        mUserPackageDailyIoUsageById.clear();
    }
    mLastUserPackageIoMonitorTime = time;

    const auto perUidIoUsage = uidIoStats.promote()->deltaStats();
    /*
     * TODO(b/167240592): Maybe move the packageInfo fetching logic into UidIoStats module.
     *  This will also help avoid fetching package names in IoPerfCollection module.
     */
    std::vector<uid_t> seenUids;
    for (const auto& [uid, uidIoStats] : perUidIoUsage) {
        seenUids.push_back(uid);
    }
    const auto packageInfoByUid = mPackageInfoResolver->getPackageInfosForUids(seenUids);
    std::vector<PackageIoOveruseStats> overusingNativeStats;
    std::vector<PackageIoOveruseStats> overusingAppStats;
    for (const auto& [uid, uidIoStats] : perUidIoUsage) {
        const auto& packageInfo = packageInfoByUid.find(uid);
        if (packageInfo == packageInfoByUid.end()) {
            continue;
        }
        /*
         * TODO(b/167240592): Derive the garage mode status from the collection flag, which will
         *  be added to the |onPeriodicCollection| API.
         */
        UserPackageIoUsage curUsage(packageInfo->second, uidIoStats.ios,
                                    /*isGarageModeActive=*/false);
        UserPackageIoUsage* dailyIoUsage;
        if (auto cachedUsage = mUserPackageDailyIoUsageById.find(curUsage.id());
            cachedUsage != mUserPackageDailyIoUsageById.end()) {
            cachedUsage->second += curUsage;
            dailyIoUsage = &cachedUsage->second;
        } else {
            const auto& [it, wasInserted] = mUserPackageDailyIoUsageById.insert(
                    std::pair(curUsage.id(), std::move(curUsage)));
            dailyIoUsage = &it->second;
        }

        const auto diff = [](const PerStateBytes& lhs, const PerStateBytes& rhs) -> PerStateBytes {
            const auto sub = [](const uint64_t& l, const uint64_t& r) -> uint64_t {
                return l >= r ? (l - r) : 0;
            };
            PerStateBytes result;
            result.foregroundBytes = sub(lhs.foregroundBytes, rhs.foregroundBytes);
            result.backgroundBytes = sub(lhs.backgroundBytes, rhs.backgroundBytes);
            result.garageModeBytes = sub(lhs.garageModeBytes, rhs.garageModeBytes);
            return result;
        };

        const auto threshold = mIoOveruseConfigs->fetchThreshold(dailyIoUsage->packageInfo);

        PackageIoOveruseStats stats;
        stats.packageIdentifier = dailyIoUsage->packageInfo.packageIdentifier;
        stats.periodInDays = kMonitoringPeriodInDays;
        stats.writtenBytes = dailyIoUsage->writtenBytes;
        stats.remainingWriteBytes =
                diff(threshold, diff(dailyIoUsage->writtenBytes, dailyIoUsage->forgivenWriteBytes));
        /*
         * Native packages can't be disabled so don't kill them on I/O overuse rather only notify
         * them.
         */
        if (dailyIoUsage->packageInfo.uidType == UidType::NATIVE) {
            if (stats.remainingWriteBytes.foregroundBytes == 0 ||
                stats.remainingWriteBytes.backgroundBytes == 0 ||
                stats.remainingWriteBytes.garageModeBytes == 0) {
                dailyIoUsage->forgivenWriteBytes = dailyIoUsage->writtenBytes;
                stats.maybeKilledOnOveruse = false;
                stats.numOveruses = ++dailyIoUsage->numOveruses;
                overusingNativeStats.emplace_back(std::move(stats));
            }
            continue;
        }

        const auto exceedsWarnThreshold = [&](double remaining, double threshold) {
            if (threshold == 0) {
                return true;
            }
            double usedPercent = (100 - (remaining / threshold) * 100);
            return usedPercent > mIoOveruseWarnPercentage;
        };
        const bool exceedsWarnWriteBytes =
                exceedsWarnThreshold(stats.remainingWriteBytes.foregroundBytes,
                                     threshold.foregroundBytes) ||
                exceedsWarnThreshold(stats.remainingWriteBytes.backgroundBytes,
                                     threshold.backgroundBytes) ||
                exceedsWarnThreshold(stats.remainingWriteBytes.garageModeBytes,
                                     threshold.garageModeBytes);
        /*
         * Checking whether a package is safe-to-kill is expensive when done for all packages on
         * each periodic collection. Thus limit this to only packages that need to be warned or
         * notified of I/O overuse. This is less expensive because we expect only few packages
         * per day to overuse I/O.
         */
        if (stats.remainingWriteBytes.foregroundBytes == 0 ||
            stats.remainingWriteBytes.backgroundBytes == 0 ||
            stats.remainingWriteBytes.garageModeBytes == 0) {
            stats.maybeKilledOnOveruse = mIoOveruseConfigs->isSafeToKill(dailyIoUsage->packageInfo);
            // Reset counters as the package may be disabled/killed by car watchdog service.
            dailyIoUsage->forgivenWriteBytes = dailyIoUsage->writtenBytes;
            stats.numOveruses = ++dailyIoUsage->numOveruses;
            dailyIoUsage->isPackageWarned = false;
            overusingAppStats.emplace_back(std::move(stats));
        } else if (exceedsWarnWriteBytes && !dailyIoUsage->isPackageWarned) {
            stats.maybeKilledOnOveruse = mIoOveruseConfigs->isSafeToKill(dailyIoUsage->packageInfo);
            /*
             * No need to warn applications that won't be killed on I/O overuse as they will be sent
             * a notification when they exceed their daily threshold.
             */
            if (stats.maybeKilledOnOveruse) {
                overusingAppStats.emplace_back(std::move(stats));
            }
            // Avoid duplicate warning before the daily threshold exceeded notification is sent.
            dailyIoUsage->isPackageWarned = true;
        }
    }

    if (!overusingNativeStats.empty()) {
        notifyNativePackages(overusingNativeStats);
    }

    if (!overusingAppStats.empty()) {
        notifyWatchdogService(overusingAppStats);
    }

    return {};
}

Result<void> IoOveruseMonitor::onCustomCollection(
        time_t time, [[maybe_unused]] const std::unordered_set<std::string>& filterPackages,
        const android::wp<UidIoStats>& uidIoStats, const android::wp<ProcStat>& procStat,
        const android::wp<ProcPidStat>& procPidStat) {
    // Nothing special for custom collection.
    return onPeriodicCollection(time, uidIoStats, procStat, procPidStat);
}

Result<void> IoOveruseMonitor::onPeriodicMonitor(
        time_t time, const android::wp<IProcDiskStatsInterface>& procDiskStats,
        const std::function<void()>& alertHandler) {
    if (procDiskStats == nullptr) {
        return Error() << "Proc disk stats collector must not be null";
    }
    if (mLastSystemWideIoMonitorTime == 0) {
        /*
         * Do not record the first disk stats as it reflects the aggregated disks stats since the
         * system boot up and is not in sync with the polling period. This will lead to spurious
         * I/O overuse alerting.
         */
        mLastSystemWideIoMonitorTime = time;
        return {};
    }
    const auto diskStats = procDiskStats.promote()->deltaSystemWideDiskStats();
    mSystemWideWrittenBytes.push_back(
            {.pollDurationInSecs = difftime(time, mLastSystemWideIoMonitorTime),
             .bytesInKib = diskStats.numKibWritten});
    for (const auto& threshold : mIoOveruseConfigs->systemWideAlertThresholds()) {
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
    mLastSystemWideIoMonitorTime = time;
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

void IoOveruseMonitor::notifyNativePackages(
        [[maybe_unused]] const std::vector<PackageIoOveruseStats>& stats) {
    /*
     * TODO(b/167240592): Notify native packages via ICarWatchdog's public API and upload metrics.
     */
}

void IoOveruseMonitor::notifyWatchdogService(const std::vector<PackageIoOveruseStats>& stats) {
    if (const auto status = mWatchdogServiceHelper->notifyIoOveruse(stats); !status.isOk()) {
        ALOGW("Failed to notify car watchdog service of I/O overusing packages");
        /*
         * TODO(b/167240592): Upload metrics for all I/O overusing packages with decision as not
         *  killed on I/O overuse.
         */
    }

    /*
     * TODO(b/167240592): Upload metrics only for I/O overusing packages that are not safe to kill
     *  because for other packages car watchdog service will respond with the action taken then the
     *  metrics will be uploaded.
     */
}

Result<void> IoOveruseMonitor::updateIoOveruseConfiguration(ComponentType type,
                                                            const IoOveruseConfiguration& config) {
    Mutex::Autolock lock(mMutex);
    if (!isInitializedLocked()) {
        return Error(Status::EX_ILLEGAL_STATE) << name() << " is not initialized";
    }
    return mIoOveruseConfigs->update(type, config);
}

IoOveruseMonitor::UserPackageIoUsage::UserPackageIoUsage(const PackageInfo& pkgInfo,
                                                         const IoUsage& ioUsage,
                                                         const bool isGarageModeActive) {
    packageInfo = pkgInfo;
    if (isGarageModeActive) {
        writtenBytes.garageModeBytes = ioUsage.sumWriteBytes();
    } else {
        writtenBytes.foregroundBytes = ioUsage.metrics[WRITE_BYTES][FOREGROUND];
        writtenBytes.backgroundBytes = ioUsage.metrics[WRITE_BYTES][BACKGROUND];
    }
}

IoOveruseMonitor::UserPackageIoUsage& IoOveruseMonitor::UserPackageIoUsage::operator+=(
        const UserPackageIoUsage& r) {
    if (id() == r.id()) {
        packageInfo = r.packageInfo;
    }
    const auto sum = [](const uint64_t& l, const uint64_t& r) -> uint64_t {
        return (std::numeric_limits<uint64_t>::max() - l) > r
                ? (l + r)
                : std::numeric_limits<uint64_t>::max();
    };
    writtenBytes.foregroundBytes =
            sum(writtenBytes.foregroundBytes, r.writtenBytes.foregroundBytes);
    writtenBytes.backgroundBytes =
            sum(writtenBytes.backgroundBytes, r.writtenBytes.backgroundBytes);
    writtenBytes.garageModeBytes =
            sum(writtenBytes.garageModeBytes, r.writtenBytes.garageModeBytes);

    return *this;
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
