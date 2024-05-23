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

#include "LooperWrapper.h"

#include <android-base/chrono_utils.h>
#include <android-base/result.h>
#include <psi/psi.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>

#include <unistd.h>

#include <thread>  // NOLINT(build/c++11)
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

// Time between consecutive polling of pressure events.
constexpr std::chrono::milliseconds kPollingIntervalMillis = 1s;

class PressureMonitorInterface : virtual public android::RefBase {
public:
    enum PressureLevel {
        PRESSURE_LEVEL_NONE = 0,
        PRESSURE_LEVEL_LOW,
        PRESSURE_LEVEL_MEDIUM,
        PRESSURE_LEVEL_HIGH,
        PRESSURE_LEVEL_COUNT,
    };

    // Clients implement and register this callback to get notified on pressure changes.
    class PressureChangeCallbackInterface : virtual public android::RefBase {
    public:
        virtual ~PressureChangeCallbackInterface() {}

        // Called when the memory pressure level is changed.
        virtual void onPressureChanged(PressureLevel pressureLevel) = 0;
    };

    // Initializes the PSI monitors for pressure levels defined in PressureLevel enum.
    virtual android::base::Result<void> init() = 0;

    // Terminates the active PSI monitors and joins the pressure monitor thread.
    virtual void terminate() = 0;

    // Returns true when the pressure monitor is enabled.
    virtual bool isEnabled() = 0;

    // Starts the pressure monitor thread, which listens for PSI events and notifies clients on
    // pressure changes.
    virtual android::base::Result<void> start() = 0;

    // Registers a callback for pressure change notifications.
    virtual android::base::Result<void> registerPressureChangeCallback(
            android::sp<PressureChangeCallbackInterface> callback) = 0;

    // Unregisters a previously registered pressure change callback.
    virtual void unregisterPressureChangeCallback(
            android::sp<PressureChangeCallbackInterface> callback) = 0;

    // Returns the string value for the given pressure level.
    static std::string PressureLevelToString(PressureLevel pressureLevel);
};

// Monitors memory pressure and notifies registered callbacks when the pressure level changes.
class PressureMonitor final :
      public PressureMonitorInterface,
      virtual public android::MessageHandler {
public:
    PressureMonitor() :
          PressureMonitor(kDefaultProcPressureDirPath, kPollingIntervalMillis, &init_psi_monitor,
                          &register_psi_monitor, &unregister_psi_monitor, &destroy_psi_monitor,
                          &epoll_wait) {}

    // Used by unittest to configure the internal state and mock the outgoing API calls.
    PressureMonitor(const std::string& procPressureDirPath,

                    std::chrono::milliseconds pollingIntervalMillis,
                    const std::function<int(enum psi_stall_type, int, int, enum psi_resource)>&
                            initPsiMonitorFunc,
                    const std::function<int(int, int, void*)>& registerPsiMonitorFunc,
                    const std::function<int(int, int)>& unregisterPsiMonitorFunc,
                    const std::function<void(int)>& destroyPsiMonitorFunc,
                    const std::function<int(int, epoll_event*, int, int)>& epollWaitFunc) :
          kProcPressureDirPath(procPressureDirPath),
          mPollingIntervalMillis(pollingIntervalMillis),
          mInitPsiMonitorFunc(initPsiMonitorFunc),
          mRegisterPsiMonitorFunc(registerPsiMonitorFunc),
          mUnregisterPsiMonitorFunc(unregisterPsiMonitorFunc),
          mDestroyPsiMonitorFunc(destroyPsiMonitorFunc),
          mEpollWaitFunc(epollWaitFunc),
          mHandlerLooper(android::sp<LooperWrapper>::make()),
          mIsEnabled(false),
          mIsMonitorActive(false),
          mPsiEpollFd(-1),
          mLastPollUptimeNs(0),
          mLatestPressureLevel(PRESSURE_LEVEL_NONE) {}

    // Overrides PressureMonitorInterface methods.
    android::base::Result<void> init() override;

    void terminate() override;

    bool isEnabled() override {
        Mutex::Autolock lock(mMutex);
        return mIsEnabled;
    }

    android::base::Result<void> start() override;

    android::base::Result<void> registerPressureChangeCallback(
            android::sp<PressureChangeCallbackInterface> callback) override;

    void unregisterPressureChangeCallback(
            android::sp<PressureChangeCallbackInterface> callback) override;

    // Returns true when the pressure monitor thread is active.
    bool isMonitorActive() { return mIsMonitorActive; }

private:
    template <typename T>
    struct SpHash {
        size_t operator()(const sp<T>& k) const { return std::hash<T*>()(k.get()); }
    };
    // Looper messages to post / handle pressure monitor events.
    enum LooperMessage {
        MONITOR_PRESSURE = 0,
        NOTIFY_PRESSURE_CHANGE,
        LOOPER_MESSAGE_COUNT,
    };
    // Contains information about a pressure level.
    struct PressureLevelInfo {
        const PressureLevel kPressureLevel = PRESSURE_LEVEL_NONE;
        const psi_stall_type kStallType = PSI_TYPE_COUNT;
        const std::chrono::microseconds kThresholdUs = 0us;
        int psiMonitorFd = -1;
    };

    // Initializes the PSI monitors for different pressure levels.
    android::base::Result<void> initializePsiMonitorsLocked();

    // Destroys active PSI monitors.
    void destroyActivePsiMonitorsLocked();

    // Monitors current pressure levels.
    android::base::Result<void> monitorPressure();

    // Waits for the latest PSI events and returns the latest pressure level.
    android::base::Result<PressureLevel> waitForLatestPressureLevel(int psiEpollFd,
                                                                    epoll_event* events,
                                                                    size_t maxEvents);

    // Handles the looper messages.
    void handleMessage(const Message& message);

    // Notifies the clients of the latest pressure level changes.
    void notifyPressureChange();

    // Proc pressure directory path.
    const std::string kProcPressureDirPath;

    // Thread that waits for PSI triggers and notifies the pressure changes.
    std::thread mMonitorThread;

    // Time between consecutive polling of pressure events. Also used for the epoll_wait timeout.
    std::chrono::milliseconds mPollingIntervalMillis;

    // Updated by test to mock the PSI interfaces.
    std::function<int(enum psi_stall_type, int, int, enum psi_resource)> mInitPsiMonitorFunc;
    std::function<int(int, int, void*)> mRegisterPsiMonitorFunc;
    std::function<int(int, int)> mUnregisterPsiMonitorFunc;
    std::function<void(int)> mDestroyPsiMonitorFunc;
    std::function<int(int, epoll_event*, int, int)> mEpollWaitFunc;

    // Lock to guard internal state against multi-threaded access.
    mutable Mutex mMutex;

    // Handler looper to monitor pressure and notify callbacks.
    android::sp<LooperWrapper> mHandlerLooper GUARDED_BY(mMutex);

    // Set to true only when the required Kernel interfaces are accessible.
    bool mIsEnabled GUARDED_BY(mMutex);

    // Indicates whether or not the pressure monitor should continue monitoring.
    bool mIsMonitorActive GUARDED_BY(mMutex);

    // Epoll fd used to monitor the psi triggers.
    int mPsiEpollFd GUARDED_BY(mMutex);

    // Uptime NS when the last poll was performed. Used to calculate the next poll uptime.
    nsecs_t mLastPollUptimeNs GUARDED_BY(mMutex);

    // Latest highest active pressure level since the previous polling.
    PressureLevel mLatestPressureLevel GUARDED_BY(mMutex);

    // Cache of supported pressure level info.
    std::vector<PressureLevelInfo> mPressureLevels GUARDED_BY(mMutex);

    // Callbacks to notify when the pressure level changes.
    std::unordered_set<android::sp<PressureChangeCallbackInterface>,
                       SpHash<PressureChangeCallbackInterface>>
            mPressureChangeCallbacks GUARDED_BY(mMutex);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_SRC_PRESSUREMONITOR_H_
