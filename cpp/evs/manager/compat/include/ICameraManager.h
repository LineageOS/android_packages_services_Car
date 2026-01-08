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

#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>

#include <string>
#include <vector>

namespace android::hardware::automotive::evs::compat {

// Function pointer types for shared camera
typedef camera_status_t (*ACameraManager_openSharedCamera_fn)(
        ACameraManager* manager, const char* cameraId, ACameraDevice_StateCallbacks* callback,
        /*out*/ ACameraDevice** device, /*out*/ bool* primaryClient);
typedef camera_status_t (*ACameraManager_isCameraDeviceSharingSupported_fn)(
        ACameraManager* manager, const char* cameraId, bool* isSharingSupported);

// Function pointer types for shared camera streaming
typedef camera_status_t (*ACameraCaptureSessionShared_startStreaming_fn)(
        ACameraCaptureSession* sharedSession, ACameraCaptureSession_captureCallbacksV2* callbacks,
        int numOutputWindows, ANativeWindow** window, int* captureSequenceId);
typedef camera_status_t (*ACameraCaptureSessionShared_stopStreaming_fn)(
        ACameraCaptureSession* session);

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

    /**
     * Wraps the ACameraManager_registerAvailabilityCallback function.
     * @param callback The callbacks to register.
     * @return The status of the operation.
     */
    virtual camera_status_t registerAvailabilityCallback(
            const ACameraManager_AvailabilityCallbacks* callback) = 0;

    /**
     * Wraps the ACameraManager_unregisterAvailabilityCallback function.
     * @param callback The callbacks to unregister.
     * @return The status of the operation.
     */
    virtual camera_status_t unregisterAvailabilityCallback(
            const ACameraManager_AvailabilityCallbacks* callback) = 0;

    // Accessors for dynamically loaded functions
    virtual ACameraManager_openSharedCamera_fn getOpenSharedCameraFn() = 0;
    virtual ACameraManager_isCameraDeviceSharingSupported_fn
    getIsCameraDeviceSharingSupportedFn() = 0;
    virtual ACameraCaptureSessionShared_startStreaming_fn
    getCaptureSessionSharedStartStreamingFn() = 0;
    virtual ACameraCaptureSessionShared_stopStreaming_fn
    getCaptureSessionSharedStopStreamingFn() = 0;
};

}  // namespace android::hardware::automotive::evs::compat
