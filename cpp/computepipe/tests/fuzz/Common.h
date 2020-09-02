/*
 * Copyright 2020 The Android Open Source Project
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

#ifndef CPP_COMPUTEPIPE_TESTS_FUZZ_COMMON_H_
#define CPP_COMPUTEPIPE_TESTS_FUZZ_COMMON_H_

namespace android {
namespace automotive {
namespace computepipe {
namespace runner {
namespace test {

const int kMaxFuzzerConsumedBytes = 12;

#define RUNNER_COMP_BASE_ENUM                                                   \
    HANDLE_CONFIG_PHASE,                  /* verify handleConfigPhase */        \
            HANDLE_EXECUTION_PHASE,       /* verify handleExecutionPhase */     \
            HANDLE_STOP_IMMEDIATE_PHASE,  /* verify handleStopImmediatePhase */ \
            HANDLE_STOP_WITH_FLUSH_PHASE, /* verify handleStopWithFlushPhase */ \
            HANDLE_RESET_PHASE,           /* verify handleResetPhase */         \
            API_SUM

}  // namespace test
}  // namespace runner
}  // namespace computepipe
}  // namespace automotive
}  // namespace android

#endif  // CPP_COMPUTEPIPE_TESTS_FUZZ_COMMON_H_
