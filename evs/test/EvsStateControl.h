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

#ifndef CAR_EVS_APP_EVSSTATECONTROL_H
#define CAR_EVS_APP_EVSSTATECONTROL_H

#include <android/hardware/automotive/evs/1.0/IEvsCamera.h>
#include <android/hardware/automotive/evs/1.0/IEvsEnumerator.h>
#include <android/hardware/automotive/evs/1.0/IEvsDisplay.h>
#include <android/hardware/automotive/vehicle/2.0/IVehicle.h>

#include "StreamHandler.h"

using namespace ::android::hardware::automotive::evs::V1_0;
using namespace ::android::hardware::automotive::vehicle::V2_0;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::hidl_vec;
using ::android::hardware::hidl_handle;
using ::android::sp;


class EvsStateControl {
public:
    EvsStateControl(android::sp <IEvsEnumerator> pEnumerator,
                    android::sp <IEvsDisplay> pDisplay);

    enum State {
        REVERSE = 0,
        LEFT,
        RIGHT,
        OFF,
        NUM_STATES  // Must come last
    };

    bool configureEvsPipeline(State desiredState);

    unsigned getFramesReceived() {
        return mCurrentStreamHandler.get() ? mCurrentStreamHandler->getFramesReceived() : 0;
    }
    unsigned getFramesCompleted() {
        return mCurrentStreamHandler.get() ? mCurrentStreamHandler->getFramesCompleted() : 0;
    }

private:
    android::sp <IEvsEnumerator>    mEnumerator;

    State                           mCurrentState;

    sp<IEvsCamera>                  mCurrentCamera;
    CameraDesc                      mCameraInfo[State::NUM_STATES];

    sp<IEvsDisplay>                 mDisplay;
    DisplayDesc                     mDisplayInfo;

    sp<StreamHandler>               mCurrentStreamHandler;
};


#endif //CAR_EVS_APP_EVSSTATECONTROL_H
