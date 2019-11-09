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

#include "PipeRunner.h"

#include <mutex>

namespace android {
namespace automotive {
namespace computepipe {
namespace router {
namespace V1_0 {
namespace implementation {

using namespace android::automotive::computepipe::runner;

PipeRunner::PipeRunner(const sp<IPipeRunner>& graphRunner) : runner(graphRunner) {
}

void PipeMonitor::binderDied(const wp<android::IBinder>& /* base */) {
    mNotifier();
}

RunnerHandle::RunnerHandle(const sp<IPipeRunner>& r) : PipeHandle(std::make_unique<PipeRunner>(r)) {
}

bool RunnerHandle::startPipeMonitor() {
    sp<PipeMonitor> monitor = new PipeMonitor([this]() { this->markDead(); });
    // We store a weak reference to be able to perform an unlink
    mPipeMonitor = monitor;
    sp<IBinder> iface = IInterface::asBinder(mInterface->runner);
    return iface->linkToDeath(monitor) == OK;
}

void RunnerHandle::markDead() {
    std::lock_guard<std::mutex> lock(mStateLock);
    mAlive = false;
}

bool RunnerHandle::isAlive() {
    std::lock_guard<std::mutex> lock(mStateLock);
    return mAlive;
}

PipeHandle<PipeRunner>* RunnerHandle::clone() const {
    return new RunnerHandle(mInterface->runner);
}

RunnerHandle::~RunnerHandle() {
    sp<IBinder> iface = IInterface::asBinder(mInterface->runner);
    iface->unlinkToDeath(mPipeMonitor.promote());
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace router
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
