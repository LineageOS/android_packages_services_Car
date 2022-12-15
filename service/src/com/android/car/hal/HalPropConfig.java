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

import android.car.VehicleAreaType;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.property.AreaIdConfig;
import android.hardware.automotive.vehicle.VehicleArea;
import android.hardware.automotive.vehicle.VehiclePropertyType;

import java.util.ArrayList;
import java.util.List;

/**
 * HalPropConfig represents a vehicle property config.
 */
public abstract class HalPropConfig {
    private static final AreaIdConfig GLOBAL_AREA_ID_CONFIG =
            new AreaIdConfig.Builder(/*areaId=*/0).build();

    /**
     * Get the property ID.
     */
    public abstract int getPropId();

    /**
     * Get the access mode.
     */
    public abstract int getAccess();

    /**
     * Get the change mode.
     */
    public abstract int getChangeMode();

    /**
     * Get the area configs.
     */
    public abstract HalAreaConfig[] getAreaConfigs();

    /**
     * Get the config array.
     */
    public abstract int[] getConfigArray();

    /**
     * Get the config string.
     */
    public abstract String getConfigString();

    /**
     * Get the min sample rate.
     */
    public abstract float getMinSampleRate();

    /**
     * Get the max sample rate.
     */
    public abstract float getMaxSampleRate();

    /**
     * Converts to AIDL or HIDL VehiclePropConfig.
     */
    public abstract Object toVehiclePropConfig();

    /**
     * Converts {@link HalPropConfig} to {@link CarPropertyConfig}.
     *
     * @param mgrPropertyId The Property ID used by Car Property Manager, different from the
     *                      property ID used by VHAL.
     */
    public CarPropertyConfig<?> toCarPropertyConfig(int mgrPropertyId) {
        int propId = getPropId();
        int areaType = getVehicleAreaType(propId & VehicleArea.MASK);
        Class<?> clazz = CarPropertyUtils.getJavaClass(propId & VehiclePropertyType.MASK);
        CarPropertyConfig.Builder carPropertyConfigBuilder = CarPropertyConfig.newBuilder(clazz,
                mgrPropertyId, areaType).setAccess(getAccess()).setChangeMode(
                getChangeMode()).setConfigString(getConfigString());

        float maxSampleRate = 0f;
        float minSampleRate = 0f;
        if (getChangeMode() == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
            maxSampleRate = getMaxSampleRate();
            minSampleRate = getMinSampleRate();
        }
        carPropertyConfigBuilder.setMinSampleRate(minSampleRate).setMaxSampleRate(maxSampleRate);

        int[] configIntArray = getConfigArray();
        ArrayList<Integer> configArray = new ArrayList<>(configIntArray.length);
        for (int i = 0; i < configIntArray.length; i++) {
            configArray.add(configIntArray[i]);
        }
        carPropertyConfigBuilder.setConfigArray(configArray);

        HalAreaConfig[] halAreaConfigs = getAreaConfigs();
        if (halAreaConfigs.length == 0) {
            carPropertyConfigBuilder.addAreaIdConfig(GLOBAL_AREA_ID_CONFIG);
        } else {
            for (HalAreaConfig halAreaConfig : halAreaConfigs) {
                int areaId = halAreaConfig.getAreaId();
                AreaIdConfig.Builder areaIdConfigBuilder = new AreaIdConfig.Builder(areaId);
                if (classMatched(Integer.class, clazz)) {
                    if ((halAreaConfig.getMinInt32Value() != 0
                            || halAreaConfig.getMaxInt32Value() != 0)) {
                        areaIdConfigBuilder.setMinValue(
                                halAreaConfig.getMinInt32Value()).setMaxValue(
                                halAreaConfig.getMaxInt32Value());
                    }
                    long[] supportedEnumValuesArray = halAreaConfig.getSupportedEnumValues();
                    if (supportedEnumValuesArray != null && supportedEnumValuesArray.length > 0) {
                        List<Integer> supportedEnumValues = new ArrayList<>(
                                supportedEnumValuesArray.length);
                        for (int i = 0; i < supportedEnumValuesArray.length; i++) {
                            supportedEnumValues.add((int) supportedEnumValuesArray[i]);
                        }
                        areaIdConfigBuilder.setSupportedEnumValues(supportedEnumValues);
                    }
                } else if (classMatched(Float.class, clazz) && (
                        halAreaConfig.getMinFloatValue() != 0
                                || halAreaConfig.getMaxFloatValue() != 0)) {
                    areaIdConfigBuilder.setMinValue(halAreaConfig.getMinFloatValue()).setMaxValue(
                            halAreaConfig.getMaxFloatValue());
                } else if (classMatched(Long.class, clazz) && (halAreaConfig.getMinInt64Value() != 0
                        || halAreaConfig.getMaxInt64Value() != 0)) {
                    areaIdConfigBuilder.setMinValue(halAreaConfig.getMinInt64Value()).setMaxValue(
                            halAreaConfig.getMaxInt64Value());
                }
                carPropertyConfigBuilder.addAreaIdConfig(areaIdConfigBuilder.build());
            }
        }
        return carPropertyConfigBuilder.build();
    }

    private static @VehicleAreaType.VehicleAreaTypeValue int getVehicleAreaType(int halArea) {
        switch (halArea) {
            case VehicleArea.GLOBAL:
                return VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL;
            case VehicleArea.SEAT:
                return VehicleAreaType.VEHICLE_AREA_TYPE_SEAT;
            case VehicleArea.DOOR:
                return VehicleAreaType.VEHICLE_AREA_TYPE_DOOR;
            case VehicleArea.WINDOW:
                return VehicleAreaType.VEHICLE_AREA_TYPE_WINDOW;
            case VehicleArea.MIRROR:
                return VehicleAreaType.VEHICLE_AREA_TYPE_MIRROR;
            case VehicleArea.WHEEL:
                return VehicleAreaType.VEHICLE_AREA_TYPE_WHEEL;
            default:
                throw new RuntimeException("Unsupported area type " + halArea);
        }
    }

    private static boolean classMatched(Class<?> class1, Class<?> class2) {
        return class1 == class2 || class1.getComponentType() == class2;
    }

}
