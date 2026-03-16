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
        auto mockCameraManager = std::make_unique<MockCameraManager>();
        mMockCameraManager = mockCameraManager.get();

        // Create a mock CompatHalCamera
        EXPECT_CALL(*mMockCameraManager, openSharedCamera(_, _))
                .WillOnce(testing::DoAll(SetArgPointee<1>(dummyDevice), Return(ACAMERA_OK)));

        ACameraDevice* device = nullptr;
        mMockCameraManager->openSharedCamera("mockCam0", &device);

        aidlevs::Stream streamConfig;
        mMockHalCamera =
                ::ndk::SharedRefBase::make<CompatHalCamera>(device, "mockCam0", nullptr,
                                                            streamConfig, true, mMockCameraManager);
        mHalCameras.push_back(mMockHalCamera);

        mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mHalCameras);
        MockNdkCamera::setMockInstance(&mMockNdkCamera);
    }

    void TearDown() override {
        if (mStaticMetadata) {
            free_camera_metadata(mStaticMetadata);
            mStaticMetadata = nullptr;
        }
        if (mDynamicMetadata) {
            free_camera_metadata(mDynamicMetadata);
            mDynamicMetadata = nullptr;
        }
        mTestHalCameras.clear();
    }

    void setupMockCaptureResult(const std::vector<camera_metadata_entry_t>& resultEntries) {
        // 1. Calculate size and allocate memory for the metadata object
        size_t entry_count = resultEntries.size();
        size_t data_size = 0;
        for (const auto& entry : resultEntries) {
            int type = get_camera_metadata_tag_type(entry.tag);
            ASSERT_GE(type, 0);
            data_size += calculate_camera_metadata_entry_data_size(type, entry.count);
        }
        mDynamicMetadata = allocate_camera_metadata(entry_count, data_size);
        ASSERT_NE(mDynamicMetadata, nullptr);

        // 2. Populate the metadata object with the provided entries
        for (const auto& entry : resultEntries) {
            add_camera_metadata_entry(mDynamicMetadata, entry.tag, entry.data.u8, entry.count);
        }

        // 3. Set up the sequence of mock calls for the capture result
        auto* ndkLatestMetadata = reinterpret_cast<ACameraMetadata*>(mDynamicMetadata);
        auto mockHalCamera = mTestHalCameras[0];

        static intptr_t mockAddressCounter = 0x4000;
        auto* metadataCopy = reinterpret_cast<ACameraMetadata*>(mockAddressCounter++);
        auto* metadataCopyForGet = reinterpret_cast<ACameraMetadata*>(mockAddressCounter++);

        EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(ndkLatestMetadata))
                .WillOnce(Return(metadataCopy));
        mockHalCamera->handleCaptureCompleted(ndkLatestMetadata);
        EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadataCopy))
                .WillOnce(Return(metadataCopyForGet));

        for (const auto& entry : resultEntries) {
            ACameraMetadata_const_entry mockEntry;
            mockEntry.tag = entry.tag;
            mockEntry.count = entry.count;
            mockEntry.data.u8 = entry.data.u8;
            mockEntry.type = get_camera_metadata_tag_type(entry.tag);

            EXPECT_CALL(mMockNdkCamera,
                        ACameraMetadata_getConstEntry(metadataCopyForGet, entry.tag, _))
                    .WillOnce(::testing::DoAll(SetArgPointee<2>(mockEntry), Return(ACAMERA_OK)));
        }

        EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopyForGet)).Times(1);
        EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadataCopy)).Times(1);
    }

    // Generic helper to set up the camera with multiple metadata entries
    void setupCameraWithMultipleMetadata(const std::vector<camera_metadata_entry_t>& entries,
                                         const std::string& cameraId = "testCam") {
        size_t entry_count = entries.size();
        size_t data_size = 0;
        for (const auto& entry : entries) {
            int type = get_camera_metadata_tag_type(entry.tag);
            ASSERT_GE(type, 0);
            data_size += calculate_camera_metadata_entry_data_size(type, entry.count);
        }

        mStaticMetadata = allocate_camera_metadata(entry_count, data_size);
        ASSERT_NE(mStaticMetadata, nullptr);

        for (const auto& entry : entries) {
            ASSERT_EQ(add_camera_metadata_entry(mStaticMetadata, entry.tag, entry.data.u8,
                                                entry.count),
                      0);
        }
        ASSERT_EQ(validate_camera_metadata_structure(mStaticMetadata, nullptr), 0);

        // Serialize and create CameraDesc
        size_t size = get_camera_metadata_size(mStaticMetadata);
        std::vector<uint8_t> metadataVector(size);
        memcpy(metadataVector.data(), mStaticMetadata, size);

        aidlevs::CameraDesc desc;
        desc.id = cameraId;
        desc.metadata = metadataVector;

        // Create and store CompatHalCamera
        ACameraDevice* device = reinterpret_cast<ACameraDevice*>(0x1234);
        aidlevs::Stream streamConfig;
        auto halCamera =
                ::ndk::SharedRefBase::make<CompatHalCamera>(device, cameraId, &desc, streamConfig,
                                                            true, mMockCameraManager);
        mTestHalCameras.push_back(halCamera);

        // Create CompatVirtualCamera
        mVirtualCamera = ::ndk::SharedRefBase::make<CompatVirtualCamera>(mTestHalCameras);
    }

    std::shared_ptr<CompatVirtualCamera> mVirtualCamera;
    MockCameraManager* mMockCameraManager;
    MockNdkCamera mMockNdkCamera;
    std::shared_ptr<CompatHalCamera> mMockHalCamera;
    std::vector<std::shared_ptr<CompatHalCamera>> mHalCameras;
    camera_metadata_t* mStaticMetadata = nullptr;
    camera_metadata_t* mDynamicMetadata = nullptr;
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
                                                        streamConfig, true, mMockCameraManager);
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras = {halCameraWithDesc};
    std::shared_ptr<CompatVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<CompatVirtualCamera>(halCameras);

    aidlevs::CameraDesc desc;
    ndk::ScopedAStatus status = virtualCamera->getPhysicalCameraInfo("mockCam1", &desc);
    ASSERT_TRUE(status.isOk()) << "getPhysicalCameraInfo failed with status: "
                               << status.getDescription();
    EXPECT_EQ(desc.id, "mockCam1");
}

TEST_F(CompatVirtualCameraTest, Notify_Success) {
    auto mockStream = ::ndk::SharedRefBase::make<MockEvsCameraStream>();
    {
        std::lock_guard lock(mVirtualCamera->mMutex);
        mVirtualCamera->mStream = mockStream;
    }

    aidlevs::EvsEventDesc event;
    event.aType = EvsEventType::STREAM_STARTED;

    EXPECT_CALL(*mockStream, notify(event)).Times(1).WillOnce(Return(ndk::ScopedAStatus::ok()));

    mVirtualCamera->notify(event);
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
                                                        &expectedDesc, streamConfig, true,
                                                        mMockCameraManager);
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
                                                        streamConfig, true, mMockCameraManager);
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
        auto halCamera =
                ::ndk::SharedRefBase::make<CompatHalCamera>(dummyDevice, "expiredCam", nullptr,
                                                            streamConfig, true, mMockCameraManager);
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
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
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
                                                        aidlevs::Stream(), true,
                                                        mMockCameraManager);
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
                                                                     aidlevs::Stream(), true,
                                                                     mMockCameraManager);
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
                                                                 aidlevs::Stream(), true,
                                                                 mMockCameraManager);
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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_COMPENSATION_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::BRIGHTNESS);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportBrightness_noTag) {
    // Setup with a different, unrelated tag.
    std::vector<uint8_t> modes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportBrightness_invalidCount) {
    // Provide only one value when two are expected.
    std::vector<int32_t> range = {5};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_COMPENSATION_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportBrightness_zeroRange) {
    // A range of [0, 0] means the parameter is not adjustable.
    std::vector<int32_t> range = {0, 0};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_COMPENSATION_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsGainAndAutoGain_validRange) {
    std::vector<int32_t> range = {100, 1600};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES;
    entry.count = faceDetectModes.size();
    entry.data.u8 = faceDetectModes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportGainAndAutoGain_invalidCount) {
    // Provide only one value when two are expected.
    std::vector<int32_t> range = {100};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportGainAndAutoGain_invalidRange) {
    // The range is invalid because min is not less than max.
    std::vector<int32_t> range = {1600, 100};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAutoWhiteBalanceAndTemperature) {
    // Metadata setup for both parameters
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_AUTO, ACAMERA_CONTROL_AWB_MODE_OFF};
    camera_metadata_entry_t awbEntry;
    awbEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbEntry.count = awbModes.size();
    awbEntry.data.u8 = awbModes.data();

    std::vector<int32_t> tempRange = {2000, 8000};
    camera_metadata_entry_t tempEntry;
    tempEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE;
    tempEntry.count = tempRange.size();
    tempEntry.data.i32 = tempRange.data();

    setupCameraWithMultipleMetadata({awbEntry, tempEntry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 2);
    EXPECT_EQ(params[0], aidlevs::CameraParam::AUTO_WHITE_BALANCE);
    EXPECT_EQ(params[1], aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsOnlyAutoWhiteBalance) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AWB_MODE_AUTO};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::AUTO_WHITE_BALANCE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportWhiteBalance_noAwbTag) {
    // Setup with a different, unrelated tag.
    std::vector<int32_t> tempRange = {2000, 8000};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE;
    entry.count = tempRange.size();
    entry.data.i32 = tempRange.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportTemperature_noTemperatureTag) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportTemperature_invalidRange) {
    // Metadata setup
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    camera_metadata_entry_t awbEntry;
    awbEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbEntry.count = awbModes.size();
    awbEntry.data.u8 = awbModes.data();

    // Invalid range
    std::vector<int32_t> tempRange = {8000, 2000};
    camera_metadata_entry_t tempEntry;
    tempEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE;
    tempEntry.count = tempRange.size();
    tempEntry.data.i32 = tempRange.data();

    setupCameraWithMultipleMetadata({awbEntry, tempEntry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsOnlyWhiteBalanceTemperature) {
    // Metadata setup for only manual white balance
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    camera_metadata_entry_t awbEntry;
    awbEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbEntry.count = awbModes.size();
    awbEntry.data.u8 = awbModes.data();

    std::vector<int32_t> tempRange = {2000, 8000};
    camera_metadata_entry_t tempEntry;
    tempEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE;
    tempEntry.count = tempRange.size();
    tempEntry.data.i32 = tempRange.data();

    setupCameraWithMultipleMetadata({awbEntry, tempEntry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsSharpness_modeAvailable) {
    std::vector<uint8_t> modes = {ACAMERA_EDGE_MODE_OFF, ACAMERA_EDGE_MODE_FAST};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_EDGE_AVAILABLE_EDGE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::SHARPNESS);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportSharpness_noTag) {
    // Setup with a different, unrelated tag.
    std::vector<uint8_t> faceDetectModes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES;
    entry.count = faceDetectModes.size();
    entry.data.u8 = faceDetectModes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportSharpness_onlyOffMode) {
    // Only the OFF mode is available.
    std::vector<uint8_t> modes = {ACAMERA_EDGE_MODE_OFF};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_EDGE_AVAILABLE_EDGE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAutoAndAbsoluteExposure) {
    // Metadata setup for both parameters
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_ON, ACAMERA_CONTROL_AE_MODE_OFF};
    camera_metadata_entry_t aeEntry;
    aeEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeEntry.count = aeModes.size();
    aeEntry.data.u8 = aeModes.data();

    std::vector<int64_t> exposureRange = {1000, 100000000};
    camera_metadata_entry_t exposureEntry;
    exposureEntry.tag = ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE;
    exposureEntry.count = exposureRange.size();
    exposureEntry.data.i64 = exposureRange.data();

    setupCameraWithMultipleMetadata({aeEntry, exposureEntry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 2);
    EXPECT_EQ(params[0], aidlevs::CameraParam::AUTO_EXPOSURE);
    EXPECT_EQ(params[1], aidlevs::CameraParam::ABSOLUTE_EXPOSURE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsOnlyAutoExposure) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::AUTO_EXPOSURE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportExposure_noAeTag) {
    // Setup with a different, unrelated tag.
    std::vector<int64_t> exposureRange = {1000, 100000000};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE;
    entry.count = exposureRange.size();
    entry.data.i64 = exposureRange.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteExposure_noExposureTag) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AE_MODE_OFF};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteExposure_invalidRange) {
    // Metadata setup
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF};
    camera_metadata_entry_t aeEntry;
    aeEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeEntry.count = aeModes.size();
    aeEntry.data.u8 = aeModes.data();

    // Invalid range
    std::vector<int64_t> exposureRange = {100000000, 1000};
    camera_metadata_entry_t exposureEntry;
    exposureEntry.tag = ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE;
    exposureEntry.count = exposureRange.size();
    exposureEntry.data.i64 = exposureRange.data();

    setupCameraWithMultipleMetadata({aeEntry, exposureEntry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsOnlyAbsoluteExposure) {
    // Metadata setup for only manual exposure
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF};
    camera_metadata_entry_t aeEntry;
    aeEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeEntry.count = aeModes.size();
    aeEntry.data.u8 = aeModes.data();

    std::vector<int64_t> exposureRange = {1000, 100000000};
    camera_metadata_entry_t exposureEntry;
    exposureEntry.tag = ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE;
    exposureEntry.count = exposureRange.size();
    exposureEntry.data.i64 = exposureRange.data();

    setupCameraWithMultipleMetadata({aeEntry, exposureEntry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::ABSOLUTE_EXPOSURE);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAutoFocus_modeAvailable) {
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AF_MODE_OFF, ACAMERA_CONTROL_AF_MODE_AUTO};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AF_AVAILABLE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::AUTO_FOCUS);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAutoFocus_noTag) {
    // Setup with a different, unrelated tag.
    std::vector<uint8_t> faceDetectModes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES;
    entry.count = faceDetectModes.size();
    entry.data.u8 = faceDetectModes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAutoFocus_onlyOffMode) {
    // Only the OFF mode is available.
    std::vector<uint8_t> modes = {ACAMERA_CONTROL_AF_MODE_OFF};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AF_AVAILABLE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAbsoluteFocus_validDistance) {
    std::vector<float> dist = {10.0f};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE;
    entry.count = dist.size();
    entry.data.f = dist.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::ABSOLUTE_FOCUS);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteFocus_noTag) {
    // Setup with a different, unrelated tag.
    std::vector<uint8_t> faceDetectModes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES;
    entry.count = faceDetectModes.size();
    entry.data.u8 = faceDetectModes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteFocus_invalidCount) {
    // Provide no values when one is expected.
    std::vector<float> dist = {};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE;
    entry.count = dist.size();
    entry.data.f = dist.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteFocus_zeroDistance) {
    // A minimum focus distance of 0 means the parameter is not supported.
    std::vector<float> dist = {0.0f};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE;
    entry.count = dist.size();
    entry.data.f = dist.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAbsoluteZoom_zoomIn) {
    std::vector<float> range = {1.0f, 100.0f};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_ZOOM_RATIO_RANGE;
    entry.count = range.size();
    entry.data.f = range.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::ABSOLUTE_ZOOM);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAbsoluteZoom_zoomOut) {
    std::vector<float> range = {0.5f, 1.0f};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_ZOOM_RATIO_RANGE;
    entry.count = range.size();
    entry.data.f = range.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::ABSOLUTE_ZOOM);
}

TEST_F(CompatVirtualCameraTest, getParameterList_supportsAbsoluteZoom_zoomInAndOut) {
    std::vector<float> range = {0.5f, 100.0f};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_ZOOM_RATIO_RANGE;
    entry.count = range.size();
    entry.data.f = range.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(params.size(), 1);
    EXPECT_EQ(params[0], aidlevs::CameraParam::ABSOLUTE_ZOOM);
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteZoom_noTag) {
    // Setup with a different, unrelated tag.
    std::vector<uint8_t> faceDetectModes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES;
    entry.count = faceDetectModes.size();
    entry.data.u8 = faceDetectModes.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteZoom_invalidCount) {
    // Provide only one value when two are expected.
    std::vector<float> range = {1.0f};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_ZOOM_RATIO_RANGE;
    entry.count = range.size();
    entry.data.f = range.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getParameterList_doesNotSupportAbsoluteZoom_fixedZoom) {
    // A zoom range of [1.0, 1.0] means no zoom is supported.
    std::vector<float> range = {1.0f, 1.0f};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_ZOOM_RATIO_RANGE;
    entry.count = range.size();
    entry.data.f = range.data();
    setupCameraWithMultipleMetadata({entry});

    std::vector<aidlevs::CameraParam> params;
    ndk::ScopedAStatus status = mVirtualCamera->getParameterList(&params);

    ASSERT_TRUE(status.isOk());
    EXPECT_TRUE(params.empty());
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_unsupportedParam) {
    // Setup with metadata that does not support BRIGHTNESS
    std::vector<uint8_t> modes = {ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});

    aidlevs::CameraParam param = aidlevs::CameraParam::BRIGHTNESS;
    aidlevs::ParameterRange range;
    ndk::ScopedAStatus status = mVirtualCamera->getIntParameterRange(param, &range);
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getIntParameterRange_brightness) {
    std::vector<int32_t> range = {-5, 5};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_COMPENSATION_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});
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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});
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
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    camera_metadata_entry_t awbEntry;
    awbEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbEntry.count = awbModes.size();
    awbEntry.data.u8 = awbModes.data();

    std::vector<int32_t> tempRange = {2000, 8000};
    camera_metadata_entry_t tempEntry;
    tempEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE;
    tempEntry.count = tempRange.size();
    tempEntry.data.i32 = tempRange.data();

    setupCameraWithMultipleMetadata({awbEntry, tempEntry});

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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_EDGE_AVAILABLE_EDGE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});
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
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF};
    camera_metadata_entry_t aeEntry;
    aeEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeEntry.count = aeModes.size();
    aeEntry.data.u8 = aeModes.data();

    std::vector<int64_t> exposureRange = {1000, 100000000};
    camera_metadata_entry_t exposureEntry;
    exposureEntry.tag = ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE;
    exposureEntry.count = exposureRange.size();
    exposureEntry.data.i64 = exposureRange.data();

    setupCameraWithMultipleMetadata({aeEntry, exposureEntry});

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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE;
    entry.count = dist.size();
    entry.data.f = dist.data();
    setupCameraWithMultipleMetadata({entry});
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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_ZOOM_RATIO_RANGE;
    entry.count = range.size();
    entry.data.f = range.data();
    setupCameraWithMultipleMetadata({entry});
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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});
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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});
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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});
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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AF_AVAILABLE_MODES;
    entry.count = modes.size();
    entry.data.u8 = modes.data();
    setupCameraWithMultipleMetadata({entry});
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
                                                        aidlevs::Stream(), true,
                                                        mMockCameraManager);
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
                                                                     aidlevs::Stream(), true,
                                                                     mMockCameraManager);
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
                                                                 aidlevs::Stream(), true,
                                                                 mMockCameraManager);
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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_COMPENSATION_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

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
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_COMPENSATION_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

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
    // 1. Setup camera capabilities
    std::vector<int32_t> range = {-5, 5};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_COMPENSATION_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    int32_t exposureValue = 3;
    camera_metadata_entry_t exposureResultEntry;
    exposureResultEntry.tag = ACAMERA_CONTROL_AE_EXPOSURE_COMPENSATION;
    exposureResultEntry.count = 1;
    exposureResultEntry.data.i32 = &exposureValue;

    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({exposureResultEntry, aeModeResultEntry});

    // 3. Call the function under test
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::BRIGHTNESS, &values);

    // 4. Verify the result
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], exposureValue);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_brightness_aeOff) {
    // 1. Setup with metadata that supports BRIGHTNESS
    std::vector<int32_t> range = {-5, 5};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_COMPENSATION_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_OFF;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({aeModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::BRIGHTNESS, &values);

    // 4. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_gain_manualControl) {
    // 1. Setup with metadata that supports GAIN
    std::vector<int32_t> range = {100, 1600};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    int32_t sensitivityValue = 800;
    camera_metadata_entry_t sensitivityEntry;
    sensitivityEntry.tag = ACAMERA_SENSOR_SENSITIVITY;
    sensitivityEntry.count = 1;
    sensitivityEntry.data.i32 = &sensitivityValue;

    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    camera_metadata_entry_t controlEntry;
    controlEntry.tag = ACAMERA_CONTROL_MODE;
    controlEntry.count = 1;
    controlEntry.data.u8 = &controlMode;

    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    camera_metadata_entry_t aeEntry;
    aeEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeEntry.count = 1;
    aeEntry.data.u8 = &aeMode;

    setupMockCaptureResult({sensitivityEntry, controlEntry, aeEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::GAIN, &values);

    // 4. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], sensitivityValue);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_gain_autoControl) {
    // 1. Setup with metadata that supports GAIN
    std::vector<int32_t> range = {100, 1600};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlEntry;
    controlEntry.tag = ACAMERA_CONTROL_MODE;
    controlEntry.count = 1;
    controlEntry.data.u8 = &controlMode;

    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    camera_metadata_entry_t aeEntry;
    aeEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeEntry.count = 1;
    aeEntry.data.u8 = &aeMode;

    setupMockCaptureResult({controlEntry, aeEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::GAIN, &values);

    // 4. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoGain_off) {
    // 1. Setup with metadata that supports AUTOGAIN
    std::vector<int32_t> range = {100, 1600};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_OFF;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({aeModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTOGAIN, &values);

    // 4. Verify success and that the value is 0 (OFF)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 0);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoGain_on) {
    // 1. Setup with metadata that supports AUTOGAIN
    std::vector<int32_t> range = {100, 1600};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON_AUTO_FLASH;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({aeModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTOGAIN, &values);

    // 4. Verify success and that the value is 1 (ON)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoWhiteBalance_on) {
    // 1. Setup with metadata that supports AUTO_WHITE_BALANCE
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_AUTO};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    entry.count = awbModes.size();
    entry.data.u8 = awbModes.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_AUTO;
    camera_metadata_entry_t awbModeResultEntry;
    awbModeResultEntry.tag = ACAMERA_CONTROL_AWB_MODE;
    awbModeResultEntry.count = 1;
    awbModeResultEntry.data.u8 = &awbMode;

    setupMockCaptureResult({awbModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_WHITE_BALANCE, &values);

    // 4. Verify success and that the value is 1 (ON)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoWhiteBalance_off) {
    // 1. Setup with metadata that supports AUTO_WHITE_BALANCE
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_AUTO, ACAMERA_CONTROL_AWB_MODE_OFF};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    entry.count = awbModes.size();
    entry.data.u8 = awbModes.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_OFF;
    camera_metadata_entry_t awbModeResultEntry;
    awbModeResultEntry.tag = ACAMERA_CONTROL_AWB_MODE;
    awbModeResultEntry.count = 1;
    awbModeResultEntry.data.u8 = &awbMode;

    setupMockCaptureResult({awbModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_WHITE_BALANCE, &values);

    // 4. Verify success and that the value is 0 (OFF)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 0);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_whiteBalanceTemperature_awbOff) {
    // 1. Setup with metadata that supports WHITE_BALANCE_TEMPERATURE
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    camera_metadata_entry_t awbEntry;
    awbEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbEntry.count = awbModes.size();
    awbEntry.data.u8 = awbModes.data();

    std::vector<int32_t> tempRange = {2000, 8000};
    camera_metadata_entry_t tempEntry;
    tempEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE;
    tempEntry.count = tempRange.size();
    tempEntry.data.i32 = tempRange.data();

    setupCameraWithMultipleMetadata({awbEntry, tempEntry});

    // 2. Define the desired capture result and setup mocks
    int32_t tempValue = 5500;
    camera_metadata_entry_t tempResultEntry;
    tempResultEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE;
    tempResultEntry.count = 1;
    tempResultEntry.data.i32 = &tempValue;

    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_OFF;
    camera_metadata_entry_t awbModeResultEntry;
    awbModeResultEntry.tag = ACAMERA_CONTROL_AWB_MODE;
    awbModeResultEntry.count = 1;
    awbModeResultEntry.data.u8 = &awbMode;

    setupMockCaptureResult({tempResultEntry, awbModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE,
                                            &values);

    // 4. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], tempValue);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_whiteBalanceTemperature_awbOn) {
    // 1. Setup with metadata that supports WHITE_BALANCE_TEMPERATURE
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF, ACAMERA_CONTROL_AWB_MODE_AUTO};
    camera_metadata_entry_t awbEntry;
    awbEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbEntry.count = awbModes.size();
    awbEntry.data.u8 = awbModes.data();

    std::vector<int32_t> tempRange = {2000, 8000};
    camera_metadata_entry_t tempEntry;
    tempEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE;
    tempEntry.count = tempRange.size();
    tempEntry.data.i32 = tempRange.data();

    setupCameraWithMultipleMetadata({awbEntry, tempEntry});

    // 2. Define the desired capture result and setup mocks
    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_AUTO;
    camera_metadata_entry_t awbModeResultEntry;
    awbModeResultEntry.tag = ACAMERA_CONTROL_AWB_MODE;
    awbModeResultEntry.count = 1;
    awbModeResultEntry.data.u8 = &awbMode;

    setupMockCaptureResult({awbModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE,
                                            &values);

    // 4. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_sharpness) {
    // 1. Setup with metadata that supports SHARPNESS
    std::vector<uint8_t> edgeModes = {ACAMERA_EDGE_MODE_FAST};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_EDGE_AVAILABLE_EDGE_MODES;
    entry.count = edgeModes.size();
    entry.data.u8 = edgeModes.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t edgeMode = ACAMERA_EDGE_MODE_HIGH_QUALITY;
    camera_metadata_entry_t edgeModeResultEntry;
    edgeModeResultEntry.tag = ACAMERA_EDGE_MODE;
    edgeModeResultEntry.count = 1;
    edgeModeResultEntry.data.u8 = &edgeMode;

    setupMockCaptureResult({edgeModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::SHARPNESS, &values);

    // 4. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], edgeMode);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoExposure_off) {
    // 1. Setup with metadata that supports AUTO_EXPOSURE
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    entry.count = aeModes.size();
    entry.data.u8 = aeModes.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_OFF;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({aeModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_EXPOSURE, &values);

    // 4. Verify success and that the value is 0 (OFF)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 0);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoExposure_on) {
    // 1. Setup with metadata that supports AUTO_EXPOSURE
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    entry.count = aeModes.size();
    entry.data.u8 = aeModes.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({aeModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_EXPOSURE, &values);

    // 4. Verify success and that the value is 1 (ON)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_absoluteExposure_manualControl) {
    // 1. Setup with metadata that supports ABSOLUTE_EXPOSURE
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF};
    camera_metadata_entry_t aeEntry;
    aeEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeEntry.count = aeModes.size();
    aeEntry.data.u8 = aeModes.data();

    std::vector<int64_t> exposureRange = {1000, 100000000};
    camera_metadata_entry_t exposureEntry;
    exposureEntry.tag = ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE;
    exposureEntry.count = exposureRange.size();
    exposureEntry.data.i64 = exposureRange.data();

    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    camera_metadata_entry_t controlEntry;
    controlEntry.tag = ACAMERA_CONTROL_MODE;
    controlEntry.count = 1;
    controlEntry.data.u8 = &controlMode;

    setupCameraWithMultipleMetadata({aeEntry, exposureEntry, controlEntry});

    // 2. Define the desired capture result and setup mocks
    int64_t expValue = 50000000L;  // 50ms
    camera_metadata_entry_t expResultEntry;
    expResultEntry.tag = ACAMERA_SENSOR_EXPOSURE_TIME;
    expResultEntry.count = 1;
    expResultEntry.data.i64 = &expValue;

    camera_metadata_entry_t controlResultEntry;
    controlResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlResultEntry.count = 1;
    controlResultEntry.data.u8 = &controlMode;

    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_OFF;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({expResultEntry, controlResultEntry, aeModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::ABSOLUTE_EXPOSURE, &values);

    // 4. Verify success and the returned value (converted to microseconds)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], static_cast<int32_t>(expValue / 1000));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_absoluteExposure_autoControl) {
    // 1. Setup with metadata that supports ABSOLUTE_EXPOSURE
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t aeEntry;
    aeEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeEntry.count = aeModes.size();
    aeEntry.data.u8 = aeModes.data();

    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlEntry;
    controlEntry.tag = ACAMERA_CONTROL_MODE;
    controlEntry.count = 1;
    controlEntry.data.u8 = &controlMode;

    std::vector<int64_t> expRange = {1000L, 100000000L};
    camera_metadata_entry_t expEntry;
    expEntry.tag = ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE;
    expEntry.count = expRange.size();
    expEntry.data.i64 = expRange.data();

    setupCameraWithMultipleMetadata({aeEntry, controlEntry, expEntry});

    // 2. Define the desired capture result and setup mocks
    camera_metadata_entry_t controlResultEntry;
    controlResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlResultEntry.count = 1;
    controlResultEntry.data.u8 = &controlMode;

    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({controlResultEntry, aeModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::ABSOLUTE_EXPOSURE, &values);

    // 4. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoFocus_off) {
    // 1. Setup with metadata that supports AUTO_FOCUS
    std::vector<uint8_t> afModes = {ACAMERA_CONTROL_AF_MODE_OFF, ACAMERA_CONTROL_AF_MODE_AUTO};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AF_AVAILABLE_MODES;
    entry.count = afModes.size();
    entry.data.u8 = afModes.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t afMode = ACAMERA_CONTROL_AF_MODE_OFF;
    camera_metadata_entry_t afModeResultEntry;
    afModeResultEntry.tag = ACAMERA_CONTROL_AF_MODE;
    afModeResultEntry.count = 1;
    afModeResultEntry.data.u8 = &afMode;

    setupMockCaptureResult({afModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_FOCUS, &values);

    // 4. Verify success and that the value is 0 (OFF)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 0);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_autoFocus_on) {
    // 1. Setup with metadata that supports AUTO_FOCUS
    std::vector<uint8_t> afModes = {ACAMERA_CONTROL_AF_MODE_OFF, ACAMERA_CONTROL_AF_MODE_AUTO};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AF_AVAILABLE_MODES;
    entry.count = afModes.size();
    entry.data.u8 = afModes.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t afMode = ACAMERA_CONTROL_AF_MODE_AUTO;
    camera_metadata_entry_t afModeResultEntry;
    afModeResultEntry.tag = ACAMERA_CONTROL_AF_MODE;
    afModeResultEntry.count = 1;
    afModeResultEntry.data.u8 = &afMode;

    setupMockCaptureResult({afModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::AUTO_FOCUS, &values);

    // 4. Verify success and that the value is 1 (ON)
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], 1);
}

TEST_F(CompatVirtualCameraTest, getIntParameter_absoluteFocus_afOff) {
    // 1. Setup with metadata that supports ABSOLUTE_FOCUS
    std::vector<float> minFocus = {10.0f};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE;
    entry.count = minFocus.size();
    entry.data.f = minFocus.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    float focusValue = 5.0f;
    camera_metadata_entry_t focusResultEntry;
    focusResultEntry.tag = ACAMERA_LENS_FOCUS_DISTANCE;
    focusResultEntry.count = 1;
    focusResultEntry.data.f = &focusValue;

    uint8_t afMode = ACAMERA_CONTROL_AF_MODE_OFF;
    camera_metadata_entry_t afModeResultEntry;
    afModeResultEntry.tag = ACAMERA_CONTROL_AF_MODE;
    afModeResultEntry.count = 1;
    afModeResultEntry.data.u8 = &afMode;

    setupMockCaptureResult({focusResultEntry, afModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::ABSOLUTE_FOCUS, &values);

    // 4. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], static_cast<int32_t>(focusValue * 100.0f));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_absoluteFocus_afOn) {
    // 1. Setup with metadata that supports ABSOLUTE_FOCUS
    std::vector<float> minFocus = {10.0f};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE;
    entry.count = minFocus.size();
    entry.data.f = minFocus.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t afMode = ACAMERA_CONTROL_AF_MODE_AUTO;
    camera_metadata_entry_t afModeResultEntry;
    afModeResultEntry.tag = ACAMERA_CONTROL_AF_MODE;
    afModeResultEntry.count = 1;
    afModeResultEntry.data.u8 = &afMode;

    setupMockCaptureResult({afModeResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::ABSOLUTE_FOCUS, &values);

    // 4. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, getIntParameter_absoluteZoom) {
    // 1. Setup with metadata that supports ABSOLUTE_ZOOM
    std::vector<float> zoomRange = {0.5f, 100.0f};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_ZOOM_RATIO_RANGE;
    entry.count = zoomRange.size();
    entry.data.f = zoomRange.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    float zoomValue = 2.0f;
    camera_metadata_entry_t zoomResultEntry;
    zoomResultEntry.tag = ACAMERA_CONTROL_ZOOM_RATIO;
    zoomResultEntry.count = 1;
    zoomResultEntry.data.f = &zoomValue;

    setupMockCaptureResult({zoomResultEntry});

    // 3. Call getIntParameter
    std::vector<int32_t> values;
    ndk::ScopedAStatus status =
            mVirtualCamera->getIntParameter(aidlevs::CameraParam::ABSOLUTE_ZOOM, &values);

    // 4. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(values.size(), 1);
    EXPECT_EQ(values[0], static_cast<int32_t>(zoomValue * 100.0f));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_brightness_success) {
    // 1. Setup with metadata that supports BRIGHTNESS
    std::vector<int32_t> range = {-5, 5};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_COMPENSATION_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({aeModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Expect the NDK call to update the request
    int32_t valueToSet = 3;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_i32(dummyCaptureRequest,
                                             ACAMERA_CONTROL_AE_EXPOSURE_COMPENSATION, 1,
                                             ::testing::Pointee(valueToSet)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call setIntParameter
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::BRIGHTNESS, valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_brightness_aeOff) {
    // 1. Setup with metadata that supports BRIGHTNESS
    std::vector<int32_t> range = {-5, 5};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_COMPENSATION_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_OFF;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({aeModeResultEntry});

    // 3. Call setIntParameter
    int32_t valueToSet = 3;
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::BRIGHTNESS, valueToSet, &result);

    // 4. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_gain_success) {
    // 1. Setup with metadata that supports GAIN
    std::vector<int32_t> range = {100, 1600};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    camera_metadata_entry_t controlResultEntry;
    controlResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlResultEntry.count = 1;
    controlResultEntry.data.u8 = &controlMode;

    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({controlResultEntry, aeModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Expect the NDK call to update the request
    int32_t valueToSet = 800;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_i32(dummyCaptureRequest, ACAMERA_SENSOR_SENSITIVITY, 1,
                                             ::testing::Pointee(valueToSet)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call setIntParameter
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::GAIN, valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_gain_autoControl) {
    // 1. Setup with metadata that supports GAIN
    std::vector<int32_t> range = {100, 1600};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlResultEntry;
    controlResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlResultEntry.count = 1;
    controlResultEntry.data.u8 = &controlMode;

    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;

    setupMockCaptureResult({controlResultEntry, aeModeResultEntry});

    // 3. Call setIntParameter
    int32_t valueToSet = 800;
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::GAIN, valueToSet, &result);

    // 4. Verify NOT_SUPPORTED is returned
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoGain_success_on) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();

    std::vector<int32_t> sensitivityRange = {100, 1600};
    camera_metadata_entry_t sensitivityRangeEntry;
    sensitivityRangeEntry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    sensitivityRangeEntry.count = sensitivityRange.size();
    sensitivityRangeEntry.data.i32 = sensitivityRange.data();

    setupCameraWithMultipleMetadata({aeModesEntry, sensitivityRangeEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;

    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    uint8_t expectedAeMode = ACAMERA_CONTROL_AE_MODE_ON;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_CONTROL_AE_MODE, 1,
                                            testing::Pointee(expectedAeMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = 1;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTOGAIN, valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoGain_success_off) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();

    std::vector<int32_t> sensitivityRange = {100, 1600};
    camera_metadata_entry_t sensitivityRangeEntry;
    sensitivityRangeEntry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    sensitivityRangeEntry.count = sensitivityRange.size();
    sensitivityRangeEntry.data.i32 = sensitivityRange.data();

    setupCameraWithMultipleMetadata({aeModesEntry, sensitivityRangeEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;

    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    uint8_t expectedAeMode = ACAMERA_CONTROL_AE_MODE_OFF;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_CONTROL_AE_MODE, 1,
                                            testing::Pointee(expectedAeMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = 0;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTOGAIN, valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoGain_success_on_alternativeMode) {
    // 1. Setup camera capabilities with an alternative ON mode
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF,
                                    ACAMERA_CONTROL_AE_MODE_ON_AUTO_FLASH};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();

    std::vector<int32_t> sensitivityRange = {100, 1600};
    camera_metadata_entry_t sensitivityRangeEntry;
    sensitivityRangeEntry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    sensitivityRangeEntry.count = sensitivityRange.size();
    sensitivityRangeEntry.data.i32 = sensitivityRange.data();

    setupCameraWithMultipleMetadata({aeModesEntry, sensitivityRangeEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;

    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    uint8_t expectedAeMode = ACAMERA_CONTROL_AE_MODE_ON_AUTO_FLASH;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_CONTROL_AE_MODE, 1,
                                            testing::Pointee(expectedAeMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = 1;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTOGAIN, valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoGain_fails_controlModeOff) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();

    std::vector<int32_t> sensitivityRange = {100, 1600};
    camera_metadata_entry_t sensitivityRangeEntry;
    sensitivityRangeEntry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    sensitivityRangeEntry.count = sensitivityRange.size();
    sensitivityRangeEntry.data.i32 = sensitivityRange.data();

    setupCameraWithMultipleMetadata({aeModesEntry, sensitivityRangeEntry});

    // 2. Define the current camera state with CONTROL_MODE_OFF
    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;

    setupMockCaptureResult({controlModeResultEntry});

    // 3. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTOGAIN, 1, &result);

    // 4. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoGain_fails_modeNotAvailable) {
    // 1. Setup camera capabilities without AE_MODE_OFF
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();

    std::vector<int32_t> sensitivityRange = {100, 1600};
    camera_metadata_entry_t sensitivityRangeEntry;
    sensitivityRangeEntry.tag = ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE;
    sensitivityRangeEntry.count = sensitivityRange.size();
    sensitivityRangeEntry.data.i32 = sensitivityRange.data();

    setupCameraWithMultipleMetadata({aeModesEntry, sensitivityRangeEntry});

    // 2. Define the current camera state
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;

    setupMockCaptureResult({controlModeResultEntry});

    // 3. Call the function under test, attempting to turn AUTOGAIN OFF
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTOGAIN, 0, &result);

    // 4. Verify failure because AE_MODE_OFF is not available
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoWhiteBalance_success_on) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF, ACAMERA_CONTROL_AWB_MODE_AUTO};
    camera_metadata_entry_t awbModesEntry;
    awbModesEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbModesEntry.count = awbModes.size();
    awbModesEntry.data.u8 = awbModes.data();
    setupCameraWithMultipleMetadata({awbModesEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    uint8_t expectedAwbMode = ACAMERA_CONTROL_AWB_MODE_AUTO;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_CONTROL_AWB_MODE, 1,
                                            testing::Pointee(expectedAwbMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = 1;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_WHITE_BALANCE, valueToSet,
                                            &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoWhiteBalance_success_off) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF, ACAMERA_CONTROL_AWB_MODE_AUTO};
    camera_metadata_entry_t awbModesEntry;
    awbModesEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbModesEntry.count = awbModes.size();
    awbModesEntry.data.u8 = awbModes.data();
    setupCameraWithMultipleMetadata({awbModesEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    uint8_t expectedAwbMode = ACAMERA_CONTROL_AWB_MODE_OFF;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_CONTROL_AWB_MODE, 1,
                                            testing::Pointee(expectedAwbMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = 0;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_WHITE_BALANCE, valueToSet,
                                            &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoWhiteBalance_fails_controlModeOff) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF, ACAMERA_CONTROL_AWB_MODE_AUTO};
    camera_metadata_entry_t awbModesEntry;
    awbModesEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbModesEntry.count = awbModes.size();
    awbModesEntry.data.u8 = awbModes.data();
    setupCameraWithMultipleMetadata({awbModesEntry});

    // 2. Define the current camera state with CONTROL_MODE_OFF
    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_WHITE_BALANCE, 1, &result);

    // 4. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoWhiteBalance_fails_modeNotAvailable_off) {
    // 1. Setup camera capabilities without AWB_MODE_OFF
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_AUTO};
    camera_metadata_entry_t awbModesEntry;
    awbModesEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbModesEntry.count = awbModes.size();
    awbModesEntry.data.u8 = awbModes.data();
    setupCameraWithMultipleMetadata({awbModesEntry});

    // 2. Define the current camera state
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Call the function under test, attempting to turn AWB OFF
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_WHITE_BALANCE, 0, &result);

    // 4. Verify failure because AWB_MODE_OFF is not available
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_whiteBalanceTemperature_success) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    camera_metadata_entry_t awbModesEntry;
    awbModesEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbModesEntry.count = awbModes.size();
    awbModesEntry.data.u8 = awbModes.data();
    std::vector<int32_t> tempRange = {2000, 8000};
    camera_metadata_entry_t tempRangeEntry;
    tempRangeEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE;
    tempRangeEntry.count = tempRange.size();
    tempRangeEntry.data.i32 = tempRange.data();
    setupCameraWithMultipleMetadata({awbModesEntry, tempRangeEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_OFF;
    camera_metadata_entry_t awbModeResultEntry;
    awbModeResultEntry.tag = ACAMERA_CONTROL_AWB_MODE;
    awbModeResultEntry.count = 1;
    awbModeResultEntry.data.u8 = &awbMode;
    uint8_t colorCorrectionMode = ACAMERA_COLOR_CORRECTION_MODE_FAST;
    camera_metadata_entry_t colorCorrectionModeResultEntry;
    colorCorrectionModeResultEntry.tag = ACAMERA_COLOR_CORRECTION_MODE;
    colorCorrectionModeResultEntry.count = 1;
    colorCorrectionModeResultEntry.data.u8 = &colorCorrectionMode;
    setupMockCaptureResult(
            {controlModeResultEntry, awbModeResultEntry, colorCorrectionModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    int32_t valueToSet = 5500;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_i32(dummyCaptureRequest,
                                             ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE, 1,
                                             testing::Pointee(valueToSet)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE,
                                            valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_whiteBalanceTemperature_fails_controlModeNotAuto) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    camera_metadata_entry_t awbModesEntry;
    awbModesEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbModesEntry.count = awbModes.size();
    awbModesEntry.data.u8 = awbModes.data();
    std::vector<int32_t> tempRange = {2000, 8000};
    camera_metadata_entry_t tempRangeEntry;
    tempRangeEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE;
    tempRangeEntry.count = tempRange.size();
    tempRangeEntry.data.i32 = tempRange.data();
    setupCameraWithMultipleMetadata({awbModesEntry, tempRangeEntry});

    // 2. Define the current camera state with CONTROL_MODE_OFF
    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_OFF;
    camera_metadata_entry_t awbModeResultEntry;
    awbModeResultEntry.tag = ACAMERA_CONTROL_AWB_MODE;
    awbModeResultEntry.count = 1;
    awbModeResultEntry.data.u8 = &awbMode;
    uint8_t colorCorrectionMode = ACAMERA_COLOR_CORRECTION_MODE_FAST;
    camera_metadata_entry_t colorCorrectionModeResultEntry;
    colorCorrectionModeResultEntry.tag = ACAMERA_COLOR_CORRECTION_MODE;
    colorCorrectionModeResultEntry.count = 1;
    colorCorrectionModeResultEntry.data.u8 = &colorCorrectionMode;
    setupMockCaptureResult(
            {controlModeResultEntry, awbModeResultEntry, colorCorrectionModeResultEntry});

    // 3. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE, 5500,
                                            &result);

    // 4. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_whiteBalanceTemperature_fails_awbModeNotOff) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF, ACAMERA_CONTROL_AWB_MODE_AUTO};
    camera_metadata_entry_t awbModesEntry;
    awbModesEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbModesEntry.count = awbModes.size();
    awbModesEntry.data.u8 = awbModes.data();
    std::vector<int32_t> tempRange = {2000, 8000};
    camera_metadata_entry_t tempRangeEntry;
    tempRangeEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE;
    tempRangeEntry.count = tempRange.size();
    tempRangeEntry.data.i32 = tempRange.data();
    setupCameraWithMultipleMetadata({awbModesEntry, tempRangeEntry});

    // 2. Define the current camera state with AWB_MODE_AUTO
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_AUTO;
    camera_metadata_entry_t awbModeResultEntry;
    awbModeResultEntry.tag = ACAMERA_CONTROL_AWB_MODE;
    awbModeResultEntry.count = 1;
    awbModeResultEntry.data.u8 = &awbMode;
    uint8_t colorCorrectionMode = ACAMERA_COLOR_CORRECTION_MODE_FAST;
    camera_metadata_entry_t colorCorrectionModeResultEntry;
    colorCorrectionModeResultEntry.tag = ACAMERA_COLOR_CORRECTION_MODE;
    colorCorrectionModeResultEntry.count = 1;
    colorCorrectionModeResultEntry.data.u8 = &colorCorrectionMode;
    setupMockCaptureResult(
            {controlModeResultEntry, awbModeResultEntry, colorCorrectionModeResultEntry});

    // 3. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE, 5500,
                                            &result);

    // 4. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest,
       setIntParameter_whiteBalanceTemperature_fails_colorCorrectionModeTransformMatrix) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> awbModes = {ACAMERA_CONTROL_AWB_MODE_OFF};
    camera_metadata_entry_t awbModesEntry;
    awbModesEntry.tag = ACAMERA_CONTROL_AWB_AVAILABLE_MODES;
    awbModesEntry.count = awbModes.size();
    awbModesEntry.data.u8 = awbModes.data();
    std::vector<int32_t> tempRange = {2000, 8000};
    camera_metadata_entry_t tempRangeEntry;
    tempRangeEntry.tag = ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE;
    tempRangeEntry.count = tempRange.size();
    tempRangeEntry.data.i32 = tempRange.data();
    setupCameraWithMultipleMetadata({awbModesEntry, tempRangeEntry});

    // 2. Define the current camera state with COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    uint8_t awbMode = ACAMERA_CONTROL_AWB_MODE_OFF;
    camera_metadata_entry_t awbModeResultEntry;
    awbModeResultEntry.tag = ACAMERA_CONTROL_AWB_MODE;
    awbModeResultEntry.count = 1;
    awbModeResultEntry.data.u8 = &awbMode;
    uint8_t colorCorrectionMode = ACAMERA_COLOR_CORRECTION_MODE_TRANSFORM_MATRIX;
    camera_metadata_entry_t colorCorrectionModeResultEntry;
    colorCorrectionModeResultEntry.tag = ACAMERA_COLOR_CORRECTION_MODE;
    colorCorrectionModeResultEntry.count = 1;
    colorCorrectionModeResultEntry.data.u8 = &colorCorrectionMode;
    setupMockCaptureResult(
            {controlModeResultEntry, awbModeResultEntry, colorCorrectionModeResultEntry});

    // 3. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::WHITE_BALANCE_TEMPERATURE, 5500,
                                            &result);

    // 4. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_sharpness_success) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> edgeModes = {ACAMERA_EDGE_MODE_OFF, ACAMERA_EDGE_MODE_FAST};
    camera_metadata_entry_t edgeModesEntry;
    edgeModesEntry.tag = ACAMERA_EDGE_AVAILABLE_EDGE_MODES;
    edgeModesEntry.count = edgeModes.size();
    edgeModesEntry.data.u8 = edgeModes.data();
    setupCameraWithMultipleMetadata({edgeModesEntry});

    // 2. Setup mock capture result
    setupMockCaptureResult({});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 3. Set expectations for the NDK calls
    uint8_t expectedEdgeMode = ACAMERA_EDGE_MODE_FAST;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_EDGE_MODE, 1,
                                            testing::Pointee(expectedEdgeMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 4. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = ACAMERA_EDGE_MODE_FAST;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::SHARPNESS, valueToSet, &result);

    // 5. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_sharpness_fails_modeNotAvailable) {
    // 1. Setup camera capabilities that do not include the desired mode
    std::vector<uint8_t> edgeModes = {ACAMERA_EDGE_MODE_OFF, ACAMERA_EDGE_MODE_HIGH_QUALITY};
    camera_metadata_entry_t edgeModesEntry;
    edgeModesEntry.tag = ACAMERA_EDGE_AVAILABLE_EDGE_MODES;
    edgeModesEntry.count = edgeModes.size();
    edgeModesEntry.data.u8 = edgeModes.data();
    setupCameraWithMultipleMetadata({edgeModesEntry});

    // 2. Setup mock capture result
    setupMockCaptureResult({});

    // 3. Call the function under test with an unsupported mode
    std::vector<int32_t> result;
    ndk::ScopedAStatus status = mVirtualCamera->setIntParameter(aidlevs::CameraParam::SHARPNESS,
                                                                ACAMERA_EDGE_MODE_FAST, &result);

    // 3. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoExposure_success_on) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();
    setupCameraWithMultipleMetadata({aeModesEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    uint8_t expectedAeMode = ACAMERA_CONTROL_AE_MODE_ON;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_CONTROL_AE_MODE, 1,
                                            testing::Pointee(expectedAeMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = 1;
    ndk::ScopedAStatus status = mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_EXPOSURE,
                                                                valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoExposure_success_off) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();
    setupCameraWithMultipleMetadata({aeModesEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    uint8_t expectedAeMode = ACAMERA_CONTROL_AE_MODE_OFF;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_CONTROL_AE_MODE, 1,
                                            testing::Pointee(expectedAeMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = 0;
    ndk::ScopedAStatus status = mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_EXPOSURE,
                                                                valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoExposure_success_on_alternativeMode) {
    // 1. Setup camera capabilities with an alternative ON mode
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF,
                                    ACAMERA_CONTROL_AE_MODE_ON_AUTO_FLASH};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();
    setupCameraWithMultipleMetadata({aeModesEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    uint8_t expectedAeMode = ACAMERA_CONTROL_AE_MODE_ON_AUTO_FLASH;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_CONTROL_AE_MODE, 1,
                                            testing::Pointee(expectedAeMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = 1;
    ndk::ScopedAStatus status = mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_EXPOSURE,
                                                                valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoExposure_fails_controlModeOff) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();
    setupCameraWithMultipleMetadata({aeModesEntry});

    // 2. Define the current camera state with CONTROL_MODE_OFF
    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_EXPOSURE, 1, &result);

    // 4. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoExposure_fails_modeNotAvailable) {
    // 1. Setup camera capabilities without AE_MODE_OFF
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();
    setupCameraWithMultipleMetadata({aeModesEntry});

    // 2. Define the current camera state
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Call the function under test, attempting to turn AUTO_EXPOSURE OFF
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_EXPOSURE, 0, &result);

    // 4. Verify failure because AE_MODE_OFF is not available
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_absoluteExposure_success) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();
    std::vector<int64_t> exposureRange = {1000L, 100000000L};
    camera_metadata_entry_t exposureRangeEntry;
    exposureRangeEntry.tag = ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE;
    exposureRangeEntry.count = exposureRange.size();
    exposureRangeEntry.data.i64 = exposureRange.data();
    setupCameraWithMultipleMetadata({aeModesEntry, exposureRangeEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;
    setupMockCaptureResult({controlModeResultEntry, aeModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    int32_t valueToSet = 50000;  // 50ms
    int64_t expectedExposureTimeNs = static_cast<int64_t>(valueToSet) * 1000;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_i64(dummyCaptureRequest, ACAMERA_SENSOR_EXPOSURE_TIME, 1,
                                             testing::Pointee(expectedExposureTimeNs)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::ABSOLUTE_EXPOSURE, valueToSet,
                                            &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_absoluteExposure_fails_autoModesOn) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> aeModes = {ACAMERA_CONTROL_AE_MODE_OFF, ACAMERA_CONTROL_AE_MODE_ON};
    camera_metadata_entry_t aeModesEntry;
    aeModesEntry.tag = ACAMERA_CONTROL_AE_AVAILABLE_MODES;
    aeModesEntry.count = aeModes.size();
    aeModesEntry.data.u8 = aeModes.data();
    std::vector<int64_t> exposureRange = {1000L, 100000000L};
    camera_metadata_entry_t exposureRangeEntry;
    exposureRangeEntry.tag = ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE;
    exposureRangeEntry.count = exposureRange.size();
    exposureRangeEntry.data.i64 = exposureRange.data();
    setupCameraWithMultipleMetadata({aeModesEntry, exposureRangeEntry});

    // 2. Define the current camera state with both auto modes ON
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;
    setupMockCaptureResult({controlModeResultEntry, aeModeResultEntry});

    // 3. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::ABSOLUTE_EXPOSURE, 50000,
                                            &result);

    // 4. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoFocus_success_on) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> afModes = {ACAMERA_CONTROL_AF_MODE_OFF, ACAMERA_CONTROL_AF_MODE_AUTO};
    camera_metadata_entry_t afModesEntry;
    afModesEntry.tag = ACAMERA_CONTROL_AF_AVAILABLE_MODES;
    afModesEntry.count = afModes.size();
    afModesEntry.data.u8 = afModes.data();
    setupCameraWithMultipleMetadata({afModesEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    uint8_t expectedAfMode = ACAMERA_CONTROL_AF_MODE_AUTO;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_CONTROL_AF_MODE, 1,
                                            testing::Pointee(expectedAfMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = 1;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_FOCUS, valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoFocus_success_off) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> afModes = {ACAMERA_CONTROL_AF_MODE_OFF, ACAMERA_CONTROL_AF_MODE_AUTO};
    camera_metadata_entry_t afModesEntry;
    afModesEntry.tag = ACAMERA_CONTROL_AF_AVAILABLE_MODES;
    afModesEntry.count = afModes.size();
    afModesEntry.data.u8 = afModes.data();
    setupCameraWithMultipleMetadata({afModesEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    uint8_t expectedAfMode = ACAMERA_CONTROL_AF_MODE_OFF;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_CONTROL_AF_MODE, 1,
                                            testing::Pointee(expectedAfMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = 0;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_FOCUS, valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoFocus_success_on_alternativeMode) {
    // 1. Setup camera capabilities with an alternative ON mode
    std::vector<uint8_t> afModes = {ACAMERA_CONTROL_AF_MODE_OFF,
                                    ACAMERA_CONTROL_AF_MODE_CONTINUOUS_VIDEO};
    camera_metadata_entry_t afModesEntry;
    afModesEntry.tag = ACAMERA_CONTROL_AF_AVAILABLE_MODES;
    afModesEntry.count = afModes.size();
    afModesEntry.data.u8 = afModes.data();
    setupCameraWithMultipleMetadata({afModesEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    uint8_t expectedAfMode = ACAMERA_CONTROL_AF_MODE_CONTINUOUS_VIDEO;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_u8(dummyCaptureRequest, ACAMERA_CONTROL_AF_MODE, 1,
                                            testing::Pointee(expectedAfMode)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    int32_t valueToSet = 1;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_FOCUS, valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoFocus_fails_controlModeOff) {
    // 1. Setup camera capabilities
    std::vector<uint8_t> afModes = {ACAMERA_CONTROL_AF_MODE_OFF, ACAMERA_CONTROL_AF_MODE_AUTO};
    camera_metadata_entry_t afModesEntry;
    afModesEntry.tag = ACAMERA_CONTROL_AF_AVAILABLE_MODES;
    afModesEntry.count = afModes.size();
    afModesEntry.data.u8 = afModes.data();
    setupCameraWithMultipleMetadata({afModesEntry});

    // 2. Define the current camera state with CONTROL_MODE_OFF
    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_FOCUS, 1, &result);

    // 4. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_autoFocus_fails_modeNotAvailable) {
    // 1. Setup camera capabilities without AF_MODE_OFF
    std::vector<uint8_t> afModes = {ACAMERA_CONTROL_AF_MODE_AUTO};
    camera_metadata_entry_t afModesEntry;
    afModesEntry.tag = ACAMERA_CONTROL_AF_AVAILABLE_MODES;
    afModesEntry.count = afModes.size();
    afModesEntry.data.u8 = afModes.data();
    setupCameraWithMultipleMetadata({afModesEntry});

    // 2. Define the current camera state
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Call the function under test, attempting to turn AUTO_FOCUS OFF
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::AUTO_FOCUS, 0, &result);

    // 4. Verify failure because AF_MODE_OFF is not available
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_absoluteFocus_success) {
    // 1. Setup camera capabilities
    std::vector<float> minFocusDist = {10.0f};
    camera_metadata_entry_t minFocusDistEntry;
    minFocusDistEntry.tag = ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE;
    minFocusDistEntry.count = minFocusDist.size();
    minFocusDistEntry.data.f = minFocusDist.data();
    setupCameraWithMultipleMetadata({minFocusDistEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t afMode = ACAMERA_CONTROL_AF_MODE_OFF;
    camera_metadata_entry_t afModeResultEntry;
    afModeResultEntry.tag = ACAMERA_CONTROL_AF_MODE;
    afModeResultEntry.count = 1;
    afModeResultEntry.data.u8 = &afMode;
    setupMockCaptureResult({afModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    int32_t valueToSet = 500;  // Corresponds to 5.0f diopters
    float expectedFocusDistance = static_cast<float>(valueToSet) / 100.0f;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_float(dummyCaptureRequest, ACAMERA_LENS_FOCUS_DISTANCE, 1,
                                               testing::Pointee(expectedFocusDistance)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::ABSOLUTE_FOCUS, valueToSet,
                                            &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_absoluteFocus_fails_afModeNotOff) {
    // 1. Setup camera capabilities
    std::vector<float> minFocusDist = {10.0f};
    camera_metadata_entry_t minFocusDistEntry;
    minFocusDistEntry.tag = ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE;
    minFocusDistEntry.count = minFocusDist.size();
    minFocusDistEntry.data.f = minFocusDist.data();
    setupCameraWithMultipleMetadata({minFocusDistEntry});

    // 2. Define the current camera state with AF_MODE_AUTO
    uint8_t afMode = ACAMERA_CONTROL_AF_MODE_AUTO;
    camera_metadata_entry_t afModeResultEntry;
    afModeResultEntry.tag = ACAMERA_CONTROL_AF_MODE;
    afModeResultEntry.count = 1;
    afModeResultEntry.data.u8 = &afMode;
    setupMockCaptureResult({afModeResultEntry});

    // 3. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::ABSOLUTE_FOCUS, 500, &result);

    // 4. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_absoluteZoom_success) {
    // 1. Setup camera capabilities
    std::vector<float> zoomRatioRange = {1.0f, 100.0f};
    camera_metadata_entry_t zoomRatioRangeEntry;
    zoomRatioRangeEntry.tag = ACAMERA_CONTROL_ZOOM_RATIO_RANGE;
    zoomRatioRangeEntry.count = zoomRatioRange.size();
    zoomRatioRangeEntry.data.f = zoomRatioRange.data();
    setupCameraWithMultipleMetadata({zoomRatioRangeEntry});

    // 2. Define the current camera state and setup mocks
    uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set expectations for the NDK calls
    int32_t valueToSet = 200;  // Corresponds to 2.0f zoom ratio
    float expectedZoomRatio = static_cast<float>(valueToSet) / 100.0f;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_float(dummyCaptureRequest, ACAMERA_CONTROL_ZOOM_RATIO, 1,
                                               testing::Pointee(expectedZoomRatio)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 5. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status = mVirtualCamera->setIntParameter(aidlevs::CameraParam::ABSOLUTE_ZOOM,
                                                                valueToSet, &result);

    // 6. Verify success and the returned value
    ASSERT_TRUE(status.isOk());
    ASSERT_EQ(result.size(), 1);
    EXPECT_EQ(result[0], valueToSet);
}

TEST_F(CompatVirtualCameraTest, setIntParameter_absoluteZoom_fails_controlModeNotAuto) {
    // 1. Setup camera capabilities
    std::vector<float> zoomRatioRange = {1.0f, 100.0f};
    camera_metadata_entry_t zoomRatioRangeEntry;
    zoomRatioRangeEntry.tag = ACAMERA_CONTROL_ZOOM_RATIO_RANGE;
    zoomRatioRangeEntry.count = zoomRatioRange.size();
    zoomRatioRangeEntry.data.f = zoomRatioRange.data();
    setupCameraWithMultipleMetadata({zoomRatioRangeEntry});

    // 2. Define the current camera state with CONTROL_MODE_OFF
    uint8_t controlMode = ACAMERA_CONTROL_MODE_OFF;
    camera_metadata_entry_t controlModeResultEntry;
    controlModeResultEntry.tag = ACAMERA_CONTROL_MODE;
    controlModeResultEntry.count = 1;
    controlModeResultEntry.data.u8 = &controlMode;
    setupMockCaptureResult({controlModeResultEntry});

    // 3. Call the function under test
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::ABSOLUTE_ZOOM, 200, &result);

    // 4. Verify failure
    ASSERT_FALSE(status.isOk());
    EXPECT_EQ(status.getServiceSpecificError(), static_cast<int>(EvsResult::NOT_SUPPORTED));
}

TEST_F(CompatVirtualCameraTest, setIntParameter_sendsNotification) {
    // 1. Setup with metadata that supports BRIGHTNESS
    std::vector<int32_t> range = {-5, 5};
    camera_metadata_entry_t entry;
    entry.tag = ACAMERA_CONTROL_AE_COMPENSATION_RANGE;
    entry.count = range.size();
    entry.data.i32 = range.data();
    setupCameraWithMultipleMetadata({entry});

    // 2. Define the desired capture result and setup mocks
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    camera_metadata_entry_t aeModeResultEntry;
    aeModeResultEntry.tag = ACAMERA_CONTROL_AE_MODE;
    aeModeResultEntry.count = 1;
    aeModeResultEntry.data.u8 = &aeMode;
    setupMockCaptureResult({aeModeResultEntry});

    // 3. Set up the mock HalCamera to be in the RUNNING state
    mTestHalCameras[0]->mStreamState = CompatHalCamera::RUNNING;
    mTestHalCameras[0]->mSession = dummySession;
    mTestHalCameras[0]->mCaptureRequest = dummyCaptureRequest;

    // 4. Set up the mock stream and expect the notification
    auto mockStream = ::ndk::SharedRefBase::make<MockEvsCameraStream>();
    mVirtualCamera->mStream = mockStream;
    aidlevs::EvsEventDesc expectedEvent;
    expectedEvent.aType = aidlevs::EvsEventType::PARAMETER_CHANGED;
    expectedEvent.deviceId = "testCam";
    EXPECT_CALL(*mockStream, notify(expectedEvent)).Times(1);

    // 5. Expect the NDK call to update the request
    int32_t valueToSet = 3;
    EXPECT_CALL(mMockNdkCamera,
                ACaptureRequest_setEntry_i32(dummyCaptureRequest,
                                             ACAMERA_CONTROL_AE_EXPOSURE_COMPENSATION, 1,
                                             ::testing::Pointee(valueToSet)))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // 6. Call setIntParameter
    std::vector<int32_t> result;
    ndk::ScopedAStatus status =
            mVirtualCamera->setIntParameter(aidlevs::CameraParam::BRIGHTNESS, valueToSet, &result);

    // 7. Verify success
    ASSERT_TRUE(status.isOk());
}

TEST_F(CompatVirtualCameraTest, pauseVideoStream_StreamNotRunning) {
    // Stream is STOPPED by default
    ndk::ScopedAStatus status = mVirtualCamera->pauseVideoStream();
    ASSERT_TRUE(status.isOk());
    // Verify that the stream state remains STOPPED
    std::lock_guard lock(mVirtualCamera->mMutex);
    EXPECT_EQ(mVirtualCamera->mStreamState, CompatVirtualCamera::STOPPED);
}

TEST_F(CompatVirtualCameraTest, pauseVideoStream_Success) {
    // Set the stream state to RUNNING
    {
        std::lock_guard lock(mVirtualCamera->mMutex);
        mVirtualCamera->mStreamState = CompatVirtualCamera::RUNNING;
    }

    // Mock the pauseStream call on the HAL camera
    // Since mMockHalCamera is a real object, we can't use EXPECT_CALL directly.
    // Instead, we'll check the state of the virtual camera.
    ndk::ScopedAStatus status = mVirtualCamera->pauseVideoStream();
    ASSERT_TRUE(status.isOk());
}

TEST_F(CompatVirtualCameraTest, resumeVideoStream_StreamNotPaused) {
    // Stream is STOPPED by default
    ndk::ScopedAStatus status = mVirtualCamera->resumeVideoStream();
    ASSERT_TRUE(status.isOk());
    // Verify that the stream state remains STOPPED
    std::lock_guard lock(mVirtualCamera->mMutex);
    EXPECT_EQ(mVirtualCamera->mStreamState, CompatVirtualCamera::STOPPED);
}

TEST_F(CompatVirtualCameraTest, resumeVideoStream_Success) {
    // Set the stream state to RUNNING
    {
        std::lock_guard lock(mVirtualCamera->mMutex);
        mVirtualCamera->mStreamState = CompatVirtualCamera::RUNNING;
    }

    // Mock the resumeStream call on the HAL camera
    ndk::ScopedAStatus status = mVirtualCamera->resumeVideoStream();
    ASSERT_TRUE(status.isOk());
}

}  // namespace android::hardware::automotive::evs::compat
