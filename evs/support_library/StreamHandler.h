/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef EVS_VTS_STREAMHANDLER_H
#define EVS_VTS_STREAMHANDLER_H

#include <queue>

#include "ui/GraphicBuffer.h"

#include <android/hardware/automotive/evs/1.0/IEvsCameraStream.h>
#include <android/hardware/automotive/evs/1.0/IEvsCamera.h>
#include <android/hardware/automotive/evs/1.0/IEvsDisplay.h>

#include "BaseRenderCallback.h"

namespace android {
namespace automotive {
namespace evs {
namespace support {

using namespace ::android::hardware::automotive::evs::V1_0;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::hidl_vec;
using ::android::hardware::hidl_handle;
using ::android::sp;


/*
 * StreamHandler:
 * This class can be used to receive camera imagery from an IEvsCamera implementation.  It will
 * hold onto the most recent image buffer, returning older ones.
 * Note that the video frames are delivered on a background thread, while the control interface
 * is actuated from the applications foreground thread.
 */
class StreamHandler : public IEvsCameraStream {
public:
    virtual ~StreamHandler() {
        // The shutdown logic is supposed to be handled by ResourceManager
        // class. But if something goes wrong, we want to make sure that the
        // related resources are still released properly.
        if (mCamera != nullptr) {
            shutdown();
        }
    };

    StreamHandler(android::sp <IEvsCamera> pCamera);
    void shutdown();

    bool startStream();
    void asyncStopStream();
    void blockingStopStream();

    bool isRunning();

    bool newDisplayFrameAvailable();
    const BufferDesc& getNewDisplayFrame();
    void doneWithFrame(const BufferDesc& buffer);

    /*
     * Attaches a render callback to the StreamHandler.
     *
     * Every frame will be processed by the attached render callback before it
     * is delivered to the client by method getNewDisplayFrame().
     *
     * Since there is only one DisplayUseCase allowed at the same time, at most
     * only one render callback can be attached. The current render callback
     * needs to be detached first (by method detachRenderCallback()), before a
     * new callback can be attached. In other words, the call will be ignored
     * if the current render callback is not null.
     *
     * @see detachRenderCallback()
     * @see getNewDisplayFrame()
     */
    void attachRenderCallback(BaseRenderCallback*);

    /*
     * Detaches the current render callback.
     *
     * If no render callback is attached, this call will be ignored.
     *
     * @see attachRenderCallback(BaseRenderCallback*)
     */
    void detachRenderCallback();

private:
    // Implementation for ::android::hardware::automotive::evs::V1_0::ICarCameraStream
    Return<void> deliverFrame(const BufferDesc& buffer)  override;

    // Calls the attached render callback to generate the processed BufferDesc
    // for display.
    bool processFrame(const BufferDesc&, BufferDesc&);

    // Values initialized as startup
    android::sp <IEvsCamera>    mCamera;

    // Since we get frames delivered to us asnchronously via the ICarCameraStream interface,
    // we need to protect all member variables that may be modified while we're streaming
    // (ie: those below)
    std::mutex                  mLock;
    std::condition_variable     mSignal;

    bool                        mRunning = false;

    BufferDesc                  mOriginalBuffers[2];
    BufferDesc                  mProcessedBuffers[2];
    int                         mHeldBuffer = -1;   // Index of the one currently held by the client
    int                         mReadyBuffer = -1;  // Index of the newest available buffer

    BaseRenderCallback*         mRenderCallback = nullptr;
};

}  // namespace support
}  // namespace evs
}  // namespace automotive
}  // namespace android

#endif //EVS_VTS_STREAMHANDLER_H
