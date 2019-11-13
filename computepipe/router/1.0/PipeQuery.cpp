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
#include "PipeQuery.h"

#include "PipeClient.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace router {
namespace V1_0 {
namespace implementation {

using namespace android::automotive::computepipe::router;
using namespace android::automotive::computepipe::registry;
using namespace android::automotive::computepipe::runner;
using namespace android::binder;

Status PipeQuery::getGraphList(std::vector<std::string>* outNames) {
    if (!mRegistry || !outNames) {
        return Status::fromExceptionCode(Status::Exception::EX_ILLEGAL_STATE);
    }
    auto names = mRegistry->getPipeList();
    std::copy(names.begin(), names.end(), std::back_inserter(*outNames));
    return Status::ok();
}

Status PipeQuery::getPipeRunner(const std::string& graphName, const sp<IClientInfo>& info,
                                sp<IPipeRunner>* outRunner) {
    *outRunner = nullptr;
    if (!mRegistry) {
        return Status::fromExceptionCode(Status::Exception::EX_ILLEGAL_STATE);
    }
    std::unique_ptr<ClientHandle> clientHandle = std::make_unique<PipeClient>(info);
    auto pipeHandle = mRegistry->getClientPipeHandle(graphName, std::move(clientHandle));
    if (!pipeHandle) {
        return Status::fromExceptionCode(Status::Exception::EX_ILLEGAL_STATE);
    }
    auto pipeRunner = pipeHandle->getInterface();
    *outRunner = pipeRunner->runner;
    return Status::ok();
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace router
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
