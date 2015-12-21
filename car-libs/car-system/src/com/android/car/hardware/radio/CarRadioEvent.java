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

package com.android.car.hardware.radio;

import android.os.Parcel;
import android.os.Parcelable;
import android.support.car.annotation.VersionDef;
import android.support.car.os.ExtendableParcelable;

/**
 * A CarRadioEvent object corresponds to a single radio event coming from the car.
 *
 * This works in conjuction with the callbacks already defined in {@link RadioCallback.Callback}.
 */
public class CarRadioEvent extends ExtendableParcelable {
    /**
     * Event specifying that a radio preset has been changed.
     */
    public static final int RADIO_PRESET = 0;

    private static final int VERSION = 1;

    /**
     * Event type.
     */
    @VersionDef(version = 1)
    private final int mType;

    /**
     * CarRadioPreset for the event type EVENT_RADIO_PRESET.
     */
    @VersionDef(version = 1)
    private final CarRadioPreset mPreset;

    // Getters.
    public CarRadioPreset getPreset() {
        return mPreset;
    }

    public int getEventType() {
        return mType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        int startingPosition = writeHeader(dest);
        dest.writeInt(mType);
        dest.writeParcelable(mPreset, 0);
        completeWriting(dest, startingPosition);
    }

    public static final Parcelable.Creator<CarRadioEvent> CREATOR
            = new Parcelable.Creator<CarRadioEvent>() {
        public CarRadioEvent createFromParcel(Parcel in) {
            return new CarRadioEvent(in);
        }

        public CarRadioEvent[] newArray(int size) {
            return new CarRadioEvent[size];
        }
    };

    public CarRadioEvent(int type, CarRadioPreset preset) {
        super(VERSION);
        mType = type;
        mPreset = preset;
    }

    private CarRadioEvent(Parcel in) {
        super(in, VERSION);
        int lastPosition = readHeader(in);
        mType = in.readInt();
        mPreset = in.readParcelable(CarRadioPreset.class.getClassLoader());
        completeReading(in, lastPosition);
    }

    public String toString() {
        String data = "";
        switch (mType) {
          case RADIO_PRESET:
              data = mPreset.toString();
        }
        return mType + " " + data;
    }
}
