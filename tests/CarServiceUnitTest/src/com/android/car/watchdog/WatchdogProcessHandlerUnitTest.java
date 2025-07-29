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

package com.android.car.watchdog;

import static android.car.watchdog.CarWatchdogManager.TIMEOUT_CRITICAL;
import static android.car.watchdog.CarWatchdogManager.TIMEOUT_NORMAL;

import static com.android.car.internal.common.CommonConstants.INVALID_PID;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.automotive.watchdog.internal.ICarWatchdogServiceForSystem;
import android.automotive.watchdog.internal.ProcessIdentifier;
import android.car.test.NoActiveHandlerThreadCheckerRule;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.watchdog.ICarWatchdogServiceCallback;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.car.CarLocalServices;
import com.android.car.CarServiceHelperWrapper;
import com.android.car.CarServiceUtils;
import com.android.car.internal.ICarServiceHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

/**
 * <p>This class contains unit tests for the {@link WatchdogProcessHandler}.
 */
@RunWith(MockitoJUnitRunner.class)
public class WatchdogProcessHandlerUnitTest extends AbstractExtendedMockitoTestCase {
    private static final int MAX_WAIT_TIME_MS = 3000;
    private static final int INVALID_SESSION_ID = -1;
    private static final String CANONICAL_PACKAGE_NAME =
            WatchdogProcessHandlerUnitTest.class.getCanonicalName();
    private static final String CAR_WATCHDOG_SERVICE_NAME =
            CarWatchdogService.class.getSimpleName();

    @Rule
    public NoActiveHandlerThreadCheckerRule mNoActiveHandlerThreadCheckerRule =
            new NoActiveHandlerThreadCheckerRule();

    @Mock
    private CarWatchdogDaemonHelper mMockCarWatchdogDaemonHelper;
    @Mock
    private ICarServiceHelper.Stub mMockCarServiceHelper;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock private UserHandle mMockUserHandle;
    @Captor
    private ArgumentCaptor<List<ProcessIdentifier>> mProcessIdentifiersCaptor;
    private WatchdogProcessHandler mWatchdogProcessHandler;
    private ICarWatchdogServiceForSystem mWatchdogServiceForSystemImpl;
    private final SparseArray<String> mGenericPackageNameByUid = new SparseArray<>();
    private static final int TEST_CLIENT_UID = Binder.getCallingUid();
    private static final int TEST_CLIENT_USER_ID = 100;

    private HandlerThread mHandlerThread;
    private Handler mHandler;

    public WatchdogProcessHandlerUnitTest() {
        super(CarWatchdogService.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder
                .spyStatic(Binder.class)
                .spyStatic(CarLocalServices.class)
                .spyStatic(UserHandle.class);
    }

    @Before
    public void setUp() throws Exception {
        mHandlerThread = CarServiceUtils.getHandlerThread(CAR_WATCHDOG_SERVICE_NAME);
        mHandler = new Handler(mHandlerThread.getLooper());

        mockPackageManager();
        mWatchdogProcessHandler = new WatchdogProcessHandler(mWatchdogServiceForSystemImpl,
                mMockCarWatchdogDaemonHelper,
                new PackageInfoHandler(mMockPackageManager), mHandler);
        mWatchdogProcessHandler.init();
        CarServiceHelperWrapper wrapper = CarServiceHelperWrapper.create();
        wrapper.setCarServiceHelper(mMockCarServiceHelper);
        mGenericPackageNameByUid.put(TEST_CLIENT_UID, CANONICAL_PACKAGE_NAME);
        doReturn(mMockUserHandle).when(() -> UserHandle.of(TEST_CLIENT_UID));
        doReturn(TEST_CLIENT_USER_ID).when(mMockUserHandle).getIdentifier();
    }

    /**
     * Releases resources.
     */
    @After
    public void tearDown() throws Exception {
        CarLocalServices.removeServiceForTest(CarServiceHelperWrapper.class);
        CarServiceUtils.releaseHandlerThread(CAR_WATCHDOG_SERVICE_NAME);
    }

    @Test
    public void testPrepareHealthCheck() throws Exception {
        // Register bad client; bad clients do not respond to CarWatchdogService when pinged
        TestClient badClient = new BadTestClient();
        mWatchdogProcessHandler.registerClient(badClient, TIMEOUT_CRITICAL);

        // Start a health check, which will ping registered clients.
        postHealthCheckMessageAndWait(123456);

        // Clears all pending clients
        mWatchdogProcessHandler.prepareHealthCheck();

        // Since the pending clients cache was cleared, CarWatchdogService won't
        // call onPrepareProcessTermination on our bad client even if it did not
        // respond to the initial ping.
        postHealthCheckMessageAndWait(123456);

        // Check if bad client received onPrepareProcessTermination
        assertWithMessage("Pinged clients not resetting").that(
            badClient.getReceivedOnPrepareProcessTermination()).isEqualTo(false);
    }

    @Test
    public void testUpdateUserState() throws Exception {
        TestClient client = new TestClient();
        registerClientAndWait(client, TIMEOUT_CRITICAL);

        mWatchdogProcessHandler.updateUserState(100, true);

        ProtoOutputStream proto = new ProtoOutputStream();
        mWatchdogProcessHandler.dumpProto(proto);

        CarWatchdogDumpProto carWatchdogDumpProto = CarWatchdogDumpProto.parseFrom(
                proto.getBytes());
        CarWatchdogDumpProto.SystemHealthDump systemHealthDump =
                carWatchdogDumpProto.getSystemHealthDump();

        expectWithMessage("Stopped Users Count").that(
            systemHealthDump.getStoppedUsersCount()).isEqualTo(1);
        expectWithMessage("Stopped User").that(systemHealthDump.getStoppedUsers(0)).isEqualTo(100);

        mWatchdogProcessHandler.updateUserState(100, false);

        proto = new ProtoOutputStream();
        mWatchdogProcessHandler.dumpProto(proto);

        carWatchdogDumpProto = CarWatchdogDumpProto.parseFrom(proto.getBytes());
        systemHealthDump = carWatchdogDumpProto.getSystemHealthDump();

        expectWithMessage("Stopped Users Count").that(
            systemHealthDump.getStoppedUsersCount()).isEqualTo(0);
    }

    @Test
    public void testPostHealthCheckMessage() throws Exception {
        postHealthCheckMessageAndWait(123456);

        verify(mMockCarWatchdogDaemonHelper).tellCarWatchdogServiceAlive(
                eq(mWatchdogServiceForSystemImpl), mProcessIdentifiersCaptor.capture(), eq(123456));
        assertWithMessage("clients not responding").that(
                mProcessIdentifiersCaptor.getValue().size()).isEqualTo(0);
    }

    @Test
    public void testRegisterClient() throws Exception {
        TestClient client = new TestClient();

        registerClientAndWait(client, TIMEOUT_CRITICAL);

        assertWithMessage("Critical timeout client count").that(
                mWatchdogProcessHandler.getClientCount(TIMEOUT_CRITICAL)).isEqualTo(1);

        postHealthCheckMessageAndWait(123456);

        verify(mMockCarWatchdogDaemonHelper).tellCarWatchdogServiceAlive(
                eq(mWatchdogServiceForSystemImpl), any(), eq(123456));

        assertThat(client.getLastSessionId()).isNotEqualTo(INVALID_SESSION_ID);
    }

    @Test
    public void testDoubleRegisterClient() throws Exception {
        TestClient client = new TestClient();

        registerClientAndWait(client, TIMEOUT_CRITICAL);

        assertThrows(IllegalStateException.class,
                () -> mWatchdogProcessHandler.registerClient(client, TIMEOUT_CRITICAL));
    }

    @Test
    public void testUnregisterClient() throws Exception {
        TestClient client = new TestClient();

        registerClientAndWait(client, TIMEOUT_CRITICAL);

        assertWithMessage("Critical timeout client count").that(
                mWatchdogProcessHandler.getClientCount(TIMEOUT_CRITICAL)).isEqualTo(1);

        mWatchdogProcessHandler.unregisterClient(client);

        assertWithMessage("Critical timeout client count").that(
                mWatchdogProcessHandler.getClientCount(TIMEOUT_CRITICAL)).isEqualTo(0);

        postHealthCheckMessageAndWait(123456);

        assertThat(client.getLastSessionId()).isEqualTo(INVALID_SESSION_ID);
    }

    @Test
    public void testGoodClientHealthCheck() throws Exception {
        testClientHealthCheck(new TestClient(), 0);
    }

    @Test
    public void testBadClientHealthCheck() throws Exception {
        testClientHealthCheck(new BadTestClient(), 1);
    }

    @Test
    public void testAsyncFetchAidlVhalPid() throws Exception {
        int vhalPid = 15687;
        when(mMockCarServiceHelper.fetchAidlVhalPid()).thenReturn(vhalPid);

        mWatchdogProcessHandler.asyncFetchAidlVhalPid();

        verify(mMockCarWatchdogDaemonHelper, timeout(MAX_WAIT_TIME_MS)).onAidlVhalPidFetched(
                vhalPid);
    }

    @Test
    public void testAsyncFetchAidlWithInvalidPid() throws Exception {
        when(mMockCarServiceHelper.fetchAidlVhalPid()).thenReturn(INVALID_PID);

        mWatchdogProcessHandler.asyncFetchAidlVhalPid();

        verify(mMockCarServiceHelper, timeout(MAX_WAIT_TIME_MS)).fetchAidlVhalPid();
        // Shouldn't respond to car watchdog daemon when invalid pid is returned by
        // the system_server.
        verify(mMockCarWatchdogDaemonHelper, never()).onAidlVhalPidFetched(INVALID_PID);
    }

    @Test
    public void testAsyncFetchAidlVhalPidWithDaemonRemoteException() throws Exception {
        int vhalPid = 15687;
        when(mMockCarServiceHelper.fetchAidlVhalPid()).thenReturn(vhalPid);
        doThrow(RemoteException.class).when(mMockCarWatchdogDaemonHelper)
                .onAidlVhalPidFetched(anyInt());

        mWatchdogProcessHandler.asyncFetchAidlVhalPid();

        verify(mMockCarServiceHelper, timeout(MAX_WAIT_TIME_MS)).fetchAidlVhalPid();
        verify(mMockCarWatchdogDaemonHelper, timeout(MAX_WAIT_TIME_MS)).onAidlVhalPidFetched(
                vhalPid);
    }

    @Test
    public void testControlProcessHealthCheckEnabled() throws Exception {
        mWatchdogProcessHandler.controlProcessHealthCheck(true);

        verify(mMockCarWatchdogDaemonHelper).controlProcessHealthCheck(eq(true));
    }

    @Test
    public void testControlProcessHealthCheckDisabled() throws Exception {
        mWatchdogProcessHandler.controlProcessHealthCheck(false);

        verify(mMockCarWatchdogDaemonHelper).controlProcessHealthCheck(eq(false));
    }

    @Test
    public void testControlProcessHealthCheckWithDisconnectedDaemon() throws Exception {
        doThrow(IllegalStateException.class).when(
                mMockCarWatchdogDaemonHelper).controlProcessHealthCheck(true);

        assertThrows(IllegalStateException.class,
                () -> mWatchdogProcessHandler.controlProcessHealthCheck(true));
    }

    @Test
    public void testDumpProto() throws Exception {
        TestClient client = new TestClient();
        registerClientAndWait(client, TIMEOUT_NORMAL);

        mWatchdogProcessHandler.updateUserState(100, true);

        ProtoOutputStream proto = new ProtoOutputStream();
        mWatchdogProcessHandler.dumpProto(proto);

        CarWatchdogDumpProto carWatchdogDumpProto = CarWatchdogDumpProto.parseFrom(
                proto.getBytes());
        CarWatchdogDumpProto.SystemHealthDump systemHealthDump =
                carWatchdogDumpProto.getSystemHealthDump();
        expectWithMessage("RegisteredClient Count").that(
                systemHealthDump.getRegisteredClientsCount()).isEqualTo(1);

        CarWatchdogDumpProto.RegisteredClient registeredClient =
                systemHealthDump.getRegisteredClients(0);
        expectWithMessage("Health Check Timeout").that(
                registeredClient.getHealthCheckTimeout()).isEqualTo(
                CarWatchdogDumpProto.HealthCheckTimeout.NORMAL);

        UserPackageInfo userPackageInfo = registeredClient.getUserPackageInfo();
        expectWithMessage("User Id").that(userPackageInfo.getUserId()).isEqualTo(
                TEST_CLIENT_USER_ID);
        expectWithMessage("Package Name").that(userPackageInfo.getPackageName()).isEqualTo(
                CANONICAL_PACKAGE_NAME);

        expectWithMessage("Stopped Users Count").that(
                systemHealthDump.getStoppedUsersCount()).isEqualTo(1);
        expectWithMessage("Stopped User").that(systemHealthDump.getStoppedUsers(0)).isEqualTo(100);
    }

    private void testClientHealthCheck(TestClient client, int badClientCount) throws Exception {
        registerClientAndWait(client, TIMEOUT_CRITICAL);

        postHealthCheckMessageAndWait(123456);

        verify(mMockCarWatchdogDaemonHelper).tellCarWatchdogServiceAlive(
                eq(mWatchdogServiceForSystemImpl), mProcessIdentifiersCaptor.capture(), eq(123456));

        assertThat(mProcessIdentifiersCaptor.getValue()).isEmpty();

        postHealthCheckMessageAndWait(987654);

        verify(mMockCarWatchdogDaemonHelper).tellCarWatchdogServiceAlive(
                eq(mWatchdogServiceForSystemImpl), mProcessIdentifiersCaptor.capture(), eq(987654));

        assertThat(mProcessIdentifiersCaptor.getValue().size()).isEqualTo(badClientCount);
        if (badClientCount > 0) {
            assertThat(mProcessIdentifiersCaptor.getValue().get(0).processName).isEqualTo(
                    CANONICAL_PACKAGE_NAME);
            assertThat(mProcessIdentifiersCaptor.getValue().get(0).uid).isEqualTo(TEST_CLIENT_UID);
        }
    }

    private void mockPackageManager() {
        when(mMockPackageManager.getNamesForUids(any())).thenAnswer(args -> {
            int[] uids = args.getArgument(0);
            String[] names = new String[uids.length];
            for (int i = 0; i < uids.length; ++i) {
                names[i] = mGenericPackageNameByUid.get(uids[i], null);
            }
            return names;
        });
    }

    private void postHealthCheckMessageAndWait(int sessionId) {
        mWatchdogProcessHandler.postHealthCheckMessage(sessionId);
        // Wait for asynchronous postHealthCheckMessage call to return to prevent race conditions
        CarServiceUtils.runOnMainSync(() -> {});
    }

    private void registerClientAndWait(TestClient client, int timeout) {
        mWatchdogProcessHandler.registerClient(client, timeout);

        // packageName is resolved asynchronously on the service handler thread. Block until the
        // operation is complete to ensure the package name is populated in ClientInfo.
        CarServiceUtils.runEmptyRunnableOnLooperSync(CAR_WATCHDOG_SERVICE_NAME);
    }

    private class TestClient extends ICarWatchdogServiceCallback.Stub {
        protected int mLastSessionId = INVALID_SESSION_ID;
        protected boolean mReceivedOnPrepareProcessTermination = false;

        @Override
        public void onCheckHealthStatus(int sessionId, int timeout) {
            mLastSessionId = sessionId;
            mWatchdogProcessHandler.tellClientAlive(this, sessionId);
        }

        @Override
        public void onPrepareProcessTermination() {
            mReceivedOnPrepareProcessTermination = true;
        }

        public int getLastSessionId() {
            return mLastSessionId;
        }

        public boolean getReceivedOnPrepareProcessTermination() {
            return mReceivedOnPrepareProcessTermination;
        }
    }

    private final class BadTestClient extends TestClient {
        @Override
        public void onCheckHealthStatus(int sessionId, int timeout) {
            mLastSessionId = sessionId;
            // This client doesn't respond to CarWatchdogService.
        }
    }
}
