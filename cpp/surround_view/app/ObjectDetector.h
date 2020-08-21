// Copyright 2020 The Android Open Source Project
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

#pragma once

#include <android/hardware/automotive/sv/1.0/ISurroundViewService.h>
#include <android/hardware/automotive/sv/1.0/ISurroundView3dSession.h>
#include <aidl/android/automotive/computepipe/registry/BnClientInfo.h>
#include <aidl/android/automotive/computepipe/registry/IPipeQuery.h>
#include <aidl/android/automotive/computepipe/registry/IPipeRegistration.h>
#include <aidl/android/automotive/computepipe/runner/BnPipeStateCallback.h>
#include <aidl/android/automotive/computepipe/runner/BnPipeStream.h>
#include <aidl/android/automotive/computepipe/runner/PipeState.h>
#include <android/hidl/memory/1.0/IMemory.h>
#include <android/hidl/allocator/1.0/IAllocator.h>
#include <hidlmemory/mapping.h>
#include <utils/Mutex.h>
#include <thread>
#include "DetectedObjects.pb.h"

namespace android {
namespace automotive {
namespace surround_view {
namespace object_detection {

using ::aidl::android::automotive::computepipe::registry::BnClientInfo;
using ::aidl::android::automotive::computepipe::registry::IPipeQuery;
using ::aidl::android::automotive::computepipe::runner::BnPipeStateCallback;
using ::aidl::android::automotive::computepipe::runner::BnPipeStream;
using ::aidl::android::automotive::computepipe::runner::IPipeRunner;
using ::aidl::android::automotive::computepipe::runner::IPipeStream;
using ::aidl::android::automotive::computepipe::runner::PacketDescriptor;
using ::aidl::android::automotive::computepipe::runner::PipeState;
using namespace android::hardware::automotive::sv::V1_0;
using surround_view::object_detection::DetectedObjects;
using surround_view::object_detection::BoundingBox;
using surround_view::object_detection::CornerPoint;
using ::android::hardware::hidl_memory;
using ::android::hidl::memory::V1_0::IMemory;
using ::android::hidl::allocator::V1_0::IAllocator;

// Remote state of computepipe
class RemoteState {
  public:
    explicit RemoteState(std::function<void(bool, std::string)>& cb);
    PipeState GetCurrentState();
    void UpdateCurrentState(const PipeState& state);

  private:
    bool hasChanged = false;
    PipeState mState = PipeState::RESET;
    std::mutex mStateLock;
    std::condition_variable mWait;
    std::function<void(bool, std::string)> mTerminationCb;
};

// Client info of computepipe
class ClientInfo : public BnClientInfo {
  public:
    ndk::ScopedAStatus getClientName(std::string* _aidl_return) override {
        if (_aidl_return) {
            *_aidl_return = "ObjectDetectorClient";
            return ndk::ScopedAStatus::ok();
        }
        return ndk::ScopedAStatus::fromExceptionCode(EX_TRANSACTION_FAILED);
    }
};

// StreamCallback of computepipe, used to compute the projected detected
// bounding corners and set3dOverlay
class StreamCallback : public BnPipeStream {
  public:
    explicit StreamCallback(std::vector<bool>* pDetectionUpdated,
                            std::vector<DetectedObjects>* pDetectedobjects,
                            std::pair<hidl_memory, sp<IMemory>> pOverlaySharedMem) :
      mDetectionUpdated(pDetectionUpdated),
      mDetectedobjects(pDetectedobjects),
      mOverlaySharedMem(pOverlaySharedMem)
      {};

    ndk::ScopedAStatus deliverPacket(const PacketDescriptor& in_packet) override;

    // Get the projected bounding box for surround view 2d
    std::vector<DetectedObjects> getSurroundView2dOverlay();

    void set3dSession(android::sp<ISurroundView3dSession> pSession3d);

    void set2dSession(android::sp<ISurroundView2dSession> pSession2d);

  private:
    // Mutex for detection results update
    std::mutex mDetectionResultsLock;

    std::vector<bool>* mDetectionUpdated GUARDED_BY(mDetectionResultsLock);
    std::vector<DetectedObjects>* mDetectedobjects GUARDED_BY(mDetectionResultsLock);

    android::sp<ISurroundView3dSession> mSession3d;
    android::sp<ISurroundView2dSession> mSession2d;

    // HIDL shared memory used to maintain the 3d overlay data
    std::pair<hidl_memory, sp<IMemory>> mOverlaySharedMem;

    // Update the 3d overlay shared memory based on object detection results
    ndk::ScopedAStatus setSurroundView3dOverlay() REQUIRES(mDetectionResultsLock);

    // Add overlay for a bounding box
    void addOverlay(std::vector<OverlayMemoryDesc>* overlaysMemoryDesc,
                    sp<IMemory> pIMemory,
                    int* memoryPosition,
                    std::vector<Point3dFloat>* validCorners);

    // Check if all the camera's detection results are updated
    bool isAllCameraResultUpdated() REQUIRES(mDetectionResultsLock);

    // Get the conrers of all the detected bounding boxes within a DetectedObject
    std::vector<Point2dInt> getCornerVector(const DetectedObjects& object);
};

// State callback of computepipe
class StateCallback : public BnPipeStateCallback {
  public:
    explicit StateCallback(std::shared_ptr<RemoteState> s);
    ndk::ScopedAStatus handleState(PipeState state) override;

  private:
    std::shared_ptr<RemoteState> mStateTracker = nullptr;
};

// Object detector to subscribe the detection results from computepipe object detection runner
// TODO(b/161820316): Improve test coverage
class ObjectDetector {
  public:
    ObjectDetector() = default;
    ndk::ScopedAStatus init(std::function<void(bool, std::string)>&& termination,
                            std::vector<DetectedObjects>* pDetectedobjects);
    void start();
    void stop();
    void set3dsession(const android::sp<ISurroundView3dSession>& pSession);
    void set2dsession(const android::sp<ISurroundView2dSession>& pSession);
    std::shared_ptr<StreamCallback> getStreamCallback();

  private:
    // Setup the client configs for object detection runner
    ndk::ScopedAStatus setupConfig();

    // Internal function to start the object detection
    void startPipe();

    // Allocate HIDL memory based on given size
    std::pair<hidl_memory, sp<IMemory>> getMappedSharedMemory(int bytesSize);

    // HIDL shared memory used to maintain the 3d overlay data
    std::pair<hidl_memory, sp<IMemory>> overlaySharedMem;

    // Vector pointer of detected objects,
    // each camera's detection result is one DetectedObject element
    std::vector<DetectedObjects>* mDetectedobjects = nullptr;

    // Boolean vector used to indicate whether each camera's detection results is updated.
    std::vector<bool> detectionUpdated = {false, false, false, false};

    std::shared_ptr<IPipeRunner> mPipeRunner = nullptr;
    std::shared_ptr<ClientInfo> mClientInfo = nullptr;
    std::shared_ptr<StreamCallback> mStreamCallback = nullptr;
    std::shared_ptr<StateCallback> mStateCallback = nullptr;
    std::shared_ptr<RemoteState> mRemoteState = nullptr;
    android::sp<ISurroundView3dSession> mSession3d = nullptr;
    android::sp<ISurroundView2dSession> mSession2d = nullptr;
};

}  // namespace object_detection
}  // namespace surround_view
}  // namespace automotive
}  // namespace android
