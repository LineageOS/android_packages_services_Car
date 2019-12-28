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

#ifndef COMPUTEPIPE_RUNNER_COMPONENT_H
#define COMPUTEPIPE_RUNNER_COMPONENT_H
#include <memory>

#include "types/Status.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner {

class RunnerComponentInterface;
/**
 * RunnerEvent represents an event corresponding to a runner phase
 * Along with start, abort or transition complete query methods.
 */
class RunnerEvent {
  public:
    /* Is this a notification to enter the phase */
    virtual bool isPhaseEntry() const = 0;
    /* Is this a notification that all components have transitioned to the phase */
    virtual bool isTransitionComplete() const = 0;
    /* Is this a notification to abort the transition to the started phase */
    virtual bool isAborted() const = 0;
    /* Dispatch event to component */
    virtual Status dispatchToComponent(const std::shared_ptr<RunnerComponentInterface>& iface) = 0;
    /* Destructor */
    virtual ~RunnerEvent() = default;
};

/**
 * A component of the Runner Engine implements this interface to receive
 * RunnerEvents.
 * A SUCCESS return value indicates the component has handled the particular
 * event. A failure return value will result in a subsequent abort call
 * that should be ignored by the component that reported failure.
 */
class RunnerComponentInterface {
  public:
    /* handle a ConfigPhase related event notification from Runner Engine */
    virtual Status handleConfigPhase(const RunnerEvent& e);
    /* handle execution phase notification from Runner Engine */
    virtual Status handleExecutionPhase(const RunnerEvent& e);
    /* handle a stop with flushing semantics phase notification from the engine */
    virtual Status handleStopWithFlushPhase(const RunnerEvent& e);
    /* handle an immediate stop phase notification from the engine */
    virtual Status handleStopImmediatePhase(const RunnerEvent& e);
    /* handle an engine notification to return to reset state */
    virtual Status handleResetPhase(const RunnerEvent& e);
    virtual ~RunnerComponentInterface() = default;
};

}  // namespace runner
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
#endif
