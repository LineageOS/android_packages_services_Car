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
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictions.CarUxRestrictionsInfo;
import android.car.drivingstate.ICarDrivingStateChangeListener;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
import android.car.drivingstate.ICarUxRestrictionsManager;
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
public class CarUxRestrictionsManagerService extends ICarUxRestrictionsManager.Stub implements
        CarServiceBase {
    private static final String TAG = "CarUxR";
    private static final boolean DBG = false;
    private final Context mContext;
    private final CarDrivingStateService mDrivingStateService;
    // List of clients listening to UX restriction events.
    private final List<UxRestrictionsClient> mUxRClients = new ArrayList<>();
    private CarUxRestrictions mCurrentUxRestrictions;

    public CarUxRestrictionsManagerService(Context context, CarDrivingStateService drvService) {
        mContext = context;
        mDrivingStateService = drvService;
        mCurrentUxRestrictions = createUxRestrictionsEvent(
                CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    @Override
    public void init() {
        // subscribe to driving State
        mDrivingStateService.registerDrivingStateChangeListener(
                mICarDrivingStateChangeEventListener);
    }

    @Override
    public synchronized void release() {
        for (UxRestrictionsClient client : mUxRClients) {
            client.listenerBinder.unlinkToDeath(client, 0);
        }
        mUxRClients.clear();
        // Fully restricted by default
        mCurrentUxRestrictions = createUxRestrictionsEvent(
                CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
        mDrivingStateService.unregisterDrivingStateChangeListener(
                mICarDrivingStateChangeEventListener);
    }

    // Binder methods

    /**
     * Register a {@link ICarUxRestrictionsChangeListener} to be notified for changes to the UX
     * restrictions
     *
     * @param listener listener to register
     */
    @Override
    public synchronized void registerUxRestrictionsChangeListener(
            ICarUxRestrictionsChangeListener listener) {
        if (listener == null) {
            if (DBG) {
                Log.e(TAG, "registerUxRestrictionsChangeListener(): listener null");
            }
            throw new IllegalArgumentException("Listener is null");
        }
        // If a new client is registering, create a new DrivingStateClient and add it to the list
        // of listening clients.
        UxRestrictionsClient client = findUxRestrictionsClient(listener);
        if (client == null) {
            client = new UxRestrictionsClient(listener);
            try {
                listener.asBinder().linkToDeath(client, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot link death recipient to binder " + e);
            }
            mUxRClients.add(client);
        }
        return;
    }

    /**
     * Iterates through the list of registered UX Restrictions clients -
     * {@link UxRestrictionsClient} and finds if the given client is already registered.
     *
     * @param listener Listener to look for.
     * @return the {@link UxRestrictionsClient} if found, null if not
     */
    @Nullable
    private UxRestrictionsClient findUxRestrictionsClient(
            ICarUxRestrictionsChangeListener listener) {
        IBinder binder = listener.asBinder();
        for (UxRestrictionsClient client : mUxRClients) {
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
    public synchronized void unregisterUxRestrictionsChangeListener(
            ICarUxRestrictionsChangeListener listener) {
        if (listener == null) {
            Log.e(TAG, "unregisterUxRestrictionsChangeListener(): listener null");
            throw new IllegalArgumentException("Listener is null");
        }

        UxRestrictionsClient client = findUxRestrictionsClient(listener);
        if (client == null) {
            Log.e(TAG, "unregisterUxRestrictionsChangeListener(): listener was not previously "
                    + "registered");
            return;
        }
        listener.asBinder().unlinkToDeath(client, 0);
        mUxRClients.remove(client);
    }

    /**
     * Gets the current UX restrictions
     *
     * @return {@link CarUxRestrictions} for the given event type
     */
    @Override
    @Nullable
    public synchronized CarUxRestrictions getCurrentUxRestrictions() {
        return mCurrentUxRestrictions;
    }

    /**
     * Class that holds onto client related information - listener interface, process that hosts the
     * binder object etc.
     * It also registers for death notifications of the host.
     */
    private class UxRestrictionsClient implements IBinder.DeathRecipient {
        private final IBinder listenerBinder;
        private final ICarUxRestrictionsChangeListener listener;

        public UxRestrictionsClient(ICarUxRestrictionsChangeListener l) {
            listener = l;
            listenerBinder = l.asBinder();
        }

        @Override
        public void binderDied() {
            if (DBG) {
                Log.d(TAG, "Binder died " + listenerBinder);
            }
            listenerBinder.unlinkToDeath(this, 0);
            synchronized (CarUxRestrictionsManagerService.this) {
                mUxRClients.remove(this);
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
         * @param event {@link CarUxRestrictions}.
         */
        public void dispatchEventToClients(CarUxRestrictions event) {
            if (event == null) {
                return;
            }
            try {
                listener.onUxRestrictionsChanged(event);
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
        int uxRestrictions = mapDrivingStateToUxRestrictionsLocked(drivingState);
        if (DBG) {
            Log.d(TAG, "UxR old->new: " + mCurrentUxRestrictions.getActiveRestrictions() +
                    " -> " + uxRestrictions);
        }

        CarUxRestrictions newRestrictions = createUxRestrictionsEvent(uxRestrictions);
        if (!mCurrentUxRestrictions.isSameRestrictions(newRestrictions)) {
            mCurrentUxRestrictions = newRestrictions;
            if (DBG) {
                Log.d(TAG, "dispatching to " + mUxRClients.size() + " clients");
            }
            for (UxRestrictionsClient client : mUxRClients) {
                client.dispatchEventToClients(newRestrictions);
            }
        }
    }

    /**
     * Map the current driving state of the vehicle to the should be imposed UX restriction for that
     * state.
     * TODO(b/69859857): This mapping needs to be configurable per OEM.
     *
     * @param drivingState driving state to map for
     * @return UX Restriction for the given driving state
     */
    @CarUxRestrictionsInfo
    private int mapDrivingStateToUxRestrictionsLocked(@CarDrivingState int drivingState) {
        int uxRestrictions;
        switch (drivingState) {
            case (CarDrivingStateEvent.DRIVING_STATE_PARKED):
                uxRestrictions = CarUxRestrictions.UX_RESTRICTIONS_UNRESTRICTED;
                break;
            // For now, for any driving state other than parked, fall through to Fully Restricted
            // mode.
            // TODO(b/69859857): Customized mapping to a configurable UX Restriction
            case (CarDrivingStateEvent.DRIVING_STATE_IDLING):
            case (CarDrivingStateEvent.DRIVING_STATE_MOVING):
            default:
                uxRestrictions = CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED;
        }
        return uxRestrictions;
    }

    private static CarUxRestrictions createUxRestrictionsEvent(int uxr) {
        boolean requiresOpt = true;
        // TODO(b/69859857): This will eventually come from a config file
        if (uxr == CarUxRestrictions.UX_RESTRICTIONS_UNRESTRICTED) {
            requiresOpt = false;
        }
        return new CarUxRestrictions(requiresOpt, uxr, SystemClock.elapsedRealtimeNanos());
    }
}
