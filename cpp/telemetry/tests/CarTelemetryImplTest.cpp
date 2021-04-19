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

#include "CarTelemetryImpl.h"
#include "RingBuffer.h"

#include <aidl/android/frameworks/automotive/telemetry/CarData.h>
#include <aidl/android/frameworks/automotive/telemetry/ICarTelemetry.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <unistd.h>

#include <memory>

namespace android {
namespace automotive {
namespace telemetry {

using ::aidl::android::frameworks::automotive::telemetry::CarData;
using ::aidl::android::frameworks::automotive::telemetry::ICarTelemetry;
using ::testing::ContainerEq;

const size_t kMaxBufferSize = 5;

CarData buildCarData(int id, const std::vector<uint8_t>& content) {
    CarData msg;
    msg.id = id;
    msg.content = content;
    return msg;
}

BufferedCarData buildBufferedCarData(const CarData& data, uid_t publisherUid) {
    return {.mId = data.id, .mContent = std::move(data.content), .mPublisherUid = publisherUid};
}

class CarTelemetryImplTest : public ::testing::Test {
protected:
    CarTelemetryImplTest() :
          mBuffer(RingBuffer(kMaxBufferSize)),
          mTelemetry(ndk::SharedRefBase::make<CarTelemetryImpl>(&mBuffer)) {}

    RingBuffer mBuffer;
    std::shared_ptr<ICarTelemetry> mTelemetry;
};

TEST_F(CarTelemetryImplTest, WriteReturnsOkStatus) {
    CarData msg = buildCarData(101, {1, 0, 1, 0});

    auto status = mTelemetry->write({msg});

    EXPECT_TRUE(status.isOk()) << status.getMessage();
}

TEST_F(CarTelemetryImplTest, WriteAddsCarDataToRingBuffer) {
    CarData msg = buildCarData(101, {1, 0, 1, 0});

    mTelemetry->write({msg});

    EXPECT_EQ(mBuffer.popFront(), buildBufferedCarData(msg, getuid()));
}

TEST_F(CarTelemetryImplTest, WriteBuffersOnlyLimitedAmount) {
    RingBuffer buffer(/* sizeLimit= */ 3);
    auto telemetry = ndk::SharedRefBase::make<CarTelemetryImpl>(&buffer);

    CarData msg101_2 = buildCarData(101, {1, 0});
    CarData msg101_4 = buildCarData(101, {1, 0, 1, 0});
    CarData msg201_3 = buildCarData(201, {3, 3, 3});

    // Inserting 5 elements
    telemetry->write({msg101_2, msg101_4, msg101_4, msg201_3});
    telemetry->write({msg201_3});

    EXPECT_EQ(buffer.size(), 3);
    std::vector<BufferedCarData> result = {buffer.popFront(), buffer.popFront(), buffer.popFront()};
    std::vector<BufferedCarData> expected = {buildBufferedCarData(msg101_4, getuid()),
                                             buildBufferedCarData(msg201_3, getuid()),
                                             buildBufferedCarData(msg201_3, getuid())};
    EXPECT_THAT(result, ContainerEq(expected));
    EXPECT_EQ(buffer.size(), 0);
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
