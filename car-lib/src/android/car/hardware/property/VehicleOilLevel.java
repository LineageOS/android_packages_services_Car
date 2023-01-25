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

package android.car.hardware.property;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.car.annotation.ApiRequirements;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Used to enumerate the current value of {@link
 * android.car.VehiclePropertyIds#ENGINE_OIL_LEVEL}.
 * @hide
 */
@SystemApi
public final class VehicleOilLevel {

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int LEVEL_CRITICALLY_LOW = 0;

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int LEVEL_LOW = 1;

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int LEVEL_NORMAL = 2;

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int LEVEL_HIGH = 3;

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int LEVEL_ERROR = 4;

    private VehicleOilLevel() {}

    /**
     * Returns a user-friendly representation of a {@code VehicleOilLevel}.
     */
    @NonNull
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static String toString(
            @VehicleOilLevelInt int vehicleOilLevel) {
        switch (vehicleOilLevel) {
            case LEVEL_CRITICALLY_LOW:
                return "LEVEL_CRITICALLY_LOW";
            case LEVEL_LOW:
                return "LEVEL_LOW";
            case LEVEL_NORMAL:
                return "LEVEL_NORMAL";
            case LEVEL_HIGH:
                return "LEVEL_HIGH";
            case LEVEL_ERROR:
                return "LEVEL_ERROR";
            default:
                return "0x" + Integer.toHexString(vehicleOilLevel);
        }
    }

    /** @hide */
    @IntDef({LEVEL_CRITICALLY_LOW, LEVEL_LOW, LEVEL_NORMAL, LEVEL_HIGH, LEVEL_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface VehicleOilLevelInt {}
}

