/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "StreamHandlerManager.h"

namespace android {
namespace automotive {
namespace evs {
namespace support {

sp<StreamHandlerManager> StreamHandlerManager::sInstance;

sp<StreamHandlerManager> StreamHandlerManager::getInstance() {
    if (sInstance == nullptr) {
        ALOGD("Creating new StreamHandlerManager instance");
        sInstance = new StreamHandlerManager();
    }
    return sInstance;
}

sp<StreamHandler> StreamHandlerManager::getStreamHandler(sp<IEvsCamera> pCamera) {
    // Use camera Id as the key to the map
    std::string cameraId;
    pCamera.get()->getCameraInfo([&cameraId](CameraDesc desc) {
        cameraId = desc.cameraId;
    });

    auto result = mStreamHandlers.find(cameraId);
    if (result == mStreamHandlers.end()) {
        sp<StreamHandler> handler = new StreamHandler(pCamera);
        mStreamHandlers.emplace(cameraId, handler);
        return handler;
    } else {
        return result->second;
    }
}

}  // namespace support
}  // namespace evs
}  // namespace automotive
}  // namespace android
