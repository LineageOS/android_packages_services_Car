/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_1.Obd2FloatSensorIndex;
import android.hardware.automotive.vehicle.V2_1.Obd2IntegerSensorIndex;
import android.util.JsonReader;
import android.util.SparseArray;
import java.io.IOException;
import java.util.BitSet;
import java.util.Iterator;

/**
 * A builder class for a VehiclePropValue that encapsulates a diagnostic event. This is the Java
 * equivalent of Obd2SensorStore.cpp in the native layer.
 *
 * @hide
 */
public class DiagnosticEventBuilder {
    /**
     * An array-like container that knows to return a default value for any unwritten-to index.
     *
     * @param <T> the element type
     */
    class DefaultedArray<T> implements Iterable<T> {
        private final SparseArray<T> mElements = new SparseArray<>();
        private final int mSize;
        private final T mDefaultValue;

        DefaultedArray(int size, T defaultValue) {
            mSize = size;
            mDefaultValue = defaultValue;
        }

        private int checkIndex(int index) {
            if (index < 0 || index >= mSize)
                throw new IndexOutOfBoundsException(
                        String.format("Index: %d, Size: %d", index, mSize));
            return index;
        }

        DefaultedArray<T> set(int index, T element) {
            checkIndex(index);
            mElements.put(index, element);
            return this;
        }

        T get(int index) {
            checkIndex(index);
            return mElements.get(index, mDefaultValue);
        }

        int size() {
            return mSize;
        }

        @Override
        public Iterator<T> iterator() {
            return new Iterator<T>() {
                private int mIndex = 0;

                @Override
                public boolean hasNext() {
                    return (mIndex >= 0) && (mIndex < mSize);
                }

                @Override
                public T next() {
                    int index = mIndex++;
                    return get(index);
                }
            };
        }
    }

    private final int mPropertyId;
    private final int mNumIntSensors;
    private final DefaultedArray<Integer> mIntValues;
    private final DefaultedArray<Float> mFloatValues;
    private final BitSet mBitmask;
    private String mDtc = null;

    public DiagnosticEventBuilder(VehiclePropConfig propConfig) {
        this(propConfig.prop, propConfig.configArray.get(0), propConfig.configArray.get(1));
    }

    public DiagnosticEventBuilder(int propertyId) {
        this(propertyId, 0, 0);
    }

    public DiagnosticEventBuilder(
            int propertyId, int numVendorIntSensors, int numVendorFloatSensors) {
        mPropertyId = propertyId;
        mNumIntSensors = Obd2IntegerSensorIndex.LAST_SYSTEM_INDEX + 1 + numVendorIntSensors;
        final int numFloatSensors =
                Obd2FloatSensorIndex.LAST_SYSTEM_INDEX + 1 + numVendorFloatSensors;
        mBitmask = new BitSet(mNumIntSensors + numFloatSensors);
        mIntValues = new DefaultedArray<>(mNumIntSensors, 0);
        mFloatValues = new DefaultedArray<>(numFloatSensors, 0.0f);
    }

    public DiagnosticEventBuilder addIntSensor(int index, int value) {
        mIntValues.set(index, value);
        mBitmask.set(index);
        return this;
    }

    public DiagnosticEventBuilder addFloatSensor(int index, float value) {
        mFloatValues.set(index, value);
        mBitmask.set(mNumIntSensors + index);
        return this;
    }

    public DiagnosticEventBuilder setDTC(String dtc) {
        mDtc = dtc;
        return this;
    }

    public VehiclePropValue build() {
        return build(0);
    }

    public VehiclePropValue build(long timestamp) {
        VehiclePropValueBuilder propValueBuilder = VehiclePropValueBuilder.newBuilder(mPropertyId);
        if (0 == timestamp) {
            propValueBuilder.setTimestamp();
        } else {
            propValueBuilder.setTimestamp(timestamp);
        }
        mIntValues.forEach(propValueBuilder::addIntValue);
        mFloatValues.forEach(propValueBuilder::addFloatValue);
        return propValueBuilder.addByteValue(mBitmask.toByteArray()).setStringValue(mDtc).build();
    }

    private void readIntValues(JsonReader jsonReader) throws IOException {
        while (jsonReader.hasNext()) {
            int id = 0;
            int value = 0;
            jsonReader.beginObject();
            while (jsonReader.hasNext()) {
                String name = jsonReader.nextName();
                if (name.equals("id")) id = jsonReader.nextInt();
                else if (name.equals("value")) value = jsonReader.nextInt();
            }
            jsonReader.endObject();
            addIntSensor(id, value);
        }
    }

    private void readFloatValues(JsonReader jsonReader) throws IOException {
        while (jsonReader.hasNext()) {
            int id = 0;
            float value = 0.0f;
            jsonReader.beginObject();
            while (jsonReader.hasNext()) {
                String name = jsonReader.nextName();
                if (name.equals("id")) id = jsonReader.nextInt();
                else if (name.equals("value")) value = (float) jsonReader.nextDouble();
            }
            jsonReader.endObject();
            addFloatSensor(id, value);
        }
    }

    public VehiclePropValue build(JsonReader jsonReader) throws IOException {
        jsonReader.beginObject();
        long timestamp = 0;
        while (jsonReader.hasNext()) {
            String name = jsonReader.nextName();
            if (name.equals("timestamp")) {
                timestamp = jsonReader.nextLong();
            } else if (name.equals("intValues")) {
                jsonReader.beginArray();
                readIntValues(jsonReader);
                jsonReader.endArray();
            } else if (name.equals("floatValues")) {
                jsonReader.beginArray();
                readFloatValues(jsonReader);
                jsonReader.endArray();
            } else if (name.equals("stringValue")) {
                setDTC(jsonReader.nextString());
            }
        }
        jsonReader.endObject();
        return build(timestamp);
    }
}
