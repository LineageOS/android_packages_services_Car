/*
 * Copyright 2021 The Android Open Source Project
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

#include "EvsServiceWrapper.h"

#include <android-base/logging.h>
#include <android/hardware_buffer.h>
#include <android_runtime/android_hardware_HardwareBuffer.h>

using ::android::hardware::automotive::evs::V1_0::EvsResult;
using namespace ::android::hardware::automotive::evs::V1_1;

namespace android {
namespace automotive {
namespace evs {

// "default" is reserved for the latest version of EVS manager.
const char* EvsServiceWrapper::kServiceName = "default";

EvsServiceWrapper::~EvsServiceWrapper() {
    std::lock_guard<std::mutex> lock(mLock);
    if (mService != nullptr) {
        mService->unlinkToDeath(mDeathRecipient);
    }
    mCamera = nullptr;
    mStreamHandler = nullptr;
}

bool EvsServiceWrapper::initialize(const DeathCb& serviceDeathListener) {
    sp<IEvsEnumerator> service = IEvsEnumerator::tryGetService(EvsServiceWrapper::kServiceName);
    if (!service) {
        // TODO(b/177923058): it may be desired to retry a few times if the
        // connection fails.
        LOG(ERROR) << "Failed to connect to EVS service.";
        return false;
    }

    sp<EvsDeathRecipient> deathRecipient = new EvsDeathRecipient(service, serviceDeathListener);
    auto ret = service->linkToDeath(deathRecipient, /*cookie=*/0);
    if (!ret.isOk() || !ret) {
        LOG(ERROR) << "Failed to register a death recipient; the service may die.";
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(mLock);
        mService = service;
        mDeathRecipient = deathRecipient;
    }

    return true;
}

bool EvsServiceWrapper::openCamera(const char* id, const FrameCb& frameCallback,
                                   const EventCb& eventCallback) {
    if (!isServiceAvailable()) {
        LOG(ERROR) << "Has not connected to EVS service yet.";
        return false;
    }

    if (isCameraOpened()) {
        LOG(DEBUG) << "Camera " << id << " is has opened already.";
        return true;
    }

    sp<IEvsCamera> camera = IEvsCamera::castFrom(mService->openCamera(id));
    if (!camera) {
        LOG(ERROR) << "Failed to open a camera " << id;
        return false;
    }

    sp<StreamHandler> streamHandler = new StreamHandler(camera, frameCallback, eventCallback,
                                                        EvsServiceWrapper::kMaxNumFramesInFlight);
    if (!streamHandler) {
        LOG(ERROR) << "Failed to initialize a stream streamHandler.";
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(mLock);
        mCamera = camera;
        mStreamHandler = streamHandler;
    }

    return true;
}

void EvsServiceWrapper::closeCamera() {
    if (!isCameraOpened()) {
        LOG(ERROR) << "Camera has not opened yet.";
        return;
    }

    mService->closeCamera(mCamera);
}

bool EvsServiceWrapper::startVideoStream() {
    if (!isCameraOpened()) {
        LOG(ERROR) << "Camera has not opened yet.";
        return JNI_FALSE;
    }

    return mStreamHandler->startStream();
}

void EvsServiceWrapper::stopVideoStream() {
    if (!isCameraOpened()) {
        LOG(DEBUG) << "Camera has not opened; a request to stop a video steram is ignored.";
        return;
    }

    // TODO: the caller should wait for a stream-stopped signal.
    if (!mStreamHandler->asyncStopStream()) {
        LOG(WARNING) << "Failed to stop a video stream.  EVS service may die.";
    }
}

void EvsServiceWrapper::acquireCameraAndDisplay() {
    // Acquires the display ownership.  Because EVS awards this to the single
    // client, no other clients can use EvsDisplay as long as CarEvsManager
    // alives.
    mDisplay = mService->openDisplay_1_1(EvsServiceWrapper::kExclusiveMainDisplayId);
    if (!mDisplay) {
        LOG(ERROR) << "Failed to acquire the display ownership.  "
                   << "CarEvsManager may not be able to render "
                   << "the contents on the screen.";
        return;
    }

    // Attempts to become a primary owner
    auto ret = mCamera->forceMaster(mDisplay);
    if (!ret.isOk() || ret != EvsResult::OK) {
        LOG(ERROR) << "Failed to own a camera device.";
    }
}

void EvsServiceWrapper::doneWithFrame(const BufferDesc& frame) {
    mStreamHandler->doneWithFrame(frame);

    // If this is the first frame since current video stream started, we'd claim
    // the exclusive ownership of the camera and the display and keep for the rest
    // of the lifespan.
    std::call_once(mDisplayAcquired, &EvsServiceWrapper::acquireCameraAndDisplay, this);
}

}  // namespace evs
}  // namespace automotive
}  // namespace android
