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

public final class ReceiverEndpointTokenTest {

    private static final OccupantZoneInfo OCCUPANT_ZONE =
            new OccupantZoneInfo(/* zoneId= */ 0, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
    private static final int USER_ID = 123;
    private static final String PACKAGE_NAME = "my_package_name";
    private static final String RECEIVER_ENDPOINT_ID = "test_receiver_id";

    @Test
    public void testConstructReceiverEndpointToken() {
        ReceiverEndpointToken endpointToken = new ReceiverEndpointToken(
                new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME), RECEIVER_ENDPOINT_ID);

        assertThat(endpointToken.clientToken.occupantZone).isEqualTo(OCCUPANT_ZONE);
        assertThat(endpointToken.clientToken.userId).isEqualTo(USER_ID);
        assertThat(endpointToken.clientToken.packageName).isEqualTo(PACKAGE_NAME);
        assertThat(endpointToken.receiverEndpointId).isEqualTo(RECEIVER_ENDPOINT_ID);
    }

    @Test
    public void testEquals() {
        ClientToken clientToken = new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME);
        ReceiverEndpointToken endpointToken =
                new ReceiverEndpointToken(clientToken, RECEIVER_ENDPOINT_ID);
        ReceiverEndpointToken endpointToken2 =
                new ReceiverEndpointToken(clientToken, RECEIVER_ENDPOINT_ID);

        assertThat(endpointToken).isEqualTo(endpointToken2);
        assertThat(endpointToken.hashCode()).isEqualTo(endpointToken2.hashCode());
    }

    @Test
    public void testNotEqualWithDifferentClientToken() {
        ClientToken clientToken = new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME);
        ReceiverEndpointToken endpointToken =
                new ReceiverEndpointToken(clientToken, RECEIVER_ENDPOINT_ID);
        ClientToken clientToken2 = new ClientToken(OCCUPANT_ZONE, USER_ID + 1, PACKAGE_NAME);
        ReceiverEndpointToken endpointToken2 =
                new ReceiverEndpointToken(clientToken2, RECEIVER_ENDPOINT_ID);

        assertThat(endpointToken).isNotEqualTo(endpointToken2);
        assertThat(endpointToken.hashCode()).isNotEqualTo(endpointToken2.hashCode());
    }

    @Test
    public void testNotEqualWithDifferentId() {
        ClientToken clientToken = new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME);
        ReceiverEndpointToken endpointToken =
                new ReceiverEndpointToken(clientToken, RECEIVER_ENDPOINT_ID);
        ReceiverEndpointToken endpointToken2 =
                new ReceiverEndpointToken(clientToken, RECEIVER_ENDPOINT_ID + "a");

        assertThat(endpointToken).isNotEqualTo(endpointToken2);
        assertThat(endpointToken.hashCode()).isNotEqualTo(endpointToken2.hashCode());
    }

    @Test
    public void testNullParameter_throwsException() {
        assertThrows(NullPointerException.class,
                () -> new ReceiverEndpointToken(null, RECEIVER_ENDPOINT_ID));

        ClientToken clientToken = new ClientToken(OCCUPANT_ZONE, USER_ID, PACKAGE_NAME);
        assertThrows(NullPointerException.class,
                () -> new ReceiverEndpointToken(clientToken, null));
    }
}
