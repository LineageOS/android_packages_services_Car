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

#include "CompatHalCamera.h"

#include "CompatVirtualCamera.h"
#include "MockCameraManager.h"

#include <aidl/android/hardware/automotive/evs/Stream.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <android_car_feature.h>

using ::testing::_;
using ::testing::Return;
using ::testing::SetArgPointee;

namespace android::hardware::automotive::evs::compat {

class CompatHalCameraTest : public ::testing::Test {
protected:
    void SetUp() override {
        if (!android::car::feature::car_evs_compat_lib()) {
            GTEST_SKIP() << "car_evs_compat_lib feature is not enabled.";
        }
        auto mockCameraManager = std::make_unique<MockCameraManager>();
        mMockCameraManager = mockCameraManager.get();

        // Create a mock CompatHalCamera
        ACameraDevice* dummyDevice = reinterpret_cast<ACameraDevice*>(0x12345678);
        EXPECT_CALL(*mMockCameraManager, openSharedCamera(_, _))
                .WillOnce(testing::DoAll(SetArgPointee<1>(dummyDevice), Return(ACAMERA_OK)));

        ACameraDevice* device = nullptr;
        mMockCameraManager->openSharedCamera("mockCam0", &device);

        aidlevs::Stream streamConfig;
        mHalCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(device, "mockCam0", nullptr,
                                                                 streamConfig);
    }

    void TearDown() override {}

    std::shared_ptr<CompatHalCamera> mHalCamera;
    MockCameraManager* mMockCameraManager;
};

TEST_F(CompatHalCameraTest, ownVirtualCamera_NullCamera) {
    EXPECT_FALSE(mHalCamera->ownVirtualCamera(nullptr));
}

TEST_F(CompatHalCameraTest, ownVirtualCamera_ValidCamera) {
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<CompatVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);
    EXPECT_TRUE(mHalCamera->ownVirtualCamera(virtualCamera));
    EXPECT_EQ(mHalCamera->mVirtualCameras.size(), 1);
}

TEST_F(CompatHalCameraTest, disownVirtualCamera_NullCamera) {
    // Expect no crash
    mHalCamera->disownVirtualCamera(nullptr);
}

TEST_F(CompatHalCameraTest, disownVirtualCamera_ValidCamera) {
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<CompatVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);
    EXPECT_TRUE(mHalCamera->ownVirtualCamera(virtualCamera));
    EXPECT_EQ(mHalCamera->mVirtualCameras.size(), 1);

    mHalCamera->disownVirtualCamera(virtualCamera.get());
    EXPECT_EQ(mHalCamera->mVirtualCameras.size(), 0);
}

TEST_F(CompatHalCameraTest, disownVirtualCamera_NotOwnedCamera) {
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<CompatVirtualCamera> virtualCamera1 =
            ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);
    EXPECT_TRUE(mHalCamera->ownVirtualCamera(virtualCamera1));
    EXPECT_EQ(mHalCamera->mVirtualCameras.size(), 1);

    std::shared_ptr<CompatVirtualCamera> virtualCamera2 =
            ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);
    mHalCamera->disownVirtualCamera(virtualCamera2.get());
    EXPECT_EQ(mHalCamera->mVirtualCameras.size(), 1);
}
}  // namespace android::hardware::automotive::evs::compat
