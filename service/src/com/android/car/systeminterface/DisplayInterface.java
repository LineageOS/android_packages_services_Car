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
import android.util.Log;
import android.view.Display;
import com.android.car.CarLog;
import com.android.car.CarPowerManagementService;

/**
 * Interface that abstracts display operations
 */
public interface DisplayInterface {
    void setDisplayState(boolean on);
    void startDisplayStateMonitoring(CarPowerManagementService service);
    void stopDisplayStateMonitoring();

    class DefaultImpl implements DisplayInterface {
        private final DisplayManager mDisplayManager;
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
            mWakeLockInterface = wakeLockInterface;
            mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
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
