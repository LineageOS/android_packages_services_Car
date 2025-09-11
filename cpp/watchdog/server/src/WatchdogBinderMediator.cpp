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

#include "WatchdogBinderMediator.h"

#include <android-base/strings.h>
#include <android/binder_interface_utils.h>
#include <log/log.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::aidl::android::automotive::watchdog::ICarWatchdogClient;
using ::aidl::android::automotive::watchdog::TimeoutLength;
using ::android::sp;
using ::android::base::Error;
using ::android::base::Result;
using ::android::base::StringAppendF;
using ::ndk::ICInterface;
using ::ndk::ScopedAStatus;
using ::ndk::SharedRefBase;

using AddServiceFunction =
        std::function<android::base::Result<void>(const char*, ICInterface*, bool, int)>;

namespace {

constexpr const char* kNullCarWatchdogClientError =
        "Must provide a non-null car watchdog client instance";

ScopedAStatus toScopedAStatus(const int32_t exceptionCode, const std::string& message) {
    ALOGW("%s", message.c_str());
    return ScopedAStatus::fromExceptionCodeWithMessage(exceptionCode, message.c_str());
}

}  // namespace

WatchdogBinderMediator::WatchdogBinderMediator(
        const android::sp<WatchdogProcessServiceInterface>& watchdogProcessService,
        const android::sp<WatchdogPerfServiceInterface>& watchdogPerfService,
        const android::sp<WatchdogServiceHelperInterface>& watchdogServiceHelper,
        const android::sp<IoOveruseMonitorWrapperInterface>& ioOveruseMonitorWrapper,
        const AddServiceFunction& addServiceHandler) :
      WatchdogBinderMediatorBase(watchdogPerfService, watchdogServiceHelper,
                                 ioOveruseMonitorWrapper, addServiceHandler),
      mWatchdogProcessService(watchdogProcessService) {
    if (watchdogServiceHelper != nullptr) {
        mWatchdogInternalHandler =
                SharedRefBase::make<WatchdogInternalHandler>(watchdogServiceHelper,
                                                             mWatchdogProcessService,
                                                             watchdogPerfService,
                                                             ioOveruseMonitorWrapper);
    }
}

Result<void> WatchdogBinderMediator::init() {
    if (mWatchdogProcessService == nullptr) {
        std::string serviceList;
        StringAppendF(&serviceList, "%s%s", (!serviceList.empty() ? ", " : ""),
                      "Watchdog process service");
        return Error(INVALID_OPERATION)
                << serviceList << " must be initialized with non-null instance";
    }
    return WatchdogBinderMediatorBase::init();
}

ScopedAStatus WatchdogBinderMediator::registerClient(
        const std::shared_ptr<ICarWatchdogClient>& client, TimeoutLength timeout) {
    if (client == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogClientError);
    }
    return mWatchdogProcessService->registerClient(client, timeout);
}

ScopedAStatus WatchdogBinderMediator::unregisterClient(
        const std::shared_ptr<ICarWatchdogClient>& client) {
    if (client == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogClientError);
    }
    return mWatchdogProcessService->unregisterClient(client);
}

ScopedAStatus WatchdogBinderMediator::tellClientAlive(
        const std::shared_ptr<ICarWatchdogClient>& client, int32_t sessionId) {
    if (client == nullptr) {
        return toScopedAStatus(EX_ILLEGAL_ARGUMENT, kNullCarWatchdogClientError);
    }
    return mWatchdogProcessService->tellClientAlive(client, sessionId);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
