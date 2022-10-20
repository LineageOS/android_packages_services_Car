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
import android.car.hardware.property.CarPropertyManager;
import android.car.util.concurrent.AndroidAsyncFuture;
import android.car.util.concurrent.AndroidFuture;
import android.hardware.automotive.vehicle.GetValueRequest;
import android.hardware.automotive.vehicle.GetValueRequests;
import android.hardware.automotive.vehicle.GetValueResult;
import android.hardware.automotive.vehicle.GetValueResults;
import android.hardware.automotive.vehicle.IVehicle;
import android.hardware.automotive.vehicle.IVehicleCallback;
import android.hardware.automotive.vehicle.SetValueRequest;
import android.hardware.automotive.vehicle.SetValueRequests;
import android.hardware.automotive.vehicle.SetValueResult;
import android.hardware.automotive.vehicle.SetValueResults;
import android.hardware.automotive.vehicle.StatusCode;
import android.hardware.automotive.vehicle.SubscribeOptions;
import android.hardware.automotive.vehicle.VehiclePropConfig;
import android.hardware.automotive.vehicle.VehiclePropConfigs;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.hardware.automotive.vehicle.VehiclePropErrors;
import android.hardware.automotive.vehicle.VehiclePropValue;
import android.hardware.automotive.vehicle.VehiclePropValues;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LongSparseArray;

import com.android.car.hal.AidlHalPropConfig;
import com.android.car.hal.HalClientCallback;
import com.android.car.hal.HalPropConfig;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;
import com.android.car.internal.LargeParcelable;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

final class AidlVehicleStub extends VehicleStub {

    private static final String AIDL_VHAL_SERVICE =
            "android.hardware.automotive.vehicle.IVehicle/default";
    // default timeout: 10s
    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final IVehicle mAidlVehicle;
    private final HalPropValueBuilder mPropValueBuilder;
    private final GetSetValuesCallback mGetSetValuesCallback;
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final AtomicLong mRequestId = new AtomicLong(0);
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final LongSparseArray<AndroidFuture<GetValueResult>>
            mPendingSyncGetValueRequestsByVhalRequestId = new LongSparseArray();
    @GuardedBy("mLock")
    private final LongSparseArray<AsyncRequestInfo> mPendingAsyncRequestsByVhalRequestId =
            new LongSparseArray();
    @GuardedBy("mLock")
    private final LongSparseArray<AndroidFuture<SetValueResult>>
            mPendingSyncSetValueRequestsByVhalRequestId = new LongSparseArray();

    // This might be modifed during tests.
    private long mSyncOpTimeoutInMs = DEFAULT_TIMEOUT_MS;

    private static class AsyncRequestInfo {
        private final int mServiceRequestId;
        private final VehicleStubCallbackInterface mClientCallback;

        private AsyncRequestInfo(int serviceRequestId,
                VehicleStubCallbackInterface clientCallback) {
            mServiceRequestId = serviceRequestId;
            mClientCallback = clientCallback;
        }

        public int getServiceRequestId() {
            return mServiceRequestId;
        }

        public VehicleStubCallbackInterface getClientCallback() {
            return mClientCallback;
        }
    }

    AidlVehicleStub() {
        this(getAidlVehicle());
    }

    @VisibleForTesting
    AidlVehicleStub(IVehicle aidlVehicle) {
        this(aidlVehicle,
                CarServiceUtils.getHandlerThread(AidlVehicleStub.class.getSimpleName()));
    }

    @VisibleForTesting
    AidlVehicleStub(IVehicle aidlVehicle, HandlerThread handlerThread) {
        mAidlVehicle = aidlVehicle;
        mPropValueBuilder = new HalPropValueBuilder(/*isAidl=*/true);
        mHandlerThread = handlerThread;
        mHandler = new Handler(mHandlerThread.getLooper());
        mGetSetValuesCallback = new GetSetValuesCallback();
    }

    /**
     * Sets the timeout for getValue/setValue requests in milliseconds.
     */
    @VisibleForTesting
    void setSyncOpTimeoutInMs(long timeoutMs) {
        mSyncOpTimeoutInMs = timeoutMs;
    }

    @VisibleForTesting
    int countPendingRequests() {
        synchronized (mLock) {
            return mPendingSyncGetValueRequestsByVhalRequestId.size()
                    + mPendingSyncSetValueRequestsByVhalRequestId.size();
        }
    }

    /**
     * Checks whether we are connected to AIDL VHAL: {@code true} or HIDL VHAL: {@code false}.
     */
    @Override
    public boolean isAidlVhal() {
        return true;
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
        long vhalRequestId = mRequestId.getAndIncrement();

        AndroidFuture<GetValueResult> resultFuture = new AndroidFuture();
        synchronized (mLock) {
            mPendingSyncGetValueRequestsByVhalRequestId.put(vhalRequestId, resultFuture);
        }

        GetValueRequest request = new GetValueRequest();
        request.requestId = vhalRequestId;
        request.prop = (VehiclePropValue) requestedPropValue.toVehiclePropValue();
        GetRequestConverter converter = new GetRequestConverter();
        GetValueRequests requests = converter.toLargeParcelable(new GetValueRequest[]{request});
        mAidlVehicle.getValues(mGetSetValuesCallback, requests);

        AndroidAsyncFuture<GetValueResult> asyncResultFuture = new AndroidAsyncFuture(resultFuture);
        try {
            GetValueResult result = asyncResultFuture.get(mSyncOpTimeoutInMs,
                    TimeUnit.MILLISECONDS);
            if (result.status != StatusCode.OK) {
                throw new ServiceSpecificException(
                        result.status, "failed to get value: " + request.prop);
            }
            if (result.prop == null) {
                return null;
            }
            return mPropValueBuilder.build(result.prop);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore the interrupted status
            throw new ServiceSpecificException(StatusCode.INTERNAL_ERROR,
                    "thread interrupted, possibly exiting the thread");
        } catch (ExecutionException e) {
            throw new ServiceSpecificException(StatusCode.INTERNAL_ERROR,
                    "failed to resolve GetValue future, error: " + e);
        } catch (TimeoutException e) {
            synchronized (mLock) {
                mPendingSyncGetValueRequestsByVhalRequestId.remove(vhalRequestId);
            }
            throw new ServiceSpecificException(StatusCode.INTERNAL_ERROR,
                    "get value request timeout for property: " + request.prop);
        }
    }

    @Override
    public void getAsync(List<AsyncGetSetRequest> getVehicleStubAsyncRequests,
            VehicleStubCallbackInterface getCallback) {
        LongSparseArray<List<Long>> vhalRequestIdsByTimeoutInMs = new LongSparseArray<>();

        GetValueRequests requests = prepareAndConvertAsyncRequests(
                getVehicleStubAsyncRequests, getCallback, new GetRequestConverter(),
                vhalRequestIdsByTimeoutInMs);

        try {
            mAidlVehicle.getValues(mGetSetValuesCallback, requests);
        } catch (RemoteException e) {
            handleExceptionFromVhal(requests, getCallback,
                    CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        } catch (ServiceSpecificException e) {
            handleExceptionFromVhal(requests, getCallback,
                    convertHalToCarPropertyManagerError(e.errorCode));
        }

        // Register the timeout handlers for the added requests. Only register if the requests
        // are sent successfully.
        addTimeoutHandlers(vhalRequestIdsByTimeoutInMs);
    }

    @Override
    public void setAsync(List<AsyncGetSetRequest> setVehicleStubAsyncRequests,
            VehicleStubCallbackInterface setCallback) {
        // TODO(b/251213448): Implement this.
    }

    /**
     * Callback to deliver error results back to the client.
     *
     * <p>When an exception is received, the callback delivers the error results on the same thread
     * where the caller is.
     */
    private void handleExceptionFromVhal(GetValueRequests requests,
            VehicleStubCallbackInterface getCallback, int errorCode) {
        Slogf.w(CarLog.TAG_SERVICE,
                "Received RemoteException or ServiceSpecificException from VHAL. VHAL is likely "
                        + "dead.");
        List<GetVehicleStubAsyncResult> getVehicleStubAsyncResults = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < requests.payloads.length; i++) {
                long vhalRequestId = requests.payloads[i].requestId;
                AsyncRequestInfo requestInfo = getAndRemovePendingRequestInfoLocked(vhalRequestId);
                if (requestInfo == null) {
                    Slogf.w(CarLog.TAG_SERVICE,
                            "No pending request for ID: %s, possibly already timed out or "
                            + "the client already died", vhalRequestId);
                    continue;
                }
                getVehicleStubAsyncResults.add(
                        new GetVehicleStubAsyncResult(requestInfo.getServiceRequestId(),
                        errorCode));
            }
        }
        getCallback.onGetAsyncResults(getVehicleStubAsyncResults);
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
        SetValueRequest request = new SetValueRequest();
        long requestId = mRequestId.getAndIncrement();

        AndroidFuture<SetValueResult> resultFuture = new AndroidFuture();
        synchronized (mLock) {
            mPendingSyncSetValueRequestsByVhalRequestId.put(requestId, resultFuture);
        }

        request.requestId = requestId;
        request.value = (VehiclePropValue) propValue.toVehiclePropValue();
        SetValueRequests requests = new SetValueRequests();
        requests.payloads = new SetValueRequest[]{request};
        requests = (SetValueRequests) LargeParcelable.toLargeParcelable(requests, () -> {
            SetValueRequests newRequests = new SetValueRequests();
            newRequests.payloads = new SetValueRequest[0];
            return newRequests;
        });

        mAidlVehicle.setValues(mGetSetValuesCallback, requests);

        AndroidAsyncFuture<SetValueResult> asyncResultFuture = new AndroidAsyncFuture(resultFuture);
        try {
            SetValueResult result = asyncResultFuture.get(mSyncOpTimeoutInMs,
                    TimeUnit.MILLISECONDS);
            if (result.status != StatusCode.OK) {
                throw new ServiceSpecificException(
                        result.status, "failed to set value: " + request.value);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore the interrupted status
            throw new ServiceSpecificException(StatusCode.INTERNAL_ERROR,
                    "thread interrupted, possibly exiting the thread");
        } catch (ExecutionException e) {
            throw new ServiceSpecificException(StatusCode.INTERNAL_ERROR,
                    "failed to resolve SetValue future, error: " + e);
        } catch (TimeoutException e) {
            synchronized (mLock) {
                mPendingSyncSetValueRequestsByVhalRequestId.remove(requestId);
            }
            throw new ServiceSpecificException(StatusCode.INTERNAL_ERROR,
                    "set value request timeout for property: " + request.value);
        }
    }

    @Override
    public void dump(FileDescriptor fd, List<String> args) throws RemoteException {
        mAidlVehicle.asBinder().dump(fd, args.toArray(new String[args.size()]));
    }

    // Get all the VHAL request IDs according to the service request IDs and remove them from
    // pending requests map.
    @Override
    public void cancelRequests(List<Integer> serviceRequestIds) {
        Set<Integer> serviceRequestIdsSet = new ArraySet<>(serviceRequestIds);
        List<Long> vhalRequestIdsToCancel = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mPendingAsyncRequestsByVhalRequestId.size(); i++) {
                int serviceRequestId = mPendingAsyncRequestsByVhalRequestId.valueAt(i)
                        .getServiceRequestId();
                if (serviceRequestIdsSet.contains(serviceRequestId)) {
                    vhalRequestIdsToCancel.add(mPendingAsyncRequestsByVhalRequestId.keyAt(i));
                }
            }
            for (int i = 0; i < vhalRequestIdsToCancel.size(); i++) {
                long vhalRequestIdToCancel = vhalRequestIdsToCancel.get(i);
                Slogf.w(CarLog.TAG_SERVICE, "the request for VHAL request ID: %d is cancelled",
                        vhalRequestIdToCancel);
                mPendingAsyncRequestsByVhalRequestId.remove(vhalRequestIdToCancel);
            }
        }
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

        @Override
        public String getInterfaceHash() {
            return IVehicleCallback.HASH;
        }

        @Override
        public int getInterfaceVersion() {
            return IVehicleCallback.VERSION;
        }
    }

    private void onGetSyncPropertyResult(GetValueResult result, long vhalRequestId) {
        AndroidFuture<GetValueResult> pendingRequest;
        synchronized (mLock) {
            pendingRequest = mPendingSyncGetValueRequestsByVhalRequestId.get(vhalRequestId);
            mPendingSyncGetValueRequestsByVhalRequestId.remove(vhalRequestId);
        }
        if (pendingRequest == null) {
            Slogf.w(CarLog.TAG_SERVICE, "No pending request for ID: " + vhalRequestId
                    + ", possibly already timed out");
            return;
        }
        mHandler.post(() -> {
            // This might fail if the request already timed out.
            pendingRequest.complete(result);
        });
    }

    private void onGetAsyncPropertyResult(GetValueResult result, long vhalRequestId,
            Map<VehicleStubCallbackInterface, List<GetVehicleStubAsyncResult>> callbackToResult) {
        AsyncRequestInfo requestInfo;
        synchronized (mLock) {
            requestInfo = getAndRemovePendingRequestInfoLocked(vhalRequestId);
        }
        if (requestInfo == null) {
            Slogf.w(CarLog.TAG_SERVICE,
                    "No pending request for ID: %s, possibly already timed out"
                    + " or the client already died", vhalRequestId);
            return;
        }
        int serviceRequestId = requestInfo.getServiceRequestId();
        GetVehicleStubAsyncResult getVehicleStubAsyncResult;
        if (result.status != StatusCode.OK) {
            getVehicleStubAsyncResult = new GetVehicleStubAsyncResult(serviceRequestId,
                    convertHalToCarPropertyManagerError(result.status));
        } else if (result.prop == null) {
            // If status is OKAY but no property is returned, treat it as not_available.
            getVehicleStubAsyncResult = new GetVehicleStubAsyncResult(serviceRequestId,
                    CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        } else {
            getVehicleStubAsyncResult = new GetVehicleStubAsyncResult(serviceRequestId,
                    mPropValueBuilder.build(result.prop));
        }
        VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                requestInfo.getClientCallback();
        if (callbackToResult.get(getVehicleStubAsyncCallback) == null) {
            callbackToResult.put(getVehicleStubAsyncCallback, new ArrayList<>());
        }
        callbackToResult.get(getVehicleStubAsyncCallback).add(getVehicleStubAsyncResult);
    }

    private void onGetValues(GetValueResults responses) {
        GetValueResults origResponses = (GetValueResults)
                LargeParcelable.reconstructStableAIDLParcelable(responses,
                        /* keepSharedMemory= */ false);
        Map<VehicleStubCallbackInterface, List<GetVehicleStubAsyncResult>> callbackToResult =
                new ArrayMap<>();
        synchronized (mLock) {
            for (GetValueResult result : origResponses.payloads) {
                long vhalRequestId = result.requestId;
                if (mPendingAsyncRequestsByVhalRequestId.get(vhalRequestId) == null) {
                    onGetSyncPropertyResult(result, vhalRequestId);
                } else {
                    onGetAsyncPropertyResult(result, vhalRequestId, callbackToResult);
                }
            }
        }
        callGetAsyncCallbacks(callbackToResult);
    }

    private void callGetAsyncCallbacks(
            Map<VehicleStubCallbackInterface, List<GetVehicleStubAsyncResult>> callbackToResult) {
        for (Map.Entry<VehicleStubCallbackInterface, List<GetVehicleStubAsyncResult>> entry :
                callbackToResult.entrySet()) {
            VehicleStubCallbackInterface getVehicleStubAsyncCallback = entry.getKey();
            getVehicleStubAsyncCallback.onGetAsyncResults(entry.getValue());
        }
    }

    private int convertHalToCarPropertyManagerError(int errorCode) {
        switch (errorCode) {
            case StatusCode.NOT_AVAILABLE:
                return CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE;
            case StatusCode.TRY_AGAIN:
                return STATUS_TRY_AGAIN;
            default:
                return CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR;
        }
    }

    private void onSetValues(SetValueResults responses) {
        SetValueResults origResponses = (SetValueResults)
                LargeParcelable.reconstructStableAIDLParcelable(responses,
                        /*keepSharedMemory=*/false);
        for (SetValueResult result : origResponses.payloads) {
            long requestId = result.requestId;
            AndroidFuture<SetValueResult> pendingRequest;
            synchronized (mLock) {
                pendingRequest = mPendingSyncSetValueRequestsByVhalRequestId.get(requestId);
                mPendingSyncSetValueRequestsByVhalRequestId.remove(requestId);
            }
            if (pendingRequest == null) {
                Slogf.w(CarLog.TAG_SERVICE, "No pending request for ID: " + requestId
                        + ", possibly already timed out");
                return;
            }
            mHandler.post(() -> {
                // This might fail if the request already timed out.
                pendingRequest.complete(result);
            });
        }
    }

    private final class GetSetValuesCallback extends IVehicleCallback.Stub {

        @Override
        public void onGetValues(GetValueResults responses) throws RemoteException {
            AidlVehicleStub.this.onGetValues(responses);
        }

        @Override
        public void onSetValues(SetValueResults responses) throws RemoteException {
            AidlVehicleStub.this.onSetValues(responses);
        }

        @Override
        public void onPropertyEvent(VehiclePropValues propValues, int sharedMemoryFileCount)
                throws RemoteException {
            throw new UnsupportedOperationException(
                    "GetSetValuesCallback only support onGetValues or onSetValues");
        }

        @Override
        public void onPropertySetError(VehiclePropErrors errors) throws RemoteException {
            throw new UnsupportedOperationException(
                    "GetSetValuesCallback only support onGetValues or onSetValues");
        }

        @Override
        public String getInterfaceHash() {
            return IVehicleCallback.HASH;
        }

        @Override
        public int getInterfaceVersion() {
            return IVehicleCallback.VERSION;
        }
    }

    // Remove all the pending client info that has the specified callback.
    private void removePendingAsyncClientForCallback(VehicleStubCallbackInterface callback) {
        synchronized (mLock) {
            List<Long> requestIdsToRemove = new ArrayList<>();

            for (int i = 0; i < mPendingAsyncRequestsByVhalRequestId.size(); i++) {
                if (mPendingAsyncRequestsByVhalRequestId.valueAt(i).getClientCallback()
                        == callback) {
                    requestIdsToRemove.add(mPendingAsyncRequestsByVhalRequestId.keyAt(i));
                }
            }

            for (int i = 0; i < requestIdsToRemove.size(); i++) {
                mPendingAsyncRequestsByVhalRequestId.delete(requestIdsToRemove.get(i));
            }
        }
    }

    private void addTimeoutHandlers(LongSparseArray<List<Long>> vhalRequestIdsByTimeoutInMs) {
        for (int i = 0; i < vhalRequestIdsByTimeoutInMs.size(); i++) {
            long timeoutInMs = vhalRequestIdsByTimeoutInMs.keyAt(i);
            List<Long> vhalRequestIds = vhalRequestIdsByTimeoutInMs.valueAt(i);
            mHandler.postDelayed(() -> {
                requestsTimedout(vhalRequestIds);
            }, timeoutInMs);
        }
    }

    private void requestsTimedout(List<Long> vhalRequestIds) {
        Map<VehicleStubCallbackInterface, List<Integer>> timedoutServiceRequestIdsByCallback =
                new ArrayMap<>();
        synchronized (mLock) {
            for (int i = 0; i < vhalRequestIds.size(); i++) {
                long vhalRequestId = vhalRequestIds.get(i);
                AsyncRequestInfo requestInfo = getAndRemovePendingRequestInfoLocked(
                        vhalRequestId);
                if (requestInfo == null) {
                    // We already finished the request or the callback is already dead, ignore.
                    continue;
                }
                VehicleStubCallbackInterface getAsyncCallback = requestInfo.getClientCallback();
                if (timedoutServiceRequestIdsByCallback.get(getAsyncCallback) == null) {
                    timedoutServiceRequestIdsByCallback.put(getAsyncCallback, new ArrayList<>());
                }
                timedoutServiceRequestIdsByCallback.get(getAsyncCallback).add(
                        requestInfo.getServiceRequestId());
            }
        }

        for (VehicleStubCallbackInterface callback : timedoutServiceRequestIdsByCallback.keySet()) {
            callback.onRequestsTimeout(timedoutServiceRequestIdsByCallback.get(callback));
        }
    }

    @GuardedBy("mLock")
    private @Nullable AsyncRequestInfo getAndRemovePendingRequestInfoLocked(long vhalRequestId) {
        AsyncRequestInfo requestInfo = mPendingAsyncRequestsByVhalRequestId.get(vhalRequestId);
        mPendingAsyncRequestsByVhalRequestId.remove(vhalRequestId);
        return requestInfo;
    }

    private interface RequestConverter<VhalRequestType, VhalRequestsType> {
        VhalRequestType[] newVhalRequestArray(int size);
        VhalRequestType toVhalRequest(AsyncGetSetRequest clientRequest, long vhalRequestId);
        VhalRequestsType toLargeParcelable(VhalRequestType[] vhalRequestItems);
    }

    private static final class GetRequestConverter
            implements RequestConverter<GetValueRequest, GetValueRequests> {
        @Override
        public GetValueRequest[] newVhalRequestArray(int size) {
            return new GetValueRequest[size];
        }

        @Override
        public GetValueRequest toVhalRequest(AsyncGetSetRequest clientRequest, long vhalRequestId) {
            GetValueRequest vhalRequest = new GetValueRequest();
            vhalRequest.requestId = vhalRequestId;
            vhalRequest.prop = (VehiclePropValue) clientRequest.getHalPropValue()
                    .toVehiclePropValue();
            return vhalRequest;
        }

        @Override
        public GetValueRequests toLargeParcelable(GetValueRequest[] vhalRequestItems) {
            GetValueRequests requests = new GetValueRequests();
            requests.payloads = vhalRequestItems;
            requests = (GetValueRequests) LargeParcelable.toLargeParcelable(requests, () -> {
                GetValueRequests newRequests = new GetValueRequests();
                newRequests.payloads = new GetValueRequest[0];
                return newRequests;
            });
            return requests;
        }
    }

    /**
     * Prepare an async get/set request from client and convert it to vhal requests.
     *
     * <p> It does the following things:
     * <ul>
     * <li> Add a client callback death listener which will clear the pending requests when client
     * died
     * <li> Store the async requests to a pending request map.
     * <li> For each client request, generate a unique VHAL request ID and convert the request to
     * VHAL request type.
     * <li> Stores the time-out information for each request into a map so that we can register
     * timeout handlers later.
     * <li> Convert the vhal request items to a single large parcelable class.
     */
    private <VhalRequestType, VhalRequestsType> VhalRequestsType prepareAndConvertAsyncRequests(
            List<AsyncGetSetRequest> vehicleStubRequests,
            VehicleStubCallbackInterface clientCallback,
            RequestConverter<VhalRequestType, VhalRequestsType> requestConverter,
            LongSparseArray<List<Long>> outVhalRequestIdsByTimeoutInMs) {
        VhalRequestType[] outVhalRequests = requestConverter.newVhalRequestArray(
                vehicleStubRequests.size());
        synchronized (mLock) {
            // Add the death recipient so that all client info for a dead callback will be cleaned
            // up. Note that this must be in the same critical section as the following code to
            // store the client info into the map. This makes sure that even if the client is
            // died half way while adding the client info, it will wait until all the clients are
            // added and then remove them all.
            try {
                clientCallback.linkToDeath(() -> {
                    removePendingAsyncClientForCallback(clientCallback);
                });
            } catch (RemoteException e) {
                // The binder is already died.
                throw new IllegalStateException("Failed to link callback to death recipient, the "
                        + "client maybe already died");
            }

            for (int i = 0; i < vehicleStubRequests.size(); i++) {
                AsyncGetSetRequest vehicleStubRequest = vehicleStubRequests.get(i);
                long vhalRequestId = mRequestId.getAndIncrement();
                AsyncRequestInfo requestInfo = new AsyncRequestInfo(
                        vehicleStubRequest.getServiceRequestId(), clientCallback);
                mPendingAsyncRequestsByVhalRequestId.put(vhalRequestId, requestInfo);

                long timeoutInMs = vehicleStubRequest.getTimeoutInMs();
                if (outVhalRequestIdsByTimeoutInMs.get(timeoutInMs) == null) {
                    outVhalRequestIdsByTimeoutInMs.put(timeoutInMs, new ArrayList<Long>());
                }
                outVhalRequestIdsByTimeoutInMs.get(timeoutInMs).add(vhalRequestId);
                outVhalRequests[i] = requestConverter.toVhalRequest(vehicleStubRequest,
                        vhalRequestId);
            }
        }
        return requestConverter.toLargeParcelable(outVhalRequests);
    }
}
