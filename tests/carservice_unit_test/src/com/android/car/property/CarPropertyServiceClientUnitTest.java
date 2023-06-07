/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.car.property;

import static android.car.hardware.property.CarPropertyEvent.PROPERTY_EVENT_ERROR;
import static android.car.hardware.property.CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE;
import static android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.os.IBinder;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class CarPropertyServiceClientUnitTest {
    private static final int FIRST_PROPERTY_ID = 1234;
    private static final int SECOND_PROPERTY_ID = 1235;
    private static final int AREA_ID_1 = 908;
    private static final int AREA_ID_2 = 304;
    private static final Float FIRST_UPDATE_RATE_HZ = 1F;
    private static final Float SECOND_BIGGER_UPDATE_RATE_HZ = 2F;
    private static final Float SECOND_SMALLER_UPDATE_RATE_HZ = 0.5F;
    private static final long TIMESTAMP_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long FRESH_TIMESTAMP_NANOS = Duration.ofSeconds(2).toNanos();
    private static final long ALMOST_FRESH_TIMESTAMP_NANOS = Duration.ofMillis(1999).toNanos();
    private static final long STALE_TIMESTAMP_NANOS = Duration.ofMillis(500).toNanos();
    private static final Integer INTEGER_VALUE_1 = 8438;
    private static final Integer INTEGER_VALUE_2 = 4834;
    private static final CarPropertyValue<Integer> GOOD_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, TIMESTAMP_NANOS, INTEGER_VALUE_1);
    private static final CarPropertyValue<Integer> SECOND_GOOD_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(SECOND_PROPERTY_ID, AREA_ID_1, TIMESTAMP_NANOS, INTEGER_VALUE_1);
    private static final CarPropertyValue<Integer> FRESH_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, FRESH_TIMESTAMP_NANOS,
                    INTEGER_VALUE_2);
    private static final CarPropertyValue<Integer> ALMOST_FRESH_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, ALMOST_FRESH_TIMESTAMP_NANOS,
                    INTEGER_VALUE_2);
    private static final CarPropertyValue<Integer> STALE_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, STALE_TIMESTAMP_NANOS,
                    INTEGER_VALUE_2);
    private static final CarPropertyValue<Integer>
            STALE_CAR_PROPERTY_VALUE_WITH_DIFFERENT_PROPERTY_ID = new CarPropertyValue<>(
                    SECOND_PROPERTY_ID, AREA_ID_1, STALE_TIMESTAMP_NANOS, INTEGER_VALUE_1);
    private static final CarPropertyValue<Integer> STALE_CAR_PROPERTY_VALUE_WITH_DIFFERENT_AREA_ID =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_2, STALE_TIMESTAMP_NANOS,
                    INTEGER_VALUE_1);
    private static final CarPropertyValue<Integer> ERROR_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, TIMESTAMP_NANOS, null);

    private CarPropertyServiceClient mCarPropertyServiceClient;

    @Mock
    private ICarPropertyEventListener mICarPropertyEventListener;
    @Mock
    private IBinder mListenerBinder;
    @Mock
    private CarPropertyServiceClient.UnregisterCallback mUnregisterCallback;
    @Captor
    private ArgumentCaptor<List<CarPropertyEvent>> mCarPropertyEventListCaptor;

    @Before
    public void setup() {
        when(mICarPropertyEventListener.asBinder()).thenReturn(mListenerBinder);
        mCarPropertyServiceClient = new CarPropertyServiceClient(mICarPropertyEventListener,
                mUnregisterCallback);
    }

    @Test
    public void addProperty_getUpdateRateHzOnePropertyAdded() {
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, FIRST_UPDATE_RATE_HZ);

        assertThat(mCarPropertyServiceClient.getUpdateRateHz(FIRST_PROPERTY_ID))
                .isEqualTo(FIRST_UPDATE_RATE_HZ);
    }

    @Test
    public void addProperty_getLatestRateIfSamePropertyAddedTwice() {
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, FIRST_UPDATE_RATE_HZ);
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, SECOND_BIGGER_UPDATE_RATE_HZ);

        assertThat(mCarPropertyServiceClient.getUpdateRateHz(FIRST_PROPERTY_ID))
                .isEqualTo(SECOND_BIGGER_UPDATE_RATE_HZ);

        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, SECOND_SMALLER_UPDATE_RATE_HZ);

        assertThat(mCarPropertyServiceClient.getUpdateRateHz(FIRST_PROPERTY_ID))
                .isEqualTo(SECOND_SMALLER_UPDATE_RATE_HZ);
    }

    @Test
    public void addProperty_rateNotFoundIfBinderDead() {
        mCarPropertyServiceClient.binderDied();
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, FIRST_UPDATE_RATE_HZ);

        assertThat(mCarPropertyServiceClient.getUpdateRateHz(FIRST_PROPERTY_ID))
                .isEqualTo(0f);
    }

    @Test
    public void getUpdateRateHz_rateNotFoundNoPropertyAdded() {
        assertThat(mCarPropertyServiceClient.getUpdateRateHz(FIRST_PROPERTY_ID))
                .isEqualTo(0f);
    }

    @Test
    public void removeProperty_doesNothingIfNoPropertyAdded() {
        assertThat(mCarPropertyServiceClient.removeProperty(FIRST_PROPERTY_ID))
                .isEqualTo(0);
        verify(mListenerBinder).unlinkToDeath(any(), anyInt());
    }

    @Test
    public void removeProperty_onePropertyRemoved() {
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, FIRST_UPDATE_RATE_HZ);
        mCarPropertyServiceClient.addProperty(SECOND_PROPERTY_ID, FIRST_UPDATE_RATE_HZ);

        assertThat(mCarPropertyServiceClient.removeProperty(FIRST_PROPERTY_ID))
                .isEqualTo(1);
    }

    @Test
    public void onEvent_listenerNotCalledIfNoPropertyAdded() throws RemoteException {
        List<CarPropertyEvent> events = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, GOOD_CAR_PROPERTY_VALUE));

        mCarPropertyServiceClient.onEvent(events);

        verify(mICarPropertyEventListener, never()).onEvent(any());
    }

    @Test
    public void onEvent_listenerNotCalledIfBinderDead() throws RemoteException {
        List<CarPropertyEvent> events = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_ERROR, ERROR_CAR_PROPERTY_VALUE));

        mCarPropertyServiceClient.binderDied();
        mCarPropertyServiceClient.onEvent(events);

        verify(mICarPropertyEventListener, never()).onEvent(any());
    }

    @Test
    public void onEvent_listenerCalledForErrorEvents() throws RemoteException {
        List<CarPropertyEvent> events = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_ERROR, ERROR_CAR_PROPERTY_VALUE));

        mCarPropertyServiceClient.onEvent(events);

        verify(mICarPropertyEventListener).onEvent(mCarPropertyEventListCaptor.capture());
        assertThat(mCarPropertyEventListCaptor.getAllValues()).containsExactly(events);
    }

    @Test
    public void onEvent_listenerCalledForChangeEvents() throws RemoteException {
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, SENSOR_RATE_ONCHANGE);
        List<CarPropertyEvent> events = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, GOOD_CAR_PROPERTY_VALUE));

        mCarPropertyServiceClient.onEvent(events);

        verify(mICarPropertyEventListener).onEvent(mCarPropertyEventListCaptor.capture());
        assertThat(mCarPropertyEventListCaptor.getAllValues()).containsExactly(events);
    }

    @Test
    public void onEvent_listenerCalledForMultipleProperties() throws RemoteException {
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, SENSOR_RATE_ONCHANGE);
        mCarPropertyServiceClient.addProperty(SECOND_PROPERTY_ID, SENSOR_RATE_ONCHANGE);
        List<CarPropertyEvent> events = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, GOOD_CAR_PROPERTY_VALUE),
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                        SECOND_GOOD_CAR_PROPERTY_VALUE));

        mCarPropertyServiceClient.onEvent(events);

        verify(mICarPropertyEventListener).onEvent(mCarPropertyEventListCaptor.capture());
        assertThat(mCarPropertyEventListCaptor.getAllValues()).containsExactly(events);
    }

    @Test
    public void onEvent_listenerNotCalledForStaleCarPropertyValues() throws RemoteException {
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, SENSOR_RATE_ONCHANGE);
        List<CarPropertyEvent> goodEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, GOOD_CAR_PROPERTY_VALUE));
        List<CarPropertyEvent> staleEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, STALE_CAR_PROPERTY_VALUE));

        mCarPropertyServiceClient.onEvent(goodEvents);
        mCarPropertyServiceClient.onEvent(staleEvents);

        verify(mICarPropertyEventListener).onEvent(mCarPropertyEventListCaptor.capture());
        assertThat(mCarPropertyEventListCaptor.getAllValues()).containsExactly(goodEvents);
    }

    @Test
    public void onEvent_listenerNotCalledForCarPropertyValuesBeforeNextUpdateTime()
            throws RemoteException {
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, FIRST_UPDATE_RATE_HZ);
        List<CarPropertyEvent> goodEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, GOOD_CAR_PROPERTY_VALUE));
        List<CarPropertyEvent> tooEarlyEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                        ALMOST_FRESH_CAR_PROPERTY_VALUE));

        mCarPropertyServiceClient.onEvent(goodEvents);
        mCarPropertyServiceClient.onEvent(tooEarlyEvents);

        verify(mICarPropertyEventListener).onEvent(mCarPropertyEventListCaptor.capture());
        assertThat(mCarPropertyEventListCaptor.getAllValues()).containsExactly(goodEvents);
    }

    @Test
    public void onEvent_listenerCalledForFreshCarPropertyValues() throws RemoteException {
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, SENSOR_RATE_ONCHANGE);
        List<CarPropertyEvent> goodEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, GOOD_CAR_PROPERTY_VALUE));
        List<CarPropertyEvent> freshEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, FRESH_CAR_PROPERTY_VALUE));

        mCarPropertyServiceClient.onEvent(goodEvents);
        mCarPropertyServiceClient.onEvent(freshEvents);

        verify(mICarPropertyEventListener, times(2)).onEvent(
                mCarPropertyEventListCaptor.capture());
        assertThat(mCarPropertyEventListCaptor.getAllValues()).containsExactly(goodEvents,
                freshEvents);
    }

    @Test
    public void onEvent_listenerCalledForFreshCarPropertyValuesNonZeroUpdateRate()
            throws RemoteException {
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, FIRST_UPDATE_RATE_HZ);
        List<CarPropertyEvent> goodEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, GOOD_CAR_PROPERTY_VALUE));
        List<CarPropertyEvent> freshEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, FRESH_CAR_PROPERTY_VALUE));

        mCarPropertyServiceClient.onEvent(goodEvents);
        mCarPropertyServiceClient.onEvent(freshEvents);

        verify(mICarPropertyEventListener, times(2)).onEvent(
                mCarPropertyEventListCaptor.capture());
        assertThat(mCarPropertyEventListCaptor.getAllValues()).containsExactly(goodEvents,
                freshEvents);
    }

    @Test
    public void onEvent_listenerCalledForCarPropertyValuesWithDifferentPropertyId()
            throws RemoteException {
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, SENSOR_RATE_ONCHANGE);
        mCarPropertyServiceClient.addProperty(SECOND_PROPERTY_ID, SENSOR_RATE_ONCHANGE);
        List<CarPropertyEvent> goodEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, GOOD_CAR_PROPERTY_VALUE));
        List<CarPropertyEvent> staleEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                        STALE_CAR_PROPERTY_VALUE_WITH_DIFFERENT_PROPERTY_ID));

        mCarPropertyServiceClient.onEvent(goodEvents);
        mCarPropertyServiceClient.onEvent(staleEvents);

        verify(mICarPropertyEventListener, times(2)).onEvent(
                mCarPropertyEventListCaptor.capture());
        assertThat(mCarPropertyEventListCaptor.getAllValues()).containsExactly(goodEvents,
                staleEvents);
    }

    @Test
    public void onEvent_listenerCalledForCarPropertyValuesWithDifferentAreaId()
            throws RemoteException {
        mCarPropertyServiceClient.addProperty(FIRST_PROPERTY_ID, SENSOR_RATE_ONCHANGE);
        List<CarPropertyEvent> goodEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE, GOOD_CAR_PROPERTY_VALUE));
        List<CarPropertyEvent> staleEvents = List.of(
                new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                        STALE_CAR_PROPERTY_VALUE_WITH_DIFFERENT_AREA_ID));

        mCarPropertyServiceClient.onEvent(goodEvents);
        mCarPropertyServiceClient.onEvent(staleEvents);

        verify(mICarPropertyEventListener, times(2)).onEvent(
                mCarPropertyEventListCaptor.capture());
        assertThat(mCarPropertyEventListCaptor.getAllValues()).containsExactly(goodEvents,
                staleEvents);
    }
}
