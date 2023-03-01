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

import static android.car.builtin.media.AudioManagerHelper.UNDEFINED_STREAM_TYPE;
import static android.car.builtin.media.AudioManagerHelper.isMasterMute;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_DYNAMIC_ROUTING;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_OEM_AUDIO_SERVICE;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_VOLUME_GROUP_EVENTS;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_VOLUME_GROUP_MUTING;
import static android.car.media.CarAudioManager.CarAudioFeature;
import static android.car.media.CarAudioManager.INVALID_REQUEST_ID;
import static android.car.media.CarAudioManager.INVALID_VOLUME_GROUP_ID;
import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.ADJUST_RAISE;
import static android.media.AudioManager.ADJUST_SAME;
import static android.media.AudioManager.ADJUST_TOGGLE_MUTE;
import static android.media.AudioManager.FLAG_FROM_KEY;
import static android.media.AudioManager.FLAG_PLAY_SOUND;
import static android.media.AudioManager.FLAG_SHOW_UI;
import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_MUTE;
import static android.view.KeyEvent.KEYCODE_VOLUME_UP;

import static com.android.car.audio.CarVolume.VERSION_TWO;
import static com.android.car.audio.hal.AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_DUCKING;
import static com.android.car.audio.hal.AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_FOCUS;
import static com.android.car.audio.hal.AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_GAIN_CALLBACK;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DEBUGGING_CODE;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DEPRECATED_CODE;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.car.Car;
import android.car.CarOccupantZoneManager;
import android.car.ICarOccupantZoneCallback;
import android.car.builtin.media.AudioManagerHelper;
import android.car.builtin.media.AudioManagerHelper.AudioPatchInfo;
import android.car.builtin.media.AudioManagerHelper.VolumeAndMuteReceiver;
import android.car.builtin.os.TraceHelper;
import android.car.builtin.os.UserManagerHelper;
import android.car.builtin.util.Slogf;
import android.car.builtin.util.TimingsTraceLog;
import android.car.media.AudioZonesMirrorStatusCallback;
import android.car.media.CarAudioManager;
import android.car.media.CarAudioPatchHandle;
import android.car.media.CarAudioZoneConfigInfo;
import android.car.media.CarVolumeGroupInfo;
import android.car.media.IAudioZonesMirrorStatusCallback;
import android.car.media.ICarAudio;
import android.car.media.ICarVolumeCallback;
import android.car.media.ICarVolumeEventCallback;
import android.car.media.IMediaAudioRequestStatusCallback;
import android.car.media.IPrimaryZoneMediaAudioRequestCallback;
import android.car.media.ISwitchAudioZoneConfigCallback;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioPolicy;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.KeyEvent;

import com.android.car.CarInputService;
import com.android.car.CarInputService.KeyEventListener;
import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarOccupantZoneService;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.R;
import com.android.car.audio.CarAudioContext.AudioContext;
import com.android.car.audio.CarAudioPolicyVolumeCallback.AudioPolicyVolumeCallbackInternal;
import com.android.car.audio.hal.AudioControlFactory;
import com.android.car.audio.hal.AudioControlWrapper;
import com.android.car.audio.hal.AudioControlWrapperV1;
import com.android.car.audio.hal.HalAudioFocus;
import com.android.car.audio.hal.HalAudioGainCallback;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.annotation.AttributeUsage;
import com.android.car.internal.util.ArrayUtils;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.oem.CarOemProxyService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Service responsible for interaction with car's audio system.
 */
public final class CarAudioService extends ICarAudio.Stub implements CarServiceBase {

    static final String TAG = CarLog.TAG_AUDIO;

    private static final String CAR_AUDIO_SERVICE_THREAD_NAME = "CarAudioService";

    static final AudioAttributes CAR_DEFAULT_AUDIO_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA);

    private static final String PROPERTY_RO_ENABLE_AUDIO_PATCH =
            "ro.android.car.audio.enableaudiopatch";

    // CarAudioService reads configuration from the following paths respectively.
    // If the first one is found, all others are ignored.
    // If no one is found, it fallbacks to car_volume_groups.xml resource file.
    private static final String[] AUDIO_CONFIGURATION_PATHS = new String[] {
            "/vendor/etc/car_audio_configuration.xml",
            "/system/etc/car_audio_configuration.xml"
    };

    private static final List<Integer> KEYCODES_OF_INTEREST = List.of(
            KEYCODE_VOLUME_DOWN,
            KEYCODE_VOLUME_UP,
            KEYCODE_VOLUME_MUTE
    );
    private static final AudioAttributes MEDIA_AUDIO_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA);

    private final HandlerThread mHandlerThread = CarServiceUtils.getHandlerThread(
            CAR_AUDIO_SERVICE_THREAD_NAME);
    private final Handler mHandler = new Handler(mHandlerThread.getLooper());

    private final Object mImplLock = new Object();

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final AudioManager mAudioManager;
    private final boolean mUseDynamicRouting;
    private final boolean mUseCoreAudioVolume;
    private final boolean mUseCoreAudioRouting;
    private final boolean mUseCarVolumeGroupEvents;
    private final boolean mUseCarVolumeGroupMuting;
    private final boolean mUseHalDuckingSignals;
    private final @CarVolume.CarVolumeListVersion int mAudioVolumeAdjustmentContextsVersion;
    private final boolean mPersistMasterMuteState;
    private final CarAudioSettings mCarAudioSettings;
    private final int mKeyEventTimeoutMs;
    private final MediaRequestHandler mMediaRequestHandler = new MediaRequestHandler();
    private final CarAudioMirrorRequestHandler mCarAudioMirrorRequestHandler =
            new CarAudioMirrorRequestHandler();
    private final CarVolumeEventHandler mCarVolumeEventHandler = new CarVolumeEventHandler();

    private AudioControlWrapper mAudioControlWrapper;
    private CarDucking mCarDucking;
    private CarVolumeGroupMuting mCarVolumeGroupMuting;
    private HalAudioFocus mHalAudioFocus;
    private @Nullable CarAudioGainMonitor mCarAudioGainMonitor;
    @GuardedBy("mImplLock")
    private @Nullable CoreAudioVolumeGroupCallback mCoreAudioVolumeGroupCallback;

    private CarOccupantZoneService mOccupantZoneService;

    /**
     * Simulates {@link ICarVolumeCallback} when it's running in legacy mode.
     * This receiver assumes the intent is sent to {@link CarAudioManager#PRIMARY_AUDIO_ZONE}.
     */
    private final VolumeAndMuteReceiver mLegacyVolumeChangedHelper =
            new AudioManagerHelper.VolumeAndMuteReceiver() {
                @Override
                public void onVolumeChanged(int streamType) {
                    if (streamType == UNDEFINED_STREAM_TYPE) {
                        Slogf.w(TAG, "Invalid stream type: %d", streamType);
                    }
                    int groupId = getVolumeGroupIdForStreamType(streamType);
                    if (groupId == INVALID_VOLUME_GROUP_ID) {
                        Slogf.w(TAG, "Unknown stream type: %d", streamType);
                    } else {
                        callbackGroupVolumeChange(PRIMARY_AUDIO_ZONE, groupId,
                                FLAG_FROM_KEY | FLAG_SHOW_UI);
                    }
                }

                @Override
                public void onMuteChanged() {
                    callbackMasterMuteChange(PRIMARY_AUDIO_ZONE, FLAG_FROM_KEY | FLAG_SHOW_UI);
                }
    };

    private final KeyEventListener mCarKeyEventListener = new KeyEventListener() {
        @Override
        public void onKeyEvent(KeyEvent event, int displayType, int seat) {
            Slogf.i(TAG, "On key event for audio with display type: %d and seat %d", displayType,
                    seat);
            if (event.getAction() != ACTION_DOWN) {
                return;
            }
            int audioZoneId = mOccupantZoneService.getAudioZoneIdForOccupant(
                    mOccupantZoneService.getOccupantZoneIdForSeat(seat));
            if (!isAudioZoneIdValid(audioZoneId)) {
                Slogf.e(TAG, "Audio zone is invalid for event %s, displayType %d, and seat %d",
                        event, displayType, seat);
                return;
            }
            int adjustment;
            switch (event.getKeyCode()) {
                case KEYCODE_VOLUME_DOWN:
                    adjustment = ADJUST_LOWER;
                    break;
                case KEYCODE_VOLUME_UP:
                    adjustment = ADJUST_RAISE;
                    break;
                case KEYCODE_VOLUME_MUTE:
                    adjustment = ADJUST_TOGGLE_MUTE;
                    break;
                default:
                    adjustment = ADJUST_SAME;
                    break;
            }
            synchronized (mImplLock) {
                mCarAudioPolicyVolumeCallback.onVolumeAdjustment(adjustment, audioZoneId);
            }
        }
    };

    private AudioPolicy mAudioPolicy;
    @GuardedBy("mImplLock")
    private CarAudioDeviceInfo mCarAudioMirrorDeviceInfo;
    private CarZonesAudioFocus mFocusHandler;
    private String mCarAudioConfigurationPath;
    private SparseIntArray mAudioZoneIdToOccupantZoneIdMapping;
    @GuardedBy("mImplLock")
    private SparseArray<CarAudioZone> mCarAudioZones;
    @GuardedBy("mImplLock")
    private CarVolume mCarVolume;
    @GuardedBy("mImplLock")
    private CarAudioContext mCarAudioContext;
    private final CarVolumeCallbackHandler mCarVolumeCallbackHandler;
    private final SparseIntArray mAudioZoneIdToUserIdMapping;
    private final SystemClockWrapper mClock = new SystemClockWrapper();

    @GuardedBy("mImplLock")
    private final SparseArray<DeathRecipient>
            mUserAssignedToPrimaryZoneToCallbackDeathRecipient = new SparseArray<>();

    // TODO do not store uid mapping here instead use the uid
    //  device affinity in audio policy when available
    private Map<Integer, Integer> mUidToZoneMap;
    private CarAudioPlaybackCallback mCarAudioPlaybackCallback;
    private CarAudioPowerListener mCarAudioPowerListener;
    private CarInputService mCarInputService;

    private final HalAudioGainCallback mHalAudioGainCallback =
            new HalAudioGainCallback() {
                @Override
                public void onAudioDeviceGainsChanged(
                        List<Integer> halReasons, List<CarAudioGainConfigInfo> gains) {
                    synchronized (mImplLock) {
                        handleAudioDeviceGainsChangedLocked(halReasons, gains);
                    }
                }
            };

    private final ICarOccupantZoneCallback mOccupantZoneCallback =
            new ICarOccupantZoneCallback.Stub() {
                @Override
                public void onOccupantZoneConfigChanged(int flags) {
                    Slogf.d(TAG, "onOccupantZoneConfigChanged(%d)", flags);
                    if (((flags & CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER)
                            != CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER)
                            && ((flags & CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_DISPLAY)
                            != CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_DISPLAY)) {
                        return;
                    }
                    handleOccupantZoneUserChanged();
                }
            };
    @GuardedBy("mImplLock")
    private CarAudioPolicyVolumeCallback mCarAudioPolicyVolumeCallback;

    public CarAudioService(Context context) {
        this(context, getAudioConfigurationPath(), new CarVolumeCallbackHandler());
    }

    @VisibleForTesting
    CarAudioService(Context context, @Nullable String audioConfigurationPath,
            CarVolumeCallbackHandler carVolumeCallbackHandler) {
        mContext = Objects.requireNonNull(context,
                "Context to create car audio service can not be null");
        mCarAudioConfigurationPath = audioConfigurationPath;
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        mUseDynamicRouting = mContext.getResources().getBoolean(R.bool.audioUseDynamicRouting);
        mUseCoreAudioVolume = mContext.getResources().getBoolean(R.bool.audioUseCoreVolume);
        mUseCoreAudioRouting = mContext.getResources().getBoolean(R.bool.audioUseCoreRouting);
        mKeyEventTimeoutMs =
                mContext.getResources().getInteger(R.integer.audioVolumeKeyEventTimeoutMs);
        mUseHalDuckingSignals = mContext.getResources().getBoolean(
                R.bool.audioUseHalDuckingSignals);

        mUidToZoneMap = new HashMap<>();
        mCarVolumeCallbackHandler = carVolumeCallbackHandler;
        mCarAudioSettings = new CarAudioSettings(mContext);
        mAudioZoneIdToUserIdMapping = new SparseIntArray();
        mAudioVolumeAdjustmentContextsVersion =
                mContext.getResources().getInteger(R.integer.audioVolumeAdjustmentContextsVersion);
        boolean useCarVolumeGroupMuting = mUseDynamicRouting && mContext.getResources().getBoolean(
                R.bool.audioUseCarVolumeGroupMuting);
        if (mAudioVolumeAdjustmentContextsVersion != VERSION_TWO && useCarVolumeGroupMuting) {
            throw new IllegalArgumentException("audioUseCarVolumeGroupMuting is enabled but "
                    + "this requires audioVolumeAdjustmentContextsVersion 2,"
                    + " instead version " + mAudioVolumeAdjustmentContextsVersion + " was found");
        }
        // TODO(b/261647905): add new rro flag to enable the feature
        mUseCarVolumeGroupEvents = false;
        mUseCarVolumeGroupMuting = useCarVolumeGroupMuting;
        mPersistMasterMuteState = !mUseCarVolumeGroupMuting && mContext.getResources().getBoolean(
                R.bool.audioPersistMasterMuteState);
    }

    /**
     * Dynamic routing and volume groups are set only if
     * {@link #mUseDynamicRouting} is {@code true}. Otherwise, this service runs in legacy mode.
     */
    @Override
    public void init() {
        synchronized (mImplLock) {
            mOccupantZoneService = CarLocalServices.getService(CarOccupantZoneService.class);
            mCarInputService = CarLocalServices.getService(CarInputService.class);
            if (mUseDynamicRouting) {
                setupDynamicRoutingLocked();
                setupHalAudioFocusListenerLocked();
                setupHalAudioGainCallbackLocked();
                setupAudioConfigurationCallbackLocked();
                setupPowerPolicyListener();
                mCarInputService.registerKeyEventListener(mCarKeyEventListener,
                        KEYCODES_OF_INTEREST);
            } else {
                Slogf.i(TAG, "Audio dynamic routing not enabled, run in legacy mode");
                setupLegacyVolumeChangedListener();
            }

            mAudioManager.setSupportedSystemUsages(CarAudioContext.getSystemUsages());
        }

        restoreMasterMuteState();
    }

    private void setupPowerPolicyListener() {
        mCarAudioPowerListener = CarAudioPowerListener.newCarAudioPowerListener(this);
        mCarAudioPowerListener.startListeningForPolicyChanges();
    }

    private void restoreMasterMuteState() {
        if (mUseCarVolumeGroupMuting) {
            return;
        }
        // Restore master mute state if applicable
        if (mPersistMasterMuteState) {
            boolean storedMasterMute = mCarAudioSettings.isMasterMute();
            setMasterMute(storedMasterMute, 0);
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
                AudioManagerHelper.unregisterVolumeAndMuteReceiver(mContext,
                        mLegacyVolumeChangedHelper);
            }
            if (mCoreAudioVolumeGroupCallback != null) {
                mCoreAudioVolumeGroupCallback.release();
            }
            mCarVolumeCallbackHandler.release();
            mOccupantZoneService.unregisterCallback(mOccupantZoneCallback);

            if (mHalAudioFocus != null) {
                mHalAudioFocus.unregisterFocusListener();
            }

            if (mAudioControlWrapper != null) {
                mAudioControlWrapper.unlinkToDeath();
                mAudioControlWrapper = null;
            }

            if (mCarAudioPowerListener != null) {
                mCarAudioPowerListener.stopListeningForPolicyChanges();
            }
        }
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        synchronized (mImplLock) {
            writer.println("*CarAudioService*");
            writer.increaseIndent();

            writer.println("Configurations:");
            writer.increaseIndent();
            writer.printf("Run in legacy mode? %b\n", !mUseDynamicRouting);
            writer.printf("Rely on core audio for volume(%b)\n", mUseCoreAudioVolume);
            writer.printf("Rely on core audio for routing(%b)\n",  mUseCoreAudioRouting);
            writer.printf("Audio Patch APIs enabled? %b\n", areAudioPatchAPIsEnabled());
            writer.printf("Persist master mute state? %b\n", mPersistMasterMuteState);
            writer.printf("Use hal ducking signals %b\n", mUseHalDuckingSignals);
            writer.printf("Volume key event timeout ms: %d\n", mKeyEventTimeoutMs);
            if (mCarAudioConfigurationPath != null) {
                writer.printf("Car audio configuration path: %s\n", mCarAudioConfigurationPath);
            }
            writer.decreaseIndent();
            writer.println();

            writer.println("Current State:");
            writer.increaseIndent();
            writer.printf("Master muted? %b\n", isMasterMute(mAudioManager));
            if (mCarAudioPowerListener != null) {
                writer.printf("Audio enabled? %b\n", mCarAudioPowerListener.isAudioEnabled());
            }
            writer.decreaseIndent();
            writer.println();

            if (mUseDynamicRouting) {
                writer.printf("Volume Group Mute Enabled? %b\n", mUseCarVolumeGroupMuting);
                writer.println();
                mCarVolume.dump(writer);
                writer.println();
                mCarAudioContext.dump(writer);
                writer.println();
                for (int i = 0; i < mCarAudioZones.size(); i++) {
                    CarAudioZone zone = mCarAudioZones.valueAt(i);
                    zone.dump(writer);
                }

                writer.println();
                writer.println("UserId to Zone Mapping:");
                writer.increaseIndent();
                for (int index = 0; index < mAudioZoneIdToUserIdMapping.size(); index++) {
                    int audioZoneId = mAudioZoneIdToUserIdMapping.keyAt(index);
                    writer.printf("UserId %d mapped to zone %d\n",
                            mAudioZoneIdToUserIdMapping.get(audioZoneId),
                            audioZoneId);
                }
                writer.decreaseIndent();
                writer.println();
                writer.println("Audio Zone to Occupant Zone Mapping:");
                writer.increaseIndent();
                for (int index = 0; index < mAudioZoneIdToOccupantZoneIdMapping.size(); index++) {
                    int audioZoneId = mAudioZoneIdToOccupantZoneIdMapping.keyAt(index);
                    writer.printf("AudioZoneId %d mapped to OccupantZoneId %d\n", audioZoneId,
                            mAudioZoneIdToOccupantZoneIdMapping.get(audioZoneId));
                }
                writer.decreaseIndent();
                writer.println();
                writer.println("UID to Zone Mapping:");
                writer.increaseIndent();
                for (int callingId : mUidToZoneMap.keySet()) {
                    writer.printf("UID %d mapped to zone %d\n",
                            callingId,
                            mUidToZoneMap.get(callingId));
                }
                writer.decreaseIndent();

                writer.println();
                mFocusHandler.dump(writer);

                writer.println();
                getAudioControlWrapperLocked().dump(writer);

                if (mHalAudioFocus != null) {
                    writer.println();
                    mHalAudioFocus.dump(writer);
                } else {
                    writer.println("No HalAudioFocus instance\n");
                }
                if (mCarDucking != null) {
                    writer.println();
                    mCarDucking.dump(writer);
                }
                if (mCarVolumeGroupMuting != null) {
                    mCarVolumeGroupMuting.dump(writer);
                }
                if (mCarAudioPlaybackCallback != null) {
                    mCarAudioPlaybackCallback.dump(writer);
                }

                if (mCarAudioMirrorDeviceInfo != null) {
                    writer.println("Mirror device: ");
                    writer.increaseIndent();
                    mCarAudioMirrorDeviceInfo.dump(writer);
                    writer.decreaseIndent();
                }

            }
            writer.decreaseIndent();
        }
    }

    @Override
    public boolean isAudioFeatureEnabled(@CarAudioFeature int audioFeatureType) {
        switch (audioFeatureType) {
            case AUDIO_FEATURE_DYNAMIC_ROUTING:
                return mUseDynamicRouting;
            case AUDIO_FEATURE_VOLUME_GROUP_MUTING:
                return mUseCarVolumeGroupMuting;
            case AUDIO_FEATURE_OEM_AUDIO_SERVICE:
                return isAnyOemFeatureEnabled();
            case AUDIO_FEATURE_VOLUME_GROUP_EVENTS:
                return mUseCarVolumeGroupEvents;
            default:
                throw new IllegalArgumentException("Unknown Audio Feature type: "
                        + audioFeatureType);
        }
    }

    private boolean isAnyOemFeatureEnabled() {
        CarOemProxyService proxy = CarLocalServices.getService(CarOemProxyService.class);

        return proxy != null && proxy.isOemServiceEnabled()
                && (proxy.getCarOemAudioFocusService() != null
                || proxy.getCarOemAudioVolumeService() != null
                || proxy.getCarOemAudioDuckingService() != null);
    }

    /**
     * {@link android.car.media.CarAudioManager#setGroupVolume(int, int, int, int)}
     */
    @Override
    public void setGroupVolume(int zoneId, int groupId, int index, int flags) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        callbackGroupVolumeChange(zoneId, groupId, flags);
        // For legacy stream type based volume control
        boolean wasMute;
        if (!mUseDynamicRouting) {
            mAudioManager.setStreamVolume(
                    CarAudioDynamicRouting.STREAM_TYPES[groupId], index, flags);
            return;
        }
        synchronized (mImplLock) {
            CarVolumeGroup group = getCarVolumeGroupLocked(zoneId, groupId);
            wasMute = group.isMuted();
            group.setCurrentGainIndex(index);
        }
        if (wasMute) {
            handleMuteChanged(zoneId, groupId, flags);
        }
    }

    private void handleMuteChanged(int zoneId, int groupId, int flags) {
        callbackGroupMuteChanged(zoneId, groupId, flags);
        mCarVolumeGroupMuting.carMuteChanged();
    }

    private void callbackGroupVolumeChange(int zoneId, int groupId, int flags) {
        int callbackFlags = flags;
        if (mUseDynamicRouting && !isPlaybackOnVolumeGroupActive(zoneId, groupId)) {
            callbackFlags |= FLAG_PLAY_SOUND;
        }
        mCarVolumeCallbackHandler.onVolumeGroupChange(zoneId, groupId, callbackFlags);
        // TODO(b/261647905): implement logic to trigger volume event callbacks
    }

    private void callbackGroupMuteChanged(int zoneId, int groupId, int flags) {
        mCarVolumeCallbackHandler.onGroupMuteChange(zoneId, groupId, flags);
        // TODO(b/261647905): implement logic to trigger volume event callbacks
    }

    void setMasterMute(boolean mute, int flags) {
        AudioManagerHelper.setMasterMute(mAudioManager, mute, flags);

        // Master Mute only applies to primary zone
        callbackMasterMuteChange(PRIMARY_AUDIO_ZONE, flags);
    }

    void callbackMasterMuteChange(int zoneId, int flags) {
        mCarVolumeCallbackHandler.onMasterMuteChanged(zoneId, flags);

        // Persists master mute state if applicable
        if (mPersistMasterMuteState) {
            mCarAudioSettings.storeMasterMute(isMasterMute(mAudioManager));
        }
    }

    /**
     * {@link android.car.media.CarAudioManager#getGroupMaxVolume(int, int)}
     */
    @Override
    public int getGroupMaxVolume(int zoneId, int groupId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        if (!mUseDynamicRouting) {
            return mAudioManager.getStreamMaxVolume(
                    CarAudioDynamicRouting.STREAM_TYPES[groupId]);
        }

        synchronized (mImplLock) {
            CarVolumeGroup group = getCarVolumeGroupLocked(zoneId, groupId);
            return group.getMaxGainIndex();
        }
    }

    /**
     * {@link android.car.media.CarAudioManager#getGroupMinVolume(int, int)}
     */
    @Override
    public int getGroupMinVolume(int zoneId, int groupId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        if (!mUseDynamicRouting) {
            return mAudioManager.getStreamMinVolume(
                    CarAudioDynamicRouting.STREAM_TYPES[groupId]);
        }

        synchronized (mImplLock) {
            CarVolumeGroup group = getCarVolumeGroupLocked(zoneId, groupId);
            return group.getMinGainIndex();
        }
    }

    /**
     * {@link android.car.media.CarAudioManager#getGroupVolume(int, int)}
     */
    @Override
    public int getGroupVolume(int zoneId, int groupId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        // For legacy stream type based volume control
        if (!mUseDynamicRouting) {
            return mAudioManager.getStreamVolume(
                    CarAudioDynamicRouting.STREAM_TYPES[groupId]);
        }

        synchronized (mImplLock) {
            CarVolumeGroup group = getCarVolumeGroupLocked(zoneId, groupId);
            return group.getCurrentGainIndex();
        }
    }

    /**
     * {@link android.car.media.CarAudioManager#setPrimaryZoneMediaAudioRequestCallback()}
     */
    @Override
    public boolean registerPrimaryZoneMediaAudioRequestCallback(
            IPrimaryZoneMediaAudioRequestCallback callback) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        return mMediaRequestHandler.registerPrimaryZoneMediaAudioRequestCallback(callback);
    }

    /**
     * {@link android.car.media.CarAudioManager#clearPrimaryZoneMediaAudioRequestCallback()}
     */
    @Override
    public void unregisterPrimaryZoneMediaAudioRequestCallback(
            IPrimaryZoneMediaAudioRequestCallback callback) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        List<Long> ownedRequests = mMediaRequestHandler.getRequestsOwnedByApprover(callback);
        for (int index = 0; index < ownedRequests.size(); index++) {
            long requestId = ownedRequests.get(index);
            handleUnassignAudioFromUserIdOnPrimaryAudioZone(requestId);
        }
        if (!mMediaRequestHandler.unregisterPrimaryZoneMediaAudioRequestCallback(callback)) {
            Slogf.e(TAG,
                    "unregisterPrimaryZoneMediaAudioRequestCallback could not remove callback");
        }
    }

    /**
     * {@link android.car.media.CarAudioManager#requestMediaAudioOnPrimaryZone(
     *      MediaAudioRequest)}
     */
    @Override
    public long requestMediaAudioOnPrimaryZone(IMediaAudioRequestStatusCallback callback,
            CarOccupantZoneManager.OccupantZoneInfo info) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        Objects.requireNonNull(callback, "Media audio request callback can not be null");
        Objects.requireNonNull(info, "Occupant zone info can not be null");

        synchronized (mImplLock) {
            int audioZoneId = mOccupantZoneService.getAudioZoneIdForOccupant(info.zoneId);
            if (audioZoneId == PRIMARY_AUDIO_ZONE) {
                throw new IllegalArgumentException("Occupant " + info
                        + " already owns the primary audio zone");
            }

            int index = mAudioZoneIdToUserIdMapping.indexOfKey(audioZoneId);
            if (index < 0) {
                Slogf.w(TAG, "Audio zone id %d is not mapped to any user id", audioZoneId);
                return INVALID_REQUEST_ID;
            }
        }

        return mMediaRequestHandler.requestMediaAudioOnPrimaryZone(callback, info);
    }

    /**
     * {@link android.car.media.CarAudioManager#allowMediaAudioOnPrimaryZone(
     *  android.car.media.CarAudioManager.MediaRequestToken, long, boolean)}
     */
    @Override
    public boolean allowMediaAudioOnPrimaryZone(IBinder token, long requestId, boolean allow) {
        Objects.requireNonNull(token, "Media request token must not be null");
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();

        boolean canApprove = mMediaRequestHandler.isAudioMediaCallbackRegistered(token);
        if (!allow || !canApprove) {
            if (!canApprove) {
                Slogf.w(TAG, "allowMediaAudioOnPrimaryZone Request %d can not be approved by "
                                + "token %s",
                        requestId, token);
            }
            return mMediaRequestHandler.rejectMediaAudioRequest(requestId);
        }

        CarOccupantZoneManager.OccupantZoneInfo info =
                mMediaRequestHandler.getOccupantForRequest(requestId);

        if (info == null) {
            Slogf.w(TAG, "allowMediaAudioOnPrimaryZone Request %d is no longer present",
                    requestId);
            return false;
        }

        synchronized (mImplLock) {
            int userId = mOccupantZoneService.getUserForOccupant(info.zoneId);
            int audioZoneId = mOccupantZoneService.getAudioZoneIdForOccupant(info.zoneId);
            return handleAssignAudioFromUserIdToPrimaryAudioZoneLocked(token,
                    userId, audioZoneId, requestId);
        }
    }

    /**
     * {@link android.car.media.CarAudioManager#isMediaAudioAllowedInPrimaryZone(
     *      CarOccupantZoneManager.OccupantZoneInfo)}
     */
    @Override
    public boolean isMediaAudioAllowedInPrimaryZone(CarOccupantZoneManager.OccupantZoneInfo info) {
        Objects.requireNonNull(info, "Occupant zone info can not be null");
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();

        return mMediaRequestHandler.isMediaAudioAllowedInPrimaryZone(info);
    }

    /**
     * {@link android.car.media.CarAudioManager#resetMediaAudioOnPrimaryZone(
     *      CarOccupantZoneManager.OccupantZoneInfo)}
     */
    @Override
    public boolean resetMediaAudioOnPrimaryZone(CarOccupantZoneManager.OccupantZoneInfo info) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();

        long requestId = mMediaRequestHandler.getRequestIdForOccupant(info);
        if (requestId == INVALID_REQUEST_ID) {
            Slogf.w(TAG, "resetMediaAudioOnPrimaryZone no request id for occupant %s", info);
            return false;
        }
        return handleUnassignAudioFromUserIdOnPrimaryAudioZone(requestId);
    }

    /**
     * {@link android.car.media.CarAudioManager#cancelMediaAudioOnPrimaryZone(long)}
     */
    @Override
    public boolean cancelMediaAudioOnPrimaryZone(long requestId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();

        CarOccupantZoneManager.OccupantZoneInfo info =
                mMediaRequestHandler.getOccupantForRequest(requestId);
        if (info == null) {
            Slogf.w(TAG, "cancelMediaAudioOnPrimaryZone no occupant for request %d",
                    requestId);
            return false;
        }

        if (!mMediaRequestHandler.isMediaAudioAllowedInPrimaryZone(info)) {
            return mMediaRequestHandler.cancelMediaAudioOnPrimaryZone(requestId);
        }

        return handleUnassignAudioFromUserIdOnPrimaryAudioZone(requestId);
    }

    /**
     * {@link CarAudioManager#setAudioZoneMirrorStatusCallback(Executor,
     *      AudioZonesMirrorStatusCallback)}
     */
    @Override
    public boolean registerAudioZonesMirrorStatusCallback(
            IAudioZonesMirrorStatusCallback callback) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        requireAudioMirroring();

        return mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(callback);
    }

    /**
     * {@link CarAudioManager#clearAudioZonesMirrorStatusCallback()}
     */
    @Override
    public void unregisterAudioZonesMirrorStatusCallback(IAudioZonesMirrorStatusCallback callback) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        requireAudioMirroring();

        if (!mCarAudioMirrorRequestHandler.unregisterAudioZonesMirrorStatusCallback(callback)) {
            Slogf.w(TAG, "Could not unregister audio zones mirror status callback ,"
                    + "callback could have died before unregister was called.");
        }
    }

    /**
     * {@link CarAudioManager#enableMirrorForAudioZones(List)}
     */
    @Override
    public void enableMirrorForAudioZones(int[] audioZones) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        requireAudioMirroring();
        verifyCanMirrorToAudioZones(audioZones);

        mHandler.post(() -> handleEnableAudioMirrorForZones(audioZones));
    }

    /**
     * {@link CarAudioManager#disableAudioMirrorForZone(int)}
     */
    @Override
    public void disableAudioMirrorForZone(int zoneId) {
        // TODO (b/263211884): Implement focus and audio routing disable logic
    }

    /**
     * {@link CarAudioManager#getMirrorAudioZonesForAudioZone(int)}
     */
    @Override
    public int[] getMirrorAudioZonesForAudioZone(int zoneId) {
        // TODO (b/263211884): Implement query logic
        return new int[0];
    }

    @GuardedBy("mImplLock")
    private CarVolumeGroup getCarVolumeGroupLocked(int zoneId, int groupId) {
        return getCarAudioZoneLocked(zoneId).getCurrentVolumeGroup(groupId);
    }

    @GuardedBy("mImplLock")
    @Nullable
    private CarVolumeGroup getCarVolumeGroupLocked(int zoneId, String groupName) {
        return getCarAudioZoneLocked(zoneId).getCurrentVolumeGroup(groupName);
    }

    private void verifyCanMirrorToAudioZones(int[] audioZones) {
        Objects.requireNonNull(audioZones, "Mirror audio zones can not be null");
        Preconditions.checkArgument(audioZones.length > 1,
                "Mirror audio zones needs to have at least 2 zones");
        ArraySet<Integer> zones = CarServiceUtils.toIntArraySet(audioZones);

        if (zones.size() != audioZones.length) {
            throw new IllegalArgumentException(
                    "Audio zones in mirror configuration must be unique "
                            + Arrays.toString(audioZones));
        }

        if (zones.contains(PRIMARY_AUDIO_ZONE)) {
            throw new IllegalArgumentException(
                    "Audio mirroring not allowed for primary audio zone");
        }

        int[] prevConfig = null;
        Set<Integer> configSet = null;
        for (int c = 0; c < audioZones.length; c++) {
            int zoneId = audioZones[c];

            synchronized (mImplLock) {
                checkAudioZoneIdLocked(zoneId);
            }

            int userId = getUserIdForZone(zoneId);
            if (userId == UserManagerHelper.USER_NULL) {
                throw new IllegalStateException(
                        "Audio zone must have an active user to allow mirroring");
            }

            CarOccupantZoneManager.OccupantZoneInfo info = mOccupantZoneService
                    .getOccupantForAudioZoneId(zoneId);

            if (mMediaRequestHandler.isMediaAudioAllowedInPrimaryZone(info)) {
                throw new IllegalStateException(
                        "Occupant " + info + " in audio zone " + zoneId
                                + " is currently sharing to primary zone, "
                                + "undo audio sharing in primary zone before setting up mirroring");
            }

            int[] config = mCarAudioMirrorRequestHandler.getMirrorAudioZonesForAudioZone(zoneId);

            if (config == null) {
                continue;
            }

            if (prevConfig == null) {
                prevConfig = config;
                configSet = CarServiceUtils.toIntArraySet(config);
            } else if (!Arrays.equals(config, prevConfig)) {
                throw new IllegalStateException(
                        "Can not mirror zones among different audio mirroring configurations");
            }

            if (!zones.containsAll(configSet)) {
                throw new IllegalArgumentException(
                        "Can not enable zones already in audio mirroring configuration,"
                                + " current config " + Arrays.toString(audioZones)
                                + " requested config " + Arrays.toString(config));
            }
        }
    }

    private void handleEnableAudioMirrorForZones(int[] audioZoneIds) {
        boolean alreadySet = true;
        for (int index = 0; index < audioZoneIds.length; index++) {
            int audioZoneId = audioZoneIds[index];

            int userId = getUserIdForZone(audioZoneIds[index]);
            if (userId == UserManagerHelper.USER_NULL) {
                Slogf.w(TAG, "handleEnableAudioMirrorForZones failed,"
                        + " audio mirror not allowed for unassigned audio zone %d", audioZoneId);
                mCarAudioMirrorRequestHandler.rejectMirrorForZones(audioZoneIds);
                return;
            }

            int[] config = mCarAudioMirrorRequestHandler.getMirrorAudioZonesForAudioZone(
                    audioZoneId);

            // Check it is same configuration as requested, order is preserved as it is assumed
            // that the first zone id is the source and other zones are the receiver of the audio
            // mirror
            if (!Arrays.equals(audioZoneIds, config)) {
                alreadySet = false;
            }

            CarOccupantZoneManager.OccupantZoneInfo info = mOccupantZoneService
                    .getOccupantForAudioZoneId(audioZoneId);

            if (mMediaRequestHandler.isMediaAudioAllowedInPrimaryZone(info)) {
                Slogf.w(TAG, "handleEnableAudioMirrorForZones failed,"
                        + " audio mirror not allowed for audio zone %d sharing to primary zone",
                        audioZoneId);
                mCarAudioMirrorRequestHandler.rejectMirrorForZones(audioZoneIds);
                return;
            }
        }

        if (alreadySet) {
            Slogf.i(TAG, "handleEnableAudioMirrorForZones audio mirror already set for zones %s",
                    Arrays.toString(audioZoneIds));
            mCarAudioMirrorRequestHandler.enableMirrorForZones(audioZoneIds);
            return;
        }

        TimingsTraceLog t = new TimingsTraceLog(TAG, TraceHelper.TRACE_TAG_CAR_SERVICE);
        t.traceBegin("audio-mirror-" + Arrays.toString(audioZoneIds));
        synchronized (mImplLock) {
            List<AudioFocusStackRequest> mediaFocusStacks = new ArrayList<>();
            t.traceBegin("audio-mirror-focus-loss-" + Arrays.toString(audioZoneIds));
            for (int index = 0; index < audioZoneIds.length; index++) {
                int zoneId = audioZoneIds[index];
                int userId = getUserIdForZone(zoneId);
                t.traceBegin("audio-mirror-focus-loss-zone-" + zoneId);
                mediaFocusStacks.add(new AudioFocusStackRequest(mFocusHandler
                        .transientlyLoseMediaAudioFocusForUser(userId, zoneId), zoneId));
                t.traceEnd();
            }
            t.traceEnd();

            t.traceBegin("audio-mirror-routing-" + Arrays.toString(audioZoneIds));
            if (!setupAudioRoutingForUserInMirrorDeviceLocked(audioZoneIds)) {
                for (int index = 0; index < mediaFocusStacks.size(); index++) {
                    AudioFocusStackRequest request = mediaFocusStacks.get(index);
                    mFocusHandler.regainMediaAudioFocusInZone(request.mStack,
                            request.mOriginalZoneId);
                }
                mCarAudioMirrorRequestHandler.rejectMirrorForZones(audioZoneIds);
                return;
            }
            t.traceEnd();

            // TODO(b/268383539): Implement multi zone focus for mirror
            // Currently only selecting the source zone as focus manager
            t.traceBegin("audio-mirror-focus-gain-" + Arrays.toString(audioZoneIds));
            int zoneId = audioZoneIds[0];
            for (int index = 0; index < mediaFocusStacks.size(); index++) {
                AudioFocusStackRequest request = mediaFocusStacks.get(index);
                t.traceBegin("audio-mirror-focus-gain-" + index + "-zone-" + zoneId);
                mFocusHandler.regainMediaAudioFocusInZone(request.mStack, zoneId);
                t.traceEnd();
            }
            t.traceEnd();
        }
        t.traceEnd();

        mCarAudioMirrorRequestHandler.enableMirrorForZones(audioZoneIds);
    }

    @GuardedBy("mImplLock")
    private boolean setupAudioRoutingForUserInMirrorDeviceLocked(int[] audioZones) {
        int index;
        boolean succeeded = true;
        for (index = 0; index < audioZones.length; index++) {
            int zoneId = audioZones[index];
            int userId = getUserIdForZone(zoneId);
            CarAudioZone audioZone = getCarAudioZone(zoneId);
            boolean enabled = setupMirrorDeviceForUserIdLocked(userId, audioZone,
                    mCarAudioMirrorDeviceInfo.getAudioDeviceInfo());
            if (!enabled) {
                succeeded = false;
                Slogf.w(TAG, "setupAudioRoutingForUserInMirrorDeviceLocked failed for zone "
                        + "id %d and user id %d", zoneId, userId);
                break;
            }
        }

        if (succeeded) {
            return true;
        }

        // Attempt to reset user id routing for other mirror zones
        for (int count = 0; count < index; count++) {
            int zoneId = audioZones[count];
            int userId = getUserIdForZone(zoneId);
            CarAudioZone audioZone = getCarAudioZone(zoneId);
            setUserIdDeviceAffinitiesLocked(audioZone, userId, zoneId);
        }

        return false;
    }

    private void setupLegacyVolumeChangedListener() {
        AudioManagerHelper.registerVolumeAndMuteReceiver(mContext, mLegacyVolumeChangedHelper);
    }

    private List<CarAudioDeviceInfo> generateCarAudioDeviceInfos() {
        AudioDeviceInfo[] deviceInfos = mAudioManager.getDevices(
                AudioManager.GET_DEVICES_OUTPUTS);

        List<CarAudioDeviceInfo> infos = new ArrayList<>();

        for (int index = 0; index < deviceInfos.length; index++) {
            if (deviceInfos[index].getType() == AudioDeviceInfo.TYPE_BUS) {
                infos.add(new CarAudioDeviceInfo(mAudioManager, deviceInfos[index]));
            }
        }
        return infos;
    }

    private AudioDeviceInfo[] getAllInputDevices() {
        return mAudioManager.getDevices(
                AudioManager.GET_DEVICES_INPUTS);
    }

    @GuardedBy("mImplLock")
    private SparseArray<CarAudioZone> loadCarAudioConfigurationLocked(
            List<CarAudioDeviceInfo> carAudioDeviceInfos, AudioDeviceInfo[] inputDevices) {

        try (InputStream fileStream = new FileInputStream(mCarAudioConfigurationPath);
                 InputStream inputStream = new BufferedInputStream(fileStream)) {
            CarAudioZonesHelper zonesHelper = new CarAudioZonesHelper(mAudioManager,
                    mCarAudioSettings, inputStream, carAudioDeviceInfos, inputDevices,
                    mUseCarVolumeGroupMuting, mUseCoreAudioVolume, mUseCoreAudioRouting);
            mAudioZoneIdToOccupantZoneIdMapping =
                    zonesHelper.getCarAudioZoneIdToOccupantZoneIdMapping();
            SparseArray<CarAudioZone> zones = zonesHelper.loadAudioZones();
            mCarAudioMirrorDeviceInfo = zonesHelper.getMirrorDeviceInfo();
            if (mCarAudioMirrorDeviceInfo != null) {
                mCarAudioMirrorRequestHandler.setMirrorDeviceAddress(
                        mCarAudioMirrorDeviceInfo.getAddress());
            }
            mCarAudioContext = zonesHelper.getCarAudioContext();
            return zones;
        } catch (IOException | XmlPullParserException e) {
            throw new RuntimeException("Failed to parse audio zone configuration", e);
        }
    }

    @GuardedBy("mImplLock")
    @ExcludeFromCodeCoverageGeneratedReport(reason = DEPRECATED_CODE)
    private SparseArray<CarAudioZone> loadVolumeGroupConfigurationWithAudioControlLocked(
            List<CarAudioDeviceInfo> carAudioDeviceInfos, AudioDeviceInfo[] inputDevices) {
        AudioControlWrapper audioControlWrapper = getAudioControlWrapperLocked();
        if (!(audioControlWrapper instanceof AudioControlWrapperV1)) {
            throw new IllegalStateException(
                    "Updated version of IAudioControl no longer supports CarAudioZonesHelperLegacy."
                    + " Please provide car_audio_configuration.xml.");
        }
        mCarAudioContext = new CarAudioContext(CarAudioContext.getAllContextsInfo(),
                mUseCoreAudioVolume);
        CarAudioZonesHelperLegacy legacyHelper = new CarAudioZonesHelperLegacy(mContext,
                mCarAudioContext, R.xml.car_volume_groups, carAudioDeviceInfos,
                (AudioControlWrapperV1) audioControlWrapper,
                mCarAudioSettings, inputDevices);
        return legacyHelper.loadAudioZones();
    }

    @GuardedBy("mImplLock")
    private void loadCarAudioZonesLocked() {
        List<CarAudioDeviceInfo> carAudioDeviceInfos = generateCarAudioDeviceInfos();
        AudioDeviceInfo[] inputDevices = getAllInputDevices();

        if (mCarAudioConfigurationPath != null) {
            mCarAudioZones = loadCarAudioConfigurationLocked(carAudioDeviceInfos, inputDevices);
        } else {
            mCarAudioZones =
                    loadVolumeGroupConfigurationWithAudioControlLocked(carAudioDeviceInfos,
                            inputDevices);
        }

        CarAudioZonesValidator.validate(mCarAudioZones, mUseCoreAudioRouting);
    }

    @GuardedBy("mImplLock")
    private void setupDynamicRoutingLocked() {
        AudioPolicy.Builder builder = new AudioPolicy.Builder(mContext);
        builder.setLooper(Looper.getMainLooper());

        loadCarAudioZonesLocked();

        mCarVolume = new CarVolume(mCarAudioContext, mClock,
                mAudioVolumeAdjustmentContextsVersion, mKeyEventTimeoutMs);

        for (int i = 0; i < mCarAudioZones.size(); i++) {
            CarAudioZone zone = mCarAudioZones.valueAt(i);
            // Ensure HAL gets our initial value
            zone.init();
            Slogf.v(TAG, "Processed audio zone: %s", zone);
        }

        // Mirror policy has to be set before general audio policy
        setupMirrorDevicePolicyLocked(builder);
        CarAudioDynamicRouting.setupAudioDynamicRouting(builder, mCarAudioZones, mCarAudioContext);

        AudioPolicyVolumeCallbackInternal volumeCallbackInternal =
                new AudioPolicyVolumeCallbackInternal() {
            @Override
            public void onMuteChange(boolean mute, int zoneId, int groupId, int flags) {
                if (mUseCarVolumeGroupMuting) {
                    setVolumeGroupMute(zoneId, groupId, mute, flags);
                    return;
                }
                setMasterMute(mute, flags);
            }

            @Override
            public void onGroupVolumeChange(int zoneId, int groupId, int volumeValue, int flags) {
                setGroupVolume(zoneId, groupId, volumeValue, flags);
            }
        };

        mCarAudioPolicyVolumeCallback = new CarAudioPolicyVolumeCallback(volumeCallbackInternal,
                mAudioManager, new CarVolumeInfoWrapper(this), mUseCarVolumeGroupMuting);

        // Attach the {@link AudioPolicyVolumeCallback}
        CarAudioPolicyVolumeCallback.addVolumeCallbackToPolicy(builder,
                mCarAudioPolicyVolumeCallback);

        AudioControlWrapper audioControlWrapper = getAudioControlWrapperLocked();
        if (mUseHalDuckingSignals) {
            if (audioControlWrapper.supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_DUCKING)) {
                mCarDucking = new CarDucking(mCarAudioZones, audioControlWrapper);
            }
        }

        if (mUseCarVolumeGroupMuting) {
            mCarVolumeGroupMuting = new CarVolumeGroupMuting(mCarAudioZones, audioControlWrapper);
        }

        // Configure our AudioPolicy to handle focus events.
        // This gives us the ability to decide which audio focus requests to accept and bypasses
        // the framework ducking logic.
        mFocusHandler = CarZonesAudioFocus.createCarZonesAudioFocus(mAudioManager,
                mContext.getPackageManager(),
                mCarAudioZones,
                mCarAudioSettings,
                mCarDucking,
                new CarVolumeInfoWrapper(this));
        builder.setAudioPolicyFocusListener(mFocusHandler);
        builder.setIsAudioFocusPolicy(true);

        mAudioPolicy = builder.build();

        // Connect the AudioPolicy and the focus listener
        mFocusHandler.setOwningPolicy(this, mAudioPolicy);

        int r = mAudioManager.registerAudioPolicy(mAudioPolicy);
        if (r != AudioManager.SUCCESS) {
            throw new RuntimeException("registerAudioPolicy failed " + r);
        }

        setupOccupantZoneInfoLocked();

        if (mUseCoreAudioVolume) {
            mCoreAudioVolumeGroupCallback = new CoreAudioVolumeGroupCallback(
                    new CarVolumeInfoWrapper(this), mAudioManager);
            mCoreAudioVolumeGroupCallback.init(mContext.getMainExecutor());
        }
    }

    @GuardedBy("mImplLock")
    private void setupMirrorDevicePolicyLocked(AudioPolicy.Builder mirrorPolicyBuilder) {
        if (mCarAudioMirrorDeviceInfo == null) {
            return;
        }

        CarAudioDynamicRouting.setupAudioDynamicRoutingForMirrorDevice(mirrorPolicyBuilder,
                mCarAudioMirrorDeviceInfo);
    }

    @GuardedBy("mImplLock")
    private void setupAudioConfigurationCallbackLocked() {
        mCarAudioPlaybackCallback =
                new CarAudioPlaybackCallback(mCarAudioZones, mClock, mKeyEventTimeoutMs);
        mAudioManager.registerAudioPlaybackCallback(mCarAudioPlaybackCallback, null);
    }

    @GuardedBy("mImplLock")
    private void setupOccupantZoneInfoLocked() {
        CarOccupantZoneService occupantZoneService;
        SparseIntArray audioZoneIdToOccupantZoneMapping;
        audioZoneIdToOccupantZoneMapping = mAudioZoneIdToOccupantZoneIdMapping;
        occupantZoneService = mOccupantZoneService;
        occupantZoneService.setAudioZoneIdsForOccupantZoneIds(audioZoneIdToOccupantZoneMapping);
        occupantZoneService.registerCallback(mOccupantZoneCallback);
    }

    @GuardedBy("mImplLock")
    private void setupHalAudioFocusListenerLocked() {
        AudioControlWrapper audioControlWrapper = getAudioControlWrapperLocked();
        if (!audioControlWrapper.supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_FOCUS)) {
            Slogf.d(TAG, "HalAudioFocus is not supported on this device");
            return;
        }

        mHalAudioFocus = new HalAudioFocus(mAudioManager, mAudioControlWrapper, getAudioZoneIds());
        mHalAudioFocus.registerFocusListener();
    }

    @GuardedBy("mImplLock")
    private void setupHalAudioGainCallbackLocked() {
        AudioControlWrapper audioControlWrapper = getAudioControlWrapperLocked();
        if (!audioControlWrapper.supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_GAIN_CALLBACK)) {
            Slogf.d(CarLog.TAG_AUDIO, "HalAudioGainCallback is not supported on this device");
            return;
        }
        mCarAudioGainMonitor = new CarAudioGainMonitor(mAudioControlWrapper, mCarAudioZones);
        mCarAudioGainMonitor.registerAudioGainListener(mHalAudioGainCallback);
    }

    /**
     * Read from {@link #AUDIO_CONFIGURATION_PATHS} respectively.
     * @return File path of the first hit in {@link #AUDIO_CONFIGURATION_PATHS}
     */
    @Nullable
    private static String getAudioConfigurationPath() {
        for (String path : AUDIO_CONFIGURATION_PATHS) {
            File configuration = new File(path);
            if (configuration.exists()) {
                return path;
            }
        }
        return null;
    }

    @Override
    public void setFadeTowardFront(float value) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
            requireValidFadeRange(value);
            getAudioControlWrapperLocked().setFadeTowardFront(value);
        }
    }

    @Override
    public void setBalanceTowardRight(float value) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
            requireValidBalanceRange(value);
            getAudioControlWrapperLocked().setBalanceTowardRight(value);
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
                Slogf.w(TAG, "getExternalSources, no input devices found");
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
                            Slogf.w(TAG, "Discarded device with empty address, type=%d",
                                    info.getType());
                        } else {
                            sourceAddresses.add(address);
                        }
                }
            }

            return sourceAddresses.toArray(new String[0]);
        }
    }

    @Override
    public CarAudioPatchHandle createAudioPatch(String sourceAddress,
            @AttributeUsage int usage, int gainInMillibels) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        enforceCanUseAudioPatchAPI();
        synchronized (mImplLock) {
            return createAudioPatchLocked(sourceAddress, usage, gainInMillibels);
        }
    }

    @Override
    public void releaseAudioPatch(CarAudioPatchHandle carPatch) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        enforceCanUseAudioPatchAPI();
        synchronized (mImplLock) {
            releaseAudioPatchLocked(carPatch);
        }
    }

    private void enforceCanUseAudioPatchAPI() {
        if (!areAudioPatchAPIsEnabled()) {
            throw new IllegalStateException("Audio Patch APIs not enabled, see "
                    + PROPERTY_RO_ENABLE_AUDIO_PATCH);
        }
    }

    private boolean areAudioPatchAPIsEnabled() {
        return SystemProperties.getBoolean(PROPERTY_RO_ENABLE_AUDIO_PATCH, /* default= */ false);
    }

    @GuardedBy("mImplLock")
    private CarAudioPatchHandle createAudioPatchLocked(String sourceAddress,
            @AttributeUsage int usage, int gainInMillibels) {
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
        Objects.requireNonNull(sourcePortInfo,
                "Specified source is not available: " + sourceAddress);

        AudioAttributes audioAttributes = CarAudioContext.getAudioAttributeFromUsage(usage);

        AudioPatchInfo audioPatchInfo = AudioManagerHelper.createAudioPatch(sourcePortInfo,
                getOutputDeviceForAudioAttributeLocked(PRIMARY_AUDIO_ZONE, audioAttributes),
                gainInMillibels);

        Slogf.d(TAG, "Audio patch created: %s", audioPatchInfo);

        // Ensure the initial volume on output device port
        int groupId = getVolumeGroupIdForAudioAttributeLocked(PRIMARY_AUDIO_ZONE, audioAttributes);
        setGroupVolume(PRIMARY_AUDIO_ZONE, groupId,
                getGroupVolume(PRIMARY_AUDIO_ZONE, groupId), 0);

        return new CarAudioPatchHandle(audioPatchInfo.getHandleId(),
                audioPatchInfo.getSourceAddress(), audioPatchInfo.getSinkAddress());
    }

    @GuardedBy("mImplLock")
    private void releaseAudioPatchLocked(CarAudioPatchHandle carPatch) {
        Objects.requireNonNull(carPatch);

        if (AudioManagerHelper.releaseAudioPatch(mAudioManager, getAudioPatchInfo(carPatch))) {
            Slogf.d(TAG, "releaseAudioPatch %s successfully", carPatch);
        }
        // If we didn't find a match, then something went awry, but it's probably not fatal...
        Slogf.e(TAG, "releaseAudioPatch found no match for %s", carPatch);
    }

    private static AudioPatchInfo getAudioPatchInfo(CarAudioPatchHandle carPatch) {
        return new AudioPatchInfo(carPatch.getSourceAddress(),
                carPatch.getSinkAddress(),
                carPatch.getHandleId());
    }

    @Override
    public int getVolumeGroupCount(int zoneId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        if (!mUseDynamicRouting) {
            return CarAudioDynamicRouting.STREAM_TYPES.length;
        }

        synchronized (mImplLock) {
            return getCarAudioZoneLocked(zoneId).getCurrentVolumeGroupCount();
        }
    }

    @Override
    public int getVolumeGroupIdForUsage(int zoneId, @AttributeUsage int usage) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        if (!CarAudioContext.isValidAudioAttributeUsage(usage)) {
            return INVALID_VOLUME_GROUP_ID;
        }

        synchronized (mImplLock) {
            return getVolumeGroupIdForAudioAttributeLocked(zoneId,
                    CarAudioContext.getAudioAttributeFromUsage(usage));
        }
    }

    @Override
    public CarVolumeGroupInfo getVolumeGroupInfo(int zoneId, int groupId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        if (!mUseDynamicRouting) {
            return null;
        }
        synchronized (mImplLock) {
            return getCarVolumeGroupLocked(zoneId, groupId).getCarVolumeGroupInfo();
        }
    }

    @Override
    public List<CarVolumeGroupInfo> getVolumeGroupInfosForZone(int zoneId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        if (!mUseDynamicRouting) {
            return Collections.EMPTY_LIST;
        }
        synchronized (mImplLock) {
            return getCarAudioZoneLocked(zoneId).getCurrentVolumeGroupInfos();
        }
    }

    @Override
    public List<AudioAttributes> getAudioAttributesForVolumeGroup(CarVolumeGroupInfo groupInfo) {
        Objects.requireNonNull(groupInfo, "Car volume group info can not be null");
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        if (!mUseDynamicRouting) {
            return Collections.EMPTY_LIST;
        }

        synchronized (mImplLock) {
            return getCarAudioZoneLocked(groupInfo.getZoneId())
                    .getCurrentVolumeGroup(groupInfo.getId()).getAudioAttributes();
        }
    }

    @GuardedBy("mImplLock")
    private int getVolumeGroupIdForAudioAttributeLocked(int zoneId,
            AudioAttributes audioAttributes) {
        if (!mUseDynamicRouting) {
            return getStreamTypeFromAudioAttribute(audioAttributes);
        }

        @AudioContext int audioContext =
                mCarAudioContext.getContextForAudioAttribute(audioAttributes);
        return getVolumeGroupIdForAudioContextLocked(zoneId, audioContext);
    }

    private static int getStreamTypeFromAudioAttribute(AudioAttributes audioAttributes) {
        int usage = audioAttributes.getSystemUsage();
        for (int i = 0; i < CarAudioDynamicRouting.STREAM_TYPE_USAGES.length; i++) {
            if (usage == CarAudioDynamicRouting.STREAM_TYPE_USAGES[i]) {
                return i;
            }
        }

        return INVALID_VOLUME_GROUP_ID;
    }

    @GuardedBy("mImplLock")
    private int getVolumeGroupIdForAudioContextLocked(int zoneId, @AudioContext int audioContext) {
        CarVolumeGroup[] groups = getCarAudioZoneLocked(zoneId).getCurrentVolumeGroups();
        for (int i = 0; i < groups.length; i++) {
            int[] groupAudioContexts = groups[i].getContexts();
            for (int groupAudioContext : groupAudioContexts) {
                if (audioContext == groupAudioContext) {
                    return i;
                }
            }
        }
        return INVALID_VOLUME_GROUP_ID;
    }

    @Override
    public @NonNull int[] getUsagesForVolumeGroupId(int zoneId, int groupId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

        if (!mUseDynamicRouting) {
            return new int[] { CarAudioDynamicRouting.STREAM_TYPE_USAGES[groupId] };
        }
        synchronized (mImplLock) {
            CarVolumeGroup group = getCarVolumeGroupLocked(zoneId, groupId);
            int[] contexts = group.getContexts();
            List<Integer> usages = new ArrayList<>();
            for (int index = 0; index < contexts.length; index++) {
                AudioAttributes[] attributesForContext =
                        mCarAudioContext.getAudioAttributesForContext(contexts[index]);
                for (int counter = 0; counter < attributesForContext.length; counter++) {
                    usages.add(attributesForContext[counter].getSystemUsage());
                }
            }

            int[] usagesArray = CarServiceUtils.toIntArray(usages);

            return usagesArray;
        }
    }

    @Override
    public boolean isPlaybackOnVolumeGroupActive(int zoneId, int groupId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        requireDynamicRouting();
        Preconditions.checkArgument(isAudioZoneIdValid(zoneId),
                "Invalid audio zone id %d", zoneId);

        CarVolume carVolume;
        synchronized (mImplLock) {
            carVolume = mCarVolume;
        }
        return carVolume.isAnyContextActive(getContextsForVolumeGroupId(zoneId, groupId),
                getActiveAttributesFromPlaybackConfigurations(zoneId),
                getCallStateForZone(zoneId), getActiveHalAudioAttributesForZone(zoneId));
    }

    /**
     *
     * returns the current call state ({@code CALL_STATE_OFFHOOK}, {@code CALL_STATE_RINGING},
     * {@code CALL_STATE_IDLE}) from the telephony manager.
     */
    int getCallStateForZone(int zoneId) {
        synchronized (mImplLock) {
            // Only driver can use telephony stack
            if (getUserIdForZoneLocked(zoneId) == mOccupantZoneService.getDriverUserId()) {
                return mTelephonyManager.getCallState();
            }
        }
        return TelephonyManager.CALL_STATE_IDLE;
    }

    private List<AudioAttributes> getActiveAttributesFromPlaybackConfigurations(int zoneId) {
        return getCarAudioZone(zoneId)
                .findActiveAudioAttributesFromPlaybackConfigurations(mAudioManager
                        .getActivePlaybackConfigurations());
    }


    private @NonNull @AudioContext int[] getContextsForVolumeGroupId(int zoneId, int groupId) {
        synchronized (mImplLock) {
            CarVolumeGroup group = getCarVolumeGroupLocked(zoneId, groupId);
            return group.getContexts();
        }
    }

    /**
     * Gets the ids of all available audio zones
     *
     * @return Array of available audio zones ids
     */
    @Override
    public @NonNull int[] getAudioZoneIds() {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        synchronized (mImplLock) {
            int[] zoneIds = new int[mCarAudioZones.size()];
            for (int i = 0; i < mCarAudioZones.size(); i++) {
                zoneIds[i] = mCarAudioZones.keyAt(i);
            }
            return zoneIds;
        }
    }

    /**
     * Gets the audio zone id currently mapped to uid,
     *
     * <p><b>Note:</b> Will use uid mapping first, followed by uid's {@userId} mapping.
     * defaults to PRIMARY_AUDIO_ZONE if no mapping exist
     *
     * @param uid The uid
     * @return zone id mapped to uid
     */
    @Override
    public int getZoneIdForUid(int uid) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        synchronized (mImplLock) {
            return getZoneIdForUidLocked(uid);
        }
    }

    @GuardedBy("mImplLock")
    private int getZoneIdForUidLocked(int uid) {
        if (mUidToZoneMap.containsKey(uid)) {
            return mUidToZoneMap.get(uid);
        }

        return getZoneIdForUserLocked(UserHandle.getUserHandleForUid(uid));
    }

    @GuardedBy("mImplLock")
    private int getZoneIdForUserLocked(UserHandle handle) {
        CarOccupantZoneManager.OccupantZoneInfo info =
                mOccupantZoneService.getOccupantZoneForUser(handle);

        int audioZoneId = CarAudioManager.INVALID_AUDIO_ZONE;
        if (info != null) {
            audioZoneId = mOccupantZoneService.getAudioZoneIdForOccupant(info.zoneId);
        }

        return audioZoneId == CarAudioManager.INVALID_AUDIO_ZONE ? PRIMARY_AUDIO_ZONE : audioZoneId;
    }

    /**
     * Maps the audio zone id to uid
     *
     * @param zoneId The audio zone id
     * @param uid The uid to map
     *
     * <p><b>Note:</b> Will throw if occupant zone mapping exist, as uid and occupant zone mapping
     * do not work in conjunction.
     *
     * @return true if the device affinities, for devices in zone, are successfully set
     */
    @Override
    public boolean setZoneIdForUid(int zoneId, int uid) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        synchronized (mImplLock) {
            checkAudioZoneIdLocked(zoneId);
            Slogf.i(TAG, "setZoneIdForUid Calling uid %d mapped to : %d", uid, zoneId);

            // If occupant mapping exist uid routing can not be used
            requiredOccupantZoneMappingDisabledLocked();

            // Figure out if anything is currently holding focus,
            // This will change the focus to transient loss while we are switching zones
            Integer currentZoneId = mUidToZoneMap.get(uid);
            ArrayList<AudioFocusInfo> currentFocusHoldersForUid = new ArrayList<>();
            ArrayList<AudioFocusInfo> currentFocusLosersForUid = new ArrayList<>();
            if (currentZoneId != null) {
                currentFocusHoldersForUid = mFocusHandler.getAudioFocusHoldersForUid(uid,
                        currentZoneId.intValue());
                currentFocusLosersForUid = mFocusHandler.getAudioFocusLosersForUid(uid,
                        currentZoneId.intValue());
                if (!currentFocusHoldersForUid.isEmpty() || !currentFocusLosersForUid.isEmpty()) {
                    // Order matters here: Remove the focus losers first
                    // then do the current holder to prevent loser from popping up while
                    // the focus is being remove for current holders
                    // Remove focus for current focus losers
                    mFocusHandler.transientlyLoseInFocusInZone(currentFocusLosersForUid,
                            currentZoneId.intValue());
                    // Remove focus for current holders
                    mFocusHandler.transientlyLoseInFocusInZone(currentFocusHoldersForUid,
                            currentZoneId.intValue());
                }
            }

            // if the current uid is in the list
            // remove it from the list

            if (checkAndRemoveUidLocked(uid)) {
                if (setZoneIdForUidNoCheckLocked(zoneId, uid)) {
                    // Order matters here: Regain focus for
                    // Previously lost focus holders then regain
                    // focus for holders that had it last
                    // Regain focus for the focus losers from previous zone
                    if (!currentFocusLosersForUid.isEmpty()) {
                        regainAudioFocusLocked(currentFocusLosersForUid, zoneId);
                    }
                    // Regain focus for the focus holders from previous zone
                    if (!currentFocusHoldersForUid.isEmpty()) {
                        regainAudioFocusLocked(currentFocusHoldersForUid, zoneId);
                    }
                    return true;
                }
            }
            return false;
        }
    }

    @GuardedBy("mImplLock")
    private boolean handleAssignAudioFromUserIdToPrimaryAudioZoneLocked(
            IBinder token, int userId, int zoneId, long requestId) {
        AudioFocusStack mediaFocusStack =
                mFocusHandler.transientlyLoseMediaAudioFocusForUser(userId, zoneId);

        if (!shareAudioRoutingForUserInPrimaryAudioZoneLocked(userId, zoneId)) {
            Slogf.w(TAG, "Can not route user id %s to primary audio zone", userId);
            mFocusHandler.regainMediaAudioFocusInZone(mediaFocusStack, zoneId);
            return false;
        }

        DeathRecipient deathRecipient = () -> handleAssignedAudioFromUserDeath(requestId);
        try {
            token.linkToDeath(deathRecipient, /* flags= */ 0);
        } catch (RemoteException e) {
            Slogf.e(TAG, e, "Can not route user id %d to primary audio zone, caller died", userId);
            mFocusHandler.regainMediaAudioFocusInZone(mediaFocusStack, zoneId);
            return false;
        }

        mFocusHandler.regainMediaAudioFocusInZone(mediaFocusStack, PRIMARY_AUDIO_ZONE);
        mUserAssignedToPrimaryZoneToCallbackDeathRecipient.put(userId, deathRecipient);
        mMediaRequestHandler.acceptMediaAudioRequest(token, requestId);

        Slogf.d(TAG, "Assigning user id %d from primary audio zone", userId);

        return true;
    }

    @GuardedBy("mImplLock")
    private boolean shareAudioRoutingForUserInPrimaryAudioZoneLocked(int userId, int zoneId) {
        CarAudioZone zone = mCarAudioZones.get(zoneId);
        return shareUserIdMediaInMainZoneLocked(userId, zone);
    }

    @GuardedBy("mImplLock")
    private boolean shareUserIdMediaInMainZoneLocked(int userId, CarAudioZone audioZone) {
        List<AudioDeviceInfo> devices = audioZone.getCurrentAudioDeviceInfos();
        CarAudioZone primaryAudioZone = getCarAudioZoneLocked(PRIMARY_AUDIO_ZONE);
        devices.add(primaryAudioZone.getAudioDeviceForContext(mCarAudioContext
                .getContextForAudioAttribute(MEDIA_AUDIO_ATTRIBUTE)));

        return setUserIdDeviceAffinityLocked(devices, userId, audioZone.getId());
    }

    @GuardedBy("mImplLock")
    private boolean setupMirrorDeviceForUserIdLocked(int userId, CarAudioZone audioZone,
            AudioDeviceInfo mirrorDevice) {
        List<AudioDeviceInfo> devices = audioZone.getCurrentAudioDeviceInfos();
        devices.add(mirrorDevice);

        Slogf.d(TAG, "setupMirrorDeviceForUserIdLocked for userId %d in zone %d", userId,
                audioZone.getId());

        return setUserIdDeviceAffinityLocked(devices, userId, audioZone.getId());
    }

    @GuardedBy("mImplLock")
    private boolean setUserIdDeviceAffinityLocked(List<AudioDeviceInfo> devices,
            int userId, int zoneId) {
        boolean results = mAudioPolicy.setUserIdDeviceAffinity(userId, devices);
        if (!results) {
            Slogf.w(TAG, "setUserIdDeviceAffinityLocked for userId %d in zone %d Failed,"
                    + " could not set audio routing.", userId, zoneId);
        }
        return results;
    }

    private void handleAssignedAudioFromUserDeath(long requestId) {
        Slogf.e(TAG, "IBinder for request %d died", requestId);
        handleUnassignAudioFromUserIdOnPrimaryAudioZone(requestId);
    }

    private boolean handleUnassignAudioFromUserIdOnPrimaryAudioZone(long requestId) {
        CarOccupantZoneManager.OccupantZoneInfo info =
                mMediaRequestHandler.getOccupantForRequest(requestId);

        if (info == null) {
            Slogf.w(TAG, "Occupant %s is not mapped to any audio zone", info);
            return false;
        }
        int userId = mOccupantZoneService.getUserForOccupant(info.zoneId);
        int audioZoneId = mOccupantZoneService.getAudioZoneIdForOccupant(info.zoneId);

        synchronized (mImplLock) {
            CarAudioZone audioZone = getCarAudioZoneLocked(audioZoneId);

            AudioFocusStack mediaFocusStack =
                    mFocusHandler.transientlyLoseMediaAudioFocusForUser(userId, PRIMARY_AUDIO_ZONE);

            if (!resetUserIdMediaInMainZoneLocked(userId, audioZone)) {
                Slogf.w(TAG, "Can not remove route for user id %d to primary audio zone", userId);
                mFocusHandler.regainMediaAudioFocusInZone(mediaFocusStack, PRIMARY_AUDIO_ZONE);
                return false;
            }

            mFocusHandler.regainMediaAudioFocusInZone(mediaFocusStack, audioZoneId);
            removeAssignedUserInfoLocked(userId);
        }

        Slogf.d(TAG, "Unassigned user id %d from primary audio zone", userId);

        return mMediaRequestHandler.stopMediaAudioOnPrimaryZone(requestId);
    }

    @GuardedBy("mImplLock")
    private void removeAssignedUserInfoLocked(int userId) {
        mUserAssignedToPrimaryZoneToCallbackDeathRecipient.remove(userId);
    }

    @GuardedBy("mImplLock")
    private boolean resetUserIdMediaInMainZoneLocked(int userId, CarAudioZone audioZone) {
        List<AudioDeviceInfo> devices = audioZone.getCurrentAudioDeviceInfos();
        return setUserIdDeviceAffinityLocked(devices, userId, audioZone.getId());
    }

    @GuardedBy("mImplLock")
    private AudioDeviceInfo getOutputDeviceForAudioAttributeLocked(int zoneId,
            AudioAttributes audioAttributes) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        int contextForUsage = mCarAudioContext.getContextForAudioAttribute(audioAttributes);
        Preconditions.checkArgument(!CarAudioContext.isInvalidContextId(contextForUsage),
                "Invalid audio attribute usage %s", audioAttributes);
        return getCarAudioZoneLocked(zoneId).getAudioDeviceForContext(contextForUsage);
    }

    @Override
    public String getOutputDeviceAddressForUsage(int zoneId, @AttributeUsage int usage) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        CarAudioContext.checkAudioAttributeUsage(usage);
        int contextForUsage = getCarAudioContext()
                .getContextForAudioAttribute(CarAudioContext.getAudioAttributeFromUsage(usage));
        return getCarAudioZone(zoneId).getAddressForContext(contextForUsage);
    }

    /**
     * Regain focus for the focus list passed in
     * @param afiList focus info list to regain
     * @param zoneId zone id where the focus holder belong
     */
    @GuardedBy("mImplLock")
    void regainAudioFocusLocked(ArrayList<AudioFocusInfo> afiList, int zoneId) {
        for (AudioFocusInfo info : afiList) {
            if (mFocusHandler.reevaluateAndRegainAudioFocus(info)
                    != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Slogf.i(TAG,
                        " Focus could not be granted for entry %s uid %d in zone %d",
                        info.getClientId(), info.getClientUid(), zoneId);
            }
        }
    }

    /**
     * Removes the current mapping of the uid, focus will be lost in zone
     * @param uid The uid to remove
     *
     * <p><b>Note:</b> Will throw if occupant zone mapping exist, as uid and occupant zone mapping
     * do not work in conjunction.
     *
     * return true if all the devices affinities currently
     *            mapped to uid are successfully removed
     */
    @Override
    public boolean clearZoneIdForUid(int uid) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        synchronized (mImplLock) {
            // Throw so as to not set the wrong expectation,
            // that routing will be changed if clearZoneIdForUid is called.
            requiredOccupantZoneMappingDisabledLocked();

            return checkAndRemoveUidLocked(uid);
        }
    }

    /**
     * Sets the zone id for uid
     * @param zoneId zone id to map to uid
     * @param uid uid to map
     * @return true if setting uid device affinity is successful
     */
    @GuardedBy("mImplLock")
    private boolean setZoneIdForUidNoCheckLocked(int zoneId, int uid) {
        Slogf.d(TAG, "setZoneIdForUidNoCheck Calling uid %d mapped to %d", uid, zoneId);
        //Request to add uid device affinity
        List<AudioDeviceInfo> deviceInfos =
                getCarAudioZoneLocked(zoneId).getCurrentAudioDeviceInfos();
        if (mAudioPolicy.setUidDeviceAffinity(uid, deviceInfos)) {
            // TODO do not store uid mapping here instead use the uid
            //  device affinity in audio policy when available
            mUidToZoneMap.put(uid, zoneId);
            return true;
        }
        Slogf.w(TAG, "setZoneIdForUidNoCheck Failed set device affinity for uid %d in zone %d",
                uid, zoneId);
        return false;
    }

    /**
     * Check if uid is attached to a zone and remove it
     * @param uid unique id to remove
     * @return true if the uid was successfully removed or mapping was not assigned
     */
    @GuardedBy("mImplLock")
    private boolean checkAndRemoveUidLocked(int uid) {
        Integer zoneId = mUidToZoneMap.get(uid);
        if (zoneId != null) {
            Slogf.i(TAG, "checkAndRemoveUid removing Calling uid %d from zone %d", uid, zoneId);
            if (mAudioPolicy.removeUidDeviceAffinity(uid)) {
                // TODO use the uid device affinity in audio policy when available
                mUidToZoneMap.remove(uid);
                return true;
            }
            //failed to remove device affinity from zone devices
            Slogf.w(TAG, "checkAndRemoveUid Failed remove device affinity for uid %d in zone %d",
                    uid, zoneId);
            return false;
        }
        return true;
    }

    /*
     *  {@link android.car.media.CarAudioManager#registerCarVolumeGroupEventCallback()}
     */
    @Override
    public boolean registerCarVolumeEventCallback(ICarVolumeEventCallback callback) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        requireDynamicRouting();
        requireVolumeGroupEvents();

        mCarVolumeEventHandler.registerCarVolumeEventCallback(callback);
        return true;
    }

    /*
     *  {@link android.car.media.CarAudioManager#unregisterCarVolumeGroupEventCallback()}
     */
    @Override
    public boolean unregisterCarVolumeEventCallback(ICarVolumeEventCallback callback) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        requireDynamicRouting();
        requireVolumeGroupEvents();

        mCarVolumeEventHandler.unregisterCarVolumeEventCallback(callback);
        return true;
    }

    @Override
    public void registerVolumeCallback(@NonNull IBinder binder) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
            mCarVolumeCallbackHandler.registerCallback(binder);
        }
    }

    @Override
    public void unregisterVolumeCallback(@NonNull IBinder binder) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
            mCarVolumeCallbackHandler.unregisterCallback(binder);
        }
    }

    /**
     * {@link android.car.media.CarAudioManager#isVolumeGroupMuted(int, int)}
     */
    @Override
    public boolean isVolumeGroupMuted(int zoneId, int groupId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        requireDynamicRouting();
        if (!mUseCarVolumeGroupMuting) {
            return false;
        }
        synchronized (mImplLock) {
            CarVolumeGroup group = getCarVolumeGroupLocked(zoneId, groupId);
            return group.isMuted();
        }
    }

    /**
     * {@link android.car.media.CarAudioManager#setVolumeGroupMute(int, int, boolean, int)}
     */
    @Override
    public void setVolumeGroupMute(int zoneId, int groupId, boolean mute, int flags) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        requireDynamicRouting();
        requireVolumeGroupMuting();
        synchronized (mImplLock) {
            CarVolumeGroup group = getCarVolumeGroupLocked(zoneId, groupId);
            group.setMute(mute);
        }
        handleMuteChanged(zoneId, groupId, flags);
    }

    @Override
    public @NonNull List<AudioDeviceAttributes> getInputDevicesForZoneId(int zoneId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();

        return getCarAudioZone(zoneId).getInputAudioDevices();
    }

    @Override
    public CarAudioZoneConfigInfo getCurrentAudioZoneConfigInfo(int zoneId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        synchronized (mImplLock) {
            return getCarAudioZoneLocked(zoneId).getCurrentCarAudioZoneConfig()
                    .getCarAudioZoneConfigInfo();
        }
    }

    @Override
    public List<CarAudioZoneConfigInfo> getAudioZoneConfigInfos(int zoneId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        synchronized (mImplLock) {
            return getCarAudioZoneLocked(zoneId).getCarAudioZoneConfigInfos();
        }
    }

    @Override
    public void switchZoneToConfig(CarAudioZoneConfigInfo zoneConfig,
            ISwitchAudioZoneConfigCallback callback) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        requireDynamicRouting();
        Objects.requireNonNull(zoneConfig, "Car audio zone config to switch to can not be null");
        verifyCanSwitchZoneConfigs(zoneConfig);
        mHandler.post(() -> {
            boolean isSuccessful = handleSwitchZoneConfig(zoneConfig);
            try {
                callback.onAudioZoneConfigSwitched(zoneConfig, isSuccessful);
            } catch (RemoteException e) {
                Slogf.e(TAG, e, "Could not inform zone configuration %s switch result",
                        zoneConfig);
            }
        });
    }

    private void verifyCanSwitchZoneConfigs(CarAudioZoneConfigInfo zoneConfig) {
        int zoneId = zoneConfig.getZoneId();
        synchronized (mImplLock) {
            checkAudioZoneIdLocked(zoneId);
        }

        int userId = getUserIdForZone(zoneId);
        if (userId == UserManagerHelper.USER_NULL) {
            throw new IllegalStateException(
                    "Audio zone must have an active user to allow switching zone configuration");
        }

        CarOccupantZoneManager.OccupantZoneInfo info =
                mOccupantZoneService.getOccupantForAudioZoneId(zoneId);

        if (mMediaRequestHandler.isMediaAudioAllowedInPrimaryZone(info)) {
            throw new IllegalStateException(
                    "Occupant " + info + " in audio zone " + zoneId
                            + " is currently sharing to primary zone, undo audio sharing in "
                            + "primary zone before switching zone configuration");
        }
    }

    private boolean handleSwitchZoneConfig(CarAudioZoneConfigInfo zoneConfig) {
        int zoneId = zoneConfig.getZoneId();
        CarAudioZone zone;
        synchronized (mImplLock) {
            zone = getCarAudioZoneLocked(zoneId);
        }
        if (zone.isCurrentZoneConfig(zoneConfig)) {
            Slogf.w(TAG, "handleSwitchZoneConfig switch current zone configuration");
            return true;
        }

        CarOccupantZoneManager.OccupantZoneInfo info =
                mOccupantZoneService.getOccupantForAudioZoneId(zoneId);
        if (mMediaRequestHandler.isMediaAudioAllowedInPrimaryZone(info)) {
            Slogf.w(TAG, "handleSwitchZoneConfig failed, occupant %s in audio zone %d is "
                            + "currently sharing to primary zone, undo audio sharing in primary "
                            + "zone before switching zone configuration", info, zoneId);
            return false;
        }

        synchronized (mImplLock) {
            int userId = getUserIdForZoneLocked(zoneId);
            if (userId == UserManagerHelper.USER_NULL) {
                Slogf.w(TAG, "handleSwitchZoneConfig failed, audio zone configuration switching "
                        + "not allowed for unassigned audio zone %d", zoneId);
                return false;
            }
            List<AudioFocusInfo> pendingFocusInfos =
                    mFocusHandler.transientlyLoseAllFocusInZone(zoneId);

            boolean succeeded = true;
            CarAudioZoneConfig prevZoneConfig = zone.getCurrentCarAudioZoneConfig();
            try {
                zone.setCurrentCarZoneConfig(zoneConfig);
                setUserIdDeviceAffinitiesLocked(zone, userId, zoneId);
            } catch (IllegalStateException e) {
                zone.setCurrentCarZoneConfig(prevZoneConfig.getCarAudioZoneConfigInfo());
                succeeded = false;
            }
            mFocusHandler.reevaluateAndRegainAudioFocusList(pendingFocusInfos);
            return succeeded;
        }
    }

    void setAudioEnabled(boolean isAudioEnabled) {
        Slogf.i(TAG, "Setting isAudioEnabled to %b", isAudioEnabled);

        mFocusHandler.setRestrictFocus(/* isFocusRestricted= */ !isAudioEnabled);
        if (mUseCarVolumeGroupMuting) {
            mCarVolumeGroupMuting.setRestrictMuting(/* isMutingRestricted= */ !isAudioEnabled);
        }
        // TODO(b/176258537) if not using group volume, then set master mute accordingly
    }

    private void enforcePermission(String permissionName) {
        if (mContext.checkCallingOrSelfPermission(permissionName)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("requires permission " + permissionName);
        }
    }

    private void requireDynamicRouting() {
        Preconditions.checkState(mUseDynamicRouting, "Dynamic routing is required");
    }

    private void requireAudioMirroring() {
        Preconditions.checkState(mCarAudioMirrorRequestHandler.isMirrorAudioEnabled(),
                "Audio zones mirroring is required");
    }

    private void requireVolumeGroupMuting() {
        Preconditions.checkState(mUseCarVolumeGroupMuting,
                "Car Volume Group Muting is required");
    }

    private void requireVolumeGroupEvents() {
        Preconditions.checkState(mUseCarVolumeGroupEvents,
                "Car Volume Group Events is required");
    }

    private void requireValidFadeRange(float value) {
        Preconditions.checkArgumentInRange(value, -1f, 1f, "Fade");
    }

    private void requireValidBalanceRange(float value) {
        Preconditions.checkArgumentInRange(value, -1f, 1f, "Balance");
    }

    @GuardedBy("mImplLock")
    private void requiredOccupantZoneMappingDisabledLocked() {
        if (isOccupantZoneMappingAvailableLocked()) {
            throw new IllegalStateException(
                    "UID based routing is not supported while using occupant zone mapping");
        }
    }

    @AudioContext int getSuggestedAudioContextForZone(int zoneId) {
        if (!isAudioZoneIdValid(zoneId)) {
            return CarAudioContext.getInvalidContext();
        }
        CarVolume carVolume;
        synchronized (mImplLock) {
            carVolume = mCarVolume;
        }
        return carVolume.getSuggestedAudioContextAndSaveIfFound(
                getAllActiveAttributesForZone(zoneId), getCallStateForZone(zoneId),
                getActiveHalAudioAttributesForZone(zoneId));
    }

    private List<AudioAttributes> getActiveHalAudioAttributesForZone(int zoneId) {
        if (mHalAudioFocus == null) {
            return new ArrayList<>(0);
        }
        return mHalAudioFocus.getActiveAudioAttributesForZone(zoneId);
    }

    /**
     * Gets volume group by a given legacy stream type
     * @param streamType Legacy stream type such as {@link AudioManager#STREAM_MUSIC}
     * @return volume group id mapped from stream type
     */
    private int getVolumeGroupIdForStreamType(int streamType) {
        int groupId = INVALID_VOLUME_GROUP_ID;
        for (int i = 0; i < CarAudioDynamicRouting.STREAM_TYPES.length; i++) {
            if (streamType == CarAudioDynamicRouting.STREAM_TYPES[i]) {
                groupId = i;
                break;
            }
        }
        return groupId;
    }

    private void handleOccupantZoneUserChanged() {
        int driverUserId = mOccupantZoneService.getDriverUserId();
        synchronized (mImplLock) {
            if (!isOccupantZoneMappingAvailableLocked()) {
                adjustZonesToUserIdLocked(driverUserId);
                return;
            }
            int occupantZoneForDriver =  getOccupantZoneIdForDriver();
            Set<Integer> assignedZones = new HashSet<Integer>();
            for (int index = 0; index < mAudioZoneIdToOccupantZoneIdMapping.size(); index++) {
                int audioZoneId = mAudioZoneIdToOccupantZoneIdMapping.keyAt(index);
                int occupantZoneId = mAudioZoneIdToOccupantZoneIdMapping.get(audioZoneId);
                assignedZones.add(audioZoneId);
                updateUserForOccupantZoneLocked(occupantZoneId, audioZoneId, driverUserId,
                        occupantZoneForDriver);
            }

            assignMissingZonesToDriverLocked(driverUserId, assignedZones);
        }
        restoreVolumeGroupMuteState();
    }

    private void restoreVolumeGroupMuteState() {
        if (!mUseCarVolumeGroupMuting) {
            return;
        }
        mCarVolumeGroupMuting.carMuteChanged();
    }

    @GuardedBy("mImplLock")
    private void assignMissingZonesToDriverLocked(@UserIdInt int driverUserId,
            Set<Integer> assignedZones) {
        for (int i = 0; i < mCarAudioZones.size(); i++) {
            CarAudioZone zone = mCarAudioZones.valueAt(i);
            if (assignedZones.contains(zone.getId())) {
                continue;
            }
            assignUserIdToAudioZoneLocked(zone, driverUserId);
        }
    }

    @GuardedBy("mImplLock")
    private void adjustZonesToUserIdLocked(@UserIdInt int userId) {
        for (int i = 0; i < mCarAudioZones.size(); i++) {
            CarAudioZone zone = mCarAudioZones.valueAt(i);
            assignUserIdToAudioZoneLocked(zone, userId);
        }
    }

    @GuardedBy("mImplLock")
    private void assignUserIdToAudioZoneLocked(CarAudioZone zone, @UserIdInt int userId) {
        if (userId == getUserIdForZoneLocked(zone.getId())) {
            Slogf.d(TAG, "assignUserIdToAudioZone userId(%d) already assigned to audioZoneId(%d)",
                    userId, zone.getId());
            return;
        }
        Slogf.d(TAG, "assignUserIdToAudioZone assigning userId(%d) to audioZoneId(%d)",
                userId, zone.getId());
        zone.updateVolumeGroupsSettingsForUser(userId);
        mFocusHandler.updateUserForZoneId(zone.getId(), userId);
        setUserIdForAudioZoneLocked(userId, zone.getId());
    }

    @GuardedBy("mImplLock")
    private boolean isOccupantZoneMappingAvailableLocked() {
        return mAudioZoneIdToOccupantZoneIdMapping.size() > 0;
    }

    @GuardedBy("mImplLock")
    private void updateUserForOccupantZoneLocked(int occupantZoneId, int audioZoneId,
            @UserIdInt int driverUserId, int occupantZoneForDriver) {
        CarAudioZone audioZone = getCarAudioZoneLocked(audioZoneId);
        int userId = mOccupantZoneService.getUserForOccupant(occupantZoneId);
        int prevUserId = getUserIdForZoneLocked(audioZoneId);

        if (userId == prevUserId) {
            Slogf.d(TAG, "updateUserForOccupantZone userId(%d) already assigned to audioZoneId(%d)",
                    userId, audioZoneId);
            return;
        }
        Slogf.d(TAG, "updateUserForOccupantZone assigning userId(%d) to audioZoneId(%d)",
                userId, audioZoneId);
        // If the user has changed, be sure to remove from current routing
        // This would be true even if the new user is UserManagerHelper.USER_NULL,
        // as that indicates the user has logged out.
        removeUserIdDeviceAffinitiesLocked(prevUserId);

        if (userId == UserManagerHelper.USER_NULL) {
            // Reset zone back to driver user id
            resetZoneToDefaultUser(audioZone, driverUserId);
            setUserIdForAudioZoneLocked(userId, audioZoneId);
            return;
        }

        // Only set user id device affinities for driver when it is the driver's occupant zone
        if (userId != driverUserId || occupantZoneId == occupantZoneForDriver) {
            setUserIdDeviceAffinitiesLocked(audioZone, userId, audioZoneId);
        }
        audioZone.updateVolumeGroupsSettingsForUser(userId);
        mFocusHandler.updateUserForZoneId(audioZoneId, userId);
        setUserIdForAudioZoneLocked(userId, audioZoneId);
    }

    private int getOccupantZoneIdForDriver() {
        List<CarOccupantZoneManager.OccupantZoneInfo> occupantZoneInfos =
                mOccupantZoneService.getAllOccupantZones();
        for (CarOccupantZoneManager.OccupantZoneInfo info: occupantZoneInfos) {
            if (info.occupantType == CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER) {
                return info.zoneId;
            }
        }
        return CarOccupantZoneManager.OccupantZoneInfo.INVALID_ZONE_ID;
    }

    @GuardedBy("mImplLock")
    private void setUserIdDeviceAffinitiesLocked(CarAudioZone zone, @UserIdInt int userId,
            int audioZoneId) {
        List<AudioDeviceInfo> infos = zone.getCurrentAudioDeviceInfosSupportingDynamicMix();
        if (!infos.isEmpty() && !mAudioPolicy.setUserIdDeviceAffinity(userId, infos)) {
            throw new IllegalStateException(String.format(
                    "setUserIdDeviceAffinity for userId %d in zone %d Failed,"
                            + " could not set audio routing.",
                    userId, audioZoneId));
        }
    }

    private void resetZoneToDefaultUser(CarAudioZone zone, @UserIdInt int driverUserId) {
        resetCarZonesAudioFocus(zone.getId(), driverUserId);
        zone.updateVolumeGroupsSettingsForUser(driverUserId);
    }

    private void resetCarZonesAudioFocus(int audioZoneId, @UserIdInt int driverUserId) {
        mFocusHandler.updateUserForZoneId(audioZoneId, driverUserId);
    }

    @GuardedBy("mImplLock")
    private void removeUserIdDeviceAffinitiesLocked(@UserIdInt int userId) {
        Slogf.d(TAG, "removeUserIdDeviceAffinities(%d) Succeeded", userId);
        if (userId == UserManagerHelper.USER_NULL) {
            return;
        }
        if (!mAudioPolicy.removeUserIdDeviceAffinity(userId)) {
            Slogf.e(TAG, "removeUserIdDeviceAffinities(%d) Failed", userId);
            return;
        }
    }

    @VisibleForTesting
    @UserIdInt int getUserIdForZone(int audioZoneId) {
        synchronized (mImplLock) {
            return getUserIdForZoneLocked(audioZoneId);
        }
    }

    @GuardedBy("mImplLock")
    private @UserIdInt int getUserIdForZoneLocked(int audioZoneId) {
        return mAudioZoneIdToUserIdMapping.get(audioZoneId, UserManagerHelper.USER_NULL);
    }

    @GuardedBy("mImplLock")
    private void setUserIdForAudioZoneLocked(@UserIdInt int userId, int audioZoneId) {
        mAudioZoneIdToUserIdMapping.put(audioZoneId, userId);
    }

    @GuardedBy("mImplLock")
    private AudioControlWrapper getAudioControlWrapperLocked() {
        if (mAudioControlWrapper == null) {
            mAudioControlWrapper = AudioControlFactory.newAudioControl();
            mAudioControlWrapper.linkToDeath(this::audioControlDied);
        }
        return mAudioControlWrapper;
    }

    private void resetHalAudioFocus() {
        if (mHalAudioFocus != null) {
            mHalAudioFocus.reset();
            mHalAudioFocus.registerFocusListener();
        }
    }

    private void resetHalAudioGain() {
        if (mCarAudioGainMonitor != null) {
            mCarAudioGainMonitor.reset();
            mCarAudioGainMonitor.registerAudioGainListener(mHalAudioGainCallback);
        }
    }

    private void handleAudioDeviceGainsChangedLocked(
            List<Integer> halReasons, List<CarAudioGainConfigInfo> gains) {
        mCarAudioGainMonitor.handleAudioDeviceGainsChanged(halReasons, gains);
    }

    private void audioControlDied() {
        resetHalAudioFocus();
        resetHalAudioGain();
    }

    boolean isAudioZoneIdValid(int zoneId) {
        synchronized (mImplLock) {
            return mCarAudioZones.contains(zoneId);
        }
    }

    private CarAudioZone getCarAudioZone(int zoneId) {
        synchronized (mImplLock) {
            return getCarAudioZoneLocked(zoneId);
        }
    }

    @GuardedBy("mImplLock")
    private CarAudioZone getCarAudioZoneLocked(int zoneId) {
        checkAudioZoneIdLocked(zoneId);
        return mCarAudioZones.get(zoneId);
    }

    @GuardedBy("mImplLock")
    private void checkAudioZoneIdLocked(int zoneId) {
        Preconditions.checkArgument(mCarAudioZones.contains(zoneId),
                "Invalid audio zone Id " + zoneId);
    }

    int getVolumeGroupIdForAudioContext(int zoneId, int suggestedContext) {
        synchronized (mImplLock) {
            return getVolumeGroupIdForAudioContextLocked(zoneId, suggestedContext);
        }
    }

    /**
     * Resets the last selected volume context.
     */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DEBUGGING_CODE)
    public void resetSelectedVolumeContext() {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        synchronized (mImplLock) {
            mCarVolume.resetSelectedVolumeContext();
            mCarAudioPlaybackCallback.resetStillActiveContexts();
        }
    }

    @VisibleForTesting
    CarAudioContext getCarAudioContext() {
        synchronized (mImplLock) {
            return mCarAudioContext;
        }
    }

    @VisibleForTesting
    void requestAudioFocusForTest(AudioFocusInfo audioFocusInfo, int audioFocusResult) {
        mFocusHandler.onAudioFocusRequest(audioFocusInfo, audioFocusResult);
    }

    int getZoneIdForAudioFocusInfo(AudioFocusInfo focusInfo) {
        if (isAllowedInPrimaryZone(focusInfo)) {
            return PRIMARY_AUDIO_ZONE;
        }

        int audioZoneId;
        synchronized (mImplLock) {
            audioZoneId = getZoneIdForUidLocked(focusInfo.getClientUid());
        }

        if (isAudioZoneMirroringEnabledForMedia(focusInfo, audioZoneId)) {
            int[] mirrorZones = mCarAudioMirrorRequestHandler.getMirrorAudioZonesForAudioZone(
                    audioZoneId);
            return ArrayUtils.isEmpty(mirrorZones) ? audioZoneId : mirrorZones[0];
        }

        return audioZoneId;
    }

    private boolean isAllowedInPrimaryZone(AudioFocusInfo focusInfo) {
        boolean isMedia = CarAudioContext.AudioAttributesWrapper.audioAttributeMatches(
                CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA),
                focusInfo.getAttributes());

        return isMedia && mMediaRequestHandler
                .isMediaAudioAllowedInPrimaryZone(mOccupantZoneService
                        .getOccupantZoneForUser(UserHandle
                                .getUserHandleForUid(focusInfo.getClientUid())));
    }

    private boolean isAudioZoneMirroringEnabledForMedia(AudioFocusInfo focusInfo, int zoneId) {
        boolean isMedia = CarAudioContext.AudioAttributesWrapper.audioAttributeMatches(
                CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA),
                focusInfo.getAttributes());

        return isMedia && mCarAudioMirrorRequestHandler.isMirrorEnabledForZone(zoneId);
    }

    private List<AudioAttributes> getAllActiveAttributesForZone(int zoneId) {
        synchronized (mImplLock) {
            return mCarAudioPlaybackCallback.getAllActiveAudioAttributesForZone(zoneId);
        }
    }

    List<CarVolumeGroupInfo> getMutedVolumeGroups(int zoneId) {
        List<CarVolumeGroupInfo> mutedGroups = new ArrayList<>();

        if (!mUseCarVolumeGroupMuting || !isAudioZoneIdValid(zoneId)) {
            return mutedGroups;
        }

        synchronized (mImplLock) {
            int groupCount = getCarAudioZoneLocked(zoneId).getCurrentVolumeGroupCount();
            for (int groupId = 0; groupId < groupCount; groupId++) {
                CarVolumeGroup group = getCarVolumeGroupLocked(zoneId, groupId);
                if (!group.isMuted()) {
                    continue;
                }

                mutedGroups.add(group.getCarVolumeGroupInfo());
            }
        }

        return mutedGroups;
    }

    List<AudioAttributes> getActiveAudioAttributesForZone(int zoneId) {
        List<AudioAttributes> activeAudioAttributes = new ArrayList<>();
        activeAudioAttributes.addAll(getAllActiveAttributesForZone(zoneId));
        activeAudioAttributes.addAll(getActiveHalAudioAttributesForZone(zoneId));

        return activeAudioAttributes;
    }

    static final class SystemClockWrapper {
        public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }
    }

    void onAudioVolumeGroupChanged(int zoneId, String groupName, int flags) {
        synchronized (mImplLock) {
            CarVolumeGroup group = getCarVolumeGroupLocked(zoneId, groupName);
            if (group == null) {
                Slogf.w(TAG, "onAudioVolumeGroupChanged reported on unmanaged group (%s)",
                        groupName);
                return;
            }
            int volumeEventFlags = group.onAudioVolumeGroupChanged(flags);

            if (CarVolumeEventFlag.hasInvalidFlag(volumeEventFlags)) {
                Slogf.e(TAG, "onAudioVolumeGroupChanged has invalid flag(%s)",
                        CarVolumeEventFlag.flagsToString(volumeEventFlags));
                return;
            }

            if ((volumeEventFlags & CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE) != 0) {
                callbackGroupVolumeChange(zoneId, group.getId(), /* flags= */ 0);
                volumeEventFlags &= ~CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE;
            }
            if ((volumeEventFlags & CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE) != 0) {
                callbackGroupMuteChanged(zoneId, group.getId(), /* flags= */ 0);
                volumeEventFlags &= ~CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE;
            }
        }
    }

    private static final class AudioFocusStackRequest {
        private final AudioFocusStack mStack;
        private final int mOriginalZoneId;

        AudioFocusStackRequest(AudioFocusStack stack, int originalZoneId) {
            mOriginalZoneId = originalZoneId;
            mStack = stack;
        }
    }
}
