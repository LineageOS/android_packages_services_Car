/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.media.AudioManager;
import android.media.AudioPatch;
import android.media.AudioPort;
import android.os.Parcel;
import android.os.Parcelable;



/**
 * A class to encapsulate the handle for a system level audio patch.  This is used
 * to provide a "safe" way for permitted applications to route automotive audio sources
 * outside of android.
 */
public final class CarAudioPatchHandle implements Parcelable {

    // This is enough information to uniquely identify a patch to the system
    private final int    mHandleId;
    private final String mSourcePortName;
    private final String mSinkPortName;


    /**
     * Construct a audio patch handle container given the system level handle
     * NOTE: Assumes (as it true today), that there is exactly one element in the source
     * and sink arrays.
     */
    public CarAudioPatchHandle(AudioPatch patch) {
        AudioPort sourcePort = patch.sources()[0].port();
        AudioPort sinkPort   = patch.sinks()[0].port();

        mHandleId       = patch.id();
        mSourcePortName = sourcePort.name();
        mSinkPortName   = sinkPort.name();
    }


    /**
     * @hide
     * Returns true if this instance matches the provided AudioPatch object.
     * This is intended only for use by the CarAudioManager implementation when
     * communicating with the AudioManager API
     */
    public boolean represents(AudioPatch patch) {
        if (patch.id() != mHandleId)                                    return false;
        if (patch.sources().length != 1)                                return false;
        if (!patch.sources()[0].port().name().equals(mSourcePortName))  return false;
        if (patch.sinks().length != 1)                                  return false;
        if (!patch.sinks()[0].port().name().equals(mSinkPortName))      return false;
        return true;
    }


    @Override
    public String toString() {
        StringBuilder statement = new StringBuilder();
        statement.append("Patch (");
        statement.append(mSourcePortName);
        statement.append("=>");
        statement.append(mSinkPortName);
        statement.append(")");
        return statement.toString();
    }


    /**
     * Given a parcel, populate our data members
     */
    private CarAudioPatchHandle(Parcel in) {
        mHandleId       = in.readInt();
        mSourcePortName = in.readString();
        mSinkPortName   = in.readString();
    }


    /**
     * Serialize our internal data to a parcel
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mHandleId);
        out.writeString(mSourcePortName);
        out.writeString(mSinkPortName);
    }


    public static final Parcelable.Creator<CarAudioPatchHandle> CREATOR
            = new Parcelable.Creator<CarAudioPatchHandle>() {
        public CarAudioPatchHandle createFromParcel(Parcel in) {
            return new CarAudioPatchHandle(in);
        }

        public CarAudioPatchHandle[] newArray(int size) {
            return new CarAudioPatchHandle[size];
        }
    };


    @Override
    public int describeContents() {
        return 0;
    }
}
