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
#define DEBUG false

#include "WatchdogProcessService.h"

#include <android-base/chrono_utils.h>
#include <android-base/stringprintf.h>
#include <binder/IPCThreadState.h>
#include <private/android_filesystem_config.h>

namespace android {
namespace automotive {
namespace watchdog {

using std::literals::chrono_literals::operator""s;
using android::base::Error;
using android::base::Result;
using android::base::StringPrintf;
using android::binder::Status;

static const std::vector<TimeoutLength> kTimeouts = {TimeoutLength::TIMEOUT_CRITICAL,
                                                     TimeoutLength::TIMEOUT_MODERATE,
                                                     TimeoutLength::TIMEOUT_NORMAL};

static std::chrono::nanoseconds timeoutToDurationNs(const TimeoutLength& timeout) {
    switch (timeout) {
        case TimeoutLength::TIMEOUT_CRITICAL:
            return 3s;  // 3s and no buffer time.
        case TimeoutLength::TIMEOUT_MODERATE:
            return 6s;  // 5s + 1s as buffer time.
        case TimeoutLength::TIMEOUT_NORMAL:
            return 12s;  // 10s + 2s as buffer time.
    }
}

static Status checkSystemPermission() {
    uid_t callingUid = IPCThreadState::self()->getCallingUid();
    if (callingUid != AID_SYSTEM) {
        return Status::fromExceptionCode(Status::EX_SECURITY,
                                         "Calling process does not have proper privilege.");
    }
    return Status::ok();
}

WatchdogProcessService::WatchdogProcessService(const sp<Looper>& handlerLooper) :
      mHandlerLooper(handlerLooper), mLastSessionId(0) {
    mMessageHandler = new MessageHandlerImpl(this);
    for (const auto& timeout : kTimeouts) {
        mClients.insert(std::make_pair(timeout, std::vector<ClientInfo>()));
        mPingedClients.insert(std::make_pair(timeout, PingedClientSet()));
    }
}

Status WatchdogProcessService::registerClient(const sp<ICarWatchdogClient>& client,
                                              TimeoutLength timeout) {
    Mutex::Autolock lock(mMutex);
    return registerClientLocked(client, timeout, ClientType::Regular);
}

Status WatchdogProcessService::unregisterClient(const sp<ICarWatchdogClient>& client) {
    Mutex::Autolock lock(mMutex);
    sp<IBinder> binder = asBinder(client);
    // kTimeouts is declared as global static constant to cover all kinds of timeout (CRITICAL,
    // MODERATE, NORMAL).
    Status status = unregisterClientLocked(kTimeouts, binder);
    if (!status.isOk()) {
        ALOGW("Cannot unregister the client: %s", status.exceptionMessage().c_str());
        return status;
    }
    return Status::ok();
}

Status WatchdogProcessService::registerMediator(const sp<ICarWatchdogClient>& mediator) {
    Status status = checkSystemPermission();
    if (!status.isOk()) {
        return status;
    }
    Mutex::Autolock lock(mMutex);
    // Mediator's timeout is always TIMEOUT_NORMAL.
    return registerClientLocked(mediator, TimeoutLength::TIMEOUT_NORMAL, ClientType::Mediator);
}

Status WatchdogProcessService::unregisterMediator(const sp<ICarWatchdogClient>& mediator) {
    Status status = checkSystemPermission();
    if (!status.isOk()) {
        return status;
    }
    std::vector<TimeoutLength> timeouts = {TimeoutLength::TIMEOUT_NORMAL};
    sp<IBinder> binder = asBinder(mediator);
    Mutex::Autolock lock(mMutex);
    status = unregisterClientLocked(timeouts, binder);
    if (!status.isOk()) {
        ALOGW("Cannot unregister the mediator. The mediator has not been registered.");
        return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         "The mediator has not been registered.");
    }
    return Status::ok();
}

Status WatchdogProcessService::registerMonitor(const sp<ICarWatchdogMonitor>& monitor) {
    Status status = checkSystemPermission();
    if (!status.isOk()) {
        return status;
    }
    Mutex::Autolock lock(mMutex);
    if (mMonitor != nullptr) {
        ALOGW("Cannot register the monitor. The other monitor is already registered.");
        return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         "The other monitor is already registered.");
    }
    sp<IBinder> binder = asBinder(monitor);
    status_t ret = binder->linkToDeath(this);
    if (ret != OK) {
        ALOGW("Cannot register the monitor. The monitor is dead.");
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "The monitor is dead.");
    }
    mMonitor = monitor;
    return Status::ok();
}

Status WatchdogProcessService::unregisterMonitor(const sp<ICarWatchdogMonitor>& monitor) {
    Status status = checkSystemPermission();
    if (!status.isOk()) {
        return status;
    }
    Mutex::Autolock lock(mMutex);
    if (mMonitor != monitor) {
        ALOGW("Cannot unregister the monitor. The monitor has not been registered.");
        return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         "The monitor has not been registered.");
    }
    sp<IBinder> binder = asBinder(monitor);
    binder->unlinkToDeath(this);
    mMonitor = nullptr;
    return Status::ok();
}

Status WatchdogProcessService::tellClientAlive(const sp<ICarWatchdogClient>& client,
                                               int32_t sessionId) {
    Mutex::Autolock lock(mMutex);
    return tellClientAliveLocked(client, sessionId);
}

Status WatchdogProcessService::tellMediatorAlive(const sp<ICarWatchdogClient>& mediator,
                                                 const std::vector<int32_t>& clientsNotResponding,
                                                 int32_t sessionId) {
    Status status;
    {
        Mutex::Autolock lock(mMutex);
        status = tellClientAliveLocked(mediator, sessionId);
    }
    if (status.isOk()) {
        dumpAndKillAllProcesses(clientsNotResponding);
    }
    return status;
}

Status WatchdogProcessService::tellDumpFinished(const sp<ICarWatchdogMonitor>& monitor,
                                                int32_t pid) {
    // TODO(b/148223510): implement this method.
    (void)monitor;
    (void)pid;
    return Status::ok();
}

Status WatchdogProcessService::notifyPowerCycleChange(PowerCycle cycle) {
    // TODO(b/148223510): implement this method.
    (void)cycle;
    return Status::ok();
}

Status WatchdogProcessService::notifyUserStateChange(int32_t userId, UserState state) {
    // TODO(b/148223510): implement this method.
    (void)userId;
    (void)state;
    return Status::ok();
}

status_t WatchdogProcessService::dump(int fd, const Vector<String16>&) {
    // TODO(b/148223510): implement this method.
    (void)fd;
    return NO_ERROR;
}

void WatchdogProcessService::doHealthCheck(int what) {
    mHandlerLooper->removeMessages(mMessageHandler, what);
    const TimeoutLength timeout = static_cast<TimeoutLength>(what);
    std::vector<ClientInfo> clientsToCheck;
    PingedClientSet& pingedClients = mPingedClients[timeout];

    dumpAndKillClientsIfNotResponding(timeout);

    /* Generates a temporary/local vector containing clients.
     * Using a local copy may send unnecessary ping messages to clients after they are unregistered.
     * Clients should be able to handle them.
     */
    {
        Mutex::Autolock lock(mMutex);
        clientsToCheck = mClients[timeout];
        pingedClients.clear();
    }

    for (const auto& clientInfo : clientsToCheck) {
        int32_t sessionId = getNewSessionId();
        PingedClient targetClient(clientInfo.client, sessionId);
        {
            Mutex::Autolock lock(mMutex);
            pingedClients.insert(targetClient);
        }
        Status status = clientInfo.client->checkIfAlive(sessionId, timeout);
        if (!status.isOk()) {
            ALOGW("Sending a ping message to client(pid: %d) failed: %s", clientInfo.pid,
                  status.exceptionMessage().c_str());
            {
                Mutex::Autolock lock(mMutex);
                pingedClients.erase(targetClient);
            }
        }
    }
    // Though the size of pingedClients is a more specific measure, clientsToCheck is used as a
    // conservative approach.
    if (clientsToCheck.size() > 0) {
        auto durationNs = timeoutToDurationNs(timeout);
        mHandlerLooper->sendMessageDelayed(durationNs.count(), mMessageHandler, Message(what));
    }
}

void WatchdogProcessService::terminate() {
    Mutex::Autolock lock(mMutex);
    for (const auto& timeout : kTimeouts) {
        std::vector<ClientInfo>& clients = mClients[timeout];
        for (auto it = clients.begin(); it != clients.end();) {
            sp<IBinder> binder = asBinder((*it).client);
            binder->unlinkToDeath(this);
            it = clients.erase(it);
        }
    }
}

void WatchdogProcessService::binderDied(const wp<IBinder>& who) {
    Mutex::Autolock lock(mMutex);
    IBinder* binder = who.unsafe_get();
    // Check if dead binder is monitor.
    sp<IBinder> monitor = asBinder(mMonitor);
    if (monitor == binder) {
        mMonitor = nullptr;
        ALOGI("The monitor has died.");
        return;
    }
    findClientAndProcessLocked(kTimeouts, binder,
                               [&](std::vector<ClientInfo>& clients,
                                   std::vector<ClientInfo>::const_iterator it) {
                                   clients.erase(it);
                               });
}

bool WatchdogProcessService::isRegisteredLocked(const sp<ICarWatchdogClient>& client) {
    sp<IBinder> binder = asBinder(client);
    return findClientAndProcessLocked(kTimeouts, binder, nullptr);
}

Status WatchdogProcessService::registerClientLocked(const sp<ICarWatchdogClient>& client,
                                                    TimeoutLength timeout, ClientType clientType) {
    const char* clientName = clientType == ClientType::Regular ? "client" : "mediator";
    if (isRegisteredLocked(client)) {
        std::string errorStr = StringPrintf("The %s is already registered.", clientName);
        const char* errorCause = errorStr.c_str();
        ALOGW("Cannot register the %s. %s", clientName, errorCause);
        return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT, errorCause);
    }
    sp<IBinder> binder = asBinder(client);
    status_t status = binder->linkToDeath(this);
    if (status != OK) {
        std::string errorStr = StringPrintf("The %s is dead.", clientName);
        const char* errorCause = errorStr.c_str();
        ALOGW("Cannot register the %s. %s", clientName, errorCause);
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, errorCause);
    }
    std::vector<ClientInfo>& clients = mClients[timeout];
    pid_t callingPid = IPCThreadState::self()->getCallingPid();
    clients.push_back(ClientInfo(client, callingPid, clientType));

    // If the client array becomes non-empty, start health checking.
    if (clients.size() == 1) {
        startHealthChecking(timeout);
    }
    return Status::ok();
}

Status WatchdogProcessService::unregisterClientLocked(const std::vector<TimeoutLength>& timeouts,
                                                      sp<IBinder> binder) {
    bool result = findClientAndProcessLocked(timeouts, binder,
                                             [&](std::vector<ClientInfo>& clients,
                                                 std::vector<ClientInfo>::const_iterator it) {
                                                 binder->unlinkToDeath(this);
                                                 clients.erase(it);
                                             });
    return result ? Status::ok()
                  : Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                              "The client has not been registered.");
}

Status WatchdogProcessService::tellClientAliveLocked(const sp<ICarWatchdogClient>& client,
                                                     int32_t sessionId) {
    for (const auto& timeout : kTimeouts) {
        PingedClientSet& clients = mPingedClients[timeout];
        PingedClient respondingClient(client, sessionId);
        PingedClientSet::const_iterator it = clients.find(respondingClient);
        if (it == clients.cend()) {
            continue;
        }
        clients.erase(it);
        return Status::ok();
    }
    return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                     "The client is not registered or the session ID is not found");
}

bool WatchdogProcessService::findClientAndProcessLocked(const std::vector<TimeoutLength> timeouts,
                                                        const sp<IBinder> binder,
                                                        const Processor& processor) {
    for (const auto& timeout : timeouts) {
        std::vector<ClientInfo>& clients = mClients[timeout];
        for (auto it = clients.begin(); it != clients.end(); it++) {
            if (asBinder((*it).client) != binder) {
                continue;
            }
            if (processor != nullptr) {
                processor(clients, it);
            }
            return true;
        }
    }
    return false;
}

Result<void> WatchdogProcessService::startHealthChecking(TimeoutLength timeout) {
    int what = static_cast<int>(timeout);
    auto durationNs = timeoutToDurationNs(timeout);
    mHandlerLooper->sendMessageDelayed(durationNs.count(), mMessageHandler, Message(what));
    return {};
}

Result<void> WatchdogProcessService::dumpAndKillClientsIfNotResponding(TimeoutLength timeout) {
    std::vector<int32_t> processIds;
    {
        Mutex::Autolock lock(mMutex);
        PingedClientSet& clients = mPingedClients[timeout];
        for (PingedClientSet::const_iterator it = clients.cbegin(); it != clients.cend(); it++) {
            pid_t pid = -1;
            sp<IBinder> binder = asBinder((*it).client);
            std::vector<TimeoutLength> timeouts = {timeout};
            findClientAndProcessLocked(timeouts, binder,
                                       [&](std::vector<ClientInfo>& clients,
                                           std::vector<ClientInfo>::const_iterator it) {
                                           pid = (*it).pid;
                                           clients.erase(it);
                                       });
            if (pid != -1) {
                processIds.push_back(pid);
            }
        }
    }
    return dumpAndKillAllProcesses(processIds);
}

// TODO(ericjeong): do it quickly or do it in a separate thread.
Result<void> WatchdogProcessService::dumpAndKillAllProcesses(
        const std::vector<int32_t>& processesNotResponding) {
    Mutex::Autolock lock(mMutex);
    if (mMonitor == nullptr) {
        std::string errorMsg = "Cannot dump and kill processes: Monitor is not set";
        ALOGW("%s", errorMsg.c_str());
        return Error() << errorMsg;
    }
    // TODO(b/149346622): Change the interface of ICarWatchdogMonitor and follow up here.
    for (auto pid : processesNotResponding) {
        mMonitor->onClientNotResponding(nullptr, pid);
        ALOGD("Dumping and killing process(%d) is requested.", pid);
    }
    return {};
}

int32_t WatchdogProcessService::getNewSessionId() {
    // Make sure that session id is always positive number.
    if (++mLastSessionId <= 0) {
        mLastSessionId = 1;
    }
    return mLastSessionId;
}

WatchdogProcessService::MessageHandlerImpl::MessageHandlerImpl(
        const sp<WatchdogProcessService>& service) :
      mService(service) {}

void WatchdogProcessService::MessageHandlerImpl::handleMessage(const Message& message) {
    switch (message.what) {
        case static_cast<int>(TimeoutLength::TIMEOUT_CRITICAL):
        case static_cast<int>(TimeoutLength::TIMEOUT_MODERATE):
        case static_cast<int>(TimeoutLength::TIMEOUT_NORMAL):
            mService->doHealthCheck(message.what);
            break;
        default:
            ALOGW("Unknown message: %d", message.what);
    }
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
