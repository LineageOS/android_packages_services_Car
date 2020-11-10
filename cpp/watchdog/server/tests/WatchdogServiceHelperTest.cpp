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

#include "WatchdogServiceHelper.h"

#include <binder/IBinder.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

namespace android {
namespace automotive {
namespace watchdog {

namespace aawi = android::automotive::watchdog::internal;

using android::BBinder;
using android::sp;
using android::automotive::watchdog::internal::ICarWatchdogServiceForSystem;
using android::automotive::watchdog::internal::ICarWatchdogServiceForSystemDefault;
using android::binder::Status;
using ::testing::_;
using ::testing::Return;

namespace {

class MockBinder : public BBinder {
public:
    MockBinder() {
        EXPECT_CALL(*this, linkToDeath(_, nullptr, 0)).WillRepeatedly(Return(OK));
        EXPECT_CALL(*this, unlinkToDeath(_, nullptr, 0, nullptr)).WillRepeatedly(Return(OK));
    }
    void injectLinkToDeathResult(status_t linkToDeathResult) {
        EXPECT_CALL(*this, linkToDeath(_, nullptr, 0)).WillRepeatedly(Return(linkToDeathResult));
    }
    MOCK_METHOD(status_t, linkToDeath,
                (const sp<DeathRecipient>& recipient, void* cookie, uint32_t flags), (override));
    MOCK_METHOD(status_t, unlinkToDeath,
                (const wp<DeathRecipient>& recipient, void* cookie, uint32_t flags,
                 wp<DeathRecipient>* outRecipient),
                (override));
};

class MockCarWatchdogServiceForSystem : public ICarWatchdogServiceForSystemDefault {
public:
    MockCarWatchdogServiceForSystem() : mBinder(new MockBinder()) {
        EXPECT_CALL(*this, onAsBinder()).WillRepeatedly(Return(mBinder.get()));
    }

    sp<MockBinder> getBinder() const { return mBinder; }
    void injectLinkToDeathResult(status_t linkToDeathResult) {
        mBinder->injectLinkToDeathResult(linkToDeathResult);
    }

    MOCK_METHOD(IBinder*, onAsBinder, (), (override));
    MOCK_METHOD(Status, checkIfAlive, (int32_t, aawi::TimeoutLength), (override));
    MOCK_METHOD(Status, prepareProcessTermination, (), (override));

private:
    sp<MockBinder> mBinder;
};

class WatchdogServiceHelperTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mMockCarWatchdogServiceForSystem = new MockCarWatchdogServiceForSystem();
        mWatchdogServiceHelper = new WatchdogServiceHelper();
        Status status = mWatchdogServiceHelper->registerService(mMockCarWatchdogServiceForSystem);
        ASSERT_TRUE(status.isOk()) << status;
    }

    virtual void TearDown() {
        mMockCarWatchdogServiceForSystem.clear();
        mWatchdogServiceHelper.clear();
    }

    sp<MockCarWatchdogServiceForSystem> mMockCarWatchdogServiceForSystem;
    sp<WatchdogServiceHelper> mWatchdogServiceHelper;
};

}  // namespace

TEST_F(WatchdogServiceHelperTest, TestTerminate) {
    EXPECT_CALL(*(mMockCarWatchdogServiceForSystem->getBinder()),
                unlinkToDeath(_, nullptr, 0, nullptr))
            .WillOnce(Return(OK));

    mWatchdogServiceHelper->terminate();

    ASSERT_EQ(mWatchdogServiceHelper->mService, nullptr);
}

TEST_F(WatchdogServiceHelperTest, TestRegisterService) {
    sp<ICarWatchdogServiceForSystem> service = new MockCarWatchdogServiceForSystem();
    sp<WatchdogServiceHelperInterface> helper = new WatchdogServiceHelper();
    Status status = helper->registerService(service);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogServiceHelperTest, TestErrorOnRegisterServiceWithBinderDied) {
    sp<MockCarWatchdogServiceForSystem> service = new MockCarWatchdogServiceForSystem();
    service->injectLinkToDeathResult(DEAD_OBJECT);
    sp<WatchdogServiceHelperInterface> helper = new WatchdogServiceHelper();
    ASSERT_FALSE(helper->registerService(service).isOk())
            << "Failed to return error on register service with dead binder";
}

TEST_F(WatchdogServiceHelperTest, TestUnregisterService) {
    Status status = mWatchdogServiceHelper->unregisterService(mMockCarWatchdogServiceForSystem);
    ASSERT_TRUE(status.isOk()) << status;

    status = mWatchdogServiceHelper->unregisterService(mMockCarWatchdogServiceForSystem);
    ASSERT_FALSE(status.isOk()) << "Unregistering an unregistered service should return an error: "
                                << status;
}

TEST_F(WatchdogServiceHelperTest, TestCheckIfAlive) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem,
                checkIfAlive(0, aawi::TimeoutLength::TIMEOUT_CRITICAL))
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogServiceHelper->checkIfAlive(0, TimeoutLength::TIMEOUT_CRITICAL);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogServiceHelperTest, TestErrorOnCheckIfAliveWithoutValidCarWatchdogService) {
    Status status = mWatchdogServiceHelper->unregisterService(mMockCarWatchdogServiceForSystem);
    ASSERT_TRUE(status.isOk()) << status;

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, checkIfAlive(_, _)).Times(0);
    status = mWatchdogServiceHelper->checkIfAlive(0, TimeoutLength::TIMEOUT_CRITICAL);
    ASSERT_FALSE(status.isOk())
            << "checkIfAlive should fail when no car watchdog service registered with the helper";
}

TEST_F(WatchdogServiceHelperTest, TestErrorOnCheckIfAliveWithErrorStatusFromCarWatchdogService) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem,
                checkIfAlive(0, aawi::TimeoutLength::TIMEOUT_CRITICAL))
            .WillOnce(Return(Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "Illegal state")));
    Status status = mWatchdogServiceHelper->checkIfAlive(0, TimeoutLength::TIMEOUT_CRITICAL);
    ASSERT_FALSE(status.isOk())
            << "checkIfAlive should fail when car watchdog service API returns error";
}

TEST_F(WatchdogServiceHelperTest, TestPrepareProcessTermination) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, prepareProcessTermination())
            .WillOnce(Return(Status::ok()));
    Status status = mWatchdogServiceHelper->prepareProcessTermination();
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorOnPrepareProcessTerminationWithoutValidCarWatchdogService) {
    Status status = mWatchdogServiceHelper->unregisterService(mMockCarWatchdogServiceForSystem);
    ASSERT_TRUE(status.isOk()) << status;

    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, prepareProcessTermination()).Times(0);
    status = mWatchdogServiceHelper->prepareProcessTermination();
    ASSERT_FALSE(status.isOk()) << "prepareProcessTermination should fail when no car watchdog "
                                   "service registered with the helper";
}

TEST_F(WatchdogServiceHelperTest,
       TestErrorOnPrepareProcessTerminationWithErrorStatusFromCarWatchdogService) {
    EXPECT_CALL(*mMockCarWatchdogServiceForSystem, prepareProcessTermination())
            .WillOnce(Return(Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "Illegal state")));
    Status status = mWatchdogServiceHelper->prepareProcessTermination();
    ASSERT_FALSE(status.isOk())
            << "prepareProcessTermination should fail when car watchdog service API returns error";
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
