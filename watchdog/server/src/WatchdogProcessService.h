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

#ifndef WATCHDOG_SERVER_SRC_WATCHDOGPROCESSSERVICE_H_
#define WATCHDOG_SERVER_SRC_WATCHDOGPROCESSSERVICE_H_

#include <android/automotive/watchdog/BnCarWatchdog.h>
#include <utils/Looper.h>
#include <utils/Mutex.h>

#include <map>
#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

class WatchdogProcessService : public BnCarWatchdog, public IBinder::DeathRecipient {
  public:
    explicit WatchdogProcessService(const android::sp<Looper>& handlerLooper);

    status_t dump(int fd, const Vector<String16>& args) override;

    binder::Status registerClient(const sp<ICarWatchdogClient>& client,
                                  TimeoutLength timeout) override;
    binder::Status unregisterClient(const sp<ICarWatchdogClient>& client) override;
    binder::Status registerMediator(const sp<ICarWatchdogClient>& mediator) override;
    binder::Status unregisterMediator(const sp<ICarWatchdogClient>& mediator) override;
    binder::Status registerMonitor(const sp<ICarWatchdogMonitor>& monitor) override;
    binder::Status unregisterMonitor(const sp<ICarWatchdogMonitor>& monitor) override;
    binder::Status tellClientAlive(const sp<ICarWatchdogClient>& client, int32_t sessionId) override;
    binder::Status tellMediatorAlive(const sp<ICarWatchdogClient>& mediator,
                                     const std::vector<int32_t>& clientsNotResponding,
                                     int32_t sessionId) override;
    binder::Status tellDumpFinished(const android::sp<ICarWatchdogMonitor>& monitor,
                                    int32_t pid) override;
    binder::Status notifyPowerCycleChange(PowerCycle cycle) override;
    binder::Status notifyUserStateChange(int32_t userId, UserState state) override;

    void doHealthCheck(int what);

  private:
    enum ClientType {
        Regular,
        Mediator,
    };

    struct ClientInfo {
        ClientInfo(const android::sp<ICarWatchdogClient>& client, pid_t pid, ClientType type)
            : client(client), pid(pid), type(type) {
        }

        android::sp<ICarWatchdogClient> client;
        pid_t pid;
        ClientType type;
    };

    class MessageHandlerImpl : public MessageHandler {
      public:
        explicit MessageHandlerImpl(const android::sp<WatchdogProcessService>& service);

        void handleMessage(const Message& message) override;

      private:
        android::sp<WatchdogProcessService> mService;
    };

  private:
    void binderDied(const android::wp<IBinder>& who) override;

    binder::Status unregisterClientLocked(const std::vector<TimeoutLength>& timeouts,
                                          android::sp<IBinder> binder);
    bool isRegisteredLocked(const android::sp<ICarWatchdogClient>& client);
    binder::Status registerClientLocked(const sp<ICarWatchdogClient>& client, TimeoutLength timeout,
                                ClientType clientType);
    void startHealthChecking(TimeoutLength timeout);
    int32_t getNewSessionId();

    using Processor =
        std::function<void(std::vector<ClientInfo>&, std::vector<ClientInfo>::const_iterator)>;
    bool findClientAndProcessLocked(const std::vector<TimeoutLength> timeouts,
                                    const android::sp<IBinder> binder, const Processor& processor);

  private:
    Mutex mMutex;
    sp<Looper> mHandlerLooper;
    android::sp<MessageHandlerImpl> mMessageHandler;
    std::map<TimeoutLength, std::vector<ClientInfo>> mClients GUARDED_BY(mMutex);
    android::sp<ICarWatchdogMonitor> mMonitor GUARDED_BY(mMutex);
    // mLastSessionId is accessed only within main thread. No need for mutual-exclusion.
    int32_t mLastSessionId;
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  // WATCHDOG_SERVER_SRC_WATCHDOGPROCESSSERVICE_H_
