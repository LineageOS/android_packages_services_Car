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

#ifndef CPP_WATCHDOG_SERVER_SRC_PRESSUREMONITOR_H_
#define CPP_WATCHDOG_SERVER_SRC_PRESSUREMONITOR_H_

#include <android-base/chrono_utils.h>
#include <android-base/result.h>
#include <psi/psi.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>

#include <unistd.h>

#include <unordered_set>

namespace android {
namespace automotive {
namespace watchdog {

constexpr const char kDefaultProcPressureDirPath[] = "/proc/pressure";
constexpr const char kMemoryFile[] = "memory";

// PSI monitor window over which the PSI thresholds are defined.
constexpr std::chrono::microseconds kPsiWindowSizeUs = 1s;

// PSI stall levels for different PSI levels.
constexpr psi_stall_type kLowPsiStallLevel = PSI_SOME;
constexpr psi_stall_type kMediumPsiStallLevel = PSI_FULL;
constexpr psi_stall_type kHighPsiStallLevel = PSI_FULL;

// Threshold durations for different PSI levels for the above window size.
constexpr std::chrono::microseconds kLowThresholdUs = 15ms;
constexpr std::chrono::microseconds kMediumThresholdUs = 30ms;
constexpr std::chrono::microseconds kHighThresholdUs = 50ms;

// Monitors memory pressure and notifies registered callbacks when the pressure level changes.
class PressureMonitor final : virtual public android::RefBase {
public:
    enum PressureLevel {
        PRESSURE_LEVEL_NONE = 0,
        PRESSURE_LEVEL_LOW,
        PRESSURE_LEVEL_MEDIUM,
        PRESSURE_LEVEL_HIGH,
        PRESSURE_LEVEL_COUNT,
    };

    PressureMonitor() :
          PressureMonitor(kDefaultProcPressureDirPath, &init_psi_monitor, &register_psi_monitor,
                          &unregister_psi_monitor, &destroy_psi_monitor) {}

    // Used by unittest to configure the internal state and mock the outgoing API calls.
    PressureMonitor(const std::string& procPressureDirPath,
                    const std::function<int(enum psi_stall_type, int, int)>& initPsiMonitorFunc,
                    const std::function<int(int, int, void*)>& registerPsiMonitorFunc,
                    const std::function<int(int, int)>& unregisterPsiMonitorFunc,
                    const std::function<void(int)>& destroyPsiMonitorFunc) :
          kProcPressureDirPath(procPressureDirPath),
          mInitPsiMonitorFunc(initPsiMonitorFunc),
          mRegisterPsiMonitorFunc(registerPsiMonitorFunc),
          mUnregisterPsiMonitorFunc(unregisterPsiMonitorFunc),
          mDestroyPsiMonitorFunc(destroyPsiMonitorFunc),
          mIsEnabled(false),
          mPsiEpollFd(-1) {}

    // Initializes the PSI monitors for pressure levels defined in PressureLevel enum.
    android::base::Result<void> init();

    // Terminates the active PSI monitors and joins the pressure monitor thread.
    void terminate();

    // Returns true when the pressure monitor is enabled.
    bool isEnabled() {
        Mutex::Autolock lock(mMutex);
        return mIsEnabled;
    }

    // Starts the pressure monitor thread, which listens for PSI events and notifies clients on
    // pressure changes.
    android::base::Result<void> start();

private:
    // Contains information about a pressure level.
    struct PressureLevelInfo {
        const PressureLevel kPressureLevel = PRESSURE_LEVEL_NONE;
        const psi_stall_type kStallType = PSI_TYPE_COUNT;
        const std::chrono::microseconds kThresholdUs = 0us;
        int psiMonitorFd = -1;
    };

    // Returns the string value for the given pressure level.
    std::string PressureLevelToString(PressureLevel pressureLevel);

    // Initializes the PSI monitors for different pressure levels.
    android::base::Result<void> initializePsiMonitorsLocked();

    // Destroys active PSI monitors.
    void destroyActivePsiMonitorsLocked();

    // Proc pressure directory path.
    const std::string kProcPressureDirPath;

    // Updated by test to mock the PSI interfaces.
    std::function<int(enum psi_stall_type, int, int)> mInitPsiMonitorFunc;
    std::function<int(int, int, void*)> mRegisterPsiMonitorFunc;
    std::function<int(int, int)> mUnregisterPsiMonitorFunc;
    std::function<void(int)> mDestroyPsiMonitorFunc;

    // Lock to guard internal state against multi-threaded access.
    mutable Mutex mMutex;

    // Set to true only when the required Kernel interfaces are accessible.
    bool mIsEnabled GUARDED_BY(mMutex);

    // Epoll fd used to monitor the psi triggers.
    int mPsiEpollFd GUARDED_BY(mMutex);

    // Cache of supported pressure level info.
    std::vector<PressureLevelInfo> mPressureLevels GUARDED_BY(mMutex);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_PRESSUREMONITOR_H_
