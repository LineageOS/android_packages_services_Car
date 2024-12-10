/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.car.hardware.property;

import static android.car.feature.Flags.FLAG_ANDROID_B_VEHICLE_PROPERTIES;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;

import com.android.car.internal.util.ConstantDebugUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Used to enumerate the various size classes of vehicles for the {@link
 * android.car.VehiclePropertyIds#INFO_VEHICLE_SIZE_CLASS} PROPERTY.
 *
 * <p>This enum can be extended in future releases to include additional values.
 */
@FlaggedApi(FLAG_ANDROID_B_VEHICLE_PROPERTIES)
public final class VehicleSizeClass {
    /**
     * Represents two-seaters as defined by the EPA standard of size classes for vehicles in the
     * United States.
     *
     * <p>The current implementation of EPA-standard enums follows the classification defined in
     * Federal Regulation, Title 40—Protection of Environment, Section 600.315-08 "Classes of
     * comparable automobiles".
     */
    public static final int EPA_TWO_SEATER = 0x100;
    /**
     * Represents minicompact cars as defined by the EPA standard of size classes for vehicles in
     * the United States.
     */
    public static final int EPA_MINICOMPACT = 0x101;
    /**
     * Represents subcompact cars as defined by the EPA standard of size classes for vehicles in the
     * United States.
     */
    public static final int EPA_SUBCOMPACT = 0x102;
    /**
     * Represents compact cars as defined by the EPA standard of size classes for vehicles in the
     * United States.
     */
    public static final int EPA_COMPACT = 0x103;
    /**
     * Represents midsize cars as defined by the EPA standard of size classes for vehicles in the
     * United States.
     */
    public static final int EPA_MIDSIZE = 0x104;
    /**
     * Represents large cars as defined by the EPA standard of size classes for vehicles in the
     * United States.
     */
    public static final int EPA_LARGE = 0x105;
    /**
     * Represents small station wagons as defined by the EPA standard of size classes for vehicles
     * in the United States.
     */
    public static final int EPA_SMALL_STATION_WAGON = 0x106;
    /**
     * Represents midsize station wagons as defined by the EPA standard of size classes for vehicles
     * in the United States.
     */
    public static final int EPA_MIDSIZE_STATION_WAGON = 0x107;
    /**
     * Represents large station wagons as defined by the EPA standard of size classes for vehicles
     * in the United States.
     */
    public static final int EPA_LARGE_STATION_WAGON = 0x108;
    /**
     * Represents small pickup trucks as defined by the EPA standard of size classes for vehicles
     * in the United States.
     */
    public static final int EPA_SMALL_PICKUP_TRUCK = 0x109;
    /**
     * Represents standard pickup trucks as defined by the EPA standard of size classes for vehicles
     * in the United States.
     */
    public static final int EPA_STANDARD_PICKUP_TRUCK = 0x10A;
    /**
     * Represents vans as defined by the EPA standard of size classes for vehicles in the United
     * States.
     */
    public static final int EPA_VAN = 0x10B;
    /**
     * Represents minivans as defined by the EPA standard of size classes for vehicles in the United
     * States.
     */
    public static final int EPA_MINIVAN = 0x10C;
    /**
     * Represents small sport utility vehicles (SUVs) as defined by the EPA standard of size classes
     * for vehicles in the United States.
     */
    public static final int EPA_SMALL_SUV = 0x10D;
    /**
     * Represents standard sport utility vehicles (SUVs) as defined by the EPA standard of size
     * classes for vehicles in the United States.
     */
    public static final int EPA_STANDARD_SUV = 0x10E;
    /**
     * Represents A-segment vehicle size class, commonly called "mini" cars or "city" cars, as
     * classified in the EU.
     *
     * <p>The current implementation of the EU Car Segment enums follows the classification first
     * described in Case No COMP/M.1406 Hyundai / Kia Regulation (EEC) No 4064/89 Merger Procedure.
     */
    public static final int EU_A_SEGMENT = 0x200;
    /**
     * Represents B-segment vehicle size class, commonly called "small" cars, as classified in the
     * EU.
     */
    public static final int EU_B_SEGMENT = 0x201;
    /**
     * Represents C-segment vehicle size class, commonly called "medium" cars, as classified in the
     * EU.
     */
    public static final int EU_C_SEGMENT = 0x202;
    /**
     * Represents D-segment vehicle size class, commonly called "large" cars, as classified in the
     * EU.
     */
    public static final int EU_D_SEGMENT = 0x203;
    /**
     * Represents E-segment vehicle size class, commonly called "executive" cars, as classified in
     * the EU.
     */
    public static final int EU_E_SEGMENT = 0x204;
    /**
     * Represents F-segment vehicle size class, commonly called "luxury" cars, as classified in the
     * EU.
     */
    public static final int EU_F_SEGMENT = 0x205;
    /**
     * Represents J-segment vehicle size class, commonly associated with SUVs and off-road vehicles,
     * as classified in the EU.
     */
    public static final int EU_J_SEGMENT = 0x206;
    /**
     * Represents M-segment vehicle size class, commonly called "multi-purpose" cars, as classified
     * in the EU.
     */
    public static final int EU_M_SEGMENT = 0x207;
    /**
     * Represents S-segment vehicle size class, commonly called "sports" cars, as classified in the
     * EU.
     */
    public static final int EU_S_SEGMENT = 0x208;
    /**
     * Represents keijidosha or "kei" cars as defined by the Japanese standard of size classes for
     * vehicles.
     *
     * <p>The current implementation of Japan-standard enums follows the classification defined in
     * the Japanese Government's Road Vehicle Act of 1951.
     */
    public static final int JPN_KEI = 0x300;
    /**
     * Represents small-size passenger vehicles as defined by the Japanese standard of size classes
     * for vehicles.
     */
    public static final int JPN_SMALL_SIZE = 0x301;
    /**
     * Represents normal-size passenger vehicles as defined by the Japanese standard of size classes
     * for vehicles.
     */
    public static final int JPN_NORMAL_SIZE = 0x302;
    /**
     * Represents Class 1 trucks following the US GVWR classification of commercial vehicles. This
     * is classified under "Light duty" vehicles by the US Federal Highway Association.
     */
    public static final int US_GVWR_CLASS_1_CV = 0x400;
    /**
     * Represents Class 2 trucks following the US GVWR classification of commercial vehicles. This
     * is classified under "Light duty" vehicles by the US Federal Highway Association.
     */
    public static final int US_GVWR_CLASS_2_CV = 0x401;
    /**
     * Represents Class 3 trucks following the US GVWR classification of commercial vehicles. This
     * is classified under "Medium duty" vehicles by the US Federal Highway Association.
     */
    public static final int US_GVWR_CLASS_3_CV = 0x402;
    /**
     * Represents Class 4 trucks following the US GVWR classification of commercial vehicles. This
     * is classified under "Medium duty" vehicles by the US Federal Highway Association.
     */
    public static final int US_GVWR_CLASS_4_CV = 0x403;
    /**
     * Represents Class 5 trucks following the US GVWR classification of commercial vehicles. This
     * is classified under "Medium duty" vehicles by the US Federal Highway Association.
     */
    public static final int US_GVWR_CLASS_5_CV = 0x404;
    /**
     * Represents Class 6 trucks following the US GVWR classification of commercial vehicles. This
     * is classified under "Medium duty" vehicles by the US Federal Highway Association.
     */
    public static final int US_GVWR_CLASS_6_CV = 0x405;
    /**
     * Represents Class 7 trucks following the US GVWR classification of commercial vehicles. This
     * is classified under "Heavy duty" vehicles by the US Federal Highway Association.
     */
    public static final int US_GVWR_CLASS_7_CV = 0x406;
    /**
     * Represents Class 8 trucks following the US GVWR classification of commercial vehicles. This
     * is classified under "Heavy duty" vehicles by the US Federal Highway Association.
     */
    public static final int US_GVWR_CLASS_8_CV = 0x407;

    private VehicleSizeClass() {}

    /**
     * Returns a user-friendly representation of {@code VehicleSizeClass}.
     */
    @NonNull
    public static String toString(@VehicleSizeClassInt int vehicleSizeClass) {
        String vehicleSizeClassString = ConstantDebugUtils.toName(
                VehicleSizeClass.class, vehicleSizeClass);
        return (vehicleSizeClassString != null)
                ? vehicleSizeClassString
                : "0x" + Integer.toHexString(vehicleSizeClass);
    }

    /** @hide */
    @IntDef({EPA_TWO_SEATER, EPA_MINICOMPACT, EPA_SUBCOMPACT, EPA_COMPACT, EPA_MIDSIZE, EPA_LARGE,
            EPA_SMALL_STATION_WAGON, EPA_MIDSIZE_STATION_WAGON, EPA_LARGE_STATION_WAGON,
            EPA_SMALL_PICKUP_TRUCK, EPA_STANDARD_PICKUP_TRUCK, EPA_VAN, EPA_MINIVAN, EPA_SMALL_SUV,
            EPA_STANDARD_SUV, EU_A_SEGMENT, EU_B_SEGMENT, EU_C_SEGMENT, EU_D_SEGMENT, EU_E_SEGMENT,
            EU_F_SEGMENT, EU_J_SEGMENT, EU_M_SEGMENT, EU_S_SEGMENT, JPN_KEI, JPN_SMALL_SIZE,
            JPN_NORMAL_SIZE, US_GVWR_CLASS_1_CV, US_GVWR_CLASS_2_CV, US_GVWR_CLASS_3_CV,
            US_GVWR_CLASS_4_CV, US_GVWR_CLASS_5_CV, US_GVWR_CLASS_6_CV, US_GVWR_CLASS_7_CV,
            US_GVWR_CLASS_8_CV})
    @Retention(RetentionPolicy.SOURCE)
    public @interface VehicleSizeClassInt {}
}
