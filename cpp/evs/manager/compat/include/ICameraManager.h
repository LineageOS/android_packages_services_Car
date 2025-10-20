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

#include <camera/NdkCameraManager.h>

#include <string>
#include <vector>

namespace android::hardware::automotive::evs::compat {

/**
 * Wrapper interface for NDK ACameraManager C functions.
 * This allows for dependency injection and mocking in unit tests.
 */
class ICameraManager {
public:
    virtual ~ICameraManager() = default;

    /**
     * Checks if the underlying ACameraManager is available.
     * @return true if the manager was created successfully, false otherwise.
     */
    virtual bool isAvailable() = 0;

    /**
     * Gets the list of available camera device IDs.
     * @param _aidl_return A pointer to a vector of strings to be filled with camera IDs.
     * @return the status of the operation.
     */
    virtual camera_status_t getCameraIdList(std::vector<std::string>* idList) = 0;

    /**
     * Wraps the ACameraManager_getCameraCharacteristics function.
     * @param cameraId The ID of the camera to get characteristics for.
     * @param metadata A pointer to an ACameraMetadata pointer to be filled.
     * @return The status of the operation.
     */
    virtual camera_status_t getCameraCharacteristics(const char* cameraId,
                                                     ACameraMetadata** metadata) = 0;

    /**
     * Gets the raw ACameraManager pointer.
     * @return The raw ACameraManager pointer, or nullptr if not available.
     */
    virtual ACameraManager* get() = 0;
};

}  // namespace android::hardware::automotive::evs::compat
