/*
 * Copyright 2020 The Android Open Source Project
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
#include "MockWatchdogPerfService.h"
#include "MockWatchdogProcessService.h"
#include "MockWatchdogServiceHelper.h"
#include "WatchdogBinderMediator.h"

#include <binder/IBinder.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <errno.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::sp;
using ::android::base::Result;
using ::android::binder::Status;
using ::testing::_;
using ::testing::NiceMock;
using ::testing::Return;

namespace {

const std::function<android::base::Result<void>(const char*, const android::sp<android::IBinder>&)>
        kAddServiceFunctionStub =
                [](const char*, const android::sp<android::IBinder>&) -> Result<void> {
    return Result<void>{};
};

class MockICarWatchdogClient : public ICarWatchdogClient {
public:
    MOCK_METHOD(Status, checkIfAlive, (int32_t sessionId, TimeoutLength timeout), (override));
    MOCK_METHOD(Status, prepareProcessTermination, (), (override));
    MOCK_METHOD(IBinder*, onAsBinder, (), (override));
    MOCK_METHOD(int32_t, getInterfaceVersion, (), (override));
    MOCK_METHOD(std::string, getInterfaceHash, (), (override));
};

}  // namespace

namespace internal {

class WatchdogBinderMediatorPeer {
public:
    explicit WatchdogBinderMediatorPeer(const sp<WatchdogBinderMediator>& mediator) :
          mMediator(mediator) {}
    ~WatchdogBinderMediatorPeer() { mMediator.clear(); }

    Result<void> init() { return mMediator->init(); }

private:
    sp<WatchdogBinderMediator> mMediator;
};

}  // namespace internal

class WatchdogBinderMediatorTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mMockWatchdogProcessService = new MockWatchdogProcessService();
        mMockWatchdogPerfService = new MockWatchdogPerfService();
        mMockIoOveruseMonitor = new MockIoOveruseMonitor();
        mWatchdogBinderMediator =
                new WatchdogBinderMediator(mMockWatchdogProcessService, mMockWatchdogPerfService,
                                           mMockIoOveruseMonitor, new MockWatchdogServiceHelper(),
                                           kAddServiceFunctionStub);
        internal::WatchdogBinderMediatorPeer mediatorPeer(mWatchdogBinderMediator);
        ASSERT_RESULT_OK(mediatorPeer.init());
    }
    virtual void TearDown() {
        mMockWatchdogProcessService.clear();
        mMockWatchdogPerfService.clear();
        mMockIoOveruseMonitor.clear();
        mWatchdogBinderMediator.clear();
    }

    sp<MockWatchdogProcessService> mMockWatchdogProcessService;
    sp<MockWatchdogPerfService> mMockWatchdogPerfService;
    sp<MockIoOveruseMonitor> mMockIoOveruseMonitor;
    sp<WatchdogBinderMediator> mWatchdogBinderMediator;
};

TEST_F(WatchdogBinderMediatorTest, TestInit) {
    sp<WatchdogBinderMediator> mediator =
            new WatchdogBinderMediator(new MockWatchdogProcessService(),
                                       new MockWatchdogPerfService(), new MockIoOveruseMonitor(),
                                       new MockWatchdogServiceHelper(), kAddServiceFunctionStub);

    ASSERT_RESULT_OK(mediator->init());

    ASSERT_NE(mediator->mWatchdogProcessService, nullptr);
    ASSERT_NE(mediator->mWatchdogPerfService, nullptr);
    ASSERT_NE(mediator->mIoOveruseMonitor, nullptr);
    ASSERT_NE(mediator->mWatchdogInternalHandler, nullptr);
}

TEST_F(WatchdogBinderMediatorTest, TestErrorOnInitWithNullServiceInstances) {
    sp<WatchdogBinderMediator> mediator =
            new WatchdogBinderMediator(nullptr, new MockWatchdogPerfService(),
                                       new MockIoOveruseMonitor(), new MockWatchdogServiceHelper(),
                                       kAddServiceFunctionStub);

    ASSERT_FALSE(mediator->init().ok()) << "No error returned on nullptr watchdog process service";
    mediator.clear();

    mediator = new WatchdogBinderMediator(new MockWatchdogProcessService(), nullptr,
                                          new MockIoOveruseMonitor(),
                                          new MockWatchdogServiceHelper(), kAddServiceFunctionStub);

    ASSERT_FALSE(mediator->init().ok()) << "No error returned on nullptr watchdog perf service";
    mediator.clear();

    mediator = new WatchdogBinderMediator(new MockWatchdogProcessService(),
                                          new MockWatchdogPerfService(), nullptr,
                                          new MockWatchdogServiceHelper(), kAddServiceFunctionStub);

    ASSERT_FALSE(mediator->init().ok()) << "No error returned on nullptr I/O overuse monitor";
    mediator.clear();

    mediator = new WatchdogBinderMediator(new MockWatchdogProcessService(),
                                          new MockWatchdogPerfService(), new MockIoOveruseMonitor(),
                                          nullptr, kAddServiceFunctionStub);

    ASSERT_FALSE(mediator->init().ok()) << "No error returned on nullptr watchdog service helper";
    mediator.clear();

    mediator =
            new WatchdogBinderMediator(nullptr, nullptr, nullptr, nullptr, kAddServiceFunctionStub);

    ASSERT_FALSE(mediator->init().ok()) << "No error returned on nullptr watchdog service helper";
    mediator.clear();
}

TEST_F(WatchdogBinderMediatorTest, TestTerminate) {
    ASSERT_NE(mWatchdogBinderMediator->mWatchdogProcessService, nullptr);
    ASSERT_NE(mWatchdogBinderMediator->mWatchdogPerfService, nullptr);
    ASSERT_NE(mWatchdogBinderMediator->mIoOveruseMonitor, nullptr);
    ASSERT_NE(mWatchdogBinderMediator->mWatchdogInternalHandler, nullptr);

    mWatchdogBinderMediator->terminate();

    ASSERT_EQ(mWatchdogBinderMediator->mWatchdogProcessService, nullptr);
    ASSERT_EQ(mWatchdogBinderMediator->mWatchdogPerfService, nullptr);
    ASSERT_EQ(mWatchdogBinderMediator->mIoOveruseMonitor, nullptr);
    ASSERT_EQ(mWatchdogBinderMediator->mWatchdogInternalHandler, nullptr);
}

TEST_F(WatchdogBinderMediatorTest, TestHandlesEmptyDumpArgs) {
    EXPECT_CALL(*mMockWatchdogProcessService, dump(-1, _)).WillOnce(Return(Result<void>()));
    EXPECT_CALL(*mMockWatchdogPerfService, onDump(-1)).WillOnce(Return(Result<void>()));
    mWatchdogBinderMediator->dump(-1, Vector<String16>());
}

TEST_F(WatchdogBinderMediatorTest, TestHandlesStartCustomPerfCollection) {
    EXPECT_CALL(*mMockWatchdogPerfService, onCustomCollection(-1, _))
            .WillOnce(Return(Result<void>()));

    Vector<String16> args;
    args.push_back(String16(kStartCustomCollectionFlag));
    ASSERT_EQ(mWatchdogBinderMediator->dump(-1, args), OK);
}

TEST_F(WatchdogBinderMediatorTest, TestHandlesStopCustomPerfCollection) {
    EXPECT_CALL(*mMockWatchdogPerfService, onCustomCollection(-1, _))
            .WillOnce(Return(Result<void>()));

    Vector<String16> args;
    args.push_back(String16(kEndCustomCollectionFlag));
    ASSERT_EQ(mWatchdogBinderMediator->dump(-1, args), OK);
}

TEST_F(WatchdogBinderMediatorTest, TestErrorOnInvalidDumpArgs) {
    Vector<String16> args;
    args.push_back(String16("--invalid_option"));
    ASSERT_EQ(mWatchdogBinderMediator->dump(-1, args), OK) << "Error returned on invalid args";
}

TEST_F(WatchdogBinderMediatorTest, TestRegisterClient) {
    sp<ICarWatchdogClient> client = new MockICarWatchdogClient();
    TimeoutLength timeout = TimeoutLength::TIMEOUT_MODERATE;
    EXPECT_CALL(*mMockWatchdogProcessService, registerClient(client, timeout))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->registerClient(client, timeout);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestUnregisterClient) {
    sp<ICarWatchdogClient> client = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, unregisterClient(client))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->unregisterClient(client);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestTellClientAlive) {
    sp<ICarWatchdogClient> client = new MockICarWatchdogClient();
    EXPECT_CALL(*mMockWatchdogProcessService, tellClientAlive(client, 456))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogBinderMediator->tellClientAlive(client, 456);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogBinderMediatorTest, TestRegisterMediator) {
    Status status = mWatchdogBinderMediator->registerMediator(nullptr);
    ASSERT_EQ(status.exceptionCode(), Status::EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorTest, TestUnregisterMediator) {
    Status status = mWatchdogBinderMediator->unregisterMediator(nullptr);
    ASSERT_EQ(status.exceptionCode(), Status::EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorTest, TestRegisterMonitor) {
    Status status = mWatchdogBinderMediator->registerMonitor(nullptr);
    ASSERT_EQ(status.exceptionCode(), Status::EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorTest, TestUnregisterMonitor) {
    Status status = mWatchdogBinderMediator->unregisterMonitor(nullptr);
    ASSERT_EQ(status.exceptionCode(), Status::EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorTest, TestTellMediatorAlive) {
    Status status = mWatchdogBinderMediator->tellMediatorAlive(nullptr, {}, 0);
    ASSERT_EQ(status.exceptionCode(), Status::EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorTest, TestTellDumpFinished) {
    Status status = mWatchdogBinderMediator->tellDumpFinished(nullptr, 0);
    ASSERT_EQ(status.exceptionCode(), Status::EX_UNSUPPORTED_OPERATION);
}

TEST_F(WatchdogBinderMediatorTest, TestNotifySystemStateChange) {
    Status status = mWatchdogBinderMediator->notifySystemStateChange(StateType::POWER_CYCLE, 0, 0);
    ASSERT_EQ(status.exceptionCode(), Status::EX_UNSUPPORTED_OPERATION);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
