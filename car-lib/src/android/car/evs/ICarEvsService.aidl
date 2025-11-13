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
import android.car.evs.CarEvsStatus;
import android.car.evs.ICarEvsStatusListener;
import android.car.evs.ICarEvsStreamCallback;
import android.os.IBinder;

/**
 * @hide
 * @deprecated EVS functionality and APIs are deprecated.
 *             Applications should use the standard Android <a
 *             href="https://developer.android.com/media/camera/camera2">Camera2 API
 *             (android.hardware.camera2)</a> for camera access and management. Use either the
 *             Camera2 NDK APIs
 *             (<a
 *             href="https://developer.android.com/ndk/reference/group/camera#acameramanager">ACameraManager</a>)
 *             or Camera2 Java APIs ({@link android.hardware.camera2.CameraManager}) instead.
 *             CarEvsService is
 *             deprecated, and OEMs who use this feature must transition the associated logic to an
 *             OEM-owned app:
 *             <ul>
 *                 <li>Monitor the <code>GEAR_SELECTION VHAL</code> property.</li>
 *                 <li>Launch the rear view camera activity when the reverse gear is activated.</li>
 *                 <li>Use Camera2 APIs to display the camera feed.</li>
 *             </ul>
 *             See <a
 *             href="https://source.android.com/docs/automotive/camera/acs/camera2-migration#camera2_4">here</a>
 *             for the recommended guidelines and for more information on implementing a rear view
 *             camera stream using Camera2 APIs.
 */
interface ICarEvsService {
    /**
     * Registers a listener to receive changes in CarEvsManager's status.
     *
     * @deprecated EVS functionality and APIs are deprecated.
     */
    void registerStatusListener(in ICarEvsStatusListener listener);

    /**
     * Unregisters a service listener.
     *
     * @deprecated EVS functionality and APIs are deprecated.
     */
    void unregisterStatusListener(in ICarEvsStatusListener listener);

    /**
     * Requests to start a video stream.
     *
     * @deprecated EVS functionality and APIs are deprecated.
     *             Use the Camera2 NDK API (<a
     *             href="https://developer.android.com/ndk/reference/group/camera#acameracapturesession_setrepeatingrequestv2">ACameraCaptureSession_setRepeatingRequestV2</a>)
     *             or the Camera2 Java API ({@link
     *             android.hardware.camera2.CameraCaptureSession#setSingleRepeatingRequest})
     *             instead.
     */
    int startVideoStream(int serviceType, in IBinder token, in ICarEvsStreamCallback callback);

    /**
     * Requests to stop an active video stream.
     *
     * @deprecated EVS functionality and APIs are deprecated.
     *             Use the Camera2 NDK API (<a
     *             href="https://developer.android.com/ndk/reference/group/camera#acameracapturesession_stoprepeating">ACameraCaptureSession_stopRepeating</a>)
     *             or the Camera2 Java API ({@link
     *             android.hardware.camera2.CameraCaptureSession#stopRepeating}) instead.
     */
    void stopVideoStream(in ICarEvsStreamCallback callback);

    /**
     * Requests to stop an active video stream from a given service type.
     *
     * @deprecated EVS functionality and APIs are deprecated.
     *             Use the Camera2 NDK API (<a
     *             href="https://developer.android.com/ndk/reference/group/camera#acameracapturesession_stoprepeating">ACameraCaptureSession_stopRepeating</a>)
     *             or the Camera2 Java API ({@link
     *             android.hardware.camera2.CameraCaptureSession#stopRepeating}) on a specific
     *             capture session instead.
     */
    void stopVideoStreamFrom(in int serviceType, in ICarEvsStreamCallback callback);

    /**
     * Returns the buffer when its usages are done.
     *
     * @deprecated EVS functionality and APIs are deprecated.
     *             Use the Camera2 NDK API (<a
     *             href="https://developer.android.com/ndk/reference/group/media#aimage_delete">AImage_delete</a>)
     *             or the Camera2 Java API ({@link android.media.Image#close}) instead.
     */
    void returnFrameBuffer(in CarEvsBufferDescriptor buffer);

    /**
     * Returns a current status of a given CarEvsService type.
     *
     * @deprecated EVS functionality and APIs are deprecated.
     */
    CarEvsStatus getCurrentStatus(in int serviceType);

    /**
     * Returns a generated session token.
     *
     * @deprecated EVS functionality and APIs are deprecated.
     */
    IBinder generateSessionToken();

    /**
     * Requests to start a camera previewing activity for a given service type.
     *
     * @deprecated EVS functionality and APIs are deprecated.
     */
    int startActivity(int type);

    /**
     * Requests to stop a camera previewing activity, which was launched via startActivity().
     *
     * @deprecated EVS functionality and APIs are deprecated.
     */
    void stopActivity();

    /**
     * Returns whether or not a given service type is supported.
     *
     * @deprecated EVS functionality and APIs are deprecated.
     */
    boolean isSupported(int type);
}
