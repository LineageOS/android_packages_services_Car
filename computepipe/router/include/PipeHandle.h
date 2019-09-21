/*
 * Copyright 2019 The Android Open Source Project
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
#ifndef ANDROID_AUTOMOTIVE_COMPUTEPIPE_ROUTER_PIPE_HANDLE
#define ANDROID_AUTOMOTIVE_COMPUTEPIPE_ROUTER_PIPE_HANDLE

#include <memory>
#include <string>
#include <utility>

namespace android {
namespace automotive {
namespace computepipe {
namespace router {

/**
 * This abstracts the runner interface object and hides its
 * details from the inner routing logic.
 */
template <typename T>
class PipeHandle {
  public:
    PipeHandle(const wp<T>& intf) : mInterface(intf) {
    }
    // Check if runner process is still alive
    bool isAlive() {
        sp<T> pRunner = mInterface.promote();
        if (pRunner == nullptr) {
            return false;
        } else {
            return true;
        }
    }
    // Any successful client lookup, clones this handle
    // including the current refcount.
    // The underlying interface refcount remains unchanged
    PipeHandle<T>* clone() const {
        return new PipeHandle(mInterface);
    }
    // Retrieve the underlying remote IPC object
    wp<T> getInterface() {
        return mInterface;
    }

  private:
    // Interface object
    wp<T> mInterface;
};

}  // namespace router
}  // namespace computepipe
}  // namespace automotive
}  // namespace android

#endif
