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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.car.CarLibLog;
import android.car.CarManagerBase;
import android.car.CarNotConnectedException;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

/**
 * APIs for handling car specific audio stuff.
 */
public final class CarAudioManager implements CarManagerBase {

    // The trailing slash forms a directory-liked hierarchy and
    // allows listening for both VOLUME/MEDIA and VOLUME/NAVIGATION.
    private static final String VOLUME_SETTINGS_KEY_URI_PREFIX = "android.car.VOLUME/";

    /**
     * @param busNumber The physical bus address number
     * @return Key to persist volume index in {@link Settings.Global}
     */
    public static String getVolumeSettingsKeyForBus(int busNumber) {
        return VOLUME_SETTINGS_KEY_URI_PREFIX + busNumber;
    }

    private final ContentResolver mContentResolver;
    private final ICarAudio mService;

    /**
     * Registers a {@link ContentObserver} to listen for audio usage volume changes.
     *
     * {@link ContentObserver#onChange(boolean)} will be called on every audio usage volume change.
     *
     * @param observer The {@link ContentObserver} instance to register, non-null
     */
    @SystemApi
    public void registerVolumeChangeObserver(@NonNull ContentObserver observer) {
        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(VOLUME_SETTINGS_KEY_URI_PREFIX),
                true, observer);
    }

    /**
     * Unregisters the {@link ContentObserver} which listens for audio usage volume changes.
     *
     * @param observer The {@link ContentObserver} instance to unregister, non-null
     */
    @SystemApi
    public void unregisterVolumeChangeObserver(@NonNull ContentObserver observer) {
        mContentResolver.unregisterContentObserver(observer);
    }

    /**
     * Sets the volume index for an {@link AudioAttributes} usage.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @param usage The {@link AudioAttributes} usage whose volume index should be set.
     * @param index The volume index to set. See
     *            {@link #getUsageMaxVolume(int)} for the largest valid value.
     * @param flags One or more flags (e.g., {@link android.media.AudioManager#FLAG_SHOW_UI},
     *              {@link android.media.AudioManager#FLAG_PLAY_SOUND})
     */
    @SystemApi
    public void setUsageVolume(@AudioAttributes.AttributeUsage int usage, int index, int flags)
            throws CarNotConnectedException {
        try {
            mService.setUsageVolume(usage, index, flags);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "setUsageVolume failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Returns the maximum volume index for an {@link AudioAttributes} usage.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @param usage The {@link AudioAttributes} usage
     *                       whose maximum volume index is returned.
     * @return The maximum valid volume index for the usage.
     */
    @SystemApi
    public int getUsageMaxVolume(@AudioAttributes.AttributeUsage int usage)
            throws CarNotConnectedException {
        try {
            return mService.getUsageMaxVolume(usage);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "getUsageMaxVolume failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Returns the minimum volume index for an {@link AudioAttributes} usage.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @param usage The {@link AudioAttributes} usage
     *                       whose minimum volume index is returned.
     * @return The minimum valid volume index for the usage, non-negative
     */
    @SystemApi
    public int getUsageMinVolume(@AudioAttributes.AttributeUsage int usage)
            throws CarNotConnectedException {
        try {
            return mService.getUsageMinVolume(usage);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "getUsageMinVolume failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Returns the current volume index for an {@link AudioAttributes} usage.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @param usage The {@link AudioAttributes} usage whose volume index is returned.
     * @return The current volume index for the usage.
     *
     * @see #getUsageMaxVolume(int)
     * @see #setUsageVolume(int, int, int)
     */
    @SystemApi
    public int getUsageVolume(@AudioAttributes.AttributeUsage int usage)
            throws CarNotConnectedException {
        try {
            return mService.getUsageVolume(usage);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "getUsageVolume failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Adjust the relative volume in the front vs back of the vehicle cabin.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @param value in the range -1.0 to 1.0 for fully toward the back through
     *              fully toward the front.  0.0 means evenly balanced.
     *
     * @see #setBalanceTowardRight(float)
     */
    @SystemApi
    public void setFadeTowardFront(float value) throws CarNotConnectedException {
        try {
            mService.setFadeTowardFront(value);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "setFadeTowardFront failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Adjust the relative volume on the left vs right side of the vehicle cabin.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_VOLUME} permission.
     *
     * @param value in the range -1.0 to 1.0 for fully toward the left through
     *              fully toward the right.  0.0 means evenly balanced.
     *
     * @see #setFadeTowardFront(float)
     */
    @SystemApi
    public void setBalanceTowardRight(float value) throws CarNotConnectedException {
        try {
            mService.setBalanceTowardRight(value);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "setBalanceTowardRight failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Queries the system configuration in order to report the available, non-microphone audio
     * input devices.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_SETTINGS} permission.
     *
     * @return An array of strings representing input ports.
     *
     * @see #createAudioPatch(String, int, int)
     * @see #releaseAudioPatch(CarAudioPatchHandle)
     */
    @SystemApi
    public String[] getExternalSources() throws CarNotConnectedException {
        try {
            return mService.getExternalSources();
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "getExternalSources failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Given an input port identified by getExternalSources(), request that it's audio signal
     * be routed below the HAL to the output port associated with the given usage.  For example,
     * The output of a tuner might be routed directly to the output buss associated with
     * AudioAttributes.USAGE_MEDIA while the tuner is playing.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_SETTINGS} permission.
     *
     * @param sourceName the input port name obtained from getExternalSources().
     * @param usage the type of audio represented by this source (usually USAGE_MEDIA).
     * @param gainIndex How many steps above the minimum value defined for the source port to
     *                  set the gain when creating the patch.
     *                  This may be used for source balancing without affecting the user controlled
     *                  volumes applied to the destination ports.  A value of -1 may be passed
     *                  to indicate no gain change is requested.
     * @return A handle for the created patch which can be used to later remove it.
     *
     * @see #getExternalSources()
     * @see #releaseAudioPatch(CarAudioPatchHandle)
     */
    @SystemApi
    public CarAudioPatchHandle createAudioPatch(String sourceName, int usage, int gainIndex)
            throws CarNotConnectedException {
        try {
            return mService.createAudioPatch(sourceName, usage, gainIndex);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "createAudioPatch failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Removes the association between an input port and an output port identified by the provided
     * handle.
     *
     * Requires {@link android.car.Car#PERMISSION_CAR_CONTROL_AUDIO_SETTINGS} permission.
     *
     * @param patch CarAudioPatchHandle returned from createAudioPatch().
     *
     * @see #getExternalSources()
     * @see #createAudioPatch(String, int, int)
     */
    @SystemApi
    public void releaseAudioPatch(CarAudioPatchHandle patch) throws CarNotConnectedException {
        try {
            mService.releaseAudioPatch(patch);
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "releaseAudioPatch failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Gets the available volume groups in the system.
     *
     * @return Array of {@link CarVolumeGroup}
     */
    @SystemApi
    public @NonNull CarVolumeGroup[] getVolumeGroups() throws CarNotConnectedException {
        try {
            return mService.getVolumeGroups();
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "getContextGroups failed", e);
            throw new CarNotConnectedException(e);
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
    }

    /** @hide */
    public CarAudioManager(IBinder service, Context context, Handler handler) {
        mContentResolver = context.getContentResolver();
        mService = ICarAudio.Stub.asInterface(service);
    }
}
