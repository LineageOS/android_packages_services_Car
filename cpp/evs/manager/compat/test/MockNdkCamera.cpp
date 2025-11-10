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

#include "MockNdkCamera.h"

// C-style wrapper functions to be called by the code under test
#ifdef __cplusplus
extern "C" {
#endif

media_status_t AImageReader_new(int32_t width, int32_t height, int32_t format, int32_t maxImages,
                                AImageReader** reader) {
    return MockNdkCamera::getMockInstance()->AImageReader_new(width, height, format, maxImages,
                                                              reader);
}

media_status_t AImageReader_newWithUsage(int32_t width, int32_t height, int32_t format,
                                         uint64_t usage, int32_t maxImages, AImageReader** reader) {
    return MockNdkCamera::getMockInstance()->AImageReader_newWithUsage(width, height, format, usage,
                                                                       maxImages, reader);
}

media_status_t AImageReader_getWindow(AImageReader* reader, ANativeWindow** window) {
    return MockNdkCamera::getMockInstance()->AImageReader_getWindow(reader, window);
}

media_status_t AImageReader_setImageListener(AImageReader* reader,
                                             AImageReader_ImageListener* listener) {
    return MockNdkCamera::getMockInstance()->AImageReader_setImageListener(reader, listener);
}

void AImageReader_delete(AImageReader* reader) {
    MockNdkCamera::getMockInstance()->AImageReader_delete(reader);
}

camera_status_t ACameraOutputTarget_create(ANativeWindow* window,
                                           ACameraOutputTarget** outputTarget) {
    return MockNdkCamera::getMockInstance()->ACameraOutputTarget_create(window, outputTarget);
}

void ACameraOutputTarget_free(ACameraOutputTarget* outputTarget) {
    MockNdkCamera::getMockInstance()->ACameraOutputTarget_free(outputTarget);
}

camera_status_t ACaptureSessionOutput_create(ANativeWindow* window,
                                             ACaptureSessionOutput** output) {
    return MockNdkCamera::getMockInstance()->ACaptureSessionOutput_create(window, output);
}

void ACaptureSessionOutput_free(ACaptureSessionOutput* output) {
    MockNdkCamera::getMockInstance()->ACaptureSessionOutput_free(output);
}

camera_status_t ACaptureSessionOutputContainer_create(ACaptureSessionOutputContainer** container) {
    return MockNdkCamera::getMockInstance()->ACaptureSessionOutputContainer_create(container);
}

camera_status_t ACaptureSessionOutputContainer_add(ACaptureSessionOutputContainer* container,
                                                   const ACaptureSessionOutput* output) {
    return MockNdkCamera::getMockInstance()->ACaptureSessionOutputContainer_add(container, output);
}

void ACaptureSessionOutputContainer_free(ACaptureSessionOutputContainer* container) {
    MockNdkCamera::getMockInstance()->ACaptureSessionOutputContainer_free(container);
}

camera_status_t ACameraDevice_createCaptureSession(
        ACameraDevice* device, const ACaptureSessionOutputContainer* outputs,
        const ACameraCaptureSession_stateCallbacks* callbacks, ACameraCaptureSession** session) {
    return MockNdkCamera::getMockInstance()->ACameraDevice_createCaptureSession(device, outputs,
                                                                                callbacks, session);
}

void ACameraCaptureSession_close(ACameraCaptureSession* session) {
    MockNdkCamera::getMockInstance()->ACameraCaptureSession_close(session);
}

camera_status_t ACameraDevice_createCaptureRequest(const ACameraDevice* device,
                                                   ACameraDevice_request_template templateId,
                                                   ACaptureRequest** request) {
    return MockNdkCamera::getMockInstance()->ACameraDevice_createCaptureRequest(device, templateId,
                                                                                request);
}

camera_status_t ACaptureRequest_addTarget(ACaptureRequest* request,
                                          const ACameraOutputTarget* outputTarget) {
    return MockNdkCamera::getMockInstance()->ACaptureRequest_addTarget(request, outputTarget);
}

void ACaptureRequest_free(ACaptureRequest* request) {
    MockNdkCamera::getMockInstance()->ACaptureRequest_free(request);
}

camera_status_t ACameraCaptureSession_setRepeatingRequest(
        ACameraCaptureSession* session, ACameraCaptureSession_captureCallbacks* callbacks,
        int numRequests, ACaptureRequest** requests, int* sequenceId) {
    return MockNdkCamera::getMockInstance()->ACameraCaptureSession_setRepeatingRequest(session,
                                                                                       callbacks,
                                                                                       numRequests,
                                                                                       requests,
                                                                                       sequenceId);
}

camera_status_t ACameraCaptureSession_stopRepeating(ACameraCaptureSession* session) {
    return MockNdkCamera::getMockInstance()->ACameraCaptureSession_stopRepeating(session);
}

media_status_t AImage_getHardwareBuffer(const AImage* image, AHardwareBuffer** buffer) {
    return MockNdkCamera::getMockInstance()->AImage_getHardwareBuffer(image, buffer);
}

void AImage_delete(AImage* image) {
    MockNdkCamera::getMockInstance()->AImage_delete(image);
}

media_status_t AImage_getTimestamp(const AImage* image, int64_t* timestamp) {
    return MockNdkCamera::getMockInstance()->AImage_getTimestamp(image, timestamp);
}

const native_handle_t* AHardwareBuffer_getNativeHandle(const AHardwareBuffer* buffer) {
    return MockNdkCamera::getMockInstance()->AHardwareBuffer_getNativeHandle(buffer);
}

void AHardwareBuffer_release(AHardwareBuffer* buffer) {
    MockNdkCamera::getMockInstance()->AHardwareBuffer_release(buffer);
}

camera_status_t ACameraMetadata_getConstEntry(const ACameraMetadata* metadata, uint32_t tag,
                                              ACameraMetadata_const_entry* entry) {
    return MockNdkCamera::getMockInstance()->ACameraMetadata_getConstEntry(metadata, tag, entry);
}

ACameraMetadata* ACameraMetadata_copy(const ACameraMetadata* src) {
    return MockNdkCamera::getMockInstance()->ACameraMetadata_copy(src);
}

void ACameraMetadata_free(ACameraMetadata* metadata) {
    MockNdkCamera::getMockInstance()->ACameraMetadata_free(metadata);
}

#ifdef __cplusplus
}
#endif
