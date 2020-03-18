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

package android.car.watchdoglib;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.automotive.watchdog.ICarWatchdog;
import android.automotive.watchdog.ICarWatchdogClient;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

import java.util.ArrayList;

/**
 * <p>This class contains unit tests for the {@link CarWatchdogDaemonHelper}.
 */
@RunWith(MockitoJUnitRunner.class)
public class CarWatchdogDaemonHelperTest {

    private static final String CAR_WATCHDOG_DAEMON_INTERFACE =
            "android.automotive.watchdog.ICarWatchdog/default";

    @Mock private IBinder mBinder = new Binder();
    private ICarWatchdog mFakeCarWatchdog = new FakeCarWatchdog();
    private CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    private MockitoSession mMockSession;

    @Before
    public void setUp() {
        mMockSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(ServiceManager.class)
                .startMocking();
        expectLocalWatchdogDaemonToWork();
        mCarWatchdogDaemonHelper = new CarWatchdogDaemonHelper();
        mCarWatchdogDaemonHelper.connect();
    }

    @After
    public void tearDown() {
        mMockSession.finishMocking();
    }

    /*
     * Test that the {@link CarWatchdogDaemonHelper} throws {@code IllegalArgumentException} when
     * trying to register already-registered client again.
     */
    @Test
    public void testMultipleRegistration() throws RemoteException {
        ICarWatchdogClient client = new ICarWatchdogClientImpl();
        mCarWatchdogDaemonHelper.registerMediator(client);
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogDaemonHelper.registerMediator(client));
    }

    /*
     * Test that the {@link CarWatchdogDaemonHelper} throws {@code IllegalArgumentException} when
     * trying to unregister not-registered client.
     */
    @Test
    public void testInvalidUnregistration() throws RemoteException {
        ICarWatchdogClient client = new ICarWatchdogClientImpl();
        assertThrows(IllegalArgumentException.class,
                () -> mCarWatchdogDaemonHelper.unregisterMediator(client));
    }

    private void expectLocalWatchdogDaemonToWork() {
        when(ServiceManager.getService(CAR_WATCHDOG_DAEMON_INTERFACE)).thenReturn(mBinder);
        doReturn(mFakeCarWatchdog).when(mBinder).queryLocalInterface(anyString());
    }

    // FakeCarWatchdog mimics ICarWatchdog daemon in local process.
    private final class FakeCarWatchdog extends ICarWatchdog.Default {

        private final ArrayList<ICarWatchdogClient> mClients = new ArrayList<>();

        @Override
        public void registerMediator(ICarWatchdogClient mediator) throws RemoteException {
            for (ICarWatchdogClient client : mClients) {
                if (client == mediator) {
                    throw new IllegalArgumentException("Already registered mediator");
                }
            }
            mClients.add(mediator);
        }

        @Override
        public void unregisterMediator(ICarWatchdogClient mediator) throws RemoteException {
            for (ICarWatchdogClient client : mClients) {
                if (client == mediator) {
                    mClients.remove(mediator);
                    return;
                }
            }
            throw new IllegalArgumentException("Not registered mediator");
        }
    }

    private final class ICarWatchdogClientImpl extends ICarWatchdogClient.Stub {
        @Override
        public void checkIfAlive(int sessionId, int timeout) {}
    }
}
