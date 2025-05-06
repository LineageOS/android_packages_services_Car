/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.audio.hal;

import static android.car.builtin.media.AudioManagerHelper.usageToString;
import static android.car.builtin.media.AudioManagerHelper.usageToXsdString;
import static android.car.builtin.media.AudioManagerHelper.xsdStringToUsage;

import static com.android.car.audio.CarHalAudioUtils.usageToMetadata;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import static java.util.Collections.EMPTY_LIST;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.builtin.os.ServiceManagerHelper;
import android.car.builtin.util.Slogf;
import android.car.feature.Flags;
import android.hardware.audio.common.PlaybackTrackMetadata;
import android.hardware.automotive.audiocontrol.AudioDeviceConfiguration;
import android.hardware.automotive.audiocontrol.AudioGainConfigInfo;
import android.hardware.automotive.audiocontrol.AudioZone;
import android.hardware.automotive.audiocontrol.DuckingInfo;
import android.hardware.automotive.audiocontrol.IAudioControl;
import android.hardware.automotive.audiocontrol.IAudioGainCallback;
import android.hardware.automotive.audiocontrol.IFocusListener;
import android.hardware.automotive.audiocontrol.IModuleChangeCallback;
import android.hardware.automotive.audiocontrol.MutingInfo;
import android.hardware.automotive.audiocontrol.RoutingDeviceConfiguration;
import android.media.audio.common.AudioPort;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.car.CarLog;
import com.android.car.audio.CarAudioGainConfigInfo;
import com.android.car.audio.CarDuckingInfo;
import com.android.car.audio.CarHalAudioUtils;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.annotation.AttributeUsage;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * AudioControlWrapper wraps AIDL IAudioControl HAL interface, handling version specific support so
 * that the rest of CarAudioService doesn't need to know about it.
 */
public final class AudioControlWrapper implements IBinder.DeathRecipient {
    static final String TAG = CarLog.tagFor(AudioControlWrapper.class);

    public static final int AUDIOCONTROL_FEATURE_AUDIO_FOCUS = 0;
    public static final int AUDIOCONTROL_FEATURE_AUDIO_DUCKING = 1;
    public static final int AUDIOCONTROL_FEATURE_AUDIO_GROUP_MUTING = 2;
    public static final int AUDIOCONTROL_FEATURE_AUDIO_FOCUS_WITH_METADATA = 3;
    public static final int AUDIOCONTROL_FEATURE_AUDIO_GAIN_CALLBACK = 4;
    public static final int AUDIOCONTROL_FEATURE_AUDIO_MODULE_CALLBACK = 5;
    public static final int AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION = 6;

    @IntDef({
            AUDIOCONTROL_FEATURE_AUDIO_FOCUS,
            AUDIOCONTROL_FEATURE_AUDIO_DUCKING,
            AUDIOCONTROL_FEATURE_AUDIO_GROUP_MUTING,
            AUDIOCONTROL_FEATURE_AUDIO_FOCUS_WITH_METADATA,
            AUDIOCONTROL_FEATURE_AUDIO_GAIN_CALLBACK,
            AUDIOCONTROL_FEATURE_AUDIO_MODULE_CALLBACK,
            AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface AudioControlFeature {
    }

    private static final String AUDIO_CONTROL_SERVICE =
            "android.hardware.automotive.audiocontrol.IAudioControl/default";

    private static final int AIDL_AUDIO_CONTROL_VERSION_1 = 1;
    private static final int AIDL_AUDIO_CONTROL_VERSION_2 = 2;
    private static final int AIDL_AUDIO_CONTROL_VERSION_3 = 3;
    private static final int AIDL_AUDIO_CONTROL_VERSION_4 = 4;
    private static final int AIDL_AUDIO_CONTROL_VERSION_5 = 5;

    private IBinder mBinder;
    private IAudioControl mAudioControl;
    private boolean mListenerRegistered = false;
    private boolean mGainCallbackRegistered = false;
    private boolean mModuleChangeCallbackRegistered;

    private AudioControlDeathRecipient mDeathRecipient;

    private Executor mExecutor = Executors.newSingleThreadExecutor();

    public static @Nullable IBinder getService() {
        return ServiceManagerHelper.waitForDeclaredService(AUDIO_CONTROL_SERVICE);
    }

    /**
     * Generates {@link AudioControlWrapper} for interacting with IAudioControl HAL service.
     *
     * @return {@link AudioControlWrapper} for registered IAudioControl service.
     */
    public static AudioControlWrapper newAudioControl() {
        return new AudioControlWrapper();
    }

    AudioControlWrapper() {
        IBinder binder = getService();
        if (binder == null) {
            Slogf.i(TAG, "AIDL AudioControl HAL not in the manifest");
            throw new IllegalStateException("No version of AIDL AudioControl HAL in the manifest");
        }

        mBinder = Objects.requireNonNull(binder);
        mAudioControl = IAudioControl.Stub.asInterface(binder);
    }

    /**
     * Closes the focus listener that's registered on the AudioControl HAL
     */
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    public void unregisterFocusListener() {
        // Focus listener will be unregistered by HAL automatically
    }

    /**
     * Indicates if HAL can support specified feature
     *
     * @param feature to check support for. it's expected to be one of the features defined by
     * {@link AudioControlFeature}.
     * @return boolean indicating whether feature is supported
     */
    public boolean supportsFeature(int feature) {
        switch (feature) {
            case AUDIOCONTROL_FEATURE_AUDIO_FOCUS:
            case AUDIOCONTROL_FEATURE_AUDIO_DUCKING:
            case AUDIOCONTROL_FEATURE_AUDIO_GROUP_MUTING:
                return true;
            case AUDIOCONTROL_FEATURE_AUDIO_FOCUS_WITH_METADATA:
            case AUDIOCONTROL_FEATURE_AUDIO_GAIN_CALLBACK:
                try {
                    return mAudioControl.getInterfaceVersion() > AIDL_AUDIO_CONTROL_VERSION_1;
                } catch (RemoteException e) {
                    Slogf.w("supportsFeature Failed to get version for feature: " + feature, e);
                }
                return false;
            case AUDIOCONTROL_FEATURE_AUDIO_MODULE_CALLBACK:
                try {
                    return mAudioControl.getInterfaceVersion() > AIDL_AUDIO_CONTROL_VERSION_2;
                } catch (RemoteException e) {
                    Slogf.w("supportsFeature Failed to get version for feature: " + feature, e);
                }
                return false;
            case AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION:
                if (!Flags.audioControlHalConfiguration()) {
                    Slogf.i(TAG, "supportsFeature AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION"
                            + " not supported since audio control HAL config is disabled");
                    return false;
                }
                try {
                    return mAudioControl.getInterfaceVersion() > AIDL_AUDIO_CONTROL_VERSION_4;
                } catch (RemoteException e) {
                    Slogf.w("supportsFeature Failed to get version for feature: " + feature, e);
                }
                Slogf.i(TAG, "supportsFeature requires audio control version "
                        + AIDL_AUDIO_CONTROL_VERSION_5);
                return false;
            default:
                return false;
        }
    }

    /**
     * Registers listener for HAL audio focus requests with IAudioControl. Only works if
     * {@code supportsHalAudioFocus} returns true.
     *
     * @param focusListener the listener to register on the IAudioControl HAL.
     */
    public void registerFocusListener(HalFocusListener focusListener) {
        if (Slogf.isLoggable(TAG, Log.DEBUG)) {
            Slogf.d(TAG, "Registering focus listener on AudioControl HAL");
        }
        IFocusListener listenerWrapper = new FocusListenerWrapper(focusListener);
        try {
            mAudioControl.registerFocusListener(listenerWrapper);
        } catch (RemoteException e) {
            Slogf.e(TAG, "Failed to register focus listener");
            throw new IllegalStateException("IAudioControl#registerFocusListener failed", e);
        }
        mListenerRegistered = true;
    }

    /**
     * Registers callback for HAL audio gain changed notification with IAudioControl. Only works if
     * {@code supportsHalAudioGainCallback} returns true.
     *
     * @param gainCallback the callback to register on the IAudioControl HAL.
     */
    public void registerAudioGainCallback(HalAudioGainCallback gainCallback) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slogf.d(TAG, "Registering Audio Gain Callback on AudioControl HAL");
        }
        Objects.requireNonNull(gainCallback, "Audio Gain Callback can not be null");
        IAudioGainCallback agc = new AudioGainCallbackWrapper(gainCallback);
        try {
            if (mAudioControl.getInterfaceVersion() < AIDL_AUDIO_CONTROL_VERSION_2) {
                Slogf.w(TAG, "Registering audio gain callback is not supported"
                        + " for versions less than " + AIDL_AUDIO_CONTROL_VERSION_2);
                return;
            }
            mAudioControl.registerGainCallback(agc);
        } catch (RemoteException e) {
            Slogf.e(TAG, "Failed to register gain callback");
            throw new IllegalStateException("IAudioControl#registerAudioGainCallback failed", e);
        }
        mGainCallbackRegistered = true;
    }

    /**
     * Closes the audio gain callback registered on the AudioControl HAL
     */
    public void unregisterAudioGainCallback() {
        // Audio Gain Callback will be unregistered by HAL automatically
    }

    /**
     * Notifies HAL of change in audio focus for a request it has made.
     *
     * @param metadata {@link PlaybackTrackMetadata} that the request is associated with.
     * @param zoneId for the audio zone that the request is associated with.
     * @param focusChange the new status of the request.
     */
    public void onAudioFocusChange(PlaybackTrackMetadata metaData, int zoneId, int focusChange) {
        if (Slogf.isLoggable(TAG, Log.DEBUG)) {
            Slogf.d(TAG, "onAudioFocusChange: metadata %s, zoneId %d, focusChanged %d", metaData,
                    zoneId, focusChange);
        }
        try {
            mAudioControl.onAudioFocusChangeWithMetaData(metaData, zoneId, focusChange);
        } catch (RemoteException e) {
            Slogf.d(TAG, "onAudioFocusChange: failed with metadata, retry with usage.");
            onAudioFocusChange(metaData.usage, zoneId, focusChange);
        }
    }

    private void onAudioFocusChange(@AttributeUsage int usage, int zoneId, int focusChange) {
        if (Slogf.isLoggable(TAG, Log.DEBUG)) {
            Slogf.d(TAG, "onAudioFocusChange: usage %s, zoneId %d, focusChanged %d",
                    usageToString(usage), zoneId, focusChange);
        }
        try {
            String usageName = usageToXsdString(usage);
            mAudioControl.onAudioFocusChange(usageName, zoneId, focusChange);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to query IAudioControl#onAudioFocusChange", e);
        }
    }

    /**
     * dumps the current state of the AudioControlWrapper
     *
     * @param writer stream to write current state
     */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("*AudioControlWrapper*");
        writer.increaseIndent();
        try {
            writer.printf("Aidl Version: %d\n", mAudioControl.getInterfaceVersion());
        } catch (RemoteException e) {
            Slogf.e(TAG, "dump getInterfaceVersion error", e);
            writer.printf("Version: Could not be retrieved\n");
        }
        writer.printf("Focus listener registered on HAL? %b\n", mListenerRegistered);
        writer.printf("Audio Gain Callback registered on HAL? %b\n", mGainCallbackRegistered);
        writer.printf("Module change Callback set on HAL? %b\n", mModuleChangeCallbackRegistered);

        writer.println("Supported Features");
        writer.increaseIndent();
        writer.println("- AUDIOCONTROL_FEATURE_AUDIO_FOCUS");
        writer.println("- AUDIOCONTROL_FEATURE_AUDIO_DUCKING");
        if (supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_FOCUS_WITH_METADATA)) {
            writer.println("- AUDIOCONTROL_FEATURE_AUDIO_FOCUS_WITH_METADATA");
            writer.println("- AUDIOCONTROL_FEATURE_AUDIO_GAIN_CALLBACK");
        }
        if (supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_MODULE_CALLBACK)) {
            writer.println("- AUDIOCONTROL_FEATURE_AUDIO_MODULE_CALLBACK");
        }
        if (supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION)) {
            writer.println("- AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION");
        }
        writer.decreaseIndent();

        writer.decreaseIndent();
    }

    /**
     * Sets the fade for the vehicle.
     *
     * @param value to set for the fade. Positive is towards front.
     */
    public void setFadeTowardFront(float value) {
        try {
            mAudioControl.setFadeTowardFront(value);
        } catch (RemoteException e) {
            Slogf.e(TAG, "setFadeTowardFront with " + value + " failed", e);
        }
    }

    /**
     * Sets the balance value for the vehicle.
     *
     * @param value to set for the balance. Positive is towards the right.
     */
    public void setBalanceTowardRight(float value) {
        try {
            mAudioControl.setBalanceTowardRight(value);
        } catch (RemoteException e) {
            Slogf.e(TAG, "setBalanceTowardRight with " + value + " failed", e);
        }
    }

    /**
     * Notifies HAL of changes in usages holding focus and the corresponding ducking changes for a
     * given zone.
     *
     * @param carDuckingInfos list of information about focus and addresses to duck for each
     * impacted zone to relay to the HAL.
     */
    public void onDevicesToDuckChange(@NonNull List<CarDuckingInfo> carDuckingInfos) {
        Objects.requireNonNull(carDuckingInfos);
        DuckingInfo[] duckingInfos = new DuckingInfo[carDuckingInfos.size()];
        for (int i = 0; i < carDuckingInfos.size(); i++) {
            CarDuckingInfo info = Objects.requireNonNull(carDuckingInfos.get(i));
            duckingInfos[i] = CarHalAudioUtils.generateDuckingInfo(info);
        }

        try {
            mAudioControl.onDevicesToDuckChange(duckingInfos);
        } catch (RemoteException e) {
            Slogf.e(TAG, e, "onDevicesToDuckChange failed");
        }
    }

    /**
     * Notifies HAL of changes in muting changes for all audio zones.
     *
     * @param carZonesMutingInfo list of information about addresses to mute to relay to the HAL.
     */
    public void onDevicesToMuteChange(@NonNull List<MutingInfo> carZonesMutingInfo) {
        Objects.requireNonNull(carZonesMutingInfo, "Muting info can not be null");
        Preconditions.checkArgument(!carZonesMutingInfo.isEmpty(), "Muting info can not be empty");
        MutingInfo[] mutingInfoToHal = carZonesMutingInfo
                .toArray(new MutingInfo[carZonesMutingInfo.size()]);
        try {
            mAudioControl.onDevicesToMuteChange(mutingInfoToHal);
        } catch (RemoteException e) {
            Slogf.e(TAG, e, "onDevicesToMuteChange failed");
        }
    }

    /**
     * Registers callback for HAL audio module change notification with IAudioControl. Only works
     * if {@code supportsHalAudioModuleChangeCallback} returns true.
     *
     * @param moduleChangeCallback the callback to register on the IAudioControl HAL.
     */
    public void setModuleChangeCallback(HalAudioModuleChangeCallback moduleChangeCallback) {
        Objects.requireNonNull(moduleChangeCallback, "Module change callback can not be null");

        IModuleChangeCallback callback = new ModuleChangeCallbackWrapper(moduleChangeCallback);
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mAudioControl.getInterfaceVersion() < AIDL_AUDIO_CONTROL_VERSION_3) {
                        Slogf.w(TAG, "Setting module change callback is not supported"
                                + " for versions less than " + AIDL_AUDIO_CONTROL_VERSION_3);
                        return;
                    }
                    mAudioControl.setModuleChangeCallback(callback);
                    mModuleChangeCallbackRegistered = true;
                } catch (RemoteException e) {
                    throw new IllegalStateException(
                            "IAudioControl#setModuleChangeCallback failed", e);
                } catch (UnsupportedOperationException e) {
                    Slogf.w(TAG, "Failed to set module change callback, feature not supported");
                } catch (IllegalStateException e) {
                    // we hit this if car service crashed and restarted. lets clear callbacks and
                    // try again one more time.
                    Slogf.w(TAG, "Module change callback already set, retry after clearing");
                    try {
                        mAudioControl.clearModuleChangeCallback();
                        mAudioControl.setModuleChangeCallback(callback);
                        mModuleChangeCallbackRegistered = true;
                    } catch (RemoteException ex) {
                        throw new IllegalStateException(
                                "IAudioControl#setModuleChangeCallback failed (after retry)", ex);
                    } catch (IllegalStateException ex) {
                        Slogf.e(TAG, ex, "Failed to set module change callback (after retry)");
                        // lets  not throw any exception since it may lead to car service failure
                    }
                }
            }
        });
    }

    /**
     * Clears all module change callbacks that's registered on the AudioControl HAL
     */
    public void clearModuleChangeCallback() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mAudioControl.getInterfaceVersion() < AIDL_AUDIO_CONTROL_VERSION_3) {
                        Slogf.w(TAG, "Clearing module change callback is not supported"
                                + " for versions less than " + AIDL_AUDIO_CONTROL_VERSION_3);
                        return;
                    }
                    mAudioControl.clearModuleChangeCallback();
                    mModuleChangeCallbackRegistered = false;
                } catch (RemoteException e) {
                    throw new IllegalStateException(
                            "IAudioControl#clearModuleChangeCallback failed", e);
                } catch (UnsupportedOperationException e) {
                    Slogf.w(TAG, "Failed to clear module change callback, feature not supported");
                }
            }
        });
    }

    /**
     * Returns the audio device configurations that should be used to configure
     * the car audio service audio management.
     *
     * <p>If this method is not supported, car audio service will attempt to configure the car audio
     * service properties based on previously supported mechanisms.
     *
     * <p>If the returned value contains the
     * {@link RoutingDeviceConfiguration#DEFAULT_AUDIO_ROUTING} value, the car audio service will
     * attempt to configure audio routing based on the mechanism previously supported by car audio
     * service (e.g. car audio configuration file). Otherwise, the {@link #getCarAudioZones()}
     * API must return valid audio zone(s) configuration(s) for the device.
     *
     */
    public AudioDeviceConfiguration getAudioDeviceConfiguration() {
        if (!supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION)) {
            return getDefaultAudioConfiguration();
        }
        try {
            return mAudioControl.getAudioDeviceConfiguration();
        } catch (RemoteException e) {
            Slogf.w(TAG, "Failed to get audio device configuration", e);
            return getDefaultAudioConfiguration();
        } catch (UnsupportedOperationException e) {
            Slogf.w(TAG, "Failed to get audio device configuration, feature not supported",
                    e);
            return getDefaultAudioConfiguration();
        }
    }

    /**
     * Returns the list of audio devices that can be used for mirroring between different audio
     * zones.
     */
    public List<AudioPort> getOutputMirroringDevices() {
        if (!supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION)) {
            return EMPTY_LIST;
        }
        try {
            return mAudioControl.getOutputMirroringDevices();
        } catch (RemoteException e) {
            Slogf.w(TAG, "Failed to get audio mirroring devices", e);
            return EMPTY_LIST;
        } catch (UnsupportedOperationException e) {
            Slogf.w(TAG, "Failed to get audio mirroring devices, feature not supported",
                    e);
            return EMPTY_LIST;
        }
    }

    /**
     * List of audio zones used to configure car audio service at bootup.
     */
    public List<AudioZone> getCarAudioZones() {
        if (!supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION)) {
            return EMPTY_LIST;
        }
        try {
            return mAudioControl.getCarAudioZones();
        } catch (RemoteException e) {
            Slogf.w(TAG, "Failed to get audio zones", e);
            return EMPTY_LIST;
        } catch (UnsupportedOperationException e) {
            Slogf.w(TAG, "Failed to get audio zones, feature not supported", e);
            return EMPTY_LIST;
        }
    }

    /**
     * Registers recipient to be notified if AudioControl HAL service dies.
     *
     * @param deathRecipient to be notified upon HAL service death.
     */
    public void linkToDeath(@Nullable AudioControlDeathRecipient deathRecipient) {
        try {
            mBinder.linkToDeath(this, 0);
            mDeathRecipient = deathRecipient;
        } catch (RemoteException e) {
            throw new IllegalStateException("Call to IAudioControl#linkToDeath failed", e);
        }
    }

    /**
     * Unregisters recipient for AudioControl HAL service death.
     */
    public void unlinkToDeath() {
        mBinder.unlinkToDeath(this, 0);
        mDeathRecipient = null;
    }

    @Override
    public void binderDied() {
        Slogf.w(TAG, "AudioControl HAL died. Fetching new handle");
        mBinder.unlinkToDeath(this, 0);
        mListenerRegistered = false;
        mGainCallbackRegistered = false;
        mModuleChangeCallbackRegistered = false;
        mBinder = AudioControlWrapper.getService();
        mAudioControl = IAudioControl.Stub.asInterface(mBinder);
        // TODO(b/284043199): Refactor the retry logic out and add delay between retry.
        try {
            mBinder.linkToDeath(this, 0);
        } catch (RemoteException e) {
            // Avoid crashing the binder thread.
            Slogf.e(TAG, "Call to IAudioControl#linkToDeath failed", e);
        }
        if (mDeathRecipient != null) {
            mDeathRecipient.serviceDied();
        }
    }

    private AudioDeviceConfiguration getDefaultAudioConfiguration() {
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.routingConfig = RoutingDeviceConfiguration.DEFAULT_AUDIO_ROUTING;
        return configuration;
    }

    private static final class FocusListenerWrapper extends IFocusListener.Stub {
        private final HalFocusListener mListener;

        FocusListenerWrapper(HalFocusListener halFocusListener) {
            mListener = halFocusListener;
        }

        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
        public String getInterfaceHash() {
            return this.HASH;
        }

        @Override
        public void requestAudioFocus(String usage, int zoneId, int focusGain) {
            @AttributeUsage int usageValue = xsdStringToUsage(usage);
            requestAudioFocus(usageValue, zoneId, focusGain);
        }

        @Override
        public void abandonAudioFocus(String usage, int zoneId) {
            @AttributeUsage int usageValue = xsdStringToUsage(usage);
            abandonAudioFocus(usageValue, zoneId);
        }

        @Override
        public void requestAudioFocusWithMetaData(
                PlaybackTrackMetadata playbackMetaData, int zoneId, int focusGain) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Slogf.d(TAG, "requestAudioFocusWithMetaData metadata=%s, zoneId=%d, focusGain=%d",
                        playbackMetaData, zoneId, focusGain);
            }
            mListener.requestAudioFocus(playbackMetaData, zoneId, focusGain);
        }

        @Override
        public void abandonAudioFocusWithMetaData(
                PlaybackTrackMetadata playbackMetaData, int zoneId) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Slogf.d(TAG, "abandonAudioFocusWithMetaData metadata=%s, zoneId=%d",
                        playbackMetaData, zoneId);
            }
            mListener.abandonAudioFocus(playbackMetaData, zoneId);
        }

        private void abandonAudioFocus(int usage, int zoneId) {
            abandonAudioFocusWithMetaData(usageToMetadata(usage), zoneId);
        }

        private void requestAudioFocus(int usage, int zoneId, int focusGain) {
            requestAudioFocusWithMetaData(usageToMetadata(usage), zoneId, focusGain);
        }
    }

    private static final class AudioGainCallbackWrapper extends IAudioGainCallback.Stub {
        private @NonNull final HalAudioGainCallback mCallback;

        AudioGainCallbackWrapper(@NonNull HalAudioGainCallback gainCallback) {
            mCallback = gainCallback;
        }

        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
        public int getInterfaceVersion() {
            return VERSION;
        }

        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
        public String getInterfaceHash() {
            return HASH;
        }

        @Override
        public void onAudioDeviceGainsChanged(int[] halReasons, AudioGainConfigInfo[] gains) {
            List<CarAudioGainConfigInfo> carAudioGainConfigs = new ArrayList<>();
            for (int index = 0; index < gains.length; index++) {
                AudioGainConfigInfo gain = gains[index];
                carAudioGainConfigs.add(new CarAudioGainConfigInfo(gain));
            }
            List<Integer> reasonsList = new ArrayList<>();
            for (int index = 0; index < halReasons.length; index++) {
                int halReason = halReasons[index];
                if (!HalAudioGainCallback.isReasonValid(halReason)) {
                    Slogf.e(
                            TAG,
                            "onAudioDeviceGainsChanged invalid reasons %d reported, skipped",
                            halReason);
                    continue;
                }
                reasonsList.add(halReason);
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                List<String> gainsString = new ArrayList<>();
                for (int i = 0; i < carAudioGainConfigs.size(); i++) {
                    gainsString.add(carAudioGainConfigs.get(i).toString());
                }
                String gainsLiteral = String.join(",", gainsString);

                List<String> reasonsString = new ArrayList<>();
                for (int i = 0; i < reasonsString.size(); i++) {
                    reasonsString.add(HalAudioGainCallback.reasonToString(reasonsList.get(i)));
                }
                String reasonsLiteral = String.join(",", reasonsString);
                Slogf.d(
                        TAG,
                        "onAudioDeviceGainsChanged for reasons=[%s], gains=[%s]",
                        reasonsLiteral,
                        gainsLiteral);
            }
            mCallback.onAudioDeviceGainsChanged(reasonsList, carAudioGainConfigs);
        }
    }

    private static final class ModuleChangeCallbackWrapper extends IModuleChangeCallback.Stub {
        private final HalAudioModuleChangeCallback mCallback;

        ModuleChangeCallbackWrapper(HalAudioModuleChangeCallback callback) {
            mCallback = callback;
        }

        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
        public String getInterfaceHash() {
            return this.HASH;
        }

        @Override
        public void onAudioPortsChanged(AudioPort[] audioPorts) {
            mCallback.onAudioPortsChanged(convertAudioPortToHalAudioDevice(audioPorts));
        }
    }

    private static List<HalAudioDeviceInfo> convertAudioPortToHalAudioDevice(AudioPort[] ports) {
        List<HalAudioDeviceInfo> halAudioDeviceInfos = new ArrayList<>();
        for (AudioPort port : ports) {
            halAudioDeviceInfos.add(new HalAudioDeviceInfo(port));
        }
        return halAudioDeviceInfos;
    }

    /**
     * Recipient to be notified upon death of AudioControl HAL.
     */
    public interface AudioControlDeathRecipient {
        /**
         * Called if AudioControl HAL dies.
         */
        void serviceDied();
    }
}
