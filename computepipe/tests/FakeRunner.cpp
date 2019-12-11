/*
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

#include "FakeRunner.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace tests {

using namespace android::binder;
using namespace android::automotive::computepipe::runner;

// Methods from ::android::automotive::computepipe::runner::V1_0::IFakeRunnerV1_0 follow.
Status FakeRunner::init(const sp<IPipeStateCallback>& stateCb) {
    mStateCallback = stateCb;
    return Status::ok();
}

Status FakeRunner::getPipeDescriptor(
    ::android::automotive::computepipe::runner::PipeDescriptor* _aidl_return) {
    (void)_aidl_return;
    return Status::ok();
}

Status FakeRunner::setPipeInputSource(int32_t configId) {
    (void)configId;
    return Status::ok();
}

Status FakeRunner::setPipeOffloadOptions(int32_t configId) {
    (void)configId;
    return Status::ok();
}

Status FakeRunner::setPipeTermination(int32_t configId) {
    (void)configId;
    return Status::ok();
}

Status FakeRunner::setPipeOutputConfig(int32_t configId, int32_t maxInFlightCount,
                                       const ::android::sp<IPipeStream>& handler) {
    (void)configId;
    (void)maxInFlightCount;
    mOutputCallbacks.push_back(handler);
    return Status::ok();
}

Status FakeRunner::applyPipeConfigs() {
    return Status::ok();
}

Status FakeRunner::startPipe() {
    return Status::ok();
}

Status FakeRunner::stopPipe() {
    return Status::ok();
}

Status FakeRunner::doneWithPacket(int32_t id) {
    (void)id;
    return Status::ok();
}

Status FakeRunner::getPipeDebugger(sp<IPipeDebugger>* _aidl_return) {
    (void)_aidl_return;
    return Status::ok();
}

Status FakeRunner::releaseRunner() {
    return Status::ok();
}

}  // namespace tests
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
