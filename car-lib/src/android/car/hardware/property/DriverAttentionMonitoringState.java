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
 * Used to enumerate the current driver state of {@link
 * android.car.VehiclePropertyIds#DRIVER_ATTENTION_MONITORING_STATE}.
 *
 * <p>This enum could be extended in future releases to include additional feature states.
 * @hide
 */
@SystemApi
public class DriverAttentionMonitoringState {
    /**
     * This state is used as an alternative for any DriverAttentionMonitoringState value that is
     * not defined in the platform. Ideally, implementations of
     * VehicleProperty#DRIVER_ATTENTION_MONITORING_STATE should not use this state. The
     * framework can use this field to remain backwards compatible if DriverAttentionMonitoringState
     * is extended to include additional states.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int OTHER = 0;
    /**
     * The system detects that the driver is distracted.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int DISTRACTED = 1;
    /**
     * The system detects that the driver is attentive / not distracted.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int NOT_DISTRACTED = 2;


    private DriverAttentionMonitoringState() {}

    /**
     * Returns a user-friendly representation of a {@code DriverAttentionMonitoringState}.
     */
    @NonNull
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static String toString(
            @DriverAttentionMonitoringState.DriverAttentionMonitoringStateInt
                    int driverAttentionMonitoringState) {
        switch (driverAttentionMonitoringState) {
            case OTHER:
                return "OTHER";
            case DISTRACTED:
                return "DISTRACTED";
            case NOT_DISTRACTED:
                return "NOT_DISTRACTED";
            default:
                return "0x" + Integer.toHexString(driverAttentionMonitoringState);
        }
    }

    /** @hide */
    @IntDef({OTHER, DISTRACTED, NOT_DISTRACTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DriverAttentionMonitoringStateInt {}
}
