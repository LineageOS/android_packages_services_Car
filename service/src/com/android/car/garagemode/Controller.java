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

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.android.car.CarPowerManagementService;
import com.android.car.CarPowerManagementService.PowerEventProcessingHandler;
import com.android.car.CarPowerManagementService.PowerServiceEventListener;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Main controller for GarageMode. It controls all the flows of GarageMode and defines the logic.
 */
public class Controller implements PowerEventProcessingHandler, PowerServiceEventListener {

    private static final Logger LOG = new Logger("Controller");

    /**
     * Setting large number, since we expect that GarageMode will exit early if running jobs
     * that have idleness constraint finished.
     */
    @VisibleForTesting
    static final int GARAGE_MODE_TIMEOUT_SEC = 86400; // One day

    private final CarPowerManagementService mCarPowerManagementService;
    @VisibleForTesting
    protected final WakeupPolicy mWakeupPolicy;
    private final GarageMode mGarageMode;
    private final Handler mHandler;
    private final Context mContext;

    public Controller(
            Context context, CarPowerManagementService carPowerManagementService, Looper looper) {
        this(context, carPowerManagementService, looper, null, null, null);
    }

    public Controller(
            Context context,
            CarPowerManagementService carPowerManagementService,
            Looper looper,
            WakeupPolicy wakeupPolicy,
            Handler handler,
            GarageMode garageMode) {
        mContext = context;
        mCarPowerManagementService = carPowerManagementService;
        mHandler = (handler == null ? new Handler(looper) : handler);
        mWakeupPolicy =
                (wakeupPolicy == null ? WakeupPolicy.initFromResources(context) : wakeupPolicy);
        mGarageMode = (garageMode == null ? new GarageMode(this) : garageMode);
    }

    /**
     * We are controlling PowerManager through this method. PowerManager wont sleep until we return
     * 0 as a response to this method call
     *
     * @param shuttingDown whether system is shutting down or not (= sleep entry).
     * @return integer that represents amount of seconds PowerManager should wait before checking
     * again. If we return 0, PowerManager will proceed to shutdown.
     */
    @Override
    public long onPrepareShutdown(boolean shuttingDown) {
        LOG.d("Received onPrepareShutdown() signal from CPMS.\nInitiating GarageMode ...");
        initiateGarageMode();
        return GARAGE_MODE_TIMEOUT_SEC;
    }

    /**
     * When car went into full on mode, this method will be called and GarageMode should get the
     * system out of idle state and cancel jobs that depend on idleness
     */
    @Override
    public void onPowerOn(boolean displayOn) {
        LOG.d("Received onPowerOn() signal from CPMS. Resetting GarageMode");
        // Car is going into full on mode, reset the GarageMode
        resetGarageMode();
    }

    /**
     * When we are done with GarageMode and completed running JobScheduler jobs, PowerManager
     * will call this method to find out when to wake up next time.
     *
     * @return integer that represents amount of seconds after which car will wake up again
     */
    @Override
    public int getWakeupTime() {
        LOG.d("Received getWakeupTime() signal from CPMS");
        return mWakeupPolicy.getNextWakeUpInterval();
    }

    /**
     * When PowerManager exits suspend to RAM, it calls this method to notify subscribed components
     */
    @Override
    public void onSleepExit() {
        LOG.d("Received onSleepExit() signal from CPMS");
        // ignored
    }

    /**
     * When PowerManager is actually going into suspend to RAM, it will call this method.
     * If GarageMode did not complete before this time, it means there are some other reasons
     * (like low battery) for PowerManager to shutdown early.
     */
    @Override
    public void onSleepEntry() {
        LOG.d("Received onSleepEntry() signal from CPMS");
        // We are exiting GarageMode at this time, since onPrepareShutdown() sequence from
        // CarPowerManagementService is completed.
        mGarageMode.exitGarageMode();
    }

    /**
     * When PowerManager is going cold (full shutdown) this method will be called. Since we don't
     * have much to do in GarageMode in this case, we simply ignore this.
     */
    @Override
    public void onShutdown() {
        LOG.d("Received onShutdown() signal from CPMS");
        // We are not considering restorations from shutdown state for now. Thus, ignoring
    }

    /**
     * @return boolean whether any jobs are currently in running that GarageMode cares about
     */
    boolean isGarageModeActive() {
        return mGarageMode.isGarageModeActive();
    }

    /**
     * Starts the GarageMode listeners
     */
    void start() {
        LOG.d("Received start() command. Starting to listen to CPMS");
        mCarPowerManagementService.registerPowerEventProcessingHandler(this);
    }

    /**
     * Stops GarageMode listeners
     */
    void stop() {
        LOG.d("Received stop() command. Finishing ...");
        // There is no ability in CPMS to unregister power event processing handlers
    }

    /**
     * Notifies CarPowerManagementService that GarageMode is completed and it can proceed with
     * shutting down or putting system into suspend RAM
     */
    synchronized void notifyGarageModeEndToPowerManager() {
        mCarPowerManagementService.notifyPowerEventProcessingCompletion(Controller.this);
    }

    /**
     * Wrapper method to send a broadcast
     *
     * @param i intent that contains broadcast data
     */
    void sendBroadcast(Intent i) {
        mContext.sendBroadcast(i);
    }

    /**
     * @return JobSchedulerService instance
     */
    JobScheduler getJobSchedulerService() {
        return (JobScheduler) mContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    /**
     * @return Handler instance used by controller
     */
    Handler getHandler() {
        return mHandler;
    }

    /**
     * Initiates GarageMode flow which will set the system idleness to true and will start
     * monitoring jobs which has idleness constraint enabled.
     */
    void initiateGarageMode() {
        mGarageMode.enterGarageMode();
        // Increment WakeupPolicy index each time entering the GarageMode
        mWakeupPolicy.incrementCounter();
        // Schedule GarageMode exit, that will kick in if updates are taking too long
        mHandler.postDelayed(() -> mGarageMode.exitGarageMode(), GARAGE_MODE_TIMEOUT_SEC);
    }

    /**
     * Resets GarageMode.
     */
    void resetGarageMode() {
        mGarageMode.exitGarageMode();
        mWakeupPolicy.resetCounter();
    }
}