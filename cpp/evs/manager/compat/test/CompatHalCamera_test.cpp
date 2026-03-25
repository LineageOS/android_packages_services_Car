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
#include "DummyNdkObjects.h"
#include "MockCameraManager.h"
#include "MockNdkCamera.h"

#include <aidl/android/hardware/automotive/evs/Stream.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

using ::testing::_;
using ::testing::Invoke;
using ::testing::Return;
using ::testing::SetArgPointee;

namespace android::hardware::automotive::evs::compat {

class MockVirtualCamera : public CompatVirtualCamera {
public:
    explicit MockVirtualCamera(const std::vector<std::shared_ptr<CompatHalCamera>>& halCameras) :
          CompatVirtualCamera(halCameras) {}

    MOCK_METHOD(bool, deliverFrame, (const aidlevs::BufferDesc&), (override));
    MOCK_METHOD(bool, isStreaming, (), (const, override));
    MOCK_METHOD(bool, notify, (const aidlevs::EvsEventDesc&), (override));
};

class CompatHalCameraTest : public ::testing::Test {
protected:
    void SetUp() override {
        auto mockCameraManager = std::make_unique<MockCameraManager>();
        mMockCameraManager = mockCameraManager.get();

        // Create a mock CompatHalCamera
        ACameraDevice* dummyDevice = reinterpret_cast<ACameraDevice*>(0x12345678);
        EXPECT_CALL(*mMockCameraManager, openSharedCamera(_, _))
                .WillOnce(testing::DoAll(SetArgPointee<1>(dummyDevice), Return(ACAMERA_OK)));

        ACameraDevice* device = nullptr;
        mMockCameraManager->openSharedCamera("mockCam0", &device);

        aidlevs::Stream streamConfig;
        mHalCamera =
                ::ndk::SharedRefBase::make<CompatHalCamera>(device, "mockCam0", nullptr,
                                                            streamConfig, true, mMockCameraManager);

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
    std::shared_ptr<MockVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
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
    std::shared_ptr<MockVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    EXPECT_TRUE(mHalCamera->ownVirtualCamera(virtualCamera));
    EXPECT_EQ(mHalCamera->mVirtualCameras.size(), 1);

    mHalCamera->disownVirtualCamera(virtualCamera.get());
    EXPECT_EQ(mHalCamera->mVirtualCameras.size(), 0);
}

TEST_F(CompatHalCameraTest, disownVirtualCamera_NotOwnedCamera) {
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<MockVirtualCamera> virtualCamera1 =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    EXPECT_TRUE(mHalCamera->ownVirtualCamera(virtualCamera1));
    EXPECT_EQ(mHalCamera->mVirtualCameras.size(), 1);

    std::shared_ptr<MockVirtualCamera> virtualCamera2 =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    mHalCamera->disownVirtualCamera(virtualCamera2.get());
    EXPECT_EQ(mHalCamera->mVirtualCameras.size(), 1);
}

TEST_F(CompatHalCameraTest, Notify_Success) {
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<MockVirtualCamera> virtualCamera1 =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    std::shared_ptr<MockVirtualCamera> virtualCamera2 =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    mHalCamera->ownVirtualCamera(virtualCamera1);
    mHalCamera->ownVirtualCamera(virtualCamera2);

    aidlevs::EvsEventDesc event;
    event.aType = aidlevs::EvsEventType::STREAM_STARTED;

    EXPECT_CALL(*virtualCamera1, notify(event)).Times(1);
    EXPECT_CALL(*virtualCamera2, notify(event)).Times(1);

    mHalCamera->notify(event);
}

TEST_F(CompatHalCameraTest, CaptureError_NotifiesStreamError) {
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<MockVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    mHalCamera->ownVirtualCamera(virtualCamera);

    aidlevs::EvsEventDesc event;
    event.aType = aidlevs::EvsEventType::STREAM_ERROR;
    event.deviceId = mHalCamera->getId();

    EXPECT_CALL(*virtualCamera, notify(event)).Times(1);

    ACameraCaptureFailure failure;
    CompatHalCamera::onCaptureFailed(mHalCamera.get(), dummySession, dummyCaptureRequest, &failure);
}

TEST_F(CompatHalCameraTest, BufferLost_NotifiesStreamError) {
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<MockVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    mHalCamera->ownVirtualCamera(virtualCamera);

    aidlevs::EvsEventDesc event;
    event.aType = aidlevs::EvsEventType::FRAME_DROPPED;
    event.deviceId = mHalCamera->getId();

    EXPECT_CALL(*virtualCamera, notify(event)).Times(1);

    CompatHalCamera::onCaptureBufferLost(mHalCamera.get(), dummySession, dummyCaptureRequest,
                                         dummyWindow, 0);
}

TEST_F(CompatHalCameraTest, clientStreamStarting_Success) {
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<MockVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    mHalCamera->ownVirtualCamera(virtualCamera);

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
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
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

    // Call cleanUpNdkStreamResources to trigger the mocked clean up functions
    mHalCamera->cleanUpNdkStreamResources();
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

TEST_F(CompatHalCameraTest, clientStreamStarting_PrimaryClient) {
    mHalCamera->setPrimaryClient(true);

    // Common NDK setup calls
    EXPECT_CALL(mMockNdkCamera, AImageReader_newWithUsage(_, _, _, _, _, _))
            .WillOnce(Invoke([](int32_t, int32_t, int32_t, uint64_t, int32_t,
                                AImageReader** reader) -> media_status_t {
                *reader = dummyReader;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AImageReader_getWindow(_, _))
            .WillOnce(Invoke([](AImageReader*, ANativeWindow** window) -> media_status_t {
                *window = dummyWindow;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AImageReader_setImageListener(_, _)).WillOnce(Return(AMEDIA_OK));
    EXPECT_CALL(mMockNdkCamera, ACameraOutputTarget_create(_, _))
            .WillOnce(Invoke([](ANativeWindow*, ACameraOutputTarget** outputTarget) {
                *outputTarget = dummyOutputTarget;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutput_create(_, _))
            .WillOnce(Invoke([](ANativeWindow*, ACaptureSessionOutput** output) {
                *output = dummySessionOutput;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutputContainer_create(_))
            .WillOnce(Invoke([](ACaptureSessionOutputContainer** container) {
                *container = dummyOutputContainer;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutputContainer_add(_, _))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera, ACameraDevice_createCaptureRequest(_, _, _))
            .WillOnce(Invoke([](const ACameraDevice*, ACameraDevice_request_template,
                                ACaptureRequest** request) {
                *request = dummyCaptureRequest;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureRequest_addTarget(_, _)).WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera, ACameraDevice_createCaptureSession(_, _, _, _))
            .WillOnce(Invoke([](ACameraDevice*, const ACaptureSessionOutputContainer*,
                                const ACameraCaptureSession_stateCallbacks*,
                                ACameraCaptureSession** session) {
                *session = dummySession;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_setRepeatingRequestV2(_, _, _, _, _))
            .Times(1);

    // Non-primary client specific calls - SHOULD NOT be called
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSessionShared_startStreaming(_, _, _, _, _)).Times(0);

    mHalCamera->clientStreamStarting();
}

TEST_F(CompatHalCameraTest, clientStreamStarting_SecondaryClient) {
    mHalCamera->setPrimaryClient(false);

    // Common NDK setup calls
    EXPECT_CALL(mMockNdkCamera, AImageReader_newWithUsage(_, _, _, _, _, _))
            .WillOnce(Invoke([](int32_t, int32_t, int32_t, uint64_t, int32_t,
                                AImageReader** reader) -> media_status_t {
                *reader = dummyReader;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AImageReader_getWindow(_, _))
            .WillOnce(Invoke([](AImageReader*, ANativeWindow** window) -> media_status_t {
                *window = dummyWindow;
                return AMEDIA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, AImageReader_setImageListener(_, _)).WillOnce(Return(AMEDIA_OK));
    EXPECT_CALL(mMockNdkCamera, ACameraOutputTarget_create(_, _))
            .WillOnce(Invoke([](ANativeWindow*, ACameraOutputTarget** outputTarget) {
                *outputTarget = dummyOutputTarget;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutput_create(_, _))
            .WillOnce(Invoke([](ANativeWindow*, ACaptureSessionOutput** output) {
                *output = dummySessionOutput;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutputContainer_create(_))
            .WillOnce(Invoke([](ACaptureSessionOutputContainer** container) {
                *container = dummyOutputContainer;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutputContainer_add(_, _))
            .WillOnce(Return(ACAMERA_OK));

    // Non-primary client specific calls
    EXPECT_CALL(mMockNdkCamera, ACameraDevice_createCaptureSession(_, _, _, _))
            .WillOnce(Invoke([](ACameraDevice*, const ACaptureSessionOutputContainer*,
                                const ACameraCaptureSession_stateCallbacks*,
                                ACameraCaptureSession** session) {
                *session = dummySession;
                return ACAMERA_OK;
            }));
    EXPECT_CALL(*mMockCameraManager, getCaptureSessionSharedStartStreamingFn())
            .WillOnce(Return(&ACameraCaptureSessionShared_startStreaming));

    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSessionShared_startStreaming(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera, ACameraDevice_createCaptureRequest(_, _, _)).Times(0);
    EXPECT_CALL(mMockNdkCamera, ACaptureRequest_addTarget(_, _)).Times(0);
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_setRepeatingRequestV2(_, _, _, _, _))
            .Times(0);

    mHalCamera->clientStreamStarting();
}

TEST_F(CompatHalCameraTest, cleanUpNdkStreamResources_PrimaryClient) {
    aidlevs::Stream streamConfig;
    mHalCamera = ::ndk::SharedRefBase::make<CompatHalCamera>(dummyDevice, "mockCam0", nullptr,
                                                             streamConfig, /*isPrimary=*/true,
                                                             mMockCameraManager);

    // Set up dummy NDK objects to be "cleaned up"
    mHalCamera->mImageReader = dummyReader;
    mHalCamera->mWindow = dummyWindow;
    mHalCamera->mOutputTarget = dummyOutputTarget;
    mHalCamera->mSessionOutput = dummySessionOutput;
    mHalCamera->mOutputs = dummyOutputContainer;
    mHalCamera->mSession = dummySession;
    mHalCamera->mCaptureRequest = dummyCaptureRequest;

    // Expect clean up calls for primary client
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_stopRepeating(dummySession)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_close(dummySession)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureRequest_free(dummyCaptureRequest)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutputContainer_free(dummyOutputContainer)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutput_free(dummySessionOutput)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACameraOutputTarget_free(dummyOutputTarget)).Times(1);
    EXPECT_CALL(mMockNdkCamera, AImageReader_delete(dummyReader)).Times(1);

    // Expect NO calls for secondary client cleanup
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSessionShared_stopStreaming(dummySession)).Times(0);

    mHalCamera->cleanUpNdkStreamResources();
}

TEST_F(CompatHalCameraTest, cleanUpNdkStreamResources_SecondaryClient) {
    // Recreate HalCamera as a secondary client
    mHalCamera->setPrimaryClient(false);

    // Set up dummy NDK objects to be "cleaned up"
    mHalCamera->mImageReader = dummyReader;
    mHalCamera->mWindow = dummyWindow;
    mHalCamera->mOutputTarget = dummyOutputTarget;
    mHalCamera->mSessionOutput = dummySessionOutput;
    mHalCamera->mOutputs = dummyOutputContainer;
    mHalCamera->mSession = dummySession;
    mHalCamera->mCaptureRequest = dummyCaptureRequest;

    // Expect clean up calls for secondary client
    EXPECT_CALL(*mMockCameraManager, getCaptureSessionSharedStopStreamingFn())
            .WillOnce(Return(&ACameraCaptureSessionShared_stopStreaming));
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_close(dummySession)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureRequest_free(dummyCaptureRequest)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutputContainer_free(dummyOutputContainer)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutput_free(dummySessionOutput)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACameraOutputTarget_free(dummyOutputTarget)).Times(1);
    EXPECT_CALL(mMockNdkCamera, AImageReader_delete(dummyReader)).Times(1);

    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSessionShared_stopStreaming(dummySession))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_stopRepeating(dummySession)).Times(0);

    mHalCamera->cleanUpNdkStreamResources();
}

TEST_F(CompatHalCameraTest, deliverFrame_EmptyBuffer) {
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<MockVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    EXPECT_TRUE(mHalCamera->ownVirtualCamera(virtualCamera));

    EXPECT_CALL(*virtualCamera, deliverFrame(_)).Times(0);

    std::vector<aidlevs::BufferDesc> buffers;
    ::ndk::ScopedAStatus status = mHalCamera->deliverFrame(buffers);
    EXPECT_TRUE(status.isOk());
}

TEST_F(CompatHalCameraTest, deliverFrame_NonEmptyBuffer) {
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<MockVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    EXPECT_TRUE(mHalCamera->ownVirtualCamera(virtualCamera));

    // Add a frame request
    mHalCamera->mNextRequests.push_back({virtualCamera, 0});

    EXPECT_CALL(*virtualCamera, deliverFrame(_)).Times(1).WillOnce(Return(true));

    std::vector<aidlevs::BufferDesc> buffers;
    aidlevs::BufferDesc buffer;
    buffer.bufferId = 123;
    buffer.timestamp = 20000;
    buffers.emplace_back(std::move(buffer));
    ::ndk::ScopedAStatus status = mHalCamera->deliverFrame(buffers);
    EXPECT_TRUE(status.isOk());
}

TEST_F(CompatHalCameraTest, doneWithFrame_InvalidBufferId) {
    aidlevs::BufferDesc buffer;
    buffer.bufferId = 999;  // Invalid buffer ID
    // Expect AImage_delete NOT to be called for this unknown bufferId
    EXPECT_CALL(mMockNdkCamera, AImage_delete(_)).Times(0);

    ::ndk::ScopedAStatus status = mHalCamera->doneWithFrame(std::move(buffer));
    EXPECT_TRUE(status.isOk());
}

TEST_F(CompatHalCameraTest, doneWithFrame_ValidBufferId) {
    const uint32_t bufferId = 123;
    AImage* dummyImage = reinterpret_cast<AImage*>(0x9999);

    {
        std::lock_guard lock(mHalCamera->mMutex);
        // Simulate that a frame was delivered and is being tracked
        mHalCamera->mFrameRecords.emplace_back(bufferId, 1);
        mHalCamera->mLiveImages[bufferId] = dummyImage;
    }

    // Expect AImage_delete to be called when the last reference is released
    EXPECT_CALL(mMockNdkCamera, AImage_delete(dummyImage)).Times(1);

    aidlevs::BufferDesc buffer;
    buffer.bufferId = bufferId;
    ::ndk::ScopedAStatus status = mHalCamera->doneWithFrame(std::move(buffer));
    EXPECT_TRUE(status.isOk());

    // Verify that the buffer is removed from mLiveImages and mFrameRecords
    {
        std::lock_guard lock(mHalCamera->mMutex);
        EXPECT_EQ(mHalCamera->mLiveImages.find(bufferId), mHalCamera->mLiveImages.end());
        auto it = std::find_if(mHalCamera->mFrameRecords.begin(), mHalCamera->mFrameRecords.end(),
                               [bufferId](const auto& rec) { return rec.frameId == bufferId; });
        EXPECT_TRUE(it == mHalCamera->mFrameRecords.end() || it->refCount == 0);
    }
}

TEST_F(CompatHalCameraTest, clientStreamEnding_NotRunning) {
    // Ensure stream is not RUNNING
    {
        std::lock_guard<std::mutex> lock(mHalCamera->mMutex);
        mHalCamera->mStreamState = CompatHalCamera::STOPPED;
    }

    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<MockVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);

    // Expect no cleanup calls
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_stopRepeating(_)).Times(0);
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_close(_)).Times(0);

    mHalCamera->clientStreamEnding(virtualCamera.get());
    EXPECT_EQ(mHalCamera->mStreamState, CompatHalCamera::STOPPED);
}

TEST_F(CompatHalCameraTest, clientStreamEnding_OneClientStops) {
    // Set state to RUNNING
    {
        std::lock_guard<std::mutex> lock(mHalCamera->mMutex);
        mHalCamera->mStreamState = CompatHalCamera::RUNNING;
    }

    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<MockVirtualCamera> virtualCamera =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    EXPECT_TRUE(mHalCamera->ownVirtualCamera(virtualCamera));

    // Mock the virtual camera to return false for isStreaming
    EXPECT_CALL(*virtualCamera, isStreaming()).WillOnce(Return(false));

    // Set up dummy NDK objects to be "cleaned up"
    mHalCamera->mImageReader = dummyReader;
    mHalCamera->mWindow = dummyWindow;
    mHalCamera->mOutputTarget = dummyOutputTarget;
    mHalCamera->mSessionOutput = dummySessionOutput;
    mHalCamera->mOutputs = dummyOutputContainer;
    mHalCamera->mSession = dummySession;
    mHalCamera->mCaptureRequest = dummyCaptureRequest;

    // Expect clean up calls
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_stopRepeating(dummySession)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_close(dummySession)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureRequest_free(dummyCaptureRequest)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutputContainer_free(dummyOutputContainer)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACaptureSessionOutput_free(dummySessionOutput)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACameraOutputTarget_free(dummyOutputTarget)).Times(1);
    EXPECT_CALL(mMockNdkCamera, AImageReader_delete(dummyReader)).Times(1);

    mHalCamera->clientStreamEnding(virtualCamera.get());
    EXPECT_EQ(mHalCamera->mStreamState, CompatHalCamera::STOPPING);

    // Simulate onSessionClosed callback
    mHalCamera->onSessionClosed(mHalCamera.get(), dummySession);
    EXPECT_EQ(mHalCamera->mStreamState, CompatHalCamera::STOPPED);
}

TEST_F(CompatHalCameraTest, clientStreamEnding_ClientStopsWithOthersRunning) {
    // Set state to RUNNING
    {
        std::lock_guard<std::mutex> lock(mHalCamera->mMutex);
        mHalCamera->mStreamState = CompatHalCamera::RUNNING;
    }

    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    halCameras.push_back(mHalCamera);
    std::shared_ptr<MockVirtualCamera> virtualCamera1 =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    std::shared_ptr<MockVirtualCamera> virtualCamera2 =
            ::ndk::SharedRefBase::make<MockVirtualCamera>(halCameras);
    EXPECT_TRUE(mHalCamera->ownVirtualCamera(virtualCamera1));
    EXPECT_TRUE(mHalCamera->ownVirtualCamera(virtualCamera2));

    // virtualCamera1 stops, virtualCamera2 is still running
    EXPECT_CALL(*virtualCamera1, isStreaming()).WillOnce(Return(false));
    EXPECT_CALL(*virtualCamera2, isStreaming()).WillOnce(Return(true));

    // Expect no cleanup calls as one client is still running
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_stopRepeating(_)).Times(0);
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_close(_)).Times(0);

    mHalCamera->clientStreamEnding(virtualCamera1.get());
    EXPECT_EQ(mHalCamera->mStreamState, CompatHalCamera::RUNNING);
}

TEST_F(CompatHalCameraTest, MetadataHandling) {
    auto* metadata1 = reinterpret_cast<ACameraMetadata*>(0x1111);
    auto* metadata1_copy = reinterpret_cast<ACameraMetadata*>(0x1112);
    auto* metadata2 = reinterpret_cast<ACameraMetadata*>(0x2222);
    auto* metadata2_copy = reinterpret_cast<ACameraMetadata*>(0x2223);

    // 1. First capture completion
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadata1)).WillOnce(Return(metadata1_copy));
    mHalCamera->handleCaptureCompleted(metadata1);

    // 2. Verify getLatestMetadata returns the copied metadata
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadata1_copy))
            .WillOnce(Return(metadata1_copy));
    ACameraMetadata* retrievedMetadata = mHalCamera->getLatestMetadata();
    EXPECT_EQ(retrievedMetadata, metadata1_copy);

    // 3. Second capture completion, expect the old metadata to be freed
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadata1_copy)).Times(1);
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadata2)).WillOnce(Return(metadata2_copy));
    mHalCamera->handleCaptureCompleted(metadata2);

    // 4. Verify getLatestMetadata returns the new metadata
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_copy(metadata2_copy))
            .WillOnce(Return(metadata2_copy));
    retrievedMetadata = mHalCamera->getLatestMetadata();
    EXPECT_EQ(retrievedMetadata, metadata2_copy);

    // 5. Verify metadata is freed on destruction
    EXPECT_CALL(mMockNdkCamera, ACameraMetadata_free(metadata2_copy)).Times(1);
    mHalCamera.reset();
}

TEST_F(CompatHalCameraTest, updateRequest_Success) {
    // Set state to RUNNING and create a dummy session and request
    {
        std::lock_guard<std::mutex> lock(mHalCamera->mMutex);
        mHalCamera->mStreamState = CompatHalCamera::RUNNING;
        mHalCamera->mSession = dummySession;
        mHalCamera->mCaptureRequest = dummyCaptureRequest;
    }

    // Create dummy metadata with a setting
    camera_metadata_t* rawMetadata = allocate_camera_metadata(1, 1);
    ASSERT_NE(rawMetadata, nullptr);
    uint8_t aeMode = ACAMERA_CONTROL_AE_MODE_ON;
    add_camera_metadata_entry(rawMetadata, ACAMERA_CONTROL_AE_MODE, &aeMode, 1);
    ACameraMetadata* settings = reinterpret_cast<ACameraMetadata*>(rawMetadata);

    // Mock NDK calls
    uint32_t tag = ACAMERA_CONTROL_AE_MODE;
    ACameraMetadata_const_entry entry;
    entry.tag = tag;
    entry.type = ACAMERA_TYPE_BYTE;
    entry.count = 1;
    entry.data.u8 = &aeMode;

    EXPECT_CALL(mMockNdkCamera, ACaptureRequest_setEntry_u8(dummyCaptureRequest, tag, 1, &aeMode))
            .WillOnce(Return(ACAMERA_OK));
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .WillOnce(Return(ACAMERA_OK));

    // Call the method under test
    ::ndk::ScopedAStatus status = mHalCamera->updateRequest(entry);

    // Verify
    EXPECT_TRUE(status.isOk());

    // Clean up
    free_camera_metadata(rawMetadata);
}

TEST_F(CompatHalCameraTest, pauseStream_StreamNotRunning) {
    // Stream is STOPPED by default
    ::ndk::ScopedAStatus status = mHalCamera->pauseStream();
    ASSERT_TRUE(status.isOk());
    // Verify that the stream state remains STOPPED
    EXPECT_EQ(mHalCamera->mStreamState, CompatHalCamera::STOPPED);
}

TEST_F(CompatHalCameraTest, pauseStream_Success) {
    // Set the stream state to RUNNING
    {
        std::lock_guard<std::mutex> lock(mHalCamera->mMutex);
        mHalCamera->mStreamState = CompatHalCamera::RUNNING;
    }

    // Mock the NDK call to stop the repeating request
    mHalCamera->mSession = dummySession;
    EXPECT_CALL(mMockNdkCamera, ACameraCaptureSession_stopRepeating(dummySession)).Times(1);

    ::ndk::ScopedAStatus status = mHalCamera->pauseStream();
    ASSERT_TRUE(status.isOk());
}

TEST_F(CompatHalCameraTest, resumeStream_StreamNotPaused) {
    // Stream is STOPPED by default
    ::ndk::ScopedAStatus status = mHalCamera->resumeStream();
    ASSERT_TRUE(status.isOk());
    // Verify that the stream state remains STOPPED
    EXPECT_EQ(mHalCamera->mStreamState, CompatHalCamera::STOPPED);
}

TEST_F(CompatHalCameraTest, resumeStream_Success) {
    // Set the stream state to RUNNING
    {
        std::lock_guard<std::mutex> lock(mHalCamera->mMutex);
        mHalCamera->mStreamState = CompatHalCamera::RUNNING;
    }

    // Mock the NDK call to resume the repeating request
    mHalCamera->mSession = dummySession;
    EXPECT_CALL(mMockNdkCamera,
                ACameraCaptureSession_setRepeatingRequestV2(dummySession, _, 1, _, _))
            .Times(1);

    ::ndk::ScopedAStatus status = mHalCamera->resumeStream();
    ASSERT_TRUE(status.isOk());
}

}  // namespace android::hardware::automotive::evs::compat
