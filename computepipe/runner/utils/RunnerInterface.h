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

#include <memory>
#include <string>

#include "InterfaceImpl.h"
#include "RunnerInterfaceCallbacks.h"
#include "types/GraphState.h"
#include "types/Status.h"

#include "Options.pb.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner_utils {

// RunnerInterface registers an IPipeRunner interface with computepipe router.
// RunnerInterface handles binder IPC calls and invokes appropriate callbacks.
class RunnerInterface {
  public:
    explicit RunnerInterface(const proto::Options graphOptions,
                             const RunnerInterfaceCallbacks& runnerInterfaceCallbacks)
        : mGraphOptions(graphOptions), mRunnerInterfaceCallbacks(runnerInterfaceCallbacks) {
    }

    ~RunnerInterface() {
    }

    // init() should be invoked when the process is ready to receive commands
    // from Clients.
    Status init();

    // Thread-safe function to deliver new packets to client.
    Status newPacketNotification(int32_t streamId, const std::shared_ptr<MemHandle>& packetHandle);

    // Thread-safe function to notify clients of new state.
    Status stateUpdateNotification(const GraphState newState);

    void routerDied();

  private:
    // Attempt to register pipe runner with router. Returns true on success.
    // This is a blocking API, calling thread will be blocked until router connection is
    // established or max attempts are made without success.
    bool tryRegisterPipeRunner();

    const int mMaxRouterConnectionAttempts = 10;
    const int mRouterConnectionAttemptIntervalSeconds = 2;

    const proto::Options mGraphOptions;
    const RunnerInterfaceCallbacks mRunnerInterfaceCallbacks;
    std::shared_ptr<InterfaceImpl> mPipeRunner = nullptr;
};

}  // namespace runner_utils
}  // namespace computepipe
}  // namespace automotive
}  // namespace android

#endif  // COMPUTEPIPE_RUNNER_UTILS_RUNNERINTERFACE_H_
