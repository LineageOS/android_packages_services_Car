/**
 * Copyright 2019 The Android Open Source Project
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
#include "RouterSvc.h"

#include <binder/IServiceManager.h>

#include "PipeRegistration.h"
#include "Registry.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace router {
namespace V1_0 {
namespace implementation {

const static char kRouterName[] = "router";

Error RouterSvc::parseArgs(int argc, char** argv) {
    (void)argc;
    (void)argv;
    return OK;
}

Error RouterSvc::initSvc() {
    mRegistry = std::make_shared<RouterRegistry>();
    auto ret = initRegistrationEngine();
    if (ret != OK) {
        return ret;
    }
    ret = initQueryEngine();
    if (ret != OK) {
        return ret;
    }
    return OK;
}

Error RouterSvc::initRegistrationEngine() {
    mRegisterEngine = new PipeRegistration(mRegistry);
    if (!mRegisterEngine) {
        ALOGE("unable to allocate registration engine");
        return NOMEM;
    }
    std::string name =
        std::string() + String8(mRegisterEngine->getIfaceName()).c_str() + "/" + kRouterName;
    auto status = defaultServiceManager()->addService(String16(name.c_str()), mRegisterEngine);
    if (status != android::OK) {
        ALOGE("unable to add registration service %s", name.c_str());
        return INTERNAL_ERR;
    }
    return OK;
}

Error RouterSvc::initQueryEngine() {
    mQueryEngine = new PipeQuery(mRegistry);
    if (!mQueryEngine) {
        ALOGE("unable to allocate query service");
        return NOMEM;
    }
    std::string name =
        std::string() + String8(mQueryEngine->getIfaceName()).c_str() + "/" + kRouterName;
    auto status = defaultServiceManager()->addService(String16(name.c_str()), mQueryEngine);
    if (status != android::OK) {
        ALOGE("unable to add query service %s", name.c_str());
        return INTERNAL_ERR;
    }
    return OK;
}
}  // namespace implementation
}  // namespace V1_0
}  // namespace router
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
