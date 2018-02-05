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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.Car;
import android.car.media.CarAudioPatchHandle;
import android.car.media.ICarAudio;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.automotive.audiocontrol.V1_0.ContextNumber;
import android.hardware.automotive.audiocontrol.V1_0.IAudioControl;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioGain;
import android.media.AudioGainConfig;
import android.media.AudioManager;
import android.media.AudioPatch;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioPort;
import android.media.AudioPortConfig;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.Looper;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

public class CarAudioService extends ICarAudio.Stub implements CarServiceBase {

    private static final int DEFAULT_AUDIO_USAGE = AudioAttributes.USAGE_MEDIA;

    private static final int[] CONTEXT_NUMBERS = new int[] {
            ContextNumber.MUSIC,
            ContextNumber.NAVIGATION,
            ContextNumber.VOICE_COMMAND,
            ContextNumber.CALL_RING,
            ContextNumber.CALL,
            ContextNumber.ALARM,
            ContextNumber.NOTIFICATION,
            ContextNumber.SYSTEM_SOUND
    };

    private static final SparseIntArray USAGE_TO_CONTEXT = new SparseIntArray();

    static {
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_UNKNOWN, ContextNumber.MUSIC);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_MEDIA, ContextNumber.MUSIC);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_VOICE_COMMUNICATION, ContextNumber.CALL);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
                ContextNumber.CALL);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_ALARM, ContextNumber.ALARM);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_NOTIFICATION, ContextNumber.NOTIFICATION);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, ContextNumber.CALL_RING);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
                ContextNumber.NOTIFICATION);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
                ContextNumber.NOTIFICATION);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
                ContextNumber.NOTIFICATION);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_NOTIFICATION_EVENT, ContextNumber.NOTIFICATION);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
                ContextNumber.VOICE_COMMAND);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                ContextNumber.NAVIGATION);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                ContextNumber.SYSTEM_SOUND);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_GAME, ContextNumber.MUSIC);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_VIRTUAL_SOURCE, ContextNumber.INVALID);
        USAGE_TO_CONTEXT.put(AudioAttributes.USAGE_ASSISTANT, ContextNumber.VOICE_COMMAND);
    }

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final AudioManager mAudioManager;
    private final boolean mUseDynamicRouting;
    private final SparseIntArray mUsageToBus = new SparseIntArray();
    private final SparseArray<AudioDeviceInfoState> mAudioDeviceInfoStates = new SparseArray<>();

    private final AudioPolicy.AudioPolicyVolumeCallback mAudioPolicyVolumeCallback =
            new AudioPolicy.AudioPolicyVolumeCallback() {
        @Override
        public void onVolumeAdjustment(int adjustment) {
            final int usage = getSuggestedAudioUsage();
            Log.v(CarLog.TAG_AUDIO,
                    "onVolumeAdjustment: " + AudioManager.adjustToString(adjustment)
                            + " suggested usage: " + AudioAttributes.usageToString(usage));
            final int currentVolume = getUsageVolume(usage);
            final int flags = AudioManager.FLAG_FROM_KEY;
            switch (adjustment) {
                case AudioManager.ADJUST_LOWER:
                    if (currentVolume > getUsageMinVolume(usage)) {
                        setUsageVolume(usage, currentVolume - 1, flags);
                    }
                    break;
                case AudioManager.ADJUST_RAISE:
                    if (currentVolume < getUsageMaxVolume(usage)) {
                        setUsageVolume(usage, currentVolume + 1, flags);
                    }
                    break;
                case AudioManager.ADJUST_MUTE:
                    mAudioManager.setMasterMute(true, flags);
                    break;
                case AudioManager.ADJUST_UNMUTE:
                    mAudioManager.setMasterMute(false, flags);
                    break;
                case AudioManager.ADJUST_TOGGLE_MUTE:
                    mAudioManager.setMasterMute(!mAudioManager.isMasterMute(), flags);
                    break;
                case AudioManager.ADJUST_SAME:
                default:
                    break;
            }
        }
    };

    private AudioPolicy mAudioPolicy;

    public CarAudioService(Context context) {
        mContext = context;
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        Resources res = context.getResources();
        mUseDynamicRouting = res.getBoolean(R.bool.audioUseDynamicRouting);
    }

    @Override
    public void init() {
        if (!mUseDynamicRouting) {
            Log.i(CarLog.TAG_AUDIO, "Audio dynamic routing not configured, run in legacy mode");
            return;
        }
        setupDynamicRouting();
    }

    @Override
    public void release() {
        if (mUseDynamicRouting && mAudioPolicy != null) {
            mAudioManager.unregisterAudioPolicyAsync(mAudioPolicy);
            mAudioPolicy = null;
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*CarAudioService*");
        writer.println("mUseDynamicRouting: " + mUseDynamicRouting);
        if (mUseDynamicRouting) {
            int size = mAudioDeviceInfoStates.size();
            for (int i = 0; i < size; i++) {
                writer.println("\tBus number: " + mAudioDeviceInfoStates.keyAt(i));
                AudioDeviceInfoState state = mAudioDeviceInfoStates.valueAt(i);
                writer.printf("\tGain configuration: %s\n", state.toString());
            }
        }
    }

    /**
     * @see {@link android.car.media.CarAudioManager#setUsageVolume(int, int, int)}
     */
    @Override
    public void setUsageVolume(
            @AudioAttributes.AttributeUsage int usage, int index, int flags) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        AudioPort audioPort = getAudioPort(usage);
        AudioGainConfig audioGainConfig = null;
        AudioGain audioGain = getAudioGain(audioPort);
        if (audioGain != null) {
            int gainValue = indexToGain(audioGain, index);
            // size of gain values is 1 in MODE_JOINT
            audioGainConfig = audioGain.buildConfig(AudioGain.MODE_JOINT,
                    audioGain.channelMask(), new int[] { gainValue }, 0);
        }
        if (audioGainConfig != null) {
            int r = AudioManager.setAudioPortGain(audioPort, audioGainConfig);
            if (r == 0) {
                AudioDeviceInfoState state = mAudioDeviceInfoStates.get(mUsageToBus.get(usage));
                state.currentGainIndex = gainToIndex(audioGain, audioGainConfig.values()[0]);
            }
        }
    }

    /**
     * @see {@link android.car.media.CarAudioManager#getUsageMaxVolume(int)}
     */
    @Override
    public int getUsageMaxVolume(@AudioAttributes.AttributeUsage int usage) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        final AudioDeviceInfoState state = mAudioDeviceInfoStates.get(mUsageToBus.get(usage));
        return state == null ? 0 : state.maxGainIndex;
    }

    /**
     * TODO(hwwang): some audio usages may have a min volume greater than zero
     * @see {@link android.car.media.CarAudioManager#getUsageMinVolume(int)}
     */
    @Override
    public int getUsageMinVolume(@AudioAttributes.AttributeUsage int usage) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        final AudioDeviceInfoState state = mAudioDeviceInfoStates.get(mUsageToBus.get(usage));
        return state == null ? 0 : state.minGainIndex;
    }

    /**
     * @see {@link android.car.media.CarAudioManager#getUsageVolume(int)}
     */
    @Override
    public int getUsageVolume(@AudioAttributes.AttributeUsage int usage) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        final AudioDeviceInfoState state = mAudioDeviceInfoStates.get(mUsageToBus.get(usage));
        return state == null ? 0 : state.currentGainIndex;
    }

    private void setupDynamicRouting() {
        final IAudioControl audioControl = getAudioControl();
        if (audioControl == null) {
            return;
        }
        AudioPolicy audioPolicy = getDynamicAudioPolicy(audioControl);
        int r = mAudioManager.registerAudioPolicy(audioPolicy);
        if (r != 0) {
            throw new RuntimeException("registerAudioPolicy failed " + r);
        }
        mAudioPolicy = audioPolicy;
    }

    @Nullable
    private AudioPolicy getDynamicAudioPolicy(@NonNull IAudioControl audioControl) {
        AudioPolicy.Builder builder = new AudioPolicy.Builder(mContext);
        builder.setLooper(Looper.getMainLooper());

        // 1st, enumerate all output bus device ports
        AudioDeviceInfo[] deviceInfos = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (deviceInfos.length == 0) {
            Log.e(CarLog.TAG_AUDIO, "setupDynamicRouting, no output device available, ignore");
            return null;
        }
        for (AudioDeviceInfo info : deviceInfos) {
            Log.v(CarLog.TAG_AUDIO, String.format(
                    "output device=%s id=%d name=%s address=%s type=%s",
                    info.toString(), info.getId(), info.getProductName(), info.getAddress(),
                    info.getType()));
            if (info.getType() == AudioDeviceInfo.TYPE_BUS) {
                int addressNumeric = parseDeviceAddress(info.getAddress());
                if (addressNumeric >= 0) {
                    final AudioDeviceInfoState state = new AudioDeviceInfoState(info);
                    mAudioDeviceInfoStates.put(addressNumeric, state);
                    Log.i(CarLog.TAG_AUDIO, "Valid bus found " + state);
                }
            }
        }

        // 2nd, enumerate bus for all supported contexts and build the routing policy
        try {
            for (int contextNumber : CONTEXT_NUMBERS) {
                int busNumber = audioControl.getBusForContext(contextNumber);
                AudioDeviceInfoState state = mAudioDeviceInfoStates.get(busNumber);
                if (state == null) {
                    Log.w(CarLog.TAG_AUDIO, "No bus configured for context: " + contextNumber);
                    continue;
                }
                AudioFormat mixFormat = new AudioFormat.Builder()
                        .setSampleRate(state.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(state.channelCount)
                        .build();
                Log.i(CarLog.TAG_AUDIO, String.format(
                        "Bus number %d, sampleRate:%d, channels:0x%s",
                        busNumber, state.sampleRate, Integer.toHexString(state.channelCount)));
                int[] usages = getUsagesForContext(contextNumber);
                AudioMixingRule.Builder mixingRuleBuilder = new AudioMixingRule.Builder();
                for (int usage : usages) {
                    mUsageToBus.put(usage, busNumber);
                    mixingRuleBuilder.addRule(
                            new AudioAttributes.Builder().setUsage(usage).build(),
                            AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);
                }
                AudioMix audioMix = new AudioMix.Builder(mixingRuleBuilder.build())
                        .setFormat(mixFormat)
                        .setDevice(state.audioDeviceInfo)
                        .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                        .build();
                builder.addMix(audioMix);
            }
        } catch (RemoteException e) {
            Log.e(CarLog.TAG_AUDIO, "Error mapping context to physical bus", e);
        }

        // 3rd, attach the {@link AudioPolicyVolumeCallback}
        builder.setAudioPolicyVolumeCallback(mAudioPolicyVolumeCallback);

        return builder.build();
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

    private int[] getUsagesForContext(int contextNumber) {
        final List<Integer> usages = new ArrayList<>();
        for (int i = 0; i < USAGE_TO_CONTEXT.size(); i++) {
            if (USAGE_TO_CONTEXT.valueAt(i) == contextNumber) {
                usages.add(USAGE_TO_CONTEXT.keyAt(i));
            }
        }
        return usages.stream().mapToInt(i -> i).toArray();
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
        // TODO(randolphs): refer to TvInputHardwareManager for creating audio patch
        AudioPatch[] patch = new AudioPatch[] { null };
        AudioPortConfig[] sourceConfigs = { sourceConfig };
        AudioPortConfig[] sinkConfigs = { sinkConfig };
        int result = AudioManager.createAudioPatch(patch, sourceConfigs, sinkConfigs);
        if (result != AudioManager.SUCCESS) {
            throw new RuntimeException("createAudioPatch failed with code " + result);
        }
        if (patch[0] == null) {
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
        int result = AudioManager.listAudioPatches(patches);
        if (result != AudioManager.SUCCESS) {
            throw new RuntimeException("listAudioPatches failed with code " + result);
        }

        // Look for a patch that matches the provided user side handle
        for (AudioPatch patch: patches) {
            if (carPatch.represents(patch)) {
                // Found it!
                result = AudioManager.releaseAudioPatch(patch);
                if (result != AudioManager.SUCCESS) {
                    throw new RuntimeException("releaseAudioPatch failed with code " + result);
                }
                return;
            }
        }

        // If we didn't find a match, then something went awry, but it's probably not fatal...
        Log.e(CarLog.TAG_AUDIO, "releaseAudioPatch found no match for " + carPatch.toString());
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
        final int busNumber = mUsageToBus.get(usage);
        if (mAudioDeviceInfoStates.get(busNumber) != null) {
            return mAudioDeviceInfoStates.get(busNumber).audioDeviceInfo.getPort();
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

    /**
     * Constraints applied to gain configuration, see also audio_policy_configuration.xml
     */
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

    /**
     * @return The suggested {@link AudioAttributes} usage to which the volume key events apply
     */
    private @AudioAttributes.AttributeUsage int getSuggestedAudioUsage() {
        int callState = mTelephonyManager.getCallState();
        if (callState == TelephonyManager.CALL_STATE_RINGING) {
            return AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
        } else if (callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            return AudioAttributes.USAGE_VOICE_COMMUNICATION;
        } else {
            List<AudioPlaybackConfiguration> playbacks = mAudioManager
                    .getActivePlaybackConfigurations()
                    .stream()
                    .filter(p -> p.isActive())
                    .collect(Collectors.toList());
            if (!playbacks.isEmpty()) {
                // Get audio usage from active playbacks if there is any, last one if multiple
                return playbacks.get(playbacks.size() - 1).getAudioAttributes().getUsage();
            } else {
                // TODO(b/72695246): Otherwise, get audio usage from foreground activity/window
                return DEFAULT_AUDIO_USAGE;
            }
        }
    }

    @Nullable
    private static IAudioControl getAudioControl() {
        try {
            return IAudioControl.getService();
        } catch (RemoteException e) {
            Log.e(CarLog.TAG_SERVICE, "Failed to get IAudioControl service", e);
        } catch (NoSuchElementException e) {
            Log.e(CarLog.TAG_SERVICE, "IAudioControl service not registered yet");
        }
        return null;
    }

    /**
     * This class holds the min and max gain index translated from min / max / step values in
     * gain control. Also tracks the current gain index on a certain {@link AudioDevicePort}.
     */
    private class AudioDeviceInfoState {
        private final AudioDeviceInfo audioDeviceInfo;
        private final int maxGainIndex;
        private final int minGainIndex;
        private final int sampleRate;
        private final int channelCount;
        private int currentGainIndex;

        private AudioDeviceInfoState(AudioDeviceInfo audioDeviceInfo) {
            this.audioDeviceInfo = audioDeviceInfo;
            this.sampleRate = getMaxSampleRate(audioDeviceInfo);
            this.channelCount = getMaxChannels(audioDeviceInfo);
            final AudioGain audioGain = Preconditions.checkNotNull(
                    getAudioGain(audioDeviceInfo.getPort()),
                    "No audio gain on device port " + audioDeviceInfo);
            this.maxGainIndex = gainToIndex(audioGain, audioGain.maxValue());
            this.minGainIndex = gainToIndex(audioGain, audioGain.minValue());
            this.currentGainIndex = gainToIndex(audioGain, audioGain.defaultValue());
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
            for (int channelMask : channelMasks) {
                int currentNumChannels = Integer.bitCount(channelMask);
                if (currentNumChannels > numChannels) {
                    numChannels = currentNumChannels;
                    channels = channelMask;
                }
            }
            return channels;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("device: " + audioDeviceInfo.getAddress());
            sb.append(" currentGain: " + currentGainIndex);
            sb.append(" maxGain: " + maxGainIndex);
            sb.append(" minGain: " + minGainIndex);
            return sb.toString();
        }
    }
}
