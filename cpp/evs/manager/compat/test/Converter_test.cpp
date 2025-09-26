/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "Converter.h"

#include <camera/NdkCameraMetadata.h>
#include <gtest/gtest.h>
#include <system/camera_metadata.h>

namespace android::hardware::automotive::evs::compat {

using aidl::android::hardware::automotive::evs::CameraDesc;

TEST(ConverterTest, toCameraDesc_WithValidMetadata) {
    const char* cameraId = "test_camera";
    const int vendorFlags = 123;

    // Create a dummy ACameraMetadata
    camera_metadata_t* raw_metadata = allocate_camera_metadata(1, 1);
    ASSERT_NE(raw_metadata, nullptr);
    int32_t lens_facing = ACAMERA_LENS_FACING_FRONT;
    add_camera_metadata_entry(raw_metadata, ACAMERA_LENS_FACING, &lens_facing, 1);
    ASSERT_EQ(validate_camera_metadata_structure(raw_metadata, nullptr), 0);
    const ACameraMetadata* metadata = reinterpret_cast<const ACameraMetadata*>(raw_metadata);

    CameraDesc desc = Converter::toCameraDesc(cameraId, metadata, vendorFlags);

    EXPECT_EQ(desc.id, cameraId);
    EXPECT_EQ(desc.vendorFlags, vendorFlags);

    // Verify metadata serialization
    size_t expected_size = get_camera_metadata_size(raw_metadata);
    EXPECT_EQ(desc.metadata.size(), expected_size);
    EXPECT_EQ(memcmp(desc.metadata.data(), raw_metadata, expected_size), 0);

    free_camera_metadata(raw_metadata);
}

TEST(ConverterTest, toCameraDesc_WithNullMetadata) {
    const char* cameraId = "test_camera_null";
    const int vendorFlags = 789;

    CameraDesc desc = Converter::toCameraDesc(cameraId, nullptr, vendorFlags);

    EXPECT_EQ(desc.id, cameraId);
    EXPECT_EQ(desc.vendorFlags, vendorFlags);
    EXPECT_TRUE(desc.metadata.empty());
}

}  // namespace android::hardware::automotive::evs::compat
