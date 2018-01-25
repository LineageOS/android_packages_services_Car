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
import android.car.media.CarAudioPatchHandle;
import android.car.media.ICarAudio;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.automotive.audiocontrol.V1_0.IAudioControl;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioGain;
import android.media.AudioGainConfig;
import android.media.AudioManager;
import android.media.AudioPatch;
import android.media.AudioPort;
import android.media.AudioPortConfig;
import android.media.IVolumeController;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

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
            int[] usages = audioRoutingPolicy.getUsagesForPhysicalStream(i);
            AudioMixingRule.Builder mixingRuleBuilder = new AudioMixingRule.Builder();
            for (int usage : usages) {
                mixingRuleBuilder.addRule(
                        new AudioAttributes.Builder().setUsage(usage).build(),
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
            int currentNumChannels = Integer.bitCount(channelMasks[i]);
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
    public void setUsageVolume(
            @AudioAttributes.AttributeUsage int usage, int index, int flags) {
        // TODO:  Use or remove flags argument
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        AudioPort audioPort = getAudioPort(usage);
        AudioGainConfig audioGainConfig = getPortGainForIndex(audioPort, index);
        if (audioGainConfig != null) {
            AudioManager.setAudioPortGain(audioPort, audioGainConfig);
        }
    }

    @Override
    public void setVolumeController(IVolumeController controller) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        /** TODO(hwwang): validate the use cases for {@link IVolumeController} */
        mVolumeController = controller;
    }

    @Override
    public int getUsageMaxVolume(@AudioAttributes.AttributeUsage int usage) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        AudioPort audioPort = getAudioPort(usage);
        AudioGain audioGain = getAudioGain(audioPort);
        if (audioGain == null) {
            throw new RuntimeException("No audio gain found for usage: " + usage);
        }
        return gainToIndex(audioGain, audioGain.maxValue());
    }

    @Override
    public int getUsageMinVolume(@AudioAttributes.AttributeUsage int usage) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        AudioPort audioPort = getAudioPort(usage);
        AudioGain audioGain = getAudioGain(audioPort);
        if (audioGain == null) {
            throw new RuntimeException("No audio gain found for usage: " + usage);
        }
        /** TODO(hwwang): some audio usages may have a min volume greater than zero.  Can they ever be negative? */
        return 0;
    }

    @Override
    public int getUsageVolume(@AudioAttributes.AttributeUsage int usage) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        final int physicalStream = mAudioRoutingPolicy.getPhysicalStreamForUsage(usage);
        return mAudioDeviceGainIndexes[physicalStream];
    }

    @Override
    public void setFadeTowardFront(float value) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        final IAudioControl audioControlHal = getAudioControl();
        if (audioControlHal != null) {
            try {
                audioControlHal.setFadeTowardFront(value);
            } catch (RemoteException e) {
                Log.e(CarLog.TAG_SERVICE, "setFadeTowardFront failed", e);
            }
        }
    }

    @Override
    public void setBalanceTowardRight(float value) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        final IAudioControl audioControlHal = getAudioControl();
        if (audioControlHal != null) {
            try {
                audioControlHal.setBalanceTowardRight(value);
            } catch (RemoteException e) {
                Log.e(CarLog.TAG_SERVICE, "setBalanceTowardRight failed", e);
            }
        }
    }

    @Override
    public String[] getExternalSources() {
        List<String> sourceNames = new ArrayList<>();

        AudioDeviceInfo[] devices = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (devices.length == 0) {
            Log.w(CarLog.TAG_AUDIO, "getExternalSources, no input devices found.");
        }

        // Collect the list of non-microphone input ports
        for (AudioDeviceInfo info: devices) {
            switch (info.getType()) {
                // TODO:  Can we trim this set down?  Espcially duplicates that FM vs FM_TUNER?
                case AudioDeviceInfo.TYPE_FM:
                case AudioDeviceInfo.TYPE_FM_TUNER:
                case AudioDeviceInfo.TYPE_TV_TUNER:
                case AudioDeviceInfo.TYPE_HDMI:
                case AudioDeviceInfo.TYPE_AUX_LINE:
                case AudioDeviceInfo.TYPE_LINE_ANALOG:
                case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_IP:
                case AudioDeviceInfo.TYPE_BUS:
                    sourceNames.add(info.getProductName().toString());
            }
        }

        // Return our list of accumulated device names (or an empty array if we found nothing)
        return sourceNames.toArray(new String[sourceNames.size()]);
    }

    @Override
    public CarAudioPatchHandle createAudioPatch(String sourceName, int usage, int gainIndex) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);

        // Find the named source port
        AudioDevicePort sourcePort = null;
        AudioDeviceInfo[] devices = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo info: devices) {
            if (sourceName.equals(info.getProductName())) {
                // This is the one for which we're looking
                sourcePort = info.getPort();
            }
        }
        if (sourcePort == null) {
            throw new IllegalArgumentException("Specified source is not available: " + sourceName);
        }

        // Find the output port associated with the given carUsage
        AudioDevicePort sinkPort = getAudioPort(usage);
        if (sinkPort == null) {
            throw new IllegalArgumentException("Sink not available for usage: " + usage);
        }

        // Continue to use the current port config on the output bus
        AudioPortConfig sinkConfig = sinkPort.activeConfig();

        // Configure the source port to match the output bus with optional gain adjustment
        AudioGainConfig audioGainConfig = null;
        if (gainIndex >= 0) {
            audioGainConfig = getPortGainForIndex(sourcePort, gainIndex);
            if (audioGainConfig == null) {
                Log.w(CarLog.TAG_AUDIO, "audio gain could not be applied.");
            }
        }
        AudioPortConfig sourceConfig = sourcePort.buildConfig(
                sinkConfig.samplingRate(),
                sinkConfig.channelMask(),
                sinkConfig.format(),
                audioGainConfig);

        // Create an audioPatch to connect the two ports
        AudioPatch[] patch = null;
        AudioPortConfig[] sourceConfigs = {sourceConfig};
        AudioPortConfig[] sinkConfigs   = {sinkConfig};
        int result = mAudioManager.createAudioPatch(patch, sourceConfigs, sinkConfigs);
        if (result != AudioManager.SUCCESS) {
            throw new RuntimeException("createAudioPatch failed with code " + result);
        }
        if (patch.length != 1) {
            throw new RuntimeException("createAudioPatch didn't provide the expected single handle");
        }

        return new CarAudioPatchHandle(patch[0]);
    }

    @Override
    public void releaseAudioPatch(CarAudioPatchHandle carPatch) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);

        // NOTE:  AudioPolicyService::removeNotificationClient will take care of this automatically
        //        if the client that created a patch quits.

        // Get the list of active patches
        ArrayList<AudioPatch> patches = new ArrayList<AudioPatch>();
        int result = mAudioManager.listAudioPatches(patches);
        if (result != AudioManager.SUCCESS) {
            throw new RuntimeException("listAudioPatches failed with code " + result);
        }

        // Look for a patch that matches the provided user side handle
        for (AudioPatch patch: patches) {
            if (carPatch.represents(patch)) {
                // Found it!
                result = mAudioManager.releaseAudioPatch(patch);
                if (result != AudioManager.SUCCESS) {
                    throw new RuntimeException("releaseAudioPatch failed with code " + result);
                }
                return;
            }
        }

        // If we didn't find a match, then something went awry, but it's probably not fatal...
        Log.e(CarLog.TAG_AUDIO, "releaseAudioPatch found no match for " + carPatch.toString());
    }

    public AudioRoutingPolicy getAudioRoutingPolicy() {
        return mAudioRoutingPolicy;
    }

    private void enforcePermission(String permissionName) {
        if (mContext.checkCallingOrSelfPermission(permissionName)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "requires permission " + permissionName);
        }
    }

    /**
     * @return {@link AudioDevicePort} that handles the given car audio usage. Multiple car
     * audio usages may share one {@link AudioDevicePort}
     */
    private @Nullable AudioDevicePort getAudioPort(@AudioAttributes.AttributeUsage int usage) {
        final int physicalStream = mAudioRoutingPolicy.getPhysicalStreamForUsage(usage);
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

    @Nullable
    private AudioGainConfig getPortGainForIndex(AudioPort port, int index) {
        AudioGainConfig audioGainConfig = null;
        AudioGain audioGain = getAudioGain(port);
        if (audioGain != null) {
            int gainValue = indexToGain(audioGain, index);
            // size of gain values is 1 in MODE_JOINT
            audioGainConfig = audioGain.buildConfig(AudioGain.MODE_JOINT,
                    audioGain.channelMask(), new int[] { gainValue }, 0);
        }
        return audioGainConfig;
    }

    private int checkGainBound(AudioGain audioGain, int gain) {
        if (gain < audioGain.minValue() || gain > audioGain.maxValue()) {
            throw new RuntimeException("Gain value out of bound: " + gain);
        }
        return gain;
    }

    @Nullable
    private static IAudioControl getAudioControl() {
        try {
            return android.hardware.automotive.audiocontrol.V1_0.IAudioControl.getService();
        } catch (RemoteException e) {
            Log.e(CarLog.TAG_SERVICE, "Failed to get IAudioControl service", e);
        } catch (NoSuchElementException e) {
            Log.e(CarLog.TAG_SERVICE, "IAudioControl service not registered yet");
        }
        return null;
    }
}
