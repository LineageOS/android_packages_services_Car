/*
 * Copyright (C) 2021 The Android Open Source Project
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

#ifndef CPP_CAR_BINDER_LIB_LARGEPARCELABLE_INCLUDE_SHAREDMEMORY_H_
#define CPP_CAR_BINDER_LIB_LARGEPARCELABLE_INCLUDE_SHAREDMEMORY_H_

#include "MappedFile.h"

#include <android-base/unique_fd.h>
#include <utils/Errors.h>

#include <unistd.h>

#include <memory>

namespace android {
namespace automotive {
namespace car_binder_lib {

using ::android::status_t;
using ::android::base::unique_fd;

// SharedMemory represents a shared memory file object.
class SharedMemory {
public:
    // Initialize the shared memory object with the file descriptor to a shared memory file. The fd
    // is owned by this class. Caller should use isValid() to check whether the initialization
    // succeed and use getErr() to get error if isValid() is not true.
    explicit SharedMemory(unique_fd fd);

    // Create a shared memory object with 'size'. Caller should use isValid() to check whether the
    // initialization succeed and use getErr() to get error if isValid() is not true.
    explicit SharedMemory(size_t size);

    inline bool isValid() const { return mFd.ok(); }

    inline size_t getSize() const { return mSize; }

    inline status_t getErr() const { return -mErrno; }

    inline int getFd() const { return mFd.get(); }

    inline unique_fd getDupFd() const {
        unique_fd fd(dup(mFd.get()));
        return std::move(fd);
    }

    inline std::unique_ptr<MappedFile> mapReadWrite() const {
        assert(!mLocked);
        bool writtable = true;
        return std::unique_ptr<MappedFile>(new MappedFile(mFd.get(), mSize, writtable));
    }

    inline std::unique_ptr<MappedFile> mapReadOnly() const {
        bool writtable = false;
        return std::unique_ptr<MappedFile>(new MappedFile(mFd.get(), mSize, writtable));
    }

    status_t lock();

private:
    unique_fd mFd;
    int mErrno = 0;
    bool mLocked = false;
    size_t mSize = 0;
};

}  // namespace car_binder_lib
}  // namespace automotive
}  // namespace android

#endif  // CPP_CAR_BINDER_LIB_LARGEPARCELABLE_INCLUDE_SHAREDMEMORY_H_
