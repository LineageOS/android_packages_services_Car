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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.GetPropertyServiceRequest;
import android.car.hardware.property.GetValueResult;
import android.car.hardware.property.IGetAsyncPropertyResultCallback;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.test.runner.AndroidJUnit4;

import com.android.car.VehicleStub;
import com.android.car.VehicleStub.GetVehicleStubAsyncRequest;
import com.android.car.VehicleStub.GetVehicleStubAsyncResult;

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
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PropertyHalServiceTest {
    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    private VehicleHal mVehicleHal;

    @Mock
    private IGetAsyncPropertyResultCallback mGetAsyncPropertyResultCallback;
    @Mock
    private IBinder mGetAsyncPropertyResultBinder;

    private PropertyHalService mPropertyHalService;
    private static final int REQUEST_ID_1 = 1;
    private static final int REQUEST_ID_2 = 2;
    private static final int RECEIVED_REQUEST_ID_1 = 0;
    private static final int RECEIVED_REQUEST_ID_2 = 1;
    private static final int RECEIVED_REQUEST_ID_3 = 2;
    private static final GetPropertyServiceRequest GET_PROPERTY_SERVICE_REQUEST_1 =
            new GetPropertyServiceRequest(REQUEST_ID_1, HVAC_TEMPERATURE_SET, /*areaId=*/0);
    private static final GetPropertyServiceRequest GET_PROPERTY_SERVICE_REQUEST_2 =
            new GetPropertyServiceRequest(REQUEST_ID_2, HVAC_TEMPERATURE_SET, /*areaId=*/0);
    private final HalPropValueBuilder mPropValueBuilder = new HalPropValueBuilder(/*isAidl=*/true);
    private final HalPropValue mPropValue = mPropValueBuilder.build(
            HVAC_TEMPERATURE_SET, /*areaId=*/0, 17.0f);

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

        ArgumentCaptor<List<GetVehicleStubAsyncRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mVehicleHal).getAsync(captor.capture(),
                any(VehicleStub.VehicleStubCallbackInterface.class));
        GetVehicleStubAsyncRequest gotRequest = captor.getValue().get(0);
        assertThat(gotRequest.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_1);
        assertThat(gotRequest.getHalPropValue().getPropId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(gotRequest.getTimeoutInMs()).isEqualTo(1000);
    }

    @Test
    public void testGetCarPropertyValuesAsync_multipleRequests() {
        List<GetPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>();
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_1);
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_2);
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<GetVehicleStubAsyncRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mVehicleHal).getAsync(captor.capture(),
                any(VehicleStub.VehicleStubCallbackInterface.class));
        GetVehicleStubAsyncRequest gotRequest0 = captor.getValue().get(0);
        assertThat(gotRequest0.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_1);
        assertThat(gotRequest0.getHalPropValue().getPropId()).isEqualTo(
                HVAC_TEMPERATURE_SET);
        assertThat(gotRequest0.getHalPropValue().getAreaId()).isEqualTo(0);
        assertThat(gotRequest0.getTimeoutInMs()).isEqualTo(1000);
        GetVehicleStubAsyncRequest gotRequest1 = captor.getValue().get(1);
        assertThat(gotRequest1.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_2);
        assertThat(gotRequest1.getHalPropValue().getPropId()).isEqualTo(
                HVAC_TEMPERATURE_SET);
        assertThat(gotRequest1.getHalPropValue().getAreaId()).isEqualTo(0);
        assertThat(gotRequest1.getTimeoutInMs()).isEqualTo(1000);
    }

    @Test
    public void testGetCarPropertyValuesAsync_linkToDeath() throws RemoteException {
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();
        List<GetPropertyServiceRequest> getPropertyServiceRequests = mock(List.class);
        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);
        verify(mGetAsyncPropertyResultBinder).linkToDeath(any(IBinder.DeathRecipient.class),
                anyInt());
    }

    @Test
    public void testGetCarProeprtyValuesAsync_binderDiedBeforeLinkToDeath() throws RemoteException {
        IBinder mockBinder = mock(IBinder.class);
        when(mGetAsyncPropertyResultCallback.asBinder()).thenReturn(mockBinder);
        doThrow(new RemoteException()).when(mockBinder).linkToDeath(any(), anyInt());
        List<GetPropertyServiceRequest> getPropertyServiceRequests = mock(List.class);

        assertThrows(IllegalStateException.class, () -> {
            mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                    mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);
        });
    }

    private Object deliverPropResult(InvocationOnMock invocation, int expectedServiceRequestId) {
        Object[] args = invocation.getArguments();
        List getVehicleHalRequests = (List) args[0];
        GetVehicleStubAsyncRequest getVehicleHalRequest =
                (GetVehicleStubAsyncRequest) getVehicleHalRequests.get(0);
        VehicleStub.VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                (VehicleStub.VehicleStubCallbackInterface) args[1];

        int serviceRequestId = getVehicleHalRequest.getServiceRequestId();
        assertThat(serviceRequestId).isEqualTo(expectedServiceRequestId);
        assertThat(getVehicleHalRequest.getHalPropValue().getPropId()).isEqualTo(
                HVAC_TEMPERATURE_SET);

        GetVehicleStubAsyncResult getVehicleStubAsyncResult =
                new GetVehicleStubAsyncResult(serviceRequestId, mPropValue);
        getVehicleStubAsyncCallback.onGetAsyncResults(List.of(getVehicleStubAsyncResult));
        return null;
    }

    @Test
    public void testOnGetAsyncResults() throws RemoteException {
        doAnswer((invocation) -> {
            return deliverPropResult(invocation, RECEIVED_REQUEST_ID_1);
        }).when(mVehicleHal).getAsync(any(List.class),
                any(VehicleStub.VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<GetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResult(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getCarPropertyValue().getValue()).isEqualTo(17.0f);
        assertThat(value.getValue().get(0).getCarPropertyValue().getAreaId()).isEqualTo(0);
    }

    private Object deliverTryAgainResult(InvocationOnMock invocation,
            Integer expectedServiceRequestId) {
        Object[] args = invocation.getArguments();
        List getVehicleHalRequests = (List) args[0];
        GetVehicleStubAsyncRequest getVehicleHalRequest =
                (GetVehicleStubAsyncRequest) getVehicleHalRequests.get(0);
        VehicleStub.VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                (VehicleStub.VehicleStubCallbackInterface) args[1];

        int serviceRequestId = getVehicleHalRequest.getServiceRequestId();
        if (expectedServiceRequestId != null) {
            assertThat(serviceRequestId).isEqualTo(expectedServiceRequestId);
        }

        GetVehicleStubAsyncResult getVehicleStubAsyncResult = new GetVehicleStubAsyncResult(
                serviceRequestId, VehicleStub.STATUS_TRY_AGAIN);
        getVehicleStubAsyncCallback.onGetAsyncResults(List.of(getVehicleStubAsyncResult));
        return null;
    }

    @Test
    public void testOnGetAsyncResults_RetryTwiceAndSucceed() throws RemoteException {
        doAnswer(new Answer() {
            private int mCount = 0;

            public Object answer(InvocationOnMock invocation) {
                switch (mCount++) {
                    case 0:
                        return deliverTryAgainResult(invocation, RECEIVED_REQUEST_ID_1);
                    case 1:
                        return deliverTryAgainResult(invocation, RECEIVED_REQUEST_ID_2);
                    case 2:
                        return deliverPropResult(invocation, RECEIVED_REQUEST_ID_3);
                    default:
                        throw new IllegalStateException("Only expect 3 calls");
                }
            }
        }).when(mVehicleHal).getAsync(any(List.class),
                any(VehicleStub.VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<GetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResult(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getCarPropertyValue().getValue()).isEqualTo(17.0f);
        assertThat(value.getValue().get(0).getCarPropertyValue().getAreaId()).isEqualTo(0);
    }

    @Test
    public void testOnGetAsyncResults_RetryAndTimedout() throws RemoteException {
        doAnswer((invocation) -> {
            // For every request, we return retry result.
            return deliverTryAgainResult(invocation, null);
        }).when(mVehicleHal).getAsync(any(List.class),
                any(VehicleStub.VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 10);

        ArgumentCaptor<List<GetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResult(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);
    }

    @Test
    public void testOnGetAsyncResults_TimeoutFromVehicleStub() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List getVehicleHalRequests = (List) args[0];
            GetVehicleStubAsyncRequest getVehicleHalRequest =
                    (GetVehicleStubAsyncRequest) getVehicleHalRequests.get(0);
            VehicleStub.VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                    (VehicleStub.VehicleStubCallbackInterface) args[1];
            // Simulate the request has already timed-out.
            getVehicleStubAsyncCallback.onRequestsTimeout(List.of(
                    getVehicleHalRequest.getServiceRequestId()));
            return null;
        }).when(mVehicleHal).getAsync(any(List.class),
                any(VehicleStub.VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<GetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResult(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_TIMEOUT);
    }

    @Test
    public void testOnGetAsyncResults_errorResult() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List getVehicleHalRequests = (List) args[0];
            GetVehicleStubAsyncRequest getVehicleHalRequest =
                    (GetVehicleStubAsyncRequest) getVehicleHalRequests.get(0);
            VehicleStub.VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                    (VehicleStub.VehicleStubCallbackInterface) args[1];

            assertThat(getVehicleHalRequest.getServiceRequestId()).isEqualTo(RECEIVED_REQUEST_ID_1);
            assertThat(getVehicleHalRequest.getHalPropValue().getPropId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);

            GetVehicleStubAsyncResult getVehicleStubAsyncResult =
                    new GetVehicleStubAsyncResult(RECEIVED_REQUEST_ID_1,
                            CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
            getVehicleStubAsyncCallback.onGetAsyncResults(List.of(getVehicleStubAsyncResult));
            return null;
        }).when(mVehicleHal).getAsync(any(List.class),
                any(VehicleStub.VehicleStubCallbackInterface.class));

        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(List.of(GET_PROPERTY_SERVICE_REQUEST_1),
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        ArgumentCaptor<List<GetValueResult>> value = ArgumentCaptor.forClass(List.class);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResult(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        assertThat(value.getValue().get(0).getCarPropertyValue()).isEqualTo(null);
    }

    @Test
    public void testOnGetAsyncResults_multipleResultsSameCall() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List<GetVehicleStubAsyncRequest> getVehicleHalRequests = (List) args[0];
            VehicleStub.VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                    (VehicleStub.VehicleStubCallbackInterface) args[1];

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
        }).when(mVehicleHal).getAsync(any(List.class),
                any(VehicleStub.VehicleStubCallbackInterface.class));

        ArgumentCaptor<List<GetValueResult>> value = ArgumentCaptor.forClass(List.class);
        List<GetPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>();
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_1);
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_2);
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000)).onGetValueResult(value.capture());
        assertThat(value.getValue().get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(value.getValue().get(0).getCarPropertyValue().getValue()).isEqualTo(17.0f);
        assertThat(value.getValue().get(1).getRequestId()).isEqualTo(REQUEST_ID_2);
        assertThat(value.getValue().get(1).getCarPropertyValue().getValue()).isEqualTo(17.0f);
    }

    @Test
    public void testOnGetAsyncResults_multipleResultsDifferentCalls() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List<GetVehicleStubAsyncRequest> getVehicleHalRequests = (List) args[0];
            VehicleStub.VehicleStubCallbackInterface getVehicleStubAsyncCallback =
                    (VehicleStub.VehicleStubCallbackInterface) args[1];

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
        }).when(mVehicleHal).getAsync(any(List.class),
                any(VehicleStub.VehicleStubCallbackInterface.class));

        ArgumentCaptor<List<GetValueResult>> value = ArgumentCaptor.forClass(List.class);
        List<GetPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>();
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_1);
        getPropertyServiceRequests.add(GET_PROPERTY_SERVICE_REQUEST_2);
        doReturn(mGetAsyncPropertyResultBinder).when(mGetAsyncPropertyResultCallback).asBinder();

        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                mGetAsyncPropertyResultCallback, /* timeoutInMs= */ 1000);

        verify(mGetAsyncPropertyResultCallback, timeout(1000).times(2))
                .onGetValueResult(value.capture());
        List<List<GetValueResult>> getValuesResults = value.getAllValues();
        assertThat(getValuesResults.get(0).get(0).getRequestId()).isEqualTo(REQUEST_ID_1);
        assertThat(getValuesResults.get(0).get(0).getCarPropertyValue().getValue()).isEqualTo(
                17.0f);
        assertThat(getValuesResults.get(1).get(0).getRequestId()).isEqualTo(REQUEST_ID_2);
        assertThat(getValuesResults.get(1).get(0).getCarPropertyValue().getValue()).isEqualTo(
                17.0f);
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
}
