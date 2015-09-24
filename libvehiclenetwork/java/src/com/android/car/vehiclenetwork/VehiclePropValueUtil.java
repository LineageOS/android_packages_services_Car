/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.car.vehiclenetwork;

import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehicleNetworkProto.ZonedValue;
import com.google.protobuf.ByteString;

/**
 * Utility class to help creating VehiclePropValue.
 */
public class VehiclePropValueUtil {

    public static VehiclePropValue createIntValue(int property, int value, long timestamp) {
        return createBuilder(property, VehicleValueType.VEHICLE_VALUE_TYPE_INT32,timestamp).
                addInt32Values(value).
                build();
    }

    public static VehiclePropValue createIntVectorValue(int property, int[] values,
            long timestamp) {
        int len = values.length;
        VehiclePropValue.Builder builder =createBuilder(property,
                VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC2 + len - 2,
                timestamp);
        for (int v : values) {
            builder.addInt32Values(v);
        }
        return builder.build();
    }

    public static VehiclePropValue createFloatValue(int property, float value, long timestamp) {
        return createBuilder(property, VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT,timestamp).
                addFloatValues(value).
                build();
    }

    public static VehiclePropValue createFloatVectorValue(int property, float[] values,
            long timestamp) {
        int len = values.length;
        VehiclePropValue.Builder builder =createBuilder(property,
                VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT_VEC2 + len - 2,
                timestamp);
        for (float v : values) {
            builder.addFloatValues(v);
        }
        return builder.build();
    }

    public static VehiclePropValue createLongValue(int property, long value, long timestamp) {
        return createBuilder(property, VehicleValueType.VEHICLE_VALUE_TYPE_INT64,timestamp).
                setInt64Value(value).
                build();
    }

    public static VehiclePropValue createStringValue(int property, String value, long timestamp) {
        return createBuilder(property, VehicleValueType.VEHICLE_VALUE_TYPE_STRING,timestamp).
                setStringValue(value).
                build();
    }

    public static VehiclePropValue createBooleanValue(int property, boolean value, long timestamp) {
        return createBuilder(property, VehicleValueType.VEHICLE_VALUE_TYPE_BOOLEAN,timestamp).
                addInt32Values(value ? 1 : 0).
                build();
    }

    public static VehiclePropValue createBytesValue(int property, byte[] value, long timestamp) {
        return createBuilder(property, VehicleValueType.VEHICLE_VALUE_TYPE_BYTES,timestamp).
                setBytesValue(ByteString.copyFrom(value)).
                build();
    }

    public static VehiclePropValue createZonedIntValue(int property, int zone, int value,
            long timestamp) {
        return createBuilder(property, VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_INT32,timestamp).
                setZonedValue(ZonedValue.newBuilder().setZoneOrWindow(zone).setInt32Value(value).
                        build()).
                build();
    }

    public static VehiclePropValue createZonedBooleanValue(int property, int zone, boolean value,
            long timestamp) {
        return createBuilder(property, VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_BOOLEAN,timestamp).
                setZonedValue(ZonedValue.newBuilder().setZoneOrWindow(zone).
                        setInt32Value(value ? 1 : 0).build()).
                build();
    }

    public static VehiclePropValue createZonedFloatValue(int property, int zone, float value,
            long timestamp) {
        return createBuilder(property, VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_FLOAT,timestamp).
                setZonedValue(ZonedValue.newBuilder().setZoneOrWindow(zone).setFloatValue(value).
                        build()).
                build();
    }

    public static VehiclePropValue createDummyValue(int property, int valueType) {
        switch (valueType) {
            case VehicleValueType.VEHICLE_VALUE_TYPE_STRING: {
                return createStringValue(property, "dummy", 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_BYTES: {
                return createBytesValue(property, new byte[1], 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_BOOLEAN: {
                return createBooleanValue(property, false, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_INT32: {
                return createZonedIntValue(property, 0, 0, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_FLOAT: {
                return createZonedFloatValue(property, 0, 0, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_BOOLEAN: {
                return createZonedBooleanValue(property, 0, false, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_INT64: {
                return createLongValue(property, 0, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT: {
                return createFloatValue(property, 0, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT_VEC2: {
                return createFloatVectorValue(property, new float[] {0, 0}, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT_VEC3: {
                return createFloatVectorValue(property, new float[] {0, 0, 0}, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT_VEC4: {
                return createFloatVectorValue(property, new float[] {0, 0, 0, 0}, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_INT32: {
                return createIntValue(property, 0, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC2: {
                return createIntVectorValue(property, new int[] {0, 0}, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC3: {
                return createIntVectorValue(property, new int[] {0, 0, 0}, 0);
            }
            case VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC4: {
                return createIntVectorValue(property, new int[] {0, 0, 0, 0}, 0);
            }
        }
        return null;
    }

    public static VehiclePropValue.Builder createBuilder(int property, int valueType,
            long timestamp) {
        return VehiclePropValue.newBuilder().
                setProp(property).
                setValueType(valueType).
                setTimestamp(timestamp);
    }
}
