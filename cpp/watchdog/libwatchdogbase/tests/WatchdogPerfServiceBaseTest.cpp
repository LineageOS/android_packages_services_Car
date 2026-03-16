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

#include "LooperStub.h"
#include "MockIoOveruseMonitorBase.h"
#include "MockProcDiskStatsCollector.h"
#include "MockUidStatsCollectorBase.h"
#include "MockWatchdogServiceHelperBase.h"
#include "UidStatsCollectorBase.h"
#include "WatchdogPerfServiceBase.h"

#include <IoWatchdogProperties.sysprop.h>
#include <aidl/android/automotive/watchdog/internal/PackageIoOveruseStats.h>
#include <aidl/android/automotive/watchdog/internal/ResourceOveruseStats.h>
#include <aidl/android/automotive/watchdog/internal/ResourceStats.h>
#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <android/binder_auto_utils.h>
#include <android/binder_interface_utils.h>
#include <gmock/gmock.h>
#include <utils/RefBase.h>

#include <future>  // NOLINT(build/c++11)
#include <string>
#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

namespace {

using ::aidl::android::automotive::watchdog::internal::PackageIoOveruseStats;
using ::aidl::android::automotive::watchdog::internal::ResourceOveruseStats;
using ::aidl::android::automotive::watchdog::internal::ResourceStats;
using ::android::RefBase;
using ::android::sp;
using ::android::automotive::watchdog::testing::LooperStub;
using ::android::base::Error;
using ::android::base::Result;
using ::android::base::StringAppendF;
using ::testing::_;
using ::testing::ByMove;
using ::testing::Eq;
using ::testing::Mock;
using ::testing::NiceMock;
using ::testing::Return;

constexpr std::chrono::seconds kTestSystemEventCollectionIntervalSecs = 1s;
constexpr std::chrono::seconds kTestPeriodicCollectionIntervalSecs = 5s;
constexpr std::chrono::seconds kTestCustomCollectionIntervalSecs = 3s;
constexpr std::chrono::seconds kTestCustomCollectionDurationSecs = 11s;
constexpr std::chrono::seconds kTestPeriodicMonitorIntervalSecs = 2s;
constexpr const int32_t kTestPackageIoOveruseStatsUid = 100124036;
constexpr const int32_t kTestPerStateForegroundBytes = 1000;
constexpr const int32_t kTestPerStateBackgroundBytes = 2000;
constexpr const int32_t kTestPerStateGarageModeBytes = 3000;
constexpr char kTestLooperThreadName[] = "WdPerfSvcTest";

std::string toString(const std::vector<ResourceStats>& resourceStats) {
    std::string buffer;
    StringAppendF(&buffer, "{");
    for (const auto& stats : resourceStats) {
        StringAppendF(&buffer, "%s,\n", stats.toString().c_str());
    }
    if (buffer.size() > 2) {
        buffer.resize(buffer.size() - 2);  // Remove ",\n" from last element
    }
    StringAppendF(&buffer, "}");
    return buffer;
}

ResourceStats constructResourceStats(
        const std::optional<ResourceOveruseStats>& resourceOveruseStats) {
    ResourceStats resourceStats = {};
    resourceStats.resourceUsageStats = {};
    resourceStats.resourceOveruseStats = resourceOveruseStats;

    return resourceStats;
}

}  // namespace

namespace internal {

class WatchdogPerfServiceBasePeer final : public RefBase {
public:
    explicit WatchdogPerfServiceBasePeer(const sp<WatchdogPerfServiceBase>& service) :
          mService(service) {}
    WatchdogPerfServiceBasePeer() = delete;

    void init(const sp<UidStatsCollectorBaseInterface>& uidStatsCollectorBase,
              const sp<ProcDiskStatsCollectorInterface>& procDiskStatsCollector) {
        Mutex::Autolock lock(mService->mMutex);
        mService->mUidStatsCollectorBase = uidStatsCollectorBase;
        mService->mProcDiskStatsCollector = procDiskStatsCollector;
    }

    void updateIntervals() {
        Mutex::Autolock lock(mService->mMutex);
        mService->mPeriodicCollection.pollingIntervalNs = kTestPeriodicCollectionIntervalSecs;
        mService->mPeriodicMonitor.pollingIntervalNs = kTestPeriodicMonitorIntervalSecs;
        mService->mCustomCollection.pollingIntervalNs = kTestCustomCollectionIntervalSecs;
    }

    EventType getCurrCollectionEvent() {
        Mutex::Autolock lock(mService->mMutex);
        return mService->mCurrCollectionEvent;
    }

    int64_t getCurrentCollectionIntervalMillis() {
        // This method is always called while WatchdogPerfServiceBase is already
        // holding the lock.
        auto metadata = mService->getCurrentCollectionMetadataLocked();
        if (metadata == nullptr) {
            return std::chrono::duration_cast<std::chrono::milliseconds>(
                           kTestSystemEventCollectionIntervalSecs)
                    .count();
        }
        return std::chrono::duration_cast<std::chrono::milliseconds>(metadata->pollingIntervalNs)
                .count();
    }

    Result<std::unordered_set<std::string>> onFilterPackagesFlag(const char** args,
                                                                 uint32_t valuePos,
                                                                 uint32_t numArgs) {
        return mService->onFilterPackagesFlag(args, valuePos, numArgs);
    }

protected:
    sp<WatchdogPerfServiceBase> mService;
};

}  // namespace internal

namespace {

class WatchdogPerfServiceBaseTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mMockUidStatsCollectorBase = sp<MockUidStatsCollectorBase>::make();
        mMockWatchdogServiceHelperBase = sp<MockWatchdogServiceHelperBase>::make();
        mMockIoOveruseMonitorBase = sp<MockIoOveruseMonitorBase>::make();
        mMockProcDiskStatsCollector = sp<NiceMock<MockProcDiskStatsCollector>>::make();
        mLooperStub = sp<LooperStub>::make();
        mService = sp<WatchdogPerfServiceBase>::make(mLooperStub, mMockWatchdogServiceHelperBase,
                                                     nullptr);
        mServicePeer = sp<internal::WatchdogPerfServiceBasePeer>::make(mService);
        prepareLooper();
    }

    virtual void TearDown() {
        if (auto event = mServicePeer->getCurrCollectionEvent();
            event != EventType::INIT && event != EventType::TERMINATED) {
            EXPECT_CALL(*mMockIoOveruseMonitorBase, terminate()).Times(1);
            mService->terminate();
        }
        wakeAndJoinLooper();
        mService.clear();
        mServicePeer.clear();
        mLooperStub.clear();
        mLooper.clear();
        mMockUidStatsCollectorBase.clear();
        mMockWatchdogServiceHelperBase.clear();
        mMockIoOveruseMonitorBase.clear();
        mMockProcDiskStatsCollector.clear();
    }

    void startService() {
        mServicePeer->init(mMockUidStatsCollectorBase, mMockProcDiskStatsCollector);

        EXPECT_CALL(*mMockIoOveruseMonitorBase, init()).Times(1);

        ASSERT_RESULT_OK(mService->registerIoOveruseMonitorBase(mMockIoOveruseMonitorBase));

        EXPECT_CALL(*mMockUidStatsCollectorBase, init()).Times(1);
        EXPECT_CALL(*mMockProcDiskStatsCollector, init()).Times(1);

        mService->init();

        mServicePeer->updateIntervals();

        ASSERT_RESULT_OK(mService->start());
    }

    void checkPeriodicCollectionStarted() {
        EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);
        EXPECT_CALL(*mMockIoOveruseMonitorBase,
                    onPeriodicCollection(_, _, Eq(mMockUidStatsCollectorBase), _))
                .Times(1);

        // Make sure the collection event changes from EventType::INIT to
        // EventType::PERIODIC_COLLECTION.
        ASSERT_RESULT_OK(mLooperStub->pollCache());

        // Verify switch to periodic collection.
        ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::PERIODIC_COLLECTION)
                << "Invalid collection event";

        ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());
    }

    void skipPeriodicMonitorEvents() {
        EXPECT_CALL(*mMockIoOveruseMonitorBase, onPeriodicMonitor(_, _, _)).Times(2);
        ASSERT_RESULT_OK(mLooperStub->pollCache());
        ASSERT_RESULT_OK(mLooperStub->pollCache());
    }

    void removePeriodicMonitorEvents() {
        mLooperStub->removeMessages(mService, EventType::PERIODIC_MONITOR);
    }

    void skipPeriodicCollection() {
        EXPECT_CALL(*mMockIoOveruseMonitorBase,
                    onPeriodicCollection(_, /*isGarageModeActive=*/false, _, _))
                .Times(1);
        ASSERT_RESULT_OK(mLooperStub->pollCache());
    }

    void verifyAndClearExpectations() {
        Mock::VerifyAndClearExpectations(mMockUidStatsCollectorBase.get());
        Mock::VerifyAndClearExpectations(mMockProcDiskStatsCollector.get());
        Mock::VerifyAndClearExpectations(mMockIoOveruseMonitorBase.get());
        Mock::VerifyAndClearExpectations(mMockWatchdogServiceHelperBase.get());
    }

    void prepareLooper() {
        mLooper = Looper::prepare(/*opts=*/0);
        mLooperStub->setLooper(mLooper);
        mHandlerLooperThread = std::thread([this]() {
            Looper::setForThread(mLooper);
            if (int result = pthread_setname_np(pthread_self(), kTestLooperThreadName);
                result != 0) {
                ALOGE("Failed to set test looper thread name: %s", strerror(result));
            }
            mService->pollLooper();
        });
    }

    void wakeAndJoinLooper() {
        mLooperStub->wake();
        if (mHandlerLooperThread.joinable()) {
            mHandlerLooperThread.join();
        }
    }

    std::future<void> joinCollectionThread() {
        return std::async([&]() { wakeAndJoinLooper(); });
    }

    sp<WatchdogPerfServiceBase> mService;
    sp<internal::WatchdogPerfServiceBasePeer> mServicePeer;
    sp<LooperStub> mLooperStub;
    sp<Looper> mLooper;
    sp<MockUidStatsCollectorBase> mMockUidStatsCollectorBase;
    sp<MockProcDiskStatsCollector> mMockProcDiskStatsCollector;
    sp<MockWatchdogServiceHelperBase> mMockWatchdogServiceHelperBase;
    sp<MockIoOveruseMonitorBase> mMockIoOveruseMonitorBase;
    std::thread mHandlerLooperThread;
};

}  // namespace

TEST_F(WatchdogPerfServiceBaseTest, TestServiceStartAndTerminate) {
    mServicePeer->init(mMockUidStatsCollectorBase, mMockProcDiskStatsCollector);

    EXPECT_CALL(*mMockIoOveruseMonitorBase, init()).Times(1);

    ASSERT_RESULT_OK(mService->registerIoOveruseMonitorBase(mMockIoOveruseMonitorBase));

    EXPECT_CALL(*mMockUidStatsCollectorBase, init()).Times(1);
    EXPECT_CALL(*mMockProcDiskStatsCollector, init()).Times(1);

    mService->init();
    ASSERT_RESULT_OK(mService->start());

    EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_FALSE(mService->start().ok())
            << "No error returned when WatchdogPerfServiceBase was started more than once";

    ASSERT_TRUE(sysprop::periodicCollectionInterval().has_value());
    ASSERT_EQ(std::chrono::duration_cast<std::chrono::seconds>(
                      mService->mPeriodicCollection.pollingIntervalNs)
                      .count(),
              sysprop::periodicCollectionInterval().value());

    EXPECT_CALL(*mMockIoOveruseMonitorBase, terminate()).Times(1);

    mService->terminate();
}

TEST_F(WatchdogPerfServiceBaseTest, TestValidCollectionSequence) {
    ASSERT_NO_FATAL_FAILURE(startService());

    // #1 Periodic monitor
    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::PERIODIC_COLLECTION)
            << "Invalid collection event";
    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    EXPECT_CALL(*mMockProcDiskStatsCollector, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicMonitor(_, Eq(mMockProcDiskStatsCollector), _))
            .Times(1);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(mLooperStub->numSecondsElapsed(), kTestPeriodicMonitorIntervalSecs.count())
            << "First periodic monitor didn't happen at "
            << kTestPeriodicMonitorIntervalSecs.count() << " seconds interval";
    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    // #2 Periodic monitor
    EXPECT_CALL(*mMockProcDiskStatsCollector, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicMonitor(_, Eq(mMockProcDiskStatsCollector), _))
            .Times(1);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(mLooperStub->numSecondsElapsed(), kTestPeriodicMonitorIntervalSecs.count())
            << "Second periodic monitor didn't happen at "
            << kTestPeriodicMonitorIntervalSecs.count() << " seconds interval";
    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    // #3 Periodic collection
    std::vector<ResourceStats> actualResourceStats = {};
    ResourceOveruseStats expectedResourceOveruseStats = {};
    PackageIoOveruseStats packageIoOveruseStats = {};
    packageIoOveruseStats.uid = kTestPackageIoOveruseStatsUid;
    packageIoOveruseStats.forgivenWriteBytes = {kTestPerStateForegroundBytes,
                                                kTestPerStateBackgroundBytes,
                                                kTestPerStateGarageModeBytes};
    expectedResourceOveruseStats.packageIoOveruseStats.push_back(packageIoOveruseStats);
    std::vector<ResourceStats> expectedResourceStats = {
            constructResourceStats(expectedResourceOveruseStats),
    };
    EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/false,
                                     Eq(mMockUidStatsCollectorBase), _))
            .Times(1)
            .WillOnce([&](auto, auto, auto, auto* resourceStats) -> Result<void> {
                resourceStats->resourceOveruseStats =
                        std::make_optional<ResourceOveruseStats>(expectedResourceOveruseStats);
                return {};
            });
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, isServiceConnected())
            .Times(1)
            .WillOnce(Return(true));
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, onLatestResourceStats(_))
            .Times(1)
            .WillOnce([&](auto& resourceStats) -> ndk::ScopedAStatus {
                actualResourceStats = resourceStats;
                return ndk::ScopedAStatus::ok();
            });

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(mLooperStub->numSecondsElapsed(), 1)
            << "First periodic collection didn't happen at 1 second interval";
    ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::PERIODIC_COLLECTION)
            << "Invalid collection event";

    // Handle the SEND_RESOURCE_STATS message
    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(actualResourceStats, expectedResourceStats)
            << "Expected: " << toString(expectedResourceStats)
            << "\nActual: " << toString(actualResourceStats);

    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    std::string customCollectionIntervalStr =
            std::to_string(kTestCustomCollectionIntervalSecs.count());
    std::string customCollectionDurationStr =
            std::to_string(kTestCustomCollectionDurationSecs.count());
    // #7 Custom collection
    actualResourceStats = {};
    const char* firstArgs[] = {kStartCustomCollectionFlag, kIntervalFlag,
                               customCollectionIntervalStr.c_str(), kMaxDurationFlag,
                               customCollectionDurationStr.c_str()};

    ASSERT_RESULT_OK(mService->onCustomCollection(-1, firstArgs, /*numArgs=*/5));

    expectedResourceStats = {
            constructResourceStats(expectedResourceOveruseStats),
    };

    EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/false,
                                     Eq(mMockUidStatsCollectorBase), _))
            .Times(1)
            .WillOnce([&](auto, auto, auto, auto* resourceStats) -> Result<void> {
                resourceStats->resourceOveruseStats =
                        std::make_optional<ResourceOveruseStats>(expectedResourceOveruseStats);
                return {};
            });
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, isServiceConnected())
            .Times(1)
            .WillOnce(Return(true));
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, onLatestResourceStats(_))
            .Times(1)
            .WillOnce([&](auto& resourceStats) -> ndk::ScopedAStatus {
                actualResourceStats = resourceStats;
                return ndk::ScopedAStatus::ok();
            });

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    // Handle the SEND_RESOURCE_STATS message
    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(mLooperStub->numSecondsElapsed(), 0) << "Custom collection didn't start immediately";
    ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::CUSTOM_COLLECTION)
            << "Invalid collection event";
    ASSERT_EQ(actualResourceStats, expectedResourceStats)
            << "Expected: " << toString(expectedResourceStats)
            << "\nActual: " << toString(actualResourceStats);

    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    // #8 Custom collection
    EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/false,
                                     Eq(mMockUidStatsCollectorBase), _))
            .Times(1);
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, isServiceConnected()).Times(0);
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, onLatestResourceStats(_)).Times(0);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(mLooperStub->numSecondsElapsed(), kTestCustomCollectionIntervalSecs.count())
            << "Subsequent custom collection didn't happen at "
            << kTestCustomCollectionIntervalSecs.count() << " seconds interval";
    ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::CUSTOM_COLLECTION)
            << "Invalid collection event";
    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    // #9 End custom collection
    TemporaryFile customDump;

    const char* secondArgs[] = {kEndCustomCollectionFlag};
    ASSERT_RESULT_OK(mService->onCustomCollection(customDump.fd, secondArgs, /*numArgs=*/1));
    ASSERT_RESULT_OK(mLooperStub->pollCache());
    ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::PERIODIC_COLLECTION)
            << "Invalid collection event";

    // #10 Switch to periodic collection
    EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/false,
                                     Eq(mMockUidStatsCollectorBase), _))
            .Times(1);
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, isServiceConnected()).Times(0);
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, onLatestResourceStats(_)).Times(0);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(mLooperStub->numSecondsElapsed(), 0)
            << "Periodic collection didn't start immediately after ending custom collection";
    ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::PERIODIC_COLLECTION)
            << "Invalid collection event";
    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    // #11 Periodic monitor.
    EXPECT_CALL(*mMockProcDiskStatsCollector, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicMonitor(_, Eq(mMockProcDiskStatsCollector), _))
            .Times(1);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(mLooperStub->numSecondsElapsed(), kTestPeriodicMonitorIntervalSecs.count());
    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    EXPECT_CALL(*mMockIoOveruseMonitorBase, terminate()).Times(1);
}

TEST_F(WatchdogPerfServiceBaseTest, TestCollectionTerminatesOnZeroEnabledCollectors) {
    ASSERT_NO_FATAL_FAILURE(startService());

    ON_CALL(*mMockUidStatsCollectorBase, enabled()).WillByDefault(Return(false));

    // Collection should terminate and call io overuse monitor's terminate method on error.
    EXPECT_CALL(*mMockIoOveruseMonitorBase, terminate()).Times(1);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(joinCollectionThread().wait_for(1s), std::future_status::ready)
            << "Collection thread didn't terminate within 1 second.";
    ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::TERMINATED);
}

TEST_F(WatchdogPerfServiceBaseTest, TestCollectionTerminatesOnDataCollectorError) {
    ASSERT_NO_FATAL_FAILURE(startService());

    // Inject data collector error.
    Result<void> errorRes = Error() << "Failed to collect data";
    EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).WillOnce(Return(errorRes));

    // Collection should terminate and call io overuse monitor's terminate method on error.
    EXPECT_CALL(*mMockIoOveruseMonitorBase, terminate()).Times(1);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(joinCollectionThread().wait_for(1s), std::future_status::ready)
            << "Collection thread didn't terminate within 1 second.";
    ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::TERMINATED);
}

TEST_F(WatchdogPerfServiceBaseTest, TestCollectionTerminatesOnIoOveruseMonitorError) {
    ASSERT_NO_FATAL_FAILURE(startService());

    // Inject io overuse monitor error.
    Result<void> errorRes = Error() << "Failed to process data";
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/false,
                                     Eq(mMockUidStatsCollectorBase), _))
            .WillOnce(Return(errorRes));

    // Collection should terminate and call io overuse monitor's terminate method on error.
    EXPECT_CALL(*mMockIoOveruseMonitorBase, terminate()).Times(1);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(joinCollectionThread().wait_for(1s), std::future_status::ready)
            << "Collection thread didn't terminate within 1 second.";
    ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::TERMINATED);
}

TEST_F(WatchdogPerfServiceBaseTest, TestCustomCollection) {
    ASSERT_NO_FATAL_FAILURE(startService());

    ASSERT_NO_FATAL_FAILURE(checkPeriodicCollectionStarted());

    std::string customCollectionIntervalStr =
            std::to_string(kTestCustomCollectionIntervalSecs.count());
    std::string customCollectionDurationStr =
            std::to_string(kTestCustomCollectionDurationSecs.count());
    // Start custom collection with filter packages option.
    const char* args[] = {kStartCustomCollectionFlag, kIntervalFlag,
                          customCollectionIntervalStr.c_str(), kMaxDurationFlag,
                          customCollectionDurationStr.c_str()};

    ASSERT_RESULT_OK(mService->onCustomCollection(-1, args, /*numArgs=*/5));

    // Poll until custom collection auto terminates.
    int maxIterations = static_cast<int>(kTestCustomCollectionDurationSecs.count() /
                                         kTestCustomCollectionIntervalSecs.count());
    for (int i = 0; i <= maxIterations; ++i) {
        EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);
        EXPECT_CALL(*mMockIoOveruseMonitorBase,
                    onPeriodicCollection(_, /*isGarageModeActive=*/false,
                                         Eq(mMockUidStatsCollectorBase), _))
                .Times(1);

        ASSERT_RESULT_OK(mLooperStub->pollCache());

        int secondsElapsed = (i == 0 ? 0 : kTestCustomCollectionIntervalSecs.count());
        ASSERT_EQ(mLooperStub->numSecondsElapsed(), secondsElapsed)
                << "Custom collection didn't happen at " << secondsElapsed
                << " seconds interval in iteration " << i;
        ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::CUSTOM_COLLECTION)
                << "Invalid collection event";
        ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());
    }

    // Next looper message was injected during startCustomCollection to end the custom collection
    // after |kTestCustomCollectionDurationSecs|. On processing this message, the custom collection
    // should auto terminate.
    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(mLooperStub->numSecondsElapsed(),
              kTestCustomCollectionDurationSecs.count() % kTestCustomCollectionIntervalSecs.count())
            << "Custom collection did't end after " << kTestCustomCollectionDurationSecs.count()
            << " seconds";
    ASSERT_EQ(mServicePeer->getCurrCollectionEvent(), EventType::PERIODIC_COLLECTION)
            << "Invalid collection event";
    EXPECT_CALL(*mMockIoOveruseMonitorBase, terminate()).Times(1);
}

TEST_F(WatchdogPerfServiceBaseTest, TestPeriodicMonitorRequestsCollection) {
    ASSERT_NO_FATAL_FAILURE(startService());

    ASSERT_NO_FATAL_FAILURE(checkPeriodicCollectionStarted());

    // Periodic monitor issuing an alert to start new collection.
    EXPECT_CALL(*mMockProcDiskStatsCollector, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicMonitor(_, Eq(mMockProcDiskStatsCollector), _))
            .WillOnce([&](auto, auto, const auto& alertHandler) -> Result<void> {
                alertHandler();
                return {};
            });

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(mLooperStub->numSecondsElapsed(), kTestPeriodicMonitorIntervalSecs.count())
            << "First periodic monitor didn't happen at "
            << kTestPeriodicMonitorIntervalSecs.count() << " seconds interval";
    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/false,
                                     Eq(mMockUidStatsCollectorBase), _))
            .Times(1);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_EQ(mLooperStub->numSecondsElapsed(), 0)
            << "First periodic collection didn't happen immediately after the alert";

    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    EXPECT_CALL(*mMockIoOveruseMonitorBase, terminate()).Times(1);
}

TEST_F(WatchdogPerfServiceBaseTest, TestSystemStateSwitch) {
    ASSERT_NO_FATAL_FAILURE(startService());

    ASSERT_NO_FATAL_FAILURE(checkPeriodicCollectionStarted());
    ASSERT_NO_FATAL_FAILURE(skipPeriodicMonitorEvents());

    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/false, _, _))
            .Times(1);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    ASSERT_NO_FATAL_FAILURE(skipPeriodicMonitorEvents());

    mService->setSystemState(SystemState::GARAGE_MODE);

    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/true, _, _))
            .Times(1);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    ASSERT_NO_FATAL_FAILURE(skipPeriodicMonitorEvents());

    mService->setSystemState(SystemState::NORMAL_MODE);

    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/false, _, _))
            .Times(1);

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    EXPECT_CALL(*mMockIoOveruseMonitorBase, terminate()).Times(1);
}

TEST_F(WatchdogPerfServiceBaseTest, TestOnCarWatchdogServiceRegistered) {
    ASSERT_NO_FATAL_FAILURE(startService());
    ASSERT_NO_FATAL_FAILURE(checkPeriodicCollectionStarted());
    ASSERT_NO_FATAL_FAILURE(skipPeriodicMonitorEvents());
    ASSERT_NO_FATAL_FAILURE(skipPeriodicCollection());

    // Expect because the next pollCache call will result in an onPeriodicMonitor call
    // because no message is sent to process unsent resource stats
    EXPECT_CALL(*mMockIoOveruseMonitorBase, onPeriodicMonitor(_, _, _)).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase, onCarWatchdogServiceRegistered()).Times(1);
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, onLatestResourceStats(_)).Times(0);

    mService->onCarWatchdogServiceRegistered();

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());
}

TEST_F(WatchdogPerfServiceBaseTest, TestOnCarWatchdogServiceRegisteredWithUnsentResourceStats) {
    ASSERT_NO_FATAL_FAILURE(startService());
    ASSERT_NO_FATAL_FAILURE(checkPeriodicCollectionStarted());
    ASSERT_NO_FATAL_FAILURE(skipPeriodicMonitorEvents());

    EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase, onCarWatchdogServiceRegistered()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/false,
                                     Eq(mMockUidStatsCollectorBase), _))
            .Times(1)
            .WillOnce([&](auto, auto, auto, auto* resourceStats) -> Result<void> {
                resourceStats->resourceOveruseStats = std::make_optional<ResourceOveruseStats>({});
                return {};
            });
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, isServiceConnected())
            .Times(1)
            .WillOnce(Return(false));
    // Called when CarWatchdogService is registered
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, onLatestResourceStats(_))
            .Times(1)
            .WillOnce(Return(ByMove(ndk::ScopedAStatus::ok())));

    // Handle the periodic collection
    ASSERT_RESULT_OK(mLooperStub->pollCache());

    mService->onCarWatchdogServiceRegistered();

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());
}

TEST_F(WatchdogPerfServiceBaseTest, TestUnsentResourceStatsEviction) {
    ASSERT_NO_FATAL_FAILURE(startService());
    ASSERT_NO_FATAL_FAILURE(checkPeriodicCollectionStarted());
    ASSERT_NO_FATAL_FAILURE(skipPeriodicMonitorEvents());

    EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase, onCarWatchdogServiceRegistered()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/false,
                                     Eq(mMockUidStatsCollectorBase), _))
            .Times(1)
            .WillOnce([&](auto, auto, auto, auto* resourceStats) -> Result<void> {
                resourceStats->resourceOveruseStats = std::make_optional<ResourceOveruseStats>({});
                return {};
            });
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, isServiceConnected())
            .Times(1)
            .WillOnce(Return(false));
    // Should not be called once CarWatchdogService is registered
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, onLatestResourceStats(_)).Times(0);

    // Handle the periodic collection
    ASSERT_RESULT_OK(mLooperStub->pollCache());

    // Increment time so that the unsent resource stat is evicted
    mLooperStub->incrementTime(kPrevUnsentResourceStatsMaxDurationNs);

    mService->onCarWatchdogServiceRegistered();

    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());
}

TEST_F(WatchdogPerfServiceBaseTest, TestUnsentResourceStatsMaxCacheSize) {
    ASSERT_NO_FATAL_FAILURE(startService());
    ASSERT_NO_FATAL_FAILURE(checkPeriodicCollectionStarted());
    ASSERT_NO_FATAL_FAILURE(removePeriodicMonitorEvents());

    int32_t maxCacheSize = 10;
    int64_t elapsedPeriodicIntervalMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                                                kTestPeriodicCollectionIntervalSecs)
                                                .count();

    std::vector<ResourceStats> expectedResourceStats = {};

    ResourceOveruseStats expectedResourceOveruseStats = {};
    PackageIoOveruseStats packageIoOveruseStats = {};
    // Handle the periodic collections.
    for (int64_t i = 0; i < maxCacheSize; ++i) {
        expectedResourceOveruseStats = {};
        packageIoOveruseStats = {};
        packageIoOveruseStats.uid = i;
        packageIoOveruseStats.forgivenWriteBytes = {kTestPerStateForegroundBytes + i,
                                                    kTestPerStateBackgroundBytes + i,
                                                    kTestPerStateGarageModeBytes + i};
        expectedResourceOveruseStats.packageIoOveruseStats.push_back(packageIoOveruseStats);
        expectedResourceStats.push_back(ResourceStats{
                .resourceOveruseStats =
                        std::make_optional<ResourceOveruseStats>(expectedResourceOveruseStats),
        });

        EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);
        EXPECT_CALL(*mMockIoOveruseMonitorBase,
                    onPeriodicCollection(_, /*isGarageModeActive=*/false,
                                         Eq(mMockUidStatsCollectorBase), _))
                .Times(1)
                .WillOnce([&](auto, auto, auto, auto* resourceStats) -> Result<void> {
                    resourceStats->resourceOveruseStats =
                            std::make_optional<ResourceOveruseStats>(expectedResourceOveruseStats);
                    return {};
                });
        EXPECT_CALL(*mMockWatchdogServiceHelperBase, isServiceConnected())
                .Times(1)
                .WillRepeatedly(Return(false));

        ASSERT_RESULT_OK(mLooperStub->pollCache());
    }

    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());

    // The first resource stats should be evicted.
    expectedResourceStats.erase(expectedResourceStats.begin());

    packageIoOveruseStats.uid = kTestPackageIoOveruseStatsUid;
    packageIoOveruseStats.forgivenWriteBytes = {kTestPerStateForegroundBytes,
                                                kTestPerStateBackgroundBytes,
                                                kTestPerStateGarageModeBytes};
    expectedResourceOveruseStats.packageIoOveruseStats.push_back(packageIoOveruseStats);
    expectedResourceStats.push_back(ResourceStats{
            .resourceOveruseStats =
                    std::make_optional<ResourceOveruseStats>(expectedResourceOveruseStats),
    });

    std::vector<ResourceStats> actualResourceStats;

    EXPECT_CALL(*mMockUidStatsCollectorBase, collect()).Times(1);
    EXPECT_CALL(*mMockIoOveruseMonitorBase,
                onPeriodicCollection(_, /*isGarageModeActive=*/false,
                                     Eq(mMockUidStatsCollectorBase), _))
            .Times(1)
            .WillRepeatedly([&](auto, auto, auto, auto* resourceStats) -> Result<void> {
                resourceStats->resourceOveruseStats =
                        std::make_optional<ResourceOveruseStats>(expectedResourceOveruseStats);
                return {};
            });
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, isServiceConnected())
            .Times(1)
            .WillOnce(Return(true));
    EXPECT_CALL(*mMockWatchdogServiceHelperBase, onLatestResourceStats(_))
            .Times(1)
            .WillOnce([&](auto unsentStats) -> ndk::ScopedAStatus {
                actualResourceStats = unsentStats;
                return ndk::ScopedAStatus::ok();
            });

    // Handle an extra periodic collection, where unsent resource cache should
    // evict the oldest stats.
    ASSERT_RESULT_OK(mLooperStub->pollCache());

    // Handle the SEND_RESOURCE_STATS message.
    ASSERT_RESULT_OK(mLooperStub->pollCache());

    ASSERT_NO_FATAL_FAILURE(verifyAndClearExpectations());
    ASSERT_EQ(actualResourceStats, expectedResourceStats)
            << "Expected: " << toString(expectedResourceStats)
            << "\nActual: " << toString(actualResourceStats);
}

TEST_F(WatchdogPerfServiceBaseTest, TestOnFilterPackagesFlag) {
    ASSERT_NO_FATAL_FAILURE(startService());

    const char* test_flags[] = {"flag1", "flag2", "flag3"};
    const char** args = test_flags;
    std::unordered_set<std::string> filterPackages;

    ASSERT_FALSE(mServicePeer->onFilterPackagesFlag(test_flags, /*valuePos=*/1, /*numArgs=*/3).ok())
            << "Base implementation doesn't support filter packages flag";
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
