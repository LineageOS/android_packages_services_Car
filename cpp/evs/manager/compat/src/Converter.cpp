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
#include <aidl/android/hardware/automotive/evs/EvsResult.h>
#include <aidl/android/hardware/automotive/evs/Stream.h>
#include <aidl/android/hardware/graphics/common/PixelFormat.h>
#include <aidlcommonsupport/NativeHandle.h>
#include <android-base/logging.h>
#include <android/hardware_buffer.h>
#include <camera/NdkCameraError.h>
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
using aidl::android::hardware::automotive::evs::EvsResult;
using aidl::android::hardware::automotive::evs::Stream;
using aidl::android::hardware::graphics::common::PixelFormat;

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

int32_t toAImageFormat(PixelFormat format) {
    switch (format) {
        case PixelFormat::RGBA_8888:
            return AIMAGE_FORMAT_RGBA_8888;
        case PixelFormat::RGBX_8888:
            return AIMAGE_FORMAT_RGBX_8888;
        case PixelFormat::RGB_888:
            return AIMAGE_FORMAT_RGB_888;
        case PixelFormat::RGB_565:
            return AIMAGE_FORMAT_RGB_565;
        case PixelFormat::RGBA_FP16:
            return AIMAGE_FORMAT_RGBA_FP16;
        case PixelFormat::YCBCR_420_888:
        case PixelFormat::YCRCB_420_SP:
            return AIMAGE_FORMAT_YUV_420_888;
        case PixelFormat::RAW16:
            return AIMAGE_FORMAT_RAW16;
        case PixelFormat::BLOB:
            return AIMAGE_FORMAT_JPEG;
        case PixelFormat::IMPLEMENTATION_DEFINED:
            return AIMAGE_FORMAT_PRIVATE;
        case PixelFormat::RAW_OPAQUE:
            return AIMAGE_FORMAT_RAW_PRIVATE;
        case PixelFormat::RAW10:
            return AIMAGE_FORMAT_RAW10;
        case PixelFormat::RAW12:
            return AIMAGE_FORMAT_RAW12;
        case PixelFormat::DEPTH_16:
            return AIMAGE_FORMAT_DEPTH16;
        case PixelFormat::Y8:
            return AIMAGE_FORMAT_Y8;
        default:
            LOG(WARNING) << "Unsupported PixelFormat: " << static_cast<int32_t>(format)
                         << ", defaulting to AIMAGE_FORMAT_RGBA_8888";
            return AIMAGE_FORMAT_RGBA_8888;
    }
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

    int32_t format = toAImageFormat(config.format);

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

EvsResult Converter::toEvsResult(camera_status_t status) {
    switch (status) {
        case ACAMERA_OK:
            // Operation succeeded.
            return EvsResult::OK;
        case ACAMERA_ERROR_INVALID_PARAMETER:
        case ACAMERA_ERROR_METADATA_NOT_FOUND:
        case ACAMERA_ERROR_STREAM_CONFIGURE_FAIL:
            // Errors related to invalid arguments or configuration.
            return EvsResult::INVALID_ARG;
        case ACAMERA_ERROR_CAMERA_DISCONNECTED:
        case ACAMERA_ERROR_SESSION_CLOSED:
            // Camera/session is no longer available.
            return EvsResult::OWNERSHIP_LOST;
        case ACAMERA_ERROR_NOT_ENOUGH_MEMORY:
            // Memory allocation failure.
            return EvsResult::BUFFER_NOT_AVAILABLE;
        case ACAMERA_ERROR_CAMERA_DEVICE:
        case ACAMERA_ERROR_CAMERA_SERVICE:
        case ACAMERA_ERROR_INVALID_OPERATION:
        case ACAMERA_ERROR_UNKNOWN:
            // Fatal errors in the camera device, service, or unknown internal errors.
            return EvsResult::UNDERLYING_SERVICE_ERROR;
        case ACAMERA_ERROR_CAMERA_IN_USE:
        case ACAMERA_ERROR_MAX_CAMERA_IN_USE:
            // Camera or system resources are currently busy.
            return EvsResult::RESOURCE_BUSY;
        case ACAMERA_ERROR_CAMERA_DISABLED:
        case ACAMERA_ERROR_PERMISSION_DENIED:
            // Access to the camera is not allowed.
            return EvsResult::PERMISSION_DENIED;
        case ACAMERA_ERROR_UNSUPPORTED_OPERATION:
            // The requested operation is not supported.
            return EvsResult::NOT_SUPPORTED;
        default:
            // Catch-all for any other errors.
            LOG(ERROR) << "Unknown camera status: " << status;
            return EvsResult::UNDERLYING_SERVICE_ERROR;
    }
}
}  // namespace android::hardware::automotive::evs::compat
