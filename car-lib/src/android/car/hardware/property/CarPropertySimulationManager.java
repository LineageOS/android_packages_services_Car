/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.car.hardware.property;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.feature.Flags;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.os.IBinder;

import com.android.car.internal.ICarBase;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * This class provides APIs for recording and injecting fake vehicle properties for simulation
 * purposes. This class is only available for userdebug and eng builds.
 *
 * <p>This class is used to record and inject vehicle property data.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
public final class CarPropertySimulationManager extends CarManagerBase {

    private final ICarProperty mCarPropertyService;

    /**
     * Get an instance of the CarPropertySimulationManager.
     *
     * Should not be obtained directly by clients, use {@link Car#getCarManager(String)} instead.
     * @hide
     */
    public CarPropertySimulationManager(ICarBase car, @NonNull IBinder service) {
        super(car);
        mCarPropertyService =  ICarProperty.Stub.asInterface(service);
    }

    /**
     * Initiates recording of vehicle properties. The recorded data can be used for playback with
     * {@link CarPropertySimulationManager#injectFakeVehicleProperties}.
     *
     * <p>This API is only available for userdebug and eng build. The caller must call
     * {@link CarPropertySimulationManager#stopRecordingVehicleProperties} to stop this recording.
     *
     * <p>If the listener can no longer be reached (binder goes away) then the recording will be
     * stopped.
     *
     * @param listener A listener to receive callbacks for VHAL events.
     * @param callbackExecutor The executor in which the callback is done on. If this is
     *                         {@code null}, the callback will be executed on the event handler
     *                         provided to the {@link android.car.Car} or the main thread if none
     *                         was provided.
     *
     * @throws IllegalStateException If the build is not userdebug or eng.
     * @throws IllegalStateException If there is a recording already in progress this includes one
     *                               started by this process and started by other processes, only
     *                               one system-wide recording is allowed at a single time.
     * @throws IllegalStateException If fake vehicle injection mode is enabled.
     * @throws SecurityException If missing permission.
     *
     * @return A list of {@link CarPropertyConfig} that are being recorded.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @RequiresPermission(Car.PERMISSION_RECORD_VEHICLE_PROPERTIES)
    @NonNull
    public List<CarPropertyConfig> startRecordingVehicleProperties(@Nullable Executor
            callbackExecutor, @NonNull CarRecorderListener listener) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Checks whether vehicle properties recording is in progress.
     *
     * @throws SecurityException If missing permission.
     *
     * @return true if a recording is in progress, false otherwise.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @RequiresPermission(Car.PERMISSION_RECORD_VEHICLE_PROPERTIES)
    public boolean isRecordingVehicleProperties() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Stops recording of vehicle properties. If there is no recording in progress, this call will
     * be treated as a no-op.
     *
     * @throws IllegalStateException If the build is not userdebug or eng.
     * @throws IllegalStateException If the recording that was started was not started by this
     *                               process.
     * @throws SecurityException If missing permission.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @RequiresPermission(Car.PERMISSION_RECORD_VEHICLE_PROPERTIES)
    public void stopRecordingVehicleProperties() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Initializes vehicle property injection mode, when this is enabled properties not in
     * {@code propertyIdsFromRealHardware} will not receive VHAL events. To inject a vehicle
     * property see {@link CarPropertySimulationManager#injectFakeVehicleProperties}.
     *
     * <p>This method is system-wide.
     *
     * <p>This method is idempotent. If the fake vehicle property injection is already
     * enabled, calling this method has no effect.
     *
     * @param propertyIdsFromRealHardware The propertyIds allowed to receive events from real VHAL.
     * If the propertyId is not supported by the real VHAL, it will be ignored.
     *
     * @throws IllegalStateException If the build is not userdebug or eng.
     * @throws IllegalStateException If recording vehicle property state is enabled.
     * @throws SecurityException If missing permission.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @RequiresPermission(Car.PERMISSION_INJECT_VEHICLE_PROPERTIES)
    public void enableInjectionMode(@NonNull List<Integer> propertyIdsFromRealHardware) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Disables vehicle property injection mode. See
     * {@link CarPropertySimulationManager#enableInjectionMode}
     *
     * <p>This method is system-wide.
     *
     * <p>This method is idempotent. If the fake vehicle property injection is already disabled,
     * calling this method has no effect.
     *
     * @throws IllegalStateException if the build is not userdebug or eng.
     * @throws SecurityException If missing permission.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @RequiresPermission(Car.PERMISSION_INJECT_VEHICLE_PROPERTIES)
    public void disableInjectionMode() {
        throw new UnsupportedOperationException("Not yet Implemented");
    }

    /**
     * Gets the vehicle property injection mode. See
     * {@link CarPropertySimulationManager#enableInjectionMode}
     *
     * @throws IllegalStateException if the build is not userdebug or eng.
     * @throws SecurityException If missing permission.
     *
     * @return True if propertyInjectionMode is enabled False otherwise.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @RequiresPermission(Car.PERMISSION_INJECT_VEHICLE_PROPERTIES)
    public boolean isVehiclePropertyInjectionModeEnabled() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Returns the latest {@link CarPropertyValue} that has been injected for the given propertyId.
     *
     * <p>**Note:** Due to potential concurrency, it is possible that a newer value has been
     * injected since the retrieval of this {@link CarPropertyValue}.
     *
     * <p>This method returns null if no previous vehicle property injection has occurred for the
     * specified propertyId.
     *
     * <p>Calling {@link CarPropertySimulationManager#disableInjectionMode} clears the last
     * injected property value.
     *
     * @throws IllegalStateException if the build is not userdebug or eng.
     * @throws IllegalStateException if fakeVehiclePropertyInjection mode is not enabled.
     * @throws SecurityException If missing permission.
     *
     * @return The latest CarPropertyValue that has been injected for the given PropertyId.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @RequiresPermission(Car.PERMISSION_INJECT_VEHICLE_PROPERTIES)
    @Nullable
    public CarPropertyValue getLastInjectedVehicleProperty(int propertyId) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Injects fake VHAL data into the VHAL. It will call the onPropertyEvent callback in the VHAL.
     * If the carPropertyValue's propertyId is not supported then that CarPropertyValue will be
     * ignored and will not be injected. If the propertyId is part of the
     * propertyIdsFromRealHardware when {@code enableInjectionMode} was called, those properties
     * will also be ignored and will not be injected. The {@code mTimestampNanos} field in each
     * {@link CarPropertyValue} represents the time elapsed since the initial call to
     * {@link CarPropertySimulationManager#injectVehicleProperties}. This elapsed time determines
     * when the corresponding value is injected into the VHAL.
     *
     * <p>This method supports queuing multiple injections. Each injection will be processed
     * independently at its designated time, ensuring that subsequent injections do not override
     * previous ones.
     *
     * <p>If {@code disableInjectionMode} is called before all scheduled property injections have
     * occurred, any pending injections will be cancelled.
     *
     * @param carPropertyValues A list of carPropertyValues to inject. The VHAL will inject the
     *                          vehiclePropValue when the has reached elapsed timestamp in ns. If
     *                          the timestamp has passed, it will inject the value immediately in
     *                          increasing order.
     *
     * @throws IllegalStateException if the build is not userdebug or eng.
     * @throws IllegalStateException if FakeVehiclePropertyInjectionMode is not enabled.
     * @throws SecurityException If missing permission.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @RequiresPermission(Car.PERMISSION_INJECT_VEHICLE_PROPERTIES)
    public void injectVehicleProperties(@NonNull List<CarPropertyValue> carPropertyValues) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Creates a {@link CarPropertyValue} object.
     *
     * <p>This method is used to construct {@link CarPropertyValue} objects for use with
     * {@link CarPropertySimulationManager#injectVehicleProperties}.
     *
     * @param propertyId The property ID to be injected.
     * @param areaId The area ID of the property, or {@code 0} if global.
     * @param timestampNanos The timestamp of the property value in nanoseconds. This timestamp
     *                       represents the elapsed time since the initial call to
     *                       {@link CarPropertySimulationManager#injectVehicleProperties}.
     * @param value The value of the property.
     * @param <T> The type of the property value.
     *
     * @return A {@link CarPropertyValue} object with the specified parameters.
     *
     * @throws SecurityException If missing permission.
     * @throws IllegalStateException If the build is not userdebug or eng.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @RequiresPermission(Car.PERMISSION_INJECT_VEHICLE_PROPERTIES)
    @NonNull
    public <T> CarPropertyValue<T> createCarPropertyValue(int propertyId, int areaId,
            long timestampNanos, T value) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** @hide */
    @Override
    protected void onCarDisconnected() {
        // Not yet implemented
    }

    /**
     * Applications registers CarRecorderListener object to receive updates on subscribed VHAL data.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    public interface CarRecorderListener {
        /**
         * Notifies client of events that have occurred. Notifies client every 100 events or
         * delivers all remaining events if fewer than 100 remain when recording stopped.
         *
         * @param carPropertyValues A List of carPropertyValues, the carPropertyValues will be
         *                          sorted in terms of increasing timestamps.
         *
         * @hide
         */
        @SystemApi
        @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
        void onCarPropertyEvents(@NonNull List<CarPropertyValue<?>> carPropertyValues);

        /**
         * When stop recording has been called, this will notify client that the last event has
         * occurred.
         *
         * @hide
         */
        @SystemApi
        @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
        void onRecordingFinished();
    }
}
