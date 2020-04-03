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
#define LOG_TAG "SurroundViewService"

#include <android-base/logging.h>

#include "CoreLibSetupHelper.h"
#include "SurroundViewService.h"

using namespace android_auto::surround_view;

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace V1_0 {
namespace implementation {

std::mutex SurroundViewService::sLock;
sp<SurroundViewService> SurroundViewService::sService;
sp<SurroundView2dSession> SurroundViewService::sSurroundView2dSession;
sp<SurroundView3dSession> SurroundViewService::sSurroundView3dSession;

const std::string kCameraIds[] = {"0", "1", "2", "3"};

sp<SurroundViewService> SurroundViewService::getInstance() {
    std::scoped_lock<std::mutex> lock(sLock);
    if (sService == nullptr) {
        sService = new SurroundViewService();
    }
    return sService;
}

Return<void> SurroundViewService::getCameraIds(getCameraIds_cb _hidl_cb) {
    hidl_vec<hidl_string> cameraIds = {kCameraIds[0], kCameraIds[1],
        kCameraIds[2], kCameraIds[3]};
    _hidl_cb(cameraIds);
    return {};
}

Return<void> SurroundViewService::start2dSession(start2dSession_cb _hidl_cb) {
    LOG(DEBUG) << __FUNCTION__;
    std::scoped_lock<std::mutex> lock(sLock);

    if (sSurroundView2dSession != nullptr) {
        LOG(WARNING) << "Only one 2d session is supported at the same time";
        _hidl_cb(nullptr, SvResult::INTERNAL_ERROR);
    } else {
        sSurroundView2dSession = new SurroundView2dSession();
        _hidl_cb(sSurroundView2dSession, SvResult::OK);
    }
    return {};
}

Return<SvResult> SurroundViewService::stop2dSession(
    const sp<ISurroundView2dSession>& sv2dSession) {
    LOG(DEBUG) << __FUNCTION__;
    std::scoped_lock<std::mutex> lock(sLock);

    if (sv2dSession != nullptr && sv2dSession == sSurroundView2dSession) {
        sSurroundView2dSession = nullptr;
        return SvResult::OK;
    } else {
        LOG(ERROR) << __FUNCTION__ << ": Invalid argument";
        return SvResult::INVALID_ARG;
    }
}

Return<void> SurroundViewService::start3dSession(start3dSession_cb _hidl_cb) {
    LOG(DEBUG) << __FUNCTION__;
    std::scoped_lock<std::mutex> lock(sLock);

    if (sSurroundView3dSession != nullptr) {
        LOG(WARNING) << "Only one 3d session is supported at the same time";
        _hidl_cb(nullptr, SvResult::INTERNAL_ERROR);
    } else {
        sSurroundView3dSession = new SurroundView3dSession();
        _hidl_cb(sSurroundView3dSession, SvResult::OK);
    }
    return {};
}

Return<SvResult> SurroundViewService::stop3dSession(
    const sp<ISurroundView3dSession>& sv3dSession) {
    LOG(DEBUG) << __FUNCTION__;
    std::scoped_lock<std::mutex> lock(sLock);

    if (sv3dSession != nullptr && sv3dSession == sSurroundView3dSession) {
        sSurroundView3dSession = nullptr;
        return SvResult::OK;
    } else {
        LOG(ERROR) << __FUNCTION__ << ": Invalid argument";
        return SvResult::INVALID_ARG;
    }
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android

