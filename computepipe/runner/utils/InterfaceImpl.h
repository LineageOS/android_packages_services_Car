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

#ifndef COMPUTEPIPE_RUNNER_UTILS_INTERFACEIMPL_H_
#define COMPUTEPIPE_RUNNER_UTILS_INTERFACEIMPL_H_
#include <aidl/android/automotive/computepipe/runner/BnPipeRunner.h>

#include <map>
#include <memory>
#include <string>

#include "RunnerInterfaceCallbacks.h"
#include "MemHandle.h"
#include "types/GraphState.h"
#include "types/Status.h"

#include "Options.pb.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner_utils {

// RunnerInterface registers an IPipeRunner interface with computepipe router.
// RunnerInterface handles binder IPC calls and invokes appropriate callbacks.
class InterfaceImpl : public aidl::android::automotive::computepipe::runner::BnPipeRunner {
  public:
    explicit InterfaceImpl(const proto::Options graphOptions,
                           const RunnerInterfaceCallbacks& runnerInterfaceCallbacks)
        : mGraphOptions(graphOptions), mRunnerInterfaceCallbacks(runnerInterfaceCallbacks) {
    }

    ~InterfaceImpl() {
    }

    Status newPacketNotification(int32_t streamId, const std::shared_ptr<MemHandle>& packetHandle);

    Status stateUpdateNotification(const GraphState newState);

    // Methods from android::automotive::computepipe::runner::BnPipeRunner
    ndk::ScopedAStatus init(
        const std::shared_ptr<aidl::android::automotive::computepipe::runner::IPipeStateCallback>&
            stateCb) override;
    ndk::ScopedAStatus getPipeDescriptor(
        aidl::android::automotive::computepipe::runner::PipeDescriptor* _aidl_return) override;
    ndk::ScopedAStatus setPipeInputSource(int32_t configId) override;
    ndk::ScopedAStatus setPipeOffloadOptions(int32_t configId) override;
    ndk::ScopedAStatus setPipeTermination(int32_t configId) override;
    ndk::ScopedAStatus setPipeOutputConfig(
        int32_t streamId, int32_t maxInFlightCount,
        const std::shared_ptr<aidl::android::automotive::computepipe::runner::IPipeStream>& handler)
        override;
    ndk::ScopedAStatus applyPipeConfigs() override;
    ndk::ScopedAStatus startPipe() override;
    ndk::ScopedAStatus stopPipe() override;
    ndk::ScopedAStatus doneWithPacket(int32_t id) override;

    ndk::ScopedAStatus getPipeDebugger(
        std::shared_ptr<aidl::android::automotive::computepipe::runner::IPipeDebugger>* _aidl_return)
        override;

    ndk::ScopedAStatus releaseRunner() override;

    void clientDied();

  private:
    const proto::Options mGraphOptions;
    const RunnerInterfaceCallbacks& mRunnerInterfaceCallbacks;

    bool isClientInitDone();

    // If value of mClientStateChangeCallback is null pointer, client has not
    // invoked init.
    std::shared_ptr<aidl::android::automotive::computepipe::runner::IPipeStateCallback>
        mClientStateChangeCallback = nullptr;

    std::map<int, std::shared_ptr<aidl::android::automotive::computepipe::runner::IPipeStream>>
        mPacketHandlers;
};

}  // namespace runner_utils
}  // namespace computepipe
}  // namespace automotive
}  // namespace android

#endif  // COMPUTEPIPE_RUNNER_UTILS_INTERFACEIMPL_H_
