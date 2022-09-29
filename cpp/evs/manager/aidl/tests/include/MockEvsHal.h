/*
 * Copyright 2022 The Android Open Source Project
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

#pragma once

#include "MockEvsCamera.h"
#include "MockEvsDisplay.h"
#include "MockEvsEnumerator.h"

#include <aidl/android/hardware/automotive/evs/IEvsCameraStream.h>
#include <aidl/android/hardware/graphics/common/PixelFormat.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <vndk/hardware_buffer.h>

#include <unordered_map>

namespace aidl::android::automotive::evs::implementation {

namespace aidlevs = ::aidl::android::hardware::automotive::evs;

class MockEvsHal {
public:
    MockEvsHal(size_t numCameras, size_t numDisplays) :
          mNumCameras(numCameras), mNumDisplays(numDisplays) {}
    ~MockEvsHal();

    void initialize();
    std::shared_ptr<aidlevs::IEvsEnumerator> getEnumerator();

private:
    bool buildCameraMetadata(int32_t width, int32_t height, int32_t format,
                             std::vector<uint8_t>* out);
    void configureCameras(size_t n);
    void configureDisplays(size_t n);
    void configureEnumerator();
    void forwardFrames(size_t numberOfFramesToForward);
    size_t initializeBufferPool(size_t size);
    void deinitializeBufferPool();

    std::shared_ptr<NiceMockEvsEnumerator> mMockEvsEnumerator;
    std::vector<std::shared_ptr<NiceMockEvsCamera>> mMockEvsCameras;
    std::vector<std::shared_ptr<NiceMockEvsDisplay>> mMockEvsDisplays;
    std::shared_ptr<aidlevs::IEvsCameraStream> mCameraClient;

    struct CameraRecord {
        aidlevs::CameraDesc desc;
        std::weak_ptr<aidlevs::IEvsCamera> activeInstance;

        CameraRecord(aidlevs::CameraDesc& desc) : desc(desc) {}
    };

    std::map<std::string, CameraRecord> mCameraList;
    std::map<int32_t, std::vector<uint8_t>> mCameraExtendedInfo;
    std::map<aidlevs::CameraParam, int32_t> mCameraParams;
    std::vector<aidlevs::BufferDesc> mBufferPool;
    std::vector<aidlevs::BufferDesc> mBuffersInUse;
    std::unordered_map<size_t, AHardwareBuffer*> mBufferRecord;
    std::weak_ptr<aidlevs::IEvsDisplay> mActiveDisplay;
    bool mDisplayOwnedExclusively;

    aidlevs::DisplayState mCurrentDisplayState = aidlevs::DisplayState::NOT_OPEN;

    size_t mNumCameras;
    size_t mNumDisplays;
    size_t mBufferPoolSize;

    mutable std::mutex mLock;
    std::condition_variable mBufferAvailableSignal;
};

}  // namespace aidl::android::automotive::evs::implementation
