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

import static android.car.settings.CarSettings.Secure.KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NEVER;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NO;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_YES;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.os.Process.INVALID_UID;
import static android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS;

import static com.android.car.CarServiceUtils.getContentResolverForUser;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_IO_OVERUSE_STATS_REPORTED;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_UID_IO_USAGE_SUMMARY;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;
import static com.android.car.internal.NotificationHelperBase.CAR_WATCHDOG_ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION;
import static com.android.car.internal.NotificationHelperBase.CAR_WATCHDOG_ACTION_LAUNCH_APP_SETTINGS;
import static com.android.car.watchdog.CarWatchdogService.DEBUG;
import static com.android.car.watchdog.CarWatchdogService.TAG;
import static com.android.car.watchdog.PackageInfoHandler.SHARED_PACKAGE_PREFIX;
import static com.android.car.watchdog.WatchdogPerfHandlerInterface.INTENT_EXTRA_NOTIFICATION_ID;
import static com.android.car.watchdog.WatchdogPerfHandlerInterface.PACKAGES_DISABLED_ON_RESOURCE_OVERUSE_SEPARATOR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.automotive.watchdog.internal.ComponentType;
import android.automotive.watchdog.internal.GarageMode;
import android.automotive.watchdog.internal.PackageIoOveruseStats;
import android.automotive.watchdog.internal.UserPackageIoUsageStats;
import android.car.builtin.util.EventLogHelper;
import android.car.builtin.util.Slogf;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.IResourceOveruseListener;
import android.car.watchdog.IoOveruseStats;
import android.car.watchdog.PackageKillableState;
import android.car.watchdog.PackageKillableState.KillableState;
import android.car.watchdog.ResourceOveruseConfiguration;
import android.car.watchdog.ResourceOveruseStats;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.StatsEvent;
import android.util.proto.ProtoOutputStream;
import android.view.Display;

import com.android.car.BuiltinPackageDependency;
import com.android.car.CarLocalServices;
import com.android.car.CarUxRestrictionsManagerService;
import com.android.car.R;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.NotificationHelperBase;
import com.android.car.internal.dep.Trace;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.stats.CarStatsLogWrapper;
import com.android.internal.annotations.GuardedBy;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Handles system resource performance monitoring module.
 */
public final class WatchdogPerfHandler implements WatchdogPerfHandlerInterface {
    private final Context mContext;
    /**
     * Context of the builtin car service that hosts the permissions, resources, and external
     * facing services required for showing notifications.
     */
    private final Context mBuiltinPackageContext;
    private final PackageInfoHandler mPackageInfoHandler;
    private final int mResourceOveruseNotificationBaseId;
    private final int mResourceOveruseNotificationMaxOffset;
    private final TimeSource mTimeSource;
    private final IoOveruseHandler mIoOveruseHandler;
    private final Object mLock = new Object();
    /**
     * Tracks user packages' resource usage. When cache is updated, call
     * {@link WatchdogStorage#markDirty} to notify database is out of sync.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, PackageResourceUsage> mUsageByUserPackage = new ArrayMap<>();
    /** Keys in {@link mUsageByUserPackage} for user notification on resource overuse. */
    @GuardedBy("mLock")
    private final ArraySet<String> mUserNotifiablePackages = new ArraySet<>();
    /** Values are the unique ids generated by {@code IoOveruseHandler.getUserPackageUniqueId}. */
    @GuardedBy("mLock")
    private final SparseArray<String> mActiveUserNotificationsByNotificationId =
            new SparseArray<>();
    /** Keys are the unique ids generated by {@code IoOveruseHandler.getUserPackageUniqueId}. */
    @GuardedBy("mLock")
    private final ArraySet<String> mActiveUserNotifications = new ArraySet<>();
    @GuardedBy("mLock")
    private boolean mIsDisplayOn;
    @GuardedBy("mLock")
    private @IoOveruseHandler.UxStateType int mCurrentUxState =
            IoOveruseHandler.UX_STATE_NO_DISTRACTION;
    @GuardedBy("mLock")
    private CarUxRestrictions mCurrentUxRestrictions;
    @GuardedBy("mLock")
    private boolean mIsHeadsUpNotificationSent;
    @GuardedBy("mLock")
    private int mCurrentOveruseNotificationIdOffset;
    @GuardedBy("mLock")
    private @GarageMode int mCurrentGarageMode = GarageMode.GARAGE_MODE_OFF;

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

    public WatchdogPerfHandler(Context context, Context builtinPackageContext,
            CarWatchdogDaemonHelper daemonHelper, PackageInfoHandler packageInfoHandler,
            WatchdogStorage watchdogStorage, TimeSource timeSource, Handler serviceHandler,
            CarStatsLogWrapper carStatsLogWrapper) {
        mContext = context;
        mBuiltinPackageContext = builtinPackageContext;
        mPackageInfoHandler = packageInfoHandler;
        mTimeSource = timeSource;
        mIsDisplayOn = true;
        Resources resources = mContext.getResources();

        int uidIoUsageSummaryTopCount = resources.getInteger(R.integer.uidIoUsageSummaryTopCount);
        int ioUsageSummaryMinSystemTotalWrittenBytes =
                resources.getInteger(R.integer.ioUsageSummaryMinSystemTotalWrittenBytes);
        int packageKillableStateResetDays =
                resources.getInteger(R.integer.watchdogUserPackageSettingsResetDays);
        int recurringOverusePeriodInDays =
                resources.getInteger(R.integer.recurringResourceOverusePeriodInDays);
        int recurringOveruseTimes = resources.getInteger(R.integer.recurringResourceOveruseTimes);
        mIoOveruseHandler = new IoOveruseHandler(context,
                new IoOveruseHelperImpl(context, this, carStatsLogWrapper),
                mBuiltinPackageContext, daemonHelper, packageInfoHandler, watchdogStorage,
                timeSource, uidIoUsageSummaryTopCount, ioUsageSummaryMinSystemTotalWrittenBytes,
                packageKillableStateResetDays, recurringOverusePeriodInDays, recurringOveruseTimes,
                serviceHandler);
        mResourceOveruseNotificationBaseId =
                NotificationHelperBase.RESOURCE_OVERUSE_NOTIFICATION_BASE_ID;
        mResourceOveruseNotificationMaxOffset =
                NotificationHelperBase.RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET;
    }

    /** Initializes the handler. */
    public void init() {
        mIoOveruseHandler.init();

        CarUxRestrictionsManagerService carUxRestrictionsManagerService =
                CarLocalServices.getService(CarUxRestrictionsManagerService.class);
        CarUxRestrictions uxRestrictions =
                carUxRestrictionsManagerService.getCurrentUxRestrictions();
        synchronized (mLock) {
            mCurrentUxRestrictions = uxRestrictions;
            applyCurrentUxRestrictionsLocked();
            syncDisabledUserPackagesLocked();
        }
        carUxRestrictionsManagerService.registerUxRestrictionsChangeListener(
                mCarUxRestrictionsChangeListener, Display.DEFAULT_DISPLAY);

        if (DEBUG) {
            Slogf.d(TAG, "WatchdogPerfHandler is initialized");
        }
    }

    /** Releases resources. */
    @Override
    public void release() {
        CarLocalServices.getService(CarUxRestrictionsManagerService.class)
                .unregisterUxRestrictionsChangeListener(mCarUxRestrictionsChangeListener);
    }

    /** Dumps its state. */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    @Override
    public void dump(IndentingPrintWriter writer) {
        mIoOveruseHandler.dump(writer);
    }

    /** Dumps its state in proto format */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    @Override
    public void dumpProto(ProtoOutputStream proto) {
        long performanceDumpToken = proto.start(CarWatchdogDumpProto.PERFORMANCE_DUMP);
        mIoOveruseHandler.dumpProto(proto);
        synchronized (mLock) {
            proto.write(PerformanceDump.RESOURCE_OVERUSE_NOTIFICATION_BASE_ID,
                    mResourceOveruseNotificationBaseId);
            proto.write(PerformanceDump.RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET,
                    mResourceOveruseNotificationMaxOffset);
            proto.write(PerformanceDump.IS_HEADS_UP_NOTIFICATION_SENT, mIsHeadsUpNotificationSent);
            proto.write(PerformanceDump.CURRENT_OVERUSE_NOTIFICATION_ID_OFFSET,
                    mCurrentOveruseNotificationIdOffset);
            proto.write(PerformanceDump.IS_GARAGE_MODE_ACTIVE, mCurrentGarageMode);

            IoOveruseHandler.dumpUserPackageInfo(mActiveUserNotifications,
                    PerformanceDump.ACTIVE_USER_NOTIFICATIONS, proto);
        }
        proto.end(performanceDumpToken);
    }

    /** Retries any pending requests on re-connecting to the daemon */
    @Override
    public void onDaemonConnectionChange(boolean isConnected) {
        mIoOveruseHandler.onDaemonConnectionChange(isConnected);
    }

    /** Updates the current UX state based on the display state. */
    @Override
    public void onDisplayStateChanged(boolean isEnabled) {
        Trace.beginSection("WdPerfHandler.onDisplayStateChanged(isEnabled=" + isEnabled + ")");
        synchronized (mLock) {
            mIsDisplayOn = isEnabled;
            if (isEnabled) {
                applyCurrentUxRestrictionsLocked();
            } else {
                mIoOveruseHandler.processUxStateChange(IoOveruseHandler.UX_STATE_NO_INTERACTION);
            }
        }
        Trace.endSection();
    }

    /** Handles garage mode change. */
    @Override
    public void onGarageModeChange(@GarageMode int garageMode) {
        Trace.beginSection("WdPerfHandler.onGarageModeChange(garageMode="
                + (garageMode == GarageMode.GARAGE_MODE_ON ? "ON" : "OFF") + ")");
        synchronized (mLock) {
            mCurrentGarageMode = garageMode;
            if (mCurrentGarageMode == GarageMode.GARAGE_MODE_ON) {
                mIoOveruseHandler.processUxStateChange(IoOveruseHandler.UX_STATE_NO_INTERACTION);
            }
        }
        Trace.endSection();
    }

    /** Returns resource overuse stats for the calling package. */
    @Override
    @NonNull
    public ResourceOveruseStats getResourceOveruseStats(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        return mIoOveruseHandler.getResourceOveruseStats(resourceOveruseFlag, maxStatsPeriod);
    }

    /** Returns resource overuse stats for all packages. */
    @Override
    @NonNull
    public List<ResourceOveruseStats> getAllResourceOveruseStats(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.MinimumStatsFlag int minimumStatsFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        return mIoOveruseHandler.getAllResourceOveruseStats(resourceOveruseFlag, minimumStatsFlag,
                maxStatsPeriod);
    }

    /** Returns resource overuse stats for the specified user package. */
    @Override
    @NonNull
    public ResourceOveruseStats getResourceOveruseStatsForUserPackage(
            @NonNull String packageName, @NonNull UserHandle userHandle,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        return mIoOveruseHandler.getResourceOveruseStatsForUserPackage(packageName, userHandle,
                resourceOveruseFlag, maxStatsPeriod);
    }

    /** Adds the resource overuse listener. */
    @Override
    public void addResourceOveruseListener(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener) {
        mIoOveruseHandler.addResourceOveruseListener(resourceOveruseFlag, listener);
    }

    /** Removes the previously added resource overuse listener. */
    @Override
    public void removeResourceOveruseListener(@NonNull IResourceOveruseListener listener) {
        mIoOveruseHandler.removeResourceOveruseListener(listener);
    }

    /** Adds the resource overuse system listener. */
    @Override
    public void addResourceOveruseListenerForSystem(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener) {
        mIoOveruseHandler.addResourceOveruseListenerForSystem(resourceOveruseFlag, listener);
    }

    /** Removes the previously added resource overuse system listener. */
    @Override
    public void removeResourceOveruseListenerForSystem(@NonNull IResourceOveruseListener listener) {
        mIoOveruseHandler.removeResourceOveruseListenerForSystem(listener);
    }

    /** Sets whether or not a package is killable on resource overuse. */
    @Override
    public void setKillablePackageAsUser(String packageName, UserHandle userHandle,
            boolean isKillable) {
        mIoOveruseHandler.setKillablePackageAsUser(packageName, userHandle, isKillable);
    }

    /** Returns the list of package killable states on resource overuse for the user. */
    @Override
    @NonNull
    public List<PackageKillableState> getPackageKillableStatesAsUser(UserHandle userHandle) {
        return mIoOveruseHandler.getPackageKillableStatesAsUser(userHandle);
    }

    /** Sets the given resource overuse configurations. */
    @Override
    @CarWatchdogManager.ReturnCode
    public int setResourceOveruseConfigurations(
            List<ResourceOveruseConfiguration> configurations,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag)
            throws RemoteException {
        return mIoOveruseHandler.setResourceOveruseConfigurations(configurations,
                resourceOveruseFlag);
    }

    /** Returns the available resource overuse configurations. */
    @Override
    @NonNull
    public List<ResourceOveruseConfiguration> getResourceOveruseConfigurations(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag) {
        return mIoOveruseHandler.getResourceOveruseConfigurations(resourceOveruseFlag);
    }

    /** Processes the latest I/O overuse stats */
    @Override
    public void latestIoOveruseStats(List<PackageIoOveruseStats> packageIoOveruseStats) {
        mIoOveruseHandler.latestIoOveruseStats(packageIoOveruseStats);
    }

    /** Resets the resource overuse settings and stats for the given generic package names. */
    @Override
    public void resetResourceOveruseStats(Set<String> genericPackageNames) {
        synchronized (mLock) {
            mIsHeadsUpNotificationSent = false;
            mIoOveruseHandler.resetResourceOveruseStats(genericPackageNames);
        }
    }

    /**
     * Asynchronously fetches today's I/O usage stats for all packages collected during the
     * previous boot and sends them to the CarWatchdog daemon.
     */
    @Override
    public void asyncFetchTodayIoUsageStats() {
        mIoOveruseHandler.asyncFetchTodayIoUsageStats();
    }

    /** Returns today's I/O usage stats for all packages collected during the previous boot. */
    @Override
    public List<UserPackageIoUsageStats> getTodayIoUsageStats() {
        return mIoOveruseHandler.getTodayIoUsageStats();
    }

    /** Deletes all data for specific user. */
    @Override
    public void deleteUser(@UserIdInt int userId) {
        mIoOveruseHandler.deleteUser(userId);
    }

    /** Handles intents from user notification actions. */
    @Override
    public void processUserNotificationIntent(Intent intent) {
        String action = intent.getAction();
        String packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        UserHandle userHandle = intent.getParcelableExtra(Intent.EXTRA_USER);
        int notificationId = intent.getIntExtra(INTENT_EXTRA_NOTIFICATION_ID, -1);
        if (packageName == null || packageName.isEmpty() || userHandle == null
                || userHandle.getIdentifier() < 0) {
            Slogf.w(TAG, "Invalid package '%s' or userHandle '%s' received in the intent",
                    packageName, userHandle);
            return;
        }
        Trace.beginSection("WdPerfHandler.processUserNotificationIntent(action=" + action + ")");
        try {
            switch (action) {
                case CAR_WATCHDOG_ACTION_LAUNCH_APP_SETTINGS:
                    Intent settingsIntent = new Intent(ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:" + packageName))
                            .setFlags(FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK);
                    mBuiltinPackageContext.startActivityAsUser(settingsIntent, userHandle);
                    if (DEBUG) {
                        Slogf.d(TAG, "Handled user notification action to launch settings app for "
                                + "package %s and user %s", packageName, userHandle);
                    }
                    break;
                case CAR_WATCHDOG_ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION:
                    break;
                default:
                    Slogf.e(TAG, "Skipping invalid user notification intent action: %s", action);
                    return;
            }

            if (notificationId == -1) {
                Slogf.e(TAG, "Didn't received user notification id in action %s", action);
                return;
            }

            int maxNotificationId =
                    mResourceOveruseNotificationBaseId + mResourceOveruseNotificationMaxOffset - 1;
            if (notificationId < mResourceOveruseNotificationBaseId
                    || notificationId > maxNotificationId) {
                Slogf.e(TAG, "Notification id (%d) outside of reserved IDs (%d - %d) "
                                + "for car watchdog.", notificationId,
                        mResourceOveruseNotificationBaseId, maxNotificationId);
                return;
            }

            synchronized (mLock) {
                String uniqueUserPackageId = mActiveUserNotificationsByNotificationId.get(
                        notificationId);
                if (uniqueUserPackageId != null
                        && uniqueUserPackageId.equals(IoOveruseHandler.getUserPackageUniqueId(
                                userHandle.getIdentifier(), packageName))) {
                    mActiveUserNotificationsByNotificationId.remove(notificationId);
                    mActiveUserNotifications.remove(uniqueUserPackageId);
                }
            }

            cancelNotificationAsUser(notificationId, userHandle);
            if (DEBUG) {
                Slogf.d(TAG, "Successfully canceled notification id %d for user %s and package %s",
                        notificationId, userHandle, packageName);
            }
        } finally {
            Trace.endSection();
        }
    }

    /** Handles when system broadcast package changed action */
    @Override
    public void processActionPackageChanged(Intent intent) {
        mIoOveruseHandler.processActionPackageChanged(intent);
    }

    /** Disables a package for specific user until used. */
    @Override
    public boolean disablePackageForUser(String packageName, @UserIdInt int userId) {
        return mIoOveruseHandler.disablePackageForUser(packageName, userId);
    }

    /**
     * Sets the delay to handle resource overuse after the package is notified of resource overuse.
     *
     * TODO(b/400460188): This method is used only by the unit test. Remove this method once,
     * the test is removed from WatchdogPerfHandlerUnitTest. IoOveruseHandlerUnitTest uses the
     * method in IoOveruseHandler. Also make this method a package private method in
     * WatchdogPerfHandlerStable & IoOveruseHandler.
     */
    @Override
    public void setOveruseHandlingDelay(long millis) {
        mIoOveruseHandler.setOveruseHandlingDelay(millis);
    }

    /** Writes to watchdog metadata file. */
    @Override
    public void writeMetadataFile() {
        mIoOveruseHandler.writeMetadataFile();
    }

    /**
     * Writes user package settings and stats to database. If database is marked as clean,
     * no writing is executed.
     */
    @Override
    public void writeToDatabase() {
        mIoOveruseHandler.writeToDatabase();
    }

    @GuardedBy("mLock")
    private void applyCurrentUxRestrictionsLocked() {
        if (mCurrentUxRestrictions == null
                || mCurrentUxRestrictions.isRequiresDistractionOptimization()) {
            mIoOveruseHandler.processUxStateChange(IoOveruseHandler.UX_STATE_NO_DISTRACTION);
        } else if (mIsDisplayOn) {
            // On no restrictions and display on, show user notifications.
            mIoOveruseHandler.processUxStateChange(IoOveruseHandler.UX_STATE_USER_NOTIFICATION);
        }
    }

    private int[] getAliveUserIds() {
        Trace.beginSection("WdPerfHandler.getAliveUserIds");
        UserManager userManager = mContext.getSystemService(UserManager.class);
        List<UserHandle> aliveUsers = userManager.getUserHandles(/* excludeDying= */ true);
        int userSize = aliveUsers.size();
        int[] userIds = new int[userSize];
        for (int i = 0; i < userSize; ++i) {
            userIds[i] = aliveUsers.get(i).getIdentifier();
        }
        Trace.endSection();
        return userIds;
    }

    // TODO(b/400460188): Refactor this method as a callback from IoOveruseHandler to handle
    // user notification.
    private void notifyUserOnOveruse() {
        Trace.beginSection("WdPerfHandler.notifyUserOnOveruse");
        SparseArray<String> headsUpNotificationPackagesByNotificationId = new SparseArray<>();
        SparseArray<String> notificationCenterPackagesByNotificationId = new SparseArray<>();
        int currentUserId = ActivityManager.getCurrentUser();
        synchronized (mLock) {
            for (int i = mUserNotifiablePackages.size() - 1; i >= 0; i--) {
                String uniqueId = mUserNotifiablePackages.valueAt(i);
                PackageResourceUsage usage = mUsageByUserPackage.get(uniqueId);
                if (usage == null || (usage.userId == currentUserId
                        && usage.getKillableState() != KILLABLE_STATE_YES)) {
                    mUserNotifiablePackages.removeAt(i);
                    continue;
                }
                if (usage.userId != currentUserId) {
                    Slogf.i(TAG, "Skipping notification for user %d and package %s because current"
                                    + " user %d is different", usage.userId,
                            usage.genericPackageName, currentUserId);
                    continue;
                }
                List<String> packages;
                if (usage.isSharedPackage()) {
                    packages = mPackageInfoHandler.getPackagesForUid(usage.getUid(),
                            usage.genericPackageName);
                } else {
                    packages = Collections.singletonList(usage.genericPackageName);
                }
                for (int pkgIdx = 0; pkgIdx < packages.size(); pkgIdx++) {
                    String packageName = packages.get(pkgIdx);
                    String userPackageUniqueId = IoOveruseHandler.getUserPackageUniqueId(
                            currentUserId, packageName);
                    if (mActiveUserNotifications.contains(userPackageUniqueId)) {
                        Slogf.e(TAG, "Dropping notification for user %d and package %s as it has "
                                + "an active notification", currentUserId, packageName);
                        continue;
                    }
                    int notificationId = mResourceOveruseNotificationBaseId
                            + mCurrentOveruseNotificationIdOffset;
                    if (mCurrentUxState == IoOveruseHandler.UX_STATE_NO_INTERACTION
                            || mIsHeadsUpNotificationSent) {
                        notificationCenterPackagesByNotificationId.put(notificationId, packageName);
                    } else {
                        headsUpNotificationPackagesByNotificationId.put(notificationId,
                                packageName);
                        mIsHeadsUpNotificationSent = true;
                    }
                    if (mActiveUserNotificationsByNotificationId.contains(notificationId)) {
                        mActiveUserNotifications.remove(
                                mActiveUserNotificationsByNotificationId.get(notificationId));
                    }
                    mActiveUserNotifications.add(userPackageUniqueId);
                    mActiveUserNotificationsByNotificationId.put(notificationId,
                            userPackageUniqueId);
                    mCurrentOveruseNotificationIdOffset = ++mCurrentOveruseNotificationIdOffset
                            % mResourceOveruseNotificationMaxOffset;
                }
                mUserNotifiablePackages.removeAt(i);
            }
        }
        sendResourceOveruseNotificationsAsUser(currentUserId,
                headsUpNotificationPackagesByNotificationId,
                notificationCenterPackagesByNotificationId);
        if (DEBUG) {
            Slogf.d(TAG, "Sent %d resource overuse notifications successfully",
                    headsUpNotificationPackagesByNotificationId.size()
                            + notificationCenterPackagesByNotificationId.size());
        }
        Trace.endSection();
    }

    private void sendResourceOveruseNotificationsAsUser(@UserIdInt int userId,
            SparseArray<String> headsUpNotificationPackagesById,
            SparseArray<String> notificationCenterPackagesById) {
        if (headsUpNotificationPackagesById.size() == 0
                && notificationCenterPackagesById.size() == 0) {
            return;
        }
        BuiltinPackageDependency.createNotificationHelper(mBuiltinPackageContext)
                .showResourceOveruseNotificationsAsUser(
                        UserHandle.of(userId),
                        headsUpNotificationPackagesById, notificationCenterPackagesById);
    }

    private void cancelNotificationAsUser(int notificationId, UserHandle userHandle) {
        BuiltinPackageDependency.createNotificationHelper(mBuiltinPackageContext)
                        .cancelNotificationAsUser(userHandle, notificationId);
    }

    /**
     * Syncs the {@link KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE} {@code Settings} of all users
     * with the internal cache.
     *
     * <p> Appending and removing package names to/from the settings string
     *     KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE is done only by this class. So, synchronize
     *     these operations using the class wide lock.
     */
    @GuardedBy("mLock")
    private void syncDisabledUserPackagesLocked() {
        int[] userIds = getAliveUserIds();
        SparseArray<ArraySet<String>> disabledUserPackagesByUserId = new SparseArray<>();
        for (int i = 0; i < userIds.length; i++) {
            int userId = userIds[i];
            ContentResolver contentResolverForUser = getContentResolverForUser(mContext, userId);
            ArraySet<String> packages = extractPackages(
                    Settings.Secure.getString(contentResolverForUser,
                            KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE));
            if (packages.isEmpty()) {
                continue;
            }
            disabledUserPackagesByUserId.put(userId, packages);
        }
        mIoOveruseHandler.setDisabledUserPackagesByUserId(disabledUserPackagesByUserId);
        if (DEBUG) {
            Slogf.d(TAG, "Synced the %s settings to the disabled user packages cache.",
                    KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE);
        }
    }

    private static ArraySet<String> extractPackages(String settingsString) {
        return TextUtils.isEmpty(settingsString) ? new ArraySet<>()
                : new ArraySet<>(Arrays.asList(settingsString.split(
                        PACKAGES_DISABLED_ON_RESOURCE_OVERUSE_SEPARATOR)));
    }

    @Nullable
    private static String constructSettingsString(ArraySet<String> packages) {
        return packages.isEmpty() ? null :
                TextUtils.join(PACKAGES_DISABLED_ON_RESOURCE_OVERUSE_SEPARATOR, packages);
    }

    private final class PackageResourceUsage {
        public final String genericPackageName;
        public @UserIdInt final int userId;
        public final PackageIoUsage ioUsage = new PackageIoUsage();
        private @KillableState int mKillableState;
        public ZonedDateTime mKillableStateLastModifiedDate;
        private int mUid;

        /** Must be called only after acquiring {@link mLock} */
        PackageResourceUsage(@UserIdInt int userId, String genericPackageName,
                @KillableState int defaultKillableState) {
            this.genericPackageName = genericPackageName;
            this.userId = userId;
            this.mKillableState = defaultKillableState;
            this.mKillableStateLastModifiedDate = mTimeSource.getCurrentDate();
            this.mUid = INVALID_UID;
        }

        public boolean isSharedPackage() {
            return this.genericPackageName.startsWith(SHARED_PACKAGE_PREFIX);
        }

        public String getUniqueId() {
            return IoOveruseHandler.getUserPackageUniqueId(userId, genericPackageName);
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

        public void setKillableState(@KillableState int killableState, ZonedDateTime modifiedDate) {
            mKillableState = killableState;
            mKillableStateLastModifiedDate = modifiedDate;
        }

        public boolean verifyAndSetKillableState(boolean isKillable, ZonedDateTime modifiedDate) {
            if (mKillableState == KILLABLE_STATE_NEVER) {
                return false;
            }
            mKillableState = isKillable ? KILLABLE_STATE_YES : KILLABLE_STATE_NO;
            mKillableStateLastModifiedDate = modifiedDate;
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

        public ZonedDateTime getKillableStateLastModifiedDate() {
            return mKillableStateLastModifiedDate;
        }

        public void resetStats() {
            ioUsage.resetStats();
        }
    }

    private final class IoOveruseHelperImpl implements IoOveruseHandler.IoOveruseHelper {
        private final Context mContext;
        private final WatchdogPerfHandler mWatchdogPerfHandler;
        private final CarStatsLogWrapper mCarStatsLogWrapper;

        IoOveruseHelperImpl(Context context, WatchdogPerfHandler watchdogPerfHandler,
                            CarStatsLogWrapper carStatsLogWrapper) {
            mContext = context;
            mWatchdogPerfHandler = watchdogPerfHandler;
            mCarStatsLogWrapper = carStatsLogWrapper;
        }

        @Override
        public boolean isInIdleMode() {
            synchronized (mWatchdogPerfHandler.mLock) {
                return mWatchdogPerfHandler.mCurrentGarageMode == GarageMode.GARAGE_MODE_ON;
            }
        }

        @Override
        public void writeKillEventLog(String packageName, int userId,
                long foregroundBytes, long backgroundBytes, long garageModeBytes,
                long thresholdForegroundBytes, long thresholdBackgroundBytes,
                long thresholdGarageModeBytes, int totalTimesKilled, boolean isPackageDisabled) {
            // TODO(b/432831327): When the event log tag is moved to the core Android, inline this
            // logic in IoOveruseHandler.
            EventLogHelper.writeCarWatchdogServiceIoOveruseKill(packageName, userId,
                    foregroundBytes, backgroundBytes, garageModeBytes, thresholdForegroundBytes,
                    thresholdBackgroundBytes, thresholdGarageModeBytes, totalTimesKilled,
                    isPackageDisabled);
        }

        @Override
        public void onPackageEnabledLocked(String packageName, int userId) {
            ContentResolver contentResolverForUser = getContentResolverForUser(mContext, userId);
            ArraySet<String> packages = WatchdogPerfHandler.extractPackages(
                    Settings.Secure.getString(contentResolverForUser,
                            KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE));
            if (!packages.remove(packageName)) {
                return;
            }
            String settingsString = WatchdogPerfHandler.constructSettingsString(packages);
            Settings.Secure.putString(contentResolverForUser,
                    KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE, settingsString);
            if (DEBUG) {
                Slogf.d(TAG, "Removed %s from %s. New value is '%s'", packageName,
                        KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE, settingsString);
            }
        }

        @Override
        public void onPackageDisabledLocked(String packageName, int userId) {
            ContentResolver contentResolverForUser = getContentResolverForUser(mContext, userId);
            ArraySet<String> packages = WatchdogPerfHandler.extractPackages(
                    Settings.Secure.getString(contentResolverForUser,
                            KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE));
            if (!packages.add(packageName)) {
                return;
            }
            String settingsString = WatchdogPerfHandler.constructSettingsString(packages);
            Settings.Secure.putString(contentResolverForUser,
                    KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE, settingsString);
            if (DEBUG) {
                Slogf.d(TAG, "Appended %s to %s. New value is '%s'", packageName,
                        KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE, settingsString);
            }
        }

        @Override
        public void logIoOveruseStatsReported(int uid, byte[] ioOveruseStats) {
            mCarStatsLogWrapper.write(CAR_WATCHDOG_IO_OVERUSE_STATS_REPORTED, uid,
                    ioOveruseStats);
        }

        @Override
        public void logKillStatsReported(int uid, int uidState, int systemState, int killReason,
                                         byte[] processStats, byte[] ioOveruseStats) {
            mCarStatsLogWrapper.write(CAR_WATCHDOG_KILL_STATS_REPORTED, uid, uidState, systemState,
                    killReason, processStats, ioOveruseStats);
        }

        @Override
        public StatsEvent buildSystemIoUsageSummaryStatsEvent(byte[] ioUsageSummary,
                                                              long startTimeMillis) {
            return mCarStatsLogWrapper.buildStatsEvent(CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY,
                    ioUsageSummary, startTimeMillis);
        }

        @Override
        public StatsEvent buildUidIoUsageSummaryStatsEvent(int uid, byte[] ioUsageSummary,
                                                            long startTimeMillis) {
            return mCarStatsLogWrapper.buildStatsEvent(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY, uid,
                    ioUsageSummary, startTimeMillis);
        }
    }
}
