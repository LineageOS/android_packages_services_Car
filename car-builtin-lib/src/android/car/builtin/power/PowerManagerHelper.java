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

package android.car.builtin.power;

import android.annotation.SystemApi;
import android.content.Context;
import android.os.PowerManager;

/**
 * Helper for PowerManager related operations.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class PowerManagerHelper {

    private final PowerManager mPowerManager;

    public PowerManagerHelper(Context context) {
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    /**
     * Gets the maximum supported screen brightness setting.
     * This wraps {@link PowerManager.getMaximumScreenBrightnessSetting}.
     *
     * @return The maximum value that can be set by the user.
     */
    public int getMaximumScreenBrightnessSetting() {
        return mPowerManager.getMaximumScreenBrightnessSetting();
    }

    /**
     * Gets the minimum supported screen brightness setting.
     * This wraps {@link PowerManager.getMinimumScreenBrightnessSetting}.
     *
     * @return The minimum value that can be set by the user.
     */
    public int getMinimumScreenBrightnessSetting() {
        return mPowerManager.getMinimumScreenBrightnessSetting();
    }

    /**
     * Forces the {@link com.android.server.display.DisplayGroup#DEFAULT default display group}
     * to turn on or off.
     *
     * @param on Whether to turn the display on or off.
     * @param upTime The time when the request was issued, in the
     * {@link SystemClock#uptimeMillis()} time base.
     */
    public void setDisplayState(boolean on, long upTime) {
        if (on) {
            mPowerManager.wakeUp(upTime, PowerManager.WAKE_REASON_UNKNOWN, "wake up by CarService");
        } else {
            mPowerManager.goToSleep(upTime, PowerManager.GO_TO_SLEEP_REASON_APPLICATION,
                    /* flag= */ 0);
        }
    }

    /** Turns off the device. */
    public void shutdown(boolean confirm, String reason, boolean wait) {
        mPowerManager.shutdown(confirm, reason, wait);
    }
}
