/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.watchdog;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.automotive.watchdog.ICarWatchdog;
import android.automotive.watchdog.ICarWatchdogClient;
import android.automotive.watchdog.TimeoutLength;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * <p>This class contains unit tests for the {@link CarWatchdogService}.
 */
@RunWith(MockitoJUnitRunner.class)
public class CarWatchdogServiceTest {

    private static final String TAG = CarWatchdogServiceTest.class.getSimpleName();
    private static final String CAR_WATCHDOG_DAEMON_INTERFACE =
            "android.automotive.watchdog.ICarWatchdog/default";

    @Mock private Context mMockContext;
    @Mock private UserManager mUserManager;
    @Spy private IBinder mBinder = new Binder();

    private FakeCarWatchdog mFakeCarWatchdog;
    private CarWatchdogService mCarWatchdogService;
    private MockitoSession mMockSession;

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUpMocks() {
        mMockSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(ServiceManager.class)
                .spyStatic(UserManager.class)
                .startMocking();
        mFakeCarWatchdog = new FakeCarWatchdog();
        mCarWatchdogService = new CarWatchdogService(mMockContext);
        expectLocalWatchdogDaemon();
        expectNoUsers();
    }

    @After
    public void tearDown() {
        mMockSession.finishMocking();
    }

    /**
     * Test that the {@link CarWatchdogService} binds to car watchdog daemon.
     */
    @Test
    public void testInitializeCarWatchdService() throws Exception {
        mFakeCarWatchdog.setMediatorCheckCount(1);
        mCarWatchdogService.init();
        mFakeCarWatchdog.waitForMediatorResponse();
        assertThat(mFakeCarWatchdog.getClientCount()).isEqualTo(1);
        assertThat(mFakeCarWatchdog.gotResponse()).isTrue();
    }

    private void expectLocalWatchdogDaemon() {
        when(ServiceManager.getService(CAR_WATCHDOG_DAEMON_INTERFACE)).thenReturn(mBinder);
        doReturn(mFakeCarWatchdog).when(mBinder).queryLocalInterface(anyString());
    }

    private void expectNoUsers() {
        doReturn(mUserManager).when(mMockContext).getSystemService(Context.USER_SERVICE);
        doReturn(new ArrayList<UserInfo>()).when(mUserManager).getUsers();
    }

    // FakeCarWatchdog mimics ICarWatchdog daemon in local process.
    private final class FakeCarWatchdog extends ICarWatchdog.Default {

        private static final int TEST_SESSION_ID = 11223344;
        private static final int THREE_SECONDS_IN_MS = 3_000;

        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final List<ICarWatchdogClient> mClients = new ArrayList<>();
        private long mLastPingTimeMs;
        private boolean mGotResponse;
        private int mNumberOfKilledClients;
        private CountDownLatch mClientResponse;
        private int mMediatorCheckCount;

        public int getClientCount() {
            return mClients.size();
        }

        public boolean gotResponse() {
            return mGotResponse;
        }

        public int getNumberOfKilledClients() {
            return mNumberOfKilledClients;
        }

        public void setMediatorCheckCount(int count) {
            mClientResponse = new CountDownLatch(count);
            mMediatorCheckCount = count;
        }

        void waitForMediatorResponse() throws TimeoutException, InterruptedException {
            long waitTime = THREE_SECONDS_IN_MS * mMediatorCheckCount;
            if (!mClientResponse.await(waitTime, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Mediator doesn't respond within timeout("
                        + waitTime + "ms)");
            }
        }

        @Override
        public IBinder asBinder() {
            return mBinder;
        }

        @Override
        public void registerMediator(ICarWatchdogClient mediator) throws RemoteException {
            mClients.add(mediator);
            mMainHandler.post(() -> {
                checkMediator();
            });
        }

        @Override
        public void unregisterMediator(ICarWatchdogClient mediator) throws RemoteException {
            mClients.remove(mediator);
        }

        @Override
        public void tellMediatorAlive(ICarWatchdogClient mediator, int[] clientsNotResponding,
                int sessionId) throws RemoteException {
            long currentTimeMs = SystemClock.uptimeMillis();
            if (sessionId == TEST_SESSION_ID && mClients.contains(mediator)
                    && currentTimeMs < mLastPingTimeMs + THREE_SECONDS_IN_MS) {
                mGotResponse = true;
            }
            mNumberOfKilledClients += clientsNotResponding.length;
            mClientResponse.countDown();
        }

        public void checkMediator() {
            checkMediatorInternal(mMediatorCheckCount);
        }

        private void checkMediatorInternal(int count) {
            for (ICarWatchdogClient client : mClients) {
                try {
                    client.checkIfAlive(TEST_SESSION_ID, TimeoutLength.TIMEOUT_CRITICAL);
                } catch (RemoteException e) {
                    // Ignore.
                }
            }
            mLastPingTimeMs = SystemClock.uptimeMillis();
            if (count > 1) {
                mMainHandler.postDelayed(
                        () -> checkMediatorInternal(count - 1), THREE_SECONDS_IN_MS);
            }
        }
    }

    private final class CarWatchdogClient extends ICarWatchdogClient.Stub {
        private final WeakReference<CarWatchdogService> mService;
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final boolean mRespond;

        private CarWatchdogClient(CarWatchdogService service, boolean respond) {
            mService = new WeakReference<>(service);
            mRespond = respond;
        }
        @Override
        public void checkIfAlive(int sessionId, int timeout) {
            mMainHandler.post(() -> {
                CarWatchdogService service = mService.get();
                if (service != null && mRespond) {
                    service.tellClientAlive(this, sessionId);
                }
            });
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }
    }
}
