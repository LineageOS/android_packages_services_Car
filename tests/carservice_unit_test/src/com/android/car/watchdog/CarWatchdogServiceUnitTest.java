/*
 * Copyright (C) 2020 The Android Open Source Project
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
import static android.automotive.watchdog.internal.ResourceOveruseActionType.NOT_KILLED;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetAliveUsers;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetAllUsers;
import static android.car.watchdog.CarWatchdogManager.TIMEOUT_CRITICAL;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityThread;
import android.automotive.watchdog.ResourceType;
import android.automotive.watchdog.internal.ApplicationCategoryType;
import android.automotive.watchdog.internal.ComponentType;
import android.automotive.watchdog.internal.ICarWatchdog;
import android.automotive.watchdog.internal.ICarWatchdogServiceForSystem;
import android.automotive.watchdog.internal.PackageIdentifier;
import android.automotive.watchdog.internal.PackageInfo;
import android.automotive.watchdog.internal.PackageIoOveruseStats;
import android.automotive.watchdog.internal.PackageMetadata;
import android.automotive.watchdog.internal.PackageResourceOveruseAction;
import android.automotive.watchdog.internal.PerStateIoOveruseThreshold;
import android.automotive.watchdog.internal.ResourceSpecificConfiguration;
import android.automotive.watchdog.internal.UidType;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.ICarWatchdogServiceCallback;
import android.car.watchdog.IResourceOveruseListener;
import android.car.watchdog.IoOveruseAlertThreshold;
import android.car.watchdog.IoOveruseConfiguration;
import android.car.watchdog.IoOveruseStats;
import android.car.watchdog.PackageKillableState;
import android.car.watchdog.PerStateBytes;
import android.car.watchdog.ResourceOveruseConfiguration;
import android.car.watchdog.ResourceOveruseStats;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.util.function.TriConsumer;

import com.google.common.truth.Correspondence;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>This class contains unit tests for the {@link CarWatchdogService}.
 */
@RunWith(MockitoJUnitRunner.class)
public class CarWatchdogServiceUnitTest extends AbstractExtendedMockitoTestCase {

    private static final String CAR_WATCHDOG_DAEMON_INTERFACE = "carwatchdogd_system";
    private static final int MAX_WAIT_TIME_MS = 3000;
    private static final int INVALID_SESSION_ID = -1;

    @Mock private Context mMockContext;
    @Mock private PackageManager mMockPackageManager;
    @Mock private UserManager mMockUserManager;
    @Mock private IBinder mMockBinder;
    @Mock private ICarWatchdog mMockCarWatchdogDaemon;

    private CarWatchdogService mCarWatchdogService;
    private ICarWatchdogServiceForSystem mWatchdogServiceForSystemImpl;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder
            .spyStatic(ServiceManager.class)
            .spyStatic(Binder.class)
            .spyStatic(ActivityThread.class);
    }

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUpMocks() throws Exception {
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockContext.getPackageName()).thenReturn(
                CarWatchdogServiceUnitTest.class.getCanonicalName());
        mCarWatchdogService = new CarWatchdogService(mMockContext);
        mockWatchdogDaemon();
        setupUsers();
        mCarWatchdogService.init();
        mWatchdogServiceForSystemImpl = registerCarWatchdogService();
    }

    @Test
    public void testCarWatchdogServiceHealthCheck() throws Exception {
        mWatchdogServiceForSystemImpl.checkIfAlive(123456, TIMEOUT_CRITICAL);
        verify(mMockCarWatchdogDaemon,
                timeout(MAX_WAIT_TIME_MS)).tellCarWatchdogServiceAlive(
                eq(mWatchdogServiceForSystemImpl), any(int[].class), eq(123456));
    }

    @Test
    public void testRegisterClient() throws Exception {
        TestClient client = new TestClient();
        mCarWatchdogService.registerClient(client, TIMEOUT_CRITICAL);
        mWatchdogServiceForSystemImpl.checkIfAlive(123456, TIMEOUT_CRITICAL);
        // Checking client health is asynchronous, so wait at most 1 second.
        int repeat = 10;
        while (repeat > 0) {
            int sessionId = client.getLastSessionId();
            if (sessionId != INVALID_SESSION_ID) {
                return;
            }
            SystemClock.sleep(100L);
            repeat--;
        }
        assertThat(client.getLastSessionId()).isNotEqualTo(INVALID_SESSION_ID);
    }

    @Test
    public void testUnregisterUnregisteredClient() throws Exception {
        TestClient client = new TestClient();
        mCarWatchdogService.registerClient(client, TIMEOUT_CRITICAL);
        mCarWatchdogService.unregisterClient(client);
        mWatchdogServiceForSystemImpl.checkIfAlive(123456, TIMEOUT_CRITICAL);
        assertThat(client.getLastSessionId()).isEqualTo(INVALID_SESSION_ID);
    }

    @Test
    public void testGoodClientHealthCheck() throws Exception {
        testClientHealthCheck(new TestClient(), 0);
    }

    @Test
    public void testBadClientHealthCheck() throws Exception {
        testClientHealthCheck(new BadTestClient(), 1);
    }

    @Test
    public void testGetResourceOveruseStats() throws Exception {
        SparseArray<String> packageNamesByUid = new SparseArray<>();
        packageNamesByUid.put(Binder.getCallingUid(), mMockContext.getPackageName());
        injectUidToPackageNameMapping(packageNamesByUid);

        List<PackageIoOveruseStats> packageIoOveruseStats = new ArrayList<>(
                Collections.singletonList(
                        constructPackageIoOveruseStats(
                                Binder.getCallingUid(), /* shouldNotify= */false,
                                constructInternalIoOveruseStats(/* killableOnOveruse= */false,
                                        /* remainingWriteBytes= */
                                        constructPerStateBytes(20, 20, 20),
                                        /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                                        /* totalOveruses= */2)))
        );
        mWatchdogServiceForSystemImpl.latestIoOveruseStats(packageIoOveruseStats);

        ResourceOveruseStats expectedStats =
                constructResourceOveruseStats(packageNamesByUid.keyAt(0),
                        packageNamesByUid.valueAt(0), packageIoOveruseStats.get(0).ioOveruseStats);

        ResourceOveruseStats actualStats = mCarWatchdogService.getResourceOveruseStats(
                CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                CarWatchdogManager.STATS_PERIOD_CURRENT_DAY);

        assertWithMessage("Expected: " + expectedStats.toString() + "\nActual: "
                + actualStats.toString())
                .that(ResourceOveruseStatsSubject.isEquals(actualStats, expectedStats)).isTrue();
    }

    @Test
    public void testFailsGetResourceOveruseStatsWithInvalidArgs() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getResourceOveruseStats(/* resourceOveruseFlag= */0,
                        CarWatchdogManager.STATS_PERIOD_CURRENT_DAY));

        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getResourceOveruseStats(
                        CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO, /* maxStatsPeriod= */0));
    }

    @Test
    public void testGetAllResourceOveruseStatsWithNoMinimum() throws Exception {
        SparseArray<String> packageNamesByUid = new SparseArray<>();
        packageNamesByUid.put(1103456, "third_party_package");
        packageNamesByUid.put(1201278, "vendor_package.critical");
        injectUidToPackageNameMapping(packageNamesByUid);

        List<PackageIoOveruseStats> packageIoOveruseStats = new ArrayList<>(Arrays.asList(
                constructPackageIoOveruseStats(packageNamesByUid.keyAt(0), /* shouldNotify= */false,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */true,
                                /* remainingWriteBytes= */constructPerStateBytes(20, 20, 20),
                                /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                                /* totalOveruses= */2)),
                constructPackageIoOveruseStats(packageNamesByUid.keyAt(1), /* shouldNotify= */false,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */false,
                                /* remainingWriteBytes= */constructPerStateBytes(450, 120, 340),
                                /* writtenBytes= */constructPerStateBytes(5000, 6000, 9000),
                                /* totalOveruses= */2))));
        mWatchdogServiceForSystemImpl.latestIoOveruseStats(packageIoOveruseStats);

        List<ResourceOveruseStats> expectedStats = new ArrayList<>(Arrays.asList(
                constructResourceOveruseStats(packageNamesByUid.keyAt(0),
                        packageNamesByUid.valueAt(0), packageIoOveruseStats.get(0).ioOveruseStats),
                constructResourceOveruseStats(packageNamesByUid.keyAt(1),
                        packageNamesByUid.valueAt(1), packageIoOveruseStats.get(1).ioOveruseStats))
        );

        List<ResourceOveruseStats> actualStats = mCarWatchdogService.getAllResourceOveruseStats(
                CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO, /* minimumStatsFlag= */0,
                CarWatchdogManager.STATS_PERIOD_CURRENT_DAY);

        ResourceOveruseStatsSubject.assertThat(actualStats)
                .containsExactlyElementsIn(expectedStats);
    }

    @Test
    public void testFailsGetAllResourceOveruseStatsWithInvalidArgs() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getAllResourceOveruseStats(0, /* minimumStatsFlag= */0,
                        CarWatchdogManager.STATS_PERIOD_CURRENT_DAY));

        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getAllResourceOveruseStats(
                        CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                        CarWatchdogManager.FLAG_MINIMUM_STATS_IO_1_MB
                                | CarWatchdogManager.FLAG_MINIMUM_STATS_IO_100_MB,
                        CarWatchdogManager.STATS_PERIOD_CURRENT_DAY));

        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getAllResourceOveruseStats(
                        CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO, /* minimumStatsFlag= */1 << 5,
                        CarWatchdogManager.STATS_PERIOD_CURRENT_DAY));

        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getAllResourceOveruseStats(
                        CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO, /* minimumStatsFlag= */0,
                        /* maxStatsPeriod= */0));
    }

    @Test
    public void testGetAllResourceOveruseStatsWithMinimum() throws Exception {
        SparseArray<String> packageNamesByUid = new SparseArray<>();
        packageNamesByUid.put(1103456, "third_party_package");
        packageNamesByUid.put(1201278, "vendor_package.critical");
        injectUidToPackageNameMapping(packageNamesByUid);

        List<PackageIoOveruseStats> packageIoOveruseStats = new ArrayList<>(Arrays.asList(
                constructPackageIoOveruseStats(packageNamesByUid.keyAt(0), /* shouldNotify= */false,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */true,
                                /* remainingWriteBytes= */constructPerStateBytes(20, 20, 20),
                                /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                                /* totalOveruses= */2)),
                constructPackageIoOveruseStats(packageNamesByUid.keyAt(1), /* shouldNotify= */false,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */false,
                                /* remainingWriteBytes= */constructPerStateBytes(450, 120, 340),
                                /* writtenBytes= */constructPerStateBytes(7000000, 6000, 9000),
                                /* totalOveruses= */2))));
        mWatchdogServiceForSystemImpl.latestIoOveruseStats(packageIoOveruseStats);

        List<ResourceOveruseStats> expectedStats = new ArrayList<>(Arrays.asList(
                constructResourceOveruseStats(packageNamesByUid.keyAt(1),
                        packageNamesByUid.valueAt(1), packageIoOveruseStats.get(1).ioOveruseStats))
        );

        List<ResourceOveruseStats> actualStats = mCarWatchdogService.getAllResourceOveruseStats(
                CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                CarWatchdogManager.FLAG_MINIMUM_STATS_IO_1_MB,
                CarWatchdogManager.STATS_PERIOD_CURRENT_DAY);

        ResourceOveruseStatsSubject.assertThat(actualStats)
                .containsExactlyElementsIn(expectedStats);
    }

    @Test
    public void testGetResourceOveruseStatsForUserPackage() throws Exception {
        SparseArray<String> packageNamesByUid = new SparseArray<>();
        packageNamesByUid.put(1103456, "third_party_package");
        packageNamesByUid.put(1201278, "vendor_package.critical");
        injectUidToPackageNameMapping(packageNamesByUid);

        List<PackageIoOveruseStats> packageIoOveruseStats = new ArrayList<>(Arrays.asList(
                constructPackageIoOveruseStats(packageNamesByUid.keyAt(0), /* shouldNotify= */false,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */true,
                                /* remainingWriteBytes= */constructPerStateBytes(20, 20, 20),
                                /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                                /* totalOveruses= */2)),
                constructPackageIoOveruseStats(packageNamesByUid.keyAt(1), /* shouldNotify= */false,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */false,
                                /* remainingWriteBytes= */constructPerStateBytes(450, 120, 340),
                                /* writtenBytes= */constructPerStateBytes(500, 600, 900),
                                /* totalOveruses= */2))));
        mWatchdogServiceForSystemImpl.latestIoOveruseStats(packageIoOveruseStats);

        ResourceOveruseStats expectedStats =
                constructResourceOveruseStats(packageNamesByUid.keyAt(1),
                        packageNamesByUid.valueAt(1), packageIoOveruseStats.get(1).ioOveruseStats);

        ResourceOveruseStats actualStats =
                mCarWatchdogService.getResourceOveruseStatsForUserPackage(
                        "vendor_package.critical", new UserHandle(12),
                        CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                        CarWatchdogManager.STATS_PERIOD_CURRENT_DAY);

        assertWithMessage("Expected: " + expectedStats.toString() + "\nActual: "
                + actualStats.toString())
                .that(ResourceOveruseStatsSubject.isEquals(actualStats, expectedStats)).isTrue();
    }

    @Test
    public void testFailsGetResourceOveruseStatsForUserPackageWithInvalidArgs() throws Exception {
        assertThrows(NullPointerException.class,
                () -> mCarWatchdogService.getResourceOveruseStatsForUserPackage(
                        /* packageName= */null, new UserHandle(10),
                        CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                        CarWatchdogManager.STATS_PERIOD_CURRENT_DAY));

        assertThrows(NullPointerException.class,
                () -> mCarWatchdogService.getResourceOveruseStatsForUserPackage("some.package",
                        /* userHandle= */null, CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                        CarWatchdogManager.STATS_PERIOD_CURRENT_DAY));

        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getResourceOveruseStatsForUserPackage("some.package",
                        UserHandle.ALL, CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                        CarWatchdogManager.STATS_PERIOD_CURRENT_DAY));

        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getResourceOveruseStatsForUserPackage("some.package",
                        new UserHandle(10), /* resourceOveruseFlag= */0,
                        CarWatchdogManager.STATS_PERIOD_CURRENT_DAY));

        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getResourceOveruseStatsForUserPackage("some.package",
                        new UserHandle(10), CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                        /* maxStatsPeriod= */0));
    }

    @Test
    public void testAddResourceOveruseListenerThrowsWithInvalidFlag() throws Exception {
        IResourceOveruseListener mockListener = createMockResourceOveruseListener();
        assertThrows(IllegalArgumentException.class, () -> {
            mCarWatchdogService.addResourceOveruseListener(0, mockListener);
        });
    }

    @Test
    public void testResourceOveruseListener() throws Exception {
        int callingUid = Binder.getCallingUid();
        SparseArray<String> packageNamesByUid = new SparseArray<>();
        packageNamesByUid.put(callingUid, "critical.system.package");
        injectUidToPackageNameMapping(packageNamesByUid);

        IResourceOveruseListener mockListener = createMockResourceOveruseListener();
        mCarWatchdogService.addResourceOveruseListener(CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                mockListener);

        IBinder mockBinder = mockListener.asBinder();
        verify(mockBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        List<PackageIoOveruseStats> packageIoOveruseStats = new ArrayList<>(
                Collections.singletonList(constructPackageIoOveruseStats(
                        callingUid, /* shouldNotify= */true,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */true,
                                /* remainingWriteBytes= */constructPerStateBytes(20, 20, 20),
                                /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                                /* totalOveruses= */2))));

        mWatchdogServiceForSystemImpl.latestIoOveruseStats(packageIoOveruseStats);

        verify(mockListener, times(1)).onOveruse(any());

        mCarWatchdogService.removeResourceOveruseListener(mockListener);

        verify(mockListener, atLeastOnce()).asBinder();
        verify(mockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        mWatchdogServiceForSystemImpl.latestIoOveruseStats(packageIoOveruseStats);

        verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void testAddResourceOveruseListenerForSystemThrowsWithInvalidFlag() throws Exception {
        IResourceOveruseListener mockListener = createMockResourceOveruseListener();
        assertThrows(IllegalArgumentException.class, () -> {
            mCarWatchdogService.addResourceOveruseListenerForSystem(0, mockListener);
        });
    }

    @Test
    public void testResourceOveruseListenerForSystem() throws Exception {
        int callingUid = Binder.getCallingUid();
        SparseArray<String> packageNamesByUid = new SparseArray<>();
        packageNamesByUid.put(callingUid, "critical.system.package");
        injectUidToPackageNameMapping(packageNamesByUid);

        IResourceOveruseListener mockListener = createMockResourceOveruseListener();
        mCarWatchdogService.addResourceOveruseListenerForSystem(
                CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO, mockListener);

        IBinder mockBinder = mockListener.asBinder();
        verify(mockBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        List<PackageIoOveruseStats> packageIoOveruseStats = new ArrayList<>(
                Collections.singletonList(constructPackageIoOveruseStats(
                        callingUid, /* shouldNotify= */true,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */true,
                                /* remainingWriteBytes= */constructPerStateBytes(20, 20, 20),
                                /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                                /* totalOveruses= */2))));

        mWatchdogServiceForSystemImpl.latestIoOveruseStats(packageIoOveruseStats);

        verify(mockListener, times(1)).onOveruse(any());

        mCarWatchdogService.removeResourceOveruseListenerForSystem(mockListener);

        verify(mockListener, atLeastOnce()).asBinder();
        verify(mockBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        mWatchdogServiceForSystemImpl.latestIoOveruseStats(packageIoOveruseStats);

        verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void testSetKillablePackageAsUserWithPackageStats() throws Exception {
        mockUmGetAliveUsers(mMockUserManager, 11, 12);
        injectPackageInfos(new ArrayList<>(Arrays.asList("third_party_package",
                "vendor_package.critical")));

        SparseArray<String> packageNamesByUid = new SparseArray<>();
        packageNamesByUid.put(1103456, "third_party_package");
        packageNamesByUid.put(1101278, "vendor_package.critical");
        injectIoOveruseStatsForPackages(packageNamesByUid,
                new ArraySet<>(Collections.singletonList("third_party_package")));

        UserHandle userHandle = new UserHandle(11);

        mCarWatchdogService.setKillablePackageAsUser("third_party_package", userHandle,
                /* isKillable= */ true);
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.setKillablePackageAsUser("vendor_package.critical",
                        userHandle, /* isKillable= */ true));

        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(userHandle)).containsExactly(
                new PackageKillableState("third_party_package", 11,
                        PackageKillableState.KILLABLE_STATE_YES),
                new PackageKillableState("vendor_package.critical", 11,
                        PackageKillableState.KILLABLE_STATE_NEVER));

        mCarWatchdogService.setKillablePackageAsUser("third_party_package", userHandle,
                /* isKillable= */ false);
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.setKillablePackageAsUser("vendor_package.critical",
                        userHandle, /* isKillable= */ false));

        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(userHandle)).containsExactly(
                new PackageKillableState("third_party_package", 11,
                        PackageKillableState.KILLABLE_STATE_NO),
                new PackageKillableState("vendor_package.critical", 11,
                        PackageKillableState.KILLABLE_STATE_NEVER));
    }

    @Test
    public void testSetKillablePackageAsUserWithNoPackageStats() throws Exception {
        mockUmGetAliveUsers(mMockUserManager, 11, 12);
        injectPackageInfos(new ArrayList<>(Arrays.asList("third_party_package",
                "vendor_package.critical")));

        UserHandle userHandle = new UserHandle(11);
        mCarWatchdogService.setKillablePackageAsUser("third_party_package", userHandle,
                /* isKillable= */ true);
        mCarWatchdogService.setKillablePackageAsUser("vendor_package.critical",
                userHandle, /* isKillable= */ true);

        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(userHandle)).containsExactly(
                new PackageKillableState("third_party_package", 11,
                        PackageKillableState.KILLABLE_STATE_YES),
                new PackageKillableState("vendor_package.critical", 11,
                        PackageKillableState.KILLABLE_STATE_NEVER));

        mCarWatchdogService.setKillablePackageAsUser("third_party_package", userHandle,
                /* isKillable= */ false);
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.setKillablePackageAsUser("vendor_package.critical",
                        userHandle, /* isKillable= */ false));

        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(userHandle)).containsExactly(
                new PackageKillableState("third_party_package", 11,
                        PackageKillableState.KILLABLE_STATE_NO),
                new PackageKillableState("vendor_package.critical", 11,
                        PackageKillableState.KILLABLE_STATE_NEVER));
    }

    @Test
    public void testSetKillablePackageAsUserForAllUsersWithPackageStats() throws Exception {
        mockUmGetAliveUsers(mMockUserManager, 11, 12);
        injectPackageInfos(new ArrayList<>(Arrays.asList("third_party_package",
                "vendor_package.critical")));

        SparseArray<String> packageNamesByUid = new SparseArray<>();
        packageNamesByUid.put(1103456, "third_party_package");
        packageNamesByUid.put(1101278, "vendor_package.critical");
        injectIoOveruseStatsForPackages(packageNamesByUid,
                new ArraySet<>(Collections.singletonList("third_party_package")));

        mCarWatchdogService.setKillablePackageAsUser("third_party_package", UserHandle.ALL,
                /* isKillable= */ true);
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.setKillablePackageAsUser("vendor_package.critical",
                        UserHandle.ALL, /* isKillable= */ true));

        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(UserHandle.ALL)).containsExactly(
                new PackageKillableState("third_party_package", 11,
                        PackageKillableState.KILLABLE_STATE_YES),
                new PackageKillableState("vendor_package.critical", 11,
                        PackageKillableState.KILLABLE_STATE_NEVER),
                new PackageKillableState("third_party_package", 12,
                        PackageKillableState.KILLABLE_STATE_YES),
                new PackageKillableState("vendor_package.critical", 12,
                        PackageKillableState.KILLABLE_STATE_NEVER));

        mCarWatchdogService.setKillablePackageAsUser("third_party_package", UserHandle.ALL,
                /* isKillable= */ false);
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.setKillablePackageAsUser("vendor_package.critical",
                        UserHandle.ALL, /* isKillable= */ false));

        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(UserHandle.ALL)).containsExactly(
                new PackageKillableState("third_party_package", 11,
                        PackageKillableState.KILLABLE_STATE_NO),
                new PackageKillableState("vendor_package.critical", 11,
                        PackageKillableState.KILLABLE_STATE_NEVER),
                new PackageKillableState("third_party_package", 12,
                        PackageKillableState.KILLABLE_STATE_NO),
                new PackageKillableState("vendor_package.critical", 12,
                        PackageKillableState.KILLABLE_STATE_NEVER));
    }

    @Test
    public void testSetKillablePackageAsUserForAllUsersWithNoPackageStats() throws Exception {
        mockUmGetAliveUsers(mMockUserManager, 11, 12);
        injectPackageInfos(new ArrayList<>(Arrays.asList("third_party_package",
                "vendor_package.critical")));

        mCarWatchdogService.setKillablePackageAsUser("third_party_package", UserHandle.ALL,
                /* isKillable= */ true);
        mCarWatchdogService.setKillablePackageAsUser("vendor_package.critical",
                UserHandle.ALL, /* isKillable= */ true);

        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(UserHandle.ALL)).containsExactly(
                new PackageKillableState("third_party_package", 11,
                        PackageKillableState.KILLABLE_STATE_YES),
                new PackageKillableState("vendor_package.critical", 11,
                        PackageKillableState.KILLABLE_STATE_NEVER),
                new PackageKillableState("third_party_package", 12,
                        PackageKillableState.KILLABLE_STATE_YES),
                new PackageKillableState("vendor_package.critical", 12,
                        PackageKillableState.KILLABLE_STATE_NEVER));

        mCarWatchdogService.setKillablePackageAsUser("third_party_package", UserHandle.ALL,
                /* isKillable= */ false);
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.setKillablePackageAsUser("vendor_package.critical",
                        UserHandle.ALL, /* isKillable= */ false));

        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(UserHandle.ALL)).containsExactly(
                new PackageKillableState("third_party_package", 11,
                        PackageKillableState.KILLABLE_STATE_NO),
                new PackageKillableState("vendor_package.critical", 11,
                        PackageKillableState.KILLABLE_STATE_NEVER),
                new PackageKillableState("third_party_package", 12,
                        PackageKillableState.KILLABLE_STATE_NO),
                new PackageKillableState("vendor_package.critical", 12,
                        PackageKillableState.KILLABLE_STATE_NEVER));
    }

    @Test
    public void testGetPackageKillableStatesAsUser() throws Exception {
        mockUmGetAliveUsers(mMockUserManager, 11, 12);
        injectPackageInfos(new ArrayList<>(Arrays.asList("third_party_package",
                "vendor_package.critical")));
        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(new UserHandle(11)))
                .containsExactly(
                        new PackageKillableState("third_party_package", 11,
                                PackageKillableState.KILLABLE_STATE_YES),
                        new PackageKillableState("vendor_package.critical", 11,
                                PackageKillableState.KILLABLE_STATE_NEVER));
    }

    @Test
    public void testGetPackageKillableStatesAsUserForAllUsers() throws Exception {
        mockUmGetAliveUsers(mMockUserManager, 11, 12);
        injectPackageInfos(new ArrayList<>(Arrays.asList("third_party_package",
                "vendor_package.critical")));
        PackageKillableStateSubject.assertThat(
                mCarWatchdogService.getPackageKillableStatesAsUser(UserHandle.ALL)).containsExactly(
                new PackageKillableState("third_party_package", 11,
                        PackageKillableState.KILLABLE_STATE_YES),
                new PackageKillableState("vendor_package.critical", 11,
                        PackageKillableState.KILLABLE_STATE_NEVER),
                new PackageKillableState("third_party_package", 12,
                        PackageKillableState.KILLABLE_STATE_YES),
                new PackageKillableState("vendor_package.critical", 12,
                        PackageKillableState.KILLABLE_STATE_NEVER));
    }

    @Test
    public void testSetResourceOveruseConfigurations() throws Exception {
        List<ResourceOveruseConfiguration> resourceOveruseConfigs = new ArrayList<>(Arrays.asList(
                sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                        sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM).build()).build(),
                sampleResourceOveruseConfigurationBuilder(ComponentType.VENDOR,
                        sampleIoOveruseConfigurationBuilder(ComponentType.VENDOR).build()).build(),
                sampleResourceOveruseConfigurationBuilder(ComponentType.THIRD_PARTY,
                        sampleIoOveruseConfigurationBuilder(ComponentType.THIRD_PARTY).build())
                        .build()));

        mCarWatchdogService.setResourceOveruseConfigurations(resourceOveruseConfigs,
                CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO);

        List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>
                actualConfigs = captureOnSetResourceOveruseConfigurations();

        List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>
                expectedConfigs = new ArrayList<>(Arrays.asList(
                sampleInternalResourceOveruseConfiguration(ComponentType.SYSTEM,
                        sampleInternalIoOveruseConfiguration(ComponentType.SYSTEM)),
                sampleInternalResourceOveruseConfiguration(ComponentType.VENDOR,
                        sampleInternalIoOveruseConfiguration(ComponentType.VENDOR)),
                sampleInternalResourceOveruseConfiguration(ComponentType.THIRD_PARTY,
                        sampleInternalIoOveruseConfiguration(ComponentType.THIRD_PARTY))));

        InternalResourceOveruseConfigurationSubject.assertThat(actualConfigs)
                .containsExactlyElementsIn(expectedConfigs);
    }

    @Test
    public void testFailsSetResourceOveruseConfigurationsOnInvalidArgs() throws Exception {
        assertThrows(NullPointerException.class,
                () -> mCarWatchdogService.setResourceOveruseConfigurations(null,
                        CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO));

        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.setResourceOveruseConfigurations(new ArrayList<>(),
                        CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO));

        List<ResourceOveruseConfiguration> resourceOveruseConfigs = new ArrayList<>(
                Collections.singletonList(
                        sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                                sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM).build())
                                .build()));
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.setResourceOveruseConfigurations(resourceOveruseConfigs,
                        0));
    }

    @Test
    public void testFailsSetResourceOveruseConfigurationsOnDuplicateComponents() throws Exception {
        ResourceOveruseConfiguration config =
                sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                        sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM).build()).build();
        List<ResourceOveruseConfiguration> resourceOveruseConfigs = new ArrayList<>(Arrays.asList(
                config, config));
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.setResourceOveruseConfigurations(resourceOveruseConfigs,
                        CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO));
    }

    @Test
    public void testFailsSetResourceOveruseConfigurationsOnNullIoOveruseConfiguration()
            throws Exception {
        List<ResourceOveruseConfiguration> resourceOveruseConfigs = new ArrayList<>(
                Collections.singletonList(
                        sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                                null).build()));
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.setResourceOveruseConfigurations(resourceOveruseConfigs,
                        CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO));
    }

    @Test
    public void testGetResourceOveruseConfigurations() throws Exception {
        List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>
                internalResourceOveruseConfigs = new ArrayList<>(Arrays.asList(
                sampleInternalResourceOveruseConfiguration(ComponentType.SYSTEM,
                        sampleInternalIoOveruseConfiguration(ComponentType.SYSTEM)),
                sampleInternalResourceOveruseConfiguration(ComponentType.VENDOR,
                        sampleInternalIoOveruseConfiguration(ComponentType.VENDOR)),
                sampleInternalResourceOveruseConfiguration(ComponentType.THIRD_PARTY,
                        sampleInternalIoOveruseConfiguration(ComponentType.THIRD_PARTY))));
        doReturn(internalResourceOveruseConfigs).when(mMockCarWatchdogDaemon)
                .getResourceOveruseConfigurations();

        List<ResourceOveruseConfiguration> actualConfigs =
                mCarWatchdogService.getResourceOveruseConfigurations(
                        CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO);

        List<ResourceOveruseConfiguration> expectedConfigs = new ArrayList<>(Arrays.asList(
                sampleResourceOveruseConfigurationBuilder(ComponentType.SYSTEM,
                        sampleIoOveruseConfigurationBuilder(ComponentType.SYSTEM).build()).build(),
                sampleResourceOveruseConfigurationBuilder(ComponentType.VENDOR,
                        sampleIoOveruseConfigurationBuilder(ComponentType.VENDOR).build()).build(),
                sampleResourceOveruseConfigurationBuilder(ComponentType.THIRD_PARTY,
                        sampleIoOveruseConfigurationBuilder(ComponentType.THIRD_PARTY).build())
                        .build()));

        ResourceOveruseConfigurationSubject.assertThat(actualConfigs)
                .containsExactlyElementsIn(expectedConfigs);
    }

    @Test
    public void testFailsGetResourceOveruseConfigurationsOnInvalidArgs() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogService.getResourceOveruseConfigurations(0));
    }

    @Test
    public void testLatestIoOveruseStats() throws Exception {
        int criticalSysPkgUid = Binder.getCallingUid();
        int nonCriticalSysPkgUid = getUid(1056);
        int nonCriticalVndrPkgUid = getUid(2564);
        int thirdPartyPkgUid = getUid(2044);

        SparseArray<String> packageNamesByUid = new SparseArray<>();
        packageNamesByUid.put(criticalSysPkgUid, "critical.system.package");
        packageNamesByUid.put(nonCriticalSysPkgUid, "non_critical.system.package");
        packageNamesByUid.put(nonCriticalVndrPkgUid, "non_critical.vendor.package");
        packageNamesByUid.put(thirdPartyPkgUid, "third_party.package");
        injectUidToPackageNameMapping(packageNamesByUid);

        IResourceOveruseListener mockSystemListener = createMockResourceOveruseListener();
        mCarWatchdogService.addResourceOveruseListenerForSystem(
                CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO, mockSystemListener);

        IResourceOveruseListener mockListener = createMockResourceOveruseListener();
        mCarWatchdogService.addResourceOveruseListener(
                CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO, mockListener);

        IPackageManager packageManagerService = Mockito.spy(ActivityThread.getPackageManager());
        when(ActivityThread.getPackageManager()).thenReturn(packageManagerService);
        mockApplicationEnabledSettingAccessors(packageManagerService);

        List<PackageIoOveruseStats> packageIoOveruseStats = new ArrayList<>(Arrays.asList(
                /* Overuse occurred but cannot be killed/disabled. */
                constructPackageIoOveruseStats(criticalSysPkgUid, /* shouldNotify= */true,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */false,
                                /* remainingWriteBytes= */constructPerStateBytes(0, 0, 0),
                                /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                                /* totalOveruses= */2)),
                /* No overuse occurred but should be notified. */
                constructPackageIoOveruseStats(nonCriticalSysPkgUid, /* shouldNotify= */true,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */true,
                                /* remainingWriteBytes= */constructPerStateBytes(20, 30, 40),
                                /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                                /* totalOveruses= */2)),
                /* Neither overuse occurred nor be notified. */
                constructPackageIoOveruseStats(nonCriticalVndrPkgUid, /* shouldNotify= */false,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */true,
                                /* remainingWriteBytes= */constructPerStateBytes(200, 300, 400),
                                /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                                /* totalOveruses= */2)),
                /* Overuse occurred and can be killed/disabled. */
                constructPackageIoOveruseStats(thirdPartyPkgUid, /* shouldNotify= */true,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */true,
                                /* remainingWriteBytes= */constructPerStateBytes(0, 0, 0),
                                /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                                /* totalOveruses= */2))));

        mWatchdogServiceForSystemImpl.latestIoOveruseStats(packageIoOveruseStats);

        List<ResourceOveruseStats> expectedStats = new ArrayList<>();

        expectedStats.add(constructResourceOveruseStats(criticalSysPkgUid,
                packageNamesByUid.get(criticalSysPkgUid),
                packageIoOveruseStats.get(0).ioOveruseStats));

        verifyOnOveruseCalled(expectedStats, mockListener);

        expectedStats.add(constructResourceOveruseStats(nonCriticalSysPkgUid,
                packageNamesByUid.get(nonCriticalSysPkgUid),
                packageIoOveruseStats.get(1).ioOveruseStats));

        expectedStats.add(constructResourceOveruseStats(thirdPartyPkgUid,
                packageNamesByUid.get(thirdPartyPkgUid),
                packageIoOveruseStats.get(3).ioOveruseStats));

        verifyOnOveruseCalled(expectedStats, mockSystemListener);

        verify(packageManagerService, times(1)).getApplicationEnabledSetting(
                packageNamesByUid.get(thirdPartyPkgUid), UserHandle.getUserId(thirdPartyPkgUid));
        verify(packageManagerService, times(1)).setApplicationEnabledSetting(
                eq(packageNamesByUid.get(thirdPartyPkgUid)), anyInt(), anyInt(),
                eq(UserHandle.getUserId(thirdPartyPkgUid)), anyString());

        List<PackageResourceOveruseAction> expectedActions = new ArrayList<>(Arrays.asList(
                constructPackageResourceOveruseAction(packageNamesByUid.get(criticalSysPkgUid),
                        criticalSysPkgUid, new int[]{ResourceType.IO}, NOT_KILLED),
                constructPackageResourceOveruseAction(packageNamesByUid.get(thirdPartyPkgUid),
                        thirdPartyPkgUid, new int[]{ResourceType.IO}, KILLED)));
        verifyActionsTakenOnResourceOveruse(expectedActions);
    }

    @Test
    public void testLatestIoOveruseStatsWithUserOptedOutPackage() throws Exception {
        // TODO(b/170741935): Test that the user opted out package is not killed on overuse.
    }

    @Test
    public void testLatestIoOveruseStatsWithRecurringOveruse() throws Exception {
        /*
         * TODO(b/170741935): Test that non-critical packages are killed on recurring overuse
         *  regardless of user settings.
         */
    }

    @Test
    public void testResetResourceOveruseStats() throws Exception {
        SparseArray<String> packageNamesByUid = new SparseArray<>();
        packageNamesByUid.put(Binder.getCallingUid(), mMockContext.getPackageName());
        packageNamesByUid.put(1101278, "vendor_package.critical");
        injectUidToPackageNameMapping(packageNamesByUid);

        List<PackageIoOveruseStats> packageIoOveruseStats = Arrays.asList(
                constructPackageIoOveruseStats(
                        Binder.getCallingUid(), /* shouldNotify= */false,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */false,
                                /* remainingWriteBytes= */constructPerStateBytes(20, 20, 20),
                                /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                                /* totalOveruses= */2)),
                constructPackageIoOveruseStats(
                        1101278, /* shouldNotify= */false,
                        constructInternalIoOveruseStats(/* killableOnOveruse= */false,
                                /* remainingWriteBytes= */constructPerStateBytes(120, 220, 230),
                                /* writtenBytes= */constructPerStateBytes(3100, 5200, 6300),
                                /* totalOveruses= */3)));
        mWatchdogServiceForSystemImpl.latestIoOveruseStats(packageIoOveruseStats);

        mWatchdogServiceForSystemImpl.resetResourceOveruseStats(
                Collections.singletonList(mMockContext.getPackageName()));

        ResourceOveruseStats actualStats = mCarWatchdogService.getResourceOveruseStats(
                CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                CarWatchdogManager.STATS_PERIOD_CURRENT_DAY);

        ResourceOveruseStats expectedStats = new ResourceOveruseStats.Builder(
                mMockContext.getPackageName(),
                UserHandle.getUserHandleForUid(Binder.getCallingUid())).build();

        assertWithMessage("Expected: " + expectedStats.toString() + "\nActual: "
                + actualStats.toString())
                .that(ResourceOveruseStatsSubject.isEquals(actualStats, expectedStats)).isTrue();
    }

    @Test
    public void testGetPackageInfosForUids() throws Exception {
        int[] uids = new int[]{6001, 6050, 5100, 110035, 120056, 120078, 1345678};
        List<PackageInfo> expectedPackageInfos = new ArrayList<>(Arrays.asList(
                constructPackageInfo("system.package.A", 6001, new ArrayList<>(),
                        UidType.NATIVE, ComponentType.SYSTEM, ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:system.package", 6050,
                        new ArrayList<>(Arrays.asList("system.package.B", "third_party.package.C")),
                        UidType.NATIVE, ComponentType.SYSTEM, ApplicationCategoryType.OTHERS),
                constructPackageInfo("vendor.package.D", 5100, new ArrayList<>(),
                        UidType.NATIVE, ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:vendor.package", 110035,
                        new ArrayList<>(Arrays.asList("vendor.package.E", "system.package.F",
                                "third_party.package.G")), UidType.APPLICATION,
                        ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("third_party.package.H", 120056, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.THIRD_PARTY,
                        ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:third_party.package", 120078,
                        new ArrayList<>(Arrays.asList("third_party.package.I")),
                        UidType.APPLICATION, ComponentType.THIRD_PARTY,
                        ApplicationCategoryType.OTHERS),
                constructPackageInfo("vendor.package.J", 1345678, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.VENDOR,
                        ApplicationCategoryType.OTHERS)));

        ArrayMap<String, ApplicationInfo> applicationInfos = new ArrayMap<>(9);
        applicationInfos.put("system.package.A",
                constructApplicationInfo(ApplicationInfo.FLAG_SYSTEM, 0));
        applicationInfos.put("system.package.B",
                constructApplicationInfo(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP, 0));
        applicationInfos.put("system.package.F",
                constructApplicationInfo(0, ApplicationInfo.PRIVATE_FLAG_PRODUCT));
        applicationInfos.put("vendor.package.D",
                constructApplicationInfo(0, ApplicationInfo.PRIVATE_FLAG_OEM));
        applicationInfos.put("vendor.package.E",
                constructApplicationInfo(0, ApplicationInfo.PRIVATE_FLAG_VENDOR));
        applicationInfos.put("vendor.package.J",
                constructApplicationInfo(0, ApplicationInfo.PRIVATE_FLAG_ODM));
        applicationInfos.put("third_party.package.C", constructApplicationInfo(0, 0));
        applicationInfos.put("third_party.package.G", constructApplicationInfo(0, 0));
        applicationInfos.put("third_party.package.H", constructApplicationInfo(0, 0));
        applicationInfos.put("third_party.package.I", constructApplicationInfo(0, 0));

        mockPackageManager(uids, expectedPackageInfos, applicationInfos);

        List<PackageInfo> actualPackageInfos = mWatchdogServiceForSystemImpl.getPackageInfosForUids(
                uids, new ArrayList<>());

        assertPackageInfoEquals(actualPackageInfos, expectedPackageInfos);
    }

    @Test
    public void testGetPackageInfosForUidsWithVendorPackagePrefixes() throws Exception {
        int[] uids = new int[]{110034, 110035, 123456, 120078};
        List<PackageInfo> expectedPackageInfos = new ArrayList<>(Arrays.asList(
                constructPackageInfo("vendor.package.D", 110034, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:vendor.package", 110035,
                        new ArrayList<>(Arrays.asList("vendor.pkg.E", "third_party.package.F",
                                "third_party.package.G")), UidType.APPLICATION,
                        ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("vendor.package.imposter", 123456,
                        new ArrayList<>(), UidType.APPLICATION, ComponentType.THIRD_PARTY,
                        ApplicationCategoryType.OTHERS),
                constructPackageInfo("third_party.package.H", 120078,
                        new ArrayList<>(), UidType.APPLICATION, ComponentType.THIRD_PARTY,
                        ApplicationCategoryType.OTHERS)));

        ArrayMap<String, ApplicationInfo> applicationInfos = new ArrayMap<>(5);
        applicationInfos.put("vendor.package.D", constructApplicationInfo(0,
                ApplicationInfo.PRIVATE_FLAG_PRODUCT));
        applicationInfos.put("vendor.pkg.E", constructApplicationInfo(ApplicationInfo.FLAG_SYSTEM,
                0));
        /*
         * A 3p package pretending to be a vendor package because 3p packages won't have the
         * required flags.
         */
        applicationInfos.put("vendor.package.imposter", constructApplicationInfo(0, 0));
        applicationInfos.put("third_party.package.F", constructApplicationInfo(0, 0));
        applicationInfos.put("third_party.package.G", constructApplicationInfo(0, 0));
        applicationInfos.put("third_party.package.H", constructApplicationInfo(0, 0));

        mockPackageManager(uids, expectedPackageInfos, applicationInfos);

        List<PackageInfo> actualPackageInfos = mWatchdogServiceForSystemImpl.getPackageInfosForUids(
                uids, new ArrayList<>(Arrays.asList("vendor.package.", "vendor.pkg.")));

        assertPackageInfoEquals(actualPackageInfos, expectedPackageInfos);
    }

    @Test
    public void testGetPackageInfosForUidsWithMissingApplicationInfos() throws Exception {
        int[] uids = new int[]{110034, 110035, 120056, 120078};
        List<PackageInfo> expectedPackageInfos = new ArrayList<>(Arrays.asList(
                constructPackageInfo("vendor.package.D", 110034, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:vendor.package", 110035,
                        new ArrayList<>(Arrays.asList("vendor.package.E", "third_party.package.F",
                                "third_party.package.G")),
                        UidType.APPLICATION, ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("third_party.package.H", 120056, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.UNKNOWN,
                        ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:third_party.package", 120078,
                        new ArrayList<>(Arrays.asList("third_party.package.I")),
                        UidType.APPLICATION, ComponentType.UNKNOWN,
                        ApplicationCategoryType.OTHERS)));

        ArrayMap<String, ApplicationInfo> applicationInfos = new ArrayMap<>(3);
        applicationInfos.put(
                "vendor.package.D", constructApplicationInfo(0,
                        ApplicationInfo.PRIVATE_FLAG_VENDOR));
        applicationInfos.put(
                "vendor.package.E", constructApplicationInfo(0,
                        ApplicationInfo.PRIVATE_FLAG_VENDOR));
        applicationInfos.put(
                "third_party.package.F", constructApplicationInfo(0, 0));

        mockPackageManager(uids, expectedPackageInfos, applicationInfos);

        List<PackageInfo> actualPackageInfos = mWatchdogServiceForSystemImpl.getPackageInfosForUids(
                uids, new ArrayList<>());

        assertPackageInfoEquals(actualPackageInfos, expectedPackageInfos);
    }

    private void mockWatchdogDaemon() {
        doReturn(mMockBinder).when(() -> ServiceManager.getService(CAR_WATCHDOG_DAEMON_INTERFACE));
        when(mMockBinder.queryLocalInterface(anyString())).thenReturn(mMockCarWatchdogDaemon);
    }

    private void setupUsers() {
        when(mMockContext.getSystemService(Context.USER_SERVICE)).thenReturn(mMockUserManager);
        mockUmGetAllUsers(mMockUserManager, new UserInfo[0]);
    }

    private ICarWatchdogServiceForSystem registerCarWatchdogService() throws Exception {
        ArgumentCaptor<ICarWatchdogServiceForSystem> watchdogServiceForSystemImplCaptor =
                ArgumentCaptor.forClass(ICarWatchdogServiceForSystem.class);
        verify(mMockCarWatchdogDaemon).registerCarWatchdogService(
                watchdogServiceForSystemImplCaptor.capture());
        return watchdogServiceForSystemImplCaptor.getValue();
    }

    private void testClientHealthCheck(TestClient client, int badClientCount) throws Exception {
        mCarWatchdogService.registerClient(client, TIMEOUT_CRITICAL);
        mWatchdogServiceForSystemImpl.checkIfAlive(123456, TIMEOUT_CRITICAL);
        ArgumentCaptor<int[]> notRespondingClients = ArgumentCaptor.forClass(int[].class);
        verify(mMockCarWatchdogDaemon, timeout(MAX_WAIT_TIME_MS)).tellCarWatchdogServiceAlive(
                eq(mWatchdogServiceForSystemImpl), notRespondingClients.capture(), eq(123456));
        assertThat(notRespondingClients.getValue().length).isEqualTo(0);
        mWatchdogServiceForSystemImpl.checkIfAlive(987654, TIMEOUT_CRITICAL);
        verify(mMockCarWatchdogDaemon, timeout(MAX_WAIT_TIME_MS)).tellCarWatchdogServiceAlive(
                eq(mWatchdogServiceForSystemImpl), notRespondingClients.capture(), eq(987654));
        assertThat(notRespondingClients.getValue().length).isEqualTo(badClientCount);
    }

    private List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>
            captureOnSetResourceOveruseConfigurations() throws Exception {
        ArgumentCaptor<List<android.automotive.watchdog.internal.ResourceOveruseConfiguration>>
                resourceOveruseConfigurationsCaptor = ArgumentCaptor.forClass(List.class);
        verify(mMockCarWatchdogDaemon).updateResourceOveruseConfigurations(
                resourceOveruseConfigurationsCaptor.capture());
        return resourceOveruseConfigurationsCaptor.getValue();
    }

    private void injectUidToPackageNameMapping(SparseArray<String> packageNamesByUid) {
        doAnswer(args -> {
            int[] uids = args.getArgument(0);
            String[] packageNames = new String[uids.length];
            for (int i = 0; i < uids.length; ++i) {
                packageNames[i] = packageNamesByUid.get(uids[i], null);
            }
            return packageNames;
        }).when(mMockPackageManager).getNamesForUids(any());
    }

    private void injectIoOveruseStatsForPackages(SparseArray<String> packageNamesByUid,
            Set<String> killablePackages) throws RemoteException {
        injectUidToPackageNameMapping(packageNamesByUid);
        List<PackageIoOveruseStats> packageIoOveruseStats = new ArrayList<>();
        for (int i = 0; i < packageNamesByUid.size(); ++i) {
            String packageName = packageNamesByUid.valueAt(i);
            int uid = packageNamesByUid.keyAt(i);
            packageIoOveruseStats.add(constructPackageIoOveruseStats(uid,
                    false,
                    constructInternalIoOveruseStats(killablePackages.contains(packageName),
                            /* remainingWriteBytes= */constructPerStateBytes(20, 20, 20),
                            /* writtenBytes= */constructPerStateBytes(100, 200, 300),
                            /* totalOveruses= */2)));
        }
        mWatchdogServiceForSystemImpl.latestIoOveruseStats(packageIoOveruseStats);
    }

    private void injectPackageInfos(List<String> packageNames) {
        List<android.content.pm.PackageInfo> packageInfos = new ArrayList<>();
        TriConsumer<String, Integer, Integer> addPackageInfo =
                (packageName, flags, privateFlags) -> {
                    android.content.pm.PackageInfo packageInfo =
                            new android.content.pm.PackageInfo();
                    packageInfo.packageName = packageName;
                    packageInfo.applicationInfo = new ApplicationInfo();
                    packageInfo.applicationInfo.flags = flags;
                    packageInfo.applicationInfo.privateFlags = privateFlags;
                    packageInfos.add(packageInfo);
                };
        for (String packageName : packageNames) {
            if (packageName.startsWith("system")) {
                addPackageInfo.accept(packageName, ApplicationInfo.FLAG_SYSTEM, 0);
            } else if (packageName.startsWith("vendor")) {
                addPackageInfo.accept(packageName, ApplicationInfo.FLAG_SYSTEM,
                        ApplicationInfo.PRIVATE_FLAG_OEM);
            } else {
                addPackageInfo.accept(packageName, 0, 0);
            }
        }
        doReturn(packageInfos).when(mMockPackageManager).getInstalledPackagesAsUser(
                eq(0), anyInt());
    }

    private void mockApplicationEnabledSettingAccessors(IPackageManager pm) throws Exception {
        doReturn(COMPONENT_ENABLED_STATE_ENABLED).when(pm)
                .getApplicationEnabledSetting(anyString(), eq(UserHandle.myUserId()));

        doNothing().when(pm).setApplicationEnabledSetting(anyString(), anyInt(),
                anyInt(), eq(UserHandle.myUserId()), anyString());
    }

    private void verifyActionsTakenOnResourceOveruse(List<PackageResourceOveruseAction> expected)
            throws Exception {
        ArgumentCaptor<List<PackageResourceOveruseAction>> resourceOveruseActionsCaptor =
                ArgumentCaptor.forClass((Class) List.class);

        verify(mMockCarWatchdogDaemon, timeout(MAX_WAIT_TIME_MS)).actionTakenOnResourceOveruse(
                resourceOveruseActionsCaptor.capture());
        List<PackageResourceOveruseAction> actual = resourceOveruseActionsCaptor.getValue();

        assertThat(actual).comparingElementsUsing(
                Correspondence.from(
                        CarWatchdogServiceUnitTest::isPackageResourceOveruseActionEquals,
                        "is overuse action equal to")).containsExactlyElementsIn(expected);
    }

    public static boolean isPackageResourceOveruseActionEquals(PackageResourceOveruseAction actual,
            PackageResourceOveruseAction expected) {
        return isEquals(actual.packageIdentifier, expected.packageIdentifier)
                && Arrays.equals(actual.resourceTypes, expected.resourceTypes)
                && actual.resourceOveruseActionType == expected.resourceOveruseActionType;
    }

    private static void verifyOnOveruseCalled(List<ResourceOveruseStats> expectedStats,
            IResourceOveruseListener mockListener) throws Exception {
        ArgumentCaptor<ResourceOveruseStats> resourceOveruseStatsCaptor =
                ArgumentCaptor.forClass(ResourceOveruseStats.class);

        verify(mockListener, times(expectedStats.size()))
                .onOveruse(resourceOveruseStatsCaptor.capture());

        ResourceOveruseStatsSubject.assertThat(resourceOveruseStatsCaptor.getAllValues())
                .containsExactlyElementsIn(expectedStats);
    }

    private static int getUid(int appId) {
        return UserHandle.getUid(UserHandle.myUserId(), appId);
    }

    private static ResourceOveruseConfiguration.Builder sampleResourceOveruseConfigurationBuilder(
            int componentType, IoOveruseConfiguration ioOveruseConfig) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType);
        List<String> safeToKill = new ArrayList<>(Arrays.asList(
                prefix + "_package.A", prefix + "_pkg.B"));
        List<String> vendorPrefixes = new ArrayList<>(Arrays.asList(
                prefix + "_package", prefix + "_pkg"));
        Map<String, String> pkgToAppCategory = new ArrayMap<>();
        pkgToAppCategory.put(prefix + "_package.A", "android.car.watchdog.app.category.MEDIA");
        ResourceOveruseConfiguration.Builder configBuilder =
                new ResourceOveruseConfiguration.Builder(componentType, safeToKill,
                        vendorPrefixes, pkgToAppCategory);
        configBuilder.setIoOveruseConfiguration(ioOveruseConfig);
        return configBuilder;
    }

    private static IoOveruseConfiguration.Builder sampleIoOveruseConfigurationBuilder(
            int componentType) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType);
        PerStateBytes componentLevelThresholds = new PerStateBytes(
                /* foregroundModeBytes= */10, /* backgroundModeBytes= */20,
                /* garageModeBytes= */30);
        Map<String, PerStateBytes> packageSpecificThresholds = new ArrayMap<>();
        packageSpecificThresholds.put(prefix + "_package.A", new PerStateBytes(
                /* foregroundModeBytes= */40, /* backgroundModeBytes= */50,
                /* garageModeBytes= */60));

        Map<String, PerStateBytes> appCategorySpecificThresholds = new ArrayMap<>();
        appCategorySpecificThresholds.put(
                ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MEDIA,
                new PerStateBytes(/* foregroundModeBytes= */100, /* backgroundModeBytes= */200,
                        /* garageModeBytes= */300));
        appCategorySpecificThresholds.put(
                ResourceOveruseConfiguration.APPLICATION_CATEGORY_TYPE_MAPS,
                new PerStateBytes(/* foregroundModeBytes= */1100, /* backgroundModeBytes= */2200,
                        /* garageModeBytes= */3300));

        List<IoOveruseAlertThreshold> systemWideThresholds = new ArrayList<>(
                Collections.singletonList(new IoOveruseAlertThreshold(/* durationInSeconds= */10,
                        /* writtenBytesPerSecond= */200)));

        return new IoOveruseConfiguration.Builder(componentLevelThresholds,
                packageSpecificThresholds, appCategorySpecificThresholds, systemWideThresholds);
    }

    private static android.automotive.watchdog.internal.ResourceOveruseConfiguration
            sampleInternalResourceOveruseConfiguration(int componentType,
            android.automotive.watchdog.internal.IoOveruseConfiguration ioOveruseConfig) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType);
        android.automotive.watchdog.internal.ResourceOveruseConfiguration config =
                new android.automotive.watchdog.internal.ResourceOveruseConfiguration();
        config.componentType = componentType;
        config.safeToKillPackages = new ArrayList<>(Arrays.asList(
                prefix + "_package.A", prefix + "_pkg.B"));
        config.vendorPackagePrefixes = new ArrayList<>(Arrays.asList(
                prefix + "_package", prefix + "_pkg"));

        PackageMetadata metadata = new PackageMetadata();
        metadata.packageName = prefix + "_package.A";
        metadata.appCategoryType = ApplicationCategoryType.MEDIA;
        config.packageMetadata = new ArrayList<>(Collections.singletonList(metadata));

        ResourceSpecificConfiguration resourceSpecificConfig = new ResourceSpecificConfiguration();
        resourceSpecificConfig.setIoOveruseConfiguration(ioOveruseConfig);
        config.resourceSpecificConfigurations = new ArrayList<>(
                Collections.singletonList(resourceSpecificConfig));

        return config;
    }

    private static android.automotive.watchdog.internal.IoOveruseConfiguration
            sampleInternalIoOveruseConfiguration(int componentType) {
        String prefix = WatchdogPerfHandler.toComponentTypeStr(componentType);
        android.automotive.watchdog.internal.IoOveruseConfiguration config =
                new android.automotive.watchdog.internal.IoOveruseConfiguration();
        config.componentLevelThresholds = constructPerStateIoOveruseThreshold(prefix,
                /* fgBytes= */10, /* bgBytes= */20, /* gmBytes= */30);
        config.packageSpecificThresholds = new ArrayList<>(Collections.singletonList(
                constructPerStateIoOveruseThreshold(prefix + "_package.A", /* fgBytes= */40,
                        /* bgBytes= */50, /* gmBytes= */60)));
        config.categorySpecificThresholds = new ArrayList<>(Arrays.asList(
                constructPerStateIoOveruseThreshold(
                        WatchdogPerfHandler.INTERNAL_APPLICATION_CATEGORY_TYPE_MEDIA,
                        /* fgBytes= */100, /* bgBytes= */200, /* gmBytes= */300),
                constructPerStateIoOveruseThreshold(
                        WatchdogPerfHandler.INTERNAL_APPLICATION_CATEGORY_TYPE_MAPS,
                        /* fgBytes= */1100, /* bgBytes= */2200, /* gmBytes= */3300)));
        config.systemWideThresholds = new ArrayList<>(Collections.singletonList(
                constructInternalIoOveruseAlertThreshold(/* duration= */10, /* writeBPS= */200)));
        return config;
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

    private static android.automotive.watchdog.internal.IoOveruseAlertThreshold
            constructInternalIoOveruseAlertThreshold(long duration, long writeBPS) {
        android.automotive.watchdog.internal.IoOveruseAlertThreshold threshold =
                new android.automotive.watchdog.internal.IoOveruseAlertThreshold();
        threshold.durationInSeconds = duration;
        threshold.writtenBytesPerSecond = writeBPS;
        return threshold;
    }

    private static PackageIoOveruseStats constructPackageIoOveruseStats(int uid,
            boolean shouldNotify, android.automotive.watchdog.IoOveruseStats ioOveruseStats) {
        PackageIoOveruseStats stats = new PackageIoOveruseStats();
        stats.uid = uid;
        stats.shouldNotify = shouldNotify;
        stats.ioOveruseStats = ioOveruseStats;
        return stats;
    }

    private static ResourceOveruseStats constructResourceOveruseStats(int uid, String packageName,
            android.automotive.watchdog.IoOveruseStats internalIoOveruseStats) {
        IoOveruseStats ioOveruseStats =
                WatchdogPerfHandler.toIoOveruseStatsBuilder(internalIoOveruseStats)
                        .setKillableOnOveruse(internalIoOveruseStats.killableOnOveruse).build();

        return new ResourceOveruseStats.Builder(packageName, UserHandle.getUserHandleForUid(uid))
                .setIoOveruseStats(ioOveruseStats).build();
    }

    private static android.automotive.watchdog.IoOveruseStats constructInternalIoOveruseStats(
            boolean killableOnOveruse,
            android.automotive.watchdog.PerStateBytes remainingWriteBytes,
            android.automotive.watchdog.PerStateBytes writtenBytes, int totalOveruses) {
        android.automotive.watchdog.IoOveruseStats stats =
                new android.automotive.watchdog.IoOveruseStats();
        stats.killableOnOveruse = killableOnOveruse;
        stats.remainingWriteBytes = remainingWriteBytes;
        stats.writtenBytes = writtenBytes;
        stats.totalOveruses = totalOveruses;
        return stats;
    }

    private static android.automotive.watchdog.PerStateBytes constructPerStateBytes(long fgBytes,
            long bgBytes, long gmBytes) {
        android.automotive.watchdog.PerStateBytes perStateBytes =
                new android.automotive.watchdog.PerStateBytes();
        perStateBytes.foregroundBytes = fgBytes;
        perStateBytes.backgroundBytes = bgBytes;
        perStateBytes.garageModeBytes = gmBytes;
        return perStateBytes;
    }

    private static PackageResourceOveruseAction constructPackageResourceOveruseAction(
            String packageName, int uid, int[] resourceTypes, int resourceOveruseActionType) {
        PackageResourceOveruseAction action = new PackageResourceOveruseAction();
        action.packageIdentifier = new PackageIdentifier();
        action.packageIdentifier.name = packageName;
        action.packageIdentifier.uid = uid;
        action.resourceTypes = resourceTypes;
        action.resourceOveruseActionType = resourceOveruseActionType;
        return action;
    }

    private class TestClient extends ICarWatchdogServiceCallback.Stub {
        protected int mLastSessionId = INVALID_SESSION_ID;

        @Override
        public void onCheckHealthStatus(int sessionId, int timeout) {
            mLastSessionId = sessionId;
            mCarWatchdogService.tellClientAlive(this, sessionId);
        }

        @Override
        public void onPrepareProcessTermination() {
        }

        public int getLastSessionId() {
            return mLastSessionId;
        }
    }

    private final class BadTestClient extends TestClient {
        @Override
        public void onCheckHealthStatus(int sessionId, int timeout) {
            mLastSessionId = sessionId;
            // This client doesn't respond to CarWatchdogService.
        }
    }

    private static IResourceOveruseListener createMockResourceOveruseListener() {
        IResourceOveruseListener listener = mock(IResourceOveruseListener.Stub.class);
        when(listener.asBinder()).thenCallRealMethod();
        return listener;
    }

    private void mockPackageManager(int[] uids, List<PackageInfo> packageInfos,
            ArrayMap<String, ApplicationInfo> applicationInfos)
            throws PackageManager.NameNotFoundException {
        String[] packageNames = new String[packageInfos.size()];
        for (int i = 0; i < packageInfos.size(); ++i) {
            PackageInfo packageInfo = packageInfos.get(i);
            packageNames[i] = packageInfo.packageIdentifier.name;
            when(mMockPackageManager.getPackagesForUid(packageInfo.packageIdentifier.uid))
                    .thenReturn(packageInfo.sharedUidPackages.toArray(new String[0]));
        }
        when(mMockPackageManager.getNamesForUids(any())).thenReturn(packageNames);
        when(mMockPackageManager.getApplicationInfoAsUser(any(), anyInt(), anyInt())).thenAnswer(
                (InvocationOnMock invocation) -> {
                    String packageName = invocation.getArgument(0);
                    ApplicationInfo applicationInfo = applicationInfos
                            .getOrDefault(packageName, /* defaultValue= */ null);
                    if (applicationInfo == null) {
                        throw new PackageManager.NameNotFoundException(
                                "Package " + packageName + " not found exception");
                    }
                    return applicationInfo;
                });
    }

    private static PackageInfo constructPackageInfo(String packageName, int uid,
            List<String> sharedUidPackages, int uidType, int componentType, int appCategoryType) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageIdentifier = new PackageIdentifier();
        packageInfo.packageIdentifier.name = packageName;
        packageInfo.packageIdentifier.uid = uid;
        packageInfo.uidType = uidType;
        packageInfo.sharedUidPackages = sharedUidPackages;
        packageInfo.componentType = componentType;
        packageInfo.appCategoryType = appCategoryType;

        return packageInfo;
    }

    private static ApplicationInfo constructApplicationInfo(int flags, int privateFlags) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = flags;
        applicationInfo.privateFlags = privateFlags;
        return applicationInfo;
    }

    private static String toString(List<PackageInfo> packageInfos) {
        StringBuilder builder = new StringBuilder();
        for (PackageInfo packageInfo : packageInfos) {
            builder = toString(builder, packageInfo).append('\n');
        }
        return builder.toString();
    }

    private static StringBuilder toString(StringBuilder builder, PackageInfo packageInfo) {
        if (packageInfo == null) {
            return builder.append("Null package info\n");
        }
        builder.append("Package name: '").append(packageInfo.packageIdentifier.name)
                .append("', UID: ").append(packageInfo.packageIdentifier.uid).append('\n')
                .append("Owned packages: ");
        if (packageInfo.sharedUidPackages != null) {
            for (int i = 0; i < packageInfo.sharedUidPackages.size(); ++i) {
                builder.append('\'').append(packageInfo.sharedUidPackages.get(i)).append('\'');
                if (i < packageInfo.sharedUidPackages.size() - 1) {
                    builder.append(", ");
                }
            }
            builder.append('\n');
        } else {
            builder.append("Null");
        }
        builder.append("Component type: ").append(packageInfo.componentType).append('\n')
                .append("Application category type: ").append(packageInfo.appCategoryType).append(
                '\n');

        return builder;
    }

    private static void assertPackageInfoEquals(List<PackageInfo> actual,
            List<PackageInfo> expected) throws Exception {
        assertWithMessage("Package infos for UIDs:\nExpected: %s\nActual: %s",
                toString(expected), toString(actual)).that(actual).comparingElementsUsing(
                Correspondence.from(CarWatchdogServiceUnitTest::isPackageInfoEquals,
                        "is package info equal to")).containsExactlyElementsIn(expected);
    }

    private static boolean isPackageInfoEquals(PackageInfo lhs, PackageInfo rhs) {
        return isEquals(lhs.packageIdentifier, rhs.packageIdentifier)
                && lhs.sharedUidPackages.equals(rhs.sharedUidPackages)
                && lhs.componentType == rhs.componentType
                && lhs.appCategoryType == rhs.appCategoryType;
    }

    private static boolean isEquals(PackageIdentifier lhs, PackageIdentifier rhs) {
        return lhs.name.equals(rhs.name) && lhs.uid == rhs.uid;
    }
}
