/*
 * Copyright (C) 2019 The Android Open Source Project
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
#include "BaseUseCase.h"

namespace android {
namespace automotive {
namespace evs {
namespace support {

// TODO(b/130246343): use evs manager 1.1 instead.
const string BaseUseCase::kDefaultServiceName = "EvsEnumeratorV1_0";
sp<IEvsEnumerator> BaseUseCase::sEvs;

sp<IEvsEnumerator> BaseUseCase::getEvsEnumerator(string serviceName) {
    if (sEvs.get() == nullptr) {
        sEvs = IEvsEnumerator::getService(serviceName);
    }
    return sEvs;
}

}  // namespace support
}  // namespace evs
}  // namespace automotive
}  // namespace android
