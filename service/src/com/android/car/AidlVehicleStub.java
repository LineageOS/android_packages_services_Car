/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.car.builtin.os.ServiceManagerHelper;
import android.car.builtin.util.Slogf;
import android.hardware.automotive.vehicle.GetValueResults;
import android.hardware.automotive.vehicle.IVehicle;
import android.hardware.automotive.vehicle.IVehicleCallback;
import android.hardware.automotive.vehicle.SetValueResults;
import android.hardware.automotive.vehicle.SubscribeOptions;
import android.hardware.automotive.vehicle.VehiclePropConfig;
import android.hardware.automotive.vehicle.VehiclePropConfigs;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.hardware.automotive.vehicle.VehiclePropErrors;
import android.hardware.automotive.vehicle.VehiclePropValue;
import android.hardware.automotive.vehicle.VehiclePropValues;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import com.android.car.hal.AidlHalPropConfig;
import com.android.car.hal.HalClientCallback;
import com.android.car.hal.HalPropConfig;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;
import com.android.car.internal.LargeParcelable;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

final class AidlVehicleStub extends VehicleStub {

    private static final String AIDL_VHAL_SERVICE =
            "android.hardware.automotive.vehicle.IVehicle/default";

    private final IVehicle mAidlVehicle;
    private final HalPropValueBuilder mPropValueBuilder;

    AidlVehicleStub() {
        this(getAidlVehicle());
    }

    @VisibleForTesting
    AidlVehicleStub(IVehicle aidlVehicle) {
        mAidlVehicle = aidlVehicle;
        mPropValueBuilder = new HalPropValueBuilder(/*isAidl=*/true);
    }


    /**
     * Gets a HalPropValueBuilder that could be used to build a HalPropValue.
     *
     * @return a builder to build HalPropValue.
     */
    @Override
    public HalPropValueBuilder getHalPropValueBuilder() {
        return mPropValueBuilder;
    }

    /**
     * Returns whether this vehicle stub is connecting to a valid vehicle HAL.
     *
     * @return Whether this vehicle stub is connecting to a valid vehicle HAL.
     */
    @Override
    public boolean isValid() {
        return mAidlVehicle != null;
    }

    /**
     * Gets the interface descriptor for the connecting vehicle HAL.
     *
     * @return the interface descriptor.
     * @throws IllegalStateException If unable to get the descriptor.
     */
    @Override
    public String getInterfaceDescriptor() throws IllegalStateException {
        try {
            return mAidlVehicle.asBinder().getInterfaceDescriptor();
        } catch (RemoteException e) {
            throw new IllegalStateException("Unable to get Vehicle HAL interface descriptor", e);
        }
    }

    /**
     * Registers a death recipient that would be called when vehicle HAL died.
     *
     * @param recipient A death recipient.
     * @throws IllegalStateException If unable to register the death recipient.
     */
    @Override
    public void linkToDeath(IVehicleDeathRecipient recipient) throws IllegalStateException {
        try {
            mAidlVehicle.asBinder().linkToDeath(recipient, /*flag=*/ 0);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to linkToDeath Vehicle HAL");
        }
    }

    /**
     * Unlinks a previously linked death recipient.
     *
     * @param recipient A previously linked death recipient.
     */
    @Override
    public void unlinkToDeath(IVehicleDeathRecipient recipient) {
        mAidlVehicle.asBinder().unlinkToDeath(recipient, /*flag=*/ 0);
    }

    /**
     * Gets all property configs.
     *
     * @return All the property configs.
     * @throws RemoteException if the remote operation fails.
     * @throws ServiceSpecificException if VHAL returns service specific error.
     */
    @Override
    public HalPropConfig[] getAllPropConfigs()
            throws RemoteException, ServiceSpecificException {
        VehiclePropConfigs propConfigs = (VehiclePropConfigs)
                LargeParcelable.reconstructStableAIDLParcelable(
                        mAidlVehicle.getAllPropConfigs(), /* keepSharedMemory= */ false);
        VehiclePropConfig[] payloads = propConfigs.payloads;
        int size = payloads.length;
        HalPropConfig[] configs = new HalPropConfig[size];
        for (int i = 0; i < size; i++) {
            configs[i] = new AidlHalPropConfig(payloads[i]);
        }
        return configs;
    }

    /**
     * Gets a new {@code SubscriptionClient} that could be used to subscribe/unsubscribe.
     *
     * @param callback A callback that could be used to receive events.
     * @return a {@code SubscriptionClient} that could be used to subscribe/unsubscribe.
     */
    @Override
    public SubscriptionClient newSubscriptionClient(HalClientCallback callback) {
        return new AidlSubscriptionClient(callback, mPropValueBuilder);
    }

    /**
     * Gets a property.
     *
     * @param requestedPropValue The property to get.
     * @return The vehicle property value.
     * @throws RemoteException if the remote operation fails.
     * @throws ServiceSpecificException if VHAL returns service specific error.
     */
    @Override
    @Nullable
    public HalPropValue get(HalPropValue requestedPropValue)
            throws RemoteException, ServiceSpecificException {
        // TODO(b/205774940): Call AIDL APIs.
        return null;
    }

    /**
     * Sets a property.
     *
     * @param propValue The property to set.
     * @throws RemoteException if the remote operation fails.
     * @throws ServiceSpecificException if VHAL returns service specific error.
     */
    @Override
    public void set(HalPropValue propValue) throws RemoteException, ServiceSpecificException {
        // TODO(b/205774940): Call AIDL APIs.
        return;
    }

    @Nullable
    private static IVehicle getAidlVehicle() {
        try {
            return IVehicle.Stub.asInterface(
                    ServiceManagerHelper.waitForDeclaredService(AIDL_VHAL_SERVICE));
        } catch (RuntimeException e) {
            Slogf.w(CarLog.TAG_SERVICE, "Failed to get \"" + AIDL_VHAL_SERVICE + "\" service", e);
        }
        return null;
    }

    private class AidlSubscriptionClient extends IVehicleCallback.Stub
            implements SubscriptionClient {
        private final HalClientCallback mCallback;
        private final HalPropValueBuilder mBuilder;

        AidlSubscriptionClient(HalClientCallback callback, HalPropValueBuilder builder) {
            mCallback = callback;
            mBuilder = builder;
        }

        @Override
        public void onGetValues(GetValueResults responses) throws RemoteException {
            // We use GetSetValuesCallback for getValues and setValues operation.
            throw new UnsupportedOperationException(
                    "onGetValues should never be called on AidlSubscriptionClient");
        }

        @Override
        public void onSetValues(SetValueResults responses) throws RemoteException {
            // We use GetSetValuesCallback for getValues and setValues operation.
            throw new UnsupportedOperationException(
                    "onSetValues should never be called on AidlSubscriptionClient");
        }

        @Override
        public void onPropertyEvent(VehiclePropValues propValues, int sharedMemoryFileCount)
                throws RemoteException {
            VehiclePropValues origPropValues = (VehiclePropValues)
                    LargeParcelable.reconstructStableAIDLParcelable(propValues,
                            /* keepSharedMemory= */ false);
            ArrayList<HalPropValue> values = new ArrayList<>(origPropValues.payloads.length);
            for (VehiclePropValue value : origPropValues.payloads) {
                values.add(mBuilder.build(value));
            }
            mCallback.onPropertyEvent(values);
        }

        @Override
        public void onPropertySetError(VehiclePropErrors errors) throws RemoteException {
            VehiclePropErrors origErrors = (VehiclePropErrors)
                    LargeParcelable.reconstructStableAIDLParcelable(errors,
                            /* keepSharedMemory= */ false);
            ArrayList<VehiclePropError> errorList = new ArrayList<>(origErrors.payloads.length);
            for (VehiclePropError error : origErrors.payloads) {
                errorList.add(error);
            }
            mCallback.onPropertySetError(errorList);
        }

        @Override
        public void subscribe(SubscribeOptions[] options)
                throws RemoteException, ServiceSpecificException {
            mAidlVehicle.subscribe(this, options, /* maxSharedMemoryFileCount= */ 2);
        }

        @Override
        public void unsubscribe(int prop) throws RemoteException, ServiceSpecificException {
            mAidlVehicle.unsubscribe(this, new int[]{prop});
        }
    }
}
