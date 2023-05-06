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

package com.android.car.remoteaccess;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.app.Service;
import android.car.Car;
import android.car.remoteaccess.CarRemoteAccessManager;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.automotive.remoteaccess.ApState;
import android.hardware.automotive.remoteaccess.IRemoteAccess;
import android.os.IBinder;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.car.ICarImpl;
import com.android.car.MockedCarTestBase;
import com.android.car.systeminterface.SystemStateInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.time.Duration;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class CarRemoteAccessManagerTest extends MockedCarTestBase {
    private static final String TAG = CarRemoteAccessManagerTest.class.getSimpleName();

    private static final int DEFAULT_TIME_OUT_MS = 10_000;

    private static final String PACKAGE_NAME_1 = "android.car.package1";
    private static final String PACKAGE_NAME_2 = "android.car.package2";
    private static final String SERVICE_NAME_1 = "android.car.package1:service1";
    private static final String SERVICE_NAME_2 = "android.car.package2:service2";
    private static final String NO_PERMISSION_PACKAGE = "android.car.nopermission";

    private CarRemoteAccessManager mCarRemoteAccesesManager;
    @Mock
    private IRemoteAccess mRemoteAccessHal;
    private PackageManager mPackageManager;
    private final MockSystemStateInterface mSystemStateInterface = new MockSystemStateInterface();

    @Captor
    private ArgumentCaptor<ApState> mApStateCaptor;

    private final class TestRemoteTaskClientService extends Service {
        private String mServiceName;

        @Override
        public IBinder onBind(Intent intent) {
            mServiceName = intent.getStringExtra("component");
            return null;
        }
    }

    private final class MyMockedCarTestContext extends MockedCarTestContext {
        MyMockedCarTestContext(Context base) {
            super(base);
        }

        @Override
        public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
                UserHandle user) {
            Intent intent = new Intent();
            intent.setClassName(this, TestRemoteTaskClientService.class.getName());
            intent.putExtra("component", service.getComponent());
            super.bindServiceAsUser(intent, conn, flags, user);
            return true;
        }
    }

    @Override
    protected MockedCarTestContext createMockedCarTestContext(Context context) {
        return new MyMockedCarTestContext(context);
    }

    static final class MockSystemStateInterface implements SystemStateInterface {
        private Runnable mAction;

        @Override
        public void shutdown() {}

        @Override
        public boolean enterDeepSleep() {
            return true;
        }

        @Override
        public boolean enterHibernation() {
            return true;
        }

        @Override
        public void scheduleActionForBootCompleted(Runnable action, Duration delay) {
            mAction = action;
        }

        public Runnable getAction() {
            return mAction;
        }
    }

    @Override
    protected SystemStateInterface createMockSystemStateInterface() {
        return mSystemStateInterface;
    }

    private ResolveInfo newResolveInfo(String packageName, String serviceName, int uid) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.packageName = packageName;
        resolveInfo.serviceInfo.name = serviceName;
        resolveInfo.serviceInfo.applicationInfo = new ApplicationInfo();
        resolveInfo.serviceInfo.applicationInfo.uid = uid;
        return resolveInfo;
    }

    @Override
    protected void configureFakeSystemInterface() {
        // We need to do this before ICarImpl.init and after fake system interface is set.
        setCarRemoteAccessService(new CarRemoteAccessService(getContext(),
                getFakeSystemInterface(), /* powerHalService= */ null, /* dep= */ null,
                /* remoteAccessHal= */ mRemoteAccessHal,
                /* remoteAccessStorage= */ null, /* allowedSystemUptimeMs= */ -1,
                /* inMemoryStorage= */ true));
    }

    @Override
    protected void spyOnBeforeCarImplInit(ICarImpl carImpl) {
        mPackageManager = getContext().getPackageManager();
        spyOn(mPackageManager);

        doReturn(List.of(newResolveInfo(PACKAGE_NAME_1, SERVICE_NAME_1, 0),
                newResolveInfo(PACKAGE_NAME_2, SERVICE_NAME_2, 9),
                newResolveInfo(NO_PERMISSION_PACKAGE, SERVICE_NAME_1, 0))).when(mPackageManager)
                .queryIntentServicesAsUser(any(), anyInt(), any());
        doReturn(PackageManager.PERMISSION_GRANTED).when(mPackageManager).checkPermission(
                Car.PERMISSION_USE_REMOTE_ACCESS, PACKAGE_NAME_1);
        doReturn(PackageManager.PERMISSION_GRANTED).when(mPackageManager).checkPermission(
                Car.PERMISSION_USE_REMOTE_ACCESS, PACKAGE_NAME_2);
        doReturn(PackageManager.PERMISSION_DENIED).when(mPackageManager).checkPermission(
                Car.PERMISSION_USE_REMOTE_ACCESS, NO_PERMISSION_PACKAGE);
    }

    @Override
    protected void configureResourceOverrides(MockResources resources) {
        super.configureResourceOverrides(resources);
        // Enable remote access feature.
        resources.overrideResource(com.android.car.R.array.config_allowed_optional_car_features,
                new String[] {Car.CAR_REMOTE_ACCESS_SERVICE});
        resources.overrideResource(
                com.android.car.R.integer.config_allowedSystemUptimeForRemoteAccess, 300);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mCarRemoteAccesesManager = (CarRemoteAccessManager) getCar().getCarManager(
                Car.CAR_REMOTE_ACCESS_SERVICE);
        assertThat(mCarRemoteAccesesManager).isNotNull();
    }

    @Test
    public void testNotifyApInitialState() throws Exception {
        verify(mRemoteAccessHal, timeout(DEFAULT_TIME_OUT_MS)).notifyApStateChange(
                mApStateCaptor.capture());
        ApState apState = mApStateCaptor.getValue();

        assertThat(apState.isReadyForRemoteTask).isTrue();
        assertThat(apState.isWakeupRequired).isFalse();
    }
}
