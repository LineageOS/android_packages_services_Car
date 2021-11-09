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

import static android.car.watchdog.CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO;
import static android.car.watchdog.CarWatchdogManager.STATS_PERIOD_CURRENT_DAY;
import static android.car.watchdog.CarWatchdogManager.STATS_PERIOD_PAST_15_DAYS;
import static android.car.watchdog.CarWatchdogManager.STATS_PERIOD_PAST_30_DAYS;
import static android.car.watchdog.CarWatchdogManager.STATS_PERIOD_PAST_3_DAYS;
import static android.car.watchdog.CarWatchdogManager.STATS_PERIOD_PAST_7_DAYS;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NEVER;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NO;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_YES;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import static com.android.car.CarStatsLog.CAR_WATCHDOG_IO_OVERUSE_STATS_REPORTED;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__KILL_REASON__KILLED_ON_IO_OVERUSE;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__GARAGE_MODE;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__USER_INTERACTION_MODE;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__USER_NO_INTERACTION_MODE;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__UID_STATE__UNKNOWN_UID_STATE;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;
import static com.android.car.watchdog.CarWatchdogService.DEBUG;
import static com.android.car.watchdog.CarWatchdogService.SYSTEM_INSTANCE;
import static com.android.car.watchdog.CarWatchdogService.TAG;
import static com.android.car.watchdog.PackageInfoHandler.SHARED_PACKAGE_PREFIX;
import static com.android.car.watchdog.WatchdogStorage.STATS_TEMPORAL_UNIT;
import static com.android.car.watchdog.WatchdogStorage.ZONE_OFFSET;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.automotive.watchdog.internal.ApplicationCategoryType;
import android.automotive.watchdog.internal.ComponentType;
import android.automotive.watchdog.internal.GarageMode;
import android.automotive.watchdog.internal.IoUsageStats;
import android.automotive.watchdog.internal.PackageIoOveruseStats;
import android.automotive.watchdog.internal.PackageMetadata;
import android.automotive.watchdog.internal.PerStateIoOveruseThreshold;
import android.automotive.watchdog.internal.ResourceSpecificConfiguration;
import android.automotive.watchdog.internal.UserPackageIoUsageStats;
import android.car.builtin.content.pm.PackageManagerHelper;
import android.car.builtin.util.Slogf;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import android.util.SparseArray;
import android.view.Display;

import com.android.car.CarLocalServices;
import com.android.car.CarServiceUtils;
import com.android.car.CarStatsLog;
import com.android.car.CarUxRestrictionsManagerService;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

    private static final long OVERUSE_HANDLING_DELAY_MILLS = 10_000;
    private static final long MAX_WAIT_TIME_MILLS = 3_000;

    // TODO(b/195425666): Define this constant as a resource overlay config with two values:
    //  1. Recurring overuse period - Period to calculate the recurring overuse.
    //  2. Recurring overuse threshold - Total overuses for recurring behavior.
    private static final int RECURRING_OVERUSE_THRESHOLD = 2;

    /**
     * Don't distract the user by sending user notifications/dialogs, killing foreground
     * applications, repeatedly killing persistent background services, or disabling any
     * application.
     */
    private static final int UX_STATE_NO_DISTRACTION = 1;
    /** The user can safely receive user notifications or dialogs. */
    private static final int UX_STATE_USER_NOTIFICATION = 2;
    /**
     * Any application or service can be safely killed/disabled. User notifications can be sent
     * only to the notification center.
     */
    private static final int UX_STATE_NO_INTERACTION = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"UX_STATE_"}, value = {
            UX_STATE_NO_DISTRACTION,
            UX_STATE_USER_NOTIFICATION,
            UX_STATE_NO_INTERACTION
    })
    private @interface UxStateType{}

    private final Context mContext;
    private final CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    private final PackageInfoHandler mPackageInfoHandler;
    private final Handler mMainHandler;
    private final Handler mServiceHandler;
    private final WatchdogStorage mWatchdogStorage;
    private final OveruseConfigurationCache mOveruseConfigurationCache;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArrayMap<String, PackageResourceUsage> mUsageByUserPackage = new ArrayMap<>();
    @GuardedBy("mLock")
    private final SparseArray<ArrayList<ResourceOveruseListenerInfo>> mOveruseListenerInfosByUid =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<ArrayList<ResourceOveruseListenerInfo>>
            mOveruseSystemListenerInfosByUid = new SparseArray<>();
    /** Default killable state for packages. Updated only for {@link UserHandle.ALL} user handle. */
    @GuardedBy("mLock")
    private final ArraySet<String> mDefaultNotKillableGenericPackages = new ArraySet<>();
    /** Keys in {@link mUsageByUserPackage} for user notification on resource overuse. */
    @GuardedBy("mLock")
    private final ArraySet<String> mUserNotifiablePackages = new ArraySet<>();
    /**
     * Keys in {@link mUsageByUserPackage} that should be killed/disabled due to resource overuse.
     */
    @GuardedBy("mLock")
    private final ArraySet<String> mActionableUserPackages = new ArraySet<>();
    @GuardedBy("mLock")
    private ZonedDateTime mLatestStatsReportDate;
    @GuardedBy("mLock")
    private List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>
            mPendingSetResourceOveruseConfigurationsRequest = null;
    @GuardedBy("mLock")
    private boolean mIsConnectedToDaemon;
    @GuardedBy("mLock")
    private boolean mIsWrittenToDatabase;
    @GuardedBy("mLock")
    private @UxStateType int mCurrentUxState;
    @GuardedBy("mLock")
    private CarUxRestrictions mCurrentUxRestrictions;
    @GuardedBy("mLock")
    private @GarageMode int mCurrentGarageMode;
    @GuardedBy("mLock")
    private TimeSourceInterface mTimeSource;
    @GuardedBy("mLock")
    private long mOveruseHandlingDelayMills;
    @GuardedBy("mLock")
    private long mRecurringOveruseThreshold;

    private final ICarUxRestrictionsChangeListener mCarUxRestrictionsChangeListener =
            new ICarUxRestrictionsChangeListener.Stub() {
                @Override
                public void onUxRestrictionsChanged(CarUxRestrictions restrictions) {
                    synchronized (mLock) {
                        mCurrentUxRestrictions = new CarUxRestrictions(restrictions);
                        applyCurrentUxRestrictionsLocked();
                    }
                }
            };

    public WatchdogPerfHandler(Context context, CarWatchdogDaemonHelper daemonHelper,
            PackageInfoHandler packageInfoHandler, WatchdogStorage watchdogStorage) {
        mContext = context;
        mCarWatchdogDaemonHelper = daemonHelper;
        mPackageInfoHandler = packageInfoHandler;
        mMainHandler = new Handler(Looper.getMainLooper());
        mServiceHandler = new Handler(CarServiceUtils.getHandlerThread(
                CarWatchdogService.class.getSimpleName()).getLooper());
        mWatchdogStorage = watchdogStorage;
        mOveruseConfigurationCache = new OveruseConfigurationCache();
        mTimeSource = SYSTEM_INSTANCE;
        mOveruseHandlingDelayMills = OVERUSE_HANDLING_DELAY_MILLS;
        mCurrentUxState = UX_STATE_NO_DISTRACTION;
        mCurrentGarageMode = GarageMode.GARAGE_MODE_OFF;
        mRecurringOveruseThreshold = RECURRING_OVERUSE_THRESHOLD;
    }

    /** Initializes the handler. */
    public void init() {
        /* First database read is expensive, so post it on a separate handler thread. */
        mServiceHandler.post(() -> {
            readFromDatabase();
            synchronized (mLock) {
                checkAndHandleDateChangeLocked();
                mIsWrittenToDatabase = false;
            }});

        CarLocalServices.getService(CarUxRestrictionsManagerService.class)
                .registerUxRestrictionsChangeListener(
                        mCarUxRestrictionsChangeListener, Display.DEFAULT_DISPLAY);

        if (DEBUG) {
            Slogf.d(TAG, "WatchdogPerfHandler is initialized");
        }
    }

    /** Releases resources. */
    public void release() {
        CarLocalServices.getService(CarUxRestrictionsManagerService.class)
                .unregisterUxRestrictionsChangeListener(mCarUxRestrictionsChangeListener);
    }

    /** Dumps its state. */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        /*
         * TODO(b/183436216): Implement this method.
         */
        mOveruseConfigurationCache.dump(writer);
    }

    /** Retries any pending requests on re-connecting to the daemon */
    public void onDaemonConnectionChange(boolean isConnected) {
        boolean hasPendingRequest;
        synchronized (mLock) {
            mIsConnectedToDaemon = isConnected;
            hasPendingRequest = mPendingSetResourceOveruseConfigurationsRequest != null;
        }
        if (isConnected) {
            if (hasPendingRequest) {
                /*
                 * Retry pending set resource overuse configuration request before processing any
                 * new set/get requests. Thus notify the waiting requests only after the retry
                 * completes.
                 */
                retryPendingSetResourceOveruseConfigurations();
            } else {
                /* Start fetch/sync configs only when there are no pending set requests because the
                 * above retry starts fetch/sync configs on success. If the retry fails, the daemon
                 * has crashed and shouldn't start fetchAndSyncResourceOveruseConfigurations.
                 */
                mMainHandler.post(this::fetchAndSyncResourceOveruseConfigurations);
            }
        }
        synchronized (mLock) {
            mLock.notifyAll();
        }
    }

    /** Updates the current UX state based on the display state. */
    public void onDisplayStateChanged(boolean isEnabled) {
        synchronized (mLock) {
            if (isEnabled) {
                mCurrentUxState = UX_STATE_NO_DISTRACTION;
                applyCurrentUxRestrictionsLocked();
            } else {
                mCurrentUxState = UX_STATE_NO_INTERACTION;
                performOveruseHandlingLocked();
            }
        }
    }

    /** Handles garage mode change. */
    public void onGarageModeChange(@GarageMode int garageMode) {
        synchronized (mLock) {
            mCurrentGarageMode = garageMode;
            if (mCurrentGarageMode == GarageMode.GARAGE_MODE_ON) {
                mCurrentUxState = UX_STATE_NO_INTERACTION;
                performOveruseHandlingLocked();
            }
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
        UserHandle callingUserHandle = Binder.getCallingUserHandle();
        int callingUserId = callingUserHandle.getIdentifier();
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
                usage = new PackageResourceUsage(userId, genericPackageName,
                        getDefaultKillableStateLocked(genericPackageName));
            }
            if (!usage.verifyAndSetKillableState(isKillable)) {
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
        int[] userIds = getAliveUserIds();
        String genericPackageName = null;
        synchronized (mLock) {
            for (int i = 0; i < userIds.length; ++i) {
                int userId = userIds[i];
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
                if (!usage.verifyAndSetKillableState(isKillable)) {
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
        int[] userIds = getAliveUserIds();
        for (int i = 0; i < userIds.length; ++i) {
            packageKillableStates.addAll(
                    getPackageKillableStatesForUserId(userIds[i], pm));
        }
        if (DEBUG) {
            Slogf.d(TAG, "Returning all package killable states for all users");
        }
        return packageKillableStates;
    }

    private List<PackageKillableState> getPackageKillableStatesForUserId(int userId,
            PackageManager pm) {
        List<PackageInfo> packageInfos = pm.getInstalledPackagesAsUser(/* flags= */ 0, userId);
        List<PackageKillableState> states = new ArrayList<>();
        synchronized (mLock) {
            ArrayMap<String, List<ApplicationInfo>> applicationInfosBySharedPackage =
                    new ArrayMap<>();
            for (int i = 0; i < packageInfos.size(); ++i) {
                PackageInfo packageInfo = packageInfos.get(i);
                String genericPackageName = mPackageInfoHandler.getNameForPackage(packageInfo);
                if (packageInfo.sharedUserId == null) {
                    int componentType = mPackageInfoHandler.getComponentType(
                            packageInfo.applicationInfo);
                    int killableState = getPackageKillableStateForUserPackageLocked(
                            userId, genericPackageName, componentType,
                            mOveruseConfigurationCache.isSafeToKill(
                                    genericPackageName, componentType, /* sharedPackages= */null));
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
                List<String> packageNames = new ArrayList<>(applicationInfos.size());
                for (int i = 0; i < applicationInfos.size(); ++i) {
                    packageNames.add(applicationInfos.get(i).packageName);
                }
                int killableState = getPackageKillableStateForUserPackageLocked(
                        userId, genericPackageName, componentType,
                        mOveruseConfigurationCache.isSafeToKill(
                                genericPackageName, componentType, packageNames));
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
        checkResourceOveruseConfigs(configurations, resourceOveruseFlag);
        List<android.automotive.watchdog.internal.ResourceOveruseConfiguration> internalConfigs =
                new ArrayList<>();
        for (int i = 0; i < configurations.size(); ++i) {
            internalConfigs.add(toInternalResourceOveruseConfiguration(configurations.get(i),
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
        ArraySet<String> overusingUserPackageKeys = new ArraySet<>();
        synchronized (mLock) {
            checkAndHandleDateChangeLocked();
            for (int i = 0; i < packageIoOveruseStats.size(); ++i) {
                PackageIoOveruseStats stats = packageIoOveruseStats.get(i);
                String genericPackageName = genericPackageNamesByUid.get(stats.uid);
                if (genericPackageName == null) {
                    continue;
                }
                PackageResourceUsage usage = cacheAndFetchUsageLocked(stats.uid, genericPackageName,
                        stats.ioOveruseStats, stats.forgivenWriteBytes);
                if (stats.shouldNotify) {
                    /*
                     * Packages that exceed the warn threshold percentage should be notified as well
                     * and only the daemon is aware of such packages. Thus the flag is used to
                     * indicate which packages should be notified.
                     */
                    ResourceOveruseStats resourceOveruseStats =
                            usage.getResourceOveruseStatsBuilder().setIoOveruseStats(
                                    usage.getIoOveruseStats()).build();
                    notifyResourceOveruseStatsLocked(stats.uid, resourceOveruseStats);
                }
                if (!usage.ioUsage.exceedsThreshold()) {
                    continue;
                }
                overusingUserPackageKeys.add(usage.getUniqueId());
                int killableState = usage.getKillableState();
                if (killableState == KILLABLE_STATE_NEVER) {
                    continue;
                }
                if (isRecurringOveruseLocked(usage)) {
                    String id = usage.getUniqueId();
                    mActionableUserPackages.add(id);
                    mUserNotifiablePackages.add(id);
                }
            }
            if (mCurrentUxState != UX_STATE_NO_DISTRACTION
                    && (!mActionableUserPackages.isEmpty() || !mUserNotifiablePackages.isEmpty())) {
                mMainHandler.postDelayed(() -> {
                    synchronized (mLock) {
                        performOveruseHandlingLocked();
                    }}, mOveruseHandlingDelayMills);
            }
        }
        if (!overusingUserPackageKeys.isEmpty()) {
            pushIoOveruseMetrics(overusingUserPackageKeys);
        }
        if (DEBUG) {
            Slogf.d(TAG, "Processed latest I/O overuse stats");
        }
    }

    /** Resets the resource overuse settings and stats for the given generic package names. */
    public void resetResourceOveruseStats(Set<String> genericPackageNames) {
        synchronized (mLock) {
            for (int i = 0; i < mUsageByUserPackage.size(); ++i) {
                PackageResourceUsage usage = mUsageByUserPackage.valueAt(i);
                if (!genericPackageNames.contains(usage.genericPackageName)) {
                    continue;
                }
                usage.resetStats();
                usage.verifyAndSetKillableState(/* isKillable= */ true);
                mWatchdogStorage.deleteUserPackage(usage.userId, usage.genericPackageName);
                mActionableUserPackages.remove(usage.getUniqueId());
                Slogf.i(TAG,
                        "Reset resource overuse settings and stats for user '%d' package '%s'",
                        usage.userId, usage.genericPackageName);
                try {
                    if (PackageManagerHelper.getApplicationEnabledSettingForUser(
                            usage.genericPackageName, usage.userId)
                            != COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                        continue;
                    }
                    PackageManagerHelper.setApplicationEnabledSettingForUser(
                            usage.genericPackageName, COMPONENT_ENABLED_STATE_ENABLED,
                            /* flags= */ 0, usage.userId, mContext.getPackageName());
                    Slogf.i(TAG, "Enabled user '%d' package '%s'", usage.userId,
                            usage.genericPackageName);
                } catch (RemoteException | IllegalArgumentException e) {
                    Slogf.e(TAG, e, "Failed to verify and enable user %d, package '%s'",
                            usage.userId, usage.genericPackageName);
                }
            }
        }
    }

    /** Returns today's I/O usage stats for all packages collected during the previous boot. */
    public List<UserPackageIoUsageStats> getTodayIoUsageStats() {
        List<UserPackageIoUsageStats> userPackageIoUsageStats = new ArrayList<>();
        List<WatchdogStorage.IoUsageStatsEntry> entries = mWatchdogStorage.getTodayIoUsageStats();
        for (int i = 0; i < entries.size(); ++i) {
            WatchdogStorage.IoUsageStatsEntry entry = entries.get(i);
            UserPackageIoUsageStats stats = new UserPackageIoUsageStats();
            stats.userId = entry.userId;
            stats.packageName = entry.packageName;
            stats.ioUsageStats = new IoUsageStats();
            android.automotive.watchdog.IoOveruseStats internalIoUsage =
                    entry.ioUsage.getInternalIoOveruseStats();
            stats.ioUsageStats.writtenBytes = internalIoUsage.writtenBytes;
            stats.ioUsageStats.forgivenWriteBytes = entry.ioUsage.getForgivenWriteBytes();
            stats.ioUsageStats.totalOveruses = internalIoUsage.totalOveruses;
            userPackageIoUsageStats.add(stats);
        }
        return userPackageIoUsageStats;
    }

    /** Deletes all data for specific user. */
    public void deleteUser(@UserIdInt int userId) {
        synchronized (mLock) {
            for (int i = mUsageByUserPackage.size() - 1; i >= 0; --i) {
                if (userId == mUsageByUserPackage.valueAt(i).userId) {
                    mUsageByUserPackage.removeAt(i);
                }
            }
            mWatchdogStorage.syncUsers(getAliveUserIds());
        }
        if (DEBUG) {
            Slogf.d(TAG, "Resource usage for user id: %d was deleted.", userId);
        }
    }

    /** Sets the time source. */
    public void setTimeSource(TimeSourceInterface timeSource) {
        synchronized (mLock) {
            mTimeSource = timeSource;
        }
    }

    /**
     * Sets the delay to handle resource overuse after the package is notified of resource overuse.
     */
    public void setOveruseHandlingDelay(long millis) {
        synchronized (mLock) {
            mOveruseHandlingDelayMills = millis;
        }
    }

    /** Sets the threshold for recurring overuse behavior. */
    public void setRecurringOveruseThreshold(int threshold) {
        synchronized (mLock) {
            mRecurringOveruseThreshold = threshold;
        }
    }

    /** Fetches and syncs the resource overuse configurations from watchdog daemon. */
    private void fetchAndSyncResourceOveruseConfigurations() {
        List<android.automotive.watchdog.internal.ResourceOveruseConfiguration> internalConfigs;
        try {
            internalConfigs = mCarWatchdogDaemonHelper.getResourceOveruseConfigurations();
        } catch (RemoteException | RuntimeException e) {
            Slogf.w(TAG, e, "Failed to fetch resource overuse configurations");
            return;
        }
        if (internalConfigs.isEmpty()) {
            Slogf.e(TAG, "Fetched resource overuse configurations are empty");
            return;
        }
        mOveruseConfigurationCache.set(internalConfigs);
        mPackageInfoHandler.setVendorPackagePrefixes(
                mOveruseConfigurationCache.getVendorPackagePrefixes());
        if (DEBUG) {
            Slogf.d(TAG, "Fetched and synced resource overuse configs.");
        }
    }

    private void readFromDatabase() {
        mWatchdogStorage.syncUsers(getAliveUserIds());
        List<WatchdogStorage.UserPackageSettingsEntry> settingsEntries =
                mWatchdogStorage.getUserPackageSettings();
        Slogf.i(TAG, "Read %d user package settings from database", settingsEntries.size());
        List<WatchdogStorage.IoUsageStatsEntry> ioStatsEntries =
                mWatchdogStorage.getTodayIoUsageStats();
        Slogf.i(TAG, "Read %d I/O usage stats from database", ioStatsEntries.size());
        synchronized (mLock) {
            for (int i = 0; i < settingsEntries.size(); ++i) {
                WatchdogStorage.UserPackageSettingsEntry entry = settingsEntries.get(i);
                if (entry.userId == UserHandle.ALL.getIdentifier()) {
                    if (entry.killableState != KILLABLE_STATE_YES) {
                        mDefaultNotKillableGenericPackages.add(entry.packageName);
                    }
                    continue;
                }
                String key = getUserPackageUniqueId(entry.userId, entry.packageName);
                PackageResourceUsage usage = mUsageByUserPackage.get(key);
                if (usage == null) {
                    usage = new PackageResourceUsage(entry.userId, entry.packageName,
                            getDefaultKillableStateLocked(entry.packageName));
                }
                usage.setKillableState(entry.killableState);
                mUsageByUserPackage.put(key, usage);
            }
            ZonedDateTime curReportDate =
                    mTimeSource.now().atZone(ZONE_OFFSET).truncatedTo(STATS_TEMPORAL_UNIT);
            for (int i = 0; i < ioStatsEntries.size(); ++i) {
                WatchdogStorage.IoUsageStatsEntry entry = ioStatsEntries.get(i);
                String key = getUserPackageUniqueId(entry.userId, entry.packageName);
                PackageResourceUsage usage = mUsageByUserPackage.get(key);
                if (usage == null) {
                    usage = new PackageResourceUsage(entry.userId, entry.packageName,
                            getDefaultKillableStateLocked(entry.packageName));
                }
                /* Overwrite in memory cache as the stats will be merged on the daemon side and
                 * pushed on the next latestIoOveruseStats call. This is tolerable because the next
                 * push should happen soon.
                 */
                usage.ioUsage.overwrite(entry.ioUsage);
                mUsageByUserPackage.put(key, usage);
            }
            if (!ioStatsEntries.isEmpty()) {
                /* When mLatestStatsReportDate is null, the latest stats push from daemon hasn't
                 * happened yet. Thus the cached stats contains only the stats read from database.
                 */
                mIsWrittenToDatabase = mLatestStatsReportDate == null;
                mLatestStatsReportDate = curReportDate;
            }
        }
    }

    /** Writes user package settings and stats to database. */
    public void writeToDatabase() {
        synchronized (mLock) {
            if (mIsWrittenToDatabase) {
                return;
            }
            List<WatchdogStorage.UserPackageSettingsEntry>  entries =
                    new ArrayList<>(mUsageByUserPackage.size());
            for (int i = 0; i < mUsageByUserPackage.size(); ++i) {
                PackageResourceUsage usage = mUsageByUserPackage.valueAt(i);
                entries.add(new WatchdogStorage.UserPackageSettingsEntry(
                        usage.userId, usage.genericPackageName, usage.getKillableState()));
            }
            for (String packageName : mDefaultNotKillableGenericPackages) {
                entries.add(new WatchdogStorage.UserPackageSettingsEntry(
                        UserHandle.ALL.getIdentifier(), packageName, KILLABLE_STATE_NO));
            }
            if (!mWatchdogStorage.saveUserPackageSettings(entries)) {
                Slogf.e(TAG, "Failed to write user package settings to database");
            } else {
                Slogf.i(TAG, "Successfully saved %d user package settings to database",
                        entries.size());
            }
            writeStatsLocked();
            mIsWrittenToDatabase = true;
        }
    }

    @GuardedBy("mLock")
    private @KillableState int getDefaultKillableStateLocked(String genericPackageName) {
        return mDefaultNotKillableGenericPackages.contains(genericPackageName)
                ? KILLABLE_STATE_NO : KILLABLE_STATE_YES;
    }

    @GuardedBy("mLock")
    private void writeStatsLocked() {
        List<WatchdogStorage.IoUsageStatsEntry> entries =
                new ArrayList<>(mUsageByUserPackage.size());
        for (int i = 0; i < mUsageByUserPackage.size(); ++i) {
            PackageResourceUsage usage = mUsageByUserPackage.valueAt(i);
            if (!usage.ioUsage.hasUsage()) {
                continue;
            }
            entries.add(new WatchdogStorage.IoUsageStatsEntry(
                    usage.userId, usage.genericPackageName, usage.ioUsage));
        }
        if (!mWatchdogStorage.saveIoUsageStats(entries)) {
            Slogf.e(TAG, "Failed to write %d I/O overuse stats to database", entries.size());
        } else {
            Slogf.i(TAG, "Successfully saved %d I/O overuse stats to database", entries.size());
        }
    }

    @GuardedBy("mLock")
    private void applyCurrentUxRestrictionsLocked() {
        if (mCurrentUxRestrictions == null
                || mCurrentUxRestrictions.isRequiresDistractionOptimization()) {
            mCurrentUxState = UX_STATE_NO_DISTRACTION;
            return;
        }
        if (mCurrentUxState == UX_STATE_NO_INTERACTION) {
            return;
        }
        mCurrentUxState = UX_STATE_USER_NOTIFICATION;
        performOveruseHandlingLocked();
    }

    @GuardedBy("mLock")
    private int getPackageKillableStateForUserPackageLocked(
            int userId, String genericPackageName, int componentType, boolean isSafeToKill) {
        String key = getUserPackageUniqueId(userId, genericPackageName);
        PackageResourceUsage usage = mUsageByUserPackage.get(key);
        int defaultKillableState = getDefaultKillableStateLocked(genericPackageName);
        if (usage == null) {
            usage = new PackageResourceUsage(userId, genericPackageName, defaultKillableState);
        }
        int killableState = usage.syncAndFetchKillableState(
                componentType, isSafeToKill, defaultKillableState);
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
        ZonedDateTime currentDate = mTimeSource.now().atZone(ZoneOffset.UTC)
                .truncatedTo(STATS_TEMPORAL_UNIT);
        if (currentDate.equals(mLatestStatsReportDate)) {
            return;
        }
        /* After the first database read or on the first stats sync from the daemon, whichever
         * happens first, the cached stats would either be empty or initialized from the database.
         * In either case, don't write to database.
         */
        if (mLatestStatsReportDate != null && !mIsWrittenToDatabase) {
            writeStatsLocked();
        }
        for (int i = 0; i < mUsageByUserPackage.size(); ++i) {
            mUsageByUserPackage.valueAt(i).resetStats();
        }
        mLatestStatsReportDate = currentDate;
        if (DEBUG) {
            Slogf.d(TAG, "Handled date change successfully");
        }
    }

    @GuardedBy("mLock")
    private PackageResourceUsage cacheAndFetchUsageLocked(int uid, String genericPackageName,
            android.automotive.watchdog.IoOveruseStats internalStats,
            android.automotive.watchdog.PerStateBytes forgivenWriteBytes) {
        int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
        String key = getUserPackageUniqueId(userId, genericPackageName);
        int defaultKillableState = getDefaultKillableStateLocked(genericPackageName);
        PackageResourceUsage usage = mUsageByUserPackage.get(key);
        if (usage == null) {
            usage = new PackageResourceUsage(userId, genericPackageName, defaultKillableState);
        }
        usage.update(uid, internalStats, forgivenWriteBytes, defaultKillableState);
        mUsageByUserPackage.put(key, usage);
        return usage;
    }

    @GuardedBy("mLock")
    private boolean isRecurringOveruseLocked(PackageResourceUsage usage) {
        // TODO(b/195425666): Look up I/O overuse history and determine whether or not the package
        //  has recurring I/O overuse behavior.
        return usage.ioUsage.getInternalIoOveruseStats().totalOveruses
                > mRecurringOveruseThreshold;
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
        if (!usage.ioUsage.hasUsage()) {
            /* Return I/O overuse stats only when the package has usage for the current day.
             * Without the current day usage, the returned stats will contain zero remaining
             * bytes, which is incorrect.
             */
            return null;
        }
        IoOveruseStats currentStats = usage.getIoOveruseStats();
        long totalBytesWritten = currentStats.getTotalBytesWritten();
        int numDays = toNumDays(maxStatsPeriod);
        IoOveruseStats historyStats = null;
        if (numDays > 0) {
            historyStats = mWatchdogStorage.getHistoricalIoOveruseStats(
                    usage.userId, usage.genericPackageName, numDays - 1);
            totalBytesWritten += historyStats != null ? historyStats.getTotalBytesWritten() : 0;
        }
        if (totalBytesWritten < minimumBytesWritten) {
            return null;
        }
        if (historyStats == null) {
            return currentStats;
        }
        IoOveruseStats.Builder statsBuilder = new IoOveruseStats.Builder(
                historyStats.getStartTime(),
                historyStats.getDurationInSeconds() + currentStats.getDurationInSeconds());
        statsBuilder.setTotalTimesKilled(
                historyStats.getTotalTimesKilled() + currentStats.getTotalTimesKilled());
        statsBuilder.setTotalOveruses(
                historyStats.getTotalOveruses() + currentStats.getTotalOveruses());
        statsBuilder.setTotalBytesWritten(
                historyStats.getTotalBytesWritten() + currentStats.getTotalBytesWritten());
        statsBuilder.setKillableOnOveruse(currentStats.isKillableOnOveruse());
        statsBuilder.setRemainingWriteBytes(currentStats.getRemainingWriteBytes());
        return statsBuilder.build();
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
            mMainHandler.post(this::fetchAndSyncResourceOveruseConfigurations);
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

    private int[] getAliveUserIds() {
        UserManager userManager = mContext.getSystemService(UserManager.class);
        List<UserHandle> aliveUsers = userManager.getUserHandles(/* excludeDying= */ true);
        int userSize = aliveUsers.size();
        int[] userIds = new int[userSize];
        for (int i = 0; i < userSize; ++i) {
            userIds[i] = aliveUsers.get(i).getIdentifier();
        }
        return userIds;
    }

    @GuardedBy("mLock")
    private void performOveruseHandlingLocked() {
        if (mCurrentUxState == UX_STATE_NO_DISTRACTION) {
            return;
        }
        if (!mUserNotifiablePackages.isEmpty()) {
            notifyUserOnOveruseLocked();
        }
        if (mActionableUserPackages.isEmpty() || mCurrentUxState != UX_STATE_NO_INTERACTION) {
            return;
        }
        ArraySet<String> killedUserPackageKeys = new ArraySet<>();
        for (int i = 0; i < mActionableUserPackages.size(); ++i) {
            PackageResourceUsage usage =
                    mUsageByUserPackage.get(mActionableUserPackages.valueAt(i));
            if (usage == null) {
                continue;
            }
            // Between detecting and handling the overuse, either the package killable state or
            // the resource overuse configuration was updated. So, verify the killable state
            // before proceeding.
            int killableState = usage.getKillableState();
            if (killableState != KILLABLE_STATE_YES) {
                continue;
            }
            List<String> packages = Collections.singletonList(usage.genericPackageName);
            if (usage.isSharedPackage()) {
                packages = mPackageInfoHandler.getPackagesForUid(usage.getUid(),
                        usage.genericPackageName);
            }
            boolean isKilled = false;
            for (int pkgIdx = 0; pkgIdx < packages.size(); pkgIdx++) {
                String packageName = packages.get(pkgIdx);
                try {
                    int currentEnabledState =
                            PackageManagerHelper.getApplicationEnabledSettingForUser(
                                    packageName, usage.userId);
                    if (currentEnabledState == COMPONENT_ENABLED_STATE_DISABLED
                            || currentEnabledState == COMPONENT_ENABLED_STATE_DISABLED_USER
                            || currentEnabledState
                                == COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                        continue;
                    }
                    PackageManagerHelper.setApplicationEnabledSettingForUser(packageName,
                            COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED, /* flags= */ 0,
                            usage.userId, mContext.getPackageName());
                    isKilled = true;
                    Slogf.i(TAG,
                            "Disabled user %d's package '%s' until used due to disk I/O overuse",
                            usage.userId, packageName);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "Failed to disable application for user %d, package '%s'",
                            usage.userId, packageName);
                }
            }
            if (isKilled) {
                usage.ioUsage.killed();
                killedUserPackageKeys.add(usage.getUniqueId());
            }
        }
        pushIoOveruseKillMetrics(killedUserPackageKeys);
        mActionableUserPackages.clear();
    }

    @GuardedBy("mLock")
    private void notifyUserOnOveruseLocked() {
        // TODO(b/197770456): Notify the current user about the resource overusing packages, that
        //  belong only to the current user, from the list {@link mUserNotifiablePackages}.
        //  1. When {@code mCurrentUxState == UX_STATE_NO_INTERACTION}, the notifications should be
        //  posted only on the notification center.
        //  2. When there are multiple resource overusing packages, post the heads-up notification
        //  only for one package and the remaining notifications should be posted on
        //  the notification center.
        //  3. When this method is called multiple times while
        //  {@code mCurrentUxState == UX_STATE_USER_NOTIFICATION}, only one heads-up notification
        //  should be posted and the rest should be posted on the notification center.
        //  4. If a package's killable state is KILLABLE_STATE_NO or KILLABLE_STATE_NEVER,
        //  skip posting the notification.
        mUserNotifiablePackages.clear();
    }

    private void pushIoOveruseMetrics(ArraySet<String> userPackageKeys) {
        SparseArray<AtomsProto.CarWatchdogIoOveruseStats> statsByUid = new SparseArray<>();
        synchronized (mLock) {
            for (int i = 0; i < userPackageKeys.size(); ++i) {
                String key = userPackageKeys.valueAt(i);
                PackageResourceUsage usage = mUsageByUserPackage.get(key);
                if (usage == null) {
                    Slogf.w(TAG, "Missing usage stats for user package key %s", key);
                    continue;
                }
                statsByUid.put(usage.getUid(), constructCarWatchdogIoOveruseStatsLocked(usage));
            }
        }
        for (int i = 0; i < statsByUid.size(); ++i) {
            CarStatsLog.write(CAR_WATCHDOG_IO_OVERUSE_STATS_REPORTED, statsByUid.keyAt(i),
                    statsByUid.valueAt(i).toByteArray());
        }
    }

    private void pushIoOveruseKillMetrics(ArraySet<String> userPackageKeys) {
        int systemState;
        SparseArray<AtomsProto.CarWatchdogIoOveruseStats> statsByUid = new SparseArray<>();
        synchronized (mLock) {
            systemState = inferSystemStateLocked();
            for (int i = 0; i < userPackageKeys.size(); ++i) {
                String key = userPackageKeys.valueAt(i);
                PackageResourceUsage usage = mUsageByUserPackage.get(key);
                if (usage == null) {
                    Slogf.w(TAG, "Missing usage stats for user package key %s", key);
                    continue;
                }
                statsByUid.put(usage.getUid(), constructCarWatchdogIoOveruseStatsLocked(usage));
            }
        }
        for (int i = 0; i < statsByUid.size(); ++i) {
            // TODO(b/200598815): After watchdog can classify foreground vs background apps,
            //  report the correct uid state.
            CarStatsLog.write(CAR_WATCHDOG_KILL_STATS_REPORTED, statsByUid.keyAt(i),
                    CAR_WATCHDOG_KILL_STATS_REPORTED__UID_STATE__UNKNOWN_UID_STATE,
                    systemState,
                    CAR_WATCHDOG_KILL_STATS_REPORTED__KILL_REASON__KILLED_ON_IO_OVERUSE,
                    /* arg5= */ null, statsByUid.valueAt(i).toByteArray());
        }
    }

    @GuardedBy("mLock")
    private int inferSystemStateLocked() {
        if (mCurrentGarageMode == GarageMode.GARAGE_MODE_ON) {
            return CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__GARAGE_MODE;
        }
        return mCurrentUxState == UX_STATE_NO_INTERACTION
                ? CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__USER_NO_INTERACTION_MODE
                : CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__USER_INTERACTION_MODE;
    }

    @GuardedBy("mLock")
    private AtomsProto.CarWatchdogIoOveruseStats constructCarWatchdogIoOveruseStatsLocked(
            PackageResourceUsage usage) {
        @ComponentType int componentType = mPackageInfoHandler.getComponentType(
                usage.getUid(), usage.genericPackageName);
        android.automotive.watchdog.PerStateBytes threshold =
                mOveruseConfigurationCache.fetchThreshold(usage.genericPackageName, componentType);
        android.automotive.watchdog.PerStateBytes writtenBytes =
                usage.ioUsage.getInternalIoOveruseStats().writtenBytes;
        return constructCarWatchdogIoOveruseStats(
                AtomsProto.CarWatchdogIoOveruseStats.Period.DAILY,
                constructCarWatchdogPerStateBytes(threshold.foregroundBytes,
                        threshold.backgroundBytes, threshold.garageModeBytes),
                constructCarWatchdogPerStateBytes(writtenBytes.foregroundBytes,
                        writtenBytes.backgroundBytes, writtenBytes.garageModeBytes));
    }

    private static String getUserPackageUniqueId(@UserIdInt int userId, String genericPackageName) {
        return userId + ":" + genericPackageName;
    }

    @VisibleForTesting
    static IoOveruseStats.Builder toIoOveruseStatsBuilder(
            android.automotive.watchdog.IoOveruseStats internalStats,
            int totalTimesKilled, boolean isKillableOnOveruses) {
        return new IoOveruseStats.Builder(internalStats.startTime, internalStats.durationInSeconds)
                .setTotalOveruses(internalStats.totalOveruses)
                .setTotalTimesKilled(totalTimesKilled)
                .setTotalBytesWritten(totalPerStateBytes(internalStats.writtenBytes))
                .setKillableOnOveruse(isKillableOnOveruses)
                .setRemainingWriteBytes(toPerStateBytes(internalStats.remainingWriteBytes));
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
                    Slogf.i(TAG, "Invalid application category type: %s skipping package: %s",
                            entry.getValue(), metadata.packageName);
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

    private static void checkResourceOveruseConfigs(
            List<ResourceOveruseConfiguration> configurations,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag) {
        ArraySet<Integer> seenComponentTypes = new ArraySet<>();
        for (int i = 0; i < configurations.size(); ++i) {
            ResourceOveruseConfiguration config = configurations.get(i);
            if (seenComponentTypes.contains(config.getComponentType())) {
                throw new IllegalArgumentException(
                        "Cannot provide duplicate configurations for the same component type");
            }
            checkResourceOveruseConfig(config, resourceOveruseFlag);
            seenComponentTypes.add(config.getComponentType());
        }
    }

    private static void checkResourceOveruseConfig(ResourceOveruseConfiguration config,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag) {
        int componentType = config.getComponentType();
        if (toComponentTypeStr(componentType).equals("UNKNOWN")) {
            throw new IllegalArgumentException(
                    "Invalid component type in the configuration: " + componentType);
        }
        if ((resourceOveruseFlag & FLAG_RESOURCE_OVERUSE_IO) != 0
                && config.getIoOveruseConfiguration() == null) {
            throw new IllegalArgumentException("Must provide I/O overuse configuration");
        }
        checkIoOveruseConfig(config.getIoOveruseConfiguration(), componentType);
    }

    private static void checkIoOveruseConfig(IoOveruseConfiguration config, int componentType) {
        if (config.getComponentLevelThresholds().getBackgroundModeBytes() <= 0
                || config.getComponentLevelThresholds().getForegroundModeBytes() <= 0
                || config.getComponentLevelThresholds().getGarageModeBytes() <= 0) {
            throw new IllegalArgumentException(
                    "For component: " + toComponentTypeStr(componentType)
                            + " some thresholds are zero for: "
                            + config.getComponentLevelThresholds().toString());
        }
        if (componentType == ComponentType.SYSTEM) {
            List<IoOveruseAlertThreshold> systemThresholds = config.getSystemWideThresholds();
            if (systemThresholds.isEmpty()) {
                throw new IllegalArgumentException(
                        "Empty system-wide alert thresholds provided in "
                                + toComponentTypeStr(componentType)
                                + " config.");
            }
            for (int i = 0; i < systemThresholds.size(); i++) {
                checkIoOveruseAlertThreshold(systemThresholds.get(i));
            }
        }
    }

    private static void checkIoOveruseAlertThreshold(
            IoOveruseAlertThreshold ioOveruseAlertThreshold) {
        if (ioOveruseAlertThreshold.getDurationInSeconds() <= 0) {
            throw new IllegalArgumentException(
                    "System wide threshold duration must be greater than zero for: "
                            + ioOveruseAlertThreshold);
        }
        if (ioOveruseAlertThreshold.getWrittenBytesPerSecond() <= 0) {
            throw new IllegalArgumentException(
                    "System wide threshold written bytes per second must be greater than zero for: "
                            + ioOveruseAlertThreshold);
        }
    }

    private static void replaceKey(Map<String, PerStateBytes> map, String oldKey, String newKey) {
        PerStateBytes perStateBytes = map.get(oldKey);
        if (perStateBytes != null) {
            map.put(newKey, perStateBytes);
            map.remove(oldKey);
        }
    }

    private static int toNumDays(@CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        switch(maxStatsPeriod) {
            case STATS_PERIOD_CURRENT_DAY:
                return 0;
            case STATS_PERIOD_PAST_3_DAYS:
                return 3;
            case STATS_PERIOD_PAST_7_DAYS:
                return 7;
            case STATS_PERIOD_PAST_15_DAYS:
                return 15;
            case STATS_PERIOD_PAST_30_DAYS:
                return 30;
            default:
                throw new IllegalArgumentException(
                        "Invalid max stats period provided: " + maxStatsPeriod);
        }
    }

    @VisibleForTesting
    static AtomsProto.CarWatchdogIoOveruseStats constructCarWatchdogIoOveruseStats(
            AtomsProto.CarWatchdogIoOveruseStats.Period period,
            AtomsProto.CarWatchdogPerStateBytes threshold,
            AtomsProto.CarWatchdogPerStateBytes writtenBytes) {
        // TODO(b/184310189): Report uptime once daemon pushes it to CarService.
        return AtomsProto.CarWatchdogIoOveruseStats.newBuilder()
                .setPeriod(period)
                .setThreshold(threshold)
                .setWrittenBytes(writtenBytes).build();
    }

    @VisibleForTesting
    static AtomsProto.CarWatchdogPerStateBytes constructCarWatchdogPerStateBytes(
            long foregroundBytes, long backgroundBytes, long garageModeBytes) {
        return AtomsProto.CarWatchdogPerStateBytes.newBuilder()
                .setForegroundBytes(foregroundBytes)
                .setBackgroundBytes(backgroundBytes)
                .setGarageModeBytes(garageModeBytes).build();
    }

    private final class PackageResourceUsage {
        public final String genericPackageName;
        public @UserIdInt final int userId;
        public final PackageIoUsage ioUsage = new PackageIoUsage();
        private @KillableState int mKillableState;
        private int mUid;

        /** Must be called only after acquiring {@link mLock} */
        PackageResourceUsage(@UserIdInt int userId, String genericPackageName,
                @KillableState int defaultKillableState) {
            this.genericPackageName = genericPackageName;
            this.userId = userId;
            this.mKillableState = defaultKillableState;
        }

        public boolean isSharedPackage() {
            return this.genericPackageName.startsWith(SHARED_PACKAGE_PREFIX);
        }

        public String getUniqueId() {
            return getUserPackageUniqueId(userId, genericPackageName);
        }

        public int getUid() {
            return mUid;
        }

        public void update(int uid, android.automotive.watchdog.IoOveruseStats internalStats,
                android.automotive.watchdog.PerStateBytes forgivenWriteBytes,
                @KillableState int defaultKillableState) {
            // Package UID would change if it was re-installed, so keep it up-to-date.
            mUid = uid;
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
                mKillableState = defaultKillableState;
            }
            ioUsage.update(internalStats, forgivenWriteBytes);
        }

        public ResourceOveruseStats.Builder getResourceOveruseStatsBuilder() {
            return new ResourceOveruseStats.Builder(genericPackageName, UserHandle.of(userId));
        }


        public IoOveruseStats getIoOveruseStats() {
            if (!ioUsage.hasUsage()) {
                return null;
            }
            return ioUsage.getIoOveruseStats(mKillableState != KILLABLE_STATE_NEVER);
        }

        public @KillableState int getKillableState() {
            return mKillableState;
        }

        public void setKillableState(@KillableState int killableState) {
            mKillableState = killableState;
        }

        public boolean verifyAndSetKillableState(boolean isKillable) {
            if (mKillableState == KILLABLE_STATE_NEVER) {
                return false;
            }
            mKillableState = isKillable ? KILLABLE_STATE_YES : KILLABLE_STATE_NO;
            return true;
        }

        public int syncAndFetchKillableState(int myComponentType, boolean isSafeToKill,
                @KillableState int defaultKillableState) {
            /*
             * The killable state goes out-of-sync:
             * 1. When the on-device safe-to-kill list was recently updated and the user package
             * didn't have any resource usage so the native daemon didn't update the killable state.
             * 2. When a package has no resource usage and is initialized outside of processing the
             * latest resource usage stats.
             */
            if (myComponentType != ComponentType.THIRD_PARTY && !isSafeToKill) {
                mKillableState = KILLABLE_STATE_NEVER;
            } else if (mKillableState == KILLABLE_STATE_NEVER) {
                mKillableState = defaultKillableState;
            }
            return mKillableState;
        }

        public void resetStats() {
            ioUsage.resetStats();
        }
    }

    /** Defines I/O usage fields for a package. */
    public static final class PackageIoUsage {
        private static final android.automotive.watchdog.PerStateBytes DEFAULT_PER_STATE_BYTES =
                new android.automotive.watchdog.PerStateBytes();
        private android.automotive.watchdog.IoOveruseStats mIoOveruseStats;
        private android.automotive.watchdog.PerStateBytes mForgivenWriteBytes;
        private int mTotalTimesKilled;

        private PackageIoUsage() {
            mForgivenWriteBytes = DEFAULT_PER_STATE_BYTES;
            mTotalTimesKilled = 0;
        }

        public PackageIoUsage(android.automotive.watchdog.IoOveruseStats ioOveruseStats,
                android.automotive.watchdog.PerStateBytes forgivenWriteBytes,
                int totalTimesKilled) {
            mIoOveruseStats = ioOveruseStats;
            mForgivenWriteBytes = forgivenWriteBytes;
            mTotalTimesKilled = totalTimesKilled;
        }

        public android.automotive.watchdog.IoOveruseStats getInternalIoOveruseStats() {
            return mIoOveruseStats;
        }

        public android.automotive.watchdog.PerStateBytes getForgivenWriteBytes() {
            return mForgivenWriteBytes;
        }

        public int getTotalTimesKilled() {
            return mTotalTimesKilled;
        }

        boolean hasUsage() {
            return mIoOveruseStats != null;
        }

        void overwrite(PackageIoUsage ioUsage) {
            mIoOveruseStats = ioUsage.mIoOveruseStats;
            mForgivenWriteBytes = ioUsage.mForgivenWriteBytes;
            mTotalTimesKilled = ioUsage.mTotalTimesKilled;
        }

        void update(android.automotive.watchdog.IoOveruseStats internalStats,
                android.automotive.watchdog.PerStateBytes forgivenWriteBytes) {
            mIoOveruseStats = internalStats;
            mForgivenWriteBytes = forgivenWriteBytes;
        }

        IoOveruseStats getIoOveruseStats(boolean isKillable) {
            return toIoOveruseStatsBuilder(mIoOveruseStats, mTotalTimesKilled, isKillable).build();
        }

        boolean exceedsThreshold() {
            if (!hasUsage()) {
                return false;
            }
            android.automotive.watchdog.PerStateBytes remaining =
                    mIoOveruseStats.remainingWriteBytes;
            return remaining.foregroundBytes == 0 || remaining.backgroundBytes == 0
                    || remaining.garageModeBytes == 0;
        }

        void killed() {
            ++mTotalTimesKilled;
        }

        void resetStats() {
            mIoOveruseStats = null;
            mForgivenWriteBytes = DEFAULT_PER_STATE_BYTES;
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
            synchronized (mLock) {
                if (isListenerForSystem) {
                    removeListenerInfo.accept(mOveruseSystemListenerInfosByUid);
                } else {
                    removeListenerInfo.accept(mOveruseListenerInfosByUid);
                }
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
