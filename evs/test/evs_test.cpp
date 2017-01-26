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
#include <android/hardware/evs/1.0/IEvsCamera.h>
#include <android/hardware/evs/1.0/IEvsEnumerator.h>
#include <android/hardware/evs/1.0/IEvsCameraStream.h>
#include <android/hardware/evs/1.0/IEvsDisplay.h>

#include <hwbinder/ProcessState.h>

#include "EvsStateControl.h"


// libhidl:
using android::hardware::configureRpcThreadpool;
using android::hardware::joinRpcThreadpool;


// TODO:  How should we configure these values to target appropriate hardware?
const static char kDirectEnumeratorName[]  = "EvsEnumeratorHw-Mock";
const static char kManagedEnumeratorName[] = "EvsSharedEnumerator";


bool VerifyDisplayState(DisplayState expectedState, DisplayState actualState) {
    if (expectedState != actualState) {
        ALOGE("ERROR:  DisplayState should be %d, but is %d instead.",
              expectedState, actualState);
        printf("ERROR:  DisplayState should be NOT_OPEN(%d), but is %d instead.\n",
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
    ALOGI("Acquiring EVS Enumerator");
    sp<IEvsEnumerator> pEnumerator = IEvsEnumerator::getService(serviceName);
    if (pEnumerator.get() == nullptr) {
        ALOGE("getService returned NULL, exiting");
        return 1;
    }
    DisplayState displayState = pEnumerator->getDisplayState();
    if (!VerifyDisplayState(DisplayState::NOT_OPEN, displayState)) {
        return 1;
    }

    // Request exclusive access to the EVS display
    ALOGI("Acquiring EVS Display");
    sp<IEvsDisplay> pDisplay = pEnumerator->openDisplay();
    if (pDisplay.get() == nullptr) {
        ALOGE("EVS Display unavailable, exiting");
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
    ALOGD("Starting thread pool to handle async callbacks");
    configureRpcThreadpool(1, false /* callerWillJoin */);

    // Run our test sequence
    printf("Reverse...\n");
    stateController.configureEvsPipeline(EvsStateControl::State::REVERSE);
    sleep(1);

    // Make sure we get the expected EVS Display State
    displayState = pEnumerator->getDisplayState();
    printf("EVS Display State is %d\n", displayState);
    if (displayState != pDisplay->getDisplayState()) {
        ALOGE("ERROR:  DisplayState mismatch.");
        printf("ERROR:  DisplayState mismatch.\n");
        return 1;
    }
    if (!VerifyDisplayState(DisplayState::VISIBLE, displayState)) {
        return 1;
    }

    printf("Left...\n");
    stateController.configureEvsPipeline(EvsStateControl::State::LEFT);
    sleep(3);

    printf("Right...\n");
    stateController.configureEvsPipeline(EvsStateControl::State::RIGHT);
    sleep(3);

    printf("Off...\n");
    stateController.configureEvsPipeline(EvsStateControl::State::OFF);
    sleep(1);
    displayState = pEnumerator->getDisplayState();
    if (!VerifyDisplayState(DisplayState::NOT_VISIBLE, displayState)) {
        return 1;
    }

    // Explicitly release our resources while still in main
    printf("Exiting...\n");

    pEnumerator->closeDisplay(pDisplay);
    displayState = pEnumerator->getDisplayState();
    if (!VerifyDisplayState(DisplayState::NOT_OPEN, displayState)) {
        return 1;
    }

    pDisplay = nullptr;
    pEnumerator = nullptr;

    return 0;
}
