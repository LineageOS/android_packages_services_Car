/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.garagemode;

import static com.android.car.garagemode.GarageMode.ACTION_GARAGE_MODE_OFF;
import static com.android.car.garagemode.GarageMode.ACTION_GARAGE_MODE_ON;
import static com.android.car.garagemode.GarageMode.JOB_SNAPSHOT_INITIAL_UPDATE_MS;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.hardware.power.CarPowerManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.car.CarLocalServices;
import com.android.car.power.CarPowerManagementService;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.user.CarUserService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ControllerTest {

    private static final int[] TEMPLATE_WAKEUP_SCHEDULE_SECONDS = new int[] {
            15 * 60,
            6 * 60 * 60,
            6 * 60 * 60,
            6 * 60 * 60,
            6 * 60 * 60,
            24 * 60 * 60};

    @Rule public final MockitoRule rule = MockitoJUnit.rule();

    @Mock private Context mContextMock;
    @Mock private Looper mLooperMock;
    @Mock private Handler mHandlerMock;
    @Mock private Car mCarMock;
    @Mock private CarPowerManager mCarPowerManagerMock;
    @Mock private CarUserService mCarUserServiceMock;
    @Mock private SystemInterface mSystemInterfaceMock;
    @Mock private CarPowerManagementService mCarPowerManagementServiceMock;
    private CarUserService mCarUserServiceOriginal;
    private SystemInterface mSystemInterfaceOriginal;
    private CarPowerManagementService mCarPowerManagementServiceOriginal;
    @Captor private ArgumentCaptor<Intent> mIntentCaptor;
    @Captor private ArgumentCaptor<Integer> mIntegerCaptor;

    private Controller mController;
    private CompletablePowerStateChangeFutureImpl mFuture;

    @Before
    public void setUp() {
        mController = new Controller(mContextMock, mLooperMock, mHandlerMock,
                /* garageMode= */ null);
        mController.setCarPowerManager(mCarPowerManagerMock);
        mFuture = new CompletablePowerStateChangeFutureImpl();
        mCarUserServiceOriginal = CarLocalServices.getService(CarUserService.class);
        mCarPowerManagementServiceOriginal = CarLocalServices.getService(
                CarPowerManagementService.class);
        CarLocalServices.removeServiceForTest(CarUserService.class);
        CarLocalServices.addService(CarUserService.class, mCarUserServiceMock);
        CarLocalServices.removeServiceForTest(SystemInterface.class);
        CarLocalServices.addService(SystemInterface.class, mSystemInterfaceMock);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class,
                mCarPowerManagementServiceMock);
        doReturn(new ArrayList<Integer>()).when(mCarUserServiceMock)
                .startAllBackgroundUsersInGarageMode();
        doNothing().when(mSystemInterfaceMock)
                .sendBroadcastAsUser(any(Intent.class), any(UserHandle.class));
    }

    @After
    public void tearDown() {
        CarLocalServices.removeServiceForTest(CarUserService.class);
        CarLocalServices.addService(CarUserService.class, mCarUserServiceOriginal);
        CarLocalServices.removeServiceForTest(SystemInterface.class);
        CarLocalServices.addService(SystemInterface.class, mSystemInterfaceOriginal);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class,
                mCarPowerManagementServiceOriginal);
    }

    @Test
    public void testOnShutdownPrepare_shouldInitiateGarageMode() {
        startAndAssertGarageModeWithSignal(CarPowerManager.STATE_SHUTDOWN_PREPARE);
        verify(mSystemInterfaceMock)
                .sendBroadcastAsUser(mIntentCaptor.capture(), eq(UserHandle.ALL));
        verifyGarageModeBroadcast(mIntentCaptor.getAllValues(), 1, ACTION_GARAGE_MODE_ON);
    }

    @Test
    public void testOnShutdownCancelled_shouldCancelGarageMode() {
        startAndAssertGarageModeWithSignal(CarPowerManager.STATE_SHUTDOWN_PREPARE);

        // Sending shutdown cancelled signal to controller, GarageMode should wrap up and stop
        mController.onStateChanged(CarPowerManager.STATE_SHUTDOWN_CANCELLED, /* future= */ null);

        // Verify that GarageMode is not active anymore
        assertThat(mController.isGarageModeActive()).isFalse();

        // Verify that monitoring thread has stopped
        verify(mHandlerMock, Mockito.atLeastOnce()).removeCallbacks(any(Runnable.class));

        // Verify that OFF signal broadcasted to JobScheduler
        verify(mSystemInterfaceMock, times(2))
                .sendBroadcastAsUser(mIntentCaptor.capture(), eq(UserHandle.ALL));
        verifyGarageModeBroadcast(mIntentCaptor.getAllValues(), 1, ACTION_GARAGE_MODE_ON);
        verifyGarageModeBroadcast(mIntentCaptor.getAllValues(), 2, ACTION_GARAGE_MODE_OFF);

        // Verify that bounded future got completed due to the cancellation.
        assertThat(mFuture.isCompleted()).isTrue();
    }

    private void verifyGarageModeBroadcast(List<Intent> intents, int times, String action) {
        // Capture sent intent and verify that it is correct
        Intent i = intents.get(times - 1);
        assertThat(i.getAction()).isEqualTo(action);

        // Verify that additional critical flags are bundled as well
        final int flags = Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_NO_ABORT;
        boolean areRequiredFlagsSet = ((flags & i.getFlags()) == flags);
        assertThat(areRequiredFlagsSet).isTrue();
    }

    private void verifyScheduledTimes(List<Integer> ints) {
        int idx = 0;
        for (int i : ints) {
            assertWithMessage("Index " + i).that(i)
                    .isEqualTo(TEMPLATE_WAKEUP_SCHEDULE_SECONDS[idx++]);
        }
    }

    private void startAndAssertGarageModeWithSignal(int signal) {
        // Sending notification that state has changed
        mController.onStateChanged(signal, mFuture);

        // Assert that GarageMode has been started
        assertThat(mController.isGarageModeActive()).isTrue();

        // Verify that worker that polls running jobs from JobScheduler is scheduled.
        verify(mHandlerMock).postDelayed(any(), eq(JOB_SNAPSHOT_INITIAL_UPDATE_MS));
    }

    @Test
    public void testInitAndRelease() {
        Executor mockExecutor = mock(Executor.class);
        when(mContextMock.getMainExecutor()).thenReturn(mockExecutor);
        GarageMode garageMode = mock(GarageMode.class);
        Controller controller = new Controller(mContextMock, mLooperMock, mHandlerMock, garageMode);

        controller.init();
        controller.release();

        verify(garageMode).init();
        verify(garageMode).release();
    }

    @Test
    public void testConstructor() {
        Resources resourcesMock = mock(Resources.class);
        when(mContextMock.getResources()).thenReturn(resourcesMock);

        Controller controller = new Controller(mContextMock, mLooperMock);

        assertThat(controller).isNotNull();
    }

    @Test
    public void testOnStateChanged() {
        GarageMode garageMode = mock(GarageMode.class);

        Controller controller = spy(new Controller(mContextMock, mLooperMock, mHandlerMock,
                garageMode));

        controller.onStateChanged(CarPowerManager.STATE_SHUTDOWN_CANCELLED, /* future= */ null);
        verify(controller).resetGarageMode();

        clearInvocations(controller);
        controller.onStateChanged(CarPowerManager.STATE_SHUTDOWN_ENTER, /* future= */ null);
        verify(controller).resetGarageMode();

        clearInvocations(controller);
        controller.onStateChanged(CarPowerManager.STATE_SUSPEND_ENTER, /* future= */ null);
        verify(controller).resetGarageMode();

        clearInvocations(controller);
        controller.onStateChanged(CarPowerManager.STATE_SUSPEND_EXIT, /* future= */ null);
        verify(controller).resetGarageMode();

        clearInvocations(controller);
        controller.onStateChanged(CarPowerManager.STATE_HIBERNATION_ENTER, /* future= */ null);
        verify(controller).resetGarageMode();

        clearInvocations(controller);
        controller.onStateChanged(CarPowerManager.STATE_HIBERNATION_EXIT, /* future= */ null);
        verify(controller).resetGarageMode();

        clearInvocations(controller);
        controller.onStateChanged(CarPowerManager.STATE_INVALID , /* future= */ null);
        verify(controller, never()).resetGarageMode();
    }
}
