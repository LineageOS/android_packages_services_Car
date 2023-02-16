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

import android.annotation.NonNull;
import android.car.builtin.util.Slogf;
import android.car.media.CarVolumeGroupEvent;
import android.car.media.ICarVolumeEventCallback;
import android.os.RemoteException;

import com.android.car.BinderInterfaceContainer;
import com.android.car.CarLog;

import java.util.List;
import java.util.Objects;

/**
 * Manages callbacks for volume events
 */
final class CarVolumeEventHandler {
    private final BinderInterfaceContainer<ICarVolumeEventCallback> mVolumeEventCallbackContainer =
            new BinderInterfaceContainer<>();

    void release() {
        mVolumeEventCallbackContainer.clear();
    }

    public void registerCarVolumeEventCallback(ICarVolumeEventCallback callback) {
        Objects.requireNonNull(callback, "Car volume  callback can not be null");
        mVolumeEventCallbackContainer.addBinder(callback);
    }

    public void unregisterCarVolumeEventCallback(@NonNull ICarVolumeEventCallback callback) {
        Objects.requireNonNull(callback, "Car volume  callback can not be null");
        mVolumeEventCallbackContainer.removeBinder(callback);
    }

    void onVolumeGroupEvent(List<CarVolumeGroupEvent> events) {
        for (BinderInterfaceContainer.BinderInterface<ICarVolumeEventCallback> callback :
                mVolumeEventCallbackContainer.getInterfaces()) {
            try {
                callback.binderInterface.onVolumeGroupEvent(List.copyOf(events));
            } catch (RemoteException e) {
                Slogf.e(CarLog.TAG_AUDIO, "Failed to callback onVolumeGroupEvent", e);
            }
        }
    }

    void onMasterMuteChanged(int zoneId, int flags) {
        for (BinderInterfaceContainer.BinderInterface<ICarVolumeEventCallback> callback :
                mVolumeEventCallbackContainer.getInterfaces()) {
            try {
                callback.binderInterface.onMasterMuteChanged(zoneId, flags);
            } catch (RemoteException e) {
                Slogf.e(CarLog.TAG_AUDIO, "Failed to callback onMasterMuteChanged", e);
            }
        }
    }
}
