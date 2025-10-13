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

#include "MockNdkCamera.h"

#include <aidl/android/hardware/automotive/evs/BufferDesc.h>
#include <camera/NdkCameraMetadata.h>
#include <cutils/native_handle.h>
#include <gtest/gtest.h>
#include <hardware/gralloc.h>
#include <system/camera_metadata.h>

#include <fcntl.h>
#include <unistd.h>

using aidl::android::hardware::automotive::evs::CameraDesc;
using ::testing::_;
using ::testing::Invoke;
using ::testing::Return;

namespace android::hardware::automotive::evs::compat {

using aidl::android::hardware::automotive::evs::BufferDesc;
using aidl::android::hardware::automotive::evs::CameraDesc;

// Dummy NDK object pointers from CompatHalCamera_test.cpp
extern AImageReader* dummyReader;

class ConverterTest : public ::testing::Test {
protected:
    void SetUp() override {
        // Set up MockNdkCamera
        MockNdkCamera::setMockInstance(&mMockNdkCamera);
    }

    void TearDown() override { MockNdkCamera::setMockInstance(nullptr); }

    MockNdkCamera mMockNdkCamera;
};

TEST_F(ConverterTest, toCameraDesc_WithValidMetadata) {
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

TEST_F(ConverterTest, toCameraDesc_WithNullMetadata) {
    const char* cameraId = "test_camera_null";
    const int vendorFlags = 789;

    CameraDesc desc = Converter::toCameraDesc(cameraId, nullptr, vendorFlags);

    EXPECT_EQ(desc.id, cameraId);
    EXPECT_EQ(desc.vendorFlags, vendorFlags);
    EXPECT_TRUE(desc.metadata.empty());
}

TEST_F(ConverterTest, toAImageReader_ValidConfig) {
    aidl::android::hardware::automotive::evs::Stream config;
    config.width = 640;
    config.height = 480;

    EXPECT_CALL(mMockNdkCamera,
                AImageReader_newWithUsage(640, 480, AIMAGE_FORMAT_RGBA_8888,
                                          GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_SW_READ_OFTEN |
                                                  GRALLOC_USAGE_SW_WRITE_OFTEN,
                                          3, _))
            .WillOnce(Invoke([](int32_t, int32_t, int32_t, uint64_t, int32_t,
                                AImageReader** reader) -> media_status_t {
                *reader = dummyReader;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AImageReader_delete(dummyReader));

    AImageReader* reader = nullptr;
    media_status_t status = Converter::toAImageReader(config, /*maxImages=*/3, &reader);

    EXPECT_EQ(status, AMEDIA_OK);
    ASSERT_NE(reader, nullptr);

    // Clean up
    AImageReader_delete(reader);
}

TEST_F(ConverterTest, toAImageReader_NullReader) {
    aidl::android::hardware::automotive::evs::Stream config;
    config.width = 640;
    config.height = 480;

    media_status_t status = Converter::toAImageReader(config, /*maxImages=*/3, nullptr);
    EXPECT_EQ(status, AMEDIA_ERROR_INVALID_PARAMETER);
}

TEST_F(ConverterTest, toAImageReader_AImageReaderNewFails) {
    aidl::android::hardware::automotive::evs::Stream config;
    config.width = 640;
    config.height = 480;

    EXPECT_CALL(mMockNdkCamera,
                AImageReader_newWithUsage(640, 480, AIMAGE_FORMAT_RGBA_8888,
                                          GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_SW_READ_OFTEN |
                                                  GRALLOC_USAGE_SW_WRITE_OFTEN,
                                          3, _))
            .WillOnce(Return(AMEDIA_ERROR_UNKNOWN));

    AImageReader* reader = nullptr;
    media_status_t status = Converter::toAImageReader(config, /*maxImages=*/3, &reader);

    EXPECT_EQ(status, AMEDIA_ERROR_UNKNOWN);
    EXPECT_EQ(reader, nullptr);
}

TEST_F(ConverterTest, toBufferDesc_NullImage) {
    aidl::android::hardware::automotive::evs::BufferDesc bufferDesc;
    media_status_t status = Converter::toBufferDesc(nullptr, 0, "test_device", bufferDesc);
    EXPECT_EQ(status, AMEDIA_ERROR_INVALID_PARAMETER);
}

TEST_F(ConverterTest, toBufferDesc_Success) {
    AImage* dummyImage = reinterpret_cast<AImage*>(0x1234);
    AHardwareBuffer* dummyBuffer = reinterpret_cast<AHardwareBuffer*>(0x5678);
    native_handle_t* dummyHandle = native_handle_create(1, 0);
    int devNullFd = open("/dev/null", O_RDONLY);
    ASSERT_GE(devNullFd, 0);
    dummyHandle->data[0] = devNullFd;  // Use a valid fd

    EXPECT_CALL(mMockNdkCamera, AImage_getHardwareBuffer(dummyImage, _))
            .WillOnce(Invoke([&](const AImage*, AHardwareBuffer** buffer) {
                *buffer = dummyBuffer;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AHardwareBuffer_getNativeHandle(dummyBuffer))
            .WillOnce(Return(dummyHandle));
    EXPECT_CALL(mMockNdkCamera, AImage_getTimestamp(dummyImage, _))
            .WillOnce(Invoke([](const AImage*, int64_t* timestamp) {
                *timestamp = 12345;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AHardwareBuffer_release(dummyBuffer));

    BufferDesc bufferDesc;
    media_status_t status = Converter::toBufferDesc(dummyImage, 1, "test_device", bufferDesc);

    EXPECT_EQ(status, AMEDIA_OK);
    EXPECT_EQ(bufferDesc.bufferId, 1);
    EXPECT_EQ(bufferDesc.deviceId, "test_device");
    EXPECT_EQ(bufferDesc.timestamp, 12345);
    EXPECT_EQ(bufferDesc.pixelSizeBytes, -1);
    ASSERT_EQ(bufferDesc.buffer.handle.fds.size(), 1);
    EXPECT_NE(bufferDesc.buffer.handle.fds[0].get(), devNullFd);  // dupToAidl should dup the fd
    EXPECT_GE(bufferDesc.buffer.handle.fds[0].get(), 0);

    close(devNullFd);
    native_handle_delete(dummyHandle);
}

TEST_F(ConverterTest, toBufferDesc_GetHardwareBufferFails) {
    AImage* dummyImage = reinterpret_cast<AImage*>(0x1234);

    EXPECT_CALL(mMockNdkCamera, AImage_getHardwareBuffer(dummyImage, _))
            .WillOnce(Return(AMEDIA_ERROR_UNKNOWN));

    BufferDesc bufferDesc;
    media_status_t status = Converter::toBufferDesc(dummyImage, 1, "test_device", bufferDesc);

    EXPECT_EQ(status, AMEDIA_ERROR_UNKNOWN);
}

TEST_F(ConverterTest, toBufferDesc_GetNativeHandleFails) {
    AImage* dummyImage = reinterpret_cast<AImage*>(0x1234);
    AHardwareBuffer* dummyBuffer = reinterpret_cast<AHardwareBuffer*>(0x5678);

    EXPECT_CALL(mMockNdkCamera, AImage_getHardwareBuffer(dummyImage, _))
            .WillOnce(Invoke([&](const AImage*, AHardwareBuffer** buffer) {
                *buffer = dummyBuffer;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AHardwareBuffer_getNativeHandle(dummyBuffer))
            .WillOnce(Return(nullptr));
    EXPECT_CALL(mMockNdkCamera, AHardwareBuffer_release(dummyBuffer));

    BufferDesc bufferDesc;
    media_status_t status = Converter::toBufferDesc(dummyImage, 1, "test_device", bufferDesc);

    EXPECT_EQ(status, AMEDIA_ERROR_UNKNOWN);
}

}  // namespace android::hardware::automotive::evs::compat
