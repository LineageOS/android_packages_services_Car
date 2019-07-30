/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef ANDROID_AUTOMOTIVE_EVS_V1_1_HALCAMERA_H
#define ANDROID_AUTOMOTIVE_EVS_V1_1_HALCAMERA_H

#include <android/hardware/automotive/evs/1.1/types.h>
#include <android/hardware/automotive/evs/1.1/IEvsCamera.h>
#include <android/hardware/automotive/evs/1.1/IEvsCameraStream.h>
#include <ui/GraphicBuffer.h>

#include <thread>
#include <list>


using namespace ::android::hardware::automotive::evs::V1_1;
using ::android::hardware::Return;
using ::android::hardware::hidl_handle;
using ::android::hardware::automotive::evs::V1_0::EvsResult;
using IEvsCamera_1_0 = ::android::hardware::automotive::evs::V1_0::IEvsCamera;
using IEvsCamera_1_1 = ::android::hardware::automotive::evs::V1_1::IEvsCamera;
using BufferDesc_1_0 = ::android::hardware::automotive::evs::V1_0::BufferDesc;
using BufferDesc_1_1 = ::android::hardware::automotive::evs::V1_1::BufferDesc;
using IEvsCameraStream_1_0 = ::android::hardware::automotive::evs::V1_0::IEvsCameraStream;
using IEvsCameraStream_1_1 = ::android::hardware::automotive::evs::V1_1::IEvsCameraStream;

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {


class VirtualCamera;    // From VirtualCamera.h


// This class wraps the actual hardware IEvsCamera objects.  There is a one to many
// relationship between instances of this class and instances of the VirtualCamera class.
// This class implements the IEvsCameraStream interface so that it can receive the video
// stream from the hardware camera and distribute it to the associated VirtualCamera objects.
class HalCamera : public IEvsCameraStream_1_1 {
public:
    HalCamera(sp<IEvsCamera_1_1> hwCamera) : mHwCamera(hwCamera) {};

    // Factory methods for client VirtualCameras
    sp<VirtualCamera>     makeVirtualCamera();
    void                  disownVirtualCamera(sp<VirtualCamera> virtualCamera);

    // Implementation details
    sp<IEvsCamera_1_0>  getHwCamera()       { return mHwCamera; };
    unsigned            getClientCount()    { return mClients.size(); };
    bool                changeFramesInFlight(int delta);

    Return<EvsResult>   clientStreamStarting();
    void                clientStreamEnding();
    Return<void>        doneWithFrame(const BufferDesc_1_0& buffer);
    Return<void>        doneWithFrame(const BufferDesc_1_1& buffer);
    Return<EvsResult>   setMaster(sp<VirtualCamera> virtualCamera);
    Return<EvsResult>   unsetMaster(sp<VirtualCamera> virtualCamera);
    Return<EvsResult>   setParameter(sp<VirtualCamera> virtualCamera,
                                     CameraParam id, int32_t& value);
    Return<EvsResult>   getParameter(CameraParam id, int32_t& value);

    // Methods from ::android::hardware::automotive::evs::V1_1::IEvsCameraStream follow.
    Return<void> deliverFrame(const BufferDesc_1_0& buffer) override;
    Return<void> notifyEvent(const EvsEvent& event) override;

private:
    sp<IEvsCamera_1_1>              mHwCamera;
    std::list<wp<VirtualCamera>>    mClients;   // Weak pointers -> objects destruct if client dies

    enum {
        STOPPED,
        RUNNING,
        STOPPING,
    }                               mStreamState = STOPPED;

    struct FrameRecord {
        uint32_t    frameId;
        uint32_t    refCount;
        FrameRecord(uint32_t id) : frameId(id), refCount(0) {};
    };
    std::vector<FrameRecord>        mFrames;
    wp<VirtualCamera>               mMaster = nullptr;
};

} // namespace implementation
} // namespace V1_1
} // namespace evs
} // namespace automotive
} // namespace android

#endif  // ANDROID_AUTOMOTIVE_EVS_V1_1_HALCAMERA_H
