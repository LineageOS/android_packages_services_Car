/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.car;

import static com.google.common.truth.Truth.assertThat;

import android.app.Application;
import android.car.telemetry.CarTelemetryManager;
import android.car.testapi.CarTelemetryController;
import android.car.testapi.FakeCar;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.internal.DoNotInstrument;

import java.util.concurrent.Executor;

@RunWith(RobolectricTestRunner.class)
@DoNotInstrument
public class CarTelemetryManagerTest {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private CarTelemetryController mCarTelemetryController;
    private CarTelemetryManager mCarTelemetryManager;
    @Mock
    private CarTelemetryManager.CarTelemetryResultsListener mListener;



    @Before
    public void setUp() {
        Application context = ApplicationProvider.getApplicationContext();
        FakeCar fakeCar = FakeCar.createFakeCar(context);
        Car carApi = fakeCar.getCar();

        mCarTelemetryManager =
                (CarTelemetryManager) carApi.getCarManager(Car.CAR_TELEMETRY_SERVICE);
        mCarTelemetryController = fakeCar.getCarTelemetryController();
    }

    @Test
    public void setListener_shouldSucceed() {
        mCarTelemetryManager.setListener(DIRECT_EXECUTOR, mListener);

        assertThat(mCarTelemetryController.isListenerSet()).isTrue();
    }

    @Test
    public void clearListener_shouldSucceed() {
        mCarTelemetryManager.setListener(DIRECT_EXECUTOR, mListener);
        mCarTelemetryManager.clearListener();

        assertThat(mCarTelemetryController.isListenerSet()).isFalse();
    }
}
