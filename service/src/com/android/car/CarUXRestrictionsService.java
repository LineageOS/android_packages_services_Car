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
import android.car.Car;
import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarDrivingStateEvent.CarDrivingState;
import android.car.drivingstate.CarUXRestrictionsEvent;
import android.car.drivingstate.CarUXRestrictionsEvent.CarUXRestrictions;
import android.car.drivingstate.ICarDrivingStateChangeListener;
import android.car.drivingstate.ICarUXRestrictions;
import android.car.drivingstate.ICarUXRestrictionsChangeListener;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * A service that listens to current driving state of the vehicle and maps it to the
 * appropriate UX restrictions for that driving state.
 */
public class CarUXRestrictionsService extends ICarUXRestrictions.Stub implements CarServiceBase {
    private static final String TAG = "CarUXR";
    private static final boolean DBG = false;
    private final Context mContext;
    private final CarDrivingStateService mDrivingStateService;
    // List of clients listening to UX restriction events.
    private final List<UXRestrictionsClient> mUXRClients = new ArrayList<>();
    private CarUXRestrictionsEvent mCurrentUXRestrictions;

    public CarUXRestrictionsService(Context context, CarDrivingStateService drvService) {
        mContext = context;
        mDrivingStateService = drvService;
        mCurrentUXRestrictions = createUXRestrictionsEvent(
                CarUXRestrictionsEvent.UXR_FULLY_RESTRICTED);
    }

    @Override
    public void init() {
        // subscribe to driving State
        mDrivingStateService.registerDrivingStateChangeListener(
                mICarDrivingStateChangeEventListener);
    }

    @Override
    public synchronized void release() {
        for (UXRestrictionsClient client : mUXRClients) {
            client.listenerBinder.unlinkToDeath(client, 0);
        }
        mUXRClients.clear();
        // Fully restricted by default
        mCurrentUXRestrictions = createUXRestrictionsEvent(
                CarUXRestrictionsEvent.UXR_FULLY_RESTRICTED);
        mDrivingStateService.unregisterDrivingStateChangeListener(
                mICarDrivingStateChangeEventListener);
    }

    // Binder methods

    /**
     * Register a {@link ICarUXRestrictionsChangeListener} to be notified for changes to the UX
     * restrictions
     *
     * @param listener listener to register
     */
    @Override
    public synchronized void registerUXRestrictionsChangeListener(
            ICarUXRestrictionsChangeListener listener) {
        if (listener == null) {
            if (DBG) {
                Log.e(TAG, "registerUXRestrictionsChangeListener(): listener null");
            }
            throw new IllegalArgumentException("Listener is null");
        }
        // If a new client is registering, create a new DrivingStateClient and add it to the list
        // of listening clients.
        UXRestrictionsClient client = findUXRestrictionsClient(listener);
        if (client == null) {
            client = new UXRestrictionsClient(listener);
            try {
                listener.asBinder().linkToDeath(client, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot link death recipient to binder " + e);
            }
            mUXRClients.add(client);
        }
        return;
    }

    /**
     * Iterates through the list of registered UX Restrictions clients -
     * {@link UXRestrictionsClient} and finds if the given client is already registered.
     *
     * @param listener Listener to look for.
     * @return the {@link UXRestrictionsClient} if found, null if not
     */
    @Nullable
    private UXRestrictionsClient findUXRestrictionsClient(
            ICarUXRestrictionsChangeListener listener) {
        IBinder binder = listener.asBinder();
        for (UXRestrictionsClient client : mUXRClients) {
            if (client.isHoldingBinder(binder)) {
                return client;
            }
        }
        return null;
    }


    /**
     * Unregister the given UX Restrictions listener
     *
     * @param listener client to unregister
     */
    @Override
    public synchronized void unregisterUXRestrictionsChangeListener(
            ICarUXRestrictionsChangeListener listener) {
        if (listener == null) {
            Log.e(TAG, "unregisterUXRestrictionsChangeListener(): listener null");
            throw new IllegalArgumentException("Listener is null");
        }

        UXRestrictionsClient client = findUXRestrictionsClient(listener);
        if (client == null) {
            Log.e(TAG, "unregisterUXRestrictionsChangeListener(): listener was not previously "
                    + "registered");
            return;
        }
        listener.asBinder().unlinkToDeath(client, 0);
        mUXRClients.remove(client);
    }

    /**
     * Gets the current UX restrictions
     *
     * @return {@link CarUXRestrictionsEvent} for the given event type
     */
    @Override
    @Nullable
    public synchronized CarUXRestrictionsEvent getCurrentUXRestrictions() {
        return mCurrentUXRestrictions;
    }

    /**
     * Class that holds onto client related information - listener interface, process that hosts the
     * binder object etc.
     * It also registers for death notifications of the host.
     */
    private class UXRestrictionsClient implements IBinder.DeathRecipient {
        private final IBinder listenerBinder;
        private final ICarUXRestrictionsChangeListener listener;

        public UXRestrictionsClient(ICarUXRestrictionsChangeListener l) {
            listener = l;
            listenerBinder = l.asBinder();
        }

        @Override
        public void binderDied() {
            if (DBG) {
                Log.d(TAG, "Binder died " + listenerBinder);
            }
            listenerBinder.unlinkToDeath(this, 0);
            synchronized(CarUXRestrictionsService.this) {
                mUXRClients.remove(this);
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
         * Dispatch the event to the listener
         *
         * @param event {@link CarUXRestrictionsEvent}.
         */
        public void dispatchEventToClients(CarUXRestrictionsEvent event) {
            if (event == null) {
                return;
            }
            try {
                listener.onUXRestrictionsChanged(event);
            } catch (RemoteException e) {
                if (DBG) {
                    Log.d(TAG, "Dispatch to listener failed");
                }
            }
        }
    }

    @Override
    public void dump(PrintWriter writer) {

    }

    /**
     * {@link CarDrivingStateEvent} listener registered with the {@link CarDrivingStateService}
     * for getting driving state change notifications.
     */
    private final ICarDrivingStateChangeListener mICarDrivingStateChangeEventListener =
            new ICarDrivingStateChangeListener.Stub() {
                @Override
                public void onDrivingStateChanged(CarDrivingStateEvent event) {
                    Log.d(TAG, "Driving State Changed:" + event.eventValue);
                    handleDrivingStateEvent(event);
                }
            };

    /**
     * Handle the driving state change events coming from the {@link CarDrivingStateService}.
     * Map the driving state to the corresponding UX Restrictions and dispatch the
     * UX Restriction change to the registered clients.
     */
    private synchronized void handleDrivingStateEvent(CarDrivingStateEvent event) {
        if (event == null) {
            return;
        }
        int drivingState = event.eventValue;
        int uxRestrictions = mapDrivingStateToUXRestrictionsLocked(drivingState);
        if (DBG) {
            Log.d(TAG, "UXR for new driving state->old: " + uxRestrictions + "->"
                    + mCurrentUXRestrictions.eventValue);
        }
        if (mCurrentUXRestrictions.eventValue != uxRestrictions) {
            CarUXRestrictionsEvent newUXR = createUXRestrictionsEvent(uxRestrictions);
            if (DBG) {
                Log.d(TAG, "dispatching to " + mUXRClients.size() + " clients");
            }
            for (UXRestrictionsClient client : mUXRClients) {
                client.dispatchEventToClients(newUXR);
            }
        }
    }

    /**
     * Map the current driving state of the vehicle to the should be imposed UX restriction for that
     * state.
     * TODO: (b/69859857) This mapping needs to be configurable per OEM.
     *
     * @param drivingState driving state to map for
     * @return UX Restriction for the given driving state
     */
    @CarUXRestrictions
    private int mapDrivingStateToUXRestrictionsLocked(@CarDrivingState int drivingState) {
        int uxRestrictions;
        switch (drivingState) {
            case (CarDrivingStateEvent.DRIVING_STATE_PARKED):
                uxRestrictions = CarUXRestrictionsEvent.UXR_NO_RESTRICTIONS;
                break;
            // For now, for any driving state other than parked, fall through to Fully Restricted
            // mode.
            // TODO: (b/69859857) Customized mapping to a configurable UX Restriction
            case (CarDrivingStateEvent.DRIVING_STATE_IDLING):
            case (CarDrivingStateEvent.DRIVING_STATE_MOVING):
            default:
                uxRestrictions = CarUXRestrictionsEvent.UXR_FULLY_RESTRICTED;
        }
        return uxRestrictions;
    }

    private static CarUXRestrictionsEvent createUXRestrictionsEvent(int eventValue) {
        return new CarUXRestrictionsEvent(eventValue, SystemClock.elapsedRealtimeNanos());
    }
}
