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

#include <android/frameworks/automotive/telemetry/CarData.h>
#include <android/frameworks/automotive/telemetry/ICarTelemetry.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <unistd.h>

#include <memory>

namespace android {
namespace automotive {
namespace telemetry {

using ::android::frameworks::automotive::telemetry::CarData;
using ::android::frameworks::automotive::telemetry::ICarTelemetry;
using ::testing::ContainerEq;

const size_t kMaxBufferSizeBytes = 1024;

CarData buildCarData(int id, const std::vector<uint8_t>& content) {
    CarData msg;
    msg.id = id;
    msg.content = content;
    return msg;
}

class CarTelemetryImplTest : public ::testing::Test {
protected:
    CarTelemetryImplTest() :
          mBuffer(RingBuffer(kMaxBufferSizeBytes)),
          mTelemetry(std::make_unique<CarTelemetryImpl>(&mBuffer)) {}

    RingBuffer mBuffer;
    std::unique_ptr<ICarTelemetry> mTelemetry;
};

TEST_F(CarTelemetryImplTest, TestWriteReturnsOkStatus) {
    CarData msg = buildCarData(101, {1, 0, 1, 0});

    auto status = mTelemetry->write({msg});

    EXPECT_TRUE(status.isOk()) << status;
}

TEST_F(CarTelemetryImplTest, TestWriteAddsCarDataToRingBuffer) {
    CarData msg = buildCarData(101, {1, 0, 1, 0});

    mTelemetry->write({msg});

    std::vector<BufferedCarData> result = mBuffer.popAllDataForId(101);
    std::vector<BufferedCarData> expected = {BufferedCarData(msg, getuid())};
    EXPECT_THAT(result, ContainerEq(expected));
}

TEST_F(CarTelemetryImplTest, TestWriteBuffersOnlyLimitedAmount) {
    RingBuffer buffer(15);  // bytes
    CarTelemetryImpl telemetry(&buffer);

    CarData msg101_2 = buildCarData(101, {1, 0});        // 2 bytes
    CarData msg101_4 = buildCarData(101, {1, 0, 1, 0});  // 4 bytes
    CarData msg201_3 = buildCarData(201, {3, 3, 3});     // 3 bytes

    telemetry.write({msg101_2, msg101_4, msg101_4, msg201_3, msg201_3});

    // Size without the first msg101_2, because ushing the last msg201_3 will force RingBuffer to
    // drop the earliest msg101_2.
    EXPECT_EQ(buffer.currentSizeBytes(), 14);
    std::vector<BufferedCarData> result = buffer.popAllDataForId(101);
    std::vector<BufferedCarData> expected = {BufferedCarData(msg101_4, getuid()),
                                             BufferedCarData(msg101_4, getuid())};
    EXPECT_THAT(result, ContainerEq(expected));
    // Fetching 2x msg101_4 will decrease the size of the RingBuffer
    EXPECT_EQ(buffer.currentSizeBytes(), 6);
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
