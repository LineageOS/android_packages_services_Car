/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.car.hardware.property;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.annotation.ApiRequirements;
import android.car.hardware.CarPropertyConfig;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.util.Set;

/**
 * Represents the subscription data to {@link CarPropertyManager#subscribePropertyEvents}. To
 * create an SubscriptionOption use {@link SubscriptionOption.Builder}.
 */
public final class SubscriptionOption {
    private final int mPropertyId;
    @FloatRange(from = 0.0, to = 100.0)
    private final float mUpdateRateHz;
    private final int[] mAreaIds;

    private SubscriptionOption(@NonNull Builder builder) {
        mPropertyId = builder.mBuilderPropertyId;
        mUpdateRateHz = builder.mBuilderUpdateRateHz;
        mAreaIds = new int[builder.mBuilderAreaIds.size()];
        int index = 0;
        for (Integer i : builder.mBuilderAreaIds) {
            mAreaIds[index++] = i;
        }
    }

    /**
     * Gets the {@link CarPropertyManager#SENSOR_RATE_UI} which is 1hz.
     *
     * @return {@link CarPropertyManager#SENSOR_RATE_UI}
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
    public float getUpdateRateUi() {
        return CarPropertyManager.SENSOR_RATE_UI;
    }

    /**
     * Gets the {@link CarPropertyManager#SENSOR_RATE_NORMAL} which is 5hz.
     *
     * @return {@link CarPropertyManager#SENSOR_RATE_NORMAL}
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
    public float getUpdateRateNormal() {
        return CarPropertyManager.SENSOR_RATE_NORMAL;
    }

    /**
     * Gets the {@link CarPropertyManager#SENSOR_RATE_FAST} which is 10hz.
     *
     * @return {@link CarPropertyManager#SENSOR_RATE_FAST}
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
    public float getUpdateRateFast() {
        return CarPropertyManager.SENSOR_RATE_FAST;
    }

    /**
     * Gets the {@link CarPropertyManager#SENSOR_RATE_FASTEST} which is 100hz.
     *
     * @return {@link CarPropertyManager#SENSOR_RATE_FASTEST}
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
    public float getUpdateRateFastest() {
        return CarPropertyManager.SENSOR_RATE_FASTEST;
    }

    /**
     * @return The propertyId to subscribe to
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
    public int getPropertyId() {
        return mPropertyId;
    }

    /**
     * @return The update rate to subscribe to
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
    public float getUpdateRateHz() {
        return mUpdateRateHz;
    }

    /**
     * @return The areaIds to subscribe to
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
    @Nullable
    public int[] getAreaIds() {
        return mAreaIds.clone();
    }

    /**
     * Builder for {@link SubscriptionOption}
     */
    public static final class Builder {
        private final int mBuilderPropertyId;
        private float mBuilderUpdateRateHz = CarPropertyManager.SENSOR_RATE_ONCHANGE;
        private final Set<Integer> mBuilderAreaIds = new ArraySet<>();
        private long mBuilderFieldsSet = 0L;


        public Builder(int propertyId) {
            this.mBuilderPropertyId = propertyId;
        }

        /**
         * Sets the update rate in Hz for continuous property.
         * For {@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE} properties, this
         * operation has no effect and update rate is defaulted to {@code 0}.
         *
         * @param updateRateHz The update rate to set for the given builder
         * @return The original Builder object. This value cannot be {@code null}.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
        @NonNull
        public Builder setUpdateRateHz(@FloatRange(from = 0.0, to = 100.0) float updateRateHz) {
            Preconditions.checkArgumentInRange(updateRateHz, /* lower= */ 0.0f,
                    /* upper= */ 100.0f,
                    /* valueName= */ "Update rate should be in the range [0.0f, 100.0f]");
            mBuilderUpdateRateHz = updateRateHz;
            return this;
        }

        /**
         * Sets the update rate to {@link CarPropertyManager#SENSOR_RATE_UI}
         * @return The original Builder object. This value cannot be {@code null}.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
        @NonNull
        public Builder setUpdateRateUi() {
            mBuilderUpdateRateHz = CarPropertyManager.SENSOR_RATE_UI;
            return this;
        }

        /**
         * Sets the update rate to {@link CarPropertyManager#SENSOR_RATE_NORMAL}
         * @return The original Builder object. This value cannot be {@code null}.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
        @NonNull
        public Builder setUpdateRateNormal() {
            mBuilderUpdateRateHz = CarPropertyManager.SENSOR_RATE_NORMAL;
            return this;
        }

        /**
         * Sets the update rate to {@link CarPropertyManager#SENSOR_RATE_FAST}
         * @return The original Builder object. This value cannot be {@code null}.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
        @NonNull
        public Builder setUpdateRateFast() {
            mBuilderUpdateRateHz = CarPropertyManager.SENSOR_RATE_FAST;
            return this;
        }

        /**
         * Sets the update rate to {@link CarPropertyManager#SENSOR_RATE_FASTEST}
         * @return The original Builder object. This value cannot be {@code null}.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
        @NonNull
        public Builder setUpdateRateFastest() {
            mBuilderUpdateRateHz = CarPropertyManager.SENSOR_RATE_FASTEST;
            return this;
        }

        /**
         * Adds an areaId for the {@link SubscriptionOption} being built. If the areaId is already
         * present, then the operation is ignored.
         *
         * @param areaId The areaId to add for the given builder
         * @return The original Builder object. This value cannot be {@code null}.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
        @NonNull
        public Builder addAreaId(int areaId) {
            mBuilderAreaIds.add(areaId);
            return this;
        }

        /**
         * Builds and returns the SubscriptionOption
         *
         * @throws IllegalStateException if build is used more than once.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.VANILLA_ICE_CREAM_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.VANILLA_ICE_CREAM_0)
        @NonNull
        public SubscriptionOption build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            return new SubscriptionOption(this);
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x1) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
