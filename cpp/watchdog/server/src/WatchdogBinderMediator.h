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

#ifndef CPP_WATCHDOG_SERVER_SRC_WATCHDOGBINDERMEDIATOR_H_
#define CPP_WATCHDOG_SERVER_SRC_WATCHDOGBINDERMEDIATOR_H_

#include "WatchdogPerfService.h"
#include "WatchdogProcessService.h"

#include <android-base/result.h>
#include <android/automotive/watchdog/BnCarWatchdog.h>
#include <android/automotive/watchdog/ComponentType.h>
#include <android/automotive/watchdog/IoOveruseConfiguration.h>
#include <android/automotive/watchdog/StateType.h>
#include <binder/IBinder.h>
#include <binder/Status.h>
#include <gtest/gtest_prod.h>
#include <utils/Errors.h>
#include <utils/String16.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

namespace android {
namespace automotive {
namespace watchdog {

class ServiceManager;

// WatchdogBinderMediator implements the carwatchdog binder APIs such that it forwards the calls
// either to process ANR or performance services.
class WatchdogBinderMediator : public BnCarWatchdog {
public:
    WatchdogBinderMediator() : mWatchdogProcessService(nullptr), mWatchdogPerfService(nullptr) {}

    status_t dump(int fd, const Vector<String16>& args) override;
    binder::Status registerClient(const sp<ICarWatchdogClient>& client,
                                  TimeoutLength timeout) override {
        return mWatchdogProcessService->registerClient(client, timeout);
    }
    binder::Status unregisterClient(const sp<ICarWatchdogClient>& client) override {
        return mWatchdogProcessService->unregisterClient(client);
    }
    binder::Status registerMediator(const sp<ICarWatchdogClient>& mediator) override;
    binder::Status unregisterMediator(const sp<ICarWatchdogClient>& mediator) override;
    binder::Status registerMonitor(const sp<ICarWatchdogMonitor>& monitor) override;
    binder::Status unregisterMonitor(const sp<ICarWatchdogMonitor>& monitor) override;
    binder::Status tellClientAlive(const sp<ICarWatchdogClient>& client,
                                   int32_t sessionId) override {
        return mWatchdogProcessService->tellClientAlive(client, sessionId);
    }
    binder::Status tellMediatorAlive(const sp<ICarWatchdogClient>& mediator,
                                     const std::vector<int32_t>& clientsNotResponding,
                                     int32_t sessionId) override {
        return mWatchdogProcessService->tellMediatorAlive(mediator, clientsNotResponding,
                                                          sessionId);
    }
    binder::Status tellDumpFinished(const android::sp<ICarWatchdogMonitor>& monitor,
                                    int32_t pid) override {
        return mWatchdogProcessService->tellDumpFinished(monitor, pid);
    }
    binder::Status notifySystemStateChange(StateType type, int32_t arg1, int32_t arg2) override;

    binder::Status updateIoOveruseConfiguration(ComponentType type,
                                                const IoOveruseConfiguration& config) override;

protected:
    android::base::Result<void> init(android::sp<WatchdogProcessService> watchdogProcessService,
                                     android::sp<WatchdogPerfService> watchdogPerfService);
    void terminate() {
        mWatchdogProcessService = nullptr;
        mWatchdogPerfService = nullptr;
    }

private:
    bool dumpHelpText(int fd, std::string errorMsg);

    android::sp<WatchdogProcessService> mWatchdogProcessService;
    android::sp<WatchdogPerfService> mWatchdogPerfService;

    friend class ServiceManager;
    friend class WatchdogBinderMediatorTest;
    FRIEND_TEST(WatchdogBinderMediatorTest, TestErrorOnNullptrDuringInit);
    FRIEND_TEST(WatchdogBinderMediatorTest, TestHandlesEmptyDumpArgs);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  // CPP_WATCHDOG_SERVER_SRC_WATCHDOGBINDERMEDIATOR_H_
