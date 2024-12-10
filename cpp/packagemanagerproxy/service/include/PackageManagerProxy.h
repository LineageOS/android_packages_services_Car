/*
 * Copyright (C) 2024 The Android Open Source Project
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

#pragma once

#include <aidl/google/sdv/packagemanagerproxy/BnPackageManagerProxy.h>
#include <aidl/google/sdv/packagemanagerproxy/IPackageManagerProxy.h>
#include <android-base/result.h>
#include <android/content/pm/IPackageManagerNative.h>

#include <string>
#include <vector>

namespace google {
namespace sdv {
namespace packagemanagerproxy {

/**
 * This class implements the IPackageManagerProxy AIDL interface.
 *
 * Once binder is setup for the process, create an instance of this class
 * and call init() on the object. This will wait for the IPackageManagerNative
 * service, then reigster itself with service manager as a provider of the
 * IPackageManagerProxy interface.
 */
class PackageManagerProxy : public aidl::google::sdv::packagemanagerproxy::BnPackageManagerProxy {
public:
    android::base::Result<void> init();

    // Implements IPackageManagerProxy.aidl interfaces.
    ndk::ScopedAStatus getNamesForUids(const std::vector<int32_t>& uids,
                                       std::vector<std::string>* _aidl_return) override;
    ndk::ScopedAStatus getPackageUid(const std::string& packageName, int64_t flags, int32_t userId,
                                     int32_t* _aidl_return) override;
    ndk::ScopedAStatus getVersionCodeForPackage(const std::string& packageName,
                                                int64_t* _aidl_return) override;

private:
    android::sp<android::content::pm::IPackageManagerNative> mPackageManagerNativeService;
};

}  // namespace packagemanagerproxy
}  // namespace sdv
}  // namespace google
