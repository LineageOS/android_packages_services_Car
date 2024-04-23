/*
 * Copyright (c) 2024, The Android Open Source Project
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
#define DEBUG false  // STOPSHIP if true.

#include "PressureMonitor.h"

#include <android-base/stringprintf.h>
#include <log/log.h>

#include <errno.h>
#include <string.h>
#include <sys/epoll.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::base::Error;
using ::android::base::Result;
using ::android::base::StringPrintf;

Result<void> PressureMonitor::init() {
    std::string memoryPath = StringPrintf("%s/%s", kProcPressureDirPath.c_str(), kMemoryFile);
    if (access(memoryPath.c_str(), R_OK) != 0) {
        return Error() << "'" << memoryPath << "' path is not accessible";
    }

    Mutex::Autolock lock(mMutex);
    // TODO(b/335508921): Read the below stall type and thresholds from system properties (one per
    //  pressure level).
    mPressureLevels.push_back(PressureLevelInfo{
            .kPressureLevel = PRESSURE_LEVEL_LOW,
            .kStallType = kLowPsiStallLevel,
            .kThresholdUs = kLowThresholdUs,
    });
    mPressureLevels.push_back(PressureLevelInfo{
            .kPressureLevel = PRESSURE_LEVEL_MEDIUM,
            .kStallType = kMediumPsiStallLevel,
            .kThresholdUs = kMediumThresholdUs,
    });
    mPressureLevels.push_back(PressureLevelInfo{
            .kPressureLevel = PRESSURE_LEVEL_HIGH,
            .kStallType = kHighPsiStallLevel,
            .kThresholdUs = kHighThresholdUs,
    });

    if (const auto& result = initializePsiMonitorsLocked(); !result.ok()) {
        destroyActivePsiMonitorsLocked();
        return Error() << "Failed to initialize memory PSI monitors: " << result.error();
    }

    mIsEnabled = true;
    return {};
}

void PressureMonitor::terminate() {
    Mutex::Autolock lock(mMutex);
    destroyActivePsiMonitorsLocked();
}

std::string PressureMonitor::PressureLevelToString(PressureMonitor::PressureLevel pressureLevel) {
    switch (pressureLevel) {
        case PRESSURE_LEVEL_NONE:
            return "PRESSURE_LEVEL_NONE";
        case PRESSURE_LEVEL_LOW:
            return "PRESSURE_LEVEL_LOW";
        case PRESSURE_LEVEL_MEDIUM:
            return "PRESSURE_LEVEL_MEDIUM";
        case PRESSURE_LEVEL_HIGH:
            return "PRESSURE_LEVEL_HIGH";
        default:
            return "UNKNOWN_PRESSURE_LEVEL";
    }
}

Result<void> PressureMonitor::initializePsiMonitorsLocked() {
    if (mPsiEpollFd = epoll_create(mPressureLevels.size()); mPsiEpollFd < 0) {
        return Error() << "epoll_create failed: " << strerror(errno);
    }

    int totalActivePsiMonitors = 0;
    for (auto& info : mPressureLevels) {
        if (info.kThresholdUs.count() == 0) {
            ALOGI("Disabled PSI monitor for %s",
                  PressureLevelToString(info.kPressureLevel).c_str());
            continue;
        }
        // TODO(b/335508921): Read the below window size from system properties. This need to be
        //  read from system properties (one per pressure level) and store in the PressureLevelInfo.
        if (info.kThresholdUs >= kPsiWindowSizeUs) {
            return Error() << "Threshold duration (" << info.kThresholdUs.count()
                           << ") must be less than the window size duration ("
                           << kPsiWindowSizeUs.count() << ") for "
                           << PressureLevelToString(info.kPressureLevel);
        }
        // The algorithm that determines the current pressure level and notifies the clients
        // require all PSI monitors to be initialized successfully. So, early fail when one of
        // PSI monitor fails to initialize.
        int fd = mInitPsiMonitorFunc(info.kStallType, info.kThresholdUs.count(),
                                     kPsiWindowSizeUs.count());
        if (fd < 0) {
            return Error() << "Failed to initialize memory PSI monitor for "
                           << PressureLevelToString(info.kPressureLevel) << ": " << strerror(errno);
            continue;
        }
        if (mRegisterPsiMonitorFunc(mPsiEpollFd, fd, reinterpret_cast<void*>(info.kPressureLevel)) <
            0) {
            mDestroyPsiMonitorFunc(fd);
            return Error() << "Failed to register memory PSI monitor for "
                           << PressureLevelToString(info.kPressureLevel) << ": " << strerror(errno);
        }
        info.psiMonitorFd = fd;
        ++totalActivePsiMonitors;
    }
    if (totalActivePsiMonitors == 0) {
        return Error() << "No PSI monitors are initialized because all PSI levels are disabled";
    }
    ALOGI("Successfully initialized %d memory PSI monitors", totalActivePsiMonitors);
    return {};
}

void PressureMonitor::destroyActivePsiMonitorsLocked() {
    int totalDestroyedPsiMonitors = 0;
    for (auto& info : mPressureLevels) {
        if (info.psiMonitorFd < 0) {
            continue;
        }
        if (mUnregisterPsiMonitorFunc(mPsiEpollFd, info.psiMonitorFd) < 0) {
            ALOGE("Failed to unregister memory PSI monitor for %s: %s",
                  PressureLevelToString(info.kPressureLevel).c_str(), strerror(errno));
        }
        mDestroyPsiMonitorFunc(info.psiMonitorFd);
        info.psiMonitorFd = -1;
        ++totalDestroyedPsiMonitors;
    }
    if (mPsiEpollFd > 0) {
        close(mPsiEpollFd);
        mPsiEpollFd = -1;
    }
    ALOGI("Destroyed %d memory PSI monitors", totalDestroyedPsiMonitors);
}

Result<void> PressureMonitor::start() {
    {
        Mutex::Autolock lock(mMutex);
        if (!mIsEnabled) {
            return Error() << "Monitor is either disabled or not initialized";
        }
    }
    // TODO(b/335514378): Start the monitor thread here and wait for PSI events.
    return {};
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
