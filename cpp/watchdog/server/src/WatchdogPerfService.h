/*
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

#pragma once

#include "LooperWrapper.h"
#include "ProcDiskStatsCollector.h"
#include "ProcStatCollector.h"
#include "UidStatsCollector.h"
#include "WatchdogPerfServiceBase.h"
#include "WatchdogServiceHelper.h"

#include <WatchdogProperties.sysprop.h>
#include <aidl/android/automotive/watchdog/internal/ResourceStats.h>
#include <aidl/android/automotive/watchdog/internal/UserState.h>
#include <android-base/chrono_utils.h>
#include <android-base/result.h>
#include <android/util/ProtoOutputStream.h>
#include <cutils/multiuser.h>
#include <gtest/gtest_prod.h>
#include <utils/Errors.h>
#include <utils/Looper.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>
#include <utils/String16.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

#include <time.h>

#include <string>
#include <thread>  // NOLINT(build/c++11)
#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

// Forward declaration for testing use only.
namespace internal {

class WatchdogPerfServicePeer;

}  // namespace internal

constexpr std::chrono::seconds kDefaultPostSystemEventDurationSec = 30s;
constexpr std::chrono::seconds kDefaultWakeUpEventDurationSec = 30s;
constexpr std::chrono::seconds kDefaultUserSwitchTimeoutSec = 30s;

// TODO(b/409786932): Remove using statements from header files
using time_point_millis =
        std::chrono::time_point<std::chrono::system_clock, std::chrono::milliseconds>;

/**
 * DataProcessor defines methods that must be implemented in order to process the data collected
 * by |WatchdogPerfService|.
 */
class DataProcessorInterface : virtual public android::RefBase {
public:
    struct CollectionIntervals {
        std::chrono::milliseconds mBoottimeIntervalMillis = std::chrono::milliseconds(0);
        std::chrono::milliseconds mPeriodicIntervalMillis = std::chrono::milliseconds(0);
        std::chrono::milliseconds mUserSwitchIntervalMillis = std::chrono::milliseconds(0);
        std::chrono::milliseconds mWakeUpIntervalMillis = std::chrono::milliseconds(0);
        std::chrono::milliseconds mCustomIntervalMillis = std::chrono::milliseconds(0);
        bool operator==(const CollectionIntervals& other) const {
            return mBoottimeIntervalMillis == other.mBoottimeIntervalMillis &&
                    mPeriodicIntervalMillis == other.mPeriodicIntervalMillis &&
                    mUserSwitchIntervalMillis == other.mUserSwitchIntervalMillis &&
                    mWakeUpIntervalMillis == other.mWakeUpIntervalMillis &&
                    mCustomIntervalMillis == other.mCustomIntervalMillis;
        }
    };
    DataProcessorInterface() {}
    virtual ~DataProcessorInterface() {}
    // Returns the name of the data processor.
    virtual std::string name() const = 0;
    // Callback to initialize the data processor.
    virtual android::base::Result<void> init() = 0;
    // Callback to terminate the data processor.
    virtual void terminate() = 0;
    // Callback to perform actions (such as clearing stats from previous system startup events)
    // before starting boot-time or wake-up collections.
    virtual android::base::Result<void> onSystemStartup() = 0;
    // Callback to perform actions once CarWatchdogService is registered.
    virtual void onCarWatchdogServiceRegistered() = 0;
    // Callback to process the data collected during boot-time.
    virtual android::base::Result<void> onBoottimeCollection(
            time_point_millis time,
            const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
            const android::wp<ProcStatCollectorInterface>& procStatCollector,
            aidl::android::automotive::watchdog::internal::ResourceStats* resourceStats) = 0;
    // Callback to process the data collected during a wake-up event.
    virtual android::base::Result<void> onWakeUpCollection(
            time_point_millis time,
            const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
            const android::wp<ProcStatCollectorInterface>& procStatCollector) = 0;
    // Callback to process the data collected periodically post boot complete.
    virtual android::base::Result<void> onPeriodicCollection(
            time_point_millis time, SystemState systemState,
            const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
            const android::wp<ProcStatCollectorInterface>& procStatCollector,
            aidl::android::automotive::watchdog::internal::ResourceStats* resourceStats) = 0;
    // Callback to process the data collected during user switch.
    virtual android::base::Result<void> onUserSwitchCollection(
            time_point_millis time, userid_t from, userid_t to,
            const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
            const android::wp<ProcStatCollectorInterface>& procStatCollector) = 0;

    /**
     * Callback to process the data collected on custom collection and filter the results only to
     * the specified |filterPackages|.
     */
    virtual android::base::Result<void> onCustomCollection(
            time_point_millis time, SystemState systemState,
            const std::unordered_set<std::string>& filterPackages,
            const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
            const android::wp<ProcStatCollectorInterface>& procStatCollector,
            aidl::android::automotive::watchdog::internal::ResourceStats* resourceStats) = 0;
    /**
     * Callback to periodically monitor the collected data and trigger the given |alertHandler|
     * on detecting resource overuse.
     */
    virtual android::base::Result<void> onPeriodicMonitor(
            time_t time, const android::wp<ProcDiskStatsCollectorInterface>& procDiskStatsCollector,
            const std::function<void()>& alertHandler) = 0;
    // Callback to dump system event data and periodically collected data.
    virtual android::base::Result<void> onDump(int fd) const = 0;
    // Callback to dump system event data and periodically collected data in proto format.
    virtual android::base::Result<void> onDumpProto(
            const CollectionIntervals& collectionIntervals,
            android::util::ProtoOutputStream& outProto) const = 0;
    /**
     * Callback to dump the custom collected data. When fd == -1, clear the custom collection cache.
     */
    virtual android::base::Result<void> onCustomCollectionDump(int fd) = 0;
};

/**
 * WatchdogPerfServiceInterface collects performance data during boot-time, user switch, system wake
 * up and periodically post system events. It exposes APIs that the main thread and binder service
 * can call to start a collection, switch the collection type, and generate collection dumps.
 */
class WatchdogPerfServiceInterface : virtual public WatchdogPerfServiceBaseInterface {
public:
    // Register a data processor to process the data collected by |WatchdogPerfService|.
    virtual android::base::Result<void> registerDataProcessor(
            android::sp<DataProcessorInterface> processor) = 0;
    // Ends the boot-time collection by switching to periodic collection after the post event
    // duration.
    virtual android::base::Result<void> onBootFinished() = 0;
    // Starts and ends the user switch collection depending on the user states received.
    virtual android::base::Result<void> onUserStateChange(
            userid_t userId,
            const aidl::android::automotive::watchdog::internal::UserState& userState) = 0;
    // Starts wake-up collection. Any running collection is stopped, except for custom collections.
    virtual android::base::Result<void> onSuspendExit() = 0;
    // Called on shutdown enter, suspend enter and hibernation enter.
    virtual android::base::Result<void> onShutdownEnter() = 0;

    // Generates a proto dump from system events and periodic collection events.
    virtual android::base::Result<void> onDumpProto(
            android::util::ProtoOutputStream& outProto) const = 0;
};

class WatchdogPerfService final :
      public WatchdogPerfServiceInterface,
      public WatchdogPerfServiceBase {
public:
    WatchdogPerfService(const android::sp<WatchdogServiceHelperInterface>& watchdogServiceHelper,
                        const std::shared_ptr<PackageInfoResolverInterface>& packageInfoResolver,
                        const std::function<int64_t()>& getElapsedTimeSinceBootMsFunc) :
          WatchdogPerfServiceBase(watchdogServiceHelper, packageInfoResolver),
          kGetElapsedTimeSinceBootMillisFunc(std::move(getElapsedTimeSinceBootMsFunc)),
          mPostSystemEventDurationNs(std::chrono::duration_cast<std::chrono::nanoseconds>(
                  std::chrono::seconds(sysprop::postSystemEventDuration().value_or(
                          kDefaultPostSystemEventDurationSec.count())))),
          mWakeUpDurationNs(std::chrono::duration_cast<std::chrono::nanoseconds>(
                  std::chrono::seconds(sysprop::wakeUpEventDuration().value_or(
                          kDefaultWakeUpEventDurationSec.count())))),
          mUserSwitchTimeoutNs(std::chrono::duration_cast<std::chrono::nanoseconds>(
                  std::chrono::seconds(sysprop::userSwitchTimeout().value_or(
                          kDefaultUserSwitchTimeoutSec.count())))),
          mLastCollectionTimeMillis(0),
          mBoottimeCollection({}),
          mUserSwitchCollection({}),
          mBootCompletedTimeEpochSeconds(0),
          mKernelStartTimeEpochSeconds(0),
          mUidStatsCollector(android::sp<UidStatsCollector>::make(packageInfoResolver)),
          mProcStatCollector(android::sp<ProcStatCollector>::make()),
          mDataProcessors({}) {}

    android::base::Result<void> registerIoOveruseMonitorBase(
            [[maybe_unused]] android::sp<IoOveruseMonitorBaseInterface> ioOveruseMonitor) override {
        // Implemented in registerDataProcessor.
        return android::base::Error() << "This method should only be called from the base"
                                         " class' instance. Use registerDataProcessor in the"
                                         " derived class' instance.";
    }

    android::base::Result<void> registerDataProcessor(
            android::sp<DataProcessorInterface> processor) override;

    void init() override { WatchdogPerfServiceBase::init(); }

    android::base::Result<void> start() override { return WatchdogPerfServiceBase::start(); }

    void terminate() override { WatchdogPerfServiceBase::terminate(); }

    void setSystemState(SystemState systemState) override {
        WatchdogPerfServiceBase::setSystemState(systemState);
    }

    void onCarWatchdogServiceRegistered() override {
        WatchdogPerfServiceBase::onCarWatchdogServiceRegistered();
    }

    android::base::Result<void> onBootFinished() override;

    android::base::Result<void> onUserStateChange(
            userid_t userId,
            const aidl::android::automotive::watchdog::internal::UserState& userState) override;

    android::base::Result<void> onSuspendExit() override;

    android::base::Result<void> onShutdownEnter() override;

    android::base::Result<void> onCustomCollection(int fd, const char** args,
                                                   uint32_t numArgs) override {
        return WatchdogPerfServiceBase::onCustomCollection(fd, args, numArgs);
    }

    android::base::Result<void> onDump(int fd) const override {
        return WatchdogPerfServiceBase::onDump(fd);
    };
    android::base::Result<void> onDumpProto(
            android::util::ProtoOutputStream& outProto) const override;

    bool dumpHelpText(int fd) const override;

private:
    struct UserSwitchEventMetadata : EventMetadata {
        // User id of user being switched from.
        userid_t from = 0;
        // User id of user being switched to.
        userid_t to = 0;
    };

    // Dumps the collectors' status when they are disabled.
    android::base::Result<void> dumpCollectorsStatusLocked(int fd) const override;

    // Start a user switch collection.
    android::base::Result<void> startUserSwitchCollection();

    // Handles the messages received by the looper.
    void handleMessage(const Message& message) override {
        return WatchdogPerfServiceBase::handleMessage(message);
    }

    // Handles extra message logic.
    android::base::Result<void> handleMessageExtension(const Message& message) override;

    // Collects/processes the performance data for the current collection event.
    android::base::Result<void> collectLocked(EventMetadata* metadata) override;

    // Notifies all registered data processors that either boot-time or wake-up collection will
    // start. Individual implementations of data processors may clear stats collected during
    // previous system startup events.
    android::base::Result<void> notifySystemStartUpLocked();

    /**
     * Returns the metadata for the current collection based on |mCurrCollectionEvent|. Returns
     * nullptr on invalid collection event.
     */
    EventMetadata* getCurrentCollectionMetadataLocked() override;

    // Initialize collection intervals and I/O collectors.
    void initInternalLocked() override;

    // Check if the data processors were registered.
    bool isDataProcessorRegisteredLocked() override;

    // Start the first collection event in mCollectionThread.
    void startFirstCollectionEventLocked() override;

    // Clear any custom collection caches.
    void clearCustomCollectionCacheLocked() override;

    // Handle onDump timestamp and printing logic.
    android::base::Result<void> onDumpInternalLocked(int fd) const override;

    // Invokes periodic monitor methods in data processors. Called by the base class.
    android::base::Result<void> onDataProcessorPeriodicMonitorLocked(
            time_t now, const std::function<void()>& requestCollection,
            const char* eventTypeString) override;

    // Invokes terminate methods in data processors. Called by the base class.
    void onDataProcessorTerminateLocked() override;

    // Invokes onCarWatchdogServiceRegistered methods in data processors. Called by the base class.
    void onDataProcessorCarWatchdogServiceRegisteredLocked() override;

    // Invokes onCustomCollectionDump methods in data processors. Called by the base class.
    android::base::Result<void> onDataProcessorCustomCollectionDumpLocked(int fd) override;

    // Handles the filterPackagesFlag during custom collection. Called by the base class.
    android::base::Result<std::unordered_set<std::string>> onFilterPackagesFlag(
            const char** args, uint32_t valuePos, uint32_t numArgs) override;

    std::function<int64_t()> kGetElapsedTimeSinceBootMillisFunc;

    // Duration to extend a system event collection after the final signal is received.
    std::chrono::nanoseconds mPostSystemEventDurationNs;

    // Duration of the wake-up collection event.
    std::chrono::nanoseconds mWakeUpDurationNs;

    // Timeout duration for user switch collection in case final signal isn't received.
    std::chrono::nanoseconds mUserSwitchTimeoutNs;

    // Tracks the latest collection time since boot in millis.
    int64_t mLastCollectionTimeMillis GUARDED_BY(mMutex);

    // Info for the |EventType::BOOT_TIME_COLLECTION| collection event.
    EventMetadata mBoottimeCollection GUARDED_BY(mMutex);

    // Info for the |EventType::USER_SWITCH_COLLECTION| collection event.
    UserSwitchEventMetadata mUserSwitchCollection GUARDED_BY(mMutex);

    // Info for the |EventType::WAKE_UP_COLLECTION| collection event.
    EventMetadata mWakeUpCollection GUARDED_BY(mMutex);

    // Time of receiving boot complete signal.
    time_t mBootCompletedTimeEpochSeconds GUARDED_BY(mMutex);

    // Boot start time collected from /proc/stat.
    time_t mKernelStartTimeEpochSeconds GUARDED_BY(mMutex);

    // Collector for UID process and I/O stats.
    android::sp<UidStatsCollectorInterface> mUidStatsCollector GUARDED_BY(mMutex);

    // Collector/parser for `/proc/stat`.
    android::sp<ProcStatCollectorInterface> mProcStatCollector GUARDED_BY(mMutex);

    // Data processors for the collected performance data.
    std::vector<android::sp<DataProcessorInterface>> mDataProcessors GUARDED_BY(mMutex);

    // For unit tests.
    friend class internal::WatchdogPerfServicePeer;
    FRIEND_TEST(WatchdogPerfServiceTest, TestServiceStartAndTerminate);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
