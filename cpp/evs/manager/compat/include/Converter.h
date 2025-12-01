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

#include <aidl/android/hardware/automotive/evs/BufferDesc.h>
#include <aidl/android/hardware/automotive/evs/CameraDesc.h>
#include <aidl/android/hardware/automotive/evs/EvsResult.h>
#include <aidl/android/hardware/automotive/evs/Stream.h>
#include <camera/NdkCameraError.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImageReader.h>

namespace android::hardware::automotive::evs::compat {

class Converter {
public:
    static ::aidl::android::hardware::automotive::evs::CameraDesc toCameraDesc(
            const char* cameraId, const ACameraMetadata* metadata, int vendorFlags);

    static media_status_t toAImageReader(
            const ::aidl::android::hardware::automotive::evs::Stream& config, int32_t maxImages,
            AImageReader** reader);

    static media_status_t toBufferDesc(
            AImage* image, uint32_t bufferId, const std::string& deviceId,
            ::aidl::android::hardware::automotive::evs::BufferDesc& outBufferDesc);

    static ::aidl::android::hardware::automotive::evs::EvsResult toEvsResult(
            camera_status_t status);
};

}  // namespace android::hardware::automotive::evs::compat
