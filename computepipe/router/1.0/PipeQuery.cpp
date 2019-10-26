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
using namespace android::automotive::computepipe::registry::V1_0;
using namespace android::automotive::computepipe::runner::V1_0;

using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;
using ::android::hardware::Return;

Return<void> PipeQuery::getGraphList(getGraphList_cb _hidl_cb) {
    hidl_vec<hidl_string> graphList;
    if (!mRegistry) {
        _hidl_cb(graphList);
        return {};
    }
    auto names = mRegistry->getPipeList();
    graphList.resize(names.size());
    std::transform(names.begin(), names.end(), graphList.begin(),
                   [](const std::string& in) -> hidl_string { return hidl_string(in); });
    _hidl_cb(graphList);
    return {};
}

Return<sp<IPipeRunner>> PipeQuery::getPipeRunner(const hidl_string& graphName,
                                                 const sp<IClientInfo>& info) {
    if (!mRegistry) {
        return nullptr;
    }
    std::unique_ptr<ClientHandle> clientHandle = std::make_unique<PipeClient>(info);
    auto pipeHandle = mRegistry->getClientPipeHandle(graphName, std::move(clientHandle));
    if (!pipeHandle) {
        return nullptr;
    }
    sp<IPipeRunner> runner = pipeHandle->getInterface().promote();
    return runner;
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace router
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
