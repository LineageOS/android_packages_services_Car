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

package com.android.car.power;

import static org.testng.Assert.assertThrows;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.JavaMockitoHelper;
import android.content.Context;
import android.content.res.Resources;
import android.frameworks.automotive.powerpolicy.internal.ICarPowerPolicySystemNotification;
import android.hardware.automotive.vehicle.V2_0.VehicleApPowerStateReq;
import android.os.UserManager;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.CarLocalServices;
import com.android.car.hal.MockedPowerHalService;
import com.android.car.hal.PowerHalService.PowerState;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.test.utils.TemporaryFile;
import com.android.internal.app.IVoiceInteractionManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.concurrent.CountDownLatch;

@SmallTest
public final class SilentModeControllerUnitTest extends AbstractExtendedMockitoTestCase {

    @Mock
    private Resources mResources;
    @Mock
    private UserManager mUserManager;
    @Mock
    private SystemInterface mMockSystemInterface;
    @Mock
    private IVoiceInteractionManagerService mMockInteractionManager;
    @Mock
    private ICarPowerPolicySystemNotification mPowerPolicyDaemon;

    private static final String TAG = SilentModeControllerUnitTest.class.getSimpleName();
    private static final long WAIT_TIMEOUT_MS = 5_000;
    // A shorter value for use when the test is expected to time out
    private static final long WAIT_WHEN_TIMEOUT_EXPECTED_MS = 50;
    private static final long POLLING_DELAY_MS = 10;
    private static final int MAX_POLLING_TRIES = 5;
    private static final String SILENT_STRING = "1";
    private static final String NONSILENT_STRING = "0";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private CarPowerManagementService mCarPowerManagementService;
    private MockedPowerHalService mPowerHal;
    private SilentModeController mSilentModeController;
    private TemporaryFile mSilenceFile;

    @Before
    public void setUp() throws Exception {
        mSilenceFile = new TemporaryFile(TAG);
        mSilenceFile.write(NONSILENT_STRING);
        mPowerHal = new MockedPowerHalService(true /*isPowerStateSupported*/,
                true /*isDeepSleepAllowed*/, true /*isTimedWakeupAllowed*/);
        mCarPowerManagementService = new CarPowerManagementService(mContext, mResources, mPowerHal,
                mMockSystemInterface, mUserManager, null, mPowerPolicyDaemon, null);
        CarLocalServices.addService(CarPowerManagementService.class, mCarPowerManagementService);
        mCarPowerManagementService.onApPowerStateChange(
                new PowerState(VehicleApPowerStateReq.ON, 0));
        mSilentModeController = new SilentModeController(mContext, mMockSystemInterface,
                mMockInteractionManager, mSilenceFile.getPath().toString());
        mSilentModeController.init();
    }

    @After
    public void tearDown() {
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
    }

    @Test
    public void readModeFileTest_nonsilent() throws Exception {
        // The file is set to Non-silent in 'setUp'
        queryForSilentState(false);
    }

    @Test
    public void readModeFileTest_silent() throws Exception {
        mSilenceFile.write(SILENT_STRING);
        queryForSilentState(true);
    }

    @Test
    public void listenerTest() throws Exception {
        WaitableStateListener stateListenerFalse = new WaitableStateListener(false,
                mSilentModeController);
        stateListenerFalse.await();

        WaitableStateListener stateListenerTrue = new WaitableStateListener(true,
                mSilentModeController);
        mSilenceFile.write(SILENT_STRING);

        stateListenerTrue.await();
    }

    @Test
    public void unregisterListenerTest() throws Exception {
        WaitableStateListener stateListener = new WaitableStateListener(true,
                mSilentModeController, WAIT_WHEN_TIMEOUT_EXPECTED_MS);

        stateListener.unregister();
        mSilenceFile.write(SILENT_STRING);

        assertThrows(IllegalStateException.class, () -> stateListener.await());
    }

    private void queryForSilentState(boolean expectedState) throws Exception {
        for (int loopCount = 0; loopCount < MAX_POLLING_TRIES; loopCount++) {
            if (mSilentModeController.isSilent() == expectedState) {
                return;
            }
            Thread.sleep(POLLING_DELAY_MS);
        }
        throw new IllegalStateException("Timed out waiting for isSilent() to return "
                + expectedState);
    }

    /**
     * Helper class to set a power-state listener and verify that the listener gets called with
     * the expected final state.
     */
    private final class WaitableStateListener {
        private final CountDownLatch mLatch;
        private final SilentModeController mSilentModeController;
        private final long mTimeoutMs;
        private final boolean mExpectedState;

        private SilentModeController.SilentModeListener mListener =
                new SilentModeController.SilentModeListener() {
                    @Override
                    public void onModeChange(boolean state) {
                        if (state == mExpectedState) {
                            mLatch.countDown();
                        }
                    }
                };

        WaitableStateListener(boolean expectedState, SilentModeController silentModeController) {
            this(expectedState, silentModeController, WAIT_TIMEOUT_MS);
        }

        WaitableStateListener(boolean expectedState, SilentModeController silentModeController,
                long timeoutMs) {
            mExpectedState = expectedState;
            mSilentModeController = silentModeController;
            mTimeoutMs = timeoutMs;
            mLatch = new CountDownLatch(1);
            mSilentModeController.registerListener(mListener);
        }

        void unregister() {
            mSilentModeController.unregisterListener(mListener);
        }

        void await() throws Exception {
            JavaMockitoHelper.await(mLatch, mTimeoutMs);
        }
    }
}
