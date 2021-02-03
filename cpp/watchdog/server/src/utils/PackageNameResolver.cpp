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

#include <android/automotive/watchdog/internal/PackageInfo.h>
#include <cutils/android_filesystem_config.h>
#include <utils/String16.h>

#include <inttypes.h>
#include <pwd.h>

namespace android {
namespace automotive {
namespace watchdog {

using android::IBinder;
using android::sp;
using android::String16;
using android::automotive::watchdog::internal::PackageInfo;
using android::base::Error;
using android::base::Result;
using android::binder::Status;

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

Result<void> PackageNameResolver::initWatchdogServiceHelper(
        const sp<WatchdogServiceHelperInterface>& watchdogServiceHelper) {
    std::unique_lock writeLock(mRWMutex);
    if (watchdogServiceHelper == nullptr) {
        return Error() << "Must provide a non-null watchdog service helper instance";
    }
    if (mWatchdogServiceHelper != nullptr) {
        return Error() << "Duplicate initialization";
    }
    mWatchdogServiceHelper = watchdogServiceHelper;
    return {};
}

Result<void> PackageNameResolver::setVendorPackagePrefixes(
        const std::unordered_set<std::string>& prefixes) {
    std::unique_lock writeLock(mRWMutex);
    mVendorPackagePrefixes.clear();
    for (const auto& prefix : prefixes) {
        mVendorPackagePrefixes.push_back(prefix);
    }
    // TODO(b/167240592): Update locally cached per-package information to reflect the latest
    //  vendor package prefixes. Because these prefixes list are not updated frequently, the local
    //  cache can be cleared on this update. Then the next call to resolve UIDs will use the latest
    //  prefixes list.
    return {};
}

std::unordered_map<uid_t, std::string> PackageNameResolver::resolveUids(
        const std::unordered_set<uid_t>& uids) {
    std::unordered_map<uid_t, std::string> uidToPackageNameMapping;
    std::vector<int32_t> missingAppUids;
    std::vector<uid_t> missingNativeUids;
    {
        std::shared_lock readLock(mRWMutex);
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

    std::unique_lock writeLock(mRWMutex);
    for (const auto& uid : missingNativeUids) {
        // System/native UIDs.
        passwd* usrpwd = getpwuid(uid);
        if (!usrpwd) {
            continue;
        }
        uidToPackageNameMapping[uid] = std::string(usrpwd->pw_name);
        mUidToPackageNameMapping[uid] = std::string(usrpwd->pw_name);
    }

    /*
     * There is delay between creating package manager instance and initializing watchdog service
     * helper. Thus check the watchdog service helper instance before proceeding further.
     */
    if (missingAppUids.empty() || mWatchdogServiceHelper == nullptr) {
        return uidToPackageNameMapping;
    }

    std::vector<PackageInfo> packageInfos;
    Status status =
            mWatchdogServiceHelper->getPackageInfosForUids(missingAppUids, mVendorPackagePrefixes,
                                                           &packageInfos);
    if (!status.isOk()) {
        ALOGE("Failed to resolve application UIDs: %s", status.exceptionMessage().c_str());
        return uidToPackageNameMapping;
    }

    // TODO(b/167240592): Use the package info here to return the package name mapping for app UIDs.
    return uidToPackageNameMapping;
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
