/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.car.remoteaccess;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.annotation.ApiRequirements;
import android.car.annotation.ApiRequirements.CarVersion;
import android.car.annotation.ApiRequirements.PlatformVersion;
import android.os.IBinder;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * CarRemoteAccessManager allows applications to listen to remote task requests even while Android
 * System is not running.
 *
 * <p>The remote task client registers to {@link CarRemoteAccessManager} to listen to remote access
 * events. At {@link RemoteTaskClientCallback#onClientRegistered} it is required to share
 * {@code serviceId}, {@code deviceId} and {@code clientId} with the cloud service which will use
 * the IDs to wake the vehicle. At {@link RemoteTaskClientCallback#onRemoteTaskRequested}, it starts
 * executing the given task. It is supposed to call {@link reportRemoteTaskDone} when it finishes
 * the given task. Once the task completion is reported or the timeout expires, Android System goes
 * back to either the previous power state or the specified power state.
 */
public final class CarRemoteAccessManager extends CarManagerBase {

    private static final String TAG = CarRemoteAccessManager.class.getSimpleName();

    /**
     * The system remains ON after completing the remote tasks.
     *
     * @hide
     */
    @SystemApi
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int NEXT_POWER_STATE_ON = 1;

    /**
     * The system shuts down to power off after completing the remote tasks.
     *
     * @hide
     */
    @SystemApi
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int NEXT_POWER_STATE_OFF = 2;

    /**
     * The system goes into deep sleep after completing the remote tasks.
     *
     * @hide
     */
    @SystemApi
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int NEXT_POWER_STATE_SUSPEND_TO_RAM = 3;

    /**
     * The system goes into hibernation after completing the remote tasks.
     *
     * @hide
     */
    @SystemApi
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int NEXT_POWER_STATE_SUSPEND_TO_DISK = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "NEXT_POWER_STATE_", value = {
            NEXT_POWER_STATE_ON,
            NEXT_POWER_STATE_OFF,
            NEXT_POWER_STATE_SUSPEND_TO_RAM,
            NEXT_POWER_STATE_SUSPEND_TO_DISK,
    })
    @Target({ElementType.TYPE_USE})
    public @interface NextPowerState {}

    private final ICarRemoteAccessService mService;

    /**
     * An interface passed from {@link RemoteTaskClientCallback}.
     *
     * <p>The remote task client uses this interface to tell {@link CarRemoteAccessManager} that it
     * finalized the pending remote tasks.
     */
    public interface CompletableRemoteTaskFuture {
        /**
         * Tells {@link CarRemoteAccessManager} that the remote task client finalized the pending
         * remoate tasks.
         */
        @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void complete();
    }

    /**
     * Listener for remote task events.
     */
    public interface RemoteTaskClientCallback {
        /**
         * This is called when the remote task client is successfully registered or the client ID is
         * updated by AAOS.
         *
         * @param serviceId Globally unique identifier to specify the wake-up service.
         * @param deviceId Globally unique identifier to specify the vehicle.
         * @param clientId Locally unique identifier to specify the remote task client.
         */
        @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void onRegistrationUpdated(@NonNull String serviceId, @NonNull String deviceId,
                @NonNull String clientId);

        /**
         * This is called when registering the remote task client fails.
         */
        @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void onRegistrationFailed();

        /**
         * This is called when a wake-up request is received/processed.
         *
         * @param taskId ID of the task that is requested by the remote task server.
         * @param data Extra data passed along with the wake-up request.
         * @param taskMaxDurationInSec The timeout before AAOS goes back to the previous power
         *                             state.
         */
        @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void onRemoteTaskRequested(@NonNull String taskId, @Nullable byte[] data,
                int taskMaxDurationInSec);

        /**
         * This is called when the device is about to shutdown.
         *
         * <p>The remote task client should finalize the ongoing tasks, if any, and complete the
         * given future within 5 seconds. After the given timeout, the Android system will shutdown,
         * anyway.
         *
         * @param future {@link CompletableRemoteTaskFuture} used by the remote task client to
         *               notify CarRemoteAccessManager that all pending remote tasks are finalized.
         */
        @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void onShutdownStarting(@NonNull CompletableRemoteTaskFuture future);
    }

    /** @hide */
    public CarRemoteAccessManager(Car car, IBinder service) {
        super(car);
        mService = ICarRemoteAccessService.Stub.asInterface(service);
    }

    /** @hide */
    @Override
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void onCarDisconnected() {
        // Nothing to do.
    }

    /**
     * Sets the remote task client represented as {@link RemoteTaskClientCallback}.
     *
     * @param executor Executor on which {@code callback} is executed.
     * @param callback {@link RemoteTaskClientCallback} that listens to remote task events.
     */
    @RequiresPermission(Car.PERMISSION_USE_REMOTE_ACCESS)
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void setRemoteTaskClient(@NonNull @CallbackExecutor Executor executor,
            @NonNull RemoteTaskClientCallback callback) {
        Objects.requireNonNull(executor, "Executor cannot be null");
        Objects.requireNonNull(callback, "Callback cannot be null");

        // TODO(b/134519794): Implement the logic.
    }

    /**
     * Clears the remote task client previously set via {@link setRemoteTaskClient}.
     *
     * @throws IllegalStateException if {@code callback} is not registered.
     */
    @RequiresPermission(Car.PERMISSION_USE_REMOTE_ACCESS)
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void clearRemoteTaskClient() {
        // TODO(b/134519794): Implement the logic.
    }

    /**
     * Reports that remote tast execution is completed, so that the vehicle will go back to the
     * power state before the wake-up.
     *
     * @param taskId ID of the remote task which has been completed.
     * @throws NullPointerException if {@code taskId} is {@code null}.
     * @throws IllegalArgumentException if {@code taskId} is invalid.
     * @throws IllegalStateException if the remote task client is not registered or not woken up.
     */
    @RequiresPermission(Car.PERMISSION_USE_REMOTE_ACCESS)
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void reportRemoteTaskDone(@NonNull String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");

        // TODO(b/134519794): Implement the logic.
    }

    /**
     * Sets the power state after all the remote tasks are completed.
     *
     * <p>By default, the system returns to the previous power state from which the system woke up.
     * If the given power state is {@code NEXT_POWER_STATE_ON}, Garage Mode is not executed.
     *
     * @param nextPowerState The next power state after the remote task is completed.
     * @param runGarageMode Whether to run Garage Mode when switching to the next power state.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CONTROL_REMOTE_ACCESS)
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void setPowerStatePostTaskExecution(@NextPowerState int nextPowerState,
            boolean runGarageMode) {

        // TODO(b/134519794): Implement the logic.
    }
}
