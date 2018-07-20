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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.android.car.CarPowerManagementService;
import com.android.car.CarPowerManagementService.PowerEventProcessingHandler;
import com.android.car.CarPowerManagementService.PowerServiceEventListener;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Main controller for GarageMode. It controls all the flows of GarageMode and defines the sequence
 */
public class Controller implements PowerEventProcessingHandler, PowerServiceEventListener {
    private static final Logger LOG = new Logger("Controller");

    private final CommandHandler mCommandHandler;
    private final CarPowerManagementService mCarPowerManagementService;
    @VisibleForTesting protected final WakeupPolicy mWakeupPolicy;
    private final DeviceIdleControllerWrapper mDeviceIdleController;

    private boolean mInGarageMode;
    private boolean mGarageModeEnabled;
    private int mMaintenanceTimeout;

    protected enum Commands {
        EXIT_GARAGE_MODE,
        SET_MAINTENANCE_ACTIVITY
    }

    public Controller(
            Context context,
            CarPowerManagementService carPowerManagementService,
            Looper looper) {
        this(
                context,
                carPowerManagementService,
                looper,
                null,
                null,
                null);
    }

    public Controller(
            Context context,
            CarPowerManagementService carPowerManagementService,
            Looper looper,
            WakeupPolicy wakeupPolicy,
            CommandHandler commandHandler,
            DeviceIdleControllerWrapper deviceIdleControllerWrapper) {
        mCarPowerManagementService = carPowerManagementService;
        mInGarageMode = false;
        if (commandHandler == null) {
            mCommandHandler = new CommandHandler(looper);
        } else {
            mCommandHandler = commandHandler;
        }
        if (wakeupPolicy == null) {
            mWakeupPolicy = WakeupPolicy.initFromResources(context);
        } else {
            mWakeupPolicy = wakeupPolicy;
        }
        if (deviceIdleControllerWrapper == null) {
            mDeviceIdleController = new DeviceIdleControllerWrapper(mCommandHandler);
        } else {
            mDeviceIdleController = deviceIdleControllerWrapper;
        }
    }

    @Override
    public long onPrepareShutdown(boolean shuttingDown) {
        if (mGarageModeEnabled) {
            initiateGarageMode();
            return mMaintenanceTimeout;
        } else {
            LOG.d("GarageMode is disabled from settings. Skipping prepareShutdown sequence");
            return 0;
        }
    }

    @Override
    public void onPowerOn(boolean displayOn) {
        if (!mGarageModeEnabled) {
            LOG.d("GarageMode is disabled from settings. Skipping powerOn sequence");
            return;
        }
        // This call may happen when user entered car.
        // Temporarily assume that whenever user enters the car, screen will be turned on.
        // In that case, we can use display state to determine if we should exit GarageMode
        if (displayOn) {
            resetGarageMode();
        }
    }

    @Override
    public int getWakeupTime() {
        if (!mGarageModeEnabled) {
            LOG.d("GarageMode is disabled from settings. No wake up times to return");
            return 0;
        }
        return mWakeupPolicy.getNextWakeUpInterval();
    }

    @Override
    public void onSleepExit() {
        // ignored
    }

    @Override
    public void onSleepEntry() {
        mInGarageMode = false;
    }

    @Override
    public void onShutdown() {
        // We are not considering restorations from shutdown state for now. Thus, ignoring
    }

    public int getMaintenanceTimeout() {
        return mMaintenanceTimeout;
    }
    public void setMaintenanceTimeout(int maintenanceTimeout) {
        mMaintenanceTimeout = maintenanceTimeout;
    }
    public boolean isGarageModeEnabled() {
        return mGarageModeEnabled;
    }
    public boolean isGarageModeInProgress() {
        return mInGarageMode;
    }

    /** Starts the GarageMode listeners */
    public void start() {
        mDeviceIdleController.registerListener();
        mCarPowerManagementService.registerPowerEventProcessingHandler(this);
    }

    /** Stops GarageMode listeners */
    public void stop() {
        mDeviceIdleController.unregisterListener();
    }

    /**
     * Initiates GarageMode flow and returns the timeout that PowerManager needs to wait
     * before proceeding the shutdown
     */
    public void initiateGarageMode() {
        LOG.d("System is going to shutdown. Initiating GarageMode ...");
        if (!mGarageModeEnabled) {
            LOG.d("GarageMode is disabled from settings. Skipping flow ...");
            return;
        }
        mInGarageMode = true;
        // Increment WakeupPolicy index each time entering the GarageMode
        mWakeupPolicy.incrementCounter();
        // Schedule GarageMode exit, that will kick in if there updates are taking too long
        mCommandHandler.sendMessageDelayed(
                mCommandHandler.obtainMessage(Commands.EXIT_GARAGE_MODE.ordinal()),
                mMaintenanceTimeout);
    }

    /**
     * Enables GarageMode
     */
    public void enableGarageMode() {
        mGarageModeEnabled = true;
    }

    /**
     * Disables GarageMode
     */
    public void disableGarageMode() {
        mGarageModeEnabled = false;
    }

    /**
     * Resets GarageMode
     */
    public void resetGarageMode() {
        mInGarageMode = false;
        mWakeupPolicy.resetCounter();
    }

    private synchronized void maintenanceStarted() {
        LOG.d("DeviceIdleController reported that maintenance is started");
        // Since we expect maintenance to kick in, we do nothing here for now
    }

    private synchronized void maintenanceFinished() {
        LOG.d("DeviceIdleController reported that maintenance is finished");
        // Maintenance is now finished. Meaning we can continue shutting down the system
        mInGarageMode = false;
        mCarPowerManagementService.notifyPowerEventProcessingCompletion(this);
    }

    protected class CommandHandler extends Handler {
        CommandHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (Commands.values()[msg.what]) {
                case EXIT_GARAGE_MODE:
                    mCarPowerManagementService
                            .notifyPowerEventProcessingCompletion(Controller.this);
                    break;
                case SET_MAINTENANCE_ACTIVITY:
                    synchronized (Controller.this) {
                        boolean active = msg.arg1 == 1;
                        if (active) {
                            maintenanceStarted();
                        } else {
                            maintenanceFinished();
                        }
                    }
                    break;
            }
        }
    }
}
