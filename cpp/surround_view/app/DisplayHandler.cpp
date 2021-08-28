/*
 * Copyright 2021 The Android Open Source Project
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
#include "DisplayHandler.h"

#include <android-base/logging.h>
#include <math/mat4.h>
#include <ui/GraphicBuffer.h>
#include <utils/Log.h>

using android::GraphicBuffer;
using android::sp;
using android::hardware::Return;
using android::hardware::automotive::evs::V1_0::DisplayState;
using android::hardware::automotive::evs::V1_0::EvsResult;
using std::string;

DisplayHandler::DisplayHandler(sp<IEvsDisplay> evsDisplay) : mEvsDisplay(evsDisplay) {}

bool DisplayHandler::startDisplay() {
    // Check mEvsDisplay is valid.
    if (!mEvsDisplay) {
        LOG(ERROR) << "evsDisplay is null";
        return false;
    }

    // Set the display state to VISIBLE_ON_NEXT_FRAME
    Return<EvsResult> result =
            mEvsDisplay->setDisplayState(DisplayState::VISIBLE_ON_NEXT_FRAME);
    if (result != EvsResult::OK) {
        LOG(ERROR) << "Failed to setDisplayState";
        return false;
    }
    return true;
}

BufferDesc DisplayHandler::convertBufferDesc(const BufferDesc_1_0& src) {
    BufferDesc dst = {};
    AHardwareBuffer_Desc* pDesc = reinterpret_cast<AHardwareBuffer_Desc*>(&dst.buffer.description);
    pDesc->width = src.width;
    pDesc->height = src.height;
    pDesc->layers = 1;
    pDesc->format = src.format;
    pDesc->usage = static_cast<uint64_t>(src.usage);
    pDesc->stride = src.stride;

    dst.buffer.nativeHandle = src.memHandle;
    dst.pixelSize = src.pixelSize;
    dst.bufferId = src.bufferId;

    return dst;
}

bool DisplayHandler::getNewDisplayBuffer(sp<GraphicBuffer>* pGfxBuffer) {
    // Check mEvsDisplay is valid.
    if (!mEvsDisplay) {
        LOG(ERROR) << "evsDisplay is null";
        return false;
    }

    // Get display buffer from EVS display
    auto& tgtBuffer_1_0 = mTgtBuffer;
    mEvsDisplay->getTargetBuffer(
            [&tgtBuffer_1_0](const BufferDesc_1_0& buff) { tgtBuffer_1_0 = buff; });

    auto tgtBuffer = convertBufferDesc(tgtBuffer_1_0);
    const AHardwareBuffer_Desc* pDesc =
            reinterpret_cast<const AHardwareBuffer_Desc*>(&tgtBuffer.buffer.description);
    // Hardcoded to RGBx for now
    if (pDesc->format != HAL_PIXEL_FORMAT_RGBA_8888) {
        LOG(ERROR) << "Unsupported target buffer format";
        return false;
    }

    // create a GraphicBuffer from the existing handle
    *pGfxBuffer = new GraphicBuffer(tgtBuffer.buffer.nativeHandle, GraphicBuffer::CLONE_HANDLE,
                                    pDesc->width, pDesc->height, pDesc->format, pDesc->layers,
                                    GRALLOC_USAGE_HW_RENDER, pDesc->stride);
    if (!pGfxBuffer) {
        LOG(ERROR) << "Failed to allocate GraphicBuffer to wrap image handle";
        return false;
    }
    return true;
}

bool DisplayHandler::displayCurrentBuffer() {
    // Check mEvsDisplay is valid.
    if (!mEvsDisplay) {
        LOG(ERROR) << "evsDisplay is null";
        return false;
    }

    // Return display buffer back to EVS display
    if (mEvsDisplay->returnTargetBufferForDisplay(mTgtBuffer) != EvsResult::OK) {
        LOG(ERROR) << "Failed to returnTargetBufferForDisplay.";
        return false;
    }
    return true;
}