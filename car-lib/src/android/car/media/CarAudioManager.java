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
package android.car.media;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.car.CarLibLog;
import android.car.CarManagerBase;
import android.car.CarNotConnectedException;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.IVolumeController;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * APIs for handling car specific audio stuff.
 */
public final class CarAudioManager implements CarManagerBase {

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
    /**
     * Audio usage for the ringing of a phone call.
     */
    public static final int CAR_AUDIO_USAGE_RINGTONE = 10;
    /**
     * Audio usage for external audio usage.
     * @hide
     */
    public static final int CAR_AUDIO_USAGE_EXTERNAL_AUDIO_SOURCE = 11;

    /** @hide */
    public static final int CAR_AUDIO_USAGE_MAX = CAR_AUDIO_USAGE_EXTERNAL_AUDIO_SOURCE;

    /** @hide */
    @IntDef({CAR_AUDIO_USAGE_DEFAULT, CAR_AUDIO_USAGE_MUSIC, CAR_AUDIO_USAGE_RADIO,
        CAR_AUDIO_USAGE_NAVIGATION_GUIDANCE, CAR_AUDIO_USAGE_VOICE_CALL,
        CAR_AUDIO_USAGE_VOICE_COMMAND, CAR_AUDIO_USAGE_ALARM, CAR_AUDIO_USAGE_NOTIFICATION,
        CAR_AUDIO_USAGE_SYSTEM_SOUND, CAR_AUDIO_USAGE_SYSTEM_SAFETY_ALERT,
        CAR_AUDIO_USAGE_EXTERNAL_AUDIO_SOURCE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CarAudioUsage {}

    /** @hide */
    public static final String CAR_RADIO_TYPE_AM_FM = "RADIO_AM_FM";
    /** @hide */
    public static final String CAR_RADIO_TYPE_AM_FM_HD = "RADIO_AM_FM_HD";
    /** @hide */
    public static final String CAR_RADIO_TYPE_DAB = "RADIO_DAB";
    /** @hide */
    public static final String CAR_RADIO_TYPE_SATELLITE = "RADIO_SATELLITE";

    /** @hide */
    public static final String CAR_EXTERNAL_SOURCE_TYPE_CD_DVD = "CD_DVD";
    /** @hide */
    public static final String CAR_EXTERNAL_SOURCE_TYPE_AUX_IN0 = "AUX_IN0";
    /** @hide */
    public static final String CAR_EXTERNAL_SOURCE_TYPE_AUX_IN1 = "AUX_IN1";
    /** @hide */
    public static final String CAR_EXTERNAL_SOURCE_TYPE_EXT_NAV_GUIDANCE = "EXT_NAV_GUIDANCE";
    /** @hide */
    public static final String CAR_EXTERNAL_SOURCE_TYPE_EXT_VOICE_CALL = "EXT_VOICE_CALL";
    /** @hide */
    public static final String CAR_EXTERNAL_SOURCE_TYPE_EXT_VOICE_COMMAND = "EXT_VOICE_COMMAND";
    /** @hide */
    public static final String CAR_EXTERNAL_SOURCE_TYPE_EXT_SAFETY_ALERT = "EXT_SAFETY_ALERT";

    private final ICarAudio mService;

    /**
     * Get {@link AudioAttributes} relevant for the given usage in car.
     * @param carUsage
     * @return
     */
    public AudioAttributes getAudioAttributesForCarUsage(@CarAudioUsage int carUsage)
            throws CarNotConnectedException {
        try {
            return mService.getAudioAttributesForCarUsage(carUsage);
        } catch (RemoteException e) {
            throw new CarNotConnectedException();
        }
    }

    /**
     * Sets the volume index for a particular usage.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @param carUsage The car audio usage whose volume index should be set.
     * @param index The volume index to set. See
     *            {@link #getUsageMaxVolume(int)} for the largest valid value.
     * @param flags One or more flags (e.g., {@link android.media.AudioManager#FLAG_SHOW_UI},
     *              {@link android.media.AudioManager#FLAG_PLAY_SOUND})
     */
    @SystemApi
    public void setUsageVolume(@CarAudioUsage int carUsage, int index, int flags)
            throws CarNotConnectedException {
        try {
            mService.setUsageVolume(carUsage, index, flags);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "setUsageVolume failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Registers a global volume controller interface.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @hide
     */
    @SystemApi
    public void setVolumeController(IVolumeController controller)
            throws CarNotConnectedException {
        try {
            mService.setVolumeController(controller);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "setVolumeController failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Returns the maximum volume index for a particular usage.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @param carUsage The car audio usage whose maximum volume index is returned.
     * @return The maximum valid volume index for the usage.
     */
    @SystemApi
    public int getUsageMaxVolume(@CarAudioUsage int carUsage) throws CarNotConnectedException {
        try {
            return mService.getUsageMaxVolume(carUsage);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "getUsageMaxVolume failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Returns the minimum volume index for a particular usage.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @param carUsage The car audio usage whose maximum volume index is returned.
     * @return The maximum valid volume index for the usage.
     */
    @SystemApi
    public int getUsageMinVolume(@CarAudioUsage int carUsage) throws CarNotConnectedException {
        try {
            return mService.getUsageMinVolume(carUsage);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "getUsageMinVolume failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Returns the current volume index for a particular usage.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @param carUsage The car audio usage whose volume index is returned.
     * @return The current volume index for the usage.
     *
     * @see #getUsageMaxVolume(int)
     * @see #setUsageVolume(int, int, int)
     */
    @SystemApi
    public int getUsageVolume(@CarAudioUsage int carUsage) throws CarNotConnectedException {
        try {
            return mService.getUsageVolume(carUsage);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "getUsageVolume failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
    }

    /** @hide */
    public CarAudioManager(IBinder service, Context context, Handler handler) {
        mService = ICarAudio.Stub.asInterface(service);
    }
}
