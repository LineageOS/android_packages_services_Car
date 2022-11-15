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

package android.car.media;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.car.annotation.ApiRequirements;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * Class to encapsulate car volume group information.
 *
 * @hide
 */
@SystemApi
@ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
        minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
public final class CarVolumeGroupInfo implements Parcelable {

    private final int mZoneId;
    private final int mId;
    private final @NonNull String mName;

    /**
     * Creates volume info from parcel
     *
     * @hide
     */
    @VisibleForTesting()
    public CarVolumeGroupInfo(@NonNull Parcel in) {
        mZoneId = in.readInt();
        mId = in.readInt();
        mName = in.readString();
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @NonNull
    public static final Creator<CarVolumeGroupInfo> CREATOR = new Creator<>() {
        @Override
        @NonNull
        public CarVolumeGroupInfo createFromParcel(@NonNull Parcel in) {
            return new CarVolumeGroupInfo(in);
        }

        @Override
        @NonNull
        public CarVolumeGroupInfo[] newArray(int size) {
            return new CarVolumeGroupInfo[size];
        }
    };

    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public int describeContents() {
        return 0;
    }


    CarVolumeGroupInfo(
            int zoneId,
            int id,
            @NonNull String name) {
        this.mZoneId = zoneId;
        this.mId = id;
        this.mName = Objects.requireNonNull(name, "Volume info name can not be null");
    }

    /**
     * Returns the zone id where the volume group belongs
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public int getZoneId() {
        return mZoneId;
    }

    /**
     * Returns the volume group id
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public int getId() {
        return mId;
    }

    /**
     * Returns the volume group name
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public @NonNull String getName() {
        return mName;
    }

    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public String toString() {
        return new StringBuilder().append("CarVolumeGroupId { zone id = ")
                .append(mZoneId).append(" id =").append(mId)
                .append(", name = ").append(mName)
                .append(" }").toString();
    }

    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mZoneId);
        dest.writeInt(mId);
        dest.writeString(mName);
    }

    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_2,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof CarVolumeGroupInfo)) {
            return false;
        }

        CarVolumeGroupInfo that = (CarVolumeGroupInfo) o;

        return mZoneId == that.mZoneId && mId == that.mId && mName.equals(that.mName);
    }

    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_2,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public int hashCode() {
        return Objects.hash(mName, mZoneId, mId);
    }

    /**
     * A builder for {@link CarVolumeGroupInfo}
     */
    @SuppressWarnings("WeakerAccess")
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class Builder {

        private int mZoneId;
        private int mId;
        private @NonNull String mName;

        private long mBuilderFieldsSet = 0L;

        public Builder(
                int zoneId,
                int id,
                @NonNull String name) {
            mZoneId = zoneId;
            mId = id;
            mName = Objects.requireNonNull(name, "Volume info name can not be null");
        }

        /**
         * Set the volume group zone id
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public @NonNull Builder setZoneId(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mZoneId = value;
            return this;
        }

        /**
         * Set the volume group id
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public @NonNull Builder setId(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mId = value;
            return this;
        }

        /**
         * Sets the volume group name
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public Builder setName(@NonNull String name) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mName = Objects.requireNonNull(name, "Volume info name can not be null");
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public CarVolumeGroupInfo build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8; // Mark builder used

            return new CarVolumeGroupInfo(mZoneId, mId, mName);
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x8) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
