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

#include <aidl/android/automotive/telemetry/internal/BnCarDataListener.h>
#include <aidl/android/automotive/telemetry/internal/CarDataInternal.h>
#include <aidl/android/automotive/telemetry/internal/ICarTelemetryInternal.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <unistd.h>

#include <memory>

namespace android {
namespace automotive {
namespace telemetry {

using ::aidl::android::automotive::telemetry::internal::BnCarDataListener;
using ::aidl::android::automotive::telemetry::internal::CarDataInternal;
using ::aidl::android::automotive::telemetry::internal::ICarTelemetryInternal;
using ::ndk::ScopedAStatus;

const size_t kMaxBufferSize = 5;

class MockCarDataListener : public BnCarDataListener {
public:
    MOCK_METHOD(ScopedAStatus, onCarDataReceived, (const std::vector<CarDataInternal>& dataList),
                (override));
};

// The main test class.
class CarTelemetryInternalImplTest : public ::testing::Test {
protected:
    CarTelemetryInternalImplTest() :
          mBuffer(RingBuffer(kMaxBufferSize)),
          mTelemetryInternal(ndk::SharedRefBase::make<CarTelemetryInternalImpl>(&mBuffer)),
          mMockCarDataListener(ndk::SharedRefBase::make<MockCarDataListener>()) {}

    RingBuffer mBuffer;
    std::shared_ptr<ICarTelemetryInternal> mTelemetryInternal;
    std::shared_ptr<MockCarDataListener> mMockCarDataListener;
};

TEST_F(CarTelemetryInternalImplTest, SetListenerReturnsOk) {
    auto status = mTelemetryInternal->setListener(mMockCarDataListener);

    EXPECT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(CarTelemetryInternalImplTest, SetListenerFailsWhenAlreadySubscribed) {
    mTelemetryInternal->setListener(mMockCarDataListener);

    auto status = mTelemetryInternal->setListener(ndk::SharedRefBase::make<MockCarDataListener>());

    EXPECT_EQ(status.getExceptionCode(), ::EX_ILLEGAL_STATE) << status.getMessage();
}

TEST_F(CarTelemetryInternalImplTest, ClearListenerWorks) {
    mTelemetryInternal->setListener(mMockCarDataListener);

    mTelemetryInternal->clearListener();
    auto status = mTelemetryInternal->setListener(mMockCarDataListener);

    EXPECT_TRUE(status.isOk()) << status.getMessage();
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
