// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#define LOG_TAG "RunnerIpcInterface"

#include "RunnerInterface.h"

#include <thread>

#include <aidl/android/automotive/computepipe/registry/IPipeRegistration.h>
#include <android/binder_manager.h>
#include <android-base/logging.h>

namespace android {
namespace automotive {
namespace computepipe {
namespace runner_utils {

using ::aidl::android::automotive::computepipe::registry::IPipeRegistration;
using ::ndk::ScopedAStatus;

namespace {
const char kRegistryInterfaceName[] = "router";

void deathNotifier(void* cookie) {
    RunnerInterface* runnerIface = static_cast<RunnerInterface*>(cookie);
    runnerIface->routerDied();
}

}  // namespace

Status RunnerInterface::init() {
    if (mPipeRunner) {
        return Status::INVALID_ARGUMENT;
    }

    mPipeRunner = std::make_shared<InterfaceImpl>(mGraphOptions, mRunnerInterfaceCallbacks);
    std::thread t(&RunnerInterface::tryRegisterPipeRunner, this);
    t.detach();
    return Status::SUCCESS;
}

bool RunnerInterface::tryRegisterPipeRunner() {
    if (!mPipeRunner) {
        LOG(ERROR) << "Init must be called before attempting to connect to router.";
        return false;
    }

    const std::string instanceName =
        std::string() + IPipeRegistration::descriptor + "/" + kRegistryInterfaceName;

    for (int i =0; i < mMaxRouterConnectionAttempts; i++) {
        if (i != 0) {
            sleep(mRouterConnectionAttemptIntervalSeconds);
        }

        ndk::SpAIBinder binder(AServiceManager_getService(instanceName.c_str()));
        if (binder.get() == nullptr) {
            LOG(ERROR) << "Failed to connect to router service";
            continue;
        }

        // Connected to router registry, register the runner and dealth callback.
        std::shared_ptr<IPipeRegistration> registryService = IPipeRegistration::fromBinder(binder);
        ndk::ScopedAStatus status =
            registryService->registerPipeRunner(mGraphOptions.graph_name().c_str(), mPipeRunner);

        if (!status.isOk()) {
            LOG(ERROR) << "Failed to register runner instance at router registy.";
            continue;
        }

        AIBinder_DeathRecipient* recipient = AIBinder_DeathRecipient_new(&deathNotifier);
        AIBinder_linkToDeath(registryService->asBinder().get(), recipient, this);
        LOG(ERROR) << "Runner was registered at router registry.";
        return true;
    }

    LOG(ERROR) << "Max connection attempts reached, router connection attempts failed.";
    return false;
}

void RunnerInterface::routerDied() {
    std::thread t(&RunnerInterface::tryRegisterPipeRunner, this);
    t.detach();
}

// Thread-safe function to deliver new packets to client.
Status RunnerInterface::newPacketNotification(int32_t streamId,
                                              const std::shared_ptr<MemHandle>& packetHandle) {
    if (!mPipeRunner) {
        return Status::INVALID_ARGUMENT;
    }
    return mPipeRunner->newPacketNotification(streamId, packetHandle);
}

Status RunnerInterface::stateUpdateNotification(const GraphState newState) {
    if (!mPipeRunner) {
        return Status::INVALID_ARGUMENT;
    }
    return mPipeRunner->stateUpdateNotification(newState);
}

}  // namespace runner_utils
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
