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
    return {.mId = id, .mContent = content, .mPublisherUid = 0};
}

TEST(RingBufferTest, PopFrontReturnsCorrectResults) {
    RingBuffer buffer(/* sizeLimit= */ 10);
    buffer.push(buildBufferedCarData(101, {7}));
    buffer.push(buildBufferedCarData(102, {7}));

    BufferedCarData result = buffer.popFront();

    EXPECT_EQ(result, buildBufferedCarData(101, {7}));
}

TEST(RingBufferTest, PopFrontRemovesFromBuffer) {
    RingBuffer buffer(/* sizeLimit= */ 10);
    buffer.push(buildBufferedCarData(101, {7}));
    buffer.push(buildBufferedCarData(102, {7, 8}));

    buffer.popFront();

    EXPECT_EQ(buffer.size(), 1);  // only ID=102 left
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
