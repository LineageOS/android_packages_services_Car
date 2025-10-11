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
#include "MockNdkCamera.h"

#include <aidl/android/hardware/automotive/evs/Stream.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <android_car_feature.h>

using ::testing::_;
using ::testing::Invoke;
using ::testing::Return;
using ::testing::SetArgPointee;

namespace android::hardware::automotive::evs::compat {

// Dummy NDK object pointers
auto* dummyReader = reinterpret_cast<AImageReader*>(0x1001);
auto* dummyWindow = reinterpret_cast<ANativeWindow*>(0x1002);
auto* dummyOutputTarget = reinterpret_cast<ACameraOutputTarget*>(0x1003);
auto* dummySessionOutput = reinterpret_cast<ACaptureSessionOutput*>(0x1004);
auto* dummyOutputContainer = reinterpret_cast<ACaptureSessionOutputContainer*>(0x1005);
auto* dummySession = reinterpret_cast<ACameraCaptureSession*>(0x1006);
auto* dummyCaptureRequest = reinterpret_cast<ACaptureRequest*>(0x1007);

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

        // Set up MockNdkCamera
        MockNdkCamera::setMockInstance(&mMockNdkCamera);
    }

    void TearDown() override { MockNdkCamera::setMockInstance(nullptr); }

    std::shared_ptr<CompatHalCamera> mHalCamera;
    MockCameraManager* mMockCameraManager;
    MockNdkCamera mMockNdkCamera;
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

TEST_F(CompatHalCameraTest, clientStreamStarting_Success) {
    // Mock NDK calls for successful stream start
    EXPECT_CALL(mMockNdkCamera, AImageReader_newWithUsage(_, _, _, _, _, _))
            .WillOnce(Invoke([](int32_t, int32_t, int32_t, uint64_t, int32_t,
                                AImageReader** reader) -> media_status_t {
                *reader = dummyReader;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AImageReader_getWindow(dummyReader, _))
            .WillOnce(Invoke([](AImageReader*, ANativeWindow** window) -> media_status_t {
                *window = dummyWindow;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AImageReader_setImageListener(dummyReader, _))
            .WillOnce(Return(AMEDIA_OK));

    EXPECT_CALL(mMockNdkCamera, ACameraOutputTarget_create(dummyWindow, _))
            .WillOnce(Invoke([](ANativeWindow*, ACameraOutputTarget** outputTarget) {
                *outputTarget = dummyOutputTarget;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutput_create(dummyWindow, _))
            .WillOnce(Invoke([](ANativeWindow*, ACaptureSessionOutput** output) {
                *output = dummySessionOutput;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutputContainer_create(_))
            .WillOnce(Invoke([](ACaptureSessionOutputContainer** container) {
                *container = dummyOutputContainer;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera,
                ACaptureSessionOutputContainer_add(dummyOutputContainer, dummySessionOutput))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera, ACameraDevice_createCaptureSession(_, dummyOutputContainer, _, _))
            .WillOnce(Invoke([](ACameraDevice*, const ACaptureSessionOutputContainer*,
                                const ACameraCaptureSession_stateCallbacks*,
                                ACameraCaptureSession** session) {
                *session = dummySession;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACameraDevice_createCaptureRequest(_, _, _))
            .WillOnce(Invoke([](const ACameraDevice*, ACameraDevice_request_template,
                                ACaptureRequest** request) {
                *request = dummyCaptureRequest;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureRequest_addTarget(dummyCaptureRequest, dummyOutputTarget))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_setRepeatingRequest(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    ::ndk::ScopedAStatus status = mHalCamera->clientStreamStarting();
    EXPECT_TRUE(status.isOk()) << status.getDescription();
    EXPECT_EQ(mHalCamera->mStreamState, CompatHalCamera::RUNNING);

    // Expect clean up calls
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_stopRepeating(dummySession)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_close(dummySession)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureRequest_free(dummyCaptureRequest)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutputContainer_free(dummyOutputContainer)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutput_free(dummySessionOutput)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACameraOutputTarget_free(dummyOutputTarget)).Times(1);
    EXPECT_CALL(mMockNdkCamera, AImageReader_delete(dummyReader)).Times(1);

    // Call cleanUpNdkResources to trigger the mocked clean up functions
    mHalCamera->cleanUpNdkResources();
}

TEST_F(CompatHalCameraTest, clientStreamStarting_AlreadyRunning) {
    // Set state to RUNNING
    {
        std::lock_guard<std::mutex> lock(mHalCamera->mMutex);
        mHalCamera->mStreamState = CompatHalCamera::RUNNING;
    }

    ::ndk::ScopedAStatus status = mHalCamera->clientStreamStarting();
    EXPECT_TRUE(status.isOk());  // Should return OK if already running
}

TEST_F(CompatHalCameraTest, clientStreamStarting_StartStreamFail) {
    // Mock AImageReader_newWithUsage to fail
    EXPECT_CALL(mMockNdkCamera, AImageReader_newWithUsage(_, _, _, _, _, _))
            .WillOnce(Return(AMEDIA_ERROR_UNKNOWN));

    ::ndk::ScopedAStatus status = mHalCamera->clientStreamStarting();
    EXPECT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
    EXPECT_EQ(status.getServiceSpecificError(),
              static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    EXPECT_EQ(mHalCamera->mStreamState, CompatHalCamera::STOPPED);
}

}  // namespace android::hardware::automotive::evs::compat
