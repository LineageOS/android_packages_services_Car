/*
 * Copyright 2020 The Android Open Source Project
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

#include "CameraUtils.h"

#include <android-base/logging.h>
#include <android/hardware/automotive/evs/1.1/types.h>

using namespace android::hardware::automotive::evs::V1_1;

using ::android::sp;
using ::std::string;
using ::std::vector;

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace V1_0 {
namespace implementation {

bool isLogicalCamera(const camera_metadata_t* metadata) {
    if (metadata == nullptr) {
        // A logical camera device must have a valid camera metadata.
        return false;
    }

    // Looking for LOGICAL_MULTI_CAMERA capability from metadata.
    camera_metadata_ro_entry_t entry;
    int rc =
        find_camera_metadata_ro_entry(metadata,
                                      ANDROID_REQUEST_AVAILABLE_CAPABILITIES,
                                      &entry);
    if (0 != rc) {
        // No capabilities are found.
        return false;
    }

    for (size_t i = 0; i < entry.count; ++i) {
        uint8_t cap = entry.data.u8[i];
        if (cap ==
            ANDROID_REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
            return true;
        }
    }

    return false;
}

vector<string> getPhysicalCameraIds(sp<IEvsCamera> camera) {
    if (camera == nullptr) {
        LOG(WARNING) << __FUNCTION__ << "The EVS camera object is invalid";
        return {};
    }

    CameraDesc desc;
    camera->getCameraInfo_1_1([&desc](const CameraDesc& info) {
        desc = info;
    });

    vector<string> physicalCameras;
    const camera_metadata_t* metadata =
        reinterpret_cast<camera_metadata_t*>(&desc.metadata[0]);

    if (!isLogicalCamera(metadata)) {
        // EVS assumes that the device w/o a valid metadata is a physical
        // device.
        LOG(INFO) << desc.v1.cameraId << " is not a logical camera device.";
        physicalCameras.emplace_back(desc.v1.cameraId);
        return physicalCameras;
    }

    // Look for physical camera identifiers
    camera_metadata_ro_entry entry;
    int rc =
        find_camera_metadata_ro_entry(metadata,
                                      ANDROID_LOGICAL_MULTI_CAMERA_PHYSICAL_IDS,
                                      &entry);
    if (rc != 0) {
        LOG(ERROR) << "No physical camera ID is found for "
                   << desc.v1.cameraId;
        return {};
    }

    const uint8_t* ids = entry.data.u8;
    size_t start = 0;
    for (size_t i = 0; i < entry.count; ++i) {
        if (ids[i] == '\0') {
            if (start != i) {
                string id(reinterpret_cast<const char*>(ids + start));
                physicalCameras.emplace_back(id);
            }
            start = i + 1;
        }
    }

    LOG(INFO) << desc.v1.cameraId << " consists of " << physicalCameras.size()
              << " physical camera devices";
    return physicalCameras;
}

string tagToString(uint32_t tag) {
    switch (tag) {
        case ANDROID_LENS_DISTORTION:
            return "ANDROID_LENS_DISTORTION";
        case ANDROID_LENS_INTRINSIC_CALIBRATION:
            return "ANDROID_LENS_INTRINSIC_CALIBRATION";
        case ANDROID_LENS_POSE_TRANSLATION:
            return "ANDROID_LENS_POSE_TRANSLATION";
        case ANDROID_LENS_POSE_ROTATION:
            return "ANDROID_LENS_POSE_ROTATION";
        default:
            LOG(WARNING) << "Cannot recognize the tag: " << tag;
            return {};
    }
}

bool getParam(const camera_metadata_t* metadata,
              uint32_t tag,
              int size,
              float* param) {
    camera_metadata_ro_entry_t entry = camera_metadata_ro_entry_t();
    int rc = find_camera_metadata_ro_entry(metadata, tag, &entry);

    if (rc != 0) {
        LOG(ERROR) << "No metadata found for " << tagToString(tag);
        return false;
    }

    if (entry.count != size || entry.type != TYPE_FLOAT) {
        LOG(ERROR) << "Unexpected size or type for " << tagToString(tag);
        return false;
    }

    const float* lensParam = entry.data.f;
    for (int i = 0; i < size; i++) {
        param[i] = lensParam[i];
    }
    return true;
}

bool getAndroidCameraParams(sp<IEvsCamera> camera,
                            string cameraId,
                            AndroidCameraParams& params) {
    if (camera == nullptr) {
        LOG(WARNING) << __FUNCTION__ << "The EVS camera object is invalid";
        return {};
    }

    CameraDesc desc = {};
    camera->getPhysicalCameraInfo(cameraId, [&desc](const CameraDesc& info) {
        desc = info;
    });

    if (desc.metadata.size() == 0) {
        LOG(ERROR) << "No metadata found for " << desc.v1.cameraId;
        return false;
    }

    const camera_metadata_t* metadata =
        reinterpret_cast<camera_metadata_t*>(&desc.metadata[0]);

    // Look for ANDROID_LENS_DISTORTION
    if (!getParam(metadata,
                  ANDROID_LENS_DISTORTION,
                  kSizeLensDistortion,
                  &params.lensDistortion[0])) {
        return false;
    }

    // Look for ANDROID_LENS_INTRINSIC_CALIBRATION
    if (!getParam(metadata,
                  ANDROID_LENS_INTRINSIC_CALIBRATION,
                  kSizeLensIntrinsicCalibration,
                  &params.lensIntrinsicCalibration[0])) {
        return false;
    }

    // Look for ANDROID_LENS_POSE_TRANSLATION
    if (!getParam(metadata,
                  ANDROID_LENS_POSE_TRANSLATION,
                  kSizeLensPoseTranslation,
                  &params.lensPoseTranslation[0])) {
        return false;
    }

    // Look for ANDROID_LENS_POSE_ROTATION
    if (!getParam(metadata,
                  ANDROID_LENS_POSE_ROTATION,
                  kSizeLensPoseRotation,
                  &params.lensPoseRotation[0])) {
        return false;
    }

    return true;
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android

