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

package android.car.occupantconnection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.annotation.ApiRequirements;
import android.os.Parcel;

import com.android.car.internal.LargeParcelableBase;

import java.util.Arrays;
import java.util.Objects;

/**
 * A payload sent between endpoints in the car.
 *
 * @hide
 */
// TODO(b/257117236): Change it to system API once it's ready to release.
// @SystemApi
public final class Payload extends LargeParcelableBase {
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @Nullable
    public byte[] bytes;

    public Payload(@NonNull byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes cannot be null");
        this.bytes = bytes.clone();
    }

    private Payload(Parcel in) {
        super(in);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof Payload) {
            Payload other = (Payload) o;
            return Arrays.equals(bytes, other.bytes);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @NonNull
    public static final Creator<Payload> CREATOR = new Creator<>() {
        @Override
        public Payload createFromParcel(Parcel in) {
            return new Payload(in);
        }

        @Override
        public Payload[] newArray(int size) {
            return new Payload[size];
        }
    };

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @Override
    public int describeContents() {
        return 0;
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    protected void serialize(Parcel dest, int flags) {
        dest.writeByteArray(bytes);
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    protected void serializeNullPayload(@NonNull Parcel dest) {
        dest.writeByteArray(null);
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    protected void deserialize(Parcel src) {
        bytes = src.createByteArray();
    }
}
