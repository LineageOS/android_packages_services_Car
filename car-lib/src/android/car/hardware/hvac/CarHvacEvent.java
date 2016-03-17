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

@SystemApi
public class CarHvacEvent implements Parcelable {
    public static final int HVAC_EVENT_PROPERTY_CHANGE = 0;
    public static final int HVAC_EVENT_ERROR = 1;

    /**
     * EventType of this message
     */
    private final int mEventType;
    /**
     * PropertyId is defined in {@link CarHvacProperty} and refers only to HVAC properties
     */
    private final int mPropertyId;
    /**
     * Type denotes whether the property is a bool or integer.
     */
    private final int mDataType;
    /**
     * Float value of the property
     */
    private final float mFloatValue;
    /**
     * Integer value of the property
     */
    private final int mIntValue;
    /**
     * Affected zone(s) for the property.  Zone is a bitmask as defined in {@link CarHvacProperty}
     */
    private final int mZone;

    // Getters.

    /**
     * @return EventType field
     */
    public int getEventType() { return mEventType; }

    /**
     * @return Property ID of the event
     */
    public int getPropertyId() { return mPropertyId; }

    /**
     * @return Property Type (boolean or integer)
     */
    public int getPropertyType() { return mDataType; }

    /**
     * @return Float property Value
     */
    public float getFloatValue() { return mFloatValue; }

    /**
     * @return Int or bool property Value
     */
    public int getIntValue() { return mIntValue; }

    /**
     * @return Affected zone(s) for this property
     */
    public int getZone() { return mZone; }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEventType);
        dest.writeInt(mPropertyId);
        dest.writeInt(mDataType);
        dest.writeFloat(mFloatValue);
        dest.writeInt(mIntValue);
        dest.writeInt(mZone);
    }

    public static final Parcelable.Creator<CarHvacEvent> CREATOR
            = new Parcelable.Creator<CarHvacEvent>() {
        public CarHvacEvent createFromParcel(Parcel in) {
            return new CarHvacEvent(in);
        }

        public CarHvacEvent[] newArray(int size) {
            return new CarHvacEvent[size];
        }
    };

    /**
     * Constructor for boolean type
     * @param eventType
     * @param propertyId
     * @param zone
     * @param value
     */
    public CarHvacEvent(int eventType, int propertyId, int zone, boolean value) {
        mEventType  = eventType;
        mPropertyId = propertyId;
        mDataType   = CarHvacManager.PROPERTY_TYPE_BOOLEAN;
        mFloatValue = 0;
        if (value) {
            mIntValue = 1;
        } else {
            mIntValue = 0;
        }
        mZone       = zone;
    }

    /**
     * Constructor for float event
     * @param eventType
     * @param propertyId
     * @param zone
     * @param value
     */
    public CarHvacEvent(int eventType, int propertyId, int zone, float value) {
        mEventType  = eventType;
        mPropertyId = propertyId;
        mDataType   = CarHvacManager.PROPERTY_TYPE_FLOAT;
        mFloatValue = value;
        mIntValue   = 0;
        mZone       = zone;
    }

    /**
     * Constructor for integer event
     * @param eventType
     * @param propertyId
     * @param zone
     * @param value
     */
    public CarHvacEvent(int eventType, int propertyId, int zone, int value) {
        mEventType  = eventType;
        mPropertyId = propertyId;
        mDataType   = CarHvacManager.PROPERTY_TYPE_INT;
        mFloatValue = 0;
        mIntValue   = value;
        mZone       = zone;
    }

    private CarHvacEvent(Parcel in) {
        mEventType  = in.readInt();
        mPropertyId = in.readInt();
        mDataType   = in.readInt();
        mFloatValue = in.readFloat();
        mIntValue   = in.readInt();
        mZone       = in.readInt();
    }

    public String toString() {
        return "mEventType:  " + mEventType    + "\n" +
               "mPropertyId: " + mPropertyId   + "\n" +
               "mDataType:   " + mDataType     + "\n" +
               "mFloatValue: " + mFloatValue   + "\n" +
               "mIntValue:   " + mIntValue + "\n" +
               "mZone:       " + mZone         + "\n";
    }
}

