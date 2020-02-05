/**
 * Copyright (c) 2020, The Android Open Source Project
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

#define LOG_TAG "carwatchdogd"
#define DEBUG false

#include "IoPerfCollection.h"

#include <log/log.h>

namespace android {
namespace automotive {
namespace watchdog {

using android::base::Error;
using android::base::Errorf;
using android::base::Result;

Result<void> IoPerfCollection::start() {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != CollectionEvent::NONE) {
        return Error() << "Cannot start I/O performance collection more than once";
    }
    mCurrCollectionEvent = CollectionEvent::BOOT_TIME;

    // TODO(b/148486340): Implement this method.
    return Error() << "Unimplemented method";
}

Result<void> IoPerfCollection::onBootFinished() {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != CollectionEvent::BOOT_TIME) {
        return Error() << "Current collection event " << toEventString(mCurrCollectionEvent)
                       << " != " << toEventString(CollectionEvent::BOOT_TIME)
                       << " collection event";
    }

    // TODO(b/148486340): Implement this method.
    return Error() << "Unimplemented method";
}

status_t IoPerfCollection::dump(int /*fd*/) {
    Mutex::Autolock lock(mMutex);

    // TODO(b/148486340): Implement this method.
    return INVALID_OPERATION;
}

status_t IoPerfCollection::startCustomCollection(std::chrono::seconds /*interval*/,
                                                 std::chrono::seconds /*maxDuration*/) {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != CollectionEvent::PERIODIC) {
        ALOGE(
            "Cannot start a custom collection when "
            "the current collection event %s != %s collection event",
            toEventString(mCurrCollectionEvent).c_str(),
            toEventString(CollectionEvent::PERIODIC).c_str());
        return INVALID_OPERATION;
    }

    // TODO(b/148486340): Implement this method.
    return INVALID_OPERATION;
}

status_t IoPerfCollection::endCustomCollection(int /*fd*/) {
    Mutex::Autolock lock(mMutex);
    if (mCurrCollectionEvent != CollectionEvent::CUSTOM) {
        ALOGE("No custom collection is running");
        return INVALID_OPERATION;
    }

    // TODO(b/148486340): Implement this method.
    return INVALID_OPERATION;
}

Result<void> IoPerfCollection::collect() {
    Mutex::Autolock lock(mMutex);

    // TODO(b/148486340): Implement this method.
    return Error() << "Unimplemented method";
}

Result<void> IoPerfCollection::collectUidIoPerfDataLocked() {
    // TODO(b/148486340): Implement this method.
    return Error() << "Unimplemented method";
}

Result<void> IoPerfCollection::collectSystemIoPerfDataLocked() {
    // TODO(b/148486340): Implement this method.
    return Error() << "Unimplemented method";
}

Result<void> IoPerfCollection::collectProcessIoPerfDataLocked() {
    // TODO(b/148486340): Implement this method.
    return Error() << "Unimplemented method";
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
