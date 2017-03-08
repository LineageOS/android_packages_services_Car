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

#include <stdio.h>
#include <string.h>

#include <hidl/HidlTransportSupport.h>
#include <utils/Errors.h>
#include <utils/StrongPointer.h>
#include <utils/Log.h>

#include <android/log.h>
#include <android/hardware/automotive/evs/1.0/IEvsCamera.h>
#include <android/hardware/automotive/evs/1.0/IEvsEnumerator.h>
#include <android/hardware/automotive/evs/1.0/IEvsCameraStream.h>
#include <android/hardware/automotive/evs/1.0/IEvsDisplay.h>

#include <hwbinder/ProcessState.h>

#include "EvsStateControl.h"


// libhidl:
using android::hardware::configureRpcThreadpool;
using android::hardware::joinRpcThreadpool;


// TODO:  How should we configure these values to target appropriate hardware?
const static char kDirectEnumeratorName[]  = "EvsEnumeratorHw-Mock";
const static char kManagedEnumeratorName[] = "EvsSharedEnumerator";


// Timing expectations for EVS performance are called out in the EVS Vehicle Camera HAL
// design document available internally at go/aae-evs
static const unsigned kMaxTimeToFirstFrame  = 500;  // units of ms
static const unsigned kMaxTimeBetweenFrames = 100;  // units of ms;

static const unsigned kTestTimeInReverse = 1;       // units of seconds;
static const unsigned kTestTimeInLeft    = 3;       // units of seconds;
static const unsigned kTestTimeInRight   = 3;       // units of seconds;
static const unsigned kTestTimeInOff     = 1;       // units of seconds;

constexpr unsigned expectedFrames(unsigned testTimeSec) {
    unsigned minTime = (testTimeSec * 1000) - kMaxTimeToFirstFrame;
    unsigned requiredFrames = minTime / kMaxTimeBetweenFrames;
    return requiredFrames;
}


bool VerifyDisplayState(DisplayState expectedState, DisplayState actualState) {
    if (expectedState != actualState) {
        printf("ERROR:  DisplayState should be %d, but is %d instead.\n",
               expectedState, actualState);
        return false;
    } else {
        return true;
    }
}


// Main entry point
int main(int argc, char** argv)
{
    const char* serviceName = kManagedEnumeratorName;
    if (argc > 1) {
        if (strcmp(argv[1], "-t") == 0) {
            serviceName = kDirectEnumeratorName;
        } else if (strcmp(argv[1], "-m") == 0) {
            serviceName = kManagedEnumeratorName;
        } else if ((strcmp(argv[1], "-s") == 0) && argc > 2) {
            serviceName = argv[2];
        } else {
            printf("Usage:  %s [mode]\n", argv[0]);
            printf("  were mode is one of:\n");
            printf("  -t  connect directly to the EVS HAL mock implementation.\n");
            printf("  -m  connect to the shared EVS manager.\n");
            printf("  -s <service name>  connect to the named service.\n");
            printf("  the default option is the shared EVS manager.\n");
            return 1;
        }
    }

    printf("EVS test starting for %s\n", serviceName);

    // Get the EVS enumerator service
    sp<IEvsEnumerator> pEnumerator = IEvsEnumerator::getService(serviceName);
    if (pEnumerator.get() == nullptr) {
        printf("getService returned NULL, exiting\n");
        return 1;
    }
    DisplayState displayState = pEnumerator->getDisplayState();
    if (!VerifyDisplayState(DisplayState::NOT_OPEN, displayState)) {
        return 1;
    }

    // Request exclusive access to the EVS display
    sp<IEvsDisplay> pDisplay = pEnumerator->openDisplay();
    if (pDisplay.get() == nullptr) {
        printf("EVS Display unavailable, exiting\n");
        return 1;
    }
    displayState = pEnumerator->getDisplayState();
    if (!VerifyDisplayState(DisplayState::NOT_VISIBLE, displayState)) {
        return 1;
    }

    // Construct our view state controller
    EvsStateControl stateController(pEnumerator, pDisplay);

    // Set thread pool size to one to avoid concurrent events from the HAL.
    // Note:  The pool _will_ run in parallel with the main thread logic below which
    // implements the test actions.
    configureRpcThreadpool(1, false /* callerWillJoin */);

    // Run our test sequence
    printf("Reverse...\n");
    stateController.configureEvsPipeline(EvsStateControl::State::REVERSE);
    sleep(kTestTimeInReverse);

    // Make sure we get the expected EVS Display State
    displayState = pEnumerator->getDisplayState();
    printf("EVS Display State is %d\n", displayState);
    if (displayState != pDisplay->getDisplayState()) {
        printf("ERROR:  DisplayState mismatch.\n");
        return 1;
    }
    if (!VerifyDisplayState(DisplayState::VISIBLE, displayState)) {
        printf("Display didn't enter visible state within %d second\n", kTestTimeInReverse);
        return 1;
    }

    // Make sure that we got at least the minimum required number of frames delivered while the
    // stream was running assuming a maximum startup time and a minimum frame rate.
    unsigned framesSent = stateController.getFramesReceived();
    unsigned framesDone = stateController.getFramesCompleted();
    printf("In the first %d second of reverse, we got %d frames delivered, and %d completed\n",
           kTestTimeInReverse, framesSent, framesDone);
    if (framesSent < expectedFrames(kTestTimeInReverse)) {
        printf("Warning: we got only %d of the required minimum %d frames in the first %d second.",
               framesSent, expectedFrames(kTestTimeInReverse), kTestTimeInReverse);
    }

    printf("Left...\n");
    stateController.configureEvsPipeline(EvsStateControl::State::LEFT);
    sleep(3);
    framesSent = stateController.getFramesReceived();
    framesDone = stateController.getFramesCompleted();
    printf("in %d seconds of Left, we got %d frames delivered, and %d completed\n",
           kTestTimeInLeft, framesSent, framesDone);

    printf("Right...\n");
    stateController.configureEvsPipeline(EvsStateControl::State::RIGHT);
    sleep(kTestTimeInRight);
    framesSent = stateController.getFramesReceived();
    framesDone = stateController.getFramesCompleted();
    printf("in %d seconds of Right, we got %d frames delivered, and %d completed\n",
           kTestTimeInRight, framesSent, framesDone);

    printf("Off...\n");
    stateController.configureEvsPipeline(EvsStateControl::State::OFF);
    sleep(kTestTimeInOff);
    displayState = pEnumerator->getDisplayState();
    if (!VerifyDisplayState(DisplayState::NOT_VISIBLE, displayState)) {
        printf("Display didn't turn off within 1 second.\n");
        return 1;
    }

    framesSent = stateController.getFramesReceived();
    framesDone = stateController.getFramesCompleted();
    printf("in %d seconds of Off, we got %d frames delivered, and %d completed\n",
           kTestTimeInOff, framesSent, framesDone);

    // Explicitly release our resources while still in main
    printf("Exiting...\n");

    pEnumerator->closeDisplay(pDisplay);
    displayState = pEnumerator->getDisplayState();
    if (!VerifyDisplayState(DisplayState::NOT_OPEN, displayState)) {
        printf("Display didn't report closed after shutdown.\n");
        return 1;
    }

    pDisplay = nullptr;
    pEnumerator = nullptr;

    return 0;
}
