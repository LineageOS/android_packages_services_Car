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

package android.car.media;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * A class encapsulates a volume group in car.
 *
 * Volume in a car is controlled by group. A group holds one or more car audio contexts.
 * Call {@link CarAudioManager#getVolumeGroups()} to get a list of
 * available {@link CarVolumeGroup} supported in a car.
 */
public final class CarVolumeGroup implements Parcelable {

    private final String mTitle;
    private final int[] mContexts;

    public CarVolumeGroup(String title, @NonNull int[] contexts) {
        mTitle = title;
        mContexts = contexts;
    }

    public String getTitle() {
        return mTitle;
    }

    public int[] getContexts() {
        return mContexts;
    }

    /**
     * Given a parcel, populate our data members
     */
    private CarVolumeGroup(Parcel in) {
        mTitle = in.readString();
        final int numberContexts = in.readInt();
        mContexts = new int[numberContexts];
        in.readIntArray(mContexts);
    }

    @Override
    public String toString() {
        return mTitle + " contexts: " + Arrays.toString(mContexts);
    }

    /**
     * Serialize our internal data to a parcel
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mTitle);
        out.writeInt(mContexts.length);
        out.writeIntArray(mContexts);
    }

    public static final Parcelable.Creator<CarVolumeGroup> CREATOR
            = new Parcelable.Creator<CarVolumeGroup>() {
        public CarVolumeGroup createFromParcel(Parcel in) {
            return new CarVolumeGroup(in);
        }

        public CarVolumeGroup[] newArray(int size) {
            return new CarVolumeGroup[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }
}