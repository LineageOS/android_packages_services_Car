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

#include "MockWatchdogServiceHelper.h"
#include "PackageNameResolver.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::testing::_;
using ::testing::DoAll;
using ::testing::NotNull;
using ::testing::Pair;
using ::testing::Return;
using ::testing::SetArgPointee;
using ::testing::UnorderedElementsAre;

TEST(PackageNameResolverTest, TestResolvesNativeUid) {
    PackageNameResolver::sInstance.clear();
    auto actualMapping = PackageNameResolver::getInstance()->resolveUids({0});

    EXPECT_THAT(actualMapping, UnorderedElementsAre(Pair(0, "root")));
}

/** TODO(b/167240592): Stub watchdog service helper's getPackageInfosForUids and test when
 *   application UIDs resolving logic is implemented
TEST(PackageNameResolverTest, TestResolvesApplicationUidFromWatchdogServiceHelper) {
    PackageNameResolver::sInstance.clear();
    auto mockWatchdogServiceHelper = new MockWatchdogServiceHelper();
    auto packageNameResolver = PackageNameResolver::getInstance();
    packageNameResolver->initWatchdogServiceHelper(mockWatchdogServiceHelper);
    std::vector<std::string> prefixes = {"vendor.package.A"};
    packageNameResolver->setVendorPackagePrefixes(prefixes);

    std::vector<std::string> packageNames = {"shared:android.uid.system"};
    EXPECT_CALL(*mock, getNamesForUids(std::vector<int32_t>({1001000}), NotNull()))
            .WillOnce(DoAll(SetArgPointee<1>(packageNames), Return(binder::Status::ok())));

    auto actualMapping = packageNameResolver->resolveUids({1001000});

    EXPECT_THAT(actualMapping, UnorderedElementsAre(Pair(1001000, "shared:android.uid.system")));
}
 */

TEST(PackageNameResolverTest, TestResolvesApplicationUidFromLocalCache) {
    PackageNameResolver::sInstance.clear();
    auto packageNameResolver = PackageNameResolver::getInstance();
    auto mockWatchdogServiceHelper = new MockWatchdogServiceHelper();
    PackageNameResolver::sInstance->initWatchdogServiceHelper(mockWatchdogServiceHelper);

    PackageNameResolver::sInstance->mUidToPackageNameMapping = {{1003456, "random package"}};
    EXPECT_CALL(*mockWatchdogServiceHelper, getPackageInfosForUids(_, _, _)).Times(0);

    auto actualMapping = packageNameResolver->resolveUids({1003456});

    EXPECT_THAT(actualMapping, UnorderedElementsAre(Pair(1003456, "random package")));
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
