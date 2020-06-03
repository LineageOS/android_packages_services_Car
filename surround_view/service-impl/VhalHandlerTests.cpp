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

#define LOG_TAG "VhalHandlerTests"

#include <android/hardware/automotive/vehicle/2.0/IVehicle.h>
#include "VhalHandler.h"

#include <gtest/gtest.h>
#include <time.h>

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace V1_0 {
namespace implementation {
namespace {

void SetSamplePropertiesToRead(VhalHandler* vhalHandler) {
    std::vector<vehicle::V2_0::VehiclePropValue> properties_to_read;
    vehicle::V2_0::VehiclePropValue property_read;
    property_read.prop = static_cast<int32_t>(vehicle::V2_0::VehicleProperty::INFO_MODEL);
    properties_to_read.push_back(property_read);
    ASSERT_TRUE(vhalHandler->setPropertiesToRead(properties_to_read));
}

TEST(VhalhandlerTests, UninitializedStartFail) {
    VhalHandler vhalHandler;
    ASSERT_FALSE(vhalHandler.startPropertiesUpdate());
}

TEST(VhalhandlerTests, StartStopSuccess) {
    VhalHandler vhalHandler;
    ASSERT_TRUE(vhalHandler.initialize(VhalHandler::UpdateMethod::GET, 10));
    SetSamplePropertiesToRead(&vhalHandler);
    ASSERT_TRUE(vhalHandler.startPropertiesUpdate());
    ASSERT_TRUE(vhalHandler.stopPropertiesUpdate());
}

TEST(VhalhandlerTests, StopTwiceFail) {
    VhalHandler vhalHandler;
    ASSERT_TRUE(vhalHandler.initialize(VhalHandler::UpdateMethod::GET, 10));
    SetSamplePropertiesToRead(&vhalHandler);
    ASSERT_TRUE(vhalHandler.startPropertiesUpdate());
    ASSERT_TRUE(vhalHandler.stopPropertiesUpdate());
    ASSERT_FALSE(vhalHandler.stopPropertiesUpdate());
}

TEST(VhalhandlerTests, NoStartFail) {
    VhalHandler vhalHandler;
    ASSERT_TRUE(vhalHandler.initialize(VhalHandler::UpdateMethod::GET, 10));
    SetSamplePropertiesToRead(&vhalHandler);
    ASSERT_FALSE(vhalHandler.stopPropertiesUpdate());
}

TEST(VhalhandlerTests, StartAgainSuccess) {
    VhalHandler vhalHandler;
    ASSERT_TRUE(vhalHandler.initialize(VhalHandler::UpdateMethod::GET, 10));
    SetSamplePropertiesToRead(&vhalHandler);
    ASSERT_TRUE(vhalHandler.startPropertiesUpdate());
    ASSERT_TRUE(vhalHandler.stopPropertiesUpdate());
    ASSERT_TRUE(vhalHandler.startPropertiesUpdate());
    ASSERT_TRUE(vhalHandler.stopPropertiesUpdate());
}

TEST(VhalhandlerTests, GetMethodSuccess) {
    VhalHandler vhalHandler;
    ASSERT_TRUE(vhalHandler.initialize(VhalHandler::UpdateMethod::GET, 10));

    SetSamplePropertiesToRead(&vhalHandler);

    ASSERT_TRUE(vhalHandler.startPropertiesUpdate());
    sleep(1);
    std::vector<vehicle::V2_0::VehiclePropValue> property_values;
    EXPECT_TRUE(vhalHandler.getPropertyValues(&property_values));
    EXPECT_EQ(property_values.size(), 1);

    EXPECT_TRUE(vhalHandler.stopPropertiesUpdate());
}

}  // namespace
}  // namespace implementation
}  // namespace V1_0
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android
