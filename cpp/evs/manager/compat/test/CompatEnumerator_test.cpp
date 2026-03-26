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

#include "CompatEnumerator.h"

#include "MockCameraManager.h"
#include "MockEvsEnumeratorStatusCallback.h"
#include "MockNdkCamera.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <system/camera_metadata.h>

using ::testing::_;
using ::testing::Return;
using ::testing::SetArgPointee;

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::CameraDesc;

class CompatEnumeratorTest : public ::testing::Test {
protected:
    void SetUp() override {
        auto mockCameraManager = std::make_unique<MockCameraManager>();
        // Keep a raw pointer to the mock for setting expectations
        mMockCameraManager = mockCameraManager.get();
        MockNdkCamera::setMockInstance(&mMockNdkCamera);
        mMockCallback = ndk::SharedRefBase::make<MockIEvsEnumeratorStatusCallback>();

        // Expect that the callback is registered in the constructor
        EXPECT_CALL(*mMockCameraManager, registerAvailabilityCallback(_)).Times(1);
        mEnumerator = ::ndk::SharedRefBase::make<CompatEnumerator>(std::move(mockCameraManager));
        EXPECT_CALL(*mMockCameraManager, isAvailable()).WillRepeatedly(Return(true));
    }

    void TearDown() override {
        if (mEnumerator && mMockCameraManager) {
            EXPECT_CALL(*mMockCameraManager,
                        unregisterAvailabilityCallback(&mEnumerator->mAvailabilityCallbacks))
                    .Times(1);
        }
        MockNdkCamera::setMockInstance(nullptr);
    }

    std::shared_ptr<CompatEnumerator> mEnumerator;
    MockCameraManager* mMockCameraManager;
    MockNdkCamera mMockNdkCamera;
    std::shared_ptr<MockIEvsEnumeratorStatusCallback> mMockCallback;
};

TEST_F(CompatEnumeratorTest, setCameraGroupMap) {
    std::vector<std::string> cameraIds = {"cam0", "cam1", "cam2", "cam3"};
    EXPECT_CALL(*mMockCameraManager, getCameraIdList(_))
            .WillOnce(testing::DoAll(SetArgPointee<0>(cameraIds), Return(ACAMERA_OK)));
    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(_, _))
            .WillRepeatedly(testing::DoAll(SetArgPointee<1>(nullptr), Return(ACAMERA_OK)));

    CameraGroupMap cameraGroupMap;
    std::string groupId1 = "test_group";
    std::vector<std::string> physicalIds1 = {"cam0", "cam1"};
    cameraGroupMap.insert({groupId1, {groupId1, physicalIds1, {}}});

    std::string groupId2 = "test_group2";
    std::vector<std::string> physicalIds2 = {"cam2", "cam3"};
    std::vector<uint8_t> metadata = {0, 1, 2};
    cameraGroupMap.insert({groupId2, {groupId2, physicalIds2, metadata}});

    ndk::ScopedAStatus status = mEnumerator->setCameraGroupMap(cameraGroupMap);
    ASSERT_TRUE(status.isOk()) << "setCameraGroupMap failed with status: "
                               << status.getDescription();

    // Verify mCameraGroupMap is updated correctly
    ASSERT_NE(mEnumerator->mCameraGroupMap, nullptr);
    EXPECT_EQ(mEnumerator->mCameraGroupMap->size(), 2);
    auto groupIt1 = mEnumerator->mCameraGroupMap->find(groupId1);
    ASSERT_NE(groupIt1, mEnumerator->mCameraGroupMap->end());
    EXPECT_EQ(groupIt1->second.groupId, groupId1);
    EXPECT_EQ(groupIt1->second.physicalIds, physicalIds1);
    ASSERT_TRUE(groupIt1->second.logicalCameraMetadata.empty());
    auto groupIt2 = mEnumerator->mCameraGroupMap->find(groupId2);
    ASSERT_NE(groupIt2, mEnumerator->mCameraGroupMap->end());
    EXPECT_EQ(groupIt2->second.groupId, groupId2);
    EXPECT_EQ(groupIt2->second.physicalIds, physicalIds2);
    EXPECT_EQ(groupIt2->second.logicalCameraMetadata, metadata);

    ASSERT_EQ(mEnumerator->mCameraDescs.size(), 6);
    auto descIt1 = mEnumerator->mCameraDescs.find(groupId1);
    ASSERT_NE(descIt1, mEnumerator->mCameraDescs.end());
    EXPECT_EQ(descIt1->second.id, groupId1);
    ASSERT_TRUE(descIt1->second.metadata.empty());
    auto descIt2 = mEnumerator->mCameraDescs.find(groupId2);
    ASSERT_NE(descIt2, mEnumerator->mCameraDescs.end());
    EXPECT_EQ(descIt2->second.id, groupId2);
    EXPECT_EQ(descIt2->second.metadata, metadata);
}

TEST_F(CompatEnumeratorTest, setCameraGroupMap_Invalid) {
    std::vector<std::string> cameraIds = {"cam0", "cam1"};

    EXPECT_CALL(*mMockCameraManager, getCameraIdList(_))
            .WillOnce(testing::DoAll(SetArgPointee<0>(cameraIds), Return(ACAMERA_OK)));

    CameraGroupMap cameraGroupMap;
    std::string groupId = "test_group";
    std::vector<std::string> physicalIds = {"cam2"};  // cam2 does not exist
    cameraGroupMap.insert({groupId, {groupId, physicalIds, {}}});

    ndk::ScopedAStatus status = mEnumerator->setCameraGroupMap(cameraGroupMap);
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_ARGUMENT);
}

TEST_F(CompatEnumeratorTest, getCameraList) {
    // Prepare mock data
    std::vector<std::string> cameraIds = {"cam0", "cam1"};

    // Set up mock calls
    EXPECT_CALL(*mMockCameraManager, getCameraIdList(_))
            .WillOnce(testing::DoAll(SetArgPointee<0>(cameraIds), Return(ACAMERA_OK)));
    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq("cam0"), _))
            .WillOnce(testing::DoAll(SetArgPointee<1>(nullptr), Return(ACAMERA_OK)));
    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq("cam1"), _))
            .WillOnce(testing::DoAll(SetArgPointee<1>(nullptr), Return(ACAMERA_OK)));

    // Execute the method under test
    std::vector<CameraDesc> cameraListResult;
    ndk::ScopedAStatus status = mEnumerator->getCameraList(&cameraListResult);

    // Verify the results
    ASSERT_TRUE(status.isOk()) << "getCameraList failed with status: " << status.getDescription();
    ASSERT_EQ(cameraListResult.size(), 2);

    // Collect the returned camera IDs into a set to ignore order
    std::set<std::string> resultSet;
    for (const auto& desc : cameraListResult) {
        resultSet.insert(desc.id);
    }

    // Check that the set contains the expected camera IDs
    EXPECT_TRUE(resultSet.count("cam0"));
    EXPECT_TRUE(resultSet.count("cam1"));
}

TEST_F(CompatEnumeratorTest, getCameraList_NoCameras) {
    // Prepare mock data for the case where no cameras are found
    std::vector<std::string> cameraIds;  // Empty list
    // Set up mock calls
    EXPECT_CALL(*mMockCameraManager, getCameraIdList(_))
            .WillOnce(testing::DoAll(SetArgPointee<0>(cameraIds), Return(ACAMERA_OK)));

    // Execute the method under test
    std::vector<CameraDesc> cameraListResult;
    ndk::ScopedAStatus status = mEnumerator->getCameraList(&cameraListResult);

    // Verify the results
    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(cameraListResult.empty());
}

TEST_F(CompatEnumeratorTest, isHardware) {
    bool isHardware = true;
    ndk::ScopedAStatus status = mEnumerator->isHardware(&isHardware);
    ASSERT_TRUE(status.isOk());
    EXPECT_FALSE(isHardware);
}

TEST_F(CompatEnumeratorTest, GetStreamList_Success) {
    const char* cameraId = "cam0";
    auto* dummyMetadata = reinterpret_cast<ACameraMetadata*>(0x1);

    // Prepare mock data
    std::vector<int32_t> streamConfigsData =
            {AIMAGE_FORMAT_YUV_420_888, 640, 480,
             ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT};
    ACameraMetadata_const_entry streamConfigsEntry = {};
    streamConfigsEntry.count = streamConfigsData.size();
    streamConfigsEntry.data.i32 = streamConfigsData.data();

    std::vector<int64_t> minFrameDurationsData = {AIMAGE_FORMAT_YUV_420_888, 640, 480, 33333333L};
    ACameraMetadata_const_entry minFrameDurationsEntry = {};
    minFrameDurationsEntry.count = minFrameDurationsData.size();
    minFrameDurationsEntry.data.i64 = minFrameDurationsData.data();

    std::vector<int32_t> orientationData = {0};
    ACameraMetadata_const_entry orientationEntry = {};
    orientationEntry.count = orientationData.size();
    orientationEntry.data.i32 = orientationData.data();

    // Set up mock calls
    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq(cameraId), _))
            .WillOnce(testing::DoAll(SetArgPointee<1>(dummyMetadata), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata, ACAMERA_SENSOR_ORIENTATION, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(orientationEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(streamConfigsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(minFrameDurationsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(dummyMetadata)).Times(1);

    // Execute
    CameraDesc desc;
    desc.id = cameraId;
    std::vector<aidlevs::Stream> streamList;
    ndk::ScopedAStatus status = mEnumerator->getStreamList(desc, &streamList);

    // Verify
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(streamList.size(), 1);
    EXPECT_EQ(streamList[0].id, 0);
    EXPECT_EQ(streamList[0].width, 640);
    EXPECT_EQ(streamList[0].height, 480);
    EXPECT_EQ(streamList[0].format,
              static_cast<::aidl::android::hardware::graphics::common::PixelFormat>(
                      AIMAGE_FORMAT_YUV_420_888));
    EXPECT_EQ(streamList[0].framerate, 30);
    EXPECT_EQ(streamList[0].streamType, aidlevs::StreamType::OUTPUT);
    EXPECT_EQ(streamList[0].rotation, aidlevs::Rotation::ROTATION_0);
    EXPECT_EQ(streamList[0].usage,
              ::aidl::android::hardware::graphics::common::BufferUsage::CAMERA_INPUT);
}

TEST_F(CompatEnumeratorTest, GetStreamList_Rotation) {
    const char* cameraId = "cam0";
    auto* dummyMetadata = reinterpret_cast<ACameraMetadata*>(0x1);

    // Prepare mock data for stream configs and frame durations
    std::vector<int32_t> streamConfigsData =
            {AIMAGE_FORMAT_YUV_420_888, 640, 480,
             ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT};
    ACameraMetadata_const_entry streamConfigsEntry = {};
    streamConfigsEntry.count = streamConfigsData.size();
    streamConfigsEntry.data.i32 = streamConfigsData.data();

    std::vector<int64_t> minFrameDurationsData = {AIMAGE_FORMAT_YUV_420_888, 640, 480, 33333333L};
    ACameraMetadata_const_entry minFrameDurationsEntry = {};
    minFrameDurationsEntry.count = minFrameDurationsData.size();
    minFrameDurationsEntry.data.i64 = minFrameDurationsData.data();

    // Define the mappings from orientation to rotation
    std::map<int, aidlevs::Rotation> rotationMap = {{0, aidlevs::Rotation::ROTATION_0},
                                                    {90, aidlevs::Rotation::ROTATION_90},
                                                    {180, aidlevs::Rotation::ROTATION_180},
                                                    {270, aidlevs::Rotation::ROTATION_270}};

    for (const auto& [orientation, expectedRotation] : rotationMap) {
        // Prepare mock data for orientation
        std::vector<int32_t> orientationData = {orientation};
        ACameraMetadata_const_entry orientationEntry = {};
        orientationEntry.count = orientationData.size();
        orientationEntry.data.i32 = orientationData.data();

        // Set up mock calls for each iteration
        EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq(cameraId), _))
                .WillOnce(testing::DoAll(SetArgPointee<1>(dummyMetadata), Return(ACAMERA_OK)));
        EXPECT_CALL(mMockNdkCamera,
                    ACameraMetadata_getConstEntry(dummyMetadata, ACAMERA_SENSOR_ORIENTATION, _))
                .WillOnce(testing::DoAll(SetArgPointee<2>(orientationEntry), Return(ACAMERA_OK)));
        EXPECT_CALL(mMockNdkCamera,
                    ACameraMetadata_getConstEntry(dummyMetadata,
                                                  ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
                                                  _))
                .WillOnce(testing::DoAll(SetArgPointee<2>(streamConfigsEntry), Return(ACAMERA_OK)));
        EXPECT_CALL(mMockNdkCamera,
                    ACameraMetadata_getConstEntry(dummyMetadata,
                                                  ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS, _))
                .WillOnce(testing::DoAll(SetArgPointee<2>(minFrameDurationsEntry),
                                         Return(ACAMERA_OK)));
        EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(dummyMetadata)).Times(1);

        // Execute
        CameraDesc desc;
        desc.id = cameraId;
        std::vector<aidlevs::Stream> streamList;
        ndk::ScopedAStatus status = mEnumerator->getStreamList(desc, &streamList);

        // Verify
        ASSERT_TRUE(status.isOk());
        ASSERT_EQ(streamList.size(), 1);
        EXPECT_EQ(streamList[0].rotation, expectedRotation)
                << "Failed for orientation " << orientation;
    }
}

TEST_F(CompatEnumeratorTest, GetStreamList_InvalidRotation) {
    const char* cameraId = "cam0";
    auto* dummyMetadata = reinterpret_cast<ACameraMetadata*>(0x1);

    // Prepare mock data
    std::vector<int32_t> streamConfigsData =
            {AIMAGE_FORMAT_YUV_420_888, 640, 480,
             ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT};
    ACameraMetadata_const_entry streamConfigsEntry = {};
    streamConfigsEntry.count = streamConfigsData.size();
    streamConfigsEntry.data.i32 = streamConfigsData.data();

    std::vector<int64_t> minFrameDurationsData = {AIMAGE_FORMAT_YUV_420_888, 640, 480, 33333333L};
    ACameraMetadata_const_entry minFrameDurationsEntry = {};
    minFrameDurationsEntry.count = minFrameDurationsData.size();
    minFrameDurationsEntry.data.i64 = minFrameDurationsData.data();

    // Prepare mock data for an invalid orientation
    std::vector<int32_t> orientationData = {123};  // Invalid orientation
    ACameraMetadata_const_entry orientationEntry = {};
    orientationEntry.count = orientationData.size();
    orientationEntry.data.i32 = orientationData.data();

    // Set up mock calls
    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq(cameraId), _))
            .WillOnce(testing::DoAll(SetArgPointee<1>(dummyMetadata), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata, ACAMERA_SENSOR_ORIENTATION, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(orientationEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(streamConfigsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(minFrameDurationsEntry), Return(ACAMERA_OK)));

    // Execute
    CameraDesc desc;
    desc.id = cameraId;
    std::vector<aidlevs::Stream> streamList;
    ndk::ScopedAStatus status = mEnumerator->getStreamList(desc, &streamList);

    // Verify
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
}

TEST_F(CompatEnumeratorTest, GetStreamList_HandlesInputStreamType) {
    const char* cameraId = "cam0";
    auto* dummyMetadata = reinterpret_cast<ACameraMetadata*>(0x1);

    std::vector<int32_t> streamConfigsData = {AIMAGE_FORMAT_YUV_420_888, 640, 480,
                                              ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_INPUT};
    ACameraMetadata_const_entry streamConfigsEntry = {};
    streamConfigsEntry.count = streamConfigsData.size();
    streamConfigsEntry.data.i32 = streamConfigsData.data();

    std::vector<int64_t> minFrameDurationsData = {AIMAGE_FORMAT_YUV_420_888, 640, 480, 33333333L};
    ACameraMetadata_const_entry minFrameDurationsEntry = {};
    minFrameDurationsEntry.count = minFrameDurationsData.size();
    minFrameDurationsEntry.data.i64 = minFrameDurationsData.data();

    std::vector<int32_t> orientationData = {0};
    ACameraMetadata_const_entry orientationEntry = {};
    orientationEntry.count = orientationData.size();
    orientationEntry.data.i32 = orientationData.data();

    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq(cameraId), _))
            .WillOnce(testing::DoAll(SetArgPointee<1>(dummyMetadata), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata, ACAMERA_SENSOR_ORIENTATION, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(orientationEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(streamConfigsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(minFrameDurationsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(dummyMetadata)).Times(1);

    CameraDesc desc;
    desc.id = cameraId;
    std::vector<aidlevs::Stream> streamList;
    ndk::ScopedAStatus status = mEnumerator->getStreamList(desc, &streamList);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(streamList.size(), 1);
    EXPECT_EQ(streamList[0].streamType, aidlevs::StreamType::INPUT);
}

TEST_F(CompatEnumeratorTest, GetStreamList_GetCharacteristicsFails) {
    const char* cameraId = "cam0";
    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq(cameraId), _))
            .WillOnce(Return(ACAMERA_ERROR_UNKNOWN));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(_)).Times(0);

    CameraDesc desc;
    desc.id = cameraId;
    std::vector<aidlevs::Stream> streamList;
    ndk::ScopedAStatus status = mEnumerator->getStreamList(desc, &streamList);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
}

TEST_F(CompatEnumeratorTest, GetStreamList_NoStreamConfigs) {
    const char* cameraId = "cam0";
    auto* dummyMetadata = reinterpret_cast<ACameraMetadata*>(0x1);
    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq(cameraId), _))
            .WillOnce(testing::DoAll(SetArgPointee<1>(dummyMetadata), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, _))
            .WillOnce(Return(ACAMERA_ERROR_METADATA_NOT_FOUND));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(dummyMetadata)).Times(1);

    CameraDesc desc;
    desc.id = cameraId;
    std::vector<aidlevs::Stream> streamList;
    ndk::ScopedAStatus status = mEnumerator->getStreamList(desc, &streamList);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
}

TEST_F(CompatEnumeratorTest, GetStreamList_NoFrameDurations) {
    const char* cameraId = "cam0";
    auto* dummyMetadata = reinterpret_cast<ACameraMetadata*>(0x1);

    std::vector<int32_t> streamConfigsData =
            {AIMAGE_FORMAT_YUV_420_888, 640, 480,
             ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT};
    ACameraMetadata_const_entry streamConfigsEntry = {};
    streamConfigsEntry.count = streamConfigsData.size();
    streamConfigsEntry.data.i32 = streamConfigsData.data();

    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq(cameraId), _))
            .WillOnce(testing::DoAll(SetArgPointee<1>(dummyMetadata), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(streamConfigsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS, _))
            .WillOnce(Return(ACAMERA_ERROR_METADATA_NOT_FOUND));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(dummyMetadata)).Times(1);

    CameraDesc desc;
    desc.id = cameraId;
    std::vector<aidlevs::Stream> streamList;
    ndk::ScopedAStatus status = mEnumerator->getStreamList(desc, &streamList);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
}

TEST_F(CompatEnumeratorTest, GetStreamList_MismatchedDuration) {
    const char* cameraId = "cam0";
    auto* dummyMetadata = reinterpret_cast<ACameraMetadata*>(0x1);

    std::vector<int32_t> streamConfigsData =
            {AIMAGE_FORMAT_YUV_420_888, 640, 480,
             ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT};
    ACameraMetadata_const_entry streamConfigsEntry = {};
    streamConfigsEntry.count = streamConfigsData.size();
    streamConfigsEntry.data.i32 = streamConfigsData.data();

    // Duration for a different resolution
    std::vector<int64_t> minFrameDurationsData = {AIMAGE_FORMAT_YUV_420_888, 1280, 720, 33333333L};
    ACameraMetadata_const_entry minFrameDurationsEntry = {};
    minFrameDurationsEntry.count = minFrameDurationsData.size();
    minFrameDurationsEntry.data.i64 = minFrameDurationsData.data();

    std::vector<int32_t> orientationData = {0};
    ACameraMetadata_const_entry orientationEntry = {};
    orientationEntry.count = orientationData.size();
    orientationEntry.data.i32 = orientationData.data();

    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq(cameraId), _))
            .WillOnce(testing::DoAll(SetArgPointee<1>(dummyMetadata), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata, ACAMERA_SENSOR_ORIENTATION, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(orientationEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(streamConfigsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(minFrameDurationsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(dummyMetadata)).Times(1);

    CameraDesc desc;
    desc.id = cameraId;
    std::vector<aidlevs::Stream> streamList;
    ndk::ScopedAStatus status = mEnumerator->getStreamList(desc, &streamList);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
}

TEST_F(CompatEnumeratorTest, GetStreamList_Nullptr) {
    CameraDesc desc;
    desc.id = "cam0";
    ndk::ScopedAStatus status = mEnumerator->getStreamList(desc, nullptr);
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_ARGUMENT);
}

TEST_F(CompatEnumeratorTest, GetStreamList_NoAvailableStreams) {
    const char* cameraId = "cam0";
    auto* dummyMetadata = reinterpret_cast<ACameraMetadata*>(0x1);

    // Prepare mock data for orientation
    std::vector<int32_t> orientationData = {0};
    ACameraMetadata_const_entry orientationEntry = {};
    orientationEntry.count = orientationData.size();
    orientationEntry.data.i32 = orientationData.data();

    // Mock zero stream configurations
    ACameraMetadata_const_entry streamConfigsEntry = {};
    streamConfigsEntry.count = 0;
    streamConfigsEntry.data.i32 = nullptr;

    std::vector<int64_t> minFrameDurationsData = {};  // Empty
    ACameraMetadata_const_entry minFrameDurationsEntry = {};
    minFrameDurationsEntry.count = 0;
    minFrameDurationsEntry.data.i64 = nullptr;

    // Set up mock calls
    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq(cameraId), _))
            .WillOnce(testing::DoAll(SetArgPointee<1>(dummyMetadata), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata, ACAMERA_SENSOR_ORIENTATION, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(orientationEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(streamConfigsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(minFrameDurationsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(dummyMetadata)).Times(1);

    // Execute
    CameraDesc desc;
    desc.id = cameraId;
    std::vector<aidlevs::Stream> streamList;
    ndk::ScopedAStatus status = mEnumerator->getStreamList(desc, &streamList);

    // Verify
    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(streamList.empty());
}

TEST_F(CompatEnumeratorTest, GetStreamList_InvalidDuration) {
    const char* cameraId = "cam0";
    auto* dummyMetadata = reinterpret_cast<ACameraMetadata*>(0x1);

    std::vector<int32_t> streamConfigsData =
            {AIMAGE_FORMAT_YUV_420_888, 640, 480,
             ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT};
    ACameraMetadata_const_entry streamConfigsEntry = {};
    streamConfigsEntry.count = streamConfigsData.size();
    streamConfigsEntry.data.i32 = streamConfigsData.data();

    std::vector<int64_t> minFrameDurationsData = {AIMAGE_FORMAT_YUV_420_888, 640, 480, 0L};
    ACameraMetadata_const_entry minFrameDurationsEntry = {};
    minFrameDurationsEntry.count = minFrameDurationsData.size();
    minFrameDurationsEntry.data.i64 = minFrameDurationsData.data();

    std::vector<int32_t> orientationData = {0};
    ACameraMetadata_const_entry orientationEntry = {};
    orientationEntry.count = orientationData.size();
    orientationEntry.data.i32 = orientationData.data();

    EXPECT_CALL(*mMockCameraManager, getCameraCharacteristics(testing::StrEq(cameraId), _))
            .WillOnce(testing::DoAll(SetArgPointee<1>(dummyMetadata), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata, ACAMERA_SENSOR_ORIENTATION, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(orientationEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(streamConfigsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(dummyMetadata,
                                              ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS, _))
            .WillOnce(testing::DoAll(SetArgPointee<2>(minFrameDurationsEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(dummyMetadata)).Times(1);

    CameraDesc desc;
    desc.id = cameraId;
    std::vector<aidlevs::Stream> streamList;
    ndk::ScopedAStatus status = mEnumerator->getStreamList(desc, &streamList);

    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
}

TEST_F(CompatEnumeratorTest, CameraAvailabilityCallbacks) {
    // Prepare mock data
    std::string cameraId = "cam0";

    // Register callback
    ndk::ScopedAStatus status = mEnumerator->registerStatusCallback(mMockCallback);
    ASSERT_TRUE(status.isOk());

    // Simulate camera available
    aidlevs::DeviceStatus availableStatus{.id = cameraId,
                                          .status = aidlevs::DeviceStatusType::CAMERA_AVAILABLE};
    EXPECT_CALL(*mMockCallback, deviceStatusChanged(testing::ElementsAre(availableStatus)))
            .Times(1)
            .WillOnce(Return(ndk::ScopedAStatus::ok()));
    mEnumerator->mAvailabilityCallbacks.onCameraAvailable(mEnumerator.get(), cameraId.c_str());

    // Simulate camera unavailable
    aidlevs::DeviceStatus
            unavailableStatus{.id = cameraId,
                              .status = aidlevs::DeviceStatusType::CAMERA_NOT_AVAILABLE};
    EXPECT_CALL(*mMockCallback, deviceStatusChanged(testing::ElementsAre(unavailableStatus)))
            .Times(1)
            .WillOnce(Return(ndk::ScopedAStatus::ok()));
    mEnumerator->mAvailabilityCallbacks.onCameraUnavailable(mEnumerator.get(), cameraId.c_str());
}

}  // namespace android::hardware::automotive::evs::compat
