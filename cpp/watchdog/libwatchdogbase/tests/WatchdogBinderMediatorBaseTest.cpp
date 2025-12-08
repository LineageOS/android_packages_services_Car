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

#include "MockIoOveruseMonitor.h"
#include "MockResourceOveruseListener.h"
#include "MockWatchdogInternalHandler.h"
#include "MockWatchdogPerfServiceBase.h"
#include "MockWatchdogServiceHelperBase.h"
#include "WatchdogBinderMediatorBase.h"

#include <android-base/stringprintf.h>
#include <binder/IBinder.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <errno.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::ICarWatchdogClient;
using ::aidl::android::automotive::watchdog::IoOveruseStats;
using ::aidl::android::automotive::watchdog::IResourceOveruseListener;
using ::aidl::android::automotive::watchdog::ResourceOveruseStats;
using ::aidl::android::automotive::watchdog::ResourceType;
using ::aidl::android::automotive::watchdog::StateType;
using ::android::sp;
using ::android::base::Result;
using ::android::base::StringAppendF;
using ::ndk::ICInterface;
using ::ndk::SharedRefBase;
using ::testing::_;
using ::testing::DoAll;
using ::testing::Return;
using ::testing::SetArgPointee;
using ::testing::UnorderedElementsAreArray;

namespace {

const std::function<android::base::Result<void>(const char*, ICInterface*, bool, int)>
        kAddServiceFunctionStub =
                [](const char*, ICInterface*, bool, int) -> Result<void> { return Result<void>{}; };

std::string toString(const std::vector<ResourceOveruseStats>& resourceOveruseStats) {
    std::string buffer;
    for (const auto& stats : resourceOveruseStats) {
        StringAppendF(&buffer, "%s\n", stats.toString().c_str());
    }
    return buffer;
}

}  // namespace

namespace internal {

class WatchdogBinderMediatorBasePeer final {
public:
    explicit WatchdogBinderMediatorBasePeer(WatchdogBinderMediatorBase* mediator) :
          mMediator(mediator) {}

    void setWatchdogInternalHandlerBase(
            const std::shared_ptr<WatchdogInternalHandlerInterface>& watchdogInternalHandlerBase) {
        mMediator->mWatchdogInternalHandler = watchdogInternalHandlerBase;
    }

private:
    WatchdogBinderMediatorBase* mMediator;
};

};  // namespace internal

class WatchdogBinderMediatorBaseTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mMockWatchdogPerfServiceBase = sp<MockWatchdogPerfServiceBase>::make();
        mMockIoOveruseMonitor = sp<MockIoOveruseMonitor>::make();
        mWatchdogBinderMediatorBase = SharedRefBase::make<
                WatchdogBinderMediatorBase>(mMockWatchdogPerfServiceBase,
                                            sp<MockWatchdogServiceHelperBase>::make(),
                                            mMockIoOveruseMonitor, kAddServiceFunctionStub);
        mMockWatchdogInternalHandlerBase = SharedRefBase::make<MockWatchdogInternalHandler>();
        internal::WatchdogBinderMediatorBasePeer peer(mWatchdogBinderMediatorBase.get());
        peer.setWatchdogInternalHandlerBase(mMockWatchdogInternalHandlerBase);
    }

    virtual void TearDown() {
        mMockWatchdogPerfServiceBase.clear();
        mMockIoOveruseMonitor.clear();
        mWatchdogBinderMediatorBase.reset();
    }

    sp<MockWatchdogPerfServiceBase> mMockWatchdogPerfServiceBase;
    sp<MockIoOveruseMonitor> mMockIoOveruseMonitor;
    std::shared_ptr<MockWatchdogInternalHandler> mMockWatchdogInternalHandlerBase;
    std::shared_ptr<WatchdogBinderMediatorBase> mWatchdogBinderMediatorBase;
};

TEST_F(WatchdogBinderMediatorBaseTest, TestInit) {
    std::shared_ptr<WatchdogBinderMediatorBase> mediator = SharedRefBase::make<
            WatchdogBinderMediatorBase>(sp<MockWatchdogPerfServiceBase>::make(),
                                        sp<MockWatchdogServiceHelperBase>::make(),
                                        sp<MockIoOveruseMonitor>::make(), kAddServiceFunctionStub);

    ASSERT_RESULT_OK(mediator->init());

    ASSERT_NE(mediator->mIoOveruseMonitor, nullptr);
    ASSERT_NE(mediator->mWatchdogInternalHandler, nullptr);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestErrorOnInitWithNullServiceInstances) {
    auto mockWatchdogPerfServiceBase = sp<MockWatchdogPerfServiceBase>::make();
    auto mockWatchdogServiceHelperBase = sp<MockWatchdogServiceHelperBase>::make();
    auto mockIoOveruseMonitor = sp<MockIoOveruseMonitor>::make();
    std::shared_ptr<WatchdogBinderMediatorBase> mediator =
            SharedRefBase::make<WatchdogBinderMediatorBase>(nullptr, mockWatchdogServiceHelperBase,
                                                            mockIoOveruseMonitor,
                                                            kAddServiceFunctionStub);

    EXPECT_FALSE(mediator->init().ok())
            << "No error returned on nullptr watchdog performance service";
    mediator.reset();

    mediator = SharedRefBase::make<WatchdogBinderMediatorBase>(mockWatchdogPerfServiceBase, nullptr,
                                                               mockIoOveruseMonitor,
                                                               kAddServiceFunctionStub);

    EXPECT_FALSE(mediator->init().ok()) << "No error returned on null watchdog "
                                           "internal handler due to nullptr "
                                           "watchdog service helper";
    mediator.reset();

    mediator = SharedRefBase::make<WatchdogBinderMediatorBase>(mockWatchdogPerfServiceBase,
                                                               mockWatchdogServiceHelperBase,
                                                               nullptr, kAddServiceFunctionStub);

    EXPECT_FALSE(mediator->init().ok()) << "No error returned on nullptr I/O overuse monitor";
    mediator.reset();

    mediator = SharedRefBase::make<WatchdogBinderMediatorBase>(nullptr, nullptr, nullptr,
                                                               kAddServiceFunctionStub);

    EXPECT_FALSE(mediator->init().ok()) << "No error returned on null services";
    mediator.reset();
}

TEST_F(WatchdogBinderMediatorBaseTest, TestDump) {
    const char* args[] = {kStartCustomCollectionFlag, kIntervalFlag, "10", kMaxDurationFlag, "200"};
    EXPECT_CALL(*mMockWatchdogInternalHandlerBase, dump(-1, args, /*numArgs=*/5))
            .WillOnce(Return(OK));

    ASSERT_EQ(mWatchdogBinderMediatorBase->dump(-1, args, /*numArgs=*/5), OK);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestRegisterClient) {
    auto status = mWatchdogBinderMediatorBase->registerMediator(nullptr);
    ASSERT_EQ(status.getExceptionCode(), EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestUnregisterClient) {
    auto status = mWatchdogBinderMediatorBase->unregisterClient(nullptr);
    ASSERT_EQ(status.getExceptionCode(), EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestTellClientAlive) {
    auto status = mWatchdogBinderMediatorBase->tellClientAlive(nullptr, 456);
    ASSERT_EQ(status.getExceptionCode(), EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestAddResourceOveruseListener) {
    std::shared_ptr<IResourceOveruseListener> listener =
            SharedRefBase::make<MockResourceOveruseListener>();

    EXPECT_CALL(*mMockIoOveruseMonitor, addIoOveruseListener(listener))
            .WillOnce(Return(Result<void>{}));

    auto status =
            mWatchdogBinderMediatorBase->addResourceOveruseListener({ResourceType::IO}, listener);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogBinderMediatorBaseTest, TestErrorsAddResourceOveruseListenerOnInvalidArgs) {
    std::shared_ptr<IResourceOveruseListener> listener =
            SharedRefBase::make<MockResourceOveruseListener>();
    EXPECT_CALL(*mMockIoOveruseMonitor, addIoOveruseListener(listener)).Times(0);

    ASSERT_FALSE(mWatchdogBinderMediatorBase->addResourceOveruseListener({}, listener).isOk())
            << "Should fail on empty resource types";

    ASSERT_FALSE(
            mWatchdogBinderMediatorBase->addResourceOveruseListener({ResourceType::IO}, nullptr)
                    .isOk())
            << "Should fail on null listener";
}

TEST_F(WatchdogBinderMediatorBaseTest, TestRemoveResourceOveruseListener) {
    std::shared_ptr<IResourceOveruseListener> listener =
            SharedRefBase::make<MockResourceOveruseListener>();

    EXPECT_CALL(*mMockIoOveruseMonitor, removeIoOveruseListener(listener))
            .WillOnce(Return(Result<void>{}));

    auto status = mWatchdogBinderMediatorBase->removeResourceOveruseListener(listener);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(WatchdogBinderMediatorBaseTest, TestGetResourceOveruseStats) {
    IoOveruseStats ioOveruseStats;
    ioOveruseStats.killableOnOveruse = true;
    ioOveruseStats.startTime = 99898;
    ioOveruseStats.durationInSeconds = 12345;
    ioOveruseStats.totalOveruses = 3;
    std::vector<ResourceOveruseStats> expected;
    ResourceOveruseStats stats;
    stats.set<ResourceOveruseStats::ioOveruseStats>(ioOveruseStats);
    expected.emplace_back(std::move(stats));

    EXPECT_CALL(*mMockIoOveruseMonitor, getIoOveruseStats(_))
            .WillOnce(DoAll(SetArgPointee<0>(ioOveruseStats), Return(Result<void>{})));

    std::vector<ResourceOveruseStats> actual;
    auto status = mWatchdogBinderMediatorBase->getResourceOveruseStats({ResourceType::IO}, &actual);

    ASSERT_TRUE(status.isOk()) << status.getMessage();
    EXPECT_THAT(actual, UnorderedElementsAreArray(expected))
            << "Expected: " << toString(expected) << "\nActual: " << toString(actual);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestErrorsGetResourceOveruseStatsOnInvalidArgs) {
    EXPECT_CALL(*mMockIoOveruseMonitor, getIoOveruseStats(_)).Times(0);

    std::vector<ResourceOveruseStats> actual;
    ASSERT_FALSE(mWatchdogBinderMediatorBase->getResourceOveruseStats({}, &actual).isOk())
            << "Should fail on empty resource types";

    ASSERT_FALSE(mWatchdogBinderMediatorBase->getResourceOveruseStats({ResourceType::IO}, nullptr)
                         .isOk())
            << "Should fail on null listener";
}

TEST_F(WatchdogBinderMediatorBaseTest, TestRegisterMediator) {
    auto status = mWatchdogBinderMediatorBase->registerMediator(nullptr);
    ASSERT_EQ(status.getExceptionCode(), EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestUnregisterMediator) {
    auto status = mWatchdogBinderMediatorBase->unregisterMediator(nullptr);
    ASSERT_EQ(status.getExceptionCode(), EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestRegisterMonitor) {
    auto status = mWatchdogBinderMediatorBase->registerMonitor(nullptr);
    ASSERT_EQ(status.getExceptionCode(), EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestUnregisterMonitor) {
    auto status = mWatchdogBinderMediatorBase->unregisterMonitor(nullptr);
    ASSERT_EQ(status.getExceptionCode(), EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestTellMediatorAlive) {
    auto status = mWatchdogBinderMediatorBase->tellMediatorAlive(nullptr, {}, 0);
    ASSERT_EQ(status.getExceptionCode(), EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestTellDumpFinished) {
    auto status = mWatchdogBinderMediatorBase->tellDumpFinished(nullptr, 0);
    ASSERT_EQ(status.getExceptionCode(), EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorBaseTest, TestNotifySystemStateChange) {
    auto status =
            mWatchdogBinderMediatorBase->notifySystemStateChange(StateType::POWER_CYCLE, 0, 0);
    ASSERT_EQ(status.getExceptionCode(), EX_UNSUPPORTED_OPERATION);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
