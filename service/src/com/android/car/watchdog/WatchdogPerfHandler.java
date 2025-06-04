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

import static android.app.StatsManager.PULL_SKIP;
import static android.app.StatsManager.PULL_SUCCESS;
import static android.car.settings.CarSettings.Secure.KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NEVER;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NO;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_YES;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.os.Process.INVALID_UID;
import static android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS;

import static com.android.car.CarServiceUtils.getContentResolverForUser;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__KILL_REASON__KILLED_ON_IO_OVERUSE;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__GARAGE_MODE;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__USER_INTERACTION_MODE;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__USER_NO_INTERACTION_MODE;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__UID_STATE__UNKNOWN_UID_STATE;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_UID_IO_USAGE_SUMMARY;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;
import static com.android.car.internal.NotificationHelperBase.CAR_WATCHDOG_ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION;
import static com.android.car.internal.NotificationHelperBase.CAR_WATCHDOG_ACTION_LAUNCH_APP_SETTINGS;
import static com.android.car.watchdog.CarWatchdogService.DEBUG;
import static com.android.car.watchdog.CarWatchdogService.TAG;
import static com.android.car.watchdog.PackageInfoHandler.SHARED_PACKAGE_PREFIX;
import static com.android.car.watchdog.TimeSource.ZONE_OFFSET;
import static com.android.car.watchdog.WatchdogPerfHandlerInterface.INTENT_EXTRA_NOTIFICATION_ID;
import static com.android.car.watchdog.WatchdogPerfHandlerInterface.PACKAGES_DISABLED_ON_RESOURCE_OVERUSE_SEPARATOR;
import static com.android.car.watchdog.WatchdogPerfHandlerInterface.USER_PACKAGE_SEPARATOR;
import static com.android.car.watchdog.WatchdogStorage.RETENTION_PERIOD;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.StatsManager;
import android.app.StatsManager.PullAtomMetadata;
import android.automotive.watchdog.internal.ComponentType;
import android.automotive.watchdog.internal.GarageMode;
import android.automotive.watchdog.internal.PackageIoOveruseStats;
import android.automotive.watchdog.internal.UserPackageIoUsageStats;
import android.car.builtin.content.pm.PackageManagerHelper;
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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.JsonReader;
import android.util.Pair;
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
import com.android.car.internal.util.ConcurrentUtils;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.stats.CarStatsLogWrapper;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Handles system resource performance monitoring module.
 */
public final class WatchdogPerfHandler implements WatchdogPerfHandlerInterface {
    private static final String METADATA_FILENAME = "metadata.json";
    private static final String SYSTEM_IO_USAGE_SUMMARY_REPORTED_DATE =
            "systemIoUsageSummaryReportedDate";
    private static final String UID_IO_USAGE_SUMMARY_REPORTED_DATE =
            "uidIoUsageSummaryReportedDate";
    private static final long OVERUSE_HANDLING_DELAY_MILLS = 10_000;

    private static final PullAtomMetadata PULL_ATOM_METADATA =
            new PullAtomMetadata.Builder()
                    // Summary atoms are populated only once a week, so a longer duration is
                    // tolerable. However, the cool down duration should be smaller than a short
                    // drive, so summary atoms can be pulled with short drives.
                    .setCoolDownMillis(TimeUnit.MILLISECONDS.convert(5L, TimeUnit.MINUTES))
                    // When summary atoms are populated once a week, watchdog needs additional time
                    // for reading from disk/DB.
                    .setTimeoutMillis(10_000)
                    .build();

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
    /**
     * Context of the builtin car service that hosts the permissions, resources, and external
     * facing services required for showing notifications.
     */
    private final Context mBuiltinPackageContext;
    private final CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    private final PackageInfoHandler mPackageInfoHandler;
    private final Handler mMainHandler;
    private final Handler mServiceHandler;
    private final WatchdogStorage mWatchdogStorage;
    private final OveruseConfigurationCache mOveruseConfigurationCache;
    private final int mUidIoUsageSummaryTopCount;
    private final int mIoUsageSummaryMinSystemTotalWrittenBytes;
    private final int mPackageKillableStateResetDays;
    private final int mRecurringOverusePeriodInDays;
    private final int mRecurringOveruseTimes;
    private final int mResourceOveruseNotificationBaseId;
    private final int mResourceOveruseNotificationMaxOffset;
    private final TimeSource mTimeSource;
    private final CarStatsLogWrapper mCarStatsLogWrapper;
    private final IoOveruseHandler mIoOveruseHandler;
    private final Object mLock = new Object();
    /**
     * Tracks user packages' resource usage. When cache is updated, call
     * {@link WatchdogStorage#markDirty} to notify database is out of sync.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, PackageResourceUsage> mUsageByUserPackage = new ArrayMap<>();
    /**
     * Default killable state for packages. Updated only for {@link UserHandle#ALL} user handle.
     * When cache is updated, call {@link WatchdogStorage#markDirty} to notify database is out of
     * sync.
     */
    // TODO(b/235615155): Update database when a default not killable package is set to killable
    //  Also, changes to mDefaultNotKillableGenericPackages should be tracked by the last modified
    //  date. This date should be copied to any new user package settings that take the default
    //  value. When this date is beyond reset days, the settings here should be reset.
    @GuardedBy("mLock")
    private final ArraySet<String> mDefaultNotKillableGenericPackages = new ArraySet<>();
    /** Keys in {@link mUsageByUserPackage} for user notification on resource overuse. */
    @GuardedBy("mLock")
    private final ArraySet<String> mUserNotifiablePackages = new ArraySet<>();
    /** Values are the unique ids generated by {@code getUserPackageUniqueId}. */
    @GuardedBy("mLock")
    private final SparseArray<String> mActiveUserNotificationsByNotificationId =
            new SparseArray<>();
    /** Keys are the unique ids generated by {@code getUserPackageUniqueId}. */
    @GuardedBy("mLock")
    private final ArraySet<String> mActiveUserNotifications = new ArraySet<>();
    /**
     * Keys in {@link mUsageByUserPackage} that should be killed/disabled due to resource overuse.
     */
    @GuardedBy("mLock")
    private final ArraySet<String> mActionableUserPackages = new ArraySet<>();
    /**
     * Tracks user packages disabled due to resource overuse.
     */
    @GuardedBy("mLock")
    private final SparseArray<ArraySet<String>> mDisabledUserPackagesByUserId = new SparseArray<>();
    @GuardedBy("mLock")
    private List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>
            mPendingSetResourceOveruseConfigurationsRequest = null;
    @GuardedBy("mLock")
    private boolean mIsConnectedToDaemon;
    @GuardedBy("mLock")
    private @UxStateType int mCurrentUxState = UX_STATE_NO_DISTRACTION;
    @GuardedBy("mLock")
    private CarUxRestrictions mCurrentUxRestrictions;
    @GuardedBy("mLock")
    private boolean mIsHeadsUpNotificationSent;
    @GuardedBy("mLock")
    private int mCurrentOveruseNotificationIdOffset;
    @GuardedBy("mLock")
    private @GarageMode int mCurrentGarageMode = GarageMode.GARAGE_MODE_OFF;
    @GuardedBy("mLock")
    private long mOveruseHandlingDelayMills = OVERUSE_HANDLING_DELAY_MILLS;
    @GuardedBy("mLock")
    private ZonedDateTime mLastSystemIoUsageSummaryReportedDate;
    @GuardedBy("mLock")
    private ZonedDateTime mLastUidIoUsageSummaryReportedDate;

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
        mCarWatchdogDaemonHelper = daemonHelper;
        mPackageInfoHandler = packageInfoHandler;
        mMainHandler = new Handler(Looper.getMainLooper());
        mServiceHandler = serviceHandler;
        mWatchdogStorage = watchdogStorage;
        mOveruseConfigurationCache = new OveruseConfigurationCache();
        mTimeSource = timeSource;
        mCarStatsLogWrapper = carStatsLogWrapper;
        Resources resources = mContext.getResources();

        // TODO(b/400460188): Store the resource values in constructor local vars and pass them
        //  only to the IoOveruseHandler constructor once all usages in WatchdogPerfHandler is
        //  removed.
        mUidIoUsageSummaryTopCount = resources.getInteger(R.integer.uidIoUsageSummaryTopCount);
        mIoUsageSummaryMinSystemTotalWrittenBytes =
                resources.getInteger(R.integer.ioUsageSummaryMinSystemTotalWrittenBytes);
        mPackageKillableStateResetDays =
                resources.getInteger(R.integer.watchdogUserPackageSettingsResetDays);
        mRecurringOverusePeriodInDays =
                resources.getInteger(R.integer.recurringResourceOverusePeriodInDays);
        mRecurringOveruseTimes = resources.getInteger(R.integer.recurringResourceOveruseTimes);
        mIoOveruseHandler = new IoOveruseHandler(context, mBuiltinPackageContext, daemonHelper,
                packageInfoHandler, watchdogStorage, timeSource, mUidIoUsageSummaryTopCount,
                mIoUsageSummaryMinSystemTotalWrittenBytes, mPackageKillableStateResetDays,
                mRecurringOverusePeriodInDays, mRecurringOveruseTimes, serviceHandler,
                mCarStatsLogWrapper);
        mResourceOveruseNotificationBaseId =
                NotificationHelperBase.RESOURCE_OVERUSE_NOTIFICATION_BASE_ID;
        mResourceOveruseNotificationMaxOffset =
                NotificationHelperBase.RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET;
    }

    // TODO(b/400460188): Reuse init from IoOveruseHandler.java.
    /** Initializes the handler. */
    public void init() {
        // First database read is expensive, so post it on a separate handler thread.
        mServiceHandler.post(() -> {
            mIoOveruseHandler.readFromDatabase();
            // Set atom pull callbacks only after the internal datastructures are updated. When the
            // pull happens, the service is already initialized and ready to populate the pulled
            // atoms.
            Trace.beginSection("WdPerfHandler.init-async-atomSetUp");
            StatsManager statsManager = mContext.getSystemService(StatsManager.class);
            statsManager.setPullAtomCallback(CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY,
                    PULL_ATOM_METADATA, ConcurrentUtils.DIRECT_EXECUTOR, this::onPullAtom);
            statsManager.setPullAtomCallback(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY,
                    PULL_ATOM_METADATA, ConcurrentUtils.DIRECT_EXECUTOR, this::onPullAtom);
            Trace.endSection();
        });

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
        /*
         * TODO(b/183436216): Implement this method.
         */
        synchronized (mLock) {
            writer.println("Current UX state: " + toUxStateString(mCurrentUxState));
            writer.println("List of disabled packages per user due to resource overuse: "
                    + mDisabledUserPackagesByUserId);
        }
        mOveruseConfigurationCache.dump(writer);
    }

    /** Dumps its state in proto format */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    @Override
    public void dumpProto(ProtoOutputStream proto) {
        synchronized (mLock) {
            long performanceDumpToken = proto.start(CarWatchdogDumpProto.PERFORMANCE_DUMP);
            proto.write(PerformanceDump.CURRENT_UX_STATE, toProtoUxState(mCurrentUxState));
            for (int i = 0; i < mDisabledUserPackagesByUserId.size(); i++) {
                for (int j = 0; j < mDisabledUserPackagesByUserId.valueAt(i).size(); j++) {
                    long disabledUserPackagesToken = proto.start(
                            PerformanceDump.DISABLED_USER_PACKAGES);
                    proto.write(UserPackageInfo.USER_ID,
                            mDisabledUserPackagesByUserId.keyAt(i));
                    proto.write(UserPackageInfo.PACKAGE_NAME,
                            mDisabledUserPackagesByUserId.valueAt(i).valueAt(j));
                    proto.end(disabledUserPackagesToken);
                }
            }
            proto.write(PerformanceDump.UID_IO_USAGE_SUMMARY_TOP_COUNT, mUidIoUsageSummaryTopCount);
            proto.write(PerformanceDump.IO_USAGE_SUMMARY_MIN_SYSTEM_TOTAL_WRITTEN_BYTES,
                    mIoUsageSummaryMinSystemTotalWrittenBytes);
            proto.write(PerformanceDump.PACKAGE_KILLABLE_STATE_RESET_DAYS,
                    mPackageKillableStateResetDays);
            proto.write(PerformanceDump.RECURRING_OVERUSE_PERIOD_DAYS,
                    mRecurringOverusePeriodInDays);
            proto.write(PerformanceDump.RESOURCE_OVERUSE_NOTIFICATION_BASE_ID,
                    mResourceOveruseNotificationBaseId);
            proto.write(PerformanceDump.RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET,
                    mResourceOveruseNotificationMaxOffset);
            proto.write(PerformanceDump.IS_CONNECTED_TO_DAEMON, mIsConnectedToDaemon);
            proto.write(PerformanceDump.IS_HEADS_UP_NOTIFICATION_SENT, mIsHeadsUpNotificationSent);
            proto.write(PerformanceDump.CURRENT_OVERUSE_NOTIFICATION_ID_OFFSET,
                    mCurrentOveruseNotificationIdOffset);
            proto.write(PerformanceDump.IS_GARAGE_MODE_ACTIVE, mCurrentGarageMode);
            proto.write(PerformanceDump.OVERUSE_HANDLING_DELAY_MILLIS, mOveruseHandlingDelayMills);

            long systemDateTimeToken = proto.start(
                    PerformanceDump.LAST_SYSTEM_IO_USAGE_SUMMARY_REPORTED_UTC_DATETIME);
            long systemDateToken = proto.start(DateTime.DATE);
            proto.write(Date.YEAR, mLastSystemIoUsageSummaryReportedDate.getYear());
            proto.write(Date.MONTH, mLastSystemIoUsageSummaryReportedDate.getMonthValue());
            proto.write(Date.DAY, mLastSystemIoUsageSummaryReportedDate.getDayOfMonth());
            proto.end(systemDateToken);
            long systemTimeOfDayToken = proto.start(DateTime.TIME_OF_DAY);
            proto.write(TimeOfDay.HOURS, mLastSystemIoUsageSummaryReportedDate.getHour());
            proto.write(TimeOfDay.MINUTES, mLastSystemIoUsageSummaryReportedDate.getMinute());
            proto.write(TimeOfDay.SECONDS, mLastSystemIoUsageSummaryReportedDate.getSecond());
            proto.end(systemTimeOfDayToken);
            proto.end(systemDateTimeToken);

            long uidDateTimeToken = proto.start(
                    PerformanceDump.LAST_UID_IO_USAGE_SUMMARY_REPORTED_UTC_DATETIME);
            long uidDateToken = proto.start(DateTime.DATE);
            proto.write(Date.YEAR, mLastUidIoUsageSummaryReportedDate.getYear());
            proto.write(Date.MONTH, mLastUidIoUsageSummaryReportedDate.getMonthValue());
            proto.write(Date.DAY, mLastUidIoUsageSummaryReportedDate.getDayOfMonth());
            proto.end(uidDateToken);
            long uidTimeOfDayToken = proto.start(DateTime.TIME_OF_DAY);
            proto.write(TimeOfDay.HOURS, mLastUidIoUsageSummaryReportedDate.getHour());
            proto.write(TimeOfDay.MINUTES, mLastUidIoUsageSummaryReportedDate.getMinute());
            proto.write(TimeOfDay.SECONDS, mLastUidIoUsageSummaryReportedDate.getSecond());
            proto.end(uidTimeOfDayToken);
            proto.end(uidDateTimeToken);

            dumpUsageByUserPackageLocked(proto);

            // TODO(b/400460188): mOveruseListenerInfosByUid and mOveruseSystemListenerInfosByUid
            // will be dumped from IoOveruseHandler.dump after the current method starts to forward
            // the dump call to IoOveruseHandler.dump.

            for (int i = 0; i < mDefaultNotKillableGenericPackages.size(); i++) {
                proto.write(PerformanceDump.DEFAULT_NOT_KILLABLE_GENERIC_PACKAGES,
                        mDefaultNotKillableGenericPackages.valueAt(i));
            }

            dumpUserPackageInfo(mUserNotifiablePackages,
                    PerformanceDump.USER_NOTIFIABLE_PACKAGES, proto);

            dumpUserPackageInfo(mActiveUserNotifications,
                    PerformanceDump.ACTIVE_USER_NOTIFICATIONS, proto);

            dumpUserPackageInfo(mActionableUserPackages,
                    PerformanceDump.ACTIONABLE_USER_PACKAGES, proto);

            proto.write(PerformanceDump.IS_PENDING_RESOURCE_OVERUSE_CONFIGURATIONS_REQUEST,
                    mPendingSetResourceOveruseConfigurationsRequest != null);

            mOveruseConfigurationCache.dumpProto(proto);

            proto.end(performanceDumpToken);
        }
    }

    /** Retries any pending requests on re-connecting to the daemon */
    @Override
    public void onDaemonConnectionChange(boolean isConnected) {
        Trace.beginSection("WdPerfHandler.onDaemonConnectionChange(isConnected=" + isConnected
                + ")");
        boolean hasPendingRequest;
        synchronized (mLock) {
            mIsConnectedToDaemon = isConnected;
            hasPendingRequest = mPendingSetResourceOveruseConfigurationsRequest != null;
            if (isConnected) {
                if (hasPendingRequest) {
                    /*
                     * Retry pending set resource overuse configuration request before processing
                     * any new set/get requests. Thus notify the waiting requests only after the
                     * retry completes.
                     *
                     * Note: Binder call to CarWatchdog daemon being made while holding the lock.
                     * It is necessary to avoid isConnectedToDaemon() method from early exiting
                     * because of interrupts or spurious wakeups.
                     */
                    retryPendingSetResourceOveruseConfigurations();
                } else {
                    /*
                     * Start fetch/sync configs only when there are no pending set requests because
                     * the above retry starts fetch/sync configs on success. If the retry fails,
                     * the daemon has crashed and shouldn't start
                     * fetchAndSyncResourceOveruseConfigurations.
                     */
                    mMainHandler.post(this::fetchAndSyncResourceOveruseConfigurations);
                }
            }
            mLock.notifyAll();
        }
        Trace.endSection();
    }

    /** Updates the current UX state based on the display state. */
    @Override
    public void onDisplayStateChanged(boolean isEnabled) {
        Trace.beginSection("WdPerfHandler.onDisplayStateChanged(isEnabled=" + isEnabled + ")");
        synchronized (mLock) {
            if (isEnabled) {
                mCurrentUxState = UX_STATE_NO_DISTRACTION;
                applyCurrentUxRestrictionsLocked();
            } else {
                mCurrentUxState = UX_STATE_NO_INTERACTION;
                performOveruseHandlingLocked();
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
                mCurrentUxState = UX_STATE_NO_INTERACTION;
                performOveruseHandlingLocked();
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
                        && uniqueUserPackageId.equals(getUserPackageUniqueId(
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
    public void processPackageChangedIntent(Intent intent) {
        mIoOveruseHandler.processPackageChangedIntent(intent);
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

    /** Fetches and syncs the resource overuse configurations from watchdog daemon. */
    private void fetchAndSyncResourceOveruseConfigurations() {
        Trace.beginSection("WdPerfHandler.fetchAndSyncResourceOveruseConfigurations");
        try {
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
        } finally {
            Trace.endSection();
        }
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
        Trace.beginSection("WdPerfHandler.setResourceOveruseConfigurationsInternal");
        try {
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
        } finally {
            Trace.endSection();
        }
        return CarWatchdogManager.RETURN_CODE_SUCCESS;
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

    @GuardedBy("mLock")
    private void performOveruseHandlingLocked() {
        if (mCurrentUxState == UX_STATE_NO_DISTRACTION) {
            return;
        }
        if (!mUserNotifiablePackages.isEmpty()) {
            // Notifications are presented asynchronously, therefore the delay added by posting
            // to the handler should not affect the system behavior.
            mServiceHandler.post(this::notifyUserOnOveruse);
        }
        if (mActionableUserPackages.isEmpty() || mCurrentUxState != UX_STATE_NO_INTERACTION) {
            return;
        }
        Trace.beginSection("WdPerfHandler.performOveruseHandlingLocked");
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
            List<String> packages;
            if (usage.isSharedPackage()) {
                packages = mPackageInfoHandler.getPackagesForUid(usage.getUid(),
                        usage.genericPackageName);
            } else {
                packages = Collections.singletonList(usage.genericPackageName);
            }
            boolean isKilled = false;
            for (int pkgIdx = 0; pkgIdx < packages.size(); pkgIdx++) {
                String packageName = packages.get(pkgIdx);
                boolean isPackageDisabled = disablePackageForUser(packageName, usage.userId);
                android.automotive.watchdog.PerStateBytes writtenBytes =
                        usage.ioUsage.getInternalIoOveruseStats().writtenBytes;
                @ComponentType int componentType = mPackageInfoHandler.getComponentType(
                        usage.getUid(), usage.genericPackageName);
                android.automotive.watchdog.PerStateBytes thresholdBytes =
                        mOveruseConfigurationCache.fetchThreshold(usage.genericPackageName,
                                                                  componentType);
                EventLogHelper.writeCarWatchdogServiceIoOveruseKill(packageName, usage.userId,
                        writtenBytes.foregroundBytes, writtenBytes.backgroundBytes,
                        writtenBytes.garageModeBytes, thresholdBytes.foregroundBytes,
                        thresholdBytes.backgroundBytes, thresholdBytes.garageModeBytes,
                        usage.ioUsage.getTotalTimesKilled(), isPackageDisabled);
                isKilled |= isPackageDisabled;
            }
            if (isKilled) {
                usage.ioUsage.onKilled();
                killedUserPackageKeys.add(usage.getUniqueId());
            }
        }
        pushIoOveruseKillMetrics(killedUserPackageKeys);
        mActionableUserPackages.clear();
        Trace.endSection();
    }

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
                    String userPackageUniqueId = getUserPackageUniqueId(currentUserId, packageName);
                    if (mActiveUserNotifications.contains(userPackageUniqueId)) {
                        Slogf.e(TAG, "Dropping notification for user %d and package %s as it has "
                                + "an active notification", currentUserId, packageName);
                        continue;
                    }
                    int notificationId = mResourceOveruseNotificationBaseId
                            + mCurrentOveruseNotificationIdOffset;
                    if (mCurrentUxState == UX_STATE_NO_INTERACTION || mIsHeadsUpNotificationSent) {
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

    // TODO(b/400460188): Make the below function as a callback, which will be called from
    // IoOveruseMonitor.
    private void appendToDisabledPackagesSettingsString(String packageName, @UserIdInt int userId) {
        ContentResolver contentResolverForUser = getContentResolverForUser(mContext, userId);
        // Appending and removing package names to/from the settings string
        // KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE is done only by this class. So, synchronize
        // these operations using the class wide lock.
        synchronized (mLock) {
            ArraySet<String> packages = extractPackages(
                    Settings.Secure.getString(contentResolverForUser,
                            KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE));
            if (!packages.add(packageName)) {
                return;
            }
            String settingsString = constructSettingsString(packages);
            Settings.Secure.putString(contentResolverForUser,
                    KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE, settingsString);
            if (DEBUG) {
                Slogf.d(TAG, "Appended %s to %s. New value is '%s'", packageName,
                        KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE, settingsString);
            }
        }
    }

    // TODO(b/400460188): Make the below function as a callback, which will be called from
    // IoOveruseMonitor.
    /**
     * Removes {@code packageName} from {@link KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE}
     * {@code Settings} of the given user.
     *
     * <p> Appending and removing package names to/from the settings string
     *     KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE is done only by this class. So, synchronize
     *     these operations using the class wide lock.
     */
    @GuardedBy("mLock")
    private void removeFromDisabledPackagesSettingsStringLocked(String packageName,
            @UserIdInt int userId) {
        ContentResolver contentResolverForUser = getContentResolverForUser(mContext, userId);
        ArraySet<String> packages = extractPackages(
                Settings.Secure.getString(contentResolverForUser,
                        KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE));
        if (!packages.remove(packageName)) {
            return;
        }
        String settingsString = constructSettingsString(packages);
        Settings.Secure.putString(contentResolverForUser,
                KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE, settingsString);
        if (DEBUG) {
            Slogf.d(TAG, "Removed %s from %s. New value is '%s'", packageName,
                    KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE, settingsString);
        }
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
        for (int i = 0; i < userIds.length; i++) {
            int userId = userIds[i];
            ContentResolver contentResolverForUser = getContentResolverForUser(mContext, userId);
            ArraySet<String> packages = extractPackages(
                    Settings.Secure.getString(contentResolverForUser,
                            KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE));
            if (packages.isEmpty()) {
                continue;
            }
            mDisabledUserPackagesByUserId.put(userId, packages);
        }
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
            mCarStatsLogWrapper.write(CAR_WATCHDOG_KILL_STATS_REPORTED, statsByUid.keyAt(i),
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

    private int onPullAtom(int atomTag, List<StatsEvent> data) {
        if (atomTag != CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY
                && atomTag != CAR_WATCHDOG_UID_IO_USAGE_SUMMARY) {
            Slogf.e(TAG, "Unexpected atom tag: %d", atomTag);
            return PULL_SKIP;
        }
        synchronized (mLock) {
            if (mLastSystemIoUsageSummaryReportedDate == null
                    || mLastUidIoUsageSummaryReportedDate == null) {
                readMetadataFileLocked();
            }
        }
        ZonedDateTime reportDate;
        switch (atomTag) {
            case CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY:
                synchronized (mLock) {
                    reportDate = mLastSystemIoUsageSummaryReportedDate;
                }
                pullAtomsForWeeklyPeriodsSinceReportedDate(reportDate, data,
                        this::pullSystemIoUsageSummaryStatsEvents);
                synchronized (mLock) {
                    mLastSystemIoUsageSummaryReportedDate = mTimeSource.getCurrentDate();
                }
                break;
            case CAR_WATCHDOG_UID_IO_USAGE_SUMMARY:
                synchronized (mLock) {
                    reportDate = mLastUidIoUsageSummaryReportedDate;
                }
                pullAtomsForWeeklyPeriodsSinceReportedDate(reportDate, data,
                        this::pullUidIoUsageSummaryStatsEvents);
                synchronized (mLock) {
                    mLastUidIoUsageSummaryReportedDate = mTimeSource.getCurrentDate();
                }
                break;
            default:
                Slogf.i(TAG, "Skipping pull atom request on invalid watchdog atom tag: %d",
                        atomTag);
        }
        return PULL_SUCCESS;
    }

    @GuardedBy("mLock")
    private void readMetadataFileLocked() {
        mLastSystemIoUsageSummaryReportedDate = mLastUidIoUsageSummaryReportedDate =
                mTimeSource.getCurrentDate().minus(RETENTION_PERIOD);
        File file = getWatchdogMetadataFile();
        if (!file.exists()) {
            Slogf.e(TAG, "Watchdog metadata file '%s' doesn't exist", file.getAbsoluteFile());
            return;
        }
        AtomicFile atomicFile = new AtomicFile(file);
        try (FileInputStream fis = atomicFile.openRead()) {
            JsonReader reader = new JsonReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case SYSTEM_IO_USAGE_SUMMARY_REPORTED_DATE:
                        mLastSystemIoUsageSummaryReportedDate =
                                ZonedDateTime.parse(reader.nextString(),
                                        DateTimeFormatter.ISO_DATE_TIME.withZone(ZONE_OFFSET));
                        break;
                    case UID_IO_USAGE_SUMMARY_REPORTED_DATE:
                        mLastUidIoUsageSummaryReportedDate =
                                ZonedDateTime.parse(reader.nextString(),
                                        DateTimeFormatter.ISO_DATE_TIME.withZone(ZONE_OFFSET));
                        break;
                    default:
                        Slogf.w(TAG, "Unrecognized key: %s", name);
                        reader.skipValue();
                }
            }
            reader.endObject();
            if (DEBUG) {
                Slogf.e(TAG, "Successfully read watchdog metadata file '%s'",
                        file.getAbsoluteFile());
            }
        } catch (IOException e) {
            Slogf.e(TAG, e, "Failed to read watchdog metadata file '%s'", file.getAbsoluteFile());
        } catch (NumberFormatException | IllegalStateException | DateTimeParseException e) {
            Slogf.e(TAG, e, "Unexpected format in watchdog metadata file '%s'",
                    file.getAbsoluteFile());
        }
    }

    private void pullAtomsForWeeklyPeriodsSinceReportedDate(ZonedDateTime reportedDate,
            List<StatsEvent> data, BiConsumer<Pair<ZonedDateTime, ZonedDateTime>,
            List<StatsEvent>> pullAtomCallback) {
        ZonedDateTime now = mTimeSource.getCurrentDate();
        ZonedDateTime nextReportWeekStartDate = reportedDate.with(ChronoField.DAY_OF_WEEK, 1)
                .truncatedTo(ChronoUnit.DAYS);
        while (ChronoUnit.WEEKS.between(nextReportWeekStartDate, now) > 0) {
            pullAtomCallback.accept(
                    new Pair<>(nextReportWeekStartDate, nextReportWeekStartDate.plusWeeks(1)),
                    data);
            nextReportWeekStartDate = nextReportWeekStartDate.plusWeeks(1);
        }
    }

    private void pullSystemIoUsageSummaryStatsEvents(Pair<ZonedDateTime, ZonedDateTime> period,
            List<StatsEvent> data) {
        List<AtomsProto.CarWatchdogDailyIoUsageSummary> dailyIoUsageSummaries =
                mWatchdogStorage.getDailySystemIoUsageSummaries(
                        mIoUsageSummaryMinSystemTotalWrittenBytes, period.first.toEpochSecond(),
                        period.second.toEpochSecond());
        if (dailyIoUsageSummaries == null) {
            Slogf.i(TAG, "No system I/O usage summary stats available to pull");
            return;
        }

        AtomsProto.CarWatchdogEventTimePeriod evenTimePeriod =
                AtomsProto.CarWatchdogEventTimePeriod.newBuilder()
                        .setPeriod(AtomsProto.CarWatchdogEventTimePeriod.Period.WEEKLY).build();
        data.add(mCarStatsLogWrapper.buildStatsEvent(CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY,
                AtomsProto.CarWatchdogIoUsageSummary.newBuilder()
                        .setEventTimePeriod(evenTimePeriod)
                        .addAllDailyIoUsageSummary(dailyIoUsageSummaries).build()
                        .toByteArray(),
                period.first.toEpochSecond() * 1000));

        Slogf.i(TAG, "Successfully pulled system I/O usage summary stats");
    }

    private void pullUidIoUsageSummaryStatsEvents(Pair<ZonedDateTime, ZonedDateTime> period,
            List<StatsEvent> data) {
        // Fetch summaries for twice the top N user packages because if the UID cannot be resolved
        // for some user packages, the fetched summaries will still contain enough entries to pull.
        List<WatchdogStorage.UserPackageDailySummaries> topUsersDailyIoUsageSummaries =
                mWatchdogStorage.getTopUsersDailyIoUsageSummaries(mUidIoUsageSummaryTopCount * 2,
                        mIoUsageSummaryMinSystemTotalWrittenBytes,
                        period.first.toEpochSecond(), period.second.toEpochSecond());
        if (topUsersDailyIoUsageSummaries == null) {
            Slogf.i(TAG, "No top users' I/O usage summary stats available to pull");
            return;
        }

        SparseArray<List<String>> genericPackageNamesByUserId = new SparseArray<>();
        for (int i = 0; i < topUsersDailyIoUsageSummaries.size(); ++i) {
            WatchdogStorage.UserPackageDailySummaries entry =
                    topUsersDailyIoUsageSummaries.get(i);
            List<String> genericPackageNames = genericPackageNamesByUserId.get(entry.userId);
            if (genericPackageNames == null) {
                genericPackageNames = new ArrayList<>();
            }
            genericPackageNames.add(entry.packageName);
            genericPackageNamesByUserId.put(entry.userId, genericPackageNames);
        }

        SparseArray<Map<String, Integer>> packageUidsByUserId =
                getPackageUidsForUsers(genericPackageNamesByUserId);

        AtomsProto.CarWatchdogEventTimePeriod.Builder evenTimePeriodBuilder =
                AtomsProto.CarWatchdogEventTimePeriod.newBuilder()
                        .setPeriod(AtomsProto.CarWatchdogEventTimePeriod.Period.WEEKLY);

        long startEpochMillis = period.first.toEpochSecond() * 1000;
        int numPulledUidSummaryStats = 0;
        for (int i = 0; i < topUsersDailyIoUsageSummaries.size()
                && numPulledUidSummaryStats < mUidIoUsageSummaryTopCount; ++i) {
            WatchdogStorage.UserPackageDailySummaries entry = topUsersDailyIoUsageSummaries.get(i);
            Map<String, Integer> uidsByGenericPackageName = packageUidsByUserId.get(entry.userId);
            if (uidsByGenericPackageName == null
                    || !uidsByGenericPackageName.containsKey(entry.packageName)) {
                Slogf.e(TAG, "Failed to fetch uid for package %s and user %d. So, skipping "
                        + "reporting stats for this user package", entry.packageName, entry.userId);
                continue;
            }
            data.add(mCarStatsLogWrapper.buildStatsEvent(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY,
                    uidsByGenericPackageName.get(entry.packageName),
                    AtomsProto.CarWatchdogIoUsageSummary.newBuilder()
                            .setEventTimePeriod(evenTimePeriodBuilder)
                            .addAllDailyIoUsageSummary(entry.dailyIoUsageSummaries).build()
                            .toByteArray(),
                    startEpochMillis));
            ++numPulledUidSummaryStats;
        }

        Slogf.e(TAG, "Successfully pulled top %d users' I/O usage summary stats",
                numPulledUidSummaryStats);
    }

    private SparseArray<Map<String, Integer>> getPackageUidsForUsers(
            SparseArray<List<String>> genericPackageNamesByUserId) {
        PackageManager pm = mContext.getPackageManager();
        SparseArray<Map<String, Integer>> packageUidsByUserId = new SparseArray<>();
        for (int i = 0; i < genericPackageNamesByUserId.size(); ++i) {
            int userId = genericPackageNamesByUserId.keyAt(i);
            Map<String, Integer> uidsByGenericPackageName = getPackageUidsForUser(pm,
                    genericPackageNamesByUserId.valueAt(i), userId);
            if (!uidsByGenericPackageName.isEmpty()) {
                packageUidsByUserId.put(userId, uidsByGenericPackageName);
            }
        }
        return packageUidsByUserId;
    }

    /**
     * Returns UIDs for the given generic package names belonging to the given user.
     *
     * <p>{@code pm.getInstalledPackagesAsUser} call is expensive as it fetches all installed
     * packages for the given user. Thus this method should be called for all packages that requires
     * the UIDs to be resolved in a single call.
     */
    private Map<String, Integer> getPackageUidsForUser(PackageManager pm,
            List<String> genericPackageNames, int userId) {
        Map<String, Integer> uidsByGenericPackageNames = new ArrayMap<>();
        Set<String> resolveSharedUserIds = new ArraySet<>();
        for (int i = 0; i < genericPackageNames.size(); ++i) {
            String genericPackageName = genericPackageNames.get(i);
            PackageResourceUsage usage;
            synchronized (mLock) {
                usage = mUsageByUserPackage.get(getUserPackageUniqueId(userId,
                        genericPackageName));
            }
            if (usage != null && usage.getUid() != INVALID_UID) {
                uidsByGenericPackageNames.put(genericPackageName, usage.getUid());
                continue;
            }
            if (isSharedPackage(genericPackageName)) {
                resolveSharedUserIds.add(
                        genericPackageName.substring(SHARED_PACKAGE_PREFIX.length()));
                continue;
            }
            int uid = getPackageUidAsUser(pm, genericPackageName, userId);
            if (uid != INVALID_UID) {
                uidsByGenericPackageNames.put(genericPackageName, uid);
            }
        }
        if (resolveSharedUserIds.isEmpty()) {
            return uidsByGenericPackageNames;
        }
        List<PackageInfo> packageInfos = pm.getInstalledPackagesAsUser(/* flags= */ 0, userId);
        for (int i = 0; i < packageInfos.size() && !resolveSharedUserIds.isEmpty(); ++i) {
            PackageInfo packageInfo = packageInfos.get(i);
            if (packageInfo.sharedUserId == null
                    || !resolveSharedUserIds.contains(packageInfo.sharedUserId)) {
                continue;
            }
            int uid = getPackageUidAsUser(pm, packageInfo.packageName, userId);
            if (uid != INVALID_UID) {
                uidsByGenericPackageNames.put(SHARED_PACKAGE_PREFIX + packageInfo.sharedUserId,
                        uid);
            }
            resolveSharedUserIds.remove(packageInfo.sharedUserId);
        }
        return uidsByGenericPackageNames;
    }

    private int getPackageUidAsUser(PackageManager pm, String packageName, @UserIdInt int userId) {
        try {
            return PackageManagerHelper.getPackageUidAsUser(pm, packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slogf.e(TAG, "Package %s for user %d is not found", packageName, userId);
            return INVALID_UID;
        }
    }

    @GuardedBy("mLock")
    private void dumpUsageByUserPackageLocked(ProtoOutputStream proto) {
        for (int i = 0; i < mUsageByUserPackage.size(); i++) {
            long usageByUserPackagesToken = proto.start(PerformanceDump.USAGE_BY_USER_PACKAGES);

            dumpUserPackageInfoFromUniqueId(mUsageByUserPackage.keyAt(i),
                    PerformanceDump.UsageByUserPackage.USER_PACKAGE_INFO, proto);

            PackageResourceUsage packageResourceUsage = mUsageByUserPackage.valueAt(i);
            PackageIoUsage packageIoUsage = packageResourceUsage.ioUsage;

            proto.write(PerformanceDump.UsageByUserPackage.KILLABLE_STATE,
                    toProtoKillableState(packageResourceUsage.mKillableState));

            packageIoUsage.dumpProto(proto);

            proto.end(usageByUserPackagesToken);
        }
    }

    private static void dumpUserPackageInfo(ArraySet<String> userPackageInfo, long fieldId,
            ProtoOutputStream proto) {
        for (int i = 0; i < userPackageInfo.size(); i++) {
            dumpUserPackageInfoFromUniqueId(userPackageInfo.valueAt(i), fieldId, proto);
        }
    }

    private static void dumpUserPackageInfoFromUniqueId(String uniqueId, long fieldId,
            ProtoOutputStream proto) {
        long fieldIdToken = proto.start(fieldId);
        proto.write(UserPackageInfo.USER_ID, getUserIdFromUniqueId(uniqueId));
        proto.write(UserPackageInfo.PACKAGE_NAME, getPackageNameFromUniqueId(uniqueId));
        proto.end(fieldIdToken);
    }

    private static File getWatchdogMetadataFile() {
        return new File(CarWatchdogService.getWatchdogDirFile(), METADATA_FILENAME);
    }

    private static String getUserPackageUniqueId(@UserIdInt int userId, String genericPackageName) {
        return userId + USER_PACKAGE_SEPARATOR + genericPackageName;
    }

    private static String getPackageNameFromUniqueId(String uniqueId) {
        return uniqueId.split(USER_PACKAGE_SEPARATOR)[0];
    }

    private static String getUserIdFromUniqueId(String uniqueId) {
        return uniqueId.split(USER_PACKAGE_SEPARATOR)[1];
    }

    private static boolean isSharedPackage(String genericPackageName) {
        return genericPackageName.startsWith(SHARED_PACKAGE_PREFIX);
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
        AtomsProto.CarWatchdogPerStateBytes.Builder perStateBytesBuilder =
                AtomsProto.CarWatchdogPerStateBytes.newBuilder();
        if (foregroundBytes != 0) {
            perStateBytesBuilder.setForegroundBytes(foregroundBytes);
        }
        if (backgroundBytes != 0) {
            perStateBytesBuilder.setBackgroundBytes(backgroundBytes);
        }
        if (garageModeBytes != 0) {
            perStateBytesBuilder.setGarageModeBytes(garageModeBytes);
        }
        return perStateBytesBuilder.build();
    }

    private static String toUxStateString(@UxStateType int uxState) {
        switch (uxState) {
            case UX_STATE_NO_DISTRACTION:
                return "UX_STATE_NO_DISTRACTION";
            case UX_STATE_USER_NOTIFICATION:
                return "UX_STATE_USER_NOTIFICATION";
            case UX_STATE_NO_INTERACTION:
                return "UX_STATE_NO_INTERACTION";
            default:
                return "UNKNOWN UX STATE";
        }
    }

    private static int toProtoUxState(@UxStateType int uxState) {
        switch (uxState) {
            case UX_STATE_NO_DISTRACTION:
                return PerformanceDump.UX_STATE_NO_DISTRACTION;
            case UX_STATE_USER_NOTIFICATION:
                return PerformanceDump.UX_STATE_USER_NOTIFICATION;
            case UX_STATE_NO_INTERACTION:
                return PerformanceDump.UX_STATE_NO_INTERACTION;
            default:
                return PerformanceDump.UX_STATE_UNSPECIFIED;
        }
    }

    private static int toProtoKillableState(@KillableState int killableState) {
        switch (killableState) {
            case KILLABLE_STATE_YES:
                return PerformanceDump.KILLABLE_STATE_YES;
            case KILLABLE_STATE_NO:
                return PerformanceDump.KILLABLE_STATE_NO;
            case KILLABLE_STATE_NEVER:
                return PerformanceDump.KILLABLE_STATE_NEVER;
            default:
                return PerformanceDump.KILLABLE_STATE_UNSPECIFIED;
        }
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
}
