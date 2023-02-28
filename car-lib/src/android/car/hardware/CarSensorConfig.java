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

package android.car.hardware;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.car.annotation.AddedInOrBefore;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

/**
 * A CarSensorConfig object corresponds to a single sensor type coming from the car.
 * @hide
 */
public class CarSensorConfig implements Parcelable {
    /** List of property specific mapped elements in bundle for WHEEL_TICK_DISTANCE sensor*/
    /** @hide */
    @AddedInOrBefore(majorVersion = 33)
    public static final String WHEEL_TICK_DISTANCE_SUPPORTED_WHEELS =
            "android.car.wheelTickDistanceSupportedWheels";
    /** @hide */
    @AddedInOrBefore(majorVersion = 33)
    public static final String WHEEL_TICK_DISTANCE_FRONT_LEFT_UM_PER_TICK =
            "android.car.wheelTickDistanceFrontLeftUmPerTick";
    /** @hide */
    @AddedInOrBefore(majorVersion = 33)
    public static final String WHEEL_TICK_DISTANCE_FRONT_RIGHT_UM_PER_TICK =
            "android.car.wheelTickDistanceFrontRightUmPerTick";
    /** @hide */
    @AddedInOrBefore(majorVersion = 33)
    public static final String WHEEL_TICK_DISTANCE_REAR_RIGHT_UM_PER_TICK =
            "android.car.wheelTickDistanceRearRightUmPerTick";
    /** @hide */
    @AddedInOrBefore(majorVersion = 33)
    public static final String WHEEL_TICK_DISTANCE_REAR_LEFT_UM_PER_TICK =
            "android.car.wheelTickDistanceRearLeftUmPerTick";

    /** Config data stored in Bundle */
    private final Bundle mConfig;
    private final int mType;

    /** @hide */
    public CarSensorConfig(Parcel in) {
        mType = in.readInt();
        mConfig = in.readBundle();
    }

    /** @hide */
    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    @AddedInOrBefore(majorVersion = 33)
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    @AddedInOrBefore(majorVersion = 33)
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeBundle(mConfig);
    }

    /** @hide */
    @AddedInOrBefore(majorVersion = 33)
    public static final Parcelable.Creator<CarSensorConfig> CREATOR =
            new Parcelable.Creator<CarSensorConfig>() {

            @Override
            public CarSensorConfig createFromParcel(Parcel in) {
                return new CarSensorConfig(in);
            }

            @Override
            public CarSensorConfig[] newArray(int size) {
                return new CarSensorConfig[size];
            }
        };

    /** @hide */
    public CarSensorConfig(int type, Bundle b) {
        mType = type;
        mConfig = b.deepCopy();
    }

    /** @hide */
    @AddedInOrBefore(majorVersion = 33)
    public Bundle getBundle() {
        return mConfig;
    }

    /** @hide */
    @AddedInOrBefore(majorVersion = 33)
    public int getInt(String key) {
        if (mConfig.containsKey(key)) {
            return mConfig.getInt(key);
        }
        throw new IllegalArgumentException("SensorType " + mType
            + " does not contain key: " + key);
    }

    /** @hide */
    @AddedInOrBefore(majorVersion = 33)
    public int getType() {
        return mType;
    }

    /** @hide */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName() + "[");
        sb.append("mType: " + mType);
        sb.append("mConfig: " + mConfig.toString());
        sb.append("]");
        return sb.toString();
    }
}
