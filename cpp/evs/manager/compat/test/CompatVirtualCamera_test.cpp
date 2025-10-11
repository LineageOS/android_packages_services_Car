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

#include "CompatVirtualCamera.h"

#include "CompatHalCamera.h"
#include "MockCameraManager.h"

#include <aidl/android/hardware/automotive/evs/EvsResult.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <android_car_feature.h>

using ::testing::_;
using ::testing::Return;
using ::testing::SetArgPointee;

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::EvsResult;

class CompatVirtualCameraTest : public ::testing::Test {
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
        std::shared_ptr<CompatHalCamera> mockHalCamera =
                ::ndk::SharedRefBase::make<CompatHalCamera>(device, "mockCam0", nullptr,
                                                            streamConfig);
        mHalCameras.push_back(mockHalCamera);

        mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mHalCameras);
    }

    void TearDown() override {}

    std::shared_ptr<CompatVirtualCamera> mVirtualCamera;
    MockCameraManager* mMockCameraManager;
    std::vector<std::shared_ptr<CompatHalCamera>> mHalCameras;
};

TEST_F(CompatVirtualCameraTest, setMaxFramesInFlight_Valid) {
    ndk::ScopedAStatus status = mVirtualCamera->setMaxFramesInFlight(5);
    ASSERT_TRUE(status.isOk()) << "setMaxFramesInFlight failed with status: "
                               << status.getDescription();
    EXPECT_EQ(mVirtualCamera->getMaxFramesInFlight(), 5);
}

TEST_F(CompatVirtualCameraTest, setMaxFramesInFlight_Invalid) {
    ndk::ScopedAStatus status = mVirtualCamera->setMaxFramesInFlight(0);
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::INVALID_ARG));

    status = mVirtualCamera->setMaxFramesInFlight(-1);
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::INVALID_ARG));
}

TEST_F(CompatVirtualCameraTest, setMaxFramesInFlight_StreamRunning) {
    // TODO: Add test for `setMaxFramesInFlight_StreamRunning`
}

TEST_F(CompatVirtualCameraTest, getPhysicalCameraInfo_DeviceIdNotFound) {
    aidlevs::CameraDesc desc;
    ndk::ScopedAStatus status = mVirtualCamera->getPhysicalCameraInfo("nonExistentId", &desc);
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::INVALID_ARG));
}

TEST_F(CompatVirtualCameraTest, getPhysicalCameraInfo_CameraDescNotSet) {
    // The default mockHalCamera in SetUp is created with a null CameraDesc
    aidlevs::CameraDesc desc;
    ndk::ScopedAStatus status = mVirtualCamera->getPhysicalCameraInfo("mockCam0", &desc);
    EXPECT_EQ(status.getServiceSpecificError(),
              static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
}

TEST_F(CompatVirtualCameraTest, getPhysicalCameraInfo_Success) {
    // Create a new virtual camera with a HalCamera that has a valid CameraDesc
    ACameraDevice* dummyDevice = reinterpret_cast<ACameraDevice*>(0x87654321);
    aidlevs::Stream streamConfig;
    aidlevs::CameraDesc validDesc;
    validDesc.id = "mockCam1";
    std::shared_ptr<CompatHalCamera> halCameraWithDesc =
            ::ndk::SharedRefBase::make<CompatHalCamera>(dummyDevice, "mockCam1", &validDesc,
                                                        streamConfig);
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras = {halCameraWithDesc};
    std::shared_ptr<CompatVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);

    aidlevs::CameraDesc desc;
    ndk::ScopedAStatus status = virtualCamera->getPhysicalCameraInfo("mockCam1", &desc);
    ASSERT_TRUE(status.isOk()) << "getPhysicalCameraInfo failed with status: "
                               << status.getDescription();
    EXPECT_EQ(desc.id, "mockCam1");
}

}  // namespace android::hardware::automotive::evs::compat
