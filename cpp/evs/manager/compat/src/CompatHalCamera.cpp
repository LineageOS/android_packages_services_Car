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

#include "Converter.h"

#include <android-base/logging.h>
#include <android/hardware_buffer.h>
#include <private/android/AHardwareBufferHelpers.h>

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::BufferDesc;
using ::aidl::android::hardware::automotive::evs::EvsEventDesc;
using ::ndk::ScopedAStatus;

// Static callback function for AImageReader. The function retrieves the image and converts it
// to BufferDesc, then calls CompatHalCamera::deliverFrame. To convert the image to BufferDesc, the
// function uses the buffer ID from the CompatHalCamera's buffer ID map. If the buffer ID is not
// found, the function generates a new buffer ID and adds it to the map. This is because apps
// performs computationally intensive calculations on the initial buffer received from a specific
// hardware buffer. These calculations may involve lens undistortion to correct the "fisheye" effect
// of wide-angle camera lenses, perspective transformation to warp the image for a top-down view,
// and mapping to a 2D model to determine pixel correspondence within the 3D bowl view. Since these
// calculations depend on the camera's properties and remain consistent for every frame from that
// camera, apps can cache the results of these calculations for each buffer ID.
void CompatHalCamera::onImageAvailable(void* context, AImageReader* reader) {
    if (!context) {
        LOG(ERROR) << "Context is null in onImageAvailable";
        return;
    }
    CompatHalCamera* halCamera = static_cast<CompatHalCamera*>(context);
    if (!halCamera) {
        LOG(ERROR) << "CompatHalCamera context is null";
        return;
    }

    AImage* image = nullptr;
    media_status_t status = AImageReader_acquireLatestImage(reader, &image);
    if (status != AMEDIA_OK || !image) {
        // The onImageAvailable callback should only be triggered when a new image is ready.
        // However, AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE indicates no new buffer was available,
        // which can happen in some edge cases. We don't treat this as an error to avoid log spam.
        if (status != AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) {
            LOG(ERROR) << "Failed to acquire image, status: " << status;
        }
        return;
    }

    AHardwareBuffer* hardwareBuffer = nullptr;
    status = AImage_getHardwareBuffer(image, &hardwareBuffer);
    if (status != AMEDIA_OK || !hardwareBuffer) {
        LOG(ERROR) << "Failed to get HardwareBuffer from AImage, status: " << status;
        AImage_delete(image);
        return;
    }

    BufferDesc bufferDesc;
    {
        std::lock_guard lock(halCamera->mMutex);
        // Get the buffer address of the hardware buffer.
        uint64_t bufferAddr = reinterpret_cast<uint64_t>(hardwareBuffer);
        uint32_t bufferId = 0;
        auto it = halCamera->mBufferIdMap.find(bufferAddr);
        if (it != halCamera->mBufferIdMap.end()) {
            bufferId = it->second;
        } else {
            bufferId = halCamera->mBufferIdMap.size();
            halCamera->mBufferIdMap[bufferAddr] = bufferId;
        }
        status = Converter::toBufferDesc(image, bufferId, halCamera->getId(), bufferDesc);
        if (status != AMEDIA_OK) {
            LOG(ERROR) << "Failed to convert AImage to BufferDesc, status: " << status;
            AImage_delete(image);
            return;
        }
        halCamera->mLiveImages[bufferId] = image;
    }

    LOG(DEBUG) << "Image available to deliver to deliverFrame";
    std::vector<aidlevs::BufferDesc> frameVec;
    frameVec.emplace_back(std::move(bufferDesc));
    halCamera->deliverFrame(frameVec);
}

// Static callback functions for ACameraCaptureSession
static void onSessionActive(void* context, ACameraCaptureSession* session) {
    LOG(INFO) << "NDK Camera session active";
}
static void onSessionReady(void* context, ACameraCaptureSession* session) {
    LOG(INFO) << "NDK Camera session ready";
}
void CompatHalCamera::onSessionClosed(void* context, ACameraCaptureSession* session) {
    LOG(INFO) << "NDK Camera session closed";
    if (!context) {
        LOG(ERROR) << "Context is null in onSessionClosed";
        return;
    }
    CompatHalCamera* halCamera = static_cast<CompatHalCamera*>(context);
    if (!halCamera) {
        LOG(ERROR) << "CompatHalCamera context is null";
        return;
    }
    std::lock_guard<std::mutex> lock(halCamera->mMutex);
    halCamera->mSessionClosed = true;
    halCamera->mStreamState = STOPPED;
    halCamera->mSessionCondVar.notify_all();
}

// Static callback functions for ACameraCaptureSession_captureCallbacks
static void onCaptureStarted(void* context, ACameraCaptureSession* session,
                             const ACaptureRequest* request, int64_t timestamp) {
    LOG(DEBUG) << "NDK Camera capture started";
}
static void onCaptureProgressed(void* context, ACameraCaptureSession* session,
                                ACaptureRequest* request, const ACameraMetadata* result) {
    LOG(DEBUG) << "NDK Camera capture progressed";
}

void CompatHalCamera::handleCaptureCompleted(const ACameraMetadata* result) {
    std::lock_guard lock(mMetadataLock);
    if (mLatestMetadata) {
        ACameraMetadata_free(mLatestMetadata);
    }
    mLatestMetadata = ACameraMetadata_copy(result);
}

void CompatHalCamera::onCaptureCompleted(void* context, ACameraCaptureSession* session,
                                         ACaptureRequest* request, const ACameraMetadata* result) {
    LOG(DEBUG) << "NDK Camera capture completed";
    if (!context) {
        LOG(ERROR) << "Context is null in onCaptureCompleted";
        return;
    }
    CompatHalCamera* halCamera = static_cast<CompatHalCamera*>(context);
    if (!halCamera) {
        LOG(ERROR) << "CompatHalCamera context is null";
        return;
    }
    halCamera->handleCaptureCompleted(result);
}

static void onCaptureFailed(void* context, ACameraCaptureSession* session, ACaptureRequest* request,
                            ACameraCaptureFailure* failure) {
    LOG(ERROR) << "NDK Camera capture failed";
}
static void onCaptureBufferLost(void* context, ACameraCaptureSession* session,
                                ACaptureRequest* request, ANativeWindow* window,
                                int64_t frameNumber) {
    LOG(ERROR) << "NDK Camera capture buffer lost";
}

CompatHalCamera::CompatHalCamera(ACameraDevice* device, const std::string& cameraId,
                                 const aidlevs::CameraDesc* desc,
                                 const aidlevs::Stream& streamConfig) :
      mDevice(device), mCameraId(cameraId), mStreamConfig(streamConfig) {
    if (desc) {
        mCameraDesc = *desc;
    }
}

CompatHalCamera::~CompatHalCamera() {
    // Destructor stub
    std::lock_guard lock(mMutex);
    for (auto const& [key, val] : mLiveImages) {
        if (val) {
            AImage_delete(val);
        }
    }
    mLiveImages.clear();
    if (mLatestMetadata) {
        ACameraMetadata_free(mLatestMetadata);
        mLatestMetadata = nullptr;
    }
}

void CompatHalCamera::requestNewFrame(std::shared_ptr<CompatVirtualCamera> client,
                                      int64_t lastTimestamp) {
    FrameRequest req;
    req.client = client;
    req.timestamp = lastTimestamp;

    std::lock_guard<std::mutex> lock(mMutex);
    mNextRequests.push_back(req);
}

ScopedAStatus CompatHalCamera::deliverFrame(const std::vector<BufferDesc>& buffers) {
    LOG(DEBUG) << "Received a frame on " << mCameraId;

    if (buffers.empty()) {
        LOG(WARNING) << "deliverFrame called with no buffers on " << mCameraId;
        return ScopedAStatus::ok();
    }

    const auto timestamp = buffers[0].timestamp;
    const uint32_t bufferId = buffers[0].bufferId;
    // TODO(b/145750636): For now, we are using a approximately half of 1 seconds / 30 frames = 33ms
    //           but this must be derived from current framerate.
    constexpr int64_t kThreshold = 16'000;  // ms
    unsigned frameDeliveries = 0;
    std::deque<FrameRequest> currentRequests;
    std::deque<FrameRequest> puntedRequests;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        currentRequests.insert(currentRequests.end(),
                               std::make_move_iterator(mNextRequests.begin()),
                               std::make_move_iterator(mNextRequests.end()));
        mNextRequests.clear();
        mFrameOpInProgress = true;
    }

    while (!currentRequests.empty()) {
        auto req = currentRequests.front();
        currentRequests.pop_front();
        std::shared_ptr<CompatVirtualCamera> vCam = req.client.lock();
        if (!vCam) {
            // Ignore a client already dead.
            continue;
        }

        if (timestamp - req.timestamp < kThreshold) {
            // Skip current frame because it arrives too soon.
            LOG(DEBUG) << "Skips a frame from " << getId();
            puntedRequests.push_back(req);
            continue;
        }

        if (!vCam->deliverFrame(buffers[0])) {
            LOG(WARNING) << getId() << " failed to forward the buffer to " << vCam.get();
        } else {
            LOG(DEBUG) << getId() << " forwarded the buffer #" << bufferId << " to " << vCam.get()
                       << " from " << this;
            ++frameDeliveries;
        }
    }

    if (frameDeliveries < 1) {
        // If none of our clients could accept the frame, then return it
        // right away.
        LOG(INFO) << "Trivially rejecting frame (" << bufferId << ") from " << getId()
                  << " with no acceptance";
        AImage* imageToDel = nullptr;
        {
            std::lock_guard lock(mMutex);
            auto it = mLiveImages.find(bufferId);
            if (it != mLiveImages.end()) {
                imageToDel = it->second;
                mLiveImages.erase(it);
            } else {
                LOG(WARNING) << "Buffer ID " << bufferId
                             << " not found in mLiveImages for trivial rejection.";
            }
        }
        if (imageToDel) {
            AImage_delete(imageToDel);
        }

        // Adding skipped capture requests back to the queue.
        std::lock_guard<std::mutex> lock(mMutex);
        mNextRequests.insert(mNextRequests.end(), std::make_move_iterator(puntedRequests.begin()),
                             std::make_move_iterator(puntedRequests.end()));
        mFrameOpInProgress = false;
        mFrameOpDone.notify_all();
    } else {
        std::lock_guard lock(mMutex);

        // Add an entry for this frame in our tracking list.
        unsigned i;
        for (i = 0; i < mFrameRecords.size(); ++i) {
            if (mFrameRecords[i].refCount == 0) {
                break;
            }
        }

        if (i == mFrameRecords.size()) {
            mFrameRecords.emplace_back(bufferId, frameDeliveries);
        } else {
            mFrameRecords[i].frameId = bufferId;
            mFrameRecords[i].refCount = frameDeliveries;
        }

        // Adding skipped capture requests back to the queue.
        mNextRequests.insert(mNextRequests.end(), std::make_move_iterator(puntedRequests.begin()),
                             std::make_move_iterator(puntedRequests.end()));
        mFrameOpInProgress = false;
        mFrameOpDone.notify_all();
    }

    return ScopedAStatus::ok();
}

ScopedAStatus CompatHalCamera::doneWithFrame(BufferDesc buffer) {
    std::unique_lock lock(mMutex);
    mFrameOpDone.wait(lock, [this]() REQUIRES(mMutex) { return mFrameOpInProgress != true; });

    const uint32_t bufferId = buffer.bufferId;
    // Find this frame in our list of outstanding frames
    auto it = std::find_if(mFrameRecords.begin(), mFrameRecords.end(),
                           [id = bufferId](const FrameRecord& rec) { return rec.frameId == id; });
    if (it == mFrameRecords.end()) {
        LOG(WARNING) << "We got a frame back with an ID we don't recognize: " << bufferId;
        return ScopedAStatus::ok();
    }

    if (it->refCount < 1) {
        LOG(WARNING) << "Buffer " << bufferId << " is returned with a zero reference counter.";
        return ScopedAStatus::ok();
    }

    // Are there still clients using this buffer?
    it->refCount = it->refCount - 1;
    if (it->refCount > 0) {
        LOG(DEBUG) << "Buffer " << bufferId << " is still being used by " << it->refCount
                   << " other client(s).";
        return ScopedAStatus::ok();
    }

    // Since all our clients are done with this buffer, we can release the buffer.
    LOG(DEBUG) << "All clients done with buffer " << bufferId << " for camera " << mCameraId;
    AImage* imageToDel = nullptr;
    {
        // mMutex is already locked by unique_lock
        auto liveIt = mLiveImages.find(bufferId);
        if (liveIt != mLiveImages.end()) {
            imageToDel = liveIt->second;
            mLiveImages.erase(liveIt);
        } else {
            LOG(WARNING) << "Buffer ID " << bufferId << " not found in mLiveImages for deletion.";
        }
    }
    if (imageToDel) {
        AImage_delete(imageToDel);
    }

    return ScopedAStatus::ok();
}

ScopedAStatus CompatHalCamera::notify([[maybe_unused]] const EvsEventDesc& event) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

bool CompatHalCamera::ownVirtualCamera(const std::shared_ptr<CompatVirtualCamera>& virtualCamera) {
    if (!virtualCamera) {
        LOG(ERROR) << "Virtual camera is null";
        return false;
    }
    std::lock_guard<std::mutex> lock(mMutex);
    mVirtualCameras.push_back(virtualCamera);
    return true;
}

void CompatHalCamera::disownVirtualCamera(const CompatVirtualCamera* virtualCamera) {
    if (!virtualCamera) {
        LOG(ERROR) << "Virtual camera is null";
        return;
    }

    std::lock_guard<std::mutex> lock(mMutex);
    size_t sizeBefore = mVirtualCameras.size();
    mVirtualCameras.remove_if(
            [virtualCamera](const std::weak_ptr<CompatVirtualCamera>& weakCurrentCam) {
                const auto currentCam = weakCurrentCam.lock();
                return currentCam == nullptr || currentCam.get() == virtualCamera;
            });

    if (mVirtualCameras.size() == sizeBefore) {
        LOG(WARNING) << "Virtual camera " << virtualCamera
                     << " not found in mVirtualCameras for camera " << mCameraId;
    }
}

ScopedAStatus CompatHalCamera::clientStreamStarting() {
    {
        std::lock_guard lock(mMutex);
        if (!mDevice) {
            LOG(ERROR) << "Camera device is not available for camera " << mCameraId;
            return ScopedAStatus::fromServiceSpecificError(
                    static_cast<int32_t>(aidlevs::EvsResult::RESOURCE_NOT_AVAILABLE));
        }
        if (mStreamState == RUNNING) {
            // This camera device is already active.
            return ScopedAStatus::ok();
        }

        if (mStreamState == STOPPED) {
            // Try to start a video stream.
            int32_t maxImages = 0;
            for (auto& client : mVirtualCameras) {
                auto virtualCamera = client.lock();
                if (!virtualCamera) {
                    continue;
                }
                maxImages += virtualCamera->getMaxFramesInFlight();
            }

            ScopedAStatus status = startNdkCameraStream(maxImages);
            if (status.isOk()) {
                mFrameRecords.resize(maxImages);
                mStreamState = RUNNING;
            }
            return status;
        }

        // We cannot start a video stream.
        return ScopedAStatus::fromServiceSpecificError(static_cast<int32_t>(
                mStreamState == STOPPING ? aidlevs::EvsResult::RESOURCE_BUSY
                                         : aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }
}

void CompatHalCamera::clientStreamEnding(const CompatVirtualCamera* virtualCamera) {
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mStreamState != RUNNING) {
            // We are being stopped or stopped already.
            return;
        }

        mNextRequests.erase(std::remove_if(mNextRequests.begin(), mNextRequests.end(),
                                           [virtualCamera](const auto& r) {
                                               return r.client.lock().get() == virtualCamera;
                                           }),
                            mNextRequests.end());
    }

    // Do we still have a running client?
    bool stillRunning = false;
    for (auto&& client : mVirtualCameras) {
        std::shared_ptr<CompatVirtualCamera> virtCam = client.lock();
        if (virtCam) {
            stillRunning |= virtCam->isStreaming();
        }
    }

    // If not, then stop the stream.
    if (!stillRunning) {
        {
            std::lock_guard lock(mMutex);
            mStreamState = STOPPING;
        }
        cleanUpNdkStreamResources();
        {
            std::lock_guard lock(mMutex);
            mBufferIdMap.clear();
        }
    }
}

void CompatHalCamera::cleanUpNdkStreamResources() {
    LOG(INFO) << "Cleaning up NDK stream resources for camera " << mCameraId;

    if (mCaptureRequest) {
        ACaptureRequest_free(mCaptureRequest);
        mCaptureRequest = nullptr;
    }
    if (mSession) {
        // Stop the repeating request first
        camera_status_t status = ACameraCaptureSession_stopRepeating(mSession);
        if (status != ACAMERA_OK) {
            LOG(ERROR) << "Failed to stop repeating request, status: " << status;
        }

        std::unique_lock<std::mutex> lock(mMutex);
        mSessionClosed = false;
        // ACameraCaptureSession_close initiates an asynchronous shutdown. Although the session
        // handle becomes unusable immediately, the full teardown (including completing any
        // in-progress captures) only finishes when the onSessionClosed callback is invoked.
        // multiple threads may interact with the camera. waiting for the onClosed callback
        // ensures that the session is fully closed and voids any race conditions where another
        // thread may still be trying to use a resource that has been destroyed.
        ACameraCaptureSession_close(mSession);

        // Wait for the onSessionClosed callback to signal that the session is closed.
        if (!mSessionClosed) {
            mSessionCondVar.wait_for(lock, std::chrono::seconds(5),
                                     [this] { return mSessionClosed; });
        }

        if (!mSessionClosed) {
            LOG(ERROR) << "Timeout waiting for session to close for camera " << mCameraId;
        }

        // Now it's safe to null out mSession.
        mSession = nullptr;
    }
    if (mOutputs) {
        ACaptureSessionOutputContainer_free(mOutputs);
        mOutputs = nullptr;
    }
    if (mSessionOutput) {
        ACaptureSessionOutput_free(mSessionOutput);
        mSessionOutput = nullptr;
    }
    if (mOutputTarget) {
        ACameraOutputTarget_free(mOutputTarget);
        mOutputTarget = nullptr;
    }
    if (mImageReader) {
        AImageReader_delete(mImageReader);
        mImageReader = nullptr;
    }

    mWindow = nullptr;  // Owned by mImageReader.
}

ScopedAStatus CompatHalCamera::startNdkCameraStream(int32_t maxImages) {
    LOG(INFO) << "Starting NDK stream for camera " << mCameraId;

    // Create an AImageReader
    media_status_t status = Converter::toAImageReader(mStreamConfig, maxImages, &mImageReader);
    if (status != AMEDIA_OK || !mImageReader) {
        LOG(ERROR) << "Failed to create AImageReader, status: " << status;
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    // Set up the image listener
    mImageListener.context = this;
    mImageListener.onImageAvailable = onImageAvailable;
    status = AImageReader_setImageListener(mImageReader, &mImageListener);
    if (status != AMEDIA_OK) {
        LOG(ERROR) << "Failed to set ImageListener, status: " << status;
        cleanUpNdkStreamResources();
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    // Get the ANativeWindow
    media_status_t mediaStatus = AImageReader_getWindow(mImageReader, &mWindow);
    if (mediaStatus != AMEDIA_OK || !mWindow) {
        LOG(ERROR) << "Failed to get window from AImageReader, status: " << mediaStatus;
        cleanUpNdkStreamResources();
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    // Create CameraOutputTarget
    camera_status_t cameraStatus = ACameraOutputTarget_create(mWindow, &mOutputTarget);
    if (cameraStatus != ACAMERA_OK || !mOutputTarget) {
        LOG(ERROR) << "Failed to create ACameraOutputTarget, status: " << cameraStatus;
        cleanUpNdkStreamResources();
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    // Create ACameraCaptureSessionOutput
    cameraStatus = ACaptureSessionOutput_create(mWindow, &mSessionOutput);
    if (cameraStatus != ACAMERA_OK || !mSessionOutput) {
        LOG(ERROR) << "Failed to create ACameraCaptureSessionOutput, status: " << cameraStatus;
        cleanUpNdkStreamResources();
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    // Create ACameraCaptureSessionOutputContainer
    cameraStatus = ACaptureSessionOutputContainer_create(&mOutputs);
    if (cameraStatus != ACAMERA_OK || !mOutputs) {
        LOG(ERROR) << "Failed to create ACameraCaptureSessionOutputContainer, status: "
                   << cameraStatus;
        cleanUpNdkStreamResources();
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    // Add output to container
    cameraStatus = ACaptureSessionOutputContainer_add(mOutputs, mSessionOutput);
    if (cameraStatus != ACAMERA_OK) {
        LOG(ERROR) << "Failed to add output to container, status: " << cameraStatus;
        cleanUpNdkStreamResources();
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    // Define session state callbacks
    mSessionStateCallbacks.context = this;
    mSessionStateCallbacks.onActive = onSessionActive;
    mSessionStateCallbacks.onReady = onSessionReady;
    mSessionStateCallbacks.onClosed = onSessionClosed;

    // Create Capture Session
    cameraStatus = ACameraDevice_createCaptureSession(mDevice, mOutputs, &mSessionStateCallbacks,
                                                      &mSession);
    if (cameraStatus != ACAMERA_OK || !mSession) {
        LOG(ERROR) << "Failed to create ACameraCaptureSession, status: " << cameraStatus;
        cleanUpNdkStreamResources();
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    // Create Capture Request
    // Use TEMPLATE_PREVIEW because high frame rate is given priority over the highest-quality
    // post-processing in this mode, which is aligned with evs use case to display a live video
    // stream from an automotive camera with the lowest possible latency.
    cameraStatus = ACameraDevice_createCaptureRequest(mDevice, TEMPLATE_PREVIEW, &mCaptureRequest);
    if (cameraStatus != ACAMERA_OK || !mCaptureRequest) {
        LOG(ERROR) << "Failed to create ACaptureRequest, status: " << cameraStatus;
        cleanUpNdkStreamResources();
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    // Add target to request
    cameraStatus = ACaptureRequest_addTarget(mCaptureRequest, mOutputTarget);
    if (cameraStatus != ACAMERA_OK) {
        LOG(ERROR) << "Failed to add target to capture request, status: " << cameraStatus;
        cleanUpNdkStreamResources();
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    // Set up capture callbacks
    mCaptureCallbacks.context = this;
    mCaptureCallbacks.onCaptureStarted = onCaptureStarted;
    mCaptureCallbacks.onCaptureProgressed = onCaptureProgressed;
    mCaptureCallbacks.onCaptureCompleted = CompatHalCamera::onCaptureCompleted;
    mCaptureCallbacks.onCaptureFailed = onCaptureFailed;
    mCaptureCallbacks.onCaptureBufferLost = onCaptureBufferLost;

    // Start the repeating request
    cameraStatus = ACameraCaptureSession_setRepeatingRequest(mSession, &mCaptureCallbacks, 1,
                                                             &mCaptureRequest, nullptr);
    if (cameraStatus != ACAMERA_OK) {
        LOG(ERROR) << "Failed to start repeating request, status: " << cameraStatus;
        cleanUpNdkStreamResources();
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    LOG(INFO) << "Successfully started NDK stream for camera " << mCameraId;
    return ScopedAStatus::ok();
}

// Tries to lock the mutex and check the stream state. Returns true if the lock was acquired, false
// otherwise. The stream state is put into the 'result' output parameter.
bool CompatHalCamera::tryIsStopped(bool& result) const {
    std::unique_lock<std::mutex> lock(mMutex, std::try_to_lock);
    if (lock.owns_lock()) {
        result = (mStreamState == STOPPED);
        return true;
    }
    return false;
}

bool CompatHalCamera::releaseACameraDevice() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mDevice) {
        LOG(INFO) << "Closing NDK device for " << mCameraId;
        ACameraDevice_close(mDevice);
        mDevice = nullptr;
        return true;
    }
    return false;
}

ACameraMetadata* CompatHalCamera::getLatestMetadata() const {
    std::lock_guard lock(mMetadataLock);
    if (!mLatestMetadata) {
        return nullptr;
    }
    return ACameraMetadata_copy(mLatestMetadata);
}

}  // namespace android::hardware::automotive::evs::compat
