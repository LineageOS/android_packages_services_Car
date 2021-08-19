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
#include "SurroundViewCallback.h"

#include <android-base/logging.h>
#include <utils/Log.h>

using android::hardware::automotive::evs::V1_0::EvsResult;
using android::hardware::Return;
using android::sp;
using std::string;

SurroundViewCallback::SurroundViewCallback(
    sp<ISurroundViewSession> pSession,
    const std::function<bool(const HardwareBuffer& hardwareBuffer)>& onSvFrameReceiveFn) :
    mSession(pSession),
    mOnSvFrameReceiveFn(onSvFrameReceiveFn) {
    // Nothing but member initialization
}

Return<void> SurroundViewCallback::notify(SvEvent svEvent) {
    // Waiting for STREAM_STARTED event.
    if (svEvent == SvEvent::STREAM_STARTED) {
        LOG(INFO) << "Received STREAM_STARTED event";
    } else if (svEvent == SvEvent::CONFIG_UPDATED) {
        LOG(INFO) << "Received CONFIG_UPDATED event";
    } else if (svEvent == SvEvent::STREAM_STOPPED) {
        LOG(INFO) << "Received STREAM_STOPPED event";
    } else if (svEvent == SvEvent::FRAME_DROPPED) {
        LOG(INFO) << "Received FRAME_DROPPED event";
    } else if (svEvent == SvEvent::TIMEOUT) {
        LOG(INFO) << "Received TIMEOUT event";
    } else {
        LOG(INFO) << "Received unknown event";
    }
    return {};
}

Return<void> SurroundViewCallback::receiveFrames(
    const SvFramesDesc& svFramesDesc) {
    LOG(INFO) << "Incoming frames with svBuffers size: " << svFramesDesc.svBuffers.size();
    if (svFramesDesc.svBuffers.size() == 0) {
        return {};
    }

    // Note: Only single frame is currently supported.
    if(!mOnSvFrameReceiveFn(svFramesDesc.svBuffers[0].hardwareBuffer)) {
        LOG(ERROR) << "Failed OnSvFrameReceiveFn() call.";
    }

    // Call HIDL API "doneWithFrames" to return the ownership.
    if (!mSession) {
        LOG(WARNING) << "SurroundViewSession in callback is invalid.";
    } else {
        mSession->doneWithFrames(svFramesDesc);
    }

    return {};
}
