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

#include "CompatHalCamera.h"
#include "CompatVirtualCamera.h"
#include "Converter.h"
#include "NdkCameraManager.h"

#include <android-base/logging.h>
#include <camera/NdkCameraMetadata.h>
#include <memory>
#include <unordered_set>

#include <android_car_feature.h>
#include <dlfcn.h>

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
    // Dynamically load libcamera2ndk.so
    mLibHandle = dlopen("libcamera2ndk.so", RTLD_NOW);
    if (!mLibHandle) {
        LOG(ERROR) << "Failed to dlopen libcamera2ndk.so: " << dlerror();
        mIsReady = false;
        return;
    }
    mOpenSharedCameraFn =
            reinterpret_cast<ACameraManager_openSharedCamera_fn>(
                    dlsym(mLibHandle, "ACameraManager_openSharedCamera"));
    if (!mOpenSharedCameraFn) {
        LOG(ERROR) << "Failed to dlsym ACameraManager_openSharedCamera: " << dlerror();
        dlclose(mLibHandle);
        mLibHandle = nullptr;
        mIsReady = false;
        return;
    }

    mCameraManager = std::make_unique<NdkCameraManager>();
    if (!mCameraManager->isAvailable()) {
        LOG(ERROR) << "Camera manager is not available.";
        mIsReady = false;
        return;
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
    if (mLibHandle) {
        dlclose(mLibHandle);
        mLibHandle = nullptr;
    }
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

ScopedAStatus CompatEnumerator::getStreamList([[maybe_unused]] const CameraDesc& description,
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

void CompatEnumerator::onDeviceDisconnected(void* context, ACameraDevice* device) {
    if (!context || !device) return;
    CompatEnumerator* self = static_cast<CompatEnumerator*>(context);
    const char* cameraId = ACameraDevice_getId(device);
    LOG(WARNING) << "Camera device " << cameraId << " disconnected. Removing from active list.";
    self->removeActiveCamera(cameraId);
}

void CompatEnumerator::onDeviceError(void* context, ACameraDevice* device, int error) {
    if (!context || !device) return;
    CompatEnumerator* self = static_cast<CompatEnumerator*>(context);
    const char* cameraId = ACameraDevice_getId(device);
    LOG(ERROR) << "Camera device " << cameraId << " error: " << error
               << ". Removing from active list.";
    self->removeActiveCamera(cameraId);
}

void CompatEnumerator::removeActiveCamera(const char* cameraId) {
    if (!cameraId) return;
    std::lock_guard lock(mLock);
    if (mActiveCameras.erase(cameraId) > 0) {
        LOG(INFO) << "Removed camera " << cameraId << " from active list.";
    }
}

void CompatEnumerator::cleanupOpenedCameras(const std::vector<std::string>& cameraIds) {
    for (const auto& idToClose : cameraIds) {
        auto camIt = mActiveCameras.find(idToClose);
        if (camIt != mActiveCameras.end()) {
            ACameraDevice_close(camIt->second->getDevice());
            mActiveCameras.erase(camIt);
            LOG(INFO) << "Cleaned up and closed camera " << idToClose;
        }
    }
}

ScopedAStatus CompatEnumerator::openCamera(const std::string& cameraId,
                                           const Stream& streamCfg,
                                           std::shared_ptr<IEvsCamera>* _aidl_return) {
    if (!mIsReady) {
        LOG(ERROR) << "Enumerator is not ready. This is likely due to a failure during "
                      "initialization.";
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    std::unordered_set<std::string> physicalCameraIds = getPhysicalCameraIds(cameraId);
    if (physicalCameraIds.empty()) {
        LOG(ERROR) << "Camera ID " << cameraId << " not found or invalid.";
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    LOG(INFO) << "Opening camera " << cameraId << " with " << physicalCameraIds.size()
              << " physical device(s).";

    std::vector<std::shared_ptr<CompatHalCamera>> sourceCameras;
    bool success = true;
    std::vector<std::string> openedInThisCall;

    {
        std::lock_guard lock(mLock);
        for (const auto& id : physicalCameraIds) {
            auto it = mActiveCameras.find(id);
            if (it == mActiveCameras.end()) {
                ACameraDevice* device = nullptr;
                ACameraDevice_StateCallbacks callbacks = {
                        .context = this,
                        .onDisconnected = &CompatEnumerator::onDeviceDisconnected,
                        .onError = &CompatEnumerator::onDeviceError,
                        .onClientSharedAccessPriorityChanged = nullptr  // Not used for now
                };
                bool isPrimaryClient = false;
                camera_status_t status = mOpenSharedCameraFn(mCameraManager->get(), id.c_str(),
                                                           &callbacks, &device, &isPrimaryClient);

                if (status != ACAMERA_OK || device == nullptr) {
                    LOG(ERROR) << "Failed to open hardware camera " << id
                               << ", status = " << status;
                    success = false;
                    break;
                }

                LOG(INFO) << "Successfully opened physical camera " << id
                          << (isPrimaryClient ? " as primary" : " as secondary");

                std::shared_ptr<CompatHalCamera> halCamera =
                        ::ndk::SharedRefBase::make<CompatHalCamera>(device, id, streamCfg);
                if (!halCamera) {
                    LOG(ERROR) << "Failed to allocate CompatHalCamera object for " << id;
                    ACameraDevice_close(device);
                    success = false;
                    break;
                }

                mActiveCameras.insert_or_assign(id, halCamera);
                openedInThisCall.push_back(id);  // Track opened camera
                sourceCameras.push_back(std::move(halCamera));
            } else {
                if (it->second->getStreamConfig().id != streamCfg.id) {
                    LOG(WARNING) << "Camera " << id
                                 << " is already active with a different stream configuration "
                                    "(requested "
                                 << streamCfg.id << ", active " << it->second->getStreamConfig().id
                                 << "). Reusing existing instance.";
                } else {
                    sourceCameras.push_back(it->second);
                }
            }
        }

        if (!success || sourceCameras.empty()) {
            LOG(ERROR) << "Failed to open one or more physical camera devices for " << cameraId;
            cleanupOpenedCameras(openedInThisCall);
            return ::ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
        }

        std::shared_ptr<CompatVirtualCamera> virtualCamera =
                ::ndk::SharedRefBase::make<CompatVirtualCamera>(sourceCameras);
        if (!virtualCamera) {
            LOG(ERROR) << "Failed to create CompatVirtualCamera instance for " << cameraId;
            cleanupOpenedCameras(openedInThisCall);
            return ::ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
        }

        // Owns created proxy camera object
        for (auto& halCamera : sourceCameras) {
            if (!halCamera->ownVirtualCamera(virtualCamera)) {
                // TODO: Remove a reference to this camera from a virtual camera object. The todo is
                // inherited from the original implementation.
                LOG(ERROR) << halCamera->getId() << " failed to own virtual camera " << cameraId;
            }
        }
        // In the EVS manager, if a logical camera is opened,
        // clientCamera->setDescriptor(&mCameraDevices[id]); is called. This is missing in
        // CompatEnumerator because the CameraDesc for the logical camera ID isn't directly
        // available from the NDK in the same way. The mCameraGroupMap provides the physical IDs,
        // but not a full CameraDesc for the logical entity.
        mActiveVirtualCameras.push_back(virtualCamera);
        *_aidl_return = std::move(virtualCamera);
        return ::ndk::ScopedAStatus::ok();
    }
}

ScopedAStatus CompatEnumerator::openDisplay(
        [[maybe_unused]] int32_t id, [[maybe_unused]] std::shared_ptr<IEvsDisplay>* _aidl_return) {
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

ScopedAStatus CompatEnumerator::getDisplayStateById([[maybe_unused]] int32_t id,
                                                    [[maybe_unused]] DisplayState* _aidl_return) {
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

std::unordered_set<std::string> CompatEnumerator::getPhysicalCameraIds(
        const std::string& cameraId) {
    if (mCameraGroupMap) {
        auto it = mCameraGroupMap->find(cameraId);
        if (it != mCameraGroupMap->end()) {
            // Found in logical camera map, return the physical IDs
            const auto& physicalIds = it->second.physicalIds;
            return std::unordered_set<std::string>(physicalIds.begin(), physicalIds.end());
        }
    }

    // Not in the group map, check if it's a known physical camera ID in mCameraDesc
    auto it = mCameraDesc.find(cameraId);
    if (it != mCameraDesc.end()) {
        // It's a physical camera
        return {cameraId};
    }
    // Not found anywhere
    LOG(WARNING) << "Camera ID " << cameraId << " not found as logical or physical camera.";
    return {};
}
}  // namespace android::hardware::automotive::evs::compat