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
import android.car.media.ICarVolumeCallback;
import android.content.Context;
import android.content.pm.PackageManager;
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
import android.media.AudioPortConfig;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
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

    // For legacy stream type based volume control.
    // Values in STREAM_TYPES and STREAM_TYPE_USAGES should be aligned.
    private static final int[] STREAM_TYPES = new int[] {
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_RING
    };
    private static final int[] STREAM_TYPE_USAGES = new int[] {
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    };

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
    private final SparseIntArray mContextToBus = new SparseIntArray();
    private final SparseArray<CarAudioDeviceInfo> mCarAudioDeviceInfos = new SparseArray<>();

    private final AudioPolicy.AudioPolicyVolumeCallback mAudioPolicyVolumeCallback =
            new AudioPolicy.AudioPolicyVolumeCallback() {
        @Override
        public void onVolumeAdjustment(int adjustment) {
            final int usage = getSuggestedAudioUsage();
            Log.v(CarLog.TAG_AUDIO,
                    "onVolumeAdjustment: " + AudioManager.adjustToString(adjustment)
                            + " suggested usage: " + AudioAttributes.usageToString(usage));
            final int groupId = getVolumeGroupIdForUsage(usage);
            final int currentVolume = getGroupVolume(groupId);
            final int flags = AudioManager.FLAG_FROM_KEY;
            switch (adjustment) {
                case AudioManager.ADJUST_LOWER:
                    if (currentVolume > getGroupMinVolume(groupId)) {
                        setGroupVolume(groupId, currentVolume - 1, flags);
                    }
                    break;
                case AudioManager.ADJUST_RAISE:
                    if (currentVolume < getGroupMaxVolume(groupId)) {
                        setGroupVolume(groupId, currentVolume + 1, flags);
                    }
                    break;
                case AudioManager.ADJUST_MUTE:
                    setMasterMute(true, flags);
                    break;
                case AudioManager.ADJUST_UNMUTE:
                    setMasterMute(false, flags);
                    break;
                case AudioManager.ADJUST_TOGGLE_MUTE:
                    setMasterMute(!mAudioManager.isMasterMute(), flags);
                    break;
                case AudioManager.ADJUST_SAME:
                default:
                    break;
            }
        }
    };

    private final BinderInterfaceContainer<ICarVolumeCallback> mVolumeCallbackContainer =
            new BinderInterfaceContainer<>();

    private AudioPolicy mAudioPolicy;
    private CarVolumeGroup[] mCarVolumeGroups;

    public CarAudioService(Context context) {
        mContext = context;
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mUseDynamicRouting = mContext.getResources().getBoolean(R.bool.audioUseDynamicRouting);
    }

    /**
     * Dynamic routing and volume groups are set only if
     * {@link #mUseDynamicRouting} is {@code true}. Otherwise, this service runs in legacy mode.
     */
    @Override
    public void init() {
        if (!mUseDynamicRouting) {
            Log.i(CarLog.TAG_AUDIO, "Audio dynamic routing not configured, run in legacy mode");
            return;
        }

        setupDynamicRouting();
        setupVolumeGroups();
    }

    @Override
    public void release() {
        if (mUseDynamicRouting && mAudioPolicy != null) {
            mAudioManager.unregisterAudioPolicyAsync(mAudioPolicy);
            mAudioPolicy = null;
        }

        mVolumeCallbackContainer.clear();
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*CarAudioService*");
        writer.println("Run in legacy mode? " + (!mUseDynamicRouting));
        // Empty line for comfortable reading
        writer.println();
        if (mUseDynamicRouting) {
            for (CarVolumeGroup group : mCarVolumeGroups) {
                group.dump(writer);
            }
        }
    }

    /**
     * @see {@link android.car.media.CarAudioManager#setGroupVolume(int, int, int)}
     */
    @Override
    public void setGroupVolume(int groupId, int index, int flags) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        for (BinderInterfaceContainer.BinderInterface<ICarVolumeCallback> callback :
                mVolumeCallbackContainer.getInterfaces()) {
            try {
                callback.binderInterface.onGroupVolumeChanged(groupId);
            } catch (RemoteException e) {
                Log.e(CarLog.TAG_AUDIO, "Failed to callback onGroupVolumeChanged", e);
            }
        }

        // For legacy stream type based volume control
        if (!mUseDynamicRouting) {
            mAudioManager.setStreamVolume(STREAM_TYPES[groupId], index, flags);
            return;
        }

        CarVolumeGroup group = getCarVolumeGroup(groupId);
        group.setCurrentGainIndex(index);
    }

    private void setMasterMute(boolean mute, int flags) {
        mAudioManager.setMasterMute(mute, flags);
        for (BinderInterfaceContainer.BinderInterface<ICarVolumeCallback> callback :
                mVolumeCallbackContainer.getInterfaces()) {
            try {
                callback.binderInterface.onMasterMuteChanged();
            } catch (RemoteException e) {
                Log.e(CarLog.TAG_AUDIO, "Failed to callback onMasterMuteChanged", e);
            }
        }
    }

    /**
     * @see {@link android.car.media.CarAudioManager#getGroupMaxVolume(int)}
     */
    @Override
    public int getGroupMaxVolume(int groupId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        // For legacy stream type based volume control
        if (!mUseDynamicRouting) {
            return mAudioManager.getStreamMaxVolume(STREAM_TYPES[groupId]);
        }

        CarVolumeGroup group = getCarVolumeGroup(groupId);
        return group.getMaxGainIndex();
    }

    /**
     * @see {@link android.car.media.CarAudioManager#getGroupMinVolume(int)}
     */
    @Override
    public int getGroupMinVolume(int groupId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        // For legacy stream type based volume control
        if (!mUseDynamicRouting) {
            return mAudioManager.getStreamMinVolume(STREAM_TYPES[groupId]);
        }

        CarVolumeGroup group = getCarVolumeGroup(groupId);
        return group.getMinGainIndex();
    }

    /**
     * @see {@link android.car.media.CarAudioManager#getGroupVolume(int)}
     */
    @Override
    public int getGroupVolume(int groupId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        // For legacy stream type based volume control
        if (!mUseDynamicRouting) {
            return mAudioManager.getStreamVolume(STREAM_TYPES[groupId]);
        }

        CarVolumeGroup group = getCarVolumeGroup(groupId);
        return group.getCurrentGainIndex();
    }

    private CarVolumeGroup getCarVolumeGroup(int groupId) {
        Preconditions.checkNotNull(mCarVolumeGroups);
        Preconditions.checkArgument(groupId >= 0 && groupId < mCarVolumeGroups.length,
                "groupId out of range: " + groupId);
        return mCarVolumeGroups[groupId];
    }

    private void setupDynamicRouting() {
        final IAudioControl audioControl = getAudioControl();
        if (audioControl == null) {
            return;
        }
        AudioPolicy audioPolicy = getDynamicAudioPolicy(audioControl);
        int r = mAudioManager.registerAudioPolicy(audioPolicy);
        if (r != AudioManager.SUCCESS) {
            throw new RuntimeException("registerAudioPolicy failed " + r);
        }
        mAudioPolicy = audioPolicy;
    }

    private void setupVolumeGroups() {
        Preconditions.checkArgument(mCarAudioDeviceInfos.size() > 0,
                "No bus device is configured to setup volume groups");
        final CarVolumeGroupsHelper helper = new CarVolumeGroupsHelper(
                mContext, R.xml.car_volume_groups);
        mCarVolumeGroups = helper.loadVolumeGroups();
        for (CarVolumeGroup group : mCarVolumeGroups) {
            for (int contextNumber : group.getContexts()) {
                int busNumber = mContextToBus.get(contextNumber);
                group.bind(contextNumber, busNumber, mCarAudioDeviceInfos.get(busNumber));
            }

            // Now that we have all our contexts, ensure the HAL gets our intial value
            group.setCurrentGainIndex(group.getCurrentGainIndex());

            Log.v(CarLog.TAG_AUDIO, "Processed volume group: " + group);
        }
        // Perform validation after all volume groups are processed
        if (!validateVolumeGroups()) {
            throw new RuntimeException("Invalid volume groups configuration");
        }
    }

    /**
     * Constraints applied here:
     *
     * - One context should not appear in two groups
     * - All contexts are assigned
     * - One bus should not appear in two groups
     * - All gain controllers in the same group have same step value
     *
     * Note that it is fine that there are buses not appear in any group, those buses may be
     * reserved for other usages.
     * Step value validation is done in {@link CarVolumeGroup#bind(int, int, CarAudioDeviceInfo)}
     *
     * See also the car_volume_groups.xml configuration
     */
    private boolean validateVolumeGroups() {
        Set<Integer> contextSet = new HashSet<>();
        Set<Integer> busNumberSet = new HashSet<>();
        for (CarVolumeGroup group : mCarVolumeGroups) {
            // One context should not appear in two groups
            for (int context : group.getContexts()) {
                if (contextSet.contains(context)) {
                    Log.e(CarLog.TAG_AUDIO, "Context appears in two groups: " + context);
                    return false;
                }
                contextSet.add(context);
            }

            // One bus should not appear in two groups
            for (int busNumber : group.getBusNumbers()) {
                if (busNumberSet.contains(busNumber)) {
                    Log.e(CarLog.TAG_AUDIO, "Bus appears in two groups: " + busNumber);
                    return false;
                }
                busNumberSet.add(busNumber);
            }
        }

        // All contexts are assigned
        if (contextSet.size() != CONTEXT_NUMBERS.length) {
            Log.e(CarLog.TAG_AUDIO, "Some contexts are not assigned to group");
            Log.e(CarLog.TAG_AUDIO, "Assigned contexts "
                    + Arrays.toString(contextSet.toArray(new Integer[contextSet.size()])));
            Log.e(CarLog.TAG_AUDIO, "All contexts " + Arrays.toString(CONTEXT_NUMBERS));
            return false;
        }

        return true;
    }

    @Nullable
    private AudioPolicy getDynamicAudioPolicy(@NonNull IAudioControl audioControl) {
        AudioPolicy.Builder builder = new AudioPolicy.Builder(mContext);
        builder.setLooper(Looper.getMainLooper());

        // 1st, enumerate all output bus device ports
        AudioDeviceInfo[] deviceInfos = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (deviceInfos.length == 0) {
            Log.e(CarLog.TAG_AUDIO, "getDynamicAudioPolicy, no output device available, ignore");
            return null;
        }
        for (AudioDeviceInfo info : deviceInfos) {
            Log.v(CarLog.TAG_AUDIO, String.format(
                    "output device=%s id=%d name=%s address=%s type=%s",
                    info.toString(), info.getId(), info.getProductName(), info.getAddress(),
                    info.getType()));
            if (info.getType() == AudioDeviceInfo.TYPE_BUS) {
                final CarAudioDeviceInfo carInfo = new CarAudioDeviceInfo(info);
                // See also the audio_policy_configuration.xml and getBusForContext in
                // audio control HAL, the bus number should be no less than zero.
                if (carInfo.getBusNumber() >= 0) {
                    mCarAudioDeviceInfos.put(carInfo.getBusNumber(), carInfo);
                    Log.i(CarLog.TAG_AUDIO, "Valid bus found " + carInfo);
                }
            }
        }

        // 2nd, enumerate bus for all supported contexts and build the routing policy
        try {
            for (int contextNumber : CONTEXT_NUMBERS) {
                int busNumber = audioControl.getBusForContext(contextNumber);
                mContextToBus.put(contextNumber, busNumber);
                CarAudioDeviceInfo info = mCarAudioDeviceInfos.get(busNumber);
                if (info == null) {
                    Log.w(CarLog.TAG_AUDIO, "No bus configured for context: " + contextNumber);
                    continue;
                }
                AudioFormat mixFormat = new AudioFormat.Builder()
                        .setSampleRate(info.getSampleRate())
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(info.getChannelCount())
                        .build();
                int[] usages = getUsagesForContext(contextNumber);
                Log.i(CarLog.TAG_AUDIO, "Bus number: " + busNumber
                        + " sampleRate: " + info.getSampleRate()
                        + " channels: " + info.getChannelCount()
                        + " usages: " + Arrays.toString(usages));
                AudioMixingRule.Builder mixingRuleBuilder = new AudioMixingRule.Builder();
                for (int usage : usages) {
                    mixingRuleBuilder.addRule(
                            new AudioAttributes.Builder().setUsage(usage).build(),
                            AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);
                }
                AudioMix audioMix = new AudioMix.Builder(mixingRuleBuilder.build())
                        .setFormat(mixFormat)
                        .setDevice(info.getAudioDeviceInfo())
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
                Log.e(CarLog.TAG_AUDIO, "setFadeTowardFront failed", e);
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
                Log.e(CarLog.TAG_AUDIO, "setBalanceTowardRight failed", e);
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
    public CarAudioPatchHandle createAudioPatch(String sourceName, int usage, int gainInMillibels) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);

        // Find the named source port
        AudioDeviceInfo sourcePortInfo = null;
        AudioDeviceInfo[] deviceInfos = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo info: deviceInfos) {
            if (sourceName.equals(info.getProductName())) {
                // This is the one for which we're looking
                sourcePortInfo = info;
            }
        }
        if (sourcePortInfo == null) {
            throw new IllegalArgumentException("Specified source is not available: " + sourceName);
        }

        // Find the output port associated with the given carUsage
        AudioDevicePort sinkPort = getAudioPort(usage);
        if (sinkPort == null) {
            throw new IllegalArgumentException("Sink not available for usage: " + usage);
        }

        // Use the current port config on the output bus
        AudioPortConfig sinkConfig = sinkPort.activeConfig();

        // Configure the source port to match the output port except for a gain adjustment
        final CarAudioDeviceInfo helper = new CarAudioDeviceInfo(sourcePortInfo);
        AudioGain audioGain = helper.getAudioGain();
        if (audioGain == null) {
            throw new RuntimeException("Gain controller not available");
        }
        // size of gain values is 1 in MODE_JOINT
        AudioGainConfig audioGainConfig = audioGain.buildConfig(
                AudioGain.MODE_JOINT,
                audioGain.channelMask(),
                new int[] { gainInMillibels },
                0);
        if (audioGainConfig == null) {
            throw new RuntimeException("Failed to construct AudioGainConfig");
        }
        AudioPortConfig sourceConfig = sourcePortInfo.getPort().buildConfig(
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
            throw new RuntimeException("createAudioPatch didn't provide expected single handle");
        }

        return new CarAudioPatchHandle(patch[0]);
    }

    @Override
    public void releaseAudioPatch(CarAudioPatchHandle carPatch) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);

        // NOTE:  AudioPolicyService::removeNotificationClient will take care of this automatically
        //        if the client that created a patch quits.

        // Get the list of active patches
        ArrayList<AudioPatch> patches = new ArrayList<>();
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
        Log.e(CarLog.TAG_AUDIO, "releaseAudioPatch found no match for " + carPatch);
    }

    @Override
    public int getVolumeGroupCount() {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        // For legacy stream type based volume control
        if (!mUseDynamicRouting) return STREAM_TYPES.length;

        return mCarVolumeGroups == null ? 0 : mCarVolumeGroups.length;
    }

    @Override
    public int getVolumeGroupIdForUsage(@AudioAttributes.AttributeUsage int usage) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        if (mCarVolumeGroups == null) {
            return -1;
        }

        for (int i = 0; i < mCarVolumeGroups.length; i++) {
            int[] contexts = mCarVolumeGroups[i].getContexts();
            for (int context : contexts) {
                if (USAGE_TO_CONTEXT.get(usage) == context) {
                    return i;
                }
            }
        }
        return -1;
    }

    @Override
    public @NonNull int[] getUsagesForVolumeGroupId(int groupId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        // For legacy stream type based volume control
        if (!mUseDynamicRouting) {
            return new int[] { STREAM_TYPE_USAGES[groupId] };
        }

        CarVolumeGroup group = getCarVolumeGroup(groupId);
        Set<Integer> contexts =
                Arrays.stream(group.getContexts()).boxed().collect(Collectors.toSet());
        final List<Integer> usages = new ArrayList<>();
        for (int i = 0; i < USAGE_TO_CONTEXT.size(); i++) {
            if (contexts.contains(USAGE_TO_CONTEXT.valueAt(i))) {
                usages.add(USAGE_TO_CONTEXT.keyAt(i));
            }
        }
        return usages.stream().mapToInt(i -> i).toArray();
    }

    /**
     * See {@link android.car.media.CarAudioManager#registerVolumeCallback(IBinder)}
     */
    @Override
    public void registerVolumeCallback(@NonNull IBinder binder) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        mVolumeCallbackContainer.addBinder(ICarVolumeCallback.Stub.asInterface(binder));
    }

    /**
     * See {@link android.car.media.CarAudioManager#unregisterVolumeCallback(IBinder)}
     */
    @Override
    public void unregisterVolumeCallback(@NonNull IBinder binder) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        mVolumeCallbackContainer.removeBinder(ICarVolumeCallback.Stub.asInterface(binder));
    }

    private void enforcePermission(String permissionName) {
        if (mContext.checkCallingOrSelfPermission(permissionName)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "requires permission " + permissionName);
        }
    }

    /**
     * @return {@link AudioDevicePort} that handles the given car audio usage.
     * Multiple usages may share one {@link AudioDevicePort}
     */
    private @Nullable AudioDevicePort getAudioPort(@AudioAttributes.AttributeUsage int usage) {
        final int groupId = getVolumeGroupIdForUsage(usage);
        final CarVolumeGroup group = Preconditions.checkNotNull(mCarVolumeGroups[groupId],
                "Can not find CarVolumeGroup by usage: "
                        + AudioAttributes.usageToString(usage));
        return group.getAudioDevicePortForContext(USAGE_TO_CONTEXT.get(usage));
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
                    .filter(AudioPlaybackConfiguration::isActive)
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
            Log.e(CarLog.TAG_AUDIO, "Failed to get IAudioControl service", e);
        } catch (NoSuchElementException e) {
            Log.e(CarLog.TAG_AUDIO, "IAudioControl service not registered yet");
        }
        return null;
    }
}
