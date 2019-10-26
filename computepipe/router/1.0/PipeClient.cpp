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

namespace android {
namespace automotive {
namespace computepipe {
namespace router {
namespace V1_0 {
namespace implementation {

using namespace android::automotive::computepipe::registry::V1_0;
using android::hardware::Return;

uint32_t PipeClient::getClientId() {
    if (mClientInfo == nullptr) {
        return 0;
    }
    Return<uint32_t> res = mClientInfo->getClientId();
    return res.isOk() ? static_cast<uint32_t>(res) : 0;
}
bool PipeClient::startClientMonitor() {
    mClientMonitor = new ClientMonitor();
    if (!mClientMonitor) {
        return false;
    }
    Return<bool> res = mClientInfo->linkToDeath(mClientMonitor, 0);
    return res.isOk() ? static_cast<bool>(res) : false;
}
bool PipeClient::isAlive() {
    return mClientMonitor->isAlive();
}

PipeClient::~PipeClient() {
    (void)mClientInfo->unlinkToDeath(mClientMonitor);
}

void ClientMonitor::serviceDied(uint64_t /* cookie */,
                                const wp<android::hidl::base::V1_0::IBase>& base) {
    std::lock_guard<std::mutex> lock(mStateLock);
    mAlive = false;
    auto iface = base.promote();
    if (iface != nullptr) {
        (void)iface->unlinkToDeath(this);
    }
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
