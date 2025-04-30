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

#include "PackageManagerProxy.h"

#include <android-base/properties.h>
#include <android/binder_manager.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <log/log.h>
#include <utils/Looper.h>

#include <android_car_feature.h>

#include <sstream>

namespace google {
namespace sdv {
namespace packagemanagerproxy {

namespace {

using ::aidl::google::sdv::packagemanagerproxy::IPackageManagerProxy;
using ::android::IBinder;
using ::android::interface_cast;
using ::android::IServiceManager;
using ::android::sp;
using ::android::String16;
using ::android::base::Error;
using ::android::base::GetProperty;
using ::android::base::Result;
using ::android::content::pm::IPackageManagerNative;
using ::ndk::ScopedAStatus;

}  // namespace

Result<void> PackageManagerProxy::init() {
    // If the feature flag is not enabled, do not initialize the service
    if (!android::car::feature::package_manager_extensions_for_sdv()) {
        ALOGI("Flag package_manager_extensions_for_sdv disabled, disabling service");
        return {};
    }

    const auto instanceName = std::string(IPackageManagerProxy::descriptor) + "/default";
    const binder_exception_t err =
            AServiceManager_addService(this->asBinder().get(), instanceName.data());
    if (err != EX_NONE) {
        return Error(err) << "Failed to add IPackageManagerProxy to ServiceManager";
    }

    mInitialized = true;
    return {};
}

ndk::ScopedAStatus PackageManagerProxy::getPackageManagerNative(
        android::sp<android::content::pm::IPackageManagerNative>* outPackageManagerNativeService) {
    if (!mInitialized) {
        return ScopedAStatus::fromServiceSpecificErrorWithMessage(-1000,
                                                                  "IPackageManagerProxy not "
                                                                  "initialized or disabled");
    }

    sp<IServiceManager> serviceManager = android::defaultServiceManager();
    if (serviceManager.get() == nullptr) {
        return ScopedAStatus::
                fromServiceSpecificErrorWithMessage(-1001,
                                                    "Unable to access native ServiceManager");
    }

    sp<IBinder> binder = serviceManager->waitForService(String16("package_native"));
    *outPackageManagerNativeService = interface_cast<IPackageManagerNative>(binder);
    if (*outPackageManagerNativeService == nullptr) {
        return ScopedAStatus::
                fromServiceSpecificErrorWithMessage(-1002,
                                                    "Unable to access native PackageManager");
    }

    return ScopedAStatus::ok();
}

ndk::ScopedAStatus PackageManagerProxy::getNamesForUids(const std::vector<int32_t>& uids,
                                                        std::vector<std::string>* _aidl_return) {
    android::sp<android::content::pm::IPackageManagerNative> packageManagerNativeService;
    ScopedAStatus initStatus = getPackageManagerNative(&packageManagerNativeService);
    if (!initStatus.isOk()) {
        return initStatus;
    }

    const auto result = packageManagerNativeService->getNamesForUids(uids, _aidl_return);

    if (!result.isOk()) {
        return ScopedAStatus::fromServiceSpecificErrorWithMessage(result.exceptionCode(),
                                                                  result.exceptionMessage()
                                                                          .c_str());
    }

    return ScopedAStatus::ok();
}

ndk::ScopedAStatus PackageManagerProxy::getPackageUid(const std::string& packageName, int64_t flags,
                                                      int32_t userId, int32_t* _aidl_return) {
    android::sp<android::content::pm::IPackageManagerNative> packageManagerNativeService;
    ScopedAStatus initStatus = getPackageManagerNative(&packageManagerNativeService);
    if (!initStatus.isOk()) {
        return initStatus;
    }

    const auto result =
            packageManagerNativeService->getPackageUid(packageName, flags, userId, _aidl_return);

    if (!result.isOk()) {
        return ScopedAStatus::fromServiceSpecificErrorWithMessage(result.exceptionCode(),
                                                                  result.exceptionMessage()
                                                                          .c_str());
    }

    return ScopedAStatus::ok();
}

ndk::ScopedAStatus PackageManagerProxy::getVersionCodeForPackage(const std::string& packageName,
                                                                 int64_t* _aidl_return) {
    android::sp<android::content::pm::IPackageManagerNative> packageManagerNativeService;
    ScopedAStatus initStatus = getPackageManagerNative(&packageManagerNativeService);
    if (!initStatus.isOk()) {
        return initStatus;
    }

    const String16 packageNameString16(packageName.c_str(), packageName.length());
    const auto result = packageManagerNativeService->getVersionCodeForPackage(packageNameString16,
                                                                              _aidl_return);

    if (!result.isOk()) {
        return ScopedAStatus::fromServiceSpecificErrorWithMessage(result.exceptionCode(),
                                                                  result.exceptionMessage()
                                                                          .c_str());
    }

    return ScopedAStatus::ok();
}

}  // namespace packagemanagerproxy
}  // namespace sdv
}  // namespace google
