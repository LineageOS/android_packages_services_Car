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

#define LOG_TAG "carpowerpolicyd"
#define DEBUG false  // STOPSHIP if true.

#include "PowerComponentHandler.h"

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

using android::base::Error;
using android::base::Result;

void PowerComponentHandler::init() {
    // TODO(b/162600133): implement initialization.
}

void PowerComponentHandler::finalize() {
    // TODO(b/162600133): implement finalization.
}

Result<void> PowerComponentHandler::applyPowerPolicy(CarPowerPolicyPtr /*powerPolicy*/) {
    // TODO(b/162600133): change power states of components according to the power policy.
    return Error(-1) << "Not implemented";
}

Result<bool> PowerComponentHandler::getPowerComponentState(PowerComponent /*componentId*/) {
    return Error(-1) << "Not implemented";
}

Result<void> PowerComponentHandler::dump(int /*fd*/, const Vector<String16>& /*args*/) {
    return {};
}

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android
