/*
 * Copyright (c) 2020 The Android Open Source Project
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

#ifndef WATCHDOG_SERVER_SRC_SERVICEMANAGER_H_
#define WATCHDOG_SERVER_SRC_SERVICEMANAGER_H_

#include <android-base/result.h>
#include <utils/Looper.h>

namespace android {
namespace automotive {
namespace watchdog {

enum ServiceType {
    PROCESS_ANR_MONITOR,
    IO_PERFORMANCE_MONITOR,
};

class ServiceManager {
public:
public:
    static android::base::Result<void> startService(ServiceType type,
                                                    const android::sp<Looper>& looper);

private:
    static android::base::Result<void> startProcessAnrMonitor(const android::sp<Looper>& looper);
    static android::base::Result<void> startIoPerfMonitor();
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  // WATCHDOG_SERVER_SRC_SERVICEMANAGER_H_
