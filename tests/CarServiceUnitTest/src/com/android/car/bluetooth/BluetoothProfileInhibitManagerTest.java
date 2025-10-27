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

package com.android.car.bluetooth;

import static android.bluetooth.BluetoothProfile.A2DP_SINK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.car.ICarBluetoothUserService;
import android.car.test.NoActiveHandlerThreadCheckerRule;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link BluetoothProfileInhibitManager}
 *
 * Run:
 * atest BluetoothProfileInhibitManagerTest
 */
@RunWith(JUnit4.class)
public class BluetoothProfileInhibitManagerTest {

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule
    public NoActiveHandlerThreadCheckerRule mNoActiveHandlerThreadCheckerRule =
            new NoActiveHandlerThreadCheckerRule();

    private static final int TEST_USER_ID = 10;

    @Mock private Context mMockContext;
    private MockContentResolver mMockContentResolver;
    private MockContentProvider mMockContentProvider;
    @Mock private ICarBluetoothUserService mMockCarBluetoothUserService;
    @Mock private BluetoothManager mMockBluetoothManager;
    @Mock private BluetoothAdapter mMockBluetoothAdapter;
    @Mock private BluetoothDevice mMockBluetoothDevice;

    private BluetoothProfileInhibitManager mBluetoothProfileInhibitManager;

    private IBinder mToken = new Binder();

    //-------------------------------------------------------------------------------------------//
    // Setup/TearDown                                                                             //
    //--------------------------------------------------------------------------------------------//

    @Before
    public void setUp() throws Exception {
        // Mock Context
        when(mMockContext.createContextAsUser(any(), anyInt())).thenReturn(mMockContext);

        // Mock ContentResolver calls so Settings Provider can be used
        mMockContentResolver = new MockContentResolver(null);
        mMockContentProvider = new MockContentProvider() {
            @Override
            public Bundle call(String method, String request, Bundle args) {
                return new Bundle();
            }
        };
        mMockContentResolver.addProvider(Settings.AUTHORITY, mMockContentProvider);
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);

        // Mock Bluetooth Manager, Adapter and Device
        when(mMockContext.getSystemService(BluetoothManager.class))
                .thenReturn(mMockBluetoothManager);
        when(mMockBluetoothManager.getAdapter()).thenReturn(mMockBluetoothAdapter);
        when(mMockBluetoothAdapter.getRemoteDevice(anyString())).thenReturn(mMockBluetoothDevice);

        // Mock CarBluetoothUserService for ConnectionPolicy calls
        when(mMockCarBluetoothUserService.isBluetoothConnectionProxyAvailable(anyInt()))
                .thenReturn(true);
        when(mMockCarBluetoothUserService.getConnectionPolicy(anyInt(), any()))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);

        mBluetoothProfileInhibitManager = new BluetoothProfileInhibitManager(
                mMockContext,
                TEST_USER_ID,
                mMockCarBluetoothUserService);

        mBluetoothProfileInhibitManager.start();
    }

    @After
    public void tearDown() {
        mBluetoothProfileInhibitManager.stop();
    }

    @Test
    public void testIsProfileInhibited_default_isNotInhibited() {
        assertThat(
                mBluetoothProfileInhibitManager.isProfileInhibited(mMockBluetoothDevice, A2DP_SINK,
                        mToken)).isFalse();
    }

    @Test
    public void testIsProfileInhibited_inhibitRequested_isInhibited() throws Exception {
        mBluetoothProfileInhibitManager.requestProfileInhibit(mMockBluetoothDevice, A2DP_SINK,
                mToken);
        when(mMockCarBluetoothUserService.getConnectionPolicy(A2DP_SINK, mMockBluetoothDevice))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);

        assertThat(
                mBluetoothProfileInhibitManager.isProfileInhibited(mMockBluetoothDevice, A2DP_SINK,
                        mToken)).isTrue();
    }

    @Test
    public void testIsProfileInhibited_proxyNotAvailable_isNotInhibited() throws Exception {
        mBluetoothProfileInhibitManager.requestProfileInhibit(mMockBluetoothDevice, A2DP_SINK,
                mToken);
        when(mMockCarBluetoothUserService.isBluetoothConnectionProxyAvailable(
                A2DP_SINK)).thenReturn(false);

        assertThat(
                mBluetoothProfileInhibitManager.isProfileInhibited(mMockBluetoothDevice, A2DP_SINK,
                        mToken)).isFalse();
    }

    @Test
    public void testIsProfileInhibited_profileAllowed_isNotInhibited() throws Exception {
        mBluetoothProfileInhibitManager.requestProfileInhibit(mMockBluetoothDevice, A2DP_SINK,
                mToken);
        when(mMockCarBluetoothUserService.getConnectionPolicy(A2DP_SINK, mMockBluetoothDevice))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);

        assertThat(
                mBluetoothProfileInhibitManager.isProfileInhibited(mMockBluetoothDevice, A2DP_SINK,
                        mToken)).isFalse();
    }

    @Test
    public void testIsProfileInhibited_inhibitReleased_isNotInhibited() {
        mBluetoothProfileInhibitManager.requestProfileInhibit(mMockBluetoothDevice, A2DP_SINK,
                mToken);
        mBluetoothProfileInhibitManager.releaseProfileInhibit(mMockBluetoothDevice, A2DP_SINK,
                mToken);

        assertThat(
                mBluetoothProfileInhibitManager.isProfileInhibited(mMockBluetoothDevice, A2DP_SINK,
                        mToken)).isFalse();
    }
}
