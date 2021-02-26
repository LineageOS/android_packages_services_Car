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

#ifndef CPP_WATCHDOG_SERVER_SRC_IOOVERUSEMONITOR_H_
#define CPP_WATCHDOG_SERVER_SRC_IOOVERUSEMONITOR_H_

#include "IoOveruseConfigs.h"
#include "PackageInfoResolver.h"
#include "ProcPidStat.h"
#include "ProcStat.h"
#include "UidIoStats.h"
#include "WatchdogPerfService.h"

#include <android-base/result.h>
#include <android/automotive/watchdog/internal/ComponentType.h>
#include <android/automotive/watchdog/internal/IoOveruseConfiguration.h>
#include <android/automotive/watchdog/internal/PackageInfo.h>
#include <android/automotive/watchdog/internal/PackageIoOveruseStats.h>
#include <android/automotive/watchdog/internal/PerStateBytes.h>
#include <cutils/multiuser.h>
#include <utils/Mutex.h>

#include <time.h>

#include <string>
#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

// Number of periodically monitored stats to cache in memory.
constexpr int32_t kDefaultPeriodicMonitorBufferSize = 360;

// Forward declaration for testing use only.
namespace internal {

class IoOveruseMonitorPeer;

}  // namespace internal

// IoOveruseMonitor implements the I/O overuse monitoring module.
class IoOveruseMonitor : public IDataProcessorInterface {
public:
    explicit IoOveruseMonitor(
            const android::sp<IWatchdogServiceHelperInterface>& watchdogServiceHelper) :
          mWatchdogServiceHelper(watchdogServiceHelper),
          mSystemWideWrittenBytes({}),
          mPeriodicMonitorBufferSize(0),
          mLastSystemWideIoMonitorTime(0),
          mUserPackageDailyIoUsageById({}),
          mIoOveruseWarnPercentage(0),
          mLastUserPackageIoMonitorTime(0) {}

    ~IoOveruseMonitor() { terminate(); }

    std::string name() { return "IoOveruseMonitor"; }

    // WatchdogBinderMediator API implementation.
    virtual android::base::Result<void> updateIoOveruseConfiguration(
            android::automotive::watchdog::internal::ComponentType type,
            const android::automotive::watchdog::internal::IoOveruseConfiguration& config);

    // Implements IDataProcessorInterface.
    android::base::Result<void> onBoottimeCollection(
            time_t /*time*/, const android::wp<UidIoStats>& /*uidIoStats*/,
            const android::wp<ProcStat>& /*procStat*/,
            const android::wp<ProcPidStat>& /*procPidStat*/) {
        // No I/O overuse monitoring during boot-time.
        return {};
    }

    // TODO(b/167240592): Forward WatchdogBinderMediator's notifySystemStateChange call to
    //  WatchdogPerfService. On POWER_CYCLE_SHUTDOWN_PREPARE, switch to garage mode collection
    //  and pass collection flag as a param in this API to indicate garage mode collection.
    android::base::Result<void> onPeriodicCollection(time_t time,
                                                     const android::wp<UidIoStats>& uidIoStats,
                                                     const android::wp<ProcStat>& procStat,
                                                     const android::wp<ProcPidStat>& procPidStat);

    android::base::Result<void> onCustomCollection(
            time_t time, const std::unordered_set<std::string>& filterPackages,
            const android::wp<UidIoStats>& uidIoStats, const android::wp<ProcStat>& procStat,
            const android::wp<ProcPidStat>& procPidStat);

    android::base::Result<void> onPeriodicMonitor(
            time_t time, const android::wp<IProcDiskStatsInterface>& procDiskStats,
            const std::function<void()>& alertHandler);

    // TODO(b/167240592): Forward WatchdogBinderMediator's notifySystemStateChange call to
    //  WatchdogProcessService. On POWER_CYCLE_SHUTDOWN_PREPARE_COMPLETE, call this method via
    //  the IDataProcessorInterface. onShutdownPrepareComplete, IoOveruseMonitor will flush
    //  in-memory stats to disk.
    android::base::Result<void> onShutdownPrepareComplete();

    android::base::Result<void> onDump(int fd);

    android::base::Result<void> onCustomCollectionDump(int /*fd*/) {
        // No special processing for custom collection. Thus no custom collection dump.
        return {};
    }

protected:
    android::base::Result<void> init();

    void terminate();

private:
    struct WrittenBytesSnapshot {
        double pollDurationInSecs;
        uint64_t bytesInKib;
    };

    struct UserPackageIoUsage {
        UserPackageIoUsage(const android::automotive::watchdog::internal::PackageInfo& packageInfo,
                           const IoUsage& IoUsage, const bool isGarageModeActive);
        android::automotive::watchdog::internal::PackageInfo packageInfo = {};
        android::automotive::watchdog::internal::PerStateBytes writtenBytes = {};
        android::automotive::watchdog::internal::PerStateBytes forgivenWriteBytes = {};
        int numOveruses = 0;
        bool isPackageWarned = false;

        UserPackageIoUsage& operator+=(const UserPackageIoUsage& r);

        const std::string id() const {
            const auto& id = packageInfo.packageIdentifier;
            return StringPrintf("%s:%" PRId32, String8(id.name).c_str(),
                                multiuser_get_user_id(id.uid));
        }
    };

    bool isInitializedLocked() { return mIoOveruseConfigs != nullptr; }

    void notifyNativePackages(
            const std::vector<android::automotive::watchdog::internal::PackageIoOveruseStats>&
                    stats);

    void notifyWatchdogService(
            const std::vector<android::automotive::watchdog::internal::PackageIoOveruseStats>&
                    stats);

    // Local IPackageInfoResolverInterface instance. Useful to mock in tests.
    sp<IPackageInfoResolverInterface> mPackageInfoResolver;

    // Makes sure only one collection is running at any given time.
    mutable Mutex mMutex;

    android::sp<IWatchdogServiceHelperInterface> mWatchdogServiceHelper GUARDED_BY(mMutex);

    // Summary of configs available for all the components and system-wide overuse alert thresholds.
    sp<IIoOveruseConfigs> mIoOveruseConfigs GUARDED_BY(mMutex);

    /*
     * Delta of system-wide written kib across all disks from the last |mPeriodicMonitorBufferSize|
     * polls along with the polling duration.
     */
    std::vector<WrittenBytesSnapshot> mSystemWideWrittenBytes GUARDED_BY(mMutex);
    size_t mPeriodicMonitorBufferSize GUARDED_BY(mMutex);
    time_t mLastSystemWideIoMonitorTime GUARDED_BY(mMutex);

    // Cache of per user package I/O usage.
    std::unordered_map<std::string, UserPackageIoUsage> mUserPackageDailyIoUsageById
            GUARDED_BY(mMutex);
    double mIoOveruseWarnPercentage GUARDED_BY(mMutex);
    time_t mLastUserPackageIoMonitorTime GUARDED_BY(mMutex);

    friend class WatchdogPerfService;

    // For unit tests.
    friend class internal::IoOveruseMonitorPeer;
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_IOOVERUSEMONITOR_H_
