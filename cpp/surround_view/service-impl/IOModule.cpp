/*
 * Copyright 2020 The Android Open Source Project
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

#include <android-base/logging.h>

#include "CarModelConfigReader.h"
#include "ConfigReader.h"
#include "IOModule.h"
#include "ObjReader.h"

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace V1_0 {
namespace implementation {

IOModule::IOModule(const std::string& svConfigFile) :
      mSvConfigFile(svConfigFile), mIsInitialized(false) {}

IOStatus IOModule::initialize() {
    if (mIsInitialized) {
        LOG(INFO) << "IOModule is already initialized.";
        return IOStatus::OK;
    }

    IOStatus status;
    if ((status = ReadSurroundViewConfig(mSvConfigFile, &mIOModuleConfig)) != IOStatus::OK) {
        LOG(ERROR) << "ReadSurroundViewConfig() failed.";
        return status;
    }
    mIsInitialized = true;
    return IOStatus::OK;
}

bool IOModule::getConfig(IOModuleConfig* ioModuleConfig) {
    if (!mIsInitialized) {
        LOG(ERROR) << "IOModule not initalized.";
        return false;
    }
    *ioModuleConfig = mIOModuleConfig;
    return true;
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android
