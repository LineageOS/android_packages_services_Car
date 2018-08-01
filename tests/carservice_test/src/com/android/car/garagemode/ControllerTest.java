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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.car.CarPowerManagementService;

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
    @Mock private Handler mHandlerMock;
    @Captor private ArgumentCaptor<Intent> mIntentCaptor;

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
                mHandlerMock,
                null);
    }

    @Test
    public void testOnPrepareShutdown_shouldInitiateGarageMode() {
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);

        // Shutting down the car
        mController.onPrepareShutdown(true);

        assertThat(mController.isGarageModeActive()).isTrue();
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(1);

        // Verify that worker that polls running jobs from JobScheduler is scheduled.
        verify(mHandlerMock).postDelayed(any(), eq(JOB_SNAPSHOT_INITIAL_UPDATE_MS));

        // Verify that GarageMode is sending the proper broadcast to JobScheduler to go idle
        verify(mContextMock).sendBroadcast(mIntentCaptor.capture());

        Intent i = mIntentCaptor.getValue();
        // Verify that broadcasting signal is correct
        assertThat(i.getAction()).isEqualTo(ACTION_GARAGE_MODE_ON);
        // Verify that additional critical flags are bundled as well
        final int flags = Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_NO_ABORT;
        final boolean areRequiredFlagsSet = ((flags & i.getFlags()) == flags);
        assertThat(areRequiredFlagsSet).isTrue();
    }

    @Test
    public void testOnPowerOn_shouldResetGarageMode() {
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);
        // Shutting down the car
        mController.onPrepareShutdown(true);
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(1);
        assertThat(mController.isGarageModeActive()).isTrue();
        verify(mHandlerMock).postDelayed(any(), eq(JOB_SNAPSHOT_INITIAL_UPDATE_MS));

        // Turning on the car
        mController.onPowerOn(true);
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);
        assertThat(mController.isGarageModeActive()).isFalse();
        // Verify that CPMS is notified that GarageMode is completed
        verify(mCarPowerManagementServiceMock)
                .notifyPowerEventProcessingCompletion(eq(mController));

        // Verify that GarageMode is sending the proper broadcast to JobScheduler to get out of idle
        verify(mContextMock, times(2)).sendBroadcast(mIntentCaptor.capture());

        Intent onIntent = mIntentCaptor.getAllValues().get(0);
        Intent offIntent = mIntentCaptor.getAllValues().get(1);

        // Verify that broadcasting signals are correct
        assertThat(onIntent.getAction()).isEqualTo(ACTION_GARAGE_MODE_ON);
        assertThat(offIntent.getAction()).isEqualTo(ACTION_GARAGE_MODE_OFF);

        // Verify that additional critical flags are bundled as well
        final int flags = Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_NO_ABORT;
        boolean areRequiredFlagsSet = ((flags & onIntent.getFlags()) == flags);
        assertThat(areRequiredFlagsSet).isTrue();
        areRequiredFlagsSet = ((flags & offIntent.getFlags()) == flags);
        assertThat(areRequiredFlagsSet).isTrue();
    }

    @Test
    public void testWakeupTimeProgression() {
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);
        mController.onPrepareShutdown(true);
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(1);
        assertThat(mController.isGarageModeActive()).isTrue();
        assertThat(mController.getWakeupTime()).isEqualTo(15 * 60);
        mController.onSleepEntry();
        assertThat(mController.isGarageModeActive()).isFalse();
        mController.onPrepareShutdown(true);
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(2);
        assertThat(mController.isGarageModeActive()).isTrue();
        assertThat(mController.getWakeupTime()).isEqualTo(6 * 60 * 60);
        for (int i = 0; i < 8; i++) {
            mController.onSleepEntry();
            mController.onPrepareShutdown(true);
        }
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(10);
        assertThat(mController.getWakeupTime()).isEqualTo(24 * 60 * 60);
    }
}
