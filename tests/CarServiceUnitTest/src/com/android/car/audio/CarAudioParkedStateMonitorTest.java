/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.car.audio;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.ICarPropertyEventListener;
import android.os.RemoteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.CarPropertyService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class CarAudioParkedStateMonitorTest {

    @Mock
    private CarPropertyService mMockCarPropertyService;
    @Mock
    private CarAudioParkedStateMonitor.ParkedStateListener mMockListener;

    private ArgumentCaptor<ICarPropertyEventListener> mCallbackCaptor;
    private CarAudioParkedStateMonitor mParkedStateMonitor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mCallbackCaptor = ArgumentCaptor.forClass(ICarPropertyEventListener.class);
    }

    private void setupMonitor(int initialGear) {
        CarPropertyValue<Integer> propertyValue = new CarPropertyValue<>(
                VehiclePropertyIds.GEAR_SELECTION, /* areaId= */ 0, initialGear);
        when(mMockCarPropertyService.getProperty(VehiclePropertyIds.GEAR_SELECTION,
                /* areaId= */ 0)).thenReturn(propertyValue);

        mParkedStateMonitor = new CarAudioParkedStateMonitor(mMockCarPropertyService,
                mMockListener);

        verify(mMockCarPropertyService).registerListener(
                eq(VehiclePropertyIds.GEAR_SELECTION),
                eq(CarPropertyManager.SENSOR_RATE_ONCHANGE),
                mCallbackCaptor.capture());
    }

    private CarPropertyEvent createGearPropertyEvent(int gear) {
        CarPropertyValue<Integer> newValue = new CarPropertyValue<>(
                VehiclePropertyIds.GEAR_SELECTION, 0, gear);
        return new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, newValue);
    }

    @Test
    public void constructor_whenGearIsInPark_setsInitialStateToParked() {
        setupMonitor(VehicleGear.GEAR_PARK);

        assertWithMessage("Parked state on construction")
                .that(mParkedStateMonitor.isParked()).isTrue();
    }

    @Test
    public void constructor_whenGearIsNotInPark_setsInitialStateToNotParked() {
        setupMonitor(VehicleGear.GEAR_DRIVE);

        assertWithMessage("Parked state on construction")
                .that(mParkedStateMonitor.isParked()).isFalse();
    }

    @Test
    public void onEvent_withGearChangeToPark_notifiesListener() throws RemoteException {
        setupMonitor(VehicleGear.GEAR_DRIVE);
        ICarPropertyEventListener callback = mCallbackCaptor.getValue();

        callback.onEvent(List.of(createGearPropertyEvent(VehicleGear.GEAR_PARK)));

        verify(mMockListener).onParkedStateChanged(true);
        assertWithMessage("Parked state after gear change")
                .that(mParkedStateMonitor.isParked()).isTrue();
    }

    @Test
    public void onEvent_withGearChangeFromPark_notifiesListener() throws RemoteException {
        setupMonitor(VehicleGear.GEAR_PARK);
        ICarPropertyEventListener callback = mCallbackCaptor.getValue();

        callback.onEvent(List.of(createGearPropertyEvent(VehicleGear.GEAR_DRIVE)));

        verify(mMockListener).onParkedStateChanged(false);
        assertWithMessage("Parked state after gear change")
                .that(mParkedStateMonitor.isParked()).isFalse();
    }

    @Test
    public void onEvent_withNoParkedStateChange_doesNotNotifyListener() throws RemoteException {
        setupMonitor(VehicleGear.GEAR_DRIVE);
        ICarPropertyEventListener callback = mCallbackCaptor.getValue();

        callback.onEvent(List.of(createGearPropertyEvent(VehicleGear.GEAR_REVERSE)));

        verify(mMockListener, never()).onParkedStateChanged(false);
    }

    @Test
    public void release_unregistersCallback() {
        setupMonitor(VehicleGear.GEAR_PARK);

        mParkedStateMonitor.release();

        verify(mMockCarPropertyService).unregisterListener(eq(VehiclePropertyIds.GEAR_SELECTION),
                eq(mCallbackCaptor.getValue()));
    }
}
