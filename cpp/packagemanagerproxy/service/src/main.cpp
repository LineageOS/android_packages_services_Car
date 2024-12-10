/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "PackageManagerProxy.h"

#include <binder/ProcessState.h>
#include <log/log.h>
#include <utils/Looper.h>
#include <utils/StrongPointer.h>

namespace {

using ::android::Looper;
using ::android::ProcessState;
using ::android::sp;
using ::google::sdv::packagemanagerproxy::PackageManagerProxy;

// Setting the maximum number of Binder threads to 2 was an arbitrary choice,
// it can be modified if needed.
constexpr size_t kMaxBinderThreadCount = 2;

}  // namespace

int main(int /*argc*/, char** /*argv*/) {
    // Set up the binder
    sp<ProcessState> ps(ProcessState::self());
    ps->setThreadPoolMaxThreadCount(kMaxBinderThreadCount);
    ps->startThreadPool();
    ps->giveThreadPoolName();

    sp<Looper> looper(Looper::prepare(/*opts=*/0));

    // Start the PackageManagerProxy service
    PackageManagerProxy service;
    auto result = service.init();
    if (!result.ok()) {
        ALOGE("Failed to start service: %s", result.error().message().c_str());
        exit(result.error().code());
    }

    ALOGI("packagemanagerproxyd server started.");

    while (true) {
        looper->pollAll(/*timeoutMillis=*/-1);
    }

    return 0;
}
