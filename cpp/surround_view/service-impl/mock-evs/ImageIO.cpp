/*
 * Copyright (C) 2021 The Android Open Source Project
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
#include "ImageIO.h"

#include <android-base/logging.h>
#include <cutils/native_handle.h>
#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferAllocator.h>

#include <png.h>

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace V1_0 {
namespace implementation {

using ::android::GraphicBuffer;
using ::android::sp;

namespace {

bool ReadRgbaDataFromPng(void* imgDataPtr, int width, int height, const char* filename) {
    // The control structure used by libpng.
    png_image pngImageControl;

    // Initialize the 'pngImageControl' structure.
    memset(&pngImageControl, 0, (sizeof pngImageControl));

    // Set the png version.
    pngImageControl.version = PNG_IMAGE_VERSION;

    // Begin reading the PNG image.
    if (png_image_begin_read_from_file(&pngImageControl, filename) == 0) {
        LOG(ERROR) << "Image not found: " << filename;
        return false;
    }

    // Set necessary image control parameters.
    pngImageControl.width = width;
    pngImageControl.height = height;
    pngImageControl.format = PNG_FORMAT_RGBA;

    // Finish reading the png image.
    if (png_image_finish_read(&pngImageControl, /*background=*/nullptr, imgDataPtr,
                              /*row_stride=*/0, /*colormap=*/nullptr) == 0) {
        LOG(ERROR) << "Read failed for image: " << filename;
        return false;
    }
    return true;
}

}  // namespace

bool ReadPngIntoBuffer(const char* filename, sp<GraphicBuffer> pGfxBuffer) {
    // Lock for writing and obtain a data pointer.
    void* imageDataPtr = nullptr;
    pGfxBuffer->lock(GRALLOC_USAGE_SW_WRITE_OFTEN | GRALLOC_USAGE_SW_READ_NEVER, &imageDataPtr);
    if (imageDataPtr == nullptr) {
        LOG(ERROR) << "Failed to gain write access to GraphicBuffer";
        return false;
    }

    // Read the png image into to data pointer.
    if (!ReadRgbaDataFromPng(imageDataPtr, pGfxBuffer->getWidth(), pGfxBuffer->getHeight(),
                             filename)) {
        pGfxBuffer->unlock();
        return false;
    }

    pGfxBuffer->unlock();
    return true;
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android