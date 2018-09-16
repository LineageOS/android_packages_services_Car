/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.car.hardware.power.ICarPowerStateListener;
import android.os.RemoteException;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.android.car.hal.PowerHalService;
import com.android.car.hal.PowerHalService.PowerState;
import com.android.car.systeminterface.DisplayInterface;
import com.android.car.systeminterface.IOInterface;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.SystemStateInterface;
import com.android.car.systeminterface.WakeLockInterface;
import com.android.car.test.utils.TemporaryDirectory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@SmallTest
public class CarPowerManagementServiceTest extends AndroidTestCase {
    private static final String TAG = CarPowerManagementServiceTest.class.getSimpleName();
    private static final long WAIT_TIMEOUT_MS = 2000;
    private static final long WAIT_TIMEOUT_LONG_MS = 5000;

    private final MockDisplayInterface mDisplayInterface = new MockDisplayInterface();
    private final MockSystemStateInterface mSystemStateInterface = new MockSystemStateInterface();
    private final MockWakeLockInterface mWakeLockInterface = new MockWakeLockInterface();
    private final MockIOInterface mIOInterface = new MockIOInterface();
    private final PowerSignalListener mPowerSignalListener = new PowerSignalListener();

    private MockedPowerHalService mPowerHal;
    private SystemInterface mSystemInterface;
    private CarPowerManagementService mService;
    private CompletableFuture<Void> mFuture;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mPowerHal = new MockedPowerHalService(true /*isPowerStateSupported*/,
                true /*isDeepSleepAllowed*/, true /*isTimedWakeupAllowed*/);
        mSystemInterface = SystemInterface.Builder.defaultSystemInterface(getContext())
            .withDisplayInterface(mDisplayInterface)
            .withSystemStateInterface(mSystemStateInterface)
            .withWakeLockInterface(mWakeLockInterface)
            .withIOInterface(mIOInterface).build();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (mService != null) {
            mService.release();
        }
        mIOInterface.tearDown();
    }

    /**
     * Helper method to create mService and initialize a test case
     */
    private void initTest(int wakeupTime) throws Exception {
        mService = new CarPowerManagementService(getContext(), mPowerHal, mSystemInterface);
        mService.init();
        mPowerHal.setSignalListener(mPowerSignalListener);
        if (wakeupTime > 0) {
            registerListenerToService();
            mService.scheduleNextWakeupTime(wakeupTime);
        }
        assertStateReceived(MockedPowerHalService.SET_BOOT_COMPLETE, 0);
    }

    public void testBootComplete() throws Exception {
        initTest(0);
    }

    public void testDisplayOff() throws Exception {
        initTest(0);
        // it will call display on for initial state
        assertTrue(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));
        mPowerHal.setCurrentPowerState(new PowerState(PowerHalService.STATE_ON_DISP_OFF, 0));
        assertFalse(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));
    }

    public void testDisplayOn() throws Exception {
        // start with display off
        mSystemInterface.setDisplayState(false);
        mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS);
        initTest(0);

        // display should be turned on as it started with off state.
        assertTrue(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));
    }

    public void testShutdown() throws Exception {
        initTest(0);
        assertTrue(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));

        mPowerHal.setCurrentPowerState(
                new PowerState(
                        PowerHalService.STATE_SHUTDOWN_PREPARE,
                        PowerHalService.SHUTDOWN_IMMEDIATELY));
        // Since modules have to manually schedule next wakeup, we should not schedule next wakeup
        // To test module behavior, we need to actually implement mock listener module.
        assertStateReceived(PowerHalService.SET_SHUTDOWN_START, 0);
        assertFalse(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));
        mPowerSignalListener.waitForShutdown(WAIT_TIMEOUT_MS);
        mSystemStateInterface.waitForShutdown(WAIT_TIMEOUT_MS);
    }

    public void testShutdownWithProcessing() throws Exception {
        final int wakeupTime = 100;
        initTest(wakeupTime);
        assertTrue(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));
        mPowerHal.setCurrentPowerState(new PowerState(PowerHalService.STATE_SHUTDOWN_PREPARE, 0));
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_SHUTDOWN_START, WAIT_TIMEOUT_LONG_MS, wakeupTime);
        assertFalse(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));
        mPowerSignalListener.waitForShutdown(WAIT_TIMEOUT_MS);
        mSystemStateInterface.waitForShutdown(WAIT_TIMEOUT_MS);
    }

    public void testSleepEntryAndWakeup() throws Exception {
        final int wakeupTime = 100;
        initTest(wakeupTime);
        assertTrue(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));
        mPowerHal.setCurrentPowerState(new PowerState(PowerHalService.STATE_SHUTDOWN_PREPARE,
                PowerHalService.SHUTDOWN_CAN_SLEEP));
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_DEEP_SLEEP_ENTRY, WAIT_TIMEOUT_LONG_MS, 0);
        assertFalse(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));
        mPowerSignalListener.waitForSleepEntry(WAIT_TIMEOUT_MS);
        int wakeupTimeReceived = mSystemStateInterface.waitForSleepEntryAndWakeup(WAIT_TIMEOUT_MS);
        assertEquals(wakeupTime, wakeupTimeReceived);
        assertStateReceived(PowerHalService.SET_DEEP_SLEEP_EXIT, 0);
        mPowerSignalListener.waitForSleepExit(WAIT_TIMEOUT_MS);

    }

    public void testSleepEntryAndPowerOnWithProcessing() throws Exception {
        final int wakeupTime = 100;
        initTest(wakeupTime);
        assertTrue(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));

        mPowerHal.setCurrentPowerState(new PowerState(PowerHalService.STATE_SHUTDOWN_PREPARE,
                PowerHalService.SHUTDOWN_CAN_SLEEP));
        assertFalse(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_DEEP_SLEEP_ENTRY, WAIT_TIMEOUT_LONG_MS, 0);
        mPowerSignalListener.waitForSleepEntry(WAIT_TIMEOUT_MS);
        // set power on here without notification. PowerManager should check the state after sleep
        // exit
        mPowerHal.setCurrentPowerState(new PowerState(PowerHalService.STATE_ON_DISP_OFF, 0), false);
        int wakeupTimeReceived = mSystemStateInterface.waitForSleepEntryAndWakeup(WAIT_TIMEOUT_MS);
        assertEquals(wakeupTime, wakeupTimeReceived);
        assertStateReceived(PowerHalService.SET_DEEP_SLEEP_EXIT, 0);
        mPowerSignalListener.waitForSleepExit(WAIT_TIMEOUT_MS);
    }

    public void testSleepEntryAndWakeUpForProcessing() throws Exception {
        final int wakeupTime = 100;
        initTest(wakeupTime);
        assertTrue(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));
        mPowerHal.setCurrentPowerState(new PowerState(PowerHalService.STATE_SHUTDOWN_PREPARE,
                PowerHalService.SHUTDOWN_CAN_SLEEP));
        assertFalse(mDisplayInterface.waitForDisplayStateChange(WAIT_TIMEOUT_MS));
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_DEEP_SLEEP_ENTRY, WAIT_TIMEOUT_LONG_MS, 0);
        mPowerSignalListener.waitForSleepEntry(WAIT_TIMEOUT_MS);
        mSystemStateInterface.setWakeupCausedByTimer(true);
        int wakeupTimeReceived = mSystemStateInterface.waitForSleepEntryAndWakeup(WAIT_TIMEOUT_MS);
        assertEquals(wakeupTime, wakeupTimeReceived);
        assertStateReceived(PowerHalService.SET_DEEP_SLEEP_EXIT, 0);
        mPowerSignalListener.waitForSleepExit(WAIT_TIMEOUT_MS);
        mService.scheduleNextWakeupTime(wakeupTime);
        // second processing after wakeup
        assertFalse(mDisplayInterface.getDisplayState());
        assertStateReceivedForShutdownOrSleepWithPostpone(
                PowerHalService.SET_DEEP_SLEEP_ENTRY, WAIT_TIMEOUT_LONG_MS, 0);
        mPowerSignalListener.waitForSleepEntry(WAIT_TIMEOUT_MS);
        // PM will shutdown system as it was not woken-up due to timer and it is not power on.
        mSystemStateInterface.setWakeupCausedByTimer(false);
        wakeupTimeReceived = mSystemStateInterface.waitForSleepEntryAndWakeup(WAIT_TIMEOUT_MS);
        assertEquals(wakeupTime, wakeupTimeReceived);
        assertStateReceived(PowerHalService.SET_DEEP_SLEEP_EXIT, 0);
        // Since we just woke up from shutdown, wake up time will be 0
        assertStateReceived(PowerHalService.SET_SHUTDOWN_START, 0);
        mPowerSignalListener.waitForShutdown(WAIT_TIMEOUT_MS);
        mSystemStateInterface.waitForShutdown(WAIT_TIMEOUT_MS);
        assertFalse(mDisplayInterface.getDisplayState());
    }

    private void registerListenerToService() {
        ICarPowerStateListener listenerToService = new ICarPowerStateListener.Stub() {
            @Override
            public void onStateChanged(int state, int token) throws RemoteException {
                if (state == CarPowerStateListener.SHUTDOWN_ENTER
                        || state == CarPowerStateListener.SUSPEND_ENTER) {
                    mFuture = new CompletableFuture<>();
                    mFuture.whenComplete((res, ex) -> {
                        if (ex == null) {
                            mService.finished(this, token);
                        }
                    });
                } else {
                    mFuture = null;
                }
            }
        };
        mService.registerListener(listenerToService);
    }

    private void assertStateReceived(int expectedState, int expectedParam) throws Exception {
        int[] state = mPowerHal.waitForSend(WAIT_TIMEOUT_MS);
        assertEquals(expectedState, state[0]);
        assertEquals(expectedParam, state[1]);
    }

    private void assertStateReceivedForShutdownOrSleepWithPostpone(
            int lastState, long timeoutMs, int expectedParamForShutdownOrSuspend) throws Exception {
        while (true) {
            if (mFuture != null && !mFuture.isDone()) {
                mFuture.complete(null);
            }
            int[] state = mPowerHal.waitForSend(timeoutMs);
            if (state[0] == PowerHalService.SET_SHUTDOWN_POSTPONE) {
                continue;
            }
            if (state[0] == lastState) {
                assertEquals(expectedParamForShutdownOrSuspend, state[1]);
                return;
            }
        }
    }

    private static void waitForSemaphore(Semaphore semaphore, long timeoutMs)
            throws InterruptedException {
        if (!semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("timeout");
        }
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
            waitForSemaphore(mDisplayStateWait, timeoutMs);
            return mDisplayOn;
        }

        @Override
        public void startDisplayStateMonitoring(CarPowerManagementService service) {}

        @Override
        public void stopDisplayStateMonitoring() {}
    }

    private static final class MockSystemStateInterface implements SystemStateInterface {
        private final Semaphore mShutdownWait = new Semaphore(0);
        private final Semaphore mSleepWait = new Semaphore(0);
        private final Semaphore mSleepExitWait = new Semaphore(0);
        private int mWakeupTime;
        private boolean mWakeupCausedByTimer = false;

        @Override
        public void shutdown() {
            mShutdownWait.release();
        }

        public void waitForShutdown(long timeoutMs) throws Exception {
            waitForSemaphore(mShutdownWait, timeoutMs);
        }

        @Override
        public boolean enterDeepSleep(int wakeupTimeSec) {
            mWakeupTime = wakeupTimeSec;
            mSleepWait.release();
            try {
                mSleepExitWait.acquire();
            } catch (InterruptedException e) {
            }
            return true;
        }

        public int waitForSleepEntryAndWakeup(long timeoutMs) throws Exception {
            waitForSemaphore(mSleepWait, timeoutMs);
            mSleepExitWait.release();
            return mWakeupTime;
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

    private static final class MockWakeLockInterface implements WakeLockInterface {

        @Override
        public void releaseAllWakeLocks() {}

        @Override
        public void switchToPartialWakeLock() {}

        @Override
        public void switchToFullWakeLock() {}
    }

    private static final class MockIOInterface implements IOInterface {
        private TemporaryDirectory mFilesDir;

        @Override
        public File getFilesDir() {
            if (mFilesDir == null) {
                try {
                    mFilesDir = new TemporaryDirectory(TAG);
                } catch (IOException e) {
                    Log.e(TAG, "failed to create temporary directory", e);
                    fail("failed to create temporary directory. exception was: " + e);
                }
            }
            return mFilesDir.getDirectory();
        }

        public void tearDown() {
            if (mFilesDir != null) {
                try {
                    mFilesDir.close();
                } catch (Exception e) {
                    Log.w(TAG, "could not remove temporary directory", e);
                }
            }
        }
    }

    private class PowerSignalListener implements MockedPowerHalService.SignalListener {
        private final Semaphore mShutdownWait = new Semaphore(0);
        private final Semaphore mSleepEntryWait = new Semaphore(0);
        private final Semaphore mSleepExitWait = new Semaphore(0);

        public void waitForSleepExit(long timeoutMs) throws Exception {
            waitForSemaphore(mSleepExitWait, timeoutMs);
        }

        public void waitForShutdown(long timeoutMs) throws Exception {
            waitForSemaphore(mShutdownWait, timeoutMs);
        }

        public void waitForSleepEntry(long timeoutMs) throws Exception {
            waitForSemaphore(mSleepEntryWait, timeoutMs);
        }

        @Override
        public void sendingSignal(int signal) {
            if (signal == PowerHalService.SET_SHUTDOWN_START) {
                mShutdownWait.release();
                return;
            }
            if (signal == PowerHalService.SET_DEEP_SLEEP_ENTRY) {
                mSleepEntryWait.release();
                return;
            }
            if (signal == PowerHalService.SET_DEEP_SLEEP_EXIT) {
                mSleepExitWait.release();
                return;
            }
        }
    }
}
