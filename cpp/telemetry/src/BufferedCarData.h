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

#ifndef CPP_TELEMETRY_SRC_BUFFEREDCARDATA_H_
#define CPP_TELEMETRY_SRC_BUFFEREDCARDATA_H_

#include <android/frameworks/automotive/telemetry/CarData.h>

namespace android {
namespace automotive {
namespace telemetry {

struct BufferedCarData {
    BufferedCarData(const android::frameworks::automotive::telemetry::CarData& data, int32_t uid) :
          mId(data.id),
          mContent(std::move(data.content)),
          mLogUid(uid) {}

    // Visible for testing.
    BufferedCarData(int32_t id, const std::vector<uint8_t>& content, int32_t uid) :
          mId(id),
          mContent(std::move(content)),
          mLogUid(uid) {}

    inline bool operator==(const BufferedCarData& rhs) const {
        return std::tie(mId, mContent, mLogUid) == std::tie(rhs.mId, rhs.mContent, rhs.mLogUid);
    }

    // Returns the size of the stored data. Note that it's not the exact size of the struct.
    int32_t contentSizeInBytes() const { return mContent.size(); }

    const int32_t mId;
    const std::vector<uint8_t> mContent;

    // The uid of the logging client (defaults to -1).
    const int32_t mLogUid;
};

}  // namespace telemetry
}  // namespace automotive
}  // namespace android

#endif  // CPP_TELEMETRY_SRC_BUFFEREDCARDATA_H_
