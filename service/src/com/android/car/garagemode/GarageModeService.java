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

import android.content.Context;
import android.os.Looper;

import com.android.car.CarPowerManagementService;
import com.android.car.CarServiceBase;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * Main service container for car garage mode.
 *
 * GarageMode enables idle time in cars.
 * {@link com.android.car.garagemode.GarageModeService} registers itself as a user of
 * {@link com.android.car.CarPowerManagementService}, which then starts GarageMode flow.
 */
public class GarageModeService implements CarServiceBase {
    private static final Logger LOG = new Logger("Service");

    private final Context mContext;
    private final Controller mController;

    public GarageModeService(Context context, CarPowerManagementService carPowerManagementService) {
        this(context, carPowerManagementService, null);
    }

    @VisibleForTesting
    protected GarageModeService(
            Context context,
            CarPowerManagementService carPowerManagementService,
            Controller controller) {
        mContext = context;
        mController = (controller != null ? controller
                : new Controller(context, carPowerManagementService, Looper.myLooper()));
    }

    /**
     * Initializes GarageMode
     */
    @Override
    public void init() {
        mController.start();
    }

    /**
     * Cleans up GarageMode processes
     */
    @Override
    public void release() {
        mController.stop();
    }

    /**
     * Dumps useful information about GarageMode
     * @param writer
     */
    @Override
    public void dump(PrintWriter writer) {
        writer.println("GarageModeInProgress " + mController.isGarageModeActive());
    }

    /**
     * @return whether GarageMode is in progress. Used by {@link com.android.car.ICarImpl}.
     */
    public boolean isGarageModeActive() {
        return mController.isGarageModeActive();
    }

    /**
     * Forces GarageMode to start. Used by {@link com.android.car.ICarImpl}.
     */
    public void forceStartGarageMode() {
        mController.initiateGarageMode();
    }

    /**
     * Stops and resets the GarageMode. Used by {@link com.android.car.ICarImpl}.
     */
    public void stopAndResetGarageMode() {
        mController.resetGarageMode();
    }
}
