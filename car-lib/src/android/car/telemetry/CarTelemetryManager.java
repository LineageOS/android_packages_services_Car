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
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.annotation.RequiredFeature;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

    private final ICarTelemetryService mService;

    /** Status to indicate that MetricsConfig was added successfully. */
    public static final int STATUS_ADD_METRICS_CONFIG_SUCCEEDED = 0;

    /**
     * Status to indicate that add MetricsConfig failed because the same MetricsConfig of the same
     * name and version already exists.
     */
    public static final int STATUS_ADD_METRICS_CONFIG_ALREADY_EXISTS = 1;

    /**
     * Status to indicate that add MetricsConfig failed because a newer version of the MetricsConfig
     * exists.
     */
    public static final int STATUS_ADD_METRICS_CONFIG_VERSION_TOO_OLD = 2;

    /**
     * Status to indicate that add MetricsConfig failed because CarTelemetryService is unable to
     * parse the given byte array into a MetricsConfig.
     */
    public static final int STATUS_ADD_METRICS_CONFIG_PARSE_FAILED = 3;

    /**
     * Status to indicate that add MetricsConfig failed because of failure to verify the signature
     * of the MetricsConfig.
     */
    public static final int STATUS_ADD_METRICS_CONFIG_SIGNATURE_VERIFICATION_FAILED = 4;

    /** Status to indicate that add MetricsConfig failed because of a general error in cars. */
    public static final int STATUS_ADD_METRICS_CONFIG_UNKNOWN = 5;

    /** @hide */
    @IntDef(
            prefix = {"STATUS_ADD_METRICS_CONFIG_"},
            value = {
                STATUS_ADD_METRICS_CONFIG_SUCCEEDED,
                STATUS_ADD_METRICS_CONFIG_ALREADY_EXISTS,
                STATUS_ADD_METRICS_CONFIG_VERSION_TOO_OLD,
                STATUS_ADD_METRICS_CONFIG_PARSE_FAILED,
                STATUS_ADD_METRICS_CONFIG_SIGNATURE_VERIFICATION_FAILED,
                STATUS_ADD_METRICS_CONFIG_UNKNOWN
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MetricsConfigStatus {}

    /** Status to indicate that MetricsConfig produced a report. */
    public static final int STATUS_GET_METRICS_CONFIG_FINISHED = 0;

    /**
     * Status to indicate a MetricsConfig exists but has produced neither interim/final report nor
     * runtime execution errors.
     */
    public static final int STATUS_GET_METRICS_CONFIG_PENDING = 1;

    /** Status to indicate a MetricsConfig exists and produced interim results. */
    public static final int STATUS_GET_METRICS_CONFIG_INTERIM_RESULTS = 2;

    /** Status to indicate the MetricsConfig produced a runtime execution error. */
    public static final int STATUS_GET_METRICS_CONFIG_RUNTIME_ERROR = 3;

    /** Status to indicate a MetricsConfig does not exist and hence no report can be found. */
    public static final int STATUS_GET_METRICS_CONFIG_DOES_NOT_EXIST = 4;

    /** @hide */
    @IntDef(
            prefix = {"STATUS_GET_METRICS_CONFIG_"},
            value = {
                STATUS_GET_METRICS_CONFIG_FINISHED,
                STATUS_GET_METRICS_CONFIG_PENDING,
                STATUS_GET_METRICS_CONFIG_INTERIM_RESULTS,
                STATUS_GET_METRICS_CONFIG_RUNTIME_ERROR,
                STATUS_GET_METRICS_CONFIG_DOES_NOT_EXIST
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MetricsReportStatus {}

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
         * @param statusCode See {@link MetricsConfigStatus}.
         */
        void onAddMetricsConfigStatus(
                @NonNull String metricsConfigName, @MetricsConfigStatus int statusCode);
    }

    /**
     * Application must pass a {@link MetricsReportCallback} object to receive finished reports from
     * {@link #getFinishedReport(String, Executor, MetricsReportCallback)} and {@link
     * #getAllFinishedReports(Executor, MetricsReportCallback)}.
     *
     * @hide
     */
    public interface MetricsReportCallback {
        /**
         * Provides the metrics report associated with metricsConfigName. If there is a metrics
         * report, it provides the metrics report. If the metrics report calculation failed due to a
         * runtime error during the execution of reporting script, it provides the runtime error in
         * the error parameter. The status parameter provides more information on the state of the
         * metrics report.
         *
         * TODO(b/184964661): Publish the documentation for the format of the finished reports.
         *
         * @param metricsConfigName name of the MetricsConfig that the report is associated with.
         * @param report the car telemetry report as serialized bytes. Null if there is no report.
         * @param telemetryError the serialized telemetry metrics configuration runtime execution
         *     error.
         * @param status of the metrics report. See {@link MetricsReportStatus}.
         */
        void onResult(
                @NonNull String metricsConfigName,
                @Nullable byte[] report,
                @Nullable byte[] telemetryError,
                @MetricsReportStatus int status);
    }

    /**
     * Gets an instance of CarTelemetryManager.
     *
     * <p>CarTelemetryManager manages {@link com.android.car.telemetry.CarTelemetryService} and
     * provides APIs so the client can use the car telemetry service.
     *
     * <p>There is only one client to this manager, which is OEM's cloud application. It uses the
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
    public void onCarDisconnected() {}

    /**
     * Adds a MetricsConfig to CarTelemetryService. The size of the MetricsConfig cannot exceed a
     * {@link #METRICS_CONFIG_MAX_SIZE_BYTES}, otherwise an exception is thrown.
     *
     * <p>The MetricsConfig will be uniquely identified by its name and version. If a MetricsConfig
     * of the same name already exists in {@link com.android.car.telemetry.CarTelemetryService}, the
     * config version will be compared. If the version is strictly higher, the existing
     * MetricsConfig will be replaced by the new one. All legacy data will be cleared if replaced.
     *
     * <p>Client should use {@link #getFinishedReport(String, Executor, MetricsReportCallback)} to
     * get the report before replacing a MetricsConfig.
     *
     * <p>The status of this API is sent back asynchronously via {@link AddMetricsConfigCallback}.
     *
     * @param metricsConfigName name of the MetricsConfig, must match {@link
     *     TelemetryProto.MetricsConfig#getName()}.
     * @param metricsConfig the serialized bytes of a MetricsConfig object.
     * @param executor The {@link Executor} on which the callback will be invoked.
     * @param callback A callback for receiving addMetricsConfig status codes.
     * @throws IllegalArgumentException if the MetricsConfig size exceeds limit.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void addMetricsConfig(
            @NonNull String metricsConfigName,
            @NonNull byte[] metricsConfig,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull AddMetricsConfigCallback callback) {
        if (metricsConfig.length > METRICS_CONFIG_MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("MetricsConfig size exceeds limit.");
        }
        try {
            mService.addMetricsConfig(metricsConfigName, metricsConfig, new ResultReceiver(null) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    executor.execute(() ->
                            callback.onAddMetricsConfigStatus(metricsConfigName, resultCode));
                }
            });
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Removes a MetricsConfig from {@link com.android.car.telemetry.CarTelemetryService}. This will
     * also remove outputs produced by the MetricsConfig. If the MetricsConfig does not exist,
     * nothing will be removed.
     *
     * @param metricsConfigName that identify the MetricsConfig.
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
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void removeAllMetricsConfigs() {
        try {
            mService.removeAllMetricsConfigs();
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Gets script execution reports of a MetricsConfig as from the {@link
     * com.android.car.telemetry.CarTelemetryService}. This API is asynchronous and the report is
     * sent back asynchronously via the {@link MetricsReportCallback}. This call is destructive. The
     * returned report will be deleted from CarTelemetryService.
     *
     * @param metricsConfigName to identify the MetricsConfig.
     * @param executor The {@link Executor} on which the callback will be invoked.
     * @param callback A callback for receiving finished reports.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void getFinishedReport(
            @NonNull String metricsConfigName,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull MetricsReportCallback callback) {
        try {
            mService.getFinishedReport(metricsConfigName, new ICarTelemetryReportListener.Stub() {
                @Override
                public void onResult(
                        @NonNull String metricsConfigName,
                        @Nullable byte[] report,
                        @Nullable byte[] telemetryError,
                        int status) {
                    executor.execute(() ->
                            callback.onResult(metricsConfigName, report, telemetryError, status));
                }
            });
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Gets all script execution reports from {@link com.android.car.telemetry.CarTelemetryService}
     * asynchronously via the {@link MetricsReportCallback}. The callback will be invoked multiple
     * times if there are multiple reports. This call is destructive. The returned reports will be
     * deleted from CarTelemetryService.
     *
     * @param executor The {@link Executor} on which the callback will be invoked.
     * @param callback A callback for receiving finished reports.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void getAllFinishedReports(
            @CallbackExecutor @NonNull Executor executor, @NonNull MetricsReportCallback callback) {
        try {
            mService.getAllFinishedReports(new ICarTelemetryReportListener.Stub() {
                @Override
                public void onResult(
                        @NonNull String metricsConfigName,
                        @Nullable byte[] report,
                        @Nullable byte[] telemetryError,
                        int status) {
                    executor.execute(() ->
                            callback.onResult(metricsConfigName, report, telemetryError, status));
                }
            });
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }
}
