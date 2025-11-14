/*
 * Copyright 2025 The Android Open Source Project
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

#define LOG_TAG "LargeParcelableJni"

#include "ParcelUtils.h"

#include <android-base/logging.h>

namespace android::jni::largeparcelable {

using ::android::Parcel;
using ::android::base::Error;
using ::android::base::Result;

Result<void> marshall(const Parcel* parcel, void* bufferAddr, int signedSize) {
    if (parcel == NULL) {
        return Error() << "Parcel must be non-NULL";
    }

    if (parcel->isForRpc()) {
        return Error() << "Tried to marshall an RPC Parcel";
    }

    if (parcel->objectsCount()) {
        return Error() << "Tried to marshall a Parcel that contains objects (binders or FDs)";
    }

    if (parcel->dataSize() != static_cast<size_t>(signedSize)) {
        return Error() << "Invalid size, must be equal to parcel size: " << parcel->dataSize();
    }

    memcpy(bufferAddr, parcel->data(), parcel->dataSize());

    return {};
}

Result<void> unmarshall(const void* bufferAddr, int signedSize, Parcel* parcel) {
    if (parcel == NULL) {
        return Error() << "Parcel must be non-NULL";
    }
    if (signedSize < 0) {
        return Error() << "size must be non-negative";
    }

    size_t size = static_cast<size_t>(signedSize);
    parcel->setDataSize(size);
    parcel->setDataPosition(0);

    void* raw = parcel->writeInplace(size);
    memcpy(raw, bufferAddr, size);

    return {};
}

}  // namespace android::jni::largeparcelable
