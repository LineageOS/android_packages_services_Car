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

#define LOG_TAG "EVSAPP"

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


EvsStateControl::EvsStateControl(android::sp <IVehicle>       pVnet,
                                 android::sp <IEvsEnumerator> pEvs,
                                 android::sp <IEvsDisplay>    pDisplay) :
    mVehicle(pVnet),
    mEvs(pEvs),
    mDisplay(pDisplay),
    mCurrentState(OFF) {

    // Initialize the property value containers we'll be updating (they'll be zeroed by default)
    static_assert(getPropType(VehicleProperty::GEAR_SELECTION) == VehiclePropertyType::INT32,
                  "Unexpected type for GEAR_SELECTION property");
    static_assert(getPropType(VehicleProperty::TURN_SIGNAL_STATE) == VehiclePropertyType::INT32,
                  "Unexpected type for TURN_SIGNAL_STATE property");

    mGearValue.prop       = VehicleProperty::GEAR_SELECTION;
    mTurnSignalValue.prop = VehicleProperty::TURN_SIGNAL_STATE;

    // Build our set of cameras for the states we support
    ALOGD("Requesting camera list");
    mEvs->getCameraList([this]
                        (hidl_vec<CameraDesc> cameraList) {
                            ALOGI("Camera list callback received %lu cameras",
                                  cameraList.size());
                            for(auto&& cam: cameraList) {
                                if ((cam.hints & UsageHint::USAGE_HINT_REVERSE) != 0) {
                                    mCameraInfo[State::REVERSE] = cam;
                                }
                                if ((cam.hints & UsageHint::USAGE_HINT_RIGHT_TURN) != 0) {
                                    mCameraInfo[State::RIGHT] = cam;
                                }
                                if ((cam.hints & UsageHint::USAGE_HINT_LEFT_TURN) != 0) {
                                    mCameraInfo[State::LEFT] = cam;
                                }

                                ALOGD("Found camera %s", cam.cameraId.c_str());
                            }
                        }
    );
    ALOGD("State controller ready");
}


bool EvsStateControl::configureForVehicleState() {
    ALOGD("configureForVehicleState");

    if (mVehicle != nullptr) {
        // Query the car state
        if (invokeGet(&mGearValue) != StatusCode::OK) {
            ALOGE("GEAR_SELECTION not available from vehicle.  Exiting.");
            return false;
        }
        if (invokeGet(&mTurnSignalValue) != StatusCode::OK) {
            ALOGE("TURN_SIGNAL_STATE not available from vehicle.  Exiting.");
            return false;
        }
    } else {
        // While testing without a vehicle, behave as if we're in reverse for the first 20 seconds
        static const int kShowTime = 20;    // seconds
        static int32_t sDummyGear   = int32_t(VehicleGear::GEAR_REVERSE);
        static int32_t sDummySignal = int32_t(VehicleTurnSignal::NONE);

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

    // Apply the desire state
    ALOGV("Selected state %d.", desiredState);
    configureEvsPipeline(desiredState);

    // Operation was successful
    return true;
}


StatusCode EvsStateControl::invokeGet(VehiclePropValue *pRequestedPropValue) {
    ALOGD("invokeGet");

    StatusCode status = StatusCode::TRY_AGAIN;
    bool called = false;

    // Call the Vehicle HAL, which will block until the callback is complete
    mVehicle->get(*pRequestedPropValue,
                  [pRequestedPropValue, &status, &called]
                  (StatusCode s, const VehiclePropValue& v) {
                       status = s;
                       *pRequestedPropValue = v;
                       called = true;
                  }
    );
    // This should be true as long as the get call is block as it should
    // TODO:  Once we've got some milage on this code and the underlying HIDL services,
    // we should remove this belt-and-suspenders check for correct operation as unnecessary.
    if (!called) {
        ALOGE("VehicleNetwork query did not run as expected.");
    }

    return status;
}


bool EvsStateControl::configureEvsPipeline(State desiredState) {
    ALOGD("configureEvsPipeline");

    // Protect access to mCurrentState which is shared with the deliverFrame callback
    std::unique_lock<std::mutex> lock(mAccessLock);

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
            mEvs->closeCamera(mCurrentCamera);
            mCurrentCamera = nullptr;
        }

        // Now do we need a new camera?
        if (!mCameraInfo[desiredState].cameraId.empty()) {

            // Need a new camera, so open it
            ALOGD("Open camera %s", mCameraInfo[desiredState].cameraId.c_str());
            mCurrentCamera = mEvs->openCamera(mCameraInfo[desiredState].cameraId);

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
            // Start the camera stream
            ALOGD("Starting camera stream");
            mCurrentCamera->startVideoStream(this);

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


Return<void> EvsStateControl::deliverFrame(const BufferDesc& buffer) {
    ALOGD("Received a frame from the camera (%p)", buffer.memHandle.getNativeHandle());

    if (buffer.memHandle == nullptr) {
        // This is the end of the stream.  Transition back to the "off" state.
        std::unique_lock<std::mutex> lock(mAccessLock);
        mCurrentState = State::OFF;
        lock.unlock();

        // In case the main thread is waiting for us, announce our change
        mSignal.notify_one();
    } else {
        // Get the output buffer we'll use to display the imagery
        BufferDesc tgtBuffer = {};
        mDisplay->getTargetBuffer([&tgtBuffer]
                                  (const BufferDesc& buff) {
                                      tgtBuffer = buff;
                                      tgtBuffer.memHandle = native_handle_clone(buff.memHandle);
                                      ALOGD("Got output buffer (%p) with id %d cloned as (%p)",
                                            buff.memHandle.getNativeHandle(),
                                            tgtBuffer.bufferId,
                                            tgtBuffer.memHandle.getNativeHandle());
                                  }
        );

        if (tgtBuffer.memHandle == nullptr) {
            ALOGE("Didn't get requested output buffer -- skipping this frame.");
        } else {
            // TODO:  Copy the contents of the of bufferHandle into tgtBuffer
            // TODO:  Add a bit of overlay graphics?
            // TODO:  Use OpenGL to render from texture?

            // Send the target buffer back for display
            ALOGD("Calling returnTargetBufferForDisplay (%p)", tgtBuffer.memHandle.getNativeHandle());
            Return<EvsResult> result = mDisplay->returnTargetBufferForDisplay(tgtBuffer);
            if (!result.isOk()) {
                ALOGE("Error making the remote function call.  HIDL said %s",
                      result.description().c_str());
            }
            if (result != EvsResult::OK) {
                ALOGE("We encountered error %d when returning a buffer to the display!",
                      (EvsResult)result);
            }

            // Now release our copy of the handle
            // TODO:  If we don't end up needing to pass it back, then close our handle earlier
            // As it stands, the buffer might still be held by this process for some time after
            // it gets returned to the server via returnTargetBufferForDisplay()
            native_handle_close(tgtBuffer.memHandle.getNativeHandle());

            // TODO:  Sort our whether this call is needed, and if so, are we forced to const_cast?
            //native_handle_delete(tgtBuffer.memHandle.getNativeHandle());
        }

        // Send the camera buffer back now that we're done with it
        ALOGD("Calling doneWithFrame");
        mCurrentCamera->doneWithFrame(buffer);

        ALOGD("Frame handling complete");
    }

    return Void();
}