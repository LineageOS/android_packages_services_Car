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

package android.car.hardware.power;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.CarNotConnectedException;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.CompletableFuture;

/**
 * API for receiving power state change notifications.
 * @hide
 */
@SystemApi
public class CarPowerManager implements CarManagerBase {
    private final static boolean DBG = false;
    private final static String TAG = "CarPowerManager";
    private CarPowerStateListener mListener;
    private final ICarPower mService;
    private CompletableFuture<Void> mFuture;

    @GuardedBy("mLock")
    private ICarPowerStateListener mListenerToService;

    private final Object mLock = new Object();

    /**
     * Power boot up reasons, returned by {@link getBootReason}
     */
    /**
     * User powered on the vehicle.  These definitions must match the ones located in the native
     * CarPowerManager:  packages/services/Car/car-lib/native/CarPowerManager/CarPowerManager.h
     *
     */
    public static final int BOOT_REASON_USER_POWER_ON = 1;
    /**
     * Door unlock caused device to boot
     */
    public static final int BOOT_REASON_DOOR_UNLOCK = 2;
    /**
     * Timer expired and vehicle woke up the AP
     */
    public static final int BOOT_REASON_TIMER = 3;
    /**
     * Door open caused device to boot
     */
    public static final int BOOT_REASON_DOOR_OPEN = 4;
    /**
     * User activated remote start
     */
    public static final int BOOT_REASON_REMOTE_START = 5;

    /** @hide */
    @IntDef({
        BOOT_REASON_USER_POWER_ON,
        BOOT_REASON_DOOR_UNLOCK,
        BOOT_REASON_TIMER,
        BOOT_REASON_DOOR_OPEN,
        BOOT_REASON_REMOTE_START,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BootReason{}

    /**
     *  Applications set a {@link CarPowerStateListener} for power state event updates.
     */
    public interface CarPowerStateListener {
        /**
         * onStateChanged() states.  These definitions must match the ones located in the native
         * CarPowerManager:  packages/services/Car/car-lib/native/CarPowerManager/CarPowerManager.h
         */
        /**
         * Shutdown is cancelled, return to normal state.
         */
        int SHUTDOWN_CANCELLED = 0;
        /**
         * Enter shutdown state.  Application is expected to cleanup and be ready to shutdown.
         */
        int SHUTDOWN_ENTER = 1;
        /**
         * Enter suspend state.  Application is expected to cleanup and be ready to suspend.
         */
        int SUSPEND_ENTER = 2;
        /**
         * Wake up from suspend, or resume from a cancelled suspend.  Application transitions to
         * normal state.
         */
        int SUSPEND_EXIT = 3;
        /**
         * NonInteractive state
         * @hide
         */
        int NON_INTERACTIVE = 4;
        /**
         * Interactive state (Full On State)
         * @hide
         */
        int INTERACTIVE = 5;
        /**
         * State where system is getting ready for shutdown or suspend
         * @hide
         */
        int SHUTDOWN_PREPARE = 6;

        /**
         *  Called when power state changes
         *  @param state New power state of device.
         *  @param future CompletableFuture used by consumer modules to notify CPMS that
         *                they are ready to continue shutting down. CPMS will wait until this future
         *                is completed.
         */
        void onStateChanged(int state, CompletableFuture<Void> future);
    }

    /**
     * Get an instance of the CarPowerManager.
     *
     * Should not be obtained directly by clients, use {@link Car#getCarManager(String)} instead.
     * @param service
     * @param context
     * @param handler
     * @hide
     */
    public CarPowerManager(IBinder service, Context context, Handler handler) {
        mService = ICarPower.Stub.asInterface(service);
    }

    /**
     * Returns the current {@link BootReason}.  This value does not change until the device goes
     * through a suspend/resume cycle.
     * @return int
     * @throws CarNotConnectedException
     * @hide
     */
    public int getBootReason() throws CarNotConnectedException {
        try {
            return mService.getBootReason();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in getBootReason", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Request power manager to shutdown in lieu of suspend at the next opportunity.
     * @throws CarNotConnectedException
     * @hide
     */
    public void requestShutdownOnNextSuspend() throws CarNotConnectedException {
        try {
            mService.requestShutdownOnNextSuspend();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in requestShutdownOnNextSuspend", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Schedule next wake up time in CarPowerManagementSystem
     * @throws CarNotConnectedException
     * @hide
     */
    public void scheduleNextWakeupTime(int seconds) throws CarNotConnectedException {
        try {
            mService.scheduleNextWakeupTime(seconds);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while scheduling next wakeup time", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Sets a listener to receive power state changes.  Only one listener may be set at a time.
     * For calls that require completion before continue, we attach a {@link CompletableFuture}
     * which is being used as a signal that caller is finished and ready to proceed.
     * Once future is completed, the {@link finished} method will automatically be called to notify
     * {@link CarPowerManagementService} that the application has handled the
     * {@link #SHUTDOWN_PREPARE} state transition.
     *
     * @param listener
     * @throws CarNotConnectedException, IllegalStateException
     * @hide
     */
    public void setListener(CarPowerStateListener listener) throws
            CarNotConnectedException, IllegalStateException {
        synchronized(mLock) {
            if (mListenerToService == null) {
                ICarPowerStateListener listenerToService = new ICarPowerStateListener.Stub() {
                    @Override
                    public void onStateChanged(int state, int token) throws RemoteException {
                        handleStateTransition(state, token);
                    }
                };
                try {
                    mService.registerListener(listenerToService);
                    mListenerToService = listenerToService;
                } catch (RemoteException ex) {
                    Log.e(TAG, "Could not connect: ", ex);
                    throw new CarNotConnectedException(ex);
                } catch (IllegalStateException ex) {
                    Car.checkCarNotConnectedExceptionFromCarService(ex);
                }
            }
            if (mListener == null) {
                // Update listener
                mListener = listener;
            } else {
                throw new IllegalStateException("Listener must be cleared first");
            }
        }
    }

    /**
     * Removes the listener from {@link CarPowerManagementService}
     * @hide
     */
    public void clearListener() {
        ICarPowerStateListener listenerToService;
        synchronized (mLock) {
            listenerToService = mListenerToService;
            mListenerToService = null;
            mListener = null;
            cleanupFuture();
        }

        if (listenerToService == null) {
            Log.w(TAG, "unregisterListener: listener was not registered");
            return;
        }

        try {
            mService.unregisterListener(listenerToService);
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to unregister listener", ex);
            //ignore
        } catch (IllegalStateException ex) {
            Car.hideCarNotConnectedExceptionFromCarService(ex);
        }
    }

    private void handleStateTransition(int state, int token) {
        // Update CompletableFuture. It will recreate it or just clean it up
        updateFuture(state, token);
        // Notify user that state has changed and supply future
        mListener.onStateChanged(state, mFuture);
    }

    private void updateFuture(int state, int token) {
        cleanupFuture();
        if (state == CarPowerStateListener.SHUTDOWN_PREPARE) {
            mFuture = new CompletableFuture<>();
            mFuture.whenComplete((result, exception) -> {
                if (exception != null) {
                    Log.e(TAG, "Exception occurred while waiting for future", exception);
                    return;
                }
                try {
                    mService.finished(mListenerToService, token);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException while calling CPMS.finished()", e);
                }
            });
        }
    }

    private void cleanupFuture() {
        if (mFuture != null) {
            if (!mFuture.isDone()) {
                mFuture.cancel(false);
            }
            mFuture = null;
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        ICarPowerStateListener listenerToService;
        synchronized (mLock) {
            listenerToService = mListenerToService;
        }

        if (listenerToService != null) {
            clearListener();
        }
    }
}
