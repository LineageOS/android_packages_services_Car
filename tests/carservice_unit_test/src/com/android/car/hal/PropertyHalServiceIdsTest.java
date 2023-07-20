/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.car.hardware.CarHvacFanDirection;
import android.hardware.automotive.vehicle.VehicleGear;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehicleUnit;
import android.os.SystemClock;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class PropertyHalServiceIdsTest {
    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    private PropertyHalServiceIds mPropertyHalServiceIds;
    private static final String TAG = PropertyHalServiceIdsTest.class.getSimpleName();
    private static final HalPropValueBuilder PROP_VALUE_BUILDER =
            new HalPropValueBuilder(/*isAidl=*/true);
    //payload test
    private static final HalPropValue GEAR_WITH_VALID_VALUE =
            PROP_VALUE_BUILDER.build(VehicleProperty.GEAR_SELECTION, /*areaId=*/0,
                    /*timestamp=*/SystemClock.elapsedRealtimeNanos(), /*status=*/0,
                    VehicleGear.GEAR_DRIVE);
    private static final HalPropValue GEAR_WITH_EXTRA_VALUE =
            PROP_VALUE_BUILDER.build(VehicleProperty.GEAR_SELECTION, /*areaId=*/0,
                    /*timestamp=*/SystemClock.elapsedRealtimeNanos(), /*status=*/0,
                    new int[]{VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_1});
    private static final HalPropValue GEAR_WITH_INVALID_VALUE =
            PROP_VALUE_BUILDER.build(VehicleProperty.GEAR_SELECTION, /*areaId=*/0,
                    /*timestamp=*/SystemClock.elapsedRealtimeNanos(), /*status=*/0,
                    VehicleUnit.KILOPASCAL);
    private static final HalPropValue GEAR_WITH_INVALID_TYPE_VALUE =
            PROP_VALUE_BUILDER.build(VehicleProperty.GEAR_SELECTION, /*areaId=*/0,
                    /*timestamp=*/SystemClock.elapsedRealtimeNanos(), /*status=*/0,
                    1.0f);
    private static final HalPropValue HVAC_FAN_DIRECTIONS_VALID =
            PROP_VALUE_BUILDER.build(VehicleProperty.HVAC_FAN_DIRECTION, /*areaId=*/0,
                    /*timestamp=*/SystemClock.elapsedRealtimeNanos(), /*status=*/0,
                    CarHvacFanDirection.FACE | CarHvacFanDirection.FLOOR);
    private static final HalPropValue HVAC_FAN_DIRECTIONS_INVALID =
            PROP_VALUE_BUILDER.build(VehicleProperty.HVAC_FAN_DIRECTION, /*areaId=*/0,
                    /*timestamp=*/SystemClock.elapsedRealtimeNanos(), /*status=*/0,
                    CarHvacFanDirection.FACE | 0x100);

    @Before
    public void setUp() {
        mPropertyHalServiceIds = new PropertyHalServiceIds();
    }

    /**
     * Test {@link PropertyHalServiceIds#checkPayload(HalPropValue)}
     */
    @Test
    public void testPayload() {
        assertThat(mPropertyHalServiceIds.checkPayload(GEAR_WITH_VALID_VALUE)).isTrue();
        assertThat(mPropertyHalServiceIds.checkPayload(GEAR_WITH_EXTRA_VALUE)).isFalse();
        assertThat(mPropertyHalServiceIds.checkPayload(GEAR_WITH_INVALID_VALUE)).isFalse();
        assertThat(mPropertyHalServiceIds.checkPayload(GEAR_WITH_INVALID_TYPE_VALUE)).isFalse();
        assertThat(mPropertyHalServiceIds.checkPayload(HVAC_FAN_DIRECTIONS_VALID)).isTrue();
        assertThat(mPropertyHalServiceIds.checkPayload(HVAC_FAN_DIRECTIONS_INVALID)).isFalse();
    }
}
