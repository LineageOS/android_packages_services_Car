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

#include "CarPowerPolicyServer.h"

#include <gmock/gmock.h>
#include <utils/Looper.h>
#include <utils/StrongPointer.h>

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

using binder::Status;
using ::testing::_;
using ::testing::Return;

namespace {

class MockBinder : public BBinder {
public:
    MOCK_METHOD(status_t, linkToDeath,
                (const sp<DeathRecipient>& recipient, void* cookie, uint32_t flags), (override));
    MOCK_METHOD(status_t, unlinkToDeath,
                (const wp<DeathRecipient>& recipient, void* cookie, uint32_t flags,
                 wp<DeathRecipient>* outRecipient),
                (override));
};

class MockPowerPolicyChangeCallback : public ICarPowerPolicyChangeCallbackDefault {
public:
    MockPowerPolicyChangeCallback() { mBinder = new MockBinder(); }

    MOCK_METHOD(IBinder*, onAsBinder, (), (override));

    void expectLinkToDeathStatus(status_t linkToDeathResult) {
        EXPECT_CALL(*mBinder, linkToDeath(_, nullptr, 0)).WillRepeatedly(Return(linkToDeathResult));
        EXPECT_CALL(*mBinder, unlinkToDeath(_, nullptr, 0, nullptr)).WillRepeatedly(Return(OK));
        EXPECT_CALL(*this, onAsBinder()).WillRepeatedly(Return(mBinder.get()));
    }

private:
    sp<MockBinder> mBinder;
};

}  // namespace

class CarPowerPolicyServerTest : public ::testing::Test {
protected:
    void SetUp() override {
        sp<Looper> looper(Looper::prepare(/*opts=*/0));
        auto ret = CarPowerPolicyServer::startService(looper);
        ASSERT_TRUE(ret.ok()) << "Failed to start service: " << ret.error().message();
        mServer = *ret;
    }

    void TearDown() override { CarPowerPolicyServer::terminateService(); }

protected:
    sp<CarPowerPolicyServer> mServer;
};

TEST_F(CarPowerPolicyServerTest, TestRegisterCallback) {
    sp<MockPowerPolicyChangeCallback> callbackOne = new MockPowerPolicyChangeCallback();
    callbackOne->expectLinkToDeathStatus(OK);

    CarPowerPolicyFilter filter;
    Status status = mServer->registerPowerPolicyChangeCallback(callbackOne, filter);
    ASSERT_TRUE(status.isOk()) << status;
    status = mServer->registerPowerPolicyChangeCallback(callbackOne, filter);
    ASSERT_FALSE(status.isOk()) << "Duplicated registration is not allowed";
    filter.components = {PowerComponent::BLUETOOTH, PowerComponent::AUDIO};
    status = mServer->registerPowerPolicyChangeCallback(callbackOne, filter);
    ASSERT_FALSE(status.isOk()) << "Duplicated registration is not allowed";

    sp<MockPowerPolicyChangeCallback> callbackTwo = new MockPowerPolicyChangeCallback();
    callbackTwo->expectLinkToDeathStatus(OK);

    status = mServer->registerPowerPolicyChangeCallback(callbackTwo, filter);
    ASSERT_TRUE(status.isOk()) << status;
}

TEST_F(CarPowerPolicyServerTest, TestRegisterCallback_BinderDied) {
    sp<MockPowerPolicyChangeCallback> callback = new MockPowerPolicyChangeCallback();
    callback->expectLinkToDeathStatus(DEAD_OBJECT);
    CarPowerPolicyFilter filter;
    ASSERT_FALSE(mServer->registerPowerPolicyChangeCallback(callback, filter).isOk())
            << "When linkToDeath fails, registerPowerPolicyChangeCallback should return an error";
}

TEST_F(CarPowerPolicyServerTest, TestUnregisterCallback) {
    sp<MockPowerPolicyChangeCallback> callback = new MockPowerPolicyChangeCallback();
    callback->expectLinkToDeathStatus(OK);
    CarPowerPolicyFilter filter;
    mServer->registerPowerPolicyChangeCallback(callback, filter);
    Status status = mServer->unregisterPowerPolicyChangeCallback(callback);
    ASSERT_TRUE(status.isOk()) << status;
    ASSERT_FALSE(mServer->unregisterPowerPolicyChangeCallback(callback).isOk())
            << "Unregistering an unregistered powerpolicy change callback should return an error";
}

TEST_F(CarPowerPolicyServerTest, TestGetCurrentPowerPolicy) {
    CarPowerPolicy currentPolicy;
    Status status = mServer->getCurrentPowerPolicy(&currentPolicy);
    ASSERT_FALSE(status.isOk()) << "The current policy at creation should be null";
    // TODO(b/168545262): Add more test cases after VHAL integration is complete.
}

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android
