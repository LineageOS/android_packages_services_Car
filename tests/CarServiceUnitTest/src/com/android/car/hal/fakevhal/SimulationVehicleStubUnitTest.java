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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.VehicleHalStatusCode;
import android.hardware.automotive.vehicle.RawPropValues;
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
    private HalPropConfig mHalPropConfig3;
    @Mock
    private HalPropValueBuilder mMockHalPropValueBuilder;
    @Mock
    private HalPropValue mMockHalPropValue1;
    @Mock
    private HalPropValue mMockHalPropValue2;
    @Mock
    private HalPropValue mMockHalPropValue3;
    @Mock
    private HalAreaConfig mAreaConfig;
    @Mock
    private VehicleHalCallback mVehicleHalCallback;
    private static final int PROP_ID_1 = 42 | 0x01000000;
    private static final int PROP_ID_2 = 952 | 0x01000000;
    private static final int PROP_ID_3 = 999 | 0x00500000;
    private static final int INVALID_PROP_ID = 987;
    private static final int AREA_ID_GLOBAL = 0;
    private static final int AREA_ID_1 = 1;
    private SimulationVehicleStub mSimulationVehicleStub;


    @Before
    public void setup() throws Exception {
        // TODO(b/392180801): Convert these into real impl instead of mocks
        HalPropValue halPropValue1 = mock(HalPropValue.class);
        HalPropValue halPropValue2 = mock(HalPropValue.class);
        HalPropValue halPropValue3 = mock(HalPropValue.class);
        when(mHalPropConfig1.getPropId()).thenReturn(PROP_ID_1);
        when(mHalPropConfig2.getPropId()).thenReturn(PROP_ID_2);
        when(mHalPropConfig3.getPropId()).thenReturn(PROP_ID_3);
        when(mHalPropConfig1.getAccess()).thenReturn(VehiclePropertyAccess.READ_WRITE);
        when(mHalPropConfig2.getAccess()).thenReturn(VehiclePropertyAccess.READ_WRITE);
        when(mHalPropConfig3.getAccess()).thenReturn(VehiclePropertyAccess.READ_WRITE);
        HalAreaConfig[] halAreaConfigs = new HalAreaConfig[1];
        halAreaConfigs[0] = mAreaConfig;
        when(mAreaConfig.getAreaId()).thenReturn(AREA_ID_1);
        when(mHalPropConfig1.getAreaConfigs()).thenReturn(new HalAreaConfig[0]);
        when(mHalPropConfig2.getAreaConfigs()).thenReturn(new HalAreaConfig[0]);
        when(mHalPropConfig3.getAreaConfigs()).thenReturn(halAreaConfigs);
        when(mMockVehicleStub.getHalPropValueBuilder()).thenReturn(mMockHalPropValueBuilder);
        when(mMockHalPropValueBuilder.build(PROP_ID_1, AREA_ID_GLOBAL))
                .thenReturn(halPropValue1);
        when(mMockHalPropValueBuilder.build(PROP_ID_2, AREA_ID_GLOBAL))
                .thenReturn(halPropValue2);
        when(mMockHalPropValueBuilder.build(PROP_ID_3, AREA_ID_1))
                .thenReturn(halPropValue3);
        when(mMockVehicleStub.get(halPropValue1)).thenReturn(mMockHalPropValue1);
        when(mMockVehicleStub.get(halPropValue2)).thenReturn(mMockHalPropValue2);
        when(mMockVehicleStub.get(halPropValue3)).thenReturn(mMockHalPropValue3);
        when(mMockHalPropValue1.getPropId()).thenReturn(PROP_ID_1);
        when(mMockHalPropValue1.getAreaId()).thenReturn(AREA_ID_GLOBAL);
        when(mMockHalPropValue2.getPropId()).thenReturn(PROP_ID_2);
        when(mMockHalPropValue2.getAreaId()).thenReturn(AREA_ID_GLOBAL);
        when(mMockHalPropValue3.getPropId()).thenReturn(PROP_ID_3);
        when(mMockHalPropValue3.getAreaId()).thenReturn(AREA_ID_1);
        HalPropConfig[] halPropConfigs = new HalPropConfig[3];
        halPropConfigs[0] = mHalPropConfig1;
        halPropConfigs[1] = mHalPropConfig2;
        halPropConfigs[2] = mHalPropConfig3;
        when(mMockVehicleStub.getAllPropConfigs()).thenReturn(halPropConfigs);

        mSimulationVehicleStub = new SimulationVehicleStub(mMockVehicleStub,
                List.of(PROP_ID_2), mVehicleHalCallback);
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

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(mMockVehicleStub, never()).set(any(HalPropValue.class));
        verify(mVehicleHalCallback).onInjectionPropertyEvent(captor.capture());
        assertWithMessage("onInjectionPropertyEvent value").that(captor.getValue()
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
    public void testInjectVehicleProperties() {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        CarPropertyValue carPropertyValue1 = new CarPropertyValue(PROP_ID_1, AREA_ID_GLOBAL, 0);
        CarPropertyValue carPropertyValue2 = new CarPropertyValue(PROP_ID_2, AREA_ID_GLOBAL, 0);
        VehiclePropValue vehiclePropValue1 = mock(VehiclePropValue.class);
        when(mMockHalPropValue1.toVehiclePropValue()).thenReturn(vehiclePropValue1);
        VehiclePropValue vehiclePropValue2 = mock(VehiclePropValue.class);
        when(mMockHalPropValue2.toVehiclePropValue()).thenReturn(vehiclePropValue2);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue1), eq(PROP_ID_1), anyLong(),
                eq(mHalPropConfig1))).thenReturn(mMockHalPropValue1);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue2), eq(PROP_ID_2), anyLong(),
                eq(mHalPropConfig2))).thenReturn(mMockHalPropValue2);

        mSimulationVehicleStub.injectVehicleProperties(List.of(carPropertyValue1,
                carPropertyValue2));

        verify(mVehicleHalCallback, timeout(2000).times(2))
                .onInjectionPropertyEvent(captor.capture());
        List<List> allCaptors = captor.getAllValues();
        List<HalPropValue> callbackList = new ArrayList<>(allCaptors.get(0));
        callbackList.addAll(allCaptors.get(1));
        assertWithMessage("onPropertyEvent called").that(callbackList)
                .containsExactly(mMockHalPropValue1, mMockHalPropValue2);
    }

    @Test
    public void testInjectVehicleProperties_oneNotPostedToHandler() {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        CarPropertyValue carPropertyValue1 = new CarPropertyValue(PROP_ID_1, AREA_ID_GLOBAL, 0);
        CarPropertyValue carPropertyValue2 = new CarPropertyValue(PROP_ID_2, AREA_ID_GLOBAL,
                1000000000L * 1000, 0);
        VehiclePropValue vehiclePropValue1 = mock(VehiclePropValue.class);
        when(mMockHalPropValue1.toVehiclePropValue()).thenReturn(vehiclePropValue1);
        VehiclePropValue vehiclePropValue2 = mock(VehiclePropValue.class);
        when(mMockHalPropValue2.toVehiclePropValue()).thenReturn(vehiclePropValue2);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue1), eq(PROP_ID_1), anyLong(),
                eq(mHalPropConfig1))).thenReturn(mMockHalPropValue1);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue2), eq(PROP_ID_2), anyLong(),
                eq(mHalPropConfig2))).thenReturn(mMockHalPropValue2);

        mSimulationVehicleStub.injectVehicleProperties(List.of(carPropertyValue1,
                carPropertyValue2));

        verify(mVehicleHalCallback, timeout(2000))
                .onInjectionPropertyEvent(captor.capture());
        List<List> allCaptors = captor.getAllValues();
        assertWithMessage("Single event").that(allCaptors).hasSize(1);
        List<HalPropValue> callbackList = new ArrayList<>(allCaptors.get(0));
        assertWithMessage("onPropertyEvent called").that(callbackList)
                .containsExactly(mMockHalPropValue1);
    }

    @Test
    public void testInjectVehicleProperties_propIdNotValid() {
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        CarPropertyValue carPropertyValue1 = new CarPropertyValue(
                INVALID_PROP_ID, AREA_ID_GLOBAL, 0);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mSimulationVehicleStub.injectVehicleProperties(List.of(carPropertyValue1)));

        assertWithMessage("PropertyId not valid")
                .that(thrown).hasMessageThat().contains("PropertyId or areaId not supported");
    }

    @Test
    public void testInjectVehicleProperties_valueInRange() {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        when(mAreaConfig.getMinInt64Value()).thenReturn(10L);
        when(mAreaConfig.getMaxInt64Value()).thenReturn(100L);
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        CarPropertyValue carPropertyValue1 = new CarPropertyValue(PROP_ID_3, AREA_ID_1, 0L);
        long[] longValues = new long[1];
        longValues[0] = 15L;
        RawPropValues rawPropValues = new RawPropValues();
        rawPropValues.int64Values = longValues;
        VehiclePropValue vehiclePropValue1 = new VehiclePropValue();
        vehiclePropValue1.value = rawPropValues;
        when(mMockHalPropValue3.toVehiclePropValue()).thenReturn(vehiclePropValue1);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue1), eq(PROP_ID_3), anyLong(),
                eq(mHalPropConfig3))).thenReturn(mMockHalPropValue3);

        mSimulationVehicleStub.injectVehicleProperties(List.of(carPropertyValue1));

        verify(mVehicleHalCallback, timeout(2000))
                .onInjectionPropertyEvent(captor.capture());
        List<List> allCaptors = captor.getAllValues();
        assertWithMessage("Single event").that(allCaptors).hasSize(1);
        List<HalPropValue> callbackList = new ArrayList<>(allCaptors.get(0));
        assertWithMessage("onInjectionPropertyEvent called").that(callbackList)
                .containsExactly(mMockHalPropValue3);
    }


    @Test
    public void testInjectVehicleProperties_valueOutOfRange() {
        when(mAreaConfig.getMinInt64Value()).thenReturn(10L);
        when(mAreaConfig.getMaxInt64Value()).thenReturn(100L);
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        CarPropertyValue carPropertyValue1 = new CarPropertyValue(PROP_ID_3, AREA_ID_1, 0L);
        long[] longValues = new long[1];
        longValues[0] = 0L;
        RawPropValues rawPropValues = new RawPropValues();
        rawPropValues.int64Values = longValues;
        VehiclePropValue vehiclePropValue1 = new VehiclePropValue();
        vehiclePropValue1.value = rawPropValues;
        when(mMockHalPropValue3.toVehiclePropValue()).thenReturn(vehiclePropValue1);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue1), eq(PROP_ID_3), anyLong(),
                eq(mHalPropConfig3))).thenReturn(mMockHalPropValue3);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mSimulationVehicleStub.injectVehicleProperties(List.of(carPropertyValue1)));

        assertWithMessage("Value out of range")
                .that(thrown).hasMessageThat().contains("The property value is not within range");
    }

    @Test
    public void testInjectVehicleProperties_incorrectAreaId() {
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        CarPropertyValue carPropertyValue1 = new CarPropertyValue(PROP_ID_3, 2, 0L);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue1), eq(PROP_ID_3), anyLong(),
                eq(mHalPropConfig3))).thenReturn(mMockHalPropValue3);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mSimulationVehicleStub.injectVehicleProperties(List.of(carPropertyValue1)));

        assertWithMessage("AreaId incorrect")
                .that(thrown).hasMessageThat().contains("PropertyId or areaId not supported");
    }

    @Test
    public void testInjectVehicleProperties_sameValue() {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);

        CarPropertyValue carPropertyValue1 = new CarPropertyValue(PROP_ID_1, AREA_ID_GLOBAL, 0);
        VehiclePropValue vehiclePropValue1 = mock(VehiclePropValue.class);
        when(mMockHalPropValue1.toVehiclePropValue()).thenReturn(vehiclePropValue1);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue1), eq(PROP_ID_1), anyLong(),
                eq(mHalPropConfig1))).thenReturn(mMockHalPropValue1);

        mSimulationVehicleStub.injectVehicleProperties(List.of(carPropertyValue1));
        verify(mVehicleHalCallback, timeout(1000).times(1))
                .onInjectionPropertyEvent(captor.capture());
        reset(mVehicleHalCallback);
        CarPropertyValue carPropertyValue2 = new CarPropertyValue(PROP_ID_1, AREA_ID_GLOBAL, 0);
        VehiclePropValue vehiclePropValue2 = mock(VehiclePropValue.class);
        when(mMockHalPropValue2.toVehiclePropValue()).thenReturn(vehiclePropValue2);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue2), eq(PROP_ID_1), anyLong(),
                eq(mHalPropConfig1))).thenReturn(mMockHalPropValue2);
        when(mMockHalPropValue1.equalsExceptTimestamp(mMockHalPropValue2)).thenReturn(true);

        mSimulationVehicleStub.injectVehicleProperties(List.of(carPropertyValue2));

        verify(mVehicleHalCallback, timeout(2000).times(1))
                .onInjectionPropertyEvent(captor.capture());
        List<List> allCaptors = captor.getAllValues();
        List<HalPropValue> callbackList = new ArrayList<>(allCaptors.get(0));
        callbackList.addAll(allCaptors.get(1));
        assertWithMessage("onInjectionPropertyEvent called").that(callbackList)
                .containsExactly(mMockHalPropValue1, mMockHalPropValue2);
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
        HalPropValue updatedHalPropValue = mock(HalPropValue.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        VehicleStub.VehicleStubCallbackInterface callback = new VehicleStubCallbackTest(
                countDownLatch);
        when(mMockHalPropValueBuilder.build(any(VehiclePropValue.class)))
                .thenReturn(updatedHalPropValue);

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
        HalPropValue updatedHalPropValue = mock(HalPropValue.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        VehicleStubCallbackTest callback = new VehicleStubCallbackTest(
                countDownLatch);
        doThrow(new ServiceSpecificException(VehicleHalStatusCode.STATUS_NOT_AVAILABLE))
                .when(mMockVehicleStub).set(mMockHalPropValue2);
        when(mMockHalPropValueBuilder.build(any(VehiclePropValue.class)))
                .thenReturn(updatedHalPropValue);

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
        HalPropValue updatedHalPropValue = mock(HalPropValue.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        VehicleStubCallbackTest callback = new VehicleStubCallbackTest(
                countDownLatch);
        doThrow(new RemoteException()).when(mMockVehicleStub).set(mMockHalPropValue2);
        when(mMockHalPropValueBuilder.build(any(VehiclePropValue.class)))
                .thenReturn(updatedHalPropValue);

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

    @Test
    public void testIsSimulationMode() {
        assertWithMessage("SimulatedVehicleStub is simulation")
                .that(mSimulationVehicleStub.isSimulatedModeEnabled()).isTrue();
    }

    @Test
    public void testGetRealVehicleStub() {
        assertWithMessage("Real Vehicle Stub").that(mSimulationVehicleStub.getRealVehicleStub())
                .isEqualTo(mMockVehicleStub);
    }

    @Test
    public void testGetLastInjectedVehicleProperty_propertyDoesNotExist() {
        assertWithMessage("last injected property does not exist")
                .that(mSimulationVehicleStub.getLastInjectedVehicleProperty(52)).isNull();
    }

    @Test
    public void testGetLastInjectedVehicleProperty_propertyExists() throws Exception {
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        CarPropertyValue carPropertyValue1 = new CarPropertyValue(PROP_ID_1, AREA_ID_GLOBAL, 0);
        VehiclePropValue vehiclePropValue1 = mock(VehiclePropValue.class);
        when(mMockHalPropValue1.toVehiclePropValue()).thenReturn(vehiclePropValue1);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue1), eq(PROP_ID_1), anyLong(),
                eq(mHalPropConfig1))).thenReturn(mMockHalPropValue1);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        mSimulationVehicleStub.setReplayingVehicleHalCallback(
                new CountDownVehicleHalCallback(countDownLatch), List.of());

        mSimulationVehicleStub.injectVehicleProperties(List.of(carPropertyValue1));

        countDownLatch.await(1000, TimeUnit.MILLISECONDS);
        assertWithMessage("last injected property does not exist")
                .that(mSimulationVehicleStub.getLastInjectedVehicleProperty(PROP_ID_1))
                .isEqualTo(carPropertyValue1);
    }

    @Test
    public void testGetLastInjectedVehicleProperty_propertyChanges() throws Exception {
        mSimulationVehicleStub.newSubscriptionClient(mVehicleHalCallback);
        CarPropertyValue carPropertyValue1 = new CarPropertyValue(PROP_ID_1, AREA_ID_GLOBAL, 0);
        VehiclePropValue vehiclePropValue1 = mock(VehiclePropValue.class);
        when(mMockHalPropValue1.toVehiclePropValue()).thenReturn(vehiclePropValue1);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue1), eq(PROP_ID_1), anyLong(),
                eq(mHalPropConfig1))).thenReturn(mMockHalPropValue1);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        mSimulationVehicleStub.setReplayingVehicleHalCallback(
                new CountDownVehicleHalCallback(countDownLatch), List.of());

        mSimulationVehicleStub.injectVehicleProperties(List.of(carPropertyValue1));

        countDownLatch.await(1000, TimeUnit.MILLISECONDS);
        assertWithMessage("last injected property does not exist")
                .that(mSimulationVehicleStub.getLastInjectedVehicleProperty(PROP_ID_1))
                .isEqualTo(carPropertyValue1);

        CarPropertyValue carPropertyValue2 = new CarPropertyValue(PROP_ID_1, AREA_ID_GLOBAL, 0);
        VehiclePropValue vehiclePropValue2 = mock(VehiclePropValue.class);
        when(mMockHalPropValue1.toVehiclePropValue()).thenReturn(vehiclePropValue2);
        when(mMockHalPropValueBuilder.build(eq(carPropertyValue2), eq(PROP_ID_1), anyLong(),
                eq(mHalPropConfig1))).thenReturn(mMockHalPropValue1);
        CountDownLatch countDownLatch2 = new CountDownLatch(1);
        mSimulationVehicleStub.setReplayingVehicleHalCallback(
                new CountDownVehicleHalCallback(countDownLatch2), List.of());

        mSimulationVehicleStub.injectVehicleProperties(List.of(carPropertyValue2));

        countDownLatch2.await(1000, TimeUnit.MILLISECONDS);
        assertWithMessage("last injected property does not exist")
                .that(mSimulationVehicleStub.getLastInjectedVehicleProperty(PROP_ID_1))
                .isEqualTo(carPropertyValue2);
    }

    private static VehicleStub.AsyncGetSetRequest defaultVehicleStubAsyncRequest(
            HalPropValue value) {
        return new VehicleStub.AsyncGetSetRequest(/* serviceRequestId=*/ 0, value,
                /* timeoutUptimeMs= */ SystemClock.uptimeMillis() + 1000);
    }

    private static final class CountDownVehicleHalCallback implements VehicleHalCallback {
        private final CountDownLatch mCountDownLatch;
        private CountDownVehicleHalCallback(CountDownLatch countDownLatch) {
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onPropertyEvent(List<HalPropValue> values) {
        }

        @Override
        public void onPropertySetError(List<VehiclePropError> errors) {
        }

        @Override
        public void onSupportedValuesChange(List<PropIdAreaId> propIdAreaIds) {
        }

        @Override
        public void onInjectionPropertyEvent(List<HalPropValue> values) {
            mCountDownLatch.countDown();
        }
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
