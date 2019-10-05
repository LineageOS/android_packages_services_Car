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

#ifndef ANDROID_AUTOMOTIVE_COMPUTEPIPE_TESTS
#define ANDROID_AUTOMOTIVE_COMPUTEPIPE_TESTS

#include <android/automotive/computepipe/runner/1.0/IPipeRunner.h>
#include <hidl/MQDescriptor.h>
#include <hidl/Status.h>

#include <memory>

namespace android {
namespace automotive {
namespace computepipe {
namespace tests {

using ::android::sp;
using ::android::hardware::hidl_array;
using ::android::hardware::hidl_memory;
using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;
using ::android::hardware::Return;
using ::android::hardware::Void;

// TODO: Wrap under version flag
using namespace android::automotive::computepipe::runner::V1_0;
using android::automotive::computepipe::V1_0::PipeStatus;

// This is a fake runner class whose various methods can be mocked in order
// to test the Runner logic.

class FakeRunnerV1_0 : public IPipeRunner {
  public:
    // Methods from ::android::automotive::computepipe::runner::V1_0::IPipeRunner follow.
    Return<void> getPipeDescriptor(getPipeDescriptor_cb _hidl_cb) override;
    Return<::android::automotive::computepipe::V1_0::PipeStatus> setPipeInputSource(
        uint32_t configId) override;
    Return<::android::automotive::computepipe::V1_0::PipeStatus> setPipeOffloadOptions(
        uint32_t configId) override;
    Return<::android::automotive::computepipe::V1_0::PipeStatus> setPipeTermination(
        uint32_t configId) override;
    Return<::android::automotive::computepipe::V1_0::PipeStatus> setPipeStateCallback(
        const sp<::android::automotive::computepipe::runner::V1_0::IPipeStateCallback>& stateCb)
        override;
    Return<::android::automotive::computepipe::V1_0::PipeStatus> setPipeOutputConfig(
        uint32_t configId, uint32_t maxInFlightCount,
        const sp<::android::automotive::computepipe::runner::V1_0::IPipeStream>& handler) override;
    Return<::android::automotive::computepipe::V1_0::PipeStatus> applyPipeConfigs() override;
    Return<::android::automotive::computepipe::V1_0::PipeStatus> startPipe() override;
    Return<::android::automotive::computepipe::V1_0::PipeStatus> stopPipe() override;
    Return<::android::automotive::computepipe::V1_0::PipeStatus> doneWithPacket(uint32_t id) override;
    Return<sp<::android::automotive::computepipe::runner::V1_0::IPipeDebugger>> getPipeDebugger()
        override;
    Return<::android::automotive::computepipe::V1_0::PipeStatus> releaseRunner() override;
    ~FakeRunnerV1_0() {
        mOutputCallbacks.clear();
    }

  private:
    std::vector<wp<::android::automotive::computepipe::runner::V1_0::IPipeStream>> mOutputCallbacks;
    wp<::android::automotive::computepipe::runner::V1_0::IPipeStateCallback> mStateCallback;
};

// TODO: Wrap under version flag
typedef FakeRunnerV1_0 FakeRunner;

}  // namespace tests
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
#endif
