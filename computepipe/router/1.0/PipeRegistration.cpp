/**
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "PipeRegistration.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace router {
namespace V1_0 {
namespace implementation {

using namespace android::automotive::computepipe::V1_0;
using namespace android::automotive::computepipe::runner::V1_0;
using android::hardware::hidl_string;
using android::hardware::Return;
// Methods from ::android::automotive::computepipe::registry::V1_0::IPipeRegistration follow.
Return<PipeStatus> PipeRegistration::registerPipeRunner(const hidl_string& graphName,
                                                        const sp<IPipeRunner>& graphRunner) {
    if (!mRegistry) {
        return PipeStatus::INTERNAL_ERR;
    }
    std::unique_ptr<PipeHandle<PipeRunner>> handle = std::make_unique<RunnerHandle>(graphRunner);
    auto err = mRegistry->RegisterPipe(std::move(handle), graphName);
    return convertToPipeStatus(err);
}

PipeStatus PipeRegistration::convertToPipeStatus(Error err) {
    switch (err) {
        case OK:
            return PipeStatus::OK;
        default:
            return PipeStatus::INTERNAL_ERR;
    }
}
}  // namespace implementation
}  // namespace V1_0
}  // namespace router
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
