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
package com.android.car.hal;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.VehiclePropertyIds;
import android.car.builtin.os.BuildHelper;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyAsyncErrorCode;
import android.car.hardware.property.GetPropertyServiceRequest;
import android.car.hardware.property.GetValueResult;
import android.car.hardware.property.IGetAsyncPropertyResultCallback;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.car.VehicleStub;
import com.android.car.VehicleStub.AsyncGetSetRequest;
import com.android.car.VehicleStub.GetVehicleStubAsyncResult;
import com.android.car.VehicleStub.VehicleStubCallbackInterface;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Common interface for HAL services that send Vehicle Properties back and forth via ICarProperty.
 * Services that communicate by passing vehicle properties back and forth via ICarProperty should
 * extend this class.
 */
public class PropertyHalService extends HalServiceBase {
    private static final boolean DBG = true;

    private static final class AsyncGetRequestInfo {
        private final GetPropertyServiceRequest mPropMgrRequest;
        // The uptimeMillis when this request time out.
        private final long mTimeoutUptimeMillis;
        // The remaining timeout in milliseconds for this request.
        private final long mTimeoutInMs;

        AsyncGetRequestInfo(GetPropertyServiceRequest propMgrRequest,
                long timeoutUptimeMillis, long timeoutInMs) {
            mPropMgrRequest = propMgrRequest;
            mTimeoutUptimeMillis = timeoutUptimeMillis;
            mTimeoutInMs = timeoutInMs;
        }

        private int getManagerRequestId() {
            return mPropMgrRequest.getRequestId();
        }

        public int getPropertyId() {
            return mPropMgrRequest.getPropertyId();
        }

        public GetPropertyServiceRequest getGetPropSvcRequest() {
            return mPropMgrRequest;
        }

        public long getTimeoutUptimeMillis() {
            return mTimeoutUptimeMillis;
        }

        public AsyncGetSetRequest toGetVehicleStubAsyncRequest(
                HalPropValueBuilder propValueBuilder, int serviceRequestId) {
            int halPropertyId = managerToHalPropId(mPropMgrRequest.getPropertyId());
            int areaId = mPropMgrRequest.getAreaId();
            return new AsyncGetSetRequest(
                    serviceRequestId, propValueBuilder.build(halPropertyId, areaId), mTimeoutInMs);
        }

        public GetValueResult toErrorGetValueResult(@CarPropertyAsyncErrorCode int errorCode) {
            return new GetValueResult(getManagerRequestId(), /* carPropertyValue= */ null,
                    errorCode);
        }

        public GetValueResult toOkayGetValueResult(CarPropertyValue value) {
            return new GetValueResult(getManagerRequestId(), value,
                    CarPropertyManager.STATUS_OK);
        }
    };

    private final LinkedList<CarPropertyEvent> mEventsToDispatch = new LinkedList<>();
    // The request ID passed by CarPropertyService (ManagerRequestId) is directly passed from
    // CarPropertyManager. Multiple CarPropertyManagers use the same car service instance, thus,
    // the ManagerRequestId is not unique. We have to create another unique ID called
    // ServiceRequestId and pass it to underlying layer (VehicleHal, HalClient and VehicleStub).
    // Internally, we will map ManagerRequestId to ServiceRequestId.
    private final AtomicInteger mServiceRequestIdCounter = new AtomicInteger(0);
    // Only contains property ID if value is different for the CarPropertyManager and the HAL.
    private static final BidirectionalSparseIntArray MGR_PROP_ID_TO_HAL_PROP_ID =
            BidirectionalSparseIntArray.create(
                    new int[]{VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS,
                            VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS});
    private static final String TAG = CarLog.tagFor(PropertyHalService.class);
    private final VehicleHal mVehicleHal;
    private final PropertyHalServiceIds mPropertyHalServiceIds = new PropertyHalServiceIds();
    private final HalPropValueBuilder mPropValueBuilder;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<IBinder, VehicleStubCallbackInterface>
            mResultBinderToVehicleStubCallback = new ArrayMap<>();
    @GuardedBy("mLock")
    private final SparseArray<CarPropertyConfig<?>> mMgrPropIdToCarPropConfig = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<HalPropConfig> mHalPropIdToPropConfig =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<Pair<String, String>> mMgrPropIdToPermissions = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<AsyncGetRequestInfo> mServiceRequestIdToAsyncGetRequestInfo =
            new SparseArray<>();
    @GuardedBy("mLock")
    private PropertyHalListener mPropertyHalListener;
    @GuardedBy("mLock")
    private final Set<Integer> mSubscribedHalPropIds = new ArraySet<>();

    private class VehicleStubCallback extends VehicleStubCallbackInterface {
        private final IGetAsyncPropertyResultCallback mGetAsyncPropertyResultCallback;
        private final IBinder mClientBinder;

        private void sendGetValueResults(List<GetValueResult> results) {
            if (results.isEmpty()) {
                return;
            }
            try {
                mGetAsyncPropertyResultCallback.onGetValueResult(results);
            } catch (RemoteException e) {
                Slogf.w(TAG, "onGetAsyncResults: Client might have died already", e);
            }
        }

        private void retryIfNotExpired(List<AsyncGetRequestInfo> retryRequestInfo) {
            List<AsyncGetSetRequest> getVehicleStubAsyncRequests = new ArrayList<>();
            List<GetValueResult> timeoutResults = new ArrayList<>();
            synchronized (mLock) {
                // Get the current time after obtaining lock since it might take some time to get
                // the lock.
                long currentTimeInMillis = SystemClock.uptimeMillis();
                for (int i = 0; i < retryRequestInfo.size(); i++) {
                    AsyncGetRequestInfo requestInfo = retryRequestInfo.get(i);
                    long timeoutUptimeMillis = requestInfo.getTimeoutUptimeMillis();
                    if (timeoutUptimeMillis <= currentTimeInMillis) {
                        // The request already expired.
                        timeoutResults.add(requestInfo.toErrorGetValueResult(
                                CarPropertyManager.STATUS_ERROR_TIMEOUT));
                        continue;
                    }
                    long timeoutInMs = timeoutUptimeMillis - currentTimeInMillis;
                    // Need to create a new request for the retry.
                    AsyncGetRequestInfo asyncGetRequestInfo = new AsyncGetRequestInfo(
                            requestInfo.getGetPropSvcRequest(), timeoutUptimeMillis, timeoutInMs);
                    getVehicleStubAsyncRequests.add(generateGetVehicleStubAsyncRequestLocked(
                            mPropValueBuilder, asyncGetRequestInfo));
                }
            }
            sendGetValueResults(timeoutResults);
            if (!getVehicleStubAsyncRequests.isEmpty()) {
                mVehicleHal.getAsync(getVehicleStubAsyncRequests, this);
            }
        }

        VehicleStubCallback(
                IGetAsyncPropertyResultCallback getAsyncPropertyResultCallback) {
            mGetAsyncPropertyResultCallback = getAsyncPropertyResultCallback;
            mClientBinder = getAsyncPropertyResultCallback.asBinder();
        }

        @Override
        public void linkToDeath(DeathRecipient recipient) throws RemoteException {
            mClientBinder.linkToDeath(recipient, /* flags= */ 0);
        }

        @Override
        public void onGetAsyncResults(
                List<GetVehicleStubAsyncResult> getVehicleStubAsyncResults) {
            List<GetValueResult> getValueResults = new ArrayList<>();
            List<AsyncGetRequestInfo> retryRequestInfo = new ArrayList<>();
            synchronized (mLock) {
                for (int i = 0; i < getVehicleStubAsyncResults.size(); i++) {
                    GetVehicleStubAsyncResult getVehicleStubAsyncResult =
                            getVehicleStubAsyncResults.get(i);
                    int serviceRequestId = getVehicleStubAsyncResult.getServiceRequestId();
                    AsyncGetRequestInfo clientRequestInfo =
                            getAndRemovePendingAsyncGetRequestInfoLocked(serviceRequestId);
                    if (clientRequestInfo == null) {
                        continue;
                    }
                    int vehicleStubErrorCode = getVehicleStubAsyncResult.getErrorCode();

                    if (vehicleStubErrorCode == VehicleStub.STATUS_TRY_AGAIN) {
                        // We have special logic requests that might need retry.
                        retryRequestInfo.add(clientRequestInfo);
                        continue;
                    }

                    if (vehicleStubErrorCode != CarPropertyManager.STATUS_OK) {
                        // All other error results will be delivered back through callback.
                        getValueResults.add(clientRequestInfo.toErrorGetValueResult(
                                vehicleStubErrorCode));
                        continue;
                    }

                    // For okay status, convert the property value to the type the client expects.
                    int managerPropertyId = clientRequestInfo.getPropertyId();
                    HalPropConfig halPropConfig = mHalPropIdToPropConfig.get(
                            managerToHalPropId(managerPropertyId));
                    CarPropertyValue carPropertyValue = getVehicleStubAsyncResult.getHalPropValue()
                            .toCarPropertyValue(managerPropertyId, halPropConfig);
                    getValueResults.add(clientRequestInfo.toOkayGetValueResult(carPropertyValue));
                }
            }

            sendGetValueResults(getValueResults);

            if (!retryRequestInfo.isEmpty()) {
                retryIfNotExpired(retryRequestInfo);
            }
        }

        @Override
        public void onRequestsTimeout(List<Integer> serviceRequestIds) {
            List<GetValueResult> timeoutResults = new ArrayList<>();
            synchronized (mLock) {
                for (int i = 0; i < serviceRequestIds.size(); i++) {
                    int serviceRequestId = serviceRequestIds.get(i);
                    AsyncGetRequestInfo requestInfo =
                            getAndRemovePendingAsyncGetRequestInfoLocked(serviceRequestId);
                    if (requestInfo == null) {
                        Slogf.w(TAG, "The request for hal svc request ID: %d timed out but no "
                                + "pending request is found. The request may have already been "
                                + "cancelled or finished", serviceRequestId);
                        continue;
                    }
                    timeoutResults.add(requestInfo.toErrorGetValueResult(
                            CarPropertyManager.STATUS_ERROR_TIMEOUT));
                }
            }
            sendGetValueResults(timeoutResults);
        }
    }

    /**
     * Converts manager property ID to Vehicle HAL property ID.
     */
    private static int managerToHalPropId(int mgrPropId) {
        return MGR_PROP_ID_TO_HAL_PROP_ID.getValue(mgrPropId, mgrPropId);
    }

    /**
     * Converts Vehicle HAL property ID to manager property ID.
     */
    private static int halToManagerPropId(int halPropId) {
        return MGR_PROP_ID_TO_HAL_PROP_ID.getKey(halPropId, halPropId);
    }

    // Checks if the property exists in this VHAL before calling methods in IVehicle.
    private boolean isPropertySupportedInVehicle(int halPropId) {
        synchronized (mLock) {
            return mHalPropIdToPropConfig.contains(halPropId);
        }
    }

    private void assertPropertySupported(int halPropId) {
        if (!isPropertySupportedInVehicle(halPropId)) {
            throw new IllegalArgumentException("Vehicle property not supported: "
                    + VehiclePropertyIds.toString(halToManagerPropId(halPropId)));
        }
    }

    // Generates a {@link AsyncGetSetRequest} according to a {@link AsyncGetRequestInfo}.
    //
    // Generates a new PropertyHalService Request ID. Associate the ID with the request and
    // returns a {@link AsyncGetSetRequest} that could be sent to {@link VehicleStub}.
    @GuardedBy("mLock")
    private AsyncGetSetRequest generateGetVehicleStubAsyncRequestLocked(
            HalPropValueBuilder propValueBuilder, AsyncGetRequestInfo asyncGetRequestInfo) {
        int newServiceRequestId = mServiceRequestIdCounter.getAndIncrement();
        mServiceRequestIdToAsyncGetRequestInfo.put(newServiceRequestId, asyncGetRequestInfo);
        return asyncGetRequestInfo.toGetVehicleStubAsyncRequest(propValueBuilder,
                newServiceRequestId);
    }

    @GuardedBy("mLock")
    private @Nullable AsyncGetRequestInfo getAndRemovePendingAsyncGetRequestInfoLocked(
            int serviceRequestId) {
        AsyncGetRequestInfo requestInfo =
                mServiceRequestIdToAsyncGetRequestInfo.get(serviceRequestId);
        mServiceRequestIdToAsyncGetRequestInfo.remove(serviceRequestId);
        if (requestInfo == null) {
            Slogf.w(TAG, "onRequestsTimeout: the request for propertyHalService request "
                    + "ID: %d already timed out or already completed", serviceRequestId);
        }
        return requestInfo;
    }

    /**
     * PropertyHalListener used to send events to CarPropertyService
     */
    public interface PropertyHalListener {
        /**
         * This event is sent whenever the property value is updated
         */
        void onPropertyChange(List<CarPropertyEvent> events);

        /**
         * This event is sent when the set property call fails
         */
        void onPropertySetError(int property, int area,
                @CarPropertyManager.CarSetPropertyErrorCode int errorCode);

    }

    public PropertyHalService(VehicleHal vehicleHal) {
        mVehicleHal = vehicleHal;
        if (DBG) {
            Slogf.d(TAG, "started PropertyHalService");
        }
        mPropValueBuilder = vehicleHal.getHalPropValueBuilder();
    }

    /**
     * Set the listener for the HAL service
     */
    public void setPropertyHalListener(PropertyHalListener propertyHalListener) {
        synchronized (mLock) {
            mPropertyHalListener = propertyHalListener;
        }
    }

    /**
     * @return SparseArray<CarPropertyConfig> List of configs available.
     */
    public SparseArray<CarPropertyConfig<?>> getPropertyList() {
        if (DBG) {
            Slogf.d(TAG, "getPropertyList");
        }
        synchronized (mLock) {
            if (mMgrPropIdToCarPropConfig.size() == 0) {
                for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                    HalPropConfig halPropConfig = mHalPropIdToPropConfig.valueAt(i);
                    int mgrPropId = halToManagerPropId(halPropConfig.getPropId());
                    CarPropertyConfig<?> carPropertyConfig = halPropConfig.toCarPropertyConfig(
                            mgrPropId);
                    mMgrPropIdToCarPropConfig.put(mgrPropId, carPropertyConfig);
                }
            }
            return mMgrPropIdToCarPropConfig;
        }
    }

    /**
     * Returns property or null if property is not ready yet.
     *
     * @param mgrPropId property id in {@link VehiclePropertyIds}
     * @throws IllegalArgumentException if argument is not valid.
     * @throws ServiceSpecificException if there is an exception in HAL.
     */
    @Nullable
    public CarPropertyValue getProperty(int mgrPropId, int areaId)
            throws IllegalArgumentException, ServiceSpecificException {
        int halPropId = managerToHalPropId(mgrPropId);
        assertPropertySupported(halPropId);

        // CarPropertyManager catches and rethrows exception, no need to handle here.
        HalPropValue halPropValue = mVehicleHal.get(halPropId, areaId);
        if (halPropValue == null) {
            return null;
        }
        HalPropConfig halPropConfig;
        synchronized (mLock) {
            halPropConfig = mHalPropIdToPropConfig.get(halPropId);
        }
        return halPropValue.toCarPropertyValue(mgrPropId, halPropConfig);
    }

    /**
     * Returns sample rate for the property
     */
    public float getSampleRate(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        assertPropertySupported(halPropId);
        return mVehicleHal.getSampleRate(halPropId);
    }

    /**
     * Get the read permission string for the property.
     */
    @Nullable
    public String getReadPermission(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropertyHalServiceIds.getReadPermission(halPropId);
    }

    /**
     * Get the write permission string for the property.
     */
    @Nullable
    public String getWritePermission(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropertyHalServiceIds.getWritePermission(halPropId);
    }

    /**
     * Get permissions for all properties in the vehicle.
     *
     * @return a SparseArray. key: propertyId, value: Pair(readPermission, writePermission).
     */
    @NonNull
    public SparseArray<Pair<String, String>> getPermissionsForAllProperties() {
        synchronized (mLock) {
            if (mMgrPropIdToPermissions.size() != 0) {
                return mMgrPropIdToPermissions;
            }
            for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                int halPropId = mHalPropIdToPropConfig.keyAt(i);
                mMgrPropIdToPermissions.put(halToManagerPropId(halPropId),
                        new Pair<>(mPropertyHalServiceIds.getReadPermission(halPropId),
                                mPropertyHalServiceIds.getWritePermission(halPropId)));
            }
            return mMgrPropIdToPermissions;
        }
    }

    /**
     * Return true if property is a display_units property
     */
    public boolean isDisplayUnitsProperty(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropertyHalServiceIds.isPropertyToChangeUnits(halPropId);
    }

    /**
     * Set the property value.
     *
     * @throws IllegalArgumentException if argument is invalid.
     * @throws ServiceSpecificException if there is an exception in HAL.
     */
    public void setProperty(CarPropertyValue carPropertyValue)
            throws IllegalArgumentException, ServiceSpecificException {
        int halPropId = managerToHalPropId(carPropertyValue.getPropertyId());
        assertPropertySupported(halPropId);
        HalPropConfig halPropConfig;
        synchronized (mLock) {
            halPropConfig = mHalPropIdToPropConfig.get(halPropId);
        }
        HalPropValue halPropValue = mPropValueBuilder.build(carPropertyValue, halPropId,
                halPropConfig);
        // CarPropertyManager catches and rethrows exception, no need to handle here.
        mVehicleHal.set(halPropValue);
    }

    /**
     * Subscribe to this property at the specified update rate.
     *
     * @throws IllegalArgumentException thrown if property is not supported by VHAL.
     */
    public void subscribeProperty(int mgrPropId, float updateRate) throws IllegalArgumentException {
        if (DBG) {
            Slogf.d(TAG, "subscribeProperty propId: %s, rate=%f",
                    VehiclePropertyIds.toString(mgrPropId), updateRate);
        }
        int halPropId = managerToHalPropId(mgrPropId);
        assertPropertySupported(halPropId);
        float rate = updateRate;
        synchronized (mLock) {
            HalPropConfig halPropConfig = mHalPropIdToPropConfig.get(halPropId);
            if (rate > halPropConfig.getMaxSampleRate()) {
                rate = halPropConfig.getMaxSampleRate();
            } else if (rate < halPropConfig.getMinSampleRate()) {
                rate = halPropConfig.getMinSampleRate();
            }
            mSubscribedHalPropIds.add(halPropId);
        }

        mVehicleHal.subscribeProperty(this, halPropId, rate);
    }

    /**
     * Unsubscribe the property and turn off update events for it.
     */
    public void unsubscribeProperty(int mgrPropId) {
        if (DBG) {
            Slogf.d(TAG, "unsubscribeProperty propId=%s", VehiclePropertyIds.toString(mgrPropId));
        }
        int halPropId = managerToHalPropId(mgrPropId);
        assertPropertySupported(halPropId);
        synchronized (mLock) {
            if (mSubscribedHalPropIds.contains(halPropId)) {
                mSubscribedHalPropIds.remove(halPropId);
                mVehicleHal.unsubscribeProperty(this, halPropId);
            }
        }
    }

    @Override
    public void init() {
        if (DBG) {
            Slogf.d(TAG, "init()");
        }
    }

    @Override
    public void release() {
        if (DBG) {
            Slogf.d(TAG, "release()");
        }
        synchronized (mLock) {
            for (Integer halPropId : mSubscribedHalPropIds) {
                mVehicleHal.unsubscribeProperty(this, halPropId);
            }
            mSubscribedHalPropIds.clear();
            mHalPropIdToPropConfig.clear();
            mMgrPropIdToCarPropConfig.clear();
            mMgrPropIdToPermissions.clear();
            mPropertyHalListener = null;
        }
    }

    @Override
    public boolean isSupportedProperty(int halPropId) {
        return mPropertyHalServiceIds.isSupportedProperty(halPropId);
    }

    @Override
    public int[] getAllSupportedProperties() {
        return CarServiceUtils.EMPTY_INT_ARRAY;
    }

    // The method is called in HAL init(). Avoid handling complex things in here.
    @Override
    public void takeProperties(Collection<HalPropConfig> halPropConfigs) {
        for (HalPropConfig halPropConfig : halPropConfigs) {
            int halPropId = halPropConfig.getPropId();
            if (isSupportedProperty(halPropId)) {
                synchronized (mLock) {
                    mHalPropIdToPropConfig.put(halPropId, halPropConfig);
                }
                if (DBG) {
                    Slogf.d(TAG, "takeSupportedProperties: %s",
                            VehiclePropertyIds.toString(halToManagerPropId(halPropId)));
                }
            }
        }
        if (DBG) {
            Slogf.d(TAG, "takeSupportedProperties() took %d properties", halPropConfigs.size());
        }
        // If vehicle hal support to select permission for vendor properties.
        HalPropConfig customizePermission;
        synchronized (mLock) {
            customizePermission = mHalPropIdToPropConfig.get(
                    VehicleProperty.SUPPORT_CUSTOMIZE_VENDOR_PERMISSION);
        }
        if (customizePermission != null) {
            mPropertyHalServiceIds.customizeVendorPermission(customizePermission.getConfigArray());
        }
    }

    @Override
    public void onHalEvents(List<HalPropValue> halPropValues) {
        PropertyHalListener propertyHalListener;
        synchronized (mLock) {
            propertyHalListener = mPropertyHalListener;
        }
        if (propertyHalListener != null) {
            for (HalPropValue halPropValue : halPropValues) {
                if (halPropValue == null) {
                    continue;
                }
                int halPropId = halPropValue.getPropId();
                if (!isPropertySupportedInVehicle(halPropId)) {
                    Slogf.w(TAG, "onHalEvents - received HalPropValue for unsupported property: %s",
                            VehiclePropertyIds.toString(halToManagerPropId(halPropId)));
                    continue;
                }
                // Check payload if it is an userdebug build.
                if (BuildHelper.isDebuggableBuild() && !mPropertyHalServiceIds.checkPayload(
                        halPropValue)) {
                    Slogf.w(TAG,
                            "Drop event for property: %s because it is failed "
                                    + "in payload checking.", halPropValue);
                    continue;
                }
                int mgrPropId = halToManagerPropId(halPropId);
                HalPropConfig halPropConfig;
                synchronized (mLock) {
                    halPropConfig = mHalPropIdToPropConfig.get(halPropId);
                }
                CarPropertyValue<?> carPropertyValue = halPropValue.toCarPropertyValue(mgrPropId,
                        halPropConfig);
                CarPropertyEvent carPropertyEvent = new CarPropertyEvent(
                        CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, carPropertyValue);
                mEventsToDispatch.add(carPropertyEvent);
            }
            propertyHalListener.onPropertyChange(mEventsToDispatch);
            mEventsToDispatch.clear();
        }
    }

    @Override
    public void onPropertySetError(ArrayList<VehiclePropError> vehiclePropErrors) {
        PropertyHalListener propertyHalListener;
        synchronized (mLock) {
            propertyHalListener = mPropertyHalListener;
        }
        if (propertyHalListener != null) {
            for (VehiclePropError vehiclePropError : vehiclePropErrors) {
                int mgrPropId = halToManagerPropId(vehiclePropError.propId);
                propertyHalListener.onPropertySetError(mgrPropId, vehiclePropError.areaId,
                        vehiclePropError.errorCode);
            }
        }
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("  Properties available:");
        synchronized (mLock) {
            for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                HalPropConfig halPropConfig = mHalPropIdToPropConfig.valueAt(i);
                writer.println("    " + halPropConfig.toString());
            }
        }
    }

    /**
     * Query CarPropertyValue with list of GetPropertyServiceRequest objects.
     *
     * <p>This method gets the CarPropertyValue using async methods. </p>
     */
    public void getCarPropertyValuesAsync(
            List<GetPropertyServiceRequest> getPropertyServiceRequests,
            IGetAsyncPropertyResultCallback getAsyncPropertyResultCallback,
            long timeoutInMs) {
        // TODO(b/242326085): Change local variables into memory pool to reduce memory
        //  allocation/release cycle
        List<AsyncGetSetRequest> getVehicleStubAsyncRequests = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < getPropertyServiceRequests.size(); i++) {
                GetPropertyServiceRequest getPropertyServiceRequest =
                        getPropertyServiceRequests.get(i);
                AsyncGetRequestInfo asyncGetRequestInfo = new AsyncGetRequestInfo(
                        getPropertyServiceRequest, SystemClock.uptimeMillis() + timeoutInMs,
                        timeoutInMs);
                AsyncGetSetRequest vehicleStubRequest = generateGetVehicleStubAsyncRequestLocked(
                        mPropValueBuilder, asyncGetRequestInfo);
                getVehicleStubAsyncRequests.add(vehicleStubRequest);
            }
        }

        IBinder getAsyncPropertyResultBinder = getAsyncPropertyResultCallback.asBinder();
        VehicleStubCallbackInterface callback;
        synchronized (mLock) {
            if (mResultBinderToVehicleStubCallback.get(getAsyncPropertyResultBinder) == null) {
                callback = new VehicleStubCallback(getAsyncPropertyResultCallback);
                try {
                    callback.linkToDeath(() -> {
                        synchronized (mLock) {
                            mResultBinderToVehicleStubCallback.remove(getAsyncPropertyResultBinder);
                        }
                    });
                } catch (RemoteException e) {
                    throw new IllegalStateException("Linking to binder death recipient failed, "
                            + "the client might already died", e);
                }
                mResultBinderToVehicleStubCallback.put(getAsyncPropertyResultBinder, callback);
            } else {
                callback = mResultBinderToVehicleStubCallback.get(getAsyncPropertyResultBinder);
            }
        }
        mVehicleHal.getAsync(getVehicleStubAsyncRequests, callback);
    }

    /**
     * Map managerRequestIds to serviceRequestIds and remove them from the pending request map.
     */
    public void cancelRequests(int[] managerRequestIds) {
        List<Integer> serviceRequestIdsToCancel = new ArrayList<>();
        Set<Integer> managerRequestIdsSet = new ArraySet<>();
        for (int i = 0; i < managerRequestIds.length; i++) {
            managerRequestIdsSet.add(managerRequestIds[i]);
        }
        synchronized (mLock) {
            for (int i = 0; i < mServiceRequestIdToAsyncGetRequestInfo.size(); i++) {
                if (managerRequestIdsSet.contains(mServiceRequestIdToAsyncGetRequestInfo.valueAt(i)
                        .getManagerRequestId())) {
                    serviceRequestIdsToCancel.add(mServiceRequestIdToAsyncGetRequestInfo.keyAt(i));
                }
            }
            for (int i = 0; i < serviceRequestIdsToCancel.size(); i++) {
                Slogf.w(TAG, "the request for propertyHalService request ID: %d is cancelled",
                        serviceRequestIdsToCancel.get(i));
                mServiceRequestIdToAsyncGetRequestInfo.remove(serviceRequestIdsToCancel.get(i));
            }
        }
        if (!serviceRequestIdsToCancel.isEmpty()) {
            mVehicleHal.cancelRequests(serviceRequestIdsToCancel);
        }
    }
}
