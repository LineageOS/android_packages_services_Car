/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "carwatchdogd"

#include "WatchdogServiceHelper.h"

#include <android/automotive/watchdog/internal/BnCarWatchdogServiceForSystem.h>

namespace android {
namespace automotive {
namespace watchdog {

namespace aawi = android::automotive::watchdog::internal;

using android::IBinder;
using android::sp;
using android::wp;
using android::automotive::watchdog::internal::BnCarWatchdogServiceForSystem;
using android::automotive::watchdog::internal::ICarWatchdogServiceForSystem;
using android::base::Result;
using android::binder::Status;

Status WatchdogServiceHelper::registerService(
        const android::sp<ICarWatchdogServiceForSystem>& service) {
    std::unique_lock write_lock(mRWMutex);
    sp<IBinder> binder = BnCarWatchdogServiceForSystem::asBinder(service);
    if (mService != nullptr && binder == BnCarWatchdogServiceForSystem::asBinder(mService)) {
        return Status::ok();
    }
    status_t ret = binder->linkToDeath(this);
    if (ret != OK) {
        ALOGW("Failed to register car watchdog service as it is dead.");
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "Car watchdog service is dead");
    }
    mService = service;
    return Status::ok();
}

Status WatchdogServiceHelper::unregisterService(const sp<ICarWatchdogServiceForSystem>& service) {
    std::unique_lock write_lock(mRWMutex);
    sp<IBinder> curBinder = BnCarWatchdogServiceForSystem::asBinder(mService);
    sp<IBinder> newBinder = BnCarWatchdogServiceForSystem::asBinder(service);
    if (curBinder != newBinder) {
        ALOGW("Failed to unregister car watchdog service as it is not registered.");
        return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         "Car watchdog service is not registered");
    }
    curBinder->unlinkToDeath(this);
    mService = nullptr;
    return Status::ok();
}

void WatchdogServiceHelper::binderDied(const wp<android::IBinder>& who) {
    std::unique_lock write_lock(mRWMutex);
    IBinder* diedBinder = who.unsafe_get();
    if (diedBinder != BnCarWatchdogServiceForSystem::asBinder(mService)) {
        return;
    }
    ALOGW("Car watchdog service had died.");
    mService = nullptr;
}

void WatchdogServiceHelper::terminate() {
    std::unique_lock write_lock(mRWMutex);
    if (mService != nullptr) {
        BnCarWatchdogServiceForSystem::asBinder(mService)->unlinkToDeath(this);
        mService = nullptr;
    }
}

Status WatchdogServiceHelper::checkIfAlive(int32_t sessionId, TimeoutLength timeout) {
    std::shared_lock read_lock(mRWMutex);
    if (mService == nullptr) {
        ALOGW("Failed to checkIfAlive. Car watchdog service is not registered");
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE,
                                         "Car watchdog service is not registered");
    }
    return mService->checkIfAlive(sessionId, static_cast<aawi::TimeoutLength>(timeout));
}

Status WatchdogServiceHelper::prepareProcessTermination() {
    std::shared_lock read_lock(mRWMutex);
    if (mService == nullptr) {
        ALOGW("Failed to prepareProcessTermination. Car watchdog service is not registered");
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE,
                                         "Car watchdog service is not registered");
    }
    return mService->prepareProcessTermination();
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
