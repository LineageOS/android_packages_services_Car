// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#ifndef COMPUTEPIPE_RUNNER_INPUT_FRAME
#define COMPUTEPIPE_RUNNER_INPUT_FRAME

#include <cstdint>
#include <functional>

#include "types/Status.h"
namespace android {
namespace automotive {
namespace computepipe {
namespace runner {

typedef std::function<void(uint8_t[])> FrameDeleter;
/**
 * Information about the input frame
 */
struct FrameInfo {
    uint32_t height;
    uint32_t width;
    PixelFormat format;
    uint32_t stride;
    int cameraId;
};

/**
 * Wrapper around the pixel data of the input frame
 */
struct InputFrame {
  public:
    /**
     * Take info about frame data, and ownership of pixel data
     */
    explicit InputFrame(uint32_t height, uint32_t width, PixelFormat format, uint32_t stride,
                        uint8_t* ptr, FrameDeleter del = std::default_delete<uint8_t[]>()) {
        mInfo.height = height;
        mInfo.width = width;
        mInfo.format = format;
        mInfo.stride = stride;
        mDataPtr = {ptr, del};
    }
    InputFrame(InputFrame&& f) {
        *this = std::move(f);
    };
    InputFrame& operator=(InputFrame&& f) {
        mInfo.height = f.mInfo.height;
        mInfo.width = f.mInfo.width;
        mInfo.format = f.mInfo.format;
        mInfo.stride = f.mInfo.stride;
        mInfo.cameraId = f.mInfo.cameraId;
        mDataPtr = std::move(f.mDataPtr);
        f.mInfo.format = PIXELFORMAT_MAX;
        f.mInfo.width = 0;
        f.mInfo.height = 0;
        f.mInfo.stride = 0;
        f.mInfo.cameraId = -1;
        return *this;
    }
    /**
     * This is an unsafe method, that a consumer should use to copy the
     * underlying frame data
     */
    const uint8_t* getFramePtr() const;
    FrameInfo getFrameInfo() const {
        return mInfo;
    }
    /**
     * Delete evil constructors
     */
    InputFrame() = delete;
    InputFrame(const InputFrame&) = delete;
    InputFrame& operator=(const InputFrame& f) = delete;

    ~InputFrame() {
        if (mDataPtr) {
            mDataPtr = nullptr;
        }
    }

  private:
    FrameInfo mInfo;
    FrameDeleter mDeleter;
    std::unique_ptr<uint8_t[], FrameDeleter> mDataPtr;
};

}  // namespace runner
}  // namespace computepipe
}  // namespace automotive
}  // namespace android

#endif
