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

#include <android/frameworks/automotive/telemetry/CarData.h>
#include <android/frameworks/automotive/telemetry/ICarTelemetry.h>
#include <gtest/gtest.h>

using android::frameworks::automotive::telemetry::CarData;
using android::frameworks::automotive::telemetry::ICarTelemetry;

namespace android {
namespace automotive {
namespace telemetry {

TEST(CarTelemetryImplTest, TestWriteReturnsOk) {
    CarTelemetryImpl telemetry;

    CarData msg;
    msg.id = 101;
    msg.content = {1, 0, 1, 0};

    auto status = telemetry.write({msg});

    EXPECT_TRUE(status.isOk()) << status;
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
