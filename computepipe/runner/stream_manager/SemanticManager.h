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

#ifndef COMPUTEPIPE_RUNNER_STREAM_MANAGER_SEMANTIC_MANAGER_H
#define COMPUTEPIPE_RUNNER_STREAM_MANAGER_SEMANTIC_MANAGER_H

#include <mutex>

#include "OutputConfig.pb.h"
#include "RunnerComponent.h"
#include "StreamManager.h"
#include "StreamManagerInit.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner {
namespace stream_manager {

class SemanticHandle : public MemHandle {
  public:
    static constexpr uint32_t kMaxSemanticDataSize = 1024;
    proto::PacketType getType() override;
    /* Retrieve packet time stamp */
    uint64_t getTimeStamp() override;
    /* Get size */
    uint32_t getSize() override;
    /* Get data, raw pointer. Only implemented for copy semantics */
    const char* getData() override;
    /* Get native handle. data with zero copy semantics */
    native_handle_t getNativeHandle() override;
    /* set info for the memory. Make a copy */
    Status setMemInfo(const char* data, uint32_t size, uint64_t timestamp,
                      const proto::PacketType& type);
    /* Destroy local copy */
    ~SemanticHandle();

  private:
    char* mData = nullptr;
    uint32_t mSize;
    uint64_t mTimestamp;
    proto::PacketType mType;
};

class SemanticManager : public StreamManager, StreamManagerInit {
  public:
    Status setIpcDispatchCallback(
        std::function<Status(const std::shared_ptr<MemHandle>)>& cb) override;
    /* Set Max in flight packets based on client specification */
    Status setMaxInFlightPackets(uint32_t maxPackets) override;
    /* Free previously dispatched packet. Once client has confirmed usage */
    Status freePacket(const std::shared_ptr<MemHandle>& memhandle) override;
    /* Queue packet produced by graph stream */
    Status queuePacket(const char* data, const uint32_t size, uint64_t timestamp) override;
    /* Override handling of Runner Engine Events */

    Status handleExecutionPhase(const RunnerEvent& e) override;
    Status handleStopWithFlushPhase(const RunnerEvent& e) override;
    Status handleStopImmediatePhase(const RunnerEvent& e) override;

    explicit SemanticManager(std::string name, const proto::PacketType& type);
    ~SemanticManager() = default;

  private:
    std::mutex mStateLock;
    std::function<Status(const std::shared_ptr<MemHandle>&)> mDispatchCallback = nullptr;
};
}  // namespace stream_manager
}  // namespace runner
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
#endif
