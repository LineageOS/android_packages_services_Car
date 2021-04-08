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

package android.car.telemetry;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.annotation.RequiredFeature;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

/**
 * Provides an application interface for interacting with the Car Telemetry Service.
 *
 * @hide
 */
@RequiredFeature(Car.CAR_TELEMETRY_SERVICE)
@SystemApi
public final class CarTelemetryManager extends CarManagerBase {

    private static final boolean DEBUG = false;
    private static final String TAG = CarTelemetryManager.class.getSimpleName();

    private final CarTelemetryServiceListener mCarTelemetryServiceListener =
            new CarTelemetryServiceListener(this);
    private final ICarTelemetryService mService;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private CarTelemetryResultsListener mResultsListener;
    @GuardedBy("mLock")
    private Executor mExecutor;

    /**
     * Application registers {@link CarTelemetryResultsListener} object to receive data from
     * {@link com.android.car.telemetry.CarTelemetryService}.
     *
     * @hide
     */
    @SystemApi
    public interface CarTelemetryResultsListener {
        /**
         * Called by {@link com.android.car.telemetry.CarTelemetryService} to send data to
         * the client.
         * TODO(b/184964661): Publish the documentation for the format of the results.
         *
         * @param data the serialized car telemetry results.
         */
        void onDataReceived(@NonNull byte[] data);
    }

    /**
     * Class implementing the listener interface
     * {@link com.android.car.ICarTelemetryServiceListener} to receive telemetry results.
     */
    private static final class CarTelemetryServiceListener
            extends ICarTelemetryServiceListener.Stub {
        private WeakReference<CarTelemetryManager> mManager;

        private CarTelemetryServiceListener(CarTelemetryManager manager) {
            mManager = new WeakReference<>(manager);
        }

        @Override
        public void onDataReceived(@NonNull byte[] data) {
            CarTelemetryManager manager = mManager.get();
            if (manager == null) {
                return;
            }
            manager.onDataReceived(data);
        }
    }

    private void onDataReceived(byte[] data) {
        synchronized (mLock) {
            mExecutor.execute(() -> mResultsListener.onDataReceived(data));
        }
    }

    /**
     * Gets an instance of CarTelemetryManager.
     *
     * CarTelemetryManager manages {@link com.android.car.telemetry.CarTelemetryService} and
     * provides APIs so the client can use the car telemetry service.
     *
     * There is only one client to this manager, which is OEM's cloud application. It uses the
     * APIs to send config to and receive data from CarTelemetryService.
     *
     * @hide
     */
    public CarTelemetryManager(Car car, IBinder service) {
        super(car);
        mService = ICarTelemetryService.Stub.asInterface(service);
        if (DEBUG) {
            Slog.d(TAG, "starting car telemetry manager");
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        synchronized (mLock) {
            mResultsListener = null;
            mExecutor = null;
        }
    }

    /**
     * Registers a listener with {@link com.android.car.telemetry.CarTelemetryService} for client
     * to receive script execution results.
     *
     * @param listener to received data from {@link com.android.car.telemetry.CarTelemetryService}.
     * @throws IllegalStateException if the listener is already set.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void setListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull CarTelemetryResultsListener listener) {
        synchronized (mLock) {
            if (mResultsListener != null) {
                throw new IllegalStateException(
                        "Attempting to set a listener that is already set.");
            }
            mExecutor = executor;
            mResultsListener = listener;
        }
        try {
            mService.setListener(mCarTelemetryServiceListener);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Unregisters the listener from {@link com.android.car.telemetry.CarTelemetryService}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void clearListener() {
        synchronized (mLock) {
            mResultsListener = null;
            mExecutor = null;
        }
        try {
            mService.clearListener();
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }
}
