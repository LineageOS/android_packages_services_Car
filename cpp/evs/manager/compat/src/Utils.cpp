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

#include "Utils.h"

#include <aidlcommonsupport/NativeHandle.h>
#include <android-base/logging.h>
#include <android/hardware_buffer.h>

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::BufferDesc;
using ::aidl::android::hardware::common::NativeHandle;
using ::aidl::android::hardware::graphics::common::HardwareBuffer;

NativeHandle dupNativeHandle(const NativeHandle& handle, bool doDup) {
    NativeHandle dup;

    dup.fds = std::vector<::ndk::ScopedFileDescriptor>(handle.fds.size());
    if (!doDup) {
        for (auto i = 0; i < handle.fds.size(); ++i) {
            dup.fds.at(i).set(handle.fds[i].get());
        }
    } else {
        for (auto i = 0; i < handle.fds.size(); ++i) {
            dup.fds[i] = handle.fds[i].dup();
        }
    }
    dup.ints = handle.ints;

    return dup;
}

HardwareBuffer dupHardwareBuffer(const HardwareBuffer& buffer, bool doDup) {
    HardwareBuffer dup = {
            .description = buffer.description,
            .handle = dupNativeHandle(buffer.handle, doDup),
    };

    return dup;
}

BufferDesc dupBufferDesc(const BufferDesc& src, bool doDup) {
    BufferDesc dup = {
            .buffer = dupHardwareBuffer(src.buffer, doDup),
            .pixelSizeBytes = src.pixelSizeBytes,
            .bufferId = src.bufferId,
            .deviceId = src.deviceId,
            .timestamp = src.timestamp,
            .metadata = src.metadata,
    };

    return dup;
}

}  // namespace android::hardware::automotive::evs::compat
