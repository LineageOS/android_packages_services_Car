/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.car.apitest;

import static android.car.drivingstate.CarDrivingStateEvent.DRIVING_STATE_IDLING;
import static android.car.drivingstate.CarDrivingStateEvent.DRIVING_STATE_MOVING;
import static android.car.drivingstate.CarDrivingStateEvent.DRIVING_STATE_PARKED;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_BASELINE;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED;
import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO;
import static android.car.drivingstate.CarUxRestrictionsConfiguration.Builder.SpeedRange.MAX_SPEED;

import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsConfiguration;
import android.support.test.filters.SmallTest;
import android.util.JsonReader;
import android.util.JsonWriter;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * Unit test for UXR config and its subclasses.
 */
@SmallTest
public class CarUxRestrictionsConfigurationTest extends TestCase {

    // This test verifies the expected way to build config would succeed.
    public void testConstruction() {
        new CarUxRestrictionsConfiguration.Builder().build();

        new CarUxRestrictionsConfiguration.Builder()
                .setMaxStringLength(1)
                .build();

        new CarUxRestrictionsConfiguration.Builder()
                .setUxRestrictions(DRIVING_STATE_PARKED, false, UX_RESTRICTIONS_BASELINE)
                .build();

        new CarUxRestrictionsConfiguration.Builder()
                .setUxRestrictions(DRIVING_STATE_MOVING, true, UX_RESTRICTIONS_FULLY_RESTRICTED)
                .build();

        new CarUxRestrictionsConfiguration.Builder()
                .setUxRestrictions(DRIVING_STATE_MOVING,
                        new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, MAX_SPEED),
                        true, UX_RESTRICTIONS_FULLY_RESTRICTED)
                .build();

        new CarUxRestrictionsConfiguration.Builder()
                .setUxRestrictions(DRIVING_STATE_MOVING,
                        new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 1f),
                        true, UX_RESTRICTIONS_FULLY_RESTRICTED)
                .setUxRestrictions(DRIVING_STATE_MOVING,
                        new CarUxRestrictionsConfiguration.Builder.SpeedRange(1f, MAX_SPEED),
                        true, UX_RESTRICTIONS_FULLY_RESTRICTED)
                .build();
    }

    public void testUnspecifiedDrivingStateUsesDefaultRestriction() {
        CarUxRestrictionsConfiguration config =
                new CarUxRestrictionsConfiguration.Builder().build();

        CarUxRestrictions parkedRestrictions = config.getUxRestrictions(DRIVING_STATE_PARKED, 0f);
        assertTrue(parkedRestrictions.isRequiresDistractionOptimization());
        assertEquals(parkedRestrictions.getActiveRestrictions(), UX_RESTRICTIONS_FULLY_RESTRICTED);

        CarUxRestrictions movingRestrictions = config.getUxRestrictions(DRIVING_STATE_MOVING, 1f);
        assertTrue(movingRestrictions.isRequiresDistractionOptimization());
        assertEquals(movingRestrictions.getActiveRestrictions(), UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    public void testBuilderValidation_MultipleSpeedRange_NonZeroStart() {
        CarUxRestrictionsConfiguration.Builder builder =
                new CarUxRestrictionsConfiguration.Builder();
        builder.setUxRestrictions(DRIVING_STATE_MOVING,
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(1, 2),
                true, UX_RESTRICTIONS_FULLY_RESTRICTED);
        builder.setUxRestrictions(DRIVING_STATE_MOVING,
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(2, MAX_SPEED),
                true, UX_RESTRICTIONS_FULLY_RESTRICTED);

        try {
            builder.build();
            fail();
        } catch (IllegalStateException e) {
            // Expected exception.
        }
    }

    public void testBuilderValidation_SpeedRange_NonZeroStart() {
        CarUxRestrictionsConfiguration.Builder builder =
                new CarUxRestrictionsConfiguration.Builder();
        builder.setUxRestrictions(DRIVING_STATE_MOVING,
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(1, MAX_SPEED),
                true, UX_RESTRICTIONS_FULLY_RESTRICTED);

        try {
            builder.build();
            fail();
        } catch (IllegalStateException e) {
            // Expected exception.
        }
    }

    public void testBuilderValidation_SpeedRange_Overlap() {
        CarUxRestrictionsConfiguration.Builder builder =
                new CarUxRestrictionsConfiguration.Builder();
        builder.setUxRestrictions(DRIVING_STATE_MOVING,
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(0, 5), true,
                UX_RESTRICTIONS_FULLY_RESTRICTED);
        builder.setUxRestrictions(DRIVING_STATE_MOVING,
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(4), true,
                UX_RESTRICTIONS_FULLY_RESTRICTED);

        try {
            builder.build();
            fail();
        } catch (IllegalStateException e) {
            // Expected exception.
        }
    }

    public void testBuilderValidation_SpeedRange_Gap() {
        CarUxRestrictionsConfiguration.Builder builder =
                new CarUxRestrictionsConfiguration.Builder();
        builder.setUxRestrictions(DRIVING_STATE_MOVING,
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(0, 5), true,
                UX_RESTRICTIONS_FULLY_RESTRICTED);
        builder.setUxRestrictions(DRIVING_STATE_MOVING,
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(8), true,
                UX_RESTRICTIONS_FULLY_RESTRICTED);

        try {
            builder.build();
            fail();
        } catch (IllegalStateException e) {
            // Expected exception.
        }
    }

    public void testBuilderValidation_NonMovingStateCannotUseSpeedRange() {
        CarUxRestrictionsConfiguration.Builder builder =
                new CarUxRestrictionsConfiguration.Builder();
        try {
            builder.setUxRestrictions(DRIVING_STATE_PARKED,
                    new CarUxRestrictionsConfiguration.Builder.SpeedRange(0, 5), true,
                    UX_RESTRICTIONS_FULLY_RESTRICTED);
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    public void testSpeedRange_Construction() {
        new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f);
        new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 1f);
        new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, MAX_SPEED);
    }

    public void testSpeedRange_NegativeMax() {
        try {
            new CarUxRestrictionsConfiguration.Builder.SpeedRange(2f, -1f);
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    public void testSpeedRange_MinGreaterThanMax() {
        try {
            new CarUxRestrictionsConfiguration.Builder.SpeedRange(5f, 2f);
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    public void testSpeedRangeComparison_DifferentMin() {
        CarUxRestrictionsConfiguration.Builder.SpeedRange s1 =
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(1f);
        CarUxRestrictionsConfiguration.Builder.SpeedRange s2 =
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(2f);
        assertTrue(s1.compareTo(s2) < 0);
        assertTrue(s2.compareTo(s1) > 0);
    }

    public void testSpeedRangeComparison_SameMin() {
        CarUxRestrictionsConfiguration.Builder.SpeedRange s1 =
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(1f);
        CarUxRestrictionsConfiguration.Builder.SpeedRange s2 =
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(1f);
        assertTrue(s1.compareTo(s2) == 0);
    }

    public void testSpeedRangeComparison_SameMinDifferentMax() {
        CarUxRestrictionsConfiguration.Builder.SpeedRange s1 =
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 1f);
        CarUxRestrictionsConfiguration.Builder.SpeedRange s2 =
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 2f);
        assertTrue(s1.compareTo(s2) < 0);
        assertTrue(s2.compareTo(s1) > 0);
    }

    public void testSpeedRangeComparison_MaxSpeed() {
        CarUxRestrictionsConfiguration.Builder.SpeedRange s1 =
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 1f);
        CarUxRestrictionsConfiguration.Builder.SpeedRange s2 =
                new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f);
        assertTrue(s1.compareTo(s2) < 0);
        assertTrue(s2.compareTo(s1) > 0);
    }

    public void testSpeedRangeEquals() {
        CarUxRestrictionsConfiguration.Builder.SpeedRange s1, s2;

        s1 = new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f);
        assertTrue(s1.equals(s1));

        s1 = new CarUxRestrictionsConfiguration.Builder.SpeedRange(1f);
        s2 = new CarUxRestrictionsConfiguration.Builder.SpeedRange(1f);
        assertTrue(s1.compareTo(s2) == 0);
        assertTrue(s1.equals(s2));

        s1 = new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 1f);
        s2 = new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 1f);
        assertTrue(s1.equals(s2));

        s1 = new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, MAX_SPEED);
        s2 = new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, MAX_SPEED);
        assertTrue(s1.equals(s2));

        s1 = new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f);
        s2 = new CarUxRestrictionsConfiguration.Builder.SpeedRange(1f);
        assertFalse(s1.equals(s2));

        s1 = new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 1f);
        s2 = new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 2f);
        assertFalse(s1.equals(s2));
    }

    // TODO: add more tests that each verifies setting one filed in builder can be
    // successfully retrieved out of saved json.
    public void testJsonSerialization_DefaultConstructor() {
        CarUxRestrictionsConfiguration config =
                new CarUxRestrictionsConfiguration.Builder().build();

        verifyConfigThroughJsonSerialization(config);
    }

    public void testJsonSerialization_RestrictionParameters() {
        CarUxRestrictionsConfiguration config = new CarUxRestrictionsConfiguration.Builder()
                .setMaxStringLength(1)
                .setMaxCumulativeContentItems(1)
                .setMaxContentDepth(1)
                .build();

        verifyConfigThroughJsonSerialization(config);
    }

    public void testJsonSerialization_NonMovingStateRestrictions() {
        CarUxRestrictionsConfiguration config = new CarUxRestrictionsConfiguration.Builder()
                .setUxRestrictions(DRIVING_STATE_PARKED, false, UX_RESTRICTIONS_BASELINE)
                .build();

        verifyConfigThroughJsonSerialization(config);
    }

    public void testJsonSerialization_MovingStateNoSpeedRange() {
        CarUxRestrictionsConfiguration config = new CarUxRestrictionsConfiguration.Builder()
                .setUxRestrictions(DRIVING_STATE_MOVING, true, UX_RESTRICTIONS_FULLY_RESTRICTED)
                .build();

        verifyConfigThroughJsonSerialization(config);
    }

    public void testJsonSerialization_MovingStateWithSpeedRange() {
        CarUxRestrictionsConfiguration config = new CarUxRestrictionsConfiguration.Builder()
                .setUxRestrictions(DRIVING_STATE_MOVING,
                        new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 5f),
                        true, UX_RESTRICTIONS_FULLY_RESTRICTED)
                .setUxRestrictions(DRIVING_STATE_MOVING,
                        new CarUxRestrictionsConfiguration.Builder.SpeedRange(5f, MAX_SPEED),
                        true, UX_RESTRICTIONS_FULLY_RESTRICTED)
                .build();

        verifyConfigThroughJsonSerialization(config);
    }

    public void testDump() {
        CarUxRestrictionsConfiguration[] configs = new CarUxRestrictionsConfiguration[] {
                // Driving state with no speed range
                new CarUxRestrictionsConfiguration.Builder()
                        .setUxRestrictions(DRIVING_STATE_PARKED, false, UX_RESTRICTIONS_BASELINE)
                        .setUxRestrictions(DRIVING_STATE_IDLING, true, UX_RESTRICTIONS_NO_VIDEO)
                        .setUxRestrictions(DRIVING_STATE_MOVING, true, UX_RESTRICTIONS_NO_VIDEO)
                        .build(),
                // Parameters
                new CarUxRestrictionsConfiguration.Builder()
                        .setMaxStringLength(1)
                        .setMaxContentDepth(1)
                        .setMaxCumulativeContentItems(1)
                        .build(),
                // Driving state with single speed range
                new CarUxRestrictionsConfiguration.Builder()
                        .setUxRestrictions(DRIVING_STATE_MOVING,
                                new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f),
                                true, UX_RESTRICTIONS_NO_VIDEO)
                        .build(),
                // Driving state with multiple speed ranges
                new CarUxRestrictionsConfiguration.Builder()
                        .setUxRestrictions(DRIVING_STATE_MOVING,
                                new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 1f),
                                true, UX_RESTRICTIONS_NO_VIDEO)
                        .setUxRestrictions(DRIVING_STATE_MOVING,
                                new CarUxRestrictionsConfiguration.Builder.SpeedRange(1f),
                                true, UX_RESTRICTIONS_NO_VIDEO)
                        .build(),
        };

        for (CarUxRestrictionsConfiguration config : configs) {
            config.dump(new PrintWriter(new ByteArrayOutputStream()));
        }
    }

    public void testDumpContainsNecessaryInfo() {

        CarUxRestrictionsConfiguration config = new CarUxRestrictionsConfiguration.Builder()
                .setUxRestrictions(DRIVING_STATE_MOVING,
                        new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 1f),
                        true, UX_RESTRICTIONS_NO_VIDEO)
                .setUxRestrictions(DRIVING_STATE_MOVING,
                        new CarUxRestrictionsConfiguration.Builder.SpeedRange(1f),
                        true, UX_RESTRICTIONS_NO_VIDEO)
                .build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintWriter writer = new PrintWriter(output)) {
            config.dump(writer);
        }

        String dump = new String(output.toByteArray());
        assertTrue(dump.contains("Max String length"));
        assertTrue(dump.contains("Max Cumulative Content Items"));
        assertTrue(dump.contains("Max Content depth"));
        assertTrue(dump.contains("State:moving"));
        assertTrue(dump.contains("Speed Range"));
        assertTrue(dump.contains("Requires DO?"));
        assertTrue(dump.contains("Restrictions"));
    }

    private void verifyConfigThroughJsonSerialization(CarUxRestrictionsConfiguration config) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonWriter writer = new JsonWriter(new OutputStreamWriter(out))) {
            config.writeJson(writer);
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        try (JsonReader reader = new JsonReader(new InputStreamReader(in))) {
            CarUxRestrictionsConfiguration deserialized = CarUxRestrictionsConfiguration.readJson(
                    reader);
            assertTrue(config.equals(deserialized));
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }
}
