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

package android.car.drivingstate;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Car UX Restrictions related event.  This contains information on the set of UX restrictions
 * that is in place due to the car's driving state.
 */
public class CarUXRestrictionsEvent implements Parcelable {

    // UXRestrictions TODO: (b/69859857) - make it configurable
    /**
     * No UX Restrictions.  Vehicle Optimized apps are allowed to display non Drive Optimized
     * Activities.
     */
    public static final int UXR_NO_RESTRICTIONS = 0;
    /**
     * UX Restrictions fully in place.  Only Drive Optimized Activities that are optimized to handle
     * the UX restrictions can run.
     * TODO: (b/72155508) - Finalize what these restrictions are
     */
    public static final int UXR_FULLY_RESTRICTED = 31;

    @IntDef({UXR_NO_RESTRICTIONS,
            UXR_FULLY_RESTRICTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CarUXRestrictions {
    }

    /**
     * Time at which this UX restriction event was deduced based on the car's driving state.
     * It is the elapsed time in nanoseconds since system boot.
     */
    public final long timeStamp;

    /**
     * UX Restriction in effect.
     * TODO:  (b/72155508) If/When we add more granular UX restrictions, this will be a list of
     * restrictions.
     */
    @CarUXRestrictions
    public final int eventValue;


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(eventValue);
        dest.writeLong(timeStamp);
    }

    public static final Parcelable.Creator<CarUXRestrictionsEvent> CREATOR
            = new Parcelable.Creator<CarUXRestrictionsEvent>() {
        public CarUXRestrictionsEvent createFromParcel(Parcel in) {
            return new CarUXRestrictionsEvent(in);
        }

        public CarUXRestrictionsEvent[] newArray(int size) {
            return new CarUXRestrictionsEvent[size];
        }
    };

    public CarUXRestrictionsEvent(int value, long time) {
        eventValue = value;
        timeStamp = time;
    }

    private CarUXRestrictionsEvent(Parcel in) {
        eventValue = in.readInt();
        timeStamp = in.readLong();
    }

    @Override
    public String toString() {
        return eventValue + " " + timeStamp;
    }
}
