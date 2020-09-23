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

#include <android-base/result.h>
#include <android/content/pm/IPackageManagerNative.h>
#include <binder/IBinder.h>
#include <gtest/gtest_prod.h>
#include <utils/Mutex.h>
#include <utils/StrongPointer.h>

#include <shared_mutex>
#include <unordered_map>
#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

// Forward declaration for testing use only.
namespace internal {

class PackageNameResolverPeer;

}  // namespace internal

// PackageNameResolver maintains a cache of the UID to PackageName mapping in the CarWatchdog
// daemon. PackageNameResolver is a singleton and must be accessed only via the public static
// methods.
// TODO(b/158131194): Extend IUidObserver in WatchdogBinderMediator and use the onUidGone API to
// keep the local mapping cache up-to-date.
class PackageNameResolver : public IBinder::DeathRecipient {
public:
    // Initializes the PackageNameResolver's singleton instance only on the first call. Main thread
    // should make the first call as this method doesn't offer multi-threading protection.
    static sp<PackageNameResolver> getInstance();

    static void terminate();

    // Resolves the given |uids| and returns a mapping of uids to package names. If the mapping
    // doesn't exist in the local cache, queries the package manager for application uids and
    // getpwuid for native uids. Logs any error observed during this process.
    // TODO(b/168155311): For shared UIDs, fetch the package names using PackageManager's java API
    // getPackagesForUid with the help of CarWatchdogService.
    std::unordered_map<uid_t, std::string> resolveUids(const std::unordered_set<uid_t>& uids);

    // If the local copy of the package manager binder instance is initialized, un-links itself from
    // the binder death notification.
    ~PackageNameResolver();

    // DeathRecipient interface.
    void binderDied(const android::wp<IBinder>& who) override;

private:
    // PackageNameResolver instance can only be obtained via |getInstance|.
    PackageNameResolver() {}

    // Initializes the local copy of package manager binder instance and links itself to the binder
    // death.
    android::base::Result<void> initializePackageManagerLocked();

    // Singleton instance.
    static android::sp<PackageNameResolver> sInstance;

    mutable std::shared_mutex mRWMutex;
    android::sp<android::content::pm::IPackageManagerNative> mPackageManager GUARDED_BY(mRWMutex);
    std::unordered_map<uid_t, std::string> mUidToPackageNameMapping GUARDED_BY(mRWMutex);

    // For unit tests.
    friend class internal::PackageNameResolverPeer;
    FRIEND_TEST(PackageNameResolverTest, TestResolvesNativeUid);
    FRIEND_TEST(PackageNameResolverTest, TestResolvesApplicationUidFromPackageManager);
    FRIEND_TEST(PackageNameResolverTest, TestResolvesApplicationUidFromLocalCache);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_UTILS_PACKAGENAMERESOLVER_H_
