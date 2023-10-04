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

package com.android.car.internal;

import static java.util.Objects.requireNonNull;

import android.car.VehiclePropertyIds;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.internal.property.CarPropertyEventTracker;
import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executor;

/**
 * Manages a group of property IDs and area IDs registered for the same {@link
 * CarPropertyEventCallback} at possibly different update rates.
 *
 * @hide
 */
public final class CarPropertyEventCallbackController {
    // Abbreviating TAG because class name is longer than the 23 character Log tag limit.
    private static final String TAG = "CPECallbackController";
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
    private final Object mLock = new Object();
    private final CarPropertyEventCallback mCarPropertyEventCallback;
    // For each property ID and area ID, track the next update time
    // based on the update rate.
    @GuardedBy("mLock")
    private final SparseArray<SparseArray<CarPropertyEventTracker>> mPropIdToAreaIdToCpeTracker =
            new SparseArray<>();
    private final Executor mExecutor;

    public CarPropertyEventCallbackController(CarPropertyEventCallback carPropertyEventCallback,
            Executor executor) {
        requireNonNull(carPropertyEventCallback);
        mCarPropertyEventCallback = carPropertyEventCallback;
        mExecutor = executor;
    }

    /**
     * Forward a {@link CarPropertyEvent} to {@link #mCarPropertyEventCallback} if the event is an
     * error event or the update rate threshold is met for a change event.
     */
    public void onEvent(CarPropertyEvent carPropertyEvent) {
        requireNonNull(carPropertyEvent);
        CarPropertyValue<?> carPropertyValue = carPropertyEvent.getCarPropertyValue();
        if (!shouldCallbackBeInvoked(carPropertyEvent)) {
            if (DBG) {
                Slogf.d(TAG, "onEvent for propertyId=%s, areaId=0x%x, cannot be invoked.",
                        VehiclePropertyIds.toString(carPropertyValue.getPropertyId()),
                        carPropertyValue.getAreaId());
            }
            return;
        }
        switch (carPropertyEvent.getEventType()) {
            case CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE:
                mExecutor.execute(() -> mCarPropertyEventCallback.onChangeEvent(carPropertyValue));
                break;
            case CarPropertyEvent.PROPERTY_EVENT_ERROR:
                if (DBG) {
                    Slogf.d(TAG, "onErrorEvent for propertyId=%s, areaId=0x%x, errorCode=%d",
                            VehiclePropertyIds.toString(carPropertyValue.getPropertyId()),
                            carPropertyValue.getAreaId(), carPropertyEvent.getErrorCode());
                }
                mExecutor.execute(() -> mCarPropertyEventCallback.onErrorEvent(
                        carPropertyValue.getPropertyId(), carPropertyValue.getAreaId(),
                        carPropertyEvent.getErrorCode()));
                break;
            default:
                Slogf.e(TAG, "onEvent: unknown errorCode=%d, for propertyId=%s, areaId=0x%x",
                        carPropertyEvent.getErrorCode(),
                        VehiclePropertyIds.toString(carPropertyValue.getPropertyId()),
                        carPropertyValue.getAreaId());
                break;
        }
    }

     /** Track the property ID and area IDs at the given update rate. */
    public void add(int propertyId, int[] areaIds, float updateRateHz) {
        requireNonNull(areaIds);
        synchronized (mLock) {
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
     * Returns the areaIds associated with the given propertyId
     */
    public int[] getAreaIds(int propertyId) {
        synchronized (mLock) {
            SparseArray<CarPropertyEventTracker> areaIdToCpeTracker =
                    mPropIdToAreaIdToCpeTracker.get(propertyId);
            int[] areaIds = new int[areaIdToCpeTracker.size()];
            for (int i = 0; i < areaIdToCpeTracker.size(); i++) {
                areaIds[i] = areaIdToCpeTracker.keyAt(i);
            }
            return areaIds;
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

    public Executor getExecutor() {
        return mExecutor;
    }

    /**
     * Stop tracking the given property ID.
     *
     * @return {@code true} if there are no remaining properties being tracked.
     */
    public boolean remove(int propertyId) {
        synchronized (mLock) {
            mPropIdToAreaIdToCpeTracker.delete(propertyId);
            return mPropIdToAreaIdToCpeTracker.size() == 0;
        }
    }

    /** Returns a list of property IDs being tracked for {@link #mCarPropertyEventCallback}. */
    public int[] getSubscribedProperties() {
        synchronized (mLock) {
            int[] propertyIds = new int[mPropIdToAreaIdToCpeTracker.size()];
            for (int i = 0; i < mPropIdToAreaIdToCpeTracker.size(); i++) {
                propertyIds[i] = mPropIdToAreaIdToCpeTracker.keyAt(i);
            }
            return propertyIds;
        }
    }

    private boolean shouldCallbackBeInvoked(CarPropertyEvent carPropertyEvent) {
        CarPropertyValue<?> carPropertyValue = carPropertyEvent.getCarPropertyValue();
        int propertyId = carPropertyValue.getPropertyId();
        int areaId = carPropertyValue.getAreaId();

        synchronized (mLock) {
            SparseArray<CarPropertyEventTracker> areaIdToCpeTracker =
                    mPropIdToAreaIdToCpeTracker.get(propertyId);
            if (areaIdToCpeTracker == null || areaIdToCpeTracker.get(areaId) == null) {
                Slogf.w(TAG, "shouldCallbackBeInvoked: callback not registered for propertyId=%s, "
                        + "areaId=0x%x, timestampNanos=%d", VehiclePropertyIds.toString(propertyId),
                        areaId, carPropertyValue.getTimestamp());
                return false;
            }
            CarPropertyEventTracker cpeTracker = areaIdToCpeTracker.get(areaId);
            return carPropertyEvent.getEventType() == CarPropertyEvent.PROPERTY_EVENT_ERROR
                    || cpeTracker.hasNextUpdateTimeArrived(carPropertyValue);
        }
    }
}
