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

#define LOG_TAG "carpowerpolicyd"
#define DEBUG false  // STOPSHIP if true.

#include "CarPowerPolicyServer.h"

#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <android/frameworks/automotive/powerpolicy/BnCarPowerPolicyChangeCallback.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

using android::defaultServiceManager;
using android::base::Error;
using android::base::Result;
using android::base::StringAppendF;
using android::base::StringPrintf;
using android::base::WriteStringToFd;
using android::binder::Status;

namespace {

std::string toString(const CallbackInfo& callback) {
    return StringPrintf("callback(pid %d, filter: %s)", callback.pid,
                        toString(callback.filter.components).c_str());
}

std::vector<CallbackInfo>::const_iterator lookupPowerPolicyChangeCallback(
        const std::vector<CallbackInfo>& callbacks, const sp<IBinder>& binder) {
    for (auto it = callbacks.begin(); it != callbacks.end(); it++) {
        if (BnCarPowerPolicyChangeCallback::asBinder(it->callback) == binder) {
            return it;
        }
    }
    return callbacks.end();
}

}  // namespace

sp<CarPowerPolicyServer> CarPowerPolicyServer::sCarPowerPolicyServer = nullptr;

std::string toString(const std::vector<PowerComponent>& components) {
    size_t size = components.size();
    if (size == 0) {
        return "none";
    }
    std::string filterStr = toString(components[0]);
    for (int i = 1; i < size; i++) {
        StringAppendF(&filterStr, ", %s", toString(components[i]).c_str());
    }
    return filterStr;
}

Result<sp<CarPowerPolicyServer>> CarPowerPolicyServer::startService(
        const android::sp<Looper>& looper) {
    if (sCarPowerPolicyServer != nullptr) {
        return Error(INVALID_OPERATION) << "Cannot start service more than once";
    }
    sp<CarPowerPolicyServer> server = new CarPowerPolicyServer();
    const auto& ret = server->init(looper);
    if (!ret.ok()) {
        return Error(ret.error().code())
                << "Failed to start car power policy server: " << ret.error();
    }
    sCarPowerPolicyServer = server;

    return sCarPowerPolicyServer;
}

void CarPowerPolicyServer::terminateService() {
    if (sCarPowerPolicyServer != nullptr) {
        sCarPowerPolicyServer->terminate();
        sCarPowerPolicyServer = nullptr;
    }
}

Status CarPowerPolicyServer::getCurrentPowerPolicy(CarPowerPolicy* aidlReturn) {
    Mutex::Autolock lock(mMutex);
    if (mCurrentPowerPolicy == nullptr) {
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE,
                                         "The current power policy is not set");
    }
    *aidlReturn = *mCurrentPowerPolicy;
    return Status::ok();
}

Status CarPowerPolicyServer::getPowerComponentState(PowerComponent componentId, bool* aidlReturn) {
    const auto& ret = mComponentHandler.getPowerComponentState(componentId);
    if (!ret.ok()) {
        std::string errorMsg = ret.error().message();
        ALOGW("getPowerComponentState(%s) failed: %s", toString(componentId).c_str(),
              errorMsg.c_str());
        return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT, errorMsg.c_str());
    }
    *aidlReturn = *ret;
    return Status::ok();
}

Status CarPowerPolicyServer::registerPowerPolicyChangeCallback(
        const sp<ICarPowerPolicyChangeCallback>& callback, const CarPowerPolicyFilter& filter) {
    Mutex::Autolock lock(mMutex);
    pid_t callingPid = IPCThreadState::self()->getCallingPid();
    uid_t callingUid = IPCThreadState::self()->getCallingUid();
    if (isRegisteredLocked(callback)) {
        std::string errorStr = StringPrintf("The callback(pid: %d, uid: %d) is already registered.",
                                            callingPid, callingUid);
        const char* errorCause = errorStr.c_str();
        ALOGW("Cannot register a callback: %s", errorCause);
        return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT, errorCause);
    }
    sp<IBinder> binder = BnCarPowerPolicyChangeCallback::asBinder(callback);
    status_t status = binder->linkToDeath(this);
    if (status != OK) {
        std::string errorStr = StringPrintf("The given callback(pid: %d, uid: %d) is dead",
                                            callingPid, callingUid);
        const char* errorCause = errorStr.c_str();
        ALOGW("Cannot register a callback: %s", errorCause);
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, errorCause);
    }
    mPolicyChangeCallbacks.emplace_back(callback, filter, callingPid);

    if (DEBUG) {
        ALOGD("Power policy change callback(pid: %d, filter: %s) is registered", callingPid,
              toString(filter.components).c_str());
    }
    return Status::ok();
}

Status CarPowerPolicyServer::unregisterPowerPolicyChangeCallback(
        const sp<ICarPowerPolicyChangeCallback>& callback) {
    Mutex::Autolock lock(mMutex);
    pid_t callingPid = IPCThreadState::self()->getCallingPid();
    uid_t callingUid = IPCThreadState::self()->getCallingUid();
    sp<IBinder> binder = BnCarPowerPolicyChangeCallback::asBinder(callback);
    auto it = lookupPowerPolicyChangeCallback(mPolicyChangeCallbacks, binder);
    if (it == mPolicyChangeCallbacks.end()) {
        std::string errorStr =
                StringPrintf("The callback(pid: %d, uid: %d) has not been registered", callingPid,
                             callingUid);
        const char* errorCause = errorStr.c_str();
        ALOGW("Cannot unregister a callback: %s", errorCause);
        return Status::fromExceptionCode(Status::EX_ILLEGAL_ARGUMENT, errorCause);
    }
    binder->unlinkToDeath(this);
    mPolicyChangeCallbacks.erase(it);
    if (DEBUG) {
        ALOGD("Power policy change callback(pid: %d, uid: %d) is unregistered", callingPid,
              callingUid);
    }
    return Status::ok();
}

status_t CarPowerPolicyServer::dump(int fd, const Vector<String16>& args) {
    Mutex::Autolock lock(mMutex);
    const char* indent = "  ";
    const char* doubleIndent = "    ";
    WriteStringToFd("CAR POWER POLICY DAEMON\n", fd);
    WriteStringToFd(StringPrintf("%sCurrent power policy: %s\n", indent,
                                 mCurrentPowerPolicy ? mCurrentPowerPolicy->policyId.c_str()
                                                     : "none"),
                    fd);
    // TODO(b/162599168): dump registered power policy and default power policy per transition.
    WriteStringToFd(StringPrintf("%sPolicy change callbacks:%s\n", indent,
                                 mPolicyChangeCallbacks.size() ? "" : " none"),
                    fd);
    for (auto& callback : mPolicyChangeCallbacks) {
        WriteStringToFd(StringPrintf("%s- %s\n", doubleIndent, toString(callback).c_str()), fd);
    }
    const auto& ret = mComponentHandler.dump(fd, args);
    if (!ret.ok()) {
        ALOGW("Failed to dump power component handler: %s", ret.error().message().c_str());
        return ret.error().code();
    }
    return OK;
}

Result<void> CarPowerPolicyServer::init(const sp<Looper>& looper) {
    mHandlerLooper = looper;

    readVendorPowerPolicy();
    mComponentHandler.init();
    checkSilentModeFromKernel();
    const auto& ret = subscribeToVhal();
    if (!ret.ok()) {
        return Error() << "Failed to subscribe to VHAL power policy properties.";
    }

    status_t status =
            defaultServiceManager()->addService(String16(
                                                        "android.frameworks.automotive.powerpolicy."
                                                        "ICarPowerPolicyServer/default"),
                                                this);
    if (status != OK) {
        return Error(status) << "Failed to add carpowerpolicyd to ServiceManager";
    }

    return {};
}

void CarPowerPolicyServer::terminate() {
    {
        Mutex::Autolock lock(mMutex);
        for (auto it = mPolicyChangeCallbacks.begin(); it != mPolicyChangeCallbacks.end(); it++) {
            sp<IBinder> binder = BnCarPowerPolicyChangeCallback::asBinder(it->callback);
            binder->unlinkToDeath(this);
        }
        mPolicyChangeCallbacks.clear();
    }
    mComponentHandler.finalize();
}

void CarPowerPolicyServer::binderDied(const wp<IBinder>& who) {
    Mutex::Autolock lock(mMutex);
    IBinder* binder = who.unsafe_get();
    auto it = lookupPowerPolicyChangeCallback(mPolicyChangeCallbacks, binder);
    if (it != mPolicyChangeCallbacks.end()) {
        ALOGW("Power policy callback(pid: %d) died", it->pid);
        binder->unlinkToDeath(this);
        mPolicyChangeCallbacks.erase(it);
    }
}

bool CarPowerPolicyServer::isRegisteredLocked(const sp<ICarPowerPolicyChangeCallback>& callback) {
    sp<IBinder> binder = BnCarPowerPolicyChangeCallback::asBinder(callback);
    return lookupPowerPolicyChangeCallback(mPolicyChangeCallbacks, binder) !=
            mPolicyChangeCallbacks.end();
}

void CarPowerPolicyServer::readVendorPowerPolicy() {
    // TODO(b/162599168): read /vendor/etc/power_policy.xml
}

void CarPowerPolicyServer::checkSilentModeFromKernel() {
    // TODO(b/162599168): check if silent mode is set by kernel.
}

Result<void> CarPowerPolicyServer::subscribeToVhal() {
    // TODO(b/162599168): subscribe APPLY_POWER_POLICY and OVERRIDE_DEFAULT_POWER_POLICY.
    return {};
}

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android
