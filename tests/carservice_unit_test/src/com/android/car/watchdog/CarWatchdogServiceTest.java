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
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * <p>This class contains unit tests for the {@link CarWatchdogService}.
 */
@RunWith(MockitoJUnitRunner.class)
public class CarWatchdogServiceTest {

    private static final String TAG = CarWatchdogServiceTest.class.getSimpleName();
    private static final String CAR_WATCHDOG_DAEMON_INTERFACE =
            "android.automotive.watchdog.ICarWatchdog/default";
    private static final long TIMEOUT_CRITICAL_DURATION = 3000L;
    private static final long TIMEOUT_BUFFER = 50L;

    @Mock private Context mMockContext;
    @Mock private IBinder mBinder = new Binder();

    private FakeCarWatchdog mFakeCarWatchdog;
    private CarWatchdogService mCarWatchdogService;
    private MockitoSession mMockSession;

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUpMocks() {
        mMockSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(ServiceManager.class)
                .startMocking();
        mFakeCarWatchdog = new FakeCarWatchdog();
        mCarWatchdogService = new CarWatchdogService(mMockContext);
        expectLocalWatchdogDaemon();
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
        mCarWatchdogService.init();
        mFakeCarWatchdog.waitForMediatorResponse();
        assertThat(mFakeCarWatchdog.getClientCount()).isEqualTo(1);
        assertThat(mFakeCarWatchdog.gotResponse()).isTrue();
    }

    @Test
    public void testClientNotResponding() throws Exception {
        mCarWatchdogService.init();
        mFakeCarWatchdog.waitForMediatorResponse();
        CarWatchdogClient client = new CarWatchdogClient(mCarWatchdogService, false);
        mCarWatchdogService.registerClient(client, TimeoutLength.TIMEOUT_CRITICAL);
        mFakeCarWatchdog.checkMediator(2);
        SystemClock.sleep(TIMEOUT_CRITICAL_DURATION + TIMEOUT_BUFFER);
        assertThat(mFakeCarWatchdog.getNumberOfKilledClients()).isEqualTo(1);
    }

    @Test
    public void testClientResponding() throws Exception {
        mCarWatchdogService.init();
        mFakeCarWatchdog.waitForMediatorResponse();
        CarWatchdogClient client = new CarWatchdogClient(mCarWatchdogService, true);
        mCarWatchdogService.registerClient(client, TimeoutLength.TIMEOUT_CRITICAL);
        mFakeCarWatchdog.checkMediator(2);
        SystemClock.sleep(TIMEOUT_CRITICAL_DURATION + TIMEOUT_BUFFER);
        assertThat(mFakeCarWatchdog.getNumberOfKilledClients()).isEqualTo(0);
    }

    private void expectLocalWatchdogDaemon() {
        when(ServiceManager.getService(CAR_WATCHDOG_DAEMON_INTERFACE)).thenReturn(mBinder);
        doReturn(mFakeCarWatchdog).when(mBinder).queryLocalInterface(anyString());
    }

    // FakeCarWatchdog mimics ICarWatchdog daemon in local process.
    private final class FakeCarWatchdog extends ICarWatchdog.Default {

        private static final int TEST_SESSION_ID = 11223344;
        private static final int TEN_SECONDS_IN_MS = 10000;

        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final List<ICarWatchdogClient> mClients = new ArrayList<>();
        private long mLastPingTimeMs;
        private boolean mGotResponse;
        private int mNumberOfKilledClients;
        private CountDownLatch mClientResponse = new CountDownLatch(1);

        public int getClientCount() {
            return mClients.size();
        }

        public boolean gotResponse() {
            return mGotResponse;
        }

        public int getNumberOfKilledClients() {
            return mNumberOfKilledClients;
        }

        void waitForMediatorResponse() throws InterruptedException {
            if (!mClientResponse.await(TEN_SECONDS_IN_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Mediator doesn't respond within timeout(" + TEN_SECONDS_IN_MS + "ms)");
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
                try {
                    mediator.checkIfAlive(TEST_SESSION_ID, TimeoutLength.TIMEOUT_CRITICAL);
                } catch (RemoteException e) {
                    // Do nothing.
                }
                mLastPingTimeMs = System.currentTimeMillis();
            });
        }

        @Override
        public void unregisterMediator(ICarWatchdogClient mediator) throws RemoteException {
            mClients.remove(mediator);
        }

        @Override
        public void tellMediatorAlive(ICarWatchdogClient mediator, int[] clientsNotResponding,
                int sessionId) throws RemoteException {
            long currentTimeMs = System.currentTimeMillis();
            if (sessionId == TEST_SESSION_ID && mClients.contains(mediator)
                    && currentTimeMs < mLastPingTimeMs + TEN_SECONDS_IN_MS) {
                mGotResponse = true;
            }
            mNumberOfKilledClients += clientsNotResponding.length;
            mClientResponse.countDown();
        }

        public void checkMediator(int count) {
            for (ICarWatchdogClient client : mClients) {
                try {
                    client.checkIfAlive(TEST_SESSION_ID, TimeoutLength.TIMEOUT_CRITICAL);
                } catch (RemoteException e) {
                    // Ignore.
                }
            }
            if (count > 1) {
                mMainHandler.postDelayed(() -> checkMediator(count - 1), TIMEOUT_CRITICAL_DURATION);
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
            Log.i(TAG, "[ERIC] test client's checkIfAlive is called");
            mMainHandler.post(() -> {
                CarWatchdogService service = mService.get();
                if (service != null && mRespond) {
                    Log.i(TAG, "[ERIC] responding to CarWatchdogService: sessio id = " + sessionId);
                    service.tellClientAlive(this, sessionId);
                }
            });
        }
    }
}
