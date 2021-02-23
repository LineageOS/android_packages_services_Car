/*
 * Copyright (c) 2021, The Android Open Source Project
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

#define LOG_TAG "carpowerpolicyd"

#include "SilentModeHandler.h"

#include "CarPowerPolicyServer.h"

#include <android-base/file.h>
#include <android-base/properties.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>

#include <sys/inotify.h>
#include <sys/stat.h>
#include <unistd.h>

namespace android {
namespace frameworks {
namespace automotive {
namespace powerpolicy {

using ::android::Mutex;
using ::android::base::Error;
using ::android::base::GetProperty;
using ::android::base::ReadFileToString;
using ::android::base::Result;
using ::android::base::StringPrintf;
using ::android::base::Trim;
using ::android::base::unique_fd;
using ::android::base::WriteStringToFd;

namespace {

constexpr const char* kPropertySystemBootReason = "sys.boot.reason";
constexpr const char* kSilentModeHwStateFilename = "/sys/power/pm_silentmode_hw_state";
constexpr const char* kKernelSilentModeFilename = "/sys/power/pm_silentmode_kernel";
constexpr int kEventBufferSize = 512;

bool fileExists(const char* filename) {
    struct stat buffer;
    return stat(filename, &buffer) == 0;
}

}  // namespace

SilentModeHandler::SilentModeHandler(ICarPowerPolicyServerInterface* server) :
      mSilentModeByHwState(false),
      mSilentModeHwStateFilename(kSilentModeHwStateFilename),
      mKernelSilentModeFilename(kKernelSilentModeFilename),
      mPolicyServer(server),
      mFdInotify(-1) {
    mBootReason = GetProperty(kPropertySystemBootReason, "");
}

void SilentModeHandler::init() {
    if (mBootReason == kBootReasonForcedSilent) {
        mForcedMode = true;
        mSilentModeByHwState = true;
    } else if (mBootReason == kBootReasonForcedNonSilent) {
        mForcedMode = true;
        mSilentModeByHwState = false;
    }
    if (mForcedMode) {
        if (auto ret = updateKernelSilentModeInternal(mSilentModeByHwState); !ret.ok()) {
            ALOGW("Failed to update kernel silent mode: %s", ret.error().message().c_str());
        }
        mPolicyServer->notifySilentModeChange(mSilentModeByHwState);
        ALOGI("Now in forced mode: monitoring %s is disabled", kSilentModeHwStateFilename);
    } else {
        startMonitoringSilentModeHwState();
    }
}

void SilentModeHandler::release() {
    stopMonitoringSilentModeHwState();
}

bool SilentModeHandler::isSilentMode() {
    Mutex::Autolock lock(mMutex);
    return mSilentModeByHwState;
}

Result<void> SilentModeHandler::updateKernelSilentMode(bool silent) {
    if (mForcedMode) {
        return Error() << "Cannot update " << mKernelSilentModeFilename << " in forced mode";
    }
    return updateKernelSilentModeInternal(silent);
}

Result<void> SilentModeHandler::updateKernelSilentModeInternal(bool silent) {
    int fd = open(mKernelSilentModeFilename.c_str(), O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        return Error() << "Failed to open " << mKernelSilentModeFilename;
    }
    Result<void> status = {};
    if (const auto& value = silent ? kValueSilentMode : kValueNonSilentMode;
        !WriteStringToFd(value, fd)) {
        status = Error() << "Failed to write " << value << " to fd " << fd;
    }
    close(fd);
    return status;
}

void SilentModeHandler::stopMonitoringSilentModeHwState() {
    if (mIsMonitoring) {
        mIsMonitoring = false;
        inotify_rm_watch(mFdInotify, mWdSilentModeHwState);
        mWdSilentModeHwState = -1;
    }
    mFdInotify.reset(-1);
}

Result<void> SilentModeHandler::dump(int fd, const Vector<String16>& /*args*/) {
    const char* indent = "  ";
    WriteStringToFd(StringPrintf("%sMonitoring HW state: %s", indent,
                                 mIsMonitoring ? "true" : "false"),
                    fd);
    WriteStringToFd(StringPrintf("%sForced silent mode: %s", indent,
                                 mForcedMode ? "true" : "false"),
                    fd);
    if (mIsMonitoring) {
        Mutex::Autolock lock(mMutex);
        WriteStringToFd(StringPrintf("%sSilent mode by HW state: %s", indent,
                                     mSilentModeByHwState ? "silent" : "non-silent"),
                        fd);
    }
    return {};
}

void SilentModeHandler::startMonitoringSilentModeHwState() {
    if (mIsMonitoring) {
        ALOGW("Silent Mode monitoring is already started");
        return;
    }
    if (mFdInotify < 0) {
        mFdInotify.reset(inotify_init1(IN_CLOEXEC));
        if (mFdInotify < 0) {
            ALOGE("Failed to start monitoring Silent Mode HW state: creating inotify instance "
                  "failed (errno = %d)",
                  errno);
            return;
        }
    }
    const char* filename = mSilentModeHwStateFilename.c_str();
    if (!fileExists(filename)) {
        ALOGW("Failed to start monitoring Silent Mode HW state: %s doesn't exist", filename);
        mFdInotify.reset(-1);
        return;
    }
    // TODO(b/178843534): Additional masks might be needed to detect sysfs change.
    const uint32_t masks = IN_MODIFY;
    mWdSilentModeHwState = inotify_add_watch(mFdInotify, filename, masks);
    mIsMonitoring = true;
    mSilentModeMonitoringThread = std::thread([this]() {
        char eventBuf[kEventBufferSize];
        struct inotify_event* event;
        constexpr size_t inotifyEventSize = sizeof(*event);
        ALOGI("Monitoring %s started", mSilentModeHwStateFilename.c_str());
        while (mIsMonitoring) {
            int eventPos = 0;
            int numBytes = read(mFdInotify, eventBuf, sizeof(eventBuf));
            if (numBytes < static_cast<int>(inotifyEventSize)) {
                if (errno == EINTR) {
                    ALOGW("System call interrupted. Wait for inotify event again.");
                    continue;
                }
                mIsMonitoring = false;
                inotify_rm_watch(mFdInotify, mWdSilentModeHwState);
                mWdSilentModeHwState = -1;
                mFdInotify.reset(-1);
                ALOGW("Failed to wait for change at %s (errno = %d)",
                      mSilentModeHwStateFilename.c_str(), errno);
                return;
            }
            while (numBytes >= static_cast<int>(inotifyEventSize)) {
                int eventSize;
                event = (struct inotify_event*)(eventBuf + eventPos);
                if (event->wd == mWdSilentModeHwState && (event->mask & masks)) {
                    handleSilentModeHwStateChange();
                }
                eventSize = inotifyEventSize + event->len;
                numBytes -= eventSize;
                eventPos += eventSize;
            }
        }
        ALOGI("Monitoring %s ended", mSilentModeHwStateFilename.c_str());
    });
}

void SilentModeHandler::handleSilentModeHwStateChange() {
    if (!mIsMonitoring) {
        return;
    }
    std::string buf;
    if (!ReadFileToString(mSilentModeHwStateFilename.c_str(), &buf)) {
        ALOGW("Failed to read %s", mSilentModeHwStateFilename.c_str());
        return;
    }
    bool newSilentMode;
    bool oldSilentMode;
    {
        Mutex::Autolock lock(mMutex);
        oldSilentMode = std::exchange(mSilentModeByHwState, Trim(buf) == kValueSilentMode);
        newSilentMode = mSilentModeByHwState;
    }
    if (newSilentMode != oldSilentMode) {
        ALOGI("%s is set to %s", mSilentModeHwStateFilename.c_str(),
              newSilentMode ? "silent" : "non-silent");
        mPolicyServer->notifySilentModeChange(newSilentMode);
    }
}

}  // namespace powerpolicy
}  // namespace automotive
}  // namespace frameworks
}  // namespace android
