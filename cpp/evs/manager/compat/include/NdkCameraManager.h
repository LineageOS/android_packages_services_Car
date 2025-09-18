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

#pragma once

#include "ICameraManager.h"

namespace android::hardware::automotive::evs::compat {

/**
 * Concrete implementation of the ICameraManager interface that calls the
 * real NDK camera functions.
 */
class NdkCameraManager : public ICameraManager {
public:
    NdkCameraManager();
    ~NdkCameraManager() override;

    // Deleting copy constructor and assignment operator
    NdkCameraManager(const NdkCameraManager&) = delete;
    NdkCameraManager& operator=(const NdkCameraManager&) = delete;

    bool isAvailable() override;
    camera_status_t getCameraIdList(std::vector<std::string>* idList) override;
    camera_status_t getCameraCharacteristics(const char* cameraId,
                                             ACameraMetadata** metadata) override;

private:
    ACameraManager* mManager;
};

}  // namespace android::hardware::automotive::evs::compat
