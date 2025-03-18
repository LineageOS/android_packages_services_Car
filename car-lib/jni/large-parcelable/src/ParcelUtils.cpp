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

#include <android-base/logging.h>
#include <binder/Parcel.h>

namespace android::jni::largeparcelable {

using ::android::Parcel;

void unmarshall(const void* buffer_addr, size_t size, Parcel* parcel) {
    if (parcel == NULL) {
        LOG(ERROR) << "Parcel must be non-NULL";
        return;
    }
    if (size < 0) {
        LOG(ERROR) << "size must be non-negative";
        return;
    }

    parcel->setDataSize(size);
    parcel->setDataPosition(0);

    void* raw = parcel->writeInplace(size);
    memcpy(raw, buffer_addr, size);
}

}  // namespace android::jni::largeparcelable
