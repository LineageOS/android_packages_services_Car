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
package com.android.car;

import android.car.Car;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.car.hardware.power.ICarPower;
import android.car.hardware.power.ICarPowerStateListener;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.android.car.hal.PowerHalService;
import com.android.car.hal.PowerHalService.PowerState;
import com.android.car.systeminterface.SystemInterface;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Power Management service class for cars. Controls the power states and interacts with other
 * parts of the system to ensure its own state.
 */
public class CarPowerManagementService extends ICarPower.Stub implements
        CarServiceBase, PowerHalService.PowerEventListener {
    private final Context mContext;
    private final PowerHalService mHal;
    private final SystemInterface mSystemInterface;
    private final PowerManagerCallbackList mPowerManagerListeners = new PowerManagerCallbackList();
    private final Map<IBinder, Integer> mPowerManagerListenerTokens = new ConcurrentHashMap<>();

    @GuardedBy("this")
    private PowerState mCurrentState;
    @GuardedBy("this")
    private Timer mTimer;
    @GuardedBy("this")
    private long mProcessingStartTime;
    @GuardedBy("this")
    private long mLastSleepEntryTime;
    @GuardedBy("this")
    private final LinkedList<PowerState> mPendingPowerStates = new LinkedList<>();
    @GuardedBy("this")
    private HandlerThread mHandlerThread;
    @GuardedBy("this")
    private PowerHandler mHandler;
    private int mBootReason;
    private boolean mShutdownOnNextSuspend = false;
    private int mNextWakeupSec;
    private int mTokenValue = 1;

    // TODO:  Make this OEM configurable.
    private static final int APP_EXTEND_MAX_MS = 86400000; // 1 day
    private final static int SHUTDOWN_POLLING_INTERVAL_MS = 2000;
    private final static int SHUTDOWN_EXTEND_MAX_MS = 5000;

    private class PowerManagerCallbackList extends RemoteCallbackList<ICarPowerStateListener> {
        /**
         * Old version of {@link #onCallbackDied(E, Object)} that
         * does not provide a cookie.
         */
        @Override
        public void onCallbackDied(ICarPowerStateListener listener) {
            Log.i(CarLog.TAG_POWER, "binderDied " + listener.asBinder());
            CarPowerManagementService.this.doUnregisterListener(listener);
        }
    }

    public CarPowerManagementService(
            Context context, PowerHalService powerHal, SystemInterface systemInterface) {
        mContext = context;
        mHal = powerHal;
        mSystemInterface = systemInterface;
    }

    /**
     * Create a dummy instance for unit testing purpose only. Instance constructed in this way
     * is not safe as members expected to be non-null are null.
     */
    @VisibleForTesting
    protected CarPowerManagementService() {
        mContext = null;
        mHal = null;
        mSystemInterface = null;
        mHandlerThread = null;
        mHandler = new PowerHandler(Looper.getMainLooper());
    }

    @Override
    public void init() {
        synchronized (this) {
            mHandlerThread = new HandlerThread(CarLog.TAG_POWER);
            mHandlerThread.start();
            mHandler = new PowerHandler(mHandlerThread.getLooper());
        }

        mHal.setListener(this);
        if (mHal.isPowerStateSupported()) {
            mHal.sendBootComplete();
            PowerState currentState = mHal.getCurrentPowerState();
            if (currentState != null) {
                onApPowerStateChange(currentState);
            } else {
                Log.w(CarLog.TAG_POWER, "Unable to get get current power state during "
                        + "initialization");
            }
        } else {
            Log.w(CarLog.TAG_POWER, "Vehicle hal does not support power state yet.");
            onApPowerStateChange(new PowerState(PowerHalService.STATE_ON_FULL, 0));
            mSystemInterface.switchToFullWakeLock();
        }
        mSystemInterface.startDisplayStateMonitoring(this);
    }

    @Override
    public void release() {
        HandlerThread handlerThread;
        synchronized (this) {
            releaseTimerLocked();
            mCurrentState = null;
            mHandler.cancelAll();
            handlerThread = mHandlerThread;
        }
        handlerThread.quitSafely();
        try {
            handlerThread.join(1000);
        } catch (InterruptedException e) {
            Log.e(CarLog.TAG_POWER, "Timeout while joining for handler thread to join.");
        }
        mSystemInterface.stopDisplayStateMonitoring();
        mPowerManagerListeners.kill();
        mPowerManagerListenerTokens.clear();
        mSystemInterface.releaseAllWakeLocks();
    }

    /**
     * Notifies earlier completion of power event processing. If some user modules need pre-shutdown
     * processing time, they will get up to #APP_EXTEND_MAX_MS to complete their tasks. Modules
     * are expected to finish earlier than that, and this call can be called in such case to trigger
     * shutdown without waiting further.
     */
    public void notifyPowerEventProcessingCompletion() {
        long processingTime = 0;
        synchronized (mPowerManagerListenerTokens) {
            if (!mPowerManagerListenerTokens.isEmpty()) {
                processingTime += APP_EXTEND_MAX_MS;
            }
        }
        long now = SystemClock.elapsedRealtime();
        long startTime;
        boolean shouldShutdown = true;
        PowerHandler powerHandler;
        synchronized (this) {
            startTime = mProcessingStartTime;
            if (mCurrentState == null) {
                return;
            }
            if (mCurrentState.mState != PowerHalService.STATE_SHUTDOWN_PREPARE) {
                return;
            }
            if (mCurrentState.canEnterDeepSleep() && !mShutdownOnNextSuspend) {
                shouldShutdown = false;
                if (mLastSleepEntryTime > mProcessingStartTime && mLastSleepEntryTime < now) {
                    // already slept
                    return;
                }
            }
            powerHandler = mHandler;
        }
        if ((startTime + processingTime) <= now) {
            Log.i(CarLog.TAG_POWER, "Processing all done");
            powerHandler.handleProcessingComplete(shouldShutdown);
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*PowerManagementService*");
        writer.print("mCurrentState:" + mCurrentState);
        writer.print(",mProcessingStartTime:" + mProcessingStartTime);
        writer.println(",mLastSleepEntryTime:" + mLastSleepEntryTime);
    }

    @Override
    public void onBootReasonReceived(int bootReason) {
        mBootReason = bootReason;
    }

    @Override
    public void onApPowerStateChange(PowerState state) {
        PowerHandler handler;
        synchronized (this) {
            mPendingPowerStates.addFirst(state);
            handler = mHandler;
        }
        handler.handlePowerStateChange();
    }

    private void doHandlePowerStateChange() {
        PowerState state = null;
        PowerHandler handler;
        synchronized (this) {
            state = mPendingPowerStates.peekFirst();
            mPendingPowerStates.clear();
            if (state == null) {
                return;
            }
            if (!needPowerStateChange(state)) {
                return;
            }
            // now real power change happens. Whatever was queued before should be all cancelled.
            releaseTimerLocked();
            handler = mHandler;
        }
        handler.cancelProcessingComplete();

        Log.i(CarLog.TAG_POWER, "Power state change:" + state);
        switch (state.mState) {
            case PowerHalService.STATE_ON_DISP_OFF:
                handleDisplayOff(state);
                break;
            case PowerHalService.STATE_ON_FULL:
                handleFullOn(state);
                break;
            case PowerHalService.STATE_SHUTDOWN_PREPARE:
                handleShutdownPrepare(state);
                break;
        }
    }

    private void handleDisplayOff(PowerState newState) {
        setCurrentState(newState);
        mSystemInterface.setDisplayState(false);
    }

    private void handleFullOn(PowerState newState) {
        setCurrentState(newState);
        mSystemInterface.setDisplayState(true);
    }

    private void handleShutdownPrepare(PowerState newState) {
        setCurrentState(newState);
        mSystemInterface.setDisplayState(false);;
        boolean shouldShutdown = true;
        if (mHal.isDeepSleepAllowed() && mSystemInterface.isSystemSupportingDeepSleep() &&
            newState.canEnterDeepSleep() && !mShutdownOnNextSuspend) {
            Log.i(CarLog.TAG_POWER, "starting sleep");
            shouldShutdown = false;
            doHandlePreprocessing(shouldShutdown);
            return;
        } else if (newState.canPostponeShutdown()) {
            Log.i(CarLog.TAG_POWER, "starting shutdown with processing");
            doHandlePreprocessing(shouldShutdown);
        } else {
            Log.i(CarLog.TAG_POWER, "starting shutdown immediately");
            synchronized (this) {
                releaseTimerLocked();
            }
            doHandleShutdown();
        }
    }

    @GuardedBy("this")
    private void releaseTimerLocked() {
        if (mTimer != null) {
            mTimer.cancel();
        }
        mTimer = null;
    }

    private void doHandlePreprocessing(boolean shuttingDown) {
        // Set time for powerManager events
        long processingTimeMs = sendPowerManagerEvent(shuttingDown);
        if (processingTimeMs > 0) {
            int pollingCount = (int)(processingTimeMs / SHUTDOWN_POLLING_INTERVAL_MS) + 1;
            Log.i(CarLog.TAG_POWER, "processing before shutdown expected for: "
                    + processingTimeMs + " ms, adding polling:" + pollingCount);
            synchronized (this) {
                mProcessingStartTime = SystemClock.elapsedRealtime();
                releaseTimerLocked();
                mTimer = new Timer();
                mTimer.scheduleAtFixedRate(
                        new ShutdownProcessingTimerTask(shuttingDown, pollingCount),
                        0 /*delay*/,
                        SHUTDOWN_POLLING_INTERVAL_MS);
            }
        } else {
            PowerHandler handler;
            synchronized (this) {
                handler = mHandler;
            }
            handler.handleProcessingComplete(shuttingDown);
        }
    }

    private long sendPowerManagerEvent(boolean shuttingDown) {
        long processingTimeMs = 0;
        int newState = shuttingDown ? CarPowerStateListener.SHUTDOWN_ENTER :
                                      CarPowerStateListener.SUSPEND_ENTER;
        synchronized (mPowerManagerListenerTokens) {
            mPowerManagerListenerTokens.clear();
            int i = mPowerManagerListeners.beginBroadcast();
            while (i-- > 0) {
                try {
                    ICarPowerStateListener listener = mPowerManagerListeners.getBroadcastItem(i);
                    listener.onStateChanged(newState, mTokenValue);
                    mPowerManagerListenerTokens.put(listener.asBinder(), mTokenValue);
                    mTokenValue++;
                } catch (RemoteException e) {
                    // Its likely the connection snapped. Let binder death handle the situation.
                    Log.e(CarLog.TAG_POWER, "onStateChanged calling failed: " + e);
                }
            }
            mPowerManagerListeners.finishBroadcast();
            if (!mPowerManagerListenerTokens.isEmpty()) {
                Log.i(CarLog.TAG_POWER,
                        "mPowerManagerListenerTokens not empty, add APP_EXTEND_MAX_MS");
                processingTimeMs += APP_EXTEND_MAX_MS;
            }
        }
        return processingTimeMs;
    }

    private void doHandleDeepSleep() {
        // keep holding partial wakelock to prevent entering sleep before enterDeepSleep call
        // enterDeepSleep should force sleep entry even if wake lock is kept.
        mSystemInterface.switchToPartialWakeLock();
        PowerHandler handler;
        synchronized (this) {
            handler = mHandler;
        }
        handler.cancelProcessingComplete();
        mHal.sendSleepEntry();
        synchronized (this) {
            mLastSleepEntryTime = SystemClock.elapsedRealtime();
        }
        if (!mSystemInterface.enterDeepSleep(mNextWakeupSec)) {
            // System did not suspend.  Need to shutdown
            // TODO:  Shutdown gracefully
            Log.e(CarLog.TAG_POWER, "Sleep did not succeed.  Need to shutdown");
        }
        // When we wake up, we reset the next wake up time and if no one will set it
        // System will suspend / shutdown forever.
        mNextWakeupSec = 0;
        mHal.sendSleepExit();
        // Notify applications
        int i = mPowerManagerListeners.beginBroadcast();
        while (i-- > 0) {
            try {
                ICarPowerStateListener listener = mPowerManagerListeners.getBroadcastItem(i);
                listener.onStateChanged(CarPowerStateListener.SUSPEND_EXIT, 0);
            } catch (RemoteException e) {
                // Its likely the connection snapped. Let binder death handle the situation.
                Log.e(CarLog.TAG_POWER, "onStateChanged calling failed: " + e);
            }
        }
        mPowerManagerListeners.finishBroadcast();

        if (mSystemInterface.isWakeupCausedByTimer()) {
            doHandlePreprocessing(false /*shuttingDown*/);
        } else {
            PowerState currentState = mHal.getCurrentPowerState();
            if (currentState != null && needPowerStateChange(currentState)) {
                onApPowerStateChange(currentState);
            } else { // power controller woke-up but no power state change. Just shutdown.
                Log.w(CarLog.TAG_POWER, "external sleep wake up, but no power state change:" +
                        currentState);
                doHandleShutdown();
            }
        }
    }

    private void doHandleNotifyPowerOn() {
        boolean displayOn = false;
        synchronized (this) {
            if (mCurrentState != null && mCurrentState.mState == PowerHalService.STATE_ON_FULL) {
                displayOn = true;
            }
        }
    }

    private boolean needPowerStateChange(PowerState newState) {
        synchronized (this) {
            if (mCurrentState != null && mCurrentState.equals(newState)) {
                return false;
            }
            return true;
        }
    }

    private void doHandleShutdown() {
        // now shutdown
        mHal.sendShutdownStart(mHal.isTimedWakeupAllowed() ? mNextWakeupSec : 0);
        mSystemInterface.shutdown();
    }

    private void doHandleProcessingComplete(boolean shutdownWhenCompleted) {
        synchronized (this) {
            releaseTimerLocked();
            if (!shutdownWhenCompleted && mLastSleepEntryTime > mProcessingStartTime) {
                // entered sleep after processing start. So this could be duplicate request.
                Log.w(CarLog.TAG_POWER, "Duplicate sleep entry request, ignore");
                return;
            }
        }
        if (shutdownWhenCompleted) {
            doHandleShutdown();
        } else {
            doHandleDeepSleep();
        }
    }

    private synchronized void setCurrentState(PowerState state) {
        mCurrentState = state;
    }

    @Override
    public void onDisplayBrightnessChange(int brightness) {
        PowerHandler handler;
        synchronized (this) {
            handler = mHandler;
        }
        handler.handleDisplayBrightnessChange(brightness);
    }

    private void doHandleDisplayBrightnessChange(int brightness) {
        mSystemInterface.setDisplayBrightness(brightness);
    }

    private void doHandleMainDisplayStateChange(boolean on) {
        Log.w(CarLog.TAG_POWER, "Unimplemented:  doHandleMainDisplayStateChange() - on = " + on);
    }

    public void handleMainDisplayChanged(boolean on) {
        PowerHandler handler;
        synchronized (this) {
            handler = mHandler;
        }
        handler.handleMainDisplayStateChange(on);
    }

    /**
     * Send display brightness to VHAL.
     * @param brightness value 0-100%
     */
    public void sendDisplayBrightness(int brightness) {
        mHal.sendDisplayBrightness(brightness);
    }

    public synchronized Handler getHandler() {
        return mHandler;
    }

    // Binder interface for CarPowerManager
    @Override
    public void registerListener(ICarPowerStateListener listener) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CAR_POWER);
        mPowerManagerListeners.register(listener);
    }

    @Override
    public void unregisterListener(ICarPowerStateListener listener) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CAR_POWER);
        doUnregisterListener(listener);
    }

    private void doUnregisterListener(ICarPowerStateListener listener) {
        boolean found = mPowerManagerListeners.unregister(listener);

        if (found) {
            // Remove outstanding token if there is one
            IBinder binder = listener.asBinder();
            synchronized (mPowerManagerListenerTokens) {
                if (mPowerManagerListenerTokens.containsKey(binder)) {
                    int token = mPowerManagerListenerTokens.get(binder);
                    finishedLocked(binder, token);
                }
            }
        }
    }

    @Override
    public void requestShutdownOnNextSuspend() {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CAR_POWER);
        mShutdownOnNextSuspend = true;
    }

    @Override
    public int getBootReason() {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CAR_POWER);
        // Return the most recent bootReason value
        return mBootReason;
    }

    @Override
    public void finished(ICarPowerStateListener listener, int token) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CAR_POWER);
        synchronized (mPowerManagerListenerTokens) {
            finishedLocked(listener.asBinder(), token);
        }
    }

    @Override
    public synchronized void scheduleNextWakeupTime(int seconds) {
        if (seconds < 0) {
            Log.w(CarLog.TAG_POWER, "Next wake up can not be in negative time. Ignoring!");
            return;
        }
        if (mNextWakeupSec == 0 || mNextWakeupSec > seconds) {
            mNextWakeupSec = seconds;
        } else {
            Log.d(CarLog.TAG_POWER, "Tried to schedule next wake up, but already had shorter "
                    + " scheduled time");
        }
    }

    private void finishedLocked(IBinder binder, int token) {
        int currentToken = mPowerManagerListenerTokens.get(binder);
        if (currentToken == token) {
            mPowerManagerListenerTokens.remove(binder);
            if (mPowerManagerListenerTokens.isEmpty() &&
                (mCurrentState.mState == PowerHalService.STATE_SHUTDOWN_PREPARE)) {
                // All apps are ready to shutdown/suspend.
                Log.i(CarLog.TAG_POWER,
                        "Apps are finished, call notifyPowerEventProcessingCompletion");
                notifyPowerEventProcessingCompletion();
            }
        }
    }

    private class PowerHandler extends Handler {

        private final int MSG_POWER_STATE_CHANGE = 0;
        private final int MSG_DISPLAY_BRIGHTNESS_CHANGE = 1;
        private final int MSG_MAIN_DISPLAY_STATE_CHANGE = 2;
        private final int MSG_PROCESSING_COMPLETE = 3;
        private final int MSG_NOTIFY_POWER_ON = 4;

        // Do not handle this immediately but with some delay as there can be a race between
        // display off due to rear view camera and delivery to here.
        private final long MAIN_DISPLAY_EVENT_DELAY_MS = 500;

        private PowerHandler(Looper looper) {
            super(looper);
        }

        private void handlePowerStateChange() {
            Message msg = obtainMessage(MSG_POWER_STATE_CHANGE);
            sendMessage(msg);
        }

        private void handleDisplayBrightnessChange(int brightness) {
            Message msg = obtainMessage(MSG_DISPLAY_BRIGHTNESS_CHANGE, brightness, 0);
            sendMessage(msg);
        }

        private void handleMainDisplayStateChange(boolean on) {
            removeMessages(MSG_MAIN_DISPLAY_STATE_CHANGE);
            Message msg = obtainMessage(MSG_MAIN_DISPLAY_STATE_CHANGE, Boolean.valueOf(on));
            sendMessageDelayed(msg, MAIN_DISPLAY_EVENT_DELAY_MS);
        }

        private void handleProcessingComplete(boolean shutdownWhenCompleted) {
            removeMessages(MSG_PROCESSING_COMPLETE);
            Message msg = obtainMessage(MSG_PROCESSING_COMPLETE, shutdownWhenCompleted ? 1 : 0, 0);
            sendMessage(msg);
        }

        private void handlePowerOn() {
            Message msg = obtainMessage(MSG_NOTIFY_POWER_ON);
            sendMessage(msg);
        }

        private void cancelProcessingComplete() {
            removeMessages(MSG_PROCESSING_COMPLETE);
        }

        private void cancelAll() {
            removeMessages(MSG_POWER_STATE_CHANGE);
            removeMessages(MSG_DISPLAY_BRIGHTNESS_CHANGE);
            removeMessages(MSG_MAIN_DISPLAY_STATE_CHANGE);
            removeMessages(MSG_PROCESSING_COMPLETE);
            removeMessages(MSG_NOTIFY_POWER_ON);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_POWER_STATE_CHANGE:
                    doHandlePowerStateChange();
                    break;
                case MSG_DISPLAY_BRIGHTNESS_CHANGE:
                    doHandleDisplayBrightnessChange(msg.arg1);
                    break;
                case MSG_MAIN_DISPLAY_STATE_CHANGE:
                    doHandleMainDisplayStateChange((Boolean) msg.obj);
                    break;
                case MSG_PROCESSING_COMPLETE:
                    doHandleProcessingComplete(msg.arg1 == 1);
                    break;
                case MSG_NOTIFY_POWER_ON:
                    doHandleNotifyPowerOn();
                    break;
            }
        }
    }

    private class ShutdownProcessingTimerTask extends TimerTask {
        private final boolean mShutdownWhenCompleted;
        private final int mExpirationCount;
        private int mCurrentCount;

        private ShutdownProcessingTimerTask(boolean shutdownWhenCompleted, int expirationCount) {
            mShutdownWhenCompleted = shutdownWhenCompleted;
            mExpirationCount = expirationCount;
            mCurrentCount = 0;
        }

        @Override
        public void run() {
            mCurrentCount++;
            if (mCurrentCount > mExpirationCount) {
                PowerHandler handler;
                synchronized (CarPowerManagementService.this) {
                    releaseTimerLocked();
                    handler = mHandler;
                }
                handler.handleProcessingComplete(mShutdownWhenCompleted);
            } else {
                mHal.sendShutdownPostpone(SHUTDOWN_EXTEND_MAX_MS);
            }
        }
    }
}
