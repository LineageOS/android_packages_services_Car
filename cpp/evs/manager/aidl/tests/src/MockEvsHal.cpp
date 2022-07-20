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

#include "MockEvsHal.h"

#include <aidl/android/hardware/automotive/evs/Rotation.h>
#include <aidl/android/hardware/automotive/evs/StreamType.h>
#include <camera/CameraMetadata.h>

namespace {

using ::aidl::android::hardware::automotive::evs::BufferDesc;
using ::aidl::android::hardware::automotive::evs::CameraDesc;
using ::aidl::android::hardware::automotive::evs::CameraParam;
using ::aidl::android::hardware::automotive::evs::DisplayDesc;
using ::aidl::android::hardware::automotive::evs::DisplayState;
using ::aidl::android::hardware::automotive::evs::EvsResult;
using ::aidl::android::hardware::automotive::evs::IEvsCamera;
using ::aidl::android::hardware::automotive::evs::IEvsCameraStream;
using ::aidl::android::hardware::automotive::evs::IEvsDisplay;
using ::aidl::android::hardware::automotive::evs::IEvsEnumerator;
using ::aidl::android::hardware::automotive::evs::IEvsEnumeratorStatusCallback;
using ::aidl::android::hardware::automotive::evs::IEvsUltrasonicsArray;
using ::aidl::android::hardware::automotive::evs::ParameterRange;
using ::aidl::android::hardware::automotive::evs::Rotation;
using ::aidl::android::hardware::automotive::evs::Stream;
using ::aidl::android::hardware::automotive::evs::StreamType;
using ::aidl::android::hardware::graphics::common::BufferUsage;
using ::aidl::android::hardware::graphics::common::PixelFormat;

inline constexpr char kMockCameraDeviceNamePrefix[] = "/dev/mockcamera";
inline constexpr int32_t kCameraParamDefaultMinValue = -255;
inline constexpr int32_t kCameraParamDefaultMaxValue = 255;
inline constexpr int32_t kCameraParamDefaultStepValue = 3;

}  // namespace

namespace aidl::android::automotive::evs::implementation {

std::shared_ptr<IEvsEnumerator> MockEvsHal::getEnumerator() {
    if (!mMockEvsEnumerator) {
        LOG(ERROR) << "MockEvsHal has not initialized yet.";
        return nullptr;
    }

    return IEvsEnumerator::fromBinder(mMockEvsEnumerator->asBinder());
}

void MockEvsHal::initialize() {
    configureCameras(mNumCameras);
    configureDisplays(mNumDisplays);
    configureEnumerator();
}

bool MockEvsHal::buildCameraMetadata(int32_t width, int32_t height, int32_t format,
                                     std::vector<uint8_t>* out) {
    ::android::CameraMetadata metadata;

    const std::vector<int32_t> availableStreamConfigurations =
            {format, width, height, ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT};

    metadata.update(ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
                    availableStreamConfigurations.data(), availableStreamConfigurations.size());

    camera_metadata_t* p = metadata.release();
    if (validate_camera_metadata_structure(p, /* expected_size= */ nullptr) != ::android::OK) {
        LOG(ERROR) << "Failed to build a camera metadata.";
        return false;
    }

    size_t n = get_camera_metadata_size(p);
    out->resize(n);
    memcpy(out->data(), p, n);

    return true;
}

void MockEvsHal::configureCameras(size_t n) {
    // Builds mock IEvsCamera instances
    std::vector<std::shared_ptr<NiceMockEvsCamera>> cameras(n);

    // Initializes a list of the camera parameters each mock camera
    // supports with their default values.
    mCameraParams = {{CameraParam::BRIGHTNESS, 80},
                     {CameraParam::CONTRAST, 60},
                     {CameraParam::AUTOGAIN, 3},
                     {CameraParam::AUTO_EXPOSURE, 1}};

    for (auto i = 0; i < n; ++i) {
        std::shared_ptr<NiceMockEvsCamera> mockCamera =
                ndk::SharedRefBase::make<NiceMockEvsCamera>();

        // For the testing purpose, this method will return
        // EvsResult::INVALID_ARG if the client returns any buffer with
        // unknown identifier.
        ON_CALL(*mockCamera, doneWithFrame)
                .WillByDefault([this](const std::vector<BufferDesc>& buffers) {
                    bool success = true;
                    for (auto& b : buffers) {
                        auto it = std::find(mBuffersInUse.begin(), mBuffersInUse.end(), b.bufferId);
                        if (it == mBuffersInUse.end()) {
                            success = false;
                            continue;
                        }

                        mBuffersInUse.erase(it);
                    }

                    return success ? ndk::ScopedAStatus::ok()
                                   : ndk::ScopedAStatus::fromServiceSpecificError(
                                             static_cast<int>(EvsResult::INVALID_ARG));
                });

        // EVS HAL aceepts only a single client; therefore, this method
        // returns a success always.
        ON_CALL(*mockCamera, forcePrimaryClient)
                .WillByDefault([](const std::shared_ptr<IEvsDisplay>&) {
                    return ndk::ScopedAStatus::ok();
                });

        // We return a mock camera descriptor with the metadata but empty vendor
        // flag.
        ON_CALL(*mockCamera, getCameraInfo).WillByDefault([i, this](CameraDesc* desc) {
            CameraDesc mockDesc = {
                    .id = kMockCameraDeviceNamePrefix + std::to_string(i),
                    .vendorFlags = 0x0,
            };

            if (!buildCameraMetadata(/* width= */ 640, /* height= */ 480,
                                     /* format= */ HAL_PIXEL_FORMAT_RGBA_8888,
                                     &mockDesc.metadata)) {
                return ndk::ScopedAStatus::fromServiceSpecificError(
                        static_cast<int>(EvsResult::UNDERLYING_SERVICE_ERROR));
            }

            *desc = std::move(mockDesc);
            return ndk::ScopedAStatus::ok();
        });

        // This method will return a value associated with a given
        // identifier if exists.
        ON_CALL(*mockCamera, getExtendedInfo)
                .WillByDefault([this](int32_t id, std::vector<uint8_t>* v) {
                    auto it = mCameraExtendedInfo.find(id);
                    if (it == mCameraExtendedInfo.end()) {
                        // A requested information does not exist.
                        return ndk::ScopedAStatus::fromServiceSpecificError(
                                static_cast<int>(EvsResult::INVALID_ARG));
                    }

                    *v = it->second;
                    return ndk::ScopedAStatus::ok();
                });

        // This method will return a value of a requested camera parameter
        // if it is supported by a mock EVS camera.
        ON_CALL(*mockCamera, getIntParameter)
                .WillByDefault([this](CameraParam id, std::vector<int32_t>* v) {
                    auto it = mCameraParams.find(id);
                    if (it == mCameraParams.end()) {
                        return ndk::ScopedAStatus::fromServiceSpecificError(
                                static_cast<int>(EvsResult::INVALID_ARG));
                    }

                    // EVS HAL always returns a single integer value.
                    v->push_back(it->second);
                    return ndk::ScopedAStatus::ok();
                });

        // This method returns the same range values if a requested camera
        // parameter is supported by a mock EVS camera.
        ON_CALL(*mockCamera, getIntParameterRange)
                .WillByDefault([this](CameraParam id, ParameterRange* range) {
                    auto it = mCameraParams.find(id);
                    if (it == mCameraParams.end()) {
                        return ndk::ScopedAStatus::fromServiceSpecificError(
                                static_cast<int>(EvsResult::INVALID_ARG));
                    }

                    // For the testing purpose, this mock EVS HAL always returns the
                    // same values.
                    range->min = kCameraParamDefaultMinValue;
                    range->max = kCameraParamDefaultMaxValue;
                    range->step = kCameraParamDefaultStepValue;
                    return ndk::ScopedAStatus::ok();
                });

        // This method returns a list of camera parameters supported by a
        // mock EVS camera.
        ON_CALL(*mockCamera, getParameterList)
                .WillByDefault([this](std::vector<CameraParam>* list) {
                    for (auto& [k, _] : mCameraParams) {
                        list->push_back(k);
                    }
                    return ndk::ScopedAStatus::ok();
                });

        // This method behaves exactly the same as getCameraInfo() because
        // the EVS HAL does not support a concept of the group (or logical)
        // camera.
        ON_CALL(*mockCamera, getPhysicalCameraInfo)
                .WillByDefault([&](const std::string&, CameraDesc* desc) {
                    CameraDesc mockDesc = {
                            .id = kMockCameraDeviceNamePrefix + std::to_string(i),
                            .vendorFlags = 0x0,
                            .metadata = {},
                    };

                    *desc = std::move(mockDesc);
                    return ndk::ScopedAStatus::ok();
                });

        // This method adds given buffer descriptors to the internal buffer
        // pool if their identifiers do not conflict to existing ones.
        ON_CALL(*mockCamera, importExternalBuffers)
                .WillByDefault([this](const std::vector<BufferDesc>& buffers, int32_t* num) {
                    for (auto i = 0; i < buffers.size(); ++i) {
                        auto it = std::find_if(mBufferPool.begin(), mBufferPool.end(),
                                               [&](const BufferDesc& b) {
                                                   return b.bufferId == buffers[i].bufferId;
                                               });
                        if (it != mBufferPool.end()) {
                            // Ignores external buffers with a conflicting
                            // identifier.
                            continue;
                        }

                        // TODO(b/235110887): Explicitly copies external buffers
                        //                    stores them in mBufferPool.
                    }

                    *num = mBufferPool.size();
                    return ndk::ScopedAStatus::ok();
                });

        ON_CALL(*mockCamera, pauseVideoStream).WillByDefault([]() {
            return ndk::ScopedAStatus::ok();
        });

        ON_CALL(*mockCamera, resumeVideoStream).WillByDefault([]() {
            return ndk::ScopedAStatus::ok();
        });

        // This method stores a given vector with id.
        ON_CALL(*mockCamera, setExtendedInfo)
                .WillByDefault([this](int32_t id, const std::vector<uint8_t>& v) {
                    mCameraExtendedInfo.insert_or_assign(id, v);
                    return ndk::ScopedAStatus::ok();
                });

        // This method updates a parameter value if exists.
        ON_CALL(*mockCamera, setIntParameter)
                .WillByDefault([this](CameraParam id, int32_t in, std::vector<int32_t>* out) {
                    auto it = mCameraParams.find(id);
                    if (it == mCameraParams.end()) {
                        return ndk::ScopedAStatus::fromServiceSpecificError(
                                static_cast<int>(EvsResult::INVALID_ARG));
                    }

                    in = in > kCameraParamDefaultMaxValue      ? kCameraParamDefaultMaxValue
                            : in < kCameraParamDefaultMinValue ? kCameraParamDefaultMinValue
                                                               : in;
                    mCameraParams.insert_or_assign(id, in);
                    out->push_back(in);

                    return ndk::ScopedAStatus::ok();
                });

        // We always return a success because EVS HAL does not allow
        // multiple camera clients exist.
        ON_CALL(*mockCamera, setPrimaryClient).WillByDefault([]() {
            return ndk::ScopedAStatus::ok();
        });

        // Because EVS HAL does allow multiple camera clients exist, we simply
        // set the size of the buffer pool.
        ON_CALL(*mockCamera, setMaxFramesInFlight).WillByDefault([this](int32_t bufferCount) {
            size_t newSize = mBufferPoolSize + bufferCount;
            constexpr size_t kMinimumNumBuffers = 2;
            if (newSize < kMinimumNumBuffers) {
                return ndk::ScopedAStatus::fromServiceSpecificError(
                        static_cast<int>(EvsResult::INVALID_ARG));
            }

            mBufferPoolSize = newSize;
            return ndk::ScopedAStatus::ok();
        });

        // We manage the camera ownership on recency-basis; therefore we simply
        // replace the client in this method.
        ON_CALL(*mockCamera, startVideoStream)
                .WillByDefault([this](const std::shared_ptr<IEvsCameraStream>& cb) {
                    // TODO(b/235110887): Notifies a camera loss to the current
                    //                    client.
                    mCameraClient = cb;

                    // Starts circulating buffers.
                    return ndk::ScopedAStatus::ok();
                });

        // We simply drop a current client.
        ON_CALL(*mockCamera, stopVideoStream).WillByDefault([this]() {
            mCameraClient = nullptr;
            return ndk::ScopedAStatus::ok();
        });

        // We don't take any action because EVS HAL allows only a single camera
        // client exists at a time.
        ON_CALL(*mockCamera, unsetPrimaryClient).WillByDefault([]() {
            return ndk::ScopedAStatus::ok();
        });

        cameras[i] = std::move(mockCamera);
    }

    mMockEvsCameras = std::move(cameras);
}

void MockEvsHal::configureDisplays(size_t n) {
    // Build mock IEvsDisplcy instances
    std::vector<std::shared_ptr<NiceMockEvsDisplay>> displays(n);

    for (auto i = 0; i < n; ++i) {
        std::shared_ptr<NiceMockEvsDisplay> mockDisplay =
                ndk::SharedRefBase::make<NiceMockEvsDisplay>();

        ON_CALL(*mockDisplay, getDisplayInfo).WillByDefault([i](DisplayDesc* out) {
            DisplayDesc desc = {
                    .width = 1920,
                    .height = 1080,
                    .orientation = Rotation::ROTATION_0,
                    .id = "MockDisplay" + std::to_string(i),
                    .vendorFlags = i,  // For the testing purpose, we put a display id in the vendor
                                       // flag field.
            };
            *out = std::move(desc);
            return ndk::ScopedAStatus::ok();
        });

        ON_CALL(*mockDisplay, getDisplayState).WillByDefault([this](DisplayState* out) {
            *out = mCurrentDisplayState;
            return ndk::ScopedAStatus::ok();
        });

        ON_CALL(*mockDisplay, getTargetBuffer).WillByDefault([](BufferDesc* out) {
            (void)out;
            return ndk::ScopedAStatus::ok();
        });

        ON_CALL(*mockDisplay, returnTargetBufferForDisplay).WillByDefault([](const BufferDesc& in) {
            (void)in;
            return ndk::ScopedAStatus::ok();
        });

        ON_CALL(*mockDisplay, setDisplayState).WillByDefault([this](DisplayState in) {
            mCurrentDisplayState = in;
            return ndk::ScopedAStatus::ok();
        });

        displays[i] = std::move(mockDisplay);
    }

    mMockEvsDisplays = std::move(displays);
}

void MockEvsHal::configureEnumerator() {
    std::shared_ptr<NiceMockEvsEnumerator> mockEnumerator =
            ndk::SharedRefBase::make<NiceMockEvsEnumerator>();

    ON_CALL(*mockEnumerator, closeCamera).WillByDefault([](const std::shared_ptr<IEvsCamera>&) {
        // Mock EVS HAL always returns a success because it safely
        // ignores a request to close unknown cameras.
        return ndk::ScopedAStatus::ok();
    });

    ON_CALL(*mockEnumerator, closeDisplay)
            .WillByDefault([this]([[maybe_unused]] const std::shared_ptr<IEvsDisplay>& displayObj) {
                auto pActiveDisplay = mActiveDisplay.lock();
                if (!pActiveDisplay) {
                    return ndk::ScopedAStatus::fromServiceSpecificError(
                            static_cast<int>(EvsResult::OWNERSHIP_LOST));
                }

                // Nothing else to do.

                return ndk::ScopedAStatus::ok();
            });

    ON_CALL(*mockEnumerator, closeUltrasonicsArray)
            .WillByDefault([](const std::shared_ptr<IEvsUltrasonicsArray>&) {
                // Mock EVS HAL does not support IEvsUltrasonicsArray.
                return ndk::ScopedAStatus::ok();
            });

    ON_CALL(*mockEnumerator, getCameraList).WillByDefault([this](std::vector<CameraDesc>* out) {
        out->resize(mMockEvsCameras.size());

        for (auto i = 0; i < mMockEvsCameras.size(); ++i) {
            CameraDesc desc;
            if (!mMockEvsCameras[i]->getCameraInfo(&desc).isOk()) {
                LOG(ERROR) << "Failed to retrieve a camera desc";
                continue;
            }

            // Inserts a camera record if it does not exist.
            if (mCameraList.find(desc.id) == mCameraList.end()) {
                mCameraList.insert_or_assign(desc.id, desc);
            }

            (*out)[i] = std::move(desc);
        }

        return ndk::ScopedAStatus::ok();
    });

    ON_CALL(*mockEnumerator, getDisplayIdList).WillByDefault([this](std::vector<uint8_t>* out) {
        out->resize(mMockEvsDisplays.size());

        for (auto i = 0; i < mMockEvsDisplays.size(); ++i) {
            DisplayDesc desc;
            if (!mMockEvsDisplays[i]->getDisplayInfo(&desc).isOk()) {
                continue;
            }

            // MockEvsDisplay contains a display ID in its vendor flags.
            (*out)[i] = static_cast<uint8_t>(desc.vendorFlags);
        }

        return ndk::ScopedAStatus::ok();
    });

    ON_CALL(*mockEnumerator, getDisplayState).WillByDefault([this](DisplayState* out) {
        *out = mCurrentDisplayState;
        return ndk::ScopedAStatus::ok();
    });

    ON_CALL(*mockEnumerator, getStreamList)
            .WillByDefault([](const CameraDesc& desc, std::vector<Stream>* out) {
                if (desc.metadata.empty()) {
                    return ndk::ScopedAStatus::ok();
                }

                camera_metadata_t* p = const_cast<camera_metadata_t*>(
                        reinterpret_cast<const camera_metadata_t*>(desc.metadata.data()));
                camera_metadata_entry_t entry;
                if (find_camera_metadata_entry(p, ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
                                               &entry)) {
                    return ndk::ScopedAStatus::ok();
                }

                const auto n = calculate_camera_metadata_entry_data_size(
                        get_camera_metadata_tag_type(
                                ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS),
                        entry.count);
                out->resize(n);

                for (auto i = 0; i < n; ++i) {
                    // ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS is a set of 5
                    // int32_t words.
                    Stream s = {
                            .id = i,
                            .streamType = entry.data.i32[3] ==
                                            ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS
                                    ? StreamType::OUTPUT
                                    : StreamType::INPUT,
                            .width = entry.data.i32[1],
                            .height = entry.data.i32[2],
                            .format = static_cast<PixelFormat>(entry.data.i32[0]),
                            .usage = BufferUsage::CAMERA_INPUT,
                            .rotation = Rotation::ROTATION_0,
                    };

                    (*out)[i] = s;
                }

                return ndk::ScopedAStatus::ok();
            });

    ON_CALL(*mockEnumerator, getUltrasonicsArrayList)
            .WillByDefault([](std::vector<aidlevs::UltrasonicsArrayDesc>*) {
                // Mock EVS HAL does not support IEvsUltrasonicsArray yet.
                return ndk::ScopedAStatus::ok();
            });

    ON_CALL(*mockEnumerator, isHardware).WillByDefault([](bool* flag) {
        *flag = false;
        return ndk::ScopedAStatus::ok();
    });

    ON_CALL(*mockEnumerator, openCamera)
            .WillByDefault([this](const std::string& id, [[maybe_unused]] const Stream& config,
                                  std::shared_ptr<IEvsCamera>* out) {
                auto it = std::find_if(mMockEvsCameras.begin(), mMockEvsCameras.end(),
                                       [id](const std::shared_ptr<NiceMockEvsCamera>& c) {
                                           CameraDesc desc;
                                           return c->getCameraInfo(&desc).isOk() && desc.id == id;
                                       });

                if (it == mMockEvsCameras.end()) {
                    return ndk::ScopedAStatus::fromServiceSpecificError(
                            static_cast<int>(EvsResult::INVALID_ARG));
                }

                auto instance = mCameraList.find(id);  // Guaranteed to exist always.
                instance->second.activeInstance = *it;
                *out = IEvsCamera::fromBinder((*it)->asBinder());
                return ndk::ScopedAStatus::ok();
            });

    ON_CALL(*mockEnumerator, openDisplay)
            .WillByDefault([this](int32_t id, std::shared_ptr<IEvsDisplay>* out) {
                auto it = std::find_if(mMockEvsDisplays.begin(), mMockEvsDisplays.end(),
                                       [id](const std::shared_ptr<NiceMockEvsDisplay>& d) {
                                           DisplayDesc desc;
                                           return d->getDisplayInfo(&desc).isOk() &&
                                                   desc.vendorFlags == id;
                                       });

                if (it == mMockEvsDisplays.end()) {
                    return ndk::ScopedAStatus::fromServiceSpecificError(
                            static_cast<int>(EvsResult::INVALID_ARG));
                }

                mActiveDisplay = *it;
                mCurrentDisplayState = DisplayState::NOT_VISIBLE;
                *out = IEvsDisplay::fromBinder((*it)->asBinder());
                return ndk::ScopedAStatus::ok();
            });

    ON_CALL(*mockEnumerator, openUltrasonicsArray)
            .WillByDefault([](const std::string&, std::shared_ptr<IEvsUltrasonicsArray>*) {
                // Mock EVS HAL does not support IEvsUltrasonicsArray yet.
                return ndk::ScopedAStatus::ok();
            });

    ON_CALL(*mockEnumerator, registerStatusCallback)
            .WillByDefault([](const std::shared_ptr<IEvsEnumeratorStatusCallback>& cb) {
                (void)cb;
                return ndk::ScopedAStatus::ok();
            });

    mMockEvsEnumerator = std::move(mockEnumerator);
}

}  // namespace aidl::android::automotive::evs::implementation
