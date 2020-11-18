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

#ifndef CPP_WATCHDOG_SERVER_SRC_WATCHDOGSERVICEHELPER_H_
#define CPP_WATCHDOG_SERVER_SRC_WATCHDOGSERVICEHELPER_H_

#include <android-base/result.h>
#include <android/automotive/watchdog/TimeoutLength.h>
#include <android/automotive/watchdog/internal/ICarWatchdogServiceForSystem.h>
#include <binder/IBinder.h>
#include <binder/Status.h>
#include <gtest/gtest_prod.h>
#include <utils/Mutex.h>
#include <utils/StrongPointer.h>

#include <shared_mutex>

namespace android {
namespace automotive {
namespace watchdog {

class ServiceManager;
class WatchdogProcessService;

class WatchdogServiceHelperInterface : public android::IBinder::DeathRecipient {
public:
    virtual android::base::Result<void> init(
            const android::sp<WatchdogProcessService>& watchdogProcessService) = 0;
    virtual android::binder::Status registerService(
            const android::sp<
                    android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service) = 0;
    virtual android::binder::Status unregisterService(
            const android::sp<
                    android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>&
                    service) = 0;

    // Helper methods for APIs in ICarWatchdogServiceForSystem.aidl.
    virtual android::binder::Status checkIfAlive(const android::wp<android::IBinder>& who,
                                                 int32_t sessionId,
                                                 TimeoutLength timeout) const = 0;
    virtual android::binder::Status prepareProcessTermination(
            const android::wp<android::IBinder>& who) = 0;

protected:
    virtual void terminate() = 0;

private:
    friend class ServiceManager;
};

// WatchdogServiceHelper implements the helper functions for the outbound API requests to
// the CarWatchdogService. This class doesn't handle the inbound APIs requests from
// CarWatchdogService except the registration APIs.
class WatchdogServiceHelper : public WatchdogServiceHelperInterface {
public:
    WatchdogServiceHelper() : mService(nullptr), mWatchdogProcessService(nullptr) {}
    ~WatchdogServiceHelper();

    android::base::Result<void> init(
            const android::sp<WatchdogProcessService>& watchdogProcessService);
    android::binder::Status registerService(
            const android::sp<
                    android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>& service)
            override;
    android::binder::Status unregisterService(
            const android::sp<
                    android::automotive::watchdog::internal::ICarWatchdogServiceForSystem>& service)
            override;
    void binderDied(const android::wp<android::IBinder>& who) override;

    // Helper methods for ICarWatchdogServiceForSystem.aidl.
    android::binder::Status checkIfAlive(const android::wp<android::IBinder>& who,
                                         int32_t sessionId, TimeoutLength timeout) const override;
    android::binder::Status prepareProcessTermination(
            const android::wp<android::IBinder>& who) override;

protected:
    void terminate();

private:
    void unregisterServiceLocked();

    mutable std::shared_mutex mRWMutex;
    android::sp<android::automotive::watchdog::internal::ICarWatchdogServiceForSystem> mService
            GUARDED_BY(mRWMutex);
    android::sp<WatchdogProcessService> mWatchdogProcessService;

    // For unit tests.
    FRIEND_TEST(WatchdogServiceHelperTest, TestTerminate);
    friend class ServiceManager;
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  // CPP_WATCHDOG_SERVER_SRC_WATCHDOGSERVICEHELPER_H_
