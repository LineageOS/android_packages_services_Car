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
package com.android.car.hal;

import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusRequest;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusState;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioStreamState;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfig;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;

import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.List;

public class AudioHalService extends HalServiceBase {

    public static final int VEHICLE_AUDIO_FOCUS_REQUEST_GAIN =
            VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN;
    public static final int VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT =
            VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT;
    public static final int VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT_MAY_DUCK =
            VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT_MAY_DUCK;
    public static final int VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE =
            VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE;

    public static String audioFocusRequestToString(int request) {
        return VehicleAudioFocusRequest.enumToString(request);
    }

    public static final int VEHICLE_AUDIO_FOCUS_STATE_GAIN =
            VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN;
    public static final int VEHICLE_AUDIO_FOCUS_STATE_GAIN_TRANSIENT =
            VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN_TRANSIENT;
    public static final int VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK =
            VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK;
    public static final int VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT =
            VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT;
    public static final int VEHICLE_AUDIO_FOCUS_STATE_LOSS =
            VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS;
    public static final int VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_EXLCUSIVE =
            VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_EXLCUSIVE;

    public static String audioFocusStateToString(int state) {
        return VehicleAudioFocusState.enumToString(state);
    }

    public static final int VEHICLE_AUDIO_STREAM_STATE_STOPPED =
            VehicleAudioStreamState.VEHICLE_AUDIO_STREAM_STATE_STOPPED;
    public static final int VEHICLE_AUDIO_STREAM_STATE_STARTED =
            VehicleAudioStreamState.VEHICLE_AUDIO_STREAM_STATE_STARTED;

    public static String audioStreamStateToString(int state) {
        return VehicleAudioStreamState.enumToString(state);
    }

    public static final int STREAM_NUM_DEFAULT = 0;

    public interface AudioHalListener {
        void onFocusChange(int focusState, int streams);
        void onVolumeChange(int volume, int streamNumber);
        void onStreamStatusChange(int state, int streamNumber);
    }

    private final VehicleHal mVehicleHal;
    private AudioHalListener mListener;
    private boolean mFocusSupported = false;
    private boolean mVolumeSupported = false;

    public AudioHalService(VehicleHal vehicleHal) {
        mVehicleHal = vehicleHal;
    }

    public synchronized void setListener(AudioHalListener listener) {
        mListener = listener;
    }

    public synchronized void requestAudioFocusChange(int request, int streams) {
        int[] payload = { request, streams };
        mVehicleHal.getVehicleNetwork().setIntVectorProperty(
                VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, payload);
    }

    @Override
    public synchronized void init() {
        if (mFocusSupported) {
            mVehicleHal.subscribeProperty(this, VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS,
                    0);
            mVehicleHal.subscribeProperty(this,
                    VehicleNetworkConsts.VEHICLE_PROPERTY_INTERNAL_AUDIO_STREAM_STATE, 0);
        }
        if (mVolumeSupported) {
            mVehicleHal.subscribeProperty(this, VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME,
                    0);
        }
    }

    @Override
    public synchronized void release() {
        if (mFocusSupported) {
            mVehicleHal.unsubscribeProperty(this,
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS);
            mVehicleHal.unsubscribeProperty(this,
                    VehicleNetworkConsts.VEHICLE_PROPERTY_INTERNAL_AUDIO_STREAM_STATE);
            mFocusSupported = false;
        }
        if (mVolumeSupported) {
            mVehicleHal.unsubscribeProperty(this,
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME);
            mVolumeSupported = false;
        }
    }

    @Override
    public synchronized List<VehiclePropConfig> takeSupportedProperties(
            List<VehiclePropConfig> allProperties) {
        List<VehiclePropConfig> taken = new LinkedList<VehiclePropConfig>();
        for (VehiclePropConfig p : allProperties) {
            switch (p.getProp()) {
                case VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS:
                    mFocusSupported = true;
                    taken.add(p);
                    break;
                case VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME:
                    mVolumeSupported = true;
                    taken.add(p);
                    break;
                case VehicleNetworkConsts.VEHICLE_PROPERTY_INTERNAL_AUDIO_STREAM_STATE:
                    taken.add(p);
                    break;
            }
        }
        return taken;
    }

    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        AudioHalListener listener = null;
        synchronized (this) {
            listener = mListener;
        }
        if (listener == null) {
            //TODO queue this?
            return;
        }
        for (VehiclePropValue v : values) {
            switch (v.getProp()) {
                case VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS: {
                    int focusState = v.getInt32Values(0);
                    int streams = v.getInt32Values(1);
                    listener.onFocusChange(focusState, streams);
                } break;
                case VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME: {
                    int volume = v.getInt32Values(0);
                    int streamNum = v.getInt32Values(1);
                    listener.onVolumeChange(volume, streamNum);
                } break;
                case VehicleNetworkConsts.VEHICLE_PROPERTY_INTERNAL_AUDIO_STREAM_STATE: {
                    int state = v.getInt32Values(0);
                    int streamNum = v.getInt32Values(1);
                    listener.onStreamStatusChange(state, streamNum);
                } break;
            }
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*Sensor HAL*");
        writer.println(" focus supported:" + mFocusSupported +
                " volume supported:" + mVolumeSupported);
    }

}
