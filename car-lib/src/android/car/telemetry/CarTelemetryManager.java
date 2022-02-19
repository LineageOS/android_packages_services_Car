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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.annotation.RequiredFeature;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

/**
 * Provides an application interface for interacting with the Car Telemetry Service.
 *
 * @hide
 */
@RequiredFeature(Car.CAR_TELEMETRY_SERVICE)
public final class CarTelemetryManager extends CarManagerBase {

    private static final boolean DEBUG = false;
    private static final String TAG = CarTelemetryManager.class.getSimpleName();
    private static final int METRICS_CONFIG_MAX_SIZE_BYTES = 10 * 1024; // 10 kb

    private final CarTelemetryServiceListener mCarTelemetryServiceListener =
            new CarTelemetryServiceListener(this);
    private final ICarTelemetryService mService;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private CarTelemetryResultsListener mResultsListener;
    @GuardedBy("mLock")
    private Executor mExecutor;

    /**
     * Status to indicate that MetricsConfig was added successfully.
     */
    public static final int STATUS_METRICS_CONFIG_SUCCESS = 0;

    /**
     * Status to indicate that add MetricsConfig failed because the same MetricsConfig of the same
     * name and version already exists.
     */
    public static final int STATUS_METRICS_CONFIG_ALREADY_EXISTS = 1;

    /**
     * Status to indicate that add MetricsConfig failed because a newer version of the MetricsConfig
     * exists.
     */
    public static final int STATUS_METRICS_CONFIG_VERSION_TOO_OLD = 2;

    /**
     * Status to indicate that add MetricsConfig failed because CarTelemetryService is unable to
     * parse the given byte array into a MetricsConfig.
     */
    public static final int STATUS_METRICS_CONFIG_PARSE_FAILED = 3;

    /**
     * Status to indicate that add MetricsConfig failed because of failure to verify the signature
     * of the MetricsConfig.
     */
    public static final int STATUS_METRICS_CONFIG_SIGNATURE_VERIFICATION_FAILED = 4;

    /**
     * Status to indicate that add MetricsConfig failed because of a general error in cars.
     */
    public static final int STATUS_METRICS_CONFIG_UNKNOWN = 5;

    /** @hide */
    @IntDef(prefix = {"STATUS_METRICS_CONFIG_"}, value = {
            STATUS_METRICS_CONFIG_SUCCESS,
            STATUS_METRICS_CONFIG_ALREADY_EXISTS,
            STATUS_METRICS_CONFIG_VERSION_TOO_OLD,
            STATUS_METRICS_CONFIG_PARSE_FAILED,
            STATUS_METRICS_CONFIG_SIGNATURE_VERIFICATION_FAILED,
            STATUS_METRICS_CONFIG_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MetricsConfigStatus {
    }

    /**
     * Application must pass a {@link AddMetricsConfigCallback} to use {@link
     * #addMetricsConfig(String, byte[], Executor, AddMetricsConfigCallback)}
     *
     * @hide
     */
    public interface AddMetricsConfigCallback {
        /**
         * Sends the {@link #addMetricsConfig(String, byte[], Executor, AddMetricsConfigCallback)}
         * status to the client.
         *
         * @param metricsConfigName name of the MetricsConfig that the status is associated with.
         * @param statusCode        See {@link MetricsConfigStatus}.
         */
        void onAddMetricsConfigStatus(@NonNull String metricsConfigName,
                @MetricsConfigStatus int statusCode);
    }

    /**
     * Application registers {@link CarTelemetryResultsListener} object to receive data from
     * {@link com.android.car.telemetry.CarTelemetryService}.
     *
     * @hide
     */
    public interface CarTelemetryResultsListener {
        /**
         * Sends script results to the client. Called by {@link CarTelemetryServiceListener}.
         *
         * TODO(b/184964661): Publish the documentation for the format of the results.
         *
         * @param metricsConfigName name of the MetricsConfig that the result is associated with.
         * @param result            the car telemetry result as serialized bytes.
         */
        void onResult(@NonNull String metricsConfigName, @NonNull byte[] result);

        /**
         * Sends script execution errors to the client.
         *
         * @param metricsConfigName name of the MetricsConfig that the error is associated with.
         * @param error             the serialized car telemetry error.
         */
        void onError(@NonNull String metricsConfigName, @NonNull byte[] error);
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
        public void onResult(@NonNull String metricsConfigName, @NonNull byte[] result) {
            CarTelemetryManager manager = mManager.get();
            if (manager == null) {
                return;
            }
            manager.onResult(metricsConfigName, result);
        }

        @Override
        public void onError(@NonNull String metricsConfigName, @NonNull byte[] error) {
            CarTelemetryManager manager = mManager.get();
            if (manager == null) {
                return;
            }
            manager.onError(metricsConfigName, error);
        }
    }

    private void onResult(String metricsConfigName, byte[] result) {
        long token = Binder.clearCallingIdentity();
        Executor executor = getExecutor();
        if (executor == null) {
            return;
        }
        executor.execute(() -> {
            CarTelemetryResultsListener listener = getResultsListener();
            if (listener != null) {
                listener.onResult(metricsConfigName, result);
            }
        });
        Binder.restoreCallingIdentity(token);
    }

    private void onError(String metricsConfigName, byte[] error) {
        long token = Binder.clearCallingIdentity();
        Executor executor = getExecutor();
        if (executor == null) {
            return;
        }
        executor.execute(() -> {
            CarTelemetryResultsListener listener = getResultsListener();
            if (listener != null) {
                listener.onError(metricsConfigName, error);
            }
        });
        Binder.restoreCallingIdentity(token);
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
     * to receive script execution results. The listener must be set before invoking other APIs in
     * this class.
     *
     * @param listener to received data from {@link com.android.car.telemetry.CarTelemetryService}.
     * @throws IllegalStateException if the listener is already set.
     * @hide
     */
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

    /**
     * Adds a MetricsConfig to CarTelemetryService. The size of the MetricsConfig cannot
     * exceed a predefined size, otherwise an exception is thrown.
     * The MetricsConfig will be uniquely identified by its name and version. If a MetricsConfig
     * of the same name already exists in {@link com.android.car.telemetry.CarTelemetryService},
     * the config version will be compared. If the version is strictly higher, the existing
     * MetricsConfig will be replaced by the new one. All legacy data will be cleared if replaced.
     * Client should use {@link #sendFinishedReports(String)} to get the result before
     * replacing a MetricsConfig.
     * The status of this API is sent back asynchronously via {@link AddMetricsConfigCallback}.
     *
     * @param metricsConfigName name of the MetricsConfig, must match
     *                          {@link TelemetryProto.MetricsConfig#getName()}.
     * @param metricsConfig     the serialized bytes of a MetricsConfig object.
     * @param executor          The {@link Executor} on which the callback will be invoked.
     * @param callback          A callback for receiving addMetricsConfig status codes.
     * @throws IllegalArgumentException if the MetricsConfig size exceeds limit.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void addMetricsConfig(@NonNull String metricsConfigName, @NonNull byte[] metricsConfig,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull AddMetricsConfigCallback callback) {
        if (metricsConfig.length > METRICS_CONFIG_MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("MetricsConfig size exceeds limit.");
        }
        try {
            mService.addMetricsConfig(metricsConfigName, metricsConfig, new ResultReceiver(null) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    executor.execute(
                            () -> callback.onAddMetricsConfigStatus(metricsConfigName, resultCode));
                }
            });
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Removes a MetricsConfig from {@link com.android.car.telemetry.CarTelemetryService}. This
     * will also remove outputs produced by the MetricsConfig. If the MetricsConfig does not exist,
     * nothing will be removed.
     *
     * @param metricsConfigName that identify the MetricsConfig.
     * @throws IllegalStateException if the listener is not set.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void removeMetricsConfig(@NonNull String metricsConfigName) {
        try {
            mService.removeMetricsConfig(metricsConfigName);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Removes all MetricsConfigs from {@link com.android.car.telemetry.CarTelemetryService}. This
     * will also remove all MetricsConfig outputs.
     *
     * @throws IllegalStateException if the listener is not set.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void removeAllMetricsConfigs() {
        if (getResultsListener() == null) {
            throw new IllegalStateException("Listener must be set.");
        }
        try {
            mService.removeAllMetricsConfigs();
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Gets script execution results of a MetricsConfig as from the
     * {@link com.android.car.telemetry.CarTelemetryService}. This API is asynchronous and the
     * result is sent back asynchronously via the {@link CarTelemetryResultsListener}.
     * This call is destructive. The returned results will be deleted from CarTelemetryService.
     *
     * @param metricsConfigName to identify the MetricsConfig.
     * @throws IllegalStateException if the listener is not set.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void sendFinishedReports(@NonNull String metricsConfigName) {
        if (getResultsListener() == null) {
            throw new IllegalStateException("Listener must be set.");
        }
        try {
            mService.sendFinishedReports(metricsConfigName);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Gets all script execution results from {@link com.android.car.telemetry.CarTelemetryService}
     * asynchronously via the {@link CarTelemetryResultsListener}.
     * This call is destructive. The returned results will be deleted from CarTelemetryService.
     *
     * @throws IllegalStateException if the listener is not set.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void sendAllFinishedReports() {
        if (getResultsListener() == null) {
            throw new IllegalStateException("Listener must be set.");
        }
        try {
            mService.sendAllFinishedReports();
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    private CarTelemetryResultsListener getResultsListener() {
        synchronized (mLock) {
            return mResultsListener;
        }
    }

    private Executor getExecutor() {
        synchronized (mLock) {
            return mExecutor;
        }
    }
}
