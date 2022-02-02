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

package com.android.car.vehiclehal;

import static com.android.car.vehiclehal.DiagnosticJsonReader.FRAME_TYPE_LIVE;
import static com.android.car.vehiclehal.DiagnosticJsonTestUtils.ANY_TIMESTAMP_VALUE;
import static com.android.car.vehiclehal.DiagnosticJsonTestUtils.buildEmptyVehiclePropertyValue;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.automotive.vehicle.VehiclePropValue;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.util.JsonReader;
import android.util.JsonWriter;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

public final class DiagnosticJsonTest {

    private static final int SOME_VEHICLE_PROPERTY = VehicleProperty.HVAC_POWER_ON;

    private static final String ANY_TYPE = FRAME_TYPE_LIVE;

    @Test
    public void testBuild_passingBuilder() throws IOException {
        DiagnosticJson diagnosticJson = buildEmptyDiagnosticJson();
        DiagnosticEventBuilder eventBuilder = new DiagnosticEventBuilder(SOME_VEHICLE_PROPERTY);

        VehiclePropValue actual = diagnosticJson.build(eventBuilder);

        VehiclePropValue expected = buildEmptyVehiclePropertyValue(
                SOME_VEHICLE_PROPERTY, ANY_TIMESTAMP_VALUE);
        assertThat(actual).isEqualTo(expected);
    }

    private DiagnosticJson buildEmptyDiagnosticJson() throws IOException {
        StringWriter stringWriter = new StringWriter(1024);
        JsonWriter jsonWriter = new JsonWriter(stringWriter);
        jsonWriter.beginObject()
                .name("type")
                .value(ANY_TYPE)  // Arbitrarily setting to live property type
                .name("timestamp")
                .value(ANY_TIMESTAMP_VALUE).endObject();
        JsonReader reader = new JsonReader(new StringReader(stringWriter.toString()));
        DiagnosticJson diagnosticJson = DiagnosticJson.build(reader);
        return diagnosticJson;
    }
}
