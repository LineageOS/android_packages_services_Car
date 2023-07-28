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

package com.android.car.hal.property;

import static com.android.car.CarServiceUtils.toIntArray;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarHvacFanDirection;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.automotive.vehicle.VehicleGear;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehicleUnit;
import android.hardware.automotive.vehicle.VehicleVendorPermission;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.SparseArray;

import androidx.test.runner.AndroidJUnit4;

import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;
import com.android.car.hal.property.PropertyHalServiceConfigs.CarSvcPropertyConfig;
import com.android.car.hal.property.PropertyPermissionInfo.AllOfPermissions;
import com.android.car.hal.property.PropertyPermissionInfo.AnyOfPermissions;
import com.android.car.hal.property.PropertyPermissionInfo.PropertyPermissions;
import com.android.car.hal.property.PropertyPermissionInfo.SinglePermission;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class PropertyHalServiceConfigsTest {
    private static final int VENDOR_PROPERTY_1 = 0x21e01111;
    private static final int VENDOR_PROPERTY_2 = 0x21e01112;
    private static final int VENDOR_PROPERTY_3 = 0x21e01113;
    private static final int VENDOR_PROPERTY_4 = 0x21e01114;
    private static final int[] VENDOR_PROPERTY_IDS = {
            VENDOR_PROPERTY_1, VENDOR_PROPERTY_2, VENDOR_PROPERTY_3, VENDOR_PROPERTY_4};

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    private Context mContext;
    @Rule
    public final Expect expect = Expect.create();

    private PropertyHalServiceConfigs mPropertyHalServiceConfigs;
    private static final String TAG = PropertyHalServiceConfigsTest.class.getSimpleName();
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
        mPropertyHalServiceConfigs = new PropertyHalServiceConfigs(/* useRuntimeConfig= */ true);

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
     * Test {@link PropertyHalServiceConfigs#checkPayload(HalPropValue)}
     */
    @Test
    public void testCheckPayload() {
        assertThat(mPropertyHalServiceConfigs.checkPayload(GEAR_WITH_VALID_VALUE)).isTrue();
        assertThat(mPropertyHalServiceConfigs.checkPayload(GEAR_WITH_EXTRA_VALUE)).isFalse();
        assertThat(mPropertyHalServiceConfigs.checkPayload(GEAR_WITH_INVALID_VALUE)).isFalse();
        assertThat(mPropertyHalServiceConfigs.checkPayload(GEAR_WITH_INVALID_TYPE_VALUE)).isFalse();
        assertThat(mPropertyHalServiceConfigs.checkPayload(HVAC_FAN_DIRECTIONS_VALID)).isTrue();
        assertThat(mPropertyHalServiceConfigs.checkPayload(HVAC_FAN_DIRECTIONS_INVALID)).isFalse();
    }

    /**
     * Test {@link PropertyHalServiceConfigs#isReadable(Context, int)}
     * and {@link PropertyHalServiceConfigs#isWritable(Context, int)} for system properties
     */
    @Test
    public void testIsReadableWritableForSystemProperty() {
        assertThat(mPropertyHalServiceConfigs
                .isReadable(mContext, VehiclePropertyIds.ENGINE_OIL_LEVEL)).isTrue();
        assertThat(mPropertyHalServiceConfigs
                .isWritable(mContext, VehiclePropertyIds.ENGINE_OIL_LEVEL)).isFalse();
        assertThat(mPropertyHalServiceConfigs
                .isReadable(mContext, VehiclePropertyIds.HVAC_FAN_SPEED)).isTrue();
        assertThat(mPropertyHalServiceConfigs
                .isWritable(mContext, VehiclePropertyIds.HVAC_FAN_SPEED)).isTrue();
        assertThat(mPropertyHalServiceConfigs
                .isReadable(mContext, VehiclePropertyIds.WINDSHIELD_WIPERS_SWITCH)).isFalse();
        assertThat(mPropertyHalServiceConfigs
                .isWritable(mContext, VehiclePropertyIds.WINDSHIELD_WIPERS_SWITCH)).isTrue();
        assertThat(mPropertyHalServiceConfigs
                .isReadable(mContext, VehiclePropertyIds.CRUISE_CONTROL_COMMAND)).isFalse();
        assertThat(mPropertyHalServiceConfigs
                .isWritable(mContext, VehiclePropertyIds.CRUISE_CONTROL_COMMAND)).isFalse();
        assertThat(mPropertyHalServiceConfigs
                .isReadable(mContext, VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS)).isTrue();
        assertThat(mPropertyHalServiceConfigs
                .isWritable(mContext, VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS)).isTrue();
        assertThat(mPropertyHalServiceConfigs
                .isReadable(mContext, VehiclePropertyIds.DISTANCE_DISPLAY_UNITS)).isFalse();
        assertThat(mPropertyHalServiceConfigs
                .isWritable(mContext, VehiclePropertyIds.DISTANCE_DISPLAY_UNITS)).isTrue();

        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_CONTROL_CAR_CLIMATE))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VENDOR_EXTENSION))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertThat(mPropertyHalServiceConfigs
                .isReadable(mContext, VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS)).isFalse();
        assertThat(mPropertyHalServiceConfigs
                .isWritable(mContext, VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS)).isFalse();
        assertThat(mPropertyHalServiceConfigs
                .isReadable(mContext, VehiclePropertyIds.DISTANCE_DISPLAY_UNITS)).isFalse();
        assertThat(mPropertyHalServiceConfigs
                .isWritable(mContext, VehiclePropertyIds.DISTANCE_DISPLAY_UNITS)).isFalse();
    }

    /**
     * Test {@link PropertyHalServiceConfigs#isReadable(Context, int)}
     * and {@link PropertyHalServiceConfigs#isWritable(Context, int)} for vendor properties
     */
    @Test
    public void testIsReadableWritableForVendorProperty() {
        for (int vendorProp : VENDOR_PROPERTY_IDS) {
            assertThat(mPropertyHalServiceConfigs.isReadable(mContext, vendorProp)).isTrue();
            assertThat(mPropertyHalServiceConfigs.isWritable(mContext, vendorProp)).isTrue();
        }

        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VENDOR_EXTENSION))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        for (int vendorProp : VENDOR_PROPERTY_IDS) {
            assertThat(mPropertyHalServiceConfigs.isReadable(mContext, vendorProp)).isFalse();
            assertThat(mPropertyHalServiceConfigs.isWritable(mContext, vendorProp)).isFalse();
        }
    }

    /**
     * Test {@link PropertyHalServiceConfigs#customizeVendorPermission(int[])}
     */
    @Test
    public void testCustomizeVendorPermission() {
        List<Integer> configArray = new ArrayList<>();
        // set up read permission and write permission to VENDOR_PROPERTY_1
        configArray.add(VENDOR_PROPERTY_1);
        configArray.add(VehicleVendorPermission.PERMISSION_DEFAULT);
        configArray.add(VehicleVendorPermission.PERMISSION_NOT_ACCESSIBLE);
        // set up read permission and write permission to VENDOR_PROPERTY_2
        configArray.add(VENDOR_PROPERTY_2);
        configArray.add(VehicleVendorPermission.PERMISSION_GET_VENDOR_CATEGORY_ENGINE);
        configArray.add(VehicleVendorPermission.PERMISSION_SET_VENDOR_CATEGORY_ENGINE);
        // set up read permission and write permission to VENDOR_PROPERTY_3
        configArray.add(VENDOR_PROPERTY_3);
        configArray.add(VehicleVendorPermission.PERMISSION_GET_VENDOR_CATEGORY_INFO);
        configArray.add(VehicleVendorPermission.PERMISSION_DEFAULT);

        mPropertyHalServiceConfigs.customizeVendorPermission(toIntArray(configArray));

        assertThat(mPropertyHalServiceConfigs.getReadPermission(VENDOR_PROPERTY_1)
                .toString()).isEqualTo(Car.PERMISSION_VENDOR_EXTENSION);
        assertThat(mPropertyHalServiceConfigs.getWritePermission(VENDOR_PROPERTY_1)).isNull();

        assertThat(mPropertyHalServiceConfigs.getReadPermission(VENDOR_PROPERTY_2)
                .toString()).isEqualTo(android.car.hardware.property
                .VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_ENGINE);
        assertThat(mPropertyHalServiceConfigs.getWritePermission(VENDOR_PROPERTY_2)
                .toString()).isEqualTo(android.car.hardware.property
                .VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_ENGINE);

        assertThat(mPropertyHalServiceConfigs.getReadPermission(VENDOR_PROPERTY_3)
                .toString()).isEqualTo(android.car.hardware.property
                .VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_INFO);
        assertThat(mPropertyHalServiceConfigs.getWritePermission(VENDOR_PROPERTY_3)
                .toString()).isEqualTo(Car.PERMISSION_VENDOR_EXTENSION);

        assertThat(mPropertyHalServiceConfigs.getReadPermission(VENDOR_PROPERTY_4)
                .toString()).isEqualTo(Car.PERMISSION_VENDOR_EXTENSION);
        assertThat(mPropertyHalServiceConfigs.getWritePermission(VENDOR_PROPERTY_4)
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

        assertThat(mPropertyHalServiceConfigs.isReadable(mContext, VENDOR_PROPERTY_1)).isTrue();
        assertThat(mPropertyHalServiceConfigs.isWritable(mContext, VENDOR_PROPERTY_1)).isFalse();
        assertThat(mPropertyHalServiceConfigs.isReadable(mContext, VENDOR_PROPERTY_2)).isTrue();
        assertThat(mPropertyHalServiceConfigs.isWritable(mContext, VENDOR_PROPERTY_2)).isTrue();
        assertThat(mPropertyHalServiceConfigs.isReadable(mContext, VENDOR_PROPERTY_3)).isFalse();
        assertThat(mPropertyHalServiceConfigs.isWritable(mContext, VENDOR_PROPERTY_3)).isTrue();
        assertThat(mPropertyHalServiceConfigs.isReadable(mContext, VENDOR_PROPERTY_4)).isTrue();
        assertThat(mPropertyHalServiceConfigs.isWritable(mContext, VENDOR_PROPERTY_4)).isTrue();
    }

    @Test
    public void testCustomizeVendorPermission_systemPropertyNotAllowed() {
        List<Integer> configArray = new ArrayList<>();
        configArray.add(VehiclePropertyIds.CURRENT_GEAR);
        configArray.add(
                VehicleVendorPermission.PERMISSION_GET_VENDOR_CATEGORY_ENGINE);
        configArray.add(
                VehicleVendorPermission.PERMISSION_SET_VENDOR_CATEGORY_ENGINE);

        assertThrows(IllegalArgumentException.class, () ->
                mPropertyHalServiceConfigs.customizeVendorPermission(toIntArray(configArray)));
    }

    @Test
    public void testCustomizeVendorPermission_overrideNotAllowed() {
        List<Integer> configArray = new ArrayList<>();
        configArray.add(VENDOR_PROPERTY_1);
        configArray.add(VehicleVendorPermission.PERMISSION_DEFAULT);
        configArray.add(VehicleVendorPermission.PERMISSION_NOT_ACCESSIBLE);
        // This must not take effect.
        configArray.add(VENDOR_PROPERTY_1);
        configArray.add(VehicleVendorPermission.PERMISSION_GET_VENDOR_CATEGORY_ENGINE);
        configArray.add(VehicleVendorPermission.PERMISSION_SET_VENDOR_CATEGORY_ENGINE);

        mPropertyHalServiceConfigs.customizeVendorPermission(toIntArray(configArray));

        assertThat(mPropertyHalServiceConfigs.getReadPermission(VENDOR_PROPERTY_1)
                .toString()).isEqualTo(Car.PERMISSION_VENDOR_EXTENSION);
        assertThat(mPropertyHalServiceConfigs.getWritePermission(VENDOR_PROPERTY_1)).isNull();
    }

    private InputStream strToInputStream(String str) {
        return new ByteArrayInputStream(str.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testParseJsonConfig_validConfig() {
        InputStream is = strToInputStream("""
        {
            'version': 1,
            'properties': {
                'PROP_NAME': {
                    'propertyName': 'PROP_NAME',
                    'propertyId': 1234,
                    'vhalPropertyId': 2345,
                    'description': 'DESCRIPTION',
                    'readPermission': {
                        'type': 'anyOf',
                        'value': [
                            {
                                'type': 'single',
                                'value': 'PERM1'
                            },
                            {
                                'type': 'single',
                                'value': 'PERM2'
                            }
                        ]
                    },
                    'writePermission': {
                        'type': 'allOf',
                        'value': [
                            {
                                'type': 'single',
                                'value': 'PERM1'
                            },
                            {
                                'type': 'single',
                                'value': 'PERM2'
                            }
                        ]
                    },
                    'dataEnums': [0, 1, 2]
                }
            }
        }
        """);
        CarSvcPropertyConfig expectedConfig = new CarSvcPropertyConfig();
        expectedConfig.propertyId = 1234;
        expectedConfig.halPropId = 2345;
        expectedConfig.propertyName = "PROP_NAME";
        expectedConfig.description = "DESCRIPTION";
        expectedConfig.permissions =  new PropertyPermissions.Builder()
                .setReadPermission(new AnyOfPermissions(
                    new SinglePermission("PERM1"),
                    new SinglePermission("PERM2")
                ))
                .setWritePermission(new AllOfPermissions(
                        new SinglePermission("PERM1"),
                        new SinglePermission("PERM2")
                ))
                .build();
        expectedConfig.dataEnums = new ArraySet<>(Set.of(0, 1, 2));

        SparseArray<CarSvcPropertyConfig> configs = mPropertyHalServiceConfigs.parseJsonConfig(
                is, /* path= */ "test");

        assertWithMessage("expect one config parsed").that(configs.size()).isEqualTo(1);
        CarSvcPropertyConfig config = configs.get(2345);
        assertThat(config).isNotNull();
        assertThat(config).isEqualTo(expectedConfig);
    }

    @Test
    public void testParseJsonConfig_halPropIdDefaultEqualPropId() {
        InputStream is = strToInputStream("""
        {
            "version": 1,
            "properties": {
                "PROP_NAME": {
                    "propertyName": "PROP_NAME",
                    "propertyId": 1234,
                    "description": "DESCRIPTION",
                    "readPermission": {
                        "type": "single",
                        "value": "PERM1"
                    }
                }
            }
        }
        """);
        CarSvcPropertyConfig expectedConfig = new CarSvcPropertyConfig();
        expectedConfig.propertyId = 1234;
        expectedConfig.halPropId = 1234;
        expectedConfig.propertyName = "PROP_NAME";
        expectedConfig.description = "DESCRIPTION";
        expectedConfig.permissions =  new PropertyPermissions.Builder()
                .setReadPermission(new SinglePermission("PERM1"))
                .build();

        SparseArray<CarSvcPropertyConfig> configs = mPropertyHalServiceConfigs.parseJsonConfig(
                is, /* path= */ "test");
        CarSvcPropertyConfig config = configs.get(1234);
        assertThat(config).isNotNull();
        assertThat(config).isEqualTo(expectedConfig);
    }

    @Test
    public void testParseJsonConfig_dataFlags() {
        InputStream is = strToInputStream("""
        {
            "version": 1,
            "properties": {
                "PROP_NAME": {
                    "propertyName": "PROP_NAME",
                    "propertyId": 1234,
                    "description": "DESCRIPTION",
                    "readPermission": {
                        "type": "single",
                        "value": "PERM1"
                    },
                    "dataFlags": [1, 2, 4, 8]
                }
            }
        }
        """);
        CarSvcPropertyConfig expectedConfig = new CarSvcPropertyConfig();
        expectedConfig.propertyId = 1234;
        expectedConfig.halPropId = 1234;
        expectedConfig.propertyName = "PROP_NAME";
        expectedConfig.description = "DESCRIPTION";
        expectedConfig.permissions =  new PropertyPermissions.Builder()
                .setReadPermission(new SinglePermission("PERM1"))
                .build();
        // Bit: 1111
        expectedConfig.validBitFlag = 15;

        SparseArray<CarSvcPropertyConfig> configs = mPropertyHalServiceConfigs.parseJsonConfig(
                is, /* path= */ "test");
        CarSvcPropertyConfig config = configs.get(1234);
        assertThat(config).isNotNull();
        assertThat(config).isEqualTo(expectedConfig);
    }

    @Test
    public void testParseJsonConfig_ignoreDeprecate() {
        InputStream is = strToInputStream("""
        {
            "version": 1,
            "properties": {
                "OLD_PROP": {
                    "propertyName": "OLD_PROP",
                    "propertyId": 12345,
                    "deprecated": true
                },
                "PROP_NAME": {
                    "propertyName": "PROP_NAME",
                    "propertyId": 1234,
                    "vhalPropertyId": 2345,
                    "description": "DESCRIPTION",
                    "readPermission": {
                        "type": "single",
                        "value": "PERM1"
                    }
                }
            }
        }
        """);

        SparseArray<CarSvcPropertyConfig> configs = mPropertyHalServiceConfigs.parseJsonConfig(
                is, /* path= */ "test");

        assertWithMessage("deprecated property must be ignored").that(configs.get(12345)).isNull();
        assertWithMessage("must continue parsing non deprecated property").that(configs.get(2345))
                .isNotNull();
    }

    @Test
    public void testParseJsonConfig_cannotReadFromStream() throws Exception {
        InputStream is = mock(InputStream.class);
        when(is.readAllBytes()).thenThrow(new IOException());

        assertThrows(IllegalArgumentException.class, () ->
                mPropertyHalServiceConfigs.parseJsonConfig(is, /* path= */ "test"));
    }

    @Test
    public void testParseJsonConfig_invalidJsonFormat() {
        InputStream is = strToInputStream("{");

        assertThrows(IllegalArgumentException.class, () ->
                mPropertyHalServiceConfigs.parseJsonConfig(is, /* path= */ "test"));
    }

    @Test
    public void testParseJsonConfig_missingPropertyIdField() {
        InputStream is = strToInputStream("""
        {
            "version": 1,
            "properties": {
                "PROP_NAME": {
                    "propertyName": "PROP_NAME",
                    "vhalPropertyId": 2345,
                    "description": "DESCRIPTION",
                    "readPermission": {
                        "type": "single",
                        "value": "PERM1"
                    }
                }
            }
        }
        """);

        assertThrows(IllegalArgumentException.class, () ->
                mPropertyHalServiceConfigs.parseJsonConfig(is, /* path= */ "test"));
    }

    @Test
    public void testParseJsonConfig_noReadOrWritePermission() {
        InputStream is = strToInputStream("""
        {
            "version": 1,
            "properties": {
                "PROP_NAME": {
                    "propertyName": "PROP_NAME",
                    "propertyId": 1234,
                    "description": "DESCRIPTION",
                    "dataFlags": [1, 2, 4, 8]
                }
            }
        }
        """);

        assertThrows(IllegalArgumentException.class, () ->
                mPropertyHalServiceConfigs.parseJsonConfig(is, /* path= */ "test"));
    }

    // TODO(b/293354967): Remove this test once we migrate to runtime config.
    @Test
    public void testCheckPayloadInvalidEnum_noRuntimeConfig() {
        PropertyHalServiceConfigs configsNoRuntime = new PropertyHalServiceConfigs(false);
        SparseArray<Set<Integer>> halPropIdToEnumSet = configsNoRuntime.getHalPropIdToEnumSet();

        for (int i = 0; i < halPropIdToEnumSet.size(); i++) {
            int halPropId = halPropIdToEnumSet.keyAt(i);
            Set<Integer> enums = halPropIdToEnumSet.valueAt(i);
            for (int possibleValue = -20; possibleValue < 20; possibleValue++) {
                HalPropValue halPropValue = PROP_VALUE_BUILDER.build(
                        // Some of the properties are zoned properties, but we don't check areaId in
                        // checkPayload.
                        halPropId, /* areaId= */0,
                        SystemClock.elapsedRealtimeNanos(), /* status= */0, possibleValue);
                boolean isValid = configsNoRuntime.checkPayload(halPropValue);

                expect.withMessage(
                        "checkPayload must fail for invalid enum and pass for valid enum")
                        .that(isValid).isEqualTo(enums.contains(possibleValue));
            }
        }
    }

    // Verifies that all enum checks using runtime config is consistent with not using runtime
    // config. Verifies all valid enums are accepted and invalid enums are rejected.
    // TODO(b/293354967): Remove this test once we migrate to runtime config.
    @Test
    public void testCheckPayloadInvalidEnum_runtimeConfigCompatible() {
        PropertyHalServiceConfigs configsNoRuntime = new PropertyHalServiceConfigs(false);
        PropertyHalServiceConfigs configsRuntime = new PropertyHalServiceConfigs(true);
        SparseArray<Set<Integer>> halPropIdToEnumSet = configsNoRuntime.getHalPropIdToEnumSet();

        for (int i = 0; i < halPropIdToEnumSet.size(); i++) {
            int halPropId = halPropIdToEnumSet.keyAt(i);
            Set<Integer> enums = halPropIdToEnumSet.valueAt(i);
            for (int possibleValue = -20; possibleValue < 20; possibleValue++) {
                HalPropValue halPropValue = PROP_VALUE_BUILDER.build(
                        // Some of the properties are zoned properties, but we don't check areaId in
                        // checkPayload.
                        halPropId, /* areaId= */0,
                        SystemClock.elapsedRealtimeNanos(), /* status= */0, possibleValue);
                boolean isValid = configsRuntime.checkPayload(halPropValue);
                boolean expectedIsValid = enums.contains(possibleValue);

                // Known inconsistency: VehicleAreaSeat.java contains 0 as a valid value but
                // VehicleAreaSeat.aidl does not.
                // TODO(b/293521207): Make VehicleAreaSeat enum consistent.
                if (halPropId == VehicleProperty.INFO_DRIVER_SEAT && possibleValue == 0) {
                    expectedIsValid = true;
                }

                expect.withMessage("checkPayload must fail for invalid enum and pass for valid enum"
                        + ", property: " + VehiclePropertyIds.toString(halPropId)
                        + ", halPropValue: " + halPropValue)
                        .that(isValid).isEqualTo(expectedIsValid);
            }
        }
    }

    private List<Integer> getAllHalPropIds() throws Exception {
        List<Integer> halPropIds = new ArrayList<>();
        for (Field field : VehicleProperty.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers) || !field.getType().equals(int.class)) {
                continue;
            }

            halPropIds.add(field.getInt(/* object= */ null));
        }
        return halPropIds;
    }

    // Verifies that {@link PropertyHalServiceConfigs.getReadPermission} is consistent before
    // and after using runtime config.
    // TODO(b/293354967): Remove this test once we migrate to runtime config.
    @Test
    public void testCheckReadPermission_runtimeConfigCompatible() throws Exception {
        PropertyHalServiceConfigs configsNoRuntime = new PropertyHalServiceConfigs(false);
        PropertyHalServiceConfigs configsRuntime = new PropertyHalServiceConfigs(true);
        List<Integer> allHalPropIds = getAllHalPropIds();
        for (int i = 0; i < allHalPropIds.size(); i++) {
            int halPropId = allHalPropIds.get(i);

            expect.withMessage("got expected read permission for: "
                    + VehiclePropertyIds.toString(halPropId))
                    .that(configsNoRuntime.getReadPermission(halPropId))
                    .isEqualTo(configsRuntime.getReadPermission(halPropId));
        }
    }

    // Verifies that {@link PropertyHalServiceConfigs.getWritePermission} is consistent before
    // and after using runtime config.
    // TODO(b/293354967): Remove this test once we migrate to runtime config.
    @Test
    public void testCheckWritePermission_runtimeConfigCompatible() throws Exception {
        PropertyHalServiceConfigs configsNoRuntime = new PropertyHalServiceConfigs(false);
        PropertyHalServiceConfigs configsRuntime = new PropertyHalServiceConfigs(true);
        List<Integer> allHalPropIds = getAllHalPropIds();
        for (int i = 0; i < allHalPropIds.size(); i++) {
            int halPropId = allHalPropIds.get(i);

            expect.withMessage("got expected write permission for: "
                    + VehiclePropertyIds.toString(halPropId))
                    .that(configsNoRuntime.getWritePermission(halPropId))
                    .isEqualTo(configsRuntime.getWritePermission(halPropId));
        }
    }

    // Verifies that {@link PropertyHalServiceConfigs.getAllPossibleSupportedEnumValues} is
    // consistent before and after using runtime config.
    // TODO(b/293354967): Remove this test once we migrate to runtime config.
    @Test
    public void testGetAllPossibleSupportedEnumValues_runtimeConfigCompatible() throws Exception {
        PropertyHalServiceConfigs configsNoRuntime = new PropertyHalServiceConfigs(false);
        PropertyHalServiceConfigs configsRuntime = new PropertyHalServiceConfigs(true);
        List<Integer> allHalPropIds = getAllHalPropIds();
        for (int i = 0; i < allHalPropIds.size(); i++) {
            int halPropId = allHalPropIds.get(i);

            if (halPropId == VehicleProperty.INFO_DRIVER_SEAT) {
                // TODO(b/293521207): Make VehicleAreaSeat enum consistent.
                continue;
            }
            if (halPropId == VehicleProperty.INFO_EV_PORT_LOCATION ||
                    halPropId == VehicleProperty.SEAT_FOOTWELL_LIGHTS_STATE ||
                    halPropId == VehicleProperty.SEAT_FOOTWELL_LIGHTS_SWITCH) {
                // TODO(b/292130544): Known missing data enum definitions.
                continue;
            }

            Set<Integer> currentSupportedEnumValues =
                    configsNoRuntime.getAllPossibleSupportedEnumValues(halPropId);
            Set<Integer> expectedEnumValues = currentSupportedEnumValues == null ? null :
                    new ArraySet<Integer>(currentSupportedEnumValues);
            if (halPropId == VehicleProperty.HVAC_TEMPERATURE_DISPLAY_UNITS ||
                    halPropId == VehicleProperty.DISTANCE_DISPLAY_UNITS ||
                    halPropId == VehicleProperty.FUEL_VOLUME_DISPLAY_UNITS ||
                    halPropId == VehicleProperty.TIRE_PRESSURE_DISPLAY_UNITS ||
                    halPropId == VehicleProperty.EV_BATTERY_DISPLAY_UNITS ||
                    halPropId == VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS ||
                    halPropId == VehicleProperty.HVAC_TEMPERATURE_DISPLAY_UNITS) {
                // TODO(b/293943088): AMPERE should not be listed in supported enums.
                expectedEnumValues.remove(VehicleUnit.AMPERE);
            }

            expect.withMessage("got expected possible supported enum values for: "
                    + VehiclePropertyIds.toString(halPropId))
                    .that(expectedEnumValues)
                    .isEqualTo(configsRuntime.getAllPossibleSupportedEnumValues(halPropId));
        }
    }

    @Test
    public void testManagerToHalPropId() {
        assertThat(mPropertyHalServiceConfigs.managerToHalPropId(
                VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS)).isEqualTo(
                VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS);
    }

    @Test
    public void testHalToManagerPropId() {
        assertThat(mPropertyHalServiceConfigs.halToManagerPropId(
                VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS)).isEqualTo(
                VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS);
    }
}
