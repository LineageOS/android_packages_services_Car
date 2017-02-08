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

package android.car.vms;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.nio.charset.Charset;

/**
 * Parcelable wrapper of the VMS property.
 *
 * @hide
 */
@SystemApi
public class VmsProperty implements Parcelable {
    private final static Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    // TODO(antoniocortes): change type to actual VMS property once defined.
    private final String mValue;

    public VmsProperty(String value) {
        mValue = value;
    }

    @SuppressWarnings("unchecked")
    public VmsProperty(Parcel in) {
        byte[] bytes = in.readBlob();
        mValue = new String(bytes, DEFAULT_CHARSET);
    }

    public static final Creator<VmsProperty> CREATOR = new Creator<VmsProperty>() {
        @Override
        public VmsProperty createFromParcel(Parcel in) {
            return new VmsProperty(in);
        }

        @Override
        public VmsProperty[] newArray(int size) {
            return new VmsProperty[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBlob(mValue.getBytes(DEFAULT_CHARSET));
    }

    public String getValue() {
        return mValue;
    }

    @Override
    public String toString() {
        return "VmsProperty{" +
                "mValue=" + mValue +
                '}';
    }
}
