/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "PowerPolicyClientBase.h"

#include <android-base/chrono_utils.h>
#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <utils/SystemClock.h>

#include <algorithm>
#include <memory>

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

namespace aafap = ::aidl::android::frameworks::automotive::powerpolicy;

using aafap::CarPowerPolicy;
using aafap::CarPowerPolicyFilter;
using aafap::ICarPowerPolicyChangeCallback;
using aafap::ICarPowerPolicyServer;
using aafap::PowerComponent;
using android::uptimeMillis;
using android::base::Error;
using android::base::Result;
using ::ndk::ScopedAStatus;
using ::ndk::SpAIBinder;

namespace {

constexpr const char* kPowerPolicyServerInterface =
        "android.frameworks.automotive.powerpolicy.ICarPowerPolicyServer/default";

constexpr std::chrono::milliseconds kPowerPolicyDaemomFindMarginalTimeMs = 500ms;

}  // namespace

bool hasComponent(const std::vector<PowerComponent>& components, PowerComponent component) {
    std::vector<PowerComponent>::const_iterator it =
            std::find(components.cbegin(), components.cend(), component);
    return it != components.cend();
}

PowerPolicyClientBase::PowerPolicyClientBase() :
      mPolicyServer(nullptr),
      mPolicyChangeCallback(nullptr),
      mDeathRecipient(AIBinder_DeathRecipient_new(PowerPolicyClientBase::onBinderDied)),
      mConnecting(false),
      mDisconnecting(false) {
    AIBinder_DeathRecipient_setOnUnlinked(mDeathRecipient.get(),
                                          &PowerPolicyClientBase::onDeathRecipientUnlinked);
}

PowerPolicyClientBase::~PowerPolicyClientBase() {
    release();
}

void PowerPolicyClientBase::onDeathRecipientUnlinked(void* cookie) {
    PowerPolicyClientBase* client = static_cast<PowerPolicyClientBase*>(cookie);
    client->handleDeathRecipientUnlinked();
}

void PowerPolicyClientBase::onBinderDied(void* cookie) {
    PowerPolicyClientBase* client = static_cast<PowerPolicyClientBase*>(cookie);
    client->handleBinderDeath();
}

void PowerPolicyClientBase::release() {
    SpAIBinder binder;
    std::shared_ptr<ICarPowerPolicyServer> policyServer;
    std::shared_ptr<ICarPowerPolicyChangeCallback> policyChangeCallback;
    {
        std::lock_guard<std::mutex> lk(mLock);

        if (std::this_thread::get_id() == mConnectionThread.get_id()) {
            LOG(ERROR) << "Cannot release from callback, deadlock would happen";
            return;
        }

        // wait for existing connection thread to finish
        mConnecting = false;
        if (mConnectionThread.joinable()) {
            mConnectionThread.join();
        }

        if (mPolicyServer == nullptr || mDisconnecting == true) {
            return;
        }

        mDisconnecting = true;
        binder = mPolicyServer->asBinder();
        policyServer = mPolicyServer;
        policyChangeCallback = mPolicyChangeCallback;
    }

    if (binder.get() != nullptr && AIBinder_isAlive(binder.get())) {
        auto status = policyServer->unregisterPowerPolicyChangeCallback(policyChangeCallback);
        if (!status.isOk()) {
            LOG(ERROR) << "Unregister power policy change callback failed";
        }

        status = ScopedAStatus::fromStatus(
                AIBinder_unlinkToDeath(binder.get(), mDeathRecipient.get(), this));
        if (!status.isOk()) {
            LOG(WARNING) << "Unlinking from death recipient failed";
        }

        // Need to wait until onUnlinked to be called.
        {
            std::unique_lock lk(mLock);
            mDeathRecipientLinkedCv.wait(lk, [this] { return !mDeathRecipientLinked; });
        }
    }

    {
        std::lock_guard<std::mutex> lk(mLock);
        mPolicyServer = nullptr;
        mPolicyChangeCallback = nullptr;
        mDisconnecting = false;
    }
}

void PowerPolicyClientBase::init() {
    std::lock_guard<std::mutex> lk(mLock);

    if (mConnecting) {
        LOG(WARNING) << "Connecting in progress";
        return;
    }

    if (mPolicyServer != nullptr) {
        LOG(WARNING) << "Already connected";
        return;
    }

    mConnecting = true;
    // ensure already finished old connection thread is cleaned up before creating new one
    if (mConnectionThread.joinable()) {
        mConnectionThread.join();
    }
    mConnectionThread = std::thread([this]() {
        Result<void> ret = connectToDaemon();
        mConnecting = false;
        if (!ret.ok()) {
            LOG(WARNING) << "Connecting to car power policy daemon failed: " << ret.error();
            onInitFailed();
        }
    });
}

void PowerPolicyClientBase::handleBinderDeath() {
    LOG(INFO) << "Power policy daemon died. Reconnecting...";
    release();
    init();
}

void PowerPolicyClientBase::handleDeathRecipientUnlinked() {
    LOG(INFO) << "Power policy death recipient unlinked";
    {
        std::lock_guard<std::mutex> lk(mLock);
        mDeathRecipientLinked = false;
    }
    mDeathRecipientLinkedCv.notify_all();
}

Result<void> PowerPolicyClientBase::connectToDaemon() {
    int64_t currentUptime = uptimeMillis();
    SpAIBinder binder(AServiceManager_waitForService(kPowerPolicyServerInterface));
    if (binder.get() == nullptr) {
        return Error() << "Failed to get car power policy daemon";
    }
    int64_t elapsedTime = uptimeMillis() - currentUptime;
    if (elapsedTime > kPowerPolicyDaemomFindMarginalTimeMs.count()) {
        LOG(WARNING) << "Finding power policy daemon took too long(" << elapsedTime << " ms)";
    }
    std::shared_ptr<ICarPowerPolicyServer> server = ICarPowerPolicyServer::fromBinder(binder);
    if (server == nullptr) {
        return Error() << "Failed to connect to car power policy daemon";
    }
    binder = this->asBinder();
    if (binder.get() == nullptr) {
        return Error() << "Failed to get car power policy client binder object";
    }
    mDeathRecipientLinked = true;
    auto status = ScopedAStatus::fromStatus(
            AIBinder_linkToDeath(server->asBinder().get(), mDeathRecipient.get(), this));
    if (!status.isOk()) {
        return Error() << "Linking to death recipient failed";
    }

    std::shared_ptr<ICarPowerPolicyChangeCallback> client =
            ICarPowerPolicyChangeCallback::fromBinder(binder);
    const auto& components = getComponentsOfInterest();
    const auto& customComponents = getCustomComponentsOfInterest();
    CarPowerPolicyFilter filter;
    filter.components = components;
    filter.customComponents = customComponents;
    status = server->registerPowerPolicyChangeCallback(client, filter);
    if (!status.isOk()) {
        status = ScopedAStatus::fromStatus(
                AIBinder_unlinkToDeath(server->asBinder().get(), mDeathRecipient.get(), this));
        if (!status.isOk()) {
            LOG(WARNING) << "Unlinking from death recipient failed";
        }
        return Error() << "Register power policy change challback failed";
    }

    mPolicyServer = server;
    mPolicyChangeCallback = client;

    LOG(INFO) << "Connected to power policy daemon";
    return {};
}

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android
