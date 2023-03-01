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

import static android.car.hardware.property.VehicleHalStatusCode.STATUS_INTERNAL_ERROR;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_NOT_AVAILABLE;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;
import static com.android.car.internal.property.CarPropertyHelper.STATUS_OK;

import android.annotation.IntDef;
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
import android.hardware.automotive.vehicle.VehiclePropError;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.os.Handler;
import android.os.HandlerThread;
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
import com.android.car.VehicleStub.SetVehicleStubAsyncResult;
import com.android.car.VehicleStub.VehicleStubCallbackInterface;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.property.AsyncPropertyServiceRequest;
import com.android.car.internal.property.CarPropertyHelper;
import com.android.car.internal.property.GetSetValueResult;
import com.android.car.internal.property.IAsyncPropertyResultCallback;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
    // This must be set to false for release.
    private static final boolean DBG = false;
    private static final int ASYNC_RETRY_SLEEP_IN_MS = 100;

    // Async get request from user.
    private static final int GET = 0;
    // Async set request from user.
    private static final int SET = 1;
    // Async get request for getting initial value when user issues async set property request.
    // The reason we need to get initial value is that if the value to be set is the same as
    // the current value, there might not be a property update event generated. In this case,
    // it should be considered a success. If we get the initial value successfully and the
    // initial value is the same as the target value, we treat the async set as success.
    private static final int GET_INITIAL_VALUE_FOR_SET = 2;

    // Different type of async get/set property requests.
    @IntDef({GET, SET, GET_INITIAL_VALUE_FOR_SET})
    @Retention(RetentionPolicy.SOURCE)
    private @interface AsyncRequestType {}

    private static final class AsyncPropRequestInfo {
        private final AsyncPropertyServiceRequest mPropMgrRequest;
        // The uptimeMillis when this request time out.
        private final long mTimeoutUptimeMillis;
        // The remaining timeout in milliseconds for this request.
        private final long mTimeoutInMs;

        private final @AsyncRequestType int mRequestType;

        AsyncPropRequestInfo(@AsyncRequestType int requestType,
                AsyncPropertyServiceRequest propMgrRequest,
                long timeoutUptimeMillis, long timeoutInMs) {
            mPropMgrRequest = propMgrRequest;
            mTimeoutUptimeMillis = timeoutUptimeMillis;
            mTimeoutInMs = timeoutInMs;
            mRequestType = requestType;
        }

        private @AsyncRequestType int getRequestType() {
            return mRequestType;
        }

        private int getManagerRequestId() {
            return mPropMgrRequest.getRequestId();
        }

        public int getPropertyId() {
            return mPropMgrRequest.getPropertyId();
        }

        public int getAreaId() {
            return mPropMgrRequest.getAreaId();
        }

        public long getTimeoutInMs() {
            return mTimeoutInMs;
        }

        public AsyncPropertyServiceRequest getPropSvcRequest() {
            return mPropMgrRequest;
        }

        public long getTimeoutUptimeMillis() {
            return mTimeoutUptimeMillis;
        }

        public GetSetValueResult toErrorGetValueResult(@CarPropertyAsyncErrorCode int errorCode) {
            return GetSetValueResult.newErrorGetValueResult(getManagerRequestId(), errorCode);
        }

        public GetSetValueResult toGetValueResult(CarPropertyValue value) {
            return GetSetValueResult.newGetValueResult(getManagerRequestId(), value);
        }
    };

    private final LinkedList<CarPropertyEvent> mEventsToDispatch = new LinkedList<>();
    // The request ID passed by CarPropertyService (ManagerRequestId) is directly passed from
    // CarPropertyManager. Multiple CarPropertyManagers use the same car service instance, thus,
    // the ManagerRequestId is not unique. We have to create another unique ID called
    // ServiceRequestId and pass it to underlying layer (VehicleHal and VehicleStub).
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
    // A map from a unique propertyHalService request ID to async property request info which
    // includes original request from CarPropertyManager and timeout info.
    @GuardedBy("mLock")
    private final SparseArray<AsyncPropRequestInfo> mServiceRequestIdToAsyncPropRequestInfo =
            new SparseArray<>();
    @GuardedBy("mLock")
    private PropertyHalListener mPropertyHalListener;
    @GuardedBy("mLock")
    private final Set<Integer> mSubscribedHalPropIds = new ArraySet<>();

    private final HandlerThread mHandlerThread =
            CarServiceUtils.getHandlerThread(getClass().getSimpleName());
    private final Handler mHandler = new Handler(mHandlerThread.getLooper());

    private class VehicleStubCallback extends VehicleStubCallbackInterface {
        private final IAsyncPropertyResultCallback mAsyncPropertyResultCallback;
        private final IBinder mClientBinder;

        private void sendGetValueResults(List<GetSetValueResult> results) {
            if (results.isEmpty()) {
                return;
            }
            try {
                mAsyncPropertyResultCallback.onGetValueResults(results);
            } catch (RemoteException e) {
                Slogf.w(TAG, "sendGetValueResults: Client might have died already", e);
            }
        }

        private void sendSetValueResults(List<GetSetValueResult> results) {
            if (results.isEmpty()) {
                return;
            }
            try {
                mAsyncPropertyResultCallback.onSetValueResults(results);
            } catch (RemoteException e) {
                Slogf.w(TAG, "sendSetValueResults: Client might have died already", e);
            }
        }

        private void retryIfNotExpired(List<AsyncPropRequestInfo> retryRequests) {
            List<AsyncGetSetRequest> vehicleStubAsyncGetRequests = new ArrayList<>();
            List<GetSetValueResult> timeoutGetResults = new ArrayList<>();
            List<AsyncGetSetRequest> vehicleStubAsyncSetRequests = new ArrayList<>();
            List<GetSetValueResult> timeoutSetResults = new ArrayList<>();
            synchronized (mLock) {
                // Get the current time after obtaining lock since it might take some time to get
                // the lock.
                long currentTimeInMillis = SystemClock.uptimeMillis();
                for (int i = 0; i < retryRequests.size(); i++) {
                    AsyncPropRequestInfo requestInfo = retryRequests.get(i);
                    long timeoutUptimeMillis = requestInfo.getTimeoutUptimeMillis();
                    if (timeoutUptimeMillis <= currentTimeInMillis) {
                        // The request already expired.
                        GetSetValueResult timeoutResult = requestInfo.toErrorGetValueResult(
                                CarPropertyManager.STATUS_ERROR_TIMEOUT);
                        switch (requestInfo.getRequestType()) {
                            case GET: // fallthrough
                            case GET_INITIAL_VALUE_FOR_SET:
                                timeoutGetResults.add(timeoutResult);
                                break;
                            case SET:
                                timeoutSetResults.add(timeoutResult);
                                break;
                        }
                        continue;
                    }
                    // Need to create a new request for the retry.
                    AsyncPropRequestInfo asyncPropRequestInfo = new AsyncPropRequestInfo(
                            requestInfo.getRequestType(), requestInfo.getPropSvcRequest(),
                            timeoutUptimeMillis, requestInfo.getTimeoutInMs());

                    AsyncGetSetRequest vehicleStubAsyncRequest =
                            generateVehicleStubAsyncRequestLocked(asyncPropRequestInfo);

                    switch (requestInfo.getRequestType()) {
                        case GET: // fallthrough
                        case GET_INITIAL_VALUE_FOR_SET:
                            vehicleStubAsyncGetRequests.add(vehicleStubAsyncRequest);
                            break;
                        case SET:
                            vehicleStubAsyncSetRequests.add(vehicleStubAsyncRequest);
                            break;
                    }
                }
            }

            sendGetValueResults(timeoutGetResults);
            if (!vehicleStubAsyncGetRequests.isEmpty()) {
                mVehicleHal.getAsync(vehicleStubAsyncGetRequests, this);
            }
            sendSetValueResults(timeoutSetResults);
            if (!vehicleStubAsyncSetRequests.isEmpty()) {
                mVehicleHal.setAsync(vehicleStubAsyncSetRequests, this);
            }
        }

        VehicleStubCallback(
                IAsyncPropertyResultCallback asyncPropertyResultCallback) {
            mAsyncPropertyResultCallback = asyncPropertyResultCallback;
            mClientBinder = asyncPropertyResultCallback.asBinder();
        }

        // This is a wrapper for death recipient that will unlink itself upon binder death.
        private final class DeathRecipientWrapper implements DeathRecipient {
            private DeathRecipient mInnerRecipient;

            DeathRecipientWrapper(DeathRecipient innerRecipient) {
                mInnerRecipient = innerRecipient;
            }

            @Override
            public void binderDied() {
                mInnerRecipient.binderDied();
                mClientBinder.unlinkToDeath(this, /* flags= */ 0);
            }
        }

        @Override
        public void linkToDeath(DeathRecipient recipient) throws RemoteException {
            mClientBinder.linkToDeath(new DeathRecipientWrapper(recipient),
                    /* flags= */ 0);
        }

        @Override
        public void onGetAsyncResults(
                List<GetVehicleStubAsyncResult> getVehicleStubAsyncResults) {
            List<GetSetValueResult> getValueResults = new ArrayList<>();
            List<AsyncPropRequestInfo> retryRequests = new ArrayList<>();
            synchronized (mLock) {
                for (int i = 0; i < getVehicleStubAsyncResults.size(); i++) {
                    GetVehicleStubAsyncResult getVehicleStubAsyncResult =
                            getVehicleStubAsyncResults.get(i);
                    int serviceRequestId = getVehicleStubAsyncResult.getServiceRequestId();
                    AsyncPropRequestInfo clientRequestInfo =
                            getAndRemovePendingAsyncPropRequestInfoLocked(serviceRequestId);
                    if (clientRequestInfo == null) {
                        Slogf.w(TAG, "async request for ID: " + serviceRequestId + " not found, "
                                + "ignore the result");
                        continue;
                    }
                    int vehicleStubErrorCode = getVehicleStubAsyncResult.getErrorCode();

                    if (vehicleStubErrorCode == VehicleStub.STATUS_TRY_AGAIN) {
                        // The request might need to be retried.
                        retryRequests.add(clientRequestInfo);
                        continue;
                    }

                    if (vehicleStubErrorCode != STATUS_OK) {
                        // All other error results will be delivered back through callback.
                        getValueResults.add(clientRequestInfo.toErrorGetValueResult(
                                vehicleStubErrorCode));
                        continue;
                    }

                    // For okay status, convert the property value to the type the client expects.
                    int mgrPropId = clientRequestInfo.getPropertyId();
                    HalPropConfig halPropConfig = mHalPropIdToPropConfig.get(
                            managerToHalPropId(mgrPropId));
                    HalPropValue halPropValue = getVehicleStubAsyncResult.getHalPropValue();
                    if (halPropValue.getStatus() == VehiclePropertyStatus.UNAVAILABLE) {
                        getValueResults.add(clientRequestInfo.toErrorGetValueResult(
                                STATUS_NOT_AVAILABLE));
                        continue;
                    }
                    if (halPropValue.getStatus() != VehiclePropertyStatus.AVAILABLE) {
                        getValueResults.add(clientRequestInfo.toErrorGetValueResult(
                                STATUS_INTERNAL_ERROR));
                        continue;
                    }
                    CarPropertyValue carPropertyValue;
                    try {
                        carPropertyValue = halPropValue.toCarPropertyValue(mgrPropId,
                                halPropConfig);
                    } catch (IllegalStateException e) {
                        Slogf.e(TAG, e, "Cannot convert halPropValue to carPropertyValue, property:"
                                + " %s, areaId: %d, exception: %s",
                                VehiclePropertyIds.toString(mgrPropId), halPropValue.getAreaId());
                        getValueResults.add(clientRequestInfo.toErrorGetValueResult(
                                STATUS_INTERNAL_ERROR));
                        continue;
                    }
                    getValueResults.add(clientRequestInfo.toGetValueResult(carPropertyValue));
                }
            }

            sendGetValueResults(getValueResults);

            if (!retryRequests.isEmpty()) {
                mHandler.postDelayed(() -> {
                    retryIfNotExpired(retryRequests);
                }, ASYNC_RETRY_SLEEP_IN_MS);
            }
        }

        @Override
        public void onSetAsyncResults(
                List<SetVehicleStubAsyncResult> setVehicleStubAsyncResults) {
            List<GetSetValueResult> setValueResults = new ArrayList<>();
            List<AsyncPropRequestInfo> retryRequests = new ArrayList<>();
            synchronized (mLock) {
                for (int i = 0; i < setVehicleStubAsyncResults.size(); i++) {
                    SetVehicleStubAsyncResult setVehicleStubAsyncResult =
                            setVehicleStubAsyncResults.get(i);
                    int serviceRequestId = setVehicleStubAsyncResult.getServiceRequestId();
                    AsyncPropRequestInfo clientRequestInfo =
                            getPendingAsyncPropRequestInfoLocked(serviceRequestId);
                    if (clientRequestInfo == null) {
                        Slogf.w(TAG, "async request for ID: " + serviceRequestId + " not found, "
                                + "ignore the result");
                        continue;
                    }
                    int vehicleStubErrorCode = setVehicleStubAsyncResult.getErrorCode();

                    if (vehicleStubErrorCode == VehicleStub.STATUS_TRY_AGAIN) {
                        // The request might need to be retried.
                        retryRequests.add(clientRequestInfo);
                        mServiceRequestIdToAsyncPropRequestInfo.remove(serviceRequestId);
                        continue;
                    }

                    if (vehicleStubErrorCode != STATUS_OK) {
                        // All other error results will be delivered back through callback.
                        setValueResults.add(clientRequestInfo.toErrorGetValueResult(
                                vehicleStubErrorCode));
                        mServiceRequestIdToAsyncPropRequestInfo.remove(serviceRequestId);
                        continue;
                    }

                    // TODO(b/264719384): If the result is okay, we should check whether we have
                    // received the property update event or if the initial value is the target
                    // value.
                    // If so, delete the pending request and mark the operation as complete.
                    // Otherwise, wait for property update event or initial value to arrive.
                    mServiceRequestIdToAsyncPropRequestInfo.remove(serviceRequestId);
                }
            }

            sendSetValueResults(setValueResults);

            if (!retryRequests.isEmpty()) {
                mHandler.postDelayed(() -> {
                    retryIfNotExpired(retryRequests);
                }, ASYNC_RETRY_SLEEP_IN_MS);
            }
        }

        @Override
        public void onRequestsTimeout(List<Integer> serviceRequestIds) {
            List<GetSetValueResult> timeoutGetResults = new ArrayList<>();
            List<GetSetValueResult> timeoutSetResults = new ArrayList<>();
            synchronized (mLock) {
                for (int i = 0; i < serviceRequestIds.size(); i++) {
                    int serviceRequestId = serviceRequestIds.get(i);
                    AsyncPropRequestInfo requestInfo =
                            getAndRemovePendingAsyncPropRequestInfoLocked(serviceRequestId);
                    if (requestInfo == null) {
                        Slogf.w(TAG, "The request for hal svc request ID: %d timed out but no "
                                + "pending request is found. The request may have already been "
                                + "cancelled or finished", serviceRequestId);
                        continue;
                    }
                    GetSetValueResult timeoutResult = requestInfo.toErrorGetValueResult(
                                CarPropertyManager.STATUS_ERROR_TIMEOUT);
                    switch (requestInfo.getRequestType()) {
                        case GET: // fallthrough
                        case GET_INITIAL_VALUE_FOR_SET:
                            timeoutGetResults.add(timeoutResult);
                            break;
                        case SET:
                            timeoutSetResults.add(timeoutResult);
                            break;
                    }

                }
            }
            sendGetValueResults(timeoutGetResults);
            sendSetValueResults(timeoutSetResults);
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

    private static void checkHalPropValueStatus(HalPropValue halPropValue, int mgrPropId,
            int areaId) {
        if (halPropValue.getStatus() == VehiclePropertyStatus.UNAVAILABLE) {
            throw new ServiceSpecificException(STATUS_NOT_AVAILABLE,
                    "VHAL returned property status as UNAVAILABLE for property: "
                    + VehiclePropertyIds.toString(mgrPropId) + ", areaId: " + areaId);
        }
        if (halPropValue.getStatus() == VehiclePropertyStatus.ERROR) {
            throw new ServiceSpecificException(STATUS_INTERNAL_ERROR,
                    "VHAL returned property status as ERROR for property: "
                    + VehiclePropertyIds.toString(mgrPropId) + ", areaId: " + areaId);
        }
    }

    /**
     * Generates a {@link AsyncGetSetRequest} according to a {@link AsyncPropRequestInfo}.
     *
     * <p>Generates a new PropertyHalService Request ID. Associate the ID with the request and
     * returns a {@link AsyncGetSetRequest} that could be sent to {@link VehicleStub}.
     */
    @GuardedBy("mLock")
    private AsyncGetSetRequest generateVehicleStubAsyncRequestLocked(
            AsyncPropRequestInfo asyncPropRequestInfo) {
        int newServiceRequestId = mServiceRequestIdCounter.getAndIncrement();
        mServiceRequestIdToAsyncPropRequestInfo.put(newServiceRequestId, asyncPropRequestInfo);

        HalPropValue halPropValue;
        CarPropertyValue requestCarPropertyValue = asyncPropRequestInfo.getPropSvcRequest()
                .getCarPropertyValue();
        if (requestCarPropertyValue != null) {
            // If this is a set request, the car property value stores the value to be set.
            halPropValue = carPropertyValueToHalPropValueLocked(requestCarPropertyValue);
        } else {
            // Otherwise this is a get request, we only need the property ID and area ID.
            int halPropertyId = managerToHalPropId(asyncPropRequestInfo.getPropertyId());
            int areaId = asyncPropRequestInfo.getAreaId();
            halPropValue = mPropValueBuilder.build(halPropertyId, areaId);
        }
        return new AsyncGetSetRequest(newServiceRequestId, halPropValue,
                asyncPropRequestInfo.getTimeoutInMs());
    }

    @GuardedBy("mLock")
    @Nullable private AsyncPropRequestInfo getPendingAsyncPropRequestInfoLocked(
            int serviceRequestId) {
        AsyncPropRequestInfo requestInfo =
                mServiceRequestIdToAsyncPropRequestInfo.get(serviceRequestId);
        if (requestInfo == null) {
            Slogf.w(TAG, "onRequestsTimeout: the request for propertyHalService request "
                    + "ID: %d already timed out or already completed", serviceRequestId);
        }
        return requestInfo;
    }

    @GuardedBy("mLock")
    @Nullable private AsyncPropRequestInfo getAndRemovePendingAsyncPropRequestInfoLocked(
            int serviceRequestId) {
        AsyncPropRequestInfo requestInfo = getPendingAsyncPropRequestInfoLocked(serviceRequestId);
        mServiceRequestIdToAsyncPropRequestInfo.remove(serviceRequestId);
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
     * Returns property value.
     *
     * @param mgrPropId property id in {@link VehiclePropertyIds}
     * @throws IllegalArgumentException if argument is not valid.
     * @throws ServiceSpecificException if there is an exception in HAL or the property status is
     *                                  not available.
     */
    public CarPropertyValue getProperty(int mgrPropId, int areaId)
            throws IllegalArgumentException, ServiceSpecificException {
        int halPropId = managerToHalPropId(mgrPropId);
        // CarPropertyManager catches and rethrows exception, no need to handle here.
        HalPropValue halPropValue = mVehicleHal.get(halPropId, areaId);
        HalPropConfig halPropConfig;
        synchronized (mLock) {
            halPropConfig = mHalPropIdToPropConfig.get(halPropId);
        }
        checkHalPropValueStatus(halPropValue, mgrPropId, areaId);
        try {
            return halPropValue.toCarPropertyValue(mgrPropId, halPropConfig);
        } catch (IllegalStateException e) {
            throw new ServiceSpecificException(STATUS_INTERNAL_ERROR,
                    "Cannot convert halPropValue to carPropertyValue, property: "
                    + VehiclePropertyIds.toString(mgrPropId) + " areaId: " + areaId
                    + ", exception: " + e);
        }
    }

    /**
     * Returns sample rate for the property
     */
    public float getSampleRate(int mgrPropId) {
        return mVehicleHal.getSampleRate(managerToHalPropId(mgrPropId));
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
        HalPropValue valueToSet;
        synchronized (mLock) {
            valueToSet = carPropertyValueToHalPropValueLocked(carPropertyValue);
        }

        // CarPropertyManager catches and rethrows exception, no need to handle here.
        mVehicleHal.set(valueToSet);
    }

    /**
     * Subscribe to this property at the specified update updateRateHz.
     *
     * @throws IllegalArgumentException thrown if property is not supported by VHAL.
     */
    public void subscribeProperty(int mgrPropId, float updateRateHz)
            throws IllegalArgumentException {
        if (DBG) {
            Slogf.d(TAG, "subscribeProperty propertyId: %s, updateRateHz=%f",
                    VehiclePropertyIds.toString(mgrPropId), updateRateHz);
        }
        int halPropId = managerToHalPropId(mgrPropId);
        synchronized (mLock) {
            mSubscribedHalPropIds.add(halPropId);
        }
        mVehicleHal.subscribeProperty(this, halPropId, updateRateHz);
    }

    /**
     * Unsubscribe the property and turn off update events for it.
     */
    public void unsubscribeProperty(int mgrPropId) {
        if (DBG) {
            Slogf.d(TAG, "unsubscribeProperty mgrPropId=%s",
                    VehiclePropertyIds.toString(mgrPropId));
        }
        int halPropId = managerToHalPropId(mgrPropId);
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
        return mPropertyHalServiceIds.isSupportedProperty(halPropId)
                && CarPropertyHelper.isSupported(halToManagerPropId(halPropId));
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
                HalPropConfig halPropConfig;
                synchronized (mLock) {
                    halPropConfig = mHalPropIdToPropConfig.get(halPropId);
                }
                if (halPropConfig == null) {
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
                if (halPropValue.getStatus() != VehiclePropertyStatus.AVAILABLE) {
                    Slogf.w(TAG, "Drop event %s with status that is not AVAILABLE", halPropValue);
                    continue;
                }
                try {
                    CarPropertyValue<?> carPropertyValue = halPropValue.toCarPropertyValue(
                            mgrPropId, halPropConfig);
                    CarPropertyEvent carPropertyEvent = new CarPropertyEvent(
                            CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, carPropertyValue);
                    mEventsToDispatch.add(carPropertyEvent);
                } catch (IllegalStateException e) {
                    Slogf.w(TAG, "Drop event %s that does not have valid value", halPropValue);
                    continue;
                }
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

    private void getOrSetCarPropertyValuesAsync(@AsyncRequestType int requestType,
            List<AsyncPropertyServiceRequest> serviceRequests,
            IAsyncPropertyResultCallback asyncPropertyResultCallback,
            long timeoutInMs) {
        // TODO(b/242326085): Change local variables into memory pool to reduce memory
        //  allocation/release cycle
        List<AsyncGetSetRequest> vehicleStubRequests = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < serviceRequests.size(); i++) {
                AsyncPropertyServiceRequest serviceRequest = serviceRequests.get(i);
                AsyncPropRequestInfo asyncPropRequestInfo = new AsyncPropRequestInfo(requestType,
                        serviceRequest, SystemClock.uptimeMillis() + timeoutInMs, timeoutInMs);
                AsyncGetSetRequest vehicleStubRequest = generateVehicleStubAsyncRequestLocked(
                        asyncPropRequestInfo);
                vehicleStubRequests.add(vehicleStubRequest);
            }
        }

        IBinder asyncPropertyResultBinder = asyncPropertyResultCallback.asBinder();
        VehicleStubCallbackInterface callback;
        synchronized (mLock) {
            if (mResultBinderToVehicleStubCallback.get(asyncPropertyResultBinder) == null) {
                callback = new VehicleStubCallback(asyncPropertyResultCallback);
                try {
                    callback.linkToDeath(() -> {
                        synchronized (mLock) {
                            mResultBinderToVehicleStubCallback.remove(asyncPropertyResultBinder);
                        }
                    });
                } catch (RemoteException e) {
                    throw new IllegalStateException("Linking to binder death recipient failed, "
                            + "the client might already died", e);
                }
                mResultBinderToVehicleStubCallback.put(asyncPropertyResultBinder, callback);
            } else {
                callback = mResultBinderToVehicleStubCallback.get(asyncPropertyResultBinder);
            }
        }

        switch (requestType) {
            case GET: // fallthrough
            case GET_INITIAL_VALUE_FOR_SET:
                mVehicleHal.getAsync(vehicleStubRequests, callback);
                break;
            case SET:
                mVehicleHal.setAsync(vehicleStubRequests, callback);
                break;
        }
    }

    /**
     * Queries CarPropertyValue with list of AsyncPropertyServiceRequest objects.
     *
     * <p>This method gets the CarPropertyValue using async methods. </p>
     */
    public void getCarPropertyValuesAsync(
            List<AsyncPropertyServiceRequest> serviceRequests,
            IAsyncPropertyResultCallback asyncPropertyResultCallback,
            long timeoutInMs) {
        getOrSetCarPropertyValuesAsync(
                GET, serviceRequests, asyncPropertyResultCallback, timeoutInMs);
    }

    /**
     * Sets car property values asynchronously.
     */
    public void setCarPropertyValuesAsync(
            List<AsyncPropertyServiceRequest> serviceRequests,
            IAsyncPropertyResultCallback asyncPropertyResultCallback,
            long timeoutInMs) {
        getOrSetCarPropertyValuesAsync(
                SET, serviceRequests, asyncPropertyResultCallback, timeoutInMs);
    }

    /**
     * Maps managerRequestIds to serviceRequestIds and remove them from the pending request map.
     */
    public void cancelRequests(int[] managerRequestIds) {
        List<Integer> serviceRequestIdsToCancel = new ArrayList<>();
        Set<Integer> managerRequestIdsSet = new ArraySet<>();
        for (int i = 0; i < managerRequestIds.length; i++) {
            managerRequestIdsSet.add(managerRequestIds[i]);
        }
        synchronized (mLock) {
            for (int i = 0; i < mServiceRequestIdToAsyncPropRequestInfo.size(); i++) {
                if (managerRequestIdsSet.contains(mServiceRequestIdToAsyncPropRequestInfo.valueAt(i)
                        .getManagerRequestId())) {
                    serviceRequestIdsToCancel.add(mServiceRequestIdToAsyncPropRequestInfo.keyAt(i));
                }
            }
            for (int i = 0; i < serviceRequestIdsToCancel.size(); i++) {
                Slogf.w(TAG, "the request for propertyHalService request ID: %d is cancelled",
                        serviceRequestIdsToCancel.get(i));
                mServiceRequestIdToAsyncPropRequestInfo.remove(serviceRequestIdsToCancel.get(i));
            }
        }
        if (!serviceRequestIdsToCancel.isEmpty()) {
            mVehicleHal.cancelRequests(serviceRequestIdsToCancel);
        }
    }

    @GuardedBy("mLock")
    private HalPropValue carPropertyValueToHalPropValueLocked(CarPropertyValue carPropertyValue) {
        int mgrPropId = carPropertyValue.getPropertyId();
        int halPropId = managerToHalPropId(mgrPropId);
        HalPropConfig halPropConfig = mHalPropIdToPropConfig.get(halPropId);
        if (halPropConfig == null) {
            throw new IllegalArgumentException("Property ID: " + mgrPropId + " is not supported");
        }
        return mPropValueBuilder.build(carPropertyValue, halPropId, halPropConfig);
    }
}
