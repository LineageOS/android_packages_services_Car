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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.Looper;
import android.os.Message;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.car.CarPowerManagementService;
import com.android.car.garagemode.Controller.CommandHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ControllerTest {
    @Mock private Context mContextMock;
    @Mock private CarPowerManagementService mCarPowerManagementServiceMock;
    @Mock private Looper mLooperMock;
    @Mock private DeviceIdleControllerWrapper mDeviceIdleControllerWrapperMock;
    @Mock private CommandHandler mCommandHandlerMock;
    @Captor private ArgumentCaptor<Message> mMessageCaptor;

    private Controller mController;
    private WakeupPolicy mWakeupPolicy;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mWakeupPolicy = new WakeupPolicy(new String[] {
                "15m,1",
                "6h,8",
                "1d,5",
        });
        mController = new Controller(
                mContextMock,
                mCarPowerManagementServiceMock,
                mLooperMock,
                mWakeupPolicy,
                mCommandHandlerMock,
                mDeviceIdleControllerWrapperMock);
        // We need to enable GarageMode, before testing
        mController.enableGarageMode();
    }

    @Test
    public void testOnPrepareShutdown_shouldInitiateGarageMode() {
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);

        mController.onPrepareShutdown(true);

        assertThat(mController.isGarageModeInProgress()).isTrue();
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(1);

        verify(mCommandHandlerMock).sendMessageDelayed(null, mController.getMaintenanceTimeout());
    }

    @Test
    public void testInitiateGarageMode_garageModeDisabled_shouldSkip() {
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);

        mController.disableGarageMode();
        mController.initiateGarageMode();
        assertThat(mController.isGarageModeInProgress()).isFalse();
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);
    }

    @Test
    public void testOnPrepareShutdown_garageModeDisabled_shouldSkip() {
        mController.disableGarageMode();
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);

        mController.onPrepareShutdown(true);

        // Verify that no intervals were taken from policy
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);
        assertThat(mController.isGarageModeInProgress()).isFalse();
    }

    @Test
    public void testOnPowerOn_shouldResetGarageMode() {
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);
        mController.onPrepareShutdown(true);
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(1);
        assertThat(mController.isGarageModeInProgress()).isTrue();

        mController.onPowerOn(true);
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);
        assertThat(mController.isGarageModeInProgress()).isFalse();
    }

    @Test
    public void testWakeupTimeProgression() {
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);
        mController.onPrepareShutdown(true);
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(1);
        assertThat(mController.isGarageModeInProgress()).isTrue();
        assertThat(mController.getWakeupTime()).isEqualTo(15 * 60);
        mController.onSleepEntry();
        assertThat(mController.isGarageModeInProgress()).isFalse();
        mController.onPrepareShutdown(true);
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(2);
        assertThat(mController.isGarageModeInProgress()).isTrue();
        assertThat(mController.getWakeupTime()).isEqualTo(6 * 60 * 60);
        for (int i = 0; i < 8; i++) {
            mController.onSleepEntry();
            mController.onPrepareShutdown(true);
        }
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(10);
        assertThat(mController.getWakeupTime()).isEqualTo(24 * 60 * 60);
    }
}
