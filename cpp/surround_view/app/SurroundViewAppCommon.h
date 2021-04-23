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
#pragma once

#include "SurroundViewServiceCallback.h"

#include <android-base/logging.h>
#include <android/hardware/automotive/evs/1.1/IEvsEnumerator.h>
#include <android/hardware/automotive/sv/1.0/ISurroundView2dSession.h>
#include <android/hardware/automotive/sv/1.0/ISurroundView3dSession.h>
#include <android/hardware/automotive/sv/1.0/ISurroundViewService.h>
#include <hidl/HidlTransportSupport.h>
#include <utils/Log.h>
#include <utils/StrongPointer.h>

#include <stdio.h>

#include <thread>

using namespace android::hardware::automotive::sv::V1_0;
using namespace android::hardware::automotive::evs::V1_1;

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace app {

const int kLowResolutionWidth = 120;
const int kLowResolutionHeight = 90;

enum DemoMode {
    UNKNOWN,
    DEMO_2D,
    DEMO_3D,
};

const float kHorizontalFov = 90;

// Number of views to generate.
const uint32_t kPoseCount = 16;

// Set of pose rotations expressed in quaternions.
// Views are generated about a circle at a height about the car, point towards the center.
const float kPoseRot[kPoseCount][4] = {{-0.251292, -0.251292, -0.660948, 0.660948},
                                       {0.197439, 0.295488, 0.777193, -0.519304},
                                       {0.135998, 0.328329, 0.86357, -0.357702},
                                       {0.0693313, 0.348552, 0.916761, -0.182355},
                                       {-7.76709e-09, 0.355381, 0.934722, 2.0429e-08},
                                       {-0.0693313, 0.348552, 0.916761, 0.182355},
                                       {-0.135998, 0.328329, 0.86357, 0.357702},
                                       {-0.197439, 0.295488, 0.777193, 0.519304},
                                       {-0.251292, 0.251292, 0.660948, 0.660948},
                                       {-0.295488, 0.197439, 0.519304, 0.777193},
                                       {-0.328329, 0.135998, 0.357702, 0.86357},
                                       {-0.348552, 0.0693313, 0.182355, 0.916761},
                                       {-0.355381, -2.11894e-09, -5.57322e-09, 0.934722},
                                       {-0.348552, -0.0693313, -0.182355, 0.916761},
                                       {-0.328329, -0.135998, -0.357702, 0.86357},
                                       {-0.295488, -0.197439, -0.519304, 0.777193}};

// Set of pose translations i.e. positions of the views.
// Views are generated about a circle at a height about the car, point towards the center.
const float kPoseTrans[kPoseCount][4] = {{4, 0, 2.5},
                                         {3.69552, 1.53073, 2.5},
                                         {2.82843, 2.82843, 2.5},
                                         {1.53073, 3.69552, 2.5},
                                         {-1.74846e-07, 4, 2.5},
                                         {-1.53073, 3.69552, 2.5},
                                         {-2.82843, 2.82843, 2.5},
                                         {-3.69552, 1.53073, 2.5},
                                         {-4, -3.49691e-07, 2.5},
                                         {-3.69552, -1.53073, 2.5},
                                         {-2.82843, -2.82843, 2.5},
                                         {-1.53073, -3.69552, 2.5},
                                         {4.76995e-08, -4, 2.5},
                                         {1.53073, -3.69552, 2.5},
                                         {2.82843, -2.82843, 2.5},
                                         {3.69552, -1.53073, 2.5}};

bool run2dSurroundView(sp<ISurroundViewService> pSurroundViewService, sp<IEvsDisplay> pDisplay);

bool run3dSurroundView(sp<ISurroundViewService> pSurroundViewService, sp<IEvsDisplay> pDisplay);

// Given a valid sv 3d session and pose, viewid and hfov parameters, sets the view.
bool setView(sp<ISurroundView3dSession> surroundView3dSession, uint32_t viewId, uint32_t poseIndex,
             float hfov);

}  // namespace app
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android
