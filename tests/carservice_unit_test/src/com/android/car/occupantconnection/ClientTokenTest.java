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

package com.android.car.occupantconnection;

import static android.car.CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER;
import static android.car.VehicleAreaSeat.SEAT_ROW_1_LEFT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.car.CarOccupantZoneManager.OccupantZoneInfo;

import org.junit.Test;


public final class ClientTokenTest {

    private static final OccupantZoneInfo OCCUPANT_ZONE =
            new OccupantZoneInfo(/* zoneId= */ 0, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
    private static final int USER_ID = 123;
    private static final String PACKAGE_NAME = "my_package_name";


    @Test
    public void testConstructClientToken() {
        ClientToken clientToken = new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME);

        assertThat(clientToken.occupantZone).isEqualTo(OCCUPANT_ZONE);
        assertThat(clientToken.userId).isEqualTo(USER_ID);
        assertThat(clientToken.packageName).isEqualTo(PACKAGE_NAME);
    }

    @Test
    public void testEquals() {
        ClientToken clientToken = new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME);
        ClientToken clientToken2 = new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME);

        assertThat(clientToken).isEqualTo(clientToken2);
        assertThat(clientToken.hashCode()).isEqualTo(clientToken2.hashCode());
    }

    @Test
    public void testNotEqualWithDifferentZone() {
        ClientToken clientToken = new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME);
        OccupantZoneInfo zone2 =
                new OccupantZoneInfo(/* zoneId= */ 1, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        ClientToken clientToken2 = new ClientToken(zone2, USER_ID, PACKAGE_NAME);
        assertThat(clientToken).isNotEqualTo(clientToken2);
        assertThat(clientToken.hashCode()).isNotEqualTo(clientToken2.hashCode());
    }

    @Test
    public void testNotEqualWithDifferentUserId() {
        ClientToken clientToken = new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME);
        ClientToken clientToken2 = new ClientToken(OCCUPANT_ZONE, USER_ID + 1, PACKAGE_NAME);

        assertThat(clientToken).isNotEqualTo(clientToken2);
        assertThat(clientToken.hashCode()).isNotEqualTo(clientToken2.hashCode());
    }

    @Test
    public void testNotEqualWithDifferentPackageName() {
        ClientToken clientToken = new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME);
        ClientToken clientToken2 = new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME + "a");

        assertThat(clientToken).isNotEqualTo(clientToken2);
        assertThat(clientToken.hashCode()).isNotEqualTo(clientToken2.hashCode());
    }

    @Test
    public void testNullParameter_throwsException() {
        assertThrows(NullPointerException.class,
                () -> new ClientToken(/* occupantZone= */ null, USER_ID, PACKAGE_NAME));
        assertThrows(NullPointerException.class,
                () -> new ClientToken(OCCUPANT_ZONE, USER_ID, /* packageName= */ null));
    }
}
