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

#include <android-base/parseint.h>
#include <android-base/strings.h>
#include <android-base/logging.h>
#include <hwbinder/IPCThreadState.h>
#include <cutils/android_filesystem_config.h>

#include "Enumerator.h"
#include "HalDisplay.h"

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

using ::android::base::EqualsIgnoreCase;
using CameraDesc_1_0 = ::android::hardware::automotive::evs::V1_0::CameraDesc;
using CameraDesc_1_1 = ::android::hardware::automotive::evs::V1_1::CameraDesc;

bool Enumerator::init(const char* hardwareServiceName) {
    LOG(DEBUG) << "init";

    // Connect with the underlying hardware enumerator
    mHwEnumerator = IEvsEnumerator::getService(hardwareServiceName);
    bool result = (mHwEnumerator.get() != nullptr);
    if (result) {
        // Get an internal display identifier.
        mHwEnumerator->getDisplayIdList(
            [this](const auto& displayPorts) {
                if (displayPorts.size() > 0) {
                    mInternalDisplayPort = displayPorts[0];
                } else {
                    LOG(WARNING) << "No display is available to EVS service.";
                }
            }
        );
    }

    return result;
}


bool Enumerator::checkPermission() {
    hardware::IPCThreadState *ipc = hardware::IPCThreadState::self();
    const auto userId = ipc->getCallingUid() / AID_USER_OFFSET;
    const auto appId = ipc->getCallingUid() % AID_USER_OFFSET;
#ifdef EVS_DEBUG
    if (AID_AUTOMOTIVE_EVS != appId && AID_ROOT != appId && AID_SYSTEM != appId) {
#else
    if (AID_AUTOMOTIVE_EVS != appId && AID_SYSTEM != appId) {
#endif
        LOG(ERROR) << "EVS access denied? "
                   << "pid = " << ipc->getCallingPid()
                   << ", userId = " << userId
                   << ", appId = " << appId;
        return false;
    }

    return true;
}


bool Enumerator::isLogicalCamera(const camera_metadata_t *metadata) {
    bool found = false;

    if (metadata == nullptr) {
        LOG(ERROR) << "Metadata is null";
        return found;
    }

    camera_metadata_ro_entry_t entry;
    int rc = find_camera_metadata_ro_entry(metadata,
                                           ANDROID_REQUEST_AVAILABLE_CAPABILITIES,
                                           &entry);
    if (0 != rc) {
        // No capabilities are found in metadata.
        LOG(DEBUG) << __FUNCTION__ << " does not find a target entry";
        return found;
    }

    for (size_t i = 0; i < entry.count; ++i) {
        uint8_t capability = entry.data.u8[i];
        if (capability == ANDROID_REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
            found = true;
            break;
        }
    }

    if (!found) {
        LOG(DEBUG) << __FUNCTION__ << " does not find a logical multi camera cap";
    }
    return found;
}


std::unordered_set<std::string> Enumerator::getPhysicalCameraIds(const std::string& id) {
    std::unordered_set<std::string> physicalCameras;
    if (mCameraDevices.find(id) == mCameraDevices.end()) {
        LOG(ERROR) << "Queried device " << id << " does not exist!";
        return physicalCameras;
    }

    const camera_metadata_t *metadata =
        reinterpret_cast<camera_metadata_t *>(&mCameraDevices[id].metadata[0]);
    if (!isLogicalCamera(metadata)) {
        // EVS assumes that the device w/o a valid metadata is a physical
        // device.
        LOG(INFO) << id << " is not a logical camera device.";
        physicalCameras.emplace(id);
        return physicalCameras;
    }

    camera_metadata_ro_entry entry;
    int rc = find_camera_metadata_ro_entry(metadata,
                                           ANDROID_LOGICAL_MULTI_CAMERA_PHYSICAL_IDS,
                                           &entry);
    if (0 != rc) {
        LOG(ERROR) << "No physical camera ID is found for a logical camera device " << id;
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

    LOG(INFO) << id << " consists of "
               << physicalCameras.size() << " physical camera devices.";
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
    LOG(DEBUG) << __FUNCTION__;
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
            LOG(ERROR) << "Failed to open hardware camera " << cameraId;
        } else {
            hwCamera = new HalCamera(device, cameraId);
            if (hwCamera == nullptr) {
                LOG(ERROR) << "Failed to allocate camera wrapper object";
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
        LOG(ERROR) << "Requested camera " << cameraId
                   << " not found or not available";
    }

    // Send the virtual camera object back to the client by strong pointer which will keep it alive
    return clientCamera;
}


Return<void> Enumerator::closeCamera(const ::android::sp<IEvsCamera_1_0>& clientCamera) {
    LOG(DEBUG) << __FUNCTION__;

    if (clientCamera.get() == nullptr) {
        LOG(ERROR) << "Ignoring call with null camera pointer.";
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
    LOG(DEBUG) << __FUNCTION__;
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
                LOG(ERROR) << "Failed to open hardware camera " << cameraId;
                success = false;
                break;
            } else {
                hwCamera = new HalCamera(device, id, streamCfg);
                if (hwCamera == nullptr) {
                    LOG(ERROR) << "Failed to allocate camera wrapper object";
                    mHwEnumerator->closeCamera(device);
                    success = false;
                    break;
                } else if (!hwCamera->isSyncSupported()) {
                    LOG(INFO) << id << " does not support a sw_sync.";
                    if (physicalCameras.size() > 1) {
                        LOG(ERROR) << "sw_sync is required for logical camera devices.";
                        success = false;
                        break;
                    }
                }
            }

            // Add the hardware camera to our list, which will keep it alive via ref count
            mActiveCameras.try_emplace(id, hwCamera);
            sourceCameras.push_back(hwCamera);
        } else {
            if (it->second->getStreamConfig().id != streamCfg.id) {
                LOG(WARNING) << "Requested camera is already active in different configuration.";
            } else {
                sourceCameras.push_back(it->second);
            }
        }
    }

    if (!success || sourceCameras.size() < 1) {
        LOG(ERROR) << "Failed to open any physical camera device";
        return nullptr;
    }

    // TODO(b/147170360): Implement a logic to handle a failure.
    // 3. Create a proxy camera object
    sp<VirtualCamera> clientCamera = new VirtualCamera(sourceCameras);
    if (clientCamera == nullptr) {
        // TODO: Any resource needs to be cleaned up explicitly?
        LOG(ERROR) << "Failed to create a client camera object";
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
                LOG(ERROR) << hwCamera->getId()
                           << " failed to own a created proxy camera object.";
            }
        }
    }

    // Send the virtual camera object back to the client by strong pointer which will keep it alive
    return clientCamera;
}


Return<void> Enumerator::getCameraList_1_1(getCameraList_1_1_cb list_cb)  {
    LOG(DEBUG) << __FUNCTION__;
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
    LOG(DEBUG) << __FUNCTION__;

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
        LOG(ERROR) << "EVS Display unavailable";

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
    LOG(DEBUG) << __FUNCTION__;

    sp<IEvsDisplay_1_0> pActiveDisplay = mActiveDisplay.promote();

    // Drop the active display
    if (display.get() != pActiveDisplay.get()) {
        LOG(WARNING) << "Ignoring call to closeDisplay with unrecognized display object.";
    } else {
        // Pass this request through to the hardware layer
        sp<HalDisplay> halDisplay = reinterpret_cast<HalDisplay *>(pActiveDisplay.get());
        mHwEnumerator->closeDisplay(halDisplay->getHwDisplay());
        mActiveDisplay = nullptr;
    }

    return Void();
}


Return<EvsDisplayState> Enumerator::getDisplayState()  {
    LOG(DEBUG) << __FUNCTION__;
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
    LOG(DEBUG) << __FUNCTION__;

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
        LOG(ERROR) << "EVS Display unavailable";

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


Return<void> Enumerator::debug(const hidl_handle& fd,
                               const hidl_vec<hidl_string>& options) {
    if (fd.getNativeHandle() != nullptr && fd->numFds > 0) {
        cmdDump(fd->data[0], options);
    } else {
        LOG(ERROR) << "Invalid parameters";
    }

    return {};
}


void Enumerator::cmdDump(int fd, const hidl_vec<hidl_string>& options) {
    if (options.size() == 0) {
        dprintf(fd, "No option is given");
        return;
    }

    const std::string option = options[0];
    if (EqualsIgnoreCase(option, "--help")) {
        cmdHelp(fd);
    } else if (EqualsIgnoreCase(option, "--list")) {
        cmdList(fd, options);
    } else if (EqualsIgnoreCase(option, "--dump")) {
        cmdDumpDevice(fd, options);
    } else {
        dprintf(fd, "Invalid option: %s\n", option.c_str());
    }
}


void Enumerator::cmdHelp(int fd) {
    dprintf(fd, "Usage: \n\n");
    dprintf(fd, "--help: shows this help.\n");
    dprintf(fd, "--list [all|camera|display]: list camera or display devices or both "
                "available to EVS manager.\n");
    dprintf(fd, "--dump [all|camera|display] <device id>: "
                "show current status of the target device or all devices "
                "when no device is given.\n");
}


void Enumerator::cmdList(int fd, const hidl_vec<hidl_string>& options) {
    bool listCameras = true;
    bool listDisplays = true;
    if (options.size() > 1) {
        const std::string option = options[1];
        const bool listAll = EqualsIgnoreCase(option, "all");
        listCameras = listAll || EqualsIgnoreCase(option, "camera");
        listDisplays = listAll || EqualsIgnoreCase(option, "display");
        if (!listCameras && !listDisplays) {
            dprintf(fd, "Unrecognized option, %s, is ignored.\n", option.c_str());
        }
    }

    if (listCameras) {
        dprintf(fd, "Camera devices available to EVS service:\n");
        if (mCameraDevices.size() < 1) {
            // Camera devices may not be enumerated yet.
            getCameraList_1_1(
                [](const auto cameras) {
                    if (cameras.size() < 1) {
                        LOG(WARNING) << "No camera device is available to EVS.";
                    }
                });
        }

        for (auto& [id, desc] : mCameraDevices) {
            dprintf(fd, "\t%s\n", id.c_str());
        }

        dprintf(fd, "\nCamera devices currently in use:\n");
        for (auto& [id, ptr] : mActiveCameras) {
            dprintf(fd, "\t%s\n", id.c_str());
        }
        dprintf(fd, "\n");
    }

    if (listDisplays) {
        if (mHwEnumerator != nullptr) {
            dprintf(fd, "Display devices available to EVS service:\n");
            // Get an internal display identifier.
            mHwEnumerator->getDisplayIdList(
                [&](const auto& displayPorts) {
                    for (auto& port : displayPorts) {
                        dprintf(fd, "\tdisplay port %u\n", (unsigned)port);
                    }
                }
            );
        }
    }
}


void Enumerator::cmdDumpDevice(int fd, const hidl_vec<hidl_string>& options) {
    bool dumpCameras = true;
    bool dumpDisplays = true;
    if (options.size() > 1) {
        const std::string option = options[1];
        const bool dumpAll = EqualsIgnoreCase(option, "all");
        dumpCameras = dumpAll || EqualsIgnoreCase(option, "camera");
        dumpDisplays = dumpAll || EqualsIgnoreCase(option, "display");
        if (!dumpCameras && !dumpDisplays) {
            dprintf(fd, "Unrecognized option, %s, is ignored.\n", option.c_str());
        }
    }

    if (dumpCameras) {
        const bool dumpAllCameras = options.size() < 3;
        std::string deviceId = "";
        if (!dumpAllCameras) {
            deviceId = options[2];
        }

        for (auto& [id, ptr] : mActiveCameras) {
            if (!dumpAllCameras && !EqualsIgnoreCase(id, deviceId)) {
                continue;
            }
            ptr->dump(fd);
        }
    }

    if (dumpDisplays) {
        dprintf(fd, "Not implemented yet\n");
    }
}

} // namespace implementation
} // namespace V1_1
} // namespace evs
} // namespace automotive
} // namespace android
