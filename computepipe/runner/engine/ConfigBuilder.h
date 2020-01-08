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

#ifndef COMPUTEPIPE_RUNNER_CONFIG_BUILDER_H
#define COMPUTEPIPE_RUNNER_CONFIG_BUILDER_H

#include "RunnerComponent.h"
#include "types/Status.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner {
namespace engine {

class ConfigBuilder {
  public:
    /**
     * Update current input option
     */
    ConfigBuilder& updateInputStreamOption(int id);
    /**
     * Update current output options
     */
    ConfigBuilder& updateOutputStreamOption(int id, int maxInFlightPackets);
    /**
     * Update current termination options
     */
    ConfigBuilder& updateTerminationOption(int id);
    /**
     * Update current offload options
     */
    ConfigBuilder& updateOffloadOption(int id);
    /**
     * Update optional Config
     */
    ConfigBuilder& updateOptionalConfig(std::string options);
    /**
     * Emit Options
     */
    ClientConfig emitClientOptions();
    /**
     * Clear current options.
     */
    ConfigBuilder& reset();

  private:
    int mInputStreamId;
    int mOffloadId;
    int mTerminationId;
    std::map<int, int> mOutputConfig;
    std::string mOptionalConfig;
};

}  // namespace engine
}  // namespace runner
}  // namespace computepipe
}  // namespace automotive
}  // namespace android

#endif
