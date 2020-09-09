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

#define LOG_TAG "powerpolicydaemon"

#include "CarPowerPolicyServer.h"

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

using android::base::Error;
using android::base::Result;
using android::binder::Status;

sp<CarPowerPolicyServer> CarPowerPolicyServer::sCarPowerPolicyServer = nullptr;

Result<void> CarPowerPolicyServer::startService(const android::sp<Looper>& looper) {
    if (sCarPowerPolicyServer != nullptr) {
        return Error(INVALID_OPERATION) << "Cannot start service more than once";
    }
    sp<CarPowerPolicyServer> server = new CarPowerPolicyServer();
    const auto& ret = server->init(looper);
    if (!ret.ok()) {
        return Error(ret.error().code())
                << "Failed to start car power policy server: " << ret.error();
    }
    return {};
}

void CarPowerPolicyServer::terminateService() {
    if (sCarPowerPolicyServer != nullptr) {
        sCarPowerPolicyServer->terminate();
        sCarPowerPolicyServer = nullptr;
    }
}

Status CarPowerPolicyServer::getCurrentPowerPolicy(CarPowerPolicy* /*aidlReturn*/) {
    return Status::fromExceptionCode(binder::Status::EX_UNSUPPORTED_OPERATION, "Not implemented");
}

Status CarPowerPolicyServer::getPowerComponentState(PowerComponent /*componentId*/,
                                                    bool* /*aidlReturn*/) {
    return Status::fromExceptionCode(binder::Status::EX_UNSUPPORTED_OPERATION, "Not implemented");
}

Status CarPowerPolicyServer::registerPowerPolicyChangeCallback(
        const sp<ICarPowerPolicyChangeCallback>& /*callback*/,
        const CarPowerPolicyFilter& /*filter*/) {
    return Status::fromExceptionCode(binder::Status::EX_UNSUPPORTED_OPERATION, "Not implemented");
}

Status CarPowerPolicyServer::unregisterPowerPolicyChangeCallback(
        const sp<ICarPowerPolicyChangeCallback>& /*callback*/) {
    return Status::fromExceptionCode(binder::Status::EX_UNSUPPORTED_OPERATION, "Not implemented");
}

status_t CarPowerPolicyServer::dump(int /*fd*/, const Vector<String16>& /*args*/) {
    // TODO(b/162599168): implement here
    return UNKNOWN_ERROR;
}

Result<void> CarPowerPolicyServer::init(const sp<Looper>& /*looper*/) {
    return Error(-1) << "Not implemented";
}

void CarPowerPolicyServer::terminate() {
    // TODO(b/162599168): implement here
}

void CarPowerPolicyServer::binderDied(const wp<IBinder>& /*who*/) {
    // TODO(b/162599168): implement here
}

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android
