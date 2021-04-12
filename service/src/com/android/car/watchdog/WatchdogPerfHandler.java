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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityThread;
import android.automotive.watchdog.ResourceType;
import android.automotive.watchdog.internal.PackageIdentifier;
import android.automotive.watchdog.internal.PackageIoOveruseStats;
import android.automotive.watchdog.internal.PackageResourceOveruseAction;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.IResourceOveruseListener;
import android.car.watchdog.IoOveruseStats;
import android.car.watchdog.PackageKillableState;
import android.car.watchdog.PackageKillableState.KillableState;
import android.car.watchdog.PerStateBytes;
import android.car.watchdog.ResourceOveruseConfiguration;
import android.car.watchdog.ResourceOveruseStats;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.utils.Slogf;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Handles system resource performance monitoring module.
 */
public final class WatchdogPerfHandler {
    private static final String TAG = CarLog.tagFor(CarWatchdogService.class);

    private final boolean mIsDebugEnabled;
    private final Context mContext;
    private final CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    private final PackageInfoHandler mPackageInfoHandler;
    private final Handler mMainHandler;
    private final Object mLock = new Object();
    /*
     * Cache of added resource overuse listeners by uid.
     */
    @GuardedBy("mLock")
    private final Map<String, PackageResourceUsage> mUsageByUserPackage = new ArrayMap<>();
    @GuardedBy("mLock")
    private final List<PackageResourceOveruseAction> mOveruseActionsByUserPackage =
            new ArrayList<>();
    @GuardedBy("mLock")
    private final SparseArray<ResourceOveruseListenerInfo> mOveruseListenerInfosByUid =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<ResourceOveruseListenerInfo> mOveruseSystemListenerInfosByUid =
            new SparseArray<>();
    @GuardedBy("mLock")
    private ZonedDateTime mLastStatsReportUTC;

    public WatchdogPerfHandler(Context context, CarWatchdogDaemonHelper daemonHelper,
            PackageInfoHandler packageInfoHandler, boolean isDebugEnabled) {
        mIsDebugEnabled = isDebugEnabled;
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
         *
         * TODO(b/170741935): Read the current day's I/O overuse stats from database and push them
         *  to the daemon.
         */
        synchronized (mLock) {
            checkAndHandleDateChangeLocked();
        }
        if (mIsDebugEnabled) {
            Slogf.d(TAG, "WatchdogPerfHandler is initialized");
        }
    }

    /** Releases the handler */
    public void release() {
        /*
         * TODO(b/170741935): Write daily usage to SQLite DB storage.
         */
        if (mIsDebugEnabled) {
            Slogf.d(TAG, "WatchdogPerfHandler is released");
        }
    }

    /** Dumps its state. */
    public void dump(IndentingPrintWriter writer) {
        /**
         * TODO(b/170741935): Implement this method.
         */
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
        // TODO(b/170741935): Implement this method.
        return new ResourceOveruseStats.Builder("",
                UserHandle.getUserHandleForUid(Binder.getCallingUid())).build();
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
        // TODO(b/170741935): Implement this method.
        return new ArrayList<>();
    }

    /** Returns resource overuse stats for the specified user package. */
    @NonNull
    public ResourceOveruseStats getResourceOveruseStatsForUserPackage(
            @NonNull String packageName, @NonNull UserHandle userHandle,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        Objects.requireNonNull(packageName, "Package name must be non-null");
        Objects.requireNonNull(userHandle, "User handle must be non-null");
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        Preconditions.checkArgument((maxStatsPeriod > 0),
                "Must provide valid maximum stats period");
        // TODO(b/170741935): Implement this method.
        return new ResourceOveruseStats.Builder("", userHandle).build();
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
        /*
         * TODO(b/170741935): Add/remove the package from the user do-no-kill list.
         *  If the {@code userHandle == UserHandle.ALL}, update the settings for all users.
         */
    }

    /** Returns the list of package killable states on resource overuse for the user. */
    @NonNull
    public List<PackageKillableState> getPackageKillableStatesAsUser(UserHandle userHandle) {
        Objects.requireNonNull(userHandle, "User handle must be non-null");
        // TODO(b/170741935): Implement this method.
        return new ArrayList<>();
    }

    /** Sets the given resource overuse configurations. */
    public void setResourceOveruseConfigurations(
            List<ResourceOveruseConfiguration> configurations,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag) {
        Objects.requireNonNull(configurations, "Configurations must be non-null");
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        Set<Integer> seenComponentTypes = new ArraySet<>();
        for (ResourceOveruseConfiguration config : configurations) {
            int componentType = config.getComponentType();
            if (seenComponentTypes.contains(componentType)) {
                throw new IllegalArgumentException(
                        "Cannot provide duplicate configurations for the same component type");
            }
            if ((resourceOveruseFlag & FLAG_RESOURCE_OVERUSE_IO) != 0
                    && config.getIoOveruseConfiguration() == null) {
                throw new IllegalArgumentException("Must provide I/O overuse configuration");
            }
            seenComponentTypes.add(config.getComponentType());
        }
        // TODO(b/170741935): Implement this method.
    }

    /** Returns the available resource overuse configurations. */
    @NonNull
    public List<ResourceOveruseConfiguration> getResourceOveruseConfigurations(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag) {
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        // TODO(b/170741935): Implement this method.
        return new ArrayList<>();
    }

    /** Processes the latest I/O overuse stats */
    public void latestIoOveruseStats(List<PackageIoOveruseStats> packageIoOveruseStats) {
        int[] uids = new int[packageIoOveruseStats.size()];
        for (int i = 0; i < packageIoOveruseStats.size(); ++i) {
            uids[i] = packageIoOveruseStats.get(i).uid;
        }
        SparseArray<String> packageNamesByUid = mPackageInfoHandler.getPackageNamesForUids(uids);
        synchronized (mLock) {
            checkAndHandleDateChangeLocked();
            for (PackageIoOveruseStats stats : packageIoOveruseStats) {
                String packageName = packageNamesByUid.get(stats.uid, null);
                if (packageName == null) {
                    continue;
                }
                int userId = UserHandle.getUserId(stats.uid);
                PackageResourceUsage usage = cacheAndFetchUsageLocked(userId, packageName,
                        stats.ioOveruseStats);
                if (stats.shouldNotify) {
                    /*
                     * Packages that exceed the warn threshold percentage should be notified as well
                     * and only the daemon is aware of such packages. Thus the flag is used to
                     * indicate which packages should be notified.
                     */
                    notifyResourceOveruseStatsLocked(stats.uid,
                            usage.getResourceOveruseStatsWithIo());
                }
                if (!usage.ioUsage.exceedsThreshold()) {
                    continue;
                }
                PackageResourceOveruseAction overuseAction = new PackageResourceOveruseAction();
                overuseAction.packageIdentifier = new PackageIdentifier();
                overuseAction.packageIdentifier.name = packageName;
                overuseAction.packageIdentifier.uid = stats.uid;
                overuseAction.resourceTypes = new int[]{ ResourceType.IO };
                overuseAction.resourceOveruseActionType = NOT_KILLED;
                /*
                 * No action required on I/O overuse on one of the following cases:
                 * #1 The package is not safe to kill as it is critical for system stability.
                 * #2 The package has no recurring overuse behavior and the user opted to not
                 *    kill the package so honor the user's decision.
                 */
                String userPackageId = getUserPackageUniqueId(userId, packageName);
                int killableState = usage.getKillableState();
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
                try {
                    int oldEnabledState = -1;
                    IPackageManager packageManager = ActivityThread.getPackageManager();
                    if (!hasRecurringOveruse) {
                        oldEnabledState = packageManager.getApplicationEnabledSetting(packageName,
                                userId);

                        if (oldEnabledState == COMPONENT_ENABLED_STATE_DISABLED
                                || oldEnabledState == COMPONENT_ENABLED_STATE_DISABLED_USER
                                || oldEnabledState == COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                            mOveruseActionsByUserPackage.add(overuseAction);
                            continue;
                        }
                    }

                    packageManager.setApplicationEnabledSetting(packageName,
                            COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED, /* flags= */ 0, userId,
                            mContext.getPackageName());

                    overuseAction.resourceOveruseActionType = hasRecurringOveruse
                            ? KILLED_RECURRING_OVERUSE : KILLED;
                    if (!hasRecurringOveruse) {
                        usage.oldEnabledState = oldEnabledState;
                    }
                } catch (RemoteException e) {
                    Slogf.e(TAG, "Failed to disable application enabled setting for user %d, "
                            + "package '%s'", userId, packageName);
                }
                mOveruseActionsByUserPackage.add(overuseAction);
            }
            if (!mOveruseActionsByUserPackage.isEmpty()) {
                mMainHandler.sendMessage(obtainMessage(
                        WatchdogPerfHandler::notifyActionsTakenOnOveruse, this));
            }
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
        } catch (RemoteException e) {
            Slogf.w(TAG, "Failed to notify car watchdog daemon of actions taken on "
                    + "resource overuse: %s", e);
        }
    }

    private void notifyResourceOveruseStatsLocked(int uid,
            ResourceOveruseStats resourceOveruseStats) {
        String packageName = resourceOveruseStats.getPackageName();
        ResourceOveruseListenerInfo listenerInfo = mOveruseListenerInfosByUid.get(uid, null);
        if (listenerInfo != null && (listenerInfo.flag & FLAG_RESOURCE_OVERUSE_IO) != 0) {
            try {
                listenerInfo.listener.onOveruse(resourceOveruseStats);
            } catch (RemoteException e) {
                Slogf.e(TAG,
                        "Failed to notify listener(uid %d, package '%s') on resource overuse: %s",
                        uid, resourceOveruseStats, e);
            }
        }
        for (int i = 0; i < mOveruseSystemListenerInfosByUid.size(); ++i) {
            ResourceOveruseListenerInfo systemListenerInfo =
                    mOveruseSystemListenerInfosByUid.valueAt(i);
            if ((systemListenerInfo.flag & FLAG_RESOURCE_OVERUSE_IO) == 0) {
                continue;
            }
            try {
                systemListenerInfo.listener.onOveruse(resourceOveruseStats);
            } catch (RemoteException e) {
                Slogf.e(TAG, "Failed to notify system listener(uid %d, pid: %d) of resource "
                                + "overuse by package(uid %d, package '%s'): %s",
                        systemListenerInfo.uid, systemListenerInfo.pid, uid, packageName, e);
            }
        }
    }

    private void checkAndHandleDateChangeLocked() {
        ZonedDateTime previousUTC = mLastStatsReportUTC;
        mLastStatsReportUTC = ZonedDateTime.now(ZoneOffset.UTC);
        if (mLastStatsReportUTC.getDayOfYear() == previousUTC.getDayOfYear()
                && mLastStatsReportUTC.getYear() == previousUTC.getYear()) {
            return;
        }
        for (PackageResourceUsage usage : mUsageByUserPackage.values()) {
            if (usage.oldEnabledState > 0) {
                // Forgive the daily disabled package on date change.
                try {
                    IPackageManager packageManager = ActivityThread.getPackageManager();
                    if (packageManager.getApplicationEnabledSetting(usage.packageName,
                            usage.userId)
                            != COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                        continue;
                    }
                    packageManager.setApplicationEnabledSetting(usage.packageName,
                            usage.oldEnabledState,
                            /* flags= */ 0, usage.userId, mContext.getPackageName());
                } catch (RemoteException e) {
                    Slogf.e(TAG,
                            "Failed to reset enabled setting for disabled package '%s', user %d",
                            usage.packageName, usage.userId);
                }
            }
            /* TODO(b/170741935): Stash the old usage into SQLite DB storage. */
            usage.ioUsage.clear();
        }
    }

    private PackageResourceUsage cacheAndFetchUsageLocked(@UserIdInt int userId, String packageName,
            android.automotive.watchdog.IoOveruseStats internalStats) {
        String key = getUserPackageUniqueId(userId, packageName);
        PackageResourceUsage usage = mUsageByUserPackage.getOrDefault(key,
                new PackageResourceUsage(userId, packageName));
        usage.update(internalStats);
        mUsageByUserPackage.put(key, usage);
        return usage;
    }

    private boolean isRecurringOveruseLocked(PackageResourceUsage ioUsage) {
        /*
         * TODO(b/170741935): Look up I/O overuse history and determine whether or not the package
         *  has recurring I/O overuse behavior.
         */
        return false;
    }

    private void addResourceOveruseListenerLocked(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener,
            SparseArray<ResourceOveruseListenerInfo> listenerInfosByUid) {
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        boolean isListenerForSystem = listenerInfosByUid == mOveruseSystemListenerInfosByUid;
        String listenerType = isListenerForSystem ? "resource overuse listener for system" :
                "resource overuse listener";

        ResourceOveruseListenerInfo existingListenerInfo = listenerInfosByUid.get(callingUid, null);
        if (existingListenerInfo != null) {
            IBinder binder = listener.asBinder();
            if (existingListenerInfo.listener.asBinder() == binder) {
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

        if (existingListenerInfo != null) {
            Slogf.w(TAG, "Overwriting existing %s: pid %d, uid: %d", listenerType,
                    existingListenerInfo.pid, existingListenerInfo.uid);
            existingListenerInfo.unlinkToDeath();
        }


        listenerInfosByUid.put(callingUid, listenerInfo);
        if (mIsDebugEnabled) {
            Slogf.d(TAG, "The %s (pid: %d, uid: %d) is added", listenerType,
                    callingPid, callingUid);
        }
    }

    private void removeResourceOveruseListenerLocked(@NonNull IResourceOveruseListener listener,
            SparseArray<ResourceOveruseListenerInfo> listenerInfosByUid) {
        int callingUid = Binder.getCallingUid();

        String listenerType = listenerInfosByUid == mOveruseSystemListenerInfosByUid
                ? "resource overuse system listener" : "resource overuse listener";

        ResourceOveruseListenerInfo listenerInfo = listenerInfosByUid.get(callingUid, null);
        if (listenerInfo == null || listenerInfo.listener != listener) {
            Slogf.w(TAG, "Cannot remove the %s: it has not been registered before", listenerType);
            return;
        }
        listenerInfo.unlinkToDeath();
        listenerInfosByUid.remove(callingUid);
        if (mIsDebugEnabled) {
            Slogf.d(TAG, "The %s (pid: %d, uid: %d) is removed", listenerType, listenerInfo.pid,
                    listenerInfo.uid);
        }
    }

    private void onResourceOveruseListenerDeath(int uid, boolean isListenerForSystem) {
        synchronized (mLock) {
            if (isListenerForSystem) {
                mOveruseSystemListenerInfosByUid.remove(uid);
            } else {
                mOveruseListenerInfosByUid.remove(uid);
            }
        }
    }

    private static String getUserPackageUniqueId(int userId, String packageName) {
        return String.valueOf(userId) + ":" + packageName;
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
        PerStateBytes perStateBytes = new PerStateBytes(internalPerStateBytes.foregroundBytes,
                internalPerStateBytes.backgroundBytes, internalPerStateBytes.garageModeBytes);
        return perStateBytes;
    }

    private static long totalPerStateBytes(
            android.automotive.watchdog.PerStateBytes internalPerStateBytes) {
        BiFunction<Long, Long, Long> sum = (l, r) -> {
            return (Long.MAX_VALUE - l > r) ? l + r : Long.MAX_VALUE;
        };
        return sum.apply(sum.apply(internalPerStateBytes.foregroundBytes,
                internalPerStateBytes.backgroundBytes), internalPerStateBytes.garageModeBytes);
    }

    private static final class PackageResourceUsage {
        public final String packageName;
        public @UserIdInt final int userId;
        public final PackageIoUsage ioUsage;
        public int oldEnabledState;

        private @KillableState int mKillableState;

        PackageResourceUsage(@UserIdInt int userId, String packageName) {
            this.packageName = packageName;
            this.userId = userId;
            this.ioUsage = new PackageIoUsage();
            this.oldEnabledState = -1;
            this.mKillableState = KILLABLE_STATE_YES;
        }

        public void update(android.automotive.watchdog.IoOveruseStats internalStats) {
            if (!internalStats.killableOnOveruse) {
                /*
                 * Killable value specified in the internal stats is provided by the native daemon.
                 * This value reflects whether or not an application is safe-to-kill on overuse.
                 * This setting is from the I/O overuse configuration specified by the system and
                 * vendor services and doesn't reflect the user choices. Thus if the internal stats
                 * specify the application is not killable, the application is not safe-to-kill.
                 */
                this.mKillableState = KILLABLE_STATE_NEVER;
            }
            ioUsage.update(internalStats);
        }

        public ResourceOveruseStats getResourceOveruseStatsWithIo() {
            IoOveruseStats ioOveruseStats = null;
            if (ioUsage.hasUsage()) {
                ioOveruseStats = ioUsage.getStatsBuilder().setKillableOnOveruse(
                        mKillableState != PackageKillableState.KILLABLE_STATE_NEVER).build();
            }

            return new ResourceOveruseStats.Builder(packageName, UserHandle.of(userId))
                    .setIoOveruseStats(ioOveruseStats).build();
        }

        public @KillableState int getKillableState() {
            return mKillableState;
        }

        public boolean setKillableState(boolean isKillable) {
            if (mKillableState == PackageKillableState.KILLABLE_STATE_NEVER) {
                return false;
            }
            mKillableState = isKillable ? KILLABLE_STATE_YES : KILLABLE_STATE_NO;
            return true;
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

        public void clear() {
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
            onResourceOveruseListenerDeath(uid, isListenerForSystem);
            unlinkToDeath();
        }

        private void linkToDeath() throws RemoteException {
            listener.asBinder().linkToDeath(this, 0);
        }

        private void unlinkToDeath() {
            listener.asBinder().unlinkToDeath(this, 0);
        }
    }
}
