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

package com.android.car;

import static com.android.car.CarStatsLog.CAR_WAKEUP_FROM_SUSPEND_REPORTED__RESUME_TYPE__DISK;
import static com.android.car.CarStatsLog.CAR_WAKEUP_FROM_SUSPEND_REPORTED__RESUME_TYPE__RAM;
import static com.android.car.CarStatsLog.CAR_WAKEUP_FROM_SUSPEND_REPORTED__RESUME_TYPE__UNSPECIFIED;

/**
 * CarStatsLogHelper provides API to send Car events to statd.
 *
 * <p>{@link CarStatsLog} is generated in
 * {@code packages/services/Car/service/Android.bp}.
 * @hide
 */
public class CarStatsLogHelper {
    /**
     * Logs a power state change event.
     * @param state an integer defined in CarPowerManagementService.CpmsState
     */
    public static void logPowerState(int state) {
        CarStatsLog.write(CarStatsLog.CAR_POWER_STATE_CHANGED, state);
    }

    /** Logs a GarageMode start event. */
    public static void logGarageModeStart() {
        CarStatsLog.write(CarStatsLog.GARAGE_MODE_INFO, true);
    }

    /** Logs a GarageMode stop event. */
    public static void logGarageModeStop() {
        CarStatsLog.write(CarStatsLog.GARAGE_MODE_INFO, false);
    }

    /** Logs resume from suspend event */
    public static void logResumeFromSuspend(String type, long kernelStartTimeMillis,
            long carServiceStartTimeMillis, long userPerceivedStartTimeMillis,
            long deviceStartTimeMillis) {
        int statsType = switch(type) {
            case "Suspend-to-Disk" -> CAR_WAKEUP_FROM_SUSPEND_REPORTED__RESUME_TYPE__DISK;
            case "Suspend-to-RAM" -> CAR_WAKEUP_FROM_SUSPEND_REPORTED__RESUME_TYPE__RAM;
            default -> CAR_WAKEUP_FROM_SUSPEND_REPORTED__RESUME_TYPE__UNSPECIFIED;
        };
        CarStatsLog.write(CarStatsLog.CAR_WAKEUP_FROM_SUSPEND_REPORTED, statsType,
                kernelStartTimeMillis, carServiceStartTimeMillis, userPerceivedStartTimeMillis,
                deviceStartTimeMillis);
    }
}
