/*
 * Copyright 2021 The Android Open Source Project
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

#include "IoOveruseMonitor.h"
#include "MockProcDiskStats.h"

namespace android {
namespace automotive {
namespace watchdog {

constexpr size_t kTestMonitorBufferSize = 3;
constexpr std::chrono::seconds kTestMonitorInterval = 5s;

using ::android::automotive::watchdog::internal::IoOveruseAlertThreshold;
using ::android::base::Result;

namespace {

IoOveruseAlertThreshold toIoOveruseAlertThreshold(const int64_t durationInSeconds,
                                                  const int64_t writtenBytesPerSecond) {
    IoOveruseAlertThreshold threshold;
    threshold.durationInSeconds = durationInSeconds;
    threshold.writtenBytesPerSecond = writtenBytesPerSecond;
    return threshold;
}

}  // namespace

namespace internal {

class IoOveruseMonitorPeer {
public:
    explicit IoOveruseMonitorPeer(IoOveruseMonitor* ioOveruseMonitor) :
          mIoOveruseMonitor(ioOveruseMonitor) {}

    Result<void> init() {
        if (const auto result = mIoOveruseMonitor->init(); !result.ok()) {
            return result;
        }
        mIoOveruseMonitor->mPeriodicMonitorBufferSize = kTestMonitorBufferSize;
        return {};
    }

    void setIoOveruseConfigs(const IoOveruseConfigs& configs) {
        mIoOveruseMonitor->mIoOveruseConfigs = configs;
    }

private:
    IoOveruseMonitor* mIoOveruseMonitor;
};

}  // namespace internal

TEST(IoOveruseMonitorTest, TestOnPeriodicMonitor) {
    IoOveruseMonitor ioOveruseMonitor;
    internal::IoOveruseMonitorPeer ioOveruseMonitorPeer(&ioOveruseMonitor);
    ioOveruseMonitorPeer.init();

    IoOveruseConfigs configs;
    configs.alertThresholds = {
            toIoOveruseAlertThreshold(
                    /*durationInSeconds=*/10, /*writtenBytesPerSecond=*/15'360),
            toIoOveruseAlertThreshold(
                    /*durationInSeconds=*/17, /*writtenBytesPerSecond=*/10'240),
            toIoOveruseAlertThreshold(
                    /*durationInSeconds=*/23, /*writtenBytesPerSecond=*/7'168),
    };
    ioOveruseMonitorPeer.setIoOveruseConfigs(configs);

    time_t time = 0;
    const auto nextCollectionTime = [&]() -> time_t {
        time += kTestMonitorInterval.count();
        return time;
    };
    bool isAlertReceived = false;
    const auto alertHandler = [&]() { isAlertReceived = true; };

    // 1st polling is ignored
    sp<MockProcDiskStats> mockProcDiskStats = new MockProcDiskStats();
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).Times(0);

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    ASSERT_FALSE(isAlertReceived) << "Triggered spurious alert as first polling is ignored";

    // 2nd polling - guarded by the heuristic to handle spurious alerting on partially filled buffer
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 70};
    });

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    ASSERT_FALSE(isAlertReceived) << "Triggered spurious alert when not exceeding the threshold";

    // 3rd polling exceeds first threshold
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 90};
    });

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    ASSERT_TRUE(isAlertReceived) << "Failed to trigger alert when exceeding the threshold";

    isAlertReceived = false;

    // 4th polling - guarded by the heuristic to handle spurious alerting on partially filled buffer
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 10};
    });

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    ASSERT_FALSE(isAlertReceived) << "Triggered spurious alert when not exceeding the threshold";

    // 5th polling exceeds second threshold
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 80};
    });

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    ASSERT_TRUE(isAlertReceived) << "Failed to trigger alert when exceeding the threshold";

    isAlertReceived = false;

    // 6th polling exceeds third threshold
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 10};
    });

    ASSERT_RESULT_OK(ioOveruseMonitor.onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                        alertHandler));
    ASSERT_TRUE(isAlertReceived) << "Failed to trigger alert when exceeding the threshold";
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
