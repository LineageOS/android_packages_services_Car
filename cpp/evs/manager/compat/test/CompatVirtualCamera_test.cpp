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

    void TearDown() override {
        if (mRawMetadata) {
            free_camera_metadata(mRawMetadata);
            mRawMetadata = nullptr;
        }
        mTestHalCameras.clear();
    }

    // Helper to build and set up the camera with specific metadata
    template <typename T>
    void setupCameraWithMetadata(uint32_t tag, const std::vector<T>& data,
                                 const std::string& cameraId = "testCam") {
        // 1. Allocate metadata
        mRawMetadata = allocate_camera_metadata(1, data.size() * sizeof(T));
        ASSERT_NE(mRawMetadata, nullptr);

        // 2. Add entry
        ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, tag, data.data(), data.size()), 0);
        ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

        // 3. Serialize
        size_t size = get_camera_metadata_size(mRawMetadata);
        std::vector<uint8_t> metadataVector(size);
        memcpy(metadataVector.data(), mRawMetadata, size);

        // 4. Create CameraDesc
        aidlevs::CameraDesc desc;
        desc.id = cameraId;
        desc.metadata = metadataVector;

        // 5. Create CompatHalCamera and store it to keep it alive
        ACameraDevice* device = reinterpret_cast<ACameraDevice*>(0x1234);
        aidlevs::Stream streamConfig;
        auto halCamera =
                ::ndk::SharedRefBase::make<CompatHalCamera>(device, cameraId, &desc, streamConfig);
        mTestHalCameras.push_back(halCamera);

        // 6. Create CompatVirtualCamera
        mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);
    }

    std::shared_ptr<CompatVirtualCamera> mVirtualCamera;
    MockCameraManager* mMockCameraManager;
    MockNdkCamera mMockNdkCamera;
    std::shared_ptr<CompatHalCamera> mMockHalCamera;
    std::vector<std::shared_ptr<CompatHalCamera>> mHalCameras;
    camera_metadata_t* mRawMetadata = nullptr;
    std::vector<std::shared_ptr<CompatHalCamera>> mTestHalCameras;
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

TEST_F(CompatVirtualCameraTest, getCameraInfo_PhysicalCamera) {
    // Create a new virtual camera with a single HalCamera to simulate a physical camera
    ACameraDevice* dummyDevice = reinterpret_cast<ACameraDevice*>(0xABCDEF12);
    aidlevs::Stream streamConfig;
    aidlevs::CameraDesc expectedDesc;
    expectedDesc.id = "mockCam_physical";
    std::shared_ptr<CompatHalCamera> halCamera =
            ::ndk::SharedRefBase::make<CompatHalCamera>(dummyDevice, "mockCam_physical",
                                                        &expectedDesc, streamConfig);
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras = {halCamera};
    auto virtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);

    aidlevs::CameraDesc actualDesc;
    ndk::ScopedAStatus status = virtualCamera->getCameraInfo(&actualDesc);

    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(actualDesc.id, expectedDesc.id);
}

TEST_F(CompatVirtualCameraTest, getCameraInfo_LogicalCamera) {
    // Create a new virtual camera with two HalCameras to simulate a logical camera
    ACameraDevice* dummyDevice2 = reinterpret_cast<ACameraDevice*>(0x87654321);
    aidlevs::Stream streamConfig;
    std::shared_ptr<CompatHalCamera> mockHalCamera2 =
            ::ndk::SharedRefBase::make<CompatHalCamera>(dummyDevice2, "mockCam1", nullptr,
                                                        streamConfig);
    std::vector<std::shared_ptr<CompatHalCamera>> logicalHalCameras = {mHalCameras[0],
                                                                       mockHalCamera2};
    auto logicalVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(logicalHalCameras);

    // For a logical camera, getCameraInfo should return the descriptor
    // that was set via setDescriptor.
    aidlevs::CameraDesc logicalDesc;
    logicalDesc.id = "logical_cam";
    logicalVirtualCamera->setDescriptor(&logicalDesc);

    aidlevs::CameraDesc actualDesc;
    ndk::ScopedAStatus status = logicalVirtualCamera->getCameraInfo(&actualDesc);

    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(actualDesc.id, logicalDesc.id);
}

TEST_F(CompatVirtualCameraTest, getCameraInfo_NoHalCamera) {
    // Create a virtual camera with no underlying HAL cameras
    std::vector<std::shared_ptr<CompatHalCamera>> emptyList;
    auto virtualCamWithNoHal = ::ndk::SharedRefBase::make<CompatVirtualCamera>(emptyList);

    aidlevs::CameraDesc desc;
    ndk::ScopedAStatus status = virtualCamWithNoHal->getCameraInfo(&desc);

    // Expect an error because there is no camera to get info from
    EXPECT_EQ(status.getServiceSpecificError(),
              static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
}

TEST_F(CompatVirtualCameraTest, getCameraInfo_ExpiredHalCamera) {
    // Create a HalCamera that will go out of scope
    std::shared_ptr<CompatVirtualCamera> virtualCamera;
    {
        ACameraDevice* dummyDevice = reinterpret_cast<ACameraDevice*>(0xDEADBEEF);
        aidlevs::Stream streamConfig;
        auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(dummyDevice, "expiredCam",
                                                                     nullptr, streamConfig);
        std::vector<std::shared_ptr<CompatHalCamera>> halCameras = {halCamera};
        virtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);
    }  // halCamera is destroyed here, weak_ptr in virtualCamera should be expired

    aidlevs::CameraDesc desc;
    ndk::ScopedAStatus status = virtualCamera->getCameraInfo(&desc);
    EXPECT_EQ(status.getServiceSpecificError(),
              static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
}

TEST_F(CompatVirtualCameraTest, getCameraInfo_UninitializedCameraDesc) {
    // The HalCamera created in SetUp has a null CameraDesc, which results in an empty id.
    // This test verifies that getCameraInfo handles this case correctly.
    aidlevs::CameraDesc desc;
    ndk::ScopedAStatus status = mVirtualCamera->getCameraInfo(&desc);
    EXPECT_EQ(status.getServiceSpecificError(),
              static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
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

TEST_F(CompatVirtualCameraTest, getParameterList_logicalCamera) {
    // A logical camera has more than one HAL camera.
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mMockHalCamera);
    // Add a second, distinct camera to make it logical
    auto anotherMockHalCamera =
            ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "mockCam1", nullptr,
                                                        aidlevs::Stream());
    halCameras.push_back(anotherMockHalCamera);
    auto virtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = virtualCamera->getParameterList(&params);

    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getParameterList_halCameraNotAvailable) {
    std::shared_ptr<CompatVirtualCamera> virtualCamera;
    {
        // Create a HalCamera that will go out of scope, leaving an expired weak_ptr in the virtual
        // camera.
        auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "test", nullptr,
                                                                     aidlevs::Stream());
        std::vector<std::shared_ptr<CompatHalCamera>> halCameras = {halCamera};
        virtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);
    }

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = virtualCamera->getParameterList(&params);

    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(),
              static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
}

TEST_F(CompatVirtualCameraTest, getParameterList_getCameraInfoFails) {
    // The mVirtualCamera from SetUp is configured with a HalCamera that has a null
    // CameraDesc, which will cause getCameraInfo() to fail.
    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(),
              static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
}

TEST_F(CompatVirtualCameraTest, getParameterList_emptyMetadata) {
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    // desc.metadata is empty by default
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras = {halCamera};
    auto virtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = virtualCamera->getParameterList(&params);

    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsBrightness_validRange) {
    std::vector<int32_t> range = {-5, 5};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_COMPENSATION_RANGE, range);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::BRIGHTNESS);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportBrightness_noTag) {
    // Setup with a different, unrelated tag.
    std::vector<uint8_t> modes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    setupCameraWithMetadata(ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, modes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportBrightness_invalidCount) {
    // Provide only one value when two are expected.
    std::vector<int32_t> range = {5};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_COMPENSATION_RANGE, range);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportBrightness_zeroRange) {
    // A range of [0, 0] means the parameter is not adjustable.
    std::vector<int32_t> range = {0, 0};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_COMPENSATION_RANGE, range);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsGainAndAutoGain_validRange) {
    std::vector<int32_t> range = {100, 1600};
    setupCameraWithMetadata(ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, range);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 2);
    EXPECT_EQ(params[0], aidlevs::CameraParam::GAIN);
    EXPECT_EQ(params[1], aidlevs::CameraParam::AUTOGAIN);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportGainAndAutoGain_noTag) {
    // Setup with a different, unrelated tag.
    std::vector<uint8_t> faceDetectModes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    setupCameraWithMetadata(ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, faceDetectModes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportGainAndAutoGain_invalidCount) {
    // Provide only one value when two are expected.
    std::vector<int32_t> range = {100};
    setupCameraWithMetadata(ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, range);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportGainAndAutoGain_invalidRange) {
    // The range is invalid because min is not less than max.
    std::vector<int32_t> range = {1600, 100};
    setupCameraWithMetadata(ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, range);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAutoWhiteBalanceAndTemperature) {
    // Metadata setup for both parameters
    mRawMetadata = allocate_camera_metadata(2, sizeof(uint8_t) * 2 + sizeof(int32_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_AUTO, ACAMERA_CONTROL_AWB_MODE_OFF};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AWB_AVAILABLE_MODES,
                                        awbModes.data(), awbModes.size()), 0);
    std::vector<int32_t> tempRange = {2000, 8000};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata,
                                        ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE,
                                        tempRange.data(), tempRange.size()), 0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 2);
    EXPECT_EQ(params[0], aidlevs::CameraParam::AUTO_WHITE_BALANCE);
    EXPECT_EQ(params[1], aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsOnlyAutoWhiteBalance) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AWB_MODE_AUTO};
    setupCameraWithMetadata(ACAMERA_CONTROL_AWB_AVAILABLE_MODES, modes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::AUTO_WHITE_BALANCE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportWhiteBalance_noAwbTag) {
    // Setup with a different, unrelated tag.
    std::vector<int32_t> tempRange = {2000, 8000};
    setupCameraWithMetadata(ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE, tempRange);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportTemperature_noTemperatureTag) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    setupCameraWithMetadata(ACAMERA_CONTROL_AWB_AVAILABLE_MODES, modes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportTemperature_invalidRange) {
    // Metadata setup
    mRawMetadata = allocate_camera_metadata(2, sizeof(uint8_t) * 1 + sizeof(int32_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AWB_AVAILABLE_MODES,
                                        awbModes.data(), awbModes.size()), 0);
    // Invalid range
    std::vector<int32_t> tempRange = {8000, 2000};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata,
                                        ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE,
                                        tempRange.data(), tempRange.size()), 0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsOnlyWhiteBalanceTemperature) {
    // Metadata setup for only manual white balance
    mRawMetadata = allocate_camera_metadata(2, sizeof(uint8_t) * 1 + sizeof(int32_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AWB_AVAILABLE_MODES,
                                        awbModes.data(), awbModes.size()), 0);
    std::vector<int32_t> tempRange = {2000, 8000};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata,
                                        ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE,
                                        tempRange.data(), tempRange.size()), 0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsSharpness_modeAvailable) {
    std::vector<uint8_t> modes = {ACAMERA_EDGE_MODE_OFF, ACAMERA_EDGE_MODE_FAST};
    setupCameraWithMetadata(ACAMERA_EDGE_AVAILABLE_EDGE_MODES, modes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::SHARPNESS);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportSharpness_noTag) {
    // Setup with a different, unrelated tag.
    std::vector<uint8_t> faceDetectModes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    setupCameraWithMetadata(ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, faceDetectModes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportSharpness_onlyOffMode) {
    // Only the OFF mode is available.
    std::vector<uint8_t> modes = {ACAMERA_EDGE_MODE_OFF};
    setupCameraWithMetadata(ACAMERA_EDGE_AVAILABLE_EDGE_MODES, modes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAutoAndAbsoluteExposure) {
    // Metadata setup for both parameters
    mRawMetadata = allocate_camera_metadata(2, sizeof(uint8_t) * 2 + sizeof(int64_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_ON, ACAMERA_CONTROL_AE_MODE_OFF};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AE_AVAILABLE_MODES,
                                        aeModes.data(), aeModes.size()), 0);
    std::vector<int64_t> exposureRange = {1000, 100000000};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE,
                                        exposureRange.data(), exposureRange.size()), 0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 2);
    EXPECT_EQ(params[0], aidlevs::CameraParam::AUTO_EXPOSURE);
    EXPECT_EQ(params[1], aidlevs::CameraParam::ABSOLUTE_EXPOSURE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsOnlyAutoExposure) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AE_MODE_ON};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_AVAILABLE_MODES, modes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::AUTO_EXPOSURE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportExposure_noAeTag) {
    // Setup with a different, unrelated tag.
    std::vector<int64_t> exposureRange = {1000, 100000000};
    setupCameraWithMetadata(ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE, exposureRange);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteExposure_noExposureTag) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AE_MODE_OFF};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_AVAILABLE_MODES, modes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteExposure_invalidRange) {
    // Metadata setup
    mRawMetadata = allocate_camera_metadata(2, sizeof(uint8_t) * 1 + sizeof(int64_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AE_AVAILABLE_MODES,
                                        aeModes.data(), aeModes.size()), 0);
    // Invalid range
    std::vector<int64_t> exposureRange = {100000000, 1000};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE,
                                        exposureRange.data(), exposureRange.size()), 0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsOnlyAbsoluteExposure) {
    // Metadata setup for only manual exposure
    mRawMetadata = allocate_camera_metadata(2, sizeof(uint8_t) * 1 + sizeof(int64_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AE_AVAILABLE_MODES,
                                        aeModes.data(), aeModes.size()), 0);
    std::vector<int64_t> exposureRange = {1000, 100000000};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE,
                                        exposureRange.data(), exposureRange.size()), 0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::ABSOLUTE_EXPOSURE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAutoFocus_modeAvailable) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AF_MODE_OFF, ACAMERA_CONTROL_AF_MODE_AUTO};
    setupCameraWithMetadata(ACAMERA_CONTROL_AF_AVAILABLE_MODES, modes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::AUTO_FOCUS);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAutoFocus_noTag) {
    // Setup with a different, unrelated tag.
    std::vector<uint8_t> faceDetectModes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    setupCameraWithMetadata(ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, faceDetectModes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAutoFocus_onlyOffMode) {
    // Only the OFF mode is available.
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AF_MODE_OFF};
    setupCameraWithMetadata(ACAMERA_CONTROL_AF_AVAILABLE_MODES, modes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAbsoluteFocus_validDistance) {
    std::vector<float> dist = {10.0f};
    setupCameraWithMetadata(ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE, dist);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::ABSOLUTE_FOCUS);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteFocus_noTag) {
    // Setup with a different, unrelated tag.
    std::vector<uint8_t> faceDetectModes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    setupCameraWithMetadata(ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, faceDetectModes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteFocus_invalidCount) {
    // Provide no values when one is expected.
    std::vector<float> dist = {};
    setupCameraWithMetadata(ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE, dist);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteFocus_zeroDistance) {
    // A minimum focus distance of 0 means the parameter is not supported.
    std::vector<float> dist = {0.0f};
    setupCameraWithMetadata(ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE, dist);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAbsoluteZoom_zoomIn) {
    std::vector<float> range = {1.0f, 100.0f};
    setupCameraWithMetadata(ACAMERA_CONTROL_ZOOM_RATIO_RANGE, range);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::ABSOLUTE_ZOOM);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAbsoluteZoom_zoomOut) {
    std::vector<float> range = {0.5f, 1.0f};
    setupCameraWithMetadata(ACAMERA_CONTROL_ZOOM_RATIO_RANGE, range);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::ABSOLUTE_ZOOM);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAbsoluteZoom_zoomInAndOut) {
    std::vector<float> range = {0.5f, 100.0f};
    setupCameraWithMetadata(ACAMERA_CONTROL_ZOOM_RATIO_RANGE, range);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::ABSOLUTE_ZOOM);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteZoom_noTag) {
    // Setup with a different, unrelated tag.
    std::vector<uint8_t> faceDetectModes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    setupCameraWithMetadata(ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, faceDetectModes);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteZoom_invalidCount) {
    // Provide only one value when two are expected.
    std::vector<float> range = {1.0f};
    setupCameraWithMetadata(ACAMERA_CONTROL_ZOOM_RATIO_RANGE, range);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteZoom_fixedZoom) {
    // A zoom range of [1.0, 1.0] means no zoom is supported.
    std::vector<float> range = {1.0f, 1.0f};
    setupCameraWithMetadata(ACAMERA_CONTROL_ZOOM_RATIO_RANGE, range);

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_unsupportedParam) {
    // Setup with metadata that does not support BRIGHTNESS
    std::vector<uint8_t> modes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    setupCameraWithMetadata(ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, modes);

    aidlevs::CameraParam param = aidlevs::CameraParam::BRIGHTNESS;
    aidlevs::ParameterRange range;
    ndk::ScopedAStatus status = mVirtualCamera->getIntParameterRange(param, &range);
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_brightness) {
    std::vector<int32_t> range = {-5, 5};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_COMPENSATION_RANGE, range);
    aidlevs::ParameterRange outRange;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameterRange(aidlevs::CameraParam::BRIGHTNESS, &outRange);
    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(outRange.min, -5);
    EXPECT_EQ(outRange.max, 5);
    EXPECT_EQ(outRange.step, 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_gain) {
    std::vector<int32_t> range = {100, 1600};
    setupCameraWithMetadata(ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, range);
    aidlevs::ParameterRange outRange;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameterRange(aidlevs::CameraParam::GAIN, &outRange);
    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(outRange.min, 100);
    EXPECT_EQ(outRange.max, 1600);
    EXPECT_EQ(outRange.step, 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_whiteBalanceTemperature) {
    // Metadata setup for only manual white balance
    mRawMetadata = allocate_camera_metadata(2, sizeof(uint8_t) * 1 + sizeof(int32_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AWB_AVAILABLE_MODES,
                                        awbModes.data(), awbModes.size()), 0);
    std::vector<int32_t> tempRange = {2000, 8000};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata,
                                        ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE,
                                        tempRange.data(), tempRange.size()), 0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    aidlevs::ParameterRange outRange;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameterRange(aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE,
                                                 &outRange);
    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(outRange.min, 2000);
    EXPECT_EQ(outRange.max, 8000);
    EXPECT_EQ(outRange.step, 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_sharpness) {
    std::vector<uint8_t> modes = {ACAMERA_EDGE_MODE_OFF, ACAMERA_EDGE_MODE_FAST};
    setupCameraWithMetadata(ACAMERA_EDGE_AVAILABLE_EDGE_MODES, modes);
    aidlevs::ParameterRange outRange;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameterRange(aidlevs::CameraParam::SHARPNESS, &outRange);
    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(outRange.min, ACAMERA_EDGE_MODE_OFF);
    EXPECT_EQ(outRange.max, ACAMERA_EDGE_MODE_ZERO_SHUTTER_LAG);
    EXPECT_EQ(outRange.step, 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_absoluteExposure) {
    // Metadata setup for only manual exposure
    mRawMetadata = allocate_camera_metadata(2, sizeof(uint8_t) * 1 + sizeof(int64_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AE_AVAILABLE_MODES,
                                        aeModes.data(), aeModes.size()), 0);
    std::vector<int64_t> exposureRange = {1000, 100000000};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE,
                                        exposureRange.data(), exposureRange.size()), 0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    aidlevs::ParameterRange outRange;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameterRange(aidlevs::CameraParam::ABSOLUTE_EXPOSURE,
                                                 &outRange);
    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(outRange.min, 1);
    EXPECT_EQ(outRange.max, 100000);
    EXPECT_EQ(outRange.step, 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_absoluteFocus) {
    std::vector<float> dist = {10.0f};
    setupCameraWithMetadata(ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE, dist);
    aidlevs::ParameterRange outRange;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameterRange(aidlevs::CameraParam::ABSOLUTE_FOCUS, &outRange);
    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(outRange.min, 0);
    EXPECT_EQ(outRange.max, 1000);
    EXPECT_EQ(outRange.step, 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_absoluteZoom) {
    std::vector<float> range = {1.0f, 100.0f};
    setupCameraWithMetadata(ACAMERA_CONTROL_ZOOM_RATIO_RANGE, range);
    aidlevs::ParameterRange outRange;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameterRange(aidlevs::CameraParam::ABSOLUTE_ZOOM, &outRange);
    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(outRange.min, 100);
    EXPECT_EQ(outRange.max, 10000);
    EXPECT_EQ(outRange.step, 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_autoGain) {
    std::vector<int32_t> range = {100, 1600};
    setupCameraWithMetadata(ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, range);
    aidlevs::ParameterRange outRange;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameterRange(aidlevs::CameraParam::AUTOGAIN, &outRange);
    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(outRange.min, 0);
    EXPECT_EQ(outRange.max, 1);
    EXPECT_EQ(outRange.step, 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_autoExposure) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AE_MODE_ON};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_AVAILABLE_MODES, modes);
    aidlevs::ParameterRange outRange;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameterRange(aidlevs::CameraParam::AUTO_EXPOSURE, &outRange);
    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(outRange.min, 0);
    EXPECT_EQ(outRange.max, 1);
    EXPECT_EQ(outRange.step, 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_autoWhiteBalance) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AWB_MODE_AUTO};
    setupCameraWithMetadata(ACAMERA_CONTROL_AWB_AVAILABLE_MODES, modes);
    aidlevs::ParameterRange outRange;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameterRange(aidlevs::CameraParam::AUTO_WHITE_BALANCE,
                                                 &outRange);
    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(outRange.min, 0);
    EXPECT_EQ(outRange.max, 1);
    EXPECT_EQ(outRange.step, 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_autoFocus) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AF_MODE_OFF, ACAMERA_CONTROL_AF_MODE_AUTO};
    setupCameraWithMetadata(ACAMERA_CONTROL_AF_AVAILABLE_MODES, modes);
    aidlevs::ParameterRange outRange;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameterRange(aidlevs::CameraParam::AUTO_FOCUS, &outRange);
    ASSERT_TRUE(status.isOk());
    EXPECT_EQ(outRange.min, 0);
    EXPECT_EQ(outRange.max, 1);
    EXPECT_EQ(outRange.step, 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_failsForLogicalCamera) {
    // A logical camera has more than one HAL camera, which does not support
    // parameter programming.
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mMockHalCamera);
    auto anotherMockHalCamera =
            ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "mockCam1", nullptr,
                                                        aidlevs::Stream());
    halCameras.push_back(anotherMockHalCamera);
    auto virtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);

    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            virtualCamera->getIntParameter(aidlevs::CameraParam::BRIGHTNESS, &values);

    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_failsWhenHalCameraNotAvailable) {
    std::shared_ptr<CompatVirtualCamera> virtualCamera;
    {
        // Create a HalCamera that will go out of scope, leaving an expired weak_ptr
        auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "test", nullptr,
                                                                     aidlevs::Stream());
        std::vector<std::shared_ptr<CompatHalCamera>> halCameras = {halCamera};
        virtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);
    }

    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            virtualCamera->getIntParameter(aidlevs::CameraParam::BRIGHTNESS, &values);

    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(),
              static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_failsWhenGetCameraInfoFails) {
    // The mVirtualCamera from SetUp is configured with a HalCamera that has a null
    // CameraDesc, which will cause getCameraInfo() to fail inside
    // populateSupportedParametersLocked().
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::BRIGHTNESS, &values);

    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(),
              static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_failsWhenMetadataIsEmpty) {
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    // desc.metadata is empty by default
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras = {halCamera};
    auto virtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);

    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            virtualCamera->getIntParameter(aidlevs::CameraParam::BRIGHTNESS, &values);

    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_unsupportedParam) {
    // Setup with metadata that supports BRIGHTNESS
    std::vector<int32_t> range = {-5, 5};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_COMPENSATION_RANGE, range);

    // Request a different parameter (CONTRAST)
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::CONTRAST, &values);
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_failsWhenGetLatestMetadataFails) {
    // 1. Setup with metadata that supports BRIGHTNESS, so populate succeeds.
    std::vector<int32_t> range = {-5, 5};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_COMPENSATION_RANGE, range);

    // 2. The underlying HalCamera's mLatestMetadata is nullptr by default, so
    //    getLatestMetadata() will return nullptr. We'll mock the NDK free
    //    function to ensure it's not called on a nullptr.
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(_)).Times(0);

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::BRIGHTNESS, &values);

    // 4. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(),
              static_cast<int>(EvsResult::UNDERLYING_SERVICE_ERROR));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_brightness_aeOn) {
    // 1. Setup with metadata that supports BRIGHTNESS
    std::vector<int32_t> range = {-5, 5};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_COMPENSATION_RANGE, range);

    // 2. Create a separate metadata object for the latest capture result
    camera_metadata_t* latestMetadata =
            allocate_camera_metadata(2, sizeof(int32_t) + sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    int32_t exposureValue = 3;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AE_EXPOSURE_COMPENSATION,
                                        &exposureValue, 1),
              0);
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AE_MODE, &aeMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata to return the latest metadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry aeEntry{};
    aeEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeEntry.type = ACAMERA_TYPE_BYTE;
    aeEntry.count = 1;
    aeEntry.data.u8 = &aeMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AE_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(aeEntry), Return(ACAMERA_OK)));

    ACameraMetadata_const_entry exposureEntry{};
    exposureEntry.tag = ACAMERA_CONTROL_AE_EXPOSURE_COMPENSATION;
    exposureEntry.type = ACAMERA_TYPE_INT32;
    exposureEntry.count = 1;
    exposureEntry.data.i32 = &exposureValue;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet,
                                              ACAMERA_CONTROL_AE_EXPOSURE_COMPENSATION, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(exposureEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::BRIGHTNESS, &values);

    // 5. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], exposureValue);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_brightness_aeOff) {
    // 1. Setup with metadata that supports BRIGHTNESS
    std::vector<int32_t> range = {-5, 5};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_COMPENSATION_RANGE, range);

    // 2. Create a separate metadata object for the latest capture result
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_OFF;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AE_MODE, &aeMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata to return the latest metadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry aeEntry{};
    aeEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeEntry.type = ACAMERA_TYPE_BYTE;
    aeEntry.count = 1;
    aeEntry.data.u8 = &aeMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AE_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(aeEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::BRIGHTNESS, &values);

    // 5. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_gain_manualControl) {
    // 1. Setup with metadata that supports GAIN
    std::vector<int32_t> range = {100, 1600};
    setupCameraWithMetadata(ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, range);

    // 2. Create a separate metadata object for the latest capture result
    camera_metadata_t* latestMetadata =
            allocate_camera_metadata(3, sizeof(int32_t) + sizeof(uint8_t) * 2);
    ASSERT_NE(latestMetadata, nullptr);
    int32_t sensitivityValue = 800;  // Example sensitivity value
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_SENSOR_SENSITIVITY,
                                        &sensitivityValue, 1),
              0);
    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_MODE, &controlMode, 1), 0);
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;  // AE mode doesn't matter if control mode is OFF
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AE_MODE, &aeMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata to return the latest metadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry controlEntry{};
    controlEntry.tag = ACAMERA_CONTROL_MODE;
    controlEntry.type = ACAMERA_TYPE_BYTE;
    controlEntry.count = 1;
    controlEntry.data.u8 = &controlMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(controlEntry), Return(ACAMERA_OK)));

    ACameraMetadata_const_entry aeEntry{};
    aeEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeEntry.type = ACAMERA_TYPE_BYTE;
    aeEntry.count = 1;
    aeEntry.data.u8 = &aeMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AE_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(aeEntry), Return(ACAMERA_OK)));

    ACameraMetadata_const_entry sensitivityEntry{};
    sensitivityEntry.tag = ACAMERA_SENSOR_SENSITIVITY;
    sensitivityEntry.type = ACAMERA_TYPE_INT32;
    sensitivityEntry.count = 1;
    sensitivityEntry.data.i32 = &sensitivityValue;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_SENSOR_SENSITIVITY, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(sensitivityEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::GAIN, &values);

    // 5. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], sensitivityValue);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_gain_autoControl) {
    // 1. Setup with metadata that supports GAIN
    std::vector<int32_t> range = {100, 1600};
    setupCameraWithMetadata(ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, range);

    // 2. Create a separate metadata object for the latest capture result
    camera_metadata_t* latestMetadata = allocate_camera_metadata(2, sizeof(uint8_t) * 2);
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_MODE, &controlMode, 1), 0);
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AE_MODE, &aeMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata to return the latest metadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry controlEntry{};
    controlEntry.tag = ACAMERA_CONTROL_MODE;
    controlEntry.type = ACAMERA_TYPE_BYTE;
    controlEntry.count = 1;
    controlEntry.data.u8 = &controlMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(controlEntry), Return(ACAMERA_OK)));

    ACameraMetadata_const_entry aeEntry{};
    aeEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeEntry.type = ACAMERA_TYPE_BYTE;
    aeEntry.count = 1;
    aeEntry.data.u8 = &aeMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AE_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(aeEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::GAIN, &values);

    // 5. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoGain_off) {
    // 1. Setup with metadata that supports AUTOGAIN
    std::vector<int32_t> range = {100, 1600};
    setupCameraWithMetadata(ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, range);

    // 2. Create metadata for the latest capture result with AE mode OFF
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_OFF;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AE_MODE, &aeMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry entry{};
    entry.tag = ACAMERA_CONTROL_AE_MODE;
    entry.type = ACAMERA_TYPE_BYTE;
    entry.count = 1;
    entry.data.u8 = &aeMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AE_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(entry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTOGAIN, &values);

    // 5. Verify success and that the value is 0 (OFF)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 0);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoGain_on) {
    // 1. Setup with metadata that supports AUTOGAIN
    std::vector<int32_t> range = {100, 1600};
    setupCameraWithMetadata(ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, range);

    // 2. Create metadata for the latest capture result with AE mode ON
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON_AUTO_FLASH;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AE_MODE, &aeMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry entry{};
    entry.tag = ACAMERA_CONTROL_AE_MODE;
    entry.type = ACAMERA_TYPE_BYTE;
    entry.count = 1;
    entry.data.u8 = &aeMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AE_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(entry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTOGAIN, &values);

    // 5. Verify success and that the value is 1 (ON)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 1);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoWhiteBalance_on) {
    // 1. Setup with metadata that supports AUTO_WHITE_BALANCE
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_AUTO};
    setupCameraWithMetadata(ACAMERA_CONTROL_AWB_AVAILABLE_MODES, awbModes);

    // 2. Create metadata for the latest capture result with AWB mode AUTO
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_AUTO;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AWB_MODE, &awbMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry entry{};
    entry.tag = ACAMERA_CONTROL_AWB_MODE;
    entry.type = ACAMERA_TYPE_BYTE;
    entry.count = 1;
    entry.data.u8 = &awbMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AWB_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(entry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_WHITE_BALANCE, &values);

    // 5. Verify success and that the value is 1 (ON)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 1);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoWhiteBalance_off) {
    // 1. Setup with metadata that supports AUTO_WHITE_BALANCE
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_AUTO, ACAMERA_CONTROL_AWB_MODE_OFF};
    setupCameraWithMetadata(ACAMERA_CONTROL_AWB_AVAILABLE_MODES, awbModes);

    // 2. Create metadata for the latest capture result with AWB mode OFF
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_OFF;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AWB_MODE, &awbMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry entry{};
    entry.tag = ACAMERA_CONTROL_AWB_MODE;
    entry.type = ACAMERA_TYPE_BYTE;
    entry.count = 1;
    entry.data.u8 = &awbMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AWB_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(entry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_WHITE_BALANCE, &values);

    // 5. Verify success and that the value is 0 (OFF)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 0);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_whiteBalanceTemperature_awbOff) {
    // 1. Setup with metadata that supports WHITE_BALANCE_TEMPERATURE
    mRawMetadata = allocate_camera_metadata(2, sizeof(uint8_t) + sizeof(int32_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_OFF;
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AWB_AVAILABLE_MODES, &awbMode,
                                        1),
              0);
    std::vector<int32_t> tempRange = {2000, 8000};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata,
                                        ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE,
                                        tempRange.data(), tempRange.size()),
              0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    // 2. Create metadata for the latest capture result
    camera_metadata_t* latestMetadata =
            allocate_camera_metadata(2, sizeof(int32_t) + sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    int32_t tempValue = 5500;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE,
                                        &tempValue, 1),
              0);
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AWB_MODE, &awbMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry awbEntry{};
    awbEntry.tag = ACAMERA_CONTROL_AWB_MODE;
    awbEntry.type = ACAMERA_TYPE_BYTE;
    awbEntry.count = 1;
    awbEntry.data.u8 = &awbMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AWB_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(awbEntry), Return(ACAMERA_OK)));

    ACameraMetadata_const_entry tempEntry{};
    tempEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE;
    tempEntry.type = ACAMERA_TYPE_INT32;
    tempEntry.count = 1;
    tempEntry.data.i32 = &tempValue;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet,
                                              ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(tempEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE,
                                            &values);

    // 5. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], tempValue);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_whiteBalanceTemperature_awbOn) {
    // 1. Setup with metadata that supports WHITE_BALANCE_TEMPERATURE
    mRawMetadata = allocate_camera_metadata(2, sizeof(uint8_t) * 2 + sizeof(int32_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_AUTO;
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF, ACAMERA_CONTROL_AWB_MODE_AUTO};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AWB_AVAILABLE_MODES,
                                        awbModes.data(), awbModes.size()),
              0);
    std::vector<int32_t> tempRange = {2000, 8000};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata,
                                        ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE,
                                        tempRange.data(), tempRange.size()),
              0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    // 2. Create metadata for the latest capture result
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AWB_MODE, &awbMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry awbEntry{};
    awbEntry.tag = ACAMERA_CONTROL_AWB_MODE;
    awbEntry.type = ACAMERA_TYPE_BYTE;
    awbEntry.count = 1;
    awbEntry.data.u8 = &awbMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AWB_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(awbEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE,
                                            &values);

    // 5. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_sharpness) {
    // 1. Setup with metadata that supports SHARPNESS
    std::vector<uint8_t> edgeModes = {ACAMERA_EDGE_MODE_FAST};
    setupCameraWithMetadata(ACAMERA_EDGE_AVAILABLE_EDGE_MODES, edgeModes);

    // 2. Create metadata for the latest capture result
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t edgeMode = ACAMERA_EDGE_MODE_HIGH_QUALITY;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_EDGE_MODE, &edgeMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry entry{};
    entry.tag = ACAMERA_EDGE_MODE;
    entry.type = ACAMERA_TYPE_BYTE;
    entry.count = 1;
    entry.data.u8 = &edgeMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_EDGE_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(entry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::SHARPNESS, &values);

    // 5. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], edgeMode);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoExposure_off) {
    // 1. Setup with metadata that supports AUTO_EXPOSURE
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_AVAILABLE_MODES, aeModes);

    // 2. Create metadata for the latest capture result with AE mode OFF
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_OFF;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AE_MODE, &aeMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry entry{};
    entry.tag = ACAMERA_CONTROL_AE_MODE;
    entry.type = ACAMERA_TYPE_BYTE;
    entry.count = 1;
    entry.data.u8 = &aeMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AE_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(entry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_EXPOSURE, &values);

    // 5. Verify success and that the value is 0 (OFF)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 0);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoExposure_on) {
    // 1. Setup with metadata that supports AUTO_EXPOSURE
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    setupCameraWithMetadata(ACAMERA_CONTROL_AE_AVAILABLE_MODES, aeModes);

    // 2. Create metadata for the latest capture result with AE mode ON
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AE_MODE, &aeMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry entry{};
    entry.tag = ACAMERA_CONTROL_AE_MODE;
    entry.type = ACAMERA_TYPE_BYTE;
    entry.count = 1;
    entry.data.u8 = &aeMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AE_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(entry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_EXPOSURE, &values);

    // 5. Verify success and that the value is 1 (ON)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 1);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_absoluteExposure_manualControl) {
    // 1. Setup with metadata that supports ABSOLUTE_EXPOSURE
    mRawMetadata = allocate_camera_metadata(3, sizeof(uint8_t) * 2 + sizeof(int64_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_OFF;
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AE_AVAILABLE_MODES, &aeMode,
                                        1),
              0);
    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_MODE, &controlMode, 1), 0);
    std::vector<int64_t> expRange = {1000L, 100000000L};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE,
                                        expRange.data(), expRange.size()),
              0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    // 2. Create metadata for the latest capture result
    camera_metadata_t* latestMetadata =
            allocate_camera_metadata(3, sizeof(int64_t) + sizeof(uint8_t) * 2);
    ASSERT_NE(latestMetadata, nullptr);
    int64_t expValue = 50000000L;  // 50ms
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_SENSOR_EXPOSURE_TIME, &expValue, 1),
              0);
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_MODE, &controlMode, 1), 0);
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AE_MODE, &aeMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry controlEntry{};
    controlEntry.tag = ACAMERA_CONTROL_MODE;
    controlEntry.type = ACAMERA_TYPE_BYTE;
    controlEntry.count = 1;
    controlEntry.data.u8 = &controlMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(controlEntry), Return(ACAMERA_OK)));

    ACameraMetadata_const_entry aeEntry{};
    aeEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeEntry.type = ACAMERA_TYPE_BYTE;
    aeEntry.count = 1;
    aeEntry.data.u8 = &aeMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AE_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(aeEntry), Return(ACAMERA_OK)));

    ACameraMetadata_const_entry expEntry{};
    expEntry.tag = ACAMERA_SENSOR_EXPOSURE_TIME;
    expEntry.type = ACAMERA_TYPE_INT64;
    expEntry.count = 1;
    expEntry.data.i64 = &expValue;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_SENSOR_EXPOSURE_TIME, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(expEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::ABSOLUTE_EXPOSURE, &values);

    // 5. Verify success and the returned value (converted to microseconds)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], static_cast<int32_t>(expValue / 1000));

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_absoluteExposure_autoControl) {
    // 1. Setup with metadata that supports ABSOLUTE_EXPOSURE
    mRawMetadata = allocate_camera_metadata(3, sizeof(uint8_t) * 3 + sizeof(int64_t) * 2);
    ASSERT_NE(mRawMetadata, nullptr);
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_AE_AVAILABLE_MODES,
                                        aeModes.data(), aeModes.size()),
              0);
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_CONTROL_MODE, &controlMode, 1), 0);
    std::vector<int64_t> expRange = {1000L, 100000000L};
    ASSERT_EQ(add_camera_metadata_entry(mRawMetadata, ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE,
                                        expRange.data(), expRange.size()),
              0);
    ASSERT_EQ(validate_camera_metadata_structure(mRawMetadata, nullptr), 0);

    // Create camera with this metadata
    size_t size = get_camera_metadata_size(mRawMetadata);
    std::vector<uint8_t> metadataVector(size);
    memcpy(metadataVector.data(), mRawMetadata, size);
    aidlevs::CameraDesc desc;
    desc.id = "testCam";
    desc.metadata = metadataVector;
    auto halCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(nullptr, "testCam", &desc,
                                                                 aidlevs::Stream());
    mTestHalCameras.push_back(halCamera);
    mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);

    // 2. Create metadata for the latest capture result
    camera_metadata_t* latestMetadata = allocate_camera_metadata(2, sizeof(uint8_t) * 2);
    ASSERT_NE(latestMetadata, nullptr);
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_MODE, &controlMode, 1), 0);
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AE_MODE, &aeMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry controlEntry{};
    controlEntry.tag = ACAMERA_CONTROL_MODE;
    controlEntry.type = ACAMERA_TYPE_BYTE;
    controlEntry.count = 1;
    controlEntry.data.u8 = &controlMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(controlEntry), Return(ACAMERA_OK)));

    ACameraMetadata_const_entry aeEntry{};
    aeEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeEntry.type = ACAMERA_TYPE_BYTE;
    aeEntry.count = 1;
    aeEntry.data.u8 = &aeMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AE_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(aeEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::ABSOLUTE_EXPOSURE, &values);

    // 5. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoFocus_off) {
    // 1. Setup with metadata that supports AUTO_FOCUS
    std::vector<uint8_t> afModes = {ACAMERA_CONTROL_AF_MODE_OFF, ACAMERA_CONTROL_AF_MODE_AUTO};
    setupCameraWithMetadata(ACAMERA_CONTROL_AF_AVAILABLE_MODES, afModes);

    // 2. Create metadata for the latest capture result with AF mode OFF
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t afMode = ACAMERA_CONTROL_AF_MODE_OFF;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AF_MODE, &afMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry entry{};
    entry.tag = ACAMERA_CONTROL_AF_MODE;
    entry.type = ACAMERA_TYPE_BYTE;
    entry.count = 1;
    entry.data.u8 = &afMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AF_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(entry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_FOCUS, &values);

    // 5. Verify success and that the value is 0 (OFF)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 0);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoFocus_on) {
    // 1. Setup with metadata that supports AUTO_FOCUS
    std::vector<uint8_t> afModes = {ACAMERA_CONTROL_AF_MODE_OFF, ACAMERA_CONTROL_AF_MODE_AUTO};
    setupCameraWithMetadata(ACAMERA_CONTROL_AF_AVAILABLE_MODES, afModes);

    // 2. Create metadata for the latest capture result with AF mode ON
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t afMode = ACAMERA_CONTROL_AF_MODE_AUTO;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AF_MODE, &afMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry entry{};
    entry.tag = ACAMERA_CONTROL_AF_MODE;
    entry.type = ACAMERA_TYPE_BYTE;
    entry.count = 1;
    entry.data.u8 = &afMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AF_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(entry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_FOCUS, &values);

    // 5. Verify success and that the value is 1 (ON)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 1);

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_absoluteFocus_afOff) {
    // 1. Setup with metadata that supports ABSOLUTE_FOCUS
    std::vector<float> minFocus = {10.0f};
    setupCameraWithMetadata(ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE, minFocus);

    // 2. Create metadata for the latest capture result
    camera_metadata_t* latestMetadata =
            allocate_camera_metadata(2, sizeof(float) + sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    float focusValue = 5.0f;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_LENS_FOCUS_DISTANCE, &focusValue,
                                        1), 0);
    uint8_t afMode = ACAMERA_CONTROL_AF_MODE_OFF;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AF_MODE, &afMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry afEntry{};
    afEntry.tag = ACAMERA_CONTROL_AF_MODE;
    afEntry.type = ACAMERA_TYPE_BYTE;
    afEntry.count = 1;
    afEntry.data.u8 = &afMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AF_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(afEntry), Return(ACAMERA_OK)));

    ACameraMetadata_const_entry focusEntry{};
    focusEntry.tag = ACAMERA_LENS_FOCUS_DISTANCE;
    focusEntry.type = ACAMERA_TYPE_FLOAT;
    focusEntry.count = 1;
    focusEntry.data.f = &focusValue;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_LENS_FOCUS_DISTANCE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(focusEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::ABSOLUTE_FOCUS, &values);

    // 5. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], static_cast<int32_t>(focusValue * 100.0f));

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_absoluteFocus_afOn) {
    // 1. Setup with metadata that supports ABSOLUTE_FOCUS
    std::vector<float> minFocus = {10.0f};
    setupCameraWithMetadata(ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE, minFocus);

    // 2. Create metadata for the latest capture result
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(uint8_t));
    ASSERT_NE(latestMetadata, nullptr);
    uint8_t afMode = ACAMERA_CONTROL_AF_MODE_AUTO;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_AF_MODE, &afMode, 1), 0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry afEntry{};
    afEntry.tag = ACAMERA_CONTROL_AF_MODE;
    afEntry.type = ACAMERA_TYPE_BYTE;
    afEntry.count = 1;
    afEntry.data.u8 = &afMode;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_AF_MODE, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(afEntry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::ABSOLUTE_FOCUS, &values);

    // 5. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_absoluteZoom) {
    // 1. Setup with metadata that supports ABSOLUTE_ZOOM
    std::vector<float> zoomRange = {0.5f, 100.0f};
    setupCameraWithMetadata(ACAMERA_CONTROL_ZOOM_RATIO_RANGE, zoomRange);

    // 2. Create metadata for the latest capture result
    camera_metadata_t* latestMetadata = allocate_camera_metadata(1, sizeof(float));
    ASSERT_NE(latestMetadata, nullptr);
    float zoomValue = 2.0f;
    ASSERT_EQ(add_camera_metadata_entry(latestMetadata, ACAMERA_CONTROL_ZOOM_RATIO, &zoomValue, 1),
              0);
    auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(latestMetadata);

    // 3. Mock getLatestMetadata
    auto mockHalCamera = mTestHalCameras[0];
    auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(0x1113);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
            .WillOnce(Return(metadataCopy));
    mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
            .WillOnce(Return(metadataCopyForGet));

    ACameraMetadata_const_entry entry{};
    entry.tag = ACAMERA_CONTROL_ZOOM_RATIO;
    entry.type = ACAMERA_TYPE_FLOAT;
    entry.count = 1;
    entry.data.f = &zoomValue;
    EXPECT_CALL(mMockNdkCamera,
                ACameraMetadata_getConstEntry(metadataCopyForGet, ACAMERA_CONTROL_ZOOM_RATIO, _))
            .WillOnce(::testing::DoAll(SetArgPointee<2>(entry), Return(ACAMERA_OK)));
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);

    // 4. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::ABSOLUTE_ZOOM, &values);

    // 5. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], static_cast<int32_t>(zoomValue * 100.0f));

    // 6. Clean up
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    free_camera_metadata(latestMetadata);
}

}  // namespace android::hardware::automotive::evs::compat
