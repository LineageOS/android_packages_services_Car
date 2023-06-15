/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_BASELINE;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetAllUsers;
import static android.car.watchdog.CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO;

import static com.android.car.CarStatsLog.CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_UID_IO_USAGE_SUMMARY;
import static com.android.car.watchdog.WatchdogStorage.WatchdogDbHelper.DATABASE_NAME;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.StatsManager;
import android.automotive.watchdog.internal.PackageIoOveruseStats;
import android.automotive.watchdog.internal.ResourceStats;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.IResourceOveruseListener;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Binder;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.SparseArray;
import android.view.Display;

import com.android.car.CarLocalServices;
import com.android.car.CarServiceUtils;
import com.android.car.CarUxRestrictionsManagerService;
import com.android.car.systeminterface.SystemInterface;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

public class WatchdogPerfHandlerUnitTest extends AbstractExtendedMockitoTestCase {
    private static final int UID_IO_USAGE_SUMMARY_TOP_COUNT = 3;
    private static final int RECURRING_OVERUSE_TIMES = 2;
    private static final int RECURRING_OVERUSE_PERIOD_IN_DAYS = 2;
    private static final int IO_USAGE_SUMMARY_MIN_SYSTEM_TOTAL_WRITTEN_BYTES = 500 * 1024 * 1024;
    private static final long STATS_DURATION_SECONDS = 3 * 60 * 60;
    private static final int OVERUSE_HANDLING_DELAY_MILLS = 1000;
    @Mock
    private Context mMockContext;
    @Mock
    private Context mMockBuiltinPackageContext;
    @Mock
    private CarWatchdogDaemonHelper mMockedCarWatchdogDaemonHelper;
    @Mock
    private PackageInfoHandler mMockedPackageInfoHandler;
    @Mock
    private CarUxRestrictionsManagerService mMockCarUxRestrictionsManagerService;
    @Mock
    private Resources mMockResources;
    @Mock
    private SystemInterface mMockSystemInterface;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private UserManager mMockUserManager;
    @Mock
    private StatsManager mMockStatsManager;
    @Captor
    private ArgumentCaptor<ICarUxRestrictionsChangeListener>
            mICarUxRestrictionsChangeListener;
    @Captor
    private ArgumentCaptor<StatsManager.StatsPullAtomCallback> mStatsPullAtomCallbackCaptor;
    private ICarUxRestrictionsChangeListener mCarUxRestrictionsChangeListener;
    private final SparseArray<String> mGenericPackageNameByUid = new SparseArray<>();
    private StatsManager.StatsPullAtomCallback mStatsPullAtomCallback;
    private WatchdogStorage mSpiedWatchdogStorage;
    private WatchdogPerfHandler mWatchdogPerfHandler;
    private final CarWatchdogServiceUnitTest.TestTimeSource mTimeSource =
            new CarWatchdogServiceUnitTest.TestTimeSource();
    private File mTempSystemCarDir;

    public WatchdogPerfHandlerUnitTest() {
        super(CarWatchdogService.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder.spyStatic(CarLocalServices.class);
    }

    @Before
    public void setUp() throws Exception {
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getSystemService(StatsManager.class)).thenReturn(mMockStatsManager);
        when(mMockContext.getPackageName()).thenReturn(
                CarWatchdogServiceUnitTest.class.getCanonicalName());
        when(mMockResources.getInteger(
                com.android.car.R.integer.recurringResourceOverusePeriodInDays))
                .thenReturn(RECURRING_OVERUSE_PERIOD_IN_DAYS);
        when(mMockResources.getInteger(
                com.android.car.R.integer.recurringResourceOveruseTimes))
                .thenReturn(RECURRING_OVERUSE_TIMES);
        when(mMockResources.getInteger(
                com.android.car.R.integer.uidIoUsageSummaryTopCount))
                .thenReturn(UID_IO_USAGE_SUMMARY_TOP_COUNT);
        when(mMockResources.getInteger(
                com.android.car.R.integer.ioUsageSummaryMinSystemTotalWrittenBytes))
                .thenReturn(IO_USAGE_SUMMARY_MIN_SYSTEM_TOTAL_WRITTEN_BYTES);
        doReturn(mMockCarUxRestrictionsManagerService)
                .when(() -> CarLocalServices.getService(CarUxRestrictionsManagerService.class));
        when(mMockCarUxRestrictionsManagerService.getCurrentUxRestrictions())
                .thenReturn(new CarUxRestrictions.Builder(/* reqOpt= */ false,
                        UX_RESTRICTIONS_BASELINE, /* time= */ 0).build());
        when(mMockedPackageInfoHandler.getNamesForUids(any())).thenAnswer(
                args -> mGenericPackageNameByUid);

        mTempSystemCarDir = Files.createTempDirectory("watchdog_test").toFile();
        when(mMockSystemInterface.getSystemCarDir()).thenReturn(mTempSystemCarDir);

        File tempDbFile = new File(mTempSystemCarDir.getPath(), DATABASE_NAME);
        when(mMockContext.createDeviceProtectedStorageContext()).thenReturn(mMockContext);
        when(mMockContext.getDatabasePath(DATABASE_NAME)).thenReturn(tempDbFile);
        mSpiedWatchdogStorage =
                spy(new WatchdogStorage(mMockContext, /* useDataSystemCarDir= */ false,
                        mTimeSource));
        mWatchdogPerfHandler = new WatchdogPerfHandler(mMockContext,
                mMockBuiltinPackageContext, mMockedCarWatchdogDaemonHelper,
                mMockedPackageInfoHandler,
                mSpiedWatchdogStorage, mTimeSource);

        setupUsers();
        initService(/* wantedInvocations= */ 1);
    }

    /**
     * Releases resources.
     */
    @After
    public void tearDown() throws Exception {
        if (mTempSystemCarDir != null) {
            FileUtils.deleteContentsAndDir(mTempSystemCarDir);
        }
    }

    private void setupUsers() {
        when(mMockContext.getSystemService(UserManager.class)).thenReturn(mMockUserManager);
        mockUmGetAllUsers(mMockUserManager, new UserHandle[0]);
    }

    private void initService(int wantedInvocations) throws Exception {
        mWatchdogPerfHandler.init();
        captureCarUxRestrictionsChangeListener(wantedInvocations);
        verifyDatabaseInit(wantedInvocations);
        captureStatsPullAtomCallback(wantedInvocations);
    }

    @Test
    public void testAddResourceOveruseListenerThrowsWithInvalidFlag() throws Exception {
        IResourceOveruseListener mockListener = createMockResourceOveruseListener();

        assertThrows(IllegalArgumentException.class, () -> {
            mWatchdogPerfHandler.addResourceOveruseListener(0, mockListener);
        });
    }

    @Test
    public void testResourceOveruseListener() throws Exception {
        mGenericPackageNameByUid.put(Binder.getCallingUid(), mMockContext.getPackageName());

        IResourceOveruseListener mockListener = createMockResourceOveruseListener();
        IBinder mockBinder = mockListener.asBinder();

        mWatchdogPerfHandler.addResourceOveruseListener(FLAG_RESOURCE_OVERUSE_IO,
                mockListener);

        verify(mockBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        injectIoOveruseStatsForPackages(
                mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                /* shouldNotifyPackages= */ new ArraySet<>(
                        Collections.singleton(mMockContext.getPackageName())));

        verify(mockListener).onOveruse(any());

        mWatchdogPerfHandler.removeResourceOveruseListener(mockListener);

        verify(mockListener, atLeastOnce()).asBinder();
        verify(mockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        injectIoOveruseStatsForPackages(
                mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                /* shouldNotifyPackages= */ new ArraySet<>(
                        Collections.singletonList(mMockContext.getPackageName())));

        verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void testDuplicateAddResourceOveruseListener() throws Exception {
        mGenericPackageNameByUid.put(Binder.getCallingUid(), mMockContext.getPackageName());

        IResourceOveruseListener mockListener = createMockResourceOveruseListener();
        IBinder mockBinder = mockListener.asBinder();

        mWatchdogPerfHandler.addResourceOveruseListener(FLAG_RESOURCE_OVERUSE_IO,
                mockListener);

        assertThrows(IllegalStateException.class,
                () -> mWatchdogPerfHandler.addResourceOveruseListener(
                        FLAG_RESOURCE_OVERUSE_IO, mockListener));

        verify(mockBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        mWatchdogPerfHandler.removeResourceOveruseListener(mockListener);

        verify(mockListener, atLeastOnce()).asBinder();
        verify(mockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void testAddMultipleResourceOveruseListeners() throws Exception {
        mGenericPackageNameByUid.put(Binder.getCallingUid(), mMockContext.getPackageName());

        IResourceOveruseListener firstMockListener = createMockResourceOveruseListener();
        IBinder firstMockBinder = firstMockListener.asBinder();
        IResourceOveruseListener secondMockListener = createMockResourceOveruseListener();
        IBinder secondMockBinder = secondMockListener.asBinder();

        mWatchdogPerfHandler.addResourceOveruseListener(FLAG_RESOURCE_OVERUSE_IO,
                firstMockListener);
        mWatchdogPerfHandler.addResourceOveruseListener(FLAG_RESOURCE_OVERUSE_IO,
                secondMockListener);

        verify(firstMockBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());
        verify(secondMockBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        injectIoOveruseStatsForPackages(
                mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                /* shouldNotifyPackages= */ new ArraySet<>(
                        Collections.singleton(mMockContext.getPackageName())));

        verify(firstMockListener).onOveruse(any());

        mWatchdogPerfHandler.removeResourceOveruseListener(firstMockListener);

        verify(firstMockListener, atLeastOnce()).asBinder();
        verify(firstMockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        injectIoOveruseStatsForPackages(
                mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                /* shouldNotifyPackages= */ new ArraySet<>(
                        Collections.singletonList(mMockContext.getPackageName())));

        verify(secondMockListener, times(2)).onOveruse(any());

        mWatchdogPerfHandler.removeResourceOveruseListener(secondMockListener);

        verify(secondMockListener, atLeastOnce()).asBinder();
        verify(secondMockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        injectIoOveruseStatsForPackages(
                mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                /* shouldNotifyPackages= */ new ArraySet<>(
                        Collections.singletonList(mMockContext.getPackageName())));

        verifyNoMoreInteractions(firstMockListener);
        verifyNoMoreInteractions(secondMockListener);
    }

    @Test
    public void testAddResourceOveruseListenerForSystemThrowsWithInvalidFlag() throws Exception {
        IResourceOveruseListener mockListener = createMockResourceOveruseListener();

        assertThrows(IllegalArgumentException.class, () -> {
            mWatchdogPerfHandler.addResourceOveruseListenerForSystem(0, mockListener);
        });
    }

    @Test
    public void testResourceOveruseListenerForSystem() throws Exception {
        int callingUid = Binder.getCallingUid();
        mGenericPackageNameByUid.put(callingUid, "system_package.critical");

        IResourceOveruseListener mockListener = createMockResourceOveruseListener();
        mWatchdogPerfHandler.addResourceOveruseListenerForSystem(
                CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO, mockListener);

        IBinder mockBinder = mockListener.asBinder();
        verify(mockBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        List<PackageIoOveruseStats> packageIoOveruseStats = Collections.singletonList(
                constructPackageIoOveruseStats(callingUid, /* shouldNotify= */ true,
                        /* forgivenWriteBytes= */ constructPerStateBytes(80, 170, 260),
                        constructInternalIoOveruseStats(/* killableOnOveruse= */ true,
                                /* remainingWriteBytes= */ constructPerStateBytes(20, 20, 20),
                                /* writtenBytes= */ constructPerStateBytes(100, 200, 300),
                                /* totalOveruses= */ 3)));

        pushLatestIoOveruseStatsAndWait(packageIoOveruseStats);

        verify(mockListener).onOveruse(any());

        mWatchdogPerfHandler.removeResourceOveruseListenerForSystem(mockListener);

        verify(mockListener, atLeastOnce()).asBinder();
        verify(mockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        pushLatestIoOveruseStatsAndWait(packageIoOveruseStats);

        verifyNoMoreInteractions(mockListener);
    }

    private void captureCarUxRestrictionsChangeListener(int wantedInvocations) {
        verify(mMockCarUxRestrictionsManagerService, times(wantedInvocations))
                .getCurrentUxRestrictions();
        verify(mMockCarUxRestrictionsManagerService, times(wantedInvocations))
                .registerUxRestrictionsChangeListener(mICarUxRestrictionsChangeListener.capture(),
                        eq(Display.DEFAULT_DISPLAY));
        mCarUxRestrictionsChangeListener = mICarUxRestrictionsChangeListener.getValue();
        assertWithMessage("UX restrictions change listener").that(mCarUxRestrictionsChangeListener)
                .isNotNull();
    }

    private void captureStatsPullAtomCallback(int wantedInvocations) {
        verify(mMockStatsManager, times(wantedInvocations)).setPullAtomCallback(
                eq(CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY), any(StatsManager.PullAtomMetadata.class),
                any(Executor.class), mStatsPullAtomCallbackCaptor.capture());
        verify(mMockStatsManager, times(wantedInvocations)).setPullAtomCallback(
                eq(CAR_WATCHDOG_UID_IO_USAGE_SUMMARY), any(StatsManager.PullAtomMetadata.class),
                any(Executor.class), mStatsPullAtomCallbackCaptor.capture());

        // The same callback is set in the above calls, so fetch the latest captured callback.
        mStatsPullAtomCallback = mStatsPullAtomCallbackCaptor.getValue();
        assertWithMessage("Stats pull atom callback").that(mStatsPullAtomCallback).isNotNull();
    }

    private void verifyDatabaseInit(int wantedInvocations) throws Exception {
        /*
         * Database read is posted on a separate handler thread. Wait until the handler thread has
         * processed the database read request before verifying.
         */
        CarServiceUtils.runEmptyRunnableOnLooperSync(CarWatchdogService.class.getSimpleName());
        verify(mSpiedWatchdogStorage, times(wantedInvocations)).syncUsers(any());
        verify(mSpiedWatchdogStorage, times(wantedInvocations)).getUserPackageSettings();
        verify(mSpiedWatchdogStorage, times(wantedInvocations)).getTodayIoUsageStats();
        verify(mSpiedWatchdogStorage, times(wantedInvocations)).getNotForgivenHistoricalIoOveruses(
                RECURRING_OVERUSE_PERIOD_IN_DAYS);
    }

    private static IResourceOveruseListener createMockResourceOveruseListener() {
        IResourceOveruseListener listener = mock(IResourceOveruseListener.Stub.class);
        when(listener.asBinder()).thenCallRealMethod();
        return listener;
    }

    private SparseArray<PackageIoOveruseStats> injectIoOveruseStatsForPackages(
            SparseArray<String> genericPackageNameByUid, Set<String> killablePackages,
            Set<String> shouldNotifyPackages) throws Exception {
        SparseArray<PackageIoOveruseStats> packageIoOveruseStatsByUid = new SparseArray<>();
        List<PackageIoOveruseStats> packageIoOveruseStats = new ArrayList<>();
        for (int i = 0; i < genericPackageNameByUid.size(); ++i) {
            String name = genericPackageNameByUid.valueAt(i);
            int uid = genericPackageNameByUid.keyAt(i);
            PackageIoOveruseStats stats = constructPackageIoOveruseStats(uid,
                    shouldNotifyPackages.contains(name),
                    constructPerStateBytes(80, 147, 213),
                    constructInternalIoOveruseStats(killablePackages.contains(name),
                            /* remainingWriteBytes= */ constructPerStateBytes(20, 20, 20),
                            /* writtenBytes= */ constructPerStateBytes(100, 200, 300),
                            /* totalOveruses= */ 3));
            packageIoOveruseStatsByUid.put(uid, stats);
            packageIoOveruseStats.add(stats);
        }
        pushLatestIoOveruseStatsAndWait(packageIoOveruseStats);
        return packageIoOveruseStatsByUid;
    }

    private static PackageIoOveruseStats constructPackageIoOveruseStats(int uid,
            boolean shouldNotify, android.automotive.watchdog.PerStateBytes forgivenWriteBytes,
            android.automotive.watchdog.IoOveruseStats ioOveruseStats) {
        PackageIoOveruseStats stats = new PackageIoOveruseStats();
        stats.uid = uid;
        stats.shouldNotify = shouldNotify;
        stats.forgivenWriteBytes = forgivenWriteBytes;
        stats.ioOveruseStats = ioOveruseStats;
        return stats;
    }

    private android.automotive.watchdog.IoOveruseStats constructInternalIoOveruseStats(
            boolean killableOnOveruse,
            android.automotive.watchdog.PerStateBytes remainingWriteBytes,
            android.automotive.watchdog.PerStateBytes writtenBytes, int totalOveruses) {
        return constructInternalIoOveruseStats(killableOnOveruse, STATS_DURATION_SECONDS,
                remainingWriteBytes, writtenBytes, totalOveruses);
    }

    public static android.automotive.watchdog.PerStateBytes constructPerStateBytes(
            long fgBytes, long bgBytes, long gmBytes) {
        android.automotive.watchdog.PerStateBytes perStateBytes =
                new android.automotive.watchdog.PerStateBytes();
        perStateBytes.foregroundBytes = fgBytes;
        perStateBytes.backgroundBytes = bgBytes;
        perStateBytes.garageModeBytes = gmBytes;
        return perStateBytes;
    }

    private android.automotive.watchdog.IoOveruseStats constructInternalIoOveruseStats(
            boolean killableOnOveruse, long durationInSecs,
            android.automotive.watchdog.PerStateBytes remainingWriteBytes,
            android.automotive.watchdog.PerStateBytes writtenBytes, int totalOveruses) {
        android.automotive.watchdog.IoOveruseStats stats =
                new android.automotive.watchdog.IoOveruseStats();
        stats.startTime = mTimeSource.now().getEpochSecond();
        stats.durationInSeconds = durationInSecs;
        stats.killableOnOveruse = killableOnOveruse;
        stats.remainingWriteBytes = remainingWriteBytes;
        stats.writtenBytes = writtenBytes;
        stats.totalOveruses = totalOveruses;
        return stats;
    }

    private void pushLatestIoOveruseStatsAndWait(List<PackageIoOveruseStats> packageIoOveruseStats)
            throws Exception {
        ResourceStats resourceStats = new ResourceStats();
        resourceStats.resourceOveruseStats =
                new android.automotive.watchdog.internal.ResourceOveruseStats();
        resourceStats.resourceOveruseStats.packageIoOveruseStats = packageIoOveruseStats;

        mWatchdogPerfHandler.latestIoOveruseStats(
                resourceStats.resourceOveruseStats.packageIoOveruseStats);

        // Handling latest I/O overuse stats is done on the CarWatchdogService service handler
        // thread. Wait until the below message is processed before returning, so the
        // latestIoOveruseStats call is completed.
        CarServiceUtils.runEmptyRunnableOnLooperSync(CarWatchdogService.class.getSimpleName());

        // Resource overuse handling is done on the main thread by posting a new message with
        // OVERUSE_HANDLING_DELAY_MILLS delay. Wait until the below message is processed before
        // returning, so the resource overuse handling is completed.
        CarServiceUtils.runOnMainSyncDelayed(() -> {
        }, OVERUSE_HANDLING_DELAY_MILLS * 2);

        if (mGenericPackageNameByUid.size() > 0) {
            verify(mSpiedWatchdogStorage, atLeastOnce()).markDirty();
        }
    }
}
