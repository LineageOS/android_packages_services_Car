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

import static android.Manifest.permission.ACCESS_FINE_LOCATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.builtin.util.Slogf;
import android.car.hardware.property.VehicleVendorPermission;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.internal.property.CarPropertyHelper;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class stores all the permission information for each vehicle property. Permission
 * information for properties is available in further detail in {@link
 * android.car.VehiclePropertyIds}.
 */
public class PropertyPermissionInfo {
    private static final String TAG = CarLog.tagFor(PropertyPermissionInfo.class);

    // Vendor permission enums
    // default vendor permission
    private static final int PERMISSION_CAR_VENDOR_DEFAULT = 0x00000000;

    // permissions for the property related with window
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_WINDOW = 0X00000001;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_WINDOW = 0x00000002;
    // permissions for the property related with door
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_DOOR = 0x00000003;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_DOOR = 0x00000004;
    // permissions for the property related with seat
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_SEAT = 0x00000005;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_SEAT = 0x00000006;
    // permissions for the property related with mirror
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_MIRROR = 0x00000007;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_MIRROR = 0x00000008;

    // permissions for the property related with car's information
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_INFO = 0x00000009;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_INFO = 0x0000000A;
    // permissions for the property related with car's engine
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_ENGINE = 0x0000000B;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_ENGINE = 0x0000000C;
    // permissions for the property related with car's HVAC
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_HVAC = 0x0000000D;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_HVAC = 0x0000000E;
    // permissions for the property related with car's light
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_LIGHT = 0x0000000F;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_LIGHT = 0x00000010;

    // permissions reserved for other vendor permission
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_1 = 0x00010000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_1 = 0x00011000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_2 = 0x00020000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_2 = 0x00021000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_3 = 0x00030000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_3 = 0x00031000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_4 = 0x00040000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_4 = 0x00041000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_5 = 0x00050000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_5 = 0x00051000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_6 = 0x00060000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_6 = 0x00061000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_7 = 0x00070000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_7 = 0x00071000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_8 = 0x00080000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_8 = 0x00081000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_9 = 0x00090000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_9 = 0x00091000;
    private static final int PERMISSION_SET_CAR_VENDOR_CATEGORY_10 = 0x000A0000;
    private static final int PERMISSION_GET_CAR_VENDOR_CATEGORY_10 = 0x000A1000;
    // Not available for android
    private static final int PERMISSION_CAR_VENDOR_NOT_ACCESSIBLE = 0xF0000000;

    // Create SinglePermission objects for each permission
    private static final SinglePermission PERMISSION_CONTROL_CAR_DOORS =
            new SinglePermission(Car.PERMISSION_CONTROL_CAR_DOORS);
    private static final SinglePermission PERMISSION_CONTROL_CAR_MIRRORS =
            new SinglePermission(Car.PERMISSION_CONTROL_CAR_MIRRORS);
    private static final SinglePermission PERMISSION_CONTROL_CAR_SEATS =
            new SinglePermission(Car.PERMISSION_CONTROL_CAR_SEATS);
    private static final SinglePermission PERMISSION_CONTROL_CAR_WINDOWS =
            new SinglePermission(Car.PERMISSION_CONTROL_CAR_WINDOWS);
    private static final SinglePermission PERMISSION_CONTROL_GLOVE_BOX =
            new SinglePermission(Car.PERMISSION_CONTROL_GLOVE_BOX);
    private static final SinglePermission PERMISSION_READ_INTERIOR_LIGHTS =
            new SinglePermission(Car.PERMISSION_READ_INTERIOR_LIGHTS);
    private static final SinglePermission PERMISSION_CONTROL_INTERIOR_LIGHTS =
            new SinglePermission(Car.PERMISSION_CONTROL_INTERIOR_LIGHTS);
    private static final SinglePermission PERMISSION_EXTERIOR_LIGHTS =
            new SinglePermission(Car.PERMISSION_EXTERIOR_LIGHTS);
    private static final SinglePermission PERMISSION_CONTROL_EXTERIOR_LIGHTS =
            new SinglePermission(Car.PERMISSION_CONTROL_EXTERIOR_LIGHTS);
    private static final SinglePermission PERMISSION_CONTROL_CAR_AIRBAGS =
            new SinglePermission(Car.PERMISSION_CONTROL_CAR_AIRBAGS);
    private static final SinglePermission PERMISSION_READ_WINDSHIELD_WIPERS =
            new SinglePermission(Car.PERMISSION_READ_WINDSHIELD_WIPERS);
    private static final SinglePermission PERMISSION_CONTROL_WINDSHIELD_WIPERS =
            new SinglePermission(Car.PERMISSION_CONTROL_WINDSHIELD_WIPERS);
    private static final SinglePermission PERMISSION_READ_STEERING_STATE =
            new SinglePermission(Car.PERMISSION_READ_STEERING_STATE);
    private static final SinglePermission PERMISSION_CONTROL_STEERING_WHEEL =
            new SinglePermission(Car.PERMISSION_CONTROL_STEERING_WHEEL);
    private static final SinglePermission PERMISSION_CONTROL_CAR_CLIMATE =
            new SinglePermission(Car.PERMISSION_CONTROL_CAR_CLIMATE);
    private static final SinglePermission PERMISSION_READ_DISPLAY_UNITS =
            new SinglePermission(Car.PERMISSION_READ_DISPLAY_UNITS);
    private static final SinglePermission PERMISSION_CONTROL_DISPLAY_UNITS =
            new SinglePermission(Car.PERMISSION_CONTROL_DISPLAY_UNITS);
    private static final SinglePermission PERMISSION_IDENTIFICATION =
            new SinglePermission(Car.PERMISSION_IDENTIFICATION);
    private static final SinglePermission PERMISSION_CAR_INFO =
            new SinglePermission(Car.PERMISSION_CAR_INFO);
    private static final SinglePermission PERMISSION_PRIVILEGED_CAR_INFO =
            new SinglePermission(Car.PERMISSION_PRIVILEGED_CAR_INFO);
    private static final SinglePermission PERMISSION_READ_ADAS_SETTINGS =
            new SinglePermission(Car.PERMISSION_READ_ADAS_SETTINGS);
    private static final SinglePermission PERMISSION_CONTROL_ADAS_SETTINGS =
            new SinglePermission(Car.PERMISSION_CONTROL_ADAS_SETTINGS);
    private static final SinglePermission PERMISSION_READ_ADAS_STATES =
            new SinglePermission(Car.PERMISSION_READ_ADAS_STATES);
    private static final SinglePermission PERMISSION_CONTROL_ADAS_STATES =
            new SinglePermission(Car.PERMISSION_CONTROL_ADAS_STATES);
    private static final SinglePermission PERMISSION_READ_DRIVER_MONITORING_SETTINGS =
            new SinglePermission(Car.PERMISSION_READ_DRIVER_MONITORING_SETTINGS);
    private static final SinglePermission PERMISSION_CONTROL_DRIVER_MONITORING_SETTINGS =
            new SinglePermission(Car.PERMISSION_CONTROL_DRIVER_MONITORING_SETTINGS);
    private static final SinglePermission PERMISSION_READ_DRIVER_MONITORING_STATES =
            new SinglePermission(Car.PERMISSION_READ_DRIVER_MONITORING_STATES);
    private static final SinglePermission PERMISSION_CAR_ENGINE_DETAILED =
            new SinglePermission(Car.PERMISSION_CAR_ENGINE_DETAILED);
    private static final SinglePermission PERMISSION_MILEAGE =
            new SinglePermission(Car.PERMISSION_MILEAGE);
    private static final SinglePermission PERMISSION_SPEED =
            new SinglePermission(Car.PERMISSION_SPEED);
    private static final SinglePermission PERMISSION_ENERGY =
            new SinglePermission(Car.PERMISSION_ENERGY);
    private static final SinglePermission PERMISSION_CONTROL_CAR_ENERGY =
            new SinglePermission(Car.PERMISSION_CONTROL_CAR_ENERGY);
    private static final SinglePermission PERMISSION_ENERGY_PORTS =
            new SinglePermission(Car.PERMISSION_ENERGY_PORTS);
    private static final SinglePermission PERMISSION_CONTROL_ENERGY_PORTS =
            new SinglePermission(Car.PERMISSION_CONTROL_ENERGY_PORTS);
    private static final SinglePermission PERMISSION_ADJUST_RANGE_REMAINING =
            new SinglePermission(Car.PERMISSION_ADJUST_RANGE_REMAINING);
    private static final SinglePermission PERMISSION_TIRES =
            new SinglePermission(Car.PERMISSION_TIRES);
    private static final SinglePermission PERMISSION_POWERTRAIN =
            new SinglePermission(Car.PERMISSION_POWERTRAIN);
    private static final SinglePermission PERMISSION_CONTROL_POWERTRAIN =
            new SinglePermission(Car.PERMISSION_CONTROL_POWERTRAIN);
    private static final SinglePermission PERMISSION_EXTERIOR_ENVIRONMENT =
            new SinglePermission(Car.PERMISSION_EXTERIOR_ENVIRONMENT);
    private static final SinglePermission PERMISSION_CAR_DYNAMICS_STATE =
            new SinglePermission(Car.PERMISSION_CAR_DYNAMICS_STATE);
    private static final SinglePermission PERMISSION_CAR_EPOCH_TIME =
            new SinglePermission(Car.PERMISSION_CAR_EPOCH_TIME);
    private static final SinglePermission PERMISSION_ACCESS_FINE_LOCATION =
            new SinglePermission(ACCESS_FINE_LOCATION);
    private static final SinglePermission PERMISSION_VENDOR_EXTENSION =
            new SinglePermission(Car.PERMISSION_VENDOR_EXTENSION);
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<PropertyPermissions> mHalPropIdToPermissions = new SparseArray<>();

    /**
     * Class to hold {@code readPermission} and {@code writePermission} in a single object.
     */
    public static final class PropertyPermissions {
        @Nullable
        private final PermissionCondition mReadPermission;
        @Nullable
        private final PermissionCondition mWritePermission;

        private PropertyPermissions(@Nullable PermissionCondition readPermission,
                @Nullable PermissionCondition writePermission) {
            mReadPermission = readPermission;
            mWritePermission = writePermission;
        }

        @Nullable
        public PermissionCondition getReadPermission() {
            return mReadPermission;
        }

        @Nullable
        public PermissionCondition getWritePermission() {
            return mWritePermission;
        }

        public static final class Builder {
            @Nullable
            private PermissionCondition mReadPermission;
            @Nullable
            private PermissionCondition mWritePermission;

            public Builder setReadPermission(PermissionCondition readPermission) {
                mReadPermission = readPermission;
                return this;
            }

            public Builder setWritePermission(PermissionCondition writePermission) {
                mWritePermission = writePermission;
                return this;
            }

            public PropertyPermissions build() {
                if (mReadPermission == null && mWritePermission == null) {
                    throw new IllegalStateException("Both read and write permissions have not been "
                        + "set");
                }
                return new PropertyPermissions(mReadPermission, mWritePermission);
            }
        }
    }

    /**
     * An interface for representing the read and write permissions required for each property.
     * <p>
     * <p>If a property requires only a singular permission for read or write, that permission
     * should be instantiated in a {@link SinglePermission} class. If the property requires multiple
     * permissions, the {@link AllOfPermissions} class should be used. If the property requires one
     * out of any group of permissions, the {@link AnyOfPermissions} class should be used. If a
     * combination of these is required for read or write, a combination of AllOfPermissions and
     * AnyOfPermissions should be used as described in their javadocs.
     */
    public interface PermissionCondition {

        /**
         * Determines whether the condition defined in the class has been met or not, within a given
         * context.
         *
         * @param context Context to check
         * @return whether required permission are granted.
         */
        boolean isMet(Context context);
    }

    /**
     * Implementation to store {@code allOf()} permission sets.
     * <p>
     * <p>This implementation of {@link PermissionCondition} stores the permissions that a property
     * would require all of in order to be granted. AllOfPermissions stores the permissions as a
     * {@code List<PermissionCondition>}, so singular permissions in AllOfPermissions will be stored
     * as {@link SinglePermission} objects in the list, and a set of anyOf permissions will be
     * stored as {@link AnyOfPermissions} objects.
     */
    public static final class AllOfPermissions implements PermissionCondition {
        private final List<PermissionCondition> mPermissionsList;

        public AllOfPermissions(PermissionCondition... permissions) {
            if (permissions.length <= 1) {
                throw new IllegalArgumentException("Input parameter should contain at least 2 "
                        + "PermissionCondition objects");
            }
            mPermissionsList = new ArrayList<>();
            Collections.addAll(mPermissionsList, permissions);
        }

        /**
         * Checks whether every {@link PermissionCondition} in this object has been granted by the
         * given context or not.
         *
         * @param context Context to check
         * @return whether all permissions in the AllOfPermissions object are met.
         */
        public boolean isMet(Context context) {
            for (int i = 0; i < mPermissionsList.size(); i++) {
                if (!mPermissionsList.get(i).isMet(context)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder stringBuffer = new StringBuilder().append('(');
            for (int i = 0; i < mPermissionsList.size() - 1; i++) {
                stringBuffer.append(mPermissionsList.get(i).toString());
                stringBuffer.append(" && ");
            }
            stringBuffer.append(mPermissionsList.get(mPermissionsList.size() - 1)).append(')');
            return stringBuffer.toString();
        }
    }

    /**
     * Implementation to store {@code anyOf()} permission sets.
     * <p>
     * <p>This implementation of {@link PermissionCondition} stores the permissions that a property
     * would require any of in order to be granted. AnyOfPermissions stores the permissions as a
     * {@code List<PermissionCondition>}, so singular permissions in AnyOfPermissions will be stored
     * as {@link SinglePermission} objects in the list, and a set of allOf permissions will be
     * stored as {@link AllOfPermissions} objects.
     */
    public static final class AnyOfPermissions implements PermissionCondition {
        private final List<PermissionCondition> mPermissionsList;

        public AnyOfPermissions(PermissionCondition... permissions) {
            if (permissions.length <= 1) {
                throw new IllegalArgumentException("Input parameter should contain at least 2 "
                        + "PermissionCondition objects");
            }
            mPermissionsList = new ArrayList<>();
            Collections.addAll(mPermissionsList, permissions);
        }

        /**
         * Checks whether any {@link PermissionCondition} in this object has been granted by the
         * given context or not.
         *
         * @param context Context to check
         * @return whether any permission in the AnyOfPermissions object has been met.
         */
        public boolean isMet(Context context) {
            for (int i = 0; i < mPermissionsList.size(); i++) {
                if (mPermissionsList.get(i).isMet(context)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder stringBuffer = new StringBuilder().append('(');
            for (int i = 0; i < mPermissionsList.size() - 1; i++) {
                stringBuffer.append(mPermissionsList.get(i).toString());
                stringBuffer.append(" || ");
            }
            stringBuffer.append(mPermissionsList.get(mPermissionsList.size() - 1)).append(')');
            return stringBuffer.toString();
        }
    }

    /**
     * Implementation to store a singular permission string.
     * <p>
     * <p>This implementation of {@link PermissionCondition} holds a singular permission. This class
     * is used to hold individual permissions on their own in the property-permissions map or within
     * some other implementation of PermissionCondition.
     */
    public static final class SinglePermission implements PermissionCondition {
        private final String mPermission;

        public SinglePermission(String permission) {
            mPermission = permission;
        }

        /**
         * Checks if the permission is granted in a given context.
         *
         * @param context Context to check
         * @return whether permission has been granted.
         */
        public boolean isMet(Context context) {
            return context.checkCallingOrSelfPermission(mPermission)
                    == PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public String toString() {
            return mPermission;
        }
    }

    /**
     * Gets readPermission using a HAL-level propertyId.
     *
     * @param halPropId HAL-level propertyId
     * @return PermissionCondition object that represents halPropId's readPermission
     */
    @Nullable
    public PermissionCondition getReadPermission(int halPropId) {
        PropertyPermissions propertyPermissions;
        synchronized (mLock) {
            propertyPermissions = mHalPropIdToPermissions.get(halPropId);
        }
        if (propertyPermissions != null) {
            // Property ID exists. Return read permission.
            PermissionCondition readPermission = propertyPermissions.getReadPermission();
            if (readPermission == null) {
                Slogf.v(TAG, "propId is not available for reading: "
                        + VehiclePropertyIds.toString(halPropId));
            }
            return readPermission;
        } else if (CarPropertyHelper.isVendorProperty(halPropId)) {
            // if property is vendor property and does not have specific permission.
            return PERMISSION_VENDOR_EXTENSION;
        } else {
            return null;
        }
    }

    /**
     * Gets writePermission using a HAL-level propertyId.
     *
     * @param halPropId HAL-level propertyId
     * @return PermissionCondition object that represents halPropId's writePermission
     */
    @Nullable
    public PermissionCondition getWritePermission(int halPropId) {
        PropertyPermissions propertyPermissions;
        synchronized (mLock) {
            propertyPermissions = mHalPropIdToPermissions.get(halPropId);
        }
        if (propertyPermissions != null) {
            // Property ID exists. Return write permission.
            PermissionCondition writePermission = propertyPermissions.getWritePermission();
            if (writePermission == null) {
                Slogf.v(TAG, "propId is not writable: "
                        + VehiclePropertyIds.toString(halPropId));
            }
            return writePermission;
        } else if (CarPropertyHelper.isVendorProperty(halPropId)) {
            // if property is vendor property and does not have specific permission.
            return PERMISSION_VENDOR_EXTENSION;
        } else {
            return null;
        }
    }

    /**
     * Checks if readPermission is granted for a HAL-level propertyId in a given context.
     *
     * @param halPropId HAL-level propertyId
     * @param context Context to check
     * @return readPermission is granted or not.
     */
    public boolean isReadable(int halPropId, Context context) {
        PermissionCondition readPermission = getReadPermission(halPropId);
        if (readPermission == null) {
            Slogf.v(TAG, "propId is not readable or is a system property but does not exist "
                    + "in PropertyPermissionInfo: " + VehiclePropertyIds.toString(halPropId));
            return false;
        }
        return readPermission.isMet(context);
    }

    /**
     * Checks if writePermission is granted for a HAL-level propertyId in a given context.
     *
     * @param halPropId HAL-level propertyId
     * @param context Context to check
     * @return writePermission is granted or not.
     */
    public boolean isWritable(int halPropId, Context context) {
        PermissionCondition writePermission = getWritePermission(halPropId);
        if (writePermission == null) {
            Slogf.v(TAG, "propId is not writable or is a system property but does not exist "
                    + "in PropertyPermissionInfo: " + VehiclePropertyIds.toString(halPropId));
            return false;
        }
        return writePermission.isMet(context);
    }

    /**
     * Adds vendor property permissions to property-permission map using a HAL-level propertyId.
     *
     * @param halPropId HAL-level propertyId
     * @param readPermissionString new read permission
     * @param writePermissionString new write permission
     */
    private void addPermissions(int halPropId, String readPermissionString,
            String writePermissionString) {
        if (!CarPropertyHelper.isVendorProperty(halPropId)) {
            throw new IllegalArgumentException(
                    "propId is not a vendor property and thus cannot "
                            + "have its permissions overwritten in PropertyPermissionInfo: 0x"
                            + VehiclePropertyIds.toString(halPropId));
        }

        synchronized (mLock) {
            if (mHalPropIdToPermissions.get(halPropId) != null) {
                Slogf.e(TAG, "propId is a vendor property that already exists in "
                        + "mHalPropIdToPermissions and thus cannot have its permissions "
                        + "overwritten in PropertyPermissionInfo: 0x"
                        + VehiclePropertyIds.toString(halPropId));
                return;
            }

            PropertyPermissions.Builder propertyPermissionBuilder =
                    new PropertyPermissions.Builder();

            if (readPermissionString != null) {
                propertyPermissionBuilder.setReadPermission(
                        new SinglePermission(readPermissionString));
            }
            if (writePermissionString != null) {
                propertyPermissionBuilder.setWritePermission(
                        new SinglePermission(writePermissionString));
            }

            mHalPropIdToPermissions.put(halPropId, propertyPermissionBuilder.build());
        }
    }

    /**
     * Overrides the permission map for vendor properties
     *
     * @param configArray the configArray for
     * {@link VehicleProperty#SUPPORT_CUSTOMIZE_VENDOR_PERMISSION}
     */
    public void customizeVendorPermission(@NonNull int[] configArray) {
        if (configArray == null || configArray.length % 3 != 0) {
            throw new IllegalArgumentException(
                    "ConfigArray for SUPPORT_CUSTOMIZE_VENDOR_PERMISSION is wrong");
        }
        int index = 0;
        while (index < configArray.length) {
            int propId = configArray[index++];
            if (!CarPropertyHelper.isVendorProperty(propId)) {
                throw new IllegalArgumentException("Property Id: " + propId
                        + " is not in vendor range");
            }
            int readPermission = configArray[index++];
            int writePermission = configArray[index++];
            addPermissions(propId, toPermissionString(readPermission, propId),
                    toPermissionString(writePermission, propId));
        }
    }

    /**
     * Maps VehicleVendorPermission enums in VHAL to android permissions.
     *
     * @return permission string, return null if vendor property is not available.
     */
    @Nullable
    private static String toPermissionString(int permissionEnum, int propId) {
        switch (permissionEnum) {
            case PERMISSION_CAR_VENDOR_DEFAULT:
                return Car.PERMISSION_VENDOR_EXTENSION;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_WINDOW:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_WINDOW;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_WINDOW:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_WINDOW;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_DOOR:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_DOOR;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_DOOR:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_DOOR;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_SEAT:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_SEAT;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_SEAT:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_SEAT;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_MIRROR:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_MIRROR;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_MIRROR:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_MIRROR;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_INFO:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_INFO;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_INFO:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_INFO;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_ENGINE:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_ENGINE;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_ENGINE:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_ENGINE;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_HVAC:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_HVAC;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_HVAC:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_HVAC;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_LIGHT:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_LIGHT;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_LIGHT:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_LIGHT;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_1:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_1;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_1:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_1;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_2:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_2;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_2:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_2;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_3:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_3;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_3:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_3;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_4:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_4;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_4:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_4;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_5:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_5;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_5:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_5;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_6:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_6;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_6:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_6;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_7:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_7;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_7:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_7;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_8:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_8;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_8:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_8;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_9:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_9;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_9:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_9;
            case PERMISSION_SET_CAR_VENDOR_CATEGORY_10:
                return VehicleVendorPermission.PERMISSION_SET_CAR_VENDOR_CATEGORY_10;
            case PERMISSION_GET_CAR_VENDOR_CATEGORY_10:
                return VehicleVendorPermission.PERMISSION_GET_CAR_VENDOR_CATEGORY_10;
            case PERMISSION_CAR_VENDOR_NOT_ACCESSIBLE:
                return null;
            default:
                throw new IllegalArgumentException("permission Id: " + permissionEnum
                        + " for property:" + propId + " is invalid vendor permission Id");
        }
    }

    /**
     * Checks if property ID is in the list of known IDs that PropertyHalService is interested it.
     */
    public boolean isSupportedProperty(int propId) {
        synchronized (mLock) {
            // Property is in the list of supported properties
            return mHalPropIdToPermissions.get(propId) != null
                    || CarPropertyHelper.isVendorProperty(propId);
        }
    }

    public PropertyPermissionInfo() {
        synchronized (mLock) {
            // Add propertyId and read/write permissions
            // Cabin Properties
            mHalPropIdToPermissions.put(VehicleProperty.DOOR_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_DOORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_DOORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.DOOR_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_DOORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_DOORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.DOOR_LOCK,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_DOORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_DOORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.DOOR_CHILD_LOCK_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_DOORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_DOORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.MIRROR_Z_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.MIRROR_Z_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.MIRROR_Y_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.MIRROR_Y_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.MIRROR_LOCK,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.MIRROR_FOLD,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.MIRROR_AUTO_FOLD_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.MIRROR_AUTO_TILT_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_MIRRORS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.GLOVE_BOX_DOOR_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_GLOVE_BOX)
                            .setWritePermission(PERMISSION_CONTROL_GLOVE_BOX)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.GLOVE_BOX_LOCKED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_GLOVE_BOX)
                            .setWritePermission(PERMISSION_CONTROL_GLOVE_BOX)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_MEMORY_SELECT,
                    new PropertyPermissions.Builder()
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_MEMORY_SET,
                    new PropertyPermissions.Builder()
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_BELT_BUCKLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_BELT_HEIGHT_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_BELT_HEIGHT_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_FORE_AFT_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_FORE_AFT_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_BACKREST_ANGLE_1_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_BACKREST_ANGLE_1_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_BACKREST_ANGLE_2_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_BACKREST_ANGLE_2_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEIGHT_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEIGHT_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_DEPTH_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_DEPTH_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_TILT_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_TILT_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_FORE_AFT_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_FORE_AFT_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_SIDE_SUPPORT_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_SIDE_SUPPORT_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_HEIGHT_POS_V2,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_HEIGHT_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_ANGLE_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_ANGLE_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_FORE_AFT_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_HEADREST_FORE_AFT_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_FOOTWELL_LIGHTS_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_INTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_FOOTWELL_LIGHTS_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_INTERIOR_LIGHTS)
                            .setWritePermission(PERMISSION_CONTROL_INTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_EASY_ACCESS_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_AIRBAG_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_AIRBAGS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_AIRBAGS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_CUSHION_SIDE_SUPPORT_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_CUSHION_SIDE_SUPPORT_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_VERTICAL_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_LUMBAR_VERTICAL_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_WALK_IN_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.SEAT_OCCUPANCY,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_SEATS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.WINDOW_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_WINDOWS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_WINDOWS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.WINDOW_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_WINDOWS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_WINDOWS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.WINDOW_LOCK,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_WINDOWS)
                            .setWritePermission(PERMISSION_CONTROL_CAR_WINDOWS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.WINDSHIELD_WIPERS_PERIOD,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_WINDSHIELD_WIPERS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.WINDSHIELD_WIPERS_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_WINDSHIELD_WIPERS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.WINDSHIELD_WIPERS_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_WINDSHIELD_WIPERS)
                            .setWritePermission(PERMISSION_CONTROL_WINDSHIELD_WIPERS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_DEPTH_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .setWritePermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_DEPTH_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .setWritePermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_HEIGHT_POS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .setWritePermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_HEIGHT_MOVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .setWritePermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_THEFT_LOCK_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .setWritePermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_LOCKED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .setWritePermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_EASY_ACCESS_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .setWritePermission(PERMISSION_CONTROL_STEERING_WHEEL)
                            .build());

            // HVAC properties
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_FAN_SPEED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_FAN_DIRECTION,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_TEMPERATURE_CURRENT,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_TEMPERATURE_SET,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_TEMPERATURE_VALUE_SUGGESTION,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_DEFROSTER,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_ELECTRIC_DEFROSTER_ON,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_AC_ON,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_MAX_AC_ON,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_MAX_DEFROST_ON,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_RECIRC_ON,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_DUAL_ON,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_AUTO_ON,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_SEAT_TEMPERATURE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_SIDE_MIRROR_HEAT,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_STEERING_WHEEL_HEAT,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_TEMPERATURE_DISPLAY_UNITS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(new AnyOfPermissions(
                                    PERMISSION_CONTROL_CAR_CLIMATE,
                                    PERMISSION_READ_DISPLAY_UNITS
                            ))
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_ACTUAL_FAN_SPEED_RPM,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_POWER_ON,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_FAN_DIRECTION_AVAILABLE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_AUTO_RECIRC_ON,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HVAC_SEAT_VENTILATION,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .setWritePermission(PERMISSION_CONTROL_CAR_CLIMATE)
                            .build());

            // Info properties
            mHalPropIdToPermissions.put(VehicleProperty.INFO_VIN,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_IDENTIFICATION)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_MAKE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_MODEL,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_MODEL_YEAR,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_FUEL_CAPACITY,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_FUEL_TYPE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_EV_BATTERY_CAPACITY,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_EV_CONNECTOR_TYPE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_FUEL_DOOR_LOCATION,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_MULTI_EV_PORT_LOCATIONS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_EV_PORT_LOCATION,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_DRIVER_SEAT,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.INFO_EXTERIOR_DIMENSIONS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());

            // Sensor properties
            mHalPropIdToPermissions.put(VehicleProperty.EMERGENCY_LANE_KEEP_ASSIST_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_SETTINGS)
                            .setWritePermission(PERMISSION_CONTROL_ADAS_SETTINGS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EMERGENCY_LANE_KEEP_ASSIST_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.CRUISE_CONTROL_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_SETTINGS)
                            .setWritePermission(PERMISSION_CONTROL_ADAS_SETTINGS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.CRUISE_CONTROL_TYPE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .setWritePermission(PERMISSION_CONTROL_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.CRUISE_CONTROL_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.CRUISE_CONTROL_COMMAND,
                    new PropertyPermissions.Builder()
                            .setWritePermission(PERMISSION_CONTROL_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.CRUISE_CONTROL_TARGET_SPEED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.ADAPTIVE_CRUISE_CONTROL_TARGET_TIME_GAP,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .setWritePermission(PERMISSION_CONTROL_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(
                    VehicleProperty.ADAPTIVE_CRUISE_CONTROL_LEAD_VEHICLE_MEASURED_DISTANCE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HANDS_ON_DETECTION_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_DRIVER_MONITORING_SETTINGS)
                            .setWritePermission(PERMISSION_CONTROL_DRIVER_MONITORING_SETTINGS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HANDS_ON_DETECTION_DRIVER_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_DRIVER_MONITORING_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HANDS_ON_DETECTION_WARNING,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_DRIVER_MONITORING_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.PERF_ODOMETER,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_MILEAGE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.PERF_VEHICLE_SPEED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_SPEED)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.PERF_VEHICLE_SPEED_DISPLAY,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_SPEED)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.ENGINE_COOLANT_TEMP,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_ENGINE_DETAILED)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.ENGINE_OIL_LEVEL,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_ENGINE_DETAILED)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.ENGINE_OIL_TEMP,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_ENGINE_DETAILED)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.ENGINE_RPM,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_ENGINE_DETAILED)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.ENGINE_IDLE_AUTO_STOP_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_ENGINE_DETAILED)
                            .setWritePermission(PERMISSION_CAR_ENGINE_DETAILED)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.WHEEL_TICK,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_SPEED)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.FUEL_LEVEL,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.FUEL_DOOR_OPEN,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY_PORTS)
                            .setWritePermission(PERMISSION_CONTROL_ENERGY_PORTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_BATTERY_LEVEL,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_CURRENT_BATTERY_CAPACITY,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_CURRENT_DRAW_LIMIT,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .setWritePermission(PERMISSION_CONTROL_CAR_ENERGY)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_PERCENT_LIMIT,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .setWritePermission(PERMISSION_CONTROL_CAR_ENERGY)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .setWritePermission(PERMISSION_CONTROL_CAR_ENERGY)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_TIME_REMAINING,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_REGENERATIVE_BRAKING_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_PORT_OPEN,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY_PORTS)
                            .setWritePermission(PERMISSION_CONTROL_ENERGY_PORTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_CHARGE_PORT_CONNECTED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY_PORTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.RANGE_REMAINING,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .setWritePermission(PERMISSION_ADJUST_RANGE_REMAINING)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.TIRE_PRESSURE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_TIRES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.CRITICALLY_LOW_TIRE_PRESSURE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_TIRES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.PERF_STEERING_ANGLE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_STEERING_STATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.PERF_REAR_STEERING_ANGLE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_STEERING_STATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.GEAR_SELECTION,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_POWERTRAIN)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.CURRENT_GEAR,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_POWERTRAIN)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.PARKING_BRAKE_ON,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_POWERTRAIN)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.PARKING_BRAKE_AUTO_APPLY,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_POWERTRAIN)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_BRAKE_REGENERATION_LEVEL,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_POWERTRAIN)
                            .setWritePermission(PERMISSION_CONTROL_POWERTRAIN)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_STOPPING_MODE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_POWERTRAIN)
                            .setWritePermission(PERMISSION_CONTROL_POWERTRAIN)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.FUEL_LEVEL_LOW,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ENERGY)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.NIGHT_MODE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_EXTERIOR_ENVIRONMENT)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.TURN_SIGNAL_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.IGNITION_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_POWERTRAIN)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.ABS_ACTIVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_DYNAMICS_STATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.TRACTION_CONTROL_ACTIVE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_DYNAMICS_STATE)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.ENV_OUTSIDE_TEMPERATURE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_EXTERIOR_ENVIRONMENT)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HEADLIGHTS_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HIGH_BEAM_LIGHTS_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.FOG_LIGHTS_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.FRONT_FOG_LIGHTS_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.REAR_FOG_LIGHTS_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HAZARD_LIGHTS_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HEADLIGHTS_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .setWritePermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HIGH_BEAM_LIGHTS_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .setWritePermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.FOG_LIGHTS_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .setWritePermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.FRONT_FOG_LIGHTS_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .setWritePermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.REAR_FOG_LIGHTS_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .setWritePermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.HAZARD_LIGHTS_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .setWritePermission(PERMISSION_CONTROL_EXTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.READING_LIGHTS_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_INTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.CABIN_LIGHTS_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_INTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_LIGHTS_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_INTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.READING_LIGHTS_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_INTERIOR_LIGHTS)
                            .setWritePermission(PERMISSION_CONTROL_INTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.CABIN_LIGHTS_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_INTERIOR_LIGHTS)
                            .setWritePermission(PERMISSION_CONTROL_INTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.STEERING_WHEEL_LIGHTS_SWITCH,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CONTROL_INTERIOR_LIGHTS)
                            .setWritePermission(PERMISSION_CONTROL_INTERIOR_LIGHTS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.ANDROID_EPOCH_TIME,
                    new PropertyPermissions.Builder()
                            .setWritePermission(PERMISSION_CAR_EPOCH_TIME)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.AUTOMATIC_EMERGENCY_BRAKING_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_SETTINGS)
                            .setWritePermission(PERMISSION_CONTROL_ADAS_SETTINGS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.AUTOMATIC_EMERGENCY_BRAKING_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.FORWARD_COLLISION_WARNING_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_SETTINGS)
                            .setWritePermission(PERMISSION_CONTROL_ADAS_SETTINGS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.FORWARD_COLLISION_WARNING_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.BLIND_SPOT_WARNING_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_SETTINGS)
                            .setWritePermission(PERMISSION_CONTROL_ADAS_SETTINGS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.BLIND_SPOT_WARNING_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.LANE_DEPARTURE_WARNING_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_SETTINGS)
                            .setWritePermission(PERMISSION_CONTROL_ADAS_SETTINGS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.LANE_DEPARTURE_WARNING_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.LANE_KEEP_ASSIST_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_SETTINGS)
                            .setWritePermission(PERMISSION_CONTROL_ADAS_SETTINGS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.LANE_KEEP_ASSIST_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.LANE_CENTERING_ASSIST_ENABLED,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_SETTINGS)
                            .setWritePermission(PERMISSION_CONTROL_ADAS_SETTINGS)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.LANE_CENTERING_ASSIST_COMMAND,
                    new PropertyPermissions.Builder()
                            .setWritePermission(PERMISSION_CONTROL_ADAS_STATES)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.LANE_CENTERING_ASSIST_STATE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_ADAS_STATES)
                            .build());

            // Display_Units
            mHalPropIdToPermissions.put(VehicleProperty.DISTANCE_DISPLAY_UNITS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_DISPLAY_UNITS)
                            .setWritePermission(new AllOfPermissions(
                                    PERMISSION_CONTROL_DISPLAY_UNITS,
                                    PERMISSION_VENDOR_EXTENSION
                            ))
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.FUEL_VOLUME_DISPLAY_UNITS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_DISPLAY_UNITS)
                            .setWritePermission(new AllOfPermissions(
                                    PERMISSION_CONTROL_DISPLAY_UNITS,
                                    PERMISSION_VENDOR_EXTENSION
                            ))
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.TIRE_PRESSURE_DISPLAY_UNITS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_DISPLAY_UNITS)
                            .setWritePermission(new AllOfPermissions(
                                    PERMISSION_CONTROL_DISPLAY_UNITS,
                                    PERMISSION_VENDOR_EXTENSION
                            ))
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.EV_BATTERY_DISPLAY_UNITS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_DISPLAY_UNITS)
                            .setWritePermission(new AllOfPermissions(
                                    PERMISSION_CONTROL_DISPLAY_UNITS,
                                    PERMISSION_VENDOR_EXTENSION
                            ))
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.FUEL_CONSUMPTION_UNITS_DISTANCE_OVER_VOLUME,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_DISPLAY_UNITS)
                            .setWritePermission(new AllOfPermissions(
                                    PERMISSION_CONTROL_DISPLAY_UNITS,
                                    PERMISSION_VENDOR_EXTENSION
                            ))
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_READ_DISPLAY_UNITS)
                            .setWritePermission(new AllOfPermissions(
                                    PERMISSION_CONTROL_DISPLAY_UNITS,
                                    PERMISSION_VENDOR_EXTENSION
                            ))
                            .build());

            mHalPropIdToPermissions.put(VehicleProperty.ELECTRONIC_TOLL_COLLECTION_CARD_TYPE,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.ELECTRONIC_TOLL_COLLECTION_CARD_STATUS,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.VEHICLE_CURB_WEIGHT,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_PRIVILEGED_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.TRAILER_PRESENT,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_PRIVILEGED_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(
                    VehicleProperty.GENERAL_SAFETY_REGULATION_COMPLIANCE_REQUIREMENT,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_CAR_INFO)
                            .build());
            mHalPropIdToPermissions.put(VehicleProperty.LOCATION_CHARACTERIZATION,
                    new PropertyPermissions.Builder()
                            .setReadPermission(PERMISSION_ACCESS_FINE_LOCATION)
                            .build());
        }
    }
}
