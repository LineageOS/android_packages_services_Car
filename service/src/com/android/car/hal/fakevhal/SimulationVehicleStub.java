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

package com.android.car.hal.fakevhal;

import android.annotation.Nullable;
import android.car.builtin.util.Slogf;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.VehicleStub;
import com.android.car.hal.HalPropConfig;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;
import com.android.car.hal.VehicleHalCallback;
import com.android.car.internal.property.PropIdAreaId;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.internal.util.PairSparseArray;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public final class SimulationVehicleStub extends VehicleStubWrapper {

    private static final String TAG = CarLog.tagFor(SimulationVehicleStub.class);

    private final ArraySet<Integer> mPropertyIdsFromRealHardware;
    private ReplayingVehicleHalCallback mReplayingVehicleHalCallback;

    public SimulationVehicleStub(VehicleStub stub, List<Integer> propertyIdsFromRealHardware)
            throws RemoteException {
        super(stub, getInitialPropValuesAndConfigs(stub));
        mPropertyIdsFromRealHardware = new ArraySet<>(propertyIdsFromRealHardware);
    }

    private static Pair<SparseArray<HalPropConfig>, PairSparseArray<HalPropValue>>
            getInitialPropValuesAndConfigs(VehicleStub realVehicleStub) throws RemoteException {
        SparseArray<HalPropConfig> allProperties = getAllPropConfigSparseArray(realVehicleStub);
        PairSparseArray<HalPropValue> propValuesByPropIdAreaId = new PairSparseArray<>();
        for (int i = 0; i < allProperties.size(); i++) {
            HalPropConfig halPropconfig = allProperties.valueAt(i);
            int propId = halPropconfig.getPropId();
            List<Integer> supportedAreaIds = getAllSupportedAreaId(propId, allProperties);
            if (isPropertyGlobal(propId)) {
                supportedAreaIds = List.of(AREA_ID_GLOBAL);
            }
            // TODO(b/377378043): Make this a batched getAsync request.
            for (int j = 0; j < supportedAreaIds.size(); j++) {
                try {
                    HalPropValue propValueFromHardware = realVehicleStub.get(
                            realVehicleStub.getHalPropValueBuilder().build(
                                    propId, supportedAreaIds.get(j)));
                    if (propValueFromHardware == null) {
                        Slogf.w(TAG, "PropValueFromHardware is null for propId: %d, areaId: %d",
                                propId, supportedAreaIds.get(j));
                        continue;
                    }
                    propValuesByPropIdAreaId.put(propValueFromHardware.getPropId(),
                            propValueFromHardware.getAreaId(), propValueFromHardware);
                } catch (RemoteException | ServiceSpecificException e) {
                    Slogf.w(TAG, "Unable to get propId: %d, areaId: %d from real vehicle stub",
                            propId, supportedAreaIds.get(j), e);
                }
            }
        }
        return new Pair<>(allProperties, propValuesByPropIdAreaId);
    }

    @Override
    public boolean isAidlVhal() {
        return mRealVehicle.isAidlVhal();
    }

    @Override
    public HalPropValueBuilder getHalPropValueBuilder() {
        return mRealVehicle.getHalPropValueBuilder();
    }

    @Override
    public String getInterfaceDescriptor() throws IllegalStateException {
        return "com.android.car.hal.fakevhal.SimulationVehicleStub";
    }

    private static SparseArray<HalPropConfig> getAllPropConfigSparseArray(
            VehicleStub realVehicleStub) throws RemoteException {
        HalPropConfig[] halPropConfigs = realVehicleStub.getAllPropConfigs();
        SparseArray<HalPropConfig> sparseArray = new SparseArray<>();
        for (int i = 0; i < halPropConfigs.length; i++) {
            sparseArray.put(halPropConfigs[i].getPropId(), halPropConfigs[i]);
        }
        return sparseArray;
    }

    @Override
    public HalPropConfig[] getAllPropConfigs() throws RemoteException, ServiceSpecificException {
        return mRealVehicle.getAllPropConfigs();
    }

    @Override
    public SubscriptionClient newSubscriptionClient(VehicleHalCallback callback) {
        mReplayingVehicleHalCallback = new ReplayingVehicleHalCallback(callback,
                mPropertyIdsFromRealHardware);
        return mRealVehicle.newSubscriptionClient(mReplayingVehicleHalCallback);
    }

    @Nullable
    @Override
    public HalPropValue get(HalPropValue requestedPropValue)
            throws RemoteException, ServiceSpecificException {
        if (mPropertyIdsFromRealHardware.contains(requestedPropValue.getPropId())) {
            Slogf.d(TAG, "Get requestedPropValue from real hardware %s", requestedPropValue);
            return mRealVehicle.get(requestedPropValue);
        }
        int propId = requestedPropValue.getPropId();
        checkPropIdSupported(propId);
        int areaId = isPropertyGlobal(propId) ? AREA_ID_GLOBAL : requestedPropValue.getAreaId();
        checkAreaIdSupported(propId, areaId);
        verifyReadAccess(propId, areaId);
        HalPropValue halPropvalue = getFakeHalPropValue(propId, areaId);
        Slogf.d(TAG, "Returning fake value for propertyId: %d, returning prop value of: %s",
                propId, halPropvalue);
        return halPropvalue;
    }

    /**
     * Dumps VHAL debug information.
     *
     * @param fd The file descriptor to print output.
     * @param args Optional additional arguments for the debug command. Can be empty.
     * @throws RemoteException if the remote operation fails.
     * @throws ServiceSpecificException if VHAL returns service specific error.
     */
    @Override
    public void dump(FileDescriptor fd, List<String> args) throws RemoteException,
            ServiceSpecificException {
        IndentingPrintWriter writer = new IndentingPrintWriter(new PrintWriter(
                new FileOutputStream(fd)));
        writer.println("Properties from realHardware: ");
        writer.increaseIndent();
        for (int i = 0; i < mPropertyIdsFromRealHardware.size(); i++) {
            writer.println("property: " + mPropertyIdsFromRealHardware.valueAt(i));
        }
        writer.decreaseIndent();
        super.dump(fd, args);
    }

    @Override
    public void set(HalPropValue propValue) throws RemoteException, ServiceSpecificException {
        if (mPropertyIdsFromRealHardware.contains(propValue.getPropId())) {
            Slogf.d(TAG, "Set requestedPropValue to real hardware %s", propValue);
            mRealVehicle.set(propValue);
            return;
        }
        Slogf.d(TAG, "Set requestedPropValue to fake stub %s", propValue);
        int propId = propValue.getPropId();
        checkPropIdSupported(propId);
        int areaId = isPropertyGlobal(propId) ? AREA_ID_GLOBAL : propValue.getAreaId();
        checkAreaIdSupported(propId, areaId);

        // Check access permission.
        verifyWriteAccess(propId, areaId);

        HalPropValue updatedValue = buildRawPropValueAndCheckRange(propValue);

        HalPropValue oldValue;
        ReplayingVehicleHalCallback callback;
        // Let CarPropertyService decide if the callback should be invoked.
        oldValue = getPropValue(propId, areaId);
        Slogf.d(TAG, "Fake value stored for propId: %d, areaId: %d, value: %s",
                propId, areaId, propValue);
        putPropValue(propId, areaId, propValue);
        if (mReplayingVehicleHalCallback == null) {
            Slogf.w(TAG, "Replaying Vehicle Hal Callback is null");
            return;
        }
        callback = mReplayingVehicleHalCallback;
        if (oldValue.equalsExceptTimestamp(propValue)) {
            Slogf.d(TAG, "Value did not change ignoring onPropertyEvent");
            return;
        }
        callback.getRealCallback().onPropertyEvent(new ArrayList<>(List.of(updatedValue)));
    }

    private static final class ReplayingVehicleHalCallback implements VehicleHalCallback {

        private final VehicleHalCallback mRealCallback;
        private final ArraySet<Integer> mPropertyIdsFromRealHardware;

        private ReplayingVehicleHalCallback(VehicleHalCallback realCallback,
                ArraySet<Integer> propertyFromRealHardware) {
            mRealCallback = realCallback;
            mPropertyIdsFromRealHardware = propertyFromRealHardware;
        }

        private <T> void filterAndInvokeCallback(List<T> items, Function<T, Integer>
                propIdExtractor, Consumer<List<T>> callback, String methodName) {
            List<T> filteredItems = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                T item = items.get(i);
                int propId = propIdExtractor.apply(item);
                if (!mPropertyIdsFromRealHardware.contains(propId)) {
                    Slogf.d(TAG, "Filtering out real hardware due to "
                            + propId + " property not being in mPropertyIdsFromRealHardware");
                    continue;
                }
                filteredItems.add(item);
            }
            if (filteredItems.isEmpty()) {
                Slogf.d(TAG, "All properties were filtered, not running %s", methodName);
                return;
            }
            callback.accept(filteredItems);
        }

        @Override
        public void onPropertyEvent(List<HalPropValue> values) {
            filterAndInvokeCallback(values, HalPropValue::getPropId, mRealCallback::onPropertyEvent,
                    "onPropertyEvent");
        }

        @Override
        public void onPropertySetError(List<VehiclePropError> errors) {
            filterAndInvokeCallback(errors, (VehiclePropError err) -> err.propId,
                    mRealCallback::onPropertySetError, "onPropertySetError");
        }

        @Override
        public void onSupportedValuesChange(List<PropIdAreaId> propIdAreaIds) {
            filterAndInvokeCallback(propIdAreaIds, (PropIdAreaId propIdAreaId) ->
                            propIdAreaId.propId, mRealCallback::onSupportedValuesChange,
                    "onSupportedValuesChange");
        }

        private VehicleHalCallback getRealCallback() {
            return mRealCallback;
        }
    }
}
