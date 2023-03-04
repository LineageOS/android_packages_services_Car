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
import android.annotation.SystemApi;
import android.car.annotation.ApiRequirements;
import android.os.Parcel;

import com.android.car.internal.LargeParcelableBase;

import java.util.Arrays;
import java.util.Objects;

/**
 * A payload sent between client apps that have the same package name but run in different occupant
 * zones in the car.
 * <p>
 * After establishing a connection to the receiver client, the sender client can send a payload via
 * {@link CarOccupantConnectionManager#sendPayload}. The receiver service in the receiver client
 * will receive the payload via {@link AbstractReceiverService#onPayloadReceived}, then dispatch it
 * to the proper receiver endpoint(s).
 * <p>
 * The sender client can put the receiver endpoint ID in the payload so that the receiver service
 * knows which receiver endpoint(s) to dispatch the payload to.
 *
 * @hide
 */
@SystemApi
public final class Payload extends LargeParcelableBase {
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)

    @Nullable
    private byte[] mBytes;

    public Payload(@NonNull byte[] bytes) {
        super();
        Objects.requireNonNull(bytes, "bytes cannot be null");
        this.mBytes = bytes.clone();
    }

    private Payload(Parcel in) {
        super(in);
    }

    /** Returns a reference to the byte array of the payload. */
    @Nullable
    public byte[] getBytes() {
        return mBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof Payload) {
            Payload other = (Payload) o;
            return Arrays.equals(mBytes, other.mBytes);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(mBytes);
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @NonNull
    public static final Creator<Payload> CREATOR = new Creator<>() {
        /**
         * {@inheritDoc}
         */
        @Override
        public Payload createFromParcel(Parcel in) {
            return new Payload(in);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Payload[] newArray(int size) {
            return new Payload[size];
        }
    };

    /**
     * {@inheritDoc}
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @Override
    public int describeContents() {
        return 0;
    }

    /** Writes this {@link Payload} into the given {@link Parcel}. */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @Override
    public void serialize(@NonNull Parcel dest, int flags) {
        dest.writeByteArray(mBytes);
    }

    /** Writes {@code null} {@link Payload} to the given {@link Parcel}. */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @Override
    public void serializeNullPayload(@NonNull Parcel dest) {
        dest.writeByteArray(null);
    }

    /** Reads a {@link Payload} from the given {@link Parcel}. */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @Override
    public void deserialize(@NonNull Parcel src) {
        mBytes = src.createByteArray();
    }
}
