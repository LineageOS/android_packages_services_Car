/*
 * Copyright 2021 The Android Open Source Project
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

#include "CarTelemetryInternalImpl.h"
#include "RingBuffer.h"

#include <android/automotive/telemetry/internal/CarDataInternal.h>
#include <android/automotive/telemetry/internal/ICarDataListener.h>
#include <android/automotive/telemetry/internal/ICarTelemetryInternal.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <unistd.h>

#include <memory>

namespace android {
namespace automotive {
namespace telemetry {

using ::android::automotive::telemetry::internal::CarDataInternal;
using ::android::automotive::telemetry::internal::ICarDataListener;
using ::android::automotive::telemetry::internal::ICarTelemetryInternal;
using ::android::binder::Status;

const size_t kMaxBufferSizeBytes = 1024;

class FakeBinder : public BBinder {
public:
    status_t linkToDeath(const sp<DeathRecipient>& recipient, void* cookie,
                         uint32_t flags) override {
        mDeathRecipient = recipient;
        return mLinkToDeathStatus;
    }

    status_t unlinkToDeath(const wp<DeathRecipient>& recipient, void* cookie, uint32_t flags,
                           wp<DeathRecipient>* outRecipient) override {
        return android::OK;
    }

    sp<DeathRecipient> mDeathRecipient;
    status_t mLinkToDeathStatus = android::OK;  // Result of linkToDeath() method.
};

// General flow for ICarDataListener is:
//   1. Internal client calls ICarTelemetryInternal.setListener(client_listener)
//   2. Binder constructs instance of ICarDataListener with its own Binder instance
//       * in this test's case, these are FakeCarDataListener and FakeBinder
class FakeCarDataListener : public ICarDataListener {
public:
    explicit FakeCarDataListener(android::sp<FakeBinder> binder) : mFakeBinder(binder) {}

    IBinder* onAsBinder() override { return mFakeBinder.get(); }

    android::binder::Status onCarDataReceived(
            const std::vector<CarDataInternal>& dataList) override {
        return Status::ok();
    }

private:
    sp<FakeBinder> mFakeBinder;
};

// Main test class.
class CarTelemetryInternalImplTest : public ::testing::Test {
protected:
    CarTelemetryInternalImplTest() :
          mBuffer(RingBuffer(kMaxBufferSizeBytes)),
          mTelemetryInternal(std::make_unique<CarTelemetryInternalImpl>(&mBuffer)),
          mFakeCarDataListenerBinder(new FakeBinder()),
          mFakeCarDataListener(new FakeCarDataListener(mFakeCarDataListenerBinder)) {}

    RingBuffer mBuffer;
    std::unique_ptr<ICarTelemetryInternal> mTelemetryInternal;
    android::sp<FakeBinder> mFakeCarDataListenerBinder;  // For mFakeCarDataListener
    android::sp<FakeCarDataListener> mFakeCarDataListener;
};

TEST_F(CarTelemetryInternalImplTest, TestSetListenerReturnsOk) {
    auto status = mTelemetryInternal->setListener(mFakeCarDataListener);

    EXPECT_TRUE(status.isOk()) << status;
}

TEST_F(CarTelemetryInternalImplTest, TestSetListenerFailsWhenAlreadySubscribed) {
    mTelemetryInternal->setListener(mFakeCarDataListener);

    auto status = mTelemetryInternal->setListener(new FakeCarDataListener(new FakeBinder()));

    EXPECT_EQ(status.exceptionCode(), Status::EX_ILLEGAL_STATE);
}

TEST_F(CarTelemetryInternalImplTest, TestSetListenerFailsIfListenedIsDead) {
    // The next call to linkToDeath() returns dead object, meaning the listener is not valid.
    mFakeCarDataListenerBinder->mLinkToDeathStatus = android::DEAD_OBJECT;

    auto status = mTelemetryInternal->setListener(mFakeCarDataListener);

    EXPECT_EQ(status.exceptionCode(), Status::EX_ILLEGAL_STATE);
}

TEST_F(CarTelemetryInternalImplTest, TestClearListenerWorks) {
    mTelemetryInternal->setListener(mFakeCarDataListener);

    mTelemetryInternal->clearListener();
    auto status = mTelemetryInternal->setListener(mFakeCarDataListener);

    EXPECT_TRUE(status.isOk()) << status;
}

TEST_F(CarTelemetryInternalImplTest, TestListenerBinderDied) {
    mTelemetryInternal->setListener(mFakeCarDataListener);  // old listener
    EXPECT_NE(mFakeCarDataListenerBinder->mDeathRecipient, nullptr);

    // the old listener died
    mFakeCarDataListenerBinder->mDeathRecipient->binderDied(mFakeCarDataListenerBinder);

    // new listener
    auto status = mTelemetryInternal->setListener(new FakeCarDataListener(new FakeBinder()));

    EXPECT_TRUE(status.isOk()) << status;
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
