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

#include "RingBuffer.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <memory>

// NOTE: many of RingBuffer's behaviors are tested as part of CarTelemetryImpl.

namespace android {
namespace automotive {
namespace telemetry {

using testing::ContainerEq;

BufferedCarData buildBufferedCarData(int32_t id, const std::vector<uint8_t>& content) {
    return BufferedCarData(id, content, /* uid= */ 0);
}

TEST(RingBufferTest, TestPopAllDataForIdReturnsCorrectResults) {
    RingBuffer buffer(10);  // bytes
    buffer.push(buildBufferedCarData(101, {7}));
    buffer.push(buildBufferedCarData(101, {7}));
    buffer.push(buildBufferedCarData(102, {7}));
    buffer.push(buildBufferedCarData(101, {7}));

    std::vector<BufferedCarData> result = buffer.popAllDataForId(101);

    std::vector<BufferedCarData> expected = {buildBufferedCarData(101, {7}),
                                             buildBufferedCarData(101, {7}),
                                             buildBufferedCarData(101, {7})};
    EXPECT_THAT(result, ContainerEq(expected));
}

TEST(RingBufferTest, TestPopAllDataForIdRemovesFromBuffer) {
    RingBuffer buffer(10);                              // bytes
    buffer.push(buildBufferedCarData(101, {7}));        // 1 byte
    buffer.push(buildBufferedCarData(102, {7, 8}));     // 2 byte
    buffer.push(buildBufferedCarData(103, {7, 8, 9}));  // 3 bytes

    buffer.popAllDataForId(101);  // also removes CarData with the given ID

    EXPECT_EQ(buffer.popAllDataForId(101).size(), 0);
    EXPECT_EQ(buffer.popAllDataForId(102).size(), 1);
    EXPECT_EQ(buffer.currentSizeBytes(), 3);  // bytes, because only ID=103 left.
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
