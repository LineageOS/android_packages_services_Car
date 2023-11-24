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

import static com.google.common.truth.Truth.assertThat;

import android.car.hardware.CarPropertyValue;

import org.junit.Before;
import org.junit.Test;

import java.time.Duration;

public final class ContCarPropertyEventTrackerUnitTest {
    private static final int FIRST_PROPERTY_ID = 1234;
    private static final int AREA_ID_1 = 908;
    private static final float FIRST_UPDATE_RATE_HZ = 1F;
    private static final long TIMESTAMP_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long FRESH_TIMESTAMP_NANOS = Duration.ofSeconds(2).toNanos();
    private static final long TOO_EARLY_TIMESTAMP_NANOS = Duration.ofMillis(1899).toNanos();
    private static final long ALMOST_FRESH_TIMESTAMP_NANOS = Duration.ofMillis(1999).toNanos();
    private static final long STALE_TIMESTAMP_NANOS = Duration.ofMillis(500).toNanos();
    private static final Integer INTEGER_VALUE_1 = 8438;
    private static final Integer INTEGER_VALUE_2 = 9134;
    private static final CarPropertyValue<Integer> GOOD_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, TIMESTAMP_NANOS, INTEGER_VALUE_1);
    private static final CarPropertyValue<Integer> FRESH_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, FRESH_TIMESTAMP_NANOS,
                    INTEGER_VALUE_1);
    private static final CarPropertyValue<Integer> FRESH_CAR_PROPERTY_VALUE_VALUE_2 =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, FRESH_TIMESTAMP_NANOS,
                    INTEGER_VALUE_2);
    private static final CarPropertyValue<Integer> TOO_EARLY_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, TOO_EARLY_TIMESTAMP_NANOS,
                    INTEGER_VALUE_1);
    private static final CarPropertyValue<Integer> ALMOST_FRESH_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, ALMOST_FRESH_TIMESTAMP_NANOS,
                    INTEGER_VALUE_1);
    private static final CarPropertyValue<Integer> STALE_CAR_PROPERTY_VALUE =
            new CarPropertyValue<>(FIRST_PROPERTY_ID, AREA_ID_1, STALE_TIMESTAMP_NANOS,
                    INTEGER_VALUE_1);
    private ContCarPropertyEventTracker mNoVURTracker;
    private ContCarPropertyEventTracker mVURTracker;

    @Before
    public void setup() {
        mNoVURTracker = new ContCarPropertyEventTracker(
                FIRST_UPDATE_RATE_HZ, /* enableVUR= */ false);
        mVURTracker = new ContCarPropertyEventTracker(
                FIRST_UPDATE_RATE_HZ, /* enableVUR= */ true);
    }

    @Test
    public void testHasUpdate_returnsTrueForFirstEvent() {
        assertThat(mNoVURTracker.hasUpdate(GOOD_CAR_PROPERTY_VALUE)).isTrue();
    }

    @Test
    public void testHasUpdate_returnsTrueForFirstEvent_vur() {
        assertThat(mVURTracker.hasUpdate(GOOD_CAR_PROPERTY_VALUE)).isTrue();
    }

    @Test
    public void testHasUpdate_returnsFalseForStaleEvent() {
        assertThat(mNoVURTracker.hasUpdate(GOOD_CAR_PROPERTY_VALUE)).isTrue();
        assertThat(mNoVURTracker.hasUpdate(STALE_CAR_PROPERTY_VALUE)).isFalse();
    }

    @Test
    public void testHasUpdate_returnsFalseForStaleEvent_vur() {
        assertThat(mVURTracker.hasUpdate(GOOD_CAR_PROPERTY_VALUE)).isTrue();
        assertThat(mVURTracker.hasUpdate(STALE_CAR_PROPERTY_VALUE)).isFalse();
    }

    @Test
    public void testHasUpdate_returnsFalseForEventArrivedTooEarly() {
        assertThat(mNoVURTracker.hasUpdate(GOOD_CAR_PROPERTY_VALUE))
                .isTrue();
        assertThat(mNoVURTracker.hasUpdate(TOO_EARLY_CAR_PROPERTY_VALUE)).isFalse();
    }

    @Test
    public void testHasUpdate_returnsFalseForEventArrivedTooEarly_vur() {
        assertThat(mVURTracker.hasUpdate(GOOD_CAR_PROPERTY_VALUE)).isTrue();
        assertThat(mVURTracker.hasUpdate(TOO_EARLY_CAR_PROPERTY_VALUE)).isFalse();
    }

    @Test
    public void testHasUpdate_returnsTrueForEventWithinOffset() {
        assertThat(mNoVURTracker.hasUpdate(GOOD_CAR_PROPERTY_VALUE)).isTrue();
        assertThat(mNoVURTracker.hasUpdate(ALMOST_FRESH_CAR_PROPERTY_VALUE)).isTrue();
    }

    @Test
    public void testHasUpdate_returnsTrueForFreshEvent() {
        assertThat(mNoVURTracker.hasUpdate(GOOD_CAR_PROPERTY_VALUE)).isTrue();
        assertThat(mNoVURTracker.hasUpdate(FRESH_CAR_PROPERTY_VALUE)).isTrue();
    }

    @Test
    public void testHasUpdate_returnsFalseIfValueIsTheSame_vur() {
        assertThat(mVURTracker.hasUpdate(GOOD_CAR_PROPERTY_VALUE)).isTrue();
        // FRESH_CAR_PROPERTY_VALUE has the same value as GOOD_CAR_PROPERTY_VALUE.
        assertThat(mVURTracker.hasUpdate(FRESH_CAR_PROPERTY_VALUE)).isFalse();
    }

    @Test
    public void testHasUpdate_returnsTrueIfValueIsDifferent_vur() {
        assertThat(mVURTracker.hasUpdate(GOOD_CAR_PROPERTY_VALUE)).isTrue();
        // FRESH_CAR_PROPERTY_VALUE has the same value as GOOD_CAR_PROPERTY_VALUE.
        assertThat(mVURTracker.hasUpdate(FRESH_CAR_PROPERTY_VALUE_VALUE_2)).isTrue();
    }
}
