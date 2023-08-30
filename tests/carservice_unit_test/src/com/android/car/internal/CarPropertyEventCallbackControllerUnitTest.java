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

package com.android.car.internal;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.Duration;


public final class CarPropertyEventCallbackControllerUnitTest {
    private static final int FIRST_PROPERTY_ID = 1234;
    private static final int SECOND_PROPERTY_ID = 4231;
    private static final int AREA_ID_1 = 908;
    private static final int AREA_ID_2 = 304;
    private static final int[] REGISTERED_AREA_IDS = new int[] {AREA_ID_1, AREA_ID_2};
    private static final float FIRST_UPDATE_RATE_HZ = 1F;
    private static final float SECOND_BIGGER_UPDATE_RATE_HZ = 2F;
    private static final float SECOND_SMALLER_UPDATE_RATE_HZ = 0.5F;
    private static final long TIMESTAMP_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long FRESH_TIMESTAMP_NANOS = Duration.ofSeconds(2).toNanos();
    private static final long ALMOST_FRESH_TIMESTAMP_NANOS = Duration.ofMillis(1999).toNanos();
    private static final long STALE_TIMESTAMP_NANOS = Duration.ofMillis(500).toNanos();
    private static final Integer INTEGER_VALUE_1 = 8438;
    private static final Integer INTEGER_VALUE_2 = 4834;
    private static final CarPropertyValue<Integer> GOOD_CAR_PROPERTY_VALUE = new CarPropertyValue<>(
            FIRST_PROPERTY_ID, AREA_ID_1, TIMESTAMP_NANOS, INTEGER_VALUE_1);
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
    private static final CarPropertyValue<Integer> STALE_CAR_PROPERTY_VALUE_WITH_DIFFERENT_AREA_ID =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_2,  STALE_TIMESTAMP_NANOS,
                    INTEGER_VALUE_1);
    private static final CarPropertyValue<Integer> ERROR_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, TIMESTAMP_NANOS, null);
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();
    @Captor
    private ArgumentCaptor<CarPropertyValue<?>> mCarPropertyValueCaptor;
    @Mock
    private CarPropertyManager.CarPropertyEventCallback mCarPropertyEventCallback;
    private CarPropertyEventCallbackController mCarPropertyEventCallbackController;

    @Before
    public void setUp() {
        mCarPropertyEventCallbackController =
                new CarPropertyEventCallbackController(mCarPropertyEventCallback);
    }

    @Test
    public void add_getUpdateRateHzOnePropertyAdded() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                FIRST_UPDATE_RATE_HZ);

        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(FIRST_PROPERTY_ID,
                AREA_ID_1)).isEqualTo(FIRST_UPDATE_RATE_HZ);
        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(FIRST_PROPERTY_ID,
                AREA_ID_2)).isEqualTo(FIRST_UPDATE_RATE_HZ);
    }

    @Test
    public void add_getLatestRateIfSamePropertyAddedTwice() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                FIRST_UPDATE_RATE_HZ);
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                SECOND_BIGGER_UPDATE_RATE_HZ);

        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(FIRST_PROPERTY_ID,
                AREA_ID_1)).isEqualTo(SECOND_BIGGER_UPDATE_RATE_HZ);
        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(FIRST_PROPERTY_ID,
                AREA_ID_2)).isEqualTo(SECOND_BIGGER_UPDATE_RATE_HZ);

        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                SECOND_SMALLER_UPDATE_RATE_HZ);

        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(FIRST_PROPERTY_ID,
                AREA_ID_1)).isEqualTo(SECOND_SMALLER_UPDATE_RATE_HZ);
        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(FIRST_PROPERTY_ID,
                AREA_ID_2)).isEqualTo(SECOND_SMALLER_UPDATE_RATE_HZ);
    }

    @Test
    public void getUpdateRateHz_rateNotFoundNoPropertyAdded() {
        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(FIRST_PROPERTY_ID,
                AREA_ID_1)).isEqualTo(0f);
    }

    @Test
    public void getUpdateRateHz_multiplePropertiesAdded() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                FIRST_UPDATE_RATE_HZ);
        mCarPropertyEventCallbackController.add(SECOND_PROPERTY_ID, REGISTERED_AREA_IDS,
                SECOND_BIGGER_UPDATE_RATE_HZ);

        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(FIRST_PROPERTY_ID,
                AREA_ID_1)).isEqualTo(FIRST_UPDATE_RATE_HZ);
        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(FIRST_PROPERTY_ID,
                AREA_ID_2)).isEqualTo(FIRST_UPDATE_RATE_HZ);
        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(SECOND_PROPERTY_ID,
                AREA_ID_1)).isEqualTo(SECOND_BIGGER_UPDATE_RATE_HZ);
        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(SECOND_PROPERTY_ID,
                AREA_ID_2)).isEqualTo(SECOND_BIGGER_UPDATE_RATE_HZ);
    }

    @Test
    public void remove_doesNothingIfNoPropertyAdded() {
        assertThat(mCarPropertyEventCallbackController.remove(FIRST_PROPERTY_ID))
                .isTrue();
        assertThat(mCarPropertyEventCallbackController.getUpdateRateHz(FIRST_PROPERTY_ID,
                AREA_ID_1)).isEqualTo(0f);
    }

    @Test
    public void remove_addThenRemoveProperty() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                FIRST_UPDATE_RATE_HZ);

        assertThat(mCarPropertyEventCallbackController.remove(FIRST_PROPERTY_ID)).isTrue();
    }

    @Test
    public void remove_addTwoPropertiesRemoveOne() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                FIRST_UPDATE_RATE_HZ);
        mCarPropertyEventCallbackController.add(SECOND_PROPERTY_ID, REGISTERED_AREA_IDS,
                FIRST_UPDATE_RATE_HZ);

        assertThat(mCarPropertyEventCallbackController.remove(FIRST_PROPERTY_ID)).isFalse();
    }

    @Test
    public void onEvent_changeEvent_doesNothingIfNoPropertiesAdded() {
        CarPropertyValue<Integer> carPropertyValue = new CarPropertyValue<>(FIRST_PROPERTY_ID,
                AREA_ID_1, 567);
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        carPropertyValue));

        verify(mCarPropertyEventCallback, never()).onChangeEvent(any());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onEvent_changeEvent_forwardsToCallback() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        GOOD_CAR_PROPERTY_VALUE));

        verify(mCarPropertyEventCallback).onChangeEvent(GOOD_CAR_PROPERTY_VALUE);
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onEvent_changeEvent_forwardsMultipleEvents() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mCarPropertyEventCallbackController.add(SECOND_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        GOOD_CAR_PROPERTY_VALUE));
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        SECOND_GOOD_CAR_PROPERTY_VALUE));

        verify(mCarPropertyEventCallback).onChangeEvent(GOOD_CAR_PROPERTY_VALUE);
        verify(mCarPropertyEventCallback).onChangeEvent(SECOND_GOOD_CAR_PROPERTY_VALUE);
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onEvent_changeEvent_skipsStaleCarPropertyValues() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        GOOD_CAR_PROPERTY_VALUE));
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        STALE_CAR_PROPERTY_VALUE));

        verify(mCarPropertyEventCallback).onChangeEvent(GOOD_CAR_PROPERTY_VALUE);
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onEvent_changeEvent_skipsCarPropertyValuesWithNonZeroUpdateRate() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                FIRST_UPDATE_RATE_HZ);
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        GOOD_CAR_PROPERTY_VALUE));
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        ALMOST_FRESH_CAR_PROPERTY_VALUE));

        verify(mCarPropertyEventCallback).onChangeEvent(mCarPropertyValueCaptor.capture());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt(), anyInt());
        assertThat(mCarPropertyValueCaptor.getAllValues()).containsExactly(GOOD_CAR_PROPERTY_VALUE);
    }

    @Test
    public void onEvent_changeEvent_forwardsFreshCarPropertyValues() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        GOOD_CAR_PROPERTY_VALUE));
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        FRESH_CAR_PROPERTY_VALUE));

        verify(mCarPropertyEventCallback, times(2)).onChangeEvent(
                mCarPropertyValueCaptor.capture());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt(), anyInt());
        assertThat(mCarPropertyValueCaptor.getAllValues()).containsExactly(GOOD_CAR_PROPERTY_VALUE,
                FRESH_CAR_PROPERTY_VALUE);
    }

    @Test
    public void onEvent_changeEvent_forwardsFreshCarPropertyValuesWithNonZeroUpdateRate() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                FIRST_UPDATE_RATE_HZ);
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        GOOD_CAR_PROPERTY_VALUE));
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        FRESH_CAR_PROPERTY_VALUE));

        verify(mCarPropertyEventCallback, times(2)).onChangeEvent(
                mCarPropertyValueCaptor.capture());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt(), anyInt());
        assertThat(mCarPropertyValueCaptor.getAllValues()).containsExactly(GOOD_CAR_PROPERTY_VALUE,
                FRESH_CAR_PROPERTY_VALUE);
    }

    @Test
    public void onEvent_changeEvent_forwardsStaleCarPropertyValuesWithDifferentAreaId() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        GOOD_CAR_PROPERTY_VALUE));
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        STALE_CAR_PROPERTY_VALUE_WITH_DIFFERENT_AREA_ID));

        verify(mCarPropertyEventCallback, times(2)).onChangeEvent(
                mCarPropertyValueCaptor.capture());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt(), anyInt());
        assertThat(mCarPropertyValueCaptor.getAllValues()).containsExactly(GOOD_CAR_PROPERTY_VALUE,
                STALE_CAR_PROPERTY_VALUE_WITH_DIFFERENT_AREA_ID);
    }

    @Test
    public void onEvent_errorEvent_doesNothingIfNoPropertiesAdded() {
        CarPropertyValue<Integer> carPropertyValue = new CarPropertyValue<>(FIRST_PROPERTY_ID,
                AREA_ID_1, 567);
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_ERROR, carPropertyValue));

        verify(mCarPropertyEventCallback, never()).onChangeEvent(any());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onEvent_errorEvent_forwardsToCallback() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_ERROR,
                        ERROR_CAR_PROPERTY_VALUE,
                        CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG));

        verify(mCarPropertyEventCallback, never()).onChangeEvent(any());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback).onErrorEvent(FIRST_PROPERTY_ID, AREA_ID_1,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG);
    }

    @Test
    public void onEvent_errorEvent_forwardsToMultipleProperties() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mCarPropertyEventCallbackController.add(SECOND_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_ERROR,
                        GOOD_CAR_PROPERTY_VALUE,
                        CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG));
        mCarPropertyEventCallbackController.onEvent(
                new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_ERROR,
                        SECOND_GOOD_CAR_PROPERTY_VALUE,
                        CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG));

        verify(mCarPropertyEventCallback, never()).onChangeEvent(any());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback).onErrorEvent(FIRST_PROPERTY_ID, AREA_ID_1,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG);
        verify(mCarPropertyEventCallback).onErrorEvent(SECOND_PROPERTY_ID, AREA_ID_1,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG);
    }

    @Test
    public void onEvent_unknownEventType() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mCarPropertyEventCallbackController.onEvent(new CarPropertyEvent(
                /* Unsupported event type: */ 2, ERROR_CAR_PROPERTY_VALUE));

        verify(mCarPropertyEventCallback, never()).onChangeEvent(any());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt());
        verify(mCarPropertyEventCallback, never()).onErrorEvent(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void getSubscribedProperties_noProperties() {
        assertThat(mCarPropertyEventCallbackController.getSubscribedProperties()).asList()
                .isEmpty();
    }

    @Test
    public void getSubscribedProperties_onePropertyAdded() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        assertThat(mCarPropertyEventCallbackController.getSubscribedProperties()).asList()
                .containsExactly(FIRST_PROPERTY_ID);
    }

    @Test
    public void getSubscribedProperties_onePropertyAddedThenRemoved() {
        mCarPropertyEventCallbackController.add(FIRST_PROPERTY_ID, REGISTERED_AREA_IDS,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        assertThat(mCarPropertyEventCallbackController.getSubscribedProperties()).asList()
                .containsExactly(FIRST_PROPERTY_ID);
        assertThat(mCarPropertyEventCallbackController.remove(FIRST_PROPERTY_ID)).isTrue();
        assertThat(mCarPropertyEventCallbackController.getSubscribedProperties()).asList()
                .isEmpty();
    }
}
