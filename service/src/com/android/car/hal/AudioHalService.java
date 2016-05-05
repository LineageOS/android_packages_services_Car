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

import android.car.VehicleZoneUtil;
import android.car.media.CarAudioManager;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.Pair;

import com.android.car.AudioRoutingPolicy;
import com.android.car.CarAudioAttributesUtil;
import com.android.car.CarLog;
import com.android.car.vehiclenetwork.VehicleNetwork;
import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioContextFlag;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioExtFocusFlag;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusIndex;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusRequest;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusState;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioHwVariantConfigFlag;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioRoutingPolicyIndex;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioStreamState;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioStreamStateIndex;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioVolumeCapabilityFlag;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioVolumeIndex;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioVolumeLimitIndex;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfig;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfigs;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehiclePropValueUtil;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class AudioHalService extends HalServiceBase {
    public static final int VEHICLE_AUDIO_FOCUS_REQUEST_INVALID = -1;
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

    public static final int VEHICLE_AUDIO_FOCUS_STATE_INVALID = -1;
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

    public static final int VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG =
            VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG;
    public static final int VEHICLE_AUDIO_EXT_FOCUS_CAR_PERMANENT_FLAG =
            VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_CAR_PERMANENT_FLAG;
    public static final int VEHICLE_AUDIO_EXT_FOCUS_CAR_TRANSIENT_FLAG =
            VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_CAR_TRANSIENT_FLAG;
    public static final int VEHICLE_AUDIO_EXT_FOCUS_CAR_PLAY_ONLY_FLAG =
            VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_CAR_PLAY_ONLY_FLAG;
    public static final int VEHICLE_AUDIO_EXT_FOCUS_CAR_MUTE_MEDIA_FLAG =
            VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_CAR_MUTE_MEDIA_FLAG;

    public static final int STREAM_NUM_DEFAULT = 0;

    public static final int FOCUS_STATE_ARRAY_INDEX_STATE = 0;
    public static final int FOCUS_STATE_ARRAY_INDEX_STREAMS = 1;
    public static final int FOCUS_STATE_ARRAY_INDEX_EXTERNAL_FOCUS = 2;

    public static final int AUDIO_CONTEXT_MUSIC_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_MUSIC_FLAG;
    public static final int AUDIO_CONTEXT_NAVIGATION_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_NAVIGATION_FLAG;
    public static final int AUDIO_CONTEXT_VOICE_COMMAND_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_VOICE_COMMAND_FLAG;
    public static final int AUDIO_CONTEXT_CALL_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_CALL_FLAG;
    public static final int AUDIO_CONTEXT_ALARM_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_ALARM_FLAG;
    public static final int AUDIO_CONTEXT_NOTIFICATION_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_NOTIFICATION_FLAG;
    public static final int AUDIO_CONTEXT_UNKNOWN_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_UNKNOWN_FLAG;
    public static final int AUDIO_CONTEXT_SAFETY_ALERT_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_SAFETY_ALERT_FLAG;
    public static final int AUDIO_CONTEXT_RADIO_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_RADIO_FLAG;
    public static final int AUDIO_CONTEXT_CD_ROM_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_CD_ROM_FLAG;
    public static final int AUDIO_CONTEXT_AUX_AUDIO_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_AUX_AUDIO_FLAG;
    public static final int AUDIO_CONTEXT_SYSTEM_SOUND_FLAG =
            VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_SYSTEM_SOUND_FLAG;

    public interface AudioHalFocusListener {
        /**
         * Audio focus change from car.
         * @param focusState
         * @param streams
         * @param externalFocus Flags of active external audio focus.
         *            0 means no external audio focus.
         */
        void onFocusChange(int focusState, int streams, int externalFocus);
        /**
         * Stream state change (start / stop) from android
         * @param streamNumber
         * @param state
         */
        void onStreamStatusChange(int streamNumber, int state);
    }

    public interface AudioHalVolumeListener {
        /**
         * Audio volume change from car.
         * @param streamNumber
         * @param volume
         * @param volumeState
         */
        void onVolumeChange(int streamNumber, int volume, int volumeState);
        /**
         * Volume limit change from car.
         * @param streamNumber
         * @param volume
         */
        void onVolumeLimitChange(int streamNumber, int volume);
    }

    private final VehicleHal mVehicleHal;
    private AudioHalFocusListener mFocusListener;
    private AudioHalVolumeListener mVolumeListener;
    private int mVariant;

    private final HashMap<Integer, VehiclePropConfig> mProperties = new HashMap<>();

    public AudioHalService(VehicleHal vehicleHal) {
        mVehicleHal = vehicleHal;
    }

    public synchronized void setFocusListener(AudioHalFocusListener focusListener) {
        mFocusListener = focusListener;
    }

    public synchronized void setVolumeListener(AudioHalVolumeListener volumeListener) {
        mVolumeListener = volumeListener;
    }

    public void setAudioRoutingPolicy(AudioRoutingPolicy policy) {
        VehicleNetwork vn = mVehicleHal.getVehicleNetwork();
        VehiclePropConfigs configs = vn.listProperties(
                VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_ROUTING_POLICY);
        if (configs == null) {
            Log.w(CarLog.TAG_AUDIO,
                    "Vehicle HAL did not implement VEHICLE_PROPERTY_AUDIO_ROUTING_POLICY");
            return;
        }
        int[] policyToSet = new int[2];
        for (int i = 0; i < policy.getPhysicalStreamsCount(); i++) {
            policyToSet[VehicleAudioRoutingPolicyIndex.VEHICLE_AUDIO_ROUTING_POLICY_INDEX_STREAM] =
                    i;
            int contexts = 0;
            for (int logicalStream : policy.getLogicalStreamsForPhysicalStream(i)) {
                contexts |= logicalStreamToHalContextType(logicalStream);
            }
            policyToSet[VehicleAudioRoutingPolicyIndex.VEHICLE_AUDIO_ROUTING_POLICY_INDEX_CONTEXTS]
                    = contexts;
            try {
                vn.setIntVectorProperty(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_ROUTING_POLICY,
                        policyToSet);
            } catch (ServiceSpecificException e) {
                Log.e(CarLog.TAG_AUDIO, "Cannot write to VEHICLE_PROPERTY_AUDIO_ROUTING_POLICY", e);
            }
        }
    }

    /**
     * Returns the volume limits of a stream in the form <min, max>.
     */
    public Pair<Integer, Integer> getStreamVolumeLimit(int stream) {
        if (!isPropertySupportedLocked(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME)) {
            throw new IllegalStateException("VEHICLE_PROPERTY_AUDIO_VOLUME not supported");
        }
        int supportedContext = getSupportedAudioVolumeContexts();
        VehiclePropConfig config = mProperties.get(
                VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME);
        List<Integer> maxs = config.getInt32MaxsList();
        List<Integer> mins = config.getInt32MinsList();

        if (maxs.size() != mins.size()) {
            Log.e(CarLog.TAG_AUDIO, "Invalid volume prop config");
            return null;
        }

        Pair<Integer, Integer> result = null;
        if (supportedContext != 0) {
            int index = VehicleZoneUtil.zoneToIndex(supportedContext, stream);
            if (index < maxs.size()) {
                result = new Pair<>(mins.get(index), maxs.get(index));
            }
        } else {
            if (stream < maxs.size()) {
                result = new Pair<>(mins.get(stream), maxs.get(stream));
            }
        }

        if (result == null) {
            Log.e(CarLog.TAG_AUDIO, "No min/max volume found in vehicle" +
                    " prop config for stream: " + stream);
        }

        return result;
    }

    /**
     * Convert car audio manager stream type (usage) into audio context type.
     */
    public static int logicalStreamToHalContextType(int logicalStream) {
        switch (logicalStream) {
            case CarAudioManager.CAR_AUDIO_USAGE_RADIO:
                return VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_RADIO_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_VOICE_CALL:
                return VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_CALL_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_MUSIC:
                return VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_MUSIC_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_NAVIGATION_GUIDANCE:
                return VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_NAVIGATION_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_VOICE_COMMAND:
                return VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_VOICE_COMMAND_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_ALARM:
                return VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_ALARM_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_NOTIFICATION:
                return VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_NOTIFICATION_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_SYSTEM_SAFETY_ALERT:
                return VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_SAFETY_ALERT_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_SYSTEM_SOUND:
                return VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_SYSTEM_SOUND_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_DEFAULT:
                return VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_UNKNOWN_FLAG;
            case CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_BOTTOM:
            case CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_CAR_PROXY:
            case CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_MEDIA_MUTE:
                // internal tag not associated with any stream
                return 0;
            default:
                Log.w(CarLog.TAG_AUDIO, "Unknown logical stream:" + logicalStream);
                return 0;
        }
    }

    /**
     * Converts car audio context type to car stream usage.
     */
    public static int carContextToCarUsage(int carContext) {
        switch (carContext) {
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_MUSIC_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_MUSIC;
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_NAVIGATION_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_NAVIGATION_GUIDANCE;
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_ALARM_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_ALARM;
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_VOICE_COMMAND_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_VOICE_COMMAND;
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_AUX_AUDIO_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_MUSIC;
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_CALL_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_VOICE_CALL;
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_CD_ROM_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_MUSIC;
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_NOTIFICATION_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_NOTIFICATION;
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_RADIO_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_RADIO;
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_SAFETY_ALERT_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_SYSTEM_SAFETY_ALERT;
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_SYSTEM_SOUND_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_SYSTEM_SOUND;
            case VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_UNKNOWN_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_DEFAULT;
            default:
                Log.w(CarLog.TAG_AUDIO, "Unknown car context:" + carContext);
                return 0;
        }
    }

    public void requestAudioFocusChange(int request, int streams, int audioContexts) {
        requestAudioFocusChange(request, streams, VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG, audioContexts);
    }

    public void requestAudioFocusChange(int request, int streams, int extFocus, int audioContexts) {
        int[] payload = { request, streams, extFocus, audioContexts };
        try {
            mVehicleHal.getVehicleNetwork().setIntVectorProperty(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, payload);
        } catch (ServiceSpecificException e) {
            Log.e(CarLog.TAG_AUDIO, "Cannot write to VEHICLE_PROPERTY_AUDIO_FOCUS", e);
            // focus timeout will reset it anyway
        }
    }

    public void setStreamVolume(int streamType, int index) {
        int[] payload = {streamType, index, 0};
        try {
            mVehicleHal.getVehicleNetwork().setIntVectorProperty(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME, payload);
        } catch (ServiceSpecificException e) {
            Log.e(CarLog.TAG_AUDIO, "Cannot write to VEHICLE_PROPERTY_AUDIO_VOLUME", e);
            //TODO should reset volume
        }
    }

    public int getStreamVolume(int stream) {
        int[] volume = {stream, 0, 0};
        VehiclePropValue streamVolume =
                VehiclePropValueUtil.createIntVectorValue(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME, volume, 0);
        VehiclePropValue value;
        try {
            value = mVehicleHal.getVehicleNetwork().getProperty(streamVolume);
        }  catch (ServiceSpecificException e) {
            Log.e(CarLog.TAG_AUDIO, "AUDIO_VOLUME not ready", e);
            return 0;
        }

        if (value.getInt32ValuesCount() != 3) {
            Log.e(CarLog.TAG_AUDIO, "returned value not valid");
            throw new IllegalStateException("Invalid preset returned from service: "
                    + value.getInt32ValuesList());
        }

        int retStreamNum = value.getInt32Values(0);
        int retVolume = value.getInt32Values(1);
        int retVolumeState = value.getInt32Values(2);

        if (retStreamNum != stream) {
            Log.e(CarLog.TAG_AUDIO, "Stream number is not the same: "
                    + stream + " vs " + retStreamNum);
            throw new IllegalStateException("Stream number is not the same");
        }
        return retVolume;
    }

    public synchronized int getHwVariant() {
        return mVariant;
    }

    public synchronized boolean isRadioExternal() {
        VehiclePropConfig config = mProperties.get(
                VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_HW_VARIANT);
        if (config == null) {
            return true;
        }
        return (config.getConfigArray(0) &
                VehicleAudioHwVariantConfigFlag.VEHICLE_AUDIO_HW_VARIANT_FLAG_INTERNAL_RADIO_FLAG)
                == 0;
    }

    public synchronized boolean isFocusSupported() {
        return isPropertySupportedLocked(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS);
    }

    public synchronized boolean isAudioVolumeSupported() {
        return isPropertySupportedLocked(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME);
    }

    public synchronized int getSupportedAudioVolumeContexts() {
        if (!isPropertySupportedLocked(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME)) {
            throw new IllegalStateException("VEHICLE_PROPERTY_AUDIO_VOLUME not supported");
        }
        VehiclePropConfig config = mProperties.get(
                VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME);
        return config.getConfigArray(0);
    }

    /**
     * Whether external audio module can memorize logical audio volumes or not.
     * @return
     */
    public synchronized boolean isExternalAudioVolumePersistent() {
        if (!isPropertySupportedLocked(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME)) {
            throw new IllegalStateException("VEHICLE_PROPERTY_AUDIO_VOLUME not supported");
        }
        VehiclePropConfig config = mProperties.get(
                VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME);
        if (config.getConfigArray(0) == 0) { // physical streams only
            return false;
        }
        if ((config.getConfigArray(1) &
                VehicleAudioVolumeCapabilityFlag.VEHICLE_AUDIO_VOLUME_CAPABILITY_PERSISTENT_STORAGE)
                != 0) {
            return true;
        }
        return false;
    }

    public synchronized boolean isAudioVolumeLimitSupported() {
        return isPropertySupportedLocked(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME_LIMIT);
    }

    /**
     * Get the current audio focus state.
     * @return 0: focusState, 1: streams, 2: externalFocus
     */
    public int[] getCurrentFocusState() {
        if (!isFocusSupported()) {
            return new int[] { VEHICLE_AUDIO_FOCUS_STATE_GAIN, 0xffffffff, 0};
        }
        try {
            return mVehicleHal.getVehicleNetwork().getIntVectorProperty(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS);
        } catch (ServiceSpecificException e) {
            Log.e(CarLog.TAG_AUDIO, "VEHICLE_PROPERTY_AUDIO_HW_VARIANT not ready", e);
            return new int[] { VEHICLE_AUDIO_FOCUS_STATE_LOSS, 0x0, 0};
        }
    }

    private boolean isPropertySupportedLocked(int property) {
        VehiclePropConfig config = mProperties.get(property);
        return config != null;
    }

    @Override
    public synchronized void init() {
        for (VehiclePropConfig config : mProperties.values()) {
            if (VehicleHal.isPropertySubscribable(config)) {
                mVehicleHal.subscribeProperty(this, config.getProp(), 0);
            }
        }
        try {
            mVariant = mVehicleHal.getVehicleNetwork().getIntProperty(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_HW_VARIANT);
        } catch (IllegalArgumentException e) {
            // no variant. Set to default, 0.
            mVariant = 0;
        } catch (ServiceSpecificException e) {
            Log.e(CarLog.TAG_AUDIO, "VEHICLE_PROPERTY_AUDIO_HW_VARIANT not ready", e);
            mVariant = 0;
        }
    }

    @Override
    public synchronized void release() {
        for (VehiclePropConfig config : mProperties.values()) {
            if (VehicleHal.isPropertySubscribable(config)) {
                mVehicleHal.unsubscribeProperty(this, config.getProp());
            }
        }
        mProperties.clear();
    }

    @Override
    public synchronized List<VehiclePropConfig> takeSupportedProperties(
            List<VehiclePropConfig> allProperties) {
        for (VehiclePropConfig p : allProperties) {
            switch (p.getProp()) {
                case VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS:
                case VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME:
                case VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME_LIMIT:
                case VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_HW_VARIANT:
                case VehicleNetworkConsts.VEHICLE_PROPERTY_INTERNAL_AUDIO_STREAM_STATE:
                    mProperties.put(p.getProp(), p);
                    break;
            }
        }
        return new LinkedList<VehiclePropConfig>(mProperties.values());
    }

    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        AudioHalFocusListener focusListener = null;
        AudioHalVolumeListener volumeListener = null;
        synchronized (this) {
            focusListener = mFocusListener;
            volumeListener = mVolumeListener;
        }
        dispatchEventToListener(focusListener, volumeListener, values);
    }

    private void dispatchEventToListener(AudioHalFocusListener focusListener,
            AudioHalVolumeListener volumeListener,
            List<VehiclePropValue> values) {
        for (VehiclePropValue v : values) {
            switch (v.getProp()) {
                case VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS: {
                    int focusState = v.getInt32Values(
                            VehicleAudioFocusIndex.VEHICLE_AUDIO_FOCUS_INDEX_FOCUS);
                    int streams = v.getInt32Values(
                            VehicleAudioFocusIndex.VEHICLE_AUDIO_FOCUS_INDEX_STREAMS);
                    int externalFocus = v.getInt32Values(
                            VehicleAudioFocusIndex.VEHICLE_AUDIO_FOCUS_INDEX_EXTERNAL_FOCUS_STATE);
                    if (focusListener != null) {
                        focusListener.onFocusChange(focusState, streams, externalFocus);
                    }
                } break;
                case VehicleNetworkConsts.VEHICLE_PROPERTY_INTERNAL_AUDIO_STREAM_STATE: {
                    int state = v.getInt32Values(
                            VehicleAudioStreamStateIndex.VEHICLE_AUDIO_STREAM_STATE_INDEX_STATE);
                    int streamNum = v.getInt32Values(
                            VehicleAudioStreamStateIndex.VEHICLE_AUDIO_STREAM_STATE_INDEX_STREAM);
                    if (focusListener != null) {
                        focusListener.onStreamStatusChange(streamNum, state);
                    }
                } break;
                case VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME: {
                    int volume = v.getInt32Values(
                            VehicleAudioVolumeIndex.VEHICLE_AUDIO_VOLUME_INDEX_VOLUME);
                    int streamNum = v.getInt32Values(
                            VehicleAudioVolumeIndex.VEHICLE_AUDIO_VOLUME_INDEX_STREAM);
                    int volumeState = v.getInt32Values(
                            VehicleAudioVolumeIndex.VEHICLE_AUDIO_VOLUME_INDEX_STATE);
                    if (volumeListener != null) {
                        volumeListener.onVolumeChange(streamNum, volume, volumeState);
                    }
                } break;
                case VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME_LIMIT: {
                    int stream = v.getInt32Values(
                            VehicleAudioVolumeLimitIndex.VEHICLE_AUDIO_VOLUME_LIMIT_INDEX_STREAM);
                    int maxVolume = v.getInt32Values(
                            VehicleAudioVolumeLimitIndex.VEHICLE_AUDIO_VOLUME_LIMIT_INDEX_MAX_VOLUME);
                    if (volumeListener != null) {
                        volumeListener.onVolumeLimitChange(stream, maxVolume);
                    }
                } break;
            }
        }
        values.clear();
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*Audio HAL*");
        writer.println(" audio H/W variant:" + mVariant);
        writer.println(" Supported properties");
        VehicleHal.dumpProperties(writer, mProperties.values());
    }

}
