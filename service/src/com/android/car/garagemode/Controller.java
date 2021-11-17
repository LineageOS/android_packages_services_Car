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

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.builtin.util.Slogf;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListenerWithCompletion;
import android.car.hardware.power.CarPowerManager.CompletablePowerStateChangeFuture;;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;

import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.systeminterface.SystemInterface;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * Main controller for GarageMode. It controls all the flows of GarageMode and defines the logic.
 */
public class Controller implements CarPowerStateListenerWithCompletion {

    private static final String TAG = CarLog.tagFor(GarageMode.class) + "_"
            + Controller.class.getSimpleName();

    private final GarageMode mGarageMode;
    private final Handler mHandler;
    private final Context mContext;
    private CarPowerManager mCarPowerManager;

    public Controller(Context context, Looper looper) {
        this(context, looper, null, null);
    }

    public Controller(
            Context context,
            Looper looper,
            Handler handler,
            GarageMode garageMode) {
        mContext = context;
        mHandler = (handler == null ? new Handler(looper) : handler);
        mGarageMode = (garageMode == null ? new GarageMode(context, this) : garageMode);
    }

    /** init */
    public void init() {
        mCarPowerManager = CarLocalServices.createCarPowerManager(mContext);
        mCarPowerManager.setListenerWithCompletion(mContext.getMainExecutor(), Controller.this);
        mGarageMode.init();
    }

    /** release */
    public void release() {
        mCarPowerManager.clearListener();
        mGarageMode.release();
    }

    @Override
    public void onStateChanged(int state, CompletablePowerStateChangeFuture future) {
        switch (state) {
            case CarPowerManager.STATE_SHUTDOWN_CANCELLED:
                Slogf.d(TAG, "CPM state changed to SHUTDOWN_CANCELLED");
                handleShutdownCancelled();
                break;
            case CarPowerManager.STATE_SHUTDOWN_ENTER:
                Slogf.d(TAG, "CPM state changed to SHUTDOWN_ENTER");
                handleShutdownEnter(future);
                break;
            case CarPowerManager.STATE_PRE_SHUTDOWN_PREPARE:
                Slogf.d(TAG, "CPM state changed to PRE_SHUTDOWN_PREPARE");
                handlePreShutdownPrepare(future);
                break;
            case CarPowerManager.STATE_SHUTDOWN_PREPARE:
                Slogf.d(TAG, "CPM state changed to SHUTDOWN_PREPARE");
                handleShutdownPrepare(future);
                break;
            case CarPowerManager.STATE_SUSPEND_ENTER:
                Slogf.d(TAG, "CPM state changed to SUSPEND_ENTER");
                handleSuspendEnter(future);
                break;
            case CarPowerManager.STATE_SUSPEND_EXIT:
                Slogf.d(TAG, "CPM state changed to SUSPEND_EXIT");
                handleSuspendExit();
                break;
            case CarPowerManager.STATE_HIBERNATION_ENTER:
                Slogf.d(TAG, "CPM state changed to HIBERNATION_ENTER");
                handleHibernationEnter(future);
                break;
            case CarPowerManager.STATE_HIBERNATION_EXIT:
                Slogf.d(TAG, "CPM state changed to HIBERNATION_EXIT");
                handleHibernationExit();
                break;
            case CarPowerManager.STATE_POST_SHUTDOWN_ENTER:
                Slogf.d(TAG, "CPM state changed to POST_SHUTDOWN_ENTER");
                handlePostShutdownEnter(future);
                break;
            case CarPowerManager.STATE_POST_SUSPEND_ENTER:
                Slogf.d(TAG, "CPM state changed to POST_SUSPEND_ENTER");
                handlePostSuspendEnter(future);
                break;
            case CarPowerManager.STATE_POST_HIBERNATION_ENTER:
                Slogf.d(TAG, "CPM state changed to POST_HIBERNATION_ENTER");
                handlePostHibernationEnter(future);
                break;
            default:
        }
    }

    /**
     * @return boolean whether any jobs are currently in running that GarageMode cares about
     */
    boolean isGarageModeActive() {
        return mGarageMode.isGarageModeActive();
    }

    /**
     * Prints Garage Mode's status, including what jobs it is waiting for
     */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    void dump(PrintWriter writer) {
        mGarageMode.dump(writer);
    }

    /**
     * Wrapper method to send a broadcast
     *
     * @param i intent that contains broadcast data
     */
    void sendBroadcast(Intent i) {
        SystemInterface systemInterface = CarLocalServices.getService(SystemInterface.class);
        Slogf.d(TAG, "Sending broadcast with action: %s", i.getAction());
        systemInterface.sendBroadcastAsUser(i, UserHandle.ALL);
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
    void initiateGarageMode(CompletablePowerStateChangeFuture future) {
        mGarageMode.enterGarageMode(future);
    }

    /**
     * Resets GarageMode.
     */
    void resetGarageMode() {
        mGarageMode.cancel();
    }

    @VisibleForTesting
    void finishGarageMode() {
        mGarageMode.finish();
    }

    @VisibleForTesting
    void setCarPowerManager(CarPowerManager cpm) {
        mCarPowerManager = cpm;
    }

    private void handleSuspendExit() {
        resetGarageMode();
    }

    private void handleSuspendEnter(CompletablePowerStateChangeFuture future) {
        resetGarageMode();
        // Garage Mode doesn't do more for suspend enter.
        if (future != null) future.complete();
    }

    private void handleShutdownEnter(CompletablePowerStateChangeFuture future) {
        resetGarageMode();
        // Garage Mode doesn't do more for shutdown enter.
        if (future != null) future.complete();
    }

    private void handlePreShutdownPrepare(CompletablePowerStateChangeFuture future) {
        // Garage Mode doesn't care about pre shutdown prepare.
        if (future != null) future.complete();
    }

    private void handleShutdownPrepare(CompletablePowerStateChangeFuture future) {
        initiateGarageMode(future);
    }

    private void handleShutdownCancelled() {
        resetGarageMode();
    }

    private void handleHibernationExit() {
        resetGarageMode();
    }

    private void handleHibernationEnter(CompletablePowerStateChangeFuture future) {
        resetGarageMode();
        // Garage Mode doesn't do more for hibernation enter.
        if (future != null) future.complete();
    }

    private void handlePostShutdownEnter(CompletablePowerStateChangeFuture future) {
        // Garage Mode doesn't do more for post shutdown enter.
        if (future != null) future.complete();
    }

    private void handlePostSuspendEnter(CompletablePowerStateChangeFuture future) {
        // Garage Mode doesn't do more for post suspend enter.
        if (future != null) future.complete();
    }

    private void handlePostHibernationEnter(CompletablePowerStateChangeFuture future) {
        // Garage Mode doesn't do more for post hibernation enter.
        if (future != null) future.complete();
    }
}
