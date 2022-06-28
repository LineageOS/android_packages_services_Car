/*
 * Copyright (C) 2022 The Android Open Source Project
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
package android.car;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.annotation.NonNull;
import android.car.annotation.AddedIn;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

/**
 * Represents the API version of the {@code Car} SDK.
 */
@AddedIn(majorVersion = 33, minorVersion = 1)
public final class CarApiVersion extends ApiVersion<CarApiVersion> implements Parcelable {

    /**
     * Helper object for main version of Android 13.
     */
    @AddedIn(majorVersion = 33, minorVersion = 1)
    @NonNull
    public static final CarApiVersion TIRAMISU_0 =
            forMajorAndMinorVersions(Build.VERSION_CODES.TIRAMISU, 0);

    /**
     * Helper object for first minor upgrade of Android 13.
     */
    @AddedIn(majorVersion = 33, minorVersion = 1)
    @NonNull
    public static final CarApiVersion TIRAMISU_1 =
            forMajorAndMinorVersions(Build.VERSION_CODES.TIRAMISU, 1);


    /**
     * Helper object for main version of Android 14.
     */
    @AddedIn(majorVersion = 10000)    // TODO(b/230004170): STOPSHIP: replace by real value
    @NonNull
    public static final CarApiVersion UPSIDE_DOWN_CAKE_0 =
            forMajorAndMinorVersions(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 0);

    /**
     * Creates a new instance with the given major and minor versions.
     */
    @AddedIn(majorVersion = 33, minorVersion = 1)
    @NonNull
    public static CarApiVersion forMajorAndMinorVersions(int majorVersion, int minorVersion) {
        return new CarApiVersion(majorVersion, minorVersion);
    }

    /**
     * Creates a new instance for a major version (i.e., the minor version will be {@code 0}.
     */
    @AddedIn(majorVersion = 33, minorVersion = 1)
    @NonNull
    public static CarApiVersion forMajorVersion(int majorVersion) {
        return new CarApiVersion(majorVersion, /* minorVersion= */ 0);
    }

    private CarApiVersion(int majorVersion, int minorVersion) {
        super(majorVersion, minorVersion);
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    @AddedIn(majorVersion = 33, minorVersion = 1)
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(getMajorVersion());
        dest.writeInt(getMinorVersion());
    }

    @AddedIn(majorVersion = 33, minorVersion = 1)
    @NonNull
    public static final Parcelable.Creator<CarApiVersion> CREATOR =
            new Parcelable.Creator<CarApiVersion>() {

        @Override
        public CarApiVersion createFromParcel(Parcel source) {
            int major = source.readInt();
            int minor = source.readInt();
            return forMajorAndMinorVersions(major, minor);
        }

        @Override
        public CarApiVersion[] newArray(int size) {
            return new CarApiVersion[size];
        }
    };
}
