/*
 * Copyright 2021 The Android Open Source Project
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
#ifndef ANDROID_CARSERVICE_EVS_SERVICE_WRAPPER_H
#define ANDROID_CARSERVICE_EVS_SERVICE_WRAPPER_H

#include "EvsDeathRecipient.h"
#include "StreamHandler.h"

#include <android/hardware/automotive/evs/1.1/IEvsDisplay.h>
#include <android/hardware/automotive/evs/1.1/IEvsEnumerator.h>
#include <android/hardware/automotive/evs/1.1/types.h>
#include <hidl/HidlTransportSupport.h>
#include <utils/StrongPointer.h>

#include <mutex>

namespace android {
namespace automotive {
namespace evs {

using DeathCb = std::function<void(const wp<hidl::base::V1_0::IBase>&)>;
using FrameCb = std::function<void(::android::hardware::automotive::evs::V1_1::BufferDesc)>;
using EventCb = std::function<void(::android::hardware::automotive::evs::V1_1::EvsEventDesc)>;

/*
 * This class wraps around HIDL transactions to the Extended View System service
 * and the video stream managements.
 */
class EvsServiceWrapper : public RefBase {
public:
    virtual ~EvsServiceWrapper();

    bool initialize(const DeathCb& serviceDeathListener) ACQUIRE(mLock);

    /*
     * Requests to open a target camera device.
     *
     * @param id a string camera device identifier
     * @param frameCallback a callback function to get EVS frames
     * @param eventCallback a callback function to listen EVS stream events
     *
     * @return bool false if it has not connected to EVS service, fails to open
     *              a camera device, or fails to initialize a stream handler;
     *              true otherwise.
     */
    bool openCamera(const char* id, const FrameCb& frameCallback, const EventCb& eventCallback)
            ACQUIRE(mLock);

    /*
     * Requests to close an active camera device.
     */
    void closeCamera();

    /*
     * Requests to start a video stream from a successfully opened camera device.
     */
    bool startVideoStream();

    /*
     * Requests to stop an active video stream.
     */
    void stopVideoStream();

    /*
     * Notifies that the client finishes with this buffer.
     *
     * @param frame a consumed frame buffer
     */
    void doneWithFrame(const hardware::automotive::evs::V1_1::BufferDesc& frame);

    /*
     * Tells whether or not we're connected to the Extended View System service
     */
    bool isServiceAvailable() const ACQUIRE(mLock) {
        std::lock_guard<std::mutex> lock(mLock);
        return mService != nullptr;
    }

    /*
     * Tells whether or not a target camera device is opened
     */
    bool isCameraOpened() const ACQUIRE(mLock) {
        std::lock_guard<std::mutex> lock(mLock);
        return mCamera != nullptr;
    }

    /*
     * Compares the binder interface
     */
    bool isEqual(const wp<hidl::base::V1_0::IBase>& who) const ACQUIRE(mLock) {
        std::lock_guard<std::mutex> lock(mLock);
        return hardware::interfacesEqual(mService, who.promote());
    }

private:
    // Acquires the camera and the display exclusive ownerships.
    void acquireCameraAndDisplay() ACQUIRE(mLock);

    // A mutex to protect shared resources
    mutable std::mutex mLock;

    // Extended View System Enumerator service handle
    sp<hardware::automotive::evs::V1_1::IEvsEnumerator> mService GUARDED_BY(mLock);

    // A camera device opened for the rearview service
    sp<hardware::automotive::evs::V1_1::IEvsCamera> mCamera GUARDED_BY(mLock);

    // A handler of a video stream from the rearview camera device
    sp<StreamHandler> mStreamHandler GUARDED_BY(mLock);

    // Extended View System display handle.  This would not be used but held by
    // us to prevent other EVS clients from using EvsDisplay.
    sp<hardware::automotive::evs::V1_1::IEvsDisplay> mDisplay;

    // A flag to acquire a display handle only once
    std::once_flag mDisplayAcquired;

    // A death recipient of Extended View System service
    sp<EvsDeathRecipient> mDeathRecipient GUARDED_BY(mLock);

    // Service name for EVS enumerator
    static const char* kServiceName;

    // Maximum number of frames CarEvsService can hold.  This number has been
    // chosen heuristically.
    static constexpr int kMaxNumFramesInFlight = 6;

    // EVS service reserves a display ID 255 to allow the clients to open the main
    // display exclusively.
    static constexpr uint8_t kExclusiveMainDisplayId = 0xFF;
};

}  // namespace evs
}  // namespace automotive
}  // namespace android

#endif  // ANDROID_CARSERVICE_EVS_SERVICE_WRAPPER_H
