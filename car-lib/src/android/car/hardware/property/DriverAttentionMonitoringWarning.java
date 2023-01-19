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
 * Used to enumerate the current warning state of Driver Attention Monitoring.
 *
 * <p>This enum could be extended in future releases to include additional feature states.
 * @hide
 */
@SystemApi
public class DriverAttentionMonitoringWarning {
    /**
     * This state is used as an alternative for any DriverAttentionMonitoringWarning value that is
     * defined in the platform. Ideally, implementations of
     * {@link DRIVER_ATTENTION_MONITORING_WARNING} should not use this state. The framework can use
     * this field to remain backwards compatible if DriverAttentionMonitoringWarning is extended to
     * include additional states.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int OTHER = 0;
    /**
     * Driver Attention Monitoring is enabled and the driver's current state does not warrant
     * sending a warning.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int NO_WARNING = 1;
    /**
     * Driver Attention Monitoring is enabled and the driver has been distracted for too long of a
     * duration, and the vehicle is sending a warning to the driver as a consequence of this.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int WARNING = 2;

    private DriverAttentionMonitoringWarning() {}

    /**
     * Returns a user-friendly representation of a {@code DriverAttentionMonitoringWarning}.
     */
    @NonNull
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static String toString(
            @DriverAttentionMonitoringWarning.DriverAttentionMonitoringWarningInt int
                    driverAttentionMonitoringWarning) {
        switch (driverAttentionMonitoringWarning) {
            case OTHER:
                return "OTHER";
            case NO_WARNING:
                return "NO_WARNING";
            case WARNING:
                return "WARNING";
            default:
                return "0x" + Integer.toHexString(driverAttentionMonitoringWarning);
        }
    }

    /** @hide */
    @IntDef({OTHER, NO_WARNING, WARNING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DriverAttentionMonitoringWarningInt {}
}
