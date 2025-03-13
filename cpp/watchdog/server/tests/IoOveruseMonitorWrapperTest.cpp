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

#include "IoOveruseMonitorWrapper.h"
#include "MockIoOveruseMonitor.h"
#include "MockProcDiskStatsCollector.h"
#include "MockResourceOveruseListener.h"
#include "MockUidStatsCollector.h"
#include "MockWatchdogServiceHelper.h"

#include <utils/RefBase.h>

#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::IoOveruseStats;
using ::aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration;
using ::aidl::android::automotive::watchdog::internal::ResourceStats;
using ::aidl::android::automotive::watchdog::internal::UserPackageIoUsageStats;
using ::android::RefBase;
using ::android::sp;
using ::android::base::Result;
using ::ndk::SharedRefBase;
using ::testing::_;
using ::testing::Eq;
using ::testing::Return;

namespace internal {

class IoOveruseMonitorWrapperPeer final : public RefBase {
public:
    explicit IoOveruseMonitorWrapperPeer(
            const sp<IoOveruseMonitorWrapper>& ioOveruseMonitorWrapper) :
          mIoOveruseMonitorWrapper(ioOveruseMonitorWrapper) {}
    ~IoOveruseMonitorWrapperPeer() { mIoOveruseMonitorWrapper.clear(); }

    Result<void> init(const sp<IoOveruseMonitorInterface>& ioOveruseMonitor) {
        mIoOveruseMonitorWrapper->mIoOveruseMonitor = ioOveruseMonitor;
        if (const auto result = mIoOveruseMonitorWrapper->init(); !result.ok()) {
            return result;
        }
        return {};
    }

private:
    sp<IoOveruseMonitorWrapper> mIoOveruseMonitorWrapper;
};

}  // namespace internal

class IoOveruseMonitorWrapperTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mMockWatchdogServiceHelper = sp<MockWatchdogServiceHelper>::make();
        mIoOveruseMonitorWrapper = sp<IoOveruseMonitorWrapper>::make(mMockWatchdogServiceHelper);
        mIoOveruseMonitorWrapperPeer =
                sp<internal::IoOveruseMonitorWrapperPeer>::make(mIoOveruseMonitorWrapper);
        mMockIoOveruseMonitor = sp<MockIoOveruseMonitor>::make();
        EXPECT_CALL(*mMockIoOveruseMonitor, init()).WillOnce(Return(Result<void>()));
        mIoOveruseMonitorWrapperPeer->init(mMockIoOveruseMonitor);
        mMockUidStatsCollector = sp<MockUidStatsCollector>::make();
    }

    virtual void TearDown() {
        mMockIoOveruseMonitor.clear();
        mIoOveruseMonitorWrapperPeer.clear();
        mMockUidStatsCollector.clear();
        mMockWatchdogServiceHelper.clear();
    }

    sp<IoOveruseMonitorWrapper> mIoOveruseMonitorWrapper;
    sp<internal::IoOveruseMonitorWrapperPeer> mIoOveruseMonitorWrapperPeer;
    sp<MockIoOveruseMonitor> mMockIoOveruseMonitor;
    sp<MockUidStatsCollector> mMockUidStatsCollector;
    sp<MockWatchdogServiceHelper> mMockWatchdogServiceHelper;
};

TEST_F(IoOveruseMonitorWrapperTest, TestInit) {
    sp<IoOveruseMonitorWrapper> ioOveruseMonitorWrapper =
            sp<IoOveruseMonitorWrapper>::make(nullptr);
    ioOveruseMonitorWrapper->mIoOveruseMonitor = mMockIoOveruseMonitor;
    EXPECT_CALL(*mMockIoOveruseMonitor, init()).WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(mIoOveruseMonitorWrapper->init());
}

TEST_F(IoOveruseMonitorWrapperTest, TestTerminate) {
    ASSERT_NE(mIoOveruseMonitorWrapper->mIoOveruseMonitor, nullptr);

    mIoOveruseMonitorWrapper->terminate();

    ASSERT_EQ(mIoOveruseMonitorWrapper->mIoOveruseMonitor, nullptr);
}

TEST_F(IoOveruseMonitorWrapperTest, TestName) {
    EXPECT_CALL(*mMockIoOveruseMonitor, name()).WillOnce(Return("IoOveruseMonitor"));

    ASSERT_EQ(mIoOveruseMonitorWrapper->name(), "IoOveruseMonitor");
}

TEST_F(IoOveruseMonitorWrapperTest, TestIsInitialized) {
    EXPECT_CALL(*mMockIoOveruseMonitor, isInitialized()).WillOnce(Return(true));

    ASSERT_TRUE(mIoOveruseMonitorWrapper->isInitialized());
}

TEST_F(IoOveruseMonitorWrapperTest, TestOnCarWatchdogServiceRegistered) {
    EXPECT_CALL(*mMockIoOveruseMonitor, onCarWatchdogServiceRegistered());

    mIoOveruseMonitorWrapper->onCarWatchdogServiceRegistered();
}

TEST_F(IoOveruseMonitorWrapperTest, TestOnPeriodicCollection) {
    ResourceStats actualResourceStats = {};

    auto currentTime = std::chrono::time_point_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now());

    EXPECT_CALL(*mMockIoOveruseMonitor,
                onPeriodicCollection(currentTime, /*isGarageModeActive=*/false,
                                     Eq(mMockUidStatsCollector), &actualResourceStats))
            .WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(mIoOveruseMonitorWrapper->onPeriodicCollection(currentTime,
                                                                    SystemState::NORMAL_MODE,
                                                                    mMockUidStatsCollector, nullptr,
                                                                    &actualResourceStats));
}

TEST_F(IoOveruseMonitorWrapperTest, TestOnCustomCollection) {
    ResourceStats actualResourceStats = {};

    auto currentTime = std::chrono::time_point_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now());

    EXPECT_CALL(*mMockIoOveruseMonitor,
                onPeriodicCollection(currentTime, /*isGarageModeActive=*/false,
                                     Eq(mMockUidStatsCollector), &actualResourceStats))
            .WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(mIoOveruseMonitorWrapper->onCustomCollection(currentTime,
                                                                  SystemState::NORMAL_MODE, {},
                                                                  mMockUidStatsCollector, nullptr,
                                                                  &actualResourceStats));
}

TEST_F(IoOveruseMonitorWrapperTest, TestOnPeriodicMonitor) {
    time_t nextCollectionTime =
            std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());

    sp<MockProcDiskStatsCollector> mockProcDiskStatsCollector =
            sp<MockProcDiskStatsCollector>::make();

    EXPECT_CALL(*mMockIoOveruseMonitor,
                onPeriodicMonitor(nextCollectionTime, Eq(mockProcDiskStatsCollector), _))
            .WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(mIoOveruseMonitorWrapper->onPeriodicMonitor(nextCollectionTime,
                                                                 mockProcDiskStatsCollector,
                                                                 [&]() {}));
}

TEST_F(IoOveruseMonitorWrapperTest, TestDumpHelpText) {
    int fd = 1;
    EXPECT_CALL(*mMockIoOveruseMonitor, dumpHelpText(fd)).WillOnce(Return(true));

    ASSERT_TRUE(mIoOveruseMonitorWrapper->dumpHelpText(fd));
}

TEST_F(IoOveruseMonitorWrapperTest, TestOnTodayIoUsageStatsFetched) {
    std::vector<UserPackageIoUsageStats> userPackageIoUsageStats = {};
    EXPECT_CALL(*mMockIoOveruseMonitor, onTodayIoUsageStatsFetched(userPackageIoUsageStats))
            .WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(mIoOveruseMonitorWrapper->onTodayIoUsageStatsFetched(userPackageIoUsageStats));
}

TEST_F(IoOveruseMonitorWrapperTest, TestUpdateResourceOveruseConfigurations) {
    std::vector<ResourceOveruseConfiguration> configs;
    EXPECT_CALL(*mMockIoOveruseMonitor, updateResourceOveruseConfigurations(configs))
            .WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(mIoOveruseMonitorWrapper->updateResourceOveruseConfigurations(configs));
}

TEST_F(IoOveruseMonitorWrapperTest, TestGetResourceOveruseConfigurations) {
    std::vector<ResourceOveruseConfiguration> configs;
    EXPECT_CALL(*mMockIoOveruseMonitor, getResourceOveruseConfigurations(&configs))
            .WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(mIoOveruseMonitorWrapper->getResourceOveruseConfigurations(&configs));
}

TEST_F(IoOveruseMonitorWrapperTest, TestAddIoOveruseListener) {
    std::shared_ptr<MockResourceOveruseListener> mockResourceOveruseListener =
            SharedRefBase::make<MockResourceOveruseListener>();

    EXPECT_CALL(*mMockIoOveruseMonitor, addIoOveruseListener(Eq(mockResourceOveruseListener)))
            .WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(mIoOveruseMonitorWrapper->addIoOveruseListener(mockResourceOveruseListener));
}

TEST_F(IoOveruseMonitorWrapperTest, TestRemoveIoOveruseListener) {
    std::shared_ptr<MockResourceOveruseListener> mockResourceOveruseListener =
            SharedRefBase::make<MockResourceOveruseListener>();

    EXPECT_CALL(*mMockIoOveruseMonitor, removeIoOveruseListener(Eq(mockResourceOveruseListener)))
            .WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(
            mIoOveruseMonitorWrapper->removeIoOveruseListener(mockResourceOveruseListener));
}

TEST_F(IoOveruseMonitorWrapperTest, TestGetIoOveruseStats) {
    IoOveruseStats actual;

    EXPECT_CALL(*mMockIoOveruseMonitor, getIoOveruseStats(&actual))
            .WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(mIoOveruseMonitorWrapper->getIoOveruseStats(&actual));
}

TEST_F(IoOveruseMonitorWrapperTest, TestResetIoOveruseStats) {
    std::vector<std::string> packageNames = {"system.daemon"};

    EXPECT_CALL(*mMockIoOveruseMonitor, resetIoOveruseStats(packageNames))
            .WillOnce(Return(Result<void>()));

    ASSERT_RESULT_OK(mIoOveruseMonitorWrapper->resetIoOveruseStats(packageNames));
}

TEST_F(IoOveruseMonitorWrapperTest, TestRemoveStatsForUser) {
    EXPECT_CALL(*mMockIoOveruseMonitor, removeStatsForUser(11));

    mIoOveruseMonitorWrapper->removeStatsForUser(/*userId=*/11);
}

TEST_F(IoOveruseMonitorWrapperTest, TestHandleBinderDeath) {
    EXPECT_CALL(*mMockIoOveruseMonitor, handleBinderDeath(nullptr));

    mIoOveruseMonitorWrapper->handleBinderDeath(nullptr);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
