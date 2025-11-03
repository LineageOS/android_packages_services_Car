\
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

#ifndef CPP_EVS_MANAGER_COMPAT_TEST_DUMMYNDKOBJECTS_H
#define CPP_EVS_MANAGER_COMPAT_TEST_DUMMYNDKOBJECTS_H

#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraError.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraMetadataTags.h>
#include <camera/NdkCaptureRequest.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

// Dummy NDK object pointers
inline auto* dummyReader = reinterpret_cast<AImageReader*>(0x1001);
inline auto* dummyWindow = reinterpret_cast<ANativeWindow*>(0x1002);
inline auto* dummyOutputTarget = reinterpret_cast<ACameraOutputTarget*>(0x1003);
inline auto* dummySessionOutput = reinterpret_cast<ACaptureSessionOutput*>(0x1004);
inline auto* dummyOutputContainer = reinterpret_cast<ACaptureSessionOutputContainer*>(0x1005);
inline auto* dummySession = reinterpret_cast<ACameraCaptureSession*>(0x1006);
inline auto* dummyCaptureRequest = reinterpret_cast<ACaptureRequest*>(0x1007);
inline auto* dummyDevice = reinterpret_cast<ACameraDevice*>(0x1008);

#endif  // CPP_EVS_MANAGER_COMPAT_TEST_DUMMYNDKOBJECTS_H
