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
package com.android.car.telemetry;

import static android.car.telemetry.CarTelemetryManager.STATUS_METRICS_CONFIG_PARSE_FAILED;
import static android.car.telemetry.CarTelemetryManager.STATUS_METRICS_CONFIG_SUCCESS;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import static java.util.stream.Collectors.toList;

import android.annotation.NonNull;
import android.car.Car;
import android.car.builtin.os.TraceHelper;
import android.car.builtin.util.Slogf;
import android.car.builtin.util.TimingsTraceLog;
import android.car.telemetry.ICarTelemetryService;
import android.car.telemetry.ICarTelemetryServiceListener;
import android.car.telemetry.MetricsConfigKey;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PersistableBundle;
import android.os.RemoteException;

import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarPropertyService;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.OnShutdownReboot;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.telemetry.databroker.DataBroker;
import com.android.car.telemetry.databroker.DataBrokerController;
import com.android.car.telemetry.databroker.DataBrokerImpl;
import com.android.car.telemetry.publisher.PublisherFactory;
import com.android.car.telemetry.systemmonitor.SystemMonitor;
import com.android.internal.annotations.VisibleForTesting;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/**
 * CarTelemetryService manages OEM telemetry collection, processing and communication
 * with a data upload service.
 */
public class CarTelemetryService extends ICarTelemetryService.Stub implements CarServiceBase {

    public static final boolean DEBUG = true; // STOPSHIP if true

    private static final String PUBLISHER_DIR = "publisher";
    public static final String TELEMETRY_DIR = "telemetry";

    private final Context mContext;
    private final CarPropertyService mCarPropertyService;
    private final HandlerThread mTelemetryThread = CarServiceUtils.getHandlerThread(
            CarTelemetryService.class.getSimpleName());
    private final Handler mTelemetryHandler = new Handler(mTelemetryThread.getLooper());

    // accessed and updated on the main thread
    private boolean mReleased = false;

    private ICarTelemetryServiceListener mListener;
    private DataBroker mDataBroker;
    private DataBrokerController mDataBrokerController;
    private MetricsConfigStore mMetricsConfigStore;
    private OnShutdownReboot mOnShutdownReboot;
    private PublisherFactory mPublisherFactory;
    private ResultStore mResultStore;
    private SystemMonitor mSystemMonitor;
    private TimingsTraceLog mTelemetryThreadTraceLog; // can only be used on telemetry thread

    public CarTelemetryService(Context context, CarPropertyService carPropertyService) {
        mContext = context;
        mCarPropertyService = carPropertyService;
    }

    @Override
    public void init() {
        mTelemetryHandler.post(() -> {
            mTelemetryThreadTraceLog = new TimingsTraceLog(
                    CarLog.TAG_TELEMETRY, TraceHelper.TRACE_TAG_CAR_SERVICE);
            mTelemetryThreadTraceLog.traceBegin("init");
            SystemInterface systemInterface = CarLocalServices.getService(SystemInterface.class);
            // full root directory path is /data/system/car/telemetry
            File rootDirectory = new File(systemInterface.getSystemCarDir(), TELEMETRY_DIR);
            File publisherDirectory = new File(rootDirectory, PUBLISHER_DIR);
            publisherDirectory.mkdirs();
            // initialize all necessary components
            mMetricsConfigStore = new MetricsConfigStore(rootDirectory);
            mResultStore = new ResultStore(rootDirectory);
            mPublisherFactory = new PublisherFactory(mCarPropertyService, mTelemetryHandler,
                    mContext, publisherDirectory);
            mDataBroker = new DataBrokerImpl(mContext, mPublisherFactory, mResultStore,
                    mTelemetryThreadTraceLog);
            mSystemMonitor = SystemMonitor.create(mContext, mTelemetryHandler);
            // controller starts metrics collection after boot complete
            mDataBrokerController = new DataBrokerController(mDataBroker, mTelemetryHandler,
                    mMetricsConfigStore, mSystemMonitor,
                    systemInterface.getSystemStateInterface());
            mTelemetryThreadTraceLog.traceEnd();
            // save state at reboot and shutdown
            mOnShutdownReboot = new OnShutdownReboot(mContext);
            mOnShutdownReboot.addAction((context, intent) -> release());
        });
    }

    @Override
    public void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        mTelemetryHandler.post(() -> {
            mTelemetryThreadTraceLog.traceBegin("release");
            mResultStore.flushToDisk();
            mOnShutdownReboot.release();
            mTelemetryThreadTraceLog.traceEnd();
        });
        mTelemetryThread.quitSafely();
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("*CarTelemetryService*");
        writer.println();
        // Print active configs with their interim results and errors.
        writer.println("Active Configs");
        writer.println();
        for (TelemetryProto.MetricsConfig config : mMetricsConfigStore.getActiveMetricsConfigs()) {
            writer.println("    Name: " + config.getName());
            writer.println("    Version: " + config.getVersion());
            PersistableBundle interimResult = mResultStore.getInterimResult(config.getName());
            if (interimResult != null) {
                writer.println("    Interim Result");
                writer.println("        Bundle keys: "
                        + Arrays.toString(interimResult.keySet().toArray()));
            }
            writer.println();
        }
        // Print info on stored final results. Configs are inactive after producing final result.
        Map<String, PersistableBundle> finalResults = mResultStore.getFinalResults();
        writer.println("Final Results");
        writer.println();
        for (Map.Entry<String, PersistableBundle> entry : finalResults.entrySet()) {
            writer.println("    Config name: " + entry.getKey());
            writer.println("    Bundle keys: "
                    + Arrays.toString(entry.getValue().keySet().toArray()));
            writer.println();
        }
        // Print info on stored errors. Configs are inactive after producing errors.
        Map<String, TelemetryProto.TelemetryError> errors = mResultStore.getErrorResults();
        writer.println("Errors");
        writer.println();
        for (Map.Entry<String, TelemetryProto.TelemetryError> entry : errors.entrySet()) {
            writer.println("    Config name: " + entry.getKey());
            TelemetryProto.TelemetryError error = entry.getValue();
            writer.println("    Error");
            writer.println("        Type: " + error.getErrorType());
            writer.println("        Message: " + error.getMessage());
            if (error.hasStackTrace() && !error.getStackTrace().isEmpty()) {
                writer.println("        Stack trace: " + error.getStackTrace());
            }
            writer.println();
        }
    }

    /**
     * Registers a listener with CarTelemetryService for the service to send data to cloud app.
     */
    @Override
    public void setListener(@NonNull ICarTelemetryServiceListener listener) {
        // TODO(b/184890506): verify that only a hardcoded app can set the listener
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "setListener");
        mTelemetryHandler.post(() -> {
            if (DEBUG) {
                Slogf.d(CarLog.TAG_TELEMETRY, "Setting the listener for car telemetry service");
            }
            mListener = listener;
        });
    }

    /**
     * Clears the listener registered with CarTelemetryService.
     */
    @Override
    public void clearListener() {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "clearListener");
        mTelemetryHandler.post(() -> {
            if (DEBUG) {
                Slogf.d(CarLog.TAG_TELEMETRY, "Clearing the listener for car telemetry service");
            }
            mListener = null;
        });
    }

    /**
     * Send a telemetry metrics config to the service. This method assumes
     * {@link #setListener(ICarTelemetryServiceListener)} is called. Otherwise it does nothing.
     *
     * @param key    the unique key to identify the MetricsConfig.
     * @param config the serialized bytes of a MetricsConfig object.
     */
    @Override
    public void addMetricsConfig(@NonNull MetricsConfigKey key, @NonNull byte[] config) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "addMetricsConfig");
        mTelemetryHandler.post(() -> {
            if (mListener == null) {
                Slogf.w(CarLog.TAG_TELEMETRY, "ICarTelemetryServiceListener is not set");
                return;
            }
            mTelemetryThreadTraceLog.traceBegin("addMetricsConfig");
            int status = addMetricsConfigInternal(key, config);
            try {
                mListener.onAddMetricsConfigStatus(key, status);
            } catch (RemoteException e) {
                Slogf.w(CarLog.TAG_TELEMETRY, "error with ICarTelemetryServiceListener", e);
            }
            mTelemetryThreadTraceLog.traceEnd();
        });
    }

    /** Adds the MetricsConfig and returns the status. */
    private int addMetricsConfigInternal(@NonNull MetricsConfigKey key, @NonNull byte[] config) {
        Slogf.d(CarLog.TAG_TELEMETRY,
                "Adding metrics config: " + key.getName() + " to car telemetry service");
        TelemetryProto.MetricsConfig metricsConfig;
        try {
            metricsConfig = TelemetryProto.MetricsConfig.parseFrom(config);
        } catch (InvalidProtocolBufferException e) {
            Slogf.e(CarLog.TAG_TELEMETRY, "Failed to parse MetricsConfig.", e);
            return STATUS_METRICS_CONFIG_PARSE_FAILED;
        }
        if (!metricsConfig.getName().equals(key.getName())
                || metricsConfig.getVersion() != key.getVersion()) {
            Slogf.e(CarLog.TAG_TELEMETRY,
                    "Argument key " + key + " doesn't match name/version in MetricsConfig ("
                            + metricsConfig.getName() + ", "
                            + metricsConfig.getVersion() + ").");
            return STATUS_METRICS_CONFIG_PARSE_FAILED;
        }
        int status = mMetricsConfigStore.addMetricsConfig(metricsConfig);
        if (status != STATUS_METRICS_CONFIG_SUCCESS) {
            return status;
        }
        // If no error (config is added to the MetricsConfigStore), remove previously collected data
        // for this key and add config to the DataBroker for metrics collection.
        mResultStore.removeResult(key);
        mDataBroker.removeMetricsConfig(key);
        mDataBroker.addMetricsConfig(key, metricsConfig);
        // TODO(b/199410900): update logic once metrics configs have expiration dates
        return STATUS_METRICS_CONFIG_SUCCESS;
    }

    /**
     * Removes a metrics config based on the key. This will also remove outputs produced by the
     * MetricsConfig.
     *
     * @param key the unique identifier of a MetricsConfig.
     */
    @Override
    public void removeMetricsConfig(@NonNull MetricsConfigKey key) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "removeMetricsConfig");
        mTelemetryHandler.post(() -> {
            if (DEBUG) {
                Slogf.d(CarLog.TAG_TELEMETRY, "Removing metrics config " + key.getName()
                        + " from car telemetry service");
            }
            mTelemetryThreadTraceLog.traceBegin("removeMetricsConfig");
            if (mMetricsConfigStore.removeMetricsConfig(key)) {
                mDataBroker.removeMetricsConfig(key);
                mResultStore.removeResult(key);
            }
            mTelemetryThreadTraceLog.traceEnd();
        });
    }

    /**
     * Removes all MetricsConfigs. This will also remove all MetricsConfig outputs.
     */
    @Override
    public void removeAllMetricsConfigs() {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "removeAllMetricsConfigs");
        mTelemetryHandler.post(() -> {
            mTelemetryThreadTraceLog.traceBegin("removeAllMetricsConfig");
            Slogf.d(CarLog.TAG_TELEMETRY,
                    "Removing all metrics config from car telemetry service");
            mDataBroker.removeAllMetricsConfigs();
            mMetricsConfigStore.removeAllMetricsConfigs();
            mResultStore.removeAllResults();
            mTelemetryThreadTraceLog.traceEnd();
        });
    }

    /**
     * Sends script results associated with the given key using the
     * {@link ICarTelemetryServiceListener}. This method assumes listener is set. Otherwise it
     * does nothing.
     *
     * @param key the unique identifier of a MetricsConfig.
     */
    @Override
    public void sendFinishedReports(@NonNull MetricsConfigKey key) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "sendFinishedReports");
        mTelemetryHandler.post(() -> {
            if (mListener == null) {
                Slogf.w(CarLog.TAG_TELEMETRY, "ICarTelemetryServiceListener is not set");
                return;
            }
            if (DEBUG) {
                Slogf.d(CarLog.TAG_TELEMETRY,
                        "Flushing reports for metrics config " + key.getName());
            }
            mTelemetryThreadTraceLog.traceBegin("sendFinishedReports");
            PersistableBundle result = mResultStore.getFinalResult(key.getName(), true);
            TelemetryProto.TelemetryError error = mResultStore.getErrorResult(key.getName(), true);
            if (result != null) {
                sendFinalResult(key, result);
            } else if (error != null) {
                sendError(key, error);
            } else {
                Slogf.i(CarLog.TAG_TELEMETRY,
                        "config " + key.getName() + " did not produce any results");
            }
            mTelemetryThreadTraceLog.traceEnd();
        });
    }

    /**
     * Sends all script results or errors using the {@link ICarTelemetryServiceListener}.
     */
    @Override
    public void sendAllFinishedReports() {
        // TODO(b/184087869): Implement
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "sendAllFinishedReports");
        if (DEBUG) {
            Slogf.d(CarLog.TAG_TELEMETRY, "Flushing all reports");
        }
    }

    /**
     * Adds the MetricsConfig. This methods is expected to be used only by {@code CarShellCommand}
     * class, because CarTelemetryService supports only a single listener and the shell command
     * shouldn't replace the existing listener. Other usages are not supported.
     *
     * @param key config key.
     * @param config config body serialized as a binary protobuf.
     * @param statusConsumer receives the status code.
     */
    public void addMetricsConfig(
            @NonNull MetricsConfigKey key,
            @NonNull byte[] config,
            @NonNull IntConsumer statusConsumer) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "addMetricsConfig");
        mTelemetryHandler.post(() -> statusConsumer.accept(addMetricsConfigInternal(key, config)));
    }

    /**
     * Returns the finished reports. This methods is expected to be used only by {@code
     * CarShellCommand} class, because CarTelemetryService supports only a single listener and the
     * shell command shouldn't replace the existing listener. Other usages are not supported.
     *
     * <p>It sends {@code ErrorType.UNSPECIFIED} if there are no results.
     *
     * @param metricsConfigName MetricsConfig name.
     * @param deleteResult if true, the result will be deleted from the storage.
     * @param consumer receives the final result or error.
     */
    public void getFinishedReports(
            @NonNull String metricsConfigName,
            boolean deleteResult,
            @NonNull BiConsumer<PersistableBundle, TelemetryProto.TelemetryError> consumer) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "getFinishedReports");
        mTelemetryHandler.post(() -> {
            PersistableBundle result = mResultStore.getFinalResult(metricsConfigName, deleteResult);
            TelemetryProto.TelemetryError error =
                    mResultStore.getErrorResult(metricsConfigName, deleteResult);
            if (result != null) {
                consumer.accept(result, null);
            } else if (error != null) {
                consumer.accept(null, error);
            } else {
                // TODO(b/209469238): Create a NO_RESULT error type
                TelemetryProto.TelemetryError unknownError =
                        TelemetryProto.TelemetryError.newBuilder()
                                .setErrorType(
                                        TelemetryProto.TelemetryError.ErrorType.UNSPECIFIED)
                                .setMessage("No results")
                                .build();
                consumer.accept(null, unknownError);
            }
        });
    }

    /**
     * Returns the list of config names and versions. This methods is expected to be used only by
     * {@code CarShellCommand} class. Other usages are not supported.
     */
    public List<String> getActiveMetricsConfigDetails() {
        return mMetricsConfigStore.getActiveMetricsConfigs().stream()
                .map((config) -> config.getName() + " version=" + config.getVersion())
                .collect(toList());
    }

    private void sendFinalResult(MetricsConfigKey key, PersistableBundle result) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            result.writeToStream(bos);
            mListener.onResult(key, bos.toByteArray());
        } catch (RemoteException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "error with ICarTelemetryServiceListener", e);
        } catch (IOException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "failed to write bundle to output stream", e);
        }
    }

    private void sendError(MetricsConfigKey key, TelemetryProto.TelemetryError error) {
        try {
            mListener.onError(key, error.toByteArray());
        } catch (RemoteException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "error with ICarTelemetryServiceListener", e);
        }
    }

    @VisibleForTesting
    Handler getTelemetryHandler() {
        return mTelemetryHandler;
    }

    @VisibleForTesting
    ResultStore getResultStore() {
        return mResultStore;
    }

    @VisibleForTesting
    MetricsConfigStore getMetricsConfigStore() {
        return mMetricsConfigStore;
    }
}
