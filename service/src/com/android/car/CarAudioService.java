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

import android.annotation.Nullable;
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
import android.media.AudioGain;
import android.media.AudioGainConfig;
import android.media.AudioManager;
import android.media.AudioPort;
import android.media.IVolumeController;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.Looper;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;

public class CarAudioService extends ICarAudio.Stub implements CarServiceBase {

    private static final boolean DBG = false;
    private static final boolean DBG_DYNAMIC_AUDIO_ROUTING = false;

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final boolean mUseDynamicRouting;

    private AudioPolicy mAudioPolicy;
    private AudioRoutingPolicy mAudioRoutingPolicy;
    private IVolumeController mVolumeController;
    private AudioDeviceInfo[] mAudioDeviceInfos;
    private int[] mAudioDeviceGainIndexes;

    public CarAudioService(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        Resources res = context.getResources();
        mUseDynamicRouting = res.getBoolean(R.bool.audioUseDynamicRouting);
    }

    @Override
    public AudioAttributes getAudioAttributesForCarUsage(int carUsage) {
        return CarAudioAttributesUtil.getAudioAttributesForCarUsage(carUsage);
    }

    @Override
    public void init() {
        mAudioRoutingPolicy = AudioRoutingPolicy.create(mContext, 0);

        if (mUseDynamicRouting) {
            AudioPolicy.Builder builder = new AudioPolicy.Builder(mContext);
            builder.setLooper(Looper.getMainLooper());
            setupDynamicRouting(mAudioRoutingPolicy, builder);
            mAudioPolicy = builder.build();
        }

        // Register audio policy only after this class is fully initialized.
        int r = mAudioManager.registerAudioPolicy(mAudioPolicy);
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
        mAudioDeviceInfos = new AudioDeviceInfo[numPhysicalStreams];
        mAudioDeviceGainIndexes = new int[numPhysicalStreams];
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
                    mAudioDeviceInfos[addressNumeric] = info;
                    AudioGain audioGain = getAudioGain(info.getPort());
                    mAudioDeviceGainIndexes[addressNumeric] =
                            gainToIndex(audioGain, audioGain.defaultValue());
                    Log.i(CarLog.TAG_AUDIO, String.format(
                            "valid bus found, devie=%s id=%d name=%s addr=%s gainIndex=%d",
                            info.toString(), info.getId(), info.getProductName(), info.getAddress(),
                            mAudioDeviceGainIndexes[addressNumeric]));
                }
            }
        }
        for (int i = 0; i < numPhysicalStreams; i++) {
            AudioDeviceInfo info = mAudioDeviceInfos[i];
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
            int[] carUsages = audioRoutingPolicy.getCarUsagesForPhysicalStream(i);
            AudioMixingRule.Builder mixingRuleBuilder = new AudioMixingRule.Builder();
            for (int carUsage : carUsages) {
                mixingRuleBuilder.addRule(
                        CarAudioAttributesUtil.getAudioAttributesForCarUsage(carUsage),
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
        if (mAudioPolicy != null) {
            mAudioManager.unregisterAudioPolicyAsync(mAudioPolicy);
            mAudioPolicy = null;
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*CarAudioService*");
        mAudioRoutingPolicy.dump(writer);
    }

    @Override
    public void setUsageVolume(@CarAudioManager.CarAudioUsage int carUsage, int index, int flags) {
        enforceAudioVolumePermission();
        AudioPort audioPort = getAudioPort(carUsage);
        AudioGainConfig audioGainConfig = null;
        AudioGain audioGain = getAudioGain(audioPort);
        if (audioGain != null) {
            int gainValue = indexToGain(audioGain, index);
            // size of gain values is 1 in MODE_JOINT
            audioGainConfig = audioGain.buildConfig(AudioGain.MODE_JOINT,
                    audioGain.channelMask(), new int[] { gainValue }, 0);
        }
        if (audioGainConfig != null) {
            AudioManager.setAudioPortGain(audioPort, audioGainConfig);
        }
    }

    @Override
    public void setVolumeController(IVolumeController controller) {
        enforceAudioVolumePermission();
        /** TODO(hwwang): validate the use cases for {@link IVolumeController} */
        mVolumeController = controller;
    }

    @Override
    public int getUsageMaxVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        enforceAudioVolumePermission();
        AudioPort audioPort = getAudioPort(carUsage);
        AudioGain audioGain = getAudioGain(audioPort);
        if (audioGain == null) {
            throw new RuntimeException("No audio gain found for car usage: " + carUsage);
        }
        return gainToIndex(audioGain, audioGain.maxValue());
    }

    @Override
    public int getUsageMinVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        enforceAudioVolumePermission();
        AudioPort audioPort = getAudioPort(carUsage);
        AudioGain audioGain = getAudioGain(audioPort);
        if (audioGain == null) {
            throw new RuntimeException("No audio gain found for car usage: " + carUsage);
        }
        /** TODO(hwwang): some audio usages may have a min volume greater than zero */
        return 0;
    }

    @Override
    public int getUsageVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        enforceAudioVolumePermission();
        final int physicalStream = mAudioRoutingPolicy.getPhysicalStreamForCarUsage(carUsage);
        return mAudioDeviceGainIndexes[physicalStream];
    }

    @Override
    public AudioAttributes getAudioAttributesForRadio(String radioType) {
        return CarAudioAttributesUtil.getCarRadioAttributes(radioType);
    }

    @Override
    public AudioAttributes getAudioAttributesForExternalSource(String externalSourceType) {
        return CarAudioAttributesUtil.getCarExtSourceAttributes(externalSourceType);
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

    /**
     * @return {@link AudioPort} that handles the given car audio usage. Multiple car audio usages
     * may share one {@link AudioPort}
     */
    private @Nullable AudioPort getAudioPort(@CarAudioManager.CarAudioUsage int carUsage) {
        final int physicalStream = mAudioRoutingPolicy.getPhysicalStreamForCarUsage(carUsage);
        if (mAudioDeviceInfos[physicalStream] != null) {
            return mAudioDeviceInfos[physicalStream].getPort();
        }
        return null;
    }

    /**
     * @return {@link AudioGain} with {@link AudioGain#MODE_JOINT} on a given {@link AudioPort}
     */
    private @Nullable AudioGain getAudioGain(AudioPort audioPort) {
        if (audioPort != null && audioPort.gains().length > 0) {
            for (AudioGain audioGain : audioPort.gains()) {
                if ((audioGain.mode() & AudioGain.MODE_JOINT) != 0) {
                    return checkAudioGainConfiguration(audioGain);
                }
            }
        }
        return null;
    }

    private AudioGain checkAudioGainConfiguration(AudioGain audioGain) {
        Preconditions.checkArgument(audioGain.maxValue() >= audioGain.minValue());
        Preconditions.checkArgument((audioGain.defaultValue() >= audioGain.minValue())
                && (audioGain.defaultValue() <= audioGain.maxValue()));
        Preconditions.checkArgument(
                ((audioGain.maxValue() - audioGain.minValue()) % audioGain.stepValue()) == 0);
        Preconditions.checkArgument(
                ((audioGain.defaultValue() - audioGain.minValue()) % audioGain.stepValue()) == 0);
        return audioGain;
    }

    /**
     * @param audioGain {@link AudioGain} on a {@link AudioPort}
     * @param gain Gain value in millibel
     * @return index value depends on max / min / step of a given {@link AudioGain}
     */
    private int gainToIndex(AudioGain audioGain, int gain) {
        gain = checkGainBound(audioGain, gain);
        return (gain - audioGain.minValue()) / audioGain.stepValue();
    }

    /**
     * @param audioGain {@link AudioGain} on a {@link AudioPort}
     * @param index index value depends on max / min / step of a given {@link AudioGain}
     * @return gain value in millibel
     */
    private int indexToGain(AudioGain audioGain, int index) {
        final int gain = index * audioGain.stepValue() + audioGain.minValue();
        return checkGainBound(audioGain, gain);
    }

    private int checkGainBound(AudioGain audioGain, int gain) {
        if (gain < audioGain.minValue() || gain > audioGain.maxValue()) {
            throw new RuntimeException("Gain value out of bound: " + gain);
        }
        return gain;
    }
}
