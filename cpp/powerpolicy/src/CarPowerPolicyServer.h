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

#ifndef CPP_POWERPOLICY_SRC_CARPOWERPOLICYSERVER_H_
#define CPP_POWERPOLICY_SRC_CARPOWERPOLICYSERVER_H_

#include "PowerComponentHandler.h"

#include <android-base/result.h>
#include <android/frameworks/automotive/powerpolicy/BnCarPowerPolicyServer.h>
#include <binder/IBinder.h>
#include <binder/Status.h>
#include <utils/Looper.h>
#include <utils/Mutex.h>
#include <utils/String16.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

#include <unordered_set>

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

struct CallbackInfo {
    CallbackInfo(const sp<ICarPowerPolicyChangeCallback>& callback,
                 const CarPowerPolicyFilter& filter, int32_t pid) :
          callback(callback),
          filter(filter),
          pid(pid) {}

    sp<ICarPowerPolicyChangeCallback> callback;
    CarPowerPolicyFilter filter;
    pid_t pid;
};

std::string toString(const std::vector<PowerComponent>& components);

class CarPowerPolicyServer : public BnCarPowerPolicyServer, public IBinder::DeathRecipient {
public:
    static base::Result<sp<CarPowerPolicyServer>> startService(const android::sp<Looper>& looper);
    static void terminateService();

    status_t dump(int fd, const Vector<String16>& args) override;
    binder::Status getCurrentPowerPolicy(CarPowerPolicy* aidlReturn) override;
    binder::Status getPowerComponentState(PowerComponent componentId, bool* aidlReturn) override;
    binder::Status registerPowerPolicyChangeCallback(
            const sp<ICarPowerPolicyChangeCallback>& callback,
            const CarPowerPolicyFilter& filter) override;
    binder::Status unregisterPowerPolicyChangeCallback(
            const sp<ICarPowerPolicyChangeCallback>& callback) override;
    void binderDied(const wp<IBinder>& who) override;

private:
    CarPowerPolicyServer() : mCurrentPowerPolicy(nullptr) {}

    base::Result<void> init(const sp<Looper>& looper);
    void terminate();
    bool isRegisteredLocked(const sp<ICarPowerPolicyChangeCallback>& callback);
    void readVendorPowerPolicy();
    void checkSilentModeFromKernel();
    base::Result<void> subscribeToVhal();

private:
    static sp<CarPowerPolicyServer> sCarPowerPolicyServer;

    sp<Looper> mHandlerLooper;
    PowerComponentHandler mComponentHandler;
    android::Mutex mMutex;
    CarPowerPolicy* mCurrentPowerPolicy GUARDED_BY(mMutex);
    std::vector<CallbackInfo> mPolicyChangeCallbacks GUARDED_BY(mMutex);
};

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android

#endif  // CPP_POWERPOLICY_SRC_CARPOWERPOLICYSERVER_H_
