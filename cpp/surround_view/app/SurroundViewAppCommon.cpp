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

#include "SurroundViewAppCommon.h"

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace app {

bool run2dSurroundView(sp<ISurroundViewService> pSurroundViewService, sp<IEvsDisplay> pDisplay) {
    LOG(INFO) << "Run 2d Surround View demo";

    // Call HIDL API "start2dSession"
    sp<ISurroundView2dSession> surroundView2dSession;

    SvResult svResult;
    pSurroundViewService->start2dSession(
            [&surroundView2dSession, &svResult](const sp<ISurroundView2dSession>& session,
                                                SvResult result) {
                surroundView2dSession = session;
                svResult = result;
            });

    if (surroundView2dSession == nullptr || svResult != SvResult::OK) {
        LOG(ERROR) << "Failed to start2dSession";
        return false;
    } else {
        LOG(INFO) << "start2dSession succeeded";
    }

    sp<SurroundViewServiceCallback> sv2dCallback =
            new SurroundViewServiceCallback(pDisplay, surroundView2dSession);

    // Start 2d stream with callback with default quality and resolution.
    // The quality is defaulted to be HIGH_QUALITY, and the default resolution
    // is set in the sv config file.
    if (surroundView2dSession->startStream(sv2dCallback) != SvResult::OK) {
        LOG(ERROR) << "Failed to start 2d stream";
        return false;
    }

    const int kTotalViewingTimeSecs = 10;

    // Let the SV algorithm run for HIGH_QUALITY until the wait time finishes
    std::this_thread::sleep_for(std::chrono::seconds(kTotalViewingTimeSecs));

    // Switch to low quality and lower resolution
    Sv2dConfig config = {.width = kLowResolutionWidth, .blending = SvQuality::LOW};
    if (surroundView2dSession->set2dConfig(config) != SvResult::OK) {
        LOG(ERROR) << "Failed to set2dConfig";
        return false;
    }

    // Let the SV algorithm run for LOW_QUALITY until the wait time finishes
    std::this_thread::sleep_for(std::chrono::seconds(kTotalViewingTimeSecs));

    // TODO(b/150412555): wait for the last frame
    // Stop the 2d stream and session
    surroundView2dSession->stopStream();

    pSurroundViewService->stop2dSession(surroundView2dSession);
    surroundView2dSession = nullptr;

    LOG(INFO) << "SV 2D session finished.";

    return true;
};

// Given a valid sv 3d session and pose, viewid and hfov parameters, sets the view.
bool setView(sp<ISurroundView3dSession> surroundView3dSession, uint32_t viewId, uint32_t poseIndex,
             float hfov) {
    const View3d view3d = {
            .viewId = viewId,
            .pose =
                    {
                            .rotation = {.x = kPoseRot[poseIndex][0],
                                         .y = kPoseRot[poseIndex][1],
                                         .z = kPoseRot[poseIndex][2],
                                         .w = kPoseRot[poseIndex][3]},
                            .translation = {.x = kPoseTrans[poseIndex][0],
                                            .y = kPoseTrans[poseIndex][1],
                                            .z = kPoseTrans[poseIndex][2]},
                    },
            .horizontalFov = hfov,
    };

    const std::vector<View3d> views = {view3d};
    if (surroundView3dSession->setViews(views) != SvResult::OK) {
        return false;
    }
    return true;
}

bool run3dSurroundView(sp<ISurroundViewService> pSurroundViewService, sp<IEvsDisplay> pDisplay) {
    LOG(INFO) << "Run 3d Surround View demo";

    // Call HIDL API "start3dSession"
    sp<ISurroundView3dSession> surroundView3dSession;

    SvResult svResult;
    pSurroundViewService->start3dSession(
            [&surroundView3dSession, &svResult](const sp<ISurroundView3dSession>& session,
                                                SvResult result) {
                surroundView3dSession = session;
                svResult = result;
            });

    if (surroundView3dSession == nullptr || svResult != SvResult::OK) {
        LOG(ERROR) << "Failed to start3dSession";
        return false;
    } else {
        LOG(INFO) << "start3dSession succeeded";
    }

    sp<SurroundViewServiceCallback> sv3dCallback =
            new SurroundViewServiceCallback(pDisplay, surroundView3dSession);

    // A view must be set before the 3d stream is started.
    if (!setView(surroundView3dSession, /*viewId=*/0, /*poseIndex=*/0, kHorizontalFov)) {
        LOG(ERROR) << "Failed to setView of pose index :" << 0;
        return false;
    }

    // Start 3d stream with callback with default quality and resolution.
    // The quality is defaulted to be HIGH_QUALITY, and the default resolution
    // is set in the sv config file.
    if (surroundView3dSession->startStream(sv3dCallback) != SvResult::OK) {
        LOG(ERROR) << "Failed to start 3d stream";
        return false;
    }

    // Let the SV algorithm run for 10 seconds for HIGH_QUALITY
    const int kTotalViewingTimeSecs = 10;
    const std::chrono::milliseconds perPoseSleepTimeMs(kTotalViewingTimeSecs * 1000 / kPoseCount);
    // Iterate through the pre-set views.
    for (uint32_t i = 0; i < kPoseCount; i++) {
        if (!setView(surroundView3dSession, /*viewId=*/i, /*poseIndex=*/i, kHorizontalFov)) {
            LOG(WARNING) << "Failed to setView of pose index :" << i;
        }
        std::this_thread::sleep_for(perPoseSleepTimeMs);
    }

    // Switch to low quality and lower resolution
    Sv3dConfig config = {.width = kLowResolutionWidth,
                         .height = kLowResolutionHeight,
                         .carDetails = SvQuality::LOW};

    if (surroundView3dSession->set3dConfig(config) != SvResult::OK) {
        LOG(ERROR) << "Failed to set3dConfig";
        return false;
    }

    // Let the SV algorithm run for 10 seconds for LOW_QUALITY
    for (uint32_t i = 0; i < kPoseCount; i++) {
        if (!setView(surroundView3dSession, i + kPoseCount, i, kHorizontalFov)) {
            LOG(WARNING) << "Failed to setView of pose index :" << i;
        }
        std::this_thread::sleep_for(perPoseSleepTimeMs);
    }

    // TODO(b/150412555): wait for the last frame
    // Stop the 3d stream and session
    surroundView3dSession->stopStream();

    pSurroundViewService->stop3dSession(surroundView3dSession);
    surroundView3dSession = nullptr;

    LOG(DEBUG) << "SV 3D session finished.";

    return true;
};
}  // namespace app
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android
