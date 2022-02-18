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

#ifndef CPP_EVS_SAMPLEDRIVER_AIDL_INCLUDE_GLWRAPPER_H
#define CPP_EVS_SAMPLEDRIVER_AIDL_INCLUDE_GLWRAPPER_H

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
#include <aidl/android/hardware/automotive/evs/BufferDesc.h>
#include <android-base/logging.h>
#include <android/frameworks/automotive/display/1.0/IAutomotiveDisplayProxyService.h>
#include <bufferqueueconverter/BufferQueueConverter.h>

namespace aidl::android::hardware::automotive::evs::implementation {

namespace automotivedisplay = ::android::frameworks::automotive::display::V1_0;

class GlWrapper {
public:
    GlWrapper() : mSurfaceHolder(::android::SurfaceHolderUniquePtr(nullptr, nullptr)) {}
    // TODO(b/170401743): using AIDL version when IAutomotiveDisplayProxyService is migrated.
    bool initialize(const ::android::sp<automotivedisplay::IAutomotiveDisplayProxyService>& svc,
                    uint64_t displayId);
    void shutdown();

    bool updateImageTexture(
            buffer_handle_t handle,
            const ::aidl::android::hardware::graphics::common::HardwareBufferDescription&
                    description);
    void renderImageToScreen();

    void showWindow(const ::android::sp<automotivedisplay::IAutomotiveDisplayProxyService>& svc,
                    uint64_t id);
    void hideWindow(const ::android::sp<automotivedisplay::IAutomotiveDisplayProxyService>& svc,
                    uint64_t id);

    unsigned getWidth() { return mWidth; };
    unsigned getHeight() { return mHeight; };

private:
    ::android::sp<::android::hardware::graphics::bufferqueue::V2_0::IGraphicBufferProducer>
            mGfxBufferProducer;

    EGLDisplay mDisplay;
    EGLSurface mSurface;
    EGLContext mContext;

    unsigned mWidth = 0;
    unsigned mHeight = 0;

    EGLImageKHR mKHRimage = EGL_NO_IMAGE_KHR;

    GLuint mTextureMap = 0;
    GLuint mShaderProgram = 0;

    // Opaque handle for a native hardware buffer defined in
    // frameworks/native/opengl/include/EGL/eglplatform.h
    ANativeWindow* mWindow;

    // Pointer to a Surface wrapper.
    ::android::SurfaceHolderUniquePtr mSurfaceHolder;
};

}  // namespace aidl::android::hardware::automotive::evs::implementation
   //
#endif  // CPP_EVS_SAMPLEDRIVER_AIDL_INCLUDE_GLWRAPPER_H
