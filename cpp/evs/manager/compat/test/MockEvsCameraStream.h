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

#include <aidl/android/hardware/automotive/evs/BnEvsCameraStream.h>
#include <aidl/android/hardware/automotive/evs/BufferDesc.h>
#include <aidl/android/hardware/automotive/evs/EvsEventDesc.h>
#include <gmock/gmock.h>

namespace android::hardware::automotive::evs::compat {

class MockEvsCameraStream : public aidl::android::hardware::automotive::evs::BnEvsCameraStream {
public:
    MockEvsCameraStream() = default;
    virtual ~MockEvsCameraStream() = default;

    MOCK_METHOD(::ndk::ScopedAStatus, deliverFrame,
                (const std::vector<aidl::android::hardware::automotive::evs::BufferDesc>&),
                (override));
    MOCK_METHOD(::ndk::ScopedAStatus, notify,
                (const aidl::android::hardware::automotive::evs::EvsEventDesc&), (override));
};

}  // namespace android::hardware::automotive::evs::compat
