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

#ifndef COMPUTEPIPE_RUNNER_UTILS_RUNNERINTERFACE_H_
#define COMPUTEPIPE_RUNNER_UTILS_RUNNERINTERFACE_H_
#include <aidl/android/automotive/computepipe/runner/BnPipeRunner.h>

#include <string>

#include "RunnerInterfaceCallbacks.h"
#include "types/GraphState.h"
#include "types/Status.h"
#include "runner/stream_manager/MemHandle.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner_utils {

// RunnerInterface registers an IPipeRunner interface with computepipe router.
// RunnerInterface handles binder IPC calls and invokes appropriate callbacks.
class RunnerInterface :
    public aidl::android::automotive::computepipe::runner::BnPipeRunner {
 public:
    explicit RunnerInterface(
        const std::string& graphName,
        const RunnerInterfaceCallbacks& runnerInterfaceCallbacks):
          mGraphName(graphName),
          mRunnerInterfaceCallbacks(runnerInterfaceCallbacks) {}

    // Init() should be invoked when the process is ready to receive commands
    // from Clients.
    Status Init();

    // Thread-safe function to deliver new packets to client.
    Status NewPacketNotification(
        int32_t streamId, const std::shared_ptr<MemHandle>& packetHandle);

    // Thread-safe function to notify clients of new state.
    Status StateUpdateNotification(const GraphState newState);

    // Methods from android::automotive::computepipe::runner::BnPipeRunner
    ndk::ScopedAStatus getPipeDescriptor(
        aidl::android::automotive::computepipe::runner::PipeDescriptor*
            _aidl_return) override;
    ndk::ScopedAStatus setPipeInputSource(int32_t configId) override;
    ndk::ScopedAStatus setPipeOffloadOptions(int32_t configId) override;
    ndk::ScopedAStatus setPipeTermination(int32_t configId) override;
    ndk::ScopedAStatus setPipeStateCallback(
        const std::shared_ptr<
            aidl::android::automotive::computepipe::runner::IPipeStateCallback>& stateCb)
        override;
    ndk::ScopedAStatus setPipeOutputConfig(
        int32_t streamId,
        int32_t maxInFlightCount,
        const std::shared_ptr<
        aidl::android::automotive::computepipe::runner::IPipeStream>& handler)
        override;
    ndk::ScopedAStatus applyPipeConfigs() override;
    ndk::ScopedAStatus startPipe() override;
    ndk::ScopedAStatus stopPipe() override;
    ndk::ScopedAStatus doneWithPacket(int32_t id) override;

 private:
    std::string mGraphName;
    const RunnerInterfaceCallbacks& mRunnerInterfaceCallbacks;

    std::map<int,
        std::shared_ptr<aidl::android::automotive::computepipe::runner::IPipeStateCallback>>
        mPacketHandlers;
};

}  // namespace runner_utils
}  // namespace computepipe
}  // namespace automotive
}  // namespace android

#endif  // COMPUTEPIPE_RUNNER_UTILS_RUNNERINTERFACE_H_
