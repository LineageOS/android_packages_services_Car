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
import static android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED;
import static android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY;
import static android.car.hardware.CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ;
import static android.car.hardware.CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE;
import static android.car.hardware.CarPropertyValue.STATUS_AVAILABLE;

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
import android.car.hardware.property.AreaIdConfig;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertySimulationManager;
import android.car.hardware.property.Subscription;
import android.car.test.PermissionsCheckerRule;
import android.car.test.PermissionsCheckerRule.EnsureHasPermission;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.android.bedstead.nene.TestApis;
import com.android.bedstead.permissions.PermissionContext;
import com.android.compatibility.common.util.ApiTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    @After
    public void tearDown() {
        // Both methods are idempotent
        try (PermissionContext p = TestApis.permissions().withPermission(
                "android.car.permission.RECORD_VEHICLE_PROPERTIES",
                "android.car.permission.INJECT_VEHICLE_PROPERTIES")) {
            mCarPropertySimulationManager.stopRecordingVehicleProperties();
            mCarPropertySimulationManager.disableInjectionMode();
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @ApiTest(
            apis = {
                    "android.car.CarProjectionManager#enableInjectionMode",
                    "android.car.CarProjectionManager#isVehiclePropertyInjectionModeEnabled",
                    "android.car.CarProjectionManager#disableInjectionMode"
            })
    @EnsureHasPermission({Car.PERMISSION_INJECT_VEHICLE_PROPERTIES,
            Car.PERMISSION_RECORD_VEHICLE_PROPERTIES})
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
    @EnsureHasPermission({Car.PERMISSION_INJECT_VEHICLE_PROPERTIES,
            Car.PERMISSION_RECORD_VEHICLE_PROPERTIES})
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
    @EnsureHasPermission({Car.PERMISSION_INJECT_VEHICLE_PROPERTIES,
            Car.PERMISSION_RECORD_VEHICLE_PROPERTIES})
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
    @EnsureHasPermission({Car.PERMISSION_INJECT_VEHICLE_PROPERTIES,
            Car.PERMISSION_RECORD_VEHICLE_PROPERTIES})
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
    @EnsureHasPermission({Car.PERMISSION_INJECT_VEHICLE_PROPERTIES,
            Car.PERMISSION_RECORD_VEHICLE_PROPERTIES})
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
    @EnsureHasPermission({Car.PERMISSION_INJECT_VEHICLE_PROPERTIES,
            Car.PERMISSION_RECORD_VEHICLE_PROPERTIES})
    public void testStopRecordingVehiclePropertiesWhenAlreadyDisabled() {
        assumeTrue(BuildHelper.isEngBuild() || BuildHelper.isUserDebugBuild());

        assertThat(mCarPropertySimulationManager.isRecordingVehicleProperties()).isFalse();
        mCarPropertySimulationManager.stopRecordingVehicleProperties();
        assertThat(mCarPropertySimulationManager.isRecordingVehicleProperties()).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @ApiTest(
            apis = {
                    "android.car.CarProjectionManager#stopRecordingVehicleProperties",
                    "android.car.CarProjectionManager#startRecordingVehicleProperties"
            })
    @EnsureHasPermission({Car.PERMISSION_INJECT_VEHICLE_PROPERTIES,
            Car.PERMISSION_RECORD_VEHICLE_PROPERTIES})
    public void testStartRecordingCheckNumberOfEvents() throws Exception {
        Set<String> adoptablePermissions = TestApis.permissions().adoptablePermissions();
        try (PermissionContext p = TestApis.permissions().withPermission(adoptablePermissions
                .toArray(new String[0]))) {
            assumeTrue(BuildHelper.isEngBuild() || BuildHelper.isUserDebugBuild());

            CountDownLatch onRecordingFinishedLatch = new CountDownLatch(1);
            CarRecordingListener listener = new CarRecordingListener(/* carPropertyEventsLatch= */
                    null,
                    onRecordingFinishedLatch);

            mCarPropertySimulationManager.startRecordingVehicleProperties(/* callbackExecutor= */
                    null,
                    listener);

            CarPropertyEventCallback carPropertyEventCallback = new CarPropertyEventCallback();
            Pair<Integer, List<Subscription>> numberOfInitialEventSubscriptionListPair =
                    createSubscriptionList(mCarPropertyManager.getPropertyList());
            List<Subscription> subscriptionList = numberOfInitialEventSubscriptionListPair.second;
            int numberOfInitialEvents = numberOfInitialEventSubscriptionListPair.first;
            assertThat(mCarPropertyManager.subscribePropertyEvents(subscriptionList,
                    /* callbackExecutor= */ null, carPropertyEventCallback)).isTrue();
            Thread.sleep(TIMEOUT_MS);
            mCarPropertyManager.unsubscribePropertyEvents(carPropertyEventCallback);

            mCarPropertySimulationManager.stopRecordingVehicleProperties();
            assertWithMessage("CarPropertySimulationManager stop recording")
                    .that(onRecordingFinishedLatch.await(TIMEOUT_MS,
                            TimeUnit.MILLISECONDS)).isTrue();
            // Subtract carPropertyConfigList because that is the size of properties that is going
            // to be sent as an initial event. Those events are propagated through the "get" api
            // instead of onEvents
            assertThat(listener.getEventCount()).isAtLeast(carPropertyEventCallback.getEventCount()
                    - numberOfInitialEvents);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @ApiTest(
            apis = {
                    "android.car.CarProjectionManager#enableInjectionMode",
                    "android.car.CarProjectionManager#disableInjectionMode"
            })
    @EnsureHasPermission({Car.PERMISSION_INJECT_VEHICLE_PROPERTIES,
            Car.PERMISSION_RECORD_VEHICLE_PROPERTIES})
    public void testEnableInjectionModeNoPropertiesFromRealHardware() throws Exception {
        assumeTrue(BuildHelper.isEngBuild() || BuildHelper.isUserDebugBuild());
        Set<String> adoptablePermissions = TestApis.permissions().adoptablePermissions();
        try (PermissionContext p = TestApis.permissions().withPermission(adoptablePermissions
                .toArray(new String[0]))) {
            List<CarPropertyConfig> carPropertyConfigList = mCarPropertyManager.getPropertyList();
            CarPropertyEventCallback carPropertyEventCallback = new CarPropertyEventCallback();
            Pair<Integer, List<Subscription>> numberOfInitialEventSubscriptionListPair =
                    createSubscriptionList(carPropertyConfigList);
            List<Subscription> subscriptionList = numberOfInitialEventSubscriptionListPair.second;
            assertThat(mCarPropertyManager.subscribePropertyEvents(subscriptionList,
                    /* callbackExecutor= */ null, carPropertyEventCallback)).isTrue();

            mCarPropertySimulationManager.enableInjectionMode(List.of());
            carPropertyEventCallback.resetCount();
            Thread.sleep(TIMEOUT_MS);

            mCarPropertyManager.unsubscribePropertyEvents(carPropertyEventCallback);
            mCarPropertySimulationManager.disableInjectionMode();
            assertThat(carPropertyEventCallback.getEventCount()).isEqualTo(0);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @ApiTest(
            apis = {
                    "android.car.CarProjectionManager#enableInjectionMode",
                    "android.car.CarProjectionManager#injectVehicleProperties",
                    "android.car.CarProjectionManager#getLastInjectedVehicleProperty",
                    "android.car.CarProjectionManager#disableInjectionMode"
            })
    @EnsureHasPermission({Car.PERMISSION_INJECT_VEHICLE_PROPERTIES,
            Car.PERMISSION_RECORD_VEHICLE_PROPERTIES})
    public void testInjectVehicleProperties() throws Exception {
        assumeTrue(BuildHelper.isEngBuild() || BuildHelper.isUserDebugBuild());
        Set<String> adoptablePermissions = TestApis.permissions().adoptablePermissions();
        try (PermissionContext p = TestApis.permissions().withPermission(adoptablePermissions
                .toArray(new String[0]))) {
            List<CarPropertyConfig> carPropertyConfigList = mCarPropertyManager.getPropertyList();
            Pair<Integer, List<Subscription>> numberOfInitialEventSubscriptionListPair =
                    createSubscriptionList(carPropertyConfigList);
            int numberOfInitialEvent = numberOfInitialEventSubscriptionListPair.first;
            CountDownLatch initialEventsLatch = new CountDownLatch(numberOfInitialEvent);
            List<Subscription> subscriptionList = numberOfInitialEventSubscriptionListPair.second;
            CarPropertyEventCallback carPropertyEventCallback = new CarPropertyEventCallback(
                    initialEventsLatch);
            mCarPropertySimulationManager.enableInjectionMode(List.of());
            assertThat(mCarPropertyManager.subscribePropertyEvents(subscriptionList,
                    /* callbackExecutor= */ null, carPropertyEventCallback)).isTrue();

            assertWithMessage("Initial events latch")
                    .that(initialEventsLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
            carPropertyEventCallback.resetCount();
            CarPropertyValue speedValueToBeInjected = mCarPropertySimulationManager
                    .createCarPropertyValue(PERF_VEHICLE_SPEED, /* areaId= */ 0, STATUS_AVAILABLE,
                            /* timestampNanos= */ 0L, /* value= */ 15.4f);
            CarPropertyValue vehicleSpeedDisplayToBeInjected = mCarPropertySimulationManager
                    .createCarPropertyValue(PERF_VEHICLE_SPEED_DISPLAY, /* areaId= */ 0,
                            STATUS_AVAILABLE, /* timestampNanos= */ 0L, /* value= */ 19.2f);
            mCarPropertySimulationManager.injectVehicleProperties(List.of(speedValueToBeInjected,
                    vehicleSpeedDisplayToBeInjected));
            Thread.sleep(TIMEOUT_MS);

            mCarPropertyManager.unsubscribePropertyEvents(carPropertyEventCallback);
            assertThat(
                    mCarPropertySimulationManager.getLastInjectedVehicleProperty(PERF_VEHICLE_SPEED)
                            .getValue()).isEqualTo(15.4f);
            assertThat(mCarPropertySimulationManager.getLastInjectedVehicleProperty(
                    PERF_VEHICLE_SPEED_DISPLAY).getValue()).isEqualTo(19.2f);
            mCarPropertySimulationManager.disableInjectionMode();
            assertThat(carPropertyEventCallback.getEventCount()).isEqualTo(2);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @ApiTest(
            apis = {
                    "android.car.CarProjectionManager#enableInjectionMode",
                    "android.car.CarProjectionManager#injectVehicleProperties",
                    "android.car.CarProjectionManager#getLastInjectedVehicleProperty",
                    "android.car.CarProjectionManager#disableInjectionMode"
            })
    @EnsureHasPermission({Car.PERMISSION_INJECT_VEHICLE_PROPERTIES,
            Car.PERMISSION_RECORD_VEHICLE_PROPERTIES})
    public void testInjectVehiclePropertiesSameValueChanged() throws Exception {
        assumeTrue(BuildHelper.isEngBuild() || BuildHelper.isUserDebugBuild());
        Set<String> adoptablePermissions = TestApis.permissions().adoptablePermissions();
        try (PermissionContext p = TestApis.permissions().withPermission(adoptablePermissions
                .toArray(new String[0]))) {

            CountDownLatch initialEventsLatch = new CountDownLatch(1);
            CarPropertyEventCallback carPropertyEventCallback = new CarPropertyEventCallback(
                    initialEventsLatch);
            assertThat(mCarPropertyManager.subscribePropertyEvents(List.of(new Subscription.Builder(
                            PERF_VEHICLE_SPEED).setUpdateRateFastest().build()),
                    /* callbackExecutor= */null, carPropertyEventCallback)).isTrue();
            assertWithMessage("Initial events latch")
                    .that(initialEventsLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
            CountDownLatch injectionCountDownLatch = new CountDownLatch(1);
            carPropertyEventCallback.setCountDownLatch(injectionCountDownLatch);

            long injectionStartTime = mCarPropertySimulationManager.enableInjectionMode(List.of());
            CarPropertyValue speedValueToBeInjected = mCarPropertySimulationManager
                    .createCarPropertyValue(PERF_VEHICLE_SPEED, /* areaId= */ 0, STATUS_AVAILABLE,
                            /* timestampNanos= */ 0L, /* value= */ 15.4f);
            mCarPropertySimulationManager.injectVehicleProperties(List.of(speedValueToBeInjected));
            assertWithMessage("Injection countdown latch")
                    .that(injectionCountDownLatch.await(TIMEOUT_MS,
                            TimeUnit.MILLISECONDS)).isTrue();
            assertThat(
                    mCarPropertySimulationManager.getLastInjectedVehicleProperty(PERF_VEHICLE_SPEED)
                            .getValue()).isEqualTo(15.4f);
            assertThat(mCarPropertyManager.getProperty(PERF_VEHICLE_SPEED, 0).getValue())
                    .isEqualTo(15.4f);

            speedValueToBeInjected = mCarPropertySimulationManager
                    .createCarPropertyValue(PERF_VEHICLE_SPEED, /* areaId= */ 0, STATUS_AVAILABLE,
                            // Inject after 1 second so it does not get filtered out by update rate
                            /* timestampNanos= */ SystemClock.elapsedRealtimeNanos()
                                    - injectionStartTime + 1000000000, /* value= */ 19.2f);
            injectionCountDownLatch = new CountDownLatch(1);
            carPropertyEventCallback.setCountDownLatch(injectionCountDownLatch);
            mCarPropertySimulationManager.injectVehicleProperties(List.of(speedValueToBeInjected));
            assertWithMessage("Injection countdown latch")
                    .that(injectionCountDownLatch.await(TIMEOUT_MS,
                            TimeUnit.MILLISECONDS)).isTrue();

            assertThat(
                    mCarPropertySimulationManager.getLastInjectedVehicleProperty(PERF_VEHICLE_SPEED)
                            .getValue()).isEqualTo(19.2f);
            assertThat(mCarPropertyManager.getProperty(PERF_VEHICLE_SPEED, 0).getValue())
                    .isEqualTo(19.2f);
            mCarPropertyManager.unsubscribePropertyEvents(carPropertyEventCallback);
            mCarPropertySimulationManager.disableInjectionMode();
        }
    }

    private Pair<Integer, List<Subscription>> createSubscriptionList(
            List<CarPropertyConfig> configList) {
        List<Subscription> list = new ArrayList<>();
        int numberOfInitialEvents = 0;
        for (int i = 0; i < configList.size(); i++) {
            CarPropertyConfig config = configList.get(i);
            Subscription.Builder builder = new Subscription.Builder(config.getPropertyId());
            List<AreaIdConfig> areaIdConfigs = config.getAreaIdConfigs();
            boolean addedAreaIds = false;
            for (int p = 0; p < areaIdConfigs.size(); p++) {
                AreaIdConfig areaIdConfig = areaIdConfigs.get(p);
                if (areaIdConfig.getAccess() != VEHICLE_PROPERTY_ACCESS_READ
                        && areaIdConfig.getAccess() != VEHICLE_PROPERTY_ACCESS_READ_WRITE) {
                    continue;
                }
                addedAreaIds = true;
                builder.addAreaId(areaIdConfigs.get(p).getAreaId());
                numberOfInitialEvents++;
            }
            if (!addedAreaIds) {
                continue;
            }
            builder.setUpdateRateFastest();
            builder.setVariableUpdateRateEnabled(false);
            list.add(builder.build());
        }
        return new Pair<>(numberOfInitialEvents, list);
    }

    private static final class CarPropertyEventCallback implements
            CarPropertyManager.CarPropertyEventCallback {

        private int mEventCount = 0;
        private CountDownLatch mCountDownLatch;

        public int getEventCount() {
            return mEventCount;
        }

        public void resetCount() {
            mEventCount = 0;
        }

        public void setCountDownLatch(CountDownLatch countDownLatch) {
            mCountDownLatch = countDownLatch;
        }

        private CarPropertyEventCallback() {
            this(null);
        }

        private CarPropertyEventCallback(CountDownLatch countDownLatch) {
            if (countDownLatch == null) {
                countDownLatch = new CountDownLatch(0);
            }
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onChangeEvent(CarPropertyValue value) {
            mEventCount++;
            mCountDownLatch.countDown();
        }

        @Override
        public void onErrorEvent(int propertyId, int areaId) {

        }
    }

    private static final class CarRecordingListener
            implements CarPropertySimulationManager.CarRecorderListener {

        private final CountDownLatch mCarPropertyEventsLatch;
        private final CountDownLatch mCarPropertyFinishedLatch;
        private int mEventCount = 0;

        private CarRecordingListener(
                CountDownLatch carPropertyEventsLatch, CountDownLatch onRecordingFinishedLatch) {
            mCarPropertyEventsLatch =
                    carPropertyEventsLatch == null ? new CountDownLatch(0) : carPropertyEventsLatch;
            mCarPropertyFinishedLatch =
                    onRecordingFinishedLatch == null
                            ? new CountDownLatch(0)
                            : onRecordingFinishedLatch;
        }

        public int getEventCount() {
            return mEventCount;
        }

        @Override
        public void onCarPropertyEvents(@NonNull List<CarPropertyValue<?>> carPropertyValues) {
            mCarPropertyEventsLatch.countDown();
            mEventCount += carPropertyValues.size();
        }

        @Override
        public void onRecordingFinished() {
            mCarPropertyFinishedLatch.countDown();
        }
    }

}
