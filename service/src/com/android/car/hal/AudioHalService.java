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

import static android.hardware.automotive.vehicle.V2_0.VehicleProperty.AUDIO_EXT_ROUTING_HINT;
import static android.hardware.automotive.vehicle.V2_0.VehicleProperty.AUDIO_HW_VARIANT;
import static android.hardware.automotive.vehicle.V2_0.VehicleProperty.AUDIO_ROUTING_POLICY;
import static android.hardware.automotive.vehicle.V2_0.VehicleProperty.AUDIO_STREAM_STATE;
import static android.hardware.automotive.vehicle.V2_0.VehicleProperty.AUDIO_VOLUME;
import static android.hardware.automotive.vehicle.V2_0.VehicleProperty.AUDIO_VOLUME_LIMIT;

import android.car.media.CarAudioManager;
import android.hardware.automotive.vehicle.V2_0.SubscribeFlags;
import android.hardware.automotive.vehicle.V2_0.VehicleAudioContextFlag;
import android.hardware.automotive.vehicle.V2_0.VehicleAudioHwVariantConfigFlag;
import android.hardware.automotive.vehicle.V2_0.VehicleAudioRoutingPolicyIndex;
import android.hardware.automotive.vehicle.V2_0.VehicleAudioVolumeIndex;
import android.hardware.automotive.vehicle.V2_0.VehicleAudioVolumeLimitIndex;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.text.TextUtils;
import android.util.Log;

import com.android.car.AudioRoutingPolicy;
import com.android.car.CarAudioAttributesUtil;
import com.android.car.CarLog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AudioHalService extends HalServiceBase {

    public static final int AUDIO_CONTEXT_MUSIC_FLAG =
            VehicleAudioContextFlag.MUSIC_FLAG;
    public static final int AUDIO_CONTEXT_NAVIGATION_FLAG =
            VehicleAudioContextFlag.NAVIGATION_FLAG;
    public static final int AUDIO_CONTEXT_VOICE_COMMAND_FLAG =
            VehicleAudioContextFlag.VOICE_COMMAND_FLAG;
    public static final int AUDIO_CONTEXT_CALL_FLAG =
            VehicleAudioContextFlag.CALL_FLAG;
    public static final int AUDIO_CONTEXT_ALARM_FLAG =
            VehicleAudioContextFlag.ALARM_FLAG;
    public static final int AUDIO_CONTEXT_NOTIFICATION_FLAG =
            VehicleAudioContextFlag.NOTIFICATION_FLAG;
    public static final int AUDIO_CONTEXT_UNKNOWN_FLAG =
            VehicleAudioContextFlag.UNKNOWN_FLAG;
    public static final int AUDIO_CONTEXT_SAFETY_ALERT_FLAG =
            VehicleAudioContextFlag.SAFETY_ALERT_FLAG;
    public static final int AUDIO_CONTEXT_RADIO_FLAG =
            VehicleAudioContextFlag.RADIO_FLAG;
    public static final int AUDIO_CONTEXT_CD_ROM_FLAG =
            VehicleAudioContextFlag.CD_ROM_FLAG;
    public static final int AUDIO_CONTEXT_AUX_AUDIO_FLAG =
            VehicleAudioContextFlag.AUX_AUDIO_FLAG;
    public static final int AUDIO_CONTEXT_SYSTEM_SOUND_FLAG =
            VehicleAudioContextFlag.SYSTEM_SOUND_FLAG;
    public static final int AUDIO_CONTEXT_EXT_SOURCE_FLAG =
            VehicleAudioContextFlag.EXT_SOURCE_FLAG;
    public static final int AUDIO_CONTEXT_RINGTONE_FLAG =
            VehicleAudioContextFlag.RINGTONE_FLAG;

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

    private static final boolean DBG = false;

    private final VehicleHal mVehicleHal;
    private AudioHalVolumeListener mVolumeListener;
    private int mVariant;

    private final HashMap<Integer, VehiclePropConfig> mProperties = new HashMap<>();

    public AudioHalService(VehicleHal vehicleHal) {
        mVehicleHal = vehicleHal;
    }

    public synchronized void setVolumeListener(AudioHalVolumeListener volumeListener) {
        mVolumeListener = volumeListener;
    }

    public void setAudioRoutingPolicy(AudioRoutingPolicy policy) {
        if (!mVehicleHal.isPropertySupported(VehicleProperty.AUDIO_ROUTING_POLICY)) {
            Log.w(CarLog.TAG_AUDIO,
                    "Vehicle HAL did not implement VehicleProperty.AUDIO_ROUTING_POLICY");
            return;
        }
        int[] policyToSet = new int[2];
        for (int i = 0; i < policy.getPhysicalStreamsCount(); i++) {
            policyToSet[VehicleAudioRoutingPolicyIndex.STREAM] = i;
            int contexts = 0;
            for (int logicalStream : policy.getLogicalStreamsForPhysicalStream(i)) {
                contexts |= logicalStreamToHalContextType(logicalStream);
            }
            policyToSet[VehicleAudioRoutingPolicyIndex.CONTEXTS] = contexts;
            try {
                mVehicleHal.set(AUDIO_ROUTING_POLICY).to(policyToSet);
            } catch (PropertyTimeoutException e) {
                Log.e(CarLog.TAG_AUDIO, "Cannot write to VehicleProperty.AUDIO_ROUTING_POLICY", e);
            }
        }
    }

    /**
     * Returns the volume limits of a stream. Returns null if max value wasn't defined for
     * AUDIO_VOLUME property.
     */
    public synchronized int getUsageMaxVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        return 100;
    }

    /**
     * Convert car audio manager stream type (usage) into audio context type.
     */
    public static int logicalStreamToHalContextType(int logicalStream) {
        return logicalStreamWithExtTypeToHalContextType(logicalStream, null);
    }

    public static int logicalStreamWithExtTypeToHalContextType(int logicalStream, String extType) {
        switch (logicalStream) {
            case CarAudioManager.CAR_AUDIO_USAGE_RADIO:
                return VehicleAudioContextFlag.RADIO_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_VOICE_CALL:
                return VehicleAudioContextFlag.CALL_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_RINGTONE:
                return VehicleAudioContextFlag.RINGTONE_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_MUSIC:
                return VehicleAudioContextFlag.MUSIC_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_NAVIGATION_GUIDANCE:
                return VehicleAudioContextFlag.NAVIGATION_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_VOICE_COMMAND:
                return VehicleAudioContextFlag.VOICE_COMMAND_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_ALARM:
                return VehicleAudioContextFlag.ALARM_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_NOTIFICATION:
                return VehicleAudioContextFlag.NOTIFICATION_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_SYSTEM_SAFETY_ALERT:
                return VehicleAudioContextFlag.SAFETY_ALERT_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_SYSTEM_SOUND:
                return VehicleAudioContextFlag.SYSTEM_SOUND_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_DEFAULT:
                return VehicleAudioContextFlag.UNKNOWN_FLAG;
            case CarAudioManager.CAR_AUDIO_USAGE_EXTERNAL_AUDIO_SOURCE:
                if (extType != null) {
                    switch (extType) {
                    case CarAudioManager.CAR_EXTERNAL_SOURCE_TYPE_CD_DVD:
                        return VehicleAudioContextFlag.CD_ROM_FLAG;
                    case CarAudioManager.CAR_EXTERNAL_SOURCE_TYPE_AUX_IN0:
                    case CarAudioManager.CAR_EXTERNAL_SOURCE_TYPE_AUX_IN1:
                        return VehicleAudioContextFlag.AUX_AUDIO_FLAG;
                    default:
                        if (extType.startsWith("RADIO_")) {
                            return VehicleAudioContextFlag.RADIO_FLAG;
                        } else {
                            return VehicleAudioContextFlag.EXT_SOURCE_FLAG;
                        }
                    }
                } else { // no external source specified. fall back to radio
                    return VehicleAudioContextFlag.RADIO_FLAG;
                }
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
            case VehicleAudioContextFlag.MUSIC_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_MUSIC;
            case VehicleAudioContextFlag.NAVIGATION_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_NAVIGATION_GUIDANCE;
            case VehicleAudioContextFlag.ALARM_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_ALARM;
            case VehicleAudioContextFlag.VOICE_COMMAND_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_VOICE_COMMAND;
            case VehicleAudioContextFlag.AUX_AUDIO_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_EXTERNAL_AUDIO_SOURCE;
            case VehicleAudioContextFlag.CALL_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_VOICE_CALL;
            case VehicleAudioContextFlag.RINGTONE_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_RINGTONE;
            case VehicleAudioContextFlag.CD_ROM_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_EXTERNAL_AUDIO_SOURCE;
            case VehicleAudioContextFlag.NOTIFICATION_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_NOTIFICATION;
            case VehicleAudioContextFlag.RADIO_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_RADIO;
            case VehicleAudioContextFlag.SAFETY_ALERT_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_SYSTEM_SAFETY_ALERT;
            case VehicleAudioContextFlag.SYSTEM_SOUND_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_SYSTEM_SOUND;
            case VehicleAudioContextFlag.UNKNOWN_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_DEFAULT;
            case VehicleAudioContextFlag.EXT_SOURCE_FLAG:
                return CarAudioManager.CAR_AUDIO_USAGE_EXTERNAL_AUDIO_SOURCE;
            default:
                Log.w(CarLog.TAG_AUDIO, "Unknown car context:" + carContext);
                return 0;
        }
    }

    public void setUsageVolume(@CarAudioManager.CarAudioUsage int carUsage, int index) {
        // TODO(hwwang): set volume by usage to device port based on carUsage
        return;
    }

    public int getUsageVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        // TODO(hwwang): get volume by usage from device port based on carUsage
        return 50;
    }

    public synchronized int getHwVariant() {
        return mVariant;
    }

    public synchronized boolean isRadioExternal() {
        VehiclePropConfig config = mProperties.get(VehicleProperty.AUDIO_HW_VARIANT);
        if (config == null) {
            return true;
        }
        return (config.configArray.get(0)
                & VehicleAudioHwVariantConfigFlag.INTERNAL_RADIO_FLAG) == 0;
    }

    public synchronized int getSupportedAudioVolumeContexts() {
        return 1;
    }

    public static class ExtRoutingSourceInfo {
        /** Represents an external route which will not disable any physical stream in android side.
         */
        public static final int NO_DISABLED_PHYSICAL_STREAM = -1;

        /** Bit position of this source in vhal */
        public final int bitPosition;
        /**
         * Physical stream replaced by this routing. will be {@link #NO_DISABLED_PHYSICAL_STREAM}
         * if no physical stream for android is replaced by this routing.
         */
        public final int physicalStreamNumber;

        public ExtRoutingSourceInfo(int bitPosition, int physycalStreamNumber) {
            this.bitPosition = bitPosition;
            this.physicalStreamNumber = physycalStreamNumber;
        }

        @Override
        public String toString() {
            return "[bitPosition=" + bitPosition + ", physicalStreamNumber="
                    + physicalStreamNumber + "]";
        }
    }

    /**
     * Get external audio routing types from AUDIO_EXT_ROUTING_HINT property.
     *
     * @return null if AUDIO_EXT_ROUTING_HINT is not supported.
     */
    public Map<String, ExtRoutingSourceInfo> getExternalAudioRoutingTypes() {
        VehiclePropConfig config;
        synchronized (this) {
            if (!isPropertySupportedLocked(AUDIO_EXT_ROUTING_HINT)) {
                if (DBG) {
                    Log.i(CarLog.TAG_AUDIO, "AUDIO_EXT_ROUTING_HINT is not supported");
                }
                return null;
            }
            config = mProperties.get(AUDIO_EXT_ROUTING_HINT);
        }
        if (TextUtils.isEmpty(config.configString)) {
            Log.w(CarLog.TAG_AUDIO, "AUDIO_EXT_ROUTING_HINT with empty config string");
            return null;
        }
        Map<String, ExtRoutingSourceInfo> routingTypes = new HashMap<>();
        String configString = config.configString;
        if (DBG) {
            Log.i(CarLog.TAG_AUDIO, "AUDIO_EXT_ROUTING_HINT config string:" + configString);
        }
        String[] routes = configString.split(",");
        for (String routeString : routes) {
            String[] tokens = routeString.split(":");
            int bitPosition = 0;
            String name = null;
            int physicalStreamNumber = ExtRoutingSourceInfo.NO_DISABLED_PHYSICAL_STREAM;
            if (tokens.length == 2) {
                bitPosition = Integer.parseInt(tokens[0]);
                name = tokens[1];
            } else if (tokens.length == 3) {
                bitPosition = Integer.parseInt(tokens[0]);
                name = tokens[1];
                physicalStreamNumber = Integer.parseInt(tokens[2]);
            } else {
                Log.w(CarLog.TAG_AUDIO, "AUDIO_EXT_ROUTING_HINT has wrong entry:" +
                        routeString);
                continue;
            }
            routingTypes.put(name, new ExtRoutingSourceInfo(bitPosition, physicalStreamNumber));
        }
        return routingTypes;
    }

    private boolean isPropertySupportedLocked(int property) {
        VehiclePropConfig config = mProperties.get(property);
        return config != null;
    }

    @Override
    public synchronized void init() {
        for (VehiclePropConfig config : mProperties.values()) {
            if (VehicleHal.isPropertySubscribable(config)) {
                int subsribeFlag = SubscribeFlags.HAL_EVENT;
                if (AUDIO_STREAM_STATE == config.prop) {
                    subsribeFlag |= SubscribeFlags.SET_CALL;
                }
                mVehicleHal.subscribeProperty(this, config.prop, 0, subsribeFlag);
            }
        }
        try {
            mVariant = mVehicleHal.get(int.class, AUDIO_HW_VARIANT);
        } catch (IllegalArgumentException e) {
            // no variant. Set to default, 0.
            mVariant = 0;
        } catch (PropertyTimeoutException e) {
            Log.e(CarLog.TAG_AUDIO, "VehicleProperty.AUDIO_HW_VARIANT not ready", e);
            mVariant = 0;
        }
    }

    @Override
    public synchronized void release() {
        for (VehiclePropConfig config : mProperties.values()) {
            if (VehicleHal.isPropertySubscribable(config)) {
                mVehicleHal.unsubscribeProperty(this, config.prop);
            }
        }
        mProperties.clear();
    }

    @Override
    public synchronized Collection<VehiclePropConfig> takeSupportedProperties(
            Collection<VehiclePropConfig> allProperties) {
        for (VehiclePropConfig p : allProperties) {
            switch (p.prop) {
                case VehicleProperty.AUDIO_FOCUS:
                case VehicleProperty.AUDIO_VOLUME:
                case VehicleProperty.AUDIO_VOLUME_LIMIT:
                case VehicleProperty.AUDIO_HW_VARIANT:
                case VehicleProperty.AUDIO_EXT_ROUTING_HINT:
                case VehicleProperty.AUDIO_STREAM_STATE:
                    mProperties.put(p.prop, p);
                    break;
            }
        }
        return new ArrayList<>(mProperties.values());
    }

    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        AudioHalVolumeListener volumeListener;
        synchronized (this) {
            volumeListener = mVolumeListener;
        }
        dispatchEventToListener(volumeListener, values);
    }

    private void dispatchEventToListener(AudioHalVolumeListener volumeListener,
            List<VehiclePropValue> values) {
        for (VehiclePropValue v : values) {
            switch (v.prop) {
                case AUDIO_VOLUME: {
                    ArrayList<Integer> vec = v.value.int32Values;
                    int streamNum = vec.get(VehicleAudioVolumeIndex.STREAM);
                    int volume = vec.get(VehicleAudioVolumeIndex.VOLUME);
                    int volumeState = vec.get(VehicleAudioVolumeIndex.STATE);
                    if (volumeListener != null) {
                        volumeListener.onVolumeChange(streamNum, volume, volumeState);
                    }
                } break;
                case AUDIO_VOLUME_LIMIT: {
                    ArrayList<Integer> vec = v.value.int32Values;
                    int stream = vec.get(VehicleAudioVolumeLimitIndex.STREAM);
                    int maxVolume = vec.get(VehicleAudioVolumeLimitIndex.MAX_VOLUME);
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
