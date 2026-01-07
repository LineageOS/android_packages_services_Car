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
package com.android.car.evs;

import static android.car.feature.Flags.FLAG_CAR_EVS_DEPRECATION;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.FlaggedApi;
import android.car.builtin.util.Slogf;
import android.car.evs.CarEvsManager;
import android.car.evs.CarEvsManager.CarEvsServiceType;
import android.car.evs.CarEvsManager.CarEvsStreamEvent;
import android.content.ComponentName;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.evs.CarEvsUtils;

/**
 * Utility class for CarEvsService
 *
 * @hide
 * @deprecated EVS functionality and APIs are deprecated. Applications should use the standard
 *     Android <a href="https://developer.android.com/media/camera/camera2">Camera2 API
 *     (android.hardware.camera2)</a> for camera access and management. Use either the Camera2 NDK
 *     APIs (<a
 *     href="https://developer.android.com/ndk/reference/group/camera#acameramanager">ACameraManager</a>)
 *     or Camera2 Java APIs ({@link android.hardware.camera2.CameraManager}) instead.
 */
@FlaggedApi(FLAG_CAR_EVS_DEPRECATION)
@Deprecated
public final class CarEvsServiceUtils {
    private static final String TAG = CarEvsServiceUtils.class.getSimpleName();

    private static final int INVALID_SERVICE_TYPE = -1;

    private static String INVALID_CAMERA_ID = "";

    private CarEvsServiceUtils() {}

    /**
     * Converts EvsEvent to CarEvsManager.CarEvsStreamEvent.
     *
     * See the definition of EvsEventType in hardware/interfaces/automotive/evs/1.1/types.hal
     */
    static @CarEvsStreamEvent int convertToStreamEvent(int inputEvent) {
        @CarEvsStreamEvent int outputStatus = CarEvsManager.STREAM_EVENT_NONE;

        switch (inputEvent) {
            case 0: // EvsEventType.STREAM_STARTED
                outputStatus = CarEvsManager.STREAM_EVENT_STREAM_STARTED;
                break;
            case 1: // EvsEventType.STREAM_STOPPED
                outputStatus = CarEvsManager.STREAM_EVENT_STREAM_STOPPED;
                break;
            case 2: // EvsEventType.FRAME_DROPPED
                outputStatus = CarEvsManager.STREAM_EVENT_FRAME_DROPPED;
                break;
            case 3: // EvsEventType.TIMEOUT
                outputStatus = CarEvsManager.STREAM_EVENT_TIMEOUT;
                break;
            case 4: // EvsEventType.PARAMETER_CHANGED
                outputStatus = CarEvsManager.STREAM_EVENT_PARAMETER_CHANGED;
                break;
            case 5: // EvsEventType.MASTER_RELEASED
                outputStatus = CarEvsManager.STREAM_EVENT_PRIMARY_OWNER_CHANGED;
                break;
            case 6: // EvsEventType.STREAM_ERROR
                outputStatus = CarEvsManager.STREAM_EVENT_OTHER_ERRORS;
                break;
            default:
                Slogf.w(TAG, "Invalid event type: " + inputEvent);
                break;
        }

        return outputStatus;
    }

    final static class Parameters {
        private final @CarEvsServiceType int mServiceType;
        private final ComponentName mActivityName;
        private String mCameraId;

        private Parameters(int type, String cameraId, String activityName) {
            mServiceType = type;
            mCameraId = cameraId;
            if (activityName != null && !activityName.isEmpty()) {
                mActivityName = ComponentName.unflattenFromString(activityName);
            } else {
                mActivityName = null;
            }
        }

        static Parameters create(int type, String cameraId, String activityName) {
            return new Parameters(type, cameraId, activityName);
        }

        @CarEvsServiceType int getType() { return mServiceType; }
        String getCameraId() { return mCameraId; }
        ComponentName getActivityComponentName() { return mActivityName; }

        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
        public String toString() {
            return "Parameter serviceType=" + CarEvsUtils.convertToString(mServiceType) +
                   ", cameraId=" + mCameraId + ", activityName=" + mActivityName;
        }
    }

    static Parameters parse(String rawString) {
        @CarEvsServiceType int serviceType = INVALID_SERVICE_TYPE;
        String activityName = null;
        String cameraId = INVALID_CAMERA_ID;

        // Example a service configuration string:
        // <item>serviceType=REARVIEW,cameraId=/dev/video0,
        //    activityName=com.android.car/com.google.android.car.evs.CarEvsCameraPreviewActivity
        // </item>
        // <item>serviceType=FRONTVIEW,cameraId=/dev/video1</item>
        String[] tokens = rawString.split(",");
        for (String token : tokens) {
            String[] keyValuePair = token.split("=");
            if (keyValuePair.length != 2) {
                Slogf.w(TAG, "Skip a key-value pair in incorrect format, " + token);
                continue;
            }

            switch (keyValuePair[0]) {
                case "serviceType":
                    serviceType = CarEvsUtils.convertToServiceType(keyValuePair[1]);
                    break;

                case "cameraId":
                    cameraId = keyValuePair[1];
                    break;

                case "activityName":
                    activityName = keyValuePair[1];
                    break;

                default:
                    Slogf.e(TAG, "Unknown parameter: " + token);
                    break;
            }
        }

        return Parameters.create(serviceType, cameraId, activityName);
    }
}
