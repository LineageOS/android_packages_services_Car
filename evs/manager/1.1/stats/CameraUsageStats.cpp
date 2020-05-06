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

#include "CameraUsageStats.h"

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {

using ::android::base::StringAppendF;


void CameraUsageStats::framesReceived(int n) {
    AutoMutex lock(mMutex);
    mStats.framesReceived += n;
}


void CameraUsageStats::framesReturned(int n) {
    AutoMutex lock(mMutex);
    mStats.framesReturned += n;
}


void CameraUsageStats::framesIgnored(int n) {
    AutoMutex lock(mMutex);
    mStats.framesIgnored += n;
}


void CameraUsageStats::framesSkippedToSync(int n) {
    AutoMutex lock(mMutex);
    mStats.framesSkippedToSync += n;
}


void CameraUsageStats::eventsReceived() {
    AutoMutex lock(mMutex);
    ++mStats.erroneousEventsCount;
}


int64_t CameraUsageStats::getTimeCreated() const {
    AutoMutex lock(mMutex);
    return mTimeCreatedMs;
}


int64_t CameraUsageStats::getFramesReceived() const {
    AutoMutex lock(mMutex);
    return mStats.framesReceived;
}


int64_t CameraUsageStats::getFramesReturned() const {
    AutoMutex lock(mMutex);
    return mStats.framesReturned;
}


CameraUsageStatsRecord CameraUsageStats::snapshot() const {
    AutoMutex lock(mMutex);
    return mStats;
}


std::string CameraUsageStats::toString(const CameraUsageStatsRecord& record, const char* indent) {
    return record.toString(indent);
}

} // namespace implementation
} // namespace V1_1
} // namespace evs
} // namespace automotive
} // namespace android

