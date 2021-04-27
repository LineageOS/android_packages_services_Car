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

#ifndef CPP_TELEMETRY_SRC_RINGBUFFER_H_
#define CPP_TELEMETRY_SRC_RINGBUFFER_H_

#include "BufferedCarData.h"

#include <list>

namespace android {
namespace automotive {
namespace telemetry {

// A ring buffer that holds BufferedCarData. It drops old data if it's full.
// Not thread-safe.
// TODO(b/182608968): make it thread-safe
class RingBuffer {
public:
    // RingBuffer limits `currentSizeBytes()` to the given param `sizeLimitBytes`.
    // There is also a hard limit on number of items, it's expected that reader clients will
    // fetch all the data before the buffer gets full.
    // TODO(b/182608968): Only limit the size using count, and restructure the methods to match
    //                    the new internal API.
    explicit RingBuffer(int32_t sizeLimitBytes);

    // Pushes the data to the buffer. If the buffer is full, it removes the oldest data.
    // Supports moving the data to the RingBuffer.
    void push(BufferedCarData&& data);

    // Returns all the CarData with the given `id` and removes them from the buffer.
    // Complexity is O(n), as this method is expected to be called infrequently.
    std::vector<BufferedCarData> popAllDataForId(int32_t id);

    // Dumps the current state for dumpsys.
    void dump(int fd) const;

    // Returns the total size of CarData content in the buffer.
    int32_t currentSizeBytes() const;

private:
    const int32_t mSizeLimitBytes;
    int32_t mCurrentSizeBytes;

    int64_t mTotalDroppedDataCount;

    // Linked list that holds all the data and allows deleting old data when the buffer is full.
    std::list<BufferedCarData> mList;
};

}  // namespace telemetry
}  // namespace automotive
}  // namespace android

#endif  // CPP_TELEMETRY_SRC_RINGBUFFER_H_
