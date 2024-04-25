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
#include <processgroup/sched_policy.h>

#include <errno.h>
#include <string.h>
#include <sys/epoll.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::sp;
using ::android::base::Error;
using ::android::base::Result;
using ::android::base::StringPrintf;

constexpr const char kThreadName[] = "PressureMonitor";

std::string PressureMonitorInterface::PressureLevelToString(PressureLevel pressureLevel) {
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
    {
        Mutex::Autolock lock(mMutex);
        mIsMonitorActive = false;
        mHandlerLooper->removeMessages(sp<PressureMonitor>::fromExisting(this));
        mHandlerLooper->wake();
    }
    if (mMonitorThread.joinable()) {
        mMonitorThread.join();
    }
    {
        Mutex::Autolock lock(mMutex);
        destroyActivePsiMonitorsLocked();
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
                                     kPsiWindowSizeUs.count(), PSI_MEMORY);
        if (fd < 0) {
            return Error() << "Failed to initialize memory PSI monitor for "
                           << PressureLevelToString(info.kPressureLevel) << ": " << strerror(errno);
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
        if (mMonitorThread.joinable()) {
            return Error()
                    << "Pressure monitoring is already in progress. So skipping this request";
        }
        mIsMonitorActive = true;
    }
    mMonitorThread = std::thread([&]() {
        if (set_sched_policy(0, SP_BACKGROUND) != 0) {
            ALOGW("Failed to set background scheduling priority to %s thread", kThreadName);
        }
        if (int result = pthread_setname_np(pthread_self(), kThreadName); result != 0) {
            ALOGW("Failed to set %s thread name: %d", kThreadName, result);
        }
        bool isMonitorActive;
        {
            Mutex::Autolock lock(mMutex);
            mHandlerLooper->setLooper(Looper::prepare(/*opts=*/0));
            mLastPollUptimeNs = mHandlerLooper->now();
            mHandlerLooper->sendMessage(sp<PressureMonitor>::fromExisting(this),
                                        LooperMessage::MONITOR_PRESSURE);
            isMonitorActive = mIsMonitorActive;
        }
        ALOGI("Starting pressure monitor");
        while (isMonitorActive) {
            mHandlerLooper->pollAll(/*timeoutMillis=*/-1);
            Mutex::Autolock lock(mMutex);
            isMonitorActive = mIsMonitorActive;
        }
    });
    return {};
}

Result<void> PressureMonitor::registerPressureChangeCallback(
        sp<PressureChangeCallbackInterface> callback) {
    Mutex::Autolock lock(mMutex);
    if (mPressureChangeCallbacks.find(callback) != mPressureChangeCallbacks.end()) {
        return Error() << "Callback is already registered";
    }
    mPressureChangeCallbacks.insert(callback);
    return {};
}

void PressureMonitor::unregisterPressureChangeCallback(
        sp<PressureChangeCallbackInterface> callback) {
    Mutex::Autolock lock(mMutex);
    const auto& it = mPressureChangeCallbacks.find(callback);
    if (it == mPressureChangeCallbacks.end()) {
        ALOGE("Pressure change callback is not registered. Skipping unregister request");
        return;
    }
    mPressureChangeCallbacks.erase(it);
}

void PressureMonitor::handleMessage(const Message& message) {
    Result<void> result;
    switch (message.what) {
        case LooperMessage::MONITOR_PRESSURE:
            if (const auto& monitorResult = monitorPressure(); !monitorResult.ok()) {
                result = Error() << "Failed to monitor pressure: " << monitorResult.error();
            }
            break;
        case LooperMessage::NOTIFY_PRESSURE_CHANGE:
            notifyPressureChange();
            break;
        default:
            ALOGE("Skipping unknown pressure monitor message: %d", message.what);
    }
    if (!result.ok()) {
        ALOGE("Terminating pressure monitor: %s", result.error().message().c_str());
        Mutex::Autolock lock(mMutex);
        mIsMonitorActive = false;
    }
}

Result<void> PressureMonitor::monitorPressure() {
    size_t maxEvents;
    int psiEpollFd;
    {
        Mutex::Autolock lock(mMutex);
        psiEpollFd = mPsiEpollFd;
        maxEvents = mPressureLevels.size();
    }
    if (psiEpollFd < 0) {
        return Error() << "Memory pressure monitor is not initialized";
    }
    struct epoll_event* events = new epoll_event[maxEvents];
    auto result = waitForLatestPressureLevel(psiEpollFd, events, maxEvents);
    if (!result.ok()) {
        delete[] events;
        return Error() << "Failed to get the latest pressure level: " << result.error();
    }
    delete[] events;

    Mutex::Autolock lock(mMutex);
    if (mLatestPressureLevel != *result) {
        mLatestPressureLevel = *result;
        mHandlerLooper->sendMessage(sp<PressureMonitor>::fromExisting(this),
                                    LooperMessage::NOTIFY_PRESSURE_CHANGE);
    }

    mLastPollUptimeNs +=
            std::chrono::duration_cast<std::chrono::nanoseconds>(mPollingIntervalMillis).count();
    // The NOTIFY_PRESSURE_CHANGE message must be handled before MONITOR_PRESSURE message.
    // Otherwise, the callbacks won't be notified of the recent pressure level change. To avoid
    // inserting MONITOR_PRESSURE message before NOTIFY_PRESSURE_CHANGE message, check the uptime.
    nsecs_t now = mHandlerLooper->now();
    mHandlerLooper->sendMessageAtTime(mLastPollUptimeNs > now ? mLastPollUptimeNs : now,
                                      sp<PressureMonitor>::fromExisting(this),
                                      LooperMessage::MONITOR_PRESSURE);
    return {};
}

Result<PressureMonitor::PressureLevel> PressureMonitor::waitForLatestPressureLevel(
        int psiEpollFd, epoll_event* events, size_t maxEvents) {
    PressureLevel highestActivePressure;
    {
        Mutex::Autolock lock(mMutex);
        highestActivePressure = mLatestPressureLevel;
    }
    int totalActiveEvents;
    do {
        if (highestActivePressure == PRESSURE_LEVEL_NONE) {
            // When the recent pressure level was none, wait with no timeout until the pressure
            // increases.
            totalActiveEvents = mEpollWaitFunc(psiEpollFd, events, maxEvents, /*timeout=*/-1);
        } else {
            // When the recent pressure level was high, assume that the pressure will stay high
            // for at least 1 second. Within 1 second window, the memory pressure state can go up
            // causing an event to trigger or it can go down when the window expires.

            // TODO(b/333411972): Review whether 1 second wait is sufficient and whether an event
            //  will trigger if the memory pressure continues to stay higher for more than this
            //  period.
            totalActiveEvents =
                    mEpollWaitFunc(psiEpollFd, events, maxEvents, mPollingIntervalMillis.count());
            if (totalActiveEvents == 0) {
                return PRESSURE_LEVEL_NONE;
            }
        }
        // Keep waiting if interrupted.
    } while (totalActiveEvents == -1 && errno == EINTR);

    if (totalActiveEvents == -1) {
        return Error() << "epoll_wait failed while waiting for PSI events: " << strerror(errno);
    }
    // Reset and identify the recent highest active pressure from the PSI events.
    highestActivePressure = PRESSURE_LEVEL_NONE;

    for (int i = 0; i < totalActiveEvents; i++) {
        if (events[i].events & (EPOLLERR | EPOLLHUP)) {
            // Should never happen unless psi got disabled in the Kernel.
            return Error() << "Memory pressure events are not available anymore";
        }
        if (events[i].data.u32 > highestActivePressure) {
            highestActivePressure = static_cast<PressureLevel>(events[i].data.u32);
        }
    }
    return highestActivePressure;
}

void PressureMonitor::notifyPressureChange() {
    PressureLevel pressureLevel;
    std::unordered_set<sp<PressureChangeCallbackInterface>, SpHash<PressureChangeCallbackInterface>>
            callbacks;
    {
        Mutex::Autolock lock(mMutex);
        pressureLevel = mLatestPressureLevel;
        callbacks = mPressureChangeCallbacks;
    }
    if (DEBUG) {
        ALOGD("Sending pressure change notification to %zu callbacks", callbacks.size());
    }
    for (const sp<PressureChangeCallbackInterface>& callback : callbacks) {
        callback->onPressureChanged(pressureLevel);
    }
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
