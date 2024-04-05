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

package com.android.car;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.NonNull;
import android.car.Car;
import android.car.VehicleAreaType;
import android.car.builtin.os.BinderHelper;
import android.car.builtin.util.Slogf;
import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarDrivingStateEvent.CarDrivingState;
import android.car.drivingstate.ICarDrivingState;
import android.car.drivingstate.ICarDrivingStateChangeListener;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.content.Context;
import android.hardware.automotive.vehicle.VehicleGear;
import android.hardware.automotive.vehicle.VehicleIgnitionState;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.util.TransitionLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * A service that infers the current driving state of the vehicle.  It computes the driving state
 * from listening to relevant properties from {@link CarPropertyService}
 */
public class CarDrivingStateService extends ICarDrivingState.Stub implements CarServiceBase {
    private static final String TAG = CarLog.tagFor(CarDrivingStateService.class);
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
    private static final int MAX_TRANSITION_LOG_SIZE = 20;
    private static final int PROPERTY_UPDATE_RATE = 5; // Update rate in Hz
    private final Context mContext;
    private final CarPropertyService mPropertyService;
    // List of clients listening to driving state events.
    private final RemoteCallbackList<ICarDrivingStateChangeListener> mDrivingStateClients =
            new RemoteCallbackList<>();
    // Array of properties that the service needs to listen to from CarPropertyService for deriving
    // the driving state.
    private static final int[] REQUIRED_PROPERTIES = {
            VehicleProperty.PERF_VEHICLE_SPEED,
            VehicleProperty.GEAR_SELECTION,
            VehicleProperty.PARKING_BRAKE_ON,
    };
    // Array of optional properties that the service needs to listen to from CarPropertyService
    // for deriving the driving state.
    private static final int[] OPTIONAL_PROPERTIES = {
            VehicleProperty.IGNITION_STATE
    };
    private final HandlerThread mClientDispatchThread = CarServiceUtils.getHandlerThread(
            getClass().getSimpleName());
    private final Handler mClientDispatchHandler = new Handler(mClientDispatchThread.getLooper());
    private final Object mLock = new Object();

    // For dumpsys logging
    @GuardedBy("mLock")
    private final LinkedList<TransitionLog> mTransitionLogs = new LinkedList<>();

    /**
     * A set of optional {@link VehicleProperty} that are supported.
     */
    @GuardedBy("mLock")
    private SparseBooleanArray mOptionalPropertiesSupported;
    @GuardedBy("mLock")
    private CarPropertyValue mLastParkingBrake;
    @GuardedBy("mLock")
    private CarPropertyValue mLastGear;
    @GuardedBy("mLock")
    private CarPropertyValue mLastSpeed;
    @GuardedBy("mLock")
    private CarPropertyValue mLastIgnitionState;

    @GuardedBy("mLock")
    private List<Integer> mSupportedGears;

    @GuardedBy("mLock")
    private CarDrivingStateEvent mCurrentDrivingState;

    public CarDrivingStateService(Context context, CarPropertyService propertyService) {
        mContext = context;
        mPropertyService = propertyService;
        mCurrentDrivingState = createDrivingStateEvent(CarDrivingStateEvent.DRIVING_STATE_UNKNOWN);
    }

    @Override
    public void init() {
        if (!checkPropertySupport()) {
            Slogf.e(TAG, "init failure.  Driving state will always be fully restrictive");
            return;
        }
        // Gets the boot state first, before getting any events from car.
        synchronized (mLock) {
            mCurrentDrivingState = createDrivingStateEvent(inferDrivingStateLocked());
            addTransitionLogLocked(TAG + " Boot", CarDrivingStateEvent.DRIVING_STATE_UNKNOWN,
                    mCurrentDrivingState.eventValue, mCurrentDrivingState.timeStamp);
        }
        subscribeToProperties();
    }

    @Override
    public void release() {
        for (int property : REQUIRED_PROPERTIES) {
            mPropertyService.unregisterListenerSafe(property, mICarPropertyEventListener);
        }
        for (int property : OPTIONAL_PROPERTIES) {
            synchronized (mLock) {
                if (isOptionalPropertySupportedLocked(property)) {
                    mPropertyService.unregisterListenerSafe(property, mICarPropertyEventListener);
                }
            }
        }
        while (mDrivingStateClients.getRegisteredCallbackCount() > 0) {
            for (int i = mDrivingStateClients.getRegisteredCallbackCount() - 1; i >= 0; i--) {
                ICarDrivingStateChangeListener client =
                        mDrivingStateClients.getRegisteredCallbackItem(i);
                if (client == null) {
                    continue;
                }
                mDrivingStateClients.unregister(client);
            }
        }
        synchronized (mLock) {
            mCurrentDrivingState = createDrivingStateEvent(
                    CarDrivingStateEvent.DRIVING_STATE_UNKNOWN);
            if (mOptionalPropertiesSupported != null) {
                mOptionalPropertiesSupported.clear();
            }
        }
    }

    /**
     * Checks if the {@link CarPropertyService} supports the required properties.
     *
     * @return {@code true} if supported, {@code false} if not
     */
    private boolean checkPropertySupport() {
        List<CarPropertyConfig> configs = mPropertyService
                .getPropertyConfigList(REQUIRED_PROPERTIES).carPropertyConfigList.getConfigs();
        for (int propertyId : REQUIRED_PROPERTIES) {
            boolean found = false;
            for (CarPropertyConfig config : configs) {
                if (config.getPropertyId() == propertyId) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                Slogf.e(TAG, "Required property not supported: " + propertyId);
                return false;
            }
        }
        configs = mPropertyService
                .getPropertyConfigList(OPTIONAL_PROPERTIES).carPropertyConfigList.getConfigs();
        if (configs != null) {
            SparseBooleanArray optionalPropertiesSupported = new SparseBooleanArray();
            for (CarPropertyConfig config : configs) {
                optionalPropertiesSupported.put(config.getPropertyId(), true);
            }
            synchronized (mLock) {
                mOptionalPropertiesSupported = optionalPropertiesSupported;
            }
        }
        return true;
    }

    /**
     * Check if the optional property is supported.
     */
    @GuardedBy("mLock")
    private boolean isOptionalPropertySupportedLocked(int propertyId) {
        if (mOptionalPropertiesSupported != null) {
            return mOptionalPropertiesSupported.get(propertyId);
        }
        return false;
    }

    /**
     * Subscribe to the {@link CarPropertyService} for required sensors.
     */
    private void subscribeToProperties() {
        for (int propertyId : REQUIRED_PROPERTIES) {
            mPropertyService.registerListenerSafe(propertyId, PROPERTY_UPDATE_RATE,
                    mICarPropertyEventListener);
        }
        for (int propertyId : OPTIONAL_PROPERTIES) {
            synchronized (mLock) {
                if (isOptionalPropertySupportedLocked(propertyId)) {
                    mPropertyService.registerListenerSafe(propertyId, PROPERTY_UPDATE_RATE,
                            mICarPropertyEventListener);
                }
            }
        }
    }

    // Binder methods

    /**
     * Register a {@link ICarDrivingStateChangeListener} to be notified for changes to the driving
     * state.
     *
     * @param listener {@link ICarDrivingStateChangeListener}
     */
    @Override
    public void registerDrivingStateChangeListener(ICarDrivingStateChangeListener listener) {
        if (listener == null) {
            if (DBG) {
                Slogf.e(TAG, "registerDrivingStateChangeListener(): listener null");
            }
            throw new IllegalArgumentException("Listener is null");
        }
        mDrivingStateClients.register(listener);
    }

    /**
     * Unregister the given Driving State Change listener
     *
     * @param listener client to unregister
     */
    @Override
    public void unregisterDrivingStateChangeListener(ICarDrivingStateChangeListener listener) {
        if (listener == null) {
            Slogf.e(TAG, "unregisterDrivingStateChangeListener(): listener null");
            throw new IllegalArgumentException("Listener is null");
        }

        mDrivingStateClients.unregister(listener);
    }

    /**
     * Gets the current driving state
     *
     * @return {@link CarDrivingStateEvent} for the given event type
     */
    @Override
    @NonNull
    public CarDrivingStateEvent getCurrentDrivingState() {
        synchronized (mLock) {
            return mCurrentDrivingState;
        }
    }

    @Override
    public void injectDrivingState(CarDrivingStateEvent event) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_CONTROL_APP_BLOCKING);

        dispatchEventToClients(event);
    }

    private void dispatchEventToClients(CarDrivingStateEvent event) {
        boolean success = mClientDispatchHandler.post(() -> {
            int numClients = mDrivingStateClients.beginBroadcast();
            for (int i = 0; i < numClients; i++) {
                ICarDrivingStateChangeListener callback = mDrivingStateClients.getBroadcastItem(i);
                try {
                    callback.onDrivingStateChanged(event);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "Dispatch to listener %s failed for event (%s)", callback, event);
                }
            }
            mDrivingStateClients.finishBroadcast();
        });

        if (!success) {
            Slogf.e(TAG, "Unable to post (" + event + ") event to dispatch handler");
        }
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("*CarDrivingStateService*");

        writer.println("Driving State Clients:");
        writer.increaseIndent();
        BinderHelper.dumpRemoteCallbackList(mDrivingStateClients, writer);
        writer.decreaseIndent();

        writer.println("Driving state change log:");
        synchronized (mLock) {
            for (TransitionLog tLog : mTransitionLogs) {
                writer.println(tLog);
            }
            writer.println("Current Driving State: " + mCurrentDrivingState.eventValue);
            if (mSupportedGears != null) {
                writer.print("Supported gears:");
                for (Integer gear : mSupportedGears) {
                    writer.print(' ');
                    writer.print(gear);
                }
                writer.println();
            }
        }
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dumpProto(ProtoOutputStream proto) {}

    /**
     * {@link CarPropertyEvent} listener registered with the {@link CarPropertyService} for getting
     * property change notifications.
     */
    private final ICarPropertyEventListener mICarPropertyEventListener =
            new ICarPropertyEventListener.Stub() {
                @Override
                public void onEvent(List<CarPropertyEvent> events) throws RemoteException {
                    synchronized (mLock) {
                        for (CarPropertyEvent event : events) {
                            handlePropertyEventLocked(event);
                        }
                    }
                }
            };

    /**
     * Handle events coming from {@link CarPropertyService}.  Compute the driving state, map it to
     * the corresponding UX Restrictions and dispatch the events to the registered clients.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    void handlePropertyEventLocked(CarPropertyEvent event) {
        if (event.getEventType() != CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE) {
            return;
        }
        CarPropertyValue value = event.getCarPropertyValue();
        if (DBG) {
            Slogf.d(TAG, "Property Changed: " + value);
        }
        switch (value.getPropertyId()) {
            case VehicleProperty.PERF_VEHICLE_SPEED:
                mLastSpeed = value;
                if (DBG) {
                    Slogf.d(TAG, "mLastSpeed: " + mLastSpeed);
                }
                break;
            case VehicleProperty.GEAR_SELECTION:
                if (mSupportedGears == null) {
                    mSupportedGears = getSupportedGears();
                }
                mLastGear = value;
                if (DBG) {
                    Slogf.d(TAG, "mLastGear: " + mLastGear);
                }
                break;
            case VehicleProperty.PARKING_BRAKE_ON:
                mLastParkingBrake = value;
                if (DBG) {
                    Slogf.d(TAG, "mLastParkingBrake: " + mLastParkingBrake);
                }
                break;
            case VehicleProperty.IGNITION_STATE:
                mLastIgnitionState = value;
                if (DBG) {
                    Slogf.d(TAG, "mLastIgnitionState: " + mLastIgnitionState);
                }
                break;
            default:
                Slogf.e(TAG,
                        "Received property event for unhandled propId=" + value.getPropertyId());
                break;
        }

        int drivingState = inferDrivingStateLocked();
        // Check if the driving state has changed.  If it has, update our records and
        // dispatch the new events to the listeners.
        if (DBG) {
            Slogf.d(TAG, "Driving state new->old " + drivingState + "->"
                    + mCurrentDrivingState.eventValue);
        }
        if (drivingState != mCurrentDrivingState.eventValue) {
            addTransitionLogLocked(TAG, mCurrentDrivingState.eventValue, drivingState,
                    System.currentTimeMillis());
            // Update if there is a change in state.
            mCurrentDrivingState = createDrivingStateEvent(drivingState);
            if (DBG) {
                Slogf.d(TAG, "dispatching to " + mDrivingStateClients.getRegisteredCallbackCount()
                        + " clients");
            }
            // Dispatch to clients on a separate thread to prevent a deadlock
            final CarDrivingStateEvent currentDrivingStateEvent = mCurrentDrivingState;
            dispatchEventToClients(currentDrivingStateEvent);
        }
    }

    private List<Integer> getSupportedGears() {
        List<CarPropertyConfig> propertyList = mPropertyService
                .getPropertyConfigList(REQUIRED_PROPERTIES).carPropertyConfigList.getConfigs();
        for (CarPropertyConfig p : propertyList) {
            if (p.getPropertyId() == VehicleProperty.GEAR_SELECTION) {
                return p.getConfigArray();
            }
        }
        return Collections.emptyList();
    }

    @GuardedBy("mLock")
    private void addTransitionLogLocked(String name, int from, int to, long timestamp) {
        if (mTransitionLogs.size() >= MAX_TRANSITION_LOG_SIZE) {
            mTransitionLogs.remove();
        }

        TransitionLog tLog = new TransitionLog(name, from, to, timestamp);
        mTransitionLogs.add(tLog);
    }

    private static boolean isPropertyStatusAvailable(CarPropertyValue<?> carPropertyValue) {
        return carPropertyValue != null
                && carPropertyValue.getStatus() == CarPropertyValue.STATUS_AVAILABLE;
    }

    /**
     * Infers the current driving state of the car from the other Car Sensor properties like
     * Current Gear, Speed etc.
     *
     * @return Current driving state
     */
    @GuardedBy("mLock")
    @CarDrivingState
    private int inferDrivingStateLocked() {
        updateVehiclePropertiesIfNeededLocked();
        if (DBG) {
            Slogf.d(TAG,
                    "inferDrivingStateLocked mLastGear: " + mLastGear + "mLastSpeed: " + mLastSpeed
                            + "mLastIgnitionState: " + mLastIgnitionState);
        }

        /*
            Logic to start off deriving driving state:
            1. If gear == parked, then Driving State is parked.
            2. If gear != parked,
                    2a. if parking brake is applied, then Driving state is parked.
                    2b. if parking brake is not applied or unknown/unavailable, then driving state
                    is still unknown.
            3. If driving state is unknown at the end of step 2,
                3a. if speed == 0, then driving state is idling
                3b. if speed != 0, then driving state is moving
                3c. if speed is unavailable,
                    3ca. If ignition state is supported and ignition state == lock or acc or
                    off, then driving state is parked
                    3cb. else driving state is unknown
         */

        if (isVehicleKnownToBeParkedLocked()) {
            return CarDrivingStateEvent.DRIVING_STATE_PARKED;
        }

        // We don't know if the vehicle is parked, let's look at the speed.
        if (!isPropertyStatusAvailable(mLastSpeed)) {
            if (isOptionalPropertySupportedLocked(VehicleProperty.IGNITION_STATE)
                    && isVehicleKnownToBeInLockOrAccOrOffIgnitionStateLocked()) {
                return CarDrivingStateEvent.DRIVING_STATE_PARKED;
            }
            return CarDrivingStateEvent.DRIVING_STATE_UNKNOWN;
        } else if (mLastSpeed.getValue().equals(0f)) {
            return CarDrivingStateEvent.DRIVING_STATE_IDLING;
        } else {
            return CarDrivingStateEvent.DRIVING_STATE_MOVING;
        }
    }

    /**
     * Find if we have signals to know if the vehicle is in ignition state lock or acc or off.
     *
     * @return true if we have enough information to say the vehicle is in ignition state acc or
     * off, false if the vehicle is either not in ignition state acc or off or if we don't have any
     * information.
     */
    @GuardedBy("mLock")
    private boolean isVehicleKnownToBeInLockOrAccOrOffIgnitionStateLocked() {
        return isPropertyStatusAvailable(mLastIgnitionState)
                && (mLastIgnitionState.getValue().equals(VehicleIgnitionState.ACC)
                || mLastIgnitionState.getValue().equals(VehicleIgnitionState.OFF)
                || mLastIgnitionState.getValue().equals(VehicleIgnitionState.LOCK));
    }

    /**
     * Find if we have signals to know if the vehicle is parked
     *
     * @return true if we have enough information to say the vehicle is parked.
     * false, if the vehicle is either not parked or if we don't have any information.
     */
    @GuardedBy("mLock")
    private boolean isVehicleKnownToBeParkedLocked() {
        // If we know the gear is in park, return true
        if (isPropertyStatusAvailable(mLastGear) && mLastGear.getValue()
                .equals(VehicleGear.GEAR_PARK)) {
            return true;
        } else if (isPropertyStatusAvailable(mLastParkingBrake)) {
            // if gear is not in park or unknown, look for status of parking brake if transmission
            // type is manual.
            if (isCarManualTransmissionTypeLocked()) {
                return (boolean) mLastParkingBrake.getValue();
            }
        }
        // if neither information is available, return false to indicate we can't determine
        // if the vehicle is parked.
        return false;
    }

    /**
     * If Supported gears information is available and GEAR_PARK is not one of the supported gears,
     * transmission type is considered to be Manual.  Automatic transmission is assumed otherwise.
     */
    @GuardedBy("mLock")
    private boolean isCarManualTransmissionTypeLocked() {
        return mSupportedGears != null
                && !mSupportedGears.isEmpty()
                && !mSupportedGears.contains(VehicleGear.GEAR_PARK);
    }

    /**
     * Try querying the gear selection and parking brake if we haven't received the event yet.
     * This could happen if the gear change occurred before car service booted up like in the
     * case of a HU restart in the middle of a drive.  Since gear and parking brake are
     * on-change only properties, we could be in this situation where we will have to query
     * VHAL.
     */
    @GuardedBy("mLock")
    private void updateVehiclePropertiesIfNeededLocked() {
        if (mLastGear == null) {
            CarPropertyValue propertyValue = mPropertyService.getPropertySafe(
                    VehicleProperty.GEAR_SELECTION,
                    VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL);
            if (propertyValue != null) {
                mLastGear = propertyValue;
                if (DBG) {
                    Slogf.d(TAG, "updateVehiclePropertiesIfNeeded: gear:" + mLastGear);
                }
            }
        }

        if (mLastParkingBrake == null) {
            CarPropertyValue propertyValue = mPropertyService.getPropertySafe(
                    VehicleProperty.PARKING_BRAKE_ON,
                    VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL);
            if (propertyValue != null) {
                mLastParkingBrake = propertyValue;
                if (DBG) {
                    Slogf.d(TAG, "updateVehiclePropertiesIfNeeded: brake:" + mLastParkingBrake);
                }
            }
        }

        if (mLastSpeed == null) {
            CarPropertyValue propertyValue = mPropertyService.getPropertySafe(
                    VehicleProperty.PERF_VEHICLE_SPEED,
                    VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL);
            if (propertyValue != null) {
                mLastSpeed = propertyValue;
                if (DBG) {
                    Slogf.d(TAG, "updateVehiclePropertiesIfNeeded: speed:" + mLastSpeed);
                }
            }
        }

        if (mLastIgnitionState == null) {
            CarPropertyValue propertyValue = mPropertyService.getPropertySafe(
                    VehicleProperty.IGNITION_STATE,
                    VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL);
            if (propertyValue != null) {
                mLastIgnitionState = propertyValue;
                if (DBG) {
                    Slogf.d(TAG, "updateVehiclePropertiesIfNeeded: ignition state:"
                            + mLastIgnitionState);
                }
            }
        }
    }

    private static CarDrivingStateEvent createDrivingStateEvent(int eventValue) {
        return new CarDrivingStateEvent(eventValue, SystemClock.elapsedRealtimeNanos());
    }
}
