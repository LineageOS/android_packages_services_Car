/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.car.hardware.hvac;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * CarHvacProperty object corresponds to a property of the car's HVAC system
 */
@SystemApi
public class CarHvacProperty implements Parcelable {

    private final int mPropertyId;
    private final int mType;
    private int mZones;
    private final float[] mFloatMaxs;
    private final float[] mFloatMins;
    private float[] mFloatValues;
    private final int[] mIntMaxs;
    private final int[] mIntMins;
    private int[] mIntValues;

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mPropertyId);
        out.writeInt(mType);
        out.writeInt(mZones);
        out.writeFloatArray(mFloatMaxs);
        out.writeFloatArray(mFloatMins);
        out.writeFloatArray(mFloatValues);
        out.writeIntArray(mIntMaxs);
        out.writeIntArray(mIntMins);
        out.writeIntArray(mIntValues);
    }

    public static final Parcelable.Creator<CarHvacProperty> CREATOR
            = new Parcelable.Creator<CarHvacProperty>() {
        public CarHvacProperty createFromParcel(Parcel in) {
            return new CarHvacProperty(in);
        }

        public CarHvacProperty[] newArray(int size) {
            return new CarHvacProperty[size];
        }
    };

    private CarHvacProperty(Parcel in) {
        mPropertyId = in.readInt();
        mType = in.readInt();
        mZones = in.readInt();
        mFloatMaxs = in.createFloatArray();
        mFloatMins = in.createFloatArray();
        mFloatValues = in.createFloatArray();
        mIntMaxs = in.createIntArray();
        mIntMins = in.createIntArray();
        mIntValues = in.createIntArray();
    }

    /**
     * Copy constructor
     */
    public CarHvacProperty(CarHvacProperty that) {
        mPropertyId = that.getPropertyId();
        mType = that.getType();
        mZones = that.getZones();
        mFloatMaxs = that.getFloatMaxs();
        mFloatMins = that.getFloatMins();
        mFloatValues = that.getFloatValues();
        mIntMaxs = that.getIntMaxs();
        mIntMins = that.getIntMins();
        mIntValues =  that.getIntValues();
    }
    /**
     * Constructor for a boolean property
     */
    public CarHvacProperty(int propertyId, int zones, boolean value) {
        mPropertyId = propertyId;
        mType = CarHvacManager.PROPERTY_TYPE_BOOLEAN;
        mZones = zones;
        mIntMaxs = new int[] { 1 };
        mIntMins = new int[] { 0 };
        mIntValues = new int[] { value ? 1 : 0 };
        mFloatMaxs = null;
        mFloatMins = null;
    }


    /**
     * Constructor for a float property
     */
    public CarHvacProperty(int propertyId, int zones, float[] mins, float[] maxs, float value) {
        mPropertyId = propertyId;
        mType = CarHvacManager.PROPERTY_TYPE_FLOAT;
        mZones = zones;
        mFloatMaxs = maxs;
        mFloatMins = mins;
        mFloatValues = new float[] {value};
        mIntValues = null;
        mIntMaxs = null;
        mIntMins = null;
    }

    /**
     * Constructor for an integer property
     */
    public CarHvacProperty(int propertyId, int zones, int[] mins, int[] maxs, int value) {
        mPropertyId = propertyId;
        mType = CarHvacManager.PROPERTY_TYPE_INT;
        mZones = zones;
        mIntMaxs = maxs;
        mIntMins = mins;
        mIntValues = new int[] { value };
        mFloatMaxs = null;
        mFloatMins = null;
    }

    /**
     * Constructor for an integer vector property
     */
    public CarHvacProperty(int propertyId, int zones, int[] mins, int[] maxs, int[] values) {
        mPropertyId = propertyId;
        mType = CarHvacManager.PROPERTY_TYPE_INT_VECTOR;
        mZones = zones;
        mIntMaxs = maxs;
        mIntMins = mins;
        mIntValues = Arrays.copyOf(values, values.length);
        mFloatMaxs = null;
        mFloatMins = null;
    }

    /**
     * Constructor for a float vector property
     */
    public CarHvacProperty(int propertyId, int zones, float[] mins, float[] maxs, float[] values) {
        mPropertyId = propertyId;
        mType = CarHvacManager.PROPERTY_TYPE_FLOAT_VECTOR;
        mZones = zones;
        mFloatMaxs = maxs;
        mFloatMins = mins;
        mFloatValues = Arrays.copyOf(values, values.length);
        mIntMaxs = null;
        mIntMins = null;
    }

    // Getters.
    public int   getPropertyId()     { return mPropertyId; }
    public int   getType()           { return mType; }
    public int   getZones()           { return mZones; }
    public boolean getBooleanValue() { return mIntValues[0] == 1; }
    public float[] getFloatMaxs()       { return mFloatMaxs; }
    public float[] getFloatMins()       { return mFloatMins; }
    public float getFloatValue()     { return mFloatValues[0]; }
    public float[] getFloatValues()  { return mFloatValues; }
    public int[]   getIntMaxs()         { return mIntMaxs; }
    public int[]   getIntMins()         { return mIntMins; }
    public int   getIntValue()       { return mIntValues[0]; }
    public int[] getIntValues()      { return mIntValues; }

    // Setters.
    public void setBooleanValue(boolean value) {
        assertType(CarHvacManager.PROPERTY_TYPE_BOOLEAN);
        mIntValues[0] = value ? 1 : 0;
    }
    public void setFloatValue(float value) {
        assertType(CarHvacManager.PROPERTY_TYPE_FLOAT);
        mFloatValues[0] = value;
    }
    public void setIntValue(int value) {
        assertType(CarHvacManager.PROPERTY_TYPE_INT);
        mIntValues[0] = value;
    }
    public void setIntValues(int[] values) {
        assertType(CarHvacManager.PROPERTY_TYPE_INT_VECTOR);
        mIntValues = Arrays.copyOf(values, values.length);
    }
    public void setFloatValues(float[] values) {
        assertType(CarHvacManager.PROPERTY_TYPE_INT_VECTOR);
        mFloatValues = Arrays.copyOf(values, values.length);
    }
    public void setZones(int zones)   { mZones  = zones; }

    @Override
    public String toString() {
        String myString = "mPropertyId: "  + mPropertyId + "\n" +
                          "mType:       "  + mType       + "\n" +
                          "mZones:       "  + mZones       + "\n";
        switch (mType) {
            case CarHvacManager.PROPERTY_TYPE_BOOLEAN:
                myString += "mIntValue:   "  + mIntValues[0] + "\n";
                break;
            case CarHvacManager.PROPERTY_TYPE_FLOAT:
                myString += "mFloatMaxs:   "  + Arrays.toString(mFloatMaxs) + "\n" +
                            "mFloatMins:   "  + Arrays.toString(mFloatMins) + "\n" +
                            "mFloatValue: "  + mFloatValues[0] + "\n";
                break;
            case CarHvacManager.PROPERTY_TYPE_INT:
                myString += "mIntMax:     "  + Arrays.toString(mIntMaxs) + "\n" +
                            "mIntMin:     "  + Arrays.toString(mIntMins) + "\n" +
                            "mIntValue:   "  + mIntValues[0] + "\n";
                break;
            case CarHvacManager.PROPERTY_TYPE_INT_VECTOR:
                myString += "mIntMax:     "  + Arrays.toString(mIntMaxs) + "\n" +
                            "mIntMin:     "  + Arrays.toString(mIntMins) + "\n" +
                            "mIntValues:  "  + Arrays.toString(mIntValues) + "\n";
                break;
            case CarHvacManager.PROPERTY_TYPE_FLOAT_VECTOR:
                myString += "mFloatMax:     "  + Arrays.toString(mFloatMaxs) + "\n" +
                            "mFloatMin:     "  + Arrays.toString(mFloatMins) + "\n" +
                            "mFloatValues:  "  + Arrays.toString(mFloatValues) + "\n";
                break;
            default:
                throw new IllegalArgumentException();
        }

        return myString;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(mFloatMaxs);
        result = prime * result + Arrays.hashCode(mFloatMins);
        result = prime * result + Arrays.hashCode(mFloatValues);
        result = prime * result + Arrays.hashCode(mIntMaxs);
        result = prime * result + Arrays.hashCode(mIntMins);
        result = prime * result + Arrays.hashCode(mIntValues);
        result = prime * result + mPropertyId;
        result = prime * result + mType;
        result = prime * result + mZones;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        CarHvacProperty other = (CarHvacProperty) obj;
        if (!Arrays.equals(mFloatMaxs, other.mFloatMaxs))
            return false;
        if (!Arrays.equals(mFloatMins, other.mFloatMins))
            return false;
        if (!Arrays.equals(mFloatValues, other.mFloatValues))
            return false;
        if (!Arrays.equals(mIntMaxs, other.mIntMaxs))
            return false;
        if (!Arrays.equals(mIntMins, other.mIntMins))
            return false;
        if (!Arrays.equals(mIntValues, other.mIntValues))
            return false;
        if (mPropertyId != other.mPropertyId)
            return false;
        if (mType != other.mType)
            return false;
        if (mZones != other.mZones)
            return false;
        return true;
    }

    private void assertType(int expectedType) {
        if (mType != expectedType) {
            throw new IllegalArgumentException(
                    "Expected type: " + expectedType + ", actual type: " + mType);
        }
    }
}
