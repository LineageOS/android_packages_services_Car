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

package com.android.car.watchdog;

import static android.automotive.watchdog.internal.ResourceOveruseActionType.KILLED;
import static android.automotive.watchdog.internal.ResourceOveruseActionType.KILLED_RECURRING_OVERUSE;
import static android.automotive.watchdog.internal.ResourceOveruseActionType.NOT_KILLED;
import static android.automotive.watchdog.internal.ResourceOveruseActionType.NOT_KILLED_USER_OPTED;
import static android.car.watchdog.CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NEVER;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NO;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_YES;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;

import static com.android.car.watchdog.CarWatchdogService.DEBUG;
import static com.android.car.watchdog.CarWatchdogService.TAG;
import static com.android.car.watchdog.PackageInfoHandler.SHARED_PACKAGE_PREFIX;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityThread;
import android.automotive.watchdog.ResourceType;
import android.automotive.watchdog.internal.ApplicationCategoryType;
import android.automotive.watchdog.internal.ComponentType;
import android.automotive.watchdog.internal.PackageIdentifier;
import android.automotive.watchdog.internal.PackageIoOveruseStats;
import android.automotive.watchdog.internal.PackageMetadata;
import android.automotive.watchdog.internal.PackageResourceOveruseAction;
import android.automotive.watchdog.internal.PerStateIoOveruseThreshold;
import android.automotive.watchdog.internal.ResourceSpecificConfiguration;
import android.car.builtin.util.Slogf;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.IResourceOveruseListener;
import android.car.watchdog.IoOveruseAlertThreshold;
import android.car.watchdog.IoOveruseConfiguration;
import android.car.watchdog.IoOveruseStats;
import android.car.watchdog.PackageKillableState;
import android.car.watchdog.PackageKillableState.KillableState;
import android.car.watchdog.PerStateBytes;
import android.car.watchdog.ResourceOveruseConfiguration;
import android.car.watchdog.ResourceOveruseStats;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Handles system resource performance monitoring module.
 */
public final class WatchdogPerfHandler {
    public static final String INTERNAL_APPLICATION_CATEGORY_TYPE_MAPS = "MAPS";
    public static final String INTERNAL_APPLICATION_CATEGORY_TYPE_MEDIA = "MEDIA";
    public static final String INTERNAL_APPLICATION_CATEGORY_TYPE_UNKNOWN = "UNKNOWN";

    private static final long MAX_WAIT_TIME_MILLS = 3_000;

    private final Context mContext;
    private final CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    private final PackageInfoHandler mPackageInfoHandler;
    private final Handler mMainHandler;
    private final Object mLock = new Object();
    /*
     * Cache of added resource overuse listeners by uid.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, PackageResourceUsage> mUsageByUserPackage = new ArrayMap<>();
    @GuardedBy("mLock")
    private final List<PackageResourceOveruseAction> mOveruseActionsByUserPackage =
            new ArrayList<>();
    @GuardedBy("mLock")
    private final SparseArray<ArrayList<ResourceOveruseListenerInfo>> mOveruseListenerInfosByUid =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<ArrayList<ResourceOveruseListenerInfo>>
            mOveruseSystemListenerInfosByUid = new SparseArray<>();
    /* Set of safe-to-kill system and vendor packages. */
    @GuardedBy("mLock")
    public final ArraySet<String> mSafeToKillPackages = new ArraySet<>();
    /* Default killable state for packages when not updated by the user. */
    @GuardedBy("mLock")
    public final ArraySet<String> mDefaultNotKillableGenericPackages = new ArraySet<>();
    @GuardedBy("mLock")
    private ZonedDateTime mLastStatsReportUTC;
    @GuardedBy("mLock")
    private List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>
            mPendingSetResourceOveruseConfigurationsRequest = null;
    @GuardedBy("mLock")
    boolean mIsConnectedToDaemon;

    public WatchdogPerfHandler(Context context, CarWatchdogDaemonHelper daemonHelper,
            PackageInfoHandler packageInfoHandler) {
        mContext = context;
        mCarWatchdogDaemonHelper = daemonHelper;
        mPackageInfoHandler = packageInfoHandler;
        mMainHandler = new Handler(Looper.getMainLooper());
        mLastStatsReportUTC = ZonedDateTime.now(ZoneOffset.UTC);
    }

    /** Initializes the handler. */
    public void init() {
        /*
         * TODO(b/183947162): Opt-in to receive package change broadcast and handle package enabled
         *  state changes.
         * TODO(b/192294393): Persist in-memory data: Read the current day's I/O overuse stats from
         *  database.
         * TODO(b/192665269): Fetch the safe-to-kill from daemon on initialization and update
         *  mSafeToKillPackages.
         */
        synchronized (mLock) {
            checkAndHandleDateChangeLocked();
        }
        if (DEBUG) {
            Slogf.d(TAG, "WatchdogPerfHandler is initialized");
        }
    }

    /** Releases the handler */
    public void release() {
        /* TODO(b/192294393): Write daily usage to SQLite DB storage. */
        if (DEBUG) {
            Slogf.d(TAG, "WatchdogPerfHandler is released");
        }
    }

    /** Dumps its state. */
    public void dump(IndentingPrintWriter writer) {
        /*
         * TODO(b/183436216): Implement this method.
         */
    }

    /** Retries any pending requests on re-connecting to the daemon */
    public void onDaemonConnectionChange(boolean isConnected) {
        synchronized (mLock) {
            mIsConnectedToDaemon = isConnected;
        }
        if (isConnected) {
            /*
             * Retry pending set resource overuse configuration request before processing any new
             * set/get requests. Thus notify the waiting requests only after the retry completes.
             */
            retryPendingSetResourceOveruseConfigurations();
        }
        synchronized (mLock) {
            mLock.notifyAll();
        }
    }

    /** Returns resource overuse stats for the calling package. */
    @NonNull
    public ResourceOveruseStats getResourceOveruseStats(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        Preconditions.checkArgument((maxStatsPeriod > 0),
                "Must provide valid maximum stats period");
        // When more resource stats are added, make this as optional.
        Preconditions.checkArgument((resourceOveruseFlag & FLAG_RESOURCE_OVERUSE_IO) != 0,
                "Must provide resource I/O overuse flag");
        int callingUid = Binder.getCallingUid();
        int callingUserId = UserHandle.getUserId(callingUid);
        UserHandle callingUserHandle = UserHandle.of(callingUserId);
        String genericPackageName =
                mPackageInfoHandler.getNamesForUids(new int[]{callingUid})
                        .get(callingUid, null);
        if (genericPackageName == null) {
            Slogf.w(TAG, "Failed to fetch package info for uid %d", callingUid);
            return new ResourceOveruseStats.Builder("", callingUserHandle).build();
        }
        ResourceOveruseStats.Builder statsBuilder =
                new ResourceOveruseStats.Builder(genericPackageName, callingUserHandle);
        statsBuilder.setIoOveruseStats(
                getIoOveruseStatsForPeriod(callingUserId, genericPackageName, maxStatsPeriod));
        if (DEBUG) {
            Slogf.d(TAG, "Returning all resource overuse stats for calling uid %d [user %d and "
                            + "package '%s']", callingUid, callingUserId, genericPackageName);
        }
        return statsBuilder.build();
    }

    /** Returns resource overuse stats for all packages. */
    @NonNull
    public List<ResourceOveruseStats> getAllResourceOveruseStats(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.MinimumStatsFlag int minimumStatsFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        Preconditions.checkArgument((maxStatsPeriod > 0),
                "Must provide valid maximum stats period");
        // When more resource types are added, make this as optional.
        Preconditions.checkArgument((resourceOveruseFlag & FLAG_RESOURCE_OVERUSE_IO) != 0,
                "Must provide resource I/O overuse flag");
        long minimumBytesWritten = getMinimumBytesWritten(minimumStatsFlag);
        List<ResourceOveruseStats> allStats = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mUsageByUserPackage.size(); ++i) {
                PackageResourceUsage usage = mUsageByUserPackage.valueAt(i);
                ResourceOveruseStats.Builder statsBuilder = usage.getResourceOveruseStatsBuilder();
                IoOveruseStats ioOveruseStats =
                        getIoOveruseStatsLocked(usage, minimumBytesWritten, maxStatsPeriod);
                if (ioOveruseStats == null) {
                    continue;
                }
                allStats.add(statsBuilder.setIoOveruseStats(ioOveruseStats).build());
            }
        }
        if (DEBUG) {
            Slogf.d(TAG, "Returning all resource overuse stats");
        }
        return allStats;
    }

    /** Returns resource overuse stats for the specified user package. */
    @NonNull
    public ResourceOveruseStats getResourceOveruseStatsForUserPackage(
            @NonNull String packageName, @NonNull UserHandle userHandle,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        Objects.requireNonNull(packageName, "Package name must be non-null");
        Objects.requireNonNull(userHandle, "User handle must be non-null");
        Preconditions.checkArgument(!userHandle.equals(UserHandle.ALL),
                "Must provide the user handle for a specific user");
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        Preconditions.checkArgument((maxStatsPeriod > 0),
                "Must provide valid maximum stats period");
        // When more resource types are added, make this as optional.
        Preconditions.checkArgument((resourceOveruseFlag & FLAG_RESOURCE_OVERUSE_IO) != 0,
                "Must provide resource I/O overuse flag");
        String genericPackageName =
                mPackageInfoHandler.getNameForUserPackage(packageName, userHandle.getIdentifier());
        if (genericPackageName == null) {
            throw new IllegalArgumentException("Package '" + packageName + "' not found");
        }
        ResourceOveruseStats.Builder statsBuilder =
                new ResourceOveruseStats.Builder(genericPackageName, userHandle);
        statsBuilder.setIoOveruseStats(getIoOveruseStatsForPeriod(userHandle.getIdentifier(),
                genericPackageName, maxStatsPeriod));
        if (DEBUG) {
            Slogf.d(TAG, "Returning resource overuse stats for user %d, package '%s', "
                    + "generic package '%s'", userHandle.getIdentifier(), packageName,
                    genericPackageName);
        }
        return statsBuilder.build();
    }

    /** Adds the resource overuse listener. */
    public void addResourceOveruseListener(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        synchronized (mLock) {
            addResourceOveruseListenerLocked(resourceOveruseFlag, listener,
                    mOveruseListenerInfosByUid);
        }
    }

    /** Removes the previously added resource overuse listener. */
    public void removeResourceOveruseListener(@NonNull IResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        synchronized (mLock) {
            removeResourceOveruseListenerLocked(listener, mOveruseListenerInfosByUid);
        }
    }

    /** Adds the resource overuse system listener. */
    public void addResourceOveruseListenerForSystem(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        synchronized (mLock) {
            addResourceOveruseListenerLocked(resourceOveruseFlag, listener,
                    mOveruseSystemListenerInfosByUid);
        }
    }

    /** Removes the previously added resource overuse system listener. */
    public void removeResourceOveruseListenerForSystem(@NonNull IResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        synchronized (mLock) {
            removeResourceOveruseListenerLocked(listener, mOveruseSystemListenerInfosByUid);
        }
    }

    /** Sets whether or not a package is killable on resource overuse. */
    public void setKillablePackageAsUser(String packageName, UserHandle userHandle,
            boolean isKillable) {
        Objects.requireNonNull(packageName, "Package name must be non-null");
        Objects.requireNonNull(userHandle, "User handle must be non-null");

        if (userHandle.equals(UserHandle.ALL)) {
            setPackageKillableStateForAllUsers(packageName, isKillable);
            return;
        }
        int userId = userHandle.getIdentifier();
        String genericPackageName = mPackageInfoHandler.getNameForUserPackage(packageName, userId);
        if (genericPackageName == null) {
            throw new IllegalArgumentException("Package '" + packageName + "' not found");
        }
        String key = getUserPackageUniqueId(userId, genericPackageName);
        synchronized (mLock) {
            /*
             * When the queried package is not cached in {@link mUsageByUserPackage}, the set API
             * will update the killable state even when the package should never be killed.
             * But the get API will return the correct killable state. This behavior is tolerable
             * because in production the set API should be called only after the get API.
             * For instance, when this case happens by mistake and the package overuses resource
             * between the set and the get API calls, the daemon will provide correct killable
             * state when pushing the latest stats. Ergo, the invalid killable state doesn't have
             * any effect.
             */
            PackageResourceUsage usage = mUsageByUserPackage.get(key);
            if (usage == null) {
                usage = new PackageResourceUsage(userId, genericPackageName);
            }
            if (!usage.setKillableStateLocked(isKillable)) {
                Slogf.e(TAG, "User %d cannot set killable state for package '%s'",
                        userHandle.getIdentifier(), genericPackageName);
                throw new IllegalArgumentException("Package killable state is not updatable");
            }
            mUsageByUserPackage.put(key, usage);
        }
        if (DEBUG) {
            Slogf.d(TAG, "Successfully set killable package state for user %d", userId);
        }
    }

    private void setPackageKillableStateForAllUsers(String packageName, boolean isKillable) {
        UserManager userManager = UserManager.get(mContext);
        List<UserInfo> userInfos = userManager.getAliveUsers();
        String genericPackageName = null;
        synchronized (mLock) {
            for (int i = 0; i < userInfos.size(); ++i) {
                int userId = userInfos.get(i).id;
                String name = mPackageInfoHandler.getNameForUserPackage(packageName, userId);
                if (name == null) {
                    continue;
                }
                genericPackageName = name;
                String key = getUserPackageUniqueId(userId, genericPackageName);
                PackageResourceUsage usage = mUsageByUserPackage.get(key);
                if (usage == null) {
                    continue;
                }
                if (!usage.setKillableStateLocked(isKillable)) {
                    Slogf.e(TAG, "Cannot set killable state for package '%s'", packageName);
                    throw new IllegalArgumentException(
                            "Package killable state is not updatable");
                }
            }
            if (genericPackageName != null) {
                if (!isKillable) {
                    mDefaultNotKillableGenericPackages.add(genericPackageName);
                } else {
                    mDefaultNotKillableGenericPackages.remove(genericPackageName);
                }
            }
        }
        if (DEBUG) {
            Slogf.d(TAG, "Successfully set killable package state for all users");
        }
    }

    /** Returns the list of package killable states on resource overuse for the user. */
    @NonNull
    public List<PackageKillableState> getPackageKillableStatesAsUser(UserHandle userHandle) {
        Objects.requireNonNull(userHandle, "User handle must be non-null");
        PackageManager pm = mContext.getPackageManager();
        if (!userHandle.equals(UserHandle.ALL)) {
            if (DEBUG) {
                Slogf.d(TAG, "Returning all package killable states for user %d",
                        userHandle.getIdentifier());
            }
            return getPackageKillableStatesForUserId(userHandle.getIdentifier(), pm);
        }
        List<PackageKillableState> packageKillableStates = new ArrayList<>();
        UserManager userManager = UserManager.get(mContext);
        List<UserInfo> userInfos = userManager.getAliveUsers();
        for (int i = 0; i < userInfos.size(); ++i) {
            packageKillableStates.addAll(
                    getPackageKillableStatesForUserId(userInfos.get(i).id, pm));
        }
        if (DEBUG) {
            Slogf.d(TAG, "Returning all package killable states for all users");
        }
        return packageKillableStates;
    }

    private List<PackageKillableState> getPackageKillableStatesForUserId(int userId,
            PackageManager pm) {
        List<PackageInfo> packageInfos = pm.getInstalledPackagesAsUser(/* flags= */0, userId);
        List<PackageKillableState> states = new ArrayList<>();
        synchronized (mLock) {
            ArrayMap<String, List<ApplicationInfo>> applicationInfosBySharedPackage =
                    new ArrayMap<>();
            for (int i = 0; i < packageInfos.size(); ++i) {
                PackageInfo packageInfo = packageInfos.get(i);
                String genericPackageName = mPackageInfoHandler.getNameForPackage(packageInfo);
                if (packageInfo.sharedUserId == null) {
                    int killableState = getPackageKillableStateForUserPackageLocked(
                            userId, genericPackageName,
                            mPackageInfoHandler.getComponentType(packageInfo.applicationInfo),
                            mSafeToKillPackages.contains(genericPackageName));
                    states.add(new PackageKillableState(packageInfo.packageName, userId,
                            killableState));
                    continue;
                }
                List<ApplicationInfo> applicationInfos =
                        applicationInfosBySharedPackage.get(genericPackageName);
                if (applicationInfos == null) {
                    applicationInfos = new ArrayList<>();
                }
                applicationInfos.add(packageInfo.applicationInfo);
                applicationInfosBySharedPackage.put(genericPackageName, applicationInfos);
            }
            for (Map.Entry<String, List<ApplicationInfo>> entry :
                    applicationInfosBySharedPackage.entrySet()) {
                String genericPackageName = entry.getKey();
                List<ApplicationInfo> applicationInfos = entry.getValue();
                int componentType = mPackageInfoHandler.getSharedComponentType(
                        applicationInfos, genericPackageName);
                boolean isSafeToKill = mSafeToKillPackages.contains(genericPackageName);
                for (int i = 0; i < applicationInfos.size(); ++i) {
                    isSafeToKill = isSafeToKill
                            || mSafeToKillPackages.contains(applicationInfos.get(i).packageName);
                }
                int killableState = getPackageKillableStateForUserPackageLocked(
                        userId, genericPackageName, componentType, isSafeToKill);
                for (int i = 0; i < applicationInfos.size(); ++i) {
                    states.add(new PackageKillableState(
                            applicationInfos.get(i).packageName, userId, killableState));
                }
            }
        }
        if (DEBUG) {
            Slogf.d(TAG, "Returning the package killable states for user packages");
        }
        return states;
    }

    /** Sets the given resource overuse configurations. */
    @CarWatchdogManager.ReturnCode
    public int setResourceOveruseConfigurations(
            List<ResourceOveruseConfiguration> configurations,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag)
            throws RemoteException {
        Objects.requireNonNull(configurations, "Configurations must be non-null");
        Preconditions.checkArgument((configurations.size() > 0),
                "Must provide at least one configuration");
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        ArraySet<Integer> seenComponentTypes = new ArraySet<>();
        List<android.automotive.watchdog.internal.ResourceOveruseConfiguration> internalConfigs =
                new ArrayList<>();
        for (int i = 0; i < configurations.size(); ++i) {
            ResourceOveruseConfiguration config = configurations.get(i);
            /*
             * TODO(b/186119640): Make sure the validation done here matches the validation done in
             *  the daemon so set requests retried at a later time will complete successfully.
             */
            int componentType = config.getComponentType();
            if (toComponentTypeStr(componentType).equals("UNKNOWN")) {
                throw new IllegalArgumentException("Invalid component type in the configuration");
            }
            if (seenComponentTypes.contains(componentType)) {
                throw new IllegalArgumentException(
                        "Cannot provide duplicate configurations for the same component type");
            }
            if ((resourceOveruseFlag & FLAG_RESOURCE_OVERUSE_IO) != 0
                    && config.getIoOveruseConfiguration() == null) {
                throw new IllegalArgumentException("Must provide I/O overuse configuration");
            }
            seenComponentTypes.add(config.getComponentType());
            internalConfigs.add(toInternalResourceOveruseConfiguration(config,
                    resourceOveruseFlag));
        }
        synchronized (mLock) {
            if (!mIsConnectedToDaemon) {
                setPendingSetResourceOveruseConfigurationsRequestLocked(internalConfigs);
                return CarWatchdogManager.RETURN_CODE_SUCCESS;
            }
            /* Verify no pending request in progress. */
            setPendingSetResourceOveruseConfigurationsRequestLocked(null);
        }
        return setResourceOveruseConfigurationsInternal(internalConfigs,
                /* isPendingRequest= */ false);
    }

    /** Returns the available resource overuse configurations. */
    @NonNull
    public List<ResourceOveruseConfiguration> getResourceOveruseConfigurations(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag) {
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        if (!isConnectedToDaemon()) {
            throw new IllegalStateException("Car watchdog daemon is not connected");
        }
        synchronized (mLock) {
            /* Verify no pending request in progress. */
            setPendingSetResourceOveruseConfigurationsRequestLocked(null);
        }
        List<android.automotive.watchdog.internal.ResourceOveruseConfiguration> internalConfigs =
                new ArrayList<>();
        try {
            internalConfigs = mCarWatchdogDaemonHelper.getResourceOveruseConfigurations();
        } catch (RemoteException | RuntimeException e) {
            Slogf.w(TAG, e, "Failed to fetch resource overuse configurations");
            throw new IllegalStateException(e);
        }
        List<ResourceOveruseConfiguration> configs = new ArrayList<>();
        for (int i = 0; i < internalConfigs.size(); ++i) {
            configs.add(
                    toResourceOveruseConfiguration(internalConfigs.get(i), resourceOveruseFlag));
        }
        if (DEBUG) {
            Slogf.d(TAG, "Returning the resource overuse configuration");
        }
        return configs;
    }

    /** Processes the latest I/O overuse stats */
    public void latestIoOveruseStats(List<PackageIoOveruseStats> packageIoOveruseStats) {
        int[] uids = new int[packageIoOveruseStats.size()];
        for (int i = 0; i < packageIoOveruseStats.size(); ++i) {
            uids[i] = packageIoOveruseStats.get(i).uid;
        }
        SparseArray<String> genericPackageNamesByUid = mPackageInfoHandler.getNamesForUids(uids);
        synchronized (mLock) {
            checkAndHandleDateChangeLocked();
            for (int i = 0; i < packageIoOveruseStats.size(); ++i) {
                PackageIoOveruseStats stats = packageIoOveruseStats.get(i);
                String genericPackageName = genericPackageNamesByUid.get(stats.uid);
                if (genericPackageName == null) {
                    continue;
                }
                int userId = UserHandle.getUserId(stats.uid);
                PackageResourceUsage usage = cacheAndFetchUsageLocked(userId, genericPackageName,
                        stats.ioOveruseStats);
                if (stats.shouldNotify) {
                    /*
                     * Packages that exceed the warn threshold percentage should be notified as well
                     * and only the daemon is aware of such packages. Thus the flag is used to
                     * indicate which packages should be notified.
                     */
                    ResourceOveruseStats resourceOveruseStats =
                            usage.getResourceOveruseStatsBuilder().setIoOveruseStats(
                                    usage.getIoOveruseStatsLocked()).build();
                    notifyResourceOveruseStatsLocked(stats.uid, resourceOveruseStats);
                }

                if (!usage.ioUsage.exceedsThreshold()) {
                    continue;
                }
                PackageResourceOveruseAction overuseAction = new PackageResourceOveruseAction();
                overuseAction.packageIdentifier = new PackageIdentifier();
                overuseAction.packageIdentifier.name = genericPackageName;
                overuseAction.packageIdentifier.uid = stats.uid;
                overuseAction.resourceTypes = new int[]{ ResourceType.IO };
                overuseAction.resourceOveruseActionType = NOT_KILLED;
                /*
                 * No action required on I/O overuse on one of the following cases:
                 * #1 The package is not safe to kill as it is critical for system stability.
                 * #2 The package has no recurring overuse behavior and the user opted to not
                 *    kill the package so honor the user's decision.
                 */
                int killableState = usage.getKillableStateLocked();
                if (killableState == KILLABLE_STATE_NEVER) {
                    mOveruseActionsByUserPackage.add(overuseAction);
                    continue;
                }
                boolean hasRecurringOveruse = isRecurringOveruseLocked(usage);
                if (!hasRecurringOveruse && killableState == KILLABLE_STATE_NO) {
                    overuseAction.resourceOveruseActionType = NOT_KILLED_USER_OPTED;
                    mOveruseActionsByUserPackage.add(overuseAction);
                    continue;
                }
                IPackageManager packageManager = ActivityThread.getPackageManager();
                List<String> packages = Collections.singletonList(genericPackageName);
                if (usage.isSharedPackage()) {
                    packages = mPackageInfoHandler.getPackagesForUid(
                            stats.uid, genericPackageName);
                }
                for (int pkgIdx = 0; pkgIdx < packages.size(); ++pkgIdx) {
                    String pkg = packages.get(pkgIdx);
                    try {
                        int oldEnabledState = -1;
                        if (!hasRecurringOveruse) {
                            oldEnabledState = packageManager.getApplicationEnabledSetting(
                                    pkg, userId);
                            if (oldEnabledState == COMPONENT_ENABLED_STATE_DISABLED
                                    || oldEnabledState == COMPONENT_ENABLED_STATE_DISABLED_USER
                                    || oldEnabledState
                                    == COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                                continue;
                            }
                        }
                        packageManager.setApplicationEnabledSetting(pkg,
                                COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED, /* flags= */ 0, userId,
                                mContext.getPackageName());
                        overuseAction.resourceOveruseActionType = hasRecurringOveruse
                                ? KILLED_RECURRING_OVERUSE : KILLED;
                        if (oldEnabledState != -1) {
                            usage.oldEnabledStateByPackage.put(pkg, oldEnabledState);
                        }
                    } catch (RemoteException e) {
                        Slogf.e(TAG, "Failed to disable application for user %d, package '%s'",
                                userId, pkg);
                    }
                }
                mOveruseActionsByUserPackage.add(overuseAction);
            }
            if (!mOveruseActionsByUserPackage.isEmpty()) {
                mMainHandler.sendMessage(obtainMessage(
                        WatchdogPerfHandler::notifyActionsTakenOnOveruse, this));
            }
        }
        if (DEBUG) {
            Slogf.d(TAG, "Processed latest I/O overuse stats");
        }
    }

    /** Notify daemon about the actions take on resource overuse */
    public void notifyActionsTakenOnOveruse() {
        List<PackageResourceOveruseAction> actions;
        synchronized (mLock) {
            if (mOveruseActionsByUserPackage.isEmpty()) {
                return;
            }
            actions = new ArrayList<>(mOveruseActionsByUserPackage);
            mOveruseActionsByUserPackage.clear();
        }
        try {
            mCarWatchdogDaemonHelper.actionTakenOnResourceOveruse(actions);
        } catch (RemoteException | RuntimeException e) {
            Slogf.w(TAG, e, "Failed to notify car watchdog daemon of actions taken on resource "
                    + "overuse");
        }
        if (DEBUG) {
            Slogf.d(TAG, "Notified car watchdog daemon of actions taken on resource overuse");
        }
    }

    /** Resets the resource overuse stats for the given generic package names. */
    public void resetResourceOveruseStats(Set<String> genericPackageNames) {
        synchronized (mLock) {
            for (int i = 0; i < mUsageByUserPackage.size(); ++i) {
                PackageResourceUsage usage = mUsageByUserPackage.valueAt(i);
                if (genericPackageNames.contains(usage.genericPackageName)) {
                    usage.resetStatsLocked();
                    /*
                     * TODO(b/192294393): When the stats are persisted in local DB, reset the stats
                     *  for this package from local DB.
                     */
                }
            }
        }
    }

    @GuardedBy("mLock")
    private int getPackageKillableStateForUserPackageLocked(
            int userId, String genericPackageName, int componentType, boolean isSafeToKill) {
        String key = getUserPackageUniqueId(userId, genericPackageName);
        PackageResourceUsage usage = mUsageByUserPackage.get(key);
        if (usage == null) {
            usage = new PackageResourceUsage(userId, genericPackageName);
        }
        int killableState = usage.syncAndFetchKillableStateLocked(componentType, isSafeToKill);
        mUsageByUserPackage.put(key, usage);
        return killableState;
    }

    @GuardedBy("mLock")
    private void notifyResourceOveruseStatsLocked(int uid,
            ResourceOveruseStats resourceOveruseStats) {
        String genericPackageName = resourceOveruseStats.getPackageName();
        ArrayList<ResourceOveruseListenerInfo> listenerInfos = mOveruseListenerInfosByUid.get(uid);
        if (listenerInfos != null) {
            for (int i = 0; i < listenerInfos.size(); ++i) {
                listenerInfos.get(i).notifyListener(
                        FLAG_RESOURCE_OVERUSE_IO, uid, genericPackageName, resourceOveruseStats);
            }
        }
        for (int i = 0; i < mOveruseSystemListenerInfosByUid.size(); ++i) {
            ArrayList<ResourceOveruseListenerInfo> systemListenerInfos =
                    mOveruseSystemListenerInfosByUid.valueAt(i);
            for (int j = 0; j < systemListenerInfos.size(); ++j) {
                systemListenerInfos.get(j).notifyListener(
                        FLAG_RESOURCE_OVERUSE_IO, uid, genericPackageName, resourceOveruseStats);
            }
        }
        if (DEBUG) {
            Slogf.d(TAG, "Notified resource overuse stats to listening applications");
        }
    }

    @GuardedBy("mLock")
    private void checkAndHandleDateChangeLocked() {
        ZonedDateTime previousUTC = mLastStatsReportUTC;
        mLastStatsReportUTC = ZonedDateTime.now(ZoneOffset.UTC);
        if (mLastStatsReportUTC.getDayOfYear() == previousUTC.getDayOfYear()
                && mLastStatsReportUTC.getYear() == previousUTC.getYear()) {
            return;
        }
        for (int i = 0; i < mUsageByUserPackage.size(); ++i) {
            PackageResourceUsage usage = mUsageByUserPackage.valueAt(i);
            // Forgive the daily disabled package on date change.
            for (Map.Entry<String, Integer> entry : usage.oldEnabledStateByPackage.entrySet()) {
                try {
                    IPackageManager packageManager = ActivityThread.getPackageManager();
                    if (packageManager.getApplicationEnabledSetting(entry.getKey(),
                            usage.userId)
                            != COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                        continue;
                    }
                    packageManager.setApplicationEnabledSetting(entry.getKey(),
                            entry.getValue(),
                            /* flags= */ 0, usage.userId, mContext.getPackageName());
                } catch (RemoteException e) {
                    Slogf.e(TAG,
                            "Failed to reset enabled setting for disabled package '%s', user '%d'",
                                    usage.genericPackageName, usage.userId);
                }
            }
            /* TODO(b/192294393): Stash the old usage into SQLite DB storage. */
            usage.resetStatsLocked();
        }
        if (DEBUG) {
            Slogf.d(TAG, "Handled date change successfully");
        }
    }

    @GuardedBy("mLock")
    private PackageResourceUsage cacheAndFetchUsageLocked(
            @UserIdInt int userId, String genericPackageName,
            android.automotive.watchdog.IoOveruseStats internalStats) {
        String key = getUserPackageUniqueId(userId, genericPackageName);
        PackageResourceUsage usage = mUsageByUserPackage.get(key);
        if (usage == null) {
            usage = new PackageResourceUsage(userId, genericPackageName);
        }
        usage.updateLocked(internalStats);
        mUsageByUserPackage.put(key, usage);
        return usage;
    }

    @GuardedBy("mLock")
    private boolean isRecurringOveruseLocked(PackageResourceUsage ioUsage) {
        /*
         * TODO(b/192294393): Look up I/O overuse history and determine whether or not the package
         *  has recurring I/O overuse behavior.
         */
        return false;
    }

    private IoOveruseStats getIoOveruseStatsForPeriod(int userId, String genericPackageName,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        synchronized (mLock) {
            String key = getUserPackageUniqueId(userId, genericPackageName);
            PackageResourceUsage usage = mUsageByUserPackage.get(key);
            if (usage == null) {
                return null;
            }
            return getIoOveruseStatsLocked(usage, /* minimumBytesWritten= */ 0, maxStatsPeriod);
        }
    }

    @GuardedBy("mLock")
    private IoOveruseStats getIoOveruseStatsLocked(PackageResourceUsage usage,
            long minimumBytesWritten, @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        IoOveruseStats stats = usage.getIoOveruseStatsLocked();
        long totalBytesWritten = stats != null ? stats.getTotalBytesWritten() : 0;
        /*
         * TODO(b/185431129): When maxStatsPeriod > current day, populate the historical stats
         *  from the local database. Also handle the case where the package doesn't have current
         *  day stats but has historical stats.
         */
        if (totalBytesWritten < minimumBytesWritten) {
            return null;
        }
        return stats;
    }

    @GuardedBy("mLock")
    private void addResourceOveruseListenerLocked(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener,
            SparseArray<ArrayList<ResourceOveruseListenerInfo>> listenerInfosByUid) {
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        boolean isListenerForSystem = listenerInfosByUid == mOveruseSystemListenerInfosByUid;
        String listenerType = isListenerForSystem ? "resource overuse listener for system" :
                "resource overuse listener";

        IBinder binder = listener.asBinder();
        ArrayList<ResourceOveruseListenerInfo> listenerInfos = listenerInfosByUid.get(callingUid);
        if (listenerInfos == null) {
            listenerInfos = new ArrayList<>();
            listenerInfosByUid.put(callingUid, listenerInfos);
        }
        for (int i = 0; i < listenerInfos.size(); ++i) {
            if (listenerInfos.get(i).listener.asBinder() == binder) {
                throw new IllegalStateException(
                        "Cannot add " + listenerType + " as it is already added");
            }
        }

        ResourceOveruseListenerInfo listenerInfo = new ResourceOveruseListenerInfo(listener,
                resourceOveruseFlag, callingPid, callingUid, isListenerForSystem);
        try {
            listenerInfo.linkToDeath();
        } catch (RemoteException e) {
            Slogf.w(TAG, "Cannot add %s: linkToDeath to listener failed", listenerType);
            return;
        }
        listenerInfos.add(listenerInfo);
        if (DEBUG) {
            Slogf.d(TAG, "The %s (pid: %d, uid: %d) is added", listenerType,
                    callingPid, callingUid);
        }
    }

    @GuardedBy("mLock")
    private void removeResourceOveruseListenerLocked(@NonNull IResourceOveruseListener listener,
            SparseArray<ArrayList<ResourceOveruseListenerInfo>> listenerInfosByUid) {
        int callingUid = Binder.getCallingUid();
        String listenerType = listenerInfosByUid == mOveruseSystemListenerInfosByUid
                ? "resource overuse system listener" : "resource overuse listener";
        ArrayList<ResourceOveruseListenerInfo> listenerInfos = listenerInfosByUid.get(callingUid);
        if (listenerInfos == null) {
            Slogf.w(TAG, "Cannot remove the %s: it has not been registered before", listenerType);
            return;
        }
        IBinder binder = listener.asBinder();
        ResourceOveruseListenerInfo cachedListenerInfo = null;
        for (int i = 0; i < listenerInfos.size(); ++i) {
            if (listenerInfos.get(i).listener.asBinder() == binder) {
                cachedListenerInfo = listenerInfos.get(i);
                break;
            }
        }
        if (cachedListenerInfo == null) {
            Slogf.w(TAG, "Cannot remove the %s: it has not been registered before", listenerType);
            return;
        }
        cachedListenerInfo.unlinkToDeath();
        listenerInfos.remove(cachedListenerInfo);
        if (listenerInfos.isEmpty()) {
            listenerInfosByUid.remove(callingUid);
        }
        if (DEBUG) {
            Slogf.d(TAG, "The %s (pid: %d, uid: %d) is removed", listenerType,
                    cachedListenerInfo.pid, cachedListenerInfo.uid);
        }
    }

    @GuardedBy("mLock")
    private void setPendingSetResourceOveruseConfigurationsRequestLocked(
            List<android.automotive.watchdog.internal.ResourceOveruseConfiguration> configs) {
        if (mPendingSetResourceOveruseConfigurationsRequest != null) {
            if (mPendingSetResourceOveruseConfigurationsRequest == configs) {
                return;
            }
            throw new IllegalStateException(
                    "Pending setResourceOveruseConfigurations request in progress");
        }
        mPendingSetResourceOveruseConfigurationsRequest = configs;
    }

    private void retryPendingSetResourceOveruseConfigurations() {
        List<android.automotive.watchdog.internal.ResourceOveruseConfiguration> configs;
        synchronized (mLock) {
            if (mPendingSetResourceOveruseConfigurationsRequest == null) {
                return;
            }
            configs = mPendingSetResourceOveruseConfigurationsRequest;
        }
        try {
            int result = setResourceOveruseConfigurationsInternal(configs,
                    /* isPendingRequest= */ true);
            if (result != CarWatchdogManager.RETURN_CODE_SUCCESS) {
                Slogf.e(TAG, "Failed to set pending resource overuse configurations. Return code "
                        + "%d", result);
            }
        } catch (Exception e) {
            Slogf.e(TAG, e, "Exception on set pending resource overuse configurations");
        }
    }

    private int setResourceOveruseConfigurationsInternal(
            List<android.automotive.watchdog.internal.ResourceOveruseConfiguration> configs,
            boolean isPendingRequest) throws RemoteException {
        boolean doClearPendingRequest = isPendingRequest;
        try {
            mCarWatchdogDaemonHelper.updateResourceOveruseConfigurations(configs);
        } catch (RemoteException e) {
            if (e instanceof TransactionTooLargeException) {
                throw e;
            }
            Slogf.e(TAG, e, "Remote exception on set resource overuse configuration");
            synchronized (mLock) {
                setPendingSetResourceOveruseConfigurationsRequestLocked(configs);
            }
            doClearPendingRequest = false;
            return CarWatchdogManager.RETURN_CODE_SUCCESS;
        } finally {
            if (doClearPendingRequest) {
                synchronized (mLock) {
                    mPendingSetResourceOveruseConfigurationsRequest = null;
                }
            }
        }
        /* TODO(b/192665269): Fetch safe-to-kill list from daemon and update mSafeToKillPackages. */
        if (DEBUG) {
            Slogf.d(TAG, "Set the resource overuse configuration successfully");
        }
        return CarWatchdogManager.RETURN_CODE_SUCCESS;
    }

    private boolean isConnectedToDaemon() {
        synchronized (mLock) {
            long startTimeMillis = SystemClock.uptimeMillis();
            long sleptDurationMillis = SystemClock.uptimeMillis() - startTimeMillis;
            while (!mIsConnectedToDaemon && sleptDurationMillis < MAX_WAIT_TIME_MILLS) {
                try {
                    mLock.wait(MAX_WAIT_TIME_MILLS - sleptDurationMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    continue;
                } finally {
                    sleptDurationMillis = SystemClock.uptimeMillis() - startTimeMillis;
                }
                break;
            }
            return mIsConnectedToDaemon;
        }
    }

    private static String getUserPackageUniqueId(int userId, String genericPackageName) {
        return String.valueOf(userId) + ":" + genericPackageName;
    }

    @VisibleForTesting
    static IoOveruseStats.Builder toIoOveruseStatsBuilder(
            android.automotive.watchdog.IoOveruseStats internalStats) {
        IoOveruseStats.Builder statsBuilder = new IoOveruseStats.Builder(
                internalStats.startTime, internalStats.durationInSeconds);
        statsBuilder.setRemainingWriteBytes(
                toPerStateBytes(internalStats.remainingWriteBytes));
        statsBuilder.setTotalBytesWritten(totalPerStateBytes(internalStats.writtenBytes));
        statsBuilder.setTotalOveruses(internalStats.totalOveruses);
        return statsBuilder;
    }

    private static PerStateBytes toPerStateBytes(
            android.automotive.watchdog.PerStateBytes internalPerStateBytes) {
        return new PerStateBytes(internalPerStateBytes.foregroundBytes,
                internalPerStateBytes.backgroundBytes, internalPerStateBytes.garageModeBytes);
    }

    private static long totalPerStateBytes(
            android.automotive.watchdog.PerStateBytes internalPerStateBytes) {
        BiFunction<Long, Long, Long> sum = (l, r) -> {
            return (Long.MAX_VALUE - l > r) ? l + r : Long.MAX_VALUE;
        };
        return sum.apply(sum.apply(internalPerStateBytes.foregroundBytes,
                internalPerStateBytes.backgroundBytes), internalPerStateBytes.garageModeBytes);
    }

    private static long getMinimumBytesWritten(
            @CarWatchdogManager.MinimumStatsFlag int minimumStatsIoFlag) {
        switch (minimumStatsIoFlag) {
            case 0:
                return 0;
            case CarWatchdogManager.FLAG_MINIMUM_STATS_IO_1_MB:
                return 1024 * 1024;
            case CarWatchdogManager.FLAG_MINIMUM_STATS_IO_100_MB:
                return 100 * 1024 * 1024;
            case CarWatchdogManager.FLAG_MINIMUM_STATS_IO_1_GB:
                return 1024 * 1024 * 1024;
            default:
                throw new IllegalArgumentException(
                        "Must provide valid minimum stats flag for I/O resource");
        }
    }

    private static android.automotive.watchdog.internal.ResourceOveruseConfiguration
            toInternalResourceOveruseConfiguration(ResourceOveruseConfiguration config,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag) {
        android.automotive.watchdog.internal.ResourceOveruseConfiguration internalConfig =
                new android.automotive.watchdog.internal.ResourceOveruseConfiguration();
        internalConfig.componentType = config.getComponentType();
        internalConfig.safeToKillPackages = config.getSafeToKillPackages();
        internalConfig.vendorPackagePrefixes = config.getVendorPackagePrefixes();
        internalConfig.packageMetadata = new ArrayList<>();
        for (Map.Entry<String, String> entry : config.getPackagesToAppCategoryTypes().entrySet()) {
            if (entry.getKey().isEmpty()) {
                continue;
            }
            PackageMetadata metadata = new PackageMetadata();
            metadata.packageName = entry.getKey();
            switch(entry.getValue()) {
                case ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MAPS:
                    metadata.appCategoryType = ApplicationCategoryType.MAPS;
                    break;
                case ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MEDIA:
                    metadata.appCategoryType = ApplicationCategoryType.MEDIA;
                    break;
                default:
                    continue;
            }
            internalConfig.packageMetadata.add(metadata);
        }
        internalConfig.resourceSpecificConfigurations = new ArrayList<>();
        if ((resourceOveruseFlag & FLAG_RESOURCE_OVERUSE_IO) != 0
                && config.getIoOveruseConfiguration() != null) {
            internalConfig.resourceSpecificConfigurations.add(
                    toResourceSpecificConfiguration(config.getComponentType(),
                            config.getIoOveruseConfiguration()));
        }
        return internalConfig;
    }

    private static ResourceSpecificConfiguration
            toResourceSpecificConfiguration(int componentType, IoOveruseConfiguration config) {
        android.automotive.watchdog.internal.IoOveruseConfiguration internalConfig =
                new android.automotive.watchdog.internal.IoOveruseConfiguration();
        internalConfig.componentLevelThresholds = toPerStateIoOveruseThreshold(
                toComponentTypeStr(componentType), config.getComponentLevelThresholds());
        internalConfig.packageSpecificThresholds = toPerStateIoOveruseThresholds(
                config.getPackageSpecificThresholds());
        internalConfig.categorySpecificThresholds = toPerStateIoOveruseThresholds(
                config.getAppCategorySpecificThresholds());
        for (int i = 0; i < internalConfig.categorySpecificThresholds.size(); ++i) {
            PerStateIoOveruseThreshold threshold = internalConfig.categorySpecificThresholds.get(i);
            switch(threshold.name) {
                case ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MAPS:
                    threshold.name = INTERNAL_APPLICATION_CATEGORY_TYPE_MAPS;
                    break;
                case ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MEDIA:
                    threshold.name = INTERNAL_APPLICATION_CATEGORY_TYPE_MEDIA;
                    break;
                default:
                    threshold.name = INTERNAL_APPLICATION_CATEGORY_TYPE_UNKNOWN;
            }
        }
        internalConfig.systemWideThresholds = toInternalIoOveruseAlertThresholds(
                config.getSystemWideThresholds());

        ResourceSpecificConfiguration resourceSpecificConfig = new ResourceSpecificConfiguration();
        resourceSpecificConfig.setIoOveruseConfiguration(internalConfig);
        return resourceSpecificConfig;
    }

    @VisibleForTesting
    static String toComponentTypeStr(int componentType) {
        switch(componentType) {
            case ComponentType.SYSTEM:
                return "SYSTEM";
            case ComponentType.VENDOR:
                return "VENDOR";
            case ComponentType.THIRD_PARTY:
                return "THIRD_PARTY";
            default:
                return "UNKNOWN";
        }
    }

    private static List<PerStateIoOveruseThreshold> toPerStateIoOveruseThresholds(
            Map<String, PerStateBytes> thresholds) {
        List<PerStateIoOveruseThreshold> internalThresholds = new ArrayList<>();
        for (Map.Entry<String, PerStateBytes> entry : thresholds.entrySet()) {
            if (!thresholds.isEmpty()) {
                internalThresholds.add(toPerStateIoOveruseThreshold(entry.getKey(),
                        entry.getValue()));
            }
        }
        return internalThresholds;
    }

    private static PerStateIoOveruseThreshold toPerStateIoOveruseThreshold(String name,
            PerStateBytes perStateBytes) {
        PerStateIoOveruseThreshold threshold = new PerStateIoOveruseThreshold();
        threshold.name = name;
        threshold.perStateWriteBytes = new android.automotive.watchdog.PerStateBytes();
        threshold.perStateWriteBytes.foregroundBytes = perStateBytes.getForegroundModeBytes();
        threshold.perStateWriteBytes.backgroundBytes = perStateBytes.getBackgroundModeBytes();
        threshold.perStateWriteBytes.garageModeBytes = perStateBytes.getGarageModeBytes();
        return threshold;
    }

    private static List<android.automotive.watchdog.internal.IoOveruseAlertThreshold>
            toInternalIoOveruseAlertThresholds(List<IoOveruseAlertThreshold> thresholds) {
        List<android.automotive.watchdog.internal.IoOveruseAlertThreshold> internalThresholds =
                new ArrayList<>();
        for (int i = 0; i < thresholds.size(); ++i) {
            if (thresholds.get(i).getDurationInSeconds() == 0
                    || thresholds.get(i).getWrittenBytesPerSecond() == 0) {
                continue;
            }
            android.automotive.watchdog.internal.IoOveruseAlertThreshold internalThreshold =
                    new android.automotive.watchdog.internal.IoOveruseAlertThreshold();
            internalThreshold.durationInSeconds = thresholds.get(i).getDurationInSeconds();
            internalThreshold.writtenBytesPerSecond = thresholds.get(i).getWrittenBytesPerSecond();
            internalThresholds.add(internalThreshold);
        }
        return internalThresholds;
    }

    private static ResourceOveruseConfiguration toResourceOveruseConfiguration(
            android.automotive.watchdog.internal.ResourceOveruseConfiguration internalConfig,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag) {
        ArrayMap<String, String> packagesToAppCategoryTypes = new ArrayMap<>();
        for (int i = 0; i < internalConfig.packageMetadata.size(); ++i) {
            String categoryTypeStr;
            switch (internalConfig.packageMetadata.get(i).appCategoryType) {
                case ApplicationCategoryType.MAPS:
                    categoryTypeStr = ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MAPS;
                    break;
                case ApplicationCategoryType.MEDIA:
                    categoryTypeStr = ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MEDIA;
                    break;
                default:
                    continue;
            }
            packagesToAppCategoryTypes.put(
                    internalConfig.packageMetadata.get(i).packageName, categoryTypeStr);
        }
        ResourceOveruseConfiguration.Builder configBuilder =
                new ResourceOveruseConfiguration.Builder(
                internalConfig.componentType,
                internalConfig.safeToKillPackages,
                internalConfig.vendorPackagePrefixes,
                packagesToAppCategoryTypes);
        for (ResourceSpecificConfiguration resourceSpecificConfig :
                internalConfig.resourceSpecificConfigurations) {
            if (resourceSpecificConfig.getTag()
                    == ResourceSpecificConfiguration.ioOveruseConfiguration
                    && (resourceOveruseFlag & FLAG_RESOURCE_OVERUSE_IO) != 0) {
                configBuilder.setIoOveruseConfiguration(toIoOveruseConfiguration(
                        resourceSpecificConfig.getIoOveruseConfiguration()));
            }
        }
        return configBuilder.build();
    }

    private static IoOveruseConfiguration toIoOveruseConfiguration(
            android.automotive.watchdog.internal.IoOveruseConfiguration internalConfig) {
        PerStateBytes componentLevelThresholds =
                toPerStateBytes(internalConfig.componentLevelThresholds.perStateWriteBytes);
        ArrayMap<String, PerStateBytes> packageSpecificThresholds =
                toPerStateBytesMap(internalConfig.packageSpecificThresholds);
        ArrayMap<String, PerStateBytes> appCategorySpecificThresholds =
                toPerStateBytesMap(internalConfig.categorySpecificThresholds);
        replaceKey(appCategorySpecificThresholds, INTERNAL_APPLICATION_CATEGORY_TYPE_MAPS,
                ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MAPS);
        replaceKey(appCategorySpecificThresholds, INTERNAL_APPLICATION_CATEGORY_TYPE_MEDIA,
                ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MEDIA);
        List<IoOveruseAlertThreshold> systemWideThresholds =
                toIoOveruseAlertThresholds(internalConfig.systemWideThresholds);

        IoOveruseConfiguration.Builder configBuilder = new IoOveruseConfiguration.Builder(
                componentLevelThresholds, packageSpecificThresholds, appCategorySpecificThresholds,
                systemWideThresholds);
        return configBuilder.build();
    }

    private static ArrayMap<String, PerStateBytes> toPerStateBytesMap(
            List<PerStateIoOveruseThreshold> thresholds) {
        ArrayMap<String, PerStateBytes> thresholdsMap = new ArrayMap<>();
        for (int i = 0; i < thresholds.size(); ++i) {
            thresholdsMap.put(
                    thresholds.get(i).name, toPerStateBytes(thresholds.get(i).perStateWriteBytes));
        }
        return thresholdsMap;
    }

    private static List<IoOveruseAlertThreshold> toIoOveruseAlertThresholds(
            List<android.automotive.watchdog.internal.IoOveruseAlertThreshold> internalThresholds) {
        List<IoOveruseAlertThreshold> thresholds = new ArrayList<>();
        for (int i = 0; i < internalThresholds.size(); ++i) {
            thresholds.add(new IoOveruseAlertThreshold(internalThresholds.get(i).durationInSeconds,
                    internalThresholds.get(i).writtenBytesPerSecond));
        }
        return thresholds;
    }

    private static void replaceKey(Map<String, PerStateBytes> map, String oldKey, String newKey) {
        PerStateBytes perStateBytes = map.get(oldKey);
        if (perStateBytes != null) {
            map.put(newKey, perStateBytes);
            map.remove(oldKey);
        }
    }

    private final class PackageResourceUsage {
        public final String genericPackageName;
        public @UserIdInt final int userId;
        @GuardedBy("mLock")
        public final PackageIoUsage ioUsage = new PackageIoUsage();
        @GuardedBy("mLock")
        public final ArrayMap<String, Integer> oldEnabledStateByPackage = new ArrayMap<>();
        @GuardedBy("mLock")
        private @KillableState int mKillableState;

        /** Must be called only after acquiring {@link mLock} */
        PackageResourceUsage(@UserIdInt int userId, String genericPackageName) {
            this.genericPackageName = genericPackageName;
            this.userId = userId;
            this.mKillableState = mDefaultNotKillableGenericPackages.contains(genericPackageName)
                    ? KILLABLE_STATE_NO : KILLABLE_STATE_YES;
        }

        public boolean isSharedPackage() {
            return this.genericPackageName.startsWith(SHARED_PACKAGE_PREFIX);
        }

        @GuardedBy("mLock")
        public void updateLocked(android.automotive.watchdog.IoOveruseStats internalStats) {
            if (!internalStats.killableOnOveruse) {
                /*
                 * Killable value specified in the internal stats is provided by the native daemon.
                 * This value reflects whether or not an application is safe-to-kill on overuse.
                 * This setting is from the I/O overuse configuration specified by the system and
                 * vendor services and doesn't reflect the user choices. Thus if the internal stats
                 * specify the application is not killable, the application is not safe-to-kill.
                 */
                mKillableState = KILLABLE_STATE_NEVER;
            } else if (mKillableState == KILLABLE_STATE_NEVER) {
                /*
                 * This case happens when a previously unsafe to kill system/vendor package was
                 * recently marked as safe-to-kill so update the old state to the default value.
                 */
                mKillableState = mDefaultNotKillableGenericPackages.contains(genericPackageName)
                        ? KILLABLE_STATE_NO : KILLABLE_STATE_YES;
            }
            ioUsage.update(internalStats);
        }

        public ResourceOveruseStats.Builder getResourceOveruseStatsBuilder() {
            return new ResourceOveruseStats.Builder(genericPackageName, UserHandle.of(userId));
        }

        @GuardedBy("mLock")
        public IoOveruseStats getIoOveruseStatsLocked() {
            if (!ioUsage.hasUsage()) {
                return null;
            }
            return ioUsage.getStatsBuilder().setKillableOnOveruse(
                        mKillableState != KILLABLE_STATE_NEVER).build();
        }

        @GuardedBy("mLock")
        public @KillableState int getKillableStateLocked() {
            return mKillableState;
        }

        @GuardedBy("mLock")
        public boolean setKillableStateLocked(boolean isKillable) {
            if (mKillableState == KILLABLE_STATE_NEVER) {
                return false;
            }
            mKillableState = isKillable ? KILLABLE_STATE_YES : KILLABLE_STATE_NO;
            return true;
        }

        @GuardedBy("mLock")
        public int syncAndFetchKillableStateLocked(int myComponentType, boolean isSafeToKill) {
            /*
             * The killable state goes out-of-sync:
             * 1. When the on-device safe-to-kill list is recently updated and the user package
             * didn't have any resource usage so the native daemon didn't update the killable state.
             * 2. When a package has no resource usage and is initialized outside of processing the
             * latest resource usage stats.
             */
            if (myComponentType != ComponentType.THIRD_PARTY && !isSafeToKill) {
                mKillableState = KILLABLE_STATE_NEVER;
            } else if (mKillableState == KILLABLE_STATE_NEVER) {
                mKillableState = mDefaultNotKillableGenericPackages.contains(genericPackageName)
                        ? KILLABLE_STATE_NO : KILLABLE_STATE_YES;
            }
            return mKillableState;
        }

        @GuardedBy("mLock")
        public void resetStatsLocked() {
            oldEnabledStateByPackage.clear();
            ioUsage.resetStats();
        }
    }

    private static final class PackageIoUsage {
        private android.automotive.watchdog.IoOveruseStats mIoOveruseStats;
        private android.automotive.watchdog.PerStateBytes mForgivenWriteBytes;
        private long mTotalTimesKilled;

        PackageIoUsage() {
            mTotalTimesKilled = 0;
        }

        public boolean hasUsage() {
            return mIoOveruseStats != null;
        }

        public void update(android.automotive.watchdog.IoOveruseStats internalStats) {
            mIoOveruseStats = internalStats;
            if (exceedsThreshold()) {
                /*
                 * Forgive written bytes on overuse as the package is either forgiven or killed on
                 * overuse. When the package is killed, the user may opt to open the corresponding
                 * app and the package should be forgiven anyways.
                 * NOTE: If this logic is updated, update the daemon side logic as well.
                 */
                mForgivenWriteBytes = internalStats.writtenBytes;
            }
        }

        public IoOveruseStats.Builder getStatsBuilder() {
            IoOveruseStats.Builder statsBuilder = toIoOveruseStatsBuilder(mIoOveruseStats);
            statsBuilder.setTotalTimesKilled(mTotalTimesKilled);
            return statsBuilder;
        }

        public boolean exceedsThreshold() {
            if (!hasUsage()) {
                return false;
            }
            android.automotive.watchdog.PerStateBytes remaining =
                    mIoOveruseStats.remainingWriteBytes;
            return remaining.foregroundBytes == 0 || remaining.backgroundBytes == 0
                    || remaining.garageModeBytes == 0;
        }

        public void resetStats() {
            mIoOveruseStats = null;
            mForgivenWriteBytes = null;
            mTotalTimesKilled = 0;
        }
    }

    private final class ResourceOveruseListenerInfo implements IBinder.DeathRecipient {
        public final IResourceOveruseListener listener;
        public final @CarWatchdogManager.ResourceOveruseFlag int flag;
        public final int pid;
        public final int uid;
        public final boolean isListenerForSystem;

        ResourceOveruseListenerInfo(IResourceOveruseListener listener,
                @CarWatchdogManager.ResourceOveruseFlag int flag, int pid, int uid,
                boolean isListenerForSystem) {
            this.listener = listener;
            this.flag = flag;
            this.pid = pid;
            this.uid = uid;
            this.isListenerForSystem = isListenerForSystem;
        }

        @Override
        public void binderDied() {
            Slogf.w(TAG, "Resource overuse listener%s (pid: %d) died",
                    isListenerForSystem ? " for system" : "", pid);
            Consumer<SparseArray<ArrayList<ResourceOveruseListenerInfo>>> removeListenerInfo =
                    listenerInfosByUid -> {
                        ArrayList<ResourceOveruseListenerInfo> listenerInfos =
                                listenerInfosByUid.get(uid);
                        if (listenerInfos == null) {
                            return;
                        }
                        listenerInfos.remove(this);
                        if (listenerInfos.isEmpty()) {
                            listenerInfosByUid.remove(uid);
                        }
                    };
            if (isListenerForSystem) {
                removeListenerInfo.accept(mOveruseSystemListenerInfosByUid);
            } else {
                removeListenerInfo.accept(mOveruseListenerInfosByUid);
            }
            unlinkToDeath();
        }

        public void notifyListener(@CarWatchdogManager.ResourceOveruseFlag int resourceType,
                int overusingUid, String overusingGenericPackageName,
                ResourceOveruseStats resourceOveruseStats) {
            if ((flag & resourceType) == 0) {
                return;
            }
            try {
                listener.onOveruse(resourceOveruseStats);
            } catch (RemoteException e) {
                Slogf.e(TAG, "Failed to notify %s (uid %d, pid: %d) of resource overuse by "
                                + "package(uid %d, generic package name '%s'): %s",
                        (isListenerForSystem ? "system listener" : "listener"), uid, pid,
                        overusingUid, overusingGenericPackageName, e);
            }
        }

        private void linkToDeath() throws RemoteException {
            listener.asBinder().linkToDeath(this, 0);
        }

        private void unlinkToDeath() {
            listener.asBinder().unlinkToDeath(this, 0);
        }
    }
}
