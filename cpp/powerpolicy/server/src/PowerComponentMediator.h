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

#ifndef CPP_POWERPOLICY_SERVER_SRC_POWERCOMPONENTMEDIATOR_H_
#define CPP_POWERPOLICY_SERVER_SRC_POWERCOMPONENTMEDIATOR_H_

#include <android-base/result.h>
#include <utils/RefBase.h>

#include <functional>
#include <string>

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

using powerStateChangeCallback = std::function<void(bool, std::string)>;

class PowerComponentMediator : public RefBase {
public:
    // Changes the power state and the result is delivered back through callback function.
    virtual base::Result<void> changePowerState(bool powerOn,
                                                powerStateChangeCallback callback) = 0;
    // Returns the current power state.
    virtual bool getPowerState() = 0;
    // Returns whether the power is supported by the device.
    virtual bool isSupported() = 0;
};

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android

#endif  // CPP_POWERPOLICY_SERVER_SRC_POWERCOMPONENTMEDIATOR_H_
