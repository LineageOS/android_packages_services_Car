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

package android.car.evs;

import android.car.evs.CarEvsBufferDescriptor;

/**
 * ICarEvsStreamCallback is the interface implemented by the clients to receive events and
 * frame buffers from Extended View System stream.
 *
 * @hide
 * @deprecated EVS functionality and APIs are deprecated.
 *             Applications should use the standard Android <a
 *             href="https://developer.android.com/media/camera/camera2">Camera2 API
 *             (android.hardware.camera2)</a> for camera access and management. Use either the
 *             Camera2 NDK APIs (<a
 *             href="https://developer.android.com/ndk/reference/group/camera#acameramanager">ACameraManager</a>)
 *             or Camera2 Java APIs ({@link android.hardware.camera2.CameraManager}) instead.
 */
oneway interface ICarEvsStreamCallback {
    /**
     * Called upon a new EVS stream event
     *
     * @deprecated EVS functionality and APIs are deprecated.
     *             Use the Camera2 NDK API (<a
     *             href="https://developer.android.com/ndk/reference/group/camera#acameracapturesession_capturecallbacksv2">ACameraCaptureSession_captureCallbacksV2</a>)
     *             or the Camera2 Java API ({@link
     *             android.hardware.camera2.CameraCaptureSession.CaptureCallback}) instead.
     */
    void onStreamEvent(int origin, int event);

    /**
     * Called when a new EVS frame arrives
     *
     * @deprecated EVS functionality and APIs are deprecated.
     *             The following Camera2 callbacks will be called to deliver the new image in the
     *             image reader queue:
     *             For the NDK:
     *             Retrieve from <a
     *             href="https://developer.android.com/ndk/reference/struct/a-image-reader-image-listener#onimageavailable">onImageAvailable</a>
     *             of <a
     *             href="https://developer.android.com/ndk/reference/struct/a-image-reader-image-listener">AImageReader_ImageListener</a>.
     *             For Java:
     *             Retrieve from {@link
     *             android.media.ImageReader.OnImageAvailableListener#onImageAvailable}.
     */
    void onNewFrame(in CarEvsBufferDescriptor buffer);
}
