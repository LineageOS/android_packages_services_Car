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
package com.android.car.vehiclehal.test;

import static java.lang.Integer.toHexString;

import android.car.hardware.CarPropertyValue;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyType;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


class VhalJsonReader {

    /**
     * Name of fields presented in JSON file. All of them are required.
     */
    private static final String JSON_FIELD_PROP = "prop";
    private static final String JSON_FIELD_AREA_ID = "areaId";
    private static final String JSON_FIELD_TIMESTAMP = "timestamp";
    private static final String JSON_FIELD_VALUE = "value";

    public static List<CarPropertyValue> readFromJson(InputStream in)
            throws IOException, JSONException {
        JSONArray rawEvents = new JSONArray(readJsonString(in));
        List<CarPropertyValue> events = new ArrayList<>();
        for (int i = 0; i < rawEvents.length(); i++) {
            events.add(getEvent(rawEvents.getJSONObject(i)));
        }
        return events;
    }

    private static String readJsonString(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.lines().forEach(builder::append);
        }
        return builder.toString();
    }

    private static CarPropertyValue<?> getEvent(JSONObject rawEvent) throws JSONException {
        int prop = rawEvent.getInt(JSON_FIELD_PROP);
        int areaId = rawEvent.getInt(JSON_FIELD_AREA_ID);
        long timestamp = rawEvent.getLong(JSON_FIELD_TIMESTAMP);

        switch (prop & VehiclePropertyType.MASK) {
            case VehiclePropertyType.BOOLEAN:
                return new CarPropertyValue<>(prop, areaId, CarPropertyValue.STATUS_AVAILABLE,
                                            timestamp, rawEvent.getInt(JSON_FIELD_VALUE) != 0);
            case VehiclePropertyType.INT32:
                return new CarPropertyValue<>(prop, areaId, CarPropertyValue.STATUS_AVAILABLE,
                                            timestamp, rawEvent.getInt(JSON_FIELD_VALUE));
            case VehiclePropertyType.INT64:
                return new CarPropertyValue<>(prop, areaId, CarPropertyValue.STATUS_AVAILABLE,
                                            timestamp, rawEvent.getLong(JSON_FIELD_VALUE));
            case VehiclePropertyType.FLOAT:
                return new CarPropertyValue<>(prop, areaId,
                                              CarPropertyValue.STATUS_AVAILABLE, timestamp,
                                              (float) rawEvent.getDouble(JSON_FIELD_VALUE));
            case VehiclePropertyType.STRING:
                return new CarPropertyValue<>(prop, areaId, CarPropertyValue.STATUS_AVAILABLE,
                                            timestamp, rawEvent.getString(JSON_FIELD_VALUE));
            //TODO: Add VehiclePropertyType.MIXED type support
            default:
                throw new IllegalArgumentException("Property type 0x"
                        + toHexString(prop & VehiclePropertyType.MASK)
                        + " is not supported in the test.");
        }
    }
}
