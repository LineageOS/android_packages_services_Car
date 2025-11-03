/**
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

#include <gtest/gtest.h>
#include <android-base/logging.h>
#include <android_car_feature.h>
#include <aidl/android/hardware/automotive/evs/CameraDesc.h>
#include <aidl/android/hardware/automotive/evs/IEvsCamera.h>
#include <aidl/android/hardware/automotive/evs/Stream.h>

#include "CompatEnumerator.h"
#include "NdkCameraManager.h"

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::CameraDesc;
using ::aidl::android::hardware::automotive::evs::IEvsCamera;
using ::aidl::android::hardware::automotive::evs::Stream;
using ::ndk::ScopedAStatus;

class CompatEnumeratorIntegrationTest : public ::testing::Test {
protected:
    void SetUp() override {
        // Ensure the feature is enabled for this test
        if (!android::car::feature::car_evs_compat_lib()) {
            LOG(WARNING) << "EVS compatibility library feature is not enabled, skipping test.";
            GTEST_SKIP();
        }
        enumerator = ::ndk::SharedRefBase::make<CompatEnumerator>();
        ASSERT_NE(enumerator, nullptr);
    }

    std::shared_ptr<CompatEnumerator> enumerator;
};

TEST_F(CompatEnumeratorIntegrationTest, OpenAndCloseFirstAvailableCamera) {
    std::vector<CameraDesc> cameraList;
    ScopedAStatus status = enumerator->getCameraList(&cameraList);
    ASSERT_TRUE(status.isOk()) << status.getDescription();

    if (cameraList.empty()) {
        LOG(WARNING) << "No cameras found, skipping openCamera test.";
        GTEST_SKIP();
    }

    const std::string& cameraId = cameraList[0].id;
    LOG(INFO) << "Attempting to open camera: " << cameraId;

    Stream streamCfg;  // Default stream config
    std::shared_ptr<IEvsCamera> camera;
    status = enumerator->openCamera(cameraId, streamCfg, &camera);
    EXPECT_TRUE(status.isOk()) << "Failed to open camera " << cameraId << ": "
                               << status.getDescription();
    EXPECT_NE(camera, nullptr) << "openCamera returned null for " << cameraId;

    if (camera) {
        LOG(INFO) << "Successfully opened camera: " << cameraId;
        // TODO: close camera and verify close success.
        // status = enumerator->closeCamera(camera);
        // EXPECT_TRUE(status.isOk())
    }
}

TEST_F(CompatEnumeratorIntegrationTest, OpenAllAvailableCameras) {
    std::vector<CameraDesc> cameraList;
    ScopedAStatus status = enumerator->getCameraList(&cameraList);
    ASSERT_TRUE(status.isOk()) << status.getDescription();

    if (cameraList.empty()) {
        LOG(WARNING) << "No cameras found, skipping openAllCameras test.";
        GTEST_SKIP();
    }

    for (const auto& desc : cameraList) {
        const std::string& cameraId = desc.id;
        LOG(INFO) << "Attempting to open camera: " << cameraId;

        Stream streamCfg;  // Default stream config
        std::shared_ptr<IEvsCamera> camera;
        status = enumerator->openCamera(cameraId, streamCfg, &camera);
        EXPECT_TRUE(status.isOk()) << "Failed to open camera " << cameraId << ": "
                                   << status.getDescription();
        EXPECT_NE(camera, nullptr) << "openCamera returned null for " << cameraId;

        if (camera) {
            LOG(INFO) << "Successfully opened camera: " << cameraId;
            // TODO: close camera and verify close success.
            // status = enumerator->closeCamera(camera);
            // EXPECT_TRUE(status.isOk())
        }
    }
}

TEST_F(CompatEnumeratorIntegrationTest, OpenInvalidCamera) {
    std::string invalidCameraId = "invalidCameraId";
    Stream streamCfg;
    std::shared_ptr<IEvsCamera> camera;
    ScopedAStatus status = enumerator->openCamera(invalidCameraId, streamCfg, &camera);
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_ARGUMENT);

    EXPECT_EQ(camera, nullptr);
}

TEST_F(CompatEnumeratorIntegrationTest, OpenLogicalCameraAndGetInfo) {
    // 1. Get the list of available physical cameras.
    std::vector<CameraDesc> cameraList;
    ScopedAStatus status = enumerator->getCameraList(&cameraList);
    ASSERT_TRUE(status.isOk()) << status.getDescription();
    if (cameraList.size() < 2) {
        LOG(WARNING) << "Less than 2 cameras available, skipping logical camera test.";
        GTEST_SKIP();
    }

    // 2. Define a logical camera group using available physical cameras.
    CameraGroupMap cameraGroupMap;
    std::string logicalCameraId = "logical_camera_0";
    std::vector<std::string> physicalIds = {cameraList[0].id, cameraList[1].id};
    cameraGroupMap[logicalCameraId] = {logicalCameraId, physicalIds};

    // 3. Set the camera group map.
    status = enumerator->setCameraGroupMap(cameraGroupMap);
    ASSERT_TRUE(status.isOk()) << "Failed to set camera group map: " << status.getDescription();

    // 4. Open the logical camera.
    Stream streamCfg;  // Default stream config
    std::shared_ptr<IEvsCamera> camera;
    status = enumerator->openCamera(logicalCameraId, streamCfg, &camera);
    ASSERT_TRUE(status.isOk()) << "Failed to open logical camera " << logicalCameraId << ": "
                               << status.getDescription();
    ASSERT_NE(camera, nullptr);

    // 5. Get the camera info and verify it.
    CameraDesc desc;
    status = camera->getCameraInfo(&desc);
    ASSERT_TRUE(status.isOk()) << "Failed to get camera info for " << logicalCameraId;

    // In the fallback case, it should use the descriptor of the first physical camera,
    // but with the logical camera's ID.
    EXPECT_EQ(desc.id, logicalCameraId);

    // 6. Close the camera.
    // TODO: close camera and verify close success.
    // status = enumerator->closeCamera(camera);
    // EXPECT_TRUE(status.isOk()) << "Failed to close camera " << logicalCameraId;
}

TEST_F(CompatEnumeratorIntegrationTest, OpenLogicalCameraWithExplicitDescAndGetInfo) {
    // 1. Get the list of available physical cameras.
    std::vector<CameraDesc> cameraList;
    ScopedAStatus status = enumerator->getCameraList(&cameraList);
    ASSERT_TRUE(status.isOk()) << status.getDescription();
    if (cameraList.size() < 2) {
        LOG(WARNING) << "Less than 2 cameras available, skipping logical camera test.";
        GTEST_SKIP();
    }

    // 2. Define a logical camera group with an explicit descriptor.
    CameraGroupMap cameraGroupMap;
    std::string logicalCameraId = "logical_camera_1";
    std::vector<std::string> physicalIds = {cameraList[0].id, cameraList[1].id};
    std::vector<uint8_t> metadata = {1, 2, 3, 4};  // Dummy metadata
    cameraGroupMap[logicalCameraId] = {logicalCameraId, physicalIds, metadata};

    // 3. Set the camera group map.
    status = enumerator->setCameraGroupMap(cameraGroupMap);
    ASSERT_TRUE(status.isOk()) << "Failed to set camera group map: " << status.getDescription();

    // 4. Open the logical camera.
    Stream streamCfg;  // Default stream config
    std::shared_ptr<IEvsCamera> camera;
    status = enumerator->openCamera(logicalCameraId, streamCfg, &camera);
    ASSERT_TRUE(status.isOk()) << "Failed to open logical camera " << logicalCameraId << ": "
                               << status.getDescription();
    ASSERT_NE(camera, nullptr);

    // 5. Get the camera info and verify it matches the explicit descriptor.
    CameraDesc desc;
    status = camera->getCameraInfo(&desc);
    ASSERT_TRUE(status.isOk()) << "Failed to get camera info for " << logicalCameraId;
    EXPECT_EQ(desc.id, logicalCameraId);
    EXPECT_EQ(desc.metadata, metadata);

    // 6. Close the camera.
    // TODO: close camera and verify close success.
    // status = enumerator->closeCamera(camera);
    // EXPECT_TRUE(status.isOk()) << "Failed to close camera " << logicalCameraId;
}

TEST_F(CompatEnumeratorIntegrationTest, GetStreamListForAllAvailableCameras) {
    std::vector<CameraDesc> cameraList;
    ScopedAStatus status = enumerator->getCameraList(&cameraList);
    ASSERT_TRUE(status.isOk()) << status.getDescription();

    if (cameraList.empty()) {
        LOG(WARNING) << "No cameras found, skipping getStreamList test.";
        GTEST_SKIP();
    }

    for (const auto& desc : cameraList) {
        LOG(INFO) << "Getting stream list for camera: " << desc.id;
        std::vector<Stream> streamList;
        status = enumerator->getStreamList(desc, &streamList);
        EXPECT_TRUE(status.isOk()) << "Failed to get stream list for camera " << desc.id << ": "
                                   << status.getDescription();
        EXPECT_FALSE(streamList.empty()) << "Stream list is empty for camera " << desc.id;
    }
}

TEST_F(CompatEnumeratorIntegrationTest, GetStreamListForInvalidCamera) {
    CameraDesc invalidDesc;
    invalidDesc.id = "invalid-camera-id";
    std::vector<Stream> streamList;
    ScopedAStatus status = enumerator->getStreamList(invalidDesc, &streamList);
    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
}

}  // namespace android::hardware::automotive::evs::compat
