/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.telemetry.publisher;

import android.annotation.Nullable;
import android.automotive.telemetry.internal.CarDataInternal;
import android.automotive.telemetry.internal.ICarDataListener;
import android.automotive.telemetry.internal.ICarTelemetryInternal;
import android.car.builtin.os.ServiceManagerHelper;
import android.car.builtin.util.Slog;
import android.car.builtin.util.Slogf;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.automotive.telemetry.CarDataProto;
import com.android.car.CarLog;
import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.databroker.DataSubscriber;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;

/**
 * Publisher for cartelemtryd service (aka ICarTelemetry).
 *
 * <p>When a subscriber is added, the publisher binds to ICarTelemetryInternal and starts listening
 * for incoming CarData. The matching CarData will be pushed to the subscriber. It unbinds itself
 * from ICarTelemetryInternal if there are no subscribers.
 *
 * <p>See {@code packages/services/Car/cpp/telemetry/cartelemetryd} to learn more about the service.
 */
public class CarTelemetrydPublisher extends AbstractPublisher {
    private static final boolean DEBUG = false;  // STOPSHIP if true
    private static final String SERVICE_NAME = ICarTelemetryInternal.DESCRIPTOR + "/default";
    private static final int BINDER_FLAGS = 0;

    private final Object mLock = new Object();

    @Nullable
    @GuardedBy("mLock")
    private ICarTelemetryInternal mCarTelemetryInternal;

    @GuardedBy("mLock")
    private final ArrayList<DataSubscriber> mSubscribers = new ArrayList<>();

    private final ICarDataListener mListener = new ICarDataListener.Stub() {
        @Override
        public void onCarDataReceived(CarDataInternal[] dataList) throws RemoteException {
            if (DEBUG) {
                Slog.d(CarLog.TAG_TELEMETRY,
                        "Received " + dataList.length + " CarData from cartelemetryd");
            }
            onCarDataListReceived(dataList);
        }
    };

    /** Called when binder for ICarTelemetry service is died. */
    void onBinderDied() {
        synchronized (mLock) {
            if (mCarTelemetryInternal != null) {
                mCarTelemetryInternal.asBinder().unlinkToDeath(this::onBinderDied, BINDER_FLAGS);
            }
            // TODO(b/193680465): try reconnecting again
            mCarTelemetryInternal = null;
        }
    }

    /**
     * Returns true if connected, false if not connected.
     *
     * @throws IllegalStateException if it cannot connect to ICarTelemetryInternal service.
     */
    @GuardedBy("mLock")
    private void connectToCarTelemetrydLocked() {
        if (mCarTelemetryInternal != null) {
            return;  // already connected
        }
        IBinder binder = ServiceManagerHelper.checkService(SERVICE_NAME);
        if (binder == null) {
            throw new IllegalStateException(
                    "Failed to connect to ICarTelemetryInternal: service is not ready");
        }
        try {
            binder.linkToDeath(this::onBinderDied, BINDER_FLAGS);
        } catch (RemoteException e) {
            throw new IllegalStateException(
                    "Failed to connect to ICarTelemetryInternal: linkToDeath failed", e);
        }
        mCarTelemetryInternal = ICarTelemetryInternal.Stub.asInterface(binder);
        try {
            mCarTelemetryInternal.setListener(mListener);
        } catch (RemoteException e) {
            binder.unlinkToDeath(this::onBinderDied, BINDER_FLAGS);
            mCarTelemetryInternal = null;
            throw new IllegalStateException(
                    "Failed to connect to ICarTelemetryInternal: Cannot set CarData listener",
                    e);
        }
    }

    @VisibleForTesting
    boolean isConnectedToCarTelemetryd() {
        synchronized (mLock) {
            return mCarTelemetryInternal != null;
        }
    }

    @Override
    public void addDataSubscriber(DataSubscriber subscriber) {
        TelemetryProto.Publisher publisherParam = subscriber.getPublisherParam();
        Preconditions.checkArgument(
                publisherParam.getPublisherCase()
                        == TelemetryProto.Publisher.PublisherCase.CARTELEMETRYD,
                "Subscribers only with CarTelemetryd publisher are supported by this class.");
        int carDataId = publisherParam.getCartelemetryd().getId();
        CarDataProto.CarData.PushedCase carDataCase =
                CarDataProto.CarData.PushedCase.forNumber(carDataId);
        Preconditions.checkArgument(
                carDataCase != null
                        && carDataCase != CarDataProto.CarData.PushedCase.PUSHED_NOT_SET,
                "Invalid CarData ID " + carDataId
                        + ". Please see CarData.proto for the list of available IDs.");

        synchronized (mLock) {
            try {
                connectToCarTelemetrydLocked();
            } catch (IllegalStateException e) {
                Slog.e(CarLog.TAG_TELEMETRY, "Failed to connect to ICarTelemetry", e);
                // TODO(b/193680465): add retry reconnecting
            }
            mSubscribers.add(subscriber);
        }

        Slogf.d(CarLog.TAG_TELEMETRY, "Subscribing to CarDat.id=%d", carDataId);
    }

    @Override
    public void removeDataSubscriber(DataSubscriber subscriber) {
        // TODO(b/189142577): implement and disconnect from cartelemetryd if necessary
    }

    @Override
    public void removeAllDataSubscribers() {
        // TODO(b/189142577): implement and disconnect from cartelemetryd
    }

    @Override
    public boolean hasDataSubscriber(DataSubscriber subscriber) {
        synchronized (mLock) {
            return mSubscribers.contains(subscriber);
        }
    }

    /**
     * Called when publisher receives new car data list. It's executed on a Binder thread.
     */
    private void onCarDataListReceived(CarDataInternal[] dataList) {
        // TODO(b/189142577): implement
    }
}
