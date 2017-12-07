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
package com.android.car;

import android.car.Car;
import android.car.VehicleZoneUtil;
import android.car.media.CarAudioManager;
import android.car.media.ICarAudio;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.IVolumeController;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.Looper;
import android.util.Log;

import com.android.car.hal.AudioHalService;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class CarAudioService extends ICarAudio.Stub implements CarServiceBase {

    private static final boolean DBG = false;
    private static final boolean DBG_DYNAMIC_AUDIO_ROUTING = false;

    private static final String RADIO_ROUTING_SOURCE_PREFIX = "RADIO_";

    private final AudioHalService mAudioHal;
    private final Context mContext;
    private final CarVolumeService mVolumeService;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private AudioPolicy mAudioPolicy;

    private AudioRoutingPolicy mAudioRoutingPolicy;
    private final AudioManager mAudioManager;

    @GuardedBy("mLock")
    private boolean mRadioOrExtSourceActive = false;
    @GuardedBy("mLock")
    private int mCurrentAudioContexts = 0;
    @GuardedBy("mLock")
    private int mCurrentPrimaryAudioContext = 0;
    @GuardedBy("mLock")
    private int mCurrentPrimaryPhysicalStream = 0;
    @GuardedBy("mLock")
    private boolean mIsRadioExternal;

    @GuardedBy("mLock")
    private boolean mExternalRoutingHintSupported;
    @GuardedBy("mLock")
    private Map<String, AudioHalService.ExtRoutingSourceInfo> mExternalRoutingTypes;
    @GuardedBy("mLock")
    private Set<String> mExternalRadioRoutingTypes;
    @GuardedBy("mLock")
    private String mDefaultRadioRoutingType;
    @GuardedBy("mLock")
    private Set<String> mExternalNonRadioRoutingTypes;
    @GuardedBy("mLock")
    private int[] mExternalRoutings = {0, 0, 0, 0};

    private final boolean mUseDynamicRouting;

    public CarAudioService(Context context, AudioHalService audioHal,
            CarInputService inputService, CanBusErrorNotifier errorNotifier) {
        mAudioHal = audioHal;
        mContext = context;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        Resources res = context.getResources();
        mUseDynamicRouting = res.getBoolean(R.bool.audioUseDynamicRouting);
        mVolumeService = new CarVolumeService(mContext, this, mAudioHal, inputService);
    }

    @Override
    public AudioAttributes getAudioAttributesForCarUsage(int carUsage) {
        return CarAudioAttributesUtil.getAudioAttributesForCarUsage(carUsage);
    }

    @Override
    public void init() {
        int audioHwVariant = mAudioHal.getHwVariant();
        AudioRoutingPolicy audioRoutingPolicy = AudioRoutingPolicy.create(mContext, audioHwVariant);

        AudioPolicy audioPolicy = null;

        if (mUseDynamicRouting) {
            AudioPolicy.Builder builder = new AudioPolicy.Builder(mContext);
            builder.setLooper(Looper.getMainLooper());
            setupDynamicRouting(audioRoutingPolicy, builder);
            audioPolicy = builder.build();
        }

        mAudioHal.setAudioRoutingPolicy(audioRoutingPolicy);
        // get call outside lock as it can take time
        HashSet<String> externalRadioRoutingTypes = new HashSet<>();
        HashSet<String> externalNonRadioRoutingTypes = new HashSet<>();
        Map<String, AudioHalService.ExtRoutingSourceInfo> externalRoutingTypes =
                mAudioHal.getExternalAudioRoutingTypes();
        if (externalRoutingTypes != null) {
            for (String routingType : externalRoutingTypes.keySet()) {
                if (routingType.startsWith(RADIO_ROUTING_SOURCE_PREFIX)) {
                    externalRadioRoutingTypes.add(routingType);
                } else {
                    externalNonRadioRoutingTypes.add(routingType);
                }
            }
        }
        // select default radio routing. AM_FM -> AM_FM_HD -> whatever with AM or FM -> first one
        String defaultRadioRouting = null;
        if (externalRadioRoutingTypes.contains(CarAudioManager.CAR_RADIO_TYPE_AM_FM)) {
            defaultRadioRouting = CarAudioManager.CAR_RADIO_TYPE_AM_FM;
        } else if (externalRadioRoutingTypes.contains(CarAudioManager.CAR_RADIO_TYPE_AM_FM_HD)) {
            defaultRadioRouting = CarAudioManager.CAR_RADIO_TYPE_AM_FM_HD;
        } else {
            for (String radioType : externalRadioRoutingTypes) {
                // set to 1st one
                if (defaultRadioRouting == null) {
                    defaultRadioRouting = radioType;
                }
                if (radioType.contains("AM") || radioType.contains("FM")) {
                    defaultRadioRouting = radioType;
                    break;
                }
            }
        }
        if (defaultRadioRouting == null) { // no radio type defined. fall back to AM_FM
            defaultRadioRouting = CarAudioManager.CAR_RADIO_TYPE_AM_FM;
        }
        synchronized (mLock) {
            if (audioPolicy != null) {
                mAudioPolicy = audioPolicy;
            }
            mAudioRoutingPolicy = audioRoutingPolicy;
            mIsRadioExternal = mAudioHal.isRadioExternal();
            if (externalRoutingTypes != null) {
                mExternalRoutingHintSupported = true;
                mExternalRoutingTypes = externalRoutingTypes;
            } else {
                mExternalRoutingHintSupported = false;
                mExternalRoutingTypes = new HashMap<>();
            }
            mExternalRadioRoutingTypes = externalRadioRoutingTypes;
            mExternalNonRadioRoutingTypes = externalNonRadioRoutingTypes;
            mDefaultRadioRoutingType = defaultRadioRouting;
            Arrays.fill(mExternalRoutings, 0);
        }
        mVolumeService.init();

        // Register audio policy only after this class is fully initialized.
        int r = mAudioManager.registerAudioPolicy(audioPolicy);
        if (r != 0) {
            throw new RuntimeException("registerAudioPolicy failed " + r);
        }
    }

    private void setupDynamicRouting(AudioRoutingPolicy audioRoutingPolicy,
            AudioPolicy.Builder audioPolicyBuilder) {
        AudioDeviceInfo[] deviceInfos = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (deviceInfos.length == 0) {
            Log.e(CarLog.TAG_AUDIO, "setupDynamicRouting, no output device available, ignore");
            return;
        }
        int numPhysicalStreams = audioRoutingPolicy.getPhysicalStreamsCount();
        AudioDeviceInfo[] devicesToRoute = new AudioDeviceInfo[numPhysicalStreams];
        for (AudioDeviceInfo info : deviceInfos) {
            if (DBG_DYNAMIC_AUDIO_ROUTING) {
                Log.v(CarLog.TAG_AUDIO, String.format(
                        "output device=%s id=%d name=%s addr=%s type=%s",
                        info.toString(), info.getId(), info.getProductName(), info.getAddress(),
                        info.getType()));
            }
            if (info.getType() == AudioDeviceInfo.TYPE_BUS) {
                int addressNumeric = parseDeviceAddress(info.getAddress());
                if (addressNumeric >= 0 && addressNumeric < numPhysicalStreams) {
                    devicesToRoute[addressNumeric] = info;
                    Log.i(CarLog.TAG_AUDIO, String.format(
                            "valid bus found, devie=%s id=%d name=%s addr=%s",
                            info.toString(), info.getId(), info.getProductName(), info.getAddress())
                            );
                }
            }
        }
        for (int i = 0; i < numPhysicalStreams; i++) {
            AudioDeviceInfo info = devicesToRoute[i];
            if (info == null) {
                Log.e(CarLog.TAG_AUDIO, "setupDynamicRouting, cannot find device for address " + i);
                return;
            }
            int sampleRate = getMaxSampleRate(info);
            int channels = getMaxChannels(info);
            AudioFormat mixFormat = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channels)
                .build();
            Log.i(CarLog.TAG_AUDIO, String.format(
                    "Physical stream %d, sampleRate:%d, channels:0x%s", i, sampleRate,
                    Integer.toHexString(channels)));
            int[] logicalStreams = audioRoutingPolicy.getLogicalStreamsForPhysicalStream(i);
            AudioMixingRule.Builder mixingRuleBuilder = new AudioMixingRule.Builder();
            for (int logicalStream : logicalStreams) {
                mixingRuleBuilder.addRule(
                        CarAudioAttributesUtil.getAudioAttributesForCarUsage(logicalStream),
                        AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);
            }
            AudioMix audioMix = new AudioMix.Builder(mixingRuleBuilder.build())
                .setFormat(mixFormat)
                .setDevice(info)
                .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                .build();
            audioPolicyBuilder.addMix(audioMix);
        }
    }

    /**
     * Parse device address. Expected format is BUS%d_%s, address, usage hint
     * @return valid address (from 0 to positive) or -1 for invalid address.
     */
    private int parseDeviceAddress(String address) {
        String[] words = address.split("_");
        int addressParsed = -1;
        if (words[0].startsWith("BUS")) {
            try {
                addressParsed = Integer.parseInt(words[0].substring(3));
            } catch (NumberFormatException e) {
                //ignore
            }
        }
        if (addressParsed < 0) {
            return -1;
        }
        return addressParsed;
    }

    private int getMaxSampleRate(AudioDeviceInfo info) {
        int[] sampleRates = info.getSampleRates();
        if (sampleRates == null || sampleRates.length == 0) {
            return 48000;
        }
        int sampleRate = sampleRates[0];
        for (int i = 1; i < sampleRates.length; i++) {
            if (sampleRates[i] > sampleRate) {
                sampleRate = sampleRates[i];
            }
        }
        return sampleRate;
    }

    private int getMaxChannels(AudioDeviceInfo info) {
        int[] channelMasks = info.getChannelMasks();
        if (channelMasks == null) {
            return AudioFormat.CHANNEL_OUT_STEREO;
        }
        int channels = AudioFormat.CHANNEL_OUT_MONO;
        int numChannels = 1;
        for (int i = 0; i < channelMasks.length; i++) {
            int currentNumChannels = VehicleZoneUtil.getNumberOfZones(channelMasks[i]);
            if (currentNumChannels > numChannels) {
                numChannels = currentNumChannels;
                channels = channelMasks[i];
            }
        }
        return channels;
    }

    @Override
    public void release() {
        AudioPolicy audioPolicy;
        synchronized (mLock) {
            mRadioOrExtSourceActive = false;
            mCurrentPrimaryAudioContext = 0;
            audioPolicy = mAudioPolicy;
            mAudioPolicy = null;
            mExternalRoutingTypes.clear();
            mExternalRadioRoutingTypes.clear();
            mExternalNonRadioRoutingTypes.clear();
        }
        if (audioPolicy != null) {
            mAudioManager.unregisterAudioPolicyAsync(audioPolicy);
        }
        mVolumeService.release();
    }

    @Override
    public void dump(PrintWriter writer) {
        synchronized (mLock) {
            writer.println("*CarAudioService*");
            writer.println(" mCurrentAudioContexts:0x" +
                    Integer.toHexString(mCurrentAudioContexts));
            writer.println(" mRadioOrExtSourceActive:" +
                    mRadioOrExtSourceActive);
            writer.println(" mCurrentPrimaryAudioContext:" + mCurrentPrimaryAudioContext +
                    " mCurrentPrimaryPhysicalStream:" + mCurrentPrimaryPhysicalStream);
            writer.println(" mIsRadioExternal:" + mIsRadioExternal);
            writer.println(" mAudioPolicy:" + mAudioPolicy);
            mAudioRoutingPolicy.dump(writer);
            writer.println(" mExternalRoutingHintSupported:" + mExternalRoutingHintSupported);
            if (mExternalRoutingHintSupported) {
                writer.println(" mDefaultRadioRoutingType:" + mDefaultRadioRoutingType);
                writer.println(" Routing Types:");
                for (Entry<String, AudioHalService.ExtRoutingSourceInfo> entry :
                    mExternalRoutingTypes.entrySet()) {
                    writer.println("  type:" + entry.getKey() + " info:" + entry.getValue());
                }
            }
        }
        writer.println("** Dump CarVolumeService**");
        mVolumeService.dump(writer);
    }

    @Override
    public void setUsageVolume(@CarAudioManager.CarAudioUsage int carUsage, int index, int flags) {
        enforceAudioVolumePermission();
        mVolumeService.setUsageVolume(carUsage, index, flags);
    }

    @Override
    public void setVolumeController(IVolumeController controller) {
        enforceAudioVolumePermission();
        mVolumeService.setVolumeController(controller);
    }

    @Override
    public int getUsageMaxVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        enforceAudioVolumePermission();
        return mVolumeService.getUsageMaxVolume(carUsage);
    }

    @Override
    public int getUsageMinVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        enforceAudioVolumePermission();
        return mVolumeService.getUsageMinVolume(carUsage);
    }

    @Override
    public int getUsageVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        enforceAudioVolumePermission();
        return mVolumeService.getUsageVolume(carUsage);
    }

    @Override
    public AudioAttributes getAudioAttributesForRadio(String radioType) {
        synchronized (mLock) {
            if (!mExternalRadioRoutingTypes.contains(radioType)) { // type not exist
                throw new IllegalArgumentException("Specified radio type is not available:" +
                        radioType);
            }
        }
      return CarAudioAttributesUtil.getCarRadioAttributes(radioType);
    }

    @Override
    public AudioAttributes getAudioAttributesForExternalSource(String externalSourceType) {
        synchronized (mLock) {
            if (!mExternalNonRadioRoutingTypes.contains(externalSourceType)) { // type not exist
                throw new IllegalArgumentException("Specified ext source type is not available:" +
                        externalSourceType);
            }
        }
        return CarAudioAttributesUtil.getCarExtSourceAttributes(externalSourceType);
    }

    @Override
    public String[] getSupportedExternalSourceTypes() {
        synchronized (mLock) {
            return mExternalNonRadioRoutingTypes.toArray(
                    new String[mExternalNonRadioRoutingTypes.size()]);
        }
    }

    @Override
    public String[] getSupportedRadioTypes() {
        synchronized (mLock) {
            return mExternalRadioRoutingTypes.toArray(
                    new String[mExternalRadioRoutingTypes.size()]);
        }
    }

    public AudioRoutingPolicy getAudioRoutingPolicy() {
        return mAudioRoutingPolicy;
    }

    private void enforceAudioVolumePermission() {
        if (mContext.checkCallingOrSelfPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "requires permission " + Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        }
    }
}
