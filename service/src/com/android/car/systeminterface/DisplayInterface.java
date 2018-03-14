/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.car.systeminterface;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import com.android.car.CarLog;
import com.android.car.CarPowerManagementService;

/**
 * Interface that abstracts display operations
 */
public interface DisplayInterface {
    /**
     * @param brightness Level from 0 to 100%
     */
    void setDisplayBrightness(int brightness);
    void setDisplayState(boolean on);
    void startDisplayStateMonitoring(CarPowerManagementService service);
    void stopDisplayStateMonitoring();

    class DefaultImpl implements DisplayInterface {
        private final Context mContext;
        private final DisplayManager mDisplayManager;
        private final int mMaximumBacklight;
        private final int mMinimumBacklight;
        private final PowerManager mPowerManager;
        private final WakeLockInterface mWakeLockInterface;
        private CarPowerManagementService mService;
        private boolean mDisplayStateSet;
        private final DisplayManager.DisplayListener mDisplayListener = new DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                //ignore
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                //ignore
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    handleMainDisplayChanged();
                }
            }
        };

        DefaultImpl(Context context, WakeLockInterface wakeLockInterface) {
            mContext = context;
            mWakeLockInterface = wakeLockInterface;
            mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            mMaximumBacklight = mPowerManager.getMaximumScreenBrightnessSetting();
            mMinimumBacklight = mPowerManager.getMinimumScreenBrightnessSetting();
        }

        private void handleMainDisplayChanged() {
            boolean isOn = isMainDisplayOn();
            CarPowerManagementService service;
            synchronized (this) {
                if (mDisplayStateSet == isOn) { // same as what is set
                    return;
                }
                service = mService;
            }
            service.handleMainDisplayChanged(isOn);
        }

        private boolean isMainDisplayOn() {
            Display disp = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
            return disp.getState() == Display.STATE_ON;
        }

        @Override
        public void setDisplayBrightness(int brightness) {
            // Brightness is set in percent.  Need to convert this into 0-255 scale.  The actual
            //  brightness algorithm should look like this:
            //
            //      newBrightness = (brightness * (max - min)) + min
            //
            //  Since we're using integer arithmetic, do the multiplication first, then add 50 to
            //  round up as needed.
            brightness *= mMaximumBacklight - mMinimumBacklight;    // Multiply by full range
            brightness += 50;                                       // Integer rounding
            brightness /= 100;                                      // Divide by 100
            brightness += mMinimumBacklight;
            // Range checking
            if (brightness < mMinimumBacklight) {
                brightness = mMinimumBacklight;
            } else if (brightness > mMaximumBacklight) {
                brightness = mMaximumBacklight;
            }
            // Set the brightness
            Settings.System.putInt(mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                                   brightness);
        }

        @Override
        public void startDisplayStateMonitoring(CarPowerManagementService service) {
            synchronized (this) {
                mService = service;
                mDisplayStateSet = isMainDisplayOn();
            }
            mDisplayManager.registerDisplayListener(mDisplayListener, service.getHandler());
        }

        @Override
        public void stopDisplayStateMonitoring() {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }

        @Override
        public void setDisplayState(boolean on) {
            synchronized (this) {
                mDisplayStateSet = on;
            }
            if (on) {
                mWakeLockInterface.switchToFullWakeLock();
                Log.i(CarLog.TAG_POWER, "on display");
                mPowerManager.wakeUp(SystemClock.uptimeMillis());
            } else {
                mWakeLockInterface.switchToPartialWakeLock();
                Log.i(CarLog.TAG_POWER, "off display");
                mPowerManager.goToSleep(SystemClock.uptimeMillis());
            }
        }
    }
}
