/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "EvsGlDisplay.h"

#include <aidl/android/hardware/automotive/evs/EvsResult.h>
#include <aidl/android/hardware/graphics/common/BufferUsage.h>
#include <aidl/android/hardware/graphics/common/PixelFormat.h>
#include <aidlcommonsupport/NativeHandle.h>
#include <ui/DisplayMode.h>
#include <ui/DisplayState.h>
#include <ui/GraphicBufferAllocator.h>
#include <ui/GraphicBufferMapper.h>
#include <utils/SystemClock.h>

namespace {

using ::aidl::android::hardware::automotive::evs::BufferDesc;
using ::aidl::android::hardware::automotive::evs::DisplayDesc;
using ::aidl::android::hardware::automotive::evs::DisplayState;
using ::aidl::android::hardware::automotive::evs::EvsResult;
using ::aidl::android::hardware::graphics::common::BufferUsage;
using ::aidl::android::hardware::graphics::common::PixelFormat;
using ::android::frameworks::automotive::display::V1_0::HwDisplayConfig;
using ::android::frameworks::automotive::display::V1_0::HwDisplayState;
using ::android::frameworks::automotive::display::V1_0::IAutomotiveDisplayProxyService;
using ::ndk::ScopedAStatus;

bool debugFirstFrameDisplayed = false;

int generateFingerPrint(buffer_handle_t handle) {
    return static_cast<int>(reinterpret_cast<long>(handle) & 0xFFFFFFFF);
}

}  // namespace

namespace aidl::android::hardware::automotive::evs::implementation {

EvsGlDisplay::EvsGlDisplay(const ::android::sp<IAutomotiveDisplayProxyService>& pDisplayProxy,
                           uint64_t displayId) :
      mDisplayProxy(pDisplayProxy), mDisplayId(displayId) {
    LOG(DEBUG) << "EvsGlDisplay instantiated";

    // Set up our self description
    // NOTE:  These are arbitrary values chosen for testing
    mInfo.id = std::to_string(displayId);
    mInfo.vendorFlags = 3870;
}

EvsGlDisplay::~EvsGlDisplay() {
    LOG(DEBUG) << "EvsGlDisplay being destroyed";
    forceShutdown();
}

/**
 * This gets called if another caller "steals" ownership of the display
 */
void EvsGlDisplay::forceShutdown() {
    LOG(DEBUG) << "EvsGlDisplay forceShutdown";
    std::lock_guard<std::mutex> lock(mAccessLock);

    // If the buffer isn't being held by a remote client, release it now as an
    // optimization to release the resources more quickly than the destructor might
    // get called.
    if (mBuffer.handle != nullptr) {
        // Report if we're going away while a buffer is outstanding
        if (mFrameBusy) {
            LOG(ERROR) << "EvsGlDisplay going down while client is holding a buffer";
        }

        // Drop the graphics buffer we've been using
        ::android::GraphicBufferAllocator& alloc(::android::GraphicBufferAllocator::get());
        alloc.free(mBuffer.handle);
        mBuffer.handle = nullptr;

        mGlWrapper.hideWindow(mDisplayProxy, mDisplayId);
        mGlWrapper.shutdown();
    }

    // Put this object into an unrecoverable error state since somebody else
    // is going to own the display now.
    mRequestedState = DisplayState::DEAD;
}

/**
 * Returns basic information about the EVS display provided by the system.
 * See the description of the DisplayDesc structure for details.
 */
ScopedAStatus EvsGlDisplay::getDisplayInfo(DisplayDesc* _aidl_return) {
    using ADisplayMode = ::android::ui::DisplayMode;
    using ADisplayState = ::android::ui::DisplayState;

    if (mDisplayProxy) {
        mDisplayProxy->getDisplayInfo(mDisplayId,
                                      [&_aidl_return](const auto& hidlMode, const auto& hidlState) {
                                          const ADisplayMode* displayMode =
                                                  reinterpret_cast<const ADisplayMode*>(
                                                          hidlMode.data());
                                          const ADisplayState* displayState =
                                                  reinterpret_cast<const ADisplayState*>(
                                                          hidlState.data());
                                          _aidl_return->width = displayMode->resolution.width;
                                          _aidl_return->width = displayMode->resolution.height;
                                          _aidl_return->orientation =
                                                  static_cast<Rotation>(displayState->orientation);
                                      });
        _aidl_return->id = mInfo.id;
        _aidl_return->vendorFlags = mInfo.vendorFlags;
        return ScopedAStatus::ok();
    } else {
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int>(EvsResult::UNDERLYING_SERVICE_ERROR));
    }
}

/**
 * Clients may set the display state to express their desired state.
 * The HAL implementation must gracefully accept a request for any state
 * while in any other state, although the response may be to ignore the request.
 * The display is defined to start in the NOT_VISIBLE state upon initialization.
 * The client is then expected to request the VISIBLE_ON_NEXT_FRAME state, and
 * then begin providing video.  When the display is no longer required, the client
 * is expected to request the NOT_VISIBLE state after passing the last video frame.
 */
ScopedAStatus EvsGlDisplay::setDisplayState(DisplayState state) {
    LOG(DEBUG) << __FUNCTION__;
    std::lock_guard<std::mutex> lock(mAccessLock);

    if (mRequestedState == DisplayState::DEAD) {
        // This object no longer owns the display -- it's been superceeded!
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::OWNERSHIP_LOST));
    }

    // Ensure we recognize the requested state so we don't go off the rails
    static constexpr ::ndk::enum_range<DisplayState> kDisplayStateRange;
    if (std::find(kDisplayStateRange.begin(), kDisplayStateRange.end(), state) ==
        kDisplayStateRange.end()) {
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::INVALID_ARG));
    }

    switch (state) {
        case DisplayState::NOT_VISIBLE:
            mGlWrapper.hideWindow(mDisplayProxy, mDisplayId);
            break;
        case DisplayState::VISIBLE:
            mGlWrapper.showWindow(mDisplayProxy, mDisplayId);
            break;
        default:
            break;
    }

    // Record the requested state
    mRequestedState = state;

    return ScopedAStatus::ok();
}

/**
 * The HAL implementation should report the actual current state, which might
 * transiently differ from the most recently requested state.  Note, however, that
 * the logic responsible for changing display states should generally live above
 * the device layer, making it undesirable for the HAL implementation to
 * spontaneously change display states.
 */
ScopedAStatus EvsGlDisplay::getDisplayState(DisplayState* _aidl_return) {
    LOG(DEBUG) << __FUNCTION__;
    std::lock_guard<std::mutex> lock(mAccessLock);
    *_aidl_return = mRequestedState;
    return ScopedAStatus::ok();
}

/**
 * This call returns a handle to a frame buffer associated with the display.
 * This buffer may be locked and written to by software and/or GL.  This buffer
 * must be returned via a call to returnTargetBufferForDisplay() even if the
 * display is no longer visible.
 */
ScopedAStatus EvsGlDisplay::getTargetBuffer(BufferDesc* _aidl_return) {
    LOG(DEBUG) << __FUNCTION__;
    std::lock_guard<std::mutex> lock(mAccessLock);

    if (mRequestedState == DisplayState::DEAD) {
        LOG(ERROR) << "Rejecting buffer request from object that lost ownership of the display.";
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::OWNERSHIP_LOST));
    }

    // If we don't already have a buffer, allocate one now
    // mBuffer.memHandle is a type of buffer_handle_t, which is equal to
    // native_handle_t*.
    if (mBuffer.handle == nullptr) {
        // Initialize our display window
        // NOTE:  This will cause the display to become "VISIBLE" before a frame is actually
        // returned, which is contrary to the spec and will likely result in a black frame being
        // (briefly) shown.
        // TODO(b/220136152): we have initialized the GL context in the context
        //                    of the binder thread but this would not work if a
        //                    binder thread id is not consistent.
        if (!mGlWrapper.initialize(mDisplayProxy, mDisplayId)) {
            // Report the failure
            LOG(ERROR) << "Failed to initialize GL display";
            return ScopedAStatus::fromServiceSpecificError(
                    static_cast<int>(EvsResult::UNDERLYING_SERVICE_ERROR));
        }

        // Assemble the buffer description we'll use for our render target
        mBuffer.description = {
                .width = static_cast<int>(mGlWrapper.getWidth()),
                .height = static_cast<int>(mGlWrapper.getHeight()),
                .layers = 1,
                .format = PixelFormat::RGBA_8888,  // HAL_PIXEL_FORMAT_RGBA_8888;
                // FIXME: Below line is not using
                // ::aidl::android::hardware::graphics::common::BufferUsage because
                // BufferUsage enum does not support a bitwise-OR operation; they
                // should be BufferUsage::GPU_RENDER_TARGET |
                // BufferUsage::COMPOSER_OVERLAY
                .usage = static_cast<BufferUsage>(GRALLOC_USAGE_HW_RENDER |
                                                  GRALLOC_USAGE_HW_COMPOSER),
        };

        ::android::GraphicBufferAllocator& alloc(::android::GraphicBufferAllocator::get());
        uint32_t stride = static_cast<uint32_t>(mBuffer.description.stride);
        const auto result =
                alloc.allocate(mBuffer.description.width, mBuffer.description.height,
                               static_cast<::android::PixelFormat>(mBuffer.description.format),
                               mBuffer.description.layers,
                               static_cast<uint64_t>(mBuffer.description.usage), &mBuffer.handle,
                               &stride, /* requestorName= */ "EvsGlDisplay");
        mBuffer.description.stride = stride;  // FIXME

        mBuffer.fingerprint = generateFingerPrint(mBuffer.handle);
        if (result != ::android::NO_ERROR) {
            LOG(ERROR) << "Error " << result << " allocating " << mBuffer.description.width << " x "
                       << mBuffer.description.height << " graphics buffer.";
            mGlWrapper.shutdown();
            return ScopedAStatus::fromServiceSpecificError(
                    static_cast<int>(EvsResult::UNDERLYING_SERVICE_ERROR));
        }
        if (mBuffer.handle == nullptr) {
            LOG(ERROR) << "We didn't get a buffer handle back from the allocator";
            mGlWrapper.shutdown();
            return ScopedAStatus::fromServiceSpecificError(
                    static_cast<int>(EvsResult::BUFFER_NOT_AVAILABLE));
        }

        LOG(DEBUG) << "Allocated new buffer " << mBuffer.handle << " with stride "
                   << mBuffer.description.stride;
        mFrameBusy = false;
    }

    // Do we have a frame available?
    if (mFrameBusy) {
        // This means either we have a 2nd client trying to compete for buffers
        // (an unsupported mode of operation) or else the client hasn't returned
        // a previously issued buffer yet (they're behaving badly).
        // NOTE:  We have to make the callback even if we have nothing to provide
        LOG(ERROR) << "getTargetBuffer called while no buffers available.";
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int>(EvsResult::BUFFER_NOT_AVAILABLE));
    }

    // Mark our buffer as busy
    mFrameBusy = true;

    // Send the buffer to the client
    LOG(VERBOSE) << "Providing display buffer handle " << mBuffer.handle;

    BufferDesc bufferDescToSend = {
            .buffer =
                    {
                            .handle = std::move(::android::dupToAidl(mBuffer.handle)),
                            .description = mBuffer.description,
                    },
            .pixelSizeBytes = 4,  // RGBA_8888 is 4-byte-per-pixel format
            .bufferId = mBuffer.fingerprint,
    };
    *_aidl_return = std::move(bufferDescToSend);

    return ScopedAStatus::ok();
}

/**
 * This call tells the display that the buffer is ready for display.
 * The buffer is no longer valid for use by the client after this call.
 */
ScopedAStatus EvsGlDisplay::returnTargetBufferForDisplay(const BufferDesc& buffer) {
    LOG(VERBOSE) << __FUNCTION__;
    std::lock_guard<std::mutex> lock(mAccessLock);

    // Nobody should call us with a null handle
    if (buffer.buffer.handle.fds.size() < 1) {
        LOG(ERROR) << __FUNCTION__ << " called without a valid buffer handle.";
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::INVALID_ARG));
    }
    if (buffer.bufferId != mBuffer.fingerprint) {
        LOG(ERROR) << "Got an unrecognized frame returned.";
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::INVALID_ARG));
    }
    if (!mFrameBusy) {
        LOG(ERROR) << "A frame was returned with no outstanding frames.";
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::INVALID_ARG));
    }

    mFrameBusy = false;

    // If we've been displaced by another owner of the display, then we can't do anything else
    if (mRequestedState == DisplayState::DEAD) {
        return ScopedAStatus::fromServiceSpecificError(static_cast<int>(EvsResult::OWNERSHIP_LOST));
    }

    // If we were waiting for a new frame, this is it!
    if (mRequestedState == DisplayState::VISIBLE_ON_NEXT_FRAME) {
        mRequestedState = DisplayState::VISIBLE;
        mGlWrapper.showWindow(mDisplayProxy, mDisplayId);
    }

    // Validate we're in an expected state
    if (mRequestedState != DisplayState::VISIBLE) {
        // Not sure why a client would send frames back when we're not visible.
        LOG(WARNING) << "Got a frame returned while not visible - ignoring.";
        return ScopedAStatus::ok();
    }

    // Update the texture contents with the provided data
    if (!mGlWrapper.updateImageTexture(mBuffer.handle, mBuffer.description)) {
        return ScopedAStatus::fromServiceSpecificError(
                static_cast<int>(EvsResult::UNDERLYING_SERVICE_ERROR));
    }

    // Put the image on the screen
    mGlWrapper.renderImageToScreen();
    if (!debugFirstFrameDisplayed) {
        LOG(DEBUG) << "EvsFirstFrameDisplayTiming start time: " << ::android::elapsedRealtime()
                   << " ms.";
        debugFirstFrameDisplayed = true;
    }

    return ScopedAStatus::ok();
}

}  // namespace aidl::android::hardware::automotive::evs::implementation
