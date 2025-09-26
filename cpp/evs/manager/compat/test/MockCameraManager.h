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

#include "ICameraManager.h"

#include <gmock/gmock.h>

namespace android::hardware::automotive::evs::compat {

class MockCameraManager : public ICameraManager {
public:
    MOCK_METHOD(bool, isAvailable, (), (override));
    MOCK_METHOD(camera_status_t, getCameraIdList, (std::vector<std::string>* idList), (override));
    MOCK_METHOD(camera_status_t, getCameraCharacteristics,
                (const char* cameraId, ACameraMetadata** metadata), (override));
    MOCK_METHOD(ACameraManager*, get, (), (override));
};

}  // namespace android::hardware::automotive::evs::compat
