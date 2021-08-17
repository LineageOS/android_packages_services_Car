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
#ifndef SURROUNDVIEW_MOCK_EVS_IMAGEIO_H
#define SURROUNDVIEW_MOCK_EVS_IMAGEIO_H

#include <stdint.h>
#include <android/hardware/automotive/evs/1.1/types.h>
#include <ui/GraphicBuffer.h>

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace V1_0 {
namespace implementation {

using BufferDesc_1_1 = ::android::hardware::automotive::evs::V1_1::BufferDesc;

// Reads the image provided in 'filename' into bufferDesc.
// filename - Full filename of image with extension.
bool ReadPngIntoBuffer(const char* filename, sp<GraphicBuffer> pGfxBuffer);

}  // namespace implementation
}  // namespace V1_0
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android

#endif // SURROUNDVIEW_MOCK_EVS_IMAGEIO_H