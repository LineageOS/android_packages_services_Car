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

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import com.android.car.hal.PowerHalService;
import com.android.car.hal.PowerHalService.PowerState;
import com.android.car.hal.VehicleHal;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

public class CarPowerManagementService implements CarServiceBase,
    PowerHalService.PowerEventListener {

    /**
     * Listener for other services to monitor power events.
     */
    public interface PowerServiceEventListener {
        /**
         * Shutdown is happening
         */
        void onShutdown();

        /**
         * Entering deep sleep.
         */
        void onSleepEntry();

        /**
         * Got out of deep sleep.
         */
        void onSleepExit();
    }

    /**
     * Interface for components requiring processing time before shutting-down or
     * entering sleep, and wake-up after shut-down.
     */
    public interface PowerEventProcessingHandler {
        /**
         * Called before shutdown or sleep entry to allow running some processing. This call
         * should only queue such task in different thread and should return quickly.
         * Blocking inside this call can trigger watchdog timer which can terminate the
         * whole system.
         * @param shuttingDown whether system is shutting down or not (= sleep entry).
         * @return time necessary to run processing in ms. should return 0 if there is no
         *         processing necessary.
         */
        long onPrePowerEvent(boolean shuttingDown);

        /**
         * Returns wake up time after system is fully shutdown. Power controller will power on
         * the system after this time. This power on is meant for regular maintenance kind of
         * operation.
         * @return 0 of wake up is not necessary.
         */
        int getWakeupTime();
    }

    private final Context mContext;
    private final PowerHalService mHal;
    private final PowerManager mPowerManager;
    private final DisplayManager mDisplayManager;
    private final HandlerThread mHandlerThread;
    private final PowerHandler mHandler;
    private final WakeLock mFullWakeLock;
    private final WakeLock mPartialWakeLock;
    private final CopyOnWriteArrayList<PowerServiceEventListener> mListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PowerEventProcessingHandler> mPowerEcentProcessingHandlers =
            new CopyOnWriteArrayList<>();
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private PowerState mCurrentState;
    @GuardedBy("mLock")
    private boolean mRearviewCameraActive = false; //TODO plumb this from rearview camera HAL
    @GuardedBy("mLock")
    private Timer mTimer;

    private final int SHUTDOWN_POLLING_INTERVAL_MS = 2000;
    private final int SHUTDOWN_EXTEND_MAX_MS = 5000;

    public CarPowerManagementService(Context context) {
        mContext = context;
        mHal = VehicleHal.getInstance().getPowerHal();
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mDisplayManager = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        mHandlerThread = new HandlerThread(CarLog.TAG_POWER);
        mHandlerThread.start();
        mHandler = new PowerHandler(mHandlerThread.getLooper());
        mFullWakeLock = mPowerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, CarLog.TAG_POWER);
        mPartialWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                CarLog.TAG_POWER);
    }

    @Override
    public void init() {
        mHal.setListener(this);
        if (mHal.isPowerStateSupported()) {
            mHal.sendBootComplete();
            PowerState currentState = mHal.getCurrentPowerState();
            mHandler.handlePowerStateChange(currentState);
        } else {
            Log.w(CarLog.TAG_POWER, "Vehicle hal does not support power state yet.");
            mFullWakeLock.acquire();
        }
        //TODO monitor display state
    }

    @Override
    public void release() {
        synchronized (mLock) {
            releaseTimerLocked();
            mCurrentState = null;
        }
        mHandler.cancelAll();
        mListeners.clear();
        mPowerEcentProcessingHandlers.clear();
        releaseAllWakeLocks();
    }

    /**
     * Register listener to monitor power event. There is no unregister counter-part and the list
     * will be cleared when the service is released.
     * @param listener
     */
    public void registerPowerEventListener(PowerServiceEventListener listener) {
        mListeners.add(listener);
    }

    /**
     * Register PowerEventPreprocessingHandler to run pre-processing before shutdown or
     * sleep entry. There is no unregister counter-part and the list
     * will be cleared when the service is released.
     * @param handler
     */
    public void registerPowerEventProcessingHandler(PowerEventProcessingHandler handler) {
        mPowerEcentProcessingHandlers.add(handler);
    }

    /**
     * Notifies earlier completion of power event processing. PowerEventProcessingHandler quotes
     * time necessary from onPrePowerEvent() call, but actual processing can finish earlier than
     * that, and this call can be called in such case to trigger shutdown without waiting further.
     *
     * @param handler PowerEventProcessingHandler that was already registered with
     *        {@link #registerPowerEventListener(PowerServiceEventListener)} call. If it was not
     *        registered before, this call will be ignored.
     */
    public void notifyPowerEventProcessingCompletion(PowerEventProcessingHandler handler) {
        //TODO
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*PowerManagementService*");
        writer.println("mCurrentState:" + mCurrentState);
    }

    @Override
    public void onApPowerStateChange(PowerState state) {
        mHandler.handlePowerStateChange(state);
    }

    private void doHandlePowerStateChange(PowerState state) {
        if (!isPowerStateChanging(state)) {
            return;
        }
        Log.i(CarLog.TAG_POWER, "Power state change:" + state);
        switch (state.state) {
            case PowerHalService.STATE_ON_DISP_OFF:
                handleDisplayOff(state);
                break;
            case PowerHalService.STATE_ON_FULL:
                if (isRearviewCameraActive()) {
                    handleFullOnWithRearviewCameraActive(state);
                } else {
                    handleFullOn(state);
                }
                break;
            case PowerHalService.STATE_SHUTDOWN_PREPARE:
                handleShutdownPrepare(state);
                break;
        }
    }

    private void handleDisplayOff(PowerState newState) {
        setCurrentState(newState);
        doDisplayOff();
    }

    private void handleFullOn(PowerState newState) {
        setCurrentState(newState);
        doDisplayOn();
    }

    private void handleFullOnWithRearviewCameraActive(PowerState newState) {
        setCurrentState(newState);
        // just hold the wakelock but do not turn display on.
        if (!mFullWakeLock.isHeld()) {
            mFullWakeLock.acquire();
        }
    }

    private void handleShutdownPrepare(PowerState newState) {
        setCurrentState(newState);
        doDisplayOff();
        boolean shouldShutdown = true;
        if (mHal.isDeepSleepAllowed() && isSystemSupportingDeepSleep() &&
                newState.canEnterDeepSleep()) {
            Log.i(CarLog.TAG_POWER, "starting sleep");
            shouldShutdown = false;
            doHandlePreprocessing(shouldShutdown);
            return;
        } else if (newState.canPostponeShutdown()) {
            Log.i(CarLog.TAG_POWER, "starting shutdown with processing");
            doHandlePreprocessing(shouldShutdown);
        } else {
            Log.i(CarLog.TAG_POWER, "starting shutdown immediately");
            synchronized (mLock) {
                releaseTimerLocked();
            }
            doHandleShutdown();
        }
    }

    private void releaseTimerLocked() {
        if (mTimer != null) {
            mTimer.cancel();
        }
        mTimer = null;
    }

    private void doHandlePreprocessing(boolean shuttingDown) {
        long processingTimeMs = 0;
        for (PowerEventProcessingHandler handler : mPowerEcentProcessingHandlers) {
            long handlerProcessingTime = handler.onPrePowerEvent(shuttingDown);
            if (handlerProcessingTime > processingTimeMs) {
                processingTimeMs = handlerProcessingTime;
            }
        }
        if (processingTimeMs > 0) {
            int pollingCount = (int)(processingTimeMs / SHUTDOWN_POLLING_INTERVAL_MS) + 2;
            Log.i(CarLog.TAG_POWER, "processing before shutdown expected for :" + processingTimeMs +
                    " ms, adding polling:" + pollingCount);
            synchronized (this) {
                releaseTimerLocked();
                mTimer = new Timer();
                mTimer.scheduleAtFixedRate(new ShutdownProcessingTimerTask(shuttingDown,
                        pollingCount),
                        0 /*delay*/,
                        SHUTDOWN_POLLING_INTERVAL_MS);
            }
        } else {
            mHandler.handleProcessingComplete(shuttingDown);
        }
    }

    private void doHandleDeepSleep() {
        for (PowerServiceEventListener listener : mListeners) {
            listener.onSleepEntry();
        }
        int wakeupTimeSec = getWakeupTime();
        mHal.sendSleepEntry();
        releaseAllWakeLocks();
        if (!shouldDoFakeShutdown()) { // if it is mocked, do not enter sleep.
            //TODO enter sleep with given wake up time
        }
        switchToPartialWakeLock();
        mHal.sendSleepExit();
        for (PowerServiceEventListener listener : mListeners) {
            listener.onSleepExit();
        }
        if (isWakeupCausedByTimer()) {
            doHandlePreprocessing(false /*shuttingDown*/);
        } else {
            PowerState currentState = mHal.getCurrentPowerState();
            if (isPowerStateChanging(currentState)) {
                mHandler.handlePowerStateChange(currentState);
            } else { // power controller woke-up but no power state change. Just shutdown.
                Log.w(CarLog.TAG_POWER, "externa sleep wake up, but no power state change:" +
                        currentState);
                doHandleShutdown();
            }
        }
    }

    private boolean isPowerStateChanging(PowerState newState) {
        synchronized (mLock) {
            if (mCurrentState != null && mCurrentState.equals(newState)) {
                return false;
            }
            return true;
        }
    }

    private boolean isWakeupCausedByTimer() {
        //TODO check wake up reason and do necessary operation information should come from kernel
        // it can be either power on or wake up for maintenance
        // power on will involve GPIO trigger from power controller
        // its own wakeup will involve timer expiration.
        return false;
    }

    private void doHandleShutdown() {
        // now shutdown
        for (PowerServiceEventListener listener : mListeners) {
            listener.onShutdown();
        }
        int wakeupTimeSec = 0;
        if (mHal.isTimedWakeupAllowed()) {
            wakeupTimeSec = getWakeupTime();
        }
        mHal.sendShutdownStart(wakeupTimeSec);
        if (!shouldDoFakeShutdown()) {
            mPowerManager.shutdown(false /* no confirm*/, null, true /* true */);
        }
    }

    private int getWakeupTime() {
        int wakeupTimeSec = 0;
        for (PowerEventProcessingHandler handler : mPowerEcentProcessingHandlers) {
            int t = handler.getWakeupTime();
            if (t > wakeupTimeSec) {
                wakeupTimeSec = t;
            }
        }
        return wakeupTimeSec;
    }

    private void doHandleProcessingComplete(boolean shutdownWhenCompleted) {
        synchronized (mLock) {
            releaseTimerLocked();
        }
        if (shutdownWhenCompleted) {
            doHandleShutdown();
        } else {
            doHandleDeepSleep();
        }
    }

    private boolean isSystemSupportingDeepSleep() {
        //TODO should return by checking some kernel suspend control sysfs
        return false;
    }

    private synchronized void setCurrentState(PowerState state) {
        mCurrentState = state;
    }

    @Override
    public void onDisplayBrightnessChange(int brightness) {
        // TODO
    }

    private void doHandleDisplayBrightnessChange(int brightness) {
        //TODO
    }

    private void doHandleMainDisplayStateChange() {
        //TODO
    }

    private boolean isMainDisplayOn() {
        Display disp = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        return disp.getState() != Display.STATE_OFF;
    }

    private synchronized boolean isRearviewCameraActive() {
        return mRearviewCameraActive;
    }

    private void doDisplayOn() {
        switchToFullWakeLock();
        if (!isMainDisplayOn()) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis());
        }
    }

    private void doDisplayOff() {
        switchToPartialWakeLock();
        if (isMainDisplayOn()) {
            mPowerManager.goToSleep(SystemClock.uptimeMillis());
        }
    }

    private void switchToPartialWakeLock() {
        if (!mPartialWakeLock.isHeld()) {
            mPartialWakeLock.acquire();
        }
        if (mFullWakeLock.isHeld()) {
            mFullWakeLock.release();
        }
    }

    private void switchToFullWakeLock() {
        if (!mFullWakeLock.isHeld()) {
            mFullWakeLock.acquire();
        }
        if (mPartialWakeLock.isHeld()) {
            mPartialWakeLock.release();
        }
    }

    private void releaseAllWakeLocks() {
        if (mPartialWakeLock.isHeld()) {
            mPartialWakeLock.release();
        }
        if (mFullWakeLock.isHeld()) {
            mFullWakeLock.release();
        }
    }

    private boolean shouldDoFakeShutdown() {
        ICarImpl carImpl = ICarImpl.getInstance(mContext);
        if (!carImpl.isInMocking()) {
            return false;
        }
        CarTestService testService = (CarTestService) carImpl.getCarService(
                CarSystemTest.TEST_SERVICE);
        return !testService.shouldDoRealShutdownInMocking();
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

        private void handlePowerStateChange(PowerState state) {
            Message msg = obtainMessage(MSG_POWER_STATE_CHANGE, state);
            sendMessage(msg);
        }

        private void handleDisplayBrightnessChange(int brightness) {
            Message msg = obtainMessage(MSG_DISPLAY_BRIGHTNESS_CHANGE, brightness, 0);
            sendMessage(msg);
        }

        private void handleMainDisplayStateChange() {
            removeMessages(MSG_MAIN_DISPLAY_STATE_CHANGE);
            Message msg = obtainMessage(MSG_MAIN_DISPLAY_STATE_CHANGE);
            sendMessageDelayed(msg, MAIN_DISPLAY_EVENT_DELAY_MS);
        }

        private void handleProcessingComplete(boolean shutdownWhenCompleted) {
            Message msg = obtainMessage(MSG_PROCESSING_COMPLETE, shutdownWhenCompleted ? 1 : 0, 0);
            sendMessage(msg);
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
                    doHandlePowerStateChange((PowerState) msg.obj);
                    break;
                case MSG_DISPLAY_BRIGHTNESS_CHANGE:
                    doHandleDisplayBrightnessChange(msg.arg1);
                    break;
                case MSG_MAIN_DISPLAY_STATE_CHANGE:
                    doHandleMainDisplayStateChange();
                case MSG_PROCESSING_COMPLETE:
                    doHandleProcessingComplete(msg.arg1 == 1);
                    break;
            }
        }
    }

    private class DisplayStateListener implements DisplayManager.DisplayListener {

        @Override
        public void onDisplayAdded(int displayId) {
            //ignore
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                mHandler.handleMainDisplayStateChange();
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            //ignore
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
                synchronized (mLock) {
                    releaseTimerLocked();
                }
                mHandler.handleProcessingComplete(mShutdownWhenCompleted);
            } else {
                mHal.sendShutdownPostpone(SHUTDOWN_EXTEND_MAX_MS);
            }
        }
    }
}
