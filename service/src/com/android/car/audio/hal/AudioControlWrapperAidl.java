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
import android.util.Slog;

import java.io.PrintWriter;
import java.util.Objects;

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
            Slog.d(TAG, "Registering focus listener on AudioControl HAL");
        }
        IFocusListener listenerWrapper = new FocusListenerWrapper(focusListener);
        try {
            mAudioControl.registerFocusListener(listenerWrapper);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register focus listener");
            throw new IllegalStateException("IAudioControl#registerFocusListener failed", e);
        }
        mListenerRegistered = true;
    }

    @Override
    public void onAudioFocusChange(@AttributeUsage int usage, int zoneId, int focusChange) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "onAudioFocusChange: usage " + AudioAttributes.usageToString(usage)
                    + ", zoneId " + zoneId + ", focusChange " + focusChange);
        }
        try {
            String usageName = AudioAttributes.usageToXsdString(usage);
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
            Slog.e(TAG, "setFadeTowardFront with " + value + " failed", e);
        }
    }

    @Override
    public void setBalanceTowardRight(float value) {
        try {
            mAudioControl.setBalanceTowardRight(value);
        } catch (RemoteException e) {
            Slog.e(TAG, "setBalanceTowardRight with " + value + " failed", e);
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
        Slog.w(TAG, "AudioControl HAL died. Fetching new handle");
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
            @AttributeUsage int usageValue = AudioAttributes.xsdStringToUsage(usage);
            mListener.requestAudioFocus(usageValue, zoneId, focusGain);
        }

        @Override
        public void abandonAudioFocus(String usage, int zoneId) throws RemoteException {
            @AttributeUsage int usageValue = AudioAttributes.xsdStringToUsage(usage);
            mListener.abandonAudioFocus(usageValue, zoneId);
        }
    }
}
