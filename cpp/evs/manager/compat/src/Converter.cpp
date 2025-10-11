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

#include <aidl/android/hardware/automotive/evs/BufferDesc.h>
#include <aidl/android/hardware/automotive/evs/Stream.h>
#include <aidlcommonsupport/NativeHandle.h>
#include <android-base/logging.h>
#include <android/hardware_buffer.h>
#include <camera/NdkCameraMetadata.h>
#include <hardware/gralloc.h>
#include <media/NdkImageReader.h>
#include <private/android/AHardwareBufferHelpers.h>
#include <system/camera_metadata.h>

#include <cstring>
#include <vector>

namespace android::hardware::automotive::evs::compat {

using aidl::android::hardware::automotive::evs::BufferDesc;
using aidl::android::hardware::automotive::evs::CameraDesc;
using aidl::android::hardware::automotive::evs::Stream;

namespace {
std::vector<uint8_t> serializeNdkMetadata(const ACameraMetadata* ndkMetadata) {
    const camera_metadata_t* rawMetadata = reinterpret_cast<const camera_metadata_t*>(ndkMetadata);
    if (!rawMetadata) {
        return {};
    }

    // Validate the metadata structure before trusting it.
    if (validate_camera_metadata_structure(rawMetadata, nullptr) != 0) {
        LOG(ERROR) << "Camera metadata validation failed.";
        return {};
    }

    size_t size = get_camera_metadata_size(rawMetadata);
    const uint8_t* data = reinterpret_cast<const uint8_t*>(rawMetadata);
    return std::vector<uint8_t>(data, data + size);
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

media_status_t Converter::toAImageReader(const Stream& config, int32_t maxImages,
                                         AImageReader** reader) {
    if (!reader) {
        return AMEDIA_ERROR_INVALID_PARAMETER;
    }

    // TODO(b/441577862): Add format conversion from Stream.format to AImageReader format.
    int32_t format = AIMAGE_FORMAT_RGBA_8888;

    media_status_t status =
            AImageReader_newWithUsage(config.width, config.height, format,
                                      GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_SW_READ_OFTEN |
                                              GRALLOC_USAGE_SW_WRITE_OFTEN,
                                      maxImages, reader);
    if (status != AMEDIA_OK) {
        LOG(ERROR) << "Failed to create AImageReader, status: " << status;
    }
    return status;
}

media_status_t Converter::toBufferDesc(AImage* image, uint32_t bufferId,
                                       const std::string& deviceId, BufferDesc& outBufferDesc) {
    if (!image) {
        LOG(ERROR) << "AImage is null";
        return AMEDIA_ERROR_INVALID_PARAMETER;
    }

    AHardwareBuffer* hardwareBuffer = nullptr;
    media_status_t status = AImage_getHardwareBuffer(image, &hardwareBuffer);
    if (status != AMEDIA_OK || !hardwareBuffer) {
        LOG(ERROR) << "Failed to get hardware buffer, status: " << status;
        return status;
    }

    const native_handle_t* handle = AHardwareBuffer_getNativeHandle(hardwareBuffer);
    if (!handle) {
        LOG(ERROR) << "Failed to get native handle from AHardwareBuffer in Converter";
        AHardwareBuffer_release(hardwareBuffer);
        return AMEDIA_ERROR_UNKNOWN;
    }

    auto aidlHandle = ::android::dupToAidl(handle);
    if (aidlHandle.fds.empty() && aidlHandle.ints.empty()) {
        LOG(ERROR) << "Failed to duplicate native handle to AIDL handle, no file descriptors or "
                      "integers";
        AHardwareBuffer_release(hardwareBuffer);
        return AMEDIA_ERROR_UNKNOWN;
    }
    outBufferDesc.buffer.handle = std::move(aidlHandle);
    AHardwareBuffer_release(hardwareBuffer);

    int64_t timestamp = 0;
    AImage_getTimestamp(image, &timestamp);
    outBufferDesc.timestamp = timestamp;
    outBufferDesc.bufferId = bufferId;
    outBufferDesc.deviceId = deviceId;
    // The pixelSizeBytes field is not used by the primary consumer of EVS Manager (Surround View).
    // Additionally, AImage can have multiple planes, each with a different pixel stride,
    // while BufferDesc.pixelSizeBytes expects a single value.  There is no straightforward
    // conversion, so we set it to -1 to indicate it's not meaningfully populated.
    outBufferDesc.pixelSizeBytes = -1;

    return AMEDIA_OK;
}
}  // namespace android::hardware::automotive::evs::compat
