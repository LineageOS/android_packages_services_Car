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

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.VehicleHalStatusCode;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.hardware.automotive.vehicle.VehiclePropValue;
import android.hardware.automotive.vehicle.VehiclePropertyAccess;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;

import com.android.car.VehicleStub;
import com.android.car.hal.HalAreaConfig;
import com.android.car.hal.HalPropConfig;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;
import com.android.car.hal.VehicleHalCallback;
import com.android.car.internal.property.PropIdAreaId;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(MockitoJUnitRunner.class)
public class SimulationVehicleStubUnitTest {

    @Mock
    private VehicleStub mMockVehicleStub;
    @Mock
    private HalPropConfig mHalPropConfig1;
    @Mock
    private HalPropConfig mHalPropConfig2;
    @Mock
    private HalPropValueBuilder mMockHalPropValueBuilder;
    @Mock
    private HalPropValue mMockHalPropValue1;
    @Mock
    private HalPropValue mMockHalPropValue2;
    @Mock
    private VehicleHalCallback mVehicleHalCallback;
    private static final int PROP_ID_1 = 42 | 0x01000000;
    private static final int PROP_ID_2 = 952 | 0x01000000;
    private static final int AREA_ID_GLOBAL = 0;
    private SimulationVehicleStub mSimulationVehicleStub;


    @Before
    public void setup() throws Exception {
        HalPropValue halPropValue1 = mock(HalPropValue.class);
        HalPropValue halPropValue2 = mock(HalPropValue.class);
        when(mHalPropConfig1.getPropId()).thenReturn(PROP_ID_1);
        when(mHalPropConfig2.getPropId()).thenReturn(PROP_ID_2);
        when(mHalPropConfig1.getAccess()).thenReturn(VehiclePropertyAccess.READ_WRITE);
        when(mHalPropConfig2.getAccess()).thenReturn(VehiclePropertyAccess.READ_WRITE);
        when(mHalPropConfig1.getAreaConfigs()).thenReturn(new HalAreaConfig[0]);
        when(mHalPropConfig2.getAreaConfigs()).thenReturn(new HalAreaConfig[0]);
        when(mMockVehicleStub.getHalPropValueBuilder()).thenReturn(mMockHalPropValueBuilder);
        when(mMockHalPropValueBuilder.build(PROP_ID_1, AREA_ID_GLOBAL))
                .thenReturn(halPropValue1);
        when(mMockHalPropValueBuilder.build(PROP_ID_2, AREA_ID_GLOBAL))
                .thenReturn(halPropValue2);
        when(mMockVehicleStub.get(halPropValue1)).thenReturn(mMockHalPropValue1);
        when(mMockVehicleStub.get(halPropValue2)).thenReturn(mMockHalPropValue2);
        when(mMockHalPropValue1.getPropId()).thenReturn(PROP_ID_1);
        when(mMockHalPropValue1.getAreaId()).thenReturn(AREA_ID_GLOBAL);
        when(mMockHalPropValue2.getPropId()).thenReturn(PROP_ID_2);
        when(mMockHalPropValue2.getAreaId()).thenReturn(AREA_ID_GLOBAL);
        HalPropConfig[] halPropConfigs = new HalPropConfig[2];
        halPropConfigs[0] = mHalPropConfig1;
        halPropConfigs[1] = mHalPropConfig2;
        when(mMockVehicleStub.getAllPropConfigs()).thenReturn(halPropConfigs);

        mSimulationVehicleStub = new SimulationVehicleStub(mMockVehicleStub,
                List.of(PROP_ID_2));
    }

    @Test
    public void testIsAidlVhal() {
        when(mMockVehicleStub.isAidlVhal()).thenReturn(true);

        assertWithMessage("Is aidl vhal when vehicle stub is true")
                .that(mSimulationVehicleStub.isAidlVhal()).isTrue();
    }

    @Test
    public void testIsAidlVhalVehicleStubFalse() {
        when(mMockVehicleStub.isAidlVhal()).thenReturn(false);

        assertWithMessage("Is aidl vhal when vehicle stub is false")
                .that(mSimulationVehicleStub.isAidlVhal()).isFalse();
    }

    @Test
    public void testGetHalPropValueBuilder() {
        assertWithMessage("Get hal prop value builder")
                .that(mSimulationVehicleStub.getHalPropValueBuilder())
                .isEqualTo(mMockHalPropValueBuilder);
    }

    @Test
    public void testGetAllPropConfigs() throws Exception {
        when(mMockVehicleStub.getAllPropConfigs()).thenReturn(
                new HalPropConfig[] {mHalPropConfig1, mHalPropConfig2});

        assertWithMessage("Get all prop configs")
                .that(List.of(mSimulationVehicleStub.getAllPropConfigs()))
                .containsExactly(mHalPropConfig1, mHalPropConfig2);
    }

    @Test
    public void testGet_propertyIdIsNotFromRealHardware() throws Exception {
        HalPropValue halPropvalue = mock(HalPropValue.class);
        when(halPropvalue.getPropId()).thenReturn(PROP_ID_1);
        when(halPropvalue.getAreaId()).thenReturn(AREA_ID_GLOBAL);

        assertWithMessage("Get property id not from real hardware")
                .that(mSimulationVehicleStub.get(halPropvalue))
                .isEqualTo(mMockHalPropValue1);
    }

    @Test
    public void testGet_propertyIdComingFromRealHardware() throws Exception {
        HalPropValue halPropvalue = mock(HalPropValue.class);
        when(halPropvalue.getPropId()).thenReturn(PROP_ID_2);
        when(halPropvalue.getAreaId()).thenReturn(AREA_ID_GLOBAL);

        mSimulationVehicleStub.get(halPropvalue);

        verify(mMockVehicleStub).get(halPropvalue);
    }

    @Test
    public void testSet_propertyIdIsRealHardware() throws Exception {
        HalPropValue halPropvalue = mock(HalPropValue.class);
        when(halPropvalue.getPropId()).thenReturn(PROP_ID_2);
        when(halPropvalue.getAreaId()).thenReturn(AREA_ID_GLOBAL);

        mSimulationVehicleStub.set(halPropvalue);

        verify(mMockVehicleStub).set(halPropvalue);
    }

    @Test
    public void testSet_thenGetDifferentValue() throws Exception {
        HalPropValue halPropValue = mock(HalPropValue.class);
        HalPropValue updatedHalPropValue = mock(HalPropValue.class);
        VehiclePropValue vehiclePropValue = mock(VehiclePropValue.class);
        when(halPropValue.getPropId()).thenReturn(PROP_ID_1);
        when(halPropValue.getAreaId()).thenReturn(AREA_ID_GLOBAL);
        when(halPropValue.toVehiclePropValue()).thenReturn(vehiclePropValue);
        HalPropValue originalHalPropValue = mSimulationVehicleStub.get(halPropValue);
        when(mMockHalPropValueBuilder.build(any(VehiclePropValue.class)))
                .thenReturn(updatedHalPropValue);

        mSimulationVehicleStub.set(halPropValue);

        assertWithMessage("Get after a set")
                .that(mSimulationVehicleStub.get(halPropValue))
                .isNotEqualTo(originalHalPropValue);
    }

    @Test
    public void testSet_verifyOnPropertyEventIsCalled() throws Exception {
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        HalPropValue halPropValue = mock(HalPropValue.class);
        HalPropValue updatedHalPropValue = mock(HalPropValue.class);
        VehiclePropValue vehiclePropValue = mock(VehiclePropValue.class);
        when(halPropValue.getPropId()).thenReturn(PROP_ID_1);
        when(halPropValue.getAreaId()).thenReturn(AREA_ID_GLOBAL);
        when(halPropValue.toVehiclePropValue()).thenReturn(vehiclePropValue);
        when(mMockHalPropValueBuilder.build(any(VehiclePropValue.class)))
                .thenReturn(updatedHalPropValue);

        mSimulationVehicleStub.set(halPropValue);

        ArgumentCaptor<ArrayList> captor = ArgumentCaptor.forClass(ArrayList.class);
        verify(mMockVehicleStub, never()).set(any(HalPropValue.class));
        verify(mVehicleHalCallback).onPropertyEvent(captor.capture());
        assertWithMessage("onPropertyEvent value").that(captor.getValue()
                .getFirst()).isEqualTo(updatedHalPropValue);
    }

    @Test
    public void testOnPropertyEvent_verifyOnPropertyEventIsNotCalled() throws Exception {
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        ArgumentCaptor<VehicleHalCallback> captor = ArgumentCaptor.forClass(
                VehicleHalCallback.class);
        verify(mMockVehicleStub).newSubscriptionClient(captor.capture());
        VehicleHalCallback callback = captor.getValue();
        HalPropValue halPropValue = mock(HalPropValue.class);
        when(halPropValue.getPropId()).thenReturn(PROP_ID_1);
        ArrayList<HalPropValue> halPropValues = new ArrayList<>(List.of(halPropValue));

        callback.onPropertyEvent(halPropValues);

        verify(mVehicleHalCallback, never()).onPropertyEvent(any());
    }

    @Test
    public void testOnPropertyEvent_verifyOnPropertyEventIsCalled() throws Exception {
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        ArgumentCaptor<VehicleHalCallback> captor = ArgumentCaptor.forClass(
                VehicleHalCallback.class);
        verify(mMockVehicleStub).newSubscriptionClient(captor.capture());
        VehicleHalCallback callback = captor.getValue();
        HalPropValue halPropValue = mock(HalPropValue.class);
        when(halPropValue.getPropId()).thenReturn(PROP_ID_2);
        ArrayList<HalPropValue> halPropValues = new ArrayList<>(List.of(halPropValue));

        callback.onPropertyEvent(halPropValues);

        ArgumentCaptor<ArrayList> listCaptor = ArgumentCaptor.forClass(ArrayList.class);
        verify(mVehicleHalCallback).onPropertyEvent(listCaptor.capture());
        assertWithMessage("onPropertyEvent value").that(listCaptor.getValue()
                .getFirst()).isEqualTo(halPropValue);
    }

    @Test
    public void testOnPropertySetError_verifyOnPropertySetErrorIsNotCalled() throws Exception {
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        ArgumentCaptor<VehicleHalCallback> captor = ArgumentCaptor.forClass(
                VehicleHalCallback.class);
        verify(mMockVehicleStub).newSubscriptionClient(captor.capture());
        VehicleHalCallback callback = captor.getValue();
        VehiclePropError err = new VehiclePropError();
        err.propId = PROP_ID_1;
        ArrayList<VehiclePropError> vehiclePropErrors = new ArrayList<>(List.of(err));

        callback.onPropertySetError(vehiclePropErrors);

        verify(mVehicleHalCallback, never()).onPropertySetError(any());
    }

    @Test
    public void testOnPropertySetError_verifyOnPropertySetErrorIsCalled() throws Exception {
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        ArgumentCaptor<VehicleHalCallback> captor = ArgumentCaptor.forClass(
                VehicleHalCallback.class);
        verify(mMockVehicleStub).newSubscriptionClient(captor.capture());
        VehicleHalCallback callback = captor.getValue();
        VehiclePropError err = new VehiclePropError();
        err.propId = PROP_ID_2;
        ArrayList<VehiclePropError> vehiclePropErrors = new ArrayList<>(List.of(err));

        callback.onPropertySetError(vehiclePropErrors);

        ArgumentCaptor<ArrayList> listCaptor = ArgumentCaptor.forClass(ArrayList.class);
        verify(mVehicleHalCallback).onPropertySetError(listCaptor.capture());
        assertWithMessage("onPropertySetError value").that(listCaptor.getValue()
                .getFirst()).isEqualTo(err);
    }

    @Test
    public void testOnSupportedValuesChange_verifyOnSupportedValueChangedIsNotCalled()
            throws Exception {
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        ArgumentCaptor<VehicleHalCallback> captor = ArgumentCaptor.forClass(
                VehicleHalCallback.class);
        verify(mMockVehicleStub).newSubscriptionClient(captor.capture());
        VehicleHalCallback callback = captor.getValue();
        PropIdAreaId propIdAreaId = new PropIdAreaId();
        propIdAreaId.propId = PROP_ID_1;
        ArrayList<PropIdAreaId> propIdAreaIds = new ArrayList<>(List.of(propIdAreaId));

        callback.onSupportedValuesChange(propIdAreaIds);

        verify(mVehicleHalCallback, never()).onSupportedValuesChange(any());
    }

    @Test
    public void testOnSupportedValuesChange_verifyOnSupportedValueChangedIsCalled()
            throws Exception {
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        ArgumentCaptor<VehicleHalCallback> captor = ArgumentCaptor.forClass(
                VehicleHalCallback.class);
        verify(mMockVehicleStub).newSubscriptionClient(captor.capture());
        VehicleHalCallback callback = captor.getValue();
        PropIdAreaId propIdAreaId = new PropIdAreaId();
        propIdAreaId.propId = PROP_ID_2;
        ArrayList<PropIdAreaId> propIdAreaIds = new ArrayList<>(List.of(propIdAreaId));

        callback.onSupportedValuesChange(propIdAreaIds);

        ArgumentCaptor<List> listCaptor = ArgumentCaptor.forClass(List.class);
        verify(mVehicleHalCallback).onSupportedValuesChange(listCaptor.capture());
        assertWithMessage("onPropertySetError value").that(listCaptor.getValue()
                .getFirst()).isEqualTo(propIdAreaId);
    }

    @Test
    public void testGetAsync() throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        VehicleStub.VehicleStubCallbackInterface callback = new VehicleStubCallbackTest(
                countDownLatch);
        mSimulationVehicleStub.getAsync(List.of(defaultVehicleStubAsyncRequest(mMockHalPropValue1),
                        defaultVehicleStubAsyncRequest(mMockHalPropValue2)),
                callback);

        verify(mMockVehicleStub).get(mMockHalPropValue2);
        boolean called = countDownLatch.await(2, TimeUnit.SECONDS);
        assertWithMessage("onGetAsyncResult called").that(called).isTrue();
    }

    @Test
    public void testGetAsync_withOneServiceSpecificError() throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        VehicleStubCallbackTest callback = new VehicleStubCallbackTest(
                countDownLatch);
        when(mMockVehicleStub.get(mMockHalPropValue2)).thenThrow(new ServiceSpecificException(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE));
        mSimulationVehicleStub.getAsync(List.of(defaultVehicleStubAsyncRequest(mMockHalPropValue1),
                        defaultVehicleStubAsyncRequest(mMockHalPropValue2)),
                callback);

        boolean called = countDownLatch.await(2, TimeUnit.SECONDS);
        assertWithMessage("onGetAsyncResult called").that(called).isTrue();
        List<VehicleStub.GetVehicleStubAsyncResult> results = callback.getGetAsyncResults();
        boolean found = false;
        for (VehicleStub.GetVehicleStubAsyncResult result : results) {
            if (result.getCarPropertyErrorCodes().toCarPropertyAsyncErrorCode()
                    == CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE) {
                found = true;
                break;
            }
        }
        assertWithMessage("Async result error").that(found).isTrue();
    }

    @Test
    public void testGetAsync_withOneRemoteException() throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        VehicleStubCallbackTest callback = new VehicleStubCallbackTest(
                countDownLatch);
        when(mMockVehicleStub.get(mMockHalPropValue2)).thenThrow(new RemoteException());
        mSimulationVehicleStub.getAsync(List.of(defaultVehicleStubAsyncRequest(mMockHalPropValue1),
                        defaultVehicleStubAsyncRequest(mMockHalPropValue2)),
                callback);

        boolean called = countDownLatch.await(2, TimeUnit.SECONDS);
        assertWithMessage("onGetAsyncResult called").that(called).isTrue();
        List<VehicleStub.GetVehicleStubAsyncResult> results = callback.getGetAsyncResults();
        boolean found = false;
        for (VehicleStub.GetVehicleStubAsyncResult result : results) {
            if (result.getCarPropertyErrorCodes().toCarPropertyAsyncErrorCode()
                    == CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR) {
                found = true;
                break;
            }
        }
        assertWithMessage("Async result error").that(found).isTrue();
    }

    @Test
    public void testSetAsync() throws Exception {
        VehiclePropValue vehiclePropValue1 = mock(VehiclePropValue.class);
        when(mMockHalPropValue1.toVehiclePropValue()).thenReturn(vehiclePropValue1);
        VehiclePropValue vehiclePropValue2 = mock(VehiclePropValue.class);
        when(mMockHalPropValue2.toVehiclePropValue()).thenReturn(vehiclePropValue2);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        VehicleStub.VehicleStubCallbackInterface callback = new VehicleStubCallbackTest(
                countDownLatch);
        mSimulationVehicleStub.setAsync(List.of(defaultVehicleStubAsyncRequest(mMockHalPropValue1),
                        defaultVehicleStubAsyncRequest(mMockHalPropValue2)),
                callback);

        verify(mMockVehicleStub).set(mMockHalPropValue2);
        boolean called = countDownLatch.await(2, TimeUnit.SECONDS);
        assertWithMessage("onSetAsyncResult called").that(called).isTrue();
    }

    @Test
    public void testSetAsync_withOneServiceSpecificError() throws Exception {
        VehiclePropValue vehiclePropValue1 = mock(VehiclePropValue.class);
        when(mMockHalPropValue1.toVehiclePropValue()).thenReturn(vehiclePropValue1);
        VehiclePropValue vehiclePropValue2 = mock(VehiclePropValue.class);
        when(mMockHalPropValue2.toVehiclePropValue()).thenReturn(vehiclePropValue2);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        VehicleStubCallbackTest callback = new VehicleStubCallbackTest(
                countDownLatch);
        doThrow(new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE))
                .when(mMockVehicleStub).set(mMockHalPropValue2);
        mSimulationVehicleStub.setAsync(List.of(defaultVehicleStubAsyncRequest(mMockHalPropValue1),
                        defaultVehicleStubAsyncRequest(mMockHalPropValue2)),
                callback);

        boolean called = countDownLatch.await(2, TimeUnit.SECONDS);
        assertWithMessage("onSetAsyncResult called").that(called).isTrue();
        List<VehicleStub.SetVehicleStubAsyncResult> results = callback.getSetAsyncResults();
        boolean found = false;
        for (VehicleStub.SetVehicleStubAsyncResult result : results) {
            if (result.getCarPropertyErrorCodes().toCarPropertyAsyncErrorCode()
                    == CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE) {
                found = true;
                break;
            }
        }
        assertWithMessage("Async result error").that(found).isTrue();
    }

    @Test
    public void testSetAsync_withOneRemoteException() throws Exception {
        VehiclePropValue vehiclePropValue1 = mock(VehiclePropValue.class);
        when(mMockHalPropValue1.toVehiclePropValue()).thenReturn(vehiclePropValue1);
        VehiclePropValue vehiclePropValue2 = mock(VehiclePropValue.class);
        when(mMockHalPropValue2.toVehiclePropValue()).thenReturn(vehiclePropValue2);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        VehicleStubCallbackTest callback = new VehicleStubCallbackTest(
                countDownLatch);
        doThrow(new RemoteException()).when(mMockVehicleStub).set(mMockHalPropValue2);
        mSimulationVehicleStub.setAsync(List.of(defaultVehicleStubAsyncRequest(mMockHalPropValue1),
                        defaultVehicleStubAsyncRequest(mMockHalPropValue2)),
                callback);

        boolean called = countDownLatch.await(2, TimeUnit.SECONDS);
        assertWithMessage("onSetAsyncResult called").that(called).isTrue();
        List<VehicleStub.SetVehicleStubAsyncResult> results = callback.getSetAsyncResults();
        boolean found = false;
        for (VehicleStub.SetVehicleStubAsyncResult result : results) {
            if (result.getCarPropertyErrorCodes().toCarPropertyAsyncErrorCode()
                    == CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR) {
                found = true;
                break;
            }
        }
        assertWithMessage("Async result error").that(found).isTrue();
    }

    private static VehicleStub.AsyncGetSetRequest defaultVehicleStubAsyncRequest(
            HalPropValue value) {
        return new VehicleStub.AsyncGetSetRequest(/* serviceRequestId=*/ 0, value,
                /* timeoutUptimeMs= */ SystemClock.uptimeMillis() + 1000);
    }

    private static class VehicleStubCallbackTest extends VehicleStub.VehicleStubCallbackInterface {

        private final CountDownLatch mCountDownLatch;
        private final List<VehicleStub.GetVehicleStubAsyncResult> mGetAsyncResults;
        private final List<VehicleStub.SetVehicleStubAsyncResult> mSetAsyncResults;

        private VehicleStubCallbackTest(CountDownLatch countdownLatch) {
            mCountDownLatch = countdownLatch;
            mGetAsyncResults = new ArrayList<>();
            mSetAsyncResults = new ArrayList<>();
        }

        public List<VehicleStub.GetVehicleStubAsyncResult> getGetAsyncResults() {
            return mGetAsyncResults;
        }

        public List<VehicleStub.SetVehicleStubAsyncResult> getSetAsyncResults() {
            return mSetAsyncResults;
        }

        @Override
        public void onGetAsyncResults(
                List<VehicleStub.GetVehicleStubAsyncResult> getVehicleStubAsyncResults) {
            mGetAsyncResults.addAll(getVehicleStubAsyncResults);
            mCountDownLatch.countDown();
        }

        @Override
        public void onSetAsyncResults(
                List<VehicleStub.SetVehicleStubAsyncResult> setVehicleStubAsyncResults) {
            mSetAsyncResults.addAll(setVehicleStubAsyncResults);
            mCountDownLatch.countDown();
        }

        @Override
        public void linkToDeath(IBinder.DeathRecipient recipient) throws RemoteException {

        }

        @Override
        public void onRequestsTimeout(List<Integer> serviceRequestIds) {

        }
    }
}
