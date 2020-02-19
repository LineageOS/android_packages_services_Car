/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <hwbinder/IPCThreadState.h>
#include <cutils/android_filesystem_config.h>

#include "Enumerator.h"
#include "HalDisplay.h"

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

using CameraDesc_1_0 = ::android::hardware::automotive::evs::V1_0::CameraDesc;
using CameraDesc_1_1 = ::android::hardware::automotive::evs::V1_1::CameraDesc;

bool Enumerator::init(const char* hardwareServiceName) {
    ALOGD("init");

    // Connect with the underlying hardware enumerator
    mHwEnumerator = IEvsEnumerator::getService(hardwareServiceName);
    bool result = (mHwEnumerator.get() != nullptr);

    return result;
}


bool Enumerator::checkPermission() {
    hardware::IPCThreadState *ipc = hardware::IPCThreadState::self();
    if (AID_AUTOMOTIVE_EVS != ipc->getCallingUid() &&
        AID_ROOT != ipc->getCallingUid()) {

        ALOGE("EVS access denied?: pid = %d, uid = %d", ipc->getCallingPid(), ipc->getCallingUid());
        return false;
    }

    return true;
}


bool Enumerator::isLogicalCamera(const camera_metadata_t *metadata) {
    bool found = false;

    if (metadata == nullptr) {
        ALOGE("Metadata is null");
        return found;
    }

    camera_metadata_ro_entry_t entry;
    int rc = find_camera_metadata_ro_entry(metadata,
                                           ANDROID_REQUEST_AVAILABLE_CAPABILITIES,
                                           &entry);
    if (0 != rc) {
        // No capabilities are found in metadata.
        ALOGD("%s does not find a target entry", __FUNCTION__);
        return found;
    }

    for (size_t i = 0; i < entry.count; ++i) {
        uint8_t capability = entry.data.u8[i];
        if (capability == ANDROID_REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
            found = true;
            break;
        }
    }

    ALOGE_IF(!found, "%s does not find a logical multi camera cap", __FUNCTION__);
    return found;
}


std::unordered_set<std::string> Enumerator::getPhysicalCameraIds(const std::string& id) {
    std::unordered_set<std::string> physicalCameras;
    if (mCameraDevices.find(id) == mCameraDevices.end()) {
        ALOGE("Queried device %s does not exist!", id.c_str());
        return physicalCameras;
    }

    const camera_metadata_t *metadata =
        reinterpret_cast<camera_metadata_t *>(&mCameraDevices[id].metadata[0]);
    if (!isLogicalCamera(metadata)) {
        // EVS assumes that the device w/o a valid metadata is a physical
        // device.
        ALOGI("%s is not a logical camera device.", id.c_str());
        physicalCameras.emplace(id);
        return physicalCameras;
    }

    camera_metadata_ro_entry entry;
    int rc = find_camera_metadata_ro_entry(metadata,
                                           ANDROID_LOGICAL_MULTI_CAMERA_PHYSICAL_IDS,
                                           &entry);
    if (0 != rc) {
        ALOGE("No physical camera ID is found for a logical camera device %s!", id.c_str());
        return physicalCameras;
    }

    const uint8_t *ids = entry.data.u8;
    size_t start = 0;
    for (size_t i = 0; i < entry.count; ++i) {
        if (ids[i] == '\0') {
            if (start != i) {
                std::string id(reinterpret_cast<const char *>(ids + start));
                physicalCameras.emplace(id);
            }
            start = i + 1;
        }
    }

    ALOGE("%s consists of %d physical camera devices.", id.c_str(), (int)physicalCameras.size());
    return physicalCameras;
}


// Methods from ::android::hardware::automotive::evs::V1_0::IEvsEnumerator follow.
Return<void> Enumerator::getCameraList(getCameraList_cb list_cb)  {
    hardware::hidl_vec<CameraDesc_1_0> cameraList;
    mHwEnumerator->getCameraList_1_1([&cameraList](auto cameraList_1_1) {
        cameraList.resize(cameraList_1_1.size());
        unsigned i = 0;
        for (auto&& cam : cameraList_1_1) {
            cameraList[i++] = cam.v1;
        }
    });

    list_cb(cameraList);

    return Void();
}


Return<sp<IEvsCamera_1_0>> Enumerator::openCamera(const hidl_string& cameraId) {
    ALOGD("openCamera");
    if (!checkPermission()) {
        return nullptr;
    }

    // Is the underlying hardware camera already open?
    sp<HalCamera> hwCamera;
    if (mActiveCameras.find(cameraId) != mActiveCameras.end()) {
        hwCamera = mActiveCameras[cameraId];
    } else {
        // Is the hardware camera available?
        sp<IEvsCamera_1_1> device =
            IEvsCamera_1_1::castFrom(mHwEnumerator->openCamera(cameraId))
            .withDefault(nullptr);
        if (device == nullptr) {
            ALOGE("Failed to open hardware camera %s", cameraId.c_str());
        } else {
            hwCamera = new HalCamera(device, cameraId);
            if (hwCamera == nullptr) {
                ALOGE("Failed to allocate camera wrapper object");
                mHwEnumerator->closeCamera(device);
            }
        }
    }

    // Construct a virtual camera wrapper for this hardware camera
    sp<VirtualCamera> clientCamera;
    if (hwCamera != nullptr) {
        clientCamera = hwCamera->makeVirtualCamera();
    }

    // Add the hardware camera to our list, which will keep it alive via ref count
    if (clientCamera != nullptr) {
        mActiveCameras.try_emplace(cameraId, hwCamera);
    } else {
        ALOGE("Requested camera %s not found or not available", cameraId.c_str());
    }

    // Send the virtual camera object back to the client by strong pointer which will keep it alive
    return clientCamera;
}


Return<void> Enumerator::closeCamera(const ::android::sp<IEvsCamera_1_0>& clientCamera) {
    ALOGD("closeCamera");

    if (clientCamera.get() == nullptr) {
        ALOGE("Ignoring call with null camera pointer.");
        return Void();
    }

    // All our client cameras are actually VirtualCamera objects
    sp<VirtualCamera> virtualCamera = reinterpret_cast<VirtualCamera *>(clientCamera.get());

    // Find the parent camera that backs this virtual camera
    for (auto&& halCamera : virtualCamera->getHalCameras()) {
        // Tell the virtual camera's parent to clean it up and drop it
        // NOTE:  The camera objects will only actually destruct when the sp<> ref counts get to
        //        zero, so it is important to break all cyclic references.
        halCamera->disownVirtualCamera(virtualCamera);

        // Did we just remove the last client of this camera?
        if (halCamera->getClientCount() == 0) {
            // Take this now unused camera out of our list
            // NOTE:  This should drop our last reference to the camera, resulting in its
            //        destruction.
            mActiveCameras.erase(halCamera->getId());
        }
    }

    // Make sure the virtual camera's stream is stopped
    virtualCamera->stopVideoStream();

    return Void();
}


// Methods from ::android::hardware::automotive::evs::V1_1::IEvsEnumerator follow.
Return<sp<IEvsCamera_1_1>> Enumerator::openCamera_1_1(const hidl_string& cameraId,
                                                      const Stream& streamCfg) {
    ALOGD("openCamera_1_1");
    if (!checkPermission()) {
        return nullptr;
    }

    // If hwCamera is null, a requested camera device is either a logical camera
    // device or a hardware camera, which is not being used now.
    std::unordered_set<std::string> physicalCameras = getPhysicalCameraIds(cameraId);
    std::vector<sp<HalCamera>> sourceCameras;
    sp<HalCamera> hwCamera;
    bool success = true;

    // 1. Try to open inactive camera devices.
    for (auto&& id : physicalCameras) {
        auto it = mActiveCameras.find(id);
        if (it == mActiveCameras.end()) {
            // Try to open a hardware camera.
            sp<IEvsCamera_1_1> device =
                IEvsCamera_1_1::castFrom(mHwEnumerator->openCamera_1_1(id, streamCfg))
                .withDefault(nullptr);
            if (device == nullptr) {
                ALOGE("Failed to open hardware camera %s", cameraId.c_str());
                success = false;
                break;
            } else {
                hwCamera = new HalCamera(device, id, streamCfg);
                if (hwCamera == nullptr) {
                    ALOGE("Failed to allocate camera wrapper object");
                    mHwEnumerator->closeCamera(device);
                    success = false;
                    break;
                }
            }

            // Add the hardware camera to our list, which will keep it alive via ref count
            mActiveCameras.try_emplace(id, hwCamera);
            sourceCameras.push_back(hwCamera);
        } else {
            if (it->second->getStreamConfig().id != streamCfg.id) {
                ALOGW("Requested camera is already active in different configuration.");
            } else {
                sourceCameras.push_back(it->second);
            }
        }
    }

    if (sourceCameras.size() < 1) {
        ALOGE("Failed to open any physical camera device");
        return nullptr;
    }

    // TODO(b/147170360): Implement a logic to handle a failure.
    // 3. Create a proxy camera object
    sp<VirtualCamera> clientCamera = new VirtualCamera(sourceCameras);
    if (clientCamera == nullptr) {
        // TODO: Any resource needs to be cleaned up explicitly?
        ALOGE("Failed to create a client camera object");
    } else {
        if (physicalCameras.size() > 1) {
            // VirtualCamera, which represents a logical device, caches its
            // descriptor.
            clientCamera->setDescriptor(&mCameraDevices[cameraId]);
        }

        // 4. Owns created proxy camera object
        for (auto&& hwCamera : sourceCameras) {
            if (!hwCamera->ownVirtualCamera(clientCamera)) {
                // TODO: Remove a referece to this camera from a virtual camera
                // object.
                ALOGE("%s failed to own a created proxy camera object.",
                      hwCamera->getId().c_str());
            }
        }
    }

    // Send the virtual camera object back to the client by strong pointer which will keep it alive
    return clientCamera;
}


Return<void> Enumerator::getCameraList_1_1(getCameraList_1_1_cb list_cb)  {
    ALOGD("getCameraList");
    if (!checkPermission()) {
        return Void();
    }

    hardware::hidl_vec<CameraDesc_1_1> hidlCameras;
    mHwEnumerator->getCameraList_1_1(
        [&hidlCameras](hardware::hidl_vec<CameraDesc_1_1> enumeratedCameras) {
            hidlCameras.resize(enumeratedCameras.size());
            unsigned count = 0;
            for (auto&& camdesc : enumeratedCameras) {
                hidlCameras[count++] = camdesc;
            }
        }
    );

    // Update the cached device list
    mCameraDevices.clear();
    for (auto&& desc : hidlCameras) {
        mCameraDevices.insert_or_assign(desc.v1.cameraId, desc);
    }

    list_cb(hidlCameras);
    return Void();
}


Return<sp<IEvsDisplay_1_0>> Enumerator::openDisplay() {
    ALOGD("openDisplay");

    if (!checkPermission()) {
        return nullptr;
    }

    // We simply keep track of the most recently opened display instance.
    // In the underlying layers we expect that a new open will cause the previous
    // object to be destroyed.  This avoids any race conditions associated with
    // create/destroy order and provides a cleaner restart sequence if the previous owner
    // is non-responsive for some reason.
    // Request exclusive access to the EVS display
    sp<IEvsDisplay_1_0> pActiveDisplay = mHwEnumerator->openDisplay();
    if (pActiveDisplay == nullptr) {
        ALOGE("EVS Display unavailable");

        return nullptr;
    }

    // Remember (via weak pointer) who we think the most recently opened display is so that
    // we can proxy state requests from other callers to it.
    // TODO: Because of b/129284474, an additional class, HalDisplay, has been defined and
    // wraps the IEvsDisplay object the driver returns.  We may want to remove this
    // additional class when it is fixed properly.
    sp<IEvsDisplay_1_0> pHalDisplay = new HalDisplay(pActiveDisplay);
    mActiveDisplay = pHalDisplay;

    return pHalDisplay;
}


Return<void> Enumerator::closeDisplay(const ::android::sp<IEvsDisplay_1_0>& display) {
    ALOGD("closeDisplay");

    sp<IEvsDisplay_1_0> pActiveDisplay = mActiveDisplay.promote();

    // Drop the active display
    if (display.get() != pActiveDisplay.get()) {
        ALOGW("Ignoring call to closeDisplay with unrecognized display object.");
    } else {
        // Pass this request through to the hardware layer
        sp<HalDisplay> halDisplay = reinterpret_cast<HalDisplay *>(pActiveDisplay.get());
        mHwEnumerator->closeDisplay(halDisplay->getHwDisplay());
        mActiveDisplay = nullptr;
    }

    return Void();
}


Return<EvsDisplayState> Enumerator::getDisplayState()  {
    ALOGD("getDisplayState");
    if (!checkPermission()) {
        return EvsDisplayState::DEAD;
    }

    // Do we have a display object we think should be active?
    sp<IEvsDisplay_1_0> pActiveDisplay = mActiveDisplay.promote();
    if (pActiveDisplay != nullptr) {
        // Pass this request through to the hardware layer
        return pActiveDisplay->getDisplayState();
    } else {
        // We don't have a live display right now
        mActiveDisplay = nullptr;
        return EvsDisplayState::NOT_OPEN;
    }
}


Return<sp<IEvsDisplay_1_1>> Enumerator::openDisplay_1_1(uint8_t id) {
    ALOGD("%s", __FUNCTION__);

    if (!checkPermission()) {
        return nullptr;
    }

    // We simply keep track of the most recently opened display instance.
    // In the underlying layers we expect that a new open will cause the previous
    // object to be destroyed.  This avoids any race conditions associated with
    // create/destroy order and provides a cleaner restart sequence if the previous owner
    // is non-responsive for some reason.
    // Request exclusive access to the EVS display
    sp<IEvsDisplay_1_1> pActiveDisplay = mHwEnumerator->openDisplay_1_1(id);
    if (pActiveDisplay == nullptr) {
        ALOGE("EVS Display unavailable");

        return nullptr;
    }

    // Remember (via weak pointer) who we think the most recently opened display is so that
    // we can proxy state requests from other callers to it.
    // TODO: Because of b/129284474, an additional class, HalDisplay, has been defined and
    // wraps the IEvsDisplay object the driver returns.  We may want to remove this
    // additional class when it is fixed properly.
    sp<IEvsDisplay_1_1> pHalDisplay = new HalDisplay(pActiveDisplay);
    mActiveDisplay = pHalDisplay;

    return pHalDisplay;
}


Return<void> Enumerator::getDisplayIdList(getDisplayIdList_cb _list_cb)  {
    return mHwEnumerator->getDisplayIdList(_list_cb);
}


// TODO(b/149874793): Add implementation for EVS Manager and Sample driver
Return<void> Enumerator::getUltrasonicsArrayList(getUltrasonicsArrayList_cb _hidl_cb) {
    hardware::hidl_vec<UltrasonicsArrayDesc> ultrasonicsArrayDesc;
    _hidl_cb(ultrasonicsArrayDesc);
    return Void();
}


// TODO(b/149874793): Add implementation for EVS Manager and Sample driver
Return<sp<IEvsUltrasonicsArray>> Enumerator::openUltrasonicsArray(
        const hidl_string& ultrasonicsArrayId) {
    (void)ultrasonicsArrayId;
    sp<IEvsUltrasonicsArray> pEvsUltrasonicsArray;
    return pEvsUltrasonicsArray;
}


// TODO(b/149874793): Add implementation for EVS Manager and Sample driver
Return<void> Enumerator::closeUltrasonicsArray(
        const ::android::sp<IEvsUltrasonicsArray>& evsUltrasonicsArray)  {
    (void)evsUltrasonicsArray;
    return Void();
}

} // namespace implementation
} // namespace V1_1
} // namespace evs
} // namespace automotive
} // namespace android
