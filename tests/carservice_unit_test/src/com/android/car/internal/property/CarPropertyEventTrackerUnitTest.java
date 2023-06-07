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

package com.android.car.internal.property;

import static android.car.hardware.property.CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE;

import static com.google.common.truth.Truth.assertThat;

import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.util.SparseLongArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;

@RunWith(MockitoJUnitRunner.class)
public final class CarPropertyEventTrackerUnitTest {
    private static final int FIRST_PROPERTY_ID = 1234;
    private static final int SECOND_PROPERTY_ID = 1235;
    private static final int AREA_ID_1 = 908;
    private static final int AREA_ID_2 = 304;
    private static final Float FIRST_UPDATE_RATE_HZ = 1F;
    private static final long TIMESTAMP_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long FRESH_TIMESTAMP_NANOS = Duration.ofSeconds(2).toNanos();
    private static final long ALMOST_FRESH_TIMESTAMP_NANOS = Duration.ofMillis(1999).toNanos();
    private static final long STALE_TIMESTAMP_NANOS = Duration.ofMillis(500).toNanos();
    private static final Integer INTEGER_VALUE_1 = 8438;
    private static final Integer INTEGER_VALUE_2 = 4834;
    private static final CarPropertyValue<Integer> GOOD_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, TIMESTAMP_NANOS, INTEGER_VALUE_1);
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
    private CarPropertyEventTracker<Integer> mCarPropertyEventTracker;

    @Before
    public void setup() {
        mCarPropertyEventTracker = new CarPropertyEventTracker<>();
    }

    @Test
    public void put_keyBeingTracked() {
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                GOOD_CAR_PROPERTY_VALUE);

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, event, FIRST_UPDATE_RATE_HZ)).isFalse();

        mCarPropertyEventTracker.put(FIRST_PROPERTY_ID, new SparseLongArray());

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, event, FIRST_UPDATE_RATE_HZ)).isTrue();
    }

    @Test
    public void remove_keyNoLongerTracked() {
        mCarPropertyEventTracker.put(FIRST_PROPERTY_ID, new SparseLongArray());
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                GOOD_CAR_PROPERTY_VALUE);

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, event, FIRST_UPDATE_RATE_HZ)).isTrue();

        mCarPropertyEventTracker.remove(FIRST_PROPERTY_ID);

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, event, FIRST_UPDATE_RATE_HZ)).isFalse();
    }

    @Test
    public void shouldCallbackBeInvoked_returnsFalseWhenKeyNotFound() {
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                GOOD_CAR_PROPERTY_VALUE);

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, event, FIRST_UPDATE_RATE_HZ)).isFalse();
    }

    @Test
    public void shouldCallbackBeInvoked_returnsFalseWhenUpdateRateHzNull() {
        mCarPropertyEventTracker.put(FIRST_PROPERTY_ID, new SparseLongArray());
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                GOOD_CAR_PROPERTY_VALUE);

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, event, null)).isFalse();
    }

    @Test
    public void shouldCallbackBeInvoked_returnsTrueForFirstEvent() {
        mCarPropertyEventTracker.put(FIRST_PROPERTY_ID, new SparseLongArray());
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                GOOD_CAR_PROPERTY_VALUE);

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, event, FIRST_UPDATE_RATE_HZ)).isTrue();
    }

    @Test
    public void shouldCallbackBeInvoked_returnsTrueForFirstEventWithDifferentKey() {
        mCarPropertyEventTracker.put(FIRST_PROPERTY_ID, new SparseLongArray());
        mCarPropertyEventTracker.put(SECOND_PROPERTY_ID, new SparseLongArray());
        CarPropertyEvent firstEvent = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                GOOD_CAR_PROPERTY_VALUE);
        CarPropertyEvent secondEvent = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                STALE_CAR_PROPERTY_VALUE_WITH_DIFFERENT_PROPERTY_ID);

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, firstEvent, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                SECOND_PROPERTY_ID, secondEvent, FIRST_UPDATE_RATE_HZ)).isTrue();
    }

    @Test
    public void shouldCallbackBeInvoked_returnsTrueForFirstEventWithDifferentAreaId() {
        mCarPropertyEventTracker.put(FIRST_PROPERTY_ID, new SparseLongArray());
        CarPropertyEvent firstEvent = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                GOOD_CAR_PROPERTY_VALUE);
        CarPropertyEvent secondEvent = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                STALE_CAR_PROPERTY_VALUE_WITH_DIFFERENT_AREA_ID);

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, firstEvent, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, secondEvent, FIRST_UPDATE_RATE_HZ)).isTrue();
    }

    @Test
    public void shouldCallbackBeInvoked_returnsFalseForStaleEvent() {
        mCarPropertyEventTracker.put(FIRST_PROPERTY_ID, new SparseLongArray());
        CarPropertyEvent goodEvent = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                GOOD_CAR_PROPERTY_VALUE);
        CarPropertyEvent staleEvent = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                STALE_CAR_PROPERTY_VALUE);

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, goodEvent, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, staleEvent, FIRST_UPDATE_RATE_HZ)).isFalse();
    }

    @Test
    public void shouldCallbackBeInvoked_returnsFalseForEventBeforeNextUpdateTime() {
        mCarPropertyEventTracker.put(FIRST_PROPERTY_ID, new SparseLongArray());
        CarPropertyEvent goodEvent = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                GOOD_CAR_PROPERTY_VALUE);
        CarPropertyEvent tooEarlyEvent = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                ALMOST_FRESH_CAR_PROPERTY_VALUE);

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, goodEvent, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, tooEarlyEvent, FIRST_UPDATE_RATE_HZ)).isFalse();
    }

    @Test
    public void shouldCallbackBeInvoked_returnsTrueForFreshEvent() {
        mCarPropertyEventTracker.put(FIRST_PROPERTY_ID, new SparseLongArray());
        CarPropertyEvent goodEvent = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                GOOD_CAR_PROPERTY_VALUE);
        CarPropertyEvent freshEvent = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                FRESH_CAR_PROPERTY_VALUE);

        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, goodEvent, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyEventTracker.shouldCallbackBeInvoked(
                FIRST_PROPERTY_ID, freshEvent, FIRST_UPDATE_RATE_HZ)).isTrue();
    }
}
