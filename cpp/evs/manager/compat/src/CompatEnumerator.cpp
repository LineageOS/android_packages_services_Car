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

#include "Converter.h"
#include "NdkCameraManager.h"

#include <android-base/logging.h>
#include <memory>
#include <camera/NdkCameraMetadata.h>
#include <unordered_set>

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
    if (!android::car::feature::car_evs_compat_lib()) {
        LOG(INFO) << "EVS compat library feature is not enabled.";
        mIsReady = false;
        return;
    }
    mCameraManager = std::make_unique<NdkCameraManager>();
    if (!mCameraManager->isAvailable()) {
        LOG(ERROR) << "Camera manager is not available.";
        mIsReady = false;
    }
    mIsReady = true;
}

#ifdef EVS_COMPAT_TEST
// Constructor for dependency injection.
CompatEnumerator::CompatEnumerator(std::unique_ptr<ICameraManager> cameraManager) :
      mCameraManager(std::move(cameraManager)) {
        mIsReady = android::car::feature::car_evs_compat_lib();
}
#endif

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
    if (!mIsReady) {
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }
    if (_aidl_return == nullptr) {
        LOG(ERROR) << "Received a null pointer for the return value.";
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    std::vector<std::string> cameraIds;
    camera_status_t status = mCameraManager->getCameraIdList(&cameraIds);
    if (status != ACAMERA_OK) {
        // TODO (b/441577862): implement a conversion from camera_status_t to EvsResult.aidl and
        // return it here.
        return ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    if (cameraIds.empty()) {
        LOG(WARNING) << "No camera devices were found.";
        return ::ndk::ScopedAStatus::ok();
    }

    for (const auto& cameraId : cameraIds) {
        ACameraMetadata* metadata = nullptr;
        camera_status_t status =
                mCameraManager->getCameraCharacteristics(cameraId.c_str(), &metadata);
        if (status != ACAMERA_OK) {
            continue;  // Skip this camera
        }
        // Camera NDK does not support vendorFlags, so we pass a placeholder value.
        CameraDesc desc = Converter::toCameraDesc(cameraId.c_str(), metadata,
                                                  /* vendorFlags= */ -1);
        _aidl_return->push_back(desc);
        mCameraDesc.insert_or_assign(desc.id, desc);
        ACameraMetadata_free(metadata);
    }
    return ScopedAStatus::ok();
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

ScopedAStatus CompatEnumerator::setCameraGroupMap(const CameraGroupMap& cameraGroupMap) {
    if (!mIsReady) {
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }
    std::vector<std::string> availableCameraIds;
    camera_status_t status = mCameraManager->getCameraIdList(&availableCameraIds);
    if (status != ACAMERA_OK) {
        // TODO (b/441577862): implement a conversion from camera_status_t to EvsResult.aidl and
        // return it here.
        LOG(ERROR) << "Failed to get camera ID list. error status (camera_status_t): " << status;
        return ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    std::unordered_set<std::string> availableCameraIdSet(availableCameraIds.begin(),
                                                     availableCameraIds.end());
    for (const auto& groupEntry : cameraGroupMap) {
        for (const auto& cameraId : groupEntry.second.physicalIds) {
            if (availableCameraIdSet.find(cameraId) == availableCameraIdSet.end()) {
                LOG(ERROR) << "Camera ID " << cameraId << " in group " << groupEntry.first
                           << " does not exist.";
                return ::ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
            }
        }
    }
    mCameraGroupMap = std::make_unique<CameraGroupMap>(cameraGroupMap);
    return ::ndk::ScopedAStatus::ok();
}
}  // namespace android::hardware::automotive::evs::compat