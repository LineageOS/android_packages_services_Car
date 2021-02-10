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
#include "ProcPidStat.h"
#include "ProcStat.h"
#include "UidIoStats.h"
#include "WatchdogPerfService.h"

#include <android-base/result.h>
#include <android/automotive/watchdog/internal/ComponentType.h>
#include <android/automotive/watchdog/internal/IoOveruseConfiguration.h>
#include <utils/Mutex.h>

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
    IoOveruseMonitor() :
          mIsInitialized(false),
          mIoOveruseConfigs({}),
          mSystemWideWrittenBytes({}) {}

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
    //  WatchdogProcessService. On POWER_CYCLE_SHUTDOWN_PREPARE, switch to garage mode collection
    //  and call this method via the IDataProcessorInterface.
    android::base::Result<void> onGarageModeCollection(time_t time,
                                                       const android::wp<UidIoStats>& uidIoStats,
                                                       const android::wp<ProcStat>& procStat,
                                                       const android::wp<ProcPidStat>& procPidStat);

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

    // Makes sure only one collection is running at any given time.
    Mutex mMutex;

    bool mIsInitialized GUARDED_BY(mMutex);

    // Summary of configs available for all the components and system-wide overuse alert thresholds.
    IoOveruseConfigs mIoOveruseConfigs GUARDED_BY(mMutex);

    /*
     * Delta of system-wide written kib across all disks from the last |mPeriodicMonitorBufferSize|
     * polls along with the polling duration.
     */
    std::vector<WrittenBytesSnapshot> mSystemWideWrittenBytes GUARDED_BY(mMutex);
    size_t mPeriodicMonitorBufferSize GUARDED_BY(mMutex);
    time_t mLastPollTime GUARDED_BY(mMutex);

    friend class WatchdogPerfService;

    // For unit tests.
    friend class internal::IoOveruseMonitorPeer;
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_IOOVERUSEMONITOR_H_
