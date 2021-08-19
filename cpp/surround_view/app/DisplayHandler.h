/*
 * Copyright 2020 The Android Open Source Project
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

#include <utils/StrongPointer.h>

#include <android/hardware/automotive/evs/1.1/IEvsEnumerator.h>
#include <android/hardware_buffer.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>

using namespace android::hardware::automotive::evs::V1_1;
using android::hardware::graphics::common::V1_2::HardwareBuffer;

using BufferDesc_1_0  = ::android::hardware::automotive::evs::V1_0::BufferDesc;

// Class handles display operations. Uses EVS Display and OpenGLES.
class DisplayHandler : public android::RefBase {
public:
    DisplayHandler(android::sp<IEvsDisplay> pDisplay);

    // Initializes OpenGLES and sets EVS Display state.
    bool startDisplay();

    // Returns the OpenGLES display handler. Returns null if not initialized.
    EGLDisplay getDisplay();

    // Returns the OpenGLES surface handler. Returns null if not initialized.
    EGLSurface getSurface();

    // Returns the OpenGLES context handler. Returns null if not initialized.
    EGLContext getContext();

    // Renders the provided Hardware buffer to the screen.
    // To be used for SV2D and SV3D without external rendering.
    bool renderBufferToScreen(const HardwareBuffer& hardwareBuffer);

    // Renders the current OpenGLES target buffer to screen and adds a new target buffer.
    // To be used for SV3D with external rendering support.
    bool renderGlTargetToScreen();
private:
    bool prepareGL();
    bool makeContextCurrent();
    bool clearContext();
    static const char* getEGLError(void);
    static const std::string getGLFramebufferError(void);

    BufferDesc convertBufferDesc(const BufferDesc_1_0& src);

    // Obtains a new EVS Display buffer and attaches it as OpenGLES target render buffer.
    bool attachNewRenderTarget();

    // Detaches OpenGLES target render buffer and displays it onto the screen.
    bool detachAndDisplayCurrRenderTarget();

    static EGLDisplay   sGLDisplay;
    static EGLSurface   sGLSurface;
    static EGLContext   sGLContext;
    static GLuint       sFrameBuffer;
    static GLuint       sColorBuffer;
    static GLuint       sDepthBuffer;
    static GLuint       sTextureId;
    static EGLImageKHR  sKHRimage;

    android::sp<IEvsDisplay> mDisplay;
    BufferDesc_1_0 mTgtBuffer;
};
