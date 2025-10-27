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
#include "DummyNdkObjects.h"
#include "MockCameraManager.h"
#include "MockEvsCameraStream.h"
#include "MockNdkCamera.h"

#include <aidl/android/hardware/automotive/evs/EvsResult.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <android_car_feature.h>

using ::testing::_;
using ::testing::Return;
using ::testing::SetArgPointee;

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::BufferDesc;
using ::aidl::android::hardware::automotive::evs::EvsEventType;
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
        EXPECT_CALL(*mMockCameraManager, openSharedCamera(_, _))
                .WillOnce(testing::DoAll(SetArgPointee<1>(dummyDevice), Return(ACAMERA_OK)));

        ACameraDevice* device = nullptr;
        mMockCameraManager->openSharedCamera("mockCam0", &device);

        aidlevs::Stream streamConfig;
        mMockHalCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(device, "mockCam0", nullptr,
                                                                     streamConfig);
        mHalCameras.push_back(mMockHalCamera);

        mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mHalCameras);
        MockNdkCamera::setMockInstance(&mMockNdkCamera);
    }

    void TearDown() override {}

    std::shared_ptr<CompatVirtualCamera> mVirtualCamera;
    MockCameraManager* mMockCameraManager;
    MockNdkCamera mMockNdkCamera;
    std::shared_ptr<CompatHalCamera> mMockHalCamera;
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

TEST_F(CompatVirtualCameraTest, deliverFrame_StreamStopped) {
    {
        std::lock_guard lock(mVirtualCamera->mMutex);
        mVirtualCamera->mStreamState = CompatVirtualCamera::STOPPED;
    }
    BufferDesc buffer;
    buffer.deviceId = "mockCam0";
    EXPECT_FALSE(mVirtualCamera->deliverFrame(buffer));
}

TEST_F(CompatVirtualCameraTest, deliverFrame_FrameQuotaExceeded) {
    auto mockStream = ::ndk::SharedRefBase::make<MockEvsCameraStream>();
    EXPECT_CALL(*mockStream, notify(_)).WillOnce([](const aidlevs::EvsEventDesc& event) {
        EXPECT_EQ(event.aType, EvsEventType::FRAME_DROPPED);
        return ndk::ScopedAStatus::ok();
    });

    {
        std::lock_guard lock(mVirtualCamera->mMutex);
        mVirtualCamera->mStreamState = CompatVirtualCamera::RUNNING;
        mVirtualCamera->mMaxFramesInFlight = 1;
        mVirtualCamera->mStream = mockStream;

        BufferDesc buffer;
        buffer.deviceId = "mockCam0";
        mVirtualCamera->mFramesHeld["mockCam0"].push_back(std::move(buffer));
    }

    BufferDesc buffer;
    buffer.deviceId = "mockCam0";
    EXPECT_FALSE(mVirtualCamera->deliverFrame(buffer));
}

TEST_F(CompatVirtualCameraTest, deliverFrame_FrameQuotaExceededClientStreamNotSet) {
    {
        std::lock_guard lock(mVirtualCamera->mMutex);
        mVirtualCamera->mStreamState = CompatVirtualCamera::RUNNING;
        mVirtualCamera->mMaxFramesInFlight = 1;
        mVirtualCamera->mStream = nullptr;

        BufferDesc buffer;
        buffer.deviceId = "mockCam0";
        mVirtualCamera->mFramesHeld["mockCam0"].push_back(std::move(buffer));
    }

    BufferDesc buffer;
    buffer.deviceId = "mockCam0";
    EXPECT_FALSE(mVirtualCamera->deliverFrame(buffer));
}

TEST_F(CompatVirtualCameraTest, deliverFrame_Success) {
    auto mockStream = ::ndk::SharedRefBase::make<MockEvsCameraStream>();
    {
        std::lock_guard lock(mVirtualCamera->mMutex);
        mVirtualCamera->mStreamState = CompatVirtualCamera::RUNNING;
        mVirtualCamera->mMaxFramesInFlight = 2;
        mVirtualCamera->mStream = mockStream;
    }

    BufferDesc buffer;
    buffer.deviceId = "mockCam0";

    EXPECT_TRUE(mVirtualCamera->deliverFrame(buffer));

    {
        std::lock_guard lock(mVirtualCamera->mMutex);
        EXPECT_EQ(mVirtualCamera->mFramesHeld["mockCam0"].size(), 1);
    }
}

TEST_F(CompatVirtualCameraTest, doneWithFrame_EmptyInput) {
    std::vector<BufferDesc> buffers;
    ndk::ScopedAStatus status = mVirtualCamera->doneWithFrame(buffers);
    ASSERT_TRUE(status.isOk()) << "doneWithFrame failed with status: " << status.getDescription();
}

TEST_F(CompatVirtualCameraTest, doneWithFrame_BufferNotFound) {
    std::vector<BufferDesc> buffers;
    BufferDesc buffer;
    buffer.deviceId = "mockCam0";
    buffer.bufferId = 123;
    buffers.push_back(std::move(buffer));

    ndk::ScopedAStatus status = mVirtualCamera->doneWithFrame(buffers);
    ASSERT_TRUE(status.isOk()) << "doneWithFrame failed with status: " << status.getDescription();

    std::lock_guard lock(mVirtualCamera->mMutex);
    EXPECT_TRUE(mVirtualCamera->mFramesUsed["mockCam0"].empty());
}

TEST_F(CompatVirtualCameraTest, doneWithFrame_Success) {
    BufferDesc buffer;
    buffer.deviceId = "mockCam0";
    buffer.bufferId = 456;
    {
        std::lock_guard lock(mVirtualCamera->mMutex);
        mVirtualCamera->mStreamState = CompatVirtualCamera::RUNNING;
    }
    // Deliver the frame to add it to mFramesHeld
    EXPECT_TRUE(mVirtualCamera->deliverFrame(buffer));

    std::vector<BufferDesc> buffers;
    buffers.push_back(std::move(buffer));

    ndk::ScopedAStatus status = mVirtualCamera->doneWithFrame(buffers);
    ASSERT_TRUE(status.isOk()) << "doneWithFrame failed with status: " << status.getDescription();

    std::lock_guard lock(mVirtualCamera->mMutex);
    EXPECT_TRUE(mVirtualCamera->mFramesHeld["mockCam0"].empty());
    EXPECT_EQ(mVirtualCamera->mFramesUsed["mockCam0"].size(), 1);
    EXPECT_EQ(mVirtualCamera->mFramesUsed["mockCam0"][0].bufferId, 456);
}

TEST_F(CompatVirtualCameraTest, startVideoStream_NullReceiver) {
    ndk::ScopedAStatus status = mVirtualCamera->startVideoStream(nullptr);
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::INVALID_ARG));
}

TEST_F(CompatVirtualCameraTest, startVideoStream_StreamAlreadyRunning) {
    auto mockStream = ::ndk::SharedRefBase::make<MockEvsCameraStream>();
    {
        std::lock_guard lock(mVirtualCamera->mMutex);
        mVirtualCamera->mStreamState = CompatVirtualCamera::RUNNING;
    }
    ndk::ScopedAStatus status = mVirtualCamera->startVideoStream(mockStream);
    EXPECT_EQ(status.getServiceSpecificError(),
              static_cast<int>(EvsResult::STREAM_ALREADY_RUNNING));
}

TEST_F(CompatVirtualCameraTest, startVideoStream_Success) {
    // Mock NDK calls for successful stream start
    EXPECT_CALL(mMockNdkCamera, AImageReader_newWithUsage(_, _, _, _, _, _))
            .WillOnce(testing::Invoke([](int32_t, int32_t, int32_t, uint64_t, int32_t,
                                         AImageReader** reader) -> media_status_t {
                *reader = dummyReader;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AImageReader_getWindow(dummyReader, _))
            .WillOnce(testing::Invoke([](AImageReader*, ANativeWindow** window) -> media_status_t {
                *window = dummyWindow;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AImageReader_setImageListener(dummyReader, _))
            .WillOnce(Return(AMEDIA_OK));

    EXPECT_CALL(mMockNdkCamera, ACameraOutputTarget_create(dummyWindow, _))
            .WillOnce(testing::Invoke([](ANativeWindow*, ACameraOutputTarget** outputTarget) {
                *outputTarget = dummyOutputTarget;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutput_create(dummyWindow, _))
            .WillOnce(testing::Invoke([](ANativeWindow*, ACaptureSessionOutput** output) {
                *output = dummySessionOutput;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutputContainer_create(_))
            .WillOnce(testing::Invoke([](ACaptureSessionOutputContainer** container) {
                *container = dummyOutputContainer;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera,
                ACaptureSessionOutputContainer_add(dummyOutputContainer, dummySessionOutput))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera, ACameraDevice_createCaptureSession(_, dummyOutputContainer, _, _))
            .WillOnce(testing::Invoke([](ACameraDevice*, const ACaptureSessionOutputContainer*,
                                         const ACameraCaptureSession_stateCallbacks*,
                                         ACameraCaptureSession** session) {
                *session = dummySession;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACameraDevice_createCaptureRequest(_, _, _))
            .WillOnce(testing::Invoke([](const ACameraDevice*, ACameraDevice_request_template,
                                         ACaptureRequest** request) {
                *request = dummyCaptureRequest;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureRequest_addTarget(dummyCaptureRequest, dummyOutputTarget))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_setRepeatingRequest(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    auto mockStream = ::ndk::SharedRefBase::make<MockEvsCameraStream>();
    EXPECT_CALL(*mockStream, notify(_)).WillRepeatedly([](const aidlevs::EvsEventDesc& /*event*/) {
        return ndk::ScopedAStatus::ok();
    });
    ndk::ScopedAStatus status = mVirtualCamera->startVideoStream(mockStream);
    ASSERT_TRUE(status.isOk()) << "startVideoStream failed with status: "
                               << status.getDescription();
    status = mVirtualCamera->stopVideoStream();
    ASSERT_TRUE(status.isOk()) << "stopVideoStream failed with status: " << status.getDescription();
}
}  // namespace android::hardware::automotive::evs::compat
