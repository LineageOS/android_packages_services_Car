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

package com.android.car.pm;

import static android.Manifest.permission.QUERY_ALL_PACKAGES;
import static android.car.Car.PERMISSION_REGISTER_CAR_SYSTEM_UI_PROXY;
import static android.car.content.pm.CarPackageManager.ERROR_CODE_NO_PACKAGE;
import static android.car.content.pm.CarPackageManager.MANIFEST_METADATA_TARGET_CAR_VERSION;
import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;
import static android.os.Process.INVALID_UID;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.car.CarVersion;
import android.car.builtin.app.ActivityManagerHelper;
import android.car.content.pm.ICarBlockingUiCommandListener;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.util.SparseIntArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.CarOccupantZoneService;
import com.android.car.CarUxRestrictionsManagerService;
import com.android.car.am.CarActivityService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for {@link CarPackageManagerService}.
 */
@RunWith(AndroidJUnit4.class)
public class CarPackageManagerServiceUnitTest extends AbstractExtendedMockitoTestCase {
    CarPackageManagerService mService;

    private Context mSpiedContext;
    private PackageManager mSpiedPackageManager;

    private final UserHandle mUserHandle = UserHandle.of(666);

    @Mock
    private Context mUserContext;

    @Mock
    private CarUxRestrictionsManagerService mMockUxrService;
    @Mock
    private CarActivityService mMockActivityService;
    @Mock
    private CarOccupantZoneService mMockCarOccupantZoneService;
    @Mock
    private PendingIntent mMockPendingIntent;

    public CarPackageManagerServiceUnitTest() {
        super(CarPackageManagerService.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder
            .spyStatic(ActivityManagerHelper.class)
            .spyStatic(Binder.class)
            // Need to mock service itself because of getTargetCarVersion() - it doesn't make
            // sense to test all variations of the methods that call it
            .spyStatic(CarPackageManagerService.class);
    }

    @Before
    public void setUp() {
        mSpiedContext = spy(InstrumentationRegistry.getInstrumentation().getContext());

        doReturn(mUserContext).when(mSpiedContext).createContextAsUser(mUserHandle, /* flags= */ 0);

        mSpiedPackageManager = spy(mSpiedContext.getPackageManager());
        doReturn(mSpiedPackageManager).when(mSpiedContext).getPackageManager();

        mService = new CarPackageManagerService(mSpiedContext,
                mMockUxrService, mMockActivityService, mMockCarOccupantZoneService);
    }

    @Test
    public void testParseConfigList_SingleActivity() {
        String config = "com.android.test/.TestActivity";
        Map<String, Set<String>> map = new HashMap<>();

        mService.parseConfigList(config, map);

        assertThat(map.get("com.android.test")).containsExactly(".TestActivity");
    }

    @Test
    public void testParseConfigList_Package() {
        String config = "com.android.test";
        Map<String, Set<String>> map = new HashMap<>();

        mService.parseConfigList(config, map);

        assertThat(map.get("com.android.test")).isEmpty();
    }

    @Test
    public void testParseConfigList_MultipleActivities() {
        String config = "com.android.test/.TestActivity0,com.android.test/.TestActivity1";
        Map<String, Set<String>> map = new HashMap<>();

        mService.parseConfigList(config, map);

        assertThat(map.get("com.android.test")).containsExactly(".TestActivity0", ".TestActivity1");
    }

    @Test
    public void testParseConfigList_PackageAndActivity() {
        String config = "com.android.test/.TestActivity0,com.android.test";
        Map<String, Set<String>> map = new HashMap<>();

        mService.parseConfigList(config, map);

        assertThat(map.get("com.android.test")).isEmpty();
    }

    @Test
    public void test_checkQueryPermission_noPermission() {
        mockQueryPermission(false);

        assertThat(mService.callerCanQueryPackage("blah")).isFalse();
    }

    @Test
    public void test_checkQueryPermission_correctPermission() {
        mockQueryPermission(true);

        assertThat(mService.callerCanQueryPackage("blah")).isTrue();
    }

    @Test
    public void test_checkQueryPermission_samePackage() {
        mockQueryPermission(false);

        assertThat(mService.callerCanQueryPackage(
                "com.android.car.carservice_unittest")).isTrue();
    }

    @Test
    public void testIsPendingIntentDistractionOptimised_withoutActivity() {
        when(mMockPendingIntent.isActivity()).thenReturn(false);

        assertThat(mService.isPendingIntentDistractionOptimized(mMockPendingIntent)).isFalse();
    }

    @Test
    public void testIsPendingIntentDistractionOptimised_noIntentComponents() {
        when(mMockPendingIntent.isActivity()).thenReturn(true);
        when(mMockPendingIntent.queryIntentComponents(MATCH_DEFAULT_ONLY)).thenReturn(
                new ArrayList<>());

        assertThat(mService.isPendingIntentDistractionOptimized(mMockPendingIntent)).isFalse();
    }

    @Test
    public void testGetTargetCarVersion_ok() {
        String pkgName = "dr.evil";
        CarVersion Version = CarVersion.forMajorAndMinorVersions(66, 6);

        doReturn(Version)
                .when(() -> CarPackageManagerService.getTargetCarVersion(mUserContext, pkgName));

        mockCallingUser();

        assertWithMessage("getTargetCarVersion(%s)", pkgName)
                .that(mService.getTargetCarVersion(pkgName)).isSameInstanceAs(Version);
    }

    @Test
    public void testGetTargetCarVersion_byUser() {
        String pkgName = "dr.evil";
        CarVersion Version = CarVersion.forMajorAndMinorVersions(66, 6);

        doReturn(Version)
                .when(() -> CarPackageManagerService.getTargetCarVersion(mUserContext, pkgName));

        mockCallingUser();

        assertWithMessage("getTargetCarVersion(%s)", pkgName)
                .that(mService.getTargetCarVersion(mUserHandle, pkgName))
                .isSameInstanceAs(Version);
    }

    @Test
    public void testGetTargetCarVersion_self_ok() throws Exception {
        String pkgName = "dr.evil";
        int myUid = Process.myUid();
        doReturn(myUid).when(mSpiedPackageManager).getPackageUidAsUser(eq(pkgName), anyInt());
        CarVersion Version = CarVersion.forMajorAndMinorVersions(66, 6);

        doReturn(Version)
                .when(() -> CarPackageManagerService.getTargetCarVersion(mUserContext, pkgName));

        mockCallingUser();

        assertWithMessage("getTargetCarVersion(%s)", pkgName)
                .that(mService.getSelfTargetCarVersion(pkgName)).isSameInstanceAs(Version);
    }

    @Test
    public void testGetTargetCarVersion_self_wrongUid() throws Exception {
        int myUid = Process.myUid();
        String pkgName = "dr.evil";
        doReturn(INVALID_UID).when(mSpiedPackageManager).getPackageUidAsUser(eq(pkgName), anyInt());
        CarVersion Version = CarVersion.forMajorAndMinorVersions(66, 6);

        doReturn(Version)
                .when(() -> CarPackageManagerService.getTargetCarVersion(mUserContext, pkgName));

        mockCallingUser();

        SecurityException e = assertThrows(SecurityException.class,
                () -> mService.getSelfTargetCarVersion(pkgName));

        String msg = e.getMessage();
        assertWithMessage("exception message (pkg)").that(msg).contains(pkgName);
        assertWithMessage("exception message (uid)").that(msg).contains(String.valueOf(myUid));
    }

    @Test
    public void testGetTargetCarVersion_static_null() {
        assertThrows(NullPointerException.class,
                () -> CarPackageManagerService.getTargetCarVersion(mUserContext, null));
    }

    @Test
    public void testGetTargetCarVersion_static_noPermission() {
        mockQueryPermission(/* granted= */ false);

        assertThrows(SecurityException.class,
                () -> CarPackageManagerService.getTargetCarVersion(mUserContext, "d.oh"));
    }


    @Test
    public void testGetTargetCarVersion_static_noApp() throws Exception {
        mockQueryPermission(/* granted= */ true);
        String causeMsg = mockGetApplicationInfoThrowsNotFound(mUserContext, "meaning.of.life");

        ServiceSpecificException e = assertThrows(ServiceSpecificException.class,
                () -> CarPackageManagerService.getTargetCarVersion(mUserContext,
                        "meaning.of.life"));
        assertWithMessage("exception code").that(e.errorCode).isEqualTo(ERROR_CODE_NO_PACKAGE);
        assertWithMessage("exception msg").that(e.getMessage()).isEqualTo(causeMsg);
    }

    // No need to test all scenarios, as they're tested by CarVersionParserParseMethodTest
    @Test
    public void testGetTargetCarVersion_static_ok() throws Exception {
        mockQueryPermission(/* granted= */ true);
        ApplicationInfo info = mockGetApplicationInfo(mUserContext, "meaning.of.life");
        info.targetSdkVersion = 666; // Set to make sure it's not used
        info.metaData = new Bundle();
        info.metaData.putString(MANIFEST_METADATA_TARGET_CAR_VERSION, "42:108");

        CarVersion actualVersion = CarPackageManagerService.getTargetCarVersion(
                mUserContext, "meaning.of.life");

        assertWithMessage("static getTargetCarVersion()").that(actualVersion).isNotNull();
        assertWithMessage("major version").that(actualVersion.getMajorVersion()).isEqualTo(42);
        assertWithMessage("minor version").that(actualVersion.getMinorVersion()).isEqualTo(108);
    }

    private void mockQueryPermission(boolean granted) {
        int result = android.content.pm.PackageManager.PERMISSION_DENIED;
        if (granted) {
            result = android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        doReturn(result).when(() -> ActivityManagerHelper.checkComponentPermission(
                eq(QUERY_ALL_PACKAGES), anyInt(), anyInt(), anyBoolean()));
        when(mUserContext.checkCallingOrSelfPermission(QUERY_ALL_PACKAGES)).thenReturn(result);
    }

    private void mockCallingUser() {
        doReturn(mUserHandle).when(() -> Binder.getCallingUserHandle());
    }

    private static String mockGetApplicationInfoThrowsNotFound(Context context, String packageName)
            throws NameNotFoundException {
        String msg = "D'OH!";
        PackageManager pm = mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(eq(packageName), any()))
                .thenThrow(new NameNotFoundException(msg));
        return msg;
    }

    private static ApplicationInfo mockGetApplicationInfo(Context context, String packageName)
            throws NameNotFoundException {
        ApplicationInfo info = new ApplicationInfo();
        PackageManager pm = mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(eq(packageName), any())).thenReturn(info);
        return info;
    }

    @Test
    public void registerBlockingUiCommandListenerThrowsException_withoutPermission() {
        applyPermission(PackageManager.PERMISSION_DENIED);
        ICarBlockingUiCommandListener carBlockingUiCommandListener =
                setupBlockingUiCommandListener();

        assertThrows(SecurityException.class,
                () -> mService.registerBlockingUiCommandListener(
                        carBlockingUiCommandListener, DEFAULT_DISPLAY));
    }

    @Test
    public void unregisterBlockingUiCommandListenerThrowsException_withoutPermission() {
        applyPermission(PackageManager.PERMISSION_DENIED);
        ICarBlockingUiCommandListener carBlockingUiCommandListener =
                setupBlockingUiCommandListener();

        assertThrows(SecurityException.class,
                () -> mService.unregisterBlockingUiCommandListener(carBlockingUiCommandListener));
    }

    @Test
    public void registerBlockingUiCommandListener() {
        applyPermission(PackageManager.PERMISSION_GRANTED);
        ICarBlockingUiCommandListener carBlockingUiCommandListener =
                setupBlockingUiCommandListener();

        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener, DEFAULT_DISPLAY);

        assertThat(mService.getCarBlockingUiCommandListenerRegisteredCallbacksForDisplay(
                DEFAULT_DISPLAY)).isEqualTo(1);
    }

    @Test
    public void unregisterBlockingUiCommandListener() {
        applyPermission(PackageManager.PERMISSION_GRANTED);
        ICarBlockingUiCommandListener carBlockingUiCommandListener =
                setupBlockingUiCommandListener();

        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener, DEFAULT_DISPLAY);
        mService.unregisterBlockingUiCommandListener(carBlockingUiCommandListener);

        assertThat(mService.getCarBlockingUiCommandListenerRegisteredCallbacksForDisplay(
                DEFAULT_DISPLAY)).isEqualTo(0);
    }

    @Test
    public void registerBlockingUiCommandListener_sameListenerNotRegisteredAgain() {
        applyPermission(PackageManager.PERMISSION_GRANTED);
        ICarBlockingUiCommandListener carBlockingUiCommandListener =
                setupBlockingUiCommandListener();

        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener, DEFAULT_DISPLAY);
        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener, DEFAULT_DISPLAY);

        assertThat(mService.getCarBlockingUiCommandListenerRegisteredCallbacksForDisplay(
                DEFAULT_DISPLAY)).isEqualTo(1);
    }

    @Test
    public void registerBlockingUiCommandListener_registerMultipleListeners() {
        applyPermission(PackageManager.PERMISSION_GRANTED);
        ICarBlockingUiCommandListener carBlockingUiCommandListener1 =
                setupBlockingUiCommandListener();
        ICarBlockingUiCommandListener carBlockingUiCommandListener2 =
                setupBlockingUiCommandListener();

        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener1, DEFAULT_DISPLAY);
        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener2, DEFAULT_DISPLAY);

        assertThat(mService.getCarBlockingUiCommandListenerRegisteredCallbacksForDisplay(
                DEFAULT_DISPLAY)).isEqualTo(2);
    }

    @Test
    public void registerMultipleListeners_finishBlockingUiInvoked()
            throws RemoteException {
        applyPermission(PackageManager.PERMISSION_GRANTED);
        ICarBlockingUiCommandListener carBlockingUiCommandListener1 =
                setupBlockingUiCommandListener();
        ICarBlockingUiCommandListener carBlockingUiCommandListener2 =
                setupBlockingUiCommandListener();
        ActivityManager.RunningTaskInfo taskInfo = createTask();
        mockLastKnownDisplayId(taskInfo, DEFAULT_DISPLAY);

        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener1, DEFAULT_DISPLAY);
        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener2, DEFAULT_DISPLAY);
        mService.finishBlockingUi(taskInfo);

        verify(carBlockingUiCommandListener1).finishBlockingUi();
        verify(carBlockingUiCommandListener2).finishBlockingUi();
    }

    @Test
    public void registerListenerForOtherDisplay_finishBlockingUiNotInvoked()
            throws RemoteException {
        applyPermission(PackageManager.PERMISSION_GRANTED);
        ICarBlockingUiCommandListener carBlockingUiCommandListener =
                setupBlockingUiCommandListener();
        ActivityManager.RunningTaskInfo taskInfo = createTask();
        int tempDisplayId = 1;
        mockLastKnownDisplayId(taskInfo, tempDisplayId);

        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener, tempDisplayId);
        mService.finishBlockingUi(taskInfo);

        verify(carBlockingUiCommandListener, times(0)).finishBlockingUi();
    }

    @Test
    public void registerMultipleListenersForDifferentDisplay_finishBlockingUiInvokedForSomeDisplay()
            throws RemoteException {
        applyPermission(PackageManager.PERMISSION_GRANTED);
        ICarBlockingUiCommandListener carBlockingUiCommandListener1 =
                setupBlockingUiCommandListener();
        ICarBlockingUiCommandListener carBlockingUiCommandListener2 =
                setupBlockingUiCommandListener();
        ICarBlockingUiCommandListener carBlockingUiCommandListener3 =
                setupBlockingUiCommandListener();
        ActivityManager.RunningTaskInfo taskInfo = createTask();
        int tempDisplayId = 1;
        mockLastKnownDisplayId(taskInfo, tempDisplayId);

        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener1, DEFAULT_DISPLAY);
        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener2, DEFAULT_DISPLAY);
        mService.registerBlockingUiCommandListener(carBlockingUiCommandListener3, tempDisplayId);
        mService.finishBlockingUi(taskInfo);

        verify(carBlockingUiCommandListener1).finishBlockingUi();
        verify(carBlockingUiCommandListener2).finishBlockingUi();
        verify(carBlockingUiCommandListener3, times(0)).finishBlockingUi();
    }

    private ActivityManager.RunningTaskInfo createTask() {
        ActivityManager.RunningTaskInfo taskInfo =
                new ActivityManager.RunningTaskInfo();
        taskInfo.taskId = 1;
        taskInfo.displayId = DEFAULT_DISPLAY;
        return taskInfo;
    }

    private ICarBlockingUiCommandListener setupBlockingUiCommandListener() {
        ICarBlockingUiCommandListener carBlockingUiCommandListener =
                mock(ICarBlockingUiCommandListener.class);
        IBinder tempToken = new Binder();
        when(carBlockingUiCommandListener.asBinder()).thenReturn(tempToken);
        return carBlockingUiCommandListener;
    }

    private void applyPermission(int permissionValue) {
        doReturn(permissionValue).when(mSpiedContext).checkCallingOrSelfPermission(
                PERMISSION_REGISTER_CAR_SYSTEM_UI_PROXY);
    }

    private void mockLastKnownDisplayId(ActivityManager.RunningTaskInfo taskInfo,
            int displayId) {
        SparseIntArray tempLastKnownDisplayIdForTask = new SparseIntArray();
        tempLastKnownDisplayIdForTask.put(taskInfo.taskId, displayId);
        when(mMockActivityService.getLastKnownDisplayIdForTask(taskInfo.taskId)).thenReturn(
                displayId);
    }
}
