/*
 * Copyright 2020 The Android Open Source Project
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

#include <fuzzer/FuzzedDataProvider.h>
#include <iostream>
#include "Common.h"
#include "Enumerator.h"
#include "HalCamera.h"
#include "MockHWCamera.h"

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

namespace {

enum EvsFuzzFuncs {
    EVS_FUZZ_MAKE_VIRTUAL_CAMERA = 0,    // verify makeVirtualCamera
    EVS_FUZZ_OWN_VIRTUAL_CAMERA,         // verify ownVirtualCamera
    EVS_FUZZ_DISOWN_VIRTUAL_CAMERA,      // verify disownVirtualCamera
    EVS_FUZZ_GET_CLIENT_COUNT,           // verify getClientCount
    EVS_FUZZ_GET_ID,                     // verify getId
    EVS_FUZZ_GET_STREAM_CONFIG,          // verify getStreamConfig
    EVS_FUZZ_CHANGE_FRAMES_IN_FLIGHT,    // verify changeFramesInFlight
    EVS_FUZZ_CHANGE_FRAMES_IN_FLIGHT_1,  // verify overloaded changeFramesInFlight
    EVS_FUZZ_REQUEST_NEW_FRAME,          // verify requestNewFrame
    EVS_FUZZ_CLIENT_STREAM_STARTING,     // verify clientStreamStarting
    EVS_FUZZ_CLIENT_STREAM_ENDING,       // verify clientStreamEnding
    EVS_FUZZ_GET_STATS,                  // verify getStats
    EVS_FUZZ_GET_STREAM_CONFIGURATION,   // verify getStreamConfiguration
    EVS_FUZZ_DELIVER_FRAME_1_1,          // verify deliverFrame_1_1
    EVS_FUZZ_BASE_ENUM                   // verify common functions
};

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    FuzzedDataProvider fdp(data, size);
    sp<IEvsCamera_1_1> mockHWCamera = new MockHWCamera();
    sp<HalCamera> halCamera = new HalCamera(mockHWCamera);
    std::vector<sp<VirtualCamera>> virtualCameras;

    while (fdp.remaining_bytes() > 4) {
        switch (fdp.ConsumeIntegralInRange<uint32_t>(0, EVS_FUZZ_API_SUM)) {
            case EVS_FUZZ_MAKE_VIRTUAL_CAMERA: {
                sp<VirtualCamera> virtualCamera = halCamera->makeVirtualCamera();
                virtualCameras.emplace_back(virtualCamera);
                break;
            }
            case EVS_FUZZ_OWN_VIRTUAL_CAMERA: {
                if (!virtualCameras.empty()) {
                    uint32_t whichCam =
                            fdp.ConsumeIntegralInRange<uint32_t>(0, virtualCameras.size() - 1);
                    halCamera->ownVirtualCamera(virtualCameras[whichCam]);
                }
                break;
            }
            case EVS_FUZZ_DISOWN_VIRTUAL_CAMERA: {
                if (!virtualCameras.empty()) {
                    uint32_t whichCam =
                            fdp.ConsumeIntegralInRange<uint32_t>(0, virtualCameras.size() - 1);
                    halCamera->disownVirtualCamera(virtualCameras[whichCam]);
                }
                break;
            }
            case EVS_FUZZ_GET_HW_CAMERA: {
                halCamera->getHwCamera();
                break;
            }
            case EVS_FUZZ_GET_CLIENT_COUNT: {
                halCamera->getClientCount();
                break;
            }
            case EVS_FUZZ_GET_ID: {
                halCamera->getId();
                break;
            }
            case EVS_FUZZ_GET_STREAM_CONFIG: {
                halCamera->getStreamConfig();
                break;
            }
            case EVS_FUZZ_CHANGE_FRAMES_IN_FLIGHT: {
                uint32_t delta = fdp.ConsumeIntegral<int32_t>();
                halCamera->changeFramesInFlight(delta);
                break;
            }
            default:
                LOG(ERROR) << "Unexpected option, aborting...";
                break;
        }
    }
    return 0;
}

}  // namespace
}  // namespace implementation
}  // namespace V1_1
}  // namespace evs
}  // namespace automotive
}  // namespace android
