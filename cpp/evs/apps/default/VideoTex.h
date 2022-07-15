/*
 * Copyright (C) 2017 The Android Open Source Project
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
#ifndef VIDEOTEX_H
#define VIDEOTEX_H

#include "StreamHandler.h"
#include "TexWrapper.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
#include <android/hardware/automotive/evs/1.1/IEvsEnumerator.h>
#include <android/hardware/camera/device/3.2/ICameraDevice.h>

#include <system/graphics-base.h>

using ::android::hardware::automotive::evs::V1_1::BufferDesc;
using ::android::hardware::automotive::evs::V1_1::IEvsCamera;
using ::android::hardware::automotive::evs::V1_1::IEvsEnumerator;
using ::android::hardware::camera::device::V3_2::Stream;

class VideoTex : public TexWrapper {
    friend VideoTex* createVideoTexture(android::sp<IEvsEnumerator> pEnum, const char* evsCameraId,
                                        std::unique_ptr<Stream> streamCfg, EGLDisplay glDisplay,
                                        bool useExternalMemory, android_pixel_format_t format);

public:
    VideoTex() = delete;
    virtual ~VideoTex();

    bool refresh();  // returns true if the texture contents were updated

private:
    VideoTex(android::sp<IEvsEnumerator> pEnum, android::sp<IEvsCamera> pCamera,
             android::sp<StreamHandler> pStreamHandler, EGLDisplay glDisplay);

    android::sp<IEvsEnumerator> mEnumerator;
    android::sp<IEvsCamera> mCamera;
    android::sp<StreamHandler> mStreamHandler;
    BufferDesc mImageBuffer;

    EGLDisplay mDisplay;
    EGLImageKHR mKHRimage = EGL_NO_IMAGE_KHR;
};

// Creates a video texture to draw the camera preview.  format is effective only
// when useExternalMemory is true.
VideoTex* createVideoTexture(android::sp<IEvsEnumerator> pEnum, const char* deviceName,
                             std::unique_ptr<Stream> streamCfg, EGLDisplay glDisplay,
                             bool useExternalMemory = false,
                             android_pixel_format_t format = HAL_PIXEL_FORMAT_RGBA_8888);

#endif  // VIDEOTEX_H
