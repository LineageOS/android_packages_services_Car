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

#ifndef CPP_TELEMETRY_SRC_BINDERUTILS_BINDERDEATHRECIPIENT_H_
#define CPP_TELEMETRY_SRC_BINDERUTILS_BINDERDEATHRECIPIENT_H_

#include <binder/IBinder.h>

#include <functional>

namespace android {
namespace automotive {
namespace telemetry {

// Binder death recipient. See `android.os.IBinder#linkToDeath()` method to learn more.
// Used to monitor status of the listeners/callbacks connected through binder.
class BinderDeathRecipient : public android::IBinder::DeathRecipient {
public:
    explicit BinderDeathRecipient(
            std::function<void(const wp<android::IBinder>& what)> binderDiedCallback);

    void binderDied(const android::wp<android::IBinder>& binder) override;

private:
    std::function<void(const wp<android::IBinder>& what)> mBinderDiedCallback;
};

}  // namespace telemetry
}  // namespace automotive
}  // namespace android

#endif  // CPP_TELEMETRY_SRC_BINDERUTILS_BINDERDEATHRECIPIENT_H_
