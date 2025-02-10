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

import static com.android.car.internal.property.CarPropertyErrorCodes.ERROR_CODES_INTERNAL;
import static com.android.car.internal.property.CarPropertyErrorCodes.ERROR_CODES_NOT_AVAILABLE;
import static com.android.car.internal.property.CarPropertyErrorCodes.createFromVhalStatusCode;

import android.annotation.Nullable;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyValue;
import android.hardware.automotive.vehicle.RawPropValues;
import android.hardware.automotive.vehicle.StatusCode;
import android.hardware.automotive.vehicle.VehicleArea;
import android.hardware.automotive.vehicle.VehiclePropValue;
import android.hardware.automotive.vehicle.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.VehiclePropertyType;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.car.IVehicleDeathRecipient;
import com.android.car.VehicleStub;
import com.android.car.hal.HalAreaConfig;
import com.android.car.hal.HalPropConfig;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;
import com.android.car.internal.property.CarPropertyErrorCodes;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.internal.util.PairSparseArray;
import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public abstract class VehicleStubWrapper extends VehicleStub {

    private static final String TAG = CarLog.tagFor(VehicleStubWrapper.class);

    static final int AREA_ID_GLOBAL = 0;

    final SparseArray<HalPropConfig> mPropConfigsByPropId;
    final VehicleStub mRealVehicle;
    @GuardedBy("mLock")
    private final PairSparseArray<HalPropValue> mPropValuesByPropIdAreaId;
    final Handler mHandler;
    private final Object mLock = new Object();

    public VehicleStubWrapper(VehicleStub vehicleStub, Pair<SparseArray<HalPropConfig>,
            PairSparseArray<HalPropValue>> propConfigsByPropIdPropValuesByPropIdAreaIdPair) {
        mRealVehicle = vehicleStub;
        mHandler = new Handler(CarServiceUtils.getHandlerThread(getClass().getSimpleName())
                .getLooper());
        mPropConfigsByPropId = propConfigsByPropIdPropValuesByPropIdAreaIdPair.first;
        mPropValuesByPropIdAreaId = propConfigsByPropIdPropValuesByPropIdAreaIdPair.second;
    }

    /**
     * Checks if a property is a global property.
     *
     * @param propId The property to be checked.
     * @return {@code true} if this property is a global property.
     */
    /* package */ static boolean isPropertyGlobal(int propId) {
        return (propId & VehicleArea.MASK) == VehicleArea.GLOBAL;
    }

    /**
     * Puts the {@code HalPropValue} based on the propId and areaId.
     *
     * @param propId The given propId
     * @param areaId The given areaId
     * @param halPropValue The given HalPropValue
     */
    /* package */ void putPropValue(int propId, int areaId, HalPropValue halPropValue) {
        synchronized (mLock) {
            mPropValuesByPropIdAreaId.put(propId, areaId, halPropValue);
        }
    }

    /**
     * Gets the current {@code HalPropValue} given the propId and areaId.
     *
     * @param propId The given propId
     * @param areaId The Given areaId
     * @return The HalPropValue if it exists.
     */
    @Nullable
    /* package */ HalPropValue getPropValue(int propId, int areaId) {
        synchronized (mLock) {
            return mPropValuesByPropIdAreaId.get(propId, areaId);
        }
    }

    /**
     * Generates a list of all supported areaId for a certain property.
     *
     * @param propId The property to get all supported areaIds.
     * @return A {@link List} of all supported areaId.
     */
    /* package */ List<Integer> getAllSupportedAreaId(int propId) {
        return getAllSupportedAreaId(propId, mPropConfigsByPropId);
    }

    /**
     * Generates a list of all supported areaId for a certain property from the given configs by
     * propId.
     *
     * @param propId The property to get all supported areaIds.
     * @param allProperties The given configs by propId to get all supported areaIds for.
     * @return A {@link List} of all supported areaId.
     */
    /* package */ static List<Integer> getAllSupportedAreaId(int propId,
            SparseArray<HalPropConfig> allProperties) {
        List<Integer> allSupportedAreaId = new ArrayList<>();
        HalAreaConfig[] areaConfigs = allProperties.get(propId).getAreaConfigs();
        for (int i = 0; i < areaConfigs.length; i++) {
            allSupportedAreaId.add(areaConfigs[i].getAreaId());
        }
        return allSupportedAreaId;
    }

    /**
     * Checks if a property is supported. If not, throw a {@link ServiceSpecificException}.
     *
     * @param propId The property to be checked.
     */
    /* package */ void checkPropIdSupported(int propId) {
        // Check if the property config exists.
        if (!mPropConfigsByPropId.contains(propId)) {
            throw new ServiceSpecificException(StatusCode.INVALID_ARG, "The propId: " + propId
                    + " is not supported.");
        }
    }

    /**
     * Checks if an areaId of a property is supported.
     *
     * @param propId The property to be checked.
     * @param areaId The area to be checked.
     */
    /* package */ void checkAreaIdSupported(int propId, int areaId) {
        List<Integer> supportedAreaIds = getAllSupportedAreaId(propId);
        // For global property, areaId will be ignored if the area config array is empty.
        if ((isPropertyGlobal(propId) && supportedAreaIds.isEmpty())
                || supportedAreaIds.contains(areaId)) {
            return;
        }
        throw new ServiceSpecificException(StatusCode.INVALID_ARG, "The areaId: " + areaId
                + " is not supported.");
    }

    /**
     * Gets the HalPropValue based on propId and areaId  with checks.
     * @param propId The given propId
     * @param areaId The given areaId
     * @return The given HalPropValue.
     */
    /* package */ HalPropValue getFakeHalPropValue(int propId, int areaId) {
        // PropId config exists but the value map doesn't have this propId, this may be caused by:
        // 1. This property is a global property, and it doesn't have default prop value.
        // 2. This property has area configs, and it has neither default prop value nor area value.
        synchronized (mLock) {
            HalPropValue halPropValue = mPropValuesByPropIdAreaId.get(propId, areaId);
            if (halPropValue == null) {
                if (isPropertyGlobal(propId)) {
                    throw new ServiceSpecificException(StatusCode.NOT_AVAILABLE,
                            "propId: " + propId + " has no property value.");
                }
                throw new ServiceSpecificException(StatusCode.NOT_AVAILABLE,
                        "propId: " + propId + ", areaId: " + areaId + " has no property value.");
            }
            return halPropValue;
        }
    }

    /**
     * Gets the access of the given propId and areaId.
     * @param propId The given propId.
     * @param areaId The given areaId.
     * @return The access type of the given propId areaId.
     */
    /* package */ int getAccess(int propId, int areaId) {
        HalPropConfig halPropConfig = mPropConfigsByPropId.get(propId);
        HalAreaConfig[] halAreaConfigs = halPropConfig.getAreaConfigs();
        for (int i = 0; i < halAreaConfigs.length; i++) {
            if (halAreaConfigs[i].getAreaId() != areaId) {
                continue;
            }
            int areaAccess = halAreaConfigs[i].getAccess();
            if (areaAccess != VehiclePropertyAccess.NONE) {
                return areaAccess;
            }
            break;
        }
        return halPropConfig.getAccess();
    }

    /**
     * Checks if the set value is within the value range.
     *
     * @return {@code true} if set value is within the prop config range.
     */
    /* package */ boolean isWithinRange(int propId, int areaId, RawPropValues rawPropValues) {
        // For global property without areaId.
        if (isPropertyGlobal(propId) && getAllSupportedAreaId(propId).isEmpty()) {
            return true;
        }

        // For non-global properties and global properties with areaIds.
        int index = getAllSupportedAreaId(propId).indexOf(areaId);

        HalAreaConfig areaConfig = mPropConfigsByPropId.get(propId).getAreaConfigs()[index];

        int[] int32Values = rawPropValues.int32Values;
        long[] int64Values = rawPropValues.int64Values;
        float[] floatValues = rawPropValues.floatValues;
        // If max and min values exists, then check the boundaries. If max and min values are all
        // 0s, return true.
        switch (getPropType(propId)) {
            case VehiclePropertyType.INT32:
            case VehiclePropertyType.INT32_VEC:
                int minInt32Value = areaConfig.getMinInt32Value();
                int maxInt32Value = areaConfig.getMaxInt32Value();
                if (minInt32Value != maxInt32Value || minInt32Value != 0) {
                    for (int int32Value : int32Values) {
                        if (int32Value > maxInt32Value || int32Value < minInt32Value) {
                            Slogf.e(TAG, "For propId: %d, areaId: %d, the valid min value is: "
                                            + "%d, max value is: %d, but the given value is: %d.",
                                    propId, areaId, minInt32Value, maxInt32Value, int32Value);
                            return false;
                        }
                    }
                }
                break;
            case VehiclePropertyType.INT64:
            case VehiclePropertyType.INT64_VEC:
                long minInt64Value = areaConfig.getMinInt64Value();
                long maxInt64Value = areaConfig.getMaxInt64Value();
                if (minInt64Value != maxInt64Value || minInt64Value != 0) {
                    for (long int64Value : int64Values) {
                        if (int64Value > maxInt64Value || int64Value < minInt64Value) {
                            Slogf.e(TAG, "For propId: %d, areaId: %d, the valid min value is: "
                                            + "%d, max value is: %d, but the given value is: %d.",
                                    propId, areaId, minInt64Value, maxInt64Value, int64Value);
                            return false;
                        }
                    }
                }
                break;
            case VehiclePropertyType.FLOAT:
            case VehiclePropertyType.FLOAT_VEC:
                float minFloatValue = areaConfig.getMinFloatValue();
                float maxFloatValue = areaConfig.getMaxFloatValue();
                if (minFloatValue != maxFloatValue || minFloatValue != 0) {
                    for (float floatValue : floatValues) {
                        if (floatValue > maxFloatValue || floatValue < minFloatValue) {
                            Slogf.e(TAG, "For propId: %d, areaId: %d, the valid min value is: "
                                            + "%f, max value is: %f, but the given value is: %d.",
                                    propId, areaId, minFloatValue, maxFloatValue, floatValue);
                            return false;
                        }
                    }
                }
                break;
            default:
                Slogf.d(TAG, "Skip checking range for propId: %d because it is mixed type.",
                        propId);
        }
        return true;
    }

    /**
     * Verifies the propId areaId has read access.
     * @param propId The given propId.
     * @param areaId The given areaId.
     */
    /* package */ void verifyReadAccess(int propId, int areaId) {
        int access = getAccess(propId, areaId);
        if (access != VehiclePropertyAccess.READ && access != VehiclePropertyAccess.READ_WRITE) {
            throw new ServiceSpecificException(StatusCode.ACCESS_DENIED, "This property " + propId
                    + " doesn't have read permission.");
        }
    }

    /**
     * Verifies the propId areaId has write access.
     * @param propId The given propId.
     * @param areaId The given areaId.
     */
    /* package */ void verifyWriteAccess(int propId, int areaId) {
        int access = getAccess(propId, areaId);
        if (access != VehiclePropertyAccess.WRITE && access != VehiclePropertyAccess.READ_WRITE) {
            throw new ServiceSpecificException(StatusCode.ACCESS_DENIED, "This property " + propId
                    + " doesn't have write permission.");
        }
    }

    /**
     * Gets the type of property.
     *
     * @param propId The property to get the type.
     * @return The type.
     */
    /* package */  static int getPropType(int propId) {
        return propId & VehiclePropertyType.MASK;
    }

    /**
     * Builds a {@link HalPropValue} from the given {@link HalPropValue} and checks if the
     * raw values are within the allowed range.
     *
     * @param propValue the {@link HalPropValue} to build from
     * @return the built {@link HalPropValue}
     * @throws ServiceSpecificException if the raw values are not within the allowed range
     */
    /* package */ HalPropValue buildRawPropValueAndCheckRange(HalPropValue propValue) {
        int propId = propValue.getPropId();
        int areaId = propValue.getAreaId();
        RawPropValues rawPropValues = ((VehiclePropValue) propValue.toVehiclePropValue()).value;

        // Check if the set values are within the value config range.
        if (!isWithinRange(propId, areaId, rawPropValues)) {
            throw new ServiceSpecificException(StatusCode.INVALID_ARG,
                    "The set value is outside the range.");
        }

        return buildHalPropValue(propId, areaId,
                SystemClock.elapsedRealtimeNanos(), rawPropValues);
    }

    /**
     * Builds a {@link HalPropValue}.
     *
     * @param propId The propId of the prop value to be built.
     * @param areaId The areaId of the prop value to be built.
     * @param timestamp The elapsed time in nanoseconds when mPropConfigsByPropId is initialized.
     * @param rawPropValues The {@link RawPropValues} contains property values.
     * @return a {@link HalPropValue} built by propId, areaId, timestamp and value.
     */
    /* package */ HalPropValue buildHalPropValue(int propId, int areaId, long timestamp,
            RawPropValues rawPropValues) {
        return buildHalPropValue(propId, areaId, timestamp, rawPropValues,
                getHalPropValueBuilder());
    }

    /**
     * Builds a {@link HalPropValue} from the given parameters using a {@link HalPropValueBuilder}.
     *
     * @param propId the property ID
     * @param areaId the area ID
     * @param timestamp the timestamp
     * @param rawPropValues the raw property values
     * @param halPropValueBuilder the {@link HalPropValueBuilder} to use
     * @return the built {@link HalPropValue}
     */
    /* package */ static HalPropValue buildHalPropValue(int propId, int areaId, long timestamp,
            RawPropValues rawPropValues, HalPropValueBuilder halPropValueBuilder) {
        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = propId;
        propValue.areaId = areaId;
        propValue.timestamp = timestamp;
        propValue.value = rawPropValues;
        return halPropValueBuilder.build(propValue);
    }

    /**
     * Builds a {@link HalPropValue} from the given parameters using a {@link HalPropValueBuilder}
     *
     */
    /* package */ HalPropValue buildHalPropValue(CarPropertyValue carPropertyValue,
            int halPropId, long timestamp) {
        return getHalPropValueBuilder().build(carPropertyValue, halPropId, timestamp,
                mPropConfigsByPropId.get(halPropId));
    }

    /**
     * Gets properties asynchronously.
     *
     * @param getVehicleStubAsyncRequests The async request list.
     * @param getVehicleStubAsyncCallback The callback for getting property values.
     */
    @Override
    public void getAsync(List<AsyncGetSetRequest> getVehicleStubAsyncRequests,
            VehicleStubCallbackInterface getVehicleStubAsyncCallback) {
        List<GetVehicleStubAsyncResult> onGetAsyncResultList = new ArrayList<>();
        for (int i = 0; i < getVehicleStubAsyncRequests.size(); i++) {
            AsyncGetSetRequest request = getVehicleStubAsyncRequests.get(i);
            GetVehicleStubAsyncResult result;
            try {
                HalPropValue halPropValue = get(request.getHalPropValue());
                result = new GetVehicleStubAsyncResult(request.getServiceRequestId(),
                        halPropValue);
                if (halPropValue == null) {
                    result = new GetVehicleStubAsyncResult(request.getServiceRequestId(),
                            ERROR_CODES_NOT_AVAILABLE);
                }
            } catch (ServiceSpecificException e) {
                CarPropertyErrorCodes carPropertyErrorCodes =
                        createFromVhalStatusCode(e.errorCode);
                result = new GetVehicleStubAsyncResult(request.getServiceRequestId(),
                        carPropertyErrorCodes);
            } catch (RemoteException e) {
                result = new GetVehicleStubAsyncResult(request.getServiceRequestId(),
                        ERROR_CODES_INTERNAL);
            }
            onGetAsyncResultList.add(result);
        }
        mHandler.post(() -> {
            getVehicleStubAsyncCallback.onGetAsyncResults(onGetAsyncResultList);
        });
    }

    /**
     * Sets properties asynchronously.
     *
     * @param setVehicleStubAsyncRequests The async request list.
     * @param setVehicleStubAsyncCallback the callback for setting property values.
     */
    @Override
    public void setAsync(List<AsyncGetSetRequest> setVehicleStubAsyncRequests,
            VehicleStubCallbackInterface setVehicleStubAsyncCallback) {
        List<SetVehicleStubAsyncResult> onSetAsyncResultsList = new ArrayList<>();
        for (int i = 0; i < setVehicleStubAsyncRequests.size(); i++) {
            AsyncGetSetRequest setRequest = setVehicleStubAsyncRequests.get(i);
            int serviceRequestId = setRequest.getServiceRequestId();
            SetVehicleStubAsyncResult result;
            try {
                set(setRequest.getHalPropValue());
                result = new SetVehicleStubAsyncResult(serviceRequestId);
            } catch (RemoteException e) {
                result = new SetVehicleStubAsyncResult(serviceRequestId,
                        ERROR_CODES_INTERNAL);
            } catch (ServiceSpecificException e) {
                CarPropertyErrorCodes carPropertyErrorCodes =
                        createFromVhalStatusCode(e.errorCode);
                result = new SetVehicleStubAsyncResult(serviceRequestId, carPropertyErrorCodes);
            }
            onSetAsyncResultsList.add(result);
        }
        mHandler.post(() -> {
            setVehicleStubAsyncCallback.onSetAsyncResults(onSetAsyncResultsList);
        });
    }

    /**
     * Checks if FakeVehicleStub connects to a valid Vhal.
     *
     * @return {@code true} if connects to a valid Vhal.
     */
    @Override
    public boolean isValid() {
        return mRealVehicle.isValid();
    }

    /**
     * Registers a death recipient that would be called when Vhal died.
     *
     * @param recipient A death recipient.
     * @throws IllegalStateException If unable to register the death recipient.
     */
    @Override
    public void linkToDeath(IVehicleDeathRecipient recipient) throws IllegalStateException {
        mRealVehicle.linkToDeath(recipient);
    }

    /**
     * Unlinks a previously linked death recipient.
     *
     * @param recipient A previously linked death recipient.
     */
    @Override
    public void unlinkToDeath(IVehicleDeathRecipient recipient) {
        mRealVehicle.unlinkToDeath(recipient);
    }

    /**
     * @return {@code true} if car service is connected to FakeVehicleStub.
     */
    @Override
    public boolean isFakeModeEnabled() {
        return false;
    }

    @Override
    public void dump(FileDescriptor fd, List<String> args) throws RemoteException,
            ServiceSpecificException {
        IndentingPrintWriter writer = new IndentingPrintWriter(new PrintWriter(
                new FileOutputStream(fd)));
        synchronized (mLock) {
            writer.println("Fake values: ");
            writer.increaseIndent();
            for (int i = 0; i < mPropValuesByPropIdAreaId.size(); i++) {
                HalPropValue propValue = mPropValuesByPropIdAreaId.valueAt(i);
                writer.println("HalPropValue: " + propValue);
            }
            writer.decreaseIndent();
        }
        mRealVehicle.dump(fd, args);
    }

    /**
     * @return The real vehicle stub.
     */
    @Override
    public VehicleStub getRealVehicleStub() {
        return mRealVehicle;
    }
}
