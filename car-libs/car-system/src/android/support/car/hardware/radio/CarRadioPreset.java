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

package android.support.car.hardware.radio;

import android.os.Parcel;
import android.os.Parcelable;
import android.support.car.annotation.VersionDef;
import android.support.car.os.ExtendableParcelable;

/**
 * CarPreset object corresponds to a preset that is stored on the car's Radio unit.
 * {@CompatibilityApi}
 */
public class CarRadioPreset extends ExtendableParcelable {
    private static final int VERSION = 1;

    /*
     * Preset number at which this preset is stored.
     *
     * The value is 1 index based.
     */
    @VersionDef(version = 1)
    private final int mPresetNumber;
    /**
     * Radio band this preset belongs to.
     * See {@link RadioManager.BAND_FM}, {@link RadioManager.BAND_AM} etc.
     */
    @VersionDef(version = 1)
    private final int mBand;
    /**
     * Channel number.
     */
    @VersionDef(version = 1)
    private final int mChannel;
    /**
     * Sub channel number.
     */
    @VersionDef(version = 1)
    private final int mSubChannel;

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        int startingPosition = writeHeader(out);
        out.writeInt(mPresetNumber);
        out.writeInt(mBand);
        out.writeInt(mChannel);
        out.writeInt(mSubChannel);
        completeWriting(out, startingPosition);
    }

    public static final Parcelable.Creator<CarRadioPreset> CREATOR
            = new Parcelable.Creator<CarRadioPreset>() {
        public CarRadioPreset createFromParcel(Parcel in) {
            return new CarRadioPreset(in);
        }

        public CarRadioPreset[] newArray(int size) {
            return new CarRadioPreset[size];
        }
    };

    private CarRadioPreset(Parcel in) {
        super(in, VERSION);
        int lastPosition = readHeader(in);
        mPresetNumber = in.readInt();
        mBand = in.readInt();
        mChannel = in.readInt();
        mSubChannel = in.readInt();
        completeReading(in, lastPosition);
    }

    public CarRadioPreset(int presetNumber, int bandType, int channel, int subChannel) {
        super(VERSION);
        mPresetNumber = presetNumber;
        mBand = bandType;
        mChannel = channel;
        mSubChannel = subChannel;
    }

    // Getters.
    public int getPresetNumber() { return mPresetNumber; }

    public int getBand() { return mBand; }

    public int getChannel() { return mChannel; }

    public int getSubChannel() { return mSubChannel; }

    // Printer.
    public String toString() {
        return "Preset Number: " + mPresetNumber + "\n" +
            "Band: " + mBand + "\n" +
            "Channel: " + mChannel + "\n" +
            "Sub channel: " + mSubChannel;
    }

    // Comparator.
    public boolean equals(Object o) {
        CarRadioPreset that = (CarRadioPreset) o;

        return that.getPresetNumber() == mPresetNumber &&
            that.getBand() == mBand &&
            that.getChannel() == mChannel &&
            that.getSubChannel() == mSubChannel;

    }
}
