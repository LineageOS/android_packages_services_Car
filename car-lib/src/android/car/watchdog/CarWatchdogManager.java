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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.automotive.watchdog.ICarWatchdogClient;
import android.car.Car;
import android.car.CarManagerBase;
import android.os.IBinder;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.ref.WeakReference;

/**
 * Provides APIs and interfaces for client health checking.
 *
 * @hide
 */
// TODO(b/147845170): change to SystemApi after API review.
public final class CarWatchdogManager extends CarManagerBase {

    private static final String TAG = CarWatchdogManager.class.getSimpleName();

    /** Timeout for services which should be responsive. The length is 3,000 milliseconds. */
    public static final int TIMEOUT_LENGTH_CRITICAL = 3000;

    /** Timeout for services which are relatively responsive. The length is 5,000 milliseconds. */
    public static final int TIMEOUT_LENGTH_MODERATE = 5000;

    /** Timeout for all other services. The length is 10,000 milliseconds. */
    public static final int TIMEOUT_LENGTH_NORMAL = 10000;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "TIMEOUT_LENGTH_", value = {
            TIMEOUT_LENGTH_CRITICAL,
            TIMEOUT_LENGTH_MODERATE,
            TIMEOUT_LENGTH_NORMAL,
    })
    @Target({ElementType.TYPE_USE})
    public @interface TimeoutLengthEnum {}

    private final ICarWatchdogService mService;

    /**
     * CarWatchdogClientCallback is implemented by the clients which want to be health-checked by
     * car watchdog server. Every time onHealthCheckRequested is called, they are expected to
     * respond by calling {@link CarWatchdogManager.tellClientAlive} within timeout. If they don't
     * respond, car watchdog server reports the current state and kills them.
     */
    public abstract class CarWatchdogClientCallback {
        /**
         * Car watchdog server pings the client to check if it is alive.
         *
         * @param sessionId Unique id to distinguish each health checking.
         * @param timeout Time duration within which the client should respond.
         *
         * @return whether the response is immediately acknowledged. If {@code true}, car watchdog
         *         server considers that the response is acknowledged already. If {@code false},
         *         the client should call {@link CarWatchdogManager.tellClientAlive} later to tell
         *         that it is alive.
         */
        public boolean onHealthCheckRequested(int sessionId, @TimeoutLengthEnum int timeout) {
            return false;
        }
    }

    /** @hide */
    public CarWatchdogManager(Car car, IBinder service) {
        super(car);
        mService = ICarWatchdogService.Stub.asInterface(service);
    }

    /**
     * Registers the car watchdog clients to {@link CarWatchdogManager}.
     *
     * @param client Watchdog client implementing {@link CarWatchdogClientCallback} interface.
     * @param timeout The time duration within which the client desires to respond. The actual
     *        timeout is decided by watchdog server.
     *
     * @hide
     */
    // TODO(b/147845170): change to SystemApi after API review.
    @RequiresPermission(Car.PERMISSION_USE_CAR_WATCHDOG)
    public void registerClient(@NonNull CarWatchdogClientCallback client,
            @TimeoutLengthEnum int timeout) {
        // TODO(b/145556670): implement body.
    }

    /**
     * Unregisters the car watchdog client from {@link CarWatchdogManager}.
     *
     * @param client Watchdog client implementing {@link CarWatchdogClientCallback} interface.
     *
     * @hide
     */
    // TODO(b/147845170): change to SystemApi after API review.
    @RequiresPermission(Car.PERMISSION_USE_CAR_WATCHDOG)
    public void unregisterClient(@NonNull CarWatchdogClientCallback client) {
        // TODO(b/145556670): implement body.
    }

    /**
     * Tells {@link CarWatchdogManager} that the client is alive.
     *
     * @param client Watchdog client implementing {@link CarWatchdogClientCallback} interface.
     * @param sessionId Session id given by {@link CarWatchdogManager}.
     *
     * @hide
     */
    // TODO(b/147845170): change to SystemApi after API review.
    @RequiresPermission(Car.PERMISSION_USE_CAR_WATCHDOG)
    public void tellClientAlive(@NonNull CarWatchdogClientCallback client, int sessionId) {
        // TODO(b/145556670): implement body.
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        // nothing to do
    }

    private static final class ICarWatchdogClientImpl extends ICarWatchdogClient.Stub {
        private final WeakReference<CarWatchdogManager> mManager;

        private ICarWatchdogClientImpl(CarWatchdogManager manager) {
            mManager = new WeakReference<>(manager);
        }

        @Override
        public void checkIfAlive(int sessionId, int timeout) {
            // TODO(b/145556670): implement body.
        }
    }
}
