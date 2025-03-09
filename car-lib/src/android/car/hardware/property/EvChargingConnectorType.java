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

package android.car.hardware.property;

import static android.car.feature.Flags.FLAG_ANDROID_B_VEHICLE_PROPERTIES;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.car.feature.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Uses IEC(International Electrotechnical Commission) 62196  and other standards to
 * denote the charging connector type an electric vehicle may use.
 *
 * <p>Applications can use {@link CarPropertyManager#getProperty(int, int)} with
 * {@link android.car.VehiclePropertyIds#INFO_EV_CONNECTOR_TYPE} to query charging connector
 * types of the car.
 */
public final class EvChargingConnectorType {
    /**
     * The vehicle does not know the charging connector type.
     */
    public static final int UNKNOWN = 0;

    /**
     * IEC 62196 Type 1 connector
     *
     * <p>Also known as the "Yazaki connector" or "J1772 connector".
     */
    public static final int IEC_TYPE_1_AC = 1;

    /**
     * IEC 62196 Type 2 connector
     *
     * <p>Also known as the "Mennekes connector".
     */
    public static final int IEC_TYPE_2_AC = 2;

    /**
     * IEC 62196 Type 3 connector
     *
     * <p>Also known as the "Scame connector".
     */
    public static final int IEC_TYPE_3_AC = 3;

    /**
     * IEC 62196 Type AA connector
     *
     * <p>Also known as the "Chademo connector".
     */
    public static final int IEC_TYPE_4_DC = 4;

    /**
     * IEC 62196 Type EE connector
     *
     * <p>Also known as the “CCS1 connector” or “Combo1 connector".
     */
    public static final int IEC_TYPE_1_CCS_DC = 5;

    /**
     * IEC 62196 Type EE connector
     *
     * <p>Also known as the “CCS2 connector” or “Combo2 connector”.
     */
    public static final int IEC_TYPE_2_CCS_DC = 6;

    /** Connector of Tesla Roadster */
    public static final int TESLA_ROADSTER = 7;

    /**
     * High Power Wall Charger of Tesla.
     *
     * @deprecated This is the same connector as the {@link #SAE_J3400_AC}. Please use that field
     * instead.
     * @see #SAE_J3400_AC
     */
    @Deprecated
    public static final int TESLA_HPWC = 8;

    /**
     * Tesla Supercharger
     *
     * @deprecated This is the same connector as the {@link #SAE_J3400_DC}. Please use that field
     * instead.
     * @see #SAE_J3400_DC
     */
    @Deprecated
    public static final int TESLA_SUPERCHARGER = 9;

    /** GBT_AC Fast Charging Standard */
    public static final int GBT_AC = 10;

    /** GBT_DC Fast Charging Standard */
    public static final int GBT_DC = 11;

    /**
     * SAE J3400 connector - AC Charging.
     *
     * <p>Also known as the "North American Charging Standard" (NACS).
     *
     * <p>This enum will be used if the vehicle specifically supports AC charging. If the vehicle
     * supports both AC and DC, {@link android.car.VehiclePropertyIds#INFO_EV_CONNECTOR_TYPE} will
     * be populated with both {@code SAE_J3400_AC} and {@link #SAE_J3400_DC}. If the vehicle only
     * supports AC charging, it will only be populated with {@code SAE_J3400_AC}.
     *
     * <p>This is equivalent to {@link #TESLA_HPWC} enum, which used to map to the same value.
     */
    @FlaggedApi(FLAG_ANDROID_B_VEHICLE_PROPERTIES)
    public static final int SAE_J3400_AC = 8;

    /**
     * SAE J3400 connector - DC Charging.
     *
     * <p>Also known as the "North American Charging Standard" (NACS).
     *
     * <p>This enum will be used if the vehicle specifically supports DC charging. If the vehicle
     * supports both AC and DC, {@link android.car.VehiclePropertyIds#INFO_EV_CONNECTOR_TYPE} will
     * be populated with both {@link #SAE_J3400_AC} and {@code SAE_J3400_DC}. If the vehicle only
     * supports DC charging, it will only be populated with {@code SAE_J3400_DC}.
     *
     * <p>This is equivalent to {@link #TESLA_SUPERCHARGER} enum, which used to map to the same
     * value.
     */
    @FlaggedApi(FLAG_ANDROID_B_VEHICLE_PROPERTIES)
    public static final int SAE_J3400_DC = 9;

    /**
     * Connector type to use when no other types apply.
     */
    public static final int OTHER = 101;

    /** @hide */
    @IntDef({
            UNKNOWN,
            IEC_TYPE_1_AC,
            IEC_TYPE_2_AC,
            IEC_TYPE_3_AC,
            IEC_TYPE_4_DC,
            IEC_TYPE_1_CCS_DC,
            IEC_TYPE_2_CCS_DC,
            TESLA_ROADSTER,
            TESLA_HPWC,
            TESLA_SUPERCHARGER,
            GBT_AC,
            GBT_DC,
            SAE_J3400_AC,
            SAE_J3400_DC,
            OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Enum {}

    private EvChargingConnectorType() {}

    /**
     * Gets a user-friendly representation of a charging connector type.
     */
    @NonNull
    public static String toString(@EvChargingConnectorType.Enum int connectorType) {
        switch (connectorType) {
            case UNKNOWN:
                return "UNKNOWN";
            case IEC_TYPE_1_AC:
                return "IEC_TYPE_1_AC";
            case IEC_TYPE_2_AC:
                return "IEC_TYPE_2_AC";
            case IEC_TYPE_3_AC:
                return "IEC_TYPE_3_AC";
            case IEC_TYPE_4_DC:
                return "IEC_TYPE_4_DC";
            case IEC_TYPE_1_CCS_DC:
                return "IEC_TYPE_1_CCS_DC";
            case IEC_TYPE_2_CCS_DC:
                return "IEC_TYPE_2_CCS_DC";
            case TESLA_ROADSTER:
                return "TESLA_ROADSTER";
            case GBT_AC:
                return "GBT_AC";
            case GBT_DC:
                return "GBT_DC";
            case OTHER:
                return "OTHER";
            default:
                if (Flags.androidBVehicleProperties()) {
                    if (connectorType == SAE_J3400_AC) {
                        return "SAE_J3400_AC";
                    } else if (connectorType == SAE_J3400_DC) {
                        return "SAE_J3400_DC";
                    }
                } else {
                    if (connectorType == TESLA_HPWC) {
                        return "TESLA_HPWC";
                    } else if (connectorType == TESLA_SUPERCHARGER) {
                        return "TESLA_SUPERCHARGER";
                    }
                }
                return "0x" + Integer.toHexString(connectorType);
        }
    }
}
