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

#ifndef CPP_EVS_MANAGER_1_1_ENUMERATOR_H_
#define CPP_EVS_MANAGER_1_1_ENUMERATOR_H_

#include "HalCamera.h"
#include "VirtualCamera.h"
#include "emul/EvsEmulatedCamera.h"
#include "stats/StatsCollector.h"

#include <android/hardware/automotive/evs/1.1/IEvsDisplay.h>
#include <android/hardware/automotive/evs/1.1/IEvsEnumerator.h>
#include <android/hardware/camera/device/3.2/ICameraDevice.h>
#include <system/camera_metadata.h>

#include <list>
#include <unordered_map>
#include <unordered_set>

namespace android::automotive::evs::V1_1::implementation {

class Enumerator : public IEvsEnumerator {
public:
    // Methods from ::android::hardware::automotive::evs::V1_0::IEvsEnumerator follow.
    ::android::hardware::Return<void> getCameraList(getCameraList_cb _hidl_cb) override;
    ::android::hardware::Return<sp<::android::hardware::automotive::evs::V1_0::IEvsCamera>>
    openCamera(const ::android::hardware::hidl_string& cameraId) override;
    ::android::hardware::Return<void> closeCamera(
            const ::android::sp<IEvsCamera_1_0>& virtualCamera) override;
    ::android::hardware::Return<sp<::android::hardware::automotive::evs::V1_0::IEvsDisplay>>
    openDisplay() override;
    ::android::hardware::Return<void> closeDisplay(
            const ::android::sp<::android::hardware::automotive::evs::V1_0::IEvsDisplay>& display)
            override;
    ::android::hardware::Return<::android::hardware::automotive::evs::V1_0::DisplayState>
    getDisplayState() override;

    // Methods from ::android::hardware::automotive::evs::V1_1::IEvsEnumerator follow.
    ::android::hardware::Return<void> getCameraList_1_1(getCameraList_1_1_cb _hidl_cb) override;
    ::android::hardware::Return<sp<::android::hardware::automotive::evs::V1_1::IEvsCamera>>
    openCamera_1_1(const ::android::hardware::hidl_string& cameraId,
                   const ::android::hardware::camera::device::V3_2::Stream& streamCfg) override;
    ::android::hardware::Return<bool> isHardware() override { return false; }
    ::android::hardware::Return<void> getDisplayIdList(getDisplayIdList_cb _list_cb) override;
    ::android::hardware::Return<sp<::android::hardware::automotive::evs::V1_1::IEvsDisplay>>
    openDisplay_1_1(uint8_t id) override;
    ::android::hardware::Return<void> getUltrasonicsArrayList(
            getUltrasonicsArrayList_cb _hidl_cb) override;
    ::android::hardware::Return<sp<IEvsUltrasonicsArray>> openUltrasonicsArray(
            const ::android::hardware::hidl_string& ultrasonicsArrayId) override;
    ::android::hardware::Return<void> closeUltrasonicsArray(
            const ::android::sp<IEvsUltrasonicsArray>& evsUltrasonicsArray) override;

    // Methods from ::android.hidl.base::V1_0::IBase follow.
    ::android::hardware::Return<void> debug(
            const hidl_handle& fd,
            const hidl_vec<::android::hardware::hidl_string>& options) override;

    // Implementation details
    bool init(const char* hardwareServiceName);

    // Destructor
    virtual ~Enumerator();

private:
    bool inline checkPermission();
    bool isLogicalCamera(const camera_metadata_t* metadata);
    std::unordered_set<std::string> getPhysicalCameraIds(const std::string& id);

    sp<::android::hardware::automotive::evs::V1_1::IEvsEnumerator> mHwEnumerator;
    wp<::android::hardware::automotive::evs::V1_0::IEvsDisplay> mActiveDisplay;

    // List of active camera proxy objects that wrap hw cameras
    std::unordered_map<std::string, sp<HalCamera>> mActiveCameras;

    // List of camera descriptors of enumerated hw cameras
    std::unordered_map<std::string, CameraDesc> mCameraDevices;

    // List of available physical display devices
    std::list<uint8_t> mDisplayPorts;

    // Display port the internal display is connected to.
    uint8_t mInternalDisplayPort;

    // Collecting camera usage statistics from clients
    sp<StatsCollector> mClientsMonitor;

    // Boolean flag to tell whether the camera usages are being monitored or not
    bool mMonitorEnabled;

    // Boolean flag to tell whether EvsDisplay is owned exclusively or not
    bool mDisplayOwnedExclusively;

    // LSHAL dump
    void cmdDump(int fd, const hidl_vec<::android::hardware::hidl_string>& options);
    void cmdHelp(int fd);
    void cmdList(int fd, const hidl_vec<::android::hardware::hidl_string>& options);
    void cmdDumpDevice(int fd, const hidl_vec<::android::hardware::hidl_string>& options);

    // List of emulated camera devices
    std::unordered_map<std::string, EmulatedCameraDesc> mEmulatedCameraDevices;

    // LSHAL command to use emulated camera device
    void cmdConfigureEmulatedCamera(int fd,
                                    const hidl_vec<::android::hardware::hidl_string>& options);
};

}  // namespace android::automotive::evs::V1_1::implementation

#endif  // CPP_EVS_MANAGER_1_1_ENUMERATOR_H_
