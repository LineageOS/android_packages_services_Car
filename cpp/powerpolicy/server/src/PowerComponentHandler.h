/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef CPP_POWERPOLICY_SERVER_SRC_POWERCOMPONENTHANDLER_H_
#define CPP_POWERPOLICY_SERVER_SRC_POWERCOMPONENTHANDLER_H_

#include "PowerComponentMediator.h"

#include <android-base/result.h>
#include <android/frameworks/automotive/powerpolicy/CarPowerPolicy.h>

#include <memory>
#include <unordered_map>

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

using CarPowerPolicyPtr = std::shared_ptr<CarPowerPolicy>;

class PowerComponentHandler final {
public:
    PowerComponentHandler() {}

    void init();
    void finalize();
    base::Result<void> applyPowerPolicy(CarPowerPolicyPtr powerPolicy);
    base::Result<bool> getPowerComponentState(PowerComponent componentId);
    base::Result<void> dump(int fd, const Vector<String16>& args);

private:
    std::unordered_map<PowerComponent, PowerComponentMediator*> mComponentMediators;
};

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android

#endif  // CPP_POWERPOLICY_SERVER_SRC_POWERCOMPONENTHANDLER_H_
