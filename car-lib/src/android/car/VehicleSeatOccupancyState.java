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
package android.car;

import static android.car.feature.Flags.FLAG_VEHICLE_PROPERTY_ENUMS_REMOVE_SYSTEM_API_TAGS;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;

import com.android.car.internal.util.ConstantDebugUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Used by seat occupancy to enumerate the current occupancy state of the seat.
 * <p>Use getProperty and setProperty in {@link android.car.hardware.property.CarPropertyManager} to
 * set and get this VHAL property.
 */
@FlaggedApi(FLAG_VEHICLE_PROPERTY_ENUMS_REMOVE_SYSTEM_API_TAGS)
public final class VehicleSeatOccupancyState {
    /**
     * The occupancy state of the seat is unknown.
     */
    public static final int UNKNOWN = 0;
    /**
     * The occupancy state of the seat is currently set to vacant.
     */
    public static final int VACANT = 1;
    /**
     * The occupancy state of the seat is currently set to being occupied.
     */
    public static final int OCCUPIED = 2;

    private VehicleSeatOccupancyState() {}

    /**
     * Returns a user-friendly representation of a {@code VehicleSeatOccupancyState}.
     */
    @NonNull
    public static String toString(@VehicleSeatOccupancyState.VehicleSeatOccupancyStateInt
                                  int vehicleSeatOccupancyState) {
        String vehicleSeatOccupancyStateString = ConstantDebugUtils.toName(
                VehicleSeatOccupancyState.class, vehicleSeatOccupancyState);
        return (vehicleSeatOccupancyStateString != null) ? vehicleSeatOccupancyStateString
                : "0x" + Integer.toHexString(vehicleSeatOccupancyState);
    }

    /**
     * @hide
     */
    @IntDef({UNKNOWN, VACANT, OCCUPIED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface VehicleSeatOccupancyStateInt {}
}
