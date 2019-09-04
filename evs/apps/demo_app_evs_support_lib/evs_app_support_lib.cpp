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

#include <stdio.h>
#include <utils/SystemClock.h>
#include <string>

#include <DisplayUseCase.h>
#include <Utils.h>

using ::android::automotive::evs::support::BaseRenderCallback;
using ::android::automotive::evs::support::DisplayUseCase;
using ::android::automotive::evs::support::Frame;
using ::android::automotive::evs::support::Utils;

class SimpleRenderCallback : public BaseRenderCallback {
    void render(const Frame& inputFrame, const Frame& outputFrame) {
        ALOGI("SimpleRenderCallback::render");

        if (inputFrame.data == nullptr || outputFrame.data == nullptr) {
            ALOGE("Invalid frame data was passed to render callback");
            return;
        }

        // TODO(b/130246434): Use OpenCV to implement a more meaningful
        // callback.
        // Swap the RGB channels.
        int stride = inputFrame.stride;
        uint8_t* inDataPtr = inputFrame.data;
        uint8_t* outDataPtr = outputFrame.data;
        for (int i = 0; i < inputFrame.width; i++)
            for (int j = 0; j < inputFrame.height; j++) {
                outDataPtr[(i + j * stride) * 4 + 0] =
                    inDataPtr[(i + j * stride) * 4 + 1];
                outDataPtr[(i + j * stride) * 4 + 1] =
                    inDataPtr[(i + j * stride) * 4 + 2];
                outDataPtr[(i + j * stride) * 4 + 2] =
                    inDataPtr[(i + j * stride) * 4 + 0];
                outDataPtr[(i + j * stride) * 4 + 3] =
                    inDataPtr[(i + j * stride) * 4 + 3];
            }
    }
};

// Main entry point
int main() {
    ALOGI("EVS app starting\n");

    std::string cameraId = Utils::getRearCameraId();
    if (cameraId.empty()) {
        ALOGE("Cannot find a valid camera");
        return -1;
    }

    DisplayUseCase useCase =
        DisplayUseCase::createDefaultUseCase(cameraId, new SimpleRenderCallback());

    // Stream the video for 5 seconds.
    if (useCase.startVideoStream()) {
        std::this_thread::sleep_for(std::chrono::seconds(5));
        useCase.stopVideoStream();
    }

    return 0;
}
