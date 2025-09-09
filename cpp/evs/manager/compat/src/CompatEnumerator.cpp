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

#include "CompatEnumerator.h"

#include <android-base/logging.h>

#include <android_car_feature.h>

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::CameraDesc;
using ::aidl::android::hardware::automotive::evs::DisplayState;
using ::aidl::android::hardware::automotive::evs::IEvsCamera;
using ::aidl::android::hardware::automotive::evs::IEvsDisplay;
using ::aidl::android::hardware::automotive::evs::IEvsEnumeratorStatusCallback;
using ::aidl::android::hardware::automotive::evs::IEvsUltrasonicsArray;
using ::aidl::android::hardware::automotive::evs::Stream;
using ::aidl::android::hardware::automotive::evs::UltrasonicsArrayDesc;
using ::ndk::ScopedAStatus;

CompatEnumerator::CompatEnumerator() {
    // Constructor stub
}

CompatEnumerator::~CompatEnumerator() {
    // Destructor stub
}

ScopedAStatus CompatEnumerator::closeCamera(
        [[maybe_unused]] const std::shared_ptr<IEvsCamera>& carCamera) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::closeDisplay(
        [[maybe_unused]] const std::shared_ptr<IEvsDisplay>& display) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::closeUltrasonicsArray(
        [[maybe_unused]] const std::shared_ptr<IEvsUltrasonicsArray>& evsUltrasonicsArray) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::getCameraList(std::vector<CameraDesc>* _aidl_return) {
    if (android::car::feature::car_evs_compat_lib()) {
        // TODO(b/441577862): Implement the function.
        return ScopedAStatus::ok();
    } else {
        return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
}

ScopedAStatus CompatEnumerator::getDisplayIdList(
        [[maybe_unused]] std::vector<uint8_t>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::getDisplayState([[maybe_unused]] DisplayState* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::getStreamList(
        [[maybe_unused]] const CameraDesc& description,
        [[maybe_unused]] std::vector<Stream>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::getUltrasonicsArrayList(
        [[maybe_unused]] std::vector<UltrasonicsArrayDesc>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::isHardware([[maybe_unused]] bool* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::openCamera(
        [[maybe_unused]] const std::string& cameraId, [[maybe_unused]] const Stream& streamCfg,
        [[maybe_unused]] std::shared_ptr<IEvsCamera>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::openDisplay(
        [[maybe_unused]] int32_t id,
        [[maybe_unused]] std::shared_ptr<IEvsDisplay>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::openUltrasonicsArray(
        [[maybe_unused]] const std::string& ultrasonicsArrayId,
        [[maybe_unused]] std::shared_ptr<IEvsUltrasonicsArray>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::registerStatusCallback(
        [[maybe_unused]] const std::shared_ptr<IEvsEnumeratorStatusCallback>& callback) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::getDisplayStateById(
        [[maybe_unused]] int32_t id, [[maybe_unused]] DisplayState* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}
}  // namespace android::hardware::automotive::evs::compat
