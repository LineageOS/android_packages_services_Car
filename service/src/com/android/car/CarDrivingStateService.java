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

import android.annotation.Nullable;
import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarDrivingStateEvent.CarDrivingState;
import android.car.drivingstate.ICarDrivingState;
import android.car.drivingstate.ICarDrivingStateChangeListener;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.content.Context;
import android.hardware.automotive.vehicle.V2_0.VehicleGear;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * A service that infers the current driving state of the vehicle.  It computes the driving state
 * from listening to relevant properties from {@link CarPropertyService}
 */
public class CarDrivingStateService extends ICarDrivingState.Stub implements CarServiceBase {
    private static final String TAG = "CarDrivingState";
    private static final boolean DBG = false;
    private static final int MAX_TRANSITION_LOG_SIZE = 20;
    private static final int PROPERTY_UPDATE_RATE = 5; // Update rate in Hz
    private static final int NOT_RECEIVED = -1;
    private final Context mContext;
    private CarPropertyService mPropertyService;
    // List of clients listening to driving state events.
    private final List<DrivingStateClient> mDrivingStateClients = new ArrayList<>();
    // Array of properties that the service needs to listen to from CarPropertyService for deriving
    // the driving state. ToDo (ramperry@) - fine tune this list - b/69859926
    private static final int[] REQUIRED_PROPERTIES = {
            VehicleProperty.PERF_VEHICLE_SPEED,
            VehicleProperty.GEAR_SELECTION};
    private CarDrivingStateEvent mCurrentDrivingState;
    // For dumpsys logging
    private final LinkedList<Utils.TransitionLog> mTransitionLogs = new LinkedList<>();
    private int mLastGear;
    private long mLastGearTimestamp = NOT_RECEIVED;
    private float mLastSpeed;
    private long mLastSpeedTimestamp = NOT_RECEIVED;

    public CarDrivingStateService(Context context, CarPropertyService propertyService) {
        mContext = context;
        mPropertyService = propertyService;
        mCurrentDrivingState = createDrivingStateEvent(CarDrivingStateEvent.DRIVING_STATE_UNKNOWN);
    }

    @Override
    public void init() {
        if (!checkPropertySupport()) {
            Log.e(TAG, "init failure.  Driving state will always be fully restrictive");
            return;
        }
        subscribeToProperties();
    }

    @Override
    public synchronized void release() {
        for (int property : REQUIRED_PROPERTIES) {
            mPropertyService.unregisterListener(property, mICarPropertyEventListener);
        }
        for (DrivingStateClient client : mDrivingStateClients) {
            client.listenerBinder.unlinkToDeath(client, 0);
        }
        mDrivingStateClients.clear();
        mCurrentDrivingState = createDrivingStateEvent(CarDrivingStateEvent.DRIVING_STATE_UNKNOWN);
    }

    /**
     * Checks if the {@link CarPropertyService} supports the required properties.
     *
     * @return {@code true} if supported, {@code false} if not
     */
    private synchronized boolean checkPropertySupport() {
        List<CarPropertyConfig> configs = mPropertyService.getPropertyList();
        for (int propertyId : REQUIRED_PROPERTIES) {
            boolean found = false;
            for (CarPropertyConfig config : configs) {
                if (config.getPropertyId() == propertyId) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                Log.e(TAG, "Required property not supported: " + propertyId);
                return false;
            }
        }
        return true;
    }

    /**
     * Subscribe to the {@link CarPropertyService} for required sensors.
     */
    private synchronized void subscribeToProperties() {
        for (int propertyId : REQUIRED_PROPERTIES) {
            mPropertyService.registerListener(propertyId, PROPERTY_UPDATE_RATE,
                    mICarPropertyEventListener);
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
    public synchronized void registerDrivingStateChangeListener(
            ICarDrivingStateChangeListener listener) {
        if (listener == null) {
            if (DBG) {
                Log.e(TAG, "registerDrivingStateChangeListener(): listener null");
            }
            throw new IllegalArgumentException("Listener is null");
        }
        // If a new client is registering, create a new DrivingStateClient and add it to the list
        // of listening clients.
        DrivingStateClient client = findDrivingStateClientLocked(listener);
        if (client == null) {
            client = new DrivingStateClient(listener);
            try {
                listener.asBinder().linkToDeath(client, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot link death recipient to binder " + e);
                return;
            }
            mDrivingStateClients.add(client);
        }
    }

    /**
     * Iterates through the list of registered Driving State Change clients -
     * {@link DrivingStateClient} and finds if the given client is already registered.
     *
     * @param listener Listener to look for.
     * @return the {@link DrivingStateClient} if found, null if not
     */
    @Nullable
    private DrivingStateClient findDrivingStateClientLocked(
            ICarDrivingStateChangeListener listener) {
        IBinder binder = listener.asBinder();
        // Find the listener by comparing the binder object they host.
        for (DrivingStateClient client : mDrivingStateClients) {
            if (client.isHoldingBinder(binder)) {
                return client;
            }
        }
        return null;
    }

    /**
     * Unregister the given Driving State Change listener
     *
     * @param listener client to unregister
     */
    @Override
    public synchronized void unregisterDrivingStateChangeListener(
            ICarDrivingStateChangeListener listener) {
        if (listener == null) {
            Log.e(TAG, "unregisterDrivingStateChangeListener(): listener null");
            throw new IllegalArgumentException("Listener is null");
        }

        DrivingStateClient client = findDrivingStateClientLocked(listener);
        if (client == null) {
            Log.e(TAG, "unregisterDrivingStateChangeListener(): listener was not previously "
                    + "registered");
            return;
        }
        listener.asBinder().unlinkToDeath(client, 0);
        mDrivingStateClients.remove(client);
    }

    /**
     * Gets the current driving state
     *
     * @return {@link CarDrivingStateEvent} for the given event type
     */
    @Override
    @Nullable
    public synchronized CarDrivingStateEvent getCurrentDrivingState() {
        return mCurrentDrivingState;
    }

    /**
     * Class that holds onto client related information - listener interface, process that hosts the
     * binder object etc.
     * <p>
     * It also registers for death notifications of the host.
     */
    private class DrivingStateClient implements IBinder.DeathRecipient {
        private final IBinder listenerBinder;
        private final ICarDrivingStateChangeListener listener;

        public DrivingStateClient(ICarDrivingStateChangeListener l) {
            listener = l;
            listenerBinder = l.asBinder();
        }

        @Override
        public void binderDied() {
            if (DBG) {
                Log.d(TAG, "Binder died " + listenerBinder);
            }
            listenerBinder.unlinkToDeath(this, 0);
            synchronized (CarDrivingStateService.this) {
                mDrivingStateClients.remove(this);
            }
        }

        /**
         * Returns if the given binder object matches to what this client info holds.
         * Used to check if the listener asking to be registered is already registered.
         *
         * @return true if matches, false if not
         */
        public boolean isHoldingBinder(IBinder binder) {
            return listenerBinder == binder;
        }

        /**
         * Dispatch the events to the listener
         *
         * @param event {@link CarDrivingStateEvent}.
         */
        public void dispatchEventToClients(CarDrivingStateEvent event) {
            if (event == null) {
                return;
            }
            try {
                listener.onDrivingStateChanged(event);
            } catch (RemoteException e) {
                if (DBG) {
                    Log.d(TAG, "Dispatch to listener failed");
                }
            }
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("Driving state chane log:");
        for (Utils.TransitionLog tLog : mTransitionLogs) {
            writer.println(tLog);
        }
        writer.println("Current Driving State: " + mCurrentDrivingState.eventValue);
    }

    /**
     * {@link CarPropertyEvent} listener registered with the {@link CarPropertyService} for getting
     * property change notifications.
     */
    private final ICarPropertyEventListener mICarPropertyEventListener =
            new ICarPropertyEventListener.Stub() {
                @Override
                public void onEvent(List<CarPropertyEvent> events) throws RemoteException {
                    for (CarPropertyEvent event : events) {
                        handlePropertyEvent(event);
                    }
                }
            };

    /**
     * Handle events coming from {@link CarPropertyService}.  Compute the driving state, map it to
     * the corresponding UX Restrictions and dispatch the events to the registered clients.
     */
    private synchronized void handlePropertyEvent(CarPropertyEvent event) {
        switch (event.getEventType()) {
            case CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE:
                CarPropertyValue value = event.getCarPropertyValue();
                int propId = value.getPropertyId();
                long curTimestamp = value.getTimestamp();
                Log.d(TAG, "Property Changed: propId=" + propId);
                switch (propId) {
                    case VehicleProperty.PERF_VEHICLE_SPEED:
                        float curSpeed = (Float) value.getValue();
                        if (DBG) {
                            Log.d(TAG, "Speed: " + curSpeed + "@" + curTimestamp);
                        }
                        if (curTimestamp > mLastSpeedTimestamp) {
                            mLastSpeedTimestamp = curTimestamp;
                            mLastSpeed = curSpeed;
                        } else if (DBG) {
                            Log.d(TAG, "Ignoring speed with older timestamp:" + curTimestamp);
                        }
                        break;
                    case VehicleProperty.GEAR_SELECTION:
                        int curGear = (Integer) value.getValue();
                        if (DBG) {
                            Log.d(TAG, "Gear: " + curGear + "@" + curTimestamp);
                        }
                        if (curTimestamp > mLastGearTimestamp) {
                            mLastGearTimestamp = curTimestamp;
                            mLastGear = (Integer) value.getValue();
                        } else if (DBG) {
                            Log.d(TAG, "Ignoring Gear with older timestamp:" + curTimestamp);
                        }
                        break;
                    default:
                        Log.e(TAG, "Received property event for unhandled propId=" + propId);
                        break;
                }

                int drivingState = inferDrivingStateLocked();
                // Check if the driving state has changed.  If it has, update our records and
                // dispatch the new events to the listeners.
                if (DBG) {
                    Log.d(TAG, "Driving state new->old " + drivingState + "->"
                            + mCurrentDrivingState.eventValue);
                }
                if (drivingState != mCurrentDrivingState.eventValue) {
                    addTransitionLog(TAG, mCurrentDrivingState.eventValue, drivingState,
                            System.currentTimeMillis());
                    // Update if there is a change in state.
                    mCurrentDrivingState = createDrivingStateEvent(drivingState);
                    if (DBG) {
                        Log.d(TAG, "dispatching to " + mDrivingStateClients.size() + " clients");
                    }
                    for (DrivingStateClient client : mDrivingStateClients) {
                        client.dispatchEventToClients(mCurrentDrivingState);
                    }
                }
                break;
            default:
                // Unhandled event
                break;
        }
    }

    private void addTransitionLog(String name, int from, int to, long timestamp) {
        if (mTransitionLogs.size() >= MAX_TRANSITION_LOG_SIZE) {
            mTransitionLogs.remove();
        }

        Utils.TransitionLog tLog = new Utils.TransitionLog(name, from, to, timestamp);
        mTransitionLogs.add(tLog);
    }

    /**
     * Infers the current driving state of the car from the other Car Sensor properties like
     * Current Gear, Speed etc.
     * ToDo (ramperry@) - Fine tune this - b/69859926
     *
     * @return Current driving state
     */
    @CarDrivingState
    private int inferDrivingStateLocked() {
        /*
            Simple logic to start off deriving driving state:
            1. If gear == parked, then Driving State is parked.
            2. If gear != parked,
                2a. if speed == 0, then driving state is idling
                2b. if speed != 0, then driving state is moving
                2c. if speed unavailable, then driving state is unknown
            This logic needs to be tested and iterated on.  Tracked in b/69859926
         */
        if (DBG) {
            Log.d(TAG, "Last known Gear:" + mLastGear + " Last known speed:" + mLastSpeed);
        }
        if (mLastGearTimestamp == NOT_RECEIVED) {
            return CarDrivingStateEvent.DRIVING_STATE_UNKNOWN;
        } else if (mLastGear == VehicleGear.GEAR_PARK) {
            return CarDrivingStateEvent.DRIVING_STATE_PARKED;
        }

        if (mLastSpeedTimestamp == NOT_RECEIVED) {
            return CarDrivingStateEvent.DRIVING_STATE_UNKNOWN;
        } else if (mLastSpeed == 0f) {
            return CarDrivingStateEvent.DRIVING_STATE_IDLING;
        } else {
            return CarDrivingStateEvent.DRIVING_STATE_MOVING;
        }
    }

    private static CarDrivingStateEvent createDrivingStateEvent(int eventValue) {
        return new CarDrivingStateEvent(eventValue, SystemClock.elapsedRealtimeNanos());
    }

}
