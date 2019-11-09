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

#ifndef ANDROID_AUTOMOTIVE_COMPUTEPIPE_ROUTER_V1_0_PIPECLIENT
#define ANDROID_AUTOMOTIVE_COMPUTEPIPE_ROUTER_V1_0_PIPECLIENT

#include <android/automotive/computepipe/registry/IClientInfo.h>

#include <functional>
#include <mutex>

#include "ClientHandle.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace router {
namespace V1_0 {
namespace implementation {

/**
 * Tracks Client Death
 */
struct ClientMonitor : public IBinder::DeathRecipient {
  public:
    /* override method to track client death */
    virtual void binderDied(const wp<android::IBinder>& base) override;
    /* query for client death */
    bool isAlive();

  private:
    std::function<void()> mHandleCb;
    bool mAlive = true;
    std::mutex mStateLock;
};

/**
 * PipeClient: Encapsulated the IPC interface to the client.
 *
 * Allows for querrying the client state
 */
class PipeClient : public ClientHandle {
  public:
    explicit PipeClient(const sp<android::automotive::computepipe::registry::IClientInfo>& info)
        : mClientInfo(info) {
    }
    bool startClientMonitor() override;
    uint32_t getClientId() override;
    bool isAlive() override;
    ~PipeClient();

  private:
    sp<ClientMonitor> mClientMonitor;
    sp<android::automotive::computepipe::registry::IClientInfo> mClientInfo;
};

}  // namespace implementation
}  // namespace V1_0
}  // namespace router
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
#endif
