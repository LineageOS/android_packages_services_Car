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

import android.annotation.Nullable;
import android.hardware.automotive.audiocontrol.IAudioControl;
import android.hardware.automotive.audiocontrol.IFocusListener;
import android.media.AudioAttributes;
import android.media.AudioAttributes.AttributeUsage;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.PrintWriter;
import java.util.Objects;

import audio.policy.configuration.V7_0.AudioUsage;

/**
 * Wrapper for AIDL interface for AudioControl HAL
 */
public final class AudioControlWrapperAidl implements AudioControlWrapper {
    private static final String TAG = AudioControlWrapperAidl.class.getSimpleName();
    private static final String AUDIO_CONTROL_SERVICE =
            "android.hardware.automotive.audiocontrol.IAudioControl/default";
    private IBinder mBinder;
    private IAudioControl mAudioControl;
    private boolean mListenerRegistered = false;

    private AudioControlDeathRecipient mDeathRecipient;

    static @Nullable IBinder getService() {
        IBinder binder = Binder.allowBlocking(ServiceManager.waitForDeclaredService(
                AUDIO_CONTROL_SERVICE));
        if (binder != null) {
            return binder;
        }
        return null;
    }

    AudioControlWrapperAidl(IBinder binder) {
        mBinder = Objects.requireNonNull(binder);
        mAudioControl = IAudioControl.Stub.asInterface(binder);
    }

    @Override
    public void unregisterFocusListener() {
        // Focus listener will be unregistered by HAL automatically
    }

    @Override
    public boolean supportsHalAudioFocus() {
        return true;
    }

    @Override
    public void registerFocusListener(HalFocusListener focusListener) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Registering focus listener on AudioControl HAL");
        }
        IFocusListener listenerWrapper = new FocusListenerWrapper(focusListener);
        try {
            mAudioControl.registerFocusListener(listenerWrapper);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register focus listener");
            throw new IllegalStateException("IAudioControl#registerFocusListener failed", e);
        }
        mListenerRegistered = true;
    }

    @Override
    public void onAudioFocusChange(@AttributeUsage int usage, int zoneId, int focusChange) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onAudioFocusChange: usage " + AudioAttributes.usageToString(usage)
                    + ", zoneId " + zoneId + ", focusChange " + focusChange);
        }
        try {
            String usageName = usageToString(usage);
            mAudioControl.onAudioFocusChange(usageName, zoneId, focusChange);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to query IAudioControl#onAudioFocusChange", e);
        }
    }

    @Override
    public void dump(String indent, PrintWriter writer) {
        writer.printf("%s*AudioControlWrapperAidl*\n", indent);
        writer.printf("%s\tFocus listener registered on HAL? %b", indent, mListenerRegistered);
    }

    @Override
    public void setFadeTowardFront(float value) {
        try {
            mAudioControl.setFadeTowardFront(value);
        } catch (RemoteException e) {
            Log.e(TAG, "setFadeTowardFront with " + value + " failed", e);
        }
    }

    @Override
    public void setBalanceTowardRight(float value) {
        try {
            mAudioControl.setBalanceTowardRight(value);
        } catch (RemoteException e) {
            Log.e(TAG, "setBalanceTowardRight with " + value + " failed", e);
        }
    }

    @Override
    public void linkToDeath(@Nullable AudioControlDeathRecipient deathRecipient) {
        try {
            mBinder.linkToDeath(this::binderDied, 0);
            mDeathRecipient = deathRecipient;
        } catch (RemoteException e) {
            throw new IllegalStateException("Call to IAudioControl#linkToDeath failed", e);
        }
    }

    @Override
    public void unlinkToDeath() {
        mBinder.unlinkToDeath(this::binderDied, 0);
        mDeathRecipient = null;
    }

    private void binderDied() {
        Log.w(TAG, "AudioControl HAL died. Fetching new handle");
        mListenerRegistered = false;
        mBinder = AudioControlWrapperAidl.getService();
        mAudioControl = IAudioControl.Stub.asInterface(mBinder);
        linkToDeath(mDeathRecipient);
        if (mDeathRecipient != null) {
            mDeathRecipient.serviceDied();
        }
    }

    private final class FocusListenerWrapper extends IFocusListener.Stub {
        private final HalFocusListener mListener;

        FocusListenerWrapper(HalFocusListener halFocusListener) {
            mListener = halFocusListener;
        }

        @Override
        public void requestAudioFocus(String usage, int zoneId, int focusGain)
                throws RemoteException {
            @AttributeUsage int usageValue = stringToUsage(usage);
            mListener.requestAudioFocus(usageValue, zoneId, focusGain);
        }

        @Override
        public void abandonAudioFocus(String usage, int zoneId) throws RemoteException {
            @AttributeUsage int usageValue = stringToUsage(usage);
            mListener.abandonAudioFocus(usageValue, zoneId);
        }
    }

    // TODO(b/171572311): Move usageToString and stringToUsage to AudioAttributes
    private static String usageToString(@AttributeUsage int usage) {
        switch (usage) {
            case AudioAttributes.USAGE_UNKNOWN:
                return AudioUsage.AUDIO_USAGE_UNKNOWN.toString();
            case AudioAttributes.USAGE_MEDIA:
                return AudioUsage.AUDIO_USAGE_MEDIA.toString();
            case AudioAttributes.USAGE_VOICE_COMMUNICATION:
                return AudioUsage.AUDIO_USAGE_VOICE_COMMUNICATION.toString();
            case AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING:
                return AudioUsage.AUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING.toString();
            case AudioAttributes.USAGE_ALARM:
                return AudioUsage.AUDIO_USAGE_ALARM.toString();
            case AudioAttributes.USAGE_NOTIFICATION:
                return AudioUsage.AUDIO_USAGE_NOTIFICATION.toString();
            case AudioAttributes.USAGE_NOTIFICATION_RINGTONE:
                return AudioUsage.AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE.toString();
            case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY:
                return AudioUsage.AUDIO_USAGE_ASSISTANCE_ACCESSIBILITY.toString();
            case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
                return AudioUsage.AUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE.toString();
            case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION:
                return AudioUsage.AUDIO_USAGE_ASSISTANCE_SONIFICATION.toString();
            case AudioAttributes.USAGE_GAME:
                return AudioUsage.AUDIO_USAGE_GAME.toString();
            case AudioAttributes.USAGE_VIRTUAL_SOURCE:
                return AudioUsage.AUDIO_USAGE_VIRTUAL_SOURCE.toString();
            case AudioAttributes.USAGE_ASSISTANT:
                return AudioUsage.AUDIO_USAGE_ASSISTANT.toString();
            case AudioAttributes.USAGE_CALL_ASSISTANT:
                return AudioUsage.AUDIO_USAGE_CALL_ASSISTANT.toString();
            case AudioAttributes.USAGE_EMERGENCY:
                return AudioUsage.AUDIO_USAGE_EMERGENCY.toString();
            case AudioAttributes.USAGE_SAFETY:
                return AudioUsage.AUDIO_USAGE_SAFETY.toString();
            case AudioAttributes.USAGE_VEHICLE_STATUS:
                return AudioUsage.AUDIO_USAGE_VEHICLE_STATUS.toString();
            case AudioAttributes.USAGE_ANNOUNCEMENT:
                return AudioUsage.AUDIO_USAGE_ANNOUNCEMENT.toString();
            default:
                Log.w(TAG, "Unknown usage value " + usage);
                return AudioUsage.AUDIO_USAGE_UNKNOWN.toString();
        }
    }

    private static @AttributeUsage int stringToUsage(String usageName) {
        AudioUsage usage;
        try {
            usage = AudioUsage.valueOf(usageName);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Usage name not found in AudioUsage enum: " + usageName, e);
            return AudioAttributes.USAGE_UNKNOWN;
        }

        switch (usage) {
            case AUDIO_USAGE_UNKNOWN:
                return AudioAttributes.USAGE_UNKNOWN;
            case AUDIO_USAGE_MEDIA:
                return AudioAttributes.USAGE_MEDIA;
            case AUDIO_USAGE_VOICE_COMMUNICATION:
                return AudioAttributes.USAGE_VOICE_COMMUNICATION;
            case AUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING:
                return AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING;
            case AUDIO_USAGE_ALARM:
                return AudioAttributes.USAGE_ALARM;
            case AUDIO_USAGE_NOTIFICATION:
                return AudioAttributes.USAGE_NOTIFICATION;
            case AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE:
                return AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
            case AUDIO_USAGE_ASSISTANCE_ACCESSIBILITY:
                return AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY;
            case AUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
                return AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
            case AUDIO_USAGE_ASSISTANCE_SONIFICATION:
                return AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
            case AUDIO_USAGE_GAME:
                return AudioAttributes.USAGE_GAME;
            case AUDIO_USAGE_VIRTUAL_SOURCE:
                return AudioAttributes.USAGE_VIRTUAL_SOURCE;
            case AUDIO_USAGE_ASSISTANT:
                return AudioAttributes.USAGE_ASSISTANT;
            case AUDIO_USAGE_CALL_ASSISTANT:
                return AudioAttributes.USAGE_CALL_ASSISTANT;
            case AUDIO_USAGE_EMERGENCY:
                return AudioAttributes.USAGE_EMERGENCY;
            case AUDIO_USAGE_SAFETY:
                return AudioAttributes.USAGE_SAFETY;
            case AUDIO_USAGE_VEHICLE_STATUS:
                return AudioAttributes.USAGE_VEHICLE_STATUS;
            case AUDIO_USAGE_ANNOUNCEMENT:
                return AudioAttributes.USAGE_ANNOUNCEMENT;
            default:
                Log.w(TAG, "Mapping missing for usage " + usageName);
                return AudioAttributes.USAGE_UNKNOWN;
        }

    }
}
