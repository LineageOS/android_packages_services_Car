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
public class CarUiInfo implements Parcelable {

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        // TODO Auto-generated method stub
    }

    public static final Parcelable.Creator<CarUiInfo> CREATOR
            = new Parcelable.Creator<CarUiInfo>() {
        public CarUiInfo createFromParcel(Parcel in) {
            return new CarUiInfo(in);
        }

        public CarUiInfo[] newArray(int size) {
            return new CarUiInfo[size];
        }
    };

    private CarUiInfo(Parcel in) {
        //TODO
    }

}
