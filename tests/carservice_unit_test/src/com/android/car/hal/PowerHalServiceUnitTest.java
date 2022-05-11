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

package com.android.car.hal;

import static android.hardware.automotive.vehicle.VehicleApPowerStateConfigFlag.CONFIG_SUPPORT_TIMER_POWER_ON_FLAG;
import static android.hardware.automotive.vehicle.VehicleApPowerStateConfigFlag.ENABLE_DEEP_SLEEP_FLAG;
import static android.hardware.automotive.vehicle.VehicleApPowerStateConfigFlag.ENABLE_HIBERNATION_FLAG;
import static android.hardware.automotive.vehicle.VehicleApPowerStateReq.ON;
import static android.hardware.automotive.vehicle.VehicleApPowerStateReq.SHUTDOWN_PREPARE;
import static android.hardware.automotive.vehicle.VehicleApPowerStateShutdownParam.CAN_HIBERNATE;
import static android.hardware.automotive.vehicle.VehicleApPowerStateShutdownParam.CAN_SLEEP;
import static android.hardware.automotive.vehicle.VehicleApPowerStateShutdownParam.HIBERNATE_IMMEDIATELY;
import static android.hardware.automotive.vehicle.VehicleApPowerStateShutdownParam.SHUTDOWN_IMMEDIATELY;
import static android.hardware.automotive.vehicle.VehicleApPowerStateShutdownParam.SHUTDOWN_ONLY;
import static android.hardware.automotive.vehicle.VehicleApPowerStateShutdownParam.SLEEP_IMMEDIATELY;
import static android.hardware.automotive.vehicle.VehicleProperty.AP_POWER_STATE_REQ;
import static android.hardware.automotive.vehicle.VehicleProperty.DISPLAY_BRIGHTNESS;

import static com.android.car.hal.PowerHalService.PowerState.SHUTDOWN_TYPE_DEEP_SLEEP;
import static com.android.car.hal.PowerHalService.PowerState.SHUTDOWN_TYPE_HIBERNATION;
import static com.android.car.hal.PowerHalService.PowerState.SHUTDOWN_TYPE_POWER_OFF;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.when;

import android.hardware.automotive.vehicle.StatusCode;
import android.os.ServiceSpecificException;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.car.hal.test.AidlVehiclePropConfigBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public final class PowerHalServiceUnitTest {

    private static final SparseBooleanArray CAN_POSTPONE_SHUTDOWN = new SparseBooleanArray(6);
    static {
        CAN_POSTPONE_SHUTDOWN.put(CAN_HIBERNATE, true);
        CAN_POSTPONE_SHUTDOWN.put(HIBERNATE_IMMEDIATELY, false);
        CAN_POSTPONE_SHUTDOWN.put(CAN_SLEEP, true);
        CAN_POSTPONE_SHUTDOWN.put(SLEEP_IMMEDIATELY, false);
        CAN_POSTPONE_SHUTDOWN.put(SHUTDOWN_ONLY, true);
        CAN_POSTPONE_SHUTDOWN.put(SHUTDOWN_IMMEDIATELY, false);
    }
    private static final SparseBooleanArray CAN_SUSPEND = new SparseBooleanArray(6);
    static {
        CAN_SUSPEND.put(CAN_HIBERNATE, true);
        CAN_SUSPEND.put(HIBERNATE_IMMEDIATELY, true);
        CAN_SUSPEND.put(CAN_SLEEP, true);
        CAN_SUSPEND.put(SLEEP_IMMEDIATELY, true);
        CAN_SUSPEND.put(SHUTDOWN_ONLY, false);
        CAN_SUSPEND.put(SHUTDOWN_IMMEDIATELY, false);
    }
    private static final SparseIntArray SUSPEND_TYPE = new SparseIntArray(7);
    static {
        SUSPEND_TYPE.put(CAN_HIBERNATE, SHUTDOWN_TYPE_HIBERNATION);
        SUSPEND_TYPE.put(HIBERNATE_IMMEDIATELY, SHUTDOWN_TYPE_HIBERNATION);
        SUSPEND_TYPE.put(CAN_SLEEP, SHUTDOWN_TYPE_DEEP_SLEEP);
        SUSPEND_TYPE.put(SLEEP_IMMEDIATELY, SHUTDOWN_TYPE_DEEP_SLEEP);
        SUSPEND_TYPE.put(SHUTDOWN_ONLY, SHUTDOWN_TYPE_POWER_OFF);
        SUSPEND_TYPE.put(SHUTDOWN_IMMEDIATELY, SHUTDOWN_TYPE_POWER_OFF);
        SUSPEND_TYPE.put(0, SHUTDOWN_TYPE_POWER_OFF);  // for no flag.
    }

    private final HalPropValueBuilder mPropValueBuilder =
            new HalPropValueBuilder(/* isAidl= */ true);
    private final PowerEventListenerImpl mEventListener = new PowerEventListenerImpl();

    @Mock
    private VehicleHal mHal;
    private PowerHalService mPowerHalService;

    @Before
    public void setUp() {
        mPowerHalService = new PowerHalService(mHal);
        mPowerHalService.setListener(mEventListener);
    }

    @Test
    public void testCanPostponeShutdown() throws Exception {
        for (int i = 0; i < CAN_POSTPONE_SHUTDOWN.size(); i++) {
            int param = CAN_POSTPONE_SHUTDOWN.keyAt(i);
            boolean expected = CAN_POSTPONE_SHUTDOWN.valueAt(i);
            PowerHalService.PowerState powerState = createShutdownPrepare(param);
            assertWithMessage("canPostponeShutdown with %s flag", shutdownParamToString(param))
                    .that(powerState.canPostponeShutdown()).isEqualTo(expected);
        }
    }

    @Test
    public void testCanSuspend() throws Exception {
        for (int i = 0; i < CAN_SUSPEND.size(); i++) {
            int param = CAN_SUSPEND.keyAt(i);
            boolean expected = CAN_SUSPEND.valueAt(i);
            PowerHalService.PowerState powerState = createShutdownPrepare(param);
            assertWithMessage("canPostponeShutdown with %s flag", shutdownParamToString(param))
                    .that(powerState.canSuspend()).isEqualTo(expected);
        }
    }

    @Test
    public void testGetSuspendType() throws Exception {
        for (int i = 0; i < SUSPEND_TYPE.size(); i++) {
            int param = SUSPEND_TYPE.keyAt(i);
            int expected = SUSPEND_TYPE.valueAt(i);
            PowerHalService.PowerState powerState = createShutdownPrepare(param);
            assertWithMessage("canPostponeShutdown with %s flag", shutdownParamToString(param))
                    .that(powerState.getShutdownType()).isEqualTo(expected);
        }
    }

    @Test
    public void testGetCurrentPowerState() {
        HalPropValue value = mPropValueBuilder.build(AP_POWER_STATE_REQ, VehicleHal.NO_AREA,
                new int[]{ON, 0});
        when(mHal.get(AP_POWER_STATE_REQ)).thenReturn(value);

        PowerHalService.PowerState powerState = mPowerHalService.getCurrentPowerState();

        assertWithMessage("Current power state").that(powerState.mState).isEqualTo(ON);
        assertWithMessage("Current power param").that(powerState.mParam).isEqualTo(0);
    }

    @Test
    public void testGetCurrentPowerState_serviceSpecificError() {
        when(mHal.get(AP_POWER_STATE_REQ)).thenThrow(
                new ServiceSpecificException(StatusCode.INTERNAL_ERROR));

        PowerHalService.PowerState powerState = mPowerHalService.getCurrentPowerState();

        assertWithMessage("Current power state").that(powerState).isNull();
    }

    @Test
    public void testHalEventListenerPowerStateChange() {
        mPowerHalService.setListener(mEventListener);

        HalPropValue value = mPropValueBuilder.build(AP_POWER_STATE_REQ, VehicleHal.NO_AREA,
                new int[]{SHUTDOWN_PREPARE, CAN_SLEEP});
        mPowerHalService.onHalEvents(List.of(value));

        assertWithMessage("Current requested power state").that(mEventListener.getPowerState())
                .isEqualTo(SHUTDOWN_PREPARE);
        assertWithMessage("Current requested power param").that(mEventListener.getPowerParam())
                .isEqualTo(CAN_SLEEP);
    }

    @Test
    public void testHalEventListenerDisplayBrightnessChange() {
        AidlHalPropConfig config = new AidlHalPropConfig(
                AidlVehiclePropConfigBuilder.newBuilder(DISPLAY_BRIGHTNESS)
                .addAreaConfig(/* areaId= */ 0, /* minValue= */ 0, /* maxValue= */ 100).build());
        mPowerHalService.takeProperties(List.of(config));
        mPowerHalService.init();
        mPowerHalService.setListener(mEventListener);

        int expectedBrightness = 73;
        HalPropValue value = mPropValueBuilder.build(DISPLAY_BRIGHTNESS, VehicleHal.NO_AREA,
                expectedBrightness);
        mPowerHalService.onHalEvents(List.of(value));

        assertWithMessage("Display brightness").that(mEventListener.getDisplayBrightness())
                .isEqualTo(expectedBrightness);
    }

    @Test
    public void testHalEventListenerDisplayBrightnessChange_non100MaxBrightness() {
        AidlHalPropConfig config = new AidlHalPropConfig(
                AidlVehiclePropConfigBuilder.newBuilder(DISPLAY_BRIGHTNESS)
                .addAreaConfig(/* areaId= */ 0, /* minValue= */ 0, /* maxValue= */ 50).build());
        mPowerHalService.takeProperties(List.of(config));
        mPowerHalService.init();
        mPowerHalService.setListener(mEventListener);

        int brightness = 24;
        // Max brightness is 50, so the expected brightness should be multiplied 2 times to fit in
        // 100 scale.
        int expectedBrightness = brightness * 2;
        HalPropValue value = mPropValueBuilder.build(DISPLAY_BRIGHTNESS, VehicleHal.NO_AREA,
                brightness);
        mPowerHalService.onHalEvents(List.of(value));

        assertWithMessage("Display brightness").that(mEventListener.getDisplayBrightness())
                .isEqualTo(expectedBrightness);
    }

    @Test
    public void testIsDeepSleepAllowed() {
        AidlHalPropConfig config = new AidlHalPropConfig(
                AidlVehiclePropConfigBuilder.newBuilder(AP_POWER_STATE_REQ)
                .setConfigArray(List.of(ENABLE_DEEP_SLEEP_FLAG)).build());
        mPowerHalService.takeProperties(List.of(config));

        assertWithMessage("Deep sleep enabled").that(mPowerHalService.isDeepSleepAllowed())
                .isTrue();
    }

    @Test
    public void testIsDeepSleepAllowed_notSupported() {
        assertWithMessage("Deep sleep enabled").that(mPowerHalService.isDeepSleepAllowed())
                .isFalse();
    }

    @Test
    public void testIsHibernationAllowed() {
        AidlHalPropConfig config = new AidlHalPropConfig(AidlVehiclePropConfigBuilder
                .newBuilder(AP_POWER_STATE_REQ).setConfigArray(List.of(ENABLE_HIBERNATION_FLAG))
                .build());
        mPowerHalService.takeProperties(List.of(config));

        assertWithMessage("Hibernation enabled").that(mPowerHalService.isHibernationAllowed())
                .isTrue();
    }

    @Test
    public void testIsHibernationAllowed_notSupported() {
        assertWithMessage("Hibernation enabled").that(mPowerHalService.isHibernationAllowed())
                .isFalse();
    }

    @Test
    public void testIsTimedWakeupAllowed() {
        AidlHalPropConfig config = new AidlHalPropConfig(AidlVehiclePropConfigBuilder
                .newBuilder(AP_POWER_STATE_REQ)
                .setConfigArray(List.of(CONFIG_SUPPORT_TIMER_POWER_ON_FLAG)).build());
        mPowerHalService.takeProperties(List.of(config));

        assertWithMessage("Timed wakeup enabled").that(mPowerHalService.isTimedWakeupAllowed())
                .isTrue();
    }

    @Test
    public void testIsTimedWakeupAllowed_notSupported() {
        assertWithMessage("Timed wakeup enabled").that(mPowerHalService.isTimedWakeupAllowed())
                .isFalse();
    }

    private PowerHalService.PowerState createShutdownPrepare(int flag) {
        return new PowerHalService.PowerState(SHUTDOWN_PREPARE, flag);
    }

    private static String shutdownParamToString(int param) {
        switch (param) {
            case CAN_HIBERNATE:
                return "CAN_HIBERNATE";
            case HIBERNATE_IMMEDIATELY:
                return "HIBERNATE_IMMEDIATELY";
            case CAN_SLEEP:
                return "CAN_SLEEP";
            case SLEEP_IMMEDIATELY:
                return "SLEEP_IMMEDIATELY";
            case SHUTDOWN_ONLY:
                return "SHUTDOWN_ONLY";
            case SHUTDOWN_IMMEDIATELY:
                return "SHUTDOWN_IMMEDIATELY";
            default:
                return "UNKNOWN(" + param + ")";
        }
    }

    private static final class PowerEventListenerImpl
            implements PowerHalService.PowerEventListener {
        private static final int INVALID_VALUE = -1;

        private PowerHalService.PowerState mPowerState;
        private int mBrightness;

        @Override
        public void onApPowerStateChange(PowerHalService.PowerState state) {
            mPowerState = state;
        }

        @Override
        public void onDisplayBrightnessChange(int brightness) {
            mBrightness = brightness;
        }

        public int getPowerState() {
            return mPowerState != null ? mPowerState.mState : INVALID_VALUE;
        }

        public int getPowerParam() {
            return mPowerState != null ? mPowerState.mParam : INVALID_VALUE;
        }

        public int getDisplayBrightness() {
            return mBrightness;
        }
    }
}
