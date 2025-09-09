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

#include <android-base/logging.h>

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::BufferDesc;
using ::aidl::android::hardware::automotive::evs::CameraDesc;
using ::aidl::android::hardware::automotive::evs::CameraParam;
using ::aidl::android::hardware::automotive::evs::IEvsCameraStream;
using ::aidl::android::hardware::automotive::evs::IEvsDisplay;
using ::aidl::android::hardware::automotive::evs::ParameterRange;
using ::ndk::ScopedAStatus;

CompatVirtualCamera::CompatVirtualCamera() {
    // Constructor stub
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

ScopedAStatus CompatVirtualCamera::getPhysicalCameraInfo(
        [[maybe_unused]] const std::string& deviceId, [[maybe_unused]] CameraDesc* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
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

ScopedAStatus CompatVirtualCamera::setMaxFramesInFlight(
        [[maybe_unused]] int32_t bufferCount) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
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

}  // namespace android::hardware::automotive::evs::compat
