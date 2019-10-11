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

#ifndef ANDROID_AUTOMOTIVE_EVS_V1_1_CAMERAPROXY_H
#define ANDROID_AUTOMOTIVE_EVS_V1_1_CAMERAPROXY_H

#include <android/hardware/automotive/evs/1.0/types.h>
#include <android/hardware/automotive/evs/1.1/types.h>
#include <android/hardware/automotive/evs/1.1/IEvsCamera.h>
#include <android/hardware/automotive/evs/1.1/IEvsCameraStream.h>
#include <android/hardware/automotive/evs/1.0/IEvsDisplay.h>
#include <ui/GraphicBuffer.h>

#include <thread>
#include <deque>


using namespace ::android::hardware::automotive::evs::V1_1;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::hidl_handle;
using ::android::hardware::automotive::evs::V1_0::EvsResult;
using ::android::hardware::automotive::evs::V1_0::CameraDesc;
using ::android::hardware::automotive::evs::V1_0::IEvsDisplay;
using BufferDesc_1_0 = ::android::hardware::automotive::evs::V1_0::BufferDesc;
using BufferDesc_1_1 = ::android::hardware::automotive::evs::V1_1::BufferDesc;
using IEvsCamera_1_0 = ::android::hardware::automotive::evs::V1_0::IEvsCamera;
using IEvsCamera_1_1 = ::android::hardware::automotive::evs::V1_1::IEvsCamera;
using IEvsCameraStream_1_0 = ::android::hardware::automotive::evs::V1_0::IEvsCameraStream;
using IEvsCameraStream_1_1 = ::android::hardware::automotive::evs::V1_1::IEvsCameraStream;

namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {


class HalCamera;        // From HalCamera.h


// This class represents an EVS camera to the client application.  As such it presents
// the IEvsCamera interface, and also proxies the frame delivery to the client's
// IEvsCameraStream object.
class VirtualCamera : public IEvsCamera_1_1 {
public:
    explicit          VirtualCamera(sp<HalCamera> halCamera);
    virtual           ~VirtualCamera();
    void              shutdown();

    sp<HalCamera>     getHalCamera()      { return mHalCamera; };
    unsigned          getAllowedBuffers() { return mFramesAllowed; };
    bool              isStreaming()       { return mStreamState == RUNNING; }

    // Proxy to receive frames and forward them to the client's stream
    bool              notifyEvent(const EvsEvent& event);

    // Methods from ::android::hardware::automotive::evs::V1_0::IEvsCamera follow.
    Return<void>      getCameraInfo(getCameraInfo_cb _hidl_cb)  override;
    Return<EvsResult> setMaxFramesInFlight(uint32_t bufferCount) override;
    Return<EvsResult> startVideoStream(const ::android::sp<IEvsCameraStream_1_0>& stream) override;
    Return<void>      doneWithFrame(const BufferDesc_1_0& buffer) override;
    Return<void>      stopVideoStream() override;
    Return<int32_t>   getExtendedInfo(uint32_t opaqueIdentifier) override;
    Return<EvsResult> setExtendedInfo(uint32_t opaqueIdentifier, int32_t opaqueValue) override;

    // Methods from ::android::hardware::automotive::evs::V1_1::IEvsCamera follow.
    Return<EvsResult> doneWithFrame_1_1(const BufferDesc_1_1& buffer) override;
    Return<EvsResult> pauseVideoStream() override { return EvsResult::UNDERLYING_SERVICE_ERROR; }
    Return<EvsResult> resumeVideoStream() override { return EvsResult::UNDERLYING_SERVICE_ERROR; }
    Return<EvsResult> setMaster() override;
    Return<EvsResult> forceMaster(const sp<IEvsDisplay>& display) override;
    Return<EvsResult> unsetMaster() override;
    Return<void>      setParameter(CameraParam id, int32_t value,
                                   setParameter_cb _hidl_cb) override;
    Return<void>      getParameter(CameraParam id,
                                   getParameter_cb _hidl_cb) override;



private:
    sp<HalCamera>               mHalCamera;     // The low level camera interface that backs this proxy
    sp<IEvsCameraStream_1_0>    mStream;
    sp<IEvsCameraStream_1_1>    mStream_1_1;

    std::deque<BufferDesc_1_1>  mFramesHeld;
    unsigned                    mFramesAllowed  = 1;
    enum {
        STOPPED,
        RUNNING,
        STOPPING,
    }                           mStreamState    = STOPPED;
};

} // namespace implementation
} // namespace V1_1
} // namespace evs
} // namespace automotive
} // namespace android

#endif  // ANDROID_AUTOMOTIVE_EVS_V1_1_CAMERAPROXY_H
