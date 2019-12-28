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

#include "RunnerComponent.h"

#include "types/Status.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner {

/* handle a ConfigPhase related event notification from Runner Engine */
Status RunnerComponentInterface::handleConfigPhase(const RunnerEvent& /* e*/) {
    return Status::SUCCESS;
}
/* handle execution phase notification from Runner Engine */
Status RunnerComponentInterface::handleExecutionPhase(const RunnerEvent& /* e*/) {
    return SUCCESS;
}
/* handle a stop with flushing semantics phase notification from the engine */
Status RunnerComponentInterface::handleStopWithFlushPhase(const RunnerEvent& /* e*/) {
    return SUCCESS;
}
/* handle an immediate stop phase notification from the engine */
Status RunnerComponentInterface::handleStopImmediatePhase(const RunnerEvent& /* e*/) {
    return SUCCESS;
}
/* handle an engine notification to return to reset state */
Status RunnerComponentInterface::handleResetPhase(const RunnerEvent& /* e*/) {
    return SUCCESS;
}

}  // namespace runner
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
