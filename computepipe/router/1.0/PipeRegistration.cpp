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

using namespace android::binder;
using namespace android::automotive::computepipe;
using namespace android::automotive::computepipe::runner;
// Methods from ::android::automotive::computepipe::registry::V1_0::IPipeRegistration follow.
Status PipeRegistration::registerPipeRunner(const std::string& graphName,
                                            const sp<IPipeRunner>& graphRunner) {
    if (!mRegistry) {
        return Status::fromExceptionCode(Status::Exception::EX_ILLEGAL_STATE);
    }
    std::unique_ptr<PipeHandle<PipeRunner>> handle = std::make_unique<RunnerHandle>(graphRunner);
    auto err = mRegistry->RegisterPipe(std::move(handle), graphName);
    return convertToBinderStatus(err);
}

Status PipeRegistration::convertToBinderStatus(Error err) {
    switch (err) {
        case OK:
            return Status::ok();
        default:
            return Status::fromExceptionCode(Status::Exception::EX_ILLEGAL_STATE);
    }
}

String16 PipeRegistration::getIfaceName() {
    return this->getInterfaceDescriptor();
}
}  // namespace implementation
}  // namespace V1_0
}  // namespace router
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
