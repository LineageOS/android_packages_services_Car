/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.google.android.car.kitchensink;

import android.car.Car;
import android.car.test.VehicleHalEmulator.VehicleHalPropertyHandler;
import android.car.test.VehicleHalEmulator;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusIndex;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusRequest;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusState;
import com.android.car.vehiclenetwork.VehiclePropConfigUtil;
import com.android.car.vehiclenetwork.VehiclePropValueUtil;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePermissionModel;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropAccess;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropChangeMode;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;

import java.util.ArrayList;
import java.util.List;

public class CarEmulator {

    private final Car mCar;
    private final VehicleHalEmulator mHalEmulator;

    private final AudioFocusPropertyHandler mAudioFocusPropertyHandler =
            new AudioFocusPropertyHandler();
    private final AudioStreamStatePropertyHandler mAudioStreamStatePropertyHandler =
            new AudioStreamStatePropertyHandler();
    private final AudioVolumePropertyHandler mAudioVolumePropertyHandler =
            new AudioVolumePropertyHandler();
    private final AudioVolumeLimitPropertyHandler mAudioVolumeLimitPropertyHandler =
            new AudioVolumeLimitPropertyHandler();
    private static final int STREAM_COUNT = 2;
    private static final int[] STREAM_MAX_VOLUME = {30, 30};
    private static final int[] STREAM_MIN_VOLUME = {0, 0};

    public CarEmulator(Car car) {
        mCar = car;
        mHalEmulator = new VehicleHalEmulator(car);
        List<Integer> maxVols = new ArrayList<>();
        List<Integer> minVols = new ArrayList<>();
        for(int i = 0; i < STREAM_COUNT; i++) {
            maxVols.add(STREAM_MAX_VOLUME[i]);
            minVols.add(STREAM_MIN_VOLUME[i]);
        }

        mHalEmulator.addProperty(
                VehiclePropConfigUtil.getBuilder(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC4,
                        VehiclePermissionModel.VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/).build(),
                mAudioFocusPropertyHandler);
        mHalEmulator.addProperty(
                VehiclePropConfigUtil.getBuilder(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_INTERNAL_AUDIO_STREAM_STATE,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC2,
                        VehiclePermissionModel.VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/).build(),
                        mAudioStreamStatePropertyHandler);
        mHalEmulator.addProperty(
                VehiclePropConfigUtil.getBuilder(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC3,
                        VehiclePermissionModel.VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/,
                        minVols, maxVols).build(),
                mAudioVolumePropertyHandler);
        mHalEmulator.addProperty(
                VehiclePropConfigUtil.getBuilder(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME_LIMIT,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC2,
                        VehiclePermissionModel.VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/).build(),
                mAudioVolumeLimitPropertyHandler);

        mHalEmulator.addProperty(
                VehiclePropConfigUtil.getBuilder(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_HW_KEY_INPUT,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC4,
                        VehiclePermissionModel.VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/).build(),
                null);
    }

    public void start() {
        mHalEmulator.start();
    }

    public void stop() {
        mHalEmulator.stop();
    }

    public void setAudioFocusControl(boolean reject) {
        mAudioFocusPropertyHandler.setAudioFocusControl(reject);
    }

    public void setStreamVolume(int stream, int volume) {
        mAudioVolumePropertyHandler.setStreamVolume(stream, volume);
    }

    public void injectVolumeKey(boolean up) {
        mAudioVolumePropertyHandler.injectVolumeKey(up);
    }

    private class AudioFocusPropertyHandler implements VehicleHalPropertyHandler {
        private boolean mRejectFocus;
        private int mCurrentFocusState = VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS;
        private int mCurrentFocusStreams = 0;
        private int mCurrentFocusExtState = 0;

        public void setAudioFocusControl(boolean reject) {
            VehiclePropValue injectValue = null;
            synchronized (this) {
                if (reject) {
                    if (!mRejectFocus) {
                        mCurrentFocusState =
                                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_EXLCUSIVE;
                        mCurrentFocusStreams = 0;
                        mCurrentFocusExtState = 0;
                        mRejectFocus = true;
                        int[] values = { mCurrentFocusState, mCurrentFocusStreams,
                                mCurrentFocusExtState, 0};
                        injectValue = VehiclePropValueUtil.createIntVectorValue(
                                VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, values,
                                SystemClock.elapsedRealtimeNanos());
                    }
                } else {
                    if (mRejectFocus) {
                        mCurrentFocusState = VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS;
                        mCurrentFocusStreams = 0;
                        mCurrentFocusExtState = 0;
                        int[] values = { mCurrentFocusState, mCurrentFocusStreams,
                                mCurrentFocusExtState, 0};
                        injectValue = VehiclePropValueUtil.createIntVectorValue(
                                VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, values,
                                SystemClock.elapsedRealtimeNanos());
                    }
                    mRejectFocus = false;
                }
            }
            if (injectValue != null) {
                mHalEmulator.injectEvent(injectValue);
            }
        }

        @Override
        public void onPropertySet(VehiclePropValue value) {
            VehiclePropValue injectValue = null;
            synchronized (this) {
                if (mRejectFocus) {
                    int[] values = { mCurrentFocusState, mCurrentFocusStreams,
                            mCurrentFocusExtState, 0};
                    injectValue = VehiclePropValueUtil.createIntVectorValue(
                            VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, values,
                            SystemClock.elapsedRealtimeNanos());
                } else {
                    int request = value.getInt32Values(
                            VehicleAudioFocusIndex.VEHICLE_AUDIO_FOCUS_INDEX_FOCUS);
                    int requestedStreams = value.getInt32Values(
                            VehicleAudioFocusIndex.VEHICLE_AUDIO_FOCUS_INDEX_STREAMS);
                    int requestedExtFocus = value.getInt32Values(
                            VehicleAudioFocusIndex.VEHICLE_AUDIO_FOCUS_INDEX_EXTERNAL_FOCUS_STATE);
                    int response = VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS;
                    switch (request) {
                        case VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN:
                            response = VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN;
                            break;
                        case VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT:
                        case VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT_MAY_DUCK:
                            response =
                                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN_TRANSIENT;
                            break;
                        case VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE:
                            response = VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS;
                            break;
                    }
                    mCurrentFocusState = response;
                    mCurrentFocusStreams = requestedStreams;
                    mCurrentFocusExtState = requestedExtFocus;
                    int[] values = { mCurrentFocusState, mCurrentFocusStreams,
                            mCurrentFocusExtState, 0};
                    injectValue = VehiclePropValueUtil.createIntVectorValue(
                            VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, values,
                            SystemClock.elapsedRealtimeNanos());
                }
            }
            if (injectValue != null) {
                mHalEmulator.injectEvent(injectValue);
            }
        }

        @Override
        public synchronized VehiclePropValue onPropertyGet(VehiclePropValue value) {
            int[] values = { mCurrentFocusState, mCurrentFocusStreams,
                    mCurrentFocusExtState, 0};
            return VehiclePropValueUtil.createIntVectorValue(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, values,
                    SystemClock.elapsedRealtimeNanos());
        }

        @Override
        public void onPropertySubscribe(int property, float sampleRate, int zones) {
        }

        @Override
        public void onPropertyUnsubscribe(int property) {
        }
    }

    private class AudioStreamStatePropertyHandler implements VehicleHalPropertyHandler {
        @Override
        public void onPropertySet(VehiclePropValue value) {
        }

        @Override
        public VehiclePropValue onPropertyGet(VehiclePropValue value) {
            //ignore
            return null;
        }

        @Override
        public void onPropertySubscribe(int property, float sampleRate, int zones) {
        }

        @Override
        public void onPropertyUnsubscribe(int property) {
        }
    }

    private static class AudioVolumeLimitPropertyHandler implements VehicleHalPropertyHandler {
        @Override
        public void onPropertySet(VehiclePropValue value) {
        }

        @Override
        public VehiclePropValue onPropertyGet(VehiclePropValue value) {
            int stream = value.getInt32Values(
                    VehicleNetworkConsts.VehicleAudioVolumeLimitIndex
                            .VEHICLE_AUDIO_VOLUME_LIMIT_INDEX_STREAM);

            int[] values = {stream, 20};
            VehiclePropValue propValue = VehiclePropValueUtil.createIntVectorValue(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME_LIMIT, values,
                    SystemClock.elapsedRealtimeNanos());
            return propValue;
        }

        @Override
        public void onPropertySubscribe(int property, float sampleRate, int zones) {
        }

        @Override
        public void onPropertyUnsubscribe(int property) {
        }
    }

    private class AudioVolumePropertyHandler implements VehicleHalPropertyHandler {
        private int[] mStreamVolume = {0, 0};

        public void setStreamVolume(int stream, int index) {
            if (stream > 1) {
                return;
            }
            mStreamVolume[stream] = index;
            VehiclePropValue injectValue = null;
            int[] values = { stream, index, 0 };
            injectValue = VehiclePropValueUtil.createIntVectorValue(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME, values,
                    SystemClock.elapsedRealtimeNanos());
            mHalEmulator.injectEvent(injectValue);
        }

        public void injectVolumeKey(boolean up) {
            int[] values = {
                    VehicleNetworkConsts.VehicleHwKeyInputAction.VEHICLE_HW_KEY_INPUT_ACTION_DOWN,
                    up ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0 };

            VehiclePropValue injectValue = VehiclePropValueUtil.createIntVectorValue(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_HW_KEY_INPUT, values,
                    SystemClock.elapsedRealtimeNanos());

            mHalEmulator.injectEvent(injectValue);

            int[] upValues = {
                    VehicleNetworkConsts.VehicleHwKeyInputAction.VEHICLE_HW_KEY_INPUT_ACTION_UP,
                    up ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0 };

            injectValue = VehiclePropValueUtil.createIntVectorValue(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_HW_KEY_INPUT, upValues,
                    SystemClock.elapsedRealtimeNanos());

            mHalEmulator.injectEvent(injectValue);
        }

        @Override
        public void onPropertySet(VehiclePropValue value) {
            int stream = value.getInt32Values(
                    VehicleNetworkConsts.VehicleAudioVolumeIndex.VEHICLE_AUDIO_VOLUME_INDEX_STREAM);
            int volume = value.getInt32Values(
                    VehicleNetworkConsts.VehicleAudioVolumeIndex.VEHICLE_AUDIO_VOLUME_INDEX_VOLUME);
            int state = value.getInt32Values(
                    VehicleNetworkConsts.VehicleAudioVolumeIndex.VEHICLE_AUDIO_VOLUME_INDEX_STATE);

            int[] values = {stream, volume, state};
            VehiclePropValue injectValue = VehiclePropValueUtil.createIntVectorValue(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME, values,
                    SystemClock.elapsedRealtimeNanos());
            mStreamVolume[stream] = volume;
            mHalEmulator.injectEvent(injectValue);
        }

        @Override
        public VehiclePropValue onPropertyGet(VehiclePropValue value) {
            int stream = value.getInt32Values(
                    VehicleNetworkConsts.VehicleAudioVolumeIndex.VEHICLE_AUDIO_VOLUME_INDEX_STREAM);

            int volume = mStreamVolume[stream];
            int[] values = {stream, volume, 0};
            VehiclePropValue propValue = VehiclePropValueUtil.createIntVectorValue(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME, values,
                    SystemClock.elapsedRealtimeNanos());
            return propValue;
        }

        @Override
        public void onPropertySubscribe(int property, float sampleRate, int zones) {
        }

        @Override
        public void onPropertyUnsubscribe(int property) {
        }
    }
}
