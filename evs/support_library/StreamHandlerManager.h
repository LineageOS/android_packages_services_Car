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

#ifndef CAR_LIB_EVS_SUPPORT_STREAMHANDLERMANAGER_H
#define CAR_LIB_EVS_SUPPORT_STREAMHANDLERMANAGER_H

#include <utils/RefBase.h>
#include <unordered_map>
#include "StreamHandler.h"

namespace android {
namespace automotive {
namespace evs {
namespace support {

using ::android::sp;

class StreamHandlerManager : public android::RefBase {
public:
    static sp<StreamHandlerManager> getInstance();
    sp<StreamHandler> getStreamHandler(sp<IEvsCamera> pCamera);

private:
    static sp<StreamHandlerManager> sInstance;

    std::unordered_map<std::string, sp<StreamHandler>> mStreamHandlers;
};

}  // namespace support
}  // namespace evs
}  // namespace automotive
}  // namespace android

#endif //CAR_LIB_EVS_SUPPORT_STREAMHANDLERMANAGER_H
