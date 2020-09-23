/**
 * Copyright (c) 2020, The Android Open Source Project
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

#define LOG_TAG "carwatchdogd"

#include "PackageNameResolver.h"

#include <binder/IServiceManager.h>
#include <cutils/android_filesystem_config.h>
#include <pwd.h>

#include <inttypes.h>

namespace android {
namespace automotive {
namespace watchdog {

using android::defaultServiceManager;
using android::IBinder;
using android::IServiceManager;
using android::sp;
using android::base::Error;
using android::base::Result;
using android::content::pm::IPackageManagerNative;

static constexpr const char* kPackageNativeManager = "package_native";

sp<PackageNameResolver> PackageNameResolver::sInstance = nullptr;

sp<PackageNameResolver> PackageNameResolver::getInstance() {
    if (sInstance == nullptr) {
        sInstance = new PackageNameResolver();
    }
    return sInstance;
}

void PackageNameResolver::terminate() {
    sInstance.clear();
}

PackageNameResolver::~PackageNameResolver() {
    if (mPackageManager != nullptr) {
        IInterface::asBinder(mPackageManager)
                ->unlinkToDeath(static_cast<IBinder::DeathRecipient*>(this));
    }
}

void PackageNameResolver::binderDied(const android::wp<IBinder>& /*who*/) {
    ALOGI("%s binder died", kPackageNativeManager);
    std::unique_lock write_lock(mRWMutex);
    IInterface::asBinder(mPackageManager)
            ->unlinkToDeath(static_cast<IBinder::DeathRecipient*>(this));
    mPackageManager = nullptr;
}

std::unordered_map<uid_t, std::string> PackageNameResolver::resolveUids(
        const std::unordered_set<uid_t>& uids) {
    std::unordered_map<uid_t, std::string> uidToPackageNameMapping;
    std::vector<int32_t> missingAppUids;
    std::vector<uid_t> missingNativeUids;
    {
        std::shared_lock read_lock(mRWMutex);
        for (const auto& uid : uids) {
            if (mUidToPackageNameMapping.find(uid) != mUidToPackageNameMapping.end()) {
                uidToPackageNameMapping[uid] = mUidToPackageNameMapping.at(uid);
            } else if (uid >= AID_APP_START) {
                missingAppUids.emplace_back(static_cast<int32_t>(uid));
            } else {
                missingNativeUids.emplace_back(uid);
            }
        }
    }

    if (missingAppUids.empty() && missingNativeUids.empty()) {
        return uidToPackageNameMapping;
    }

    std::unique_lock write_lock(mRWMutex);
    for (const auto& uid : missingNativeUids) {
        // System/native UIDs.
        passwd* usrpwd = getpwuid(uid);
        if (!usrpwd) {
            continue;
        }
        uidToPackageNameMapping[uid] = std::string(usrpwd->pw_name);
        mUidToPackageNameMapping[uid] = std::string(usrpwd->pw_name);
    }

    if (missingAppUids.empty()) {
        return uidToPackageNameMapping;
    }

    // Fetch package native manager binder instance only on noticing missing application UIDs as
    // this indicates the package manager binder is already initialized. Thus the CarWatchdog daemon
    // doesn't have to wait for the package manager binder during boot up.
    auto ret = initializePackageManagerLocked();
    if (!ret.ok()) {
        ALOGE("Failed to initialize %s binder instance: %s", kPackageNativeManager,
              ret.error().message().c_str());
        return uidToPackageNameMapping;
    }

    std::vector<std::string> packageNames;
    const binder::Status& status = mPackageManager->getNamesForUids(missingAppUids, &packageNames);
    if (!status.isOk()) {
        ALOGE("Failed to get package name mapping from %s: %s", kPackageNativeManager,
              status.exceptionMessage().c_str());
        return uidToPackageNameMapping;
    }

    for (size_t i = 0; i < missingAppUids.size(); i++) {
        const int32_t uid = missingAppUids[i];
        const std::string& packageName = packageNames[i];
        if (!packageName.empty()) {
            uidToPackageNameMapping[static_cast<uid_t>(uid)] = packageName;
            mUidToPackageNameMapping[static_cast<uid_t>(uid)] = packageName;
        }
    }
    return uidToPackageNameMapping;
}

Result<void> PackageNameResolver::initializePackageManagerLocked() {
    if (mPackageManager != nullptr) {
        return {};
    }
    const sp<IServiceManager> sm = defaultServiceManager();
    if (sm == nullptr) {
        return Error() << "Failed to retrieve defaultServiceManager";
    }
    sp<IBinder> binder = sm->checkService(String16(kPackageNativeManager));
    if (binder == nullptr) {
        return Error() << "Failed to retrieve " << kPackageNativeManager << " service";
    }
    ALOGI("Initialized %s binder", kPackageNativeManager);
    mPackageManager = interface_cast<IPackageManagerNative>(binder);
    binder->linkToDeath(static_cast<IBinder::DeathRecipient*>(this));
    return {};
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
