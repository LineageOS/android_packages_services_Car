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

package android.car.extendedapitest;

import static android.hardware.automotive.vehicle.TestVendorProperty.VENDOR_PROPERTY_FOR_ERROR_CODE_TESTING;
import static android.hardware.automotive.vehicle.TestVendorProperty.VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.car.Car;
import android.car.extendedapitest.testbase.CarApiTestBase;
import android.car.feature.Flags;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarInternalErrorException;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import android.car.test.CarTestManager;
import android.car.test.PermissionsCheckerRule;
import android.car.test.PermissionsCheckerRule.EnsureHasPermission;
import android.hardware.automotive.vehicle.VehicleArea;
import android.hardware.automotive.vehicle.VehiclePropertyGroup;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.hardware.automotive.vehicle.VehiclePropertyType;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.ArraySet;
import android.util.Log;

import com.android.car.test.TestPropertyAsyncCallback;
import com.android.compatibility.common.util.ApiTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public final class CarPropertyManagerTest extends CarApiTestBase {
    private static final int EXPECTED_VENDOR_ERROR_CODE = 0x00ab;
    private static final int NUMBER_OF_TEST_CODES = 0x2000;
    // 557862912
    private static final int STARTING_TEST_CODES = 0x3000 | VehiclePropertyGroup.VENDOR
            | VehicleArea.GLOBAL | VehiclePropertyType.INT32;
    private static final int END_TEST_CODES = 0x3000 + NUMBER_OF_TEST_CODES
            | VehiclePropertyGroup.VENDOR | VehicleArea.GLOBAL | VehiclePropertyType.INT32;
    private static final int TEST_VENDOR_STATUS_1 = 0xdead;
    private static final int TEST_VENDOR_STATUS_2 = 0xbeef;
    private static final int TEST_VENDOR_STATUS_3 = 0xabcd;
    private static final String TAG = CarPropertyManagerTest.class.getSimpleName();
    private static final long VHAL_DUMP_TIMEOUT_MS = 1000;
    // Number of bits to shift left to set the property vendor status in VHAL VehiclePropValue
    // status field.
    private static final int VENDOR_STATUS_SHIFT = 16;

    private final HandlerThread mHandlerThread = new HandlerThread(getClass().getSimpleName());

    @Rule
    public final PermissionsCheckerRule mPermissionsCheckerRule = new PermissionsCheckerRule();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private CarPropertyManager mCarPropertyManager;
    private CarTestManager mTestManager;
    private Binder mToken;
    private Handler mHandler;

    @Before
    public void setUp() throws Exception {
        getContext().getApplicationInfo().targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mCarPropertyManager = (CarPropertyManager) getCar().getCarManager(Car.PROPERTY_SERVICE);
        assertThat(mCarPropertyManager).isNotNull();
        mTestManager = getCarService(Car.TEST_SERVICE);
        mToken = new Binder("stop_car_service");
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quitSafely();
    }

    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_VENDOR_ERROR_CODE_PERMISSION)
    @EnsureHasPermission(Car.PERMISSION_READ_PROPERTY_VENDOR_ERROR_CODE)
    @ApiTest(apis = {"android.car.hardware.property.CarInternalErrorException#getVendorErrorCode"})
    @Test
    public void testGetProperty_withVendorPropertyId_throws() {
        List<CarPropertyConfig> carPropertyConfigList = mCarPropertyManager.getPropertyList(
                new ArraySet(List.of(VENDOR_PROPERTY_FOR_ERROR_CODE_TESTING)));
        assumeTrue("VENDOR_PROPERTY_FOR_ERROR_CODE_TESTING is supported",
                !carPropertyConfigList.isEmpty());
        CarInternalErrorException thrown = assertThrows(CarInternalErrorException.class, () ->
                mCarPropertyManager.getProperty(VENDOR_PROPERTY_FOR_ERROR_CODE_TESTING,
                        /* areaId = */ 0));

        assertThat(thrown.getVendorErrorCode()).isEqualTo(EXPECTED_VENDOR_ERROR_CODE);
    }

    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_VENDOR_ERROR_CODE_PERMISSION)
    @EnsureHasPermission(Car.PERMISSION_READ_PROPERTY_VENDOR_ERROR_CODE)
    @ApiTest(apis = {"android.car.hardware.property.CarInternalErrorException#getVendorErrorCode"})
    @Test
    public void testSetProperty_withVendorPropertyId_throws() {
        List<CarPropertyConfig> carPropertyConfigList = mCarPropertyManager.getPropertyList(
                new ArraySet(List.of(VENDOR_PROPERTY_FOR_ERROR_CODE_TESTING)));
        assumeTrue("VENDOR_PROPERTY_FOR_ERROR_CODE_TESTING is supported",
                !carPropertyConfigList.isEmpty());
        CarInternalErrorException thrown = assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.setProperty(Integer.class,
                        VENDOR_PROPERTY_FOR_ERROR_CODE_TESTING, /* areaId = */ 0, /* val= */ 0));

        assertThat(thrown.getVendorErrorCode()).isEqualTo(EXPECTED_VENDOR_ERROR_CODE);
    }

    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_STATUS_DETAILED_NOT_AVAILABLE)
    @ApiTest(
            apis = {
                "android.car.hardware.property.CarPropertyManager#subscribePropertyEvents",
                "android.car.hardware.CarPropertyValue#getPropertyStatus"
            })
    @Test
    public void testSubscribePropertyEvents_getPropertyStatus() throws Exception {
        List<CarPropertyConfig> carPropertyConfigs =
                mCarPropertyManager.getPropertyList(
                        new ArraySet(List.of(VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING)));

        assumeTrue(
                "VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING is not supported",
                !carPropertyConfigs.isEmpty());

        int areaId = 0;
        int value = 0;
        injectEventFromVehicleSide(
                VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING,
                areaId,
                value,
                VehiclePropertyStatus.NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED,
                SystemClock.elapsedRealtimeNanos());
        int numEvents = 3;
        TestCallback callback =
                new TestCallback(VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING, areaId, numEvents);

        assertThat(
                        mCarPropertyManager.subscribePropertyEvents(
                                VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING, callback))
                .isTrue();

        CarPropertyValue initialValue = callback.waitAndGetInitialEvent();

        expectThat(initialValue.getPropertyId())
                .isEqualTo(VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING);
        expectThat(initialValue.getAreaId()).isEqualTo(areaId);
        expectThat(initialValue.getPropertyStatus())
                .isEqualTo(CarPropertyValue.STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED);

        injectEventFromVehicleSide(
                VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING,
                areaId,
                value,
                VehiclePropertyStatus.NOT_AVAILABLE_SAFETY,
                SystemClock.elapsedRealtimeNanos());
        injectEventFromVehicleSide(
                VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING,
                areaId,
                value,
                VehiclePropertyStatus.ERROR,
                SystemClock.elapsedRealtimeNanos());
        List<CarPropertyValue> carPropertyValues = callback.waitAndGetChangeEvents();
        mCarPropertyManager.unsubscribePropertyEvents(callback);

        assertThat(carPropertyValues).hasSize(2);
        for (CarPropertyValue carPropertyValue : carPropertyValues) {
            expectThat(carPropertyValue.getPropertyId())
                    .isEqualTo(VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING);
            expectThat(carPropertyValue.getAreaId()).isEqualTo(areaId);
        }
        expectThat(carPropertyValues.get(0).getPropertyStatus())
                .isEqualTo(CarPropertyValue.STATUS_NOT_AVAILABLE_SAFETY);
        expectThat(carPropertyValues.get(1).getPropertyStatus())
                .isEqualTo(CarPropertyValue.STATUS_ERROR);
    }

    @RequiresFlagsEnabled(Flags.FLAG_CAR_PROPERTY_STATUS_DETAILED_NOT_AVAILABLE)
    @EnsureHasPermission(Car.PERMISSION_READ_PROPERTY_VENDOR_STATUS)
    @ApiTest(
            apis = {
                "android.car.hardware.property.CarPropertyManager#subscribePropertyEvents",
                "android.car.hardware.CarPropertyValue#getPropertyVendorStatus"
            })
    @Test
    public void testSubscribePropertyEvents_getPropertyVendorStatus() throws Exception {
        List<CarPropertyConfig> carPropertyConfigs =
                mCarPropertyManager.getPropertyList(
                        new ArraySet(List.of(VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING)));

        assumeTrue(
                "VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING is not supported",
                !carPropertyConfigs.isEmpty());

        int areaId = 0;
        int value = 0;
        injectEventFromVehicleSide(
                VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING,
                areaId,
                value,
                combineSystemAndVendorStatus(VehiclePropertyStatus.ERROR, TEST_VENDOR_STATUS_1),
                SystemClock.elapsedRealtimeNanos());

        int numEvents = 3;
        TestCallback callback =
                new TestCallback(VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING, areaId, numEvents);

        assertThat(
                        mCarPropertyManager.subscribePropertyEvents(
                                VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING, callback))
                .isTrue();

        CarPropertyValue initialValue = callback.waitAndGetInitialEvent();

        expectThat(initialValue.getPropertyId())
                .isEqualTo(VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING);
        expectThat(initialValue.getAreaId()).isEqualTo(areaId);
        expectThat(initialValue.getPropertyVendorStatus()).isEqualTo(TEST_VENDOR_STATUS_1);

        injectEventFromVehicleSide(
                VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING,
                areaId,
                value,
                combineSystemAndVendorStatus(
                        VehiclePropertyStatus.UNAVAILABLE, TEST_VENDOR_STATUS_2),
                SystemClock.elapsedRealtimeNanos());
        injectEventFromVehicleSide(
                VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING,
                areaId,
                value,
                combineSystemAndVendorStatus(
                        VehiclePropertyStatus.NOT_AVAILABLE_SAFETY, TEST_VENDOR_STATUS_3),
                SystemClock.elapsedRealtimeNanos());

        List<CarPropertyValue> carPropertyValues = callback.waitAndGetChangeEvents();
        mCarPropertyManager.unsubscribePropertyEvents(callback);

        assertThat(carPropertyValues).hasSize(2);
        for (CarPropertyValue carPropertyValue : carPropertyValues) {
            expectThat(carPropertyValue.getPropertyId())
                    .isEqualTo(VENDOR_PROPERTY_FOR_PROPERTY_STATUS_TESTING);
            expectThat(carPropertyValue.getAreaId()).isEqualTo(areaId);
        }
        expectThat(carPropertyValues.get(0).getPropertyVendorStatus())
                .isEqualTo(TEST_VENDOR_STATUS_2);
        expectThat(carPropertyValues.get(1).getPropertyVendorStatus())
                .isEqualTo(TEST_VENDOR_STATUS_3);
    }

    @ApiTest(apis = {"android.car.hardware.property.CarPropertyManager#getPropertyList()"})
    @Test
    public void testGetPropertyListWithLargeNumberOfConfigs() throws Exception {
        List<CarPropertyConfig> carPropertyConfigsListBeforeGenerating =
                mCarPropertyManager.getPropertyList();
        Set<Integer> resultSet = new ArraySet<>();
        for (int i = 0; i < carPropertyConfigsListBeforeGenerating.size(); i++) {
            resultSet.add(carPropertyConfigsListBeforeGenerating.get(i).getPropertyId());
        }
        boolean isDebugCommandSupported = false;
        try {
            for (int i = STARTING_TEST_CODES; i < END_TEST_CODES; i++) {
                assertThat(i).isNotIn(resultSet);
            }
            Log.d(TAG, "Stopping car service for test");
            mTestManager.stopCarService(mToken);
            String result = CarApiTestBase.executeShellCommand(
                    "dumpsys car_service gen-test-vendor-configs");
            Log.d(TAG, "Starting car service for test");
            mTestManager.startCarService(mToken);

            List<CarPropertyConfig> carPropertyConfigsList = mCarPropertyManager.getPropertyList();
            resultSet = new ArraySet<>();
            for (int i = 0; i < carPropertyConfigsList.size(); i++) {
                resultSet.add(carPropertyConfigsList.get(i).getPropertyId());
            }
            isDebugCommandSupported = result.equals("successfully generated vendor configs\n");
            assumeTrue("successfully generated vendor configs, VHAL gen-test-vendor-configs "
                            + "debug command is supported",
                    isDebugCommandSupported);
            for (int i = STARTING_TEST_CODES; i < END_TEST_CODES; i++) {
                assertThat(i).isIn(resultSet);
            }
        } finally {
            restoreCarService(isDebugCommandSupported);
        }
    }

    @ApiTest(apis = {"android.car.hardware.property.CarPropertyManager#setPropertiesAsync"
            + "(List, long, CancellationSignal, Executor, SetPropertyCallback)"})
    @Test
    public void testSetPropertiesAsyncWithLargeNumberRequests() throws Exception {
        boolean isDebugCommandSupported = false;
        try {
            Log.d(TAG, "Stopping car service for test");
            mTestManager.stopCarService(mToken);
            String result = CarApiTestBase.executeShellCommand(
                    "dumpsys car_service gen-test-vendor-configs");
            Log.d(TAG, "Starting car service for test");
            mTestManager.startCarService(mToken);
            isDebugCommandSupported = result.equals("successfully generated vendor configs\n");
            assumeTrue("successfully generated vendor configs, VHAL gen-test-vendor-configs "
                            + "debug command is supported",
                    isDebugCommandSupported);
            Executor callbackExecutor = new HandlerExecutor(mHandler);
            Set<Integer> setPropertyIds = new ArraySet<>();
            List<CarPropertyManager.SetPropertyRequest<?>> setPropertyRequests = new ArrayList<>();

            for (int i = STARTING_TEST_CODES; i < END_TEST_CODES; i++) {
                CarPropertyManager.SetPropertyRequest setRequest =
                        mCarPropertyManager.generateSetPropertyRequest(
                                i, /* areaId= */ 0, /* value= */ 10);
                setRequest.setWaitForPropertyUpdate(false);
                setPropertyRequests.add(setRequest);
                setPropertyIds.add(setRequest.getRequestId());
            }
            TestPropertyAsyncCallback callback = new TestPropertyAsyncCallback(
                    setPropertyIds);
            mCarPropertyManager.setPropertiesAsync(setPropertyRequests, /* timeoutInMs= */ 10000,
                    /* cancellationSignal= */ null, callbackExecutor, callback);

            callback.waitAndFinish(/* timeoutInMs= */ 30000);
            assertThat(callback.getTestErrors()).isEmpty();
            List<CarPropertyManager.SetPropertyResult> results = callback.getSetResultList();
            assertThat(results).hasSize(NUMBER_OF_TEST_CODES);
            assertThat(callback.getErrorList().size()).isEqualTo(0);
        } finally {
            Log.d(TAG, "restoring car service");
            restoreCarService(isDebugCommandSupported);
        }
    }

    @ApiTest(apis = {"android.car.hardware.property.CarPropertyManager#getPropertiesAsync"
            + "(List, long, CancellationSignal, Executor, GetPropertyCallback)"})
    @Test
    public void testGetPropertiesAsyncWithLargeNumberRequests() throws Exception {
        boolean isDebugCommandSupported = false;
        try {
            Log.d(TAG, "Stopping car service for test");
            mTestManager.stopCarService(mToken);
            String result = CarApiTestBase.executeShellCommand(
                    "dumpsys car_service gen-test-vendor-configs");
            isDebugCommandSupported = result.equals("successfully generated vendor configs\n");
            Log.d(TAG, "Starting car service for test");
            mTestManager.startCarService(mToken);
            assumeTrue("successfully generated vendor configs, VHAL gen-test-vendor-configs "
                            + "debug command is supported",
                    isDebugCommandSupported);

            Executor callbackExecutor = new HandlerExecutor(mHandler);
            Set<Integer> getPropertyIds = new ArraySet<>();
            List<CarPropertyManager.GetPropertyRequest> getPropertyRequests = new ArrayList<>();

            for (int i = STARTING_TEST_CODES; i < END_TEST_CODES; i++) {
                CarPropertyManager.GetPropertyRequest getRequest =
                        mCarPropertyManager.generateGetPropertyRequest(
                                i, /* areaId= */ 0);
                getPropertyRequests.add(getRequest);
                getPropertyIds.add(getRequest.getRequestId());
            }
            TestPropertyAsyncCallback callback = new TestPropertyAsyncCallback(
                    getPropertyIds);
            mCarPropertyManager.getPropertiesAsync(getPropertyRequests, /* timeoutInMs= */ 10000,
                    /* cancellationSignal= */ null, callbackExecutor, callback);

            callback.waitAndFinish(/* timeoutInMs= */ 30000);
            assertThat(callback.getTestErrors()).isEmpty();
            List<CarPropertyManager.GetPropertyResult<?>> results = callback.getGetResultList();
            assertThat(results.size()).isEqualTo(NUMBER_OF_TEST_CODES);
            assertThat(callback.getErrorList()).isEmpty();
        } finally {
            Log.d(TAG, "restoring car service");
            restoreCarService(isDebugCommandSupported);
        }
    }

    private void restoreCarService(boolean isDebugCommandSupported) throws Exception {
        Binder restartCarService = new Binder("restart_car_service_after_test");
        try {
            Log.d(TAG, "Stopping car service for test");
            mTestManager.stopCarService(restartCarService);
            if (isDebugCommandSupported) {
                String result = CarApiTestBase.executeShellCommand(
                        "dumpsys car_service restore-vendor-configs");
                assertThat(result.equals("successfully restored vendor configs\n")).isTrue();
            }
            Log.d(TAG, "Starting car service for test");
        } finally {
            mTestManager.startCarService(restartCarService);
        }
    }

    /**
     * Inject events from vehicle side. It change the value of property even the property is a
     * read_only property such as GEAR_SELECTION. It only works with reference VHAL.
     */
    private void injectEventFromVehicleSide(
            int propertyId, int areaId, int value, int status, long timestampNs) {
        String propIdStr = Integer.toString(propertyId);
        String areaIdStr = Integer.toString(areaId);
        String valueStr = Integer.toString(value);
        String statusStr = Integer.toString(status);
        String timestampStr = Long.toString(timestampNs);

        String output =
                mTestManager.dumpVhal(
                        List.of(
                                "--inject-event",
                                propIdStr,
                                "-p",
                                statusStr,
                                "-a",
                                areaIdStr,
                                "-i",
                                valueStr,
                                "-t",
                                timestampStr),
                        VHAL_DUMP_TIMEOUT_MS);

        if (!output.contains("injected")) {
            throw new IllegalArgumentException(
                    "Failed to set the property via VHAL dump, output: " + output);
        }
    }

    private static int combineSystemAndVendorStatus(int systemStatus, int vendorStatus) {
        return vendorStatus << VENDOR_STATUS_SHIFT | systemStatus;
    }

    private static final class TestCallback implements CarPropertyEventCallback {
        private static final String TAG = TestCallback.class.getSimpleName();
        private static final int CALLBACK_TIMEOUT_MS = 5000;
        private final Object mLock = new Object();
        private final List<CarPropertyValue> mCarPropertyValues = new ArrayList<>();
        private final CountDownLatch mInitialEventLatch = new CountDownLatch(1);
        private final CountDownLatch mChangeEventsLatch;
        private final int mPropertyId;
        private final int mAreaId;

        TestCallback(int propertyId, int areaId, int numEvents) {
            mChangeEventsLatch = new CountDownLatch(numEvents);
            mPropertyId = propertyId;
            mAreaId = areaId;
        }

        @Override
        public void onChangeEvent(CarPropertyValue carPropertyValue) {
            if (carPropertyValue.getPropertyId() != mPropertyId
                    || carPropertyValue.getAreaId() != mAreaId) {
                return;
            }
            synchronized (mLock) {
                mCarPropertyValues.add(carPropertyValue);
                mInitialEventLatch.countDown();
                mChangeEventsLatch.countDown();
            }
        }

        @Override
        public void onErrorEvent(int propertyId, int areaId) {
            Log.w(TAG, "Error event for propertyId: " + propertyId + ", areaId: " + areaId);
        }

        CarPropertyValue waitAndGetInitialEvent() throws InterruptedException {
            assertThat(mInitialEventLatch.await(CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    .isTrue();
            synchronized (mLock) {
                return mCarPropertyValues.get(0);
            }
        }

        List<CarPropertyValue> waitAndGetChangeEvents() throws InterruptedException {
            assertThat(mChangeEventsLatch.await(CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    .isTrue();
            synchronized (mLock) {
                return List.copyOf(mCarPropertyValues.subList(1, mCarPropertyValues.size()));
            }
        }
    }
}
