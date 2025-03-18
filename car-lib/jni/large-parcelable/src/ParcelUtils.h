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

#pragma once

#include <android-base/result.h>
#include <binder/Parcel.h>

namespace android::jni::largeparcelable {

android::base::Result<void> marshall(const android::Parcel* parcel, void* bufferAddr, int size);

android::base::Result<void> unmarshall(const void* bufferAddr, int size,
                                       android::Parcel* parcel);

}  // namespace android::jni::largeparcelable
