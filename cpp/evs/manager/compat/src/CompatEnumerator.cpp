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

#include <android_car_feature.h>
#include <dlfcn.h>

#include <map>
#include <memory>
#include <tuple>
#include <unordered_set>

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::CameraDesc;
using ::aidl::android::hardware::automotive::evs::DeviceStatus;
using ::aidl::android::hardware::automotive::evs::DeviceStatusType;
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
    mOpenSharedCameraFn = reinterpret_cast<ACameraManager_openSharedCamera_fn>(
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

    initializeAvailabilityCallbacks();

    mIsReady = true;
}

void CompatEnumerator::initializeAvailabilityCallbacks() {
    mAvailabilityCallbacks.context = this;
    mAvailabilityCallbacks.onCameraAvailable = &CompatEnumerator::onCameraAvailable;
    mAvailabilityCallbacks.onCameraUnavailable = &CompatEnumerator::onCameraUnavailable;
    camera_status_t status = mCameraManager->registerAvailabilityCallback(&mAvailabilityCallbacks);
    if (status != ACAMERA_OK) {
        LOG(ERROR) << "Failed to register camera availability callback: " << status;
    }
}

#ifdef EVS_COMPAT_TEST
// Constructor for dependency injection.
CompatEnumerator::CompatEnumerator(std::unique_ptr<ICameraManager> cameraManager) :
      mCameraManager(std::move(cameraManager)) {
    mIsReady = android::car::feature::car_evs_compat_lib();
    if (!mIsReady) return;
    initializeAvailabilityCallbacks();
}
#endif

CompatEnumerator::~CompatEnumerator() {
    if (mCameraManager && mCameraManager->isAvailable()) {
        mCameraManager->unregisterAvailabilityCallback(&mAvailabilityCallbacks);
    }
    if (mLibHandle) {
        dlclose(mLibHandle);
        mLibHandle = nullptr;
    }
}

ScopedAStatus CompatEnumerator::closeCamera(const std::shared_ptr<IEvsCamera>& carCamera) {
    LOG(DEBUG) << __FUNCTION__;
    if (!carCamera) {
        LOG(WARNING) << "closeCamera called with a null camera. Ignoring.";
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(aidlevs::EvsResult::INVALID_ARG));
    }
    {
        std::lock_guard lock(mLock);
        CompatVirtualCamera* virtualCamera =
                reinterpret_cast<CompatVirtualCamera*>(carCamera.get());
        auto it = std::find_if(mActiveVirtualCameras.begin(), mActiveVirtualCameras.end(),
                               [virtualCamera](std::weak_ptr<CompatVirtualCamera>& pVirtualCamera) {
                                   auto vc = pVirtualCamera.lock();
                                   return vc.get() == virtualCamera;
                               });
        if (it == mActiveVirtualCameras.end()) {
            LOG(ERROR) << "Failed to find a virtual camera to close. ignoring.";
            return ScopedAStatus::fromServiceSpecificError(
                    static_cast<int32_t>(aidlevs::EvsResult::INVALID_ARG));
        }
        // Stop the stream first. This calls halCamera->clientStreamEnding ->
        // cleanUpNdkStreamResources,which cleans up stream resources but does NOT close the
        // ACameraDevice.
        virtualCamera->stopVideoStream();

        // Remove the virtual camera from the active list
        mActiveVirtualCameras.erase(it);

        // Disown and check if HAL cameras need to be closed
        for (auto&& halCamera : virtualCamera->getHalCameras()) {
            halCamera->disownVirtualCamera(virtualCamera);
            if (halCamera->getOwnedVirtualCameraCount() == 0) {
                LOG(INFO) << "Camera " << halCamera->getId() << " has no more virtual cameras.";
                removeActiveCamera(halCamera->getId().c_str());
            }
        }
        return ScopedAStatus::ok();
    }
}

ScopedAStatus CompatEnumerator::closeDisplay(
        [[maybe_unused]] const std::shared_ptr<IEvsDisplay>& display) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::closeUltrasonicsArray(
        [[maybe_unused]] const std::shared_ptr<IEvsUltrasonicsArray>& evsUltrasonicsArray) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::initCameraDescs() {
    std::vector<std::string> cameraIds;
    camera_status_t status = mCameraManager->getCameraIdList(&cameraIds);
    if (status != ACAMERA_OK) {
        // TODO (b/441577862): implement a conversion from camera_status_t to EvsResult.aidl and
        // return it here.
        LOG(ERROR) << "Failed to get camera ID list. Status: " << status;
        return ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    for (const auto& cameraId : cameraIds) {
        ACameraMetadata* metadata = nullptr;
        status = mCameraManager->getCameraCharacteristics(cameraId.c_str(), &metadata);
        if (status != ACAMERA_OK) {
            continue;  // Skip this camera
        }
        // Camera NDK does not support vendorFlags, so we pass a placeholder value.
        mCameraDescs.insert_or_assign(cameraId,
                                      Converter::toCameraDesc(cameraId.c_str(), metadata,
                                                              /* vendorFlags= */ -1));
        ACameraMetadata_free(metadata);
    }
    return ScopedAStatus::ok();
}

ScopedAStatus CompatEnumerator::getCameraList(std::vector<CameraDesc>* _aidl_return) {
    if (!mIsReady) {
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }
    if (_aidl_return == nullptr) {
        LOG(ERROR) << "Received a null pointer for the return value.";
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    ScopedAStatus status = initCameraDescs();
    if (!status.isOk()) {
        return status;
    }

    for (const auto& [id, desc] : mCameraDescs) {
        _aidl_return->push_back(desc);
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

ScopedAStatus CompatEnumerator::getStreamList(const CameraDesc& desc,
                                              std::vector<Stream>* _aidl_return) {
    if (!mIsReady) {
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }
    if (_aidl_return == nullptr) {
        LOG(ERROR) << "Received a null pointer for the return value.";
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    ACameraMetadata* metadata = nullptr;
    camera_status_t status = mCameraManager->getCameraCharacteristics(desc.id.c_str(), &metadata);
    if (status != ACAMERA_OK) {
        LOG(ERROR) << "Failed to get camera characteristics for " << desc.id;
        return ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    // Get available stream configurations
    ACameraMetadata_const_entry streamConfigs;
    status = ACameraMetadata_getConstEntry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
                                           &streamConfigs);
    if (status != ACAMERA_OK) {
        LOG(ERROR) << "Failed to get available stream configurations for " << desc.id;
        ACameraMetadata_free(metadata);
        return ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    // Get available minimum frame durations, which is required.
    ACameraMetadata_const_entry minFrameDurations;
    status = ACameraMetadata_getConstEntry(metadata, ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS,
                                           &minFrameDurations);
    if (status != ACAMERA_OK) {
        LOG(ERROR) << "Failed to get available min frame durations for " << desc.id;
        ACameraMetadata_free(metadata);
        return ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    // Pre-process frame durations into a map for efficient lookup
    std::map<std::tuple<int32_t, int32_t, int32_t>, int64_t> durationMap;
    for (uint32_t i = 0; i < minFrameDurations.count; i += 4) {
        int32_t format = static_cast<int32_t>(minFrameDurations.data.i64[i]);
        int32_t width = static_cast<int32_t>(minFrameDurations.data.i64[i + 1]);
        int32_t height = static_cast<int32_t>(minFrameDurations.data.i64[i + 2]);
        int64_t duration = minFrameDurations.data.i64[i + 3];
        durationMap[std::make_tuple(format, width, height)] = duration;
    }

    // Get sensor orientation
    ACameraMetadata_const_entry orientationEntry;
    int32_t orientation = -1;
    status = ACameraMetadata_getConstEntry(metadata, ACAMERA_SENSOR_ORIENTATION, &orientationEntry);
    if (status == ACAMERA_OK && orientationEntry.count > 0) {
        orientation = orientationEntry.data.i32[0];
    } else {
        LOG(WARNING) << "Failed to get sensor orientation for " << desc.id;
        return ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    aidlevs::Rotation rotation;
    switch (orientation) {
        case 0:
            rotation = aidlevs::Rotation::ROTATION_0;
            break;
        case 90:
            rotation = aidlevs::Rotation::ROTATION_90;
            break;
        case 180:
            rotation = aidlevs::Rotation::ROTATION_180;
            break;
        case 270:
            rotation = aidlevs::Rotation::ROTATION_270;
            break;
        default:
            LOG(WARNING) << "Invalid sensor orientation " << orientation;
            ACameraMetadata_free(metadata);
            return ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    std::vector<Stream> streams;
    // The data is in tuples of (format, width, height, input?)
    for (uint32_t i = 0; i < streamConfigs.count; i += 4) {
        int32_t format = streamConfigs.data.i32[i];
        int32_t width = streamConfigs.data.i32[i + 1];
        int32_t height = streamConfigs.data.i32[i + 2];
        aidlevs::StreamType streamType = streamConfigs.data.i32[i + 3] ==
                        ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_INPUT
                ? aidlevs::StreamType::INPUT
                : aidlevs::StreamType::OUTPUT;

        auto it = durationMap.find(std::make_tuple(format, width, height));
        if (it == durationMap.end()) {
            LOG(ERROR) << "No minimum frame duration found for required stream configuration: "
                       << width << "x" << height << " format " << format;
            ACameraMetadata_free(metadata);
            return ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
        }

        int64_t duration = it->second;
        if (duration <= 0) {
            LOG(ERROR) << "Invalid frame duration " << duration << " for " << width << "x" << height
                       << " format " << format;
            ACameraMetadata_free(metadata);
            return ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
        }
        int32_t framerate = 1000000000 / duration;

        Stream stream = {
                .id = static_cast<int32_t>(i / 4),
                .streamType = streamType,
                .width = width,
                .height = height,
                .format = static_cast<::aidl::android::hardware::graphics::common::PixelFormat>(
                        format),
                .framerate = framerate,
                .usage = ::aidl::android::hardware::graphics::common::BufferUsage::CAMERA_INPUT,
                .rotation = rotation,
        };
        streams.push_back(stream);
    }

    *_aidl_return = std::move(streams);
    ACameraMetadata_free(metadata);
    return ScopedAStatus::ok();
}

ScopedAStatus CompatEnumerator::getUltrasonicsArrayList(
        [[maybe_unused]] std::vector<UltrasonicsArrayDesc>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::isHardware(bool* _aidl_return) {
    *_aidl_return = false;
    return ScopedAStatus::ok();
}

void CompatEnumerator::handleDeviceStatusChange(void* context, ACameraDevice* device,
                                                const char* functionName, const int* error) {
    if (device == nullptr) {
        LOG(ERROR) << functionName << " called with a null device. Ignoring.";
        return;
    }
    const char* cameraId = ACameraDevice_getId(device);
    if (context == nullptr) {
        ACameraDevice_close(device);
        LOG(ERROR) << functionName << " called with a null context for camera " << cameraId
                   << ". Closing the camera device.";
        return;
    }
    CompatEnumerator* self = static_cast<CompatEnumerator*>(context);
    if (error == nullptr) {
        LOG(WARNING) << "Camera device " << cameraId << " disconnected. Removing from active list.";
    } else {
        LOG(ERROR) << "Camera device " << cameraId << " error: " << *error
                   << ". Removing from active list.";
    }
    {
        std::lock_guard lock(self->mLock);
        self->removeActiveCamera(cameraId);
    }
}

void CompatEnumerator::onDeviceDisconnected(void* context, ACameraDevice* device) {
    handleDeviceStatusChange(context, device, __FUNCTION__);
}

void CompatEnumerator::onDeviceError(void* context, ACameraDevice* device, int error) {
    handleDeviceStatusChange(context, device, __FUNCTION__, &error);
}

void CompatEnumerator::removeActiveCamera(const char* cameraId) {
    if (!cameraId) return;
    // mLock is already held by the caller
    auto it = mActiveCameras.find(cameraId);
    if (it != mActiveCameras.end()) {
        if (it->second) {
            it->second->releaseACameraDevice();
        }
        mActiveCameras.erase(it);
        LOG(INFO) << "Removed camera " << cameraId << " from active list.";
    } else {
        LOG(WARNING) << "Camera " << cameraId << " not found in active list for removal.";
    }
}

void CompatEnumerator::cleanupOpenedCameras(const std::vector<std::string>& cameraIds) {
    std::lock_guard lock(mLock);
    for (const auto& idToClose : cameraIds) {
        removeActiveCamera(idToClose.c_str());
    }
}

ScopedAStatus CompatEnumerator::openCamera(const std::string& cameraId, const Stream& streamCfg,
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

                auto desc_it = mCameraDescs.find(id);
                const CameraDesc* desc_ptr = nullptr;
                if (desc_it == mCameraDescs.end()) {
                    LOG(WARNING) << "CameraDesc not found for camera ID " << id
                                 << ". Proceeding with null CameraDesc.";
                } else {
                    desc_ptr = &desc_it->second;
                }

                std::shared_ptr<CompatHalCamera> halCamera =
                        ::ndk::SharedRefBase::make<CompatHalCamera>(device, id, desc_ptr,
                                                                    streamCfg);
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

        // If this is a logical camera, set its descriptor.
        if (physicalCameraIds.size() > 1) {
            auto it = mCameraDescs.find(cameraId);
            if (it == mCameraDescs.end()) {
                LOG(ERROR) << "Logical camera " << cameraId << " not found in cache.";
                cleanupOpenedCameras(openedInThisCall);
                return ::ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
            }
            virtualCamera->setDescriptor(&it->second);
        }

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
        const std::shared_ptr<IEvsEnumeratorStatusCallback>& callback) {
    std::lock_guard lock(mLock);
    mDeviceStatusCallbacks.insert(callback);
    return ScopedAStatus::ok();
}

ScopedAStatus CompatEnumerator::getDisplayStateById([[maybe_unused]] int32_t id,
                                                    [[maybe_unused]] DisplayState* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatEnumerator::setCameraGroupMap(const CameraGroupMap& cameraGroupMap) {
    if (!mIsReady) {
        return ::ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    if (mCameraDescs.empty()) {
        ScopedAStatus status = initCameraDescs();
        if (!status.isOk()) {
            return status;
        }
    }

    for (const auto& [logicalId, group] : cameraGroupMap) {
        for (const auto& physicalId : group.physicalIds) {
            if (mCameraDescs.find(physicalId) == mCameraDescs.end()) {
                LOG(ERROR) << "Physical camera ID " << physicalId << " for logical camera "
                           << logicalId << " not found.";
                return ::ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
            }
        }

        CameraDesc logicalDesc;
        logicalDesc.id = logicalId;
        if (!group.logicalCameraMetadata.empty()) {
            logicalDesc.metadata = group.logicalCameraMetadata;
            logicalDesc.vendorFlags = -1;  // Not supported
        } else if (!group.physicalIds.empty()) {
            const std::string& representativeId = group.physicalIds.front();
            auto it = mCameraDescs.find(representativeId);
            if (it != mCameraDescs.end()) {
                logicalDesc.metadata = it->second.metadata;
                logicalDesc.vendorFlags = it->second.vendorFlags;
            } else {
                LOG(WARNING) << "Could not find representative camera " << representativeId
                             << " for logical camera " << logicalId;
                continue;
            }
        } else {
            LOG(WARNING) << "Logical camera " << logicalId
                         << " has no physical cameras and no metadata.";
            continue;
        }
        mCameraDescs.insert_or_assign(logicalId, std::move(logicalDesc));
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

    // Not in the group map, check if it's a known physical camera ID in mCameraDescs
    auto it = mCameraDescs.find(cameraId);
    if (it != mCameraDescs.end()) {
        // It's a physical camera
        return {cameraId};
    }
    // Not found anywhere
    LOG(WARNING) << "Camera ID " << cameraId << " not found as logical or physical camera.";
    return {};
}

void CompatEnumerator::broadcastDeviceStatusChange(const std::vector<aidlevs::DeviceStatus>& list) {
    std::lock_guard lock(mLock);
    auto it = mDeviceStatusCallbacks.begin();
    while (it != mDeviceStatusCallbacks.end()) {
        ScopedAStatus status = (*it)->deviceStatusChanged(list);
        if (!status.isOk()) {
            // Remove callbacks that fail to process status changes. Update the iterator to the next
            // valid element after removal to ensure the loop continues correctly.
            it = mDeviceStatusCallbacks.erase(it);
        } else {
            ++it;
        }
    }
}

void CompatEnumerator::notifyDeviceStatusChange(const char* cameraId, DeviceStatusType statusType) {
    std::vector<DeviceStatus> statusList(1);
    statusList[0] = DeviceStatus{
            .id = cameraId,
            .status = statusType,
    };
    broadcastDeviceStatusChange(statusList);
}

void CompatEnumerator::onCameraAvailable(void* context, const char* cameraId) {
    LOG(INFO) << "Camera " << cameraId << " is available.";
    if (!context) return;
    CompatEnumerator* self = static_cast<CompatEnumerator*>(context);
    self->notifyDeviceStatusChange(cameraId, DeviceStatusType::CAMERA_AVAILABLE);
}

void CompatEnumerator::onCameraUnavailable(void* context, const char* cameraId) {
    LOG(INFO) << "Camera " << cameraId << " is unavailable.";
    if (!context) return;
    CompatEnumerator* self = static_cast<CompatEnumerator*>(context);
    self->notifyDeviceStatusChange(cameraId, DeviceStatusType::CAMERA_NOT_AVAILABLE);
}

}  // namespace android::hardware::automotive::evs::compat
