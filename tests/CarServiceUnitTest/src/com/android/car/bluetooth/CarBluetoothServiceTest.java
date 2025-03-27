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

package com.android.car.bluetooth;

import static android.bluetooth.BluetoothProfile.A2DP_SINK;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.car.ICarPerUserService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import com.android.car.CarLocalServices;
import com.android.car.CarPerUserServiceHelper;
import com.android.car.R;
import com.android.car.power.CarPowerManagementService;
import com.android.car.user.CarUserService;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link CarBluetoothService}
 *
 * Run:
 * atest CarBluetoothServiceTest
 *
 * Tests:
 * 1) Verify that, when the useDefaultConnectionPolicy resource overlay flag is true, we create and
 *    use the default connection policy.
 * 2) Verify that, when the useDefaultConnectionPolicy resource overlay flag is false, we do not
 *    create and use the default connection policy.
 */
@RunWith(MockitoJUnitRunner.class)
public class CarBluetoothServiceTest {
    private CarBluetoothService mCarBluetoothService;

    @Mock private Context mMockContext;
    @Mock private Resources mMockResources;
    private MockContentResolver mMockContentResolver;
    private MockContentProvider mMockContentProvider;
    @Mock private PackageManager mMockPackageManager;

    @Mock private BluetoothManager mMockBluetoothManager;
    @Mock private BluetoothAdapter mMockBluetoothAdapter;

    @Mock private CarPerUserServiceHelper mMockUserSwitchService;
    @Mock private ICarPerUserService mMockCarPerUserService;
    @Mock private CarBluetoothUserService mMockBluetoothUserService;
    @Mock private CarPowerManagementService mMockCarPowerManagementService;
    @Mock private CarUserService mMockCarUserService;
    private CarPerUserServiceHelper.ServiceCallback mUserSwitchCallback;

    private IBinder mToken = new Binder();

    //--------------------------------------------------------------------------------------------//
    // Setup/TearDown                                                                             //
    //--------------------------------------------------------------------------------------------//

    @Before
    public void setUp() {
        mMockContentResolver = new MockContentResolver(null);
        mMockContentProvider = new MockContentProvider() {
            @Override
            public Bundle call(String method, String request, Bundle args) {
                return new Bundle();
            }
        };
        mMockContentResolver.addProvider(Settings.AUTHORITY, mMockContentProvider);

        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockContext.getApplicationContext()).thenReturn(mMockContext);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockContext.getSystemService(BluetoothManager.class))
                .thenReturn(mMockBluetoothManager);
        when(mMockContext.createContextAsUser(any(), anyInt())).thenReturn(mMockContext);
        when(mMockBluetoothManager.getAdapter()).thenReturn(mMockBluetoothAdapter);
        when(mMockContext.createContextAsUser(any(), anyInt())).thenReturn(mMockContext);

        mockPermission(android.Manifest.permission.BLUETOOTH_CONNECT,
                PackageManager.PERMISSION_GRANTED);
        mockPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                PackageManager.PERMISSION_GRANTED);
        mockPermission(android.Manifest.permission.MODIFY_PHONE_STATE,
                PackageManager.PERMISSION_GRANTED);

        // Make sure we grab and store CarBluetoothService's user switch callback so we can
        // invoke it at any time.
        doAnswer((Answer<Void>) invocation -> {
            Object[] arguments = invocation.getArguments();
            if (arguments != null && arguments.length == 1 && arguments[0] != null) {
                mUserSwitchCallback =
                        (CarPerUserServiceHelper.ServiceCallback) arguments[0];
            }
            return null;
        }).when(mMockUserSwitchService).registerServiceCallback(any(
                CarPerUserServiceHelper.ServiceCallback.class));

        try {
            when(mMockCarPerUserService.getBluetoothUserService()).thenReturn(
                    mMockBluetoothUserService);
        } catch (RemoteException e) {
            Assert.fail();
        }
        CarLocalServices.addService(CarPowerManagementService.class,
                mMockCarPowerManagementService);
        CarLocalServices.addService(CarUserService.class,
                mMockCarUserService);
    }

    @After
    public void tearDown() {
        if (mCarBluetoothService != null) {
            mCarBluetoothService.release();
            mCarBluetoothService = null;
        }
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.removeServiceForTest(CarUserService.class);
    }

    //--------------------------------------------------------------------------------------------//
    // Policy Initialization Tests                                                                //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - Device connection policy flag is true
     *
     * Action:
     * - Initialize service
     *
     * Outcome:
     * - Default device connection policy should be created
     */
    @Test
    public void testConnectionResourceFlagTrue_doCreateDefaultConnectionPolicy() {
        when(mMockResources.getBoolean(
                R.bool.useDefaultBluetoothConnectionPolicy)).thenReturn(true);
        initServiceUnderTest();

        Assert.assertTrue(mCarBluetoothService.isUsingDefaultConnectionPolicy());
    }

    /**
     * Preconditions:
     * - Device connection policy flag is false
     *
     * Action:
     * - Initialize service
     *
     * Outcome:
     * - Default device connection policy should not be created
     */
    @Test
    public void testConnectionResourceFlagFalse_doNotCreateDefaultConnectionPolicy() {
        when(mMockResources.getBoolean(
                R.bool.useDefaultBluetoothConnectionPolicy)).thenReturn(false);
        initServiceUnderTest();

        Assert.assertFalse(mCarBluetoothService.isUsingDefaultConnectionPolicy());
    }

    /**
     * Preconditions:
     * - Power policy flag is true
     *
     * Action:
     * - Initialize service
     *
     * Outcome:
     * - Default power policy should be created
     */
    @Test
    public void testPowerResourceFlagTrue_doCreateDefaultPowerPolicy() {
        when(mMockResources.getBoolean(
                R.bool.useDefaultBluetoothPowerPolicy)).thenReturn(true);
        initServiceUnderTest();

        Assert.assertTrue(mCarBluetoothService.isUsingDefaultPowerPolicy());
    }

    /**
     * Preconditions:
     * - Power policy flag is false
     *
     * Action:
     * - Initialize service
     *
     * Outcome:
     * - Default power policy should not be created
     */
    @Test
    public void testPowerResourceFlagFalse_doNotCreateDefaultPowerPolicy() {
        when(mMockResources.getBoolean(
                R.bool.useDefaultBluetoothPowerPolicy)).thenReturn(false);
        initServiceUnderTest();

        Assert.assertFalse(mCarBluetoothService.isUsingDefaultPowerPolicy());
    }

    //--------------------------------------------------------------------------------------------//
    // Connect Devices Tests                                                                      //
    //--------------------------------------------------------------------------------------------//

    @Test
    public void testConnectDevices_withoutBluetoothConnect_throwsSecurityException() {
        mockPermission(android.Manifest.permission.BLUETOOTH_CONNECT,
                PackageManager.PERMISSION_DENIED);
        initServiceUnderTest();

        assertThrows(SecurityException.class, () -> mCarBluetoothService.connectDevices());
    }

    @Test
    public void testConnectDevices_withoutBluetoothCPrivileged_throwsSecurityException() {
        mockPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                PackageManager.PERMISSION_DENIED);
        initServiceUnderTest();

        assertThrows(SecurityException.class, () -> mCarBluetoothService.connectDevices());
    }

    @Test
    public void testConnectDevices_withoutModifyPhoneStatePthrowsSecurityException() {
        mockPermission(android.Manifest.permission.MODIFY_PHONE_STATE,
                PackageManager.PERMISSION_DENIED);
        initServiceUnderTest();

        assertThrows(SecurityException.class, () -> mCarBluetoothService.connectDevices());
    }

    //--------------------------------------------------------------------------------------------//
    // Profile Inhibit Tests                                                                      //
    //--------------------------------------------------------------------------------------------//

    // Request

    @Test
    public void testRequestProfileInhibit_setsConnectionPolicyForbidden() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        doReturn(CONNECTION_POLICY_ALLOWED).when(mMockBluetoothUserService)
                .getConnectionPolicy(A2DP_SINK, device);
        doReturn(true).when(mMockBluetoothUserService)
                .isBluetoothConnectionProxyAvailable(A2DP_SINK);
        initServiceUnderTest();

        assertThat(mCarBluetoothService.requestProfileInhibit(device, A2DP_SINK, mToken)).isTrue();
        verify(mMockBluetoothUserService, times(1))
                .setConnectionPolicy(eq(A2DP_SINK), eq(device), eq(CONNECTION_POLICY_FORBIDDEN));
    }

    @Test
    public void testRequestProfileInhibit_withoutBluetoothConnect_throwsSecurityException() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        mockPermission(android.Manifest.permission.BLUETOOTH_CONNECT,
                PackageManager.PERMISSION_DENIED);
        initServiceUnderTest();

        assertThrows(SecurityException.class,
                () -> mCarBluetoothService.requestProfileInhibit(device, A2DP_SINK, mToken));
    }

    @Test
    public void testRequestProfileInhibit_withoutBluetoothPrivileged_throwsSecurityException() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        mockPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                PackageManager.PERMISSION_DENIED);
        initServiceUnderTest();

        assertThrows(SecurityException.class,
                () -> mCarBluetoothService.requestProfileInhibit(device, A2DP_SINK, mToken));
    }

    // Release

    @Test
    public void testReleaseProfileInhibit_setsConnectionPolicyAllowed() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        doReturn(CONNECTION_POLICY_ALLOWED).when(mMockBluetoothUserService)
                .getConnectionPolicy(A2DP_SINK, device);
        doReturn(true).when(mMockBluetoothUserService)
                .isBluetoothConnectionProxyAvailable(A2DP_SINK);
        initServiceUnderTest();

        assertThat(mCarBluetoothService.requestProfileInhibit(device, A2DP_SINK, mToken)).isTrue();
        verify(mMockBluetoothUserService, times(1))
                .setConnectionPolicy(eq(A2DP_SINK), eq(device), eq(CONNECTION_POLICY_FORBIDDEN));

        doReturn(CONNECTION_POLICY_FORBIDDEN).when(mMockBluetoothUserService)
                .getConnectionPolicy(A2DP_SINK, device);

        assertThat(mCarBluetoothService.releaseProfileInhibit(device, A2DP_SINK, mToken)).isTrue();
        verify(mMockBluetoothUserService, times(1))
                .setConnectionPolicy(eq(A2DP_SINK), eq(device), eq(CONNECTION_POLICY_ALLOWED));
    }

    @Test
    public void testReleaseProfileInhibit_withoutBluetoothConnect_throwsSecurityException() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        mockPermission(android.Manifest.permission.BLUETOOTH_CONNECT,
                PackageManager.PERMISSION_DENIED);
        initServiceUnderTest();

        assertThrows(SecurityException.class,
                () -> mCarBluetoothService.releaseProfileInhibit(device, A2DP_SINK, mToken));
    }

    @Test
    public void testReleaseProfileInhibit_withoutBluetoothPrivileged_throwsSecurityException() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        mockPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                PackageManager.PERMISSION_DENIED);
        initServiceUnderTest();

        assertThrows(SecurityException.class,
                () -> mCarBluetoothService.releaseProfileInhibit(device, A2DP_SINK, mToken));
    }

    // isProfileInihibited

    @Test
    public void testIsProfileInhibited_defaultFalse() {
        initServiceUnderTest();

        Assert.assertFalse(mCarBluetoothService.isProfileInhibited(mock(BluetoothDevice.class),
                A2DP_SINK, mToken));
    }

    @Test
    public void testIsProfileInhibited_profileIsInhibited_returnsTrue() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(mMockBluetoothUserService.getConnectionPolicy(A2DP_SINK, device))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);
        when(mMockBluetoothUserService.isBluetoothConnectionProxyAvailable(A2DP_SINK))
                .thenReturn(true);
        initServiceUnderTest();

        mCarBluetoothService.requestProfileInhibit(device, A2DP_SINK, mToken);

        Assert.assertTrue(mCarBluetoothService.isProfileInhibited(device, A2DP_SINK, mToken));
    }

    @Test
    public void testIsProfileInhibited_profileIsNotInhibited_returnsFalse() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        when(mMockBluetoothUserService.getConnectionPolicy(A2DP_SINK, device))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);
        when(mMockBluetoothUserService.isBluetoothConnectionProxyAvailable(A2DP_SINK))
                .thenReturn(true);
        initServiceUnderTest();
        mCarBluetoothService.requestProfileInhibit(device, A2DP_SINK, mToken);

        Assert.assertTrue(mCarBluetoothService.isProfileInhibited(device, A2DP_SINK, mToken));
    }

    @Test
    public void testIsProfileInhibited_withoutBluetoothConnect_throwsSecurityException() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        mockPermission(android.Manifest.permission.BLUETOOTH_CONNECT,
                PackageManager.PERMISSION_DENIED);
        initServiceUnderTest();

        assertThrows(SecurityException.class,
                () -> mCarBluetoothService.isProfileInhibited(device, A2DP_SINK, mToken));
    }

    @Test
    public void testIsProfileInhibited_withoutBluetoothPrivileged_throwsSecurityException() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        mockPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                PackageManager.PERMISSION_DENIED);
        initServiceUnderTest();

        assertThrows(SecurityException.class,
                () -> mCarBluetoothService.isProfileInhibited(device, A2DP_SINK, mToken));
    }

    //--------------------------------------------------------------------------------------------//
    // Utilities                                                                                  //
    //--------------------------------------------------------------------------------------------//

    private void initServiceUnderTest() {
        mCarBluetoothService = new CarBluetoothService(mMockContext, mMockUserSwitchService);
        mCarBluetoothService.init();
        mUserSwitchCallback.onServiceConnected(mMockCarPerUserService);
    }

    private void mockPermission(String permission, int setting) {
        doReturn(setting).when(mMockContext).checkCallingOrSelfPermission(permission);
    }
}
