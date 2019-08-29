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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.userlib.CarUserManagerHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.filters.SmallTest;

import com.android.car.hal.VmsHalService;
import com.android.car.user.CarUserService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.function.Consumer;

@SmallTest
public class VmsClientManagerTest {
    private static final String HAL_CLIENT_NAME = "VmsHalClient";
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
    private static final String USER_CLIENT_NAME_U11 =
            "com.google.android.apps.vms.test/com.google.android.apps.vms.test.VmsUserClient U=11";
    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Resources mResources;

    @Mock
    private UserManager mUserManager;
    @Mock
    private CarUserService mUserService;
    @Mock
    private CarUserManagerHelper mUserManagerHelper;
    private int mForegroundUserId;

    @Mock
    private VmsHalService mHal;
    private Consumer<IBinder> mHalClientConnected;
    private Runnable mHalClientDisconnected;

    @Mock
    private VmsClientManager.ConnectionListener mConnectionListener;
    private VmsClientManager mClientManager;

    @Captor
    private ArgumentCaptor<ServiceConnection> mConnectionCaptor;

    @Before
    public void setUp() throws Exception {
        resetContext();
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = Car.PERMISSION_BIND_VMS_CLIENT;
        when(mPackageManager.getServiceInfo(any(), anyInt())).thenReturn(serviceInfo);

        when(mResources.getInteger(
                com.android.car.R.integer.millisecondsBeforeRebindToVmsPublisher)).thenReturn(
                5);
        when(mResources.getStringArray(
                com.android.car.R.array.vmsPublisherSystemClients)).thenReturn(
                new String[]{ SYSTEM_CLIENT });
        when(mResources.getStringArray(
                com.android.car.R.array.vmsPublisherUserClients)).thenReturn(
                new String[]{ USER_CLIENT });

        when(mContext.getSystemService(eq(Context.USER_SERVICE))).thenReturn(mUserManager);

        mClientManager = new VmsClientManager(mContext, mUserService, mUserManagerHelper, mHal);
        mClientManager.registerConnectionListener(mConnectionListener);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<IBinder>> onClientConnectedCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Runnable> onClientDisconnectedCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        verify(mHal).setPublisherConnectionCallbacks(
                onClientConnectedCaptor.capture(), onClientDisconnectedCaptor.capture());
        mHalClientConnected = onClientConnectedCaptor.getValue();
        mHalClientDisconnected = onClientDisconnectedCaptor.getValue();
    }

    @After
    public void tearDown() throws Exception {
        Thread.sleep(10); // Time to allow for delayed rebinds to settle
        verify(mContext, atLeast(0)).getSystemService(eq(Context.USER_SERVICE));
        verify(mContext, atLeast(0)).getResources();
        verify(mContext, atLeast(0)).getPackageManager();
        verifyNoMoreInteractions(mContext);
        verifyNoMoreInteractions(mHal);
    }

    @Test
    public void testInit() {
        mClientManager.init();

        // Verify registration of system user unlock listener
        verify(mUserService).runOnUser0Unlock(mClientManager.mSystemUserUnlockedListener);

        // Verify registration of user switch receiver
        ArgumentCaptor<IntentFilter> userFilterCaptor = ArgumentCaptor.forClass(IntentFilter.class);
        verify(mContext).registerReceiverAsUser(eq(mClientManager.mUserSwitchReceiver),
                eq(UserHandle.ALL), userFilterCaptor.capture(), isNull(), isNull());
        IntentFilter userEventFilter = userFilterCaptor.getValue();
        assertEquals(2, userEventFilter.countActions());
        assertTrue(userEventFilter.hasAction(Intent.ACTION_USER_SWITCHED));
        assertTrue(userEventFilter.hasAction(Intent.ACTION_USER_UNLOCKED));
    }

    @Test
    public void testRelease() {
        mClientManager.release();

        // Verify user switch receiver is unregistered
        verify(mContext).unregisterReceiver(mClientManager.mUserSwitchReceiver);
    }

    @Test
    public void testRegisterConnectionListener() {
        VmsClientManager.ConnectionListener listener =
                Mockito.mock(VmsClientManager.ConnectionListener.class);
        mClientManager.registerConnectionListener(listener);
    }

    @Test
    public void testRegisterConnectionListener_AfterHalClientConnected() {
        IBinder halClient = bindHalClient();

        VmsClientManager.ConnectionListener listener =
                Mockito.mock(VmsClientManager.ConnectionListener.class);
        mClientManager.registerConnectionListener(listener);
        verify(listener).onClientConnected(HAL_CLIENT_NAME, halClient);
    }

    @Test
    public void testRegisterConnectionListener_AfterClientsConnected() {
        IBinder halClient = bindHalClient();
        IBinder systemBinder = bindSystemClient();
        IBinder userBinder = bindUserClient();

        VmsClientManager.ConnectionListener listener =
                Mockito.mock(VmsClientManager.ConnectionListener.class);
        mClientManager.registerConnectionListener(listener);
        verify(listener).onClientConnected(HAL_CLIENT_NAME, halClient);
        verify(listener).onClientConnected(eq(SYSTEM_CLIENT_NAME), eq(systemBinder));
        verify(listener).onClientConnected(eq(USER_CLIENT_NAME), eq(userBinder));
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
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = Car.PERMISSION_VMS_PUBLISHER;
        when(mPackageManager.getServiceInfo(eq(SYSTEM_CLIENT_COMPONENT), anyInt()))
                .thenReturn(serviceInfo);
        notifySystemUserUnlocked();

        // Process will not be bound
        verifySystemBind(0);
    }

    @Test
    public void testSystemUserUnlocked_BindFailed() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), any())).thenReturn(false);
        notifySystemUserUnlocked();
        notifySystemUserUnlocked();

        // Failure state will trigger another attempt on event
        verifySystemBind(2);
    }

    @Test
    public void testSystemUserUnlocked_BindException() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), any())).thenThrow(
                new SecurityException());
        notifySystemUserUnlocked();
        notifySystemUserUnlocked();

        // Failure state will trigger another attempt on event
        verifySystemBind(2);
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
    public void testUserUnlocked_ClientNotFound() throws Exception {
        when(mPackageManager.getServiceInfo(eq(USER_CLIENT_COMPONENT), anyInt()))
                .thenThrow(new PackageManager.NameNotFoundException());
        notifyUserUnlocked(USER_ID, true);

        // Process will not be bound
        verifyUserBind(0);
    }

    @Test
    public void testUserUnlocked_WrongPermission() throws Exception {
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = Car.PERMISSION_VMS_PUBLISHER;
        when(mPackageManager.getServiceInfo(eq(USER_CLIENT_COMPONENT), anyInt()))
                .thenReturn(serviceInfo);
        notifyUserUnlocked(USER_ID, true);

        // Process will not be bound
        verifyUserBind(0);
    }

    @Test
    public void testUserUnlocked_BindFailed() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), any()))
                .thenReturn(false);
        notifyUserUnlocked(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        // Failure state will trigger another attempt
        verifyUserBind(2);
    }

    @Test
    public void testUserUnlocked_UserBindFailed() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.of(USER_ID))))
                .thenReturn(false);
        notifyUserUnlocked(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        // Failure state will trigger another attempt
        verifyUserBind(2);
    }

    @Test
    public void testUserUnlocked_BindException() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), any()))
                .thenThrow(new SecurityException());
        notifyUserUnlocked(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        // Failure state will trigger another attempt
        verifyUserBind(2);
    }

    @Test
    public void testUserUnlocked_SystemRebind() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.SYSTEM)))
                .thenReturn(false);
        notifySystemUserUnlocked();
        verifySystemBind(1);
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
        resetContext();

        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.SYSTEM)))
                .thenReturn(false);
        notifyUserUnlocked(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        verifySystemBind(2); // Failure state will trigger another attempt
        verifyUserBind(1);
    }

    @Test
    public void testUserUnlocked_SystemRebind_BindException() {
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.SYSTEM)))
                .thenThrow(new SecurityException());
        notifySystemUserUnlocked();
        verifySystemBind(1);
        resetContext();

        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), eq(UserHandle.SYSTEM)))
                .thenThrow(new SecurityException());
        notifyUserUnlocked(USER_ID, true);
        notifyUserUnlocked(USER_ID, true);

        verifySystemBind(2); // Failure state will trigger another attempt
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
    public void testUnregisterConnectionListener() {
        mClientManager.unregisterConnectionListener(mConnectionListener);
        notifySystemUserUnlocked();
        verifySystemBind(1);

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, new Binder());
        verifyZeroInteractions(mConnectionListener);
    }

    @Test
    public void testHalClientConnected() {
        IBinder binder = bindHalClient();
        verify(mConnectionListener).onClientConnected(eq(HAL_CLIENT_NAME), eq(binder));
    }

    private IBinder bindHalClient() {
        IBinder binder = new Binder();
        mHalClientConnected.accept(binder);
        return binder;
    }

    @Test
    public void testOnSystemServiceConnected() {
        IBinder binder = bindSystemClient();
        verify(mConnectionListener).onClientConnected(eq(SYSTEM_CLIENT_NAME), eq(binder));
    }

    private IBinder bindSystemClient() {
        notifySystemUserUnlocked();
        verifySystemBind(1);
        resetContext();

        IBinder binder = new Binder();
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, binder);
        return binder;
    }

    @Test
    public void testOnUserServiceConnected() {
        IBinder binder = bindUserClient();
        verify(mConnectionListener).onClientConnected(eq(USER_CLIENT_NAME), eq(binder));
    }

    private IBinder bindUserClient() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        resetContext();

        IBinder binder = new Binder();
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, binder);
        return binder;
    }

    @Test
    public void testOnHalClientDisconnected() throws Exception {
        bindHalClient();
        mHalClientDisconnected.run();

        verify(mConnectionListener).onClientDisconnected(eq(HAL_CLIENT_NAME));
    }

    @Test
    public void testOnSystemServiceDisconnected() throws Exception {
        notifySystemUserUnlocked();
        verifySystemBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, new Binder());
        connection.onServiceDisconnected(null);

        verify(mConnectionListener).onClientDisconnected(eq(SYSTEM_CLIENT_NAME));

        Thread.sleep(10);
        verify(mContext).unbindService(connection);
        verifySystemBind(1);
    }

    @Test
    public void testOnSystemServiceDisconnected_ServiceReboundByAndroid() throws Exception {
        notifySystemUserUnlocked();
        verifySystemBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, new Binder());
        connection.onServiceDisconnected(null);

        verify(mConnectionListener).onClientDisconnected(eq(SYSTEM_CLIENT_NAME));

        IBinder binder = new Binder();
        connection.onServiceConnected(null, binder);
        verify(mConnectionListener).onClientConnected(eq(SYSTEM_CLIENT_NAME), eq(binder));
        // No more interactions (verified by tearDown)
    }


    @Test
    public void testOnSystemServiceBindingDied() throws Exception {
        notifySystemUserUnlocked();
        verifySystemBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, new Binder());
        connection.onServiceDisconnected(null);
        connection.onBindingDied(null);

        verify(mConnectionListener).onClientDisconnected(eq(SYSTEM_CLIENT_NAME));

        Thread.sleep(10);
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

        verifyZeroInteractions(mConnectionListener);

        Thread.sleep(10);
        verify(mContext).unbindService(connection);
        verifySystemBind(1);
    }

    @Test
    public void testOnUserServiceDisconnected() throws Exception {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, new Binder());
        connection.onServiceDisconnected(null);

        verify(mConnectionListener).onClientDisconnected(eq(USER_CLIENT_NAME));

        Thread.sleep(10);
        verify(mContext).unbindService(connection);
        verifyUserBind(1);
    }

    @Test
    public void testOnUserServiceDisconnected_ServiceReboundByAndroid() throws Exception {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, new Binder());
        connection.onServiceDisconnected(null);

        verify(mConnectionListener).onClientDisconnected(eq(USER_CLIENT_NAME));

        IBinder binder = new Binder();
        connection.onServiceConnected(null, binder);
        verify(mConnectionListener).onClientConnected(eq(USER_CLIENT_NAME), eq(binder));
        // No more interactions (verified by tearDown)
    }

    @Test
    public void testOnUserServiceBindingDied() throws Exception {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        resetContext();

        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, new Binder());
        connection.onServiceDisconnected(null);
        connection.onBindingDied(null);

        verify(mConnectionListener).onClientDisconnected(eq(USER_CLIENT_NAME));

        Thread.sleep(10);
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

        verifyZeroInteractions(mConnectionListener);

        Thread.sleep(10);
        verify(mContext).unbindService(connection);
        verifyUserBind(1);
    }

    @Test
    public void testOnUserSwitched_UserChange() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, new Binder());
        resetContext();
        reset(mConnectionListener);

        notifyUserSwitched(USER_ID_U11, true);

        verify(mContext).unbindService(connection);
        verify(mConnectionListener).onClientDisconnected(eq(USER_CLIENT_NAME));
        verifyUserBind(1);
    }

    @Test
    public void testOnUserSwitched_UserChange_ForegroundUserNotUnlocked() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, new Binder());
        resetContext();
        reset(mConnectionListener);

        notifyUserSwitched(USER_ID_U11, false);

        verify(mContext).unbindService(connection);
        verify(mConnectionListener).onClientDisconnected(eq(USER_CLIENT_NAME));
        verifyUserBind(0);
    }

    @Test
    public void testOnUserSwitched_UserChange_ToSystemUser() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, new Binder());
        resetContext();
        reset(mConnectionListener);

        notifyUserSwitched(UserHandle.USER_SYSTEM, true);

        verify(mContext).unbindService(connection);
        verify(mConnectionListener).onClientDisconnected(eq(USER_CLIENT_NAME));
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
        connection.onServiceConnected(null, new Binder());
        resetContext();
        reset(mConnectionListener);

        notifyUserUnlocked(USER_ID_U11, true);

        verify(mContext).unbindService(connection);
        verify(mConnectionListener).onClientDisconnected(eq(USER_CLIENT_NAME));
        verifyUserBind(1);
    }

    @Test
    public void testOnUserUnlocked_UserChange_ToSystemUser() {
        notifySystemUserUnlocked();
        verifySystemBind(1);
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        connection.onServiceConnected(null, new Binder());
        resetContext();
        reset(mConnectionListener);

        notifyUserUnlocked(UserHandle.USER_SYSTEM, true);

        verify(mContext).unbindService(connection);
        verify(mConnectionListener).onClientDisconnected(eq(USER_CLIENT_NAME));
        // User processes will not be bound for system user
        verifyUserBind(0);
    }

    @Test
    public void testOnUserUnlocked_UserChange_ServiceNotConnected() {
        notifyUserUnlocked(USER_ID, true);
        verifyUserBind(1);
        ServiceConnection connection = mConnectionCaptor.getValue();
        resetContext();

        notifyUserUnlocked(USER_ID_U11, true);

        verify(mContext).unbindService(connection);
        verifyUserBind(1);
    }

    private void resetContext() {
        reset(mContext);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any(), any())).thenReturn(true);
        when(mContext.getResources()).thenReturn(mResources);
    }

    private void notifySystemUserUnlocked() {
        mClientManager.mSystemUserUnlockedListener.run();
    }

    private void notifyUserSwitched(int foregroundUserId, boolean isForegroundUserUnlocked) {
        notifyUserAction(foregroundUserId, isForegroundUserUnlocked, Intent.ACTION_USER_SWITCHED);
    }

    private void notifyUserUnlocked(int foregroundUserId, boolean isForegroundUserUnlocked) {
        notifyUserAction(foregroundUserId, isForegroundUserUnlocked, Intent.ACTION_USER_UNLOCKED);
    }

    // Sets the current foreground user + unlock state and dispatches the specified intent action
    private void notifyUserAction(int foregroundUserId, boolean isForegroundUserUnlocked,
            String action) {
        mForegroundUserId = foregroundUserId; // Member variable used by verifyUserBind()
        when(mUserManagerHelper.getCurrentForegroundUserId()).thenReturn(foregroundUserId);

        reset(mUserManager);
        when(mUserManager.isUserUnlocked(foregroundUserId)).thenReturn(isForegroundUserUnlocked);

        mClientManager.mUserSwitchReceiver.onReceive(mContext, new Intent(action));
    }

    private void verifySystemBind(int times) {
        verifyBind(times, SYSTEM_CLIENT_COMPONENT, UserHandle.SYSTEM);
    }

    private void verifyUserBind(int times) {
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
}
