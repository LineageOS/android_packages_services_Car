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

#ifndef CPP_WATCHDOG_SERVER_SRC_UTILS_PACKAGENAMERESOLVER_H_
#define CPP_WATCHDOG_SERVER_SRC_UTILS_PACKAGENAMERESOLVER_H_

#include "src/WatchdogServiceHelper.h"

#include <android-base/result.h>
#include <binder/IBinder.h>
#include <gtest/gtest_prod.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>

#include <shared_mutex>
#include <unordered_map>
#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

class ServiceManager;
class IoOveruseMonitor;
struct IoOveruseConfigs;

// Forward declaration for testing use only.
namespace internal {

class PackageNameResolverPeer;

}  // namespace internal

/*
 * PackageNameResolver maintains a cache of the UID to PackageName mapping in the CarWatchdog
 * daemon. PackageNameResolver is a singleton and must be accessed only via the public static
 * methods.
 *
 * TODO(b/158131194): Extend IUidObserver in WatchdogBinderMediator and use the onUidGone API to
 *  keep the local mapping cache up-to-date.
 */
class PackageNameResolver : public android::RefBase {
public:
    /*
     * Initializes the PackageNameResolver's singleton instance only on the first call. Main thread
     * should make the first call as this method doesn't offer multi-threading protection.
     */
    static sp<PackageNameResolver> getInstance();

    /*
     * Resolves the given |uids| and returns a mapping of uids to package names. If the mapping
     * doesn't exist in the local cache, queries the car watchdog service for application uids and
     * getpwuid for native uids. Logs any error observed during this process.
     *
     * TODO(b/168155311): For shared UIDs, fetch the package names using PackageManager's java API
     *  getPackagesForUid with the help of CarWatchdogService.
     */
    std::unordered_map<uid_t, std::string> resolveUids(const std::unordered_set<uid_t>& uids);

    ~PackageNameResolver() {
        std::unique_lock writeLock(mRWMutex);
        mWatchdogServiceHelper.clear();
        mUidToPackageNameMapping.clear();
    }

protected:
    static void terminate();

    android::base::Result<void> initWatchdogServiceHelper(
            const android::sp<WatchdogServiceHelperInterface>& watchdogServiceHelper);

    android::base::Result<void> setVendorPackagePrefixes(
            const std::unordered_set<std::string>& prefixes);

private:
    // PackageNameResolver instance can only be obtained via |getInstance|.
    PackageNameResolver() : mWatchdogServiceHelper(nullptr) {}

    // Singleton instance.
    static android::sp<PackageNameResolver> sInstance;

    mutable std::shared_mutex mRWMutex;
    /*
     * ServiceManager::startServices initializes PackageNameResolver. However, between the
     * |getInstance| and |initWatchdogServiceHelper| calls it initializes few other services, which
     * may call |resolveUids| simultaneously on a separate thread. In order to avoid a race
     * condition between |initWatchdogServiceHelper| and |resolveUids| calls, mWatchdogServiceHelper
     * is guarded by a read-write lock.
     */
    android::sp<WatchdogServiceHelperInterface> mWatchdogServiceHelper GUARDED_BY(mRWMutex);
    std::unordered_map<uid_t, std::string> mUidToPackageNameMapping GUARDED_BY(mRWMutex);
    std::vector<std::string> mVendorPackagePrefixes GUARDED_BY(mRWMutex);

    friend class ServiceManager;
    friend class IoOveruseMonitor;
    friend struct IoOveruseConfigs;

    // For unit tests.
    friend class internal::PackageNameResolverPeer;
    FRIEND_TEST(PackageNameResolverTest, TestResolvesNativeUid);
    FRIEND_TEST(PackageNameResolverTest, TestResolvesApplicationUidFromWatchdogServiceHelper);
    FRIEND_TEST(PackageNameResolverTest, TestResolvesApplicationUidFromLocalCache);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_UTILS_PACKAGENAMERESOLVER_H_
