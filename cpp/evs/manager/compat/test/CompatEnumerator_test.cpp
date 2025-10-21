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

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <system/camera_metadata.h>

#include <android_car_feature.h>

using ::testing::_;
using ::testing::Return;
using ::testing::SetArgPointee;

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::CameraDesc;

class CompatEnumeratorTest : public ::testing::Test {
protected:
    void SetUp() override {
        if (!android::car::feature::car_evs_compat_lib()) {
            GTEST_SKIP() << "car_evs_compat_lib feature is not enabled.";
        }
        auto mockCameraManager = std::make_unique<MockCameraManager>();
        // Keep a raw pointer to the mock for setting expectations
        mMockCameraManager = mockCameraManager.get();
        mEnumerator = ::ndk::SharedRefBase::make<CompatEnumerator>(std::move(mockCameraManager));
    }

    void TearDown() override {}

    std::shared_ptr<CompatEnumerator> mEnumerator;
    MockCameraManager* mMockCameraManager;
};

TEST_F(CompatEnumeratorTest, setCameraGroupMap) {
    EXPECT_CALL(*mMockCameraManager, isAvailable()).WillRepeatedly(Return(true));
    std::vector<std::string> cameraIds = {"cam0", "cam1", "cam2", "cam3"};
    EXPECT_CALL(*mMockCameraManager, getCameraIdList(_))
            .WillOnce(testing::DoAll(SetArgPointee<0>(cameraIds), Return(ACAMERA_OK)));

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

    // Access the private member directly via the friend declaration
    ASSERT_NE(mEnumerator->mCameraGroupMap, nullptr);
    EXPECT_EQ(mEnumerator->mCameraGroupMap->size(), 2);

    auto it = mEnumerator->mCameraGroupMap->find(groupId1);
    ASSERT_NE(it, mEnumerator->mCameraGroupMap->end());
    EXPECT_EQ(it->second.groupId, groupId1);
    EXPECT_EQ(it->second.physicalIds, physicalIds1);
    ASSERT_TRUE(it->second.logicalCameraMetadata.empty());

    it = mEnumerator->mCameraGroupMap->find(groupId2);
    ASSERT_NE(it, mEnumerator->mCameraGroupMap->end());
    EXPECT_EQ(it->second.groupId, groupId2);
    EXPECT_EQ(it->second.physicalIds, physicalIds2);
    EXPECT_EQ(it->second.logicalCameraMetadata, metadata);
}

TEST_F(CompatEnumeratorTest, setCameraGroupMap_Invalid) {
    EXPECT_CALL(*mMockCameraManager, isAvailable()).WillRepeatedly(Return(true));
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
    EXPECT_CALL(*mMockCameraManager, isAvailable()).WillRepeatedly(Return(true));
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
    EXPECT_EQ(cameraListResult[0].id, "cam0");
    EXPECT_EQ(cameraListResult[1].id, "cam1");
}

TEST_F(CompatEnumeratorTest, getCameraList_NoCameras) {
    // Prepare mock data for the case where no cameras are found
    std::vector<std::string> cameraIds;  // Empty list
    // Set up mock calls
    EXPECT_CALL(*mMockCameraManager, isAvailable()).WillRepeatedly(Return(true));
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

}  // namespace android::hardware::automotive::evs::compat

