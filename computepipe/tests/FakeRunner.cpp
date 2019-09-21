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

// Methods from ::android::automotive::computepipe::runner::V1_0::IFakeRunnerV1_0 follow.
Return<void> FakeRunnerV1_0::getPipeDescriptor(getPipeDescriptor_cb _hidl_cb) {
    (void)_hidl_cb;
    return Void();
}

Return<::android::automotive::computepipe::V1_0::PipeStatus> FakeRunnerV1_0::setPipeInputSource(
    uint32_t configId) {
    (void)configId;
    return ::android::automotive::computepipe::V1_0::PipeStatus::OK;
}

Return<::android::automotive::computepipe::V1_0::PipeStatus> FakeRunnerV1_0::setPipeOffloadOptions(
    uint32_t configId) {
    (void)configId;
    return ::android::automotive::computepipe::V1_0::PipeStatus::OK;
}

Return<::android::automotive::computepipe::V1_0::PipeStatus> FakeRunnerV1_0::setPipeTermination(
    uint32_t configId) {
    (void)configId;
    return ::android::automotive::computepipe::V1_0::PipeStatus::OK;
}

Return<::android::automotive::computepipe::V1_0::PipeStatus> FakeRunnerV1_0::setPipeStateCallback(
    const sp<::android::automotive::computepipe::runner::V1_0::IPipeStateCallback>& stateCb) {
    mStateCallback = stateCb;
    return ::android::automotive::computepipe::V1_0::PipeStatus::OK;
}

Return<::android::automotive::computepipe::V1_0::PipeStatus> FakeRunnerV1_0::setPipeOutputConfig(
    uint32_t configId, uint32_t maxInFlightCount,
    const sp<::android::automotive::computepipe::runner::V1_0::IPipeStream>& handler) {
    (void)configId;
    (void)maxInFlightCount;
    mOutputCallbacks.push_back(handler);
    return ::android::automotive::computepipe::V1_0::PipeStatus::OK;
}

Return<::android::automotive::computepipe::V1_0::PipeStatus> FakeRunnerV1_0::applyPipeConfigs() {
    return ::android::automotive::computepipe::V1_0::PipeStatus::OK;
}

Return<::android::automotive::computepipe::V1_0::PipeStatus> FakeRunnerV1_0::startPipe() {
    return ::android::automotive::computepipe::V1_0::PipeStatus::OK;
}

Return<::android::automotive::computepipe::V1_0::PipeStatus> FakeRunnerV1_0::stopPipe() {
    return ::android::automotive::computepipe::V1_0::PipeStatus::OK;
}

Return<::android::automotive::computepipe::V1_0::PipeStatus> FakeRunnerV1_0::doneWithPacket(
    uint32_t id) {
    (void)id;
    return ::android::automotive::computepipe::V1_0::PipeStatus::OK;
}

Return<sp<::android::automotive::computepipe::runner::V1_0::IPipeDebugger>>
FakeRunnerV1_0::getPipeDebugger() {
    return nullptr;
}

Return<::android::automotive::computepipe::V1_0::PipeStatus> FakeRunnerV1_0::releaseRunner() {
    return ::android::automotive::computepipe::V1_0::PipeStatus::OK;
}

}  // namespace tests
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
