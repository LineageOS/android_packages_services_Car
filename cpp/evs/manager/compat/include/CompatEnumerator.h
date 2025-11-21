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

#include "CompatHalCamera.h"
#include "CompatVirtualCamera.h"
#include "ICameraManager.h"
#include "android/binder_auto_utils.h"

#include <aidl/android/hardware/automotive/evs/BnEvsEnumerator.h>
#include <aidl/android/hardware/automotive/evs/CameraDesc.h>
#include <aidl/android/hardware/automotive/evs/DisplayState.h>
#include <aidl/android/hardware/automotive/evs/IEvsCamera.h>
#include <aidl/android/hardware/automotive/evs/IEvsDisplay.h>
#include <aidl/android/hardware/automotive/evs/IEvsEnumeratorStatusCallback.h>
#include <aidl/android/hardware/automotive/evs/IEvsUltrasonicsArray.h>
#include <aidl/android/hardware/automotive/evs/Stream.h>
#include <aidl/android/hardware/automotive/evs/UltrasonicsArrayDesc.h>
#include <utils/Mutex.h>

#include <cstdint>
#include <list>
#include <shared_mutex>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace android::hardware::automotive::evs::compat {

namespace aidlevs = ::aidl::android::hardware::automotive::evs;

struct CameraGroup {
    std::string groupId;
    std::vector<std::string> physicalIds;
    std::vector<uint8_t> logicalCameraMetadata;
    // other fields can be added here as needed in the future.
};
using CameraGroupMap = std::unordered_map<std::string, CameraGroup>;

class CompatEnumerator final : public aidlevs::BnEvsEnumerator {
#ifdef EVS_COMPAT_TEST
    // Grant access to private members for testing.
    friend class CompatEnumeratorTest_setCameraGroupMap_Test;
    friend class CompatEnumeratorTest_CameraAvailabilityCallbacks_Test;
    friend class CompatEnumeratorTest;
#endif

public:
    CompatEnumerator();
#ifdef EVS_COMPAT_TEST
    // Constructor for dependency injection in tests
    explicit CompatEnumerator(std::unique_ptr<ICameraManager> cameraManager);
#endif
    ~CompatEnumerator() override;

    ::ndk::ScopedAStatus closeCamera(
            const std::shared_ptr<aidlevs::IEvsCamera>& carCamera) override;
    ::ndk::ScopedAStatus closeDisplay(
            const std::shared_ptr<aidlevs::IEvsDisplay>& display) override;
    ::ndk::ScopedAStatus closeUltrasonicsArray(
            const std::shared_ptr<aidlevs::IEvsUltrasonicsArray>& evsUltrasonicsArray) override;
    /* Clients should ignore CameraDesc.vendorFlags, as it is not supported in the compat library.
     */
    ::ndk::ScopedAStatus getCameraList(std::vector<aidlevs::CameraDesc>* _aidl_return) override;
    ::ndk::ScopedAStatus getDisplayIdList(std::vector<uint8_t>* _aidl_return) override;
    ::ndk::ScopedAStatus getDisplayState(aidlevs::DisplayState* _aidl_return) override;
    ::ndk::ScopedAStatus getStreamList(const aidlevs::CameraDesc& description,
                                       std::vector<aidlevs::Stream>* _aidl_return) override;
    ::ndk::ScopedAStatus getUltrasonicsArrayList(
            std::vector<aidlevs::UltrasonicsArrayDesc>* _aidl_return) override;
    ::ndk::ScopedAStatus isHardware(bool* _aidl_return) override;
    ::ndk::ScopedAStatus openCamera(const std::string& cameraId, const aidlevs::Stream& streamCfg,
                                    std::shared_ptr<aidlevs::IEvsCamera>* _aidl_return) override;
    ::ndk::ScopedAStatus openDisplay(int32_t id,
                                     std::shared_ptr<aidlevs::IEvsDisplay>* _aidl_return) override;
    ::ndk::ScopedAStatus openUltrasonicsArray(
            const std::string& ultrasonicsArrayId,
            std::shared_ptr<aidlevs::IEvsUltrasonicsArray>* _aidl_return) override;
    ::ndk::ScopedAStatus registerStatusCallback(
            const std::shared_ptr<aidlevs::IEvsEnumeratorStatusCallback>& callback) override;
    ::ndk::ScopedAStatus getDisplayStateById(int32_t id,
                                             aidlevs::DisplayState* _aidl_return) override;
    ::ndk::ScopedAStatus setCameraGroupMap(const CameraGroupMap& cameraGroupMap);

private:
    ::ndk::ScopedAStatus initCameraDescs() REQUIRES(mLock);
    static void onDeviceDisconnected(void* context, ACameraDevice* device);
    static void onDeviceError(void* context, ACameraDevice* device, int error);
    static void onClientSharedAccessPriorityChanged(void* context, ACameraDevice* device,
                                                    bool isPrimaryClient);
    void handleDeviceStatusChange(ACameraDevice* device, const char* functionName,
                                  const int* error = nullptr);
    static void onCameraAvailable(void* context, const char* cameraId);
    static void onCameraUnavailable(void* context, const char* cameraId);
    void notifyDeviceStatusChange(const char* cameraId, aidlevs::DeviceStatusType statusType);

    void removeActiveCamera(const char* cameraId) REQUIRES(mLock);
    void cleanupOpenedCameras(const std::vector<std::string>& cameraIds) REQUIRES(mLock);
    std::unordered_set<std::string> getPhysicalCameraIds(const std::string& cameraId);
    void broadcastDeviceStatusChange(const std::vector<aidlevs::DeviceStatus>& list);

    // Initializes and registers the camera availability callbacks.
    // Caller must ensure mCameraManager is not null and mCameraManager->isAvailable() is true.
    void initializeAvailabilityCallbacks();

    std::unique_ptr<ICameraManager> mCameraManager;
    ACameraManager_AvailabilityCallbacks mAvailabilityCallbacks;
    bool mIsReady;
    // only virtual cameras are in this map.
    std::unique_ptr<CameraGroupMap> mCameraGroupMap;
    // physical and logical cameras are in this map.
    std::unordered_map<std::string, aidlevs::CameraDesc> mCameraDescs;

    mutable std::shared_mutex mLock;  // Mutex to protect mActiveCameras, mActiveVirtualCameras
    std::unordered_map<std::string, std::shared_ptr<CompatHalCamera>> mActiveCameras
            GUARDED_BY(mLock);
    std::list<std::weak_ptr<CompatVirtualCamera>> mActiveVirtualCameras GUARDED_BY(mLock);

    std::set<std::shared_ptr<aidlevs::IEvsEnumeratorStatusCallback>> mDeviceStatusCallbacks;
};

}  // namespace android::hardware::automotive::evs::compat
