/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.car.hardware;

import static android.car.hardware.CarPropertyValue.ALL_CAR_PROPERTY_STATUS;
import static android.car.feature.Flags.FLAG_CAR_PROPERTY_SIMULATION;
import static android.car.feature.Flags.FLAG_CAR_PROPERTY_VALUE_PROPERTY_STATUS;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.car.VehicleAreaType;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.car.internal.property.PropertyStatusUtils;

import org.junit.Rule;
import org.junit.Test;

/**
 * Unit tests for {@link CarPropertyValue}
 */
public final class CarPropertyValueTest extends CarPropertyTestBase {
    private static final int PROPERTY_ID = 1234;
    private static final int AREA_ID = 5678;
    private static final long TIMESTAMP_NANOS = 9294;
    private static final Float VALUE = 12.0F;
    private static final int VENDOR_STATUS = 0xDEAD;
    private static final int SYSTEM_STATUS = CarPropertyValue.STATUS_NOT_AVAILABLE_DISABLED;
    private static final CarPropertyValue CAR_PROPERTY_VALUE = getDefaultTestCarPropertyValue();

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static CarPropertyValue.Builder<Float> getDefaultTestCarPropertyValueBuilder() {
        return new CarPropertyValue.Builder<Float>(PROPERTY_ID, AREA_ID)
                .setSystemStatus(SYSTEM_STATUS)
                .setTimestampNanos(TIMESTAMP_NANOS)
                .setVendorStatus(VENDOR_STATUS)
                .setIsSimulationPropId(true)
                .setValue(VALUE);
    }

    private static CarPropertyValue getDefaultTestCarPropertyValue() {
        return getDefaultTestCarPropertyValueBuilder().build();
    }

    @Test
    public void testSimpleFloatValue() {
        CarPropertyValue<Float> floatValue =
                new CarPropertyValue<>(FLOAT_PROPERTY_ID, WINDOW_DRIVER, 10f);

        writeToParcel(floatValue);

        CarPropertyValue<Float> valueRead = readFromParcel();
        assertThat(valueRead.getValue()).isEqualTo((Object) 10f);
    }

    @Test
    public void testMixedValue() {
        CarPropertyValue<Object> mixedValue =
                new CarPropertyValue<>(MIXED_TYPE_PROPERTY_ID,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                        new Object[] { "android", 1, 2.0 });
        writeToParcel(mixedValue);
        CarPropertyValue<Object[]> valueRead = readFromParcel();
        assertThat(valueRead.getValue()).asList().containsExactly("android", 1, 2.0).inOrder();
        assertThat(valueRead.getPropertyId()).isEqualTo(MIXED_TYPE_PROPERTY_ID);
        assertThat(valueRead.getAreaId()).isEqualTo(VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL);
    }

    @Test
    public void hashCode_returnsSameValueForSameInstance() {
        assertThat(CAR_PROPERTY_VALUE.hashCode()).isEqualTo(CAR_PROPERTY_VALUE.hashCode());
    }

    @Test
    public void hashCode_returnsDifferentValueForDifferentPropertyId() {
        assertThat(CAR_PROPERTY_VALUE.hashCode()).isNotEqualTo(
               new CarPropertyValue.Builder<Float>(1345, AREA_ID)
                        .setSystemStatus(SYSTEM_STATUS)
                        .setTimestampNanos(TIMESTAMP_NANOS)
                        .setVendorStatus(VENDOR_STATUS)
                        .setIsSimulationPropId(true)
                        .setValue(VALUE)
                        .setTimestampNanos(1)
                        .build()
                        .hashCode());
    }

    @Test
    public void hashCode_returnsDifferentValueForDifferentAreaId() {
        assertThat(CAR_PROPERTY_VALUE.hashCode()).isNotEqualTo(
               new CarPropertyValue.Builder<Float>(PROPERTY_ID, 1345)
                        .setSystemStatus(SYSTEM_STATUS)
                        .setTimestampNanos(TIMESTAMP_NANOS)
                        .setVendorStatus(VENDOR_STATUS)
                        .setIsSimulationPropId(true)
                        .setValue(VALUE)
                        .setTimestampNanos(1)
                        .build()
                        .hashCode());
    }

    @Test
    public void hashCode_returnsDifferentValueForDifferentCarPropertyValue() {
        assertThat(CAR_PROPERTY_VALUE.hashCode()).isNotEqualTo(
                getDefaultTestCarPropertyValueBuilder().setValue(1.23F).build().hashCode());
    }

    @Test
    public void hashCode_returnsDifferentValueForDifferentTimestamp() {
        assertThat(CAR_PROPERTY_VALUE.hashCode()).isNotEqualTo(
                getDefaultTestCarPropertyValueBuilder().setTimestampNanos(1).build().hashCode());
    }

    @Test
    public void hashCode_returnsDifferentValueForDifferentSystemStatus() {
        assertThat(CAR_PROPERTY_VALUE.hashCode()).isNotEqualTo(
                getDefaultTestCarPropertyValueBuilder().setSystemStatus(1).build().hashCode());
    }

    @Test
    public void hashCode_returnsDifferentValueForDifferentVendorStatus() {
        assertThat(CAR_PROPERTY_VALUE.hashCode()).isNotEqualTo(
                getDefaultTestCarPropertyValueBuilder().setVendorStatus(1).build().hashCode());
    }

    @Test
    public void hashCode_returnsDifferentValueForDifferentSimulationPropId() {
        assertThat(CAR_PROPERTY_VALUE.hashCode()).isNotEqualTo(
                getDefaultTestCarPropertyValueBuilder().setIsSimulationPropId(false).build()
                .hashCode());
    }

    @Test
    public void equals_returnsTrueForSameInstance() {
        assertThat(CAR_PROPERTY_VALUE.equals(CAR_PROPERTY_VALUE)).isTrue();
    }

    @Test
    public void equals_returnsFalseForNull() {
        assertThat(CAR_PROPERTY_VALUE.equals(null)).isFalse();
    }

    @Test
    public void equals_returnsFalseForNonCarPropertyValue() {
        assertThat(CAR_PROPERTY_VALUE.equals(new Object())).isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentPropertyIds() {
        int differentPropertyId = 4444;

        assertThat(CAR_PROPERTY_VALUE.equals(
                new CarPropertyValue.Builder<Float>(differentPropertyId, AREA_ID)
                        .setSystemStatus(SYSTEM_STATUS)
                        .setTimestampNanos(TIMESTAMP_NANOS)
                        .setVendorStatus(VENDOR_STATUS)
                        .setIsSimulationPropId(true)
                        .setValue(VALUE)
                        .setTimestampNanos(1)
                        .build()))
                .isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentAreaIds() {
        int differentAreaId = 222;

        assertThat(CAR_PROPERTY_VALUE.equals(
                new CarPropertyValue.Builder<Float>(PROPERTY_ID, differentAreaId)
                        .setSystemStatus(SYSTEM_STATUS)
                        .setTimestampNanos(TIMESTAMP_NANOS)
                        .setVendorStatus(VENDOR_STATUS)
                        .setIsSimulationPropId(true)
                        .setValue(VALUE)
                        .setTimestampNanos(1)
                        .build()))
                .isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentSystemStatus() {
        int differentStatus = CarPropertyValue.STATUS_ERROR;

        assertThat(CAR_PROPERTY_VALUE.equals(
                getDefaultTestCarPropertyValueBuilder().setSystemStatus(differentStatus).build()
            )
        ).isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentVendorStatus() {
        int differentStatus = CarPropertyValue.STATUS_ERROR;

        assertThat(CAR_PROPERTY_VALUE.equals(
                getDefaultTestCarPropertyValueBuilder().setVendorStatus(differentStatus).build()
            )
        ).isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentTimestamps() {
        long differentTimestampNanos = 76845;

        assertThat(CAR_PROPERTY_VALUE.equals(
                getDefaultTestCarPropertyValueBuilder().setTimestampNanos(differentTimestampNanos)
                        .build()
            )
        ).isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentValues() {
        Float differentValue = 1.2f;

        assertThat(CAR_PROPERTY_VALUE.equals(
                getDefaultTestCarPropertyValueBuilder().setValue(differentValue).build()
            )
        ).isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentIsSimulationProp() {
        assertThat(CAR_PROPERTY_VALUE.equals(
                getDefaultTestCarPropertyValueBuilder().setIsSimulationPropId(false).build()
            )
        ).isFalse();
    }

    @Test
    public void equals_returnsTrueWhenEqual() {
        assertThat(CAR_PROPERTY_VALUE.equals(getDefaultTestCarPropertyValueBuilder().build()))
                .isTrue();
    }

    @Test
    public void equals_mixedValue() {
        assertThat(
                new CarPropertyValue<Object[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Object[]{"abcd", 1, false})
                .equals(new CarPropertyValue<Object[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Object[]{"abcd", 1, false}
                ))).isTrue();

        assertThat(
                new CarPropertyValue<Object[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Object[]{"abcd", 1, false})
                .equals(new CarPropertyValue<Object[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Object[]{"a", 1, false}
                ))).isFalse();
    }

    @Test
    public void equals_intArray() {
        assertThat(
                new CarPropertyValue<Integer[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Integer[]{1, 2})
                .equals(new CarPropertyValue<Integer[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Integer[]{1, 2}
                ))).isTrue();

        assertThat(
                new CarPropertyValue<Integer[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Integer[]{1, 2})
                .equals(new CarPropertyValue<Integer[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Integer[]{1, 2, 3}
                ))).isFalse();
    }

    @Test
    public void hashCode_mixedValue() {
        assertThat(
                new CarPropertyValue<Object[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Object[]{"abcd", 1, false}).hashCode())
                .isEqualTo(new CarPropertyValue<Object[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Object[]{"abcd", 1, false}).hashCode());

        assertThat(
                new CarPropertyValue<Object[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Object[]{"abcd", 1, false}).hashCode())
                .isNotEqualTo(new CarPropertyValue<Object[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Object[]{"abc", 1, false}).hashCode());
    }

    @Test
    public void hashCode_intArray() {
        assertThat(
                new CarPropertyValue<Integer[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Integer[]{1, 2}).hashCode())
                .isEqualTo(new CarPropertyValue<Integer[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Integer[]{1, 2}).hashCode());

        assertThat(
                new CarPropertyValue<Integer[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Integer[]{1, 2}).hashCode())
                .isNotEqualTo(new CarPropertyValue<Integer[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Integer[]{1, 2, 3}).hashCode());
    }

    @Test
    public void toString_mixedValue_containsMeaningfulValue() {
        String stringRepr = new CarPropertyValue<Object[]>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS,
                        new Object[]{"abcd", 1, false}).toString();

        expectThat(stringRepr.contains("abcd"));
        expectThat(stringRepr.contains("1"));
        expectThat(stringRepr.contains("false"));
    }

    @Test
    public void getStatus_returnsAvailable() {
        assertThat(new CarPropertyValue(PROPERTY_ID, AREA_ID, VALUE).getStatus()).isEqualTo(
                CarPropertyValue.STATUS_AVAILABLE);
    }

    @Test
    public void getStatus_returnsError() {
        assertThat(new CarPropertyValue<>(PROPERTY_ID, AREA_ID, CarPropertyValue.STATUS_ERROR,
                TIMESTAMP_NANOS, VALUE).getStatus()).isEqualTo(CarPropertyValue.STATUS_ERROR);
    }

    @Test
    public void nullValThrowException() {
        assertThrows(NullPointerException.class, () -> new CarPropertyValue<Integer>(PROPERTY_ID,
                AREA_ID, CarPropertyValue.STATUS_AVAILABLE, TIMESTAMP_NANOS, (Integer) null));
    }

    @EnableFlags({FLAG_CAR_PROPERTY_SIMULATION, FLAG_CAR_PROPERTY_VALUE_PROPERTY_STATUS})
    @Test
    public void basicBuilderTest() {
        var carPropertyValue = getDefaultTestCarPropertyValue();
        carPropertyValue.setHasPermissionToReadPropertyVendorStatus();

        assertThat(carPropertyValue.getPropertyId()).isEqualTo(PROPERTY_ID);
        assertThat(carPropertyValue.getAreaId()).isEqualTo(AREA_ID);
        assertThat(carPropertyValue.getPropertyStatus()).isEqualTo(SYSTEM_STATUS);
        assertThat(carPropertyValue.getTimestamp()).isEqualTo(TIMESTAMP_NANOS);
        assertThat(carPropertyValue.getPropertyVendorStatus()).isEqualTo(VENDOR_STATUS);
        assertThat(carPropertyValue.isPropertyIdSimulationPropId()).isTrue();
        assertThat(carPropertyValue.getValue()).isEqualTo(VALUE);
    }

    @EnableFlags({FLAG_CAR_PROPERTY_SIMULATION, FLAG_CAR_PROPERTY_VALUE_PROPERTY_STATUS})
    @Test
    public void basicBuilder_writeToParcel_readFromParcel() {
        var carPropertyValue = getDefaultTestCarPropertyValue();
        carPropertyValue.setHasPermissionToReadPropertyVendorStatus();

        writeToParcel(carPropertyValue);

        CarPropertyValue<Float> gotCarPropertyValue = readFromParcel();
        gotCarPropertyValue.setHasPermissionToReadPropertyVendorStatus();

        assertThat(gotCarPropertyValue.getPropertyId()).isEqualTo(PROPERTY_ID);
        assertThat(gotCarPropertyValue.getAreaId()).isEqualTo(AREA_ID);
        assertThat(gotCarPropertyValue.getPropertyStatus()).isEqualTo(SYSTEM_STATUS);
        assertThat(gotCarPropertyValue.getTimestamp()).isEqualTo(TIMESTAMP_NANOS);
        assertThat(gotCarPropertyValue.getPropertyVendorStatus()).isEqualTo(VENDOR_STATUS);
        assertThat(gotCarPropertyValue.isPropertyIdSimulationPropId()).isTrue();
        assertThat(gotCarPropertyValue.getValue()).isEqualTo(VALUE);
    }

    @Test
    public void builder_builtTwice_throwsException() {
        var builder = new CarPropertyValue.Builder<Float>(PROPERTY_ID, AREA_ID).setValue(VALUE);
        builder.build();

        assertThrows(IllegalStateException.class, () -> {
            builder.build();
        });
    }

    @Test
    public void builder_setValueAsNull_throwsException() {
        var builder = new CarPropertyValue.Builder<Float>(PROPERTY_ID, AREA_ID);

        assertThrows(NullPointerException.class, () -> {
            builder.setValue(null);
        });
    }

    @Test
    public void builder_noValueSet_build_throwsException() {
        var builder = new CarPropertyValue.Builder<Float>(PROPERTY_ID, AREA_ID);

        assertThrows(IllegalStateException.class, () -> {
            builder.build();
        });
    }

    @Test
    public void cloneWithVendorStatusFiltered() {
        var carPropertyValue = getDefaultTestCarPropertyValueBuilder().build();
        carPropertyValue.setHasPermissionToReadPropertyVendorStatus();

        assertThat(carPropertyValue.getPropertyVendorStatus()).isNotEqualTo(0);

        carPropertyValue = carPropertyValue.cloneWithVendorStatusFiltered();

        assertThat(carPropertyValue.getPropertyVendorStatus()).isEqualTo(0);
    }

    @Test
    public void carPropertyStatus_IsOneOf_Error_NotAvailable_Available() {
        for (int carPropertyStatus : ALL_CAR_PROPERTY_STATUS) {
            if (PropertyStatusUtils.isPropertyStatusError(carPropertyStatus)) {
                continue;
            }
            if (PropertyStatusUtils.isPropertyStatusNotAvailable(carPropertyStatus)) {
                continue;
            }
            if (PropertyStatusUtils.isPropertyStatusAvailable(carPropertyStatus)) {
                continue;
            }
            expectWithMessage("CarPropertyStatus: " + carPropertyStatus + " must be one of: "
                    + "available, not_available (detailed), error").that(false).isTrue();
        }
    }
}
