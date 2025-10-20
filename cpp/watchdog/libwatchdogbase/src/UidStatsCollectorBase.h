/*
 * Copyright (c) 2025, The Android Open Source Project
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

#pragma once

#include "PackageInfoResolver.h"
#include "UidIoStatsCollector.h"

#include <aidl/android/automotive/watchdog/internal/PackageInfo.h>
#include <android-base/result.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>

#include <string>
#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

// Forward declaration for testing use only.
namespace internal {

class UidStatsCollectorBasePeer;

}  // namespace internal

struct UidBaseStats {
    aidl::android::automotive::watchdog::internal::PackageInfo packageInfo;
    UidIoStats ioStats = {};
    // Returns package name if the |packageInfo| is available. Otherwise, returns the |uid|.
    std::string genericPackageName() const;
    // Returns true when package info is available.
    bool hasPackageInfo() const;
    // Returns the uid for the stats;
    uid_t uid() const;
};

// Collector/Aggregator for per-UID I/O stats.
class UidStatsCollectorBaseInterface : virtual public RefBase {
public:
    // Initializes the collector.
    virtual void init() = 0;
    // Collects the per-UID I/O stats.
    virtual android::base::Result<void> collect() = 0;
    // Returns the latest per-uid I/O stats.
    virtual const std::vector<UidBaseStats> latestBaseStats() const = 0;
    // Returns the delta of per-uid I/O stats since the last before collection.
    virtual const std::vector<UidBaseStats> deltaBaseStats() const = 0;
    // Returns true only when the per-UID I/O stats files are accessible.
    virtual bool enabled() const = 0;
};

class UidStatsCollectorBase : public UidStatsCollectorBaseInterface {
public:
    explicit UidStatsCollectorBase(
            const std::shared_ptr<PackageInfoResolverInterface>& packageInfoResolver) :
          mPackageInfoResolver(packageInfoResolver),
          mUidIoStatsCollector(android::sp<UidIoStatsCollector>::make()) {}

    void init() override {
        Mutex::Autolock lock(mMutex);
        mUidIoStatsCollector->init();
    }

    android::base::Result<void> collect() override;

    const std::vector<UidBaseStats> latestBaseStats() const override {
        Mutex::Autolock lock(mMutex);
        return mLatestBaseStats;
    }

    const std::vector<UidBaseStats> deltaBaseStats() const override {
        Mutex::Autolock lock(mMutex);
        return mDeltaBaseStats;
    }

    bool enabled() const override { return mUidIoStatsCollector->enabled(); }

protected:
    // Local PackageInfoResolverInterface instance. Useful to mock in tests.
    std::shared_ptr<PackageInfoResolverInterface> mPackageInfoResolver;

    mutable Mutex mMutex;

    android::sp<UidIoStatsCollectorInterface> mUidIoStatsCollector GUARDED_BY(mMutex);

    std::vector<UidBaseStats> mLatestBaseStats GUARDED_BY(mMutex);

    std::vector<UidBaseStats> mDeltaBaseStats GUARDED_BY(mMutex);

private:
    std::vector<UidBaseStats> process(
            const std::unordered_map<uid_t, UidIoStats>& uidIoStatsByUid) const;

    // For unit tests.
    friend class internal::UidStatsCollectorBasePeer;
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
