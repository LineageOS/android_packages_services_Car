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

#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <log/log.h>
#include <signal.h>
#include <utils/Looper.h>

#include "WatchdogProcessService.h"

using android::defaultServiceManager;
using android::IPCThreadState;
using android::Looper;
using android::ProcessState;
using android::sp;
using android::String16;
using android::automotive::watchdog::WatchdogProcessService;
using android::binder::Status;

namespace {

sp<WatchdogProcessService> gWatchdogProcessService = nullptr;

void sigHandler(int sig) {
    IPCThreadState::self()->stopProcess();
    if (gWatchdogProcessService != nullptr) {
        gWatchdogProcessService->terminate();
    }
    ALOGW("car watchdog server terminated on receiving signal %d.", sig);
    exit(1);
}

void registerSigHandler() {
    struct sigaction sa;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sa.sa_handler = sigHandler;
    sigaction(SIGQUIT, &sa, nullptr);
    sigaction(SIGTERM, &sa, nullptr);
}

}  // namespace

int main(int /*argc*/, char** /*argv*/) {
    const size_t maxBinderThreadCount = 16;
    // Set up the looper
    sp<Looper> looper(Looper::prepare(/*opts=*/0));

    // Set up the binder
    sp<ProcessState> ps(ProcessState::self());
    ps->setThreadPoolMaxThreadCount(maxBinderThreadCount);
    ps->startThreadPool();
    ps->giveThreadPoolName();
    IPCThreadState::self()->disableBackgroundScheduling(true);

    // Create the service
    sp<WatchdogProcessService> service = new WatchdogProcessService(looper);
    Status status = Status::fromStatusT(defaultServiceManager()->addService(
        String16("android.automotive.watchdog.ICarWatchdog/default"), service));
    if (!status.isOk()) {
        ALOGE("Failed to add carwatchdog as service: %s", status.exceptionMessage().c_str());
        return status.exceptionCode();
    }

    gWatchdogProcessService = service;
    registerSigHandler();

    // Loop forever -- the health check runs on this thread in a handler, and the binder calls
    // remain responsive in their pool of threads.
    while (true) {
        looper->pollAll(/*timeoutMillis=*/ -1);
    }
    ALOGW("Car watchdog server escaped from its loop.");

    return 0;
}
