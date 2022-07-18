/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.car.VehiclePropertyIds.HVAC_TEMPERATURE_SET;
import static android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.VehicleAreaType;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.ICarPropertyEventListener;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public final class CarPropertyManagerUnitTest {

    @Mock
    private CarPropertyService mService;
    @Mock
    private Context mContext;
    @Mock
    private CarPropertyManager.CarPropertyEventCallback mCallback;
    private CarPropertyManager mCarPropertyManager;

    @Before
    public void setUp() {
        when(mContext.getApplicationInfo()).thenReturn(new ApplicationInfo());
        Car car = new Car(mContext, /* service= */ null, /* handler= */ null);
        mCarPropertyManager = new CarPropertyManager(car, mService);
    }

    @Test
    public void testGetProperty() {
        CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);

        when(mService.getProperty(HVAC_TEMPERATURE_SET, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0)).isEqualTo(value);
    }

    @Test
    public void testSetProperty() {
        mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f);

        ArgumentCaptor<CarPropertyValue> value = ArgumentCaptor.forClass(CarPropertyValue.class);

        verify(mService).setProperty(value.capture(), any());
        assertThat(value.getValue().getValue()).isEqualTo(17.0f);
    }

    @Test
    public void testRegisterCallbackNormalRate() {
        List<CarPropertyConfig> configs = List.of(
                CarPropertyConfig.newBuilder(Float.class, HVAC_TEMPERATURE_SET,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL).build());
        when(mService.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET})).thenReturn(configs);

        // Check for HVAC_TEMPERATURE_SET
        assertThat(mCarPropertyManager.registerCallback(mCallback, HVAC_TEMPERATURE_SET, 5.0f))
                .isEqualTo(true);

        verify(mService).registerListener(eq(HVAC_TEMPERATURE_SET), eq(5.0f), any());
    }

    @Test
    public void testRegisterCallbackHigherRate() {
        List<CarPropertyConfig> configs = List.of(
                CarPropertyConfig.newBuilder(Float.class, HVAC_TEMPERATURE_SET,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL).build());
        when(mService.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET})).thenReturn(configs);

        // Check for HVAC_TEMPERATURE_SET
        assertThat(mCarPropertyManager.registerCallback(mCallback, HVAC_TEMPERATURE_SET, 5.0f))
                .isEqualTo(true);

        verify(mService).registerListener(eq(HVAC_TEMPERATURE_SET), eq(5.0f), any());

        // Check for HVAC_TEMPERATURE_SET higher rate
        assertThat(mCarPropertyManager.registerCallback(mCallback, HVAC_TEMPERATURE_SET, 10.0f))
                .isEqualTo(true);

        verify(mService).registerListener(eq(HVAC_TEMPERATURE_SET), eq(10.0f), any());
    }

    @Test
    public void testRegisterCallbackSameRate() {
        List<CarPropertyConfig> configs = List.of(
                CarPropertyConfig.newBuilder(Float.class, HVAC_TEMPERATURE_SET,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL).build());
        when(mService.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET})).thenReturn(configs);

        // Check for HVAC_TEMPERATURE_SET
        assertThat(mCarPropertyManager.registerCallback(mCallback, HVAC_TEMPERATURE_SET, 5.0f))
                .isEqualTo(true);

        verify(mService).registerListener(eq(HVAC_TEMPERATURE_SET), eq(5.0f), any());

        // Clean up invocation state.
        clearInvocations(mService);

        // Check for HVAC_TEMPERATURE_SET lower rate. Should not be called
        assertThat(mCarPropertyManager.registerCallback(mCallback, HVAC_TEMPERATURE_SET, 5.0f))
                .isEqualTo(true);

        verify(mService, never()).registerListener(eq(HVAC_TEMPERATURE_SET), eq(5.0f), any());
    }

    @Test
    public void testRegisterCallbackConfigNull() {
        when(mService.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET})).thenReturn(
                new ArrayList<>());

        assertThat(mCarPropertyManager.registerCallback(mCallback, HVAC_TEMPERATURE_SET, 5))
                .isEqualTo(false);
    }

    private ICarPropertyEventListener getCarPropertyEventListener() {
        ArgumentCaptor<ICarPropertyEventListener> carPropertyEventListenerArgumentCaptor =
                ArgumentCaptor.forClass(ICarPropertyEventListener.class);
        mCarPropertyManager.registerCallback(mCallback, HVAC_TEMPERATURE_SET, SENSOR_RATE_ONCHANGE);

        verify(mService).registerListener(eq(HVAC_TEMPERATURE_SET), eq(SENSOR_RATE_ONCHANGE),
                carPropertyEventListenerArgumentCaptor.capture());

        return carPropertyEventListenerArgumentCaptor.getValue();
    }

    private List<CarPropertyEvent> createErrorCarPropertyEventList() {
        CarPropertyValue<Integer> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0,
                CarPropertyValue.STATUS_ERROR, 0, -1);
        CarPropertyEvent carPropertyEvent = new CarPropertyEvent(
                CarPropertyEvent.PROPERTY_EVENT_ERROR, value,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN);
        return List.of(carPropertyEvent);
    }

    @Test
    public void testOnErrorEventCallback() throws RemoteException {
        List<CarPropertyEvent> eventList = createErrorCarPropertyEventList();
        List<CarPropertyConfig> configs = List.of(
                CarPropertyConfig.newBuilder(Float.class, HVAC_TEMPERATURE_SET,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL).build());
        when(mService.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET})).thenReturn(configs);
        ICarPropertyEventListener listener = getCarPropertyEventListener();

        listener.onEvent(eventList);

        // Wait until we get the on error event for the initial value.
        verify(mCallback, timeout(5000)).onErrorEvent(HVAC_TEMPERATURE_SET, 0,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN);
    }

    private List<CarPropertyEvent> createCarPropertyEventList() {
        CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);
        CarPropertyEvent carPropertyEvent = new CarPropertyEvent(
                CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, value);
        return List.of(carPropertyEvent);
    }

    @Test
    public void testOnChangeEventCallback() throws RemoteException {
        List<CarPropertyEvent> eventList = createCarPropertyEventList();
        List<CarPropertyConfig> configs = List.of(
                CarPropertyConfig.newBuilder(Float.class, HVAC_TEMPERATURE_SET,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL).build());
        when(mService.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET})).thenReturn(configs);
        ICarPropertyEventListener listener = getCarPropertyEventListener();
        ArgumentCaptor<CarPropertyValue> value = ArgumentCaptor.forClass(CarPropertyValue.class);

        listener.onEvent(eventList);

        // Wait until we get the on property change event for the initial value.
        verify(mCallback, timeout(5000)).onChangeEvent(value.capture());
        assertThat(value.getValue().getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(value.getValue().getValue()).isEqualTo(17.0f);
    }
}
