/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.car.watchdog;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.automotive.watchdog.ICarWatchdogClient;
import android.car.Car;
import android.car.CarManagerBase;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

/**
 * Provides APIs and interfaces for client health checking.
 *
 * @hide
 */
@SystemApi
public final class CarWatchdogManager extends CarManagerBase {

    private static final String TAG = CarWatchdogManager.class.getSimpleName();

    /** Timeout for services which should be responsive. The length is 3,000 milliseconds. */
    public static final int TIMEOUT_CRITICAL = 0;

    /** Timeout for services which are relatively responsive. The length is 5,000 milliseconds. */
    public static final int TIMEOUT_MODERATE = 1;

    /** Timeout for all other services. The length is 10,000 milliseconds. */
    public static final int TIMEOUT_NORMAL = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "TIMEOUT_", value = {
            TIMEOUT_CRITICAL,
            TIMEOUT_MODERATE,
            TIMEOUT_NORMAL,
    })
    @Target({ElementType.TYPE_USE})
    public @interface TimeoutLengthEnum {}

    private final ICarWatchdogService mService;
    private final ICarWatchdogClientImpl mClientImpl;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private CarWatchdogClientCallback mRegisteredClient;
    @GuardedBy("mLock")
    private Executor mCallbackExecutor;

    /**
     * CarWatchdogClientCallback is implemented by the clients which want to be health-checked by
     * car watchdog server. Every time onCheckHealthStatus is called, they are expected to
     * respond by calling {@link CarWatchdogManager.tellClientAlive} within timeout. If they don't
     * respond, car watchdog server reports the current state and kills them.
     *
     * <p>Before car watchdog server kills the client, it calls onPrepareProcessKill to allow them
     * to prepare the termination. They will be killed in 1 second.
     */
    public abstract class CarWatchdogClientCallback {
        /**
         * Car watchdog server pings the client to check if it is alive.
         *
         * <p>The callback method is called at the Executor which is specifed in {@link
         * #registerClient}.
         *
         * @param sessionId Unique id to distinguish each health checking.
         * @param timeout Time duration within which the client should respond.
         *
         * @return whether the response is immediately acknowledged. If {@code true}, car watchdog
         *         server considers that the response is acknowledged already. If {@code false},
         *         the client should call {@link CarWatchdogManager.tellClientAlive} later to tell
         *         that it is alive.
         */
        public boolean onCheckHealthStatus(int sessionId, @TimeoutLengthEnum int timeout) {
            return false;
        }

        /**
         * Car watchdog server notifies the client that it will be terminated in 1 second.
         *
         * <p>The callback method is called at the Executor which is specifed in {@link
         * #registerClient}.
         */
        // TODO(b/150006093): After adding a callback to ICarWatchdogClient, subsequent
        // implementation should be done in CarWatchdogService and CarWatchdogManager.
        public void onPrepareProcessTermination() {}
    }

    /** @hide */
    public CarWatchdogManager(Car car, IBinder service) {
        super(car);
        mService = ICarWatchdogService.Stub.asInterface(service);
        mClientImpl = new ICarWatchdogClientImpl(this);
    }

    /**
     * Registers the car watchdog clients to {@link CarWatchdogManager}.
     *
     * <p>It is allowed to register a client from any thread, but only one client can be
     * registered. If two or more clients are needed, create a new {@link Car} and register a client
     * to it.
     *
     * @param client Watchdog client implementing {@link CarWatchdogClientCallback} interface.
     * @param timeout The time duration within which the client desires to respond. The actual
     *        timeout is decided by watchdog server.
     * @throws IllegalStateException if at least one client is already registered.
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_WATCHDOG)
    public void registerClient(@NonNull @CallbackExecutor Executor executor,
            @NonNull CarWatchdogClientCallback client, @TimeoutLengthEnum int timeout) {
        synchronized (mLock) {
            if (mRegisteredClient == client) {
                return;
            }
            if (mRegisteredClient != null) {
                throw new IllegalStateException(
                        "Cannot register the client. Only one client can be registered.");
            }
            mRegisteredClient = client;
            mCallbackExecutor = executor;
        }
        try {
            mService.registerClient(mClientImpl, timeout);
        } catch (RemoteException e) {
            synchronized (mLock) {
                mRegisteredClient = null;
            }
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Unregisters the car watchdog client from {@link CarWatchdogManager}.
     *
     * @param client Watchdog client implementing {@link CarWatchdogClientCallback} interface.
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_WATCHDOG)
    public void unregisterClient(@NonNull CarWatchdogClientCallback client) {
        synchronized (mLock) {
            if (mRegisteredClient != client) {
                Log.w(TAG, "Cannot unregister the client. It has not been registered.");
                return;
            }
            mRegisteredClient = null;
            mCallbackExecutor = null;
        }
        try {
            mService.unregisterClient(mClientImpl);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Tells {@link CarWatchdogManager} that the client is alive.
     *
     * @param client Watchdog client implementing {@link CarWatchdogClientCallback} interface.
     * @param sessionId Session id given by {@link CarWatchdogManager}.
     * @throws IllegalStateException if {@code client} is not registered.
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_WATCHDOG)
    public void tellClientAlive(@NonNull CarWatchdogClientCallback client, int sessionId) {
        // TODO(ericjeong): Need to check if main thread is active regardless of how many clients
        // are registered.
        synchronized (mLock) {
            if (mRegisteredClient != client) {
                throw new IllegalStateException(
                        "Cannot report client status. The client has not been registered.");
            }
        }
        try {
            mService.tellClientAlive(mClientImpl, sessionId);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        // nothing to do
    }

    private void checkClientStatus(int sessionId, int timeout) {
        CarWatchdogClientCallback client;
        Executor executor;
        synchronized (mLock) {
            if (mRegisteredClient == null) {
                Log.w(TAG, "Cannot check client status. The client has not been registered.");
                return;
            }
            client = mRegisteredClient;
            executor = mCallbackExecutor;
        }
        executor.execute(() -> client.onCheckHealthStatus(sessionId, timeout));
    }

    /** @hide */
    private static final class ICarWatchdogClientImpl extends ICarWatchdogClient.Stub {
        private final WeakReference<CarWatchdogManager> mManager;

        private ICarWatchdogClientImpl(CarWatchdogManager manager) {
            mManager = new WeakReference<>(manager);
        }

        @Override
        public void checkIfAlive(int sessionId, int timeout) {
            CarWatchdogManager manager = mManager.get();
            if (manager != null) {
                manager.checkClientStatus(sessionId, timeout);
            }
        }
    }
}
