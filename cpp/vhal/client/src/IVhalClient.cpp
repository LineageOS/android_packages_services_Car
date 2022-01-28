/*
 * Copyright (c) 2022, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "IVhalClient.h"

#include "AidlVhalClient.h"
#include "HidlVhalClient.h"

#include <android/hardware/automotive/vehicle/2.0/IVehicle.h>

namespace android {
namespace frameworks {
namespace automotive {
namespace vhal {

std::shared_ptr<IVhalClient> IVhalClient::create() {
    auto client = AidlVhalClient::create();
    if (client != nullptr) {
        return client;
    }

    return HidlVhalClient::create();
}

std::shared_ptr<IVhalClient> IVhalClient::tryCreate() {
    auto client = AidlVhalClient::tryCreate();
    if (client != nullptr) {
        return client;
    }

    return HidlVhalClient::tryCreate();
}

}  // namespace vhal
}  // namespace automotive
}  // namespace frameworks
}  // namespace android
