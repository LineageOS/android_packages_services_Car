/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "EvsTest"

#include "EvsStateControl.h"

#include <stdio.h>
#include <string.h>

#include <android/log.h>


// TODO:  Seems like it'd be nice if the Vehicle HAL provided such helpers (but how & where?)
inline constexpr VehiclePropertyType getPropType(VehicleProperty prop) {
    return static_cast<VehiclePropertyType>(
            static_cast<int32_t>(prop)
            & static_cast<int32_t>(VehiclePropertyType::MASK));
}


EvsStateControl::EvsStateControl(android::sp <IEvsEnumerator> pEnumerator,
                                 android::sp <IEvsDisplay> pDisplay) {

    // Store the pointers to the HAL interfaces we use to assemble the EVS pipeline
    mEnumerator    = pEnumerator;
    mDisplay       = pDisplay;

    // Initialize our current state so we know we have no cameras active
    mCurrentState = State::OFF;

    // Build our set of cameras for the states we support
    ALOGD("Requesting camera list");
    mEnumerator->getCameraList([this]
                               (hidl_vec<CameraDesc> cameraList) {
                                   ALOGI("Camera list callback received %zu cameras",
                                         cameraList.size());
                                   for(auto&& cam: cameraList) {
                                       if ((cam.hints & UsageHint::USAGE_HINT_REVERSE) != 0) {
                                           mCameraInfo[State::REVERSE] = cam;
                                           ALOGD("Use for REVERSE...");
                                       }
                                       if ((cam.hints & UsageHint::USAGE_HINT_RIGHT_TURN) != 0) {
                                           mCameraInfo[State::RIGHT] = cam;
                                           ALOGD("Use for RIGHT...");
                                       }
                                       if ((cam.hints & UsageHint::USAGE_HINT_LEFT_TURN) != 0) {
                                           mCameraInfo[State::LEFT] = cam;
                                           ALOGD("Use for LEFT...");
                                       }

                                       ALOGD("Found camera %s", cam.cameraId.c_str());
                                   }
                               }
    );

    // Record information about our display device
    mDisplay->getDisplayInfo([this]
                             (DisplayDesc desc) {
                                 mDisplayInfo = desc;
                                 ALOGD("Found %dx%d display",
                                       desc.defaultHorResolution,
                                       desc.defaultVerResolution);
                             }
    );

    ALOGD("State controller ready");
}


bool EvsStateControl::configureEvsPipeline(State desiredState) {
    ALOGD("configureEvsPipeline");

    if (mCurrentState == desiredState) {
        // Nothing to do here...
        return true;
    }

    // See if we actually have to change cameras
    if (mCameraInfo[mCurrentState].cameraId != mCameraInfo[desiredState].cameraId) {
        ALOGI("Camera change required");
        ALOGD("  Current cameraId (%d) = %s", mCurrentState,
              mCameraInfo[mCurrentState].cameraId.c_str());
        ALOGD("  Desired cameraId (%d) = %s", desiredState,
              mCameraInfo[desiredState].cameraId.c_str());

        // Yup, we need to change cameras, so close the previous one, if necessary.
        if (mCurrentCamera != nullptr) {
            mCurrentStreamHandler->blockingStopStream();
            mCurrentStreamHandler = nullptr;

            mEnumerator->closeCamera(mCurrentCamera);
            mCurrentCamera = nullptr;
        }

        // Now do we need a new camera?
        if (!mCameraInfo[desiredState].cameraId.empty()) {
            // Need a new camera, so open it
            ALOGD("Open camera %s", mCameraInfo[desiredState].cameraId.c_str());
            mCurrentCamera = mEnumerator->openCamera(mCameraInfo[desiredState].cameraId);

            // If we didn't get the camera we asked for, we need to bail out and try again later
            if (mCurrentCamera == nullptr) {
                ALOGE("Failed to open EVS camera.  Skipping state change.");
                return false;
            }
        }

        // Now set the display state based on whether we have a camera feed to show
        if (mCurrentCamera == nullptr) {
            ALOGD("Turning off the display");
            mDisplay->setDisplayState(DisplayState::NOT_VISIBLE);
        } else {
            // Create the stream handler object to receive and forward the video frames
            mCurrentStreamHandler = new StreamHandler(mCurrentCamera, mCameraInfo[desiredState],
                                                      mDisplay, mDisplayInfo);

            // Start the camera stream
            ALOGD("Starting camera stream");
            mCurrentStreamHandler->startStream();

            // Activate the display
            ALOGD("Arming the display");
            mDisplay->setDisplayState(DisplayState::VISIBLE_ON_NEXT_FRAME);
        }
    }

    // Record our current state
    ALOGI("Activated state %d.", desiredState);
    mCurrentState = desiredState;

    return true;
}
