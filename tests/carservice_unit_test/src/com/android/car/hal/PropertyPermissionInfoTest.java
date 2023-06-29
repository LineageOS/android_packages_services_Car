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

package com.android.car.hal;

import static com.android.car.CarServiceUtils.toIntArray;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.automotive.vehicle.VehicleVendorPermission;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import com.android.car.hal.PropertyPermissionInfo.AllOfPermissions;
import com.android.car.hal.PropertyPermissionInfo.AnyOfPermissions;
import com.android.car.hal.PropertyPermissionInfo.SinglePermission;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PropertyPermissionInfoTest {
    private static final String TAG = PropertyPermissionInfoTest.class.getSimpleName();
    private static final int VENDOR_PROPERTY_1 = 0x21e01111;
    private static final int VENDOR_PROPERTY_2 = 0x21e01112;
    private static final int VENDOR_PROPERTY_3 = 0x21e01113;
    private static final int VENDOR_PROPERTY_4 = 0x21e01114;
    private static final int[] VENDOR_PROPERTY_IDS = {
            VENDOR_PROPERTY_1, VENDOR_PROPERTY_2, VENDOR_PROPERTY_3, VENDOR_PROPERTY_4};
    private static final int[] SYSTEM_PROPERTY_IDS = {VehiclePropertyIds.ENGINE_OIL_LEVEL,
            VehiclePropertyIds.CURRENT_GEAR, VehiclePropertyIds.NIGHT_MODE,
            VehiclePropertyIds.HVAC_FAN_SPEED, VehiclePropertyIds.DOOR_LOCK};
    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    private Context mContext;
    private PropertyPermissionInfo mPropertyPermissionInfo;

    @Before
    public void setUp() {
        mPropertyPermissionInfo = new PropertyPermissionInfo();

        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_CAR_ENGINE_DETAILED))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_CONTROL_CAR_CLIMATE))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_READ_WINDSHIELD_WIPERS))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_CONTROL_WINDSHIELD_WIPERS))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_CONTROL_ADAS_STATES))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_READ_DISPLAY_UNITS))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_CONTROL_DISPLAY_UNITS))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VENDOR_EXTENSION))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
    }

    /**
     * Test {@link PropertyPermissionInfo#getReadPermission(int)}
     * and {@link PropertyPermissionInfo#getWritePermission(int)} for system properties
     */
    @Test
    public void testGetReadWritePermissionForSystemProperty() {
        assertThat(mPropertyPermissionInfo.getReadPermission(VehiclePropertyIds.ENGINE_OIL_LEVEL)
                .toString()).isEqualTo(Car.PERMISSION_CAR_ENGINE_DETAILED);
        assertThat(mPropertyPermissionInfo.getWritePermission(VehiclePropertyIds.ENGINE_OIL_LEVEL))
                .isNull();
        assertThat(mPropertyPermissionInfo.getReadPermission(VehiclePropertyIds.HVAC_FAN_SPEED)
                .toString()).isEqualTo(Car.PERMISSION_CONTROL_CAR_CLIMATE);
        assertThat(mPropertyPermissionInfo.getWritePermission(VehiclePropertyIds.HVAC_FAN_SPEED)
                .toString()).isEqualTo(Car.PERMISSION_CONTROL_CAR_CLIMATE);
        assertThat(mPropertyPermissionInfo
                .getReadPermission(VehiclePropertyIds.WINDSHIELD_WIPERS_SWITCH).toString())
                .isEqualTo(Car.PERMISSION_READ_WINDSHIELD_WIPERS);
        assertThat(mPropertyPermissionInfo
                .getWritePermission(VehiclePropertyIds.WINDSHIELD_WIPERS_SWITCH).toString())
                .isEqualTo(Car.PERMISSION_CONTROL_WINDSHIELD_WIPERS);
        assertThat(mPropertyPermissionInfo
                .getReadPermission(VehiclePropertyIds.CRUISE_CONTROL_COMMAND)).isNull();
        assertThat(mPropertyPermissionInfo
                .getWritePermission(VehiclePropertyIds.CRUISE_CONTROL_COMMAND).toString())
                .isEqualTo(Car.PERMISSION_CONTROL_ADAS_STATES);
        assertThat(mPropertyPermissionInfo
                .getReadPermission(VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS).toString())
                .isEqualTo("(" + Car.PERMISSION_CONTROL_CAR_CLIMATE + " || "
                        + Car.PERMISSION_READ_DISPLAY_UNITS + ")");
        assertThat(mPropertyPermissionInfo
                .getWritePermission(VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS).toString())
                .isEqualTo(Car.PERMISSION_CONTROL_CAR_CLIMATE);
        assertThat(mPropertyPermissionInfo
                .getReadPermission(VehiclePropertyIds.DISTANCE_DISPLAY_UNITS).toString())
                .isEqualTo(Car.PERMISSION_READ_DISPLAY_UNITS);
        assertThat(mPropertyPermissionInfo
                .getWritePermission(VehiclePropertyIds.DISTANCE_DISPLAY_UNITS).toString())
                .isEqualTo("(" + Car.PERMISSION_CONTROL_DISPLAY_UNITS + " && "
                        + Car.PERMISSION_VENDOR_EXTENSION + ")");
    }

    /**
     * Test {@link PropertyPermissionInfo#getReadPermission(int)}
     * and {@link PropertyPermissionInfo#getWritePermission(int)} for vendor properties
     */
    @Test
    public void testGetReadWritePermissionForVendorProperty() {
        for (int vendorProp : VENDOR_PROPERTY_IDS) {
            assertThat(mPropertyPermissionInfo.getReadPermission(vendorProp)
                    .toString()).isEqualTo(Car.PERMISSION_VENDOR_EXTENSION);
            assertThat(mPropertyPermissionInfo.getWritePermission(vendorProp)
                    .toString()).isEqualTo(Car.PERMISSION_VENDOR_EXTENSION);
        }
    }

    /**
     * Test {@link PropertyPermissionInfo#isReadable(int, Context)}
     * and {@link PropertyPermissionInfo#isWritable(int, Context)} for system properties
     */
    @Test
    public void testIsReadableWritableForSystemProperty() {
        assertThat(mPropertyPermissionInfo
                .isReadable(VehiclePropertyIds.ENGINE_OIL_LEVEL, mContext)).isTrue();
        assertThat(mPropertyPermissionInfo
                .isWritable(VehiclePropertyIds.ENGINE_OIL_LEVEL, mContext)).isFalse();
        assertThat(mPropertyPermissionInfo
                .isReadable(VehiclePropertyIds.HVAC_FAN_SPEED, mContext)).isTrue();
        assertThat(mPropertyPermissionInfo
                .isWritable(VehiclePropertyIds.HVAC_FAN_SPEED, mContext)).isTrue();
        assertThat(mPropertyPermissionInfo
                .isReadable(VehiclePropertyIds.WINDSHIELD_WIPERS_SWITCH, mContext)).isFalse();
        assertThat(mPropertyPermissionInfo
                .isWritable(VehiclePropertyIds.WINDSHIELD_WIPERS_SWITCH, mContext)).isTrue();
        assertThat(mPropertyPermissionInfo
                .isReadable(VehiclePropertyIds.CRUISE_CONTROL_COMMAND, mContext)).isFalse();
        assertThat(mPropertyPermissionInfo
                .isWritable(VehiclePropertyIds.CRUISE_CONTROL_COMMAND, mContext)).isFalse();
        assertThat(mPropertyPermissionInfo
                .isReadable(VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS, mContext)).isTrue();
        assertThat(mPropertyPermissionInfo
                .isWritable(VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS, mContext)).isTrue();
        assertThat(mPropertyPermissionInfo
                .isReadable(VehiclePropertyIds.DISTANCE_DISPLAY_UNITS, mContext)).isFalse();
        assertThat(mPropertyPermissionInfo
                .isWritable(VehiclePropertyIds.DISTANCE_DISPLAY_UNITS, mContext)).isTrue();

        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_CONTROL_CAR_CLIMATE))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VENDOR_EXTENSION))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertThat(mPropertyPermissionInfo
                .isReadable(VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS, mContext)).isFalse();
        assertThat(mPropertyPermissionInfo
                .isWritable(VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS, mContext)).isFalse();
        assertThat(mPropertyPermissionInfo
                .isReadable(VehiclePropertyIds.DISTANCE_DISPLAY_UNITS, mContext)).isFalse();
        assertThat(mPropertyPermissionInfo
                .isWritable(VehiclePropertyIds.DISTANCE_DISPLAY_UNITS, mContext)).isFalse();
    }

    /**
     * Test {@link PropertyPermissionInfo#isReadable(int, Context)}
     * and {@link PropertyPermissionInfo#isWritable(int, Context)} for vendor properties
     */
    @Test
    public void testIsReadableWritableForVendorProperty() {
        for (int vendorProp : VENDOR_PROPERTY_IDS) {
            assertThat(mPropertyPermissionInfo.isReadable(vendorProp, mContext)).isTrue();
            assertThat(mPropertyPermissionInfo.isWritable(vendorProp, mContext)).isTrue();
        }

        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VENDOR_EXTENSION))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        for (int vendorProp : VENDOR_PROPERTY_IDS) {
            assertThat(mPropertyPermissionInfo.isReadable(vendorProp, mContext)).isFalse();
            assertThat(mPropertyPermissionInfo.isWritable(vendorProp, mContext)).isFalse();
        }
    }

    /**
     * Test {@link PropertyPermissionInfo#addPermissions(int, String, String)} and {@link
     * PropertyPermissionInfo#customizeVendorPermission(int[])}
     */
    @Test
    public void testCustomizeVendorPermission() {
        List<Integer> mConfigArray = new ArrayList<>();
        // set up read permission and write permission to VENDOR_PROPERTY_1
        mConfigArray.add(VENDOR_PROPERTY_1);
        mConfigArray.add(VehicleVendorPermission.PERMISSION_DEFAULT);
        mConfigArray.add(VehicleVendorPermission.PERMISSION_NOT_ACCESSIBLE);
        // set up read permission and write permission to VENDOR_PROPERTY_2
        mConfigArray.add(VENDOR_PROPERTY_2);
        mConfigArray.add(VehicleVendorPermission.PERMISSION_GET_VENDOR_CATEGORY_ENGINE);
        mConfigArray.add(VehicleVendorPermission.PERMISSION_SET_VENDOR_CATEGORY_ENGINE);
        // set up read permission and write permission to VENDOR_PROPERTY_3
        mConfigArray.add(VENDOR_PROPERTY_3);
        mConfigArray.add(VehicleVendorPermission.PERMISSION_GET_VENDOR_CATEGORY_INFO);
        mConfigArray.add(VehicleVendorPermission.PERMISSION_DEFAULT);

        List<Integer> mConfigArrayInvalidSystemProperty = new ArrayList<>();
        // set an invalid config
        mConfigArrayInvalidSystemProperty.add(VehiclePropertyIds.CURRENT_GEAR);
        mConfigArrayInvalidSystemProperty.add(
                VehicleVendorPermission.PERMISSION_GET_VENDOR_CATEGORY_ENGINE);
        mConfigArrayInvalidSystemProperty.add(
                VehicleVendorPermission.PERMISSION_SET_VENDOR_CATEGORY_ENGINE);

        List<Integer> mConfigArrayInvalidExistingVendorProperty = new ArrayList<>();
        // set an invalid config
        mConfigArrayInvalidExistingVendorProperty.add(VENDOR_PROPERTY_1);
        mConfigArrayInvalidExistingVendorProperty.add(
                VehicleVendorPermission.PERMISSION_GET_VENDOR_CATEGORY_ENGINE);
        mConfigArrayInvalidExistingVendorProperty.add(
                VehicleVendorPermission.PERMISSION_SET_VENDOR_CATEGORY_ENGINE);

        // test insert a valid config
        mPropertyPermissionInfo.customizeVendorPermission(toIntArray(mConfigArray));
        assertThat(mPropertyPermissionInfo.getReadPermission(VENDOR_PROPERTY_1)
                .toString()).isEqualTo(Car.PERMISSION_VENDOR_EXTENSION);
        assertThat(mPropertyPermissionInfo.getWritePermission(VENDOR_PROPERTY_1)).isNull();

        assertThat(mPropertyPermissionInfo.getReadPermission(VENDOR_PROPERTY_2)
                .toString()).isEqualTo(android.car.hardware.property
                .VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_ENGINE);
        assertThat(mPropertyPermissionInfo.getWritePermission(VENDOR_PROPERTY_2)
                .toString()).isEqualTo(android.car.hardware.property
                .VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_ENGINE);

        assertThat(mPropertyPermissionInfo.getReadPermission(VENDOR_PROPERTY_3)
                .toString()).isEqualTo(android.car.hardware.property
                .VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_INFO);
        assertThat(mPropertyPermissionInfo.getWritePermission(VENDOR_PROPERTY_3)
                .toString()).isEqualTo(Car.PERMISSION_VENDOR_EXTENSION);

        assertThat(mPropertyPermissionInfo.getReadPermission(VENDOR_PROPERTY_4)
                .toString()).isEqualTo(Car.PERMISSION_VENDOR_EXTENSION);
        assertThat(mPropertyPermissionInfo.getWritePermission(VENDOR_PROPERTY_4)
                .toString()).isEqualTo(Car.PERMISSION_VENDOR_EXTENSION);

        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VENDOR_EXTENSION))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.checkCallingOrSelfPermission(
                android.car.hardware.property.VehicleVendorPermission
                        .PERMISSION_GET_CAR_VENDOR_CATEGORY_ENGINE))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.checkCallingOrSelfPermission(
                android.car.hardware.property.VehicleVendorPermission
                        .PERMISSION_SET_CAR_VENDOR_CATEGORY_ENGINE))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.checkCallingOrSelfPermission(
                android.car.hardware.property.VehicleVendorPermission
                        .PERMISSION_GET_CAR_VENDOR_CATEGORY_INFO))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertThat(mPropertyPermissionInfo.isReadable(VENDOR_PROPERTY_1, mContext)).isTrue();
        assertThat(mPropertyPermissionInfo.isWritable(VENDOR_PROPERTY_1, mContext)).isFalse();
        assertThat(mPropertyPermissionInfo.isReadable(VENDOR_PROPERTY_2, mContext)).isTrue();
        assertThat(mPropertyPermissionInfo.isWritable(VENDOR_PROPERTY_2, mContext)).isTrue();
        assertThat(mPropertyPermissionInfo.isReadable(VENDOR_PROPERTY_3, mContext)).isFalse();
        assertThat(mPropertyPermissionInfo.isWritable(VENDOR_PROPERTY_3, mContext)).isTrue();
        assertThat(mPropertyPermissionInfo.isReadable(VENDOR_PROPERTY_4, mContext)).isTrue();
        assertThat(mPropertyPermissionInfo.isWritable(VENDOR_PROPERTY_4, mContext)).isTrue();

        // test that trying to overwrite a system property throws an error.
        try {
            mPropertyPermissionInfo.customizeVendorPermission(
                    toIntArray(mConfigArrayInvalidSystemProperty));
            throw new AssertionError("Insert system properties with vendor permissions");
        } catch (IllegalArgumentException e) {
            Log.v(TAG, e.getMessage());
        }

        // test that trying to overwrite an already existing property has no effect.
        mPropertyPermissionInfo.customizeVendorPermission(
                toIntArray(mConfigArrayInvalidExistingVendorProperty));
        assertThat(mPropertyPermissionInfo.getReadPermission(VENDOR_PROPERTY_1)
                .toString()).isEqualTo(Car.PERMISSION_VENDOR_EXTENSION);
        assertThat(mPropertyPermissionInfo.getWritePermission(VENDOR_PROPERTY_1)).isNull();
    }

    /**
     * Test {@link PropertyPermissionInfo#isSupportedProperty(int)}
     */
    @Test
    public void testIsSupportedProperty() {
        for (int vendorProp : VENDOR_PROPERTY_IDS) {
            assertWithMessage("Property does not exist.").that(
                    mPropertyPermissionInfo.isSupportedProperty(vendorProp)).isTrue();
        }
        for (int systemProp : SYSTEM_PROPERTY_IDS) {
            assertWithMessage("Property does not exist.").that(
                    mPropertyPermissionInfo.isSupportedProperty(systemProp)).isTrue();
        }

        int fakeSystemPropId = 0x1fffffff;
        assertWithMessage("isSupportedProperty(fakeSystemPropId) returns true.").that(
                mPropertyPermissionInfo.isSupportedProperty(fakeSystemPropId)).isFalse();
        int fakePropId = 0xffffffff;
        assertWithMessage("isSupportedProperty(fakePropId) returns true.").that(
                mPropertyPermissionInfo.isSupportedProperty(fakePropId)).isFalse();
    }

    /**
     * Test {@link AllOfPermissions}
     */
    @Test
    public void testAllOfPermission() {
        AllOfPermissions testPermission = new AllOfPermissions(
                new SinglePermission(Car.PERMISSION_CONTROL_DISPLAY_UNITS),
                new SinglePermission(Car.PERMISSION_VENDOR_EXTENSION));

        assertThat(testPermission.toString()).isEqualTo("(" + Car.PERMISSION_CONTROL_DISPLAY_UNITS
                + " && " + Car.PERMISSION_VENDOR_EXTENSION + ")");

        assertThat(testPermission.isMet(mContext)).isTrue();

        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VENDOR_EXTENSION))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertThat(testPermission.isMet(mContext)).isFalse();

        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_CONTROL_DISPLAY_UNITS))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertThat(testPermission.isMet(mContext)).isFalse();
    }

    /**
     * Test {@link AnyOfPermissions}
     */
    @Test
    public void testAnyOfPermission() {
        AnyOfPermissions testPermission = new AnyOfPermissions(
                new SinglePermission(Car.PERMISSION_CONTROL_DISPLAY_UNITS),
                new SinglePermission(Car.PERMISSION_VENDOR_EXTENSION));

        assertThat(testPermission.toString()).isEqualTo("(" + Car.PERMISSION_CONTROL_DISPLAY_UNITS
                + " || " + Car.PERMISSION_VENDOR_EXTENSION + ")");

        assertThat(testPermission.isMet(mContext)).isTrue();

        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VENDOR_EXTENSION))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertThat(testPermission.isMet(mContext)).isTrue();

        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_CONTROL_DISPLAY_UNITS))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertThat(testPermission.isMet(mContext)).isFalse();
    }
}
