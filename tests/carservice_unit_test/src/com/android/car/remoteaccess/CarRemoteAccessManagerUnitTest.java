/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.remoteaccess.CarRemoteAccessManager;
import android.car.remoteaccess.ICarRemoteAccessCallback;
import android.car.remoteaccess.ICarRemoteAccessService;
import android.content.Context;
import android.os.IBinder;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.concurrent.Executor;

@RunWith(MockitoJUnitRunner.class)
public final class CarRemoteAccessManagerUnitTest {

    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Executor mExecutor = mContext.getMainExecutor();

    private CarRemoteAccessManager mRemoteAccessManager;

    @Mock private Car mCar;
    @Mock private IBinder mBinder;
    @Mock private ICarRemoteAccessService mService;

    @Before
    public void setUp() throws Exception {
        when(mBinder.queryLocalInterface(anyString())).thenReturn(mService);
        mRemoteAccessManager = new CarRemoteAccessManager(mCar, mBinder);
    }

    @Test
    public void testSetRemoteTaskClient() throws Exception {
        RemoteTaskClient remoteTaskClient = new RemoteTaskClient();

        mRemoteAccessManager.setRemoteTaskClient(mExecutor, remoteTaskClient);

        verify(mService).addCarRemoteTaskClient(any(ICarRemoteAccessCallback.class));
    }

    // TODO(b/134519794): Implement more test cases.

    private static final class RemoteTaskClient
            implements CarRemoteAccessManager.RemoteTaskClientCallback {

        @Override
        public void onRegistrationUpdated(String serviceId, String deviceId, String clientId) {
        }

        @Override
        public void onRegistrationFailed() {
        }

        @Override
        public void onRemoteTaskRequested(String clientId, byte[] data, int remainingTimeSec) {
        }

        @Override
        public void onShutdownStarting(CarRemoteAccessManager.CompletableRemoteTaskFuture future) {
        }
    }
}
