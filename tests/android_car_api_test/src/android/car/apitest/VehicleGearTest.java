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
package android.car.apitest;

import android.car.VehicleGear;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
public class VehicleGearTest extends AndroidTestCase {

    public void testMatchWithVehicleHal() {
        // There's no GEAR_UNKOWN in types.hal, hardcodes 0 to match android API.
        assertEquals(0, VehicleGear.GEAR_UNKNOWN);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_NEUTRAL,
                VehicleGear.GEAR_NEUTRAL);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_REVERSE,
                VehicleGear.GEAR_REVERSE);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_PARK,
                VehicleGear.GEAR_PARK);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_DRIVE,
                VehicleGear.GEAR_DRIVE);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_1,
                VehicleGear.GEAR_FIRST);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_2,
                VehicleGear.GEAR_SECOND);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_3,
                VehicleGear.GEAR_THIRD);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_4,
                VehicleGear.GEAR_FOURTH);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_5,
                VehicleGear.GEAR_FIFTH);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_6,
                VehicleGear.GEAR_SIXTH);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_7,
                VehicleGear.GEAR_SEVENTH);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_8,
                VehicleGear.GEAR_EIGHTH);

        assertEquals(android.hardware.automotive.vehicle.V2_0.VehicleGear.GEAR_9,
                VehicleGear.GEAR_NINTH);
    }

    public void testToString() {
        assertEquals("GEAR_UNKNOWN", VehicleGear.toString(0));

        assertEquals("GEAR_NEUTRAL", VehicleGear.toString(VehicleGear.GEAR_NEUTRAL));

        assertEquals("GEAR_REVERSE", VehicleGear.toString(VehicleGear.GEAR_REVERSE));

        assertEquals("GEAR_PARK", VehicleGear.toString(VehicleGear.GEAR_PARK));

        assertEquals("GEAR_DRIVE", VehicleGear.toString(VehicleGear.GEAR_DRIVE));

        assertEquals("GEAR_FIRST", VehicleGear.toString(VehicleGear.GEAR_FIRST));

        assertEquals("GEAR_SECOND", VehicleGear.toString(VehicleGear.GEAR_SECOND));

        assertEquals("GEAR_THIRD", VehicleGear.toString(VehicleGear.GEAR_THIRD));

        assertEquals("GEAR_FOURTH", VehicleGear.toString(VehicleGear.GEAR_FOURTH));

        assertEquals("GEAR_FIFTH", VehicleGear.toString(VehicleGear.GEAR_FIFTH));

        assertEquals("GEAR_SIXTH", VehicleGear.toString(VehicleGear.GEAR_SIXTH));

        assertEquals("GEAR_SEVENTH", VehicleGear.toString(VehicleGear.GEAR_SEVENTH));

        assertEquals("GEAR_EIGHTH", VehicleGear.toString(VehicleGear.GEAR_EIGHTH));

        assertEquals("GEAR_NINTH", VehicleGear.toString(VehicleGear.GEAR_NINTH));

        assertEquals("0x3", VehicleGear.toString(3));

        assertEquals("0xc", VehicleGear.toString(12));
    }
}
