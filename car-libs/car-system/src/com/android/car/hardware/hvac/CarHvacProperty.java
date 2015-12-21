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

package com.android.car.hardware.hvac;

import android.os.Parcel;
import android.os.Parcelable;
import android.support.car.annotation.VersionDef;
import android.support.car.os.ExtendableParcelable;

/**
 * CarHvacProperty object corresponds to a property of the car's HVAC system
 * @hide
 */
public class CarHvacProperty extends ExtendableParcelable {
    private static final int VERSION = 1;

    @VersionDef(version = 1)
    private final int   mPropertyId;
    @VersionDef(version = 1)
    private final int   mType;
    @VersionDef(version = 1)
    private int         mZone;
    @VersionDef(version = 1)
    private final float mFloatMax;
    @VersionDef(version = 1)
    private final float mFloatMin;
    @VersionDef(version = 1)
    private float       mFloatValue;
    @VersionDef(version = 1)
    private final int   mIntMax;
    @VersionDef(version = 1)
    private final int   mIntMin;
    @VersionDef(version = 1)
    private int         mIntValue;

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        int startingPosition = writeHeader(out);
        out.writeInt(mPropertyId);
        out.writeInt(mType);
        out.writeInt(mZone);
        out.writeFloat(mFloatMax);
        out.writeFloat(mFloatMin);
        out.writeFloat(mFloatValue);
        out.writeInt(mIntMax);
        out.writeInt(mIntMin);
        out.writeInt(mIntValue);
        completeWriting(out, startingPosition);
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
        super(in, VERSION);
        int lastPosition = readHeader(in);
        mPropertyId = in.readInt();
        mType       = in.readInt();
        mZone       = in.readInt();
        mFloatMax   = in.readFloat();
        mFloatMin   = in.readFloat();
        mFloatValue = in.readFloat();
        mIntMax     = in.readInt();
        mIntMin     = in.readInt();
        mIntValue   = in.readInt();
        completeReading(in, lastPosition);
    }

    /**
     * Copy constructor
     * @param that
     */
    public CarHvacProperty(CarHvacProperty that) {
        super(VERSION);
        mPropertyId = that.getPropertyId();
        mType       = that.getType();
        mZone       = that.getZone();
        mFloatMax   = that.getFloatMax();
        mFloatMin   = that.getFloatMin();
        mFloatValue = that.getFloatValue();
        mIntMax     = that.getIntMax();
        mIntMin     = that.getIntMin();
        mIntValue   = that.getIntValue();
    }
    /**
     * Constructor for a boolean property
     *
     * @param propertyId
     * @param zone
     * @param value
     */
    public CarHvacProperty(int propertyId, int zone, boolean value) {
        super(VERSION);
        mPropertyId = propertyId;
        mType       = CarHvacManager.PROPERTY_TYPE_BOOLEAN;
        mZone       = zone;
        mFloatMax   = 0;
        mFloatMin   = 0;
        mFloatValue = 0;
        mIntMax     = 1;
        mIntMin     = 0;
        if (value == true) {
            mIntValue = 1;
        } else {
            mIntValue = 0;
        }
    }


    /**
     * Constructor for a float property
     * @param propertyId
     * @param zone
     * @param min
     * @param max
     * @param value
     */
    public CarHvacProperty(int propertyId, int zone, float min, float max, float value) {
        super(VERSION);
        mPropertyId = propertyId;
        mType       = CarHvacManager.PROPERTY_TYPE_FLOAT;
        mZone       = zone;
        mFloatMax   = min;
        mFloatMin   = max;
        mFloatValue = value;
        mIntMax     = 0;
        mIntMin     = 0;
        mIntValue   = 0;
    }

    /**
     * Constructor for an integer property
     * @param propertyId
     * @param zone
     * @param min
     * @param max
     * @param value
     */
    public CarHvacProperty(int propertyId, int zone, int min, int max, int value) {
        super(VERSION);
        mPropertyId = propertyId;
        mType       = CarHvacManager.PROPERTY_TYPE_INT;
        mZone       = zone;
        mFloatMax   = 0;
        mFloatMin   = 0;
        mFloatValue = 0;
        mIntMax     = min;
        mIntMin     = max;
        mIntValue   = value;
    }

    // Getters.
    public int   getPropertyId() { return mPropertyId; }
    public int   getType()       { return mType; }
    public int   getZone()       { return mZone; }
    public float getFloatMax()   { return mFloatMax; }
    public float getFloatMin()   { return mFloatMin; }
    public float getFloatValue() { return mFloatValue; }
    public int   getIntMax()     { return mIntMax; }
    public int   getIntMin()     { return mIntMin; }
    public int   getIntValue()   { return mIntValue; }

    // Setters.
    public void setBooleanValue(boolean value) {
        if (mType != CarHvacManager.PROPERTY_TYPE_BOOLEAN) {
            throw new IllegalArgumentException();
        }
        if (value == true) {
            mIntValue = 1;
        } else {
            mIntValue = 0;
        }
    }
    public void setFloatValue(float value) {
        if (mType != CarHvacManager.PROPERTY_TYPE_FLOAT) {
            throw new IllegalArgumentException();
        }
        mFloatValue = value;
    }
    public void setIntegerValue(int value) {
        if (mType != CarHvacManager.PROPERTY_TYPE_INT) {
            throw new IllegalArgumentException();
        }
        mIntValue = value;
    }
    public void setZone(int zone)   { mZone  = zone; }

    // Printer.
    public String toString() {
        String myString = "mPropertyId: "  + mPropertyId + "\n" +
                          "mType:       "  + mType       + "\n" +
                          "mZone:       "  + mZone       + "\n";
        switch (mType) {
            case CarHvacManager.PROPERTY_TYPE_BOOLEAN:
                myString += "mIntValue:   "  + mIntValue   + "\n";
                break;
            case CarHvacManager.PROPERTY_TYPE_FLOAT:
                myString += "mFloatMax:   "  + mFloatMax   + "\n" +
                            "mFloatMin:   "  + mFloatMin   + "\n" +
                            "mFloatValue: "  + mFloatValue + "\n";
                break;
            case CarHvacManager.PROPERTY_TYPE_INT:
                myString += "mIntMax:     "  + mIntMax     + "\n" +
                            "mIntMin:     "  + mIntMin     + "\n" +
                            "mIntValue:   "  + mIntValue   + "\n";
                break;
            default:
                throw new IllegalArgumentException();
        }

        return myString;
    }

    // Comparator.
    public boolean equals(Object o) {
        if (o instanceof CarHvacProperty) {
            CarHvacProperty that = (CarHvacProperty) o;

            if (that.getPropertyId() == mPropertyId &&
                    that.getType() == mType &&
                    that.getZone() == mZone) {
                switch (mZone) {
                    case CarHvacManager.PROPERTY_TYPE_BOOLEAN:
                        return that.getIntValue() == mIntValue;
                    case CarHvacManager.PROPERTY_TYPE_FLOAT:
                        return that.getFloatMax()   == mFloatMax &&
                               that.getFloatMin()   == mFloatMin &&
                               that.getFloatValue() == mFloatValue;
                    case CarHvacManager.PROPERTY_TYPE_INT:
                        return that.getIntMax()   == mIntMax &&
                               that.getIntMin()   == mIntMin &&
                               that.getIntValue() == mIntValue;
                    default:
                        throw new IllegalArgumentException();
                }
            }
        }
        return false;
    }
}
