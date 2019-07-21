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

import android.car.FuelType;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
public final class FuelTypeTest extends AndroidTestCase {
    public void testMatchWithVehicleHal() {
        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_UNKNOWN,
                FuelType.UNKNOWN);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_UNLEADED,
                FuelType.UNLEADED);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_LEADED,
                FuelType.LEADED);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_DIESEL_1,
                FuelType.DIESEL_1);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_DIESEL_2,
                FuelType.DIESEL_2);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_BIODIESEL,
                FuelType.BIODIESEL);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_E85,
                FuelType.E85);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_LPG,
                FuelType.LPG);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_CNG,
                FuelType.CNG);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_LNG,
                FuelType.LNG);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_ELECTRIC,
                FuelType.ELECTRIC);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_HYDROGEN,
                FuelType.HYDROGEN);

        assertEquals(android.hardware.automotive.vehicle.V2_0.FuelType.FUEL_TYPE_OTHER,
                FuelType.OTHER);
    }
}
