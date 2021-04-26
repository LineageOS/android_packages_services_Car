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

#include "RingBuffer.h"

#include <android-base/logging.h>
#include <android/frameworks/automotive/telemetry/CarData.h>

#include <inttypes.h>  // for PRIu64 and friends

#include <memory>

namespace android {
namespace automotive {
namespace telemetry {

// Do now allow buffering more than this amount of data. It's to make sure we won't get
// 200 thousands of small CarData.
const int kMaxNumberOfItems = 5000;

RingBuffer::RingBuffer(int32_t limit) : mSizeLimitBytes(limit) {}

void RingBuffer::push(BufferedCarData&& data) {
    int32_t dataSizeBytes = data.contentSizeInBytes();
    if (dataSizeBytes > mSizeLimitBytes) {
        LOG(WARNING) << "CarData(id=" << data.mId << ") size (" << dataSizeBytes
                     << "b) is larger than " << mSizeLimitBytes << "b, dropping it.";
        return;
    }
    mCurrentSizeBytes += dataSizeBytes;
    mList.push_back(std::move(data));
    while (mCurrentSizeBytes > mSizeLimitBytes || mList.size() > kMaxNumberOfItems) {
        mCurrentSizeBytes -= mList.front().contentSizeInBytes();
        mList.pop_front();
        mTotalDroppedDataCount += 1;
    }
}

std::vector<BufferedCarData> RingBuffer::popAllDataForId(int32_t id) {
    LOG(VERBOSE) << "popAllDataForId id=" << id;
    std::vector<BufferedCarData> result;
    for (auto it = mList.begin(); it != mList.end();) {
        if (it->mId == id) {
            mCurrentSizeBytes -= (*it).contentSizeInBytes();
            result.push_back(std::move(*it));
            it = mList.erase(it);
        } else {
            ++it;
        }
    }
    return result;
}

void RingBuffer::dump(int fd, int indent) const {
    dprintf(fd, "%*sRingBuffer:\n", indent, "");
    dprintf(fd, "%*s  mSizeLimitBytes=%d\n", indent, "", mSizeLimitBytes);
    dprintf(fd, "%*s  mCurrentSizeBytes=%d\n", indent, "", mCurrentSizeBytes);
    dprintf(fd, "%*s  mList.size=%zu\n", indent, "", mList.size());
    dprintf(fd, "%*s  mTotalDroppedDataCount=%" PRIu64 "\n", indent, "", mTotalDroppedDataCount);
}

int32_t RingBuffer::currentSizeBytes() const {
    return mCurrentSizeBytes;
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
