/**
 * Copyright 2019 The Android Open Source Project
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

#include "PipeClient.h"

#include <binder/ProcessState.h>

namespace android {
namespace automotive {
namespace computepipe {
namespace router {
namespace V1_0 {
namespace implementation {

using namespace android::automotive::computepipe::registry;

uint32_t PipeClient::getClientId() {
    if (mClientInfo == nullptr) {
        return 0;
    }
    int id = 0;
    auto status = mClientInfo->getClientId(&id);
    uint32_t res = (status.isOk() && id > 0) ? id : 0;
    return res;
}

bool PipeClient::startClientMonitor() {
    mClientMonitor = new ClientMonitor();
    if (!mClientMonitor) {
        return false;
    }
    const sp<IBinder> client = IInterface::asBinder(mClientInfo);
    if (!client) {
        return false;
    }
    auto res = client->linkToDeath(mClientMonitor);
    return res == OK;
}

bool PipeClient::isAlive() {
    return mClientMonitor->isAlive();
}

PipeClient::~PipeClient() {
    const sp<IBinder> client = IInterface::asBinder(mClientInfo);
    (void)client->unlinkToDeath(mClientMonitor);
}

void ClientMonitor::binderDied(const wp<android::IBinder>& /* base */) {
    std::lock_guard<std::mutex> lock(mStateLock);
    mAlive = false;
}

bool ClientMonitor::isAlive() {
    std::lock_guard<std::mutex> lock(mStateLock);
    return mAlive;
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace router
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
