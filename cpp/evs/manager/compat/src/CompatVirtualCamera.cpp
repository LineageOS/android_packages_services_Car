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

#include <android-base/logging.h>

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::BufferDesc;
using ::aidl::android::hardware::automotive::evs::CameraDesc;
using ::aidl::android::hardware::automotive::evs::CameraParam;
using ::aidl::android::hardware::automotive::evs::EvsResult;
using ::aidl::android::hardware::automotive::evs::IEvsCameraStream;
using ::aidl::android::hardware::automotive::evs::IEvsDisplay;
using ::aidl::android::hardware::automotive::evs::ParameterRange;
using ::ndk::ScopedAStatus;

CompatVirtualCamera::CompatVirtualCamera(
        const std::vector<std::shared_ptr<CompatHalCamera>>& halCameras) {
    for (auto&& halCamera : halCameras) {
        mHalCameras.insert_or_assign(halCamera->getId(), std::weak_ptr<CompatHalCamera>(halCamera));
    }
}

CompatVirtualCamera::~CompatVirtualCamera() {
    // Destructor stub
}

ScopedAStatus CompatVirtualCamera::doneWithFrame(
        [[maybe_unused]] const std::vector<BufferDesc>& buffer) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::forcePrimaryClient(
        [[maybe_unused]] const std::shared_ptr<IEvsDisplay>& display) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::getCameraInfo([[maybe_unused]] CameraDesc* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::getExtendedInfo(
        [[maybe_unused]] int32_t opaqueIdentifier,
        [[maybe_unused]] std::vector<uint8_t>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::getIntParameter(
        [[maybe_unused]] CameraParam id, [[maybe_unused]] std::vector<int32_t>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::getIntParameterRange(
        [[maybe_unused]] CameraParam id, [[maybe_unused]] ParameterRange* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::getParameterList(
        [[maybe_unused]] std::vector<CameraParam>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::getPhysicalCameraInfo(const std::string& deviceId,
                                                         CameraDesc* _aidl_return) {
    auto it = mHalCameras.find(deviceId);
    if (it == mHalCameras.end()) {
        LOG(ERROR) << "Camera " << deviceId << " not found.";
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::INVALID_ARG));
    }
    std::shared_ptr<CompatHalCamera> halCamera = it->second.lock();
    if (!halCamera) {
        LOG(ERROR) << "Camera " << deviceId << " is no longer available.";
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
    }
    // Check if CameraDesc is valid. The id field is mandatory.
    aidlevs::CameraDesc desc = halCamera->getCameraDesc();
    if (desc.id.empty()) {
        LOG(ERROR) << "CameraDesc for " << deviceId << " is not properly initialized.";
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
    }
    *_aidl_return = desc;
    return ScopedAStatus::ok();
}

ScopedAStatus CompatVirtualCamera::importExternalBuffers(
        [[maybe_unused]] const std::vector<BufferDesc>& buffers,
        [[maybe_unused]] int32_t* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::pauseVideoStream() {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::resumeVideoStream() {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::setExtendedInfo(
        [[maybe_unused]] int32_t opaqueIdentifier,
        [[maybe_unused]] const std::vector<uint8_t>& opaqueValue) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::setIntParameter(
        [[maybe_unused]] CameraParam id, [[maybe_unused]] int32_t value,
        [[maybe_unused]] std::vector<int32_t>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::setPrimaryClient() {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::setMaxFramesInFlight(int32_t bufferCount) {
    if (bufferCount <= 0) {
        LOG(ERROR) << "bufferCount must be positive, but got " << bufferCount;
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(EvsResult::INVALID_ARG));
    }

    std::lock_guard<std::mutex> lock(mMutex);
    if (bufferCount == mMaxFramesInFlight) {
        return ScopedAStatus::ok();
    }

    for (auto& [id, weak_hal_cam] : mHalCameras) {
        if (auto hal_cam = weak_hal_cam.lock()) {
            bool is_stopped;
            if (hal_cam->tryIsStopped(is_stopped)) {
                if (!is_stopped) {
                    LOG(ERROR) << "Camera " << id << " is not stopped.";
                    return ScopedAStatus::fromServiceSpecificError(
                            static_cast<int32_t>(EvsResult::STREAM_ALREADY_RUNNING));
                }
            } else {
                // Could not acquire lock, treat as busy
                LOG(WARNING) << "Could not determine state of Camera " << id << ", assuming busy.";
                return ScopedAStatus::fromServiceSpecificError(
                        static_cast<int32_t>(EvsResult::RESOURCE_BUSY));
            }
        }
    }

    mMaxFramesInFlight = bufferCount;
    return ScopedAStatus::ok();
}

ScopedAStatus CompatVirtualCamera::startVideoStream(
        [[maybe_unused]] const std::shared_ptr<IEvsCameraStream>& receiver) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::stopVideoStream() {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::unsetPrimaryClient() {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

bool CompatVirtualCamera::deliverFrame([[maybe_unused]] const aidlevs::BufferDesc& bufDesc) {
    // TODO(b/372312166): Add implementation.
    return false;
}

}  // namespace android::hardware::automotive::evs::compat
