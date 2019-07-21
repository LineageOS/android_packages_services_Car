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

import android.car.PortLocationType;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
public final class PortLocationTypeTest extends AndroidTestCase {
    public void testMatchWithVehicleHal() {
        assertEquals(android.hardware.automotive.vehicle.V2_0.PortLocationType.UNKNOWN,
                PortLocationType.UNKNOWN);

        assertEquals(android.hardware.automotive.vehicle.V2_0.PortLocationType.FRONT_LEFT,
                PortLocationType.FRONT_LEFT);

        assertEquals(android.hardware.automotive.vehicle.V2_0.PortLocationType.FRONT_RIGHT,
                PortLocationType.FRONT_RIGHT);

        assertEquals(android.hardware.automotive.vehicle.V2_0.PortLocationType.REAR_RIGHT,
                PortLocationType.REAR_RIGHT);

        assertEquals(android.hardware.automotive.vehicle.V2_0.PortLocationType.REAR_LEFT,
                PortLocationType.REAR_LEFT);

        assertEquals(android.hardware.automotive.vehicle.V2_0.PortLocationType.FRONT,
                PortLocationType.FRONT);

        assertEquals(android.hardware.automotive.vehicle.V2_0.PortLocationType.REAR,
                PortLocationType.REAR);

    }
}
