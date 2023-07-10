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
import static android.car.settings.CarSettings.Secure.KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetAllUsers;
import static android.car.watchdog.CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO;
import static android.car.watchdog.CarWatchdogManager.RETURN_CODE_SUCCESS;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import static com.android.car.CarStatsLog.CAR_WATCHDOG_SYSTEM_IO_USAGE_SUMMARY;
import static com.android.car.CarStatsLog.CAR_WATCHDOG_UID_IO_USAGE_SUMMARY;
import static com.android.car.watchdog.CarWatchdogServiceUnitTest.constructUserPackageIoUsageStats;
import static com.android.car.watchdog.WatchdogPerfHandler.MAX_WAIT_TIME_MILLS;
import static com.android.car.watchdog.WatchdogPerfHandler.PACKAGES_DISABLED_ON_RESOURCE_OVERUSE_SEPARATOR;
import static com.android.car.watchdog.WatchdogPerfHandler.USER_PACKAGE_SEPARATOR;
import static com.android.car.watchdog.WatchdogStorage.WatchdogDbHelper.DATABASE_NAME;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityThread;
import android.app.StatsManager;
import android.automotive.watchdog.internal.ApplicationCategoryType;
import android.automotive.watchdog.internal.ComponentType;
import android.automotive.watchdog.internal.PackageIoOveruseStats;
import android.automotive.watchdog.internal.PackageMetadata;
import android.automotive.watchdog.internal.PerStateIoOveruseThreshold;
import android.automotive.watchdog.internal.ResourceSpecificConfiguration;
import android.automotive.watchdog.internal.ResourceStats;
import android.automotive.watchdog.internal.UserPackageIoUsageStats;
import android.car.builtin.content.pm.PackageManagerHelper;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.MockSettings;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.IResourceOveruseListener;
import android.car.watchdog.IoOveruseAlertThreshold;
import android.car.watchdog.IoOveruseConfiguration;
import android.car.watchdog.PerStateBytes;
import android.car.watchdog.ResourceOveruseConfiguration;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.view.Display;

import com.android.car.CarLocalServices;
import com.android.car.CarServiceUtils;
import com.android.car.CarUxRestrictionsManagerService;

import com.google.common.truth.Correspondence;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

public class WatchdogPerfHandlerUnitTest extends AbstractExtendedMockitoTestCase {
    private static final int UID_IO_USAGE_SUMMARY_TOP_COUNT = 3;
    private static final int RECURRING_OVERUSE_TIMES = 2;
    private static final int RECURRING_OVERUSE_PERIOD_IN_DAYS = 2;
    private static final int IO_USAGE_SUMMARY_MIN_SYSTEM_TOTAL_WRITTEN_BYTES = 500 * 1024 * 1024;
    private static final long STATS_DURATION_SECONDS = 3 * 60 * 60;
    private static final int OVERUSE_HANDLING_DELAY_MILLS = 1000;
    private static final String WATCHDOG_DIR_NAME = "watchdog";
    private static final String CANONICAL_NAME =
            WatchdogPerfHandlerUnitTest.class.getCanonicalName();

    @Mock
    private Context mMockContext;
    @Mock
    private Context mMockBuiltinPackageContext;
    @Mock
    private CarWatchdogDaemonHelper mMockCarWatchdogDaemonHelper;
    @Mock
    private PackageInfoHandler mMockedPackageInfoHandler;
    @Mock
    private CarUxRestrictionsManagerService mMockCarUxRestrictionsManagerService;
    @Mock
    private Resources mMockResources;
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
    @Captor
    private ArgumentCaptor<List<
            android.automotive.watchdog.internal.ResourceOveruseConfiguration>>
            mResourceOveruseConfigurationsCaptor;

    private ICarUxRestrictionsChangeListener mCarUxRestrictionsChangeListener;
    private StatsManager.StatsPullAtomCallback mStatsPullAtomCallback;
    private WatchdogStorage mSpiedWatchdogStorage;
    private WatchdogPerfHandler mWatchdogPerfHandler;
    private File mTempSystemCarDir;
    // Not used directly, but sets proper mockStatic() expectations on Settings
    @SuppressWarnings("UnusedVariable")
    private MockSettings mMockSettings;

    private final SparseArray<String> mGenericPackageNameByUid = new SparseArray<>();
    private final CarWatchdogServiceUnitTest.TestTimeSource mTimeSource =
            new CarWatchdogServiceUnitTest.TestTimeSource();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final SparseArray<List<String>> mPackagesBySharedUid = new SparseArray<>();
    private final ArrayMap<String, android.content.pm.PackageInfo> mPmPackageInfoByUserPackage =
            new ArrayMap<>();
    private final ArraySet<String> mDisabledUserPackages = new ArraySet<>();
    private final SparseArray<String> mDisabledPackagesSettingsStringByUserid = new SparseArray<>();
    private final IPackageManager mSpiedPackageManager = spy(ActivityThread.getPackageManager());

    public WatchdogPerfHandlerUnitTest() {
        super(CarWatchdogService.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        mMockSettings = new MockSettings(builder);
        builder.spyStatic(PackageManagerHelper.class)
                .spyStatic(CarServiceUtils.class)
                .spyStatic(ActivityThread.class)
                .spyStatic(CarLocalServices.class);
    }

    @Before
    public void setUp() throws Exception {
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getSystemService(StatsManager.class)).thenReturn(mMockStatsManager);
        when(mMockContext.getPackageName()).thenReturn(CANONICAL_NAME);
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
        doReturn(mSpiedPackageManager).when(() -> ActivityThread.getPackageManager());
        when(mMockCarUxRestrictionsManagerService.getCurrentUxRestrictions())
                .thenReturn(new CarUxRestrictions.Builder(/* reqOpt= */ false,
                        UX_RESTRICTIONS_BASELINE, /* time= */ 0).build());
        when(mMockedPackageInfoHandler.getNamesForUids(any())).thenAnswer(
                args -> mGenericPackageNameByUid);

        mTempSystemCarDir = Files.createTempDirectory("watchdog_test").toFile();
        doReturn(new File(mTempSystemCarDir.getAbsolutePath(), WATCHDOG_DIR_NAME)).when(
                CarWatchdogService::getWatchdogDirFile);

        File tempDbFile = new File(mTempSystemCarDir.getPath(), DATABASE_NAME);
        when(mMockContext.createDeviceProtectedStorageContext()).thenReturn(mMockContext);
        when(mMockContext.getDatabasePath(DATABASE_NAME)).thenReturn(tempDbFile);
        mSpiedWatchdogStorage =
                spy(new WatchdogStorage(mMockContext, /* useDataSystemCarDir= */ false,
                        mTimeSource));
        mWatchdogPerfHandler = new WatchdogPerfHandler(mMockContext,
                mMockBuiltinPackageContext, mMockCarWatchdogDaemonHelper,
                mMockedPackageInfoHandler,
                mSpiedWatchdogStorage, mTimeSource);

        setupUsers();
        mockSettingsStringCalls();
        mockPackageManager();
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
        mWatchdogPerfHandler.onDaemonConnectionChange(/* isConnected= */ true);
        when(mMockCarWatchdogDaemonHelper.getResourceOveruseConfigurations()).thenReturn(
                sampleInternalResourceOveruseConfigurations());
    }

    @Test
    public void testAddResourceOveruseListenerThrowsWithInvalidFlag() throws Exception {
        IResourceOveruseListener mockListener = createMockResourceOveruseListener();

        assertThrows(IllegalArgumentException.class, () -> {
            mWatchdogPerfHandler.addResourceOveruseListener(/* resourceOveruseFlag= */ 0,
                    mockListener);
        });
    }

    @Test
    public void testResourceOveruseListener() throws Exception {
        mGenericPackageNameByUid.put(Binder.getCallingUid(), CANONICAL_NAME);

        IResourceOveruseListener mockListener = createMockResourceOveruseListener();
        IBinder mockBinder = mockListener.asBinder();

        mWatchdogPerfHandler.addResourceOveruseListener(FLAG_RESOURCE_OVERUSE_IO,
                mockListener);

        verify(mockBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        injectIoOveruseStatsForPackages(
                mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                /* shouldNotifyPackages= */ new ArraySet<>(
                        Collections.singleton(CANONICAL_NAME)));

        verify(mockListener).onOveruse(any());

        mWatchdogPerfHandler.removeResourceOveruseListener(mockListener);

        verify(mockListener, atLeastOnce()).asBinder();
        verify(mockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        injectIoOveruseStatsForPackages(
                mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                /* shouldNotifyPackages= */ new ArraySet<>(
                        Collections.singletonList(CANONICAL_NAME)));

        verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void testDuplicateAddResourceOveruseListener() throws Exception {
        mGenericPackageNameByUid.put(Binder.getCallingUid(), CANONICAL_NAME);

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
        mGenericPackageNameByUid.put(Binder.getCallingUid(), CANONICAL_NAME);

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
                        Collections.singleton(CANONICAL_NAME)));

        verify(firstMockListener).onOveruse(any());

        mWatchdogPerfHandler.removeResourceOveruseListener(firstMockListener);

        verify(firstMockListener, atLeastOnce()).asBinder();
        verify(firstMockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        injectIoOveruseStatsForPackages(
                mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                /* shouldNotifyPackages= */ new ArraySet<>(
                        Collections.singletonList(CANONICAL_NAME)));

        verify(secondMockListener, times(2)).onOveruse(any());

        mWatchdogPerfHandler.removeResourceOveruseListener(secondMockListener);

        verify(secondMockListener, atLeastOnce()).asBinder();
        verify(secondMockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        injectIoOveruseStatsForPackages(
                mGenericPackageNameByUid, /* killablePackages= */ new ArraySet<>(),
                /* shouldNotifyPackages= */ new ArraySet<>(
                        Collections.singletonList(CANONICAL_NAME)));

        verifyNoMoreInteractions(firstMockListener);
        verifyNoMoreInteractions(secondMockListener);
    }

    @Test
    public void testAddResourceOveruseListenerForSystemThrowsWithInvalidFlag() throws Exception {
        IResourceOveruseListener mockListener = createMockResourceOveruseListener();

        assertThrows(IllegalArgumentException.class, () -> {
            mWatchdogPerfHandler.addResourceOveruseListenerForSystem(/* resourceOveruseFlag= */ 0,
                    mockListener);
        });
    }

    @Test
    public void testResourceOveruseListenerForSystem() throws Exception {
        int callingUid = Binder.getCallingUid();
        mGenericPackageNameByUid.put(callingUid, "system_package.critical");

        IResourceOveruseListener mockListener = createMockResourceOveruseListener();
        mWatchdogPerfHandler.addResourceOveruseListenerForSystem(FLAG_RESOURCE_OVERUSE_IO,
                mockListener);

        IBinder mockBinder = mockListener.asBinder();
        verify(mockBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        List<PackageIoOveruseStats> packageIoOveruseStats = Collections.singletonList(
                constructPackageIoOveruseStats(callingUid, /* shouldNotify= */ true,
                        /* forgivenWriteBytes= */ constructPerStateBytes(/* fgBytes= */ 80,
                                /* bgBytes= */ 170, /* gmBytes= */ 260),
                        constructInternalIoOveruseStats(/* killableOnOveruse= */ true,
                                /* remainingWriteBytes= */ constructPerStateBytes(/* fgBytes= */ 20,
                                        /* bgBytes= */ 20, /* gmBytes= */ 20),
                                /* writtenBytes= */ constructPerStateBytes(/* fgBytes= */ 100,
                                        /* bgBytes= */ 200, /* gmBytes= */ 300),
                                /* totalOveruses= */ 3)));

        pushLatestIoOveruseStatsAndWait(packageIoOveruseStats);

        verify(mockListener).onOveruse(any());

        mWatchdogPerfHandler.removeResourceOveruseListenerForSystem(mockListener);

        verify(mockListener, atLeastOnce()).asBinder();
        verify(mockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        pushLatestIoOveruseStatsAndWait(packageIoOveruseStats);

        verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void testSetResourceOveruseConfigurations() throws Exception {
        assertThat(mWatchdogPerfHandler.setResourceOveruseConfigurations(
                sampleResourceOveruseConfigurations(), FLAG_RESOURCE_OVERUSE_IO))
                .isEqualTo(RETURN_CODE_SUCCESS);

        InternalResourceOveruseConfigurationSubject
                .assertThat(captureOnSetResourceOveruseConfigurations(/* wantedInvocations= */ 1))
                .containsExactlyElementsIn(sampleInternalResourceOveruseConfigurations());

        // CarService fetches and syncs resource overuse configuration on the main thread by posting
        // a new message. Wait until this completes.
        CarServiceUtils.runOnMainSync(() -> {});

        /* Expect two calls, the first is made at car watchdog service init */
        verify(mMockCarWatchdogDaemonHelper, times(2)).getResourceOveruseConfigurations();
    }

    @Test
    public void testSetResourceOveruseConfigurationsRetriedWithRepeatedRemoteException()
            throws Exception {
        doThrow(RemoteException.class)
                .when(mMockCarWatchdogDaemonHelper).updateResourceOveruseConfigurations(anyList());

        assertThat(mWatchdogPerfHandler.setResourceOveruseConfigurations(
                sampleResourceOveruseConfigurations(), FLAG_RESOURCE_OVERUSE_IO))
                .isEqualTo(CarWatchdogManager.RETURN_CODE_SUCCESS);

        mWatchdogPerfHandler.onDaemonConnectionChange(/* isConnected= */ true);

        doNothing().when(mMockCarWatchdogDaemonHelper).updateResourceOveruseConfigurations(
                anyList());

        /* The below final restart should set the resource overuse configurations successfully. */
        mWatchdogPerfHandler.onDaemonConnectionChange(/* isConnected= */ true);

        InternalResourceOveruseConfigurationSubject
                .assertThat(captureOnSetResourceOveruseConfigurations(/* wantedInvocations= */ 3))
                .containsExactlyElementsIn(sampleInternalResourceOveruseConfigurations());

        verify(mMockCarWatchdogDaemonHelper, times(3)).updateResourceOveruseConfigurations(
                anyList());
    }

    @Test
    public void testSetResourceOveruseConfigurationsRetriedWithDisconnectedDaemon()
            throws Exception {
        mWatchdogPerfHandler.onDaemonConnectionChange(/* isConnected= */ false);

        assertThat(mWatchdogPerfHandler.setResourceOveruseConfigurations(
                sampleResourceOveruseConfigurations(), FLAG_RESOURCE_OVERUSE_IO))
                .isEqualTo(RETURN_CODE_SUCCESS);

        mWatchdogPerfHandler.onDaemonConnectionChange(/* isConnected= */ true);

        InternalResourceOveruseConfigurationSubject
                .assertThat(captureOnSetResourceOveruseConfigurations(/* wantedInvocations= */ 1))
                .containsExactlyElementsIn(sampleInternalResourceOveruseConfigurations());
    }

    @Test
    public void testFailsSetResourceOveruseConfigurationsWithPendingRequest()
            throws Exception {
        mWatchdogPerfHandler.onDaemonConnectionChange(/* isConnected= */ false);

        assertThat(mWatchdogPerfHandler.setResourceOveruseConfigurations(
                sampleResourceOveruseConfigurations(), FLAG_RESOURCE_OVERUSE_IO))
                .isEqualTo(RETURN_CODE_SUCCESS);

        assertThrows(IllegalStateException.class,
                () -> mWatchdogPerfHandler.setResourceOveruseConfigurations(
                        sampleResourceOveruseConfigurations(), FLAG_RESOURCE_OVERUSE_IO));
    }

    @Test
    public void testFailsSetResourceOveruseConfigurationsOnInvalidArgs() throws Exception {
        assertThrows(NullPointerException.class,
                () -> mWatchdogPerfHandler.setResourceOveruseConfigurations(
                        /* configurations= */ null, FLAG_RESOURCE_OVERUSE_IO));

        assertThrows(IllegalArgumentException.class,
                () -> mWatchdogPerfHandler.setResourceOveruseConfigurations(
                        /* configurations= */ new ArrayList<>(), FLAG_RESOURCE_OVERUSE_IO));

        List<ResourceOveruseConfiguration> resourceOveruseConfigs = Collections.singletonList(
                sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                        sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM).build())
                        .build());

        assertThrows(IllegalArgumentException.class,
                () -> mWatchdogPerfHandler.setResourceOveruseConfigurations(resourceOveruseConfigs,
                        /* resourceOveruseFlag= */ 0));
    }

    @Test
    public void testFailsSetResourceOveruseConfigurationsOnDuplicateComponents() throws Exception {
        ResourceOveruseConfiguration config =
                sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                        sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM).build()).build();
        List<ResourceOveruseConfiguration> resourceOveruseConfigs = Arrays.asList(config, config);

        assertThrows(IllegalArgumentException.class,
                () -> mWatchdogPerfHandler.setResourceOveruseConfigurations(resourceOveruseConfigs,
                        FLAG_RESOURCE_OVERUSE_IO));
    }

    @Test
    public void testFailsSetResourceOveruseConfigurationsOnZeroComponentLevelIoOveruseThresholds()
            throws Exception {
        List<ResourceOveruseConfiguration> resourceOveruseConfigs =
                Collections.singletonList(
                        sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                                sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM)
                                        .setComponentLevelThresholds(
                                                new PerStateBytes(
                                                        /* foregroundModeBytes= */ 200,
                                                        /* backgroundModeBytes= */ 0,
                                                        /* garageModeBytes= */ 200))
                                        .build())
                                .build());

        assertThrows(IllegalArgumentException.class,
                () -> mWatchdogPerfHandler.setResourceOveruseConfigurations(resourceOveruseConfigs,
                        FLAG_RESOURCE_OVERUSE_IO));
    }

    @Test
    public void testFailsSetResourceOveruseConfigurationsOnNullIoOveruseConfiguration()
            throws Exception {
        List<ResourceOveruseConfiguration> resourceOveruseConfigs = Collections.singletonList(
                sampleResourceOveruseConfigurationBuilder(
                        ComponentType.SYSTEM, /* ioOveruseConfig= */ null).build());

        assertThrows(IllegalArgumentException.class,
                () -> mWatchdogPerfHandler.setResourceOveruseConfigurations(resourceOveruseConfigs,
                        FLAG_RESOURCE_OVERUSE_IO));
    }

    @Test
    public void testFailsSetResourceOveruseConfigurationsOnEmptyIoOveruseSystemWideThresholds()
            throws Exception {
        List<ResourceOveruseConfiguration> resourceOveruseConfigs =
                Collections.singletonList(
                        sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                                sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM)
                                        .setSystemWideThresholds(new ArrayList<>())
                                        .build())
                                .build());

        assertThrows(IllegalArgumentException.class,
                () -> mWatchdogPerfHandler.setResourceOveruseConfigurations(resourceOveruseConfigs,
                        FLAG_RESOURCE_OVERUSE_IO));
    }

    @Test
    public void testFailsSetResourceOveruseConfigurationsOnIoOveruseInvalidSystemWideThreshold()
            throws Exception {
        List<ResourceOveruseConfiguration> resourceOveruseConfigs = new ArrayList<>();
        resourceOveruseConfigs.add(sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM)
                        .setSystemWideThresholds(Collections.singletonList(
                                new IoOveruseAlertThreshold(
                                        /* durationInSeconds= */ 30,
                                        /* writtenBytesPerSecond= */ 0)))
                        .build())
                .build());

        assertThrows(IllegalArgumentException.class,
                () -> mWatchdogPerfHandler.setResourceOveruseConfigurations(
                        resourceOveruseConfigs, FLAG_RESOURCE_OVERUSE_IO));

        resourceOveruseConfigs.set(0,
                sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                        sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM)
                                .setSystemWideThresholds(Collections.singletonList(
                                        new IoOveruseAlertThreshold(
                                                /* durationInSeconds= */ 0,
                                                /* writtenBytesPerSecond= */ 300)))
                                .build())
                        .build());

        assertThrows(IllegalArgumentException.class,
                () -> mWatchdogPerfHandler.setResourceOveruseConfigurations(
                        resourceOveruseConfigs, FLAG_RESOURCE_OVERUSE_IO));
    }

    @Test
    public void testGetResourceOveruseConfigurations() throws Exception {
        List<ResourceOveruseConfiguration> actualConfigs =
                mWatchdogPerfHandler.getResourceOveruseConfigurations(FLAG_RESOURCE_OVERUSE_IO);

        ResourceOveruseConfigurationSubject.assertThat(actualConfigs)
                .containsExactlyElementsIn(sampleResourceOveruseConfigurations());
    }

    @Test
    public void testGetResourceOveruseConfigurationsWithDisconnectedDaemon() throws Exception {
        mWatchdogPerfHandler.onDaemonConnectionChange(/* isConnected= */ false);

        assertThrows(IllegalStateException.class,
                () -> mWatchdogPerfHandler.getResourceOveruseConfigurations(
                        FLAG_RESOURCE_OVERUSE_IO));

        /* Method initially called in CarWatchdogService init */
        verify(mMockCarWatchdogDaemonHelper).getResourceOveruseConfigurations();
    }

    @Test
    public void testGetResourceOveruseConfigurationsWithReconnectedDaemon() throws Exception {
        crashAndDelayReconnectDaemon();

        List<ResourceOveruseConfiguration> actualConfigs =
                mWatchdogPerfHandler.getResourceOveruseConfigurations(FLAG_RESOURCE_OVERUSE_IO);

        ResourceOveruseConfigurationSubject.assertThat(actualConfigs)
                .containsExactlyElementsIn(sampleResourceOveruseConfigurations());
    }

    @Test
    public void testConcurrentSetGetResourceOveruseConfigurationsWithReconnectedDaemon()
            throws Exception {
        crashAndDelayReconnectDaemon();

        /* Capture and respond with the configuration received in the set request. */
        List<android.automotive.watchdog.internal.ResourceOveruseConfiguration> internalConfigs =
                new ArrayList<>();
        doAnswer(args -> {
            List<android.automotive.watchdog.internal.ResourceOveruseConfiguration> configs =
                    args.getArgument(0);
            internalConfigs.addAll(configs);
            return null;
        }).when(mMockCarWatchdogDaemonHelper).updateResourceOveruseConfigurations(anyList());
        when(mMockCarWatchdogDaemonHelper.getResourceOveruseConfigurations()).thenReturn(
                internalConfigs);

        /* Start a set request that will become pending and a blocking get request. */
        List<ResourceOveruseConfiguration> setConfigs = sampleResourceOveruseConfigurations();
        assertThat(mWatchdogPerfHandler.setResourceOveruseConfigurations(
                setConfigs, FLAG_RESOURCE_OVERUSE_IO))
                .isEqualTo(CarWatchdogManager.RETURN_CODE_SUCCESS);

        List<ResourceOveruseConfiguration> getConfigs =
                mWatchdogPerfHandler.getResourceOveruseConfigurations(FLAG_RESOURCE_OVERUSE_IO);

        ResourceOveruseConfigurationSubject.assertThat(getConfigs)
                .containsExactlyElementsIn(setConfigs);
    }

    @Test
    public void testFailsGetResourceOveruseConfigurationsOnInvalidArgs() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mWatchdogPerfHandler.getResourceOveruseConfigurations(0));
    }

    private void crashAndDelayReconnectDaemon() {
        mWatchdogPerfHandler.onDaemonConnectionChange(/* isConnected= */ false);

        mMainHandler.postAtTime(
                () -> mWatchdogPerfHandler.onDaemonConnectionChange(/* isConnected= */ true),
                MAX_WAIT_TIME_MILLS - 1000);
    }

    @Test
    public void testAsyncFetchTodayIoUsageStats() throws Exception {
        List<WatchdogStorage.IoUsageStatsEntry> ioUsageStatsEntries = Arrays.asList(
                WatchdogStorageUnitTest.constructIoUsageStatsEntry(
                        /* userId= */ 100, "system_package", /* startTime */ 0,
                        /* duration= */1234,
                        /* remainingWriteBytes= */ constructPerStateBytes(200, 300, 400),
                        /* writtenBytes= */ constructPerStateBytes(1000, 2000, 3000),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 2, /* forgivenOveruses= */ 0,
                        /* totalTimesKilled= */ 1),
                WatchdogStorageUnitTest.constructIoUsageStatsEntry(
                        /* userId= */ 101, "vendor_package", /* startTime */ 0,
                        /* duration= */ 1234,
                        /* remainingWriteBytes= */ constructPerStateBytes(500, 600, 700),
                        /* writtenBytes= */ constructPerStateBytes(1100, 2300, 4300),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 4, /* forgivenOveruses= */ 1,
                        /* totalTimesKilled= */ 10));
        when(mSpiedWatchdogStorage.getTodayIoUsageStats()).thenReturn(ioUsageStatsEntries);

        mWatchdogPerfHandler.asyncFetchTodayIoUsageStats();

        ArgumentCaptor<List<UserPackageIoUsageStats>> userPackageIoUsageStatsCaptor =
                ArgumentCaptor.forClass(List.class);

        verify(mMockCarWatchdogDaemonHelper, timeout(MAX_WAIT_TIME_MILLS))
                .onTodayIoUsageStatsFetched(userPackageIoUsageStatsCaptor.capture());

        List<UserPackageIoUsageStats> expectedStats = Arrays.asList(
                constructUserPackageIoUsageStats(/* userId= */ 100, "system_package",
                        /* writtenBytes= */ constructPerStateBytes(1000, 2000, 3000),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 2),
                constructUserPackageIoUsageStats(/* userId= */ 101, "vendor_package",
                        /* writtenBytes= */ constructPerStateBytes(1100, 2300, 4300),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 4));

        assertThat(userPackageIoUsageStatsCaptor.getValue())
                .comparingElementsUsing(Correspondence.from(
                        CarWatchdogServiceUnitTest::isUserPackageIoUsageStatsEquals,
                        "is user package I/O usage stats equal to"))
                .containsExactlyElementsIn(expectedStats);
    }

    @Test
    public void testAsyncFetchTodayIoUsageStatsWithDaemonRemoteException() throws Exception {
        List<WatchdogStorage.IoUsageStatsEntry> ioUsageStatsEntries = Arrays.asList(
                WatchdogStorageUnitTest.constructIoUsageStatsEntry(
                        /* userId= */ 100, "system_package", /* startTime */ 0,
                        /* duration= */1234,
                        /* remainingWriteBytes= */ constructPerStateBytes(200, 300, 400),
                        /* writtenBytes= */ constructPerStateBytes(1000, 2000, 3000),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 2, /* forgivenOveruses= */ 0,
                        /* totalTimesKilled= */ 1),
                WatchdogStorageUnitTest.constructIoUsageStatsEntry(
                        /* userId= */ 101, "vendor_package", /* startTime */ 0,
                        /* duration= */ 1234,
                        /* remainingWriteBytes= */ constructPerStateBytes(500, 600, 700),
                        /* writtenBytes= */ constructPerStateBytes(1100, 2300, 4300),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 4, /* forgivenOveruses= */ 1,
                        /* totalTimesKilled= */ 10));
        when(mSpiedWatchdogStorage.getTodayIoUsageStats()).thenReturn(ioUsageStatsEntries);
        doThrow(RemoteException.class)
                .when(mMockCarWatchdogDaemonHelper).onTodayIoUsageStatsFetched(any());

        mWatchdogPerfHandler.asyncFetchTodayIoUsageStats();

        verify(mMockCarWatchdogDaemonHelper,
                timeout(MAX_WAIT_TIME_MILLS)).onTodayIoUsageStatsFetched(any());
    }

    @Test
    public void testGetTodayIoUsageStats() throws Exception {
        List<WatchdogStorage.IoUsageStatsEntry> ioUsageStatsEntries = Arrays.asList(
                WatchdogStorageUnitTest.constructIoUsageStatsEntry(
                        /* userId= */ 100, "system_package", /* startTime */ 0,
                        /* duration= */1234,
                        /* remainingWriteBytes= */ constructPerStateBytes(200, 300, 400),
                        /* writtenBytes= */ constructPerStateBytes(1000, 2000, 3000),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 2, /* forgivenOveruses= */ 0,
                        /* totalTimesKilled= */ 1),
                WatchdogStorageUnitTest.constructIoUsageStatsEntry(
                        /* userId= */ 101, "vendor_package", /* startTime */ 0,
                        /* duration= */ 1234,
                        /* remainingWriteBytes= */ constructPerStateBytes(500, 600, 700),
                        /* writtenBytes= */ constructPerStateBytes(1100, 2300, 4300),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 4, /* forgivenOveruses= */ 1,
                        /* totalTimesKilled= */ 10));
        when(mSpiedWatchdogStorage.getTodayIoUsageStats()).thenReturn(ioUsageStatsEntries);

        List<UserPackageIoUsageStats> actualStats =
                mWatchdogPerfHandler.getTodayIoUsageStats();

        List<UserPackageIoUsageStats> expectedStats = Arrays.asList(
                constructUserPackageIoUsageStats(/* userId= */ 100, "system_package",
                        /* writtenBytes= */ constructPerStateBytes(1000, 2000, 3000),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 2),
                constructUserPackageIoUsageStats(/* userId= */ 101, "vendor_package",
                        /* writtenBytes= */ constructPerStateBytes(1100, 2300, 4300),
                        /* forgivenWriteBytes= */ constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 4));

        assertThat(actualStats).comparingElementsUsing(Correspondence.from(
                        CarWatchdogServiceUnitTest::isUserPackageIoUsageStatsEquals,
                        "is user package I/O usage stats equal to"))
                .containsExactlyElementsIn(expectedStats);
    }

    @Test
    public void testDisablePackageForUser() throws Exception {
        assertWithMessage("Performed resource overuse kill")
                .that(mWatchdogPerfHandler.disablePackageForUser("third_party_package",
                        /* userId= */ 100)).isTrue();

        verifyDisabledPackages(/* userPackagesCsv= */ "100:third_party_package");
    }

    @Test
    public void testDisablePackageForUserWithDisabledPackage() throws Exception {
        doReturn(COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED).when(() -> PackageManagerHelper
                .getApplicationEnabledSettingForUser(anyString(), anyInt()));

        assertWithMessage("Performed resource overuse kill")
                .that(mWatchdogPerfHandler.disablePackageForUser("third_party_package",
                        /* userId= */ 100)).isFalse();

        verifyNoDisabledPackages();
    }

    @Test
    public void testDisablePackageForUserWithNonexistentPackage() throws Exception {
        doThrow(IllegalArgumentException.class).when(mSpiedPackageManager)
                .getApplicationEnabledSetting(anyString(), anyInt());

        assertWithMessage("Performed resource overuse kill")
                .that(mWatchdogPerfHandler.disablePackageForUser("fake_package",
                        /* userId= */ 100)).isFalse();

        verifyNoDisabledPackages();
    }

    private void verifyDisabledPackages(String userPackagesCsv) {
        verifyDisabledPackages(/* message= */ "", userPackagesCsv);
    }

    private void verifyDisabledPackages(String message, String userPackagesCsv) {
        assertWithMessage("Disabled user packages %s", message).that(mDisabledUserPackages)
                .containsExactlyElementsIn(userPackagesCsv.split(","));

        verifyDisabledPackagesSettingsKey(message, userPackagesCsv);
    }

    private void verifyDisabledPackagesSettingsKey(String message, String userPackagesCsv) {
        List<String> userPackagesFromSettingsString = new ArrayList<>();
        for (int i = 0; i < mDisabledPackagesSettingsStringByUserid.size(); ++i) {
            int userId = mDisabledPackagesSettingsStringByUserid.keyAt(i);
            String value = mDisabledPackagesSettingsStringByUserid.valueAt(i);
            List<String> packages = TextUtils.isEmpty(value) ? new ArrayList<>()
                    : new ArrayList<>(Arrays.asList(value.split(
                            PACKAGES_DISABLED_ON_RESOURCE_OVERUSE_SEPARATOR)));
            packages.forEach(element ->
                    userPackagesFromSettingsString.add(userId + USER_PACKAGE_SEPARATOR + element));
        }

        assertWithMessage(
                "KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE settings string user packages %s",
                message).that(userPackagesFromSettingsString)
                .containsExactlyElementsIn(userPackagesCsv.split(","));
    }

    private void verifyNoDisabledPackages() {
        verifyNoDisabledPackages(/* message= */ "");
    }

    private void verifyNoDisabledPackages(String message) {
        assertWithMessage("Disabled user packages %s", message).that(mDisabledUserPackages)
                .isEmpty();
        assertWithMessage(
                "KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE settings string user packages %s",
                message).that(mDisabledPackagesSettingsStringByUserid.size()).isEqualTo(0);
    }

    private void mockSettingsStringCalls() {
        doAnswer(args -> {
            ContentResolver contentResolver = mock(ContentResolver.class);
            when(contentResolver.getUserId()).thenReturn(args.getArgument(1));
            return contentResolver;
        }).when(() -> CarServiceUtils.getContentResolverForUser(any(), anyInt()));

        when(Settings.Secure.getString(any(ContentResolver.class),
                eq(KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE))).thenAnswer(
                    args -> {
                        ContentResolver contentResolver = args.getArgument(0);
                        int userId = contentResolver.getUserId();
                        return mDisabledPackagesSettingsStringByUserid.get(userId);
                    });

        // Use any() instead of anyString() to consider when string arg is null.
        when(Settings.Secure.putString(any(ContentResolver.class),
                eq(KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE), any())).thenAnswer(args -> {
                    ContentResolver contentResolver = args.getArgument(0);
                    int userId = contentResolver.getUserId();
                    String packageSettings = args.getArgument(2);
                    if (packageSettings == null) {
                        mDisabledPackagesSettingsStringByUserid.remove(userId);
                    } else {
                        mDisabledPackagesSettingsStringByUserid.put(userId, args.getArgument(2));
                    }
                    return null;
                });
    }

    // TODO(b/262301082): Move to PackageInfoHandlerUnitTest.
    private void mockPackageManager() throws Exception {
        when(mMockPackageManager.getNamesForUids(any())).thenAnswer(args -> {
            int[] uids = args.getArgument(0);
            String[] names = new String[uids.length];
            for (int i = 0; i < uids.length; ++i) {
                names[i] = mGenericPackageNameByUid.get(uids[i], null);
            }
            return names;
        });
        when(mMockPackageManager.getPackagesForUid(anyInt())).thenAnswer(args -> {
            int uid = args.getArgument(0);
            List<String> packages = mPackagesBySharedUid.get(uid);
            return packages.toArray(new String[0]);
        });
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), any()))
                .thenAnswer(args -> {
                    int userId = ((UserHandle) args.getArgument(2)).getIdentifier();
                    String userPackageId = userId + USER_PACKAGE_SEPARATOR + args.getArgument(0);
                    android.content.pm.PackageInfo packageInfo =
                            mPmPackageInfoByUserPackage.get(userPackageId);
                    if (packageInfo == null) {
                        throw new PackageManager.NameNotFoundException(
                                "User package id '" + userPackageId + "' not found");
                    }
                    return packageInfo.applicationInfo;
                });
        when(mMockPackageManager.getPackageInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenAnswer(args -> {
                    String userPackageId = args.getArgument(2) + USER_PACKAGE_SEPARATOR
                            + args.getArgument(0);
                    android.content.pm.PackageInfo packageInfo =
                            mPmPackageInfoByUserPackage.get(userPackageId);
                    if (packageInfo == null) {
                        throw new PackageManager.NameNotFoundException(
                                "User package id '" + userPackageId + "' not found");
                    }
                    return packageInfo;
                });
        when(mMockPackageManager.getInstalledPackagesAsUser(anyInt(), anyInt()))
                .thenAnswer(args -> {
                    int userId = args.getArgument(1);
                    List<android.content.pm.PackageInfo> packageInfos = new ArrayList<>();
                    for (android.content.pm.PackageInfo packageInfo :
                            mPmPackageInfoByUserPackage.values()) {
                        if (UserHandle.getUserId(packageInfo.applicationInfo.uid) == userId) {
                            packageInfos.add(packageInfo);
                        }
                    }
                    return packageInfos;
                });
        when(mMockPackageManager.getPackageUidAsUser(anyString(), anyInt()))
                .thenAnswer(args -> {
                    String userPackageId = args.getArgument(1) + USER_PACKAGE_SEPARATOR
                            + args.getArgument(0);
                    android.content.pm.PackageInfo packageInfo =
                            mPmPackageInfoByUserPackage.get(userPackageId);
                    if (packageInfo == null) {
                        throw new PackageManager.NameNotFoundException(
                                "User package id '" + userPackageId + "' not found");
                    }
                    return packageInfo.applicationInfo.uid;
                });
        doAnswer((args) -> {
            String value = args.getArgument(3) + USER_PACKAGE_SEPARATOR
                    + args.getArgument(0);
            mDisabledUserPackages.add(value);
            return null;
        }).when(mSpiedPackageManager).setApplicationEnabledSetting(
                anyString(), eq(COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED), anyInt(),
                anyInt(), anyString());
        doAnswer((args) -> {
            String value = args.getArgument(3) + USER_PACKAGE_SEPARATOR
                    + args.getArgument(0);
            mDisabledUserPackages.remove(value);
            return null;
        }).when(mSpiedPackageManager).setApplicationEnabledSetting(
                anyString(), eq(COMPONENT_ENABLED_STATE_ENABLED), anyInt(),
                anyInt(), anyString());
        doReturn(COMPONENT_ENABLED_STATE_ENABLED).when(mSpiedPackageManager)
                .getApplicationEnabledSetting(anyString(), anyInt());
    }

    private static List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>
            sampleInternalResourceOveruseConfigurations() {
        return Arrays.asList(
                sampleInternalResourceOveruseConfiguration(ComponentType.SYSTEM,
                        sampleInternalIoOveruseConfiguration(ComponentType.SYSTEM)),
                sampleInternalResourceOveruseConfiguration(ComponentType.VENDOR,
                        sampleInternalIoOveruseConfiguration(ComponentType.VENDOR)),
                sampleInternalResourceOveruseConfiguration(ComponentType.THIRD_PARTY,
                        sampleInternalIoOveruseConfiguration(ComponentType.THIRD_PARTY)));
    }

    private static android.automotive.watchdog.internal.ResourceOveruseConfiguration
            sampleInternalResourceOveruseConfiguration(@ComponentType int componentType,
            android.automotive.watchdog.internal.IoOveruseConfiguration ioOveruseConfig) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType).toLowerCase();
        android.automotive.watchdog.internal.ResourceOveruseConfiguration config =
                new android.automotive.watchdog.internal.ResourceOveruseConfiguration();
        config.componentType = componentType;
        config.safeToKillPackages = Arrays.asList(prefix + "_package.non_critical.A",
                prefix + "_pkg.non_critical.B",
                "shared:" + prefix + "_shared_package.non_critical.B",
                "some_pkg_as_" + prefix + "_pkg");
        config.vendorPackagePrefixes = Arrays.asList(
                prefix + "_package", "some_pkg_as_" + prefix + "_pkg");
        config.packageMetadata = Arrays.asList(
                constructPackageMetadata("system_package.MEDIA", ApplicationCategoryType.MEDIA),
                constructPackageMetadata("system_package.A", ApplicationCategoryType.MAPS),
                constructPackageMetadata("vendor_package.MEDIA", ApplicationCategoryType.MEDIA),
                constructPackageMetadata("vendor_package.A", ApplicationCategoryType.MAPS),
                constructPackageMetadata("third_party_package.MAPS", ApplicationCategoryType.MAPS));

        ResourceSpecificConfiguration resourceSpecificConfig = new ResourceSpecificConfiguration();
        resourceSpecificConfig.setIoOveruseConfiguration(ioOveruseConfig);
        config.resourceSpecificConfigurations = Collections.singletonList(resourceSpecificConfig);

        return config;
    }

    private static PackageMetadata constructPackageMetadata(
            String packageName, @ApplicationCategoryType int appCategoryType) {
        PackageMetadata metadata = new PackageMetadata();
        metadata.packageName = packageName;
        metadata.appCategoryType = appCategoryType;
        return metadata;
    }

    private static android.automotive.watchdog.internal.IoOveruseConfiguration
            sampleInternalIoOveruseConfiguration(@ComponentType int componentType) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType).toLowerCase();
        android.automotive.watchdog.internal.IoOveruseConfiguration config =
                new android.automotive.watchdog.internal.IoOveruseConfiguration();
        config.componentLevelThresholds = constructPerStateIoOveruseThreshold(
                WatchdogPerfHandler.toComponentTypeStr(componentType),
                /* fgBytes= */ componentType * 10L, /* bgBytes= */ componentType *  20L,
                /*gmBytes= */ componentType * 30L);
        config.packageSpecificThresholds = Collections.singletonList(
                constructPerStateIoOveruseThreshold(prefix + "_package.A",
                        /* fgBytes= */ componentType * 40L, /* bgBytes= */ componentType * 50L,
                        /* gmBytes= */ componentType * 60L));
        config.categorySpecificThresholds = Arrays.asList(
                constructPerStateIoOveruseThreshold(
                        WatchdogPerfHandler.INTERNAL_APPLICATION_CATEGORY_TYPE_MEDIA,
                        /* fgBytes= */ componentType * 100L, /* bgBytes= */ componentType * 200L,
                        /* gmBytes= */ componentType * 300L),
                constructPerStateIoOveruseThreshold(
                        WatchdogPerfHandler.INTERNAL_APPLICATION_CATEGORY_TYPE_MAPS,
                        /* fgBytes= */ componentType * 1100L, /* bgBytes= */ componentType * 2200L,
                        /* gmBytes= */ componentType * 3300L));
        config.systemWideThresholds = Collections.singletonList(
                constructInternalIoOveruseAlertThreshold(
                        /* duration= */ componentType * 10L, /* writeBPS= */ componentType * 200L));
        return config;
    }

    private static android.automotive.watchdog.internal.IoOveruseAlertThreshold
            constructInternalIoOveruseAlertThreshold(long duration, long writeBPS) {
        android.automotive.watchdog.internal.IoOveruseAlertThreshold threshold =
                new android.automotive.watchdog.internal.IoOveruseAlertThreshold();
        threshold.durationInSeconds = duration;
        threshold.writtenBytesPerSecond = writeBPS;
        return threshold;
    }

    private static PerStateIoOveruseThreshold constructPerStateIoOveruseThreshold(String name,
            long fgBytes, long bgBytes, long gmBytes) {
        PerStateIoOveruseThreshold threshold = new PerStateIoOveruseThreshold();
        threshold.name = name;
        threshold.perStateWriteBytes = new android.automotive.watchdog.PerStateBytes();
        threshold.perStateWriteBytes.foregroundBytes = fgBytes;
        threshold.perStateWriteBytes.backgroundBytes = bgBytes;
        threshold.perStateWriteBytes.garageModeBytes = gmBytes;
        return threshold;
    }

    private List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>
            captureOnSetResourceOveruseConfigurations(int wantedInvocations) throws Exception {
        verify(mMockCarWatchdogDaemonHelper, times(wantedInvocations))
                .updateResourceOveruseConfigurations(
                        mResourceOveruseConfigurationsCaptor.capture());
        return mResourceOveruseConfigurationsCaptor.getValue();
    }

    private static List<ResourceOveruseConfiguration> sampleResourceOveruseConfigurations() {
        return Arrays.asList(
                sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                        sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM).build()).build(),
                sampleResourceOveruseConfigurationBuilder(ComponentType.VENDOR,
                        sampleIoOveruseConfigurationBuilder(ComponentType.VENDOR).build()).build(),
                sampleResourceOveruseConfigurationBuilder(ComponentType.THIRD_PARTY,
                        sampleIoOveruseConfigurationBuilder(ComponentType.THIRD_PARTY).build())
                        .build());
    }

    private static IoOveruseConfiguration.Builder sampleIoOveruseConfigurationBuilder(
            @ComponentType int componentType) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType).toLowerCase();
        PerStateBytes componentLevelThresholds = new PerStateBytes(
                /* foregroundModeBytes= */ componentType * 10L,
                /* backgroundModeBytes= */ componentType * 20L,
                /* garageModeBytes= */ componentType * 30L);
        Map<String, PerStateBytes> packageSpecificThresholds = new ArrayMap<>();
        packageSpecificThresholds.put(prefix + "_package.A", new PerStateBytes(
                /* foregroundModeBytes= */ componentType * 40L,
                /* backgroundModeBytes= */ componentType * 50L,
                /* garageModeBytes= */ componentType * 60L));

        Map<String, PerStateBytes> appCategorySpecificThresholds = new ArrayMap<>();
        appCategorySpecificThresholds.put(
                ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MEDIA,
                new PerStateBytes(/* foregroundModeBytes= */ componentType * 100L,
                        /* backgroundModeBytes= */ componentType * 200L,
                        /* garageModeBytes= */ componentType * 300L));
        appCategorySpecificThresholds.put(
                ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MAPS,
                new PerStateBytes(/* foregroundModeBytes= */ componentType * 1100L,
                        /* backgroundModeBytes= */ componentType * 2200L,
                        /* garageModeBytes= */ componentType * 3300L));

        List<IoOveruseAlertThreshold> systemWideThresholds = Collections.singletonList(
                new IoOveruseAlertThreshold(/* durationInSeconds= */ componentType * 10L,
                        /* writtenBytesPerSecond= */ componentType * 200L));

        return new IoOveruseConfiguration.Builder(componentLevelThresholds,
                packageSpecificThresholds, appCategorySpecificThresholds, systemWideThresholds);
    }

    private static ResourceOveruseConfiguration.Builder sampleResourceOveruseConfigurationBuilder(
            @ComponentType int componentType, IoOveruseConfiguration ioOveruseConfig) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType).toLowerCase();
        List<String> safeToKill = Arrays.asList(prefix + "_package.non_critical.A",
                prefix + "_pkg.non_critical.B",
                "shared:" + prefix + "_shared_package.non_critical.B",
                "some_pkg_as_" + prefix + "_pkg");
        List<String> vendorPrefixes = Arrays.asList(
                prefix + "_package", "some_pkg_as_" + prefix + "_pkg");
        Map<String, String> pkgToAppCategory = new ArrayMap<>();
        pkgToAppCategory.put("system_package.MEDIA", "android.car.watchdog.app.category.MEDIA");
        pkgToAppCategory.put("system_package.A", "android.car.watchdog.app.category.MAPS");
        pkgToAppCategory.put("vendor_package.MEDIA", "android.car.watchdog.app.category.MEDIA");
        pkgToAppCategory.put("vendor_package.A", "android.car.watchdog.app.category.MAPS");
        pkgToAppCategory.put("third_party_package.MAPS", "android.car.watchdog.app.category.MAPS");
        ResourceOveruseConfiguration.Builder configBuilder =
                new ResourceOveruseConfiguration.Builder(componentType, safeToKill,
                        vendorPrefixes, pkgToAppCategory);
        configBuilder.setIoOveruseConfiguration(ioOveruseConfig);
        return configBuilder;
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
                            /* remainingWriteBytes= */
                            constructPerStateBytes(/* fgBytes= */ 20, /* bgBytes= */
                                    20, /* gmBytes= */ 20),
                            /* writtenBytes= */
                            constructPerStateBytes(/* fgBytes= */ 100, /* bgBytes= */
                                    200, /* gmBytes= */ 300),
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
