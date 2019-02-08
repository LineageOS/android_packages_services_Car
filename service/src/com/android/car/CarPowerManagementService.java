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
import android.hardware.automotive.vehicle.V2_0.VehicleApPowerStateReq;
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
    private CpmsState mCurrentState;
    @GuardedBy("this")
    private Timer mTimer;
    @GuardedBy("this")
    private long mProcessingStartTime;
    @GuardedBy("this")
    private long mLastSleepEntryTime;
    @GuardedBy("this")
    private final LinkedList<CpmsState> mPendingPowerStates = new LinkedList<>();
    @GuardedBy("this")
    private HandlerThread mHandlerThread;
    @GuardedBy("this")
    private PowerHandler mHandler;
    @GuardedBy("this")
    private boolean mTimerActive;
    private int mNextWakeupSec = 0;
    private int mTokenValue = 1;
    private boolean mShutdownOnFinish = false;

    // TODO:  Make this OEM configurable.
    private static final int SHUTDOWN_POLLING_INTERVAL_MS = 2000;
    private static final int SHUTDOWN_EXTEND_MAX_MS = 5000;

    // Use one hour for now
    private static int sShutdownPrepareTimeMs = 60 * 60 * 1000;

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

    @VisibleForTesting
    protected static void setShutdownPrepareTimeout(int timeoutMs) {
        // Override the timeout to keep testing time short
        if (timeoutMs < SHUTDOWN_EXTEND_MAX_MS) {
            sShutdownPrepareTimeMs = SHUTDOWN_EXTEND_MAX_MS;
        } else {
            sShutdownPrepareTimeMs = timeoutMs;
        }
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
            // Initialize CPMS in WAIT_FOR_VHAL state
            onApPowerStateChange(CpmsState.WAIT_FOR_VHAL, CarPowerStateListener.WAIT_FOR_VHAL);
        } else {
            Log.w(CarLog.TAG_POWER, "Vehicle hal does not support power state yet.");
            onApPowerStateChange(CpmsState.ON, CarPowerStateListener.ON);
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

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*PowerManagementService*");
        writer.print("mCurrentState:" + mCurrentState);
        writer.print(",mProcessingStartTime:" + mProcessingStartTime);
        writer.print(",mLastSleepEntryTime:" + mLastSleepEntryTime);
        writer.print(",mNextWakeupSec:" + mNextWakeupSec);
        writer.print(",mTokenValue:" + mTokenValue);
        writer.println(",mShutdownOnFinish:" + mShutdownOnFinish);
    }

    @Override
    public void onApPowerStateChange(PowerState state) {
        PowerHandler handler;
        synchronized (this) {
            mPendingPowerStates.addFirst(new CpmsState(state));
            handler = mHandler;
        }
        handler.handlePowerStateChange();
    }

    /**
     * Initiate state change from CPMS directly.
     */
    private void onApPowerStateChange(int apState, int carPowerStateListenerState) {
        CpmsState newState = new CpmsState(apState, carPowerStateListenerState);
        PowerHandler handler;
        synchronized (this) {
            mPendingPowerStates.addFirst(newState);
            handler = mHandler;
        }
        handler.handlePowerStateChange();
    }

    private void doHandlePowerStateChange() {
        CpmsState state;
        PowerHandler handler;
        synchronized (this) {
            state = mPendingPowerStates.peekFirst();
            mPendingPowerStates.clear();
            if (state == null) {
                return;
            }
            Log.i(CarLog.TAG_POWER, "doHandlePowerStateChange: newState=" + state.mState);
            if (!needPowerStateChangeLocked(state)) {
                Log.d(CarLog.TAG_POWER, "doHandlePowerStateChange no change needed");
                return;
            }
            // now real power change happens. Whatever was queued before should be all cancelled.
            releaseTimerLocked();
            handler = mHandler;
        }
        handler.cancelProcessingComplete();
        Log.i(CarLog.TAG_POWER, "setCurrentState " + state.toString());
        mCurrentState = state;
        switch (state.mState) {
            case CpmsState.WAIT_FOR_VHAL:
                handleWaitForVhal(state);
                break;
            case CpmsState.ON:
                handleOn();
                break;
            case CpmsState.SHUTDOWN_PREPARE:
                handleShutdownPrepare(state);
                break;
            case CpmsState.WAIT_FOR_FINISH:
                handleWaitForFinish(state);
                break;
            case CpmsState.SUSPEND:
                // Received FINISH from VHAL
                handleFinish();
                break;
            default:
                // Illegal state
                // TODO:  Throw exception?
                break;
        }
    }

    private void handleWaitForVhal(CpmsState state) {
        int carPowerStateListenerState = state.mCarPowerStateListenerState;
        sendPowerManagerEvent(carPowerStateListenerState);
        // Inspect CarPowerStateListenerState to decide which message to send via VHAL
        switch (carPowerStateListenerState) {
            case CarPowerStateListener.WAIT_FOR_VHAL:
                mHal.sendWaitForVhal();
                break;
            case CarPowerStateListener.SHUTDOWN_CANCELLED:
                mHal.sendShutdownCancel();
                break;
            case CarPowerStateListener.SUSPEND_EXIT:
                mHal.sendSleepExit();
                break;
        }
    }

    private void handleOn() {
        mSystemInterface.setDisplayState(true);
        sendPowerManagerEvent(CarPowerStateListener.ON);
        mHal.sendOn();
    }

    private void handleShutdownPrepare(CpmsState newState) {
        mSystemInterface.setDisplayState(false);
        // Shutdown on finish if the system doesn't support deep sleep or doesn't allow it.
        mShutdownOnFinish |= !mHal.isDeepSleepAllowed()
                || !mSystemInterface.isSystemSupportingDeepSleep()
                || !newState.mCanSleep;
        if (newState.mCanPostpone) {
            Log.i(CarLog.TAG_POWER, "starting shutdown postpone");
            sendPowerManagerEvent(CarPowerStateListener.SHUTDOWN_PREPARE);
            mHal.sendShutdownPrepare();
            doHandlePreprocessing();
        } else {
            Log.i(CarLog.TAG_POWER, "starting shutdown immediately");
            synchronized (this) {
                releaseTimerLocked();
            }
            // Notify hal that we are shutting down and since it is immediate, don't schedule next
            // wake up
            mHal.sendShutdownStart(0);
            // shutdown HU
            mSystemInterface.shutdown();
        }
    }

    private void handleWaitForFinish(CpmsState state) {
        sendPowerManagerEvent(state.mCarPowerStateListenerState);
        switch (state.mCarPowerStateListenerState) {
            case CarPowerStateListener.SUSPEND_ENTER:
                mHal.sendSleepEntry(mNextWakeupSec);
                break;
            case CarPowerStateListener.SHUTDOWN_ENTER:
                mHal.sendShutdownStart(mNextWakeupSec);
                break;
        }
    }

    private void handleFinish() {
        if (mShutdownOnFinish) {
            // shutdown HU
            mSystemInterface.shutdown();
        } else {
            doHandleDeepSleep();
        }
    }

    @GuardedBy("this")
    private void releaseTimerLocked() {
        synchronized (this) {
            if (mTimer != null) {
                mTimer.cancel();
            }
            mTimer = null;
            mTimerActive = false;
        }
    }

    private void doHandlePreprocessing() {
        int pollingCount = (sShutdownPrepareTimeMs / SHUTDOWN_POLLING_INTERVAL_MS) + 1;
        Log.i(CarLog.TAG_POWER, "processing before shutdown expected for: "
                + sShutdownPrepareTimeMs + " ms, adding polling:" + pollingCount);
        synchronized (this) {
            mProcessingStartTime = SystemClock.elapsedRealtime();
            releaseTimerLocked();
            mTimer = new Timer();
            mTimerActive = true;
            mTimer.scheduleAtFixedRate(
                    new ShutdownProcessingTimerTask(pollingCount),
                    0 /*delay*/,
                    SHUTDOWN_POLLING_INTERVAL_MS);
        }
    }

    private void sendPowerManagerEvent(int newState) {
        // Based on new state, do we need to use tokens? In current design, SHUTDOWN_PREPARE
        // is the only state where we need to maintain callback from listener components.
        boolean useTokens = (newState == CarPowerStateListener.SHUTDOWN_PREPARE);

        // First lets generate the tokens
        generateTokensList(useTokens);

        // Now lets notify listeners that we are making a state transition
        sendBroadcasts(newState, useTokens);
    }

    private void generateTokensList(boolean useTokens) {
        synchronized (mPowerManagerListenerTokens) {
            if (useTokens) {
                mPowerManagerListenerTokens.clear();
            }
            int i = mPowerManagerListeners.beginBroadcast();
            while (i-- > 0) {
                ICarPowerStateListener listener = mPowerManagerListeners.getBroadcastItem(i);
                if (useTokens) {
                    mPowerManagerListenerTokens.put(listener.asBinder(), mTokenValue);
                    mTokenValue++;
                }
            }
            mPowerManagerListeners.finishBroadcast();
        }
    }

    private void sendBroadcasts(int newState, boolean useTokens) {
        synchronized (mPowerManagerListenerTokens) {
            int i = mPowerManagerListeners.beginBroadcast();
            while (i-- > 0) {
                ICarPowerStateListener listener = mPowerManagerListeners.getBroadcastItem(i);
                int token = useTokens ? mPowerManagerListenerTokens.get(listener.asBinder()) : 0;
                try {
                    listener.onStateChanged(newState, token);
                } catch (RemoteException e) {
                    // Its likely the connection snapped. Let binder death handle the situation.
                    Log.e(CarLog.TAG_POWER, "onStateChanged() call failed: " + e, e);
                }
            }
            mPowerManagerListeners.finishBroadcast();
        }
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
        synchronized (this) {
            mLastSleepEntryTime = SystemClock.elapsedRealtime();
        }
        if (!mSystemInterface.enterDeepSleep()) {
            // System did not suspend.  VHAL should transition CPMS to shutdown.
            Log.e(CarLog.TAG_POWER, "Sleep did not succeed.  Need to shutdown");
        }
        // On wake, reset nextWakeup time.  If not set again, system will suspend/shutdown forever.
        mNextWakeupSec = 0;
        mSystemInterface.refreshDisplayBrightness();
        onApPowerStateChange(CpmsState.WAIT_FOR_VHAL, CarPowerStateListener.SUSPEND_EXIT);
    }

    private boolean needPowerStateChangeLocked(CpmsState newState) {
        if (newState == null) {
            return false;
        } else if (mCurrentState == null) {
            return true;
        } else if (mCurrentState.equals(newState)) {
            return false;
        }

        // The following switch/case enforces the allowed state transitions.
        switch (mCurrentState.mState) {
            case CpmsState.WAIT_FOR_VHAL:
                return (newState.mState == CpmsState.ON)
                    || (newState.mState == CpmsState.SHUTDOWN_PREPARE);
            case CpmsState.SUSPEND:
                return newState.mState == CpmsState.WAIT_FOR_VHAL;
            case CpmsState.ON:
                return newState.mState == CpmsState.SHUTDOWN_PREPARE;
            case CpmsState.SHUTDOWN_PREPARE:
                // If VHAL sends SHUTDOWN_IMMEDIATELY while in SHUTDOWN_PREPARE state, do it.
                return ((newState.mState == CpmsState.SHUTDOWN_PREPARE) && !newState.mCanPostpone)
                    || (newState.mState == CpmsState.WAIT_FOR_FINISH)
                    || (newState.mState == CpmsState.WAIT_FOR_VHAL);
            case CpmsState.WAIT_FOR_FINISH:
                return newState.mState == CpmsState.SUSPEND;
            default:
                Log.e(CarLog.TAG_POWER, "Unhandled state transition:  currentState="
                        + mCurrentState.mState + ", newState=" + newState.mState);
                return false;
        }
    }

    private void doHandleProcessingComplete() {
        synchronized (this) {
            releaseTimerLocked();
            if (!mShutdownOnFinish && mLastSleepEntryTime > mProcessingStartTime) {
                // entered sleep after processing start. So this could be duplicate request.
                Log.w(CarLog.TAG_POWER, "Duplicate sleep entry request, ignore");
                return;
            }
        }

        if (mShutdownOnFinish) {
            onApPowerStateChange(CpmsState.WAIT_FOR_FINISH, CarPowerStateListener.SHUTDOWN_ENTER);
        } else {
            onApPowerStateChange(CpmsState.WAIT_FOR_FINISH, CarPowerStateListener.SUSPEND_ENTER);
        }
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
        // TODO: Need to send current state to newly registered listener?  If so, need to handle
        //          token for SHUTDOWN_PREPARE state
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
        mShutdownOnFinish = true;
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
        if (!mHal.isTimedWakeupAllowed()) {
            Log.w(CarLog.TAG_POWER, "Setting timed wakeups are disabled in HAL. Skipping");
            mNextWakeupSec = 0;
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
                    (mCurrentState.mState == CpmsState.SHUTDOWN_PREPARE)) {
                PowerHandler powerHandler;
                // All apps are ready to shutdown/suspend.
                synchronized (this) {
                    if (!mShutdownOnFinish) {
                        if (mLastSleepEntryTime > mProcessingStartTime
                                && mLastSleepEntryTime < SystemClock.elapsedRealtime()) {
                            Log.i(CarLog.TAG_POWER, "finishedLocked:  Already slept!");
                            return;
                        }
                    }
                    powerHandler = mHandler;
                }
                Log.i(CarLog.TAG_POWER, "Apps are finished, call handleProcessingComplete()");
                powerHandler.handleProcessingComplete();
            }
        }
    }

    private class PowerHandler extends Handler {
        private final int MSG_POWER_STATE_CHANGE = 0;
        private final int MSG_DISPLAY_BRIGHTNESS_CHANGE = 1;
        private final int MSG_MAIN_DISPLAY_STATE_CHANGE = 2;
        private final int MSG_PROCESSING_COMPLETE = 3;

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

        private void handleProcessingComplete() {
            removeMessages(MSG_PROCESSING_COMPLETE);
            Message msg = obtainMessage(MSG_PROCESSING_COMPLETE);
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
                    doHandleProcessingComplete();
                    break;
            }
        }
    }

    private class ShutdownProcessingTimerTask extends TimerTask {
        private final int mExpirationCount;
        private int mCurrentCount;

        private ShutdownProcessingTimerTask(int expirationCount) {
            mExpirationCount = expirationCount;
            mCurrentCount = 0;
        }

        @Override
        public void run() {
            synchronized (this) {
                if (!mTimerActive) {
                    // Ignore timer expiration since we got cancelled
                    return;
                }
                mCurrentCount++;
                if (mCurrentCount > mExpirationCount) {
                    PowerHandler handler;
                    releaseTimerLocked();
                    handler = mHandler;
                    handler.handleProcessingComplete();
                } else {
                    mHal.sendShutdownPostpone(SHUTDOWN_EXTEND_MAX_MS);
                }
            }
        }
    }

    private static class CpmsState {
        public static final int WAIT_FOR_VHAL = 0;
        public static final int ON = 1;
        public static final int SHUTDOWN_PREPARE = 2;
        public static final int WAIT_FOR_FINISH = 3;
        public static final int SUSPEND = 4;

        /* Config values from AP_POWER_STATE_REQ */
        public final boolean mCanPostpone;
        public final boolean mCanSleep;
        /* Message sent to CarPowerStateListener in response to this state */
        public final int mCarPowerStateListenerState;
        /* One of the above state variables */
        public final int mState;

        /**
          * This constructor takes a PowerHalService.PowerState object and creates the corresponding
          * CPMS state from it.
          */
        CpmsState(PowerState halPowerState) {
            switch (halPowerState.mState) {
                case VehicleApPowerStateReq.ON:
                    this.mCanPostpone = false;
                    this.mCanSleep = false;
                    this.mCarPowerStateListenerState = cpmsStateToPowerStateListenerState(ON);
                    this.mState = ON;
                    break;
                case VehicleApPowerStateReq.SHUTDOWN_PREPARE:
                    this.mCanPostpone = halPowerState.canPostponeShutdown();
                    this.mCanSleep = halPowerState.canEnterDeepSleep();
                    this.mCarPowerStateListenerState = cpmsStateToPowerStateListenerState(
                            SHUTDOWN_PREPARE);
                    this.mState = SHUTDOWN_PREPARE;
                    break;
                case VehicleApPowerStateReq.CANCEL_SHUTDOWN:
                    this.mCanPostpone = false;
                    this.mCanSleep = false;
                    this.mCarPowerStateListenerState = CarPowerStateListener.SHUTDOWN_CANCELLED;
                    this.mState = WAIT_FOR_VHAL;
                    break;
                case VehicleApPowerStateReq.FINISHED:
                    this.mCanPostpone = false;
                    this.mCanSleep = false;
                    this.mCarPowerStateListenerState = cpmsStateToPowerStateListenerState(SUSPEND);
                    this.mState = SUSPEND;
                    break;
                default:
                    // Illegal state from PowerState.  Throw an exception?
                    this.mCanPostpone = false;
                    this.mCanSleep = false;
                    this.mCarPowerStateListenerState = 0;
                    this.mState = 0;
                    break;
            }
        }

        CpmsState(int state) {
            this(state, cpmsStateToPowerStateListenerState(state));
        }

        CpmsState(int state, int carPowerStateListenerState) {
            this.mCanPostpone = false;
            this.mCanSleep = false;
            this.mCarPowerStateListenerState = carPowerStateListenerState;
            this.mState = state;
        }

        private static int cpmsStateToPowerStateListenerState(int state) {
            int powerStateListenerState = 0;

            // Set the CarPowerStateListenerState based on current state
            switch (state) {
                case ON:
                    powerStateListenerState = CarPowerStateListener.ON;
                    break;
                case SHUTDOWN_PREPARE:
                    powerStateListenerState = CarPowerStateListener.SHUTDOWN_PREPARE;
                    break;
                case SUSPEND:
                    powerStateListenerState = CarPowerStateListener.SUSPEND_ENTER;
                    break;
                case WAIT_FOR_VHAL:
                case WAIT_FOR_FINISH:
                default:
                    // Illegal state for this constructor.  Throw an exception?
                    break;
            }
            return powerStateListenerState;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CpmsState)) {
                return false;
            }
            CpmsState that = (CpmsState) o;
            return this.mState == that.mState
                    && this.mCanSleep == that.mCanSleep
                    && this.mCanPostpone == that.mCanPostpone
                    && this.mCarPowerStateListenerState == that.mCarPowerStateListenerState;
        }

        @Override
        public String toString() {
            return "CpmsState canSleep:" + mCanSleep + ", canPostpone=" + mCanPostpone
                    + ", carPowerStateListenerState=" + mCarPowerStateListenerState
                    + ", CpmsState=" + mState;
        }
    }

}
