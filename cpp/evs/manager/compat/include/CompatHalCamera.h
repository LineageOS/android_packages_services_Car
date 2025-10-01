/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "CompatVirtualCamera.h"

#include <aidl/android/hardware/automotive/evs/BnEvsCameraStream.h>
#include <aidl/android/hardware/automotive/evs/BufferDesc.h>
#include <aidl/android/hardware/automotive/evs/EvsEventDesc.h>
#include <aidl/android/hardware/automotive/evs/Stream.h>
#include <camera/NdkCameraDevice.h>

#include <list>

namespace android::hardware::automotive::evs::compat {

namespace aidlevs = ::aidl::android::hardware::automotive::evs;

class CompatHalCamera final : public aidlevs::BnEvsCameraStream {
public:
    CompatHalCamera(ACameraDevice* device, const std::string& cameraId,
                    const aidlevs::Stream& streamConfig);
    ~CompatHalCamera() override;

    ::ndk::ScopedAStatus deliverFrame(const std::vector<aidlevs::BufferDesc>& buffer) override;
    ::ndk::ScopedAStatus notify(const aidlevs::EvsEventDesc& event) override;

    inline aidlevs::Stream getStreamConfig() const { return mStreamConfig; }
    ACameraDevice* getDevice() const { return mDevice; }
    std::string getId() const { return mCameraId; }
    bool ownVirtualCamera(const std::shared_ptr<CompatVirtualCamera>& virtualCamera);
    bool isStopped() const { return mStreamState.load(std::memory_order_acquire) == STOPPED; }

private:
    ACameraDevice* mDevice;
    std::string mCameraId;
    aidlevs::Stream mStreamConfig;

    enum StreamStateEnum {
        STOPPED,
        RUNNING,
        STOPPING,
    };
    std::atomic<StreamStateEnum> mStreamState = STOPPED;
};
}  // namespace android::hardware::automotive::evs::compat
