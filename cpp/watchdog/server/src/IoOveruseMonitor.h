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
#include <android-base/stringprintf.h>
#include <android/automotive/watchdog/BnResourceOveruseListener.h>
#include <android/automotive/watchdog/PerStateBytes.h>
#include <android/automotive/watchdog/internal/ComponentType.h>
#include <android/automotive/watchdog/internal/IoOveruseConfiguration.h>
#include <android/automotive/watchdog/internal/PackageInfo.h>
#include <android/automotive/watchdog/internal/PackageIoOveruseStats.h>
#include <utils/Mutex.h>

#include <time.h>

#include <ostream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

// Number of periodically monitored stats to cache in memory.
constexpr int32_t kDefaultPeriodicMonitorBufferSize = 360;

// Forward declaration for testing use only.
namespace internal {

class IoOveruseMonitorPeer;

}  // namespace internal

// Used only in tests.
std::tuple<int64_t, int64_t> calculateStartAndDuration(const time_t& currentTime);

/*
 * IIoOveruseMonitor interface defines the methods that the I/O overuse monitoring module
 * should implement.
 */
class IIoOveruseMonitor : virtual public IDataProcessorInterface {
public:
    // Below API is from internal/ICarWatchdog.aidl. Please refer to the AIDL for description.
    virtual android::base::Result<void> updateIoOveruseConfiguration(
            android::automotive::watchdog::internal::ComponentType type,
            const android::automotive::watchdog::internal::IoOveruseConfiguration& config) = 0;

    // Below methods support APIs from ICarWatchdog.aidl. Please refer to the AIDL for description.
    virtual android::base::Result<void> addIoOveruseListener(
            const sp<IResourceOveruseListener>& listener) = 0;

    virtual android::base::Result<void> removeIoOveruseListener(
            const sp<IResourceOveruseListener>& listener) = 0;

    virtual android::base::Result<void> getIoOveruseStats(IoOveruseStats* ioOveruseStats) = 0;
};

class IoOveruseMonitor final : public IIoOveruseMonitor {
public:
    explicit IoOveruseMonitor(
            const android::sp<IWatchdogServiceHelperInterface>& watchdogServiceHelper) :
          mWatchdogServiceHelper(watchdogServiceHelper),
          mSystemWideWrittenBytes({}),
          mPeriodicMonitorBufferSize(0),
          mLastSystemWideIoMonitorTime(0),
          mUserPackageDailyIoUsageById({}),
          mIoOveruseWarnPercentage(0),
          mLastUserPackageIoMonitorTime(0),
          mOveruseListenersByUid({}),
          mBinderDeathRecipient(new BinderDeathRecipient(this)) {}

    ~IoOveruseMonitor() { terminate(); }

    // Below methods implement IDataProcessorInterface.
    std::string name() { return "IoOveruseMonitor"; }
    friend std::ostream& operator<<(std::ostream& os, const IoOveruseMonitor& monitor);
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

    // Below methods implement AIDL interfaces.
    android::base::Result<void> updateIoOveruseConfiguration(
            android::automotive::watchdog::internal::ComponentType type,
            const android::automotive::watchdog::internal::IoOveruseConfiguration& config);

    android::base::Result<void> addIoOveruseListener(const sp<IResourceOveruseListener>& listener);

    android::base::Result<void> removeIoOveruseListener(
            const sp<IResourceOveruseListener>& listener);

    android::base::Result<void> getIoOveruseStats(IoOveruseStats* ioOveruseStats);

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
        PerStateBytes writtenBytes = {};
        PerStateBytes forgivenWriteBytes = {};
        int totalOveruses = 0;
        bool isPackageWarned = false;

        UserPackageIoUsage& operator+=(const UserPackageIoUsage& r);

        const std::string id() const;
    };

    class BinderDeathRecipient final : public android::IBinder::DeathRecipient {
    public:
        explicit BinderDeathRecipient(const android::sp<IoOveruseMonitor>& service) :
              mService(service) {}

        void binderDied(const android::wp<android::IBinder>& who) override {
            mService->handleBinderDeath(who);
        }

    private:
        android::sp<IoOveruseMonitor> mService;
    };

private:
    bool isInitializedLocked() { return mIoOveruseConfigs != nullptr; }

    void notifyNativePackagesLocked(const std::unordered_map<uid_t, IoOveruseStats>& statsByUid);

    void notifyWatchdogServiceLocked(const std::unordered_map<uid_t, IoOveruseStats>& statsByUid);

    void handleBinderDeath(const wp<IBinder>& who);

    using ListenersByUidMap = std::unordered_map<uid_t, android::sp<IResourceOveruseListener>>;
    using Processor = std::function<void(ListenersByUidMap&, ListenersByUidMap::const_iterator)>;
    bool findListenerAndProcessLocked(const sp<IBinder>& binder, const Processor& processor);

    // Local IPackageInfoResolverInterface instance. Useful to mock in tests.
    sp<IPackageInfoResolverInterface> mPackageInfoResolver;

    // Makes sure only one collection is running at any given time.
    mutable std::shared_mutex mRwMutex;

    android::sp<IWatchdogServiceHelperInterface> mWatchdogServiceHelper GUARDED_BY(mRwMutex);

    // Summary of configs available for all the components and system-wide overuse alert thresholds.
    sp<IIoOveruseConfigs> mIoOveruseConfigs GUARDED_BY(mRwMutex);

    /*
     * Delta of system-wide written kib across all disks from the last |mPeriodicMonitorBufferSize|
     * polls along with the polling duration.
     */
    std::vector<WrittenBytesSnapshot> mSystemWideWrittenBytes GUARDED_BY(mRwMutex);
    size_t mPeriodicMonitorBufferSize GUARDED_BY(mRwMutex);
    time_t mLastSystemWideIoMonitorTime GUARDED_BY(mRwMutex);

    // Cache of per user package I/O usage.
    std::unordered_map<std::string, UserPackageIoUsage> mUserPackageDailyIoUsageById
            GUARDED_BY(mRwMutex);
    double mIoOveruseWarnPercentage GUARDED_BY(mRwMutex);
    time_t mLastUserPackageIoMonitorTime GUARDED_BY(mRwMutex);

    ListenersByUidMap mOveruseListenersByUid GUARDED_BY(mRwMutex);
    android::sp<BinderDeathRecipient> mBinderDeathRecipient;

    friend class WatchdogPerfService;

    // For unit tests.
    friend class internal::IoOveruseMonitorPeer;
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_IOOVERUSEMONITOR_H_
