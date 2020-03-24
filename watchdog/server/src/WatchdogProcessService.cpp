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
#define DEBUG true  // TODO(b/151474489): stop ship if true.

#include "WatchdogProcessService.h"

#include <android-base/chrono_utils.h>
#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <binder/IPCThreadState.h>

namespace android {
namespace automotive {
namespace watchdog {

using std::literals::chrono_literals::operator""s;
using android::base::Error;
using android::base::Result;
using android::base::StringAppendF;
using android::base::StringPrintf;
using android::base::WriteStringToFd;
using android::binder::Status;

namespace {

const std::vector<TimeoutLength> kTimeouts = {TimeoutLength::TIMEOUT_CRITICAL,
                                              TimeoutLength::TIMEOUT_MODERATE,
                                              TimeoutLength::TIMEOUT_NORMAL};

std::chrono::nanoseconds timeoutToDurationNs(const TimeoutLength& timeout) {
    switch (timeout) {
        case TimeoutLength::TIMEOUT_CRITICAL:
            return 3s;  // 3s and no buffer time.
        case TimeoutLength::TIMEOUT_MODERATE:
            return 6s;  // 5s + 1s as buffer time.
        case TimeoutLength::TIMEOUT_NORMAL:
            return 12s;  // 10s + 2s as buffer time.
    }
}

}  // namespace

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
    sp<IBinder> binder = BnCarWatchdog::asBinder(client);
    // kTimeouts is declared as global static constant to cover all kinds of timeout (CRITICAL,
    // MODERATE, NORMAL).
    return unregisterClientLocked(kTimeouts, binder, ClientType::Regular);
}

Status WatchdogProcessService::registerMediator(const sp<ICarWatchdogClient>& mediator) {
    Mutex::Autolock lock(mMutex);
    // Mediator's timeout is always TIMEOUT_NORMAL.
    return registerClientLocked(mediator, TimeoutLength::TIMEOUT_NORMAL, ClientType::Mediator);
}

Status WatchdogProcessService::unregisterMediator(const sp<ICarWatchdogClient>& mediator) {
    std::vector<TimeoutLength> timeouts = {TimeoutLength::TIMEOUT_NORMAL};
    sp<IBinder> binder = BnCarWatchdog::asBinder(mediator);
    Mutex::Autolock lock(mMutex);
    return unregisterClientLocked(timeouts, binder, ClientType::Mediator);
}

Status WatchdogProcessService::registerMonitor(const sp<ICarWatchdogMonitor>& monitor) {
    Mutex::Autolock lock(mMutex);
    if (mMonitor != nullptr) {
        ALOGW("Cannot register the monitor. The other monitor is already registered.");
        return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         "The other monitor is already registered.");
    }
    sp<IBinder> binder = BnCarWatchdog::asBinder(monitor);
    status_t ret = binder->linkToDeath(this);
    if (ret != OK) {
        ALOGW("Cannot register the monitor. The monitor is dead.");
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, "The monitor is dead.");
    }
    mMonitor = monitor;
    if (DEBUG) {
        ALOGD("Car watchdog monitor is registered");
    }
    return Status::ok();
}

Status WatchdogProcessService::unregisterMonitor(const sp<ICarWatchdogMonitor>& monitor) {
    Mutex::Autolock lock(mMutex);
    if (mMonitor != monitor) {
        ALOGW("Cannot unregister the monitor. The monitor has not been registered.");
        return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                         "The monitor has not been registered.");
    }
    sp<IBinder> binder = BnCarWatchdog::asBinder(monitor);
    binder->unlinkToDeath(this);
    mMonitor = nullptr;
    if (DEBUG) {
        ALOGD("Car watchdog monitor is unregistered");
    }
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
    Mutex::Autolock lock(mMutex);
    if (mMonitor == nullptr || monitor == nullptr ||
        BnCarWatchdog::asBinder(monitor) != BnCarWatchdog::asBinder(mMonitor)) {
        return Status::
                fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT,
                                  "The monitor is not registered or an invalid monitor is given");
    }
    ALOGI("Process(pid: %d) has been dumped and killed", pid);
    return Status::ok();
}

Status WatchdogProcessService::notifyPowerCycleChange(PowerCycle cycle) {
    // TODO(b/148884065): implement this method.
    (void)cycle;
    return Status::ok();
}

Status WatchdogProcessService::notifyUserStateChange(userid_t userId, UserState state) {
    // TODO(b/146086037): implement this method.
    (void)userId;
    (void)state;
    return Status::ok();
}

Result<void> WatchdogProcessService::dump(int fd, const Vector<String16>& /*args*/) {
    Mutex::Autolock lock(mMutex);
    std::string buffer;
    WriteStringToFd("CAR WATCHDOG PROCESS SERVICE\n", fd);
    WriteStringToFd("  Registered clients\n", fd);
    int count = 1;
    for (const auto& timeout : kTimeouts) {
        std::vector<ClientInfo>& clients = mClients[timeout];
        for (auto it = clients.begin(); it != clients.end(); it++, count++) {
            WriteStringToFd(StringPrintf("    Client #%d: %s\n", count, it->toString().c_str()),
                            fd);
        }
    }
    WriteStringToFd(StringPrintf("\n  Monitor registered: %s\n",
                                 mMonitor == nullptr ? "false" : "true"),
                    fd);
    return {};
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
            sp<IBinder> binder = BnCarWatchdog::asBinder((*it).client);
            binder->unlinkToDeath(this);
            it = clients.erase(it);
        }
    }
}

void WatchdogProcessService::binderDied(const wp<IBinder>& who) {
    Mutex::Autolock lock(mMutex);
    IBinder* binder = who.unsafe_get();
    // Check if dead binder is monitor.
    sp<IBinder> monitor = BnCarWatchdog::asBinder(mMonitor);
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
    sp<IBinder> binder = BnCarWatchdog::asBinder(client);
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
    sp<IBinder> binder = BnCarWatchdog::asBinder(client);
    status_t status = binder->linkToDeath(this);
    if (status != OK) {
        std::string errorStr = StringPrintf("The %s is dead", clientName);
        const char* errorCause = errorStr.c_str();
        ALOGW("Cannot register the %s: %s", clientName, errorCause);
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, errorCause);
    }
    std::vector<ClientInfo>& clients = mClients[timeout];
    pid_t callingPid = IPCThreadState::self()->getCallingPid();
    clients.push_back(ClientInfo(client, callingPid, clientType));

    // If the client array becomes non-empty, start health checking.
    if (clients.size() == 1) {
        startHealthChecking(timeout);
    }
    if (DEBUG) {
        ALOGD("Car watchdog %s(pid: %d, timeout: %d) is registered", clientName, callingPid,
              timeout);
    }
    return Status::ok();
}

Status WatchdogProcessService::unregisterClientLocked(const std::vector<TimeoutLength>& timeouts,
                                                      sp<IBinder> binder, ClientType clientType) {
    const char* clientName = clientType == ClientType::Regular ? "client" : "mediator";
    bool result = findClientAndProcessLocked(timeouts, binder,
                                             [&](std::vector<ClientInfo>& clients,
                                                 std::vector<ClientInfo>::const_iterator it) {
                                                 binder->unlinkToDeath(this);
                                                 clients.erase(it);
                                             });
    if (!result) {
        std::string errorStr = StringPrintf("The %s has not been registered", clientName);
        const char* errorCause = errorStr.c_str();
        ALOGW("Cannot unregister the %s: %s", clientName, errorCause);
        Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT, errorCause);
    }
    if (DEBUG) {
        ALOGD("Car watchdog %s is unregistered", clientName);
    }
    return Status::ok();
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
            if (BnCarWatchdog::asBinder((*it).client) != binder) {
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
            sp<IBinder> binder = BnCarWatchdog::asBinder((*it).client);
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
        if (DEBUG) {
            ALOGD("Dumping and killing process(pid: %d) is requested.", pid);
        }
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

std::string WatchdogProcessService::ClientInfo::toString() {
    std::string buffer;
    StringAppendF(&buffer, "pid = %d, type = %s", pid, type == Regular ? "Regular" : "Mediator");
    return buffer;
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
