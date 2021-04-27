/*
 * Copyright (c) 2021, The Android Open Source Project
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

#include "BinderDeathRecipient.h"

#include <binder/IBinder.h>

namespace android {
namespace automotive {
namespace telemetry {

using ::android::IBinder;
using ::android::wp;

BinderDeathRecipient::BinderDeathRecipient(
        std::function<void(const wp<android::IBinder>& what)> binderDiedCallback) :
      mBinderDiedCallback(std::move(binderDiedCallback)) {}

void BinderDeathRecipient::binderDied(const wp<IBinder>& binder) {
    mBinderDiedCallback(binder);
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
