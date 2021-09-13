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

import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_NONE;
import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_PARSE_FAILED;
import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_UNKNOWN;

import android.annotation.NonNull;
import android.app.StatsManager;
import android.car.Car;
import android.car.telemetry.ICarTelemetryService;
import android.car.telemetry.ICarTelemetryServiceListener;
import android.car.telemetry.MetricsConfigKey;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarPropertyService;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.telemetry.databroker.DataBroker;
import com.android.car.telemetry.databroker.DataBrokerController;
import com.android.car.telemetry.databroker.DataBrokerImpl;
import com.android.car.telemetry.publisher.PublisherFactory;
import com.android.car.telemetry.publisher.StatsManagerImpl;
import com.android.car.telemetry.publisher.StatsManagerProxy;
import com.android.car.telemetry.systemmonitor.SystemMonitor;
import com.android.internal.annotations.VisibleForTesting;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.util.List;

/**
 * CarTelemetryService manages OEM telemetry collection, processing and communication
 * with a data upload service.
 */
public class CarTelemetryService extends ICarTelemetryService.Stub implements CarServiceBase {

    private static final boolean DEBUG = false;
    public static final String TELEMETRY_DIR = "telemetry";

    private final Context mContext;
    private final CarPropertyService mCarPropertyService;
    private final File mRootDirectory;
    private final HandlerThread mTelemetryThread = CarServiceUtils.getHandlerThread(
            CarTelemetryService.class.getSimpleName());
    private final Handler mTelemetryHandler = new Handler(mTelemetryThread.getLooper());

    private ICarTelemetryServiceListener mListener;
    private DataBroker mDataBroker;
    private DataBrokerController mDataBrokerController;
    private MetricsConfigStore mMetricsConfigStore;
    private PublisherFactory mPublisherFactory;
    private ResultStore mResultStore;
    private SharedPreferences mSharedPrefs;
    private StatsManagerProxy mStatsManagerProxy;
    private SystemMonitor mSystemMonitor;

    public CarTelemetryService(Context context, CarPropertyService carPropertyService) {
        mContext = context;
        mCarPropertyService = carPropertyService;
        SystemInterface systemInterface = CarLocalServices.getService(SystemInterface.class);
        // full root directory path is /data/system/car/telemetry
        mRootDirectory = new File(systemInterface.getSystemCarDir(), TELEMETRY_DIR);
    }

    @Override
    public void init() {
        mTelemetryHandler.post(() -> {
            // initialize all necessary components
            mMetricsConfigStore = new MetricsConfigStore(mRootDirectory);
            mResultStore = new ResultStore(mRootDirectory);
            mStatsManagerProxy = new StatsManagerImpl(
                    mContext.getSystemService(StatsManager.class));
            // TODO(b/197968695): delay initialization of stats publisher
            mPublisherFactory = new PublisherFactory(mCarPropertyService, mTelemetryHandler,
                    mStatsManagerProxy, null);
            mDataBroker = new DataBrokerImpl(mContext, mPublisherFactory, mResultStore);
            mSystemMonitor = SystemMonitor.create(mContext, mTelemetryHandler);
            mDataBrokerController = new DataBrokerController(mDataBroker, mSystemMonitor);

            // start collecting data. once data is sent by publisher, scripts will be able to run
            List<TelemetryProto.MetricsConfig> activeConfigs =
                    mMetricsConfigStore.getActiveMetricsConfigs();
            for (TelemetryProto.MetricsConfig config : activeConfigs) {
                mDataBroker.addMetricsConfiguration(config);
            }
        });
    }

    @Override
    public void release() {
        // TODO(b/197969149): prevent threading issue, block main thread
        mTelemetryHandler.post(() -> mResultStore.flushToDisk());
    }

    @Override
    public void dump(IndentingPrintWriter writer) {
        writer.println("Car Telemetry service");
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
                Slog.d(CarLog.TAG_TELEMETRY, "Setting the listener for car telemetry service");
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
                Slog.d(CarLog.TAG_TELEMETRY, "Clearing the listener for car telemetry service");
            }
            mListener = null;
        });
    }

    /**
     * Send a telemetry metrics config to the service.
     *
     * @param key    the unique key to identify the MetricsConfig.
     * @param config the serialized bytes of a MetricsConfig object.
     */
    @Override
    public void addMetricsConfig(@NonNull MetricsConfigKey key, @NonNull byte[] config) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "addMetricsConfig");
        mTelemetryHandler.post(() -> {
            Slog.d(CarLog.TAG_TELEMETRY, "Adding metrics config " + key.getName()
                    + " to car telemetry service");
            // TODO(b/199540952): If config is active, add it to DataBroker
            TelemetryProto.MetricsConfig metricsConfig = null;
            int status = ERROR_METRICS_CONFIG_UNKNOWN;
            try {
                metricsConfig = TelemetryProto.MetricsConfig.parseFrom(config);
            } catch (InvalidProtocolBufferException e) {
                Slog.e(CarLog.TAG_TELEMETRY, "Failed to parse MetricsConfig.", e);
                status = ERROR_METRICS_CONFIG_PARSE_FAILED;
            }
            // if config can be parsed, add it to persistent storage
            if (metricsConfig != null) {
                status = mMetricsConfigStore.addMetricsConfig(metricsConfig);
            }
            // If no error (a config is successfully added), script results from an older version
            // should be deleted
            if (status == ERROR_METRICS_CONFIG_NONE) {
                mResultStore.deleteResult(key.getName());
            }
            try {
                mListener.onAddMetricsConfigStatus(key, status);
            } catch (RemoteException e) {
                Slog.d(CarLog.TAG_TELEMETRY, "error with ICarTelemetryServiceListener", e);
            }
        });
    }

    /**
     * Removes a metrics config based on the key. This will also remove outputs produced by the
     * MetricsConfig.
     */
    @Override
    public void removeMetricsConfig(@NonNull MetricsConfigKey key) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "removeMetricsConfig");
        mTelemetryHandler.post(() -> {
            Slog.d(CarLog.TAG_TELEMETRY, "Removing metrics config " + key.getName()
                    + " from car telemetry service");
            // TODO(b/198792767): Check both config name and config version for deletion
            // TODO(b/199540952): Stop and remove config from data broker
            mResultStore.deleteResult(key.getName()); // delete the config's script results
            boolean success = mMetricsConfigStore.deleteMetricsConfig(key.getName());
            try {
                mListener.onRemoveMetricsConfigStatus(key, success);
            } catch (RemoteException e) {
                Slog.d(CarLog.TAG_TELEMETRY, "error with ICarTelemetryServiceListener", e);
            }
        });
    }

    /**
     * Removes all MetricsConfigs. This will also remove all MetricsConfig outputs.
     */
    @Override
    public void removeAllMetricsConfigs() {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "removeAllMetricsConfig");
        mTelemetryHandler.post(() -> {
            // TODO(b/199540952): Stop and remove all configs from DataBroker
            Slog.d(CarLog.TAG_TELEMETRY,
                    "Removing all metrics config from car telemetry service");
            mMetricsConfigStore.deleteAllMetricsConfigs();
            mResultStore.deleteAllResults();
        });
    }

    /**
     * Sends script results associated with the given key using the
     * {@link ICarTelemetryServiceListener}.
     */
    @Override
    public void sendFinishedReports(@NonNull MetricsConfigKey key) {
        // TODO(b/184087869): Implement
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "sendFinishedReports");
        if (DEBUG) {
            Slog.d(CarLog.TAG_TELEMETRY, "Flushing reports for a metrics config");
        }
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
            Slog.d(CarLog.TAG_TELEMETRY, "Flushing all reports");
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
