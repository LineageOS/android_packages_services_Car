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

#pragma once

#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraError.h>
#include <camera/NdkCaptureRequest.h>
#include <cutils/native_handle.h>
#include <gmock/gmock.h>
#include <media/NdkImageReader.h>

class MockNdkCamera {
public:
    MOCK_METHOD(media_status_t, AImageReader_new,
                (int32_t width, int32_t height, int32_t format, int32_t maxImages,
                 AImageReader** reader));
    MOCK_METHOD(media_status_t, AImageReader_newWithUsage,
                (int32_t width, int32_t height, int32_t format, uint64_t usage, int32_t maxImages,
                 AImageReader** reader));
    MOCK_METHOD(media_status_t, AImageReader_getWindow,
                (AImageReader * reader, ANativeWindow** window));
    MOCK_METHOD(media_status_t, AImageReader_setImageListener,
                (AImageReader * reader, AImageReader_ImageListener* listener));
    MOCK_METHOD(void, AImageReader_delete, (AImageReader * reader));

    MOCK_METHOD(media_status_t, AImage_getHardwareBuffer,
                (const AImage* image, AHardwareBuffer** buffer));
    MOCK_METHOD(void, AImage_delete, (AImage * image));
    MOCK_METHOD(media_status_t, AImage_getTimestamp, (const AImage* image, int64_t* timestamp));

    MOCK_METHOD(const native_handle_t*, AHardwareBuffer_getNativeHandle,
                (const AHardwareBuffer* buffer));
    MOCK_METHOD(void, AHardwareBuffer_release, (AHardwareBuffer * buffer));
    MOCK_METHOD(void, AHardwareBuffer_describe,
                (const AHardwareBuffer* buffer, AHardwareBuffer_Desc* outDesc));

    MOCK_METHOD(camera_status_t, ACameraOutputTarget_create,
                (ANativeWindow * window, ACameraOutputTarget** outputTarget));
    MOCK_METHOD(void, ACameraOutputTarget_free, (ACameraOutputTarget * outputTarget));

    MOCK_METHOD(camera_status_t, ACaptureSessionOutput_create,
                (ANativeWindow * window, ACaptureSessionOutput** output));
    MOCK_METHOD(void, ACaptureSessionOutput_free, (ACaptureSessionOutput * output));

    MOCK_METHOD(camera_status_t, ACaptureSessionOutputContainer_create,
                (ACaptureSessionOutputContainer * *container));
    MOCK_METHOD(camera_status_t, ACaptureSessionOutputContainer_add,
                (ACaptureSessionOutputContainer * container, const ACaptureSessionOutput* output));
    MOCK_METHOD(void, ACaptureSessionOutputContainer_free,
                (ACaptureSessionOutputContainer * container));

    MOCK_METHOD(camera_status_t, ACameraDevice_createCaptureSession,
                (ACameraDevice * device, const ACaptureSessionOutputContainer* outputs,
                 const ACameraCaptureSession_stateCallbacks* callbacks,
                 ACameraCaptureSession** session));
    MOCK_METHOD(void, ACameraCaptureSession_close, (ACameraCaptureSession * session));

    MOCK_METHOD(camera_status_t, ACameraCaptureSessionShared_startStreaming,
                (ACameraCaptureSession * session,
                 ACameraCaptureSession_captureCallbacksV2* callbacks, int numWindows,
                 ANativeWindow** windows, int* sequenceId));
    MOCK_METHOD(camera_status_t, ACameraCaptureSessionShared_stopStreaming,
                (ACameraCaptureSession * session));

    MOCK_METHOD(camera_status_t, ACameraDevice_createCaptureRequest,
                (const ACameraDevice* device, ACameraDevice_request_template templateId,
                 ACaptureRequest** request));
    MOCK_METHOD(camera_status_t, ACaptureRequest_addTarget,
                (ACaptureRequest * request, const ACameraOutputTarget* outputTarget));
    MOCK_METHOD(void, ACaptureRequest_free, (ACaptureRequest * request));

    MOCK_METHOD(camera_status_t, ACameraCaptureSession_setRepeatingRequestV2,
                (ACameraCaptureSession * session,
                 ACameraCaptureSession_captureCallbacksV2* callbacks, int numRequests,
                 ACaptureRequest** requests, int* sequenceId));
    MOCK_METHOD(camera_status_t, ACameraCaptureSession_stopRepeating,
                (ACameraCaptureSession * session));

    MOCK_METHOD(camera_status_t, ACameraMetadata_getConstEntry,
                (const ACameraMetadata* metadata, uint32_t tag,
                 ACameraMetadata_const_entry* entry));
    MOCK_METHOD(camera_status_t, ACameraMetadata_getAllTags,
                (const ACameraMetadata* metadata, int32_t* numEntries, const uint32_t** tags));
    MOCK_METHOD(ACameraMetadata*, ACameraMetadata_copy, (const ACameraMetadata* src));
    MOCK_METHOD(void, ACameraMetadata_free, (ACameraMetadata * metadata));

    MOCK_METHOD(camera_status_t, ACaptureRequest_setEntry_u8,
                (ACaptureRequest * request, uint32_t tag, uint32_t count, const uint8_t* data));
    MOCK_METHOD(camera_status_t, ACaptureRequest_setEntry_i32,
                (ACaptureRequest * request, uint32_t tag, uint32_t count, const int32_t* data));
    MOCK_METHOD(camera_status_t, ACaptureRequest_setEntry_i64,
                (ACaptureRequest * request, uint32_t tag, uint32_t count, const int64_t* data));
    MOCK_METHOD(camera_status_t, ACaptureRequest_setEntry_float,
                (ACaptureRequest * request, uint32_t tag, uint32_t count, const float* data));

    static void setMockInstance(MockNdkCamera* mock) { sMockInstance = mock; }

    static MockNdkCamera* getMockInstance() { return sMockInstance; }

private:
    static inline MockNdkCamera* sMockInstance = nullptr;
};
