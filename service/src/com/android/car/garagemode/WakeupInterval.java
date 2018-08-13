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

package com.android.car.garagemode;

/**
 * Defines wake up interval which then will be used by
 * {@link com.android.car.garagemode.GarageModeService} to determine when to schedule next wake up
 * from {@link com.android.car.CarPowerManagementService}
 */
class WakeupInterval {
    private int mWakeupInterval;
    private int mNumAttempts;

    WakeupInterval(int wakeupTime, int numAttempts) {
        mWakeupInterval = wakeupTime;
        mNumAttempts = numAttempts;
    }

    /**
     * Returns interval between now and next weke up.
     * @return interval in seconds
     */
    public int getWakeupInterval() {
        return mWakeupInterval;
    }

    /**
     * Returns amount of attempts to wake up with mWakeupInterval
     * @return amount of attempts
     */
    public int getNumAttempts() {
        return mNumAttempts;
    }
}
