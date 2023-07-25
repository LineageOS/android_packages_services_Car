/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.property;

import android.car.VehiclePropertyIds;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarPropertyService;
import com.android.car.internal.property.CarPropertyEventTracker;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;

/** Client class to keep track of listeners to {@link CarPropertyService}. */
public final class CarPropertyServiceClient implements IBinder.DeathRecipient {
    private static final String TAG = CarLog.tagFor(CarPropertyServiceClient.class);
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);

    private final ICarPropertyEventListener mListener;
    private final IBinder mListenerBinder;
    private final UnregisterCallback mUnregisterCallback;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<SparseArray<CarPropertyEventTracker>> mPropIdToAreaIdToCpeTracker =
            new SparseArray<>();
    @GuardedBy("mLock")
    private boolean mIsDead;

    public CarPropertyServiceClient(ICarPropertyEventListener listener,
            UnregisterCallback unregisterCallback) {
        mListener = listener;
        mListenerBinder = listener.asBinder();
        mUnregisterCallback = unregisterCallback;

        try {
            mListenerBinder.linkToDeath(this, /* flags= */ 0);
        } catch (RemoteException e) {
            Slogf.w(TAG, "IBinder=%s is already dead", mListenerBinder);
            mIsDead = true;
        }
    }

    /**
     * Returns whether this client is already dead.
     *
     * Caller should not assume this client is alive after getting True response because the
     * binder might die after this function checks the status. Caller should only use this
     * function to fail early.
     */
    public boolean isDead() {
        synchronized (mLock) {
            return mIsDead;
        }
    }

    /**
     * Store a property ID and area IDs with the update rate in hz that this
     * client is subscribed at.
     */
    public void addProperty(int propertyId, int[] areaIds, float updateRateHz) {
        synchronized (mLock) {
            if (mIsDead) {
                return;
            }

            SparseArray<CarPropertyEventTracker> areaIdToCpeTracker =
                    mPropIdToAreaIdToCpeTracker.get(propertyId);
            if (areaIdToCpeTracker == null) {
                areaIdToCpeTracker = new SparseArray<>(areaIds.length);
                mPropIdToAreaIdToCpeTracker.put(propertyId, areaIdToCpeTracker);
            }

            for (int i = 0; i < areaIds.length; i++) {
                areaIdToCpeTracker.put(areaIds[i], new CarPropertyEventTracker(updateRateHz));
            }
        }
    }

    /**
     * Return the update rate in hz that the property ID and area ID pair
     * is subscribed at.
     */
    public float getUpdateRateHz(int propertyId, int areaId) {
        synchronized (mLock) {
            SparseArray<CarPropertyEventTracker> areaIdToCpeTracker =
                    mPropIdToAreaIdToCpeTracker.get(propertyId);
            if (areaIdToCpeTracker == null || areaIdToCpeTracker.get(areaId) == null) {
                Slogf.e(TAG, "getUpdateRateHz: update rate hz not found for propertyId=%s",
                        VehiclePropertyIds.toString(propertyId));
                // Return 0 if property not found, since that is the slowest rate.
                return 0f;
            }
            return areaIdToCpeTracker.get(areaId).getUpdateRateHz();
        }
    }

    /**
     * Remove a property ID that this client is subscribed to.
     *
     * Once all property IDs are removed, this client instance must be removed because
     * {@link #mListenerBinder} will no longer be receiving death notifications.
     *
     * @return {@code true} if there are no properties registered to this client
     */
    public boolean removeProperty(int propertyId) {
        synchronized (mLock) {
            mPropIdToAreaIdToCpeTracker.delete(propertyId);
            if (mPropIdToAreaIdToCpeTracker.size() == 0) {
                mListenerBinder.unlinkToDeath(this, /* flags= */ 0);
            }
            return mPropIdToAreaIdToCpeTracker.size() == 0;
        }
    }

    /**
     * Handler to be called when client died.
     *
     * Remove the listener from HAL service and unregister if this is the last client.
     */
    @Override
    public void binderDied() {
        List<Integer> propertyIds = new ArrayList<>();
        synchronized (mLock) {
            mIsDead = true;

            if (DBG) {
                Slogf.d(TAG, "binderDied %s", mListenerBinder);
            }

            // Because we set mIsDead to true here, we are sure mPropertyIdToUpdateRateHz will not
            // have new elements. The property IDs here cover all the properties that we need to
            // unregister.
            for (int i = 0; i < mPropIdToAreaIdToCpeTracker.size(); i++) {
                propertyIds.add(mPropIdToAreaIdToCpeTracker.keyAt(i));
            }
        }
        mUnregisterCallback.onUnregister(propertyIds, mListenerBinder);
    }

    /**
     * Calls onEvent function on the listener if the binder is alive.
     *
     * There is still chance when onEvent might fail because binderDied is not called before
     * this function.
     */
    public void onEvent(List<CarPropertyEvent> events) throws RemoteException {
        List<CarPropertyEvent> filteredEvents = new ArrayList<>();
        synchronized (mLock) {
            if (mIsDead) {
                return;
            }
            for (int i = 0; i < events.size(); i++) {
                CarPropertyValue<?> carPropertyValue = events.get(i).getCarPropertyValue();
                int propertyId = carPropertyValue.getPropertyId();
                int areaId = carPropertyValue.getAreaId();
                SparseArray<CarPropertyEventTracker> areaIdToCpeTracker =
                        mPropIdToAreaIdToCpeTracker.get(propertyId);

                if (areaIdToCpeTracker == null || areaIdToCpeTracker.get(areaId) == null) {
                    Slogf.w(TAG, "onEvent: Client not registered to propertyId=%s, areaId=0x%x,"
                            + " timestampNanos=%d", VehiclePropertyIds.toString(propertyId), areaId,
                            carPropertyValue.getTimestamp());
                    continue;
                }

                CarPropertyEventTracker cpeTracker = areaIdToCpeTracker.get(areaId);
                if (events.get(i).getEventType() == CarPropertyEvent.PROPERTY_EVENT_ERROR
                        || cpeTracker.hasNextUpdateTimeArrived(carPropertyValue)) {
                    filteredEvents.add(events.get(i));
                }
            }
        }
        if (!filteredEvents.isEmpty()) {
            mListener.onEvent(filteredEvents);
        }
    }

    /**
     * Interface that receives updates when unregistering properties with {@link
     * CarPropertyService}.
     */
    public interface UnregisterCallback {

        /**
         * Callback from {@link CarPropertyService} when unregistering properties.
         *
         * @param propertyIds the properties to be unregistered
         * @param listenerBinder the client to be removed
         */
        void onUnregister(List<Integer> propertyIds, IBinder listenerBinder);
    }
}
