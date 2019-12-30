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

#include "AidlClient.h"

#include "types/Status.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner {
namespace client_interface {
namespace aidl_client {

AidlClient::AidlClient(const proto::Options /* graphOptions */,
                       const std::shared_ptr<ClientEngineInterface>& engine)
    : mRunnerEngine(engine) {
}

/**
 * TODO: b/146980416 flesh this out once IPC implementation has moved to
 * client_interface subdirectory.
 */
Status AidlClient::dispatchPacketToClient(int32_t /* streamId */,
                                          const std::shared_ptr<MemHandle> /* packet */) {
    return SUCCESS;
}

/**
 * TODO: b/146980416 flesh this out once IPC implementation has moved to
 * client_interface subdirectory.
 */
Status AidlClient::activate() {
    return SUCCESS;
}

/**
 * TODO: b/146980416 flesh this out once IPC implementation has moved to
 * client_interface subdirectory.
 */
Status AidlClient::handleExecutionPhase(const RunnerEvent& /* e */) {
    return SUCCESS;
}

/**
 * TODO: b/146980416 flesh this out once IPC implementation has moved to
 * client_interface subdirectory.
 */
Status AidlClient::handleStopWithFlushPhase(const RunnerEvent& /* e*/) {
    return SUCCESS;
}

/**
 * TODO: b/146980416 flesh this out once IPC implementation has moved to
 * client_interface subdirectory.
 */
Status AidlClient::handleStopImmediatePhase(const RunnerEvent& /* e*/) {
    return SUCCESS;
}

}  // namespace aidl_client
}  // namespace client_interface
}  // namespace runner
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
