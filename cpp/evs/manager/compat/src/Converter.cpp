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

#include "Converter.h"

#include <android-base/logging.h>
#include <camera/NdkCameraMetadata.h>
#include <system/camera_metadata.h>

#include <cstring>
#include <vector>

namespace android::hardware::automotive::evs::compat {

using aidl::android::hardware::automotive::evs::CameraDesc;

namespace {
std::vector<uint8_t> serializeNdkMetadata(const ACameraMetadata* ndkMetadata) {
    if (!ndkMetadata) {
        return {};
    }
    const camera_metadata_t* metadataBuffer =
            reinterpret_cast<const camera_metadata_t*>(ndkMetadata);
    size_t bufferSize = get_camera_metadata_size(metadataBuffer);
    if (bufferSize == 0) {
        return {};
    }

    std::vector<uint8_t> rawData(bufferSize);
    memcpy(rawData.data(), metadataBuffer, bufferSize);
    return rawData;
}
}  // namespace

CameraDesc Converter::toCameraDesc(const char* cameraId, const ACameraMetadata* metadata,
                                   int vendorFlags) {
    CameraDesc desc;
    desc.id = cameraId;
    desc.vendorFlags = vendorFlags;
    desc.metadata = serializeNdkMetadata(metadata);
    return desc;
}
}  // namespace android::hardware::automotive::evs::compat
