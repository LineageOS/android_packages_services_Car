/**
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
#ifndef ANDROID_AUTOMOTIVE_COMPUTEPIPE_ROUTER_REGISTRY
#define ANDROID_AUTOMOTIVE_COMPUTEPIPE_ROUTER_REGISTRY

#include <hidl/Status.h>

#include <list>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>

#include "PipeContext.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace router {

enum Error {
    // Operation successful
    OK = 0,
    // Unable to find pipe
    PIPE_NOT_FOUND = -1,
    // Duplicate pipe
    DUPLICATE_PIPE = -2,
    // Runner unavailable
    RUNNER_BUSY = -3,
    // Runner dead
    RUNNER_DEAD = -4,
    // Permission error
    BAD_PERMISSION = -5,
    // Bad args
    BAD_ARGUMENTS = -6,
};

/**
 * PipeRegistry
 *
 * Class that represents the current database of graphs and their associated
 * runners.
 */
template <typename T>
class PipeRegistry {
  public:
    /**
     * Returns the runner for a particular graph
     * If a runner dies, the discovery is made lazily at the point of
     * attempted retrieval by a client, and the correct result is returned.
     */
    std::unique_ptr<PipeHandle<T>> getPipeHandle(const std::string& name) {
        std::lock_guard<std::mutex> lock(mPipeDbLock);
        if (mPipeRunnerDb.find(name) == mPipeRunnerDb.end()) {
            return nullptr;
        }
        if (mPipeRunnerDb[name]->isAvailable()) {
            if (mPipeRunnerDb[name]->isAlive()) {
                mPipeRunnerDb[name]->setAvailability(false);
                return mPipeRunnerDb[name]->dupPipeHandle();
            } else {
                mPipeRunnerDb.erase(name);
                return nullptr;
            }
        }
        return nullptr;
    }
    /**
     * Returns list of registered graphs.
     */
    std::list<std::string> getPipeList();
    /**
     * Registers a graph and the associated runner
     * if a restarted runner attempts to reregister, the existing entry is checked
     * and updated if the old entry is found to be invalid.
     */
    Error RegisterPipe(std::unique_ptr<PipeHandle<T>> h, const std::string& name) {
        std::lock_guard<std::mutex> lock(mPipeDbLock);
        if (mPipeRunnerDb.find(name) == mPipeRunnerDb.end()) {
            mPipeRunnerDb.emplace(
                name, std::unique_ptr<PipeContext<T>>(new PipeContext<T>(std::move(h), name)));
            mPipeRunnerDb[name]->setAvailability(true);
            return OK;
        }
        if (!mPipeRunnerDb[name]->isAlive()) {
            mPipeRunnerDb.erase(name);
            mPipeRunnerDb.emplace(
                name, std::unique_ptr<PipeContext<T>>(new PipeContext<T>(std::move(h), name)));
            mPipeRunnerDb[name]->setAvailability(true);
            return OK;
        }
        return DUPLICATE_PIPE;
    }

    PipeRegistry() = default;

  private:
    // TODO: Add locks
    std::mutex mPipeDbLock;
    std::unordered_map<std::string, std::unique_ptr<PipeContext<T>>> mPipeRunnerDb;
};

template <typename T>
std::list<std::string> PipeRegistry<T>::getPipeList() {
    std::list<std::string> pNames;

    std::lock_guard<std::mutex> lock(mPipeDbLock);
    for (auto const& kv : mPipeRunnerDb) {
        pNames.push_back(kv.first);
    }
    return pNames;
}
}  // namespace router
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
#endif
