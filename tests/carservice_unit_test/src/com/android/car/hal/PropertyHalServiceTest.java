/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.car.VehiclePropertyIds.HVAC_TEMPERATURE_SET;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_INTERNAL_ERROR;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_NOT_AVAILABLE;

import static com.android.car.internal.property.CarPropertyHelper.STATUS_OK;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import androidx.test.runner.AndroidJUnit4;

import com.android.car.VehicleStub;
import com.android.car.VehicleStub.AsyncGetSetRequest;
import com.android.car.VehicleStub.GetVehicleStubAsyncResult;
import com.android.car.VehicleStub.SetVehicleStubAsyncResult;
import com.android.car.VehicleStub.VehicleStubCallbackInterface;
import com.android.car.hal.test.AidlVehiclePropValueBuilder;
import com.android.car.internal.property.AsyncPropertyServiceRequest;
import com.android.car.internal.property.GetSetValueResult;
import com.android.car.internal.property.IAsyncPropertyResultCallback;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class PropertyHalServiceTest {
    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    private VehicleHal mVehicleHal;

    @Mock
    private IAsyncPropertyResultCallback mGetAsyncPropertyResultCallback;
    @Mock
    private IAsyncPropertyResultCallback mSetAsyncPropertyResultCallback;
    @Mock
    private IBinder mGetAsyncPropertyResultBinder;
    @Mock
    private IBinder mSetAsyncPropertyResultBinder;

    private PropertyHalService mPropertyHalService;
    private static final int REQUEST_ID_1 = 1;
    private static final int REQUEST_ID_2 = 2;
    private static final int RECEIVED_REQUEST_ID_1 = 0;
    private static final int RECEIVED_REQUEST_ID_2 = 1;
    private static final int RECEIVED_REQUEST_ID_3 = 2;
    private static final int INT32_PROP = VehiclePropertyIds.INFO_FUEL_DOOR_LOCATION;
    private static final AsyncPropertyServiceRequest GET_PROPERTY_SERVICE_REQUEST_1 =
            new AsyncPropertyServiceRequest(REQUEST_ID_1, HVAC_TEMPERATURE_SET, /* areaId= */ 0);
    private static final AsyncPropertyServiceRequest GET_PROPERTY_SERVICE_REQUEST_2 =
            new AsyncPropertyServiceRequest(REQUEST_ID_2, HVAC_TEMPERATURE_SET, /* areaId= */ 0);
    private static final AsyncPropertyServiceRequest SET_PROPERTY_SERVICE_REQUEST =
            new AsyncPropertyServiceRequest(REQUEST_ID_1, HVAC_TEMPERATURE_SET, /* areaId= */ 0,
            new CarPropertyValue(HVAC_TEMPERATURE_SET, /* areaId= */ 0, 17.0f));
    private final HalPropValueBuilder mPropValueBuilder = new HalPropValueBuilder(
            /* isAidl= */ true);
    private final HalPropValue mPropValue = mPropValueBuilder.build(
            HVAC_TEMPERATURE_SET, /* areaId= */ 0, 17.0f);

    @Before
    public void setUp() {
        when(mVehicleHal.getHalPropValueBuilder()).thenReturn(mPropValueBuilder);
        mPropertyHalService = new PropertyHalService(mVehicleHal);
        mPropertyHalService.init();
    }

    @After
    public void tearDown() {
        mPropertyHalService.release();
        mPropertyHalService = null;
    }

    @Test
    public void testGetCarPropertyValuesAsync() {
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<AsyncGetSetRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mVehicleHal).getAsync(captor.capture(), any(VehicleStubCallbackInterface.class));
        AsyncGetSetRequest gotRequest = captor.getValue().get(0);
        assertThat(gotRequest.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_1);
        assertThat(gotRequest.getHalPropValue().getPropId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(gotRequest.getTimeoutInMs()).isEqualTo(1000);
    }

    @Test
    public void testGetCarPropertyValuesAsync_multipleRequests() {
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>();
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_1);
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_2);
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<AsyncGetSetRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mVehicleHal).getAsync(captor.capture(), any(VehicleStubCallbackInterface.class));
        AsyncGetSetRequest gotRequest0 = captor.getValue().get(0);
        assertThat(gotRequest0.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_1);
        assertThat(gotRequest0.getHalPropValue().getPropId()).isEqualTo(
                HVAC_TEMPERATURE_SET);
        assertThat(gotRequest0.getHalPropValue().getAreaId()).isEqualTo(0);
        assertThat(gotRequest0.getTimeoutInMs()).isEqualTo(1000);
        AsyncGetSetRequest gotRequest1 = captor.getValue().get(1);
        assertThat(gotRequest1.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_2);
        assertThat(gotRequest1.getHalPropValue().getPropId()).isEqualTo(
                HVAC_TEMPERATURE_SET);
        assertThat(gotRequest1.getHalPropValue().getAreaId()).isEqualTo(0);
        assertThat(gotRequest1.getTimeoutInMs()).isEqualTo(1000);
    }

    @Test
    public void testGetCarPropertyValuesAsync_linkToDeath() throws RemoteException {
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = mock(List.class);
        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);
        verify(mGetAsyncPropertyResultBinder).linkToDeath(any(IBinder.DeathRecipient.class),
                anyInt());
    }

    @Test
    public void testGetCarPropertyValuesAsync_binderDiedBeforeLinkToDeath() throws RemoteException {
        IBinder mockBinder = mock(IBinder.class);
        when(mGetAsyncPropertyResultCallback.asBinder()).thenReturn(mockBinder);
        doThrow(new RemoteException()).when(mockBinder).linkToDeath(any(), anyInt());
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = mock(List.class);

        assertThrows(IllegalStateException.class, () -> {
            mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                    mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);
        });
    }

    @Test
    public void testGetCarPropertyValuesAsync_unlinkToDeath_onBinderDied() throws RemoteException {
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = mock(List.class);
        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<IBinder.DeathRecipient> recipientCaptor = ArgumentCaptor.forClass(
                IBinder.DeathRecipient.class);
        verify(mGetAsyncPropertyResultBinder).linkToDeath(recipientCaptor.capture(), eq(0));

        recipientCaptor.getValue().binderDied();

        verify(mGetAsyncPropertyResultBinder).unlinkToDeath(recipientCaptor.getValue(), 0);
    }

    private Object deliverResult(InvocationOnMock invocation, Integer expectedServiceRequestId,
            int errorCode, HalPropValue propValue, boolean get) {
        Object[] args = invocation.getArguments();
        List getVehicleHalRequests = (List) args[0];
        Map<VehicleStubCallbackInterface, List<GetVehicleStubAsyncResult>> callbackToGetResults =
                new HashMap<>();
        Map<VehicleStubCallbackInterface, List<SetVehicleStubAsyncResult>> callbackToSetResults =
                new HashMap<>();
        for (int i = 0; i < getVehicleHalRequests.size(); i++) {
            AsyncGetSetRequest getVehicleHalRequest = (AsyncGetSetRequest)
                    getVehicleHalRequests.get(i);
            VehicleStubCallbackInterface callback = (VehicleStubCallbackInterface) args[1];
            if (callbackToGetResults.get(callback) == null) {
                callbackToGetResults.put(callback, new ArrayList<>());
            }
            if (callbackToSetResults.get(callback) == null) {
                callbackToSetResults.put(callback, new ArrayList<>());
            }

            int serviceRequestId = getVehicleHalRequest.getServiceRequestId();
            if (expectedServiceRequestId != null) {
                assertThat(serviceRequestId).isEqualTo(expectedServiceRequestId);
            }

            if (get) {
                if (propValue != null) {
                    callbackToGetResults.get(callback).add(new GetVehicleStubAsyncResult(
                            serviceRequestId, propValue));
                } else {
                    callbackToGetResults.get(callback).add(new GetVehicleStubAsyncResult(
                            serviceRequestId, errorCode));
                }
            } else {
                callbackToSetResults.get(callback).add(new SetVehicleStubAsyncResult(
                        serviceRequestId, errorCode));
            }
        }

        for (VehicleStubCallbackInterface callback : callbackToGetResults.keySet()) {
            callback.onGetAsyncResults(callbackToGetResults.get(callback));
        }
        for (VehicleStubCallbackInterface callback : callbackToSetResults.keySet()) {
            callback.onSetAsyncResults(callbackToSetResults.get(callback));
        }

        return null;
    }

    private Object deliverOkayGetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId) {
        return deliverResult(invocation, expectedServiceRequestId, STATUS_OK,
                mPropValue, true);
    }

    private Object deliverOkaySetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId) {
        return deliverResult(invocation, expectedServiceRequestId, STATUS_OK,
                null, false);
    }

    private Object deliverTryAgainGetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId) {
        return deliverResult(invocation, expectedServiceRequestId, VehicleStub.STATUS_TRY_AGAIN,
                null, true);
    }

    private Object deliverTryAgainSetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId) {
        return deliverResult(invocation, expectedServiceRequestId, VehicleStub.STATUS_TRY_AGAIN,
                null, false);
    }

    private Object deliverErrorGetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId, int errorCode) {
        return deliverResult(invocation, expectedServiceRequestId, errorCode, null, true);
    }

    private Object deliverErrorSetResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId, int errorCode) {
        return deliverResult(invocation, expectedServiceRequestId, errorCode, null, false);
    }

    @Test
    public void testOnGetAsyncResults() throws RemoteException {
        doAnswer((invocation) -> {
            return deliverOkayGetResult(invocation, RECEIVED_REQUEST_ID_1);
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<GetSetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getCarPropertyValue().getValue()).isEqualTo(17.0f);
        assertThat(value.getValue().get(0).getCarPropertyValue().getAreaId()).isEqualTo(0);
    }

    @Test
    public void testOnGetAsyncResults_RetryTwiceAndSucceed() throws RemoteException {
        doAnswer(new Answer() {
            private int mCount = 0;

            public Object answer(InvocationOnMock invocation) {
                switch (mCount++) {
                    case 0:
                        return deliverTryAgainGetResult(invocation, RECEIVED_REQUEST_ID_1);
                    case 1:
                        return deliverTryAgainGetResult(invocation, RECEIVED_REQUEST_ID_2);
                    case 2:
                        return deliverOkayGetResult(invocation, RECEIVED_REQUEST_ID_3);
                    default:
                        throw new IllegalStateException("Only expect 3 calls");
                }
            }
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<GetSetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getCarPropertyValue().getValue()).isEqualTo(17.0f);
        assertThat(value.getValue().get(0).getCarPropertyValue().getAreaId()).isEqualTo(0);
    }

    @Test
    public void testOnGetAsyncResults_RetryAndTimeout() throws RemoteException {
        doAnswer((invocation) -> {
            // For every request, we return retry result.
            return deliverTryAgainGetResult(invocation, null);
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 10);

        ArgumentCaptor<List<GetSetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);
    }

    @Test
    public void testOnGetAsyncResults_TimeoutFromVehicleStub() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List getVehicleHalRequests = (List) args[0];
            AsyncGetSetRequest getVehicleHalRequest =
                    (AsyncGetSetRequest) getVehicleHalRequests.get(0);
            VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                    (VehicleStubCallbackInterface) args[1];
            // Simulate the request has already timed-out.
            getVehicleStubAsyncCallback.onRequestsTimeout(List.of(
                    getVehicleHalRequest.getServiceRequestId()));
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<GetSetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);
    }

    @Test
    public void testOnGetAsyncResults_errorResult() throws RemoteException {
        doAnswer((invocation) -> {
            return deliverErrorGetResult(invocation, RECEIVED_REQUEST_ID_1,
                    CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<GetSetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        assertThat(value.getValue().get(0).getCarPropertyValue()).isEqualTo(null);
    }

    @Test
    public void testOnGetAsyncResults_multipleResultsSameCall() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List<AsyncGetSetRequest> getVehicleHalRequests = (List) args[0];
            VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                    (VehicleStubCallbackInterface) args[1];

            assertThat(getVehicleHalRequests.size()).isEqualTo(2);
            assertThat(getVehicleHalRequests.get(0).getServiceRequestId()).isEqualTo(
                    RECEIVED_REQUEST_ID_1);
            assertThat(getVehicleHalRequests.get(0).getHalPropValue().getPropId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);
            assertThat(getVehicleHalRequests.get(1).getServiceRequestId()).isEqualTo(
                    RECEIVED_REQUEST_ID_2);
            assertThat(getVehicleHalRequests.get(1).getHalPropValue().getPropId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);

            List<GetVehicleStubAsyncResult> getVehicleStubAsyncResults =
                    new ArrayList<>();
            getVehicleStubAsyncResults.add(
                    new GetVehicleStubAsyncResult(RECEIVED_REQUEST_ID_1, mPropValue));
            getVehicleStubAsyncResults.add(
                    new GetVehicleStubAsyncResult(RECEIVED_REQUEST_ID_2, mPropValue));
            getVehicleStubAsyncCallback.onGetAsyncResults(getVehicleStubAsyncResults);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        ArgumentCaptor<List<GetSetValueResult>> value = ArgumentCaptor.forClass(List.class);
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>();
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_1);
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_2);
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getCarPropertyValue().getValue()).isEqualTo(17.0f);
        assertThat(value.getValue().get(1).getRequestId()).isEqualTo(REQUEST_ID_2);
        assertThat(value.getValue().get(1).getCarPropertyValue().getValue()).isEqualTo(17.0f);
    }

    @Test
    public void testOnGetAsyncResults_multipleResultsDifferentCalls() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List<AsyncGetSetRequest> getVehicleHalRequests = (List) args[0];
            VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                    (VehicleStubCallbackInterface) args[1];

            assertThat(getVehicleHalRequests.size()).isEqualTo(2);
            assertThat(getVehicleHalRequests.get(0).getServiceRequestId()).isEqualTo(
                    RECEIVED_REQUEST_ID_1);
            assertThat(getVehicleHalRequests.get(0).getHalPropValue().getPropId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);
            assertThat(getVehicleHalRequests.get(1).getServiceRequestId()).isEqualTo(
                    RECEIVED_REQUEST_ID_2);
            assertThat(getVehicleHalRequests.get(1).getHalPropValue().getPropId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);

            getVehicleStubAsyncCallback.onGetAsyncResults(
                    List.of(new GetVehicleStubAsyncResult(RECEIVED_REQUEST_ID_1,
                            mPropValue)));
            getVehicleStubAsyncCallback.onGetAsyncResults(
                    List.of(new GetVehicleStubAsyncResult(RECEIVED_REQUEST_ID_2,
                            mPropValue)));
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        ArgumentCaptor<List<GetSetValueResult>> value = ArgumentCaptor.forClass(List.class);
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>();
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_1);
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_2);
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000).times(2))
                .onGetValueResults(value.capture());
        List<List<GetSetValueResult>> getValuesResults = value.getAllValues();
        assertThat(getValuesResults.get(0).get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(getValuesResults.get(0).get(0).getCarPropertyValue().getValue()).isEqualTo(
                17.0f);
        assertThat(getValuesResults.get(1).get(0).getRequestId()).isEqualTo(REQUEST_ID_2);
        assertThat(getValuesResults.get(1).get(0).getCarPropertyValue().getValue()).isEqualTo(
                17.0f);
    }

    private void addConfigForHvac() {
        HalPropConfig mockPropConfig = mock(HalPropConfig.class);
        when(mockPropConfig.getPropId()).thenReturn(VehicleProperty.HVAC_TEMPERATURE_SET);
        mPropertyHalService.takeProperties(ImmutableList.of(mockPropConfig));
    }

    @Test
    public void testSetCarPropertyValuesAsync() {
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();
        addConfigForHvac();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<AsyncGetSetRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mVehicleHal).setAsync(captor.capture(), any(VehicleStubCallbackInterface.class));
        AsyncGetSetRequest gotRequest = captor.getValue().get(0);
        assertThat(gotRequest.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_1);
        assertThat(gotRequest.getHalPropValue().getPropId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(gotRequest.getHalPropValue().getFloatValue(0)).isEqualTo(17.0f);
        assertThat(gotRequest.getTimeoutInMs()).isEqualTo(1000);
    }

    @Test
    public void testSetCarPropertyValuesAsync_configNotFound() {
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();
        // takeProperties for HVAC_TEMPERATURE_SET is not called, so it is not supported.

        assertThrows(IllegalArgumentException.class, () -> {
            mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                    mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);
        });
    }

    @Test
    public void testSetCarPropertyValuesAsync_linkToDeath() throws RemoteException {
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();
        List<AsyncPropertyServiceRequest> setPropertyServiceRequests = mock(List.class);

        mPropertyHalService.setCarPropertyValuesAsync(setPropertyServiceRequests,
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mSetAsyncPropertyResultBinder).linkToDeath(any(IBinder.DeathRecipient.class),
                anyInt());
    }

    // TODO(b/264719384): Add testOnSetAsyncResults for success case once we implemenet logic for
    // calling success callbacks.

    @Test
    public void testOnSetAsyncResults_RetryAndTimeout() throws RemoteException {
        doAnswer((invocation) -> {
            // For every request, we return retry result.
            return deliverTryAgainSetResult(invocation, null);
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();
        addConfigForHvac();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 10);

        ArgumentCaptor<List<GetSetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mSetAsyncPropertyResultCallback, timeout(1000)).onSetValueResults(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);
    }

    @Test
    public void testOnSetAsyncResults_TimeoutFromVehicleStub() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List requests = (List) args[0];
            AsyncGetSetRequest request = (AsyncGetSetRequest) requests.get(0);
            VehicleStubCallbackInterface callback = (VehicleStubCallbackInterface) args[1];
            // Simulate the request has already timed-out.
            callback.onRequestsTimeout(List.of(request.getServiceRequestId()));
            return null;
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        addConfigForHvac();

        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<GetSetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mSetAsyncPropertyResultCallback, timeout(1000)).onSetValueResults(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);
    }

    @Test
    public void testOnSetAsyncResults_errorResult() throws RemoteException {
        doAnswer((invocation) -> {
            return deliverErrorSetResult(invocation, RECEIVED_REQUEST_ID_1,
                    CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        }).when(mVehicleHal).setAsync(any(List.class), any(VehicleStubCallbackInterface.class));
        doReturn(mSetAsyncPropertyResultBinder).when(mSetAsyncPropertyResultCallback).asBinder();
        addConfigForHvac();

        mPropertyHalService.setCarPropertyValuesAsync(List.of(SET_PROPERTY_SERVICE_REQUEST),
                mSetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<GetSetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mSetAsyncPropertyResultCallback, timeout(1000)).onSetValueResults(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        assertThat(value.getValue().get(0).getCarPropertyValue()).isEqualTo(null);
    }

    @Test
    public void isDisplayUnitsProperty_returnsTrueForAllDisplayUnitProperties() {
        for (int propId : ImmutableList.of(VehiclePropertyIds.DISTANCE_DISPLAY_UNITS,
                VehiclePropertyIds.FUEL_CONSUMPTION_UNITS_DISTANCE_OVER_VOLUME,
                VehiclePropertyIds.FUEL_VOLUME_DISPLAY_UNITS,
                VehiclePropertyIds.TIRE_PRESSURE_DISPLAY_UNITS,
                VehiclePropertyIds.EV_BATTERY_DISPLAY_UNITS,
                VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS)) {
            Assert.assertTrue(mPropertyHalService.isDisplayUnitsProperty(propId));
        }
    }

    @Test
    public void setProperty_handlesHalAndMgrPropIdMismatch() {
        HalPropConfig mockPropConfig = mock(HalPropConfig.class);
        CarPropertyValue mockCarPropertyValue = mock(CarPropertyValue.class);
        when(mockCarPropertyValue.getPropertyId()).thenReturn(
                VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS);
        when(mockCarPropertyValue.getAreaId()).thenReturn(0);
        when(mockCarPropertyValue.getValue()).thenReturn(1.0f);
        when(mockPropConfig.getPropId()).thenReturn(VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS);
        mPropertyHalService.takeProperties(ImmutableList.of(mockPropConfig));

        mPropertyHalService.setProperty(mockCarPropertyValue);

        HalPropValue value = mPropValueBuilder.build(
                VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS, /* areaId= */ 0, /* value= */ 1.0f);
        verify(mVehicleHal).set(value);
    }

    @Test
    public void testCancelRequests() throws Exception {
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();
        List<InvocationOnMock> invocationWrap = new ArrayList<>();
        doAnswer((invocation) -> {
            invocationWrap.add(invocation);
            return null;
        }).when(mVehicleHal).getAsync(any(List.class), any(VehicleStubCallbackInterface.class));

        mPropertyHalService.getCarPropertyValuesAsync(List.of(
                GET_PROPERTY_SERVICE_REQUEST_1, GET_PROPERTY_SERVICE_REQUEST_2),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        // Cancel the first request.
        mPropertyHalService.cancelRequests(new int[]{REQUEST_ID_1});

        verify(mVehicleHal).cancelRequests(List.of(0));

        // Deliver the results.
        for (int i = 0; i < invocationWrap.size(); i++) {
            deliverOkayGetResult(invocationWrap.get(i), null);
        }

        // We should only get the result for request 2.
        ArgumentCaptor<List<GetSetValueResult>> value = ArgumentCaptor.forClass(List.class);
        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResults(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_2);
    }

    @Test
    public void testCancelRequests_noPendingRequests() {
        mPropertyHalService.cancelRequests(new int[]{0});

        verify(mVehicleHal, never()).cancelRequests(any());
    }

    @Test
    public void testGetPropertySync() throws Exception {
        HalPropValue value = mPropValueBuilder.build(INT32_PROP, /* areaId= */ 0, /* value= */ 123);
        when(mVehicleHal.get(INT32_PROP, /* areaId= */ 0)).thenReturn(value);

        CarPropertyValue carPropValue = mPropertyHalService.getProperty(
                INT32_PROP, /* areaId= */ 0);

        assertThat(carPropValue.getValue()).isEqualTo(123);
    }

    @Test
    public void testGetPropertySyncErrorPropStatus() throws Exception {
        HalPropValue value = mPropValueBuilder.build(
                AidlVehiclePropValueBuilder.newBuilder(INT32_PROP)
                        .setStatus(VehiclePropertyStatus.ERROR).build());
        when(mVehicleHal.get(INT32_PROP, /* areaId= */ 0)).thenReturn(value);

        // If the property has ERROR status, getProperty will throw ServiceSpecificException with
        // STATUS_INTERNAL_ERROR as error code.
        ServiceSpecificException e = assertThrows(ServiceSpecificException.class,
                () -> mPropertyHalService.getProperty(INT32_PROP, /* areaId= */ 0));
        assertThat(e.errorCode).isEqualTo(STATUS_INTERNAL_ERROR);
    }

    @Test
    public void testGetPropertySyncUnavailablePropStatus() throws Exception {
        HalPropValue value = mPropValueBuilder.build(
                AidlVehiclePropValueBuilder.newBuilder(INT32_PROP)
                        .setStatus(VehiclePropertyStatus.UNAVAILABLE).build());
        when(mVehicleHal.get(INT32_PROP, /* areaId= */ 0)).thenReturn(value);

        // If the property has UNAVAILABLE status, getProperty will throw ServiceSpecificException
        // with STATUS_NOT_AVAILABLE as error code.
        ServiceSpecificException e = assertThrows(ServiceSpecificException.class,
                () -> mPropertyHalService.getProperty(INT32_PROP, /* areaId= */ 0));
        assertThat(e.errorCode).isEqualTo(STATUS_NOT_AVAILABLE);
    }

    @Test
    public void testGetPropertySyncInvalidProp() throws Exception {
        // This property has no valid int array element.
        HalPropValue value = mPropValueBuilder.build(
                AidlVehiclePropValueBuilder.newBuilder(INT32_PROP).build());
        when(mVehicleHal.get(INT32_PROP, /* areaId= */ 0)).thenReturn(value);

        // If the property value is not valid and cannot be converted to CarPropertyValue,
        // getProperty will throw ServiceSpecificException with STATUS_INTERNAL_ERROR.
        ServiceSpecificException e = assertThrows(ServiceSpecificException.class,
                () -> mPropertyHalService.getProperty(INT32_PROP, /* areaId= */ 0));
        assertThat(e.errorCode).isEqualTo(STATUS_INTERNAL_ERROR);
    }
}
