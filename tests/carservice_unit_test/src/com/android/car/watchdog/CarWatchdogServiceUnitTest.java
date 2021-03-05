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

import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetAllUsers;
import static android.car.watchdog.CarWatchdogManager.TIMEOUT_CRITICAL;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.automotive.watchdog.internal.ApplicationCategoryType;
import android.automotive.watchdog.internal.ComponentType;
import android.automotive.watchdog.internal.ICarWatchdog;
import android.automotive.watchdog.internal.ICarWatchdogServiceForSystem;
import android.automotive.watchdog.internal.PackageIdentifier;
import android.automotive.watchdog.internal.PackageInfo;
import android.automotive.watchdog.internal.UidType;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.watchdog.ICarWatchdogServiceCallback;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.ArrayMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    @Mock private UserManager mUserManager;
    @Mock private IBinder mBinder;
    @Mock private ICarWatchdog mCarWatchdogDaemon;

    private CarWatchdogService mCarWatchdogService;
    private ICarWatchdogServiceForSystem mWatchdogServiceForSystemImpl;

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUpMocks() throws Exception {
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        mCarWatchdogService = new CarWatchdogService(mMockContext);
        mockWatchdogDaemon();
        setupUsers();
        mCarWatchdogService.init();
        mWatchdogServiceForSystemImpl = registerCarWatchdogService();
    }

    @Test
    public void testCarWatchdogServiceHealthCheck() throws Exception {
        mWatchdogServiceForSystemImpl.checkIfAlive(123456, TIMEOUT_CRITICAL);
        verify(mCarWatchdogDaemon,
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
    public void testGetPackageInfosForUids() throws Exception {
        int[] uids = new int[]{6001, 6050, 5100, 110035, 120056, 120078};
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
                        UidType.APPLICATION,  ComponentType.THIRD_PARTY,
                        ApplicationCategoryType.OTHERS)));

        ArrayMap<String, ApplicationInfo> applicationInfos = new ArrayMap<>(9);
        applicationInfos.put("system.package.A",
                constructApplicationInfo(ApplicationInfo.FLAG_SYSTEM));
        applicationInfos.put("system.package.B",
                constructApplicationInfo(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP));
        applicationInfos.put("system.package.F",
                constructApplicationInfo(ApplicationInfo.PRIVATE_FLAG_PRODUCT));
        applicationInfos.put("vendor.package.D",
                constructApplicationInfo(ApplicationInfo.PRIVATE_FLAG_OEM));
        applicationInfos.put("vendor.package.E",
                constructApplicationInfo(ApplicationInfo.PRIVATE_FLAG_VENDOR));
        applicationInfos.put("third_party.package.C", constructApplicationInfo(0));
        applicationInfos.put("third_party.package.G", constructApplicationInfo(0));
        applicationInfos.put("third_party.package.H", constructApplicationInfo(0));
        applicationInfos.put("third_party.package.I", constructApplicationInfo(0));

        mockPackageManager(uids, expectedPackageInfos, applicationInfos);

        List<PackageInfo> actualPackageInfos = mWatchdogServiceForSystemImpl.getPackageInfosForUids(
                uids, new ArrayList<>());

        assertWithMessage("Package infos for UIDs:\nExpected: %s\nActual: %s",
            toString(expectedPackageInfos), toString(actualPackageInfos))
            .that(equals(expectedPackageInfos, actualPackageInfos)).isTrue();
    }

    @Test
    public void testGetPackageInfosForUidsWithVendorPackagePrefixes() throws Exception {
        int[] uids = new int[]{110034, 110035, 120078};
        List<PackageInfo> expectedPackageInfos = new ArrayList<>(Arrays.asList(
                constructPackageInfo("vendor.package.D", 110034, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("shared:vendor.package", 110035,
                        new ArrayList<>(Arrays.asList("vendor.pkg.E", "third_party.package.F",
                                "third_party.package.G")), UidType.APPLICATION,
                        ComponentType.VENDOR, ApplicationCategoryType.OTHERS),
                constructPackageInfo("third_party.package.H", 120078, new ArrayList<>(),
                        UidType.APPLICATION, ComponentType.THIRD_PARTY,
                        ApplicationCategoryType.OTHERS)));

        ArrayMap<String, ApplicationInfo> applicationInfos = new ArrayMap<>(5);
        applicationInfos.put("vendor.package.D", constructApplicationInfo(0));
        applicationInfos.put("vendor.pkg.E", constructApplicationInfo(0));
        applicationInfos.put("third_party.package.F", constructApplicationInfo(0));
        applicationInfos.put("third_party.package.G", constructApplicationInfo(0));
        applicationInfos.put("third_party.package.H", constructApplicationInfo(0));

        mockPackageManager(uids, expectedPackageInfos, applicationInfos);

        List<PackageInfo> actualPackageInfos = mWatchdogServiceForSystemImpl.getPackageInfosForUids(
                uids, new ArrayList<>(Arrays.asList("vendor.package.", "vendor.pkg.")));

        assertWithMessage("Package infos for UIDs:\nExpected: %s\nActual: %s",
            toString(expectedPackageInfos), toString(actualPackageInfos))
            .that(equals(expectedPackageInfos, actualPackageInfos)).isTrue();
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
                "vendor.package.D", constructApplicationInfo(ApplicationInfo.PRIVATE_FLAG_VENDOR));
        applicationInfos.put(
                "vendor.package.E", constructApplicationInfo(ApplicationInfo.PRIVATE_FLAG_VENDOR));
        applicationInfos.put(
                "third_party.package.F", constructApplicationInfo(0));

        mockPackageManager(uids, expectedPackageInfos, applicationInfos);

        List<PackageInfo> actualPackageInfos = mWatchdogServiceForSystemImpl.getPackageInfosForUids(
                uids, new ArrayList<>());

        assertWithMessage("Package infos for UIDs:\nExpected: %s\nActual: %s",
            toString(expectedPackageInfos), toString(actualPackageInfos))
            .that(equals(expectedPackageInfos, actualPackageInfos)).isTrue();
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder.spyStatic(ServiceManager.class);
    }

    private void mockWatchdogDaemon() {
        doReturn(mBinder).when(() -> ServiceManager.getService(CAR_WATCHDOG_DAEMON_INTERFACE));
        when(mBinder.queryLocalInterface(anyString())).thenReturn(mCarWatchdogDaemon);
    }

    private void setupUsers() {
        when(mMockContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        mockUmGetAllUsers(mUserManager, new UserInfo[0]);
    }

    private ICarWatchdogServiceForSystem registerCarWatchdogService() throws Exception {
        ArgumentCaptor<ICarWatchdogServiceForSystem> watchdogServiceForSystemImplCaptor =
                ArgumentCaptor.forClass(ICarWatchdogServiceForSystem.class);
        verify(mCarWatchdogDaemon).registerCarWatchdogService(
                watchdogServiceForSystemImplCaptor.capture());
        return watchdogServiceForSystemImplCaptor.getValue();
    }

    private void testClientHealthCheck(TestClient client, int badClientCount) throws Exception {
        mCarWatchdogService.registerClient(client, TIMEOUT_CRITICAL);
        mWatchdogServiceForSystemImpl.checkIfAlive(123456, TIMEOUT_CRITICAL);
        ArgumentCaptor<int[]> notRespondingClients = ArgumentCaptor.forClass(int[].class);
        verify(mCarWatchdogDaemon, timeout(MAX_WAIT_TIME_MS)).tellCarWatchdogServiceAlive(
                eq(mWatchdogServiceForSystemImpl), notRespondingClients.capture(), eq(123456));
        assertThat(notRespondingClients.getValue().length).isEqualTo(0);
        mWatchdogServiceForSystemImpl.checkIfAlive(987654, TIMEOUT_CRITICAL);
        verify(mCarWatchdogDaemon, timeout(MAX_WAIT_TIME_MS)).tellCarWatchdogServiceAlive(
                eq(mWatchdogServiceForSystemImpl), notRespondingClients.capture(), eq(987654));
        assertThat(notRespondingClients.getValue().length).isEqualTo(badClientCount);
    }

    private class TestClient extends ICarWatchdogServiceCallback.Stub {
        protected int mLastSessionId = INVALID_SESSION_ID;

        @Override
        public void onCheckHealthStatus(int sessionId, int timeout) {
            mLastSessionId = sessionId;
            mCarWatchdogService.tellClientAlive(this, sessionId);
        }

        @Override
        public void onPrepareProcessTermination() {}

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

    private PackageInfo constructPackageInfo(String packageName, int uid,
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

    private ApplicationInfo constructApplicationInfo(int flags) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = flags;
        return applicationInfo;
    }

    private String toString(List<PackageInfo> packageInfos) {
        StringBuilder builder = new StringBuilder();
        for (PackageInfo packageInfo : packageInfos) {
            builder = toString(builder, packageInfo).append('\n');
        }
        return builder.toString();
    }

    private StringBuilder toString(StringBuilder builder, PackageInfo packageInfo) {
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
            .append("Application category type: ").append(packageInfo.appCategoryType).append('\n');

        return builder;
    }

    private boolean equals(List<PackageInfo> lhs, List<PackageInfo> rhs) {
        if (lhs.size() != rhs.size()) {
            return false;
        }
        for (int i = 0; i < lhs.size(); ++i) {
            if (!equals(lhs.get(i), rhs.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean equals(PackageInfo lhs, PackageInfo rhs) {
        return equals(lhs.packageIdentifier, rhs.packageIdentifier)
                && lhs.sharedUidPackages.equals(rhs.sharedUidPackages)
                && lhs.componentType == rhs.componentType
                && lhs.appCategoryType == rhs.appCategoryType;
    }

    private boolean equals(PackageIdentifier lhs, PackageIdentifier rhs) {
        return lhs.name.equals(rhs.name) && lhs.uid == rhs.uid;
    }
}
