/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.car.extendedapitest;

import static android.car.Car.CAR_PROPERTY_SIMULATION_SERVICE;
import static android.car.Car.PROPERTY_SERVICE;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.annotation.Nullable;
import android.car.Car;
import android.car.builtin.os.BuildHelper;
import android.car.extendedapitest.testbase.CarApiTestBase;
import android.car.feature.Flags;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertySimulationManager;
import android.car.test.PermissionsCheckerRule;
import android.car.test.PermissionsCheckerRule.EnsureHasPermission;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.annotation.NonNull;

import com.android.compatibility.common.util.ApiTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CarPropertySimulationManagerTest extends CarApiTestBase {

    private static final long TIMEOUT_MS = 5000;

    @Nullable
    private CarPropertySimulationManager mCarPropertySimulationManager;
    private CarPropertyManager mCarPropertyManager;

    @Rule
    public final PermissionsCheckerRule mPermissionsCheckerRule = new PermissionsCheckerRule();

    @Before
    public void setUp() throws Exception {
        mCarPropertySimulationManager =
                (CarPropertySimulationManager)
                        getCar().getCarManager(CAR_PROPERTY_SIMULATION_SERVICE);
        mCarPropertyManager = (CarPropertyManager) getCar().getCarManager(PROPERTY_SERVICE);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @ApiTest(
            apis = {
                    "android.car.CarProjectionManager#enableInjectionMode",
                    "android.car.CarProjectionManager#isVehiclePropertyInjectionModeEnabled",
                    "android.car.CarProjectionManager#disableInjectionMode"
            })
    @EnsureHasPermission(Car.PERMISSION_INJECT_VEHICLE_PROPERTIES)
    public void testEnableInjectionMode() {
        assumeTrue(BuildHelper.isEngBuild() || BuildHelper.isUserDebugBuild());

        assertThat(mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled()).isFalse();
        long injectionModeStartTimeNanos =
                mCarPropertySimulationManager.enableInjectionMode(List.of());

        assertThat(injectionModeStartTimeNanos).isGreaterThan(0L);
        assertThat(mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled()).isTrue();
        mCarPropertySimulationManager.disableInjectionMode();

        assertThat(mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled()).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @ApiTest(
            apis = {
                    "android.car.CarProjectionManager#startRecordingVehicleProperties",
                    "android.car.CarProjectionManager#isRecordingVehicleProperties",
                    "android.car.CarProjectionManager#stopRecordingVehicleProperties"
            })
    @EnsureHasPermission(Car.PERMISSION_RECORD_VEHICLE_PROPERTIES)
    public void testRecordingVehicleProperties() throws Exception {
        assumeTrue(BuildHelper.isEngBuild() || BuildHelper.isUserDebugBuild());

        CountDownLatch onRecordingFinishedLatch = new CountDownLatch(1);
        CarRecordingListener listener = new CarRecordingListener(/* carPropertyEventsLatch= */ null,
                onRecordingFinishedLatch);
        List<CarPropertyConfig> configList = mCarPropertyManager.getPropertyList();

        assertThat(mCarPropertySimulationManager.isRecordingVehicleProperties()).isFalse();
        List<CarPropertyConfig> carPropertyConfigsRecorded =
                mCarPropertySimulationManager.startRecordingVehicleProperties(
                        /* callbackExecutor= */ null, listener);

        assertThat(carPropertyConfigsRecorded).isNotNull();
        assertThat(carPropertyConfigsRecorded.size()).isAtLeast(configList.size());
        assertThat(mCarPropertySimulationManager.isRecordingVehicleProperties()).isTrue();
        mCarPropertySimulationManager.stopRecordingVehicleProperties();
        assertWithMessage("CarPropertySimulationManager stop recording")
                .that(onRecordingFinishedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(mCarPropertySimulationManager.isRecordingVehicleProperties()).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @ApiTest(
            apis = {
                    "android.car.CarProjectionManager#startRecordingVehicleProperties",
                    "android.car.CarProjectionManager#isRecordingVehicleProperties",
                    "android.car.CarProjectionManager#stopRecordingVehicleProperties"
            })
    @EnsureHasPermission(Car.PERMISSION_RECORD_VEHICLE_PROPERTIES)
    public void testRecordingVehiclePropertiesTwice() throws Exception {
        assumeTrue(BuildHelper.isEngBuild() || BuildHelper.isUserDebugBuild());

        CountDownLatch onRecordingFinishedLatch = new CountDownLatch(1);
        CarRecordingListener listener = new CarRecordingListener(/* carPropertyEventsLatch= */ null,
                onRecordingFinishedLatch);

        assertThat(mCarPropertySimulationManager.isRecordingVehicleProperties()).isFalse();
        mCarPropertySimulationManager.startRecordingVehicleProperties(
                /* callbackExecutor= */ null, listener);
        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                mCarPropertySimulationManager.startRecordingVehicleProperties(
                                        /* callbackExecutor= */ null, listener));

        assertThat(thrown).hasMessageThat().contains("Recording already in progress");
        assertThat(mCarPropertySimulationManager.isRecordingVehicleProperties()).isTrue();
        mCarPropertySimulationManager.stopRecordingVehicleProperties();
        assertWithMessage("CarPropertySimulationManager stop recording")
                .that(onRecordingFinishedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(mCarPropertySimulationManager.isRecordingVehicleProperties()).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @ApiTest(apis = {"android.car.CarProjectionManager#startRecordingVehicleProperties"})
    @EnsureHasPermission(Car.PERMISSION_RECORD_VEHICLE_PROPERTIES)
    public void testRecordingVehiclePropertiesNullListener() {
        assumeTrue(BuildHelper.isEngBuild() || BuildHelper.isUserDebugBuild());

        assertThrows(
                NullPointerException.class,
                () ->
                        mCarPropertySimulationManager.startRecordingVehicleProperties(
                                /* callbackExecutor= */ null, /* listener= */ null));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @ApiTest(
            apis = {
                    "android.car.CarProjectionManager#enableInjectionMode",
                    "android.car.CarProjectionManager#isVehiclePropertyInjectionModeEnabled",
                    "android.car.CarProjectionManager#disableInjectionMode"
            })
    @EnsureHasPermission(Car.PERMISSION_INJECT_VEHICLE_PROPERTIES)
    public void testEnableInjectionModeTwice() {
        assumeTrue(BuildHelper.isEngBuild() || BuildHelper.isUserDebugBuild());

        assertThat(mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled()).isFalse();
        long injectionStartTime = mCarPropertySimulationManager.enableInjectionMode(List.of());
        assertThat(injectionStartTime).isAtLeast(0L);
        assertThat(mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled()).isTrue();

        long secondInjectionStartTime =
                mCarPropertySimulationManager.enableInjectionMode(List.of());

        assertThat(secondInjectionStartTime).isEqualTo(-1L);
        assertThat(mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled()).isTrue();
        mCarPropertySimulationManager.disableInjectionMode();
        assertThat(mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled()).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @ApiTest(
            apis = {
                    "android.car.CarProjectionManager#isRecordingVehicleProperties",
                    "android.car.CarProjectionManager#stopRecordingVehicleProperties"
            })
    @EnsureHasPermission(Car.PERMISSION_RECORD_VEHICLE_PROPERTIES)
    public void testStopRecordingVehiclePropertiesWhenAlreadyDisabled() {
        assumeTrue(BuildHelper.isEngBuild() || BuildHelper.isUserDebugBuild());

        assertThat(mCarPropertySimulationManager.isRecordingVehicleProperties()).isFalse();
        mCarPropertySimulationManager.stopRecordingVehicleProperties();
        assertThat(mCarPropertySimulationManager.isRecordingVehicleProperties()).isFalse();
    }

    private static final class CarRecordingListener
            implements CarPropertySimulationManager.CarRecorderListener {

        private final CountDownLatch mCarPropertyEventsLatch;
        private final CountDownLatch mCarPropertyFinishedLatch;

        private CarRecordingListener(
                CountDownLatch carPropertyEventsLatch, CountDownLatch onRecordingFinishedLatch) {
            mCarPropertyEventsLatch =
                    carPropertyEventsLatch == null ? new CountDownLatch(0) : carPropertyEventsLatch;
            mCarPropertyFinishedLatch =
                    onRecordingFinishedLatch == null
                            ? new CountDownLatch(0)
                            : onRecordingFinishedLatch;
        }

        @Override
        public void onCarPropertyEvents(@NonNull List<CarPropertyValue<?>> carPropertyValues) {
            mCarPropertyEventsLatch.countDown();
        }

        @Override
        public void onRecordingFinished() {
            mCarPropertyFinishedLatch.countDown();
        }
    }

}
