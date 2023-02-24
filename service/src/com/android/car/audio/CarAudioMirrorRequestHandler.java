/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.Nullable;
import android.car.builtin.util.Slogf;
import android.car.media.CarAudioManager;
import android.car.media.IAudioZonesMirrorStatusCallback;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/**
 * Managed the car audio mirror request
 */
/* package */ final class CarAudioMirrorRequestHandler {
    private static final String TAG = CarLog.TAG_AUDIO;

    private static final String REQUEST_HANDLER_THREAD_NAME = "CarAudioMirrorRequest";

    private final HandlerThread mHandlerThread = CarServiceUtils.getHandlerThread(
            REQUEST_HANDLER_THREAD_NAME);
    private final Handler mHandler = new Handler(mHandlerThread.getLooper());

    private final Object mLock = new Object();

    // Lock not needed as the callback is only broadcast inside the handler's thread
    // If this changes then the lock may be needed to prevent concurrent calls to
    // mAudioZonesMirrorStatusCallbacks.beginBroadcast
    private final RemoteCallbackList<IAudioZonesMirrorStatusCallback>
            mAudioZonesMirrorStatusCallbacks = new RemoteCallbackList<>();
    @GuardedBy("mLock")
    private String mMirrorDeviceAddress;
    @GuardedBy("mLock")
    private final SparseArray<int[]> mZonesToMirrorConfigurations = new SparseArray<>();

    boolean registerAudioZonesMirrorStatusCallback(
            IAudioZonesMirrorStatusCallback callback) {
        Objects.requireNonNull(callback, "Audio zones mirror status callback can not be null");

        if (!isMirrorAudioEnabled()) {
            Slogf.w(TAG, "Could not register audio mirror status callback, mirroring not enabled");
            return false;
        }

        return mAudioZonesMirrorStatusCallbacks.register(callback);
    }

    boolean unregisterAudioZonesMirrorStatusCallback(IAudioZonesMirrorStatusCallback callback) {
        Objects.requireNonNull(callback, "Audio zones mirror status callback can not be null");

        return mAudioZonesMirrorStatusCallbacks.unregister(callback);
    }

    boolean isMirrorAudioEnabled() {
        synchronized (mLock) {
            return mMirrorDeviceAddress != null;
        }
    }

    void setMirrorDeviceAddress(String deviceAddress) {
        Objects.requireNonNull(deviceAddress, "Mirror device address can not be null");

        synchronized (mLock) {
            mMirrorDeviceAddress = deviceAddress;
        }
    }

    void enableMirrorForZones(int[] audioZones) {
        Objects.requireNonNull(audioZones, "Mirror audio zones can not be null");
        synchronized (mLock) {
            for (int index = 0; index < audioZones.length; index++) {
                mZonesToMirrorConfigurations.put(audioZones[index], audioZones);
            }
        }
        mHandler.post(() ->
                handleInformCallbacks(audioZones, CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED));
    }

    private void handleInformCallbacks(int[] audioZones, int status) {
        int n = mAudioZonesMirrorStatusCallbacks.beginBroadcast();
        for (int c = 0; c < n; c++) {
            IAudioZonesMirrorStatusCallback callback =
                    mAudioZonesMirrorStatusCallbacks.getBroadcastItem(c);
            try {
                // Calling binder inside lock here since the call is one way and doest not block.
                // The lock is needed to prevent concurrent beginBroadcast
                callback.onAudioZonesMirrorStatusChanged(audioZones, status);
            } catch (RemoteException e) {
                Slogf.e(TAG, e, "Could not inform mirror status callback index %d of total %d",
                        c, n);
            }

        }
        mAudioZonesMirrorStatusCallbacks.finishBroadcast();
    }

    @Nullable
    int[] getMirrorAudioZonesForAudioZone(int audioZoneId) {
        synchronized (mLock) {
            return mZonesToMirrorConfigurations.get(audioZoneId, /* valueIfNotFound= */ null);
        }
    }

    boolean isMirrorEnabledForZone(int zoneId) {
        synchronized (mLock) {
            return mZonesToMirrorConfigurations.contains(zoneId);
        }
    }

    void rejectMirrorForZones(int[] audioZones) {
        removeZones(audioZones);
        mHandler.post(() ->
                handleInformCallbacks(audioZones, CarAudioManager.AUDIO_REQUEST_STATUS_REJECTED));
    }

    void cancelMirrorForZones(int[] audioZones) {
        Objects.requireNonNull(audioZones, "Mirror audio zones can not be null");
        removeZones(audioZones);
        mHandler.post(() ->
                handleInformCallbacks(audioZones, CarAudioManager.AUDIO_REQUEST_STATUS_CANCELLED));
    }

    private void removeZones(int[] audioZones) {
        synchronized (mLock) {
            for (int index = 0; index < audioZones.length; index++) {
                mZonesToMirrorConfigurations.remove(audioZones[index]);
            }
        }
    }
}
