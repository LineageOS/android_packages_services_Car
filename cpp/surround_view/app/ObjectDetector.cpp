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

#include "ObjectDetector.h"

#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <utils/Log.h>

using namespace android::hardware::automotive::sv::V1_0;
using ::android::automotive::surround_view::object_detection::DetectedObjects;

namespace android {
namespace automotive {
namespace surround_view {
namespace object_detection {
namespace {

const char kReigstryInterface[] = "router";
const char kGraphName[] = "Object Detection Graph";
const int kVertexByteSize = (3 * sizeof(float)) + 4;
const int kIdByteSize = 2;
const int kCameraCount = 4;

// Maximum number of detected bounding boxes for each camera, should be consistent
// with max_num_detections of NonMaxSuppressionCalculator in object detection graph
const int kMaxNumberOfBoundingBox = 10;

struct Vertex {
    float position[3];
    uint8_t color[4];
};

// Return the estimated maximum overlay memory size, to avoid re-apply for hidl
// memory.
int getOverlayMemorySize() {
    // Maximum overlay memory size = the number of cameras *
    // the number of maximum number of bounding boxes of each frame *
    // the number of maximum memory size of each bounding box
    return kCameraCount * kMaxNumberOfBoundingBox * ( kIdByteSize +
      6 /*the number of vertex of each overlay*/ * kVertexByteSize);
}

}  // namespace

/**
 * RemoteState monitor
 */
PipeState RemoteState::GetCurrentState() {
    std::unique_lock<std::mutex> lock(mStateLock);
    mWait.wait(lock, [this]() { return hasChanged; });
    hasChanged = false;
    return mState;
}

void RemoteState::UpdateCurrentState(const PipeState& state) {
    std::lock_guard<std::mutex> lock(mStateLock);
    mState = state;
    if (mState == PipeState::ERR_HALT) {
        mTerminationCb(true, "Received error from runner");
    } else if (mState == PipeState::DONE) {
        mTerminationCb(false, "");
    } else {
        hasChanged = true;
        mWait.notify_all();
    }
}

RemoteState::RemoteState(std::function<void(bool, std::string)>& cb) : mTerminationCb(cb) {
}

/**
 * StateCallback methods
 */
StateCallback::StateCallback(std::shared_ptr<RemoteState> s) : mStateTracker(s) {
}

ndk::ScopedAStatus StateCallback::handleState(PipeState state) {
    mStateTracker->UpdateCurrentState(state);
    return ndk::ScopedAStatus::ok();
}

/**
 * ObjectDetector methods
 */

ndk::ScopedAStatus ObjectDetector::init(std::function<void(bool, std::string)>&& cb,
                                        std::vector<DetectedObjects>* pDetectedobjects) {
    auto termination = cb;
    mRemoteState = std::make_shared<RemoteState>(termination);
    std::string instanceName = std::string() + IPipeQuery::descriptor + "/" + kReigstryInterface;

    ndk::SpAIBinder binder(AServiceManager_getService(instanceName.c_str()));
    CHECK(binder.get());

    std::shared_ptr<IPipeQuery> queryService = IPipeQuery::fromBinder(binder);
    mClientInfo = ndk::SharedRefBase::make<ClientInfo>();

    ndk::ScopedAStatus status;
    status = queryService->getPipeRunner(kGraphName, mClientInfo, &mPipeRunner);

    if (!status.isOk()) {
        return status;
    }
    overlaySharedMem = getMappedSharedMemory(getOverlayMemorySize());
    mDetectedobjects = pDetectedobjects;
    mStreamCallback = ndk::SharedRefBase::make<StreamCallback>(&detectionUpdated,
                                                               mDetectedobjects,
                                                               overlaySharedMem);
    mStateCallback = ndk::SharedRefBase::make<StateCallback>(mRemoteState);

    return setupConfig();
}

ndk::ScopedAStatus ObjectDetector::setupConfig() {
    ndk::ScopedAStatus status = mPipeRunner->init(mStateCallback);
    if (!status.isOk()) {
        LOG(ERROR) << "Failed to init runner";
        return status;
    }
    status = mPipeRunner->setPipeInputSource(0);
    if (!status.isOk()) {
        LOG(ERROR) << "Failed to set pipe input config";
        return status;
    }
    status = mPipeRunner->setPipeOutputConfig(0, 10, mStreamCallback);
    if (!status.isOk()) {
        LOG(ERROR) << "Failed to set pipe output config";
        return status;
    }
    status = mPipeRunner->applyPipeConfigs();
    if (!status.isOk()) {
        LOG(ERROR) << "Failed to set apply configs";
        return status;
    }

    return ndk::ScopedAStatus::ok();
}

void ObjectDetector::start() {
    std::thread t(&ObjectDetector::startPipe, this);
    t.detach();
}

void ObjectDetector::startPipe() {
    PipeState state = mRemoteState->GetCurrentState();
    CHECK(state == PipeState::CONFIG_DONE);
    ndk::ScopedAStatus status = mPipeRunner->startPipe();
    CHECK(status.isOk());
    state = mRemoteState->GetCurrentState();
    CHECK(state == PipeState::RUNNING);
}

void ObjectDetector::stop() {
    ndk::ScopedAStatus status = mPipeRunner->stopPipe();
    CHECK(status.isOk());
}

void ObjectDetector::set3dsession(const android::sp<ISurroundView3dSession>& pSession) {
    mSession3d = pSession;
    mStreamCallback->set3dSession(mSession3d);
}

void ObjectDetector::set2dsession(const android::sp<ISurroundView2dSession>& pSession) {
    mSession2d = pSession;
    mStreamCallback->set2dSession(mSession2d);
}

std::shared_ptr<StreamCallback> ObjectDetector::getStreamCallback() {
    return mStreamCallback;
}

std::pair<hidl_memory, sp<IMemory>> ObjectDetector::getMappedSharedMemory(int bytesSize) {
    const auto nullResult = std::make_pair(hidl_memory(), nullptr);

    sp<IAllocator> ashmemAllocator = IAllocator::getService("ashmem");
    if (ashmemAllocator.get() == nullptr) {
        ALOGE("SurroundViewHidlTest getService ashmem failed");
        return nullResult;
    }

    // Allocate shared memory.
    hidl_memory hidlMemory;
    bool allocateSuccess = false;
    hardware::Return<void> result = ashmemAllocator->allocate(bytesSize,
            [&](bool success, const hidl_memory& hidlMem) {
                if (!success) {
                    return;
                }
                allocateSuccess = success;
                hidlMemory = hidlMem;
            });

    // Check result of allocated memory.
    if (!result.isOk() || !allocateSuccess) {
        ALOGE("SurroundViewHidlTest allocate shared memory failed");
        return nullResult;
    }

    // Map shared memory.
    sp<IMemory> pIMemory = mapMemory(hidlMemory);
    if (pIMemory.get() == nullptr) {
        ALOGE("SurroundViewHidlTest map shared memory failed");
        return nullResult;
    }

    return std::make_pair(hidlMemory, pIMemory);
}

/**
 * Stream Callback implementation
 */

ndk::ScopedAStatus StreamCallback::deliverPacket(const PacketDescriptor& in_packet) {
    LOG(INFO) << "Object detection received from ComputePipe";
    // Get serialized detection results
    std::string output(in_packet.data.begin(), in_packet.data.end());

    // Parse serialized detection results from string
    DetectedObjects objects;
    objects.ParseFromString(output);

    // Update detection results
    std::scoped_lock<std::mutex> lock(mDetectionResultsLock);
    mDetectionUpdated->at(objects.camera_id()) = true;
    mDetectedobjects->at(objects.camera_id()) = objects;

    // Update surround view 3d overlay if all of 4 camera's detection results are received
    if (isAllCameraResultUpdated() && mSession2d == nullptr && mSession3d != nullptr) {
        setSurroundView3dOverlay();
        *mDetectionUpdated = {false, false, false, false};
    }

    return ndk::ScopedAStatus::ok();
}

bool StreamCallback::isAllCameraResultUpdated() {
   for (bool isUpdated : *mDetectionUpdated) {
      if (!isUpdated) return false;
   }
   return true;
}

std::vector<DetectedObjects> StreamCallback::getSurroundView2dOverlay() {
    if (mSession2d == nullptr) {
        LOG(ERROR) << "Surround view 2d session is null.";
    }
    std::scoped_lock<std::mutex> lock(mDetectionResultsLock);
    std::vector<DetectedObjects> projectedobjectsvector;
    // For each camera
    for (DetectedObjects objects : *mDetectedobjects) {
      std::vector<Point2dInt> corners = getCornerVector(objects);
      // Get projected corner coordinates in surround view 3d camera frame
      std::vector<Point2dFloat> points2d;
      mSession2d->projectCameraPoints(corners, std::to_string(objects.camera_id()),
          [&points2d](const hardware::hidl_vec<Point2dFloat>& points2dproj) {
              points2d = points2dproj;
                });
      // Check valid corners
      DetectedObjects projectedobjects;
      for (size_t j = 0; j < objects.label_size(); j++) {
        BoundingBox* bounding_box = projectedobjects.add_bounding_box();
        bounding_box->mutable_corner1()->set_x(points2d[4*j].x);
        bounding_box->mutable_corner1()->set_y(points2d[4*j].y);
        bounding_box->mutable_corner2()->set_x(points2d[4*j+1].x);
        bounding_box->mutable_corner2()->set_y(points2d[4*j+1].y);
        bounding_box->mutable_corner3()->set_x(points2d[4*j+2].x);
        bounding_box->mutable_corner3()->set_y(points2d[4*j+2].y);
        bounding_box->mutable_corner4()->set_x(points2d[4*j+3].x);
        bounding_box->mutable_corner4()->set_y(points2d[4*j+3].y);
      }
      projectedobjectsvector.push_back(projectedobjects);
    }
    return projectedobjectsvector;
}

std::vector<Point2dInt> StreamCallback::getCornerVector(const DetectedObjects& object) {
   std::vector<Point2dInt> corners;
   for (size_t i = 0; i < object.bounding_box_size(); i++) {
        Point2dInt corner1;
        corner1.x = object.bounding_box(i).corner1().x();
        corner1.y = object.bounding_box(i).corner1().y();
        corners.push_back(corner1);
        Point2dInt corner2;
        corner2.x = object.bounding_box(i).corner2().x();
        corner2.y = object.bounding_box(i).corner2().y();
        corners.push_back(corner2);
        Point2dInt corner3;
        corner3.x = object.bounding_box(i).corner3().x();
        corner3.y = object.bounding_box(i).corner3().y();
        corners.push_back(corner3);
        Point2dInt corner4;
        corner4.x = object.bounding_box(i).corner4().x();
        corner4.y = object.bounding_box(i).corner4().y();
        corners.push_back(corner4);
      }
   return corners;
}

ndk::ScopedAStatus StreamCallback::setSurroundView3dOverlay() {
    // Used to maintain the current memory position
    int memoryPosition = 0;
    std::vector<OverlayMemoryDesc> overlaysMemoryDesc;
    // For each camera
    for (DetectedObjects objects : *mDetectedobjects) {
      std::vector<Point2dInt> corners = getCornerVector(objects);
      std::vector<Point3dFloat> points3d;
      // Get projected corner coordinates in surround view 3d camera frame
      mSession3d->projectCameraPointsTo3dSurface(corners, std::to_string(objects.camera_id()),
          [&points3d](const hardware::hidl_vec<Point3dFloat>& points3dproj) {
              points3d = points3dproj;
                });
      // Check valid corners
      for (size_t i = 0; i < points3d.size(); i = i + 4) {
        std::vector<Point3dFloat> validCorners;
        for (size_t j = 0; j < 4; j++) {
          if (points3d[i+j].isValid) {
            validCorners.push_back(points3d[i+j]);
          }
        }
        // Add overlay if got more than 3 valid corners
        if (validCorners.size() >= 3) {
          addOverlay(&overlaysMemoryDesc, mOverlaySharedMem.second, &memoryPosition, &validCorners);
        } else {
          LOG(INFO) << "Skip a bounding box, " <<
          "since corner coordinates are not valid after projection.";
        }
      }
    }

    if (memoryPosition == 0) {
      LOG(WARNING) << "No valid overlay found, will not update surround view 3d overlay";
      return ndk::ScopedAStatus::ok();
    }

    OverlaysData overlaysData;
    overlaysData.overlaysMemoryDesc = overlaysMemoryDesc;
    overlaysData.overlaysMemory = mOverlaySharedMem.first;

    SvResult result = mSession3d->updateOverlays(overlaysData);
    if (result == SvResult::OK) {
      LOG(INFO) << "Update overlay successed";
    } else {
      LOG(ERROR) << "Update overlay failed";
    }

    return ndk::ScopedAStatus::ok();
}

void StreamCallback::addOverlay(std::vector<OverlayMemoryDesc>* overlaysMemoryDesc,
                                sp<IMemory> pIMemory,
                                int* memoryPosition,
                                std::vector<Point3dFloat>* validCorners) {
    OverlayMemoryDesc overlayDesc;
    overlayDesc.id = overlaysMemoryDesc->size();
    uint8_t* pSharedMemoryData = reinterpret_cast<uint8_t*>((void*)pIMemory->getPointer());
    // Move to current memory position
    pSharedMemoryData += *memoryPosition;
    uint16_t* pIndex16bit = reinterpret_cast<uint16_t*>(pSharedMemoryData);

    // Add one TRIANGLES overlay if there are 3 validCorners
    if (validCorners->size() == 3) {
      overlayDesc.verticesCount = validCorners->size();
      overlayDesc.overlayPrimitive = OverlayPrimitive::TRIANGLES;
      pIMemory->update();
      // Set overlay ID
      *pIndex16bit = overlaysMemoryDesc->size();
      // Move memory after ID
      pSharedMemoryData += kIdByteSize;
      for (size_t i = 0; i < overlayDesc.verticesCount; i++) {
        Vertex vertex;
        vertex = {{validCorners->at(i).x/1000,
                       validCorners->at(i).y/1000,
                       validCorners->at(i).z/1000},
                  {0xff, 0x00, 0x00, 0xff}};
        Vertex* pIndexVertex = reinterpret_cast<Vertex*>(pSharedMemoryData);
        *pIndexVertex = vertex;
        pSharedMemoryData += sizeof(Vertex);
      }
      pIMemory->commit();
      *memoryPosition += (kIdByteSize+sizeof(Vertex)*3);
    // Add one TRIANGLES overlay if there are 4 validCorners
    } else if (validCorners->size() == 4) {
      overlayDesc.verticesCount = 6;
      overlayDesc.overlayPrimitive = OverlayPrimitive::TRIANGLES;
      pIMemory->update();
      // Set overlay ID
      *pIndex16bit = overlaysMemoryDesc->size();
      // Move memory after ID
      pSharedMemoryData += kIdByteSize;
      // Start to set vertext data
      int pointIndex[] = {0, 1, 2, 1, 2, 3};
      for (size_t i = 0; i < overlayDesc.verticesCount; i++) {
        Vertex vertex;
        vertex = {{validCorners->at(pointIndex[i]).x/1000,
                       validCorners->at(pointIndex[i]).y/1000,
                       validCorners->at(pointIndex[i]).z/1000},
                  {0xff, 0x00, 0x00, 0xff}};
        Vertex* pIndexVertex = reinterpret_cast<Vertex*>(pSharedMemoryData);
        *pIndexVertex = vertex;
        pSharedMemoryData += sizeof(Vertex);
      }
      pIMemory->commit();
      *memoryPosition += (kIdByteSize+sizeof(Vertex)*overlayDesc.verticesCount);
    }
   overlaysMemoryDesc->push_back(overlayDesc);
}

void StreamCallback::set3dSession(android::sp<ISurroundView3dSession> pSession3d) {
    mSession3d = pSession3d;
}

void StreamCallback::set2dSession(android::sp<ISurroundView2dSession> pSession2d) {
    mSession2d = pSession2d;
}

}  // namespace object_detection
}  // namespace surround_view
}  // namespace automotive
}  // namespace android

