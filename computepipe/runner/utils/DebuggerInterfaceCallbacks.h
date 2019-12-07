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

#ifndef COMPUTEPIPE_RUNNER_UTILS_DEBUGGERINTERFACECALLBACKS_H_
#define COMPUTEPIPE_RUNNER_UTILS_DEBUGGERINTERFACECALLBACKS_H_

#include <functional>
#include <string>

namespace android {
namespace automotive {
namespace computepipe {
namespace runner_utils {

struct DebuggerInterfaceCallbacks {
    explicit DebuggerInterfaceCallbacks(
        std::function<Status()> startGraphProfile,
        std::function<Status()> stopGraphProfile,
        std::function<std::string()> GetDebugData) :
            mStartGraphProfile(startGraphProfile),
            mStopGraphProfile(stopGraphProfile),
            mGetDebugData(GetDebugData) {}
    const std::function<Status()> mStartGraphProfile;
    const std::function<Status()> mStopGraphProfile;
    const std::function<std::string()> mGetDebugData;
};

}  // namespace runner_utils
}  // namespace computepipe
}  // namespace automotive
}  // namespace android

#endif  // COMPUTEPIPE_RUNNER_UTILS_DEBUGGERINTERFACECALLBACKS_H_
