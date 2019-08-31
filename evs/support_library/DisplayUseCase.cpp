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
#include <hidl/HidlTransportSupport.h>
#include <log/log.h>
#include <utils/SystemClock.h>

#include "DisplayUseCase.h"
#include "RenderDirectView.h"

namespace android {
namespace automotive {
namespace evs {
namespace support {

using android::hardware::configureRpcThreadpool;
using android::hardware::joinRpcThreadpool;

// TODO(b/130246434): since we don't support multi-display use case, there
// should only be one DisplayUseCase. Add the logic to prevent more than
// one DisplayUseCases running at the same time.
DisplayUseCase::DisplayUseCase(string cameraId, BaseRenderCallback* callback) {
    mCameraId = cameraId;
    mRenderCallback = callback;
}

DisplayUseCase::~DisplayUseCase() {
    if (mCurrentRenderer != nullptr) {
        mCurrentRenderer->deactivate();
        mCurrentRenderer = nullptr;  // It's a smart pointer, so destructs on assignment to null
    }

    mIsReadyToRun = false;
    if (mWorkerThread.joinable()) {
        mWorkerThread.join();
    }
}

bool DisplayUseCase::initialize() {
    // TODO(b/130246434): Use evs manager 1.1 instead.
    const char* evsServiceName = "EvsEnumeratorV1_0";

    // Load our configuration information
    ConfigManager config;
    if (!config.initialize("/system/etc/automotive/evs_support_lib/camera_config.json")) {
        ALOGE("Missing or improper configuration for the EVS application.  Exiting.");
        return false;
    }

    // Set thread pool size to one to avoid concurrent events from the HAL.
    // This pool will handle the EvsCameraStream callbacks.
    // Note:  This _will_ run in parallel with the EvsListener run() loop below which
    // runs the application logic that reacts to the async events.
    configureRpcThreadpool(1, false /* callerWillJoin */);

    // Get the EVS manager service
    ALOGI("Acquiring EVS Enumerator");
    mEvs = IEvsEnumerator::getService(evsServiceName);
    if (mEvs.get() == nullptr) {
        ALOGE("getService(%s) returned NULL.  Exiting.", evsServiceName);
        return false;
    }

    // Request exclusive access to the EVS display
    ALOGI("Acquiring EVS Display");

    mDisplay = mEvs->openDisplay();
    if (mDisplay.get() == nullptr) {
        ALOGE("EVS Display unavailable.  Exiting.");
        return false;
    }

    ALOGD("Requesting camera list");
    for (auto&& info : config.getCameras()) {
        if (mCameraId == info.cameraId) {
            mCamera = info;
            mIsInitialized = true;
            return true;
        }
    }

    ALOGE("Cannot find a match camera. Exiting");
    return false;
}

bool DisplayUseCase::startVideoStreaming() {
    // Initialize the use case.
    if (!mIsInitialized && !initialize()) {
        ALOGE("There is an error while initializing the use case. Exiting");
        return false;
    }

    ALOGD("Start video streaming using worker thread");

    mIsReadyToRun = true;
    mWorkerThread = std::thread([this]() {
        // We have a camera assigned to this state for direct view
        mCurrentRenderer = std::make_unique<RenderDirectView>(mEvs, mCamera);
        if (!mCurrentRenderer) {
            ALOGE("Failed to construct direct renderer. Exiting.");
            mIsReadyToRun = false;
            return;
        }

        mCurrentRenderer->mRenderCallback = mRenderCallback;

        // Now set the display state based on whether we have a video feed to show
        // Start the camera stream
        ALOGD("EvsStartCameraStreamTiming start time: %" PRId64 "ms", android::elapsedRealtime());
        if (!mCurrentRenderer->activate()) {
            ALOGE("New renderer failed to activate. Exiting");
            mIsReadyToRun = false;
            return;
        }

        // Activate the display
        ALOGD("EvsActivateDisplayTiming start time: %" PRId64 "ms", android::elapsedRealtime());
        Return<EvsResult> result = mDisplay->setDisplayState(DisplayState::VISIBLE_ON_NEXT_FRAME);
        if (result != EvsResult::OK) {
            ALOGE("setDisplayState returned an error (%d). Exiting.", (EvsResult)result);
            mIsReadyToRun = false;
            return;
        }

        while (mIsReadyToRun && streamFrame());

        ALOGD("Worker thread stops.");
    });

    return true;
}

void DisplayUseCase::stopVideoStreaming() {
    ALOGD("Stop video streaming in worker thread.");
    mIsReadyToRun = false;
    return;
}

bool DisplayUseCase::streamFrame() {
    // Get the output buffer we'll use to display the imagery
    BufferDesc tgtBuffer = {};
    mDisplay->getTargetBuffer([&tgtBuffer](const BufferDesc& buff) { tgtBuffer = buff; });

    if (tgtBuffer.memHandle == nullptr) {
        ALOGE("Didn't get requested output buffer -- skipping this frame.");
    } else {
        // Generate our output image
        if (!mCurrentRenderer->drawFrame(tgtBuffer)) {
            return false;
        }

        // Send the finished image back for display
        mDisplay->returnTargetBufferForDisplay(tgtBuffer);
    }
    return true;
}

DisplayUseCase DisplayUseCase::createDefaultUseCase(string cameraId, BaseRenderCallback* callback) {
    return DisplayUseCase(cameraId, callback);
}

}  // namespace support
}  // namespace evs
}  // namespace automotive
}  // namespace android
