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
#pragma once

#include <stdio.h>

#include <android/hardware/automotive/evs/1.1/IEvsEnumerator.h>
#include <android/hardware_buffer.h>
#include <ui/GraphicBuffer.h>
#include <utils/StrongPointer.h>

using namespace android::hardware::automotive::evs::V1_1;
using android::sp;
using android::GraphicBuffer;

using BufferDesc_1_0  = ::android::hardware::automotive::evs::V1_0::BufferDesc;

// Class handles display operations using EVS Display.
// TODO(197902107) : Make class thread-safe.
class DisplayHandler : public android::RefBase {
public:
    DisplayHandler(sp<IEvsDisplay> evsDisplay);

    // Sets the EVS display state to start displaying.
    bool startDisplay();

    // Provides a new GraphicBuffer that serves as the target for rendering. Once
    // rendering operation are complete call displayCurrentBuffer() to display onto screen.
    bool getNewDisplayBuffer(sp<GraphicBuffer>* pGfxBuffer);

    // Display the current buffer provided by getNewDisplayBuffer() onto the screen..
    bool displayCurrentBuffer();
private:
    BufferDesc convertBufferDesc(const BufferDesc_1_0& src);
    android::sp<IEvsDisplay> mEvsDisplay;
    BufferDesc_1_0 mTgtBuffer;
};