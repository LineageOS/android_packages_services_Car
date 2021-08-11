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

import android.app.StatsManager.StatsUnavailableException;
import android.car.builtin.util.Slog;
import android.content.SharedPreferences;

import com.android.car.CarLog;
import com.android.car.telemetry.StatsdConfigProto;
import com.android.car.telemetry.StatsdConfigProto.StatsdConfig;
import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.TelemetryProto.Publisher.PublisherCase;
import com.android.car.telemetry.databroker.DataSubscriber;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

/**
 * Publisher for {@link TelemetryProto.StatsPublisher}.
 *
 * <p>The publisher adds subscriber configurations in StatsD and they persist between reboots and
 * CarTelemetryService restarts. Please use {@link #removeAllDataSubscribers} or
 * {@link #removeDataSubscriber} to clean-up these configs from StatsD store.
 *
 * <p>Thread-safe.
 */
public class StatsPublisher extends AbstractPublisher {
    // These IDs are used in StatsdConfig and ConfigMetricsReport.
    private static final int APP_START_MEMORY_STATE_CAPTURED_ATOM_MATCHER_ID = 1;
    private static final int APP_START_MEMORY_STATE_CAPTURED_EVENT_METRIC_ID = 2;

    // The atom ids are from frameworks/proto_logging/stats/atoms.proto
    private static final int ATOM_APP_START_MEMORY_STATE_CAPTURED_ID = 55;

    private static final String SHARED_PREF_CONFIG_KEY_PREFIX = "statsd-publisher-config-id-";
    private static final String SHARED_PREF_CONFIG_VERSION_PREFIX =
            "statsd-publisher-config-version-";

    private final Object mLock = new Object();

    private final StatsManagerProxy mStatsManager;
    private final SharedPreferences mSharedPreferences;

    StatsPublisher(StatsManagerProxy statsManager, SharedPreferences sharedPreferences) {
        mStatsManager = statsManager;
        mSharedPreferences = sharedPreferences;
    }

    @Override
    public void addDataSubscriber(DataSubscriber subscriber) {
        TelemetryProto.Publisher publisherParam = subscriber.getPublisherParam();
        Preconditions.checkArgument(
                publisherParam.getPublisherCase() == PublisherCase.STATS,
                "Subscribers only with StatsPublisher are supported by this class.");

        synchronized (mLock) {
            addStatsConfigLocked(subscriber);
        }
    }

    /**
     * Removes the subscriber from the publisher and removes StatsdConfig from StatsD service.
     * If StatsdConfig is present in Statsd, it removes it even if the subscriber is not present
     * in the publisher (it happens when subscriber was added before and CarTelemetryService was
     * restarted and lost publisher state).
     */
    @Override
    public void removeDataSubscriber(DataSubscriber subscriber) {
        // TODO(b/189143813): implement
    }

    /** Removes all the subscribers from the publisher removes StatsdConfigs from StatsD service. */
    @Override
    public void removeAllDataSubscribers() {
        // TODO(b/189143813): implement
    }

    @Override
    public boolean hasDataSubscriber(DataSubscriber subscriber) {
        // TODO(b/189143813): implement
        return true;
    }

    /**
     * This method can be called even if StatsdConfig was added to StatsD service before. It stores
     * previously added config_keys in the shared preferences and only updates StatsD when
     * the MetricsConfig (of CarTelemetryService) has a new version.
     */
    @GuardedBy("mLock")
    private void addStatsConfigLocked(DataSubscriber subscriber) {
        String sharedPrefConfigKey =
                SHARED_PREF_CONFIG_KEY_PREFIX + subscriber.getMetricsConfig().getName() + "-"
                        + subscriber.getSubscriber().getHandler();
        // Store MetricsConfig (of CarTelemetryService) version per handler_function.
        String sharedPrefVersion =
                SHARED_PREF_CONFIG_VERSION_PREFIX + subscriber.getMetricsConfig().getName() + "-"
                        + subscriber.getSubscriber().getHandler();
        long configKey = buildConfigKey(subscriber);
        StatsdConfig config = buildStatsdConfig(subscriber, configKey);
        if (mSharedPreferences.contains(sharedPrefVersion)) {
            int currentVersion = mSharedPreferences.getInt(sharedPrefVersion, 0);
            if (currentVersion < subscriber.getMetricsConfig().getVersion()) {
                // TODO(b/189143813): remove old version from StatsD
                Slog.d(CarLog.TAG_TELEMETRY, "Removing old config from StatsD");
            } else {
                // Ignore if the MetricsConfig version is current or older.
                return;
            }
        }
        try {
            // It doesn't throw exception if the StatsdConfig is invalid. But it shouldn't happen,
            // as we generate well-tested StatsdConfig in this service.
            mStatsManager.addConfig(configKey, config.toByteArray());
            mSharedPreferences.edit()
                    .putInt(sharedPrefVersion, subscriber.getMetricsConfig().getVersion())
                    .putLong(sharedPrefConfigKey, configKey)
                    .apply();
        } catch (StatsUnavailableException e) {
            Slog.w(CarLog.TAG_TELEMETRY, "Failed to add config", e);
            // TODO(b/189143813): if StatsManager is not ready, retry N times and hard fail after
            //                    by notifying DataBroker.
        }
    }

    /**
     * Builds StatsdConfig id (aka config_key) using subscriber handler name.
     *
     * <p>StatsD uses ConfigKey struct to uniquely identify StatsdConfigs. StatsD ConfigKey consists
     * of two parts: client uid and config_key number. The StatsdConfig is added to StatsD from
     * CarService - which has uid=1000. Currently there is no client under uid=1000 and there will
     * not be config_key collision.
     */
    private static long buildConfigKey(DataSubscriber subscriber) {
        // Not to be confused with statsd metric, this one is a global CarTelemetry metric name.
        String metricConfigName = subscriber.getMetricsConfig().getName();
        String handlerFnName = subscriber.getSubscriber().getHandler();
        return HashUtils.sha256(metricConfigName + "-" + handlerFnName);
    }

    /** Builds {@link StatsdConfig} proto for given subscriber. */
    @VisibleForTesting
    static StatsdConfig buildStatsdConfig(DataSubscriber subscriber, long configId) {
        TelemetryProto.StatsPublisher.SystemMetric metric =
                subscriber.getPublisherParam().getStats().getSystemMetric();
        if (metric == TelemetryProto.StatsPublisher.SystemMetric.APP_START_MEMORY_STATE_CAPTURED) {
            return StatsdConfig.newBuilder()
                    // This id is not used in StatsD, but let's make it the same as config_key
                    // just in case.
                    .setId(configId)
                    .addAtomMatcher(StatsdConfigProto.AtomMatcher.newBuilder()
                            // The id must be unique within StatsdConfig/matchers
                            .setId(APP_START_MEMORY_STATE_CAPTURED_ATOM_MATCHER_ID)
                            .setSimpleAtomMatcher(StatsdConfigProto.SimpleAtomMatcher.newBuilder()
                                    .setAtomId(ATOM_APP_START_MEMORY_STATE_CAPTURED_ID)))
                    .addEventMetric(StatsdConfigProto.EventMetric.newBuilder()
                            // The id must be unique within StatsdConfig/metrics
                            .setId(APP_START_MEMORY_STATE_CAPTURED_EVENT_METRIC_ID)
                            .setWhat(APP_START_MEMORY_STATE_CAPTURED_ATOM_MATCHER_ID))
                    .addAllowedLogSource("AID_SYSTEM")
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported metric " + metric.name());
        }
    }
}
