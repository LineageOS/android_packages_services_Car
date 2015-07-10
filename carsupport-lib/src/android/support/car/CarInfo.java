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

package android.support.car;

import android.os.Parcel;
import android.os.Parcelable;

//TODO
public class CarInfo implements Parcelable {

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        // TODO Auto-generated method stub
    }

    public static final Parcelable.Creator<CarInfo> CREATOR
            = new Parcelable.Creator<CarInfo>() {
        public CarInfo createFromParcel(Parcel in) {
            return new CarInfo(in);
        }

        public CarInfo[] newArray(int size) {
            return new CarInfo[size];
        }
    };

    private CarInfo(Parcel in) {
        //TODO
    }

}
