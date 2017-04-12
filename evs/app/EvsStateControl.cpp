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
#include "EvsStateControl.h"
#include "RenderDirectView.h"
#include "RenderPixelCopy.h"

#include <stdio.h>
#include <string.h>

#include <log/log.h>


// TODO:  Seems like it'd be nice if the Vehicle HAL provided such helpers (but how & where?)
inline constexpr VehiclePropertyType getPropType(VehicleProperty prop) {
    return static_cast<VehiclePropertyType>(
            static_cast<int32_t>(prop)
            & static_cast<int32_t>(VehiclePropertyType::MASK));
}


EvsStateControl::EvsStateControl(android::sp <IVehicle>       pVnet,
                                 android::sp <IEvsEnumerator> pEvs,
                                 android::sp <IEvsDisplay>    pDisplay,
                                 const ConfigManager&         config) :
    mVehicle(pVnet),
    mEvs(pEvs),
    mDisplay(pDisplay),
    mCurrentState(OFF) {

    // Initialize the property value containers we'll be updating (they'll be zeroed by default)
    static_assert(getPropType(VehicleProperty::GEAR_SELECTION) == VehiclePropertyType::INT32,
                  "Unexpected type for GEAR_SELECTION property");
    static_assert(getPropType(VehicleProperty::TURN_SIGNAL_STATE) == VehiclePropertyType::INT32,
                  "Unexpected type for TURN_SIGNAL_STATE property");

    mGearValue.prop       = static_cast<int32_t>(VehicleProperty::GEAR_SELECTION);
    mTurnSignalValue.prop = static_cast<int32_t>(VehicleProperty::TURN_SIGNAL_STATE);

    // Build our set of cameras for the states we support
    ALOGD("Requesting camera list");
    mEvs->getCameraList([this, &config]
                        (hidl_vec<CameraDesc> cameraList) {
                            ALOGI("Camera list callback received %zu cameras",
                                  cameraList.size());
                            for (auto&& cam: cameraList) {
                                ALOGD("Found camera %s", cam.cameraId.c_str());
                                bool cameraConfigFound = false;

                                // Check our configuration for information about this camera
                                // Note that a camera can have a compound function string
                                // such that a camera can be "right/reverse" and be used for both.
                                for (auto&& info: config.getCameras()) {
                                    if (cam.cameraId == info.cameraId) {
                                        // We found a match!
                                        if (info.function.find("reverse") != std::string::npos) {
                                            mCameraInfo[State::REVERSE] = info;
                                        }
                                        if (info.function.find("right") != std::string::npos) {
                                            mCameraInfo[State::RIGHT] = info;
                                        }
                                        if (info.function.find("left") != std::string::npos) {
                                            mCameraInfo[State::LEFT] = info;
                                        }
                                        cameraConfigFound = true;
                                        break;
                                    }
                                }
                                if (!cameraConfigFound) {
                                    ALOGW("No config information for hardware camera %s",
                                          cam.cameraId.c_str());
                                }
                            }
                        }
    );
    ALOGD("State controller ready");
}


bool EvsStateControl::startUpdateLoop() {
    // Create the thread and report success if it gets started
    mRenderThread = std::thread([this](){ updateLoop(); });
    return mRenderThread.joinable();
}


void EvsStateControl::postCommand(const Command& cmd) {
    // Push the command onto the queue watched by updateLoop
    mLock.lock();
    mCommandQueue.push(cmd);
    mLock.unlock();

    // Send a signal to wake updateLoop in case it is asleep
    mWakeSignal.notify_all();
}


void EvsStateControl::updateLoop() {
    ALOGD("Starting EvsStateControl update loop");

    bool run = true;
    while (run) {
        // Process incoming commands
        {
            std::lock_guard <std::mutex> lock(mLock);
            while (!mCommandQueue.empty()) {
                const Command& cmd = mCommandQueue.front();
                switch (cmd.operation) {
                case Op::EXIT:
                    run = false;
                    break;
                case Op::CHECK_VEHICLE_STATE:
                    // Just running selectStateForCurrentConditions below will take care of this
                    break;
                case Op::TOUCH_EVENT:
                    // TODO:  Implement this given the x/y location of the touch event
                    // Ignore for now
                    break;
                }
                mCommandQueue.pop();
            }
        }

        // Review vehicle state and choose an appropriate renderer
        if (!selectStateForCurrentConditions()) {
            ALOGE("selectStateForCurrentConditions failed so we're going to die");
            break;
        }

        // If we have an active renderer, give it a chance to draw
        if (mCurrentRenderer) {
            // Get the output buffer we'll use to display the imagery
            BufferDesc tgtBuffer = {};
            mDisplay->getTargetBuffer([&tgtBuffer](const BufferDesc& buff) {
                                          tgtBuffer = buff;
                                      }
            );

            if (tgtBuffer.memHandle == nullptr) {
                ALOGE("Didn't get requested output buffer -- skipping this frame.");
            } else {
                // Generate our output image
                if (!mCurrentRenderer->drawFrame(tgtBuffer)) {
                    // If drawing failed, we want to exit quickly so an app restart can happen
                    run = false;
                }

                // Send the finished image back for display
                mDisplay->returnTargetBufferForDisplay(tgtBuffer);
            }
        } else {
            // No active renderer, so sleep until somebody wakes us with another command
            std::unique_lock<std::mutex> lock(mLock);
            mWakeSignal.wait(lock);
        }
    }

    ALOGW("EvsStateControl update loop ending");

    // TODO:  Fix it so we can exit cleanly from the main thread instead
    printf("Shutting down app due to state control loop ending\n");
    ALOGE("KILLING THE APP FROM THE EvsStateControl LOOP ON DRAW FAILURE!!!");
    exit(1);
}


bool EvsStateControl::selectStateForCurrentConditions() {
    ALOGD("selectStateForCurrentConditions");

    static int32_t sDummyGear   = int32_t(VehicleGear::GEAR_REVERSE);
    static int32_t sDummySignal = int32_t(VehicleTurnSignal::NONE);

    if (mVehicle != nullptr) {
        // Query the car state
        if (invokeGet(&mGearValue) != StatusCode::OK) {
            ALOGE("GEAR_SELECTION not available from vehicle.  Exiting.");
            return false;
        }
        if (invokeGet(&mTurnSignalValue) != StatusCode::OK) {
            // Silently treat missing turn signal state as no turn signal active
            mTurnSignalValue.value.int32Values.setToExternal(&sDummySignal, 1);
        }
    } else {
        // While testing without a vehicle, behave as if we're in reverse for the first 20 seconds
        static const int kShowTime = 20;    // seconds

        // See if it's time to turn off the default reverse camera
        static std::chrono::steady_clock::time_point start = std::chrono::steady_clock::now();
        std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::seconds>(now - start).count() > kShowTime) {
            // Switch to drive (which should turn off the reverse camera)
            sDummyGear = int32_t(VehicleGear::GEAR_DRIVE);
        }

        // Build the dummy vehicle state values (treating single values as 1 element vectors)
        mGearValue.value.int32Values.setToExternal(&sDummyGear, 1);
        mTurnSignalValue.value.int32Values.setToExternal(&sDummySignal, 1);
    }

    // Choose our desired EVS state based on the current car state
    State desiredState = OFF;
    if (mGearValue.value.int32Values[0] == int32_t(VehicleGear::GEAR_REVERSE)) {
        desiredState = REVERSE;
    } else if (mTurnSignalValue.value.int32Values[0] == int32_t(VehicleTurnSignal::RIGHT)) {
        desiredState = RIGHT;
    } else if (mTurnSignalValue.value.int32Values[0] == int32_t(VehicleTurnSignal::LEFT)) {
        desiredState = LEFT;
    }

    ALOGV("Selected state %d.", desiredState);

    // Apply the desire state
    return configureEvsPipeline(desiredState);
}


StatusCode EvsStateControl::invokeGet(VehiclePropValue *pRequestedPropValue) {
    ALOGD("invokeGet");

    StatusCode status = StatusCode::TRY_AGAIN;

    // Call the Vehicle HAL, which will block until the callback is complete
    mVehicle->get(*pRequestedPropValue,
                  [pRequestedPropValue, &status]
                  (StatusCode s, const VehiclePropValue& v) {
                       status = s;
                       *pRequestedPropValue = v;
                  }
    );

    return status;
}


bool EvsStateControl::configureEvsPipeline(State desiredState) {
    ALOGD("configureEvsPipeline");

    if (mCurrentState == desiredState) {
        // Nothing to do here...
        return true;
    }

    ALOGD("  Current state (%d) = %s", mCurrentState,
          mCameraInfo[mCurrentState].cameraId.c_str());
    ALOGD("  Desired state (%d) = %s", desiredState,
          mCameraInfo[desiredState].cameraId.c_str());

    // Since we're changing states, shut down the current renderer
    if (mCurrentRenderer != nullptr) {
        mCurrentRenderer->deactivate();
        mCurrentRenderer = nullptr; // It's a smart pointer, so destructs on assignment to null
    }

    // Do we need a new direct view renderer?
    if (desiredState == PARKING) {
        // We need a new top view renderer
        // TODO:  Implement this by instantiating a new RenderTopView
    } else if (!mCameraInfo[desiredState].cameraId.empty()) {
        // We have a camera assigned to this state for direct view
        mCurrentRenderer = std::make_unique<RenderDirectView>(mEvs, mCameraInfo[desiredState]);
//        mCurrentRenderer = std::make_unique<RenderPixelCopy>(mEvs, mCameraInfo[desiredState]);
        if (!mCurrentRenderer) {
            ALOGE("Failed to construct renderer.  Skipping state change.");
            return false;
        }
    }

    // Now set the display state based on whether we have a video feed to show
    if (mCurrentRenderer == nullptr) {
        ALOGD("Turning off the display");
        mDisplay->setDisplayState(DisplayState::NOT_VISIBLE);
    } else {
        // Start the camera stream
        ALOGD("Starting camera stream");
        if (!mCurrentRenderer->activate()) {
            ALOGE("New renderer failed to activate");
            return false;
        }

        // Activate the display
        ALOGD("Arming the display");
        Return<EvsResult> result = mDisplay->setDisplayState(DisplayState::VISIBLE_ON_NEXT_FRAME);
        if (result != EvsResult::OK) {
            ALOGE("setDisplayState returned an error (%d)", (EvsResult)result);
            return false;
        }
    }

    // Record our current state
    ALOGI("Activated state %d.", desiredState);
    mCurrentState = desiredState;

    return true;
}
