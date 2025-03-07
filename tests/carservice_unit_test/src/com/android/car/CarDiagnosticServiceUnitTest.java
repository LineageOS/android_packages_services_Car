/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.car;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.diagnostic.CarDiagnosticManager;
import android.car.diagnostic.ICarDiagnosticEventListener;
import android.content.Context;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;

import com.android.car.hal.DiagnosticHalService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for {@link CarDiagnosticService}.
 *
 * <p>This is added later to improve test coverage. Most of the logic is covered
 * in {@link com.android.car.diagnostic.CarDiagnosticManagerTest} instead.
 */
@RunWith(MockitoJUnitRunner.class)
public class CarDiagnosticServiceUnitTest {

    @Mock
    private DiagnosticHalService mDiagnosticHalService;
    @Mock
    private Context mContext;

    private CarDiagnosticService mService;

    @Before
    public void setUp() {
        mService = new CarDiagnosticService(mContext, mDiagnosticHalService);

        when(mDiagnosticHalService.isReady()).thenReturn(true);
    }

    @Test
    public void testRegisterOrUpdateDiagnosticListener() {
        int testRate = 1;
        ICarDiagnosticEventListener listener = mock(ICarDiagnosticEventListener.class);
        IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);
        when(mDiagnosticHalService.requestDiagnosticStart(anyInt(), anyInt())).thenReturn(true);

        boolean result = mService.registerOrUpdateDiagnosticListener(
                CarDiagnosticManager.FRAME_TYPE_LIVE, testRate, listener);

        assertWithMessage("registerOrUpdateDiagnosticListener result")
                .that(result).isTrue();
        verify(mDiagnosticHalService).requestDiagnosticStart(
                CarDiagnosticManager.FRAME_TYPE_LIVE, testRate);
        assertWithMessage("Registered client size").that(mService.countClients()).isEqualTo(1);
    }

    @Test
    public void testListenerBinderDied() throws Exception {
        int testRate = 1;
        ICarDiagnosticEventListener listener = mock(ICarDiagnosticEventListener.class);
        IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);
        when(mDiagnosticHalService.requestDiagnosticStart(anyInt(), anyInt())).thenReturn(true);
        // An array to capture death recipient.
        var deathRecipientCap = new DeathRecipient[1];
        doAnswer((inv) -> {
            deathRecipientCap[0] = (DeathRecipient) (inv.getArgument(0));
            return null;
        }).when(listenerBinder).linkToDeath(any(), anyInt());

        mService.registerOrUpdateDiagnosticListener(
                CarDiagnosticManager.FRAME_TYPE_LIVE, testRate, listener);

        verify(listenerBinder).linkToDeath(any(), anyInt());

        // Simulate the client binder died.
        deathRecipientCap[0].binderDied();

        // In reality we call this twice, once at binderDied(), once at release().
        verify(listenerBinder, atLeastOnce()).unlinkToDeath(any(), anyInt());
        verify(mDiagnosticHalService).requestDiagnosticStop(CarDiagnosticManager.FRAME_TYPE_LIVE);
        assertWithMessage("Registered client size").that(mService.countClients()).isEqualTo(0);
    }
}
