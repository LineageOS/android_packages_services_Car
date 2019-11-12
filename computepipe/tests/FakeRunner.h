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

#include <android/automotive/computepipe/runner/BnPipeRunner.h>

#include <memory>

namespace android {
namespace automotive {
namespace computepipe {
namespace tests {

// TODO: Wrap under version flag

// This is a fake runner class whose various methods can be mocked in order
// to test the Runner logic.

class FakeRunner : public runner::BnPipeRunner {
  public:
    ::android::binder::Status getPipeDescriptor(
        ::android::automotive::computepipe::runner::PipeDescriptor* _aidl_return) override;
    ::android::binder::Status setPipeInputSource(int32_t configId) override;
    ::android::binder::Status setPipeOffloadOptions(int32_t configId) override;
    ::android::binder::Status setPipeTermination(int32_t configId) override;
    ::android::binder::Status setPipeStateCallback(
        const ::android::sp<::android::automotive::computepipe::runner::IPipeStateCallback>& stateCb)
        override;
    ::android::binder::Status setPipeOutputConfig(
        int32_t configId, int32_t maxInFlightCount,
        const ::android::sp<::android::automotive::computepipe::runner::IPipeStream>& handler)
        override;
    ::android::binder::Status applyPipeConfigs() override;
    ::android::binder::Status startPipe() override;
    ::android::binder::Status stopPipe() override;
    ::android::binder::Status doneWithPacket(int32_t id) override;
    ::android::binder::Status getPipeDebugger(
        ::android::sp<::android::automotive::computepipe::runner::IPipeDebugger>* _aidl_return)
        override;
    ::android::binder::Status releaseRunner() override;
    status_t linkToDeath(const sp<DeathRecipient>& recipient, void* cookie = nullptr,
                         uint32_t flags = 0) override {
        (void)recipient;
        (void)cookie;
        (void)flags;
        return OK;
    };
    ~FakeRunner() {
        mOutputCallbacks.clear();
    }

  private:
    std::vector<wp<::android::automotive::computepipe::runner::IPipeStream>> mOutputCallbacks;
    wp<::android::automotive::computepipe::runner::IPipeStateCallback> mStateCallback;
};

}  // namespace tests
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
#endif
