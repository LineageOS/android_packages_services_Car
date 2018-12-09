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
package com.android.car.audio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.Car;
import android.car.media.CarAudioManager;
import android.car.media.CarAudioPatchHandle;
import android.car.media.ICarAudio;
import android.car.media.ICarVolumeCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.media.AudioSystem;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.KeyEvent;

import com.android.car.BinderInterfaceContainer;
import com.android.car.CarLog;
import com.android.car.CarServiceBase;
import com.android.car.R;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service responsible for interaction with car's audio system.
 */
public class CarAudioService extends ICarAudio.Stub implements CarServiceBase {

    // Turning this off will result in falling back to the default focus policy of Android
    // (which boils down to "grant if not in a phone call, else deny").
    // Aside from the obvious effect of ignoring the logic in CarAudioFocus, this will also
    // result in the framework taking over responsibility for ducking in TRANSIENT_LOSS cases.
    // Search for "DUCK_VSHAPE" in PLaybackActivityMonitor.java to see where this happens.
    private static boolean sUseCarAudioFocus = true;

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


    // Key to persist master mute state in system settings
    private static final String VOLUME_SETTINGS_KEY_MASTER_MUTE = "android.car.MASTER_MUTE";

    // The trailing slash forms a directory-liked hierarchy and
    // allows listening for both GROUP/MEDIA and GROUP/NAVIGATION.
    private static final String VOLUME_SETTINGS_KEY_FOR_GROUP_PREFIX = "android.car.VOLUME_GROUP/";

    /**
     * Gets the key to persist volume for a volume group in settings, in primary zone
     *
     * @see {@link #getVolumeSettingsKeyForGroup(int, int)}
     */
    static String getVolumeSettingsKeyForGroup(int groupId) {
        return getVolumeSettingsKeyForGroup(CarAudioManager.PRIMARY_AUDIO_ZONE, groupId);
    }

    /**
     * Gets the key to persist volume for a volume group in settings
     *
     * @param zoneId The audio zone id
     * @param groupId The volume group id
     * @return Key to persist volume index for volume group in system settings
     */
    static String getVolumeSettingsKeyForGroup(int zoneId, int groupId) {
        final int maskedGroupId = (zoneId << 8) + groupId;
        return VOLUME_SETTINGS_KEY_FOR_GROUP_PREFIX + maskedGroupId;
    }

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

    private final Object mImplLock = new Object();

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final AudioManager mAudioManager;
    private final boolean mUseDynamicRouting;
    private final boolean mPersistMasterMuteState;
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
            // TODO: Pass zone id into this callback.
            final int zoneId = CarAudioManager.PRIMARY_AUDIO_ZONE;
            final int groupId = getVolumeGroupIdForUsage(zoneId, usage);
            final int currentVolume = getGroupVolume(zoneId, groupId);
            final int flags = AudioManager.FLAG_FROM_KEY | AudioManager.FLAG_SHOW_UI;
            switch (adjustment) {
                case AudioManager.ADJUST_LOWER:
                    int minValue = Math.max(currentVolume - 1, getGroupMinVolume(zoneId, groupId));
                    setGroupVolume(zoneId, groupId, minValue , flags);
                    break;
                case AudioManager.ADJUST_RAISE:
                    int maxValue =  Math.min(currentVolume + 1, getGroupMaxVolume(zoneId, groupId));
                    setGroupVolume(zoneId, groupId, maxValue, flags);
                    break;
                case AudioManager.ADJUST_MUTE:
                    setMasterMute(true, flags);
                    callbackMasterMuteChange(zoneId, flags);
                    break;
                case AudioManager.ADJUST_UNMUTE:
                    setMasterMute(false, flags);
                    callbackMasterMuteChange(zoneId, flags);
                    break;
                case AudioManager.ADJUST_TOGGLE_MUTE:
                    setMasterMute(!mAudioManager.isMasterMute(), flags);
                    callbackMasterMuteChange(zoneId, flags);
                    break;
                case AudioManager.ADJUST_SAME:
                default:
                    break;
            }
        }
    };

    private final BinderInterfaceContainer<ICarVolumeCallback> mVolumeCallbackContainer =
            new BinderInterfaceContainer<>();

    /**
     * Simulates {@link ICarVolumeCallback} when it's running in legacy mode.
     * This receiver assumes the intent is sent to {@link CarAudioManager#PRIMARY_AUDIO_ZONE}.
     */
    private final BroadcastReceiver mLegacyVolumeChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case AudioManager.VOLUME_CHANGED_ACTION:
                    int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                    int groupId = getVolumeGroupIdForStreamType(streamType);
                    if (groupId == -1) {
                        Log.w(CarLog.TAG_AUDIO, "Unknown stream type: " + streamType);
                    } else {
                        callbackGroupVolumeChange(CarAudioManager.PRIMARY_AUDIO_ZONE, groupId, 0);
                    }
                    break;
                case AudioManager.MASTER_MUTE_CHANGED_ACTION:
                    callbackMasterMuteChange(CarAudioManager.PRIMARY_AUDIO_ZONE, 0);
                    break;
            }
        }
    };

    private AudioPolicy mAudioPolicy;
    private CarAudioFocus mFocusHandler;
    private CarVolumeGroup[] mCarVolumeGroups;

    public CarAudioService(Context context) {
        mContext = context;
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mUseDynamicRouting = mContext.getResources().getBoolean(R.bool.audioUseDynamicRouting);
        mPersistMasterMuteState = mContext.getResources().getBoolean(
                R.bool.audioPersistMasterMuteState);
    }

    /**
     * Dynamic routing and volume groups are set only if
     * {@link #mUseDynamicRouting} is {@code true}. Otherwise, this service runs in legacy mode.
     */
    @Override
    public void init() {
        synchronized (mImplLock) {
            if (!mUseDynamicRouting) {
                Log.i(CarLog.TAG_AUDIO, "Audio dynamic routing not configured, run in legacy mode");
                setupLegacyVolumeChangedListener();
            } else {
                setupDynamicRouting();
                setupVolumeGroups();
            }

            // Restore master mute state if applicable
            if (mPersistMasterMuteState) {
                boolean storedMasterMute = Settings.Global.getInt(mContext.getContentResolver(),
                        VOLUME_SETTINGS_KEY_MASTER_MUTE, 0) != 0;
                setMasterMute(storedMasterMute, 0);
            }
        }
    }

    @Override
    public void release() {
        synchronized (mImplLock) {
            if (mUseDynamicRouting) {
                if (mAudioPolicy != null) {
                    mAudioManager.unregisterAudioPolicyAsync(mAudioPolicy);
                    mAudioPolicy = null;
                    mFocusHandler.setOwningPolicy(null, null);
                    mFocusHandler = null;
                }
            } else {
                mContext.unregisterReceiver(mLegacyVolumeChangedReceiver);
            }

            mVolumeCallbackContainer.clear();
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*CarAudioService*");
        writer.println("\tRun in legacy mode? " + (!mUseDynamicRouting));
        writer.println("\tPersist master mute state? " + mPersistMasterMuteState);
        writer.println("\tMaster muted? " + mAudioManager.isMasterMute());
        // Empty line for comfortable reading
        writer.println();
        if (mUseDynamicRouting) {
            for (CarVolumeGroup group : mCarVolumeGroups) {
                group.dump(writer);
            }
        }
    }

    /**
     * @see {@link android.car.media.CarAudioManager#setGroupVolume(int, int, int, int)}
     */
    @Override
    public void setGroupVolume(int zoneId, int groupId, int index, int flags) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            callbackGroupVolumeChange(zoneId, groupId, flags);
            // For legacy stream type based volume control
            if (!mUseDynamicRouting) {
                mAudioManager.setStreamVolume(STREAM_TYPES[groupId], index, flags);
                return;
            }

            CarVolumeGroup group = getCarVolumeGroup(groupId);
            group.setCurrentGainIndex(index);
        }
    }

    private void callbackGroupVolumeChange(int zoneId, int groupId, int flags) {
        for (BinderInterfaceContainer.BinderInterface<ICarVolumeCallback> callback :
                mVolumeCallbackContainer.getInterfaces()) {
            try {
                callback.binderInterface.onGroupVolumeChanged(zoneId, groupId, flags);
            } catch (RemoteException e) {
                Log.e(CarLog.TAG_AUDIO, "Failed to callback onGroupVolumeChanged", e);
            }
        }
    }

    private void setMasterMute(boolean mute, int flags) {
        mAudioManager.setMasterMute(mute, flags);

        // When the master mute is turned ON, we want the playing app to get a "pause" command.
        // When the volume is unmuted, we want to resume playback.
        int keycode = mute ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY;
        mAudioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keycode));
        mAudioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keycode));
    }

    private void callbackMasterMuteChange(int zoneId, int flags) {
        for (BinderInterfaceContainer.BinderInterface<ICarVolumeCallback> callback :
                mVolumeCallbackContainer.getInterfaces()) {
            try {
                callback.binderInterface.onMasterMuteChanged(zoneId, flags);
            } catch (RemoteException e) {
                Log.e(CarLog.TAG_AUDIO, "Failed to callback onMasterMuteChanged", e);
            }
        }

        // Persists master mute state if applicable
        if (mPersistMasterMuteState) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    VOLUME_SETTINGS_KEY_MASTER_MUTE,
                    mAudioManager.isMasterMute() ? 1 : 0);
        }
    }

    /**
     * @see {@link android.car.media.CarAudioManager#getGroupMaxVolume(int, int)}
     */
    @Override
    public int getGroupMaxVolume(int zoneId, int groupId) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            // For legacy stream type based volume control
            if (!mUseDynamicRouting) {
                return mAudioManager.getStreamMaxVolume(STREAM_TYPES[groupId]);
            }

            CarVolumeGroup group = getCarVolumeGroup(groupId);
            return group.getMaxGainIndex();
        }
    }

    /**
     * @see {@link android.car.media.CarAudioManager#getGroupMinVolume(int, int)}
     */
    @Override
    public int getGroupMinVolume(int zoneId, int groupId) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            // For legacy stream type based volume control
            if (!mUseDynamicRouting) {
                return mAudioManager.getStreamMinVolume(STREAM_TYPES[groupId]);
            }

            CarVolumeGroup group = getCarVolumeGroup(groupId);
            return group.getMinGainIndex();
        }
    }

    /**
     * @see {@link android.car.media.CarAudioManager#getGroupVolume(int, int)}
     */
    @Override
    public int getGroupVolume(int zoneId, int groupId) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            // For legacy stream type based volume control
            if (!mUseDynamicRouting) {
                return mAudioManager.getStreamVolume(STREAM_TYPES[groupId]);
            }

            CarVolumeGroup group = getCarVolumeGroup(groupId);
            return group.getCurrentGainIndex();
        }
    }

    private CarVolumeGroup getCarVolumeGroup(int groupId) {
        Preconditions.checkNotNull(mCarVolumeGroups);
        Preconditions.checkArgument(groupId >= 0 && groupId < mCarVolumeGroups.length,
                "groupId out of range: " + groupId);
        return mCarVolumeGroups[groupId];
    }

    private void setupLegacyVolumeChangedListener() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        intentFilter.addAction(AudioManager.MASTER_MUTE_CHANGED_ACTION);
        mContext.registerReceiver(mLegacyVolumeChangedReceiver, intentFilter);
    }

    private void setupDynamicRouting() {
        final IAudioControl audioControl = getAudioControl();
        if (audioControl == null) {
            throw new RuntimeException(
                "Dynamic routing requested but audioControl HAL not available");
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

        // Enumerate all output bus device ports
        AudioDeviceInfo[] deviceInfos = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (deviceInfos.length == 0) {
            Log.e(CarLog.TAG_AUDIO, "getDynamicAudioPolicy, no output device available, ignore");
            return null;
        }
        for (AudioDeviceInfo info : deviceInfos) {
            Log.v(CarLog.TAG_AUDIO, String.format("output id=%d address=%s type=%s",
                    info.getId(), info.getAddress(), info.getType()));
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

        // Map context to physical bus
        try {
            for (int contextNumber : CONTEXT_NUMBERS) {
                int busNumber = audioControl.getBusForContext(contextNumber);
                mContextToBus.put(contextNumber, busNumber);
                CarAudioDeviceInfo info = mCarAudioDeviceInfos.get(busNumber);
                if (info == null) {
                    Log.w(CarLog.TAG_AUDIO, "No bus configured for context: " + contextNumber);
                }
            }
        } catch (RemoteException e) {
            Log.e(CarLog.TAG_AUDIO, "Error mapping context to physical bus", e);
        }

        // Enumerate all physical buses and build the routing policy.
        // Note that one can not register audio mix for same bus more than once.
        for (int i = 0; i < mCarAudioDeviceInfos.size(); i++) {
            int busNumber = mCarAudioDeviceInfos.keyAt(i);
            boolean hasContext = false;
            CarAudioDeviceInfo info = mCarAudioDeviceInfos.valueAt(i);
            AudioFormat mixFormat = new AudioFormat.Builder()
                    .setSampleRate(info.getSampleRate())
                    .setEncoding(info.getEncodingFormat())
                    .setChannelMask(info.getChannelCount())
                    .build();
            AudioMixingRule.Builder mixingRuleBuilder = new AudioMixingRule.Builder();
            for (int j = 0; j < mContextToBus.size(); j++) {
                if (mContextToBus.valueAt(j) == busNumber) {
                    hasContext = true;
                    int contextNumber = mContextToBus.keyAt(j);
                    int[] usages = getUsagesForContext(contextNumber);
                    for (int usage : usages) {
                        mixingRuleBuilder.addRule(
                                new AudioAttributes.Builder().setUsage(usage).build(),
                                AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);
                    }
                    Log.i(CarLog.TAG_AUDIO, "Bus number: " + busNumber
                            + " contextNumber: " + contextNumber
                            + " sampleRate: " + info.getSampleRate()
                            + " channels: " + info.getChannelCount()
                            + " usages: " + Arrays.toString(usages));
                }
            }
            if (hasContext) {
                // It's a valid case that an audio output bus is defined in
                // audio_policy_configuration and no context is assigned to it.
                // In such case, do not build a policy mix with zero rules.
                AudioMix audioMix = new AudioMix.Builder(mixingRuleBuilder.build())
                        .setFormat(mixFormat)
                        .setDevice(info.getAudioDeviceInfo())
                        .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                        .build();
                builder.addMix(audioMix);
            }
        }

        // Attach the {@link AudioPolicyVolumeCallback}
        builder.setAudioPolicyVolumeCallback(mAudioPolicyVolumeCallback);

        if (sUseCarAudioFocus) {
            // Configure our AudioPolicy to handle focus events.
            // This gives us the ability to decide which audio focus requests to accept and bypasses
            // the framework ducking logic.
            mFocusHandler = new CarAudioFocus(mAudioManager);
            builder.setAudioPolicyFocusListener(mFocusHandler);
            builder.setIsAudioFocusPolicy(true);
        }

        // Instantiate the AudioPolicy
        final AudioPolicy audioPolicy = builder.build();

        if (sUseCarAudioFocus) {
            // Connect the AudioPolicy and the focus listener
            mFocusHandler.setOwningPolicy(this, audioPolicy);
        }

        // Send the completed AudioPolicy back for use
        return audioPolicy;
    }

    // This is public so it can be used by our CarAudioFocus policy implementation, but since
    // "context" is a HAL level concept, this API should not be visible through the
    // CarAudioManager interface.
    // Returns 0 (INVALID) context if an unrecognized audio usage is passed in.
    public int getContextForUsage(int audioUsage) {
        return USAGE_TO_CONTEXT.get(audioUsage);
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
        synchronized (mImplLock) {
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
    }

    @Override
    public void setBalanceTowardRight(float value) {
        synchronized (mImplLock) {
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
    }

    /**
     * @return Array of accumulated device addresses, empty array if we found nothing
     */
    @Override
    public @NonNull String[] getExternalSources() {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
            List<String> sourceAddresses = new ArrayList<>();

            AudioDeviceInfo[] devices = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            if (devices.length == 0) {
                Log.w(CarLog.TAG_AUDIO, "getExternalSources, no input devices found.");
            }

            // Collect the list of non-microphone input ports
            for (AudioDeviceInfo info : devices) {
                switch (info.getType()) {
                    // TODO:  Can we trim this set down? Especially duplicates like FM vs FM_TUNER?
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
                        String address = info.getAddress();
                        if (TextUtils.isEmpty(address)) {
                            Log.w(CarLog.TAG_AUDIO,
                                    "Discarded device with empty address, type=" + info.getType());
                        } else {
                            sourceAddresses.add(address);
                        }
                }
            }

            return sourceAddresses.toArray(new String[sourceAddresses.size()]);
        }
    }

    @Override
    public CarAudioPatchHandle createAudioPatch(String sourceAddress,
            @AudioAttributes.AttributeUsage int usage, int gainInMillibels) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
            return createAudioPatchLocked(sourceAddress, usage, gainInMillibels);
        }
    }

    @Override
    public void releaseAudioPatch(CarAudioPatchHandle carPatch) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
            releaseAudioPatchLocked(carPatch);
        }
    }

    private CarAudioPatchHandle createAudioPatchLocked(String sourceAddress,
            @AudioAttributes.AttributeUsage int usage, int gainInMillibels) {
        // Find the named source port
        AudioDeviceInfo sourcePortInfo = null;
        AudioDeviceInfo[] deviceInfos = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo info : deviceInfos) {
            if (sourceAddress.equals(info.getAddress())) {
                // This is the one for which we're looking
                sourcePortInfo = info;
                break;
            }
        }
        Preconditions.checkNotNull(sourcePortInfo,
                "Specified source is not available: " + sourceAddress);

        // Find the output port associated with the given carUsage
        AudioDevicePort sinkPort = Preconditions.checkNotNull(getAudioPort(usage),
                "Sink not available for usage: " + AudioAttributes.usageToString(usage));

        // {@link android.media.AudioPort#activeConfig()} is valid for mixer port only,
        // since audio framework has no clue what's active on the device ports.
        // Therefore we construct an empty / default configuration here, which the audio HAL
        // implementation should ignore.
        AudioPortConfig sinkConfig = sinkPort.buildConfig(0,
                AudioFormat.CHANNEL_OUT_DEFAULT, AudioFormat.ENCODING_DEFAULT, null);
        Log.d(CarLog.TAG_AUDIO, "createAudioPatch sinkConfig: " + sinkConfig);

        // Configure the source port to match the output port except for a gain adjustment
        final CarAudioDeviceInfo helper = new CarAudioDeviceInfo(sourcePortInfo);
        AudioGain audioGain = Preconditions.checkNotNull(helper.getAudioGain(),
                "Gain controller not available for source port");

        // size of gain values is 1 in MODE_JOINT
        AudioGainConfig audioGainConfig = audioGain.buildConfig(AudioGain.MODE_JOINT,
                audioGain.channelMask(), new int[] { gainInMillibels }, 0);
        // Construct an empty / default configuration excepts gain config here and it's up to the
        // audio HAL how to interpret this configuration, which the audio HAL
        // implementation should ignore.
        AudioPortConfig sourceConfig = sourcePortInfo.getPort().buildConfig(0,
                AudioFormat.CHANNEL_IN_DEFAULT, AudioFormat.ENCODING_DEFAULT, audioGainConfig);

        // Create an audioPatch to connect the two ports
        AudioPatch[] patch = new AudioPatch[] { null };
        int result = AudioManager.createAudioPatch(patch,
                new AudioPortConfig[] { sourceConfig },
                new AudioPortConfig[] { sinkConfig });
        if (result != AudioManager.SUCCESS) {
            throw new RuntimeException("createAudioPatch failed with code " + result);
        }

        Preconditions.checkNotNull(patch[0],
                "createAudioPatch didn't provide expected single handle");
        Log.d(CarLog.TAG_AUDIO, "Audio patch created: " + patch[0]);

        // Ensure the initial volume on output device port
        int groupId = getVolumeGroupIdForUsage(CarAudioManager.PRIMARY_AUDIO_ZONE, usage);
        setGroupVolume(CarAudioManager.PRIMARY_AUDIO_ZONE, groupId,
                getGroupVolume(CarAudioManager.PRIMARY_AUDIO_ZONE, groupId), 0);

        return new CarAudioPatchHandle(patch[0]);
    }

    private void releaseAudioPatchLocked(CarAudioPatchHandle carPatch) {
        // NOTE:  AudioPolicyService::removeNotificationClient will take care of this automatically
        //        if the client that created a patch quits.

        // FIXME {@link AudioManager#listAudioPatches(ArrayList)} returns old generation of
        // audio patches after creation
        ArrayList<AudioPatch> patches = new ArrayList<>();
        int result = AudioSystem.listAudioPatches(patches, new int[1]);
        if (result != AudioManager.SUCCESS) {
            throw new RuntimeException("listAudioPatches failed with code " + result);
        }

        // Look for a patch that matches the provided user side handle
        for (AudioPatch patch : patches) {
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
    public int getVolumeGroupCount(int zoneId) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            // For legacy stream type based volume control
            if (!mUseDynamicRouting) return STREAM_TYPES.length;

            return mCarVolumeGroups == null ? 0 : mCarVolumeGroups.length;
        }
    }

    @Override
    public int getVolumeGroupIdForUsage(int zoneId, @AudioAttributes.AttributeUsage int usage) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            if (mCarVolumeGroups == null) {
                return -1;
            }

            for (int i = 0; i < mCarVolumeGroups.length; i++) {
                int[] contexts = mCarVolumeGroups[i].getContexts();
                for (int context : contexts) {
                    if (getContextForUsage(usage) == context) {
                        return i;
                    }
                }
            }
            return -1;
        }
    }

    @Override
    public @NonNull int[] getUsagesForVolumeGroupId(int zoneId, int groupId) {
        synchronized (mImplLock) {
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
    }

    @Override
    public void registerVolumeCallback(@NonNull IBinder binder) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            mVolumeCallbackContainer.addBinder(ICarVolumeCallback.Stub.asInterface(binder));
        }
    }

    @Override
    public void unregisterVolumeCallback(@NonNull IBinder binder) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            mVolumeCallbackContainer.removeBinder(ICarVolumeCallback.Stub.asInterface(binder));
        }
    }

    private void enforcePermission(String permissionName) {
        if (mContext.checkCallingOrSelfPermission(permissionName)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("requires permission " + permissionName);
        }
    }

    /**
     * @return {@link AudioDevicePort} that handles the given car audio usage.
     * Multiple usages may share one {@link AudioDevicePort}
     */
    private @Nullable AudioDevicePort getAudioPort(@AudioAttributes.AttributeUsage int usage) {
        final int groupId = getVolumeGroupIdForUsage(CarAudioManager.PRIMARY_AUDIO_ZONE, usage);
        final CarVolumeGroup group = Preconditions.checkNotNull(mCarVolumeGroups[groupId],
                "Can not find CarVolumeGroup by usage: "
                        + AudioAttributes.usageToString(usage));
        return group.getAudioDevicePortForContext(getContextForUsage(usage));
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

    /**
     * Gets volume group by a given legacy stream type
     * @param streamType Legacy stream type such as {@link AudioManager#STREAM_MUSIC}
     * @return volume group id mapped from stream type
     */
    private int getVolumeGroupIdForStreamType(int streamType) {
        int groupId = -1;
        for (int i = 0; i < STREAM_TYPES.length; i++) {
            if (streamType == STREAM_TYPES[i]) {
                groupId = i;
                break;
            }
        }
        return groupId;
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
