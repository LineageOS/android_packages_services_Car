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

#include "NdkCameraManager.h"

#include <android-base/logging.h>

namespace android::hardware::automotive::evs::compat {

NdkCameraManager::NdkCameraManager() : mManager(ACameraManager_create()) {
    if (!mManager) {
        LOG(ERROR) << "Failed to create ACameraManager.";
    }
}

NdkCameraManager::~NdkCameraManager() {
    if (mManager) {
        ACameraManager_delete(mManager);
    }
}

bool NdkCameraManager::isAvailable() {
    return mManager != nullptr;
}

camera_status_t NdkCameraManager::getCameraIdList(std::vector<std::string>* idList) {
    ACameraIdList* cameraIdList = nullptr;
    camera_status_t status = ACameraManager_getCameraIdList(mManager, &cameraIdList);
    if (status != ACAMERA_OK) {
        LOG(ERROR) << "ACameraManager_getCameraIdList failed: " << status;
        return status;
    }
    if (cameraIdList && idList) {
        for (int i = 0; i < cameraIdList->numCameras; ++i) {
            idList->push_back(cameraIdList->cameraIds[i]);
        }
        ACameraManager_deleteCameraIdList(cameraIdList);
    }
    return status;
}

camera_status_t NdkCameraManager::getCameraCharacteristics(const char* cameraId,
                                                           ACameraMetadata** metadata) {
    camera_status_t status = ACameraManager_getCameraCharacteristics(mManager, cameraId, metadata);
    if (status != ACAMERA_OK) {
        LOG(ERROR) << "ACameraManager_getCameraCharacteristics failed for camera " << cameraId
                   << ": " << status;
    }
    return status;
}

ACameraManager* NdkCameraManager::get() {
    return mManager;
}

camera_status_t NdkCameraManager::registerAvailabilityCallback(
        const ACameraManager_AvailabilityCallbacks* callback) {
    camera_status_t status = ACameraManager_registerAvailabilityCallback(mManager, callback);
    if (status != ACAMERA_OK) {
        LOG(ERROR) << "ACameraManager_registerAvailabilityCallback failed: " << status;
    }
    return status;
}

camera_status_t NdkCameraManager::unregisterAvailabilityCallback(
        const ACameraManager_AvailabilityCallbacks* callback) {
    camera_status_t status = ACameraManager_unregisterAvailabilityCallback(mManager, callback);
    if (status != ACAMERA_OK) {
        LOG(ERROR) << "ACameraManager_unregisterAvailabilityCallback failed: " << status;
    }
    return status;
}

}  // namespace android::hardware::automotive::evs::compat
