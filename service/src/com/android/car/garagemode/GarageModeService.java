/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.car.settings.GarageModeSettingsObserver.GARAGE_MODE_ENABLED_URI;
import static android.car.settings.GarageModeSettingsObserver.GARAGE_MODE_MAINTENANCE_WINDOW_URI;
import static android.car.settings.GarageModeSettingsObserver.GARAGE_MODE_WAKE_UP_TIME_URI;

import android.car.settings.CarSettings;
import android.car.settings.GarageModeSettingsObserver;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.provider.Settings;

import com.android.car.CarPowerManagementService;
import com.android.car.CarServiceBase;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * Controls car garage mode.
 *
 * Car garage mode is a time window for the car to do maintenance work when the car is not in use.
 * The {@link com.android.car.garagemode.GarageModeService} interacts with
 * {@link com.android.car.CarPowerManagementService} to start and end garage mode.
 * A {@link WakeupPolicy} defines when the garage mode should start and how long it should last.
 */
public class GarageModeService implements CarServiceBase {
    private static final Logger LOG = new Logger("Service");

    private final Context mContext;
    private final CarPowerManagementService mCarPowerManagementService;
    private final Controller mController;

    private GarageModeSettingsObserver mSettingsObserver;

    public GarageModeService(
            Context context, CarPowerManagementService carPowerManagementService) {
        this(
                context,
                carPowerManagementService,
                new Controller(context, carPowerManagementService, Looper.myLooper()),
                null);
    }

    @VisibleForTesting
    protected GarageModeService(
            Context context,
            CarPowerManagementService carPowerManagementService,
            Controller controller,
            GarageModeSettingsObserver settingsObserver) {
        mContext = context;
        mCarPowerManagementService = carPowerManagementService;
        mController = controller;
        if (settingsObserver == null) {
            mSettingsObserver = new GarageModeSettingsObserver(mContext, null) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    onSettingsChangedInternal(uri);
                }
            };
        } else {
            mSettingsObserver = settingsObserver;
        }
    }

    @Override
    public void init() {
        readFromSettingsLocked(
                CarSettings.Global.KEY_GARAGE_MODE_MAINTENANCE_WINDOW,
                CarSettings.Global.KEY_GARAGE_MODE_ENABLED);
        mController.start();
        mSettingsObserver.register();
    }

    @Override
    public void release() {
        mSettingsObserver.unregister();
        mController.stop();
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("GarageModeEnabled " + mController.isGarageModeEnabled());
        writer.println("GarageModeTimeWindow " + mController.getMaintenanceTimeout() + " ms");
    }

    // local methods

    /**
     * Returns {@link Controller} to hack enforce some flows.
     * @return controller instance
     */
    public Controller getController() {
        return mController;
    }

    @GuardedBy("this")
    private void readFromSettingsLocked(String... keys) {
        for (String key : keys) {
            switch (key) {
                case CarSettings.Global.KEY_GARAGE_MODE_ENABLED:
                    int enabled = Settings.Global.getInt(mContext.getContentResolver(), key, 1);
                    if (enabled == 1) {
                        mController.enableGarageMode();
                    } else {
                        mController.disableGarageMode();
                    }
                    break;
                case CarSettings.Global.KEY_GARAGE_MODE_MAINTENANCE_WINDOW:
                    int timeout = Settings.Global.getInt(
                            mContext.getContentResolver(),
                            key,
                            CarSettings.DEFAULT_GARAGE_MODE_MAINTENANCE_WINDOW);
                    mController.setMaintenanceTimeout(timeout);
                    break;
                default:
                    LOG.e("Unknown setting key " + key);
            }
        }
    }

    private synchronized void onSettingsChangedInternal(Uri uri) {
        LOG.d("Content Observer onChange: " + uri);
        if (uri.equals(GARAGE_MODE_ENABLED_URI)) {
            readFromSettingsLocked(CarSettings.Global.KEY_GARAGE_MODE_ENABLED);
        } else if (uri.equals(GARAGE_MODE_WAKE_UP_TIME_URI)) {
            readFromSettingsLocked(CarSettings.Global.KEY_GARAGE_MODE_WAKE_UP_TIME);
        } else if (uri.equals(GARAGE_MODE_MAINTENANCE_WINDOW_URI)) {
            readFromSettingsLocked(CarSettings.Global.KEY_GARAGE_MODE_MAINTENANCE_WINDOW);
        }
        LOG.d(String.format(
                "onSettingsChanged %s. enabled: %s, timeout: %d",
                uri, mController.isGarageModeEnabled(), mController.getMaintenanceTimeout()));
    }
}
