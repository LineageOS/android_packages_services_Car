/*
 * Copyright (c) 2023, The Android Open Source Project
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

#include "NativeTelemetryService.h"

#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>

#define LOG "com.android.native.telemetry"

using ::android::automotive::telemetry::LooperWrapper;
using ::android::automotive::telemetry::NativeTelemetryServer;
using ::android::automotive::telemetry::NativeTelemetryServiceImpl;

using namespace android;

const size_t kMaxBinderThreadCount = 15;

constexpr const char kCarTelemetryServiceName[] =
        "android.native.telemetry.INativeTelemetryService/default";

int main(int, char**) {
    ALOGD(LOG "Registering service");

    sp<ProcessState> ps(ProcessState::self());
    ps->setThreadPoolMaxThreadCount(kMaxBinderThreadCount);
    ps->startThreadPool();

    android::sp<android::IServiceManager> serviceManager = defaultServiceManager();

    LooperWrapper looper(android::Looper::prepare(/* opts= */ 0));
    NativeTelemetryServer server(&looper);

    ALOGI(LOG " started");

    android::sp<NativeTelemetryServiceImpl> service =
            android::sp<NativeTelemetryServiceImpl>::make(&server);
    status_t status =
            serviceManager->addService(android::String16(kCarTelemetryServiceName), service.get());

    if (status != OK) {
        ALOGI(LOG " error in registering service, Err code: %" PRId32, status);
    }

    while (true) {
        looper.pollAll(/* timeoutMillis= */ -1);
    }
    return 1;  // never reaches
}
