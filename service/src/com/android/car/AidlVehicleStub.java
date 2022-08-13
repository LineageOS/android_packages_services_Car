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
    private long mTimeoutMs = DEFAULT_TIMEOUT_MS;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final LongSparseArray<AndroidFuture<GetValueResult>>
            mPendingSyncGetValueRequestsByVhalRequestId = new LongSparseArray();
    @GuardedBy("mLock")
    private final LongSparseArray<AsyncClientInfo> mPendingAsyncGetValueCallbacksByVhalRequestId =
            new LongSparseArray();
    @GuardedBy("mLock")
    private final LongSparseArray<AndroidFuture<SetValueResult>>
            mPendingSyncSetValueRequestsByVhalRequestId = new LongSparseArray();

    private static class AsyncClientInfo {
        private final int mServiceRequestId;
        private final GetAsyncVehicleStubCallback mGetAsyncVehicleStubCallback;

        private AsyncClientInfo(int serviceRequestId,
                GetAsyncVehicleStubCallback getAsyncVehicleStubCallback) {
            mServiceRequestId = serviceRequestId;
            mGetAsyncVehicleStubCallback = getAsyncVehicleStubCallback;
        }

        public int getServiceRequestId() {
            return mServiceRequestId;
        }

        public GetAsyncVehicleStubCallback getGetAsyncVehicleStubCallback() {
            return mGetAsyncVehicleStubCallback;
        }
    }

    AidlVehicleStub() {
        this(getAidlVehicle());
    }

    @VisibleForTesting
    AidlVehicleStub(IVehicle aidlVehicle) {
        mAidlVehicle = aidlVehicle;
        mPropValueBuilder = new HalPropValueBuilder(/*isAidl=*/true);
        mHandlerThread = CarServiceUtils.getHandlerThread(AidlVehicleStub.class.getSimpleName());
        mHandler = new Handler(mHandlerThread.getLooper());
        mGetSetValuesCallback = new GetSetValuesCallback();
    }

    /**
     * Sets the timeout for getValue/setValue requests in milliseconds.
     */
    @VisibleForTesting
    void setTimeoutMs(long timeoutMs) {
        mTimeoutMs = timeoutMs;
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
        GetValueRequest request = new GetValueRequest();
        long vhalRequestId = mRequestId.getAndIncrement();

        AndroidFuture<GetValueResult> resultFuture = new AndroidFuture();
        synchronized (mLock) {
            mPendingSyncGetValueRequestsByVhalRequestId.put(vhalRequestId, resultFuture);
        }

        request.requestId = vhalRequestId;
        request.prop = (VehiclePropValue) requestedPropValue.toVehiclePropValue();
        GetValueRequests requests = new GetValueRequests();
        requests.payloads = new GetValueRequest[]{request};
        requests = (GetValueRequests) LargeParcelable.toLargeParcelable(requests, () -> {
            GetValueRequests newRequests = new GetValueRequests();
            newRequests.payloads = new GetValueRequest[0];
            return newRequests;
        });
        mAidlVehicle.getValues(mGetSetValuesCallback, requests);

        AndroidAsyncFuture<GetValueResult> asyncResultFuture = new AndroidAsyncFuture(resultFuture);
        try {
            GetValueResult result = asyncResultFuture.get(mTimeoutMs, TimeUnit.MILLISECONDS);
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
    public void getAsync(List<GetVehicleStubAsyncRequest> getVehicleStubAsyncRequests,
            GetAsyncVehicleStubCallback getAsyncVehicleStubCallback) {
        GetValueRequest[] getValueRequests =
                new GetValueRequest[getVehicleStubAsyncRequests.size()];
        synchronized (mLock) {
            for (int i = 0; i < getVehicleStubAsyncRequests.size(); i++) {
                GetVehicleStubAsyncRequest getVehicleStubAsyncRequest =
                        getVehicleStubAsyncRequests.get(i);
                long vhalRequestId = mRequestId.getAndIncrement();
                mPendingAsyncGetValueCallbacksByVhalRequestId.put(vhalRequestId,
                        new AsyncClientInfo(getVehicleStubAsyncRequest.getServiceRequestId(),
                                getAsyncVehicleStubCallback));
                GetValueRequest vhalRequest = new GetValueRequest();
                vhalRequest.requestId = vhalRequestId;
                vhalRequest.prop = (VehiclePropValue) getVehicleStubAsyncRequest.getHalPropValue()
                        .toVehiclePropValue();
                getValueRequests[i] = vhalRequest;
            }
        }
        GetValueRequests requests = new GetValueRequests();
        requests.payloads = getValueRequests;
        requests = (GetValueRequests) LargeParcelable.toLargeParcelable(requests, () -> {
            GetValueRequests newRequests = new GetValueRequests();
            newRequests.payloads = new GetValueRequest[0];
            return newRequests;
        });
        try {
            mAidlVehicle.getValues(mGetSetValuesCallback, requests);
        } catch (RemoteException e) {
            handleRemoteExceptionFromVhal(requests, getAsyncVehicleStubCallback);
        }
    }

    private void handleRemoteExceptionFromVhal(GetValueRequests requests,
            GetAsyncVehicleStubCallback getAsyncVehicleStubCallback) {
        Slogf.w(CarLog.TAG_SERVICE, "Received RemoteException from VHAL. VHAL is likely dead.");
        List<GetVehicleStubAsyncResult> getVehicleStubAsyncResults = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < requests.payloads.length; i++) {
                long vhalRequestId = requests.payloads[i].requestId;
                AsyncClientInfo clientInfo = mPendingAsyncGetValueCallbacksByVhalRequestId
                        .get(vhalRequestId);
                mPendingAsyncGetValueCallbacksByVhalRequestId.remove(vhalRequestId);
                if (clientInfo == null) {
                    Slogf.w(CarLog.TAG_SERVICE,
                            "No pending request for ID: %s, possibly already timed out",
                            vhalRequestId);
                    continue;
                }
                getVehicleStubAsyncResults.add(
                        new GetVehicleStubAsyncResult(clientInfo.getServiceRequestId(),
                                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR));
            }
        }
        getAsyncVehicleStubCallback.onGetAsyncResults(getVehicleStubAsyncResults);
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
            SetValueResult result = asyncResultFuture.get(mTimeoutMs, TimeUnit.MILLISECONDS);
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
            Map<GetAsyncVehicleStubCallback, List<GetVehicleStubAsyncResult>> callbackToResult) {
        AsyncClientInfo clientInfo;
        synchronized (mLock) {
            clientInfo = mPendingAsyncGetValueCallbacksByVhalRequestId.get(vhalRequestId);
            mPendingAsyncGetValueCallbacksByVhalRequestId.remove(vhalRequestId);
        }
        if (clientInfo == null) {
            Slogf.w(CarLog.TAG_SERVICE,
                    "No pending request for ID: %s, possibly already timed out", vhalRequestId);
            return;
        }
        int serviceRequestId = clientInfo.getServiceRequestId();
        GetVehicleStubAsyncResult getVehicleStubAsyncResult;
        if (result.prop == null) {
            getVehicleStubAsyncResult = new GetVehicleStubAsyncResult(serviceRequestId,
                    CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        } else if (result.status != StatusCode.OK) {
            getVehicleStubAsyncResult = new GetVehicleStubAsyncResult(serviceRequestId,
                    convertHalToCarPropertyManagerError(result));
        } else {
            getVehicleStubAsyncResult = new GetVehicleStubAsyncResult(serviceRequestId,
                    mPropValueBuilder.build(result.prop));
        }
        GetAsyncVehicleStubCallback getAsyncVehicleStubCallback;
        getAsyncVehicleStubCallback = clientInfo.getGetAsyncVehicleStubCallback();
        if (getAsyncVehicleStubCallback == null) {
            Slogf.w(CarLog.TAG_SERVICE,
                    "No pending request for ID: %s, possibly already timed out", vhalRequestId);
            return;
        }
        if (callbackToResult.get(getAsyncVehicleStubCallback) == null) {
            callbackToResult.put(getAsyncVehicleStubCallback, new ArrayList<>());
        }
        callbackToResult.get(getAsyncVehicleStubCallback).add(getVehicleStubAsyncResult);
    }

    private void onGetValues(GetValueResults responses) {
        GetValueResults origResponses = (GetValueResults)
                LargeParcelable.reconstructStableAIDLParcelable(responses,
                        /* keepSharedMemory= */ false);
        Map<GetAsyncVehicleStubCallback, List<GetVehicleStubAsyncResult>> callbackToResult =
                new ArrayMap<>();
        synchronized (mLock) {
            for (GetValueResult result : origResponses.payloads) {
                long vhalRequestId = result.requestId;
                if (mPendingAsyncGetValueCallbacksByVhalRequestId.get(vhalRequestId) == null) {
                    onGetSyncPropertyResult(result, vhalRequestId);
                } else {
                    onGetAsyncPropertyResult(result, vhalRequestId, callbackToResult);
                }
            }
        }
        callGetAsyncCallbacks(callbackToResult);
    }

    private void callGetAsyncCallbacks(
            Map<GetAsyncVehicleStubCallback, List<GetVehicleStubAsyncResult>> callbackToResult) {
        for (Map.Entry<GetAsyncVehicleStubCallback, List<GetVehicleStubAsyncResult>> entry :
                callbackToResult.entrySet()) {
            GetAsyncVehicleStubCallback getAsyncVehicleStubCallback = entry.getKey();
            getAsyncVehicleStubCallback.onGetAsyncResults(entry.getValue());
        }
    }

    private int convertHalToCarPropertyManagerError(GetValueResult result) {
        // TODO (b/241060746): Implement retry logic - StatusCode.TRY_AGAIN
        if (result.status == StatusCode.NOT_AVAILABLE) {
            return CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE;
        }
        return CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR;
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
}
