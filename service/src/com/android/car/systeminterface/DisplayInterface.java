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

import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.car.CarServiceUtils.getContentResolverForUser;
import static com.android.car.CarServiceUtils.isEventOfType;
import static com.android.car.util.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.car.util.BrightnessUtils.convertGammaToLinear;
import static com.android.car.util.BrightnessUtils.convertLinearToGamma;

import android.car.builtin.display.DisplayManagerHelper;
import android.car.builtin.os.UserManagerHelper;
import android.car.builtin.power.PowerManagerHelper;
import android.car.builtin.util.Slogf;
import android.car.builtin.view.DisplayHelper;
import android.car.feature.Flags;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.car.user.UserLifecycleEventFilter;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Settings.System;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Display;

import com.android.car.CarLog;
import com.android.car.internal.util.IntArray;
import com.android.car.power.CarPowerManagementService;
import com.android.car.provider.Settings;
import com.android.car.user.CarUserService;
import com.android.car.user.CurrentUserFetcher;
import com.android.car.util.BrightnessUtils;
import com.android.internal.annotations.GuardedBy;

import java.util.LinkedList;

/**
 * Interface that abstracts display operations
 */
public interface DisplayInterface {

    /**
     * Sets the required services.
     *
     * @param carPowerManagementService {@link CarPowerManagementService} to listen to car power
     *                                  management changes
     * @param carUserService            {@link CarUserService} to listen to service life cycle
     *                                  changes
     */
    void init(CarPowerManagementService carPowerManagementService, CarUserService carUserService);

    /**
     * Sets display brightness with the given displayId according to brightness change from VHAL.
     *
     * This is used to sync the system settings with VHAL.
     *
     * @param displayId ID of a display.
     * @param brightness Level from 0 to 100.
     */
    void onDisplayBrightnessChangeFromVhal(int displayId, int brightness);

    /**
     * Turns on or off display with the given displayId.
     *
     * @param displayId ID of a display.
     * @param on {@code true} to turn on, {@code false} to turn off.
     */
    void setDisplayState(int displayId, boolean on);

    /**
     * Turns on or off all displays.
     *
     * @param on {@code true} to turn on, {@code false} to turn off.
     */
    void setAllDisplayState(boolean on);

    /**
     * Starts monitoring the display state change.
     * <p> When there is a change, {@link CarPowerManagementService} is notified.
     */
    void startDisplayStateMonitoring();

    /**
     * Stops monitoring the display state change.
     */
    void stopDisplayStateMonitoring();

    /**
     * Gets the current on/off state of displays.
     *
     * @return {@code true}, if any display is turned on. Otherwise, {@code false}.
     */
    boolean isAnyDisplayEnabled();

    /**
     * Gets the current on/off state of display with the given displayId.
     *
     * @param displayId ID of a display.
     */
    boolean isDisplayEnabled(int displayId);

    /**
     * Refreshing default display brightness. Used when user is switching and car turned on.
     */
    void refreshDefaultDisplayBrightness();

    /**
     * Refreshing display brightness with the given displayId.
     * Used when brightness change is observed.
     *
     * @param displayId ID of a display.
     */
    void refreshDisplayBrightness(int displayId);

    /**
     * Default implementation of display operations
     */
    class DefaultImpl implements DisplayInterface {
        private static final String TAG = DisplayInterface.class.getSimpleName();
        private static final boolean DEBUG = Slogf.isLoggable(TAG, Log.DEBUG);

        // In order to prevent flickering caused by
        // vhal_report_brightness_1 -> vhal_report_brightness_2 -> set_1_to_display_manager
        // -> set_2_to_display_manager -> on_display_change_1 -> set_vhal_to_1
        // -> on_display_change_2 -> set_vhal_to_2 -> vhal_report_brightness_1 -> ...
        // We ignore the display brightness change event from DisplayManager if the value matches
        // a request we have sent to Displaymanager in a short period. This change is likely caused
        // by our request (caused by VHAL), rather than user inputs.
        private static final int PREVENT_LOOP_REQUEST_TIME_WINDOW_MS = 1000;

        private record BrightnessForDisplayId(float brightness, int displayId) {}

        private final Context mContext;
        private final DisplayManager mDisplayManager;
        private final Object mLock = new Object();
        private final int mMaximumBacklight;
        private final int mMinimumBacklight;
        private final WakeLockInterface mWakeLockInterface;
        private final Settings mSettings;
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final DisplayHelperInterface mDisplayHelper;
        private final CurrentUserFetcher mCurrentUserFetcher;
        @GuardedBy("mLock")
        private CarPowerManagementService mCarPowerManagementService;
        @GuardedBy("mLock")
        private CarUserService mCarUserService;
        @GuardedBy("mLock")
        private final SparseBooleanArray mDisplayStateSet = new SparseBooleanArray();
        @GuardedBy("mLock")
        private boolean mDisplayStateInitiated;
        private final UserManager mUserManager;
        // A FIFO queue that stores the brightness value we recently set to DisplayManagerHelper in
        // a short time frame.
        @GuardedBy("mLock")
        private final LinkedList<BrightnessForDisplayId> mRecentlySetBrightnessForDisplayId =
                new LinkedList<>();
        // A FIFO queue that stores the brightness value we recently set to System Settings in a
        // short time frame.
        @GuardedBy("mLock")
        private final LinkedList<Integer> mRecentlySetGlobalBrightness = new LinkedList<>();
        private final UserLifecycleListener mUserLifecycleListener;

        private final ContentObserver mBrightnessObserver =
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        Slogf.i(TAG, "Brightness change from Settings: selfChange=%b", selfChange);
                        refreshDefaultDisplayBrightness();
                    }
                };

        private final DisplayManager.DisplayListener mDisplayListener = new DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                // If in rare case, this is called before initDisplayStateOnce is called, init here.
                if (initDisplayStateOnce()) {
                    return;
                }

                Slogf.i(TAG, "onDisplayAdded: displayId=%d", displayId);
                synchronized (mLock) {
                    boolean isDisplayOn = isDisplayOn(displayId);
                    mDisplayStateSet.put(displayId, isDisplayOn);
                }
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                // If in rare case, this is called before initDisplayStateOnce is called, init here.
                if (initDisplayStateOnce()) {
                    return;
                }

                Slogf.i(TAG, "onDisplayRemoved: displayId=%d", displayId);
                synchronized (mLock) {
                    mDisplayStateSet.delete(displayId);
                }
            }

            @Override
            public void onDisplayChanged(int displayId) {
                // If in rare case, this is called before initDisplayStateOnce is called, init here.
                if (initDisplayStateOnce()) {
                    return;
                }

                Slogf.i(TAG, "onDisplayChanged: displayId=%d", displayId);
                handleDisplayChanged(displayId);
            }
        };

        public DefaultImpl(Context context, WakeLockInterface wakeLockInterface, Settings settings,
                DisplayHelperInterface displayHelper, CurrentUserFetcher currentUserFetcher) {
            mContext = context;
            mDisplayManager = context.getSystemService(DisplayManager.class);
            mMaximumBacklight = PowerManagerHelper.getMaximumScreenBrightnessSetting(context);
            mMinimumBacklight = PowerManagerHelper.getMinimumScreenBrightnessSetting(context);
            mWakeLockInterface = wakeLockInterface;
            mSettings = settings;
            mDisplayHelper = displayHelper;
            mCurrentUserFetcher = currentUserFetcher;
            mUserManager = context.getSystemService(UserManager.class);
            mUserLifecycleListener = event -> {
                if (!isEventOfType(TAG, event, USER_LIFECYCLE_EVENT_TYPE_SWITCHING)) {
                    return;
                }
                if (DEBUG) {
                    Slogf.d(TAG, "DisplayInterface.DefaultImpl.onEvent(%s)", event);
                }
                if (Flags.multiDisplayBrightnessControl()) {
                    if (event.getUserId() != mCurrentUserFetcher.getCurrentUser()) {
                        Slogf.w(TAG, "The switched user is not the driver user, "
                                + "ignore refreshing display brightness");
                        return;
                    }
                }
                onDriverUserUpdate();
            };
        }

        @Override
        public void refreshDefaultDisplayBrightness() {
            refreshDisplayBrightness(DEFAULT_DISPLAY);
        }

        @Override
        public void refreshDisplayBrightness(int displayId) {
            CarPowerManagementService carPowerManagementService = null;
            int mainDisplayIdForDriver;
            synchronized (mLock) {
                carPowerManagementService = mCarPowerManagementService;
            }
            if (carPowerManagementService == null) {
                Slogf.e(CarLog.TAG_POWER, "Could not set brightness: "
                        + "no CarPowerManagementService");
                return;
            }
            if (Flags.multiDisplayBrightnessControl()
                    || UserManagerHelper.isVisibleBackgroundUsersSupported(mUserManager)) {
                setDisplayBrightnessThroughVhal(carPowerManagementService, displayId);
            } else {
                setDisplayBrightnessThroughVhalLegacy(carPowerManagementService);
            }
        }

        private void setDisplayBrightnessThroughVhal(
                CarPowerManagementService carPowerManagementService, int displayId) {
            float displayManagerBrightness = DisplayManagerHelper.getBrightness(
                    mContext, displayId);
            if (hasRecentlySetBrightness(displayManagerBrightness, displayId)) {
                return;
            }
            int linear = BrightnessUtils.brightnessFloatToInt(displayManagerBrightness);
            int gamma = convertLinearToGamma(linear, mMinimumBacklight, mMaximumBacklight);
            int percentBright = convertGammaToPercentBright(gamma);

            Slogf.i(TAG, "Refreshing percent brightness(from display %d) to %d", displayId,
                    percentBright);
            carPowerManagementService.sendDisplayBrightness(displayId, percentBright);
        }

        private void setDisplayBrightnessThroughVhalLegacy(
                CarPowerManagementService carPowerManagementService) {
            int gamma = GAMMA_SPACE_MAX;
            try {
                int linear = mSettings.getIntSystem(getContentResolverForUser(mContext,
                        UserHandle.CURRENT.getIdentifier()), System.SCREEN_BRIGHTNESS);
                if (hasRecentlySetBrightness(linear)) {
                    return;
                }
                gamma = convertLinearToGamma(linear, mMinimumBacklight, mMaximumBacklight);
            } catch (SettingNotFoundException e) {
                Slogf.e(CarLog.TAG_POWER, "Could not get SCREEN_BRIGHTNESS: ", e);
            }
            int percentBright = convertGammaToPercentBright(gamma);

            Slogf.i(TAG, "Refreshing percent brightness(from Setting) to %d", percentBright);
            carPowerManagementService.sendDisplayBrightnessLegacy(percentBright);
        }

        private static int convertGammaToPercentBright(int gamma) {
            return (gamma * 100 + ((GAMMA_SPACE_MAX + 1) / 2)) / GAMMA_SPACE_MAX;
        }

        private void handleDisplayChanged(int displayId) {
            refreshDisplayBrightness(displayId);
            boolean isOn = isDisplayOn(displayId);
            CarPowerManagementService service;
            synchronized (mLock) {
                boolean state = mDisplayStateSet.get(displayId, false);
                if (state == isOn) { // same as what is set
                    return;
                }
                service = mCarPowerManagementService;
            }
            service.handleDisplayChanged(displayId, isOn);
        }

        private boolean isDisplayOn(int displayId) {
            Display disp = mDisplayManager.getDisplay(displayId);
            if (disp == null) {
                return false;
            }
            return disp.getState() == Display.STATE_ON;
        }

        @Override
        public void onDisplayBrightnessChangeFromVhal(int displayId, int percentBright) {
            int gamma = (percentBright * GAMMA_SPACE_MAX + 50) / 100;
            int linear = convertGammaToLinear(gamma, mMinimumBacklight, mMaximumBacklight);
            if (Flags.multiDisplayBrightnessControl()
                    || UserManagerHelper.isVisibleBackgroundUsersSupported(mUserManager)) {
                Slogf.i(TAG, "set brightness: %d", percentBright);
                float displayManagerBrightness = BrightnessUtils.brightnessIntToFloat(linear);
                addRecentlySetBrightness(displayManagerBrightness, displayId);
                DisplayManagerHelper.setBrightness(mContext, displayId, displayManagerBrightness);
            } else {
                addRecentlySetBrightness(linear);
                mSettings.putIntSystem(
                        getContentResolverForUser(mContext, UserHandle.CURRENT.getIdentifier()),
                        System.SCREEN_BRIGHTNESS,
                        linear);
            }
        }

        @Override
        public void init(CarPowerManagementService carPowerManagementService,
                CarUserService carUserService) {
            synchronized (mLock) {
                mCarPowerManagementService = carPowerManagementService;
                mCarUserService = carUserService;
            }
        }

        @Override
        public void startDisplayStateMonitoring() {
            Slogf.i(TAG, "Starting to monitor display state change");
            CarPowerManagementService carPowerManagementService;
            CarUserService carUserService;
            synchronized (mLock) {
                carPowerManagementService = mCarPowerManagementService;
                carUserService = mCarUserService;
            }
            UserLifecycleEventFilter userSwitchingEventFilter =
                    new UserLifecycleEventFilter.Builder()
                            .addEventType(USER_LIFECYCLE_EVENT_TYPE_SWITCHING).build();
            carUserService.addUserLifecycleListener(userSwitchingEventFilter,
                    mUserLifecycleListener);
            if (Flags.multiDisplayBrightnessControl()
                    || UserManagerHelper.isVisibleBackgroundUsersSupported(mUserManager)) {
                DisplayManagerHelper.registerDisplayListener(mContext, mDisplayListener,
                        carPowerManagementService.getHandler(),
                        DisplayManagerHelper.EVENT_FLAG_DISPLAY_ADDED
                                | DisplayManagerHelper.EVENT_FLAG_DISPLAY_REMOVED
                                | DisplayManagerHelper.EVENT_FLAG_DISPLAY_CHANGED,
                        DisplayManagerHelper.EVENT_FLAG_DISPLAY_BRIGHTNESS);
            } else {
                getContentResolverForUser(mContext, UserHandle.ALL.getIdentifier())
                        .registerContentObserver(mSettings.getUriForSystem(
                                System.SCREEN_BRIGHTNESS), false, mBrightnessObserver);
            }

            initDisplayStateOnce();
            refreshAllDisplaysBrightness();
        }

        @Override
        public void stopDisplayStateMonitoring() {
            CarUserService carUserService;
            synchronized (mLock) {
                carUserService = mCarUserService;
            }
            carUserService.removeUserLifecycleListener(mUserLifecycleListener);
            if (Flags.multiDisplayBrightnessControl()
                    || UserManagerHelper.isVisibleBackgroundUsersSupported(mUserManager)) {
                mDisplayManager.unregisterDisplayListener(mDisplayListener);
            } else {
                getContentResolverForUser(mContext, UserHandle.ALL.getIdentifier())
                        .unregisterContentObserver(mBrightnessObserver);
            }
        }

        @Override
        public void setDisplayState(int displayId, boolean on) {
            CarPowerManagementService carPowerManagementService;
            synchronized (mLock) {
                carPowerManagementService = mCarPowerManagementService;
                if (on && carPowerManagementService != null
                        && !carPowerManagementService.canTurnOnDisplay(displayId)) {
                    Slogf.i(CarLog.TAG_POWER, "ignore turning on display %d because "
                            + "CarPowerManagementService doesn't support it", displayId);
                    return;
                }
                mDisplayStateSet.put(displayId, on);
            }
            if (on) {
                mWakeLockInterface.switchToFullWakeLock(displayId);
                Slogf.i(CarLog.TAG_POWER, "on display %d, obtain full wake lock", displayId);
            } else {
                mWakeLockInterface.switchToPartialWakeLock(displayId);
                Slogf.i(CarLog.TAG_POWER, "off display %d, obtain partial wake lock", displayId);
                PowerManagerHelper.goToSleep(mContext, displayId, SystemClock.uptimeMillis());
            }
            if (carPowerManagementService != null) {
                carPowerManagementService.handleDisplayChanged(displayId, on);
            }
        }

        @Override
        public void setAllDisplayState(boolean on) {
            IntArray displayIds;
            synchronized (mLock) {
                displayIds = getDisplayIdsLocked();
            }
            // setDisplayState has a binder call to system_server. Should not wrap setDisplayState
            // with a lock.
            for (int i = 0; i < displayIds.size(); i++) {
                int displayId = displayIds.get(i);
                try {
                    setDisplayState(displayId, on);
                } catch (IllegalArgumentException e) {
                    Slogf.w(TAG, "Cannot set display(%d) state(%b)", displayId, on);
                }
            }
        }

        @Override
        public boolean isAnyDisplayEnabled() {
            synchronized (mLock) {
                IntArray displayIds = getDisplayIdsLocked();
                for (int i = 0; i < displayIds.size(); i++) {
                    if (isDisplayEnabled(displayIds.get(i))) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean isDisplayEnabled(int displayId) {
            return isDisplayOn(displayId);
        }

        // Gets the initial display state (on/off) if not already initiated.
        // Returns true if this is the first init.
        private boolean initDisplayStateOnce() {
            synchronized (mLock) {
                if (mDisplayStateInitiated) {
                    return false;
                }
                for (Display display : mDisplayManager.getDisplays()) {
                    int displayId = display.getDisplayId();
                    boolean isDisplayOn = isDisplayOn(displayId);
                    mDisplayStateSet.put(displayId, isDisplayOn);
                    Slogf.d(TAG, "Initial display state: " + displayId + "=" + isDisplayOn);
                }
                mDisplayStateInitiated = true;
            }
            return true;
        }

        @GuardedBy("mLock")
        private IntArray getDisplayIdsLocked() {
            IntArray displayIds = new IntArray();
            if (mDisplayStateInitiated) {
                for (int i = 0; i < mDisplayStateSet.size(); i++) {
                    displayIds.add(mDisplayStateSet.keyAt(i));
                }
                return displayIds;
            }
            Slogf.d(TAG, "Display state not initiated yet, use displayManager");
            for (Display display : mDisplayManager.getDisplays()) {
                int displayId = display.getDisplayId();
                displayIds.add(displayId);
            }
            return displayIds;
        }

        // When the driver users is updated, the brightness settings associated with the driver
        // user changes. As a result, all display's brightness should be refreshed to represent
        // the new driver user's settings.
        private void onDriverUserUpdate() {
            synchronized (mLock) {
                if (mCarPowerManagementService == null) {
                    // CarPowerManagementService is not connected yet
                    return;
                }
            }
            if (Flags.multiDisplayBrightnessControl()) {
                refreshAllDisplaysBrightness();
            } else {
                refreshDefaultDisplayBrightness();
            }
        }

        private void refreshAllDisplaysBrightness() {
            for (Display display : mDisplayManager.getDisplays()) {
                int displayId = display.getDisplayId();
                int displayType = mDisplayHelper.getType(display);
                if (displayType == DisplayHelper.TYPE_VIRTUAL
                        || displayType == DisplayHelper.TYPE_OVERLAY) {
                    Slogf.i(TAG,
                            "Ignore refreshDisplayBrightness for virtual or overlay display: "
                            + displayId);
                    continue;
                }
                refreshDisplayBrightness(displayId);
            }
        }

        private boolean hasRecentlySetBrightness(float brightness, int displayId) {
            synchronized (mLock) {
                for (int i = 0; i < mRecentlySetBrightnessForDisplayId.size(); i++) {
                    if (isSameBrightnessForDisplayId(mRecentlySetBrightnessForDisplayId.get(i),
                            brightness, displayId)) {
                        Slogf.v(TAG,
                                "Ignore brightness change from DisplayManager, brightness=%f, "
                                + "displayId=%d, same as recently change set to DisplayManager",
                                brightness, displayId);
                        return true;
                    }
                }
            }
            return false;
        }

        private void addRecentlySetBrightness(float brightness, int displayId) {
            synchronized (mLock) {
                mRecentlySetBrightnessForDisplayId.add(
                        new BrightnessForDisplayId(brightness, displayId));
                mHandler.postDelayed(() -> {
                    synchronized (mLock) {
                        mRecentlySetBrightnessForDisplayId.removeFirst();
                    }
                }, PREVENT_LOOP_REQUEST_TIME_WINDOW_MS);
            }
        }

        private boolean hasRecentlySetBrightness(int brightness) {
            synchronized (mLock) {
                for (int i = 0; i < mRecentlySetGlobalBrightness.size(); i++) {
                    if (mRecentlySetGlobalBrightness.get(i) == brightness) {
                        Slogf.v(TAG,
                                "Ignore brightness change from System Settings, brightness=%d"
                                + ", same as recently change set to System Settings", brightness);
                        return true;
                    }
                }
                return false;
            }
        }

        @GuardedBy("mLock")
        private void addRecentlySetBrightness(int brightness) {
            synchronized (mLock) {
                mRecentlySetGlobalBrightness.add(brightness);
                mHandler.postDelayed(() -> {
                    synchronized (mLock) {
                        mRecentlySetGlobalBrightness.removeFirst();
                    }
                }, PREVENT_LOOP_REQUEST_TIME_WINDOW_MS);
            }
        }

        private boolean isSameBrightnessForDisplayId(BrightnessForDisplayId toCheck,
                float brightness, int displayId) {
            return toCheck.brightness() == brightness && toCheck.displayId() == displayId;
        }
    }
}
