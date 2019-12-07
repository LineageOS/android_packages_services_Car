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

#ifndef COMPUTEPIPE_RUNNER_UTILS_RUNNERINTERFACECALLBACKS_H_
#define COMPUTEPIPE_RUNNER_UTILS_RUNNERINTERFACECALLBACKS_H_

#include <functional>
#include <string>

#include "RunnerInterface.h"
#include "ConfigurationCommand.pb.h"
#include "ControlCommand.pb.h"
#include "types/Status.h"
#include "runner/stream_manager/MemHandle.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner_utils {

struct RunnerInterfaceCallbacks {
    explicit RunnerInterfaceCallbacks(
        std::function<Status(const proto::ControlCommand&)> processControlCommand,
        std::function<Status(const proto::ConfigurationCommand&)>
            processConfigurationCommand,
        std::function<Status(const std::shared_ptr<MemHandle>&)> releasePacket) :
            mProcessControlCommand(processControlCommand),
            mProcessConfigurationCommand(processConfigurationCommand),
            mReleasePacket(releasePacket) {}

    const std::function<Status(const proto::ControlCommand&)> mProcessControlCommand;
    const std::function<Status(const proto::ConfigurationCommand&)>
        mProcessConfigurationCommand;
    const std::function<Status(const std::shared_ptr<MemHandle>&)> mReleasePacket;
};

}  // namespace runner_utils
}  // namespace computepipe
}  // namespace automotive
}  // namespace android

#endif  // COMPUTEPIPE_RUNNER_UTILS_RUNNERINTERFACECALLBACKS_H_
