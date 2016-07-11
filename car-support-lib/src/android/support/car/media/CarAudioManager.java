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
package android.support.car.media;

import android.Manifest;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.support.annotation.IntDef;
import android.support.annotation.RequiresPermission;
import android.support.car.CarManagerBase;
import android.support.car.CarNotConnectedException;
import android.support.car.CarNotSupportedException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * APIs for handling car specific audio stuffs.
 */
public abstract class CarAudioManager implements CarManagerBase {

    /**
     * Audio usage for unspecified type.
     */
    public static final int CAR_AUDIO_USAGE_DEFAULT = 0;
    /**
     * Audio usage for playing music.
     */
    public static final int CAR_AUDIO_USAGE_MUSIC = 1;
    /**
     * Audio usage for H/W radio.
     */
    public static final int CAR_AUDIO_USAGE_RADIO = 2;
    /**
     * Audio usage for playing navigation guidance.
     */
    public static final int CAR_AUDIO_USAGE_NAVIGATION_GUIDANCE = 3;
    /**
     * Audio usage for voice call
     */
    public static final int CAR_AUDIO_USAGE_VOICE_CALL = 4;
    /**
     * Audio usage for voice search or voice command.
     */
    public static final int CAR_AUDIO_USAGE_VOICE_COMMAND = 5;
    /**
     * Audio usage for playing alarm.
     */
    public static final int CAR_AUDIO_USAGE_ALARM = 6;
    /**
     * Audio usage for notification sound.
     */
    public static final int CAR_AUDIO_USAGE_NOTIFICATION = 7;
    /**
     * Audio usage for system sound like UI feedback.
     */
    public static final int CAR_AUDIO_USAGE_SYSTEM_SOUND = 8;
    /**
     * Audio usage for playing safety alert.
     */
    public static final int CAR_AUDIO_USAGE_SYSTEM_SAFETY_ALERT = 9;

    /** @hide */
    public static final int CAR_AUDIO_USAGE_MAX = CAR_AUDIO_USAGE_SYSTEM_SAFETY_ALERT;

    /** @hide */
    @IntDef({CAR_AUDIO_USAGE_DEFAULT, CAR_AUDIO_USAGE_MUSIC, CAR_AUDIO_USAGE_RADIO,
        CAR_AUDIO_USAGE_NAVIGATION_GUIDANCE, CAR_AUDIO_USAGE_VOICE_CALL,
        CAR_AUDIO_USAGE_VOICE_COMMAND, CAR_AUDIO_USAGE_ALARM, CAR_AUDIO_USAGE_NOTIFICATION,
        CAR_AUDIO_USAGE_SYSTEM_SOUND, CAR_AUDIO_USAGE_SYSTEM_SAFETY_ALERT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CarAudioUsage {}

    /**
     * Get {@link AudioAttrbutes} relevant for the given usage in car.
     * @param carUsage
     * @return
     */
    public abstract AudioAttributes getAudioAttributesForCarUsage(@CarAudioUsage int carUsage);

    /**
     * Request audio focus.
     * Send a request to obtain the audio focus.
     * @param l
     * @param requestAttributes
     * @param durationHint
     * @param flags
     */
    public abstract int requestAudioFocus(OnAudioFocusChangeListener l,
                                          AudioAttributes requestAttributes,
                                          int durationHint,
                                          int flags) throws IllegalArgumentException;
    /**
     * Abandon audio focus. Causes the previous focus owner, if any, to receive focus.
     * @param l
     * @param aa
     * @return {@link #AUDIOFOCUS_REQUEST_FAILED} or {@link #AUDIOFOCUS_REQUEST_GRANTED}
     */
    public abstract int abandonAudioFocus(OnAudioFocusChangeListener l, AudioAttributes aa);

    /**
     * Get {@link AudioFormat} for audio record.
     * @return {@link AudioFormat} for audio record.
     */
    public abstract AudioFormat getAudioRecordAudioFormat();

    /**
     * Get minimum buffer size for {@link CarAudioRecord}.
     *
     * @return buffer size in bytes.
     */
    public abstract int getAudioRecordMinBufferSize()
            throws CarNotConnectedException, CarNotSupportedException;

    /**
     * Get maximum buffer size for {@link CarAudioRecord}.
     *
     * @return buffer size in bytes.
     */
    public abstract int getAudioRecordMaxBufferSize()
            throws CarNotConnectedException, CarNotSupportedException;

    /**
     * Create a {@link CarAudioRecord} for the current {@link CarAudioManager}. There can be
     * multiple instances of {@link CarAudioRecord}. This requires {@link
     * android.Manifest.permission#RECORD_AUDIO} permission.
     *
     * @param bufferSize It should be a multiple of minimum buffer size acquired from {@link
     * #getAudioRecordMinBufferSize()}. This cannot exceed {@link #getAudioRecordMaxBufferSize()}.
     *
     * @return {@link CarAudioRecord} instance for the given stream.
     * @throws IllegalArgumentException if passed parameter like bufferSize is wrong.
     * @throws SecurityException if client does not have
     * {@link android.Manifest.permission#RECORD_AUDIO}
     * permission.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public abstract CarAudioRecord createCarAudioRecord(int bufferSize)
            throws SecurityException, CarNotConnectedException, CarNotSupportedException;

    /**
     * Check if media audio is muted or not. This will include music and radio. Any application
     * taking audio focus for media stream will get it out of mute state.
     *
     * @return {@code true} if media is muted.
     */
    public abstract boolean isMediaMuted() throws CarNotConnectedException;

    /**
     * Mute or unmute media stream including radio. This can involve audio focus change to stop
     * whatever app holding audio focus now. If requester is currently holding audio focus,
     * it will get LOSS_TRANSIENT focus loss.
     * This API requires {@link android.car.Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @param mute {@code true} if media stream should be muted.
     * @return Mute state of system after the request. Note that mute request can fail if there
     *         is higher priority audio already being played like phone call.
     * @hide
     */
    public abstract boolean setMediaMute(boolean mute) throws CarNotConnectedException;
}
