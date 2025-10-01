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

#include <android-base/logging.h>

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::BufferDesc;
using ::aidl::android::hardware::automotive::evs::EvsEventDesc;
using ::ndk::ScopedAStatus;

CompatHalCamera::CompatHalCamera(ACameraDevice* device, const std::string& cameraId,
                                 const aidlevs::Stream& streamConfig) :
      mDevice(device), mCameraId(cameraId), mStreamConfig(streamConfig) {
    // Constructor stub
}

CompatHalCamera::~CompatHalCamera() {
    // Destructor stub
}

ScopedAStatus CompatHalCamera::deliverFrame(
        [[maybe_unused]] const std::vector<BufferDesc>& buffer) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
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

}  // namespace android::hardware::automotive::evs::compat
