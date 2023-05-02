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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.remoteaccess.CarRemoteAccessManager;
import android.car.remoteaccess.CarRemoteAccessManager.RemoteTaskClientCallback;
import android.car.remoteaccess.RemoteTaskClientRegistrationInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.automotive.remoteaccess.ApState;
import android.hardware.automotive.remoteaccess.IRemoteAccess;
import android.hardware.automotive.remoteaccess.IRemoteTaskCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.car.ICarImpl;
import com.android.car.MockedCarTestBase;
import com.android.car.systeminterface.SystemStateInterface;
import com.android.compatibility.common.util.PollingCheck;
import com.android.internal.annotations.GuardedBy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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
    private static final String TEST_VEHICLE_ID = "TEST_VIN";
    private static final String TEST_WAKEUP_SERVICE_NAME = "TEST_GOOGLE_WAKEUP_SERVICE";
    private static final String TEST_PROCESSOR_ID = "TEST_PROCESSOR_ID";
    private static final byte[] TEST_DATA = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE,
            (byte) 0xEF};
    private static final byte[] TEST_DATA_2 = new byte[]{(byte) 0xBE, (byte) 0xEF};

    private Executor mExecutor = Executors.newSingleThreadExecutor();
    private CarRemoteAccessManager mCarRemoteAccesesManager;
    @Mock
    private IRemoteAccess mRemoteAccessHal;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private RemoteTaskClientCallback mRemoteTaskClientCallback;
    private final MockSystemStateInterface mSystemStateInterface = new MockSystemStateInterface();

    @Captor
    private ArgumentCaptor<ApState> mApStateCaptor;
    @Captor
    private ArgumentCaptor<IRemoteTaskCallback> mRemoteTaskCallbackCaptor;
    @Captor
    private ArgumentCaptor<RemoteTaskClientRegistrationInfo> mRegistrationInfoCaptor;
    @Captor
    private ArgumentCaptor<String> mStringCaptor;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Set<ComponentName> mRemoteTaskClientServices = new ArraySet<>();

    private final class MyMockedCarTestContext extends MockedCarTestContext {
        MyMockedCarTestContext(Context base) {
            super(base);

            when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
                    .thenReturn(true);
            when(mPackageManager.queryIntentServicesAsUser(any(), anyInt(), any())).thenReturn(
                    List.of(newResolveInfo(PACKAGE_NAME_1, SERVICE_NAME_1, 0),
                    newResolveInfo(PACKAGE_NAME_2, SERVICE_NAME_2, 9),
                    newResolveInfo(NO_PERMISSION_PACKAGE, SERVICE_NAME_1, 0)));
            when(mPackageManager.checkPermission(Car.PERMISSION_USE_REMOTE_ACCESS, PACKAGE_NAME_1))
                    .thenReturn(PackageManager.PERMISSION_GRANTED);
            when(mPackageManager.checkPermission(Car.PERMISSION_USE_REMOTE_ACCESS, PACKAGE_NAME_2))
                    .thenReturn(PackageManager.PERMISSION_GRANTED);
            when(mPackageManager.checkPermission(Car.PERMISSION_USE_REMOTE_ACCESS,
                    NO_PERMISSION_PACKAGE)).thenReturn(PackageManager.PERMISSION_DENIED);
            when(mPackageManager.checkPermission(Car.PERMISSION_CONTROL_REMOTE_ACCESS,
                    PACKAGE_NAME_1)).thenReturn(PackageManager.PERMISSION_GRANTED);
            when(mPackageManager.checkPermission(Car.PERMISSION_CONTROL_REMOTE_ACCESS,
                    PACKAGE_NAME_2)).thenReturn(PackageManager.PERMISSION_GRANTED);
            when(mPackageManager.checkPermission(Car.PERMISSION_CONTROL_REMOTE_ACCESS,
                    NO_PERMISSION_PACKAGE)).thenReturn(PackageManager.PERMISSION_DENIED);
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
                UserHandle user) {
            ComponentName component = service.getComponent();
            String packageName = component.getPackageName();
            if (!packageName.equals(PACKAGE_NAME_1) && !packageName.equals(PACKAGE_NAME_2)
                    && !packageName.equals(NO_PERMISSION_PACKAGE)) {
                // Filter out requests that we do not care.
                return true;
            }
            conn.onNullBinding(component);
            synchronized (mLock) {
                mRemoteTaskClientServices.add(component);
            }
            return true;
        }

        @Override
        public void unbindService(ServiceConnection conn) {}
    }

    private Set<ComponentName> getRemoteTaskClientServices() {
        synchronized (mLock) {
            return new ArraySet<>(mRemoteTaskClientServices);
        }
    }

    @Override
    protected MockedCarTestContext createMockedCarTestContext(Context context) {
        return new MyMockedCarTestContext(context);
    }

    static final class MockSystemStateInterface implements SystemStateInterface {
        private final Object mActionLock = new Object();
        @GuardedBy("mActionLock")
        private final List<Runnable> mActions = new ArrayList<>();

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
            synchronized (mActionLock) {
                mActions.add(action);
            }
        }

        public void runPostBootActions() {
            synchronized (mActionLock) {
                for (Runnable action : mActions) {
                    action.run();
                }
            }
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
    protected void configureResourceOverrides(MockResources resources) {
        super.configureResourceOverrides(resources);
        // Enable remote access feature.
        resources.overrideResource(com.android.car.R.array.config_allowed_optional_car_features,
                new String[] {Car.CAR_REMOTE_ACCESS_SERVICE});
        resources.overrideResource(
                com.android.car.R.integer.config_allowedSystemUptimeForRemoteAccess, 300);
    }

    @Override
    protected void spyOnBeforeCarImplInit(ICarImpl carImpl) {
        try {
            when(mRemoteAccessHal.getVehicleId()).thenReturn(TEST_VEHICLE_ID);
            when(mRemoteAccessHal.getWakeupServiceName()).thenReturn(TEST_WAKEUP_SERVICE_NAME);
            when(mRemoteAccessHal.getProcessorId()).thenReturn(TEST_PROCESSOR_ID);
        } catch (RemoteException e) {
            // Must not happen.
        }
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

        verify(mRemoteAccessHal, timeout(DEFAULT_TIME_OUT_MS)).setRemoteTaskCallback(any());
    }


    @Test
    public void testInitialBindRemoteTaskClientService() throws Exception {
        mSystemStateInterface.runPostBootActions();

        verify(mPackageManager, timeout(DEFAULT_TIME_OUT_MS)).queryIntentServicesAsUser(
                argThat(intent -> intent.getAction()
                        .equals(Car.CAR_REMOTEACCESS_REMOTE_TASK_CLIENT_SERVICE)),
                eq(0), eq(UserHandle.SYSTEM));
        PollingCheck.check("remote task client services are not started", DEFAULT_TIME_OUT_MS,
                () -> getRemoteTaskClientServices().size() >= 2);
        assertThat(getRemoteTaskClientServices()).isEqualTo(new ArraySet<>(Arrays.asList(
                new ComponentName[] {
                        new ComponentName(PACKAGE_NAME_1, SERVICE_NAME_1),
                        new ComponentName(PACKAGE_NAME_2, SERVICE_NAME_2)}
                )));
    }

    @Test
    public void testSetRemoteTaskClientGetRegistrationInfo() throws Exception {
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(SERVICE_NAME_1);

        mCarRemoteAccesesManager.setRemoteTaskClient(mExecutor, mRemoteTaskClientCallback);

        verify(mRemoteTaskClientCallback, timeout(DEFAULT_TIME_OUT_MS)).onRegistrationUpdated(
                mRegistrationInfoCaptor.capture());
        RemoteTaskClientRegistrationInfo registrationInfo = mRegistrationInfoCaptor.getValue();
        assertThat(registrationInfo).isNotNull();
        assertThat(registrationInfo.getServiceId()).isEqualTo(TEST_WAKEUP_SERVICE_NAME);
        assertThat(registrationInfo.getVehicleId()).isEqualTo(TEST_VEHICLE_ID);
        assertThat(registrationInfo.getProcessorId()).isEqualTo(TEST_PROCESSOR_ID);
        assertThat(registrationInfo.getClientId()).isNotNull();
        assertThat(registrationInfo.getClientId()).isNotEqualTo("");
    }

    @Test
    public void testSetRemoteTaskClientGetRegistrationInfo_sameClientIdForSameUid()
            throws Exception {
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(SERVICE_NAME_1);

        mCarRemoteAccesesManager.setRemoteTaskClient(mExecutor, mRemoteTaskClientCallback);

        verify(mRemoteTaskClientCallback, timeout(DEFAULT_TIME_OUT_MS)).onRegistrationUpdated(
                mRegistrationInfoCaptor.capture());
        RemoteTaskClientRegistrationInfo registrationInfo = mRegistrationInfoCaptor.getValue();
        String oldClientId = registrationInfo.getClientId();

        mCarRemoteAccesesManager.clearRemoteTaskClient();

        mCarRemoteAccesesManager.setRemoteTaskClient(mExecutor, mRemoteTaskClientCallback);

        clearInvocations(mRemoteTaskClientCallback);
        verify(mRemoteTaskClientCallback, timeout(DEFAULT_TIME_OUT_MS)).onRegistrationUpdated(
                mRegistrationInfoCaptor.capture());
        registrationInfo = mRegistrationInfoCaptor.getValue();
        String newClientId = registrationInfo.getClientId();

        assertThat(newClientId).isEqualTo(oldClientId);
    }

    @Test
    public void testSetRemoteTaskClientGetRegistrationInfo_diffClientIdForDiffUid()
            throws Exception {
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(SERVICE_NAME_1);

        mCarRemoteAccesesManager.setRemoteTaskClient(mExecutor, mRemoteTaskClientCallback);

        verify(mRemoteTaskClientCallback, timeout(DEFAULT_TIME_OUT_MS)).onRegistrationUpdated(
                mRegistrationInfoCaptor.capture());
        RemoteTaskClientRegistrationInfo registrationInfo = mRegistrationInfoCaptor.getValue();
        String oldClientId = registrationInfo.getClientId();

        mCarRemoteAccesesManager.clearRemoteTaskClient();

        when(mPackageManager.getNameForUid(anyInt())).thenReturn(SERVICE_NAME_2);

        mCarRemoteAccesesManager.setRemoteTaskClient(mExecutor, mRemoteTaskClientCallback);

        clearInvocations(mRemoteTaskClientCallback);
        verify(mRemoteTaskClientCallback, timeout(DEFAULT_TIME_OUT_MS)).onRegistrationUpdated(
                mRegistrationInfoCaptor.capture());
        registrationInfo = mRegistrationInfoCaptor.getValue();
        String newClientId = registrationInfo.getClientId();

        assertWithMessage("Client ID must be different for different UID").that(newClientId)
                .isNotEqualTo(oldClientId);
    }

    private IRemoteTaskCallback getRemoteAccessHalCallback() throws Exception {
        verify(mRemoteAccessHal, timeout(DEFAULT_TIME_OUT_MS)).setRemoteTaskCallback(
                mRemoteTaskCallbackCaptor.capture());
        return mRemoteTaskCallbackCaptor.getValue();
    }

    @Test
    public void testRemoteTaskDeliveredToClient_taskArriveAfterClientRegistration()
            throws Exception {
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(SERVICE_NAME_1);
        IRemoteTaskCallback remoteAccessHalCallback = getRemoteAccessHalCallback();

        // Wait for the remote task client services to be started.
        mSystemStateInterface.runPostBootActions();
        PollingCheck.check("remote task client services are not started", DEFAULT_TIME_OUT_MS,
                () -> getRemoteTaskClientServices().size() >= 2);

        // During init, the service will register itself.
        mCarRemoteAccesesManager.setRemoteTaskClient(mExecutor, mRemoteTaskClientCallback);
        verify(mRemoteTaskClientCallback, timeout(DEFAULT_TIME_OUT_MS)).onRegistrationUpdated(
                mRegistrationInfoCaptor.capture());
        String clientId = mRegistrationInfoCaptor.getValue().getClientId();

        // Simulates a remote task arrive for this client.
        remoteAccessHalCallback.onRemoteTaskRequested(clientId, TEST_DATA);

        verify(mRemoteTaskClientCallback, timeout(DEFAULT_TIME_OUT_MS)).onRemoteTaskRequested(
                mStringCaptor.capture(), eq(TEST_DATA), anyInt());
        String taskId1 = mStringCaptor.getValue();

        // Simulates another remote task arrive.
        remoteAccessHalCallback.onRemoteTaskRequested(clientId, TEST_DATA_2);

        verify(mRemoteTaskClientCallback, timeout(DEFAULT_TIME_OUT_MS)).onRemoteTaskRequested(
                mStringCaptor.capture(), eq(TEST_DATA_2), anyInt());
        String taskId2 = mStringCaptor.getValue();

        assertWithMessage("Task ID must not duplicate").that(taskId2).isNotEqualTo(taskId1);
    }

    @Test
    public void testRemoteTaskDeliveredToClient_taskArriveBeforePackageDiscovery()
            throws Exception {
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(SERVICE_NAME_1);
        IRemoteTaskCallback remoteAccessHalCallback = getRemoteAccessHalCallback();

        // This simulates a previous registration process when the remote task server registered
        // the client ID.
        RemoteTaskClientCallback tmpRemoteTaskClientCallback = mock(RemoteTaskClientCallback.class);
        mCarRemoteAccesesManager.setRemoteTaskClient(mExecutor, tmpRemoteTaskClientCallback);
        verify(tmpRemoteTaskClientCallback, timeout(DEFAULT_TIME_OUT_MS)).onRegistrationUpdated(
                mRegistrationInfoCaptor.capture());
        String clientId = mRegistrationInfoCaptor.getValue().getClientId();
        mCarRemoteAccesesManager.clearRemoteTaskClient();

        // Simulates a new reboot and a remote task arrives on boot.
        remoteAccessHalCallback.onRemoteTaskRequested(clientId, TEST_DATA);

        // This should trigger the binding for remote task client services.
        mSystemStateInterface.runPostBootActions();
        PollingCheck.check("remote task client services are not started", DEFAULT_TIME_OUT_MS,
                () ->  getRemoteTaskClientServices().size() >= 2);
        mCarRemoteAccesesManager.setRemoteTaskClient(mExecutor, mRemoteTaskClientCallback);

        // Task must arrive after the client service is started and registered.
        verify(mRemoteTaskClientCallback, timeout(DEFAULT_TIME_OUT_MS)).onRemoteTaskRequested(any(),
                eq(TEST_DATA), anyInt());
    }
}
