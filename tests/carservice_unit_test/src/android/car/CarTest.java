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

package android.car;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

/**
 * Unit test for Car API.
 */
@RunWith(AndroidJUnit4.class)
public class CarTest {
    private static final String TAG = CarTest.class.getSimpleName();

    private MockitoSession mMockingSession;

    @Mock
    private Context mContext;

    // It is tricky to mock this. So create dummy version instead.
    private ICar.Stub mService = new ICar.Stub() {
        @Override
        public void setCarServiceHelper(android.os.IBinder helper) {
        }

        @Override
        public void setUserLockStatus(int userHandle, int unlocked) {
        }

        @Override
        public void onSwitchUser(int userHandle) {
        }

        @Override
        public android.os.IBinder getCarService(java.lang.String serviceName) {
            return null;
        }

        @Override
        public int getCarConnectionType() {
            return 0;
        }
    };

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(ServiceManager.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
    }

    @After
    public void tearDown() {
        mMockingSession.finishMocking();
    }

    private void expectService(@Nullable IBinder service) {
        doReturn(service).when(
                () -> ServiceManager.getService(Car.CAR_SERVICE_BINDER_SERVICE_NAME));
    }

    @Test
    public void testCreateCarSuccessWithCarServiceRunning() {
        expectService(mService);
        assertThat(Car.createCar(mContext)).isNotNull();
    }

    @Test
    public void testCreateCarReturnNull() {
        // car service is not running yet and bindService does not bring the service yet.
        // createCar should timeout and give up.
        expectService(null);
        assertThat(Car.createCar(mContext)).isNull();
    }

    @Test
    public void testCreateCarOkWhenCarServiceIsStarted() {
        // Car service is not running yet and binsService call should start it.
        when(mContext.bindServiceAsUser(anyObject(), anyObject(), anyInt(),
                anyObject())).thenReturn(true);
        final int returnNonNullAfterThisCall = 10;
        doAnswer(new Answer() {

            private int mCallCount = 0;

            @Override
            public Object answer(InvocationOnMock invocation) {
                mCallCount++;
                if (mCallCount > returnNonNullAfterThisCall) {
                    return mService;
                } else {
                    return null;
                }
            }
        }).when(() -> ServiceManager.getService(Car.CAR_SERVICE_BINDER_SERVICE_NAME));
        Car car = Car.createCar(mContext);
        assertThat(car).isNotNull();
        verify(mContext, times(1)).bindServiceAsUser(anyObject(), anyObject(),
                anyInt(), anyObject());

        // Just call these to guarantee that nothing crashes when service is connected /
        // disconnected.
        car.getServiceConnectionListener().onServiceConnected(new ComponentName("", ""), mService);
        car.getServiceConnectionListener().onServiceDisconnected(new ComponentName("", ""));
    }
}
