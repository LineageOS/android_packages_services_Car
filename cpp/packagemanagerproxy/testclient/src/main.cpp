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

#define LOG_TAG "PackageManagerProxyTest"

#include <aidl/google/sdv/packagemanagerproxy/IPackageManagerProxy.h>
#include <android/binder_manager.h>
#include <log/log.h>

#include <iostream>

namespace {

using ::aidl::google::sdv::packagemanagerproxy::IPackageManagerProxy;

}  // namespace

int main(int argc, char** argv) {
    ALOGI("PackageManagerProxy Test Client started.");

    std::string packageName;

    if (argc == 2) {
        packageName = argv[1];
    } else {
        std::cerr << "Usage: proxymanagerproxyd_testclient <package name>" << std::endl;
        return 1;
    }

    auto myService =
            IPackageManagerProxy::fromBinder(ndk::SpAIBinder(AServiceManager_waitForService(
                    "google.sdv.packagemanagerproxy.IPackageManagerProxy/default")));

    std::cout << "Fetching package info for \"" << packageName << "\"" << std::endl;

    // Uid
    int32_t uid = 0;
    auto result = myService->getPackageUid(packageName, /*flags=*/0, /*userId=*/0, &uid);
    if (!result.isOk()) {
        ALOGE("getPackageUid failed: (%d, %d), %s", result.getExceptionCode(),
              result.getServiceSpecificError(), result.getMessage());
        return 1;
    }

    std::cout << "Uid: " << uid << std::endl;

    // Version code
    int64_t versionCode = 0;
    result = myService->getVersionCodeForPackage(packageName, &versionCode);

    if (!result.isOk()) {
        ALOGE("getVersionCodeForPackage failed: (%d, %d), %s", result.getExceptionCode(),
              result.getServiceSpecificError(), result.getMessage());
        return 1;
    }

    std::cout << "Version Code: " << versionCode << std::endl;

    // Validate getting package name by uid
    std::vector<int32_t> uids = {uid};
    std::vector<std::string> fetchedPackageNames;
    result = myService->getNamesForUids(uids, &fetchedPackageNames);

    if (!result.isOk()) {
        ALOGE("getNamesForUids failed: (%d, %d), %s", result.getExceptionCode(),
              result.getServiceSpecificError(), result.getMessage());
        return 1;
    }

    if (fetchedPackageNames.size() != 1) {
        ALOGE("Expected 1 returned package name, actually received %zu",
              fetchedPackageNames.size());
        return 1;
    }

    if (fetchedPackageNames[0] != packageName) {
        ALOGE("Package names do not match. Received: \"%s\", expected: \"%s\"",
              fetchedPackageNames[0].c_str(), packageName.c_str());
        return 1;
    }

    std::cout << "Fetched package name from Uid: " << fetchedPackageNames[0] << std::endl;

    return 0;
}
