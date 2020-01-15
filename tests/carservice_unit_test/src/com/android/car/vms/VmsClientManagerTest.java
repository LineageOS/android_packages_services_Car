/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.vms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.vms.IVmsPublisherClient;
import android.car.vms.IVmsPublisherService;
import android.car.vms.IVmsSubscriberClient;
import android.car.vms.VmsAvailableLayers;
import android.car.vms.VmsLayer;
import android.car.vms.VmsSubscriptionState;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.filters.SmallTest;

import com.android.car.VmsPublisherService;
import com.android.car.hal.VmsHalService;
import com.android.car.stats.CarStatsService;
import com.android.car.stats.VmsClientLogger;
import com.android.car.stats.VmsClientLogger.ConnectionState;
import com.android.car.user.CarUserService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
public class VmsClientManagerTest {
    private static final String SYSTEM_CLIENT = "com.google.android.apps.vms.test/.VmsSystemClient";
    private static final ComponentName SYSTEM_CLIENT_COMPONENT =
            ComponentName.unflattenFromString(SYSTEM_CLIENT);
    private static final String SYSTEM_CLIENT_NAME =
            "com.google.android.apps.vms.test/com.google.android.apps.vms.test.VmsSystemClient U=0";

    private static final String USER_CLIENT = "com.google.android.apps.vms.test/.VmsUserClient";
    private static final ComponentName USER_CLIENT_COMPONENT =
            ComponentName.unflattenFromString(USER_CLIENT);
    private static final int USER_ID = 10;
    private static final String USER_CLIENT_NAME =
            "com.google.android.apps.vms.test/com.google.android.apps.vms.test.VmsUserClient U=10";
    private static final int USER_ID_U11 = 11;

    private static final String TEST_PACKAGE = "test.package1";
    private static final String HAL_CLIENT_NAME = "HalClient";
    private static final String UNKNOWN_PACKAGE = "UnknownPackage";

    private static final int TEST_APP_ID = 12345;
    private static final int TEST_SYSTEM_UID = 12345;
    private static final int TEST_USER_UID = 1012345;
    private static final int TEST_USER_UID_U11 = 1112345;

    private static final long MILLIS_BEFORE_REBIND = 100;

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Resources mResources;
    @Mock
    private CarStatsService mStatsService;
    @Mock
    private UserManager mUserManager;
    @Mock
    private CarUserService mUserService;

    @Mock
    private VmsBrokerService mBrokerService;

    @Mock
    private VmsHalService mHal;

    @Mock
    private Handler mHandler;

    @Captor
    private ArgumentCaptor<Runnable> mRebindCaptor;

    @Mock
    private VmsPublisherService mPublisherService;

    @Mock
    private IVmsSubscriberClient mSubscriberClient1;
    @Mock
    private Binder mSubscriberBinder1;

    @Captor
    private ArgumentCaptor<IBinder.DeathRecipient> mDeathRecipient;

    @Mock
    private IVmsSubscriberClient mSubscriberClient2;
    @Mock
    private Binder mSubscriberBinder2;

    @Captor
    private ArgumentCaptor<ServiceConnection> mConnectionCaptor;

    @Mock
    private VmsClientLogger mSystemClientLog;
    @Mock
    private VmsClientLogger mUserClientLog;
    @Mock
    private VmsClientLogger mUserClientLog2;
    @Mock
    private VmsClientLogger mHalClientLog;

    private VmsClientManager mClientManager;

    private int mForegroundUserId;
    private int mCallingAppUid;

    private ServiceInfo mSystemServiceInfo;
    private ServiceInfo mUserServiceInfo;

    @Before
    public void setUp() throws Exception {
        resetContext();
        mSystemServiceInfo = new ServiceInfo();
        mSystemServiceInfo.permission = Car.PERMISSION_BIND_VMS_CLIENT;
        mSystemServiceInfo.applicationInfo = new ApplicationInfo();
        mSystemServiceInfo.applicationInfo.uid = TEST_APP_ID;
        when(mPackageManager.getServiceInfo(eq(SYSTEM_CLIENT_COMPONENT), anyInt()))
                .thenReturn(mSystemServiceInfo);
        when(mStatsService.getVmsClientLogger(TEST_SYSTEM_UID)).thenReturn(mSystemClientLog);

        mUserServiceInfo = new ServiceInfo();
        mUserServiceInfo.permission = Car.PERMISSION_BIND_VMS_CLIENT;
        mUserServiceInfo.applicationInfo = new ApplicationInfo();
        mUserServiceInfo.applicationInfo.uid = TEST_APP_ID;
        when(mPackageManager.getServiceInfo(eq(USER_CLIENT_COMPONENT), anyInt()))
                .thenReturn(mUserServiceInfo);
        when(mStatsService.getVmsClientLogger(TEST_USER_UID)).thenReturn(mUserClientLog);
        when(mStatsService.getVmsClientLogger(TEST_USER_UID_U11)).thenReturn(mUserClientLog2);

        when(mStatsService.getVmsClientLogger(Process.myUid())).thenReturn(mHalClientLog);

        when(mResources.getInteger(
                com.android.car.R.integer.millisecondsBeforeRebindToVmsPublisher)).thenReturn(
                (int) MILLIS_BEFORE_REBIND);
        when(mResources.getStringArray(
                com.android.car.R.array.vmsPublisherSystemClients)).thenReturn(
                new String[]{ SYSTEM_CLIENT });
        when(mResources.getStringArray(
                com.android.car.R.array.vmsPublisherUserClients)).thenReturn(
                new String[]{ USER_CLIENT });

        when(mContext.getSystemService(eq(Context.USER_SERVICE))).thenReturn(mUserManager);

        mClientManager = new VmsClientManager(mContext, mStatsService, mUserService,
                mBrokerService, mHal, mHandler, () -> mCallingAppUid);
        verify(mHal).setClientManager(mClientManager);
        mClientManager.setPublisherService(mPublisherService);

        notifyUserSwitched(USER_ID, false);
        mCallingAppUid = UserHandle.getUid(USER_ID, 0);

        when(mSubscriberClient1.asBinder()).thenReturn(mSubscriberBinder1);
        when(mSubscriberClient2.asBinder()).thenReturn(mSubscriberBinder2);

        when(mPackageManager.getNameForUid(mCallingAppUid)).thenReturn(TEST_PACKAGE);
    }

    @After
    public void tearDown() {
        verify(mContext, atLeast(0)).getSystemService(eq(Context.USER_SERVICE));
        verify(mContext, atLeast(0)).getResources();
        verify(mContext, atLeast(0)).getPackageManager();
        verifyNoMoreInteractions(mContext, mBrokerService, mHal, mPublisherService, mHandler);
        verifyNoMoreInteractions(mSystemClientLog, mUserClientLog, mUserClientLog2, mHalClientLog);
    }

    @Test
    public void testInit() {
        mClientManager.init();

        // Verify registration of system user unlock listener
        verify(mUserService).runOnUser0Unlock(mClientManager.mSystemUserUnlockedListener);
        // Verify user callback is added
        verify(mUserService).addUserCallback(eq(mClientManager.mUserCallback));
    }

    @Test
    public void testRelease() {
        mClientManager.release();

        // Verify user switch receiver is unregistered
        verify(mUserService).removeUserCallback(mClientManager.mUserCallback);
    }

    @Test
    public void testSystemUserUnlocked() {
        notifySystemUserUnlocked();
        notifySystemUserUnlocked();

        // Multiple events should only trigger a single bind, when successful
        verifySystemBind(1);
    }

    @Test
    public void testSystemUserUnlocked_ClientNotFound() throws Exception {
        when(mPackageManager.getServiceInfo(eq(SYSTEM_CLIENT_COMPONENT), anyInt()))
                .thenThrow(new PackageManager.NameNotFoundException());
        notifySystemUserUnlocked();

        // Process will not be bound
        verifySystemBind(0);
    }

    @Test
    public void testSystemUserUnlocked_WrongPermission() throws Exception {
        mSystemServiceInfo.permission = Car.PERMISSION_VMS_PUBLISHER;
        notifySystemUserUnlocked();

        // Process will not be bound
        verifySystemBind(0);
        verify(mSystemClientLog).logConnectionState(ConnectionState.CONNECTION_ERROR);
    }

    @Test
    public void testSystemUserUnlocked_BindFailed() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), any())).thenReturn(false);
        notifySystemUserUnlocked();
        notifySystemUserUnlocked();

        // Failure state will trigger another attempt on event
        verifySystemBind(2);
        verify(mSystemClientLog, times(2)).logConnectionState(ConnectionState.CONNECTION_ERROR);
    }

    @Test
    public void testSystemUserUnlocked_BindException() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), any())).thenThrow(
                new SecurityException());
        notifySystemUserUnlocked();
        notifySystemUserUnlocked();

        // Failure state will trigger another attempt on event
        verifySystemBind(2);
        verify(mSystemClientLog, times(2)).logConnectionState(ConnectionState.CONNECTION_ERROR);
    }

    @Test
    public void testUserUnlocked() {
        notifyUserUnlocked(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        // Multiple events should only trigger a single bind, when successful
        verifyUserBind(1);
    }

    @Test
    public void testUserUnlocked_ForegroundUserNotUnlocked() {
        notifyUserUnlocked(USER_ID, false);

        // Process will not be bound
        verifyUserBind(0);
    }

    @Test
    public void testUserUnlocked_OtherUserUnlocked() {
        notifyUserUnlocked(USER_ID_U11, true);

        // Process will not be bound
        verifyUserBind(0);
    }

    @Test
    public void testUserUnlocked_ClientNotFound() throws Exception {
        when(mPackageManager.getServiceInfo(eq(USER_CLIENT_COMPONENT), anyInt()))
                .thenThrow(new PackageManager.NameNotFoundException());
        notifyUserUnlocked(USER_ID, true);

        // Process will not be bound
        verifyUserBind(0);
    }

    @Test
    public void testUserUnlocked_WrongPermission() throws Exception {
        mUserServiceInfo.permission = Car.PERMISSION_VMS_PUBLISHER;
        notifyUserUnlocked(USER_ID, true);

        // Process will not be bound
        verifyUserBind(0);
        verify(mUserClientLog).logConnectionState(ConnectionState.CONNECTION_ERROR);
    }

    @Test
    public void testUserUnlocked_BindFailed() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), any()))
                .thenReturn(false);
        notifyUserUnlocked(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        // Failure state will trigger another attempt
        verifyUserBind(2);
        verify(mUserClientLog, times(2)).logConnectionState(ConnectionState.CONNECTION_ERROR);
    }

    @Test
    public void testUserUnlocked_UserBindFailed() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.of(USER_ID))))
                .thenReturn(false);
        notifyUserUnlocked(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        // Failure state will trigger another attempt
        verifyUserBind(2);
        verify(mUserClientLog, times(2)).logConnectionState(ConnectionState.CONNECTION_ERROR);
    }

    @Test
    public void testUserUnlocked_BindException() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), any()))
                .thenThrow(new SecurityException());
        notifyUserUnlocked(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        // Failure state will trigger another attempt
        verifyUserBind(2);
        verify(mUserClientLog, times(2)).logConnectionState(ConnectionState.CONNECTION_ERROR);
    }

    @Test
    public void testUserUnlocked_SystemRebind() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.SYSTEM)))
                .thenReturn(false);
        notifySystemUserUnlocked();
        verifySystemBind(1);
        verify(mSystemClientLog).logConnectionState(ConnectionState.CONNECTION_ERROR);
        resetContext();

        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.SYSTEM)))
                .thenReturn(true);
        notifyUserUnlocked(USER_ID, true);
        verifySystemBind(1);
        verifyUserBind(1);
    }

    @Test
    public void testUserUnlocked_SystemRebind_BindFailed() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.SYSTEM)))
                .thenReturn(false);
        notifySystemUserUnlocked();
        verifySystemBind(1);
        verify(mSystemClientLog).logConnectionState(ConnectionState.CONNECTION_ERROR);
        resetContext();

        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.SYSTEM)))
                .thenReturn(false);
        notifyUserUnlocked(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        verifySystemBind(2); // Failure state will trigger another attempt
        verify(mSystemClientLog, times(2)).logConnectionState(ConnectionState.CONNECTION_ERROR);
        verifyUserBind(1);
    }

    @Test
    public void testUserUnlocked_SystemRebind_BindException() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.SYSTEM)))
                .thenThrow(new SecurityException());
        notifySystemUserUnlocked();
        verifySystemBind(1);
        verify(mSystemClientLog).logConnectionState(ConnectionState.CONNECTION_ERROR);
        resetContext();

        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.SYSTEM)))
                .thenThrow(new SecurityException());
        notifyUserUnlocked(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        verifySystemBind(2); // Failure state will trigger another attempt
        verify(mSystemClientLog, times(2)).logConnectionState(ConnectionState.CONNECTION_ERROR);
        verifyUserBind(1);
    }

    @Test
    public void testUserSwitched() {
        notifyUserSwitched(USER_ID, true);
        notifyUserSwitched(USER_ID, true);

        // Multiple events should only trigger a single bind, when successful
        verifyUserBind(1);
    }

    @Test
    public void testUserSwitchedAndUnlocked() {
        notifyUserSwitched(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        // Multiple events should only trigger a single bind, when successful
        verifyUserBind(1);
    }

    @Test
    public void testUserSwitched_ForegroundUserNotUnlocked() {
        notifyUserSwitched(USER_ID, false);

        // Process will not be bound
        verifyUserBind(0);
    }

    @Test
    public void testUserSwitchedToSystemUser() {
        notifyUserSwitched(UserHandle.USER_SYSTEM, true);

        // Neither user nor system processes will be bound for system user intent
        verifySystemBind(0);
        verifyUserBind(0);
    }

    @Test
    public void testOnSystemServiceConnected() {
        IBinder binder = bindSystemClient();
        verifyOnClientConnected(SYSTEM_CLIENT_NAME, binder);
        verify(mSystemClientLog).logConnectionState(ConnectionState.CONNECTED);
    }

    private IBinder bindSystemClient() {
        notifySystemUserUnlocked();
        verifySystemBind(1);
        resetContext();

        IBinder binder = createPublisherBinder();
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, binder);
        return binder;
    }

    @Test
    public void testOnUserServiceConnected() {
        IBinder binder = bindUserClient();
        verifyOnClientConnected(USER_CLIENT_NAME, binder);
        verify(mUserClientLog).logConnectionState(ConnectionState.CONNECTED);
    }

    private IBinder bindUserClient() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        resetContext();

        IBinder binder = createPublisherBinder();
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, binder);
        return binder;
    }

    @Test
    public void testOnSystemServiceDisconnected() throws Exception {
        notifySystemUserUnlocked();
        verifySystemBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, createPublisherBinder());
        verify(mSystemClientLog).logConnectionState(ConnectionState.CONNECTED);
        reset(mPublisherService);

        connection.onServiceDisconnected(null);
        verify(mPublisherService).onClientDisconnected(eq(SYSTEM_CLIENT_NAME));
        verify(mSystemClientLog).logConnectionState(ConnectionState.DISCONNECTED);

        verifyAndRunRebindTask();
        verify(mContext).unbindService(connection);
        verifySystemBind(1);
    }

    @Test
    public void testOnSystemServiceDisconnected_ServiceReboundByAndroid() throws Exception {
        notifySystemUserUnlocked();
        verifySystemBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        IBinder binder = createPublisherBinder();
        connection.onServiceConnected(null, binder);
        verifyOnClientConnected(SYSTEM_CLIENT_NAME, binder);
        verify(mSystemClientLog).logConnectionState(ConnectionState.CONNECTED);
        reset(mPublisherService, mSystemClientLog);

        connection.onServiceDisconnected(null);
        verify(mPublisherService).onClientDisconnected(eq(SYSTEM_CLIENT_NAME));
        verify(mSystemClientLog).logConnectionState(ConnectionState.DISCONNECTED);

        binder = createPublisherBinder();
        connection.onServiceConnected(null, binder);
        verifyOnClientConnected(SYSTEM_CLIENT_NAME, binder);
        verify(mSystemClientLog).logConnectionState(ConnectionState.CONNECTED);

        verifyAndRunRebindTask();
        // No more interactions (verified by tearDown)
    }


    @Test
    public void testOnSystemServiceBindingDied() throws Exception {
        notifySystemUserUnlocked();
        verifySystemBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, createPublisherBinder());
        verify(mSystemClientLog).logConnectionState(ConnectionState.CONNECTED);
        reset(mPublisherService);

        connection.onBindingDied(null);
        verify(mPublisherService).onClientDisconnected(eq(SYSTEM_CLIENT_NAME));
        verify(mSystemClientLog).logConnectionState(ConnectionState.DISCONNECTED);

        verifyAndRunRebindTask();
        verify(mContext).unbindService(connection);
        verifySystemBind(1);
    }

    @Test
    public void testOnSystemServiceBindingDied_ServiceNotConnected() throws Exception {
        notifySystemUserUnlocked();
        verifySystemBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onBindingDied(null);

        verifyZeroInteractions(mPublisherService);

        verifyAndRunRebindTask();
        verify(mContext).unbindService(connection);
        verifySystemBind(1);
    }

    @Test
    public void testOnUserServiceDisconnected() throws Exception {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, createPublisherBinder());
        verify(mUserClientLog).logConnectionState(ConnectionState.CONNECTED);
        reset(mPublisherService);

        connection.onServiceDisconnected(null);
        verify(mPublisherService).onClientDisconnected(eq(USER_CLIENT_NAME));
        verify(mUserClientLog).logConnectionState(ConnectionState.DISCONNECTED);

        verifyAndRunRebindTask();
        verify(mContext).unbindService(connection);
        verifyUserBind(1);
    }

    @Test
    public void testOnUserServiceDisconnected_ServiceReboundByAndroid() throws Exception {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        IBinder binder = createPublisherBinder();
        connection.onServiceConnected(null, binder);
        verifyOnClientConnected(USER_CLIENT_NAME, binder);
        verify(mUserClientLog).logConnectionState(ConnectionState.CONNECTED);
        reset(mPublisherService, mUserClientLog);

        connection.onServiceDisconnected(null);
        verify(mPublisherService).onClientDisconnected(eq(USER_CLIENT_NAME));
        verify(mUserClientLog).logConnectionState(ConnectionState.DISCONNECTED);

        binder = createPublisherBinder();
        connection.onServiceConnected(null, binder);
        verifyOnClientConnected(USER_CLIENT_NAME, binder);
        verify(mUserClientLog).logConnectionState(ConnectionState.CONNECTED);

        verifyAndRunRebindTask();
        // No more interactions (verified by tearDown)
    }

    @Test
    public void testOnUserServiceBindingDied() throws Exception {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, createPublisherBinder());
        verify(mUserClientLog).logConnectionState(ConnectionState.CONNECTED);
        reset(mPublisherService);

        connection.onBindingDied(null);
        verify(mPublisherService).onClientDisconnected(eq(USER_CLIENT_NAME));
        verify(mUserClientLog).logConnectionState(ConnectionState.DISCONNECTED);

        verifyAndRunRebindTask();
        verify(mContext).unbindService(connection);
        verifyUserBind(1);
    }

    @Test
    public void testOnUserServiceBindingDied_ServiceNotConnected() throws Exception {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onBindingDied(null);

        verifyZeroInteractions(mPublisherService);

        verifyAndRunRebindTask();
        verify(mContext).unbindService(connection);
        verifyUserBind(1);
    }

    @Test
    public void testOnUserSwitched_UserChange() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, createPublisherBinder());
        verify(mUserClientLog).logConnectionState(ConnectionState.CONNECTED);
        reset(mPublisherService);

        notifyUserSwitched(USER_ID_U11, true);

        verify(mContext).unbindService(connection);
        verify(mPublisherService).onClientDisconnected(eq(USER_CLIENT_NAME));
        verify(mUserClientLog).logConnectionState(ConnectionState.TERMINATED);
        verifyUserBind(1);
    }

    @Test
    public void testOnUserSwitched_UserChange_ForegroundUserNotUnlocked() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, createPublisherBinder());
        resetContext();
        reset(mPublisherService);

        notifyUserSwitched(USER_ID_U11, false);

        verify(mContext).unbindService(connection);
        verify(mPublisherService).onClientDisconnected(eq(USER_CLIENT_NAME));
        verify(mUserClientLog).logConnectionState(ConnectionState.TERMINATED);
        verifyUserBind(0);
    }

    @Test
    public void testOnUserSwitched_UserChange_ToSystemUser() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, createPublisherBinder());
        resetContext();
        reset(mPublisherService);

        notifyUserSwitched(UserHandle.USER_SYSTEM, true);

        verify(mContext).unbindService(connection);
        verify(mPublisherService).onClientDisconnected(eq(USER_CLIENT_NAME));
        verify(mUserClientLog).logConnectionState(ConnectionState.TERMINATED);
        verifyUserBind(0);
    }

    @Test
    public void testOnUserSwitched_UserChange_ServiceNotConnected() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        resetContext();

        notifyUserSwitched(USER_ID_U11, true);

        verify(mContext).unbindService(connection);
        verifyUserBind(1);
    }

    @Test
    public void testOnUserSwitched_UserChange_ServiceNotConnected_ForegroundUserNotUnlocked() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        resetContext();

        notifyUserSwitched(USER_ID_U11, false);

        verify(mContext).unbindService(connection);
        verifyUserBind(0);
    }

    @Test
    public void testOnUserUnlocked_UserChange() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, createPublisherBinder());
        resetContext();
        reset(mPublisherService);

        notifyUserSwitched(USER_ID_U11, false);
        notifyUserUnlocked(USER_ID_U11, true);

        verify(mContext).unbindService(connection);
        verify(mPublisherService).onClientDisconnected(eq(USER_CLIENT_NAME));
        verify(mUserClientLog).logConnectionState(ConnectionState.TERMINATED);
        verifyUserBind(1);
    }

    @Test
    public void testOnUserUnlocked_UserChange_ToSystemUser() {
        notifySystemUserUnlocked();
        verifySystemBind(1);
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, createPublisherBinder());
        resetContext();
        reset(mPublisherService);

        notifyUserSwitched(USER_ID_U11, false);
        notifyUserUnlocked(UserHandle.USER_SYSTEM, true);

        verify(mContext).unbindService(connection);
        verify(mPublisherService).onClientDisconnected(eq(USER_CLIENT_NAME));
        verify(mUserClientLog).logConnectionState(ConnectionState.TERMINATED);
        // User processes will not be bound for system user
        verifyUserBind(0);
    }

    @Test
    public void testOnUserUnlocked_UserChange_ServiceNotConnected() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        resetContext();

        notifyUserSwitched(USER_ID_U11, false);
        notifyUserUnlocked(USER_ID_U11, true);

        verify(mContext).unbindService(connection);
        verifyUserBind(1);
    }

    @Test
    public void testAddSubscriber() {
        mClientManager.addSubscriber(mSubscriberClient1);
        assertEquals(TEST_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(mCallingAppUid, mClientManager.getSubscriberUid(mSubscriberClient1));
        assertEquals(UNKNOWN_PACKAGE, mClientManager.getPackageName(mSubscriberClient2));
        assertEquals(-1, mClientManager.getSubscriberUid(mSubscriberClient2));
    }

    @Test
    public void testAddSubscriber_SystemUser() {
        mCallingAppUid = UserHandle.getUid(UserHandle.USER_SYSTEM, 0);
        when(mPackageManager.getNameForUid(mCallingAppUid)).thenReturn(TEST_PACKAGE);

        mClientManager.addSubscriber(mSubscriberClient1);
        assertEquals(TEST_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(mCallingAppUid, mClientManager.getSubscriberUid(mSubscriberClient1));
        assertEquals(UNKNOWN_PACKAGE, mClientManager.getPackageName(mSubscriberClient2));
        assertEquals(-1, mClientManager.getSubscriberUid(mSubscriberClient2));
    }

    @Test
    public void testAddSubscriber_NotForegroundUser() {
        mCallingAppUid = UserHandle.getUid(USER_ID_U11, 0);

        try {
            mClientManager.addSubscriber(mSubscriberClient1);
            fail("Expected client to be rejected");
        } catch (SecurityException expected) {
            // expected
        }
        assertEquals(UNKNOWN_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(-1, mClientManager.getSubscriberUid(mSubscriberClient1));
    }

    @Test
    public void testAddSubscriber_MultipleCalls() {
        mClientManager.addSubscriber(mSubscriberClient1);
        mClientManager.addSubscriber(mSubscriberClient1);
        verify(mPackageManager, atMost(1)).getNameForUid(anyInt());
        assertEquals(TEST_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(mCallingAppUid, mClientManager.getSubscriberUid(mSubscriberClient1));
        assertEquals(UNKNOWN_PACKAGE, mClientManager.getPackageName(mSubscriberClient2));
        assertEquals(-1, mClientManager.getSubscriberUid(mSubscriberClient2));
    }

    @Test
    public void testAddSubscriber_MultipleClients_SamePackage() {
        mClientManager.addSubscriber(mSubscriberClient1);
        mClientManager.addSubscriber(mSubscriberClient2);
        verify(mPackageManager, atMost(2)).getNameForUid(anyInt());
        assertEquals(TEST_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(mCallingAppUid, mClientManager.getSubscriberUid(mSubscriberClient1));
        assertEquals(TEST_PACKAGE, mClientManager.getPackageName(mSubscriberClient2));
        assertEquals(mCallingAppUid, mClientManager.getSubscriberUid(mSubscriberClient2));
    }

    @Test
    public void testAddSubscriber_MultipleClients_ForegroundAndSystemUsers_SamePackage() {
        int clientUid1 = mCallingAppUid;
        mClientManager.addSubscriber(mSubscriberClient1);

        mCallingAppUid = UserHandle.getUid(UserHandle.USER_SYSTEM, 0);
        when(mPackageManager.getNameForUid(mCallingAppUid)).thenReturn(TEST_PACKAGE);
        mClientManager.addSubscriber(mSubscriberClient2);

        verify(mPackageManager, atMost(2)).getNameForUid(anyInt());
        assertEquals(TEST_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(clientUid1, mClientManager.getSubscriberUid(mSubscriberClient1));
        assertEquals(TEST_PACKAGE, mClientManager.getPackageName(mSubscriberClient2));
        assertEquals(mCallingAppUid, mClientManager.getSubscriberUid(mSubscriberClient2));
    }


    @Test
    public void testAddSubscriber_MultipleClients_MultiplePackages() {
        int clientUid1 = mCallingAppUid;
        mClientManager.addSubscriber(mSubscriberClient1);

        mCallingAppUid = UserHandle.getUid(mForegroundUserId, 1);
        when(mPackageManager.getNameForUid(mCallingAppUid)).thenReturn("test.package2");
        mClientManager.addSubscriber(mSubscriberClient2);

        verify(mPackageManager, times(2)).getNameForUid(anyInt());
        assertEquals(TEST_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(clientUid1, mClientManager.getSubscriberUid(mSubscriberClient1));
        assertEquals("test.package2", mClientManager.getPackageName(mSubscriberClient2));
        assertEquals(mCallingAppUid, mClientManager.getSubscriberUid(mSubscriberClient2));
    }

    @Test
    public void testRemoveSubscriber() {
        mClientManager.addSubscriber(mSubscriberClient1);
        mClientManager.removeSubscriber(mSubscriberClient1);
        verify(mBrokerService).removeDeadSubscriber(mSubscriberClient1);
        assertEquals(UNKNOWN_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(-1, mClientManager.getSubscriberUid(mSubscriberClient1));
    }

    @Test
    public void testRemoveSubscriber_NotRegistered() {
        mClientManager.removeSubscriber(mSubscriberClient1);
        assertEquals(UNKNOWN_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(-1, mClientManager.getSubscriberUid(mSubscriberClient1));
    }

    @Test
    public void testRemoveSubscriber_OnDeath() {
        mClientManager.addSubscriber(mSubscriberClient1);

        verify(mSubscriberBinder1).linkToDeath(mDeathRecipient.capture(), eq(0));
        mDeathRecipient.getValue().binderDied();

        verify(mBrokerService).removeDeadSubscriber(mSubscriberClient1);
        assertEquals(UNKNOWN_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(-1, mClientManager.getSubscriberUid(mSubscriberClient1));
    }

    @Test
    public void testOnUserSwitch_RemoveSubscriber() {
        mClientManager.addSubscriber(mSubscriberClient1);

        notifyUserSwitched(USER_ID_U11, false);
        verify(mBrokerService).removeDeadSubscriber(mSubscriberClient1);
        assertEquals(UNKNOWN_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(-1, mClientManager.getSubscriberUid(mSubscriberClient1));
        assertTrue(mClientManager.getAllSubscribers().isEmpty());
    }

    @Test
    public void testOnUserSwitch_RemoveSubscriber_AddNewSubscriber() {
        mClientManager.addSubscriber(mSubscriberClient1);

        notifyUserSwitched(USER_ID_U11, false);
        verify(mBrokerService).removeDeadSubscriber(mSubscriberClient1);

        mCallingAppUid = UserHandle.getUid(USER_ID_U11, 0);
        when(mPackageManager.getNameForUid(mCallingAppUid)).thenReturn(TEST_PACKAGE);
        mClientManager.addSubscriber(mSubscriberClient2);

        assertEquals(UNKNOWN_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(-1, mClientManager.getSubscriberUid(mSubscriberClient1));
        assertEquals(TEST_PACKAGE, mClientManager.getPackageName(mSubscriberClient2));
        assertEquals(mCallingAppUid, mClientManager.getSubscriberUid(mSubscriberClient2));
        assertFalse(mClientManager.getAllSubscribers().contains(mSubscriberClient1));
        assertTrue(mClientManager.getAllSubscribers().contains(mSubscriberClient2));
    }

    @Test
    public void testOnUserSwitch_RemoveSubscriber_RetainSystemClient() {
        mClientManager.addSubscriber(mSubscriberClient1);

        mCallingAppUid = UserHandle.getUid(UserHandle.USER_SYSTEM, 0);
        when(mPackageManager.getNameForUid(mCallingAppUid)).thenReturn(TEST_PACKAGE);

        mClientManager.addSubscriber(mSubscriberClient2);

        notifyUserSwitched(USER_ID_U11, false);

        verify(mBrokerService).removeDeadSubscriber(mSubscriberClient1);
        verify(mBrokerService, never()).removeDeadSubscriber(mSubscriberClient2);
        assertEquals(UNKNOWN_PACKAGE, mClientManager.getPackageName(mSubscriberClient1));
        assertEquals(TEST_PACKAGE, mClientManager.getPackageName(mSubscriberClient2));
    }

    @Test
    public void testOnUserSwitch_RemoveSubscriber_RetainHalClient() {
        IVmsPublisherClient publisherClient = createPublisherClient();
        IVmsSubscriberClient subscriberClient = createSubscriberClient();
        mClientManager.onHalConnected(publisherClient, subscriberClient);
        verify(mHalClientLog).logConnectionState(ConnectionState.CONNECTED);
        reset(mPublisherService);

        notifyUserSwitched(USER_ID_U11, false);

        verify(mBrokerService, never()).removeDeadSubscriber(subscriberClient);
        assertEquals(HAL_CLIENT_NAME, mClientManager.getPackageName(subscriberClient));
    }

    @Test
    public void testHalClientConnected() {
        IVmsPublisherClient publisherClient = createPublisherClient();
        IVmsSubscriberClient subscriberClient = createSubscriberClient();
        mClientManager.onHalConnected(publisherClient, subscriberClient);
        verify(mPublisherService).onClientConnected(eq(HAL_CLIENT_NAME), same(publisherClient));
        verify(mHalClientLog).logConnectionState(ConnectionState.CONNECTED);
        assertTrue(mClientManager.getAllSubscribers().contains(subscriberClient));
        assertEquals(HAL_CLIENT_NAME, mClientManager.getPackageName(subscriberClient));
    }

    @Test
    public void testHalClientConnected_AfterAddSubscriber() {
        IVmsPublisherClient publisherClient = createPublisherClient();
        IVmsSubscriberClient subscriberClient = createSubscriberClient();
        mClientManager.addSubscriber(subscriberClient);

        mClientManager.onHalConnected(publisherClient, subscriberClient);
        verify(mPublisherService).onClientConnected(eq(HAL_CLIENT_NAME), same(publisherClient));
        verify(mHalClientLog).logConnectionState(ConnectionState.CONNECTED);
        assertTrue(mClientManager.getAllSubscribers().contains(subscriberClient));
        assertEquals(HAL_CLIENT_NAME, mClientManager.getPackageName(subscriberClient));
    }

    @Test
    public void testOnHalClientDisconnected() {
        IVmsPublisherClient publisherClient = createPublisherClient();
        IVmsSubscriberClient subscriberClient = createSubscriberClient();
        mClientManager.onHalConnected(publisherClient, subscriberClient);
        verify(mHalClientLog).logConnectionState(ConnectionState.CONNECTED);
        reset(mPublisherService);

        mClientManager.onHalDisconnected();
        verify(mPublisherService).onClientDisconnected(eq(HAL_CLIENT_NAME));
        verify(mBrokerService).removeDeadSubscriber(eq(subscriberClient));
        verify(mHalClientLog).logConnectionState(ConnectionState.DISCONNECTED);
        assertFalse(mClientManager.getAllSubscribers().contains(subscriberClient));
        assertEquals(UNKNOWN_PACKAGE, mClientManager.getPackageName(subscriberClient));
    }

    @Test
    public void testOnHalClientDisconnected_NotConnected() {
        mClientManager.onHalDisconnected();
        verify(mPublisherService, never()).onClientDisconnected(eq(HAL_CLIENT_NAME));
        assertTrue(mClientManager.getAllSubscribers().isEmpty());
    }

    private void resetContext() {
        reset(mContext, mSystemClientLog, mUserClientLog);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), any())).thenReturn(true);
        when(mContext.getResources()).thenReturn(mResources);
    }

    private void notifySystemUserUnlocked() {
        mClientManager.mSystemUserUnlockedListener.run();
    }

    private void notifyUserSwitched(int foregroundUserId, boolean isForegroundUserUnlocked) {
        when(mUserManager.isUserUnlockingOrUnlocked(foregroundUserId))
                .thenReturn(isForegroundUserUnlocked);
        mForegroundUserId = foregroundUserId; // Member variable used by verifyUserBind()
        mClientManager.mUserCallback.onSwitchUser(foregroundUserId);
    }

    private void notifyUserUnlocked(int foregroundUserId, boolean isForegroundUserUnlocked) {
        when(mUserManager.isUserUnlockingOrUnlocked(foregroundUserId))
                .thenReturn(isForegroundUserUnlocked);
        mClientManager.mUserCallback.onUserLockChanged(foregroundUserId, isForegroundUserUnlocked);
    }

    private void verifySystemBind(int times) {
        verify(mSystemClientLog, times(times)).logConnectionState(ConnectionState.CONNECTING);
        verifyBind(times, SYSTEM_CLIENT_COMPONENT, UserHandle.SYSTEM);
    }

    private void verifyUserBind(int times) {
        if (mForegroundUserId == USER_ID) {
            verify(mUserClientLog, times(times)).logConnectionState(ConnectionState.CONNECTING);
        } else if (mForegroundUserId == USER_ID_U11) {
            verify(mUserClientLog2, times(times)).logConnectionState(ConnectionState.CONNECTING);
        }
        verifyBind(times, USER_CLIENT_COMPONENT, UserHandle.of(mForegroundUserId));
    }

    private void verifyBind(int times, ComponentName componentName, UserHandle user) {
        Intent expectedService = new Intent();
        expectedService.setComponent(componentName);
        verify(mContext, times(times)).bindServiceAsUser(
                argThat((service) -> service.filterEquals(expectedService)),
                mConnectionCaptor.capture(),
                eq(Context.BIND_AUTO_CREATE), any(Handler.class), eq(user));
    }

    private void verifyAndRunRebindTask() {
        verify(mHandler).postDelayed(mRebindCaptor.capture(), eq(MILLIS_BEFORE_REBIND));
        mRebindCaptor.getValue().run();
    }

    private void verifyOnClientConnected(String publisherName, IBinder binder) {
        ArgumentCaptor<IVmsPublisherClient> clientCaptor =
                ArgumentCaptor.forClass(IVmsPublisherClient.class);
        verify(mPublisherService).onClientConnected(eq(publisherName), clientCaptor.capture());
        assertSame(binder, clientCaptor.getValue().asBinder());
    }

    private IBinder createPublisherBinder() {
        return createPublisherClient().asBinder();
    }

    private IVmsPublisherClient createPublisherClient() {
        return new IVmsPublisherClient.Stub() {
            @Override
            public void setVmsPublisherService(IBinder token, IVmsPublisherService service) {
                throw new RuntimeException("Unexpected call");
            }

            @Override
            public void onVmsSubscriptionChange(VmsSubscriptionState subscriptionState) {
                throw new RuntimeException("Unexpected call");
            }
        };
    }

    private IVmsSubscriberClient createSubscriberClient() {
        return new IVmsSubscriberClient.Stub() {
            @Override
            public void onVmsMessageReceived(VmsLayer layer, byte[] payload) {
                throw new RuntimeException("Unexpected call");
            }

            @Override
            public void onLayersAvailabilityChanged(VmsAvailableLayers availableLayers) {
                throw new RuntimeException("Unexpected call");
            }
        };
    }
}
