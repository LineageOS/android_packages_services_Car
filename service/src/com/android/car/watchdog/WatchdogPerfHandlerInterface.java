/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.car.watchdog;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.automotive.watchdog.internal.GarageMode;
import android.automotive.watchdog.internal.PackageIoOveruseStats;
import android.automotive.watchdog.internal.UserPackageIoUsageStats;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.IResourceOveruseListener;
import android.car.watchdog.PackageKillableState;
import android.car.watchdog.ResourceOveruseConfiguration;
import android.car.watchdog.ResourceOveruseStats;
import android.content.Intent;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.proto.ProtoOutputStream;

import com.android.car.internal.util.IndentingPrintWriter;

import java.util.List;
import java.util.Set;

/**
 * Interface for the system resource performance monitoring.
 */
public interface WatchdogPerfHandlerInterface {
    String INTENT_EXTRA_NOTIFICATION_ID = "notification_id";

    /** Initializes the handler by registering required listeners/callbacks and setting up DB. */
    void init();

    /** Releases resources. */
    void release();

    /** Dumps its state. */
    void dump(IndentingPrintWriter writer);

    /** Dumps its state in proto format */
    void dumpProto(ProtoOutputStream proto);

    /** Retries any pending requests on re-connecting to the daemon */
    void onDaemonConnectionChange(boolean isConnected);

    /** Updates the current UX state based on the display state. */
    void onDisplayStateChanged(boolean isEnabled);

    /** Handles garage mode change. */
    void onGarageModeChange(@GarageMode int garageMode);

    /** Returns resource overuse stats for the calling package. */
    @NonNull
    ResourceOveruseStats getResourceOveruseStats(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod);

    /** Returns resource overuse stats for all packages. */
    @NonNull
    List<ResourceOveruseStats> getAllResourceOveruseStats(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.MinimumStatsFlag int minimumStatsFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod);

    /** Returns resource overuse stats for the specified user package. */
    @NonNull
    ResourceOveruseStats getResourceOveruseStatsForUserPackage(
            @NonNull String packageName, @NonNull UserHandle userHandle,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod);

    /** Adds the resource overuse listener. */
    void addResourceOveruseListener(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener);

    /** Removes the previously added resource overuse listener. */
    void removeResourceOveruseListener(@NonNull IResourceOveruseListener listener);

    /** Adds the resource overuse system listener. */
    void addResourceOveruseListenerForSystem(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener);

    /** Removes the previously added resource overuse system listener. */
    void removeResourceOveruseListenerForSystem(@NonNull IResourceOveruseListener listener);

    /** Sets whether or not a package is killable on resource overuse. */
    void setKillablePackageAsUser(String packageName, UserHandle userHandle,
                                         boolean isKillable);

    /** Returns the list of package killable states on resource overuse for the user. */
    @NonNull
    List<PackageKillableState> getPackageKillableStatesAsUser(UserHandle userHandle);

    /** Sets the given resource overuse configurations. */
    @CarWatchdogManager.ReturnCode
    int setResourceOveruseConfigurations(
            List<ResourceOveruseConfiguration> configurations,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag)
            throws RemoteException;

    /** Returns the available resource overuse configurations. */
    @NonNull
    List<ResourceOveruseConfiguration> getResourceOveruseConfigurations(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag);

    /** Processes the latest I/O overuse stats */
    void latestIoOveruseStats(List<PackageIoOveruseStats> packageIoOveruseStats);

    /** Resets the resource overuse settings and stats for the given generic package names. */
    void resetResourceOveruseStats(Set<String> genericPackageNames);

    /**
     * Asynchronously fetches today's I/O usage stats for all packages collected during the
     * previous boot and sends them to the CarWatchdog daemon.
     *
     * TODO(b/407639143): Rename the method because the async callback is hosted on the ICarWatchdog
     * AIDL interface and not passed as an arg to the API, which is the general API format for
     * a async API.
     */
    void asyncFetchTodayIoUsageStats();

    /** Returns today's I/O usage stats for all packages collected during the previous boot. */
    List<UserPackageIoUsageStats> getTodayIoUsageStats();

    /** Deletes all data for specific user. */
    void deleteUser(@UserIdInt int userId);

    /** Handles intents from user notification actions. */
    void processUserNotificationIntent(Intent intent);

    /** Handles when system broadcast package changed action */
    void processActionPackageChanged(Intent intent);

    /** Disables a package for specific user until used. */
    boolean disablePackageForUser(String packageName, @UserIdInt int userId);

    /**
     * Sets the delay to handle resource overuse after the package is notified of resource overuse.
     */
    void setOveruseHandlingDelay(long millis);

    /** Writes to watchdog metadata file. */
    void writeMetadataFile();

    /**
     * Writes user package settings and stats to database. If database is marked as clean,
     * no writing is executed.
     */
    void writeToDatabase();
}
