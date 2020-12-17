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

package com.android.car.hardware.power;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.annotation.NonNull;
import android.car.Car;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerPolicy;
import android.car.hardware.power.CarPowerPolicyFilter;
import android.car.hardware.power.PowerComponent;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.JavaMockitoHelper;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.frameworks.automotive.powerpolicy.internal.ICarPowerPolicySystemNotification;
import android.hardware.automotive.vehicle.V2_0.VehicleApPowerStateReq;
import android.hardware.automotive.vehicle.V2_0.VehicleApPowerStateShutdownParam;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.CarLocalServices;
import com.android.car.R;
import com.android.car.hal.MockedPowerHalService;
import com.android.car.hal.PowerHalService;
import com.android.car.hal.PowerHalService.PowerState;
import com.android.car.power.CarPowerManagementService;
import com.android.car.power.PowerComponentHandler;
import com.android.car.power.SilentModeController;
import com.android.car.systeminterface.DisplayInterface;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.SystemStateInterface;
import com.android.car.user.CarUserService;
import com.android.internal.app.IVoiceInteractionManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Spy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@SmallTest
public class CarPowerManagerUnitTest extends AbstractExtendedMockitoTestCase {
    private static final String TAG = CarPowerManagerUnitTest.class.getSimpleName();
    private static final long WAIT_TIMEOUT_MS = 5_000;
    private static final long WAIT_TIMEOUT_LONG_MS = 10_000;
    // A shorter value for use when the test is expected to time out
    private static final long WAIT_WHEN_TIMEOUT_EXPECTED_MS = 100;

    private final MockDisplayInterface mDisplayInterface = new MockDisplayInterface();
    private final MockSystemStateInterface mSystemStateInterface = new MockSystemStateInterface();

    @Spy
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Executor mExecutor = mContext.getMainExecutor();

    private MockedPowerHalService mPowerHal;
    private SystemInterface mSystemInterface;
    private CarPowerManagementService mService;
    private CarPowerManager mCarPowerManager;
    private SilentModeController mSilentModeController;

    @Mock
    private Resources mResources;
    @Mock
    private Car mCar;
    @Mock
    private CarUserService mCarUserService;
    @Mock
    private IVoiceInteractionManagerService mVoiceInteractionManagerService;
    @Mock
    private ICarPowerPolicySystemNotification mPowerPolicyDaemon;
    @Mock
    private PowerComponentHandler mPowerComponentHandler;

    @Before
    public void setUp() throws Exception {
        mPowerHal = new MockedPowerHalService(true /*isPowerStateSupported*/,
                true /*isDeepSleepAllowed*/, true /*isTimedWakeupAllowed*/);
        mSystemInterface = SystemInterface.Builder.defaultSystemInterface(mContext)
            .withDisplayInterface(mDisplayInterface)
            .withSystemStateInterface(mSystemStateInterface)
            .build();
        setService();
        mCarPowerManager = new CarPowerManager(mCar, mService);
    }

    @After
    public void tearDown() throws Exception {
        CarLocalServices.removeServiceForTest(SilentModeController.class);
        if (mService != null) {
            mService.release();
        }
    }

    @Test
    public void testRequestShutdownOnNextSuspend_positive() throws Exception {
        setPowerOn();
        // Tell it to shutdown
        mCarPowerManager.requestShutdownOnNextSuspend();
        // Request suspend
        setPowerState(VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                VehicleApPowerStateShutdownParam.CAN_SLEEP);
        // Verify shutdown
        assertStateReceivedForShutdownOrSleepWithPostpone(PowerHalService.SET_SHUTDOWN_START, 0);
    }

    @Test
    public void testRequestShutdownOnNextSuspend_negative() throws Exception {
        setPowerOn();

        // Do not tell it to shutdown

        // Request suspend
        setPowerState(VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                VehicleApPowerStateShutdownParam.CAN_SLEEP);
        // Verify suspend
        assertStateReceivedForShutdownOrSleepWithPostpone(PowerHalService.SET_DEEP_SLEEP_ENTRY, 0);
    }

    @Test
    public void testScheduleNextWakeupTime() throws Exception {
        setPowerOn();

        int wakeTime = 1234;
        mCarPowerManager.scheduleNextWakeupTime(wakeTime);

        setPowerState(VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                VehicleApPowerStateShutdownParam.CAN_SLEEP);

        // Verify that we suspended with the requested wake-up time
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_DEEP_SLEEP_ENTRY, wakeTime);
    }

    @Test
    public void testSetListener() throws Exception {
        setPowerOn();

        WaitablePowerStateListener listener = new WaitablePowerStateListener(2);

        setPowerState(VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                VehicleApPowerStateShutdownParam.CAN_SLEEP);

        assertStateReceivedForShutdownOrSleepWithPostpone(PowerHalService.SET_DEEP_SLEEP_ENTRY, 0);

        int finalState = listener.await();
        assertThat(finalState).isEqualTo(PowerHalService.SET_DEEP_SLEEP_ENTRY);
    }

    @Test
    public void testSetListenerWithCompletion() throws Exception {
        setPowerOn();

        WaitablePowerStateListenerWithCompletion listener =
                new WaitablePowerStateListenerWithCompletion(2);

        setPowerState(VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                VehicleApPowerStateShutdownParam.CAN_SLEEP);
        assertStateReceivedForShutdownOrSleepWithPostpone(PowerHalService.SET_DEEP_SLEEP_ENTRY, 0);

        int finalState = listener.await();
        assertThat(finalState).isEqualTo(PowerHalService.SET_DEEP_SLEEP_ENTRY);
    }

    @Test
    public void testClearListener() throws Exception {
        setPowerOn();

        // Set a listener with a short timeout, because we expect the timeout to happen
        WaitablePowerStateListener listener =
                new WaitablePowerStateListener(1, WAIT_WHEN_TIMEOUT_EXPECTED_MS);

        mCarPowerManager.clearListener();

        setPowerState(VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                VehicleApPowerStateShutdownParam.CAN_SLEEP);

        assertStateReceivedForShutdownOrSleepWithPostpone(PowerHalService.SET_DEEP_SLEEP_ENTRY, 0);
        // Verify that the listener didn't run
        assertThrows(IllegalStateException.class, () -> listener.await());
    }

    @Test
    public void testGetPowerState() throws Exception {
        setPowerOn();
        assertThat(mCarPowerManager.getPowerState()).isEqualTo(PowerHalService.SET_ON);

        // Request suspend
        setPowerState(VehicleApPowerStateReq.SHUTDOWN_PREPARE,
                        VehicleApPowerStateShutdownParam.CAN_SLEEP);
        assertStateReceivedForShutdownOrSleepWithPostpone(PowerHalService.SET_DEEP_SLEEP_ENTRY, 0);
        assertThat(mCarPowerManager.getPowerState())
                .isEqualTo(PowerHalService.SET_DEEP_SLEEP_ENTRY);
    }

    @Test
    public void testApplyPowerPolicy() throws Exception {
        grantPowerPolicyPermission();
        String policyId = "no_change_policy";
        mService.definePowerPolicy(policyId, new String[0], new String[0]);

        mCarPowerManager.applyPowerPolicy(policyId);

        assertThat(mCarPowerManager.getCurrentPowerPolicy().policyId).isEqualTo(policyId);
    }

    @Test
    public void testApplyPowerPolicy_invalidId() throws Exception {
        grantPowerPolicyPermission();
        String policyId = "invalid_power_policy";

        assertThrows(IllegalArgumentException.class,
                () -> mCarPowerManager.applyPowerPolicy(policyId));
    }

    @Test
    public void testApplyPowerPolicy_nullPolicyId() throws Exception {
        grantPowerPolicyPermission();
        assertThrows(IllegalArgumentException.class, () -> mCarPowerManager.applyPowerPolicy(null));
    }

    @Test
    public void testAddPowerPolicyListener() throws Exception {
        grantPowerPolicyPermission();
        String policyId = "audio_on_wifi_off";
        mService.definePowerPolicy(policyId, new String[]{"AUDIO"}, new String[]{"WIFI"});
        MockedPowerPolicyChangeListener listenerAudio = new MockedPowerPolicyChangeListener();
        MockedPowerPolicyChangeListener listenerWifi = new MockedPowerPolicyChangeListener();
        MockedPowerPolicyChangeListener listenerLocation = new MockedPowerPolicyChangeListener();
        CarPowerPolicyFilter filterAudio =
                new CarPowerPolicyFilter(new int[]{PowerComponent.AUDIO});
        CarPowerPolicyFilter filterWifi = new CarPowerPolicyFilter(new int[]{PowerComponent.WIFI});
        CarPowerPolicyFilter filterLocation =
                new CarPowerPolicyFilter(new int[]{PowerComponent.LOCATION});

        mCarPowerManager.addPowerPolicyChangeListener(mExecutor, listenerAudio, filterAudio);
        mCarPowerManager.addPowerPolicyChangeListener(mExecutor, listenerWifi, filterWifi);
        mCarPowerManager.addPowerPolicyChangeListener(mExecutor, listenerLocation, filterLocation);
        mCarPowerManager.applyPowerPolicy(policyId);

        assertThat(listenerAudio.getCurrentPolicyId()).isEqualTo(policyId);
        assertThat(listenerWifi.getCurrentPolicyId()).isEqualTo(policyId);
        assertThat(listenerLocation.getCurrentPolicyId()).isNull();
    }

    @Test
    public void testAddPowerPolicyListener_Twice_WithDifferentFilters() throws Exception {
        grantPowerPolicyPermission();
        String policyId = "audio_on_wifi_off";
        mService.definePowerPolicy(policyId, new String[]{"AUDIO"}, new String[]{"WIFI"});
        MockedPowerPolicyChangeListener listener = new MockedPowerPolicyChangeListener();
        CarPowerPolicyFilter filterAudio =
                new CarPowerPolicyFilter(new int[]{PowerComponent.AUDIO});
        CarPowerPolicyFilter filterLocation =
                new CarPowerPolicyFilter(new int[]{PowerComponent.LOCATION});

        mCarPowerManager.addPowerPolicyChangeListener(mExecutor, listener, filterAudio);
        mCarPowerManager.addPowerPolicyChangeListener(mExecutor, listener, filterLocation);
        mCarPowerManager.applyPowerPolicy(policyId);

        assertThat(listener.getCurrentPolicyId()).isNull();
    }

    @Test
    public void testAddPowerPolicyListener_nullListener() throws Exception {
        MockedPowerPolicyChangeListener listener = new MockedPowerPolicyChangeListener();
        CarPowerPolicyFilter filter = new CarPowerPolicyFilter(new int[]{PowerComponent.AUDIO});

        assertThrows(NullPointerException.class,
                () -> mCarPowerManager.addPowerPolicyChangeListener(null, listener, filter));
        assertThrows(NullPointerException.class,
                () -> mCarPowerManager.addPowerPolicyChangeListener(mExecutor, null, filter));
        assertThrows(NullPointerException.class,
                () -> mCarPowerManager.addPowerPolicyChangeListener(mExecutor, listener, null));
    }

    @Test
    public void testRemovePowerPolicyListener() throws Exception {
        grantPowerPolicyPermission();
        String policyId = "audio_on_wifi_off";
        mService.definePowerPolicy(policyId, new String[]{"AUDIO"}, new String[]{"WIFI"});
        MockedPowerPolicyChangeListener listenerOne = new MockedPowerPolicyChangeListener();
        MockedPowerPolicyChangeListener listenerTwo = new MockedPowerPolicyChangeListener();
        CarPowerPolicyFilter filterAudio =
                new CarPowerPolicyFilter(new int[]{PowerComponent.AUDIO});

        mCarPowerManager.addPowerPolicyChangeListener(mExecutor, listenerOne, filterAudio);
        mCarPowerManager.addPowerPolicyChangeListener(mExecutor, listenerTwo, filterAudio);
        mCarPowerManager.removePowerPolicyChangeListener(listenerOne);
        mCarPowerManager.applyPowerPolicy(policyId);

        assertThat(listenerOne.getCurrentPolicyId()).isNull();
        assertThat(listenerTwo.getCurrentPolicyId()).isEqualTo(policyId);
    }

    @Test
    public void testRemovePowerPolicyListener_Twice() throws Exception {
        grantPowerPolicyPermission();
        MockedPowerPolicyChangeListener listener = new MockedPowerPolicyChangeListener();
        CarPowerPolicyFilter filter = new CarPowerPolicyFilter(new int[]{PowerComponent.AUDIO});

        // Remove unregistered listener should not throw an exception.
        mCarPowerManager.removePowerPolicyChangeListener(listener);

        mCarPowerManager.addPowerPolicyChangeListener(mExecutor, listener, filter);
        mCarPowerManager.removePowerPolicyChangeListener(listener);
        // Remove the same listener twice should nont throw an exception.
        mCarPowerManager.removePowerPolicyChangeListener(listener);
    }

    @Test
    public void testRemovePowerPolicyListener_nullListener() throws Exception {
        assertThrows(NullPointerException.class,
                () -> mCarPowerManager.removePowerPolicyChangeListener(null));
    }

    /**
     * Helper method to create mService and initialize a test case
     */
    private void setService() throws Exception {
        Log.i(TAG, "setService(): overridden overlay properties: "
                + ", maxGarageModeRunningDurationInSecs="
                + mResources.getInteger(R.integer.maxGarageModeRunningDurationInSecs));
        mSilentModeController = new SilentModeController(mContext, mSystemInterface,
                mVoiceInteractionManagerService, "");
        CarLocalServices.addService(SilentModeController.class, mSilentModeController);
        mService = new CarPowerManagementService(mContext, mResources, mPowerHal, mSystemInterface,
                null, mCarUserService, mPowerPolicyDaemon, mPowerComponentHandler);
        mService.init();
        mService.setShutdownTimersForTest(0, 0);
        assertStateReceived(MockedPowerHalService.SET_WAIT_FOR_VHAL, 0);
    }

    private void assertStateReceived(int expectedState, int expectedParam) throws Exception {
        int[] state = mPowerHal.waitForSend(WAIT_TIMEOUT_MS);
        assertThat(state).asList().containsExactly(expectedState, expectedParam).inOrder();
    }

    /**
     * Helper method to get the system into ON
     */
    private void setPowerOn() throws Exception {
        setPowerState(VehicleApPowerStateReq.ON, 0);
        int[] state = mPowerHal.waitForSend(WAIT_TIMEOUT_MS);
        assertThat(state[0]).isEqualTo(PowerHalService.SET_ON);
    }

    /**
     * Helper to set the PowerHal state
     *
     * @param stateEnum Requested state enum
     * @param stateParam Addition state parameter
     */
    private void setPowerState(int stateEnum, int stateParam) {
        mPowerHal.setCurrentPowerState(new PowerState(stateEnum, stateParam));
    }

    private void assertStateReceivedForShutdownOrSleepWithPostpone(
            int lastState, int stateParameter) throws Exception {
        long startTime = System.currentTimeMillis();
        while (true) {
            int[] state = mPowerHal.waitForSend(WAIT_TIMEOUT_LONG_MS);
            if (state[0] == lastState) {
                assertThat(state[1]).isEqualTo(stateParameter);
                return;
            }
            assertThat(state[0]).isEqualTo(PowerHalService.SET_SHUTDOWN_POSTPONE);
            assertThat(System.currentTimeMillis() - startTime).isLessThan(WAIT_TIMEOUT_LONG_MS);
        }
    }

    private void grantPowerPolicyPermission() {
        when(mCar.getContext()).thenReturn(mContext);
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingOrSelfPermission(Car.PERMISSION_CONTROL_CAR_POWER_POLICY);
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingOrSelfPermission(Car.PERMISSION_READ_CAR_POWER_POLICY);
    }

    private static final class MockDisplayInterface implements DisplayInterface {
        private boolean mDisplayOn = true;
        private final Semaphore mDisplayStateWait = new Semaphore(0);

        @Override
        public void setDisplayBrightness(int brightness) {}

        @Override
        public synchronized void setDisplayState(boolean on) {
            mDisplayOn = on;
            mDisplayStateWait.release();
        }

        public synchronized boolean getDisplayState() {
            return mDisplayOn;
        }

        public boolean waitForDisplayStateChange(long timeoutMs) throws Exception {
            JavaMockitoHelper.await(mDisplayStateWait, timeoutMs);
            return mDisplayOn;
        }

        @Override
        public void startDisplayStateMonitoring(CarPowerManagementService service) {}

        @Override
        public void stopDisplayStateMonitoring() {}

        @Override
        public void refreshDisplayBrightness() {}
    }

    /**
     * Helper class to set a power-state listener,
     * verify that the listener gets called the
     * right number of times, and return the final
     * power state.
     */
    private final class WaitablePowerStateListener {
        private final CountDownLatch mLatch;
        private int mListenedState = -1;
        private long mTimeoutValue = WAIT_TIMEOUT_MS;

        WaitablePowerStateListener(int initialCount, long customTimeout) {
            this(initialCount);
            mTimeoutValue = customTimeout;
        }

        WaitablePowerStateListener(int initialCount) {
            mLatch = new CountDownLatch(initialCount);
            mCarPowerManager.setListener(
                    (state) -> {
                        mListenedState = state;
                        mLatch.countDown();
                    });
        }

        int await() throws Exception {
            JavaMockitoHelper.await(mLatch, WAIT_TIMEOUT_MS);
            return mListenedState;
        }
    }

    /**
     * Helper class to set a power-state listener with completion,
     * verify that the listener gets called the right number of times,
     * verify that the CompletableFuture is provided, complete the
     * CompletableFuture, and return the final power state.
     */
    private final class WaitablePowerStateListenerWithCompletion {
        private final CountDownLatch mLatch;
        private int mListenedState = -1;
        WaitablePowerStateListenerWithCompletion(int initialCount) {
            mLatch = new CountDownLatch(initialCount);
            mCarPowerManager.setListenerWithCompletion(
                    (state, future) -> {
                        mListenedState = state;
                        if (state == PowerHalService.SET_SHUTDOWN_PREPARE) {
                            assertThat(future).isNotNull();
                            future.complete(null);
                        } else {
                            assertThat(future).isNull();
                        }
                        mLatch.countDown();
                    });
        }

        int await() throws Exception {
            JavaMockitoHelper.await(mLatch, WAIT_TIMEOUT_MS);
            return mListenedState;
        }
    }

    private static final class MockSystemStateInterface implements SystemStateInterface {
        private final Semaphore mShutdownWait = new Semaphore(0);
        private final Semaphore mSleepWait = new Semaphore(0);
        private final Semaphore mSleepExitWait = new Semaphore(0);
        private boolean mWakeupCausedByTimer = false;

        @Override
        public void shutdown() {
            mShutdownWait.release();
        }

        public void waitForShutdown(long timeoutMs) throws Exception {
            JavaMockitoHelper.await(mShutdownWait, timeoutMs);
        }

        @Override
        public boolean enterDeepSleep() {
            mSleepWait.release();
            try {
                mSleepExitWait.acquire();
            } catch (InterruptedException e) {
            }
            return true;
        }

        public void waitForSleepEntryAndWakeup(long timeoutMs) throws Exception {
            JavaMockitoHelper.await(mSleepWait, timeoutMs);
            mSleepExitWait.release();
        }

        @Override
        public void scheduleActionForBootCompleted(Runnable action, Duration delay) {}

        @Override
        public boolean isWakeupCausedByTimer() {
            Log.i(TAG, "isWakeupCausedByTimer:" + mWakeupCausedByTimer);
            return mWakeupCausedByTimer;
        }

        public synchronized void setWakeupCausedByTimer(boolean set) {
            mWakeupCausedByTimer = set;
        }

        @Override
        public boolean isSystemSupportingDeepSleep() {
            return true;
        }
    }

    private final class MockedPowerPolicyChangeListener implements
            CarPowerManager.CarPowerPolicyChangeListener {
        private String mCurrentPolicyId;

        @Override
        public void onPolicyChanged(@NonNull CarPowerPolicy policy) {
            mCurrentPolicyId = policy.policyId;
        }

        public String getCurrentPolicyId() {
            return mCurrentPolicyId;
        }
    }
}
