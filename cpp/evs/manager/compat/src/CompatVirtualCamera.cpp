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

#include "CompatVirtualCamera.h"

#include "CompatHalCamera.h"
#include "Utils.h"

#include <android-base/logging.h>
#include <android-base/thread_annotations.h>

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::BufferDesc;
using ::aidl::android::hardware::automotive::evs::CameraDesc;
using ::aidl::android::hardware::automotive::evs::CameraParam;
using ::aidl::android::hardware::automotive::evs::EvsResult;
using ::aidl::android::hardware::automotive::evs::IEvsCameraStream;
using ::aidl::android::hardware::automotive::evs::IEvsDisplay;
using ::aidl::android::hardware::automotive::evs::ParameterRange;
using ::ndk::ScopedAStatus;

CompatVirtualCamera::CompatVirtualCamera(
        const std::vector<std::shared_ptr<CompatHalCamera>>& halCameras) {
    for (auto&& halCamera : halCameras) {
        mHalCameras.insert_or_assign(halCamera->getId(), std::weak_ptr<CompatHalCamera>(halCamera));
    }
}

CompatVirtualCamera::~CompatVirtualCamera() {
    shutdown();
}

void CompatVirtualCamera::shutdown() {
    {
        std::lock_guard lock(mMutex);

        // In normal operation, the stream should already be stopped by the time we get here
        if (mStreamState != RUNNING) {
            return;
        }

        // Note that if we hit this case, no terminating frame will be sent to the client,
        // but they're probably already dead anyway.
        LOG(WARNING) << "Virtual camera being shutdown while stream is running";

        // Tell the frame delivery pipeline we don't want any more frames
        mStreamState = STOPPING;

        // Returns buffers held by this client
        for (auto&& [key, hwCamera] : mHalCameras) {
            auto pHwCamera = hwCamera.lock();
            if (!pHwCamera) {
                LOG(WARNING) << "Camera device " << key << " is not alive.";
                continue;
            }

            if (!mFramesHeld[key].empty()) {
                LOG(WARNING) << "CompatVirtualCamera destructing with frames in flight.";

                // Return to the underlying hardware camera any buffers the client was holding
                while (!mFramesHeld[key].empty()) {
                    auto it = mFramesHeld[key].begin();
                    pHwCamera->doneWithFrame(std::move(*it));
                    mFramesHeld[key].erase(it);
                }
            }

            // Give the underlying hardware camera the heads up that it might be time to stop
            pHwCamera->clientStreamEnding(this);
            pHwCamera->disownVirtualCamera(this);
        }

        mFramesHeld.clear();
        mFramesUsed.clear();

        // Awake the capture and buffer-return threads; they will be terminated.
        mFramesReadySignal.notify_all();
        mReturnFramesSignal.notify_all();
    }

    // Join a capture and buffer-return threads.
    if (mCaptureThread.joinable()) {
        mCaptureThread.join();
    }

    if (mReturnThread.joinable()) {
        mReturnThread.join();
    }

    // Drop our reference to our associated hardware camera
    mHalCameras.clear();
}

ScopedAStatus CompatVirtualCamera::doneWithFrame(const std::vector<BufferDesc>& buffers) {
    std::lock_guard lock(mMutex);
    for (auto&& buffer : buffers) {
        // Find this buffer in our "held" list
        auto it = std::find_if(mFramesHeld[buffer.deviceId].begin(),
                               mFramesHeld[buffer.deviceId].end(),
                               [id = buffer.bufferId](const BufferDesc& buffer) {
                                   return id == buffer.bufferId;
                               });
        if (it == mFramesHeld[buffer.deviceId].end()) {
            // We should always find the frame in our "held" list
            LOG(WARNING) << "Ignoring doneWithFrame called with unrecognized frame id "
                         << buffer.bufferId;
            continue;
        }

        // Move this frame out of our "held" list
        mFramesUsed[buffer.deviceId].push_back(std::move(*it));
        mFramesHeld[buffer.deviceId].erase(it);
    }

    mReturnFramesSignal.notify_all();
    return ScopedAStatus::ok();
}

ScopedAStatus CompatVirtualCamera::forcePrimaryClient(
        [[maybe_unused]] const std::shared_ptr<IEvsDisplay>& display) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::getCameraInfo(CameraDesc* _aidl_return) {
    if (mHalCameras.empty()) {
        LOG(ERROR) << "No hardware camera is available.";
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
    }

    if (mHalCameras.size() > 1) {
        *_aidl_return = *mDesc;
        return ScopedAStatus::ok();
    }

    // Physical camera case
    auto halCamera = mHalCameras.begin()->second.lock();
    if (!halCamera) {
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
    }

    CameraDesc desc = halCamera->getCameraDesc();
    if (desc.id.empty()) {
        LOG(ERROR) << "CameraDesc for device is not properly initialized.";
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
    }
    *_aidl_return = desc;
    return ScopedAStatus::ok();
}

ScopedAStatus CompatVirtualCamera::getExtendedInfo(
        [[maybe_unused]] int32_t opaqueIdentifier,
        [[maybe_unused]] std::vector<uint8_t>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::getIntParameter(
        [[maybe_unused]] CameraParam id, [[maybe_unused]] std::vector<int32_t>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::getIntParameterRange(CameraParam id,
                                                        ParameterRange* _aidl_return) {
    {
        std::lock_guard lock(mMutex);
        if (!mSupportedParamsPopulated) {
            LOG(INFO) << "Supported parameter cache is not populated. Populating now.";
            auto status = populateSupportedParametersLocked();
            if (!status.isOk()) {
                return status;
            }
        }
    }

    auto it = std::find(mSupportedParams.begin(), mSupportedParams.end(), id);
    if (it == mSupportedParams.end()) {
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::NOT_SUPPORTED));
    }

    CameraDesc desc;
    auto status = getCameraInfo(&desc);
    if (!status.isOk()) {
        LOG(ERROR) << "Failed to get camera info.";
        return status;
    }

    const camera_metadata_t* metadata =
            reinterpret_cast<const camera_metadata_t*>(desc.metadata.data());

    switch (id) {
        case CameraParam::BRIGHTNESS: {
            // The Camera2 API provides a range of exposure compensation values.
            // We expose this directly to the client.
            camera_metadata_ro_entry_t entry;
            find_camera_metadata_ro_entry(metadata, ACAMERA_CONTROL_AE_COMPENSATION_RANGE, &entry);
            _aidl_return->min = entry.data.i32[0];
            _aidl_return->max = entry.data.i32[1];
            _aidl_return->step = 1;
            break;
        }
        case CameraParam::GAIN: {
            // The reference EVS HAL does not provide an implementation for GAIN.
            // In the absence of a standard EVS pattern, we expose the raw ISO
            // range provided by the underlying Camera2 API, as this is the most
            // direct and informative approach for the client.
            camera_metadata_ro_entry_t entry;
            find_camera_metadata_ro_entry(metadata, ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, &entry);
            _aidl_return->min = entry.data.i32[0];
            _aidl_return->max = entry.data.i32[1];
            _aidl_return->step = 1;
            break;
        }
        case CameraParam::WHITE_BALANCE_TEMPERATURE: {
            // The Camera2 API provides a raw color temperature range in Kelvin.
            // We expose this directly to the client, as this is consistent with
            // the behavior of existing EVS HALs, which expect clients to work
            // with Kelvin values.
            camera_metadata_ro_entry_t entry;
            find_camera_metadata_ro_entry(metadata,
                                          ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE, &entry);
            _aidl_return->min = entry.data.i32[0];
            _aidl_return->max = entry.data.i32[1];
            _aidl_return->step = 1;
            break;
        }
        case CameraParam::SHARPNESS: {
            // The Camera2 API's sharpness control is done via an enum ACAMERA_EDGE_MODE.
            // We expose the range of this enum directly to the client.
            _aidl_return->min = ACAMERA_EDGE_MODE_OFF;
            _aidl_return->max = ACAMERA_EDGE_MODE_ZERO_SHUTTER_LAG;
            _aidl_return->step = 1;
            break;
        }
        case CameraParam::ABSOLUTE_EXPOSURE: {
            // The EVS API does not specify a unit for exposure, and reference HALs
            // do not implement this parameter. We choose microseconds as the unit
            // to provide a sensible and robust range. The Camera2 API provides an
            // exposure time range in nanoseconds (int64_t), which can exceed the
            // limits of the EVS API's int32_t. Converting to microseconds prevents
            // overflow for long exposures while retaining sufficient precision.
            camera_metadata_ro_entry_t entry;
            find_camera_metadata_ro_entry(metadata, ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE,
                                          &entry);
            _aidl_return->min = static_cast<int32_t>(entry.data.i64[0] / 1000);
            _aidl_return->max = static_cast<int32_t>(
                    std::min(entry.data.i64[1] / 1000, static_cast<int64_t>(INT32_MAX)));
            _aidl_return->step = 1;
            break;
        }
        case CameraParam::ABSOLUTE_FOCUS: {
            // The Camera2 API's focus distance is a float in diopters. To represent
            // this as an integer for the EVS API, we scale the range by 100.
            // The `setIntParameter` method will scale the value back down.
            camera_metadata_ro_entry_t entry;
            find_camera_metadata_ro_entry(metadata, ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                                          &entry);
            _aidl_return->min = 0;
            _aidl_return->max = static_cast<int32_t>(entry.data.f[0] * 100);
            _aidl_return->step = 1;
            break;
        }
        case CameraParam::ABSOLUTE_ZOOM: {
            // The Camera2 API provides a float zoom ratio. To align with the EVS
            // API's integer-based control and match reference HAL behavior, we
            // scale the float ratio by 100 (e.g., 1.0f -> 100).
            camera_metadata_ro_entry_t entry;
            find_camera_metadata_ro_entry(metadata, ACAMERA_CONTROL_ZOOM_RATIO_RANGE, &entry);
            _aidl_return->min = static_cast<int32_t>(entry.data.f[0] * 100);
            _aidl_return->max = static_cast<int32_t>(entry.data.f[1] * 100);
            _aidl_return->step = 1;
            break;
        }
        case CameraParam::AUTOGAIN:
        case CameraParam::AUTO_EXPOSURE:
        case CameraParam::AUTO_WHITE_BALANCE:
        case CameraParam::AUTO_FOCUS: {
            // These parameters represent on/off switches.
            _aidl_return->min = 0;
            _aidl_return->max = 1;
            _aidl_return->step = 1;
            break;
        }
        default:
            return ScopedAStatus::fromServiceSpecificError(
                    static_cast<int>(EvsResult::NOT_SUPPORTED));
    }

    return ScopedAStatus::ok();
}

::ndk::ScopedAStatus CompatVirtualCamera::populateSupportedParametersLocked() {
    // EVS Manager does not support parameter programming for a logical camera.
    if (mHalCameras.size() > 1) {
        LOG(INFO) << "Logical camera does not support parameter programming.";
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::NOT_SUPPORTED));
    }

    // The HAL camera object must be valid.
    auto halCamera = mHalCameras.begin()->second.lock();
    if (!halCamera) {
        LOG(ERROR) << "Underlying hardware camera is not available.";
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
    }

    CameraDesc desc;
    auto status = getCameraInfo(&desc);
    if (!status.isOk()) {
        LOG(ERROR) << "Failed to get camera info.";
        return status;
    }

    if (desc.metadata.empty()) {
        LOG(WARNING) << "No camera metadata available.";
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::NOT_SUPPORTED));
    }

    const camera_metadata_t* metadata =
            reinterpret_cast<const camera_metadata_t*>(desc.metadata.data());

    // BRIGHTNESS
    camera_metadata_ro_entry_t aeCompRange;
    if (find_camera_metadata_ro_entry(metadata, ACAMERA_CONTROL_AE_COMPENSATION_RANGE,
                                      &aeCompRange) == 0 &&
        aeCompRange.count == 2 && (aeCompRange.data.i32[0] != 0 || aeCompRange.data.i32[1] != 0)) {
        mSupportedParams.push_back(CameraParam::BRIGHTNESS);
    }

    // TODO: For now CONTRAST will be left unsupported because of the disparity between EVS
    // CONTRAST implementation (usually a slider from 0-255) and camera2 implementation of CONTRAST
    // (represented by a curve). At this point, this is too complicated to translate, so will leave
    // it unimplemented until explicitly requested for.

    // camera_metadata_ro_entry_t tonemapModes;
    // if (find_camera_metadata_ro_entry(metadata, ACAMERA_TONEMAP_AVAILABLE_TONE_MAP_MODES,
    //                                   &tonemapModes) == 0) {
    //     for (size_t i = 0; i < tonemapModes.count; ++i) {
    //         if (tonemapModes.data.u8[i] == ACAMERA_TONEMAP_MODE_CONTRAST_CURVE) {
    //             supportedParams.push_back(CameraParam::CONTRAST);
    //             break;
    //         }
    //     }
    // }

    // GAIN, AUTOGAIN
    camera_metadata_ro_entry_t sensitivityRange;
    if (find_camera_metadata_ro_entry(metadata, ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE,
                                      &sensitivityRange) == 0 &&
        sensitivityRange.count == 2 &&
        sensitivityRange.data.i32[0] < sensitivityRange.data.i32[1]) {
        mSupportedParams.push_back(CameraParam::GAIN);
        mSupportedParams.push_back(CameraParam::AUTOGAIN);
    }

    // WHITE_BALANCE_TEMPERATURE, AUTO_WHITE_BALANCE
    camera_metadata_ro_entry_t awbModes;
    if (find_camera_metadata_ro_entry(metadata, ACAMERA_CONTROL_AWB_AVAILABLE_MODES, &awbModes) ==
        0) {
        bool awbOffAvailable = false;
        bool awbAutoAvailable = false;
        for (size_t i = 0; i < awbModes.count; ++i) {
            if (awbModes.data.u8[i] == ACAMERA_CONTROL_AWB_MODE_OFF) {
                awbOffAvailable = true;
            } else if (awbModes.data.u8[i] == ACAMERA_CONTROL_AWB_MODE_AUTO) {
                awbAutoAvailable = true;
            }
        }

        if (awbAutoAvailable) {
            mSupportedParams.push_back(CameraParam::AUTO_WHITE_BALANCE);
        }
        if (awbOffAvailable) {
            camera_metadata_ro_entry_t tempRange;
            if (find_camera_metadata_ro_entry(metadata,
                                              ACAMERA_COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE,
                                              &tempRange) == 0 &&
                tempRange.count == 2 && tempRange.data.i32[0] < tempRange.data.i32[1]) {
                mSupportedParams.push_back(CameraParam::WHITE_BALANCE_TEMPERATURE);
            }
        }
    }

    // SHARPNESS
    camera_metadata_ro_entry_t edgeModes;
    if (find_camera_metadata_ro_entry(metadata, ACAMERA_EDGE_AVAILABLE_EDGE_MODES, &edgeModes) ==
        0) {
        for (size_t i = 0; i < edgeModes.count; ++i) {
            if (edgeModes.data.u8[i] != ACAMERA_EDGE_MODE_OFF) {
                mSupportedParams.push_back(CameraParam::SHARPNESS);
                break;
            }
        }
    }

    // AUTO_EXPOSURE, ABSOLUTE_EXPOSURE
    camera_metadata_ro_entry_t aeModes;
    if (find_camera_metadata_ro_entry(metadata, ACAMERA_CONTROL_AE_AVAILABLE_MODES, &aeModes) ==
        0) {
        bool aeOffAvailable = false;
        bool aeOnAvailable = false;
        for (size_t i = 0; i < aeModes.count; ++i) {
            if (aeModes.data.u8[i] == ACAMERA_CONTROL_AE_MODE_OFF) {
                aeOffAvailable = true;
            } else if (aeModes.data.u8[i] != ACAMERA_CONTROL_AE_MODE_OFF) {
                aeOnAvailable = true;
            }
        }

        if (aeOnAvailable) {
            mSupportedParams.push_back(CameraParam::AUTO_EXPOSURE);
        }
        if (aeOffAvailable) {
            camera_metadata_ro_entry_t exposureTimeRange;
            if (find_camera_metadata_ro_entry(metadata, ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE,
                                              &exposureTimeRange) == 0 &&
                exposureTimeRange.count == 2 &&
                exposureTimeRange.data.i64[0] < exposureTimeRange.data.i64[1]) {
                mSupportedParams.push_back(CameraParam::ABSOLUTE_EXPOSURE);
            }
        }
    }

    // AUTO_FOCUS
    camera_metadata_ro_entry_t afModes;
    if (find_camera_metadata_ro_entry(metadata, ACAMERA_CONTROL_AF_AVAILABLE_MODES, &afModes) ==
        0) {
        for (size_t i = 0; i < afModes.count; ++i) {
            if (afModes.data.u8[i] != ACAMERA_CONTROL_AF_MODE_OFF) {
                mSupportedParams.push_back(CameraParam::AUTO_FOCUS);
                break;
            }
        }
    }

    // ABSOLUTE_FOCUS
    camera_metadata_ro_entry_t minFocusDist;
    if (find_camera_metadata_ro_entry(metadata, ACAMERA_LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                                      &minFocusDist) == 0 &&
        minFocusDist.count > 0 && minFocusDist.data.f[0] > 0) {
        mSupportedParams.push_back(CameraParam::ABSOLUTE_FOCUS);
    }

    // ABSOLUTE_ZOOM
    camera_metadata_ro_entry_t zoomRatioRange;
    if (find_camera_metadata_ro_entry(metadata, ACAMERA_CONTROL_ZOOM_RATIO_RANGE,
                                      &zoomRatioRange) == 0 &&
        zoomRatioRange.count == 2 &&
        (zoomRatioRange.data.f[0] < 1.0f || zoomRatioRange.data.f[1] > 1.0f)) {
        mSupportedParams.push_back(CameraParam::ABSOLUTE_ZOOM);
    }

    mSupportedParamsPopulated = true;
    return ScopedAStatus::ok();
}

ScopedAStatus CompatVirtualCamera::getParameterList(std::vector<CameraParam>* _aidl_return) {
    {
        std::lock_guard lock(mMutex);
        if (!mSupportedParamsPopulated) {
            LOG(INFO) << "Supported parameter cache is not populated. Populating now.";
            auto status = populateSupportedParametersLocked();
            if (!status.isOk()) {
                return status;
            }
        }
    }

    if (_aidl_return == nullptr) {
        LOG(ERROR) << "Received a null pointer for the return value.";
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::INVALID_ARG));
    }

    *_aidl_return = mSupportedParams;
    return ScopedAStatus::ok();
}

ScopedAStatus CompatVirtualCamera::getPhysicalCameraInfo(const std::string& deviceId,
                                                         CameraDesc* _aidl_return) {
    auto it = mHalCameras.find(deviceId);
    if (it == mHalCameras.end()) {
        LOG(ERROR) << "Camera " << deviceId << " not found.";
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::INVALID_ARG));
    }
    std::shared_ptr<CompatHalCamera> halCamera = it->second.lock();
    if (!halCamera) {
        LOG(ERROR) << "Camera " << deviceId << " is no longer available.";
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
    }
    // Check if CameraDesc is valid. The id field is mandatory.
    aidlevs::CameraDesc desc = halCamera->getCameraDesc();
    if (desc.id.empty()) {
        LOG(ERROR) << "CameraDesc for " << deviceId << " is not properly initialized.";
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int>(EvsResult::RESOURCE_NOT_AVAILABLE));
    }
    *_aidl_return = desc;
    return ScopedAStatus::ok();
}

ScopedAStatus CompatVirtualCamera::importExternalBuffers(
        [[maybe_unused]] const std::vector<BufferDesc>& buffers,
        [[maybe_unused]] int32_t* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::pauseVideoStream() {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::resumeVideoStream() {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::setExtendedInfo(
        [[maybe_unused]] int32_t opaqueIdentifier,
        [[maybe_unused]] const std::vector<uint8_t>& opaqueValue) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::setIntParameter(
        [[maybe_unused]] CameraParam id, [[maybe_unused]] int32_t value,
        [[maybe_unused]] std::vector<int32_t>* _aidl_return) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::setPrimaryClient() {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus CompatVirtualCamera::setMaxFramesInFlight(int32_t bufferCount) {
    if (bufferCount <= 0) {
        LOG(ERROR) << "bufferCount must be positive, but got " << bufferCount;
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(EvsResult::INVALID_ARG));
    }

    std::lock_guard<std::mutex> lock(mMutex);
    if (bufferCount == mMaxFramesInFlight) {
        return ScopedAStatus::ok();
    }

    for (auto& [id, weak_hal_cam] : mHalCameras) {
        if (auto hal_cam = weak_hal_cam.lock()) {
            bool is_stopped;
            if (hal_cam->tryIsStopped(is_stopped)) {
                if (!is_stopped) {
                    LOG(ERROR) << "Camera " << id << " is not stopped.";
                    return ScopedAStatus::fromServiceSpecificError(
                            static_cast<int32_t>(EvsResult::STREAM_ALREADY_RUNNING));
                }
            } else {
                // Could not acquire lock, treat as busy
                LOG(WARNING) << "Could not determine state of Camera " << id << ", assuming busy.";
                return ScopedAStatus::fromServiceSpecificError(
                        static_cast<int32_t>(EvsResult::RESOURCE_BUSY));
            }
        }
    }

    mMaxFramesInFlight = bufferCount;
    return ScopedAStatus::ok();
}

ScopedAStatus CompatVirtualCamera::startVideoStream(
        const std::shared_ptr<IEvsCameraStream>& receiver) {
    std::lock_guard lock(mMutex);
    if (!receiver) {
        LOG(ERROR) << "Given IEvsCameraStream object is invalid.";
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(EvsResult::INVALID_ARG));
    }
    // Only support starting a stream when the stream is stopped.
    if (mStreamState != STOPPED) {
        LOG(ERROR) << "Ignoring startVideoStream call when a stream is already running.";
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(EvsResult::STREAM_ALREADY_RUNNING));
    }
    // No frames should be held when starting a stream.
    assert(mFramesHeld.empty());

    // Record the user's callback for use when we have a frame ready
    mStream = receiver;
    mStreamState = RUNNING;

    // Tell the underlying camera hardware that we want to stream
    bool cleanUpAndReturn = true;
    auto iter = mHalCameras.begin();
    while (iter != mHalCameras.end()) {
        std::shared_ptr<CompatHalCamera> halCamera = iter->second.lock();
        if (!halCamera) {
            LOG(WARNING) << "Failed to start a video stream on " << iter->first;
            ++iter;
            continue;
        }

        LOG(INFO) << __FUNCTION__ << " starts a video stream on " << iter->first;
        if (!halCamera->clientStreamStarting().isOk()) {
            LOG(ERROR) << "Failed to start a video stream on " << iter->first;
            cleanUpAndReturn = true;
            break;
        }

        cleanUpAndReturn = false;
        ++iter;
    }

    if (cleanUpAndReturn) {
        // If we failed to start the underlying stream, then we're not actually running
        mStream = nullptr;
        mStreamState = STOPPED;

        // Request to stop streams started by this client.
        auto rb = mHalCameras.begin();
        while (rb != iter) {
            auto ptr = rb->second.lock();
            if (ptr) {
                ptr->clientStreamEnding(this);
            }
            ++rb;
        }

        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int32_t>(EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    mCaptureThread = std::thread([this]() {
        // TODO(b/145466570): With a proper camera hang handler, we may want
        // to reduce an amount of timeout.
        constexpr auto kFrameTimeout = std::chrono::seconds(5);  // timeout in seconds.
        int64_t lastFrameTimestamp = -1;
        EvsResult status = EvsResult::OK;
        while (true) {
            std::unique_lock lock(mMutex);
            ::android::base::ScopedLockAssertion assume_lock(mMutex);
            if (mStreamState != RUNNING) {
                LOG(DEBUG) << "Requested to stop capturing frames";
                break;
            }

            unsigned count = 0;
            for (auto&& [key, hwCamera] : mHalCameras) {
                std::shared_ptr<CompatHalCamera> halCamera = hwCamera.lock();
                if (!halCamera) {
                    LOG(WARNING) << "Invalid camera " << key << " is ignored.";
                    continue;
                }

                halCamera->requestNewFrame(ref<CompatVirtualCamera>(), lastFrameTimestamp);
                mSourceCameras.insert(halCamera->getId());
                ++count;
            }

            if (count < 1) {
                LOG(ERROR) << "No camera is available.";
                status = EvsResult::RESOURCE_NOT_AVAILABLE;
                break;
            }

            if (!mFramesReadySignal.wait_for(lock, kFrameTimeout, [this]() REQUIRES(mMutex) {
                    return mStreamState != RUNNING || mSourceCameras.empty();
                })) {
                LOG(DEBUG) << "Timer for a new frame expires";
                status = EvsResult::UNDERLYING_SERVICE_ERROR;
                break;
            }

            if (mStreamState != RUNNING || !mStream) {
                LOG(DEBUG) << "Requested to stop capturing frames or lost a client";
                break;
            }

            if (mFramesHeld.empty()) {
                continue;
            }

            std::vector<BufferDesc> frames;
            frames.resize(count);
            unsigned i = 0;
            for (auto&& [key, hwCamera] : mHalCameras) {
                std::shared_ptr<CompatHalCamera> halCamera = hwCamera.lock();
                if (!halCamera || mFramesHeld[key].empty()) {
                    continue;
                }

                auto frame = dupBufferDesc(mFramesHeld[key].back(), /* doDup= */ true);
                if (frame.timestamp > lastFrameTimestamp) {
                    lastFrameTimestamp = frame.timestamp;
                }
                frames[i++] = std::move(frame);
            }

            if (!mStream->deliverFrame(frames).isOk()) {
                LOG(WARNING) << "Failed to forward frames";
            }
        }

        LOG(DEBUG) << "Exiting a capture thread";
        if (status != EvsResult::OK && mStream) {
            aidlevs::EvsEventDesc event = {
                    .aType = status == EvsResult::RESOURCE_NOT_AVAILABLE
                            ? aidlevs::EvsEventType::STREAM_ERROR
                            : aidlevs::EvsEventType::TIMEOUT,
                    .payload = {static_cast<int32_t>(status)},
            };
            if (!mStream->notify(event).isOk()) {
                LOG(WARNING) << "Error delivering a stream event"
                             << static_cast<int32_t>(event.aType);
            }
        }
    });

    mReturnThread = std::thread([this]() {
        while (true) {
            std::unordered_map<std::string, std::vector<BufferDesc>> framesUsed;
            {
                std::unique_lock lock(mMutex);
                ::android::base::ScopedLockAssertion assume_lock(mMutex);
                mReturnFramesSignal.wait(lock, [this]() REQUIRES(mMutex) {
                    return mStreamState != RUNNING || !mFramesUsed.empty();
                });

                if (mStreamState != RUNNING) {
                    LOG(DEBUG) << "Requested to stop capturing frames or lost a client";
                    break;
                }

                for (auto&& [hwCameraId, buffers] : mFramesUsed) {
                    std::vector<BufferDesc> bufferToReturn(std::make_move_iterator(buffers.begin()),
                                                           std::make_move_iterator(buffers.end()));
                    framesUsed.insert_or_assign(hwCameraId, std::move(bufferToReturn));
                }

                mFramesUsed.clear();
            }

            for (auto&& [hwCameraId, buffers] : framesUsed) {
                std::shared_ptr<CompatHalCamera> halCamera = mHalCameras[hwCameraId].lock();
                if (!halCamera) {
                    LOG(WARNING) << "Possible memory leak; " << hwCameraId << " is not valid.";
                    continue;
                }

                for (auto&& buffer : buffers) {
                    const auto bufferId = buffer.bufferId;
                    if (!halCamera->doneWithFrame(std::move(buffer)).isOk()) {
                        LOG(WARNING)
                                << "Failed to return a buffer " << bufferId << " to " << hwCameraId;
                    }
                }
            }
        }

        LOG(DEBUG) << "Exiting a return thread";
    });

    return ScopedAStatus::ok();
}

ScopedAStatus CompatVirtualCamera::stopVideoStream() {
    {
        std::lock_guard lock(mMutex);
        if (mStreamState != RUNNING) {
            // No action is required.
            return ScopedAStatus::ok();
        }

        // Tell the frame delivery pipeline we don't want any more frames
        mStreamState = STOPPING;

        // Awake the capture and buffer-return threads; they will be terminated.
        mSourceCameras.clear();
        mFramesReadySignal.notify_all();
        mReturnFramesSignal.notify_all();

        // Deliver the stream-ending notification
        aidlevs::EvsEventDesc event{
                .aType = aidlevs::EvsEventType::STREAM_STOPPED,
        };
        if (mStream && !mStream->notify(event).isOk()) {
            LOG(WARNING) << "Error delivering end of stream event";
        }

        // Since we are single threaded, no frame can be delivered while this function is running,
        // so we can go directly to the STOPPED state here on the server.
        // Note, however, that there still might be frames already queued that client will see
        // after returning from the client side of this call.
        mStreamState = STOPPED;
    }

    // Give the underlying hardware camera the heads up that it might be time to stop
    for (auto&& [_, halCamera] : mHalCameras) {
        auto pHalCamera = halCamera.lock();
        if (pHalCamera) {
            pHalCamera->clientStreamEnding(this);
        }
    }

    // Join a capture and buffer-return threads.
    if (mCaptureThread.joinable()) {
        mCaptureThread.join();
    }

    if (mReturnThread.joinable()) {
        mReturnThread.join();
    }

    return ScopedAStatus::ok();
}

ScopedAStatus CompatVirtualCamera::unsetPrimaryClient() {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

bool CompatVirtualCamera::deliverFrame(const aidlevs::BufferDesc& bufferDesc) {
    std::lock_guard lock(mMutex);

    if (mStreamState == STOPPED) {
        // A stopped stream gets no frames
        LOG(ERROR) << "A stopped stream should not get any frames";
        return false;
    }

    if (mFramesHeld[bufferDesc.deviceId].size() >= mMaxFramesInFlight) {
        // Indicate that we declined to send the frame to the client because they're at quota
        LOG(INFO) << "Skipping new frame as we hold " << mFramesHeld[bufferDesc.deviceId].size()
                  << " of [maxFramesInFlight]" << mMaxFramesInFlight;

        if (mStream) {
            // Report a frame drop to the client.
            aidlevs::EvsEventDesc event;
            event.deviceId = bufferDesc.deviceId;
            event.aType = aidlevs::EvsEventType::FRAME_DROPPED;
            if (!mStream->notify(event).isOk()) {
                LOG(WARNING) << "Error delivering end of stream event";
            }
        }

        // Marks that a new frame has arrived though it was not accepted
        mSourceCameras.erase(bufferDesc.deviceId);
        mFramesReadySignal.notify_all();

        return false;
    }

    // Keep a record of this frame so we can clean up if we have to in case of client death
    mFramesHeld[bufferDesc.deviceId].push_back(dupBufferDesc(bufferDesc, /* doDup= */ true));

    // v1.0 client uses an old frame-delivery mechanism.
    if (mCaptureThread.joinable()) {
        // Keep forwarding frames as long as a capture thread is alive
        // Notify a new frame receipt
        mSourceCameras.erase(bufferDesc.deviceId);
        mFramesReadySignal.notify_all();
    }

    return true;
}

std::vector<std::shared_ptr<CompatHalCamera>> CompatVirtualCamera::getHalCameras() const {
    std::vector<std::shared_ptr<CompatHalCamera>> halCameras;
    for (auto&& [_, halCamera] : mHalCameras) {
        std::shared_ptr<CompatHalCamera> pHalCamera = halCamera.lock();
        if (pHalCamera) {
            halCameras.push_back(std::move(pHalCamera));
        }
    }
    return halCameras;
}

}  // namespace android::hardware::automotive::evs::compat
