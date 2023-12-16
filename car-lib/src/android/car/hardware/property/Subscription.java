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

import static android.car.feature.Flags.FLAG_BATCHED_SUBSCRIPTIONS;
import static android.car.feature.Flags.FLAG_VARIABLE_UPDATE_RATE;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.hardware.CarPropertyConfig;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.util.Set;

/**
 * Represents the subscription data to {@link CarPropertyManager#subscribePropertyEvents}. To
 * create an Subscription use {@link Subscription.Builder}.
 */
@FlaggedApi(FLAG_BATCHED_SUBSCRIPTIONS)
public final class Subscription {
    private final int mPropertyId;
    @FloatRange(from = 0.0, to = 100.0)
    private final float mUpdateRateHz;
    private final int[] mAreaIds;
    private final boolean mVariableUpdateRateEnabled;

    private Subscription(@NonNull Builder builder) {
        mPropertyId = builder.mBuilderPropertyId;
        mUpdateRateHz = builder.mBuilderUpdateRateHz;
        mAreaIds = new int[builder.mBuilderAreaIds.size()];
        mVariableUpdateRateEnabled = builder.mVariableUpdateRateEnabled;
        int index = 0;
        for (Integer i : builder.mBuilderAreaIds) {
            mAreaIds[index++] = i;
        }
    }

    /**
     * Gets the {@link CarPropertyManager#SENSOR_RATE_UI} which is 5hz.
     *
     * @return {@link CarPropertyManager#SENSOR_RATE_UI}
     */
    public float getUpdateRateUi() {
        return CarPropertyManager.SENSOR_RATE_UI;
    }

    /**
     * Gets the {@link CarPropertyManager#SENSOR_RATE_NORMAL} which is 1hz.
     *
     * @return {@link CarPropertyManager#SENSOR_RATE_NORMAL}
     */
    public float getUpdateRateNormal() {
        return CarPropertyManager.SENSOR_RATE_NORMAL;
    }

    /**
     * Gets the {@link CarPropertyManager#SENSOR_RATE_FAST} which is 10hz.
     *
     * @return {@link CarPropertyManager#SENSOR_RATE_FAST}
     */
    public float getUpdateRateFast() {
        return CarPropertyManager.SENSOR_RATE_FAST;
    }

    /**
     * Gets the {@link CarPropertyManager#SENSOR_RATE_FASTEST} which is 100hz.
     *
     * @return {@link CarPropertyManager#SENSOR_RATE_FASTEST}
     */
    public float getUpdateRateFastest() {
        return CarPropertyManager.SENSOR_RATE_FASTEST;
    }

    /**
     * @return The propertyId to subscribe to
     */
    public int getPropertyId() {
        return mPropertyId;
    }

    /**
     * @return The update rate to subscribe to
     */
    public float getUpdateRateHz() {
        return mUpdateRateHz;
    }

    /**
     * @return The areaIds to subscribe to
     */
    @Nullable
    public int[] getAreaIds() {
        return mAreaIds.clone();
    }

    /**
     * @return whether variable update rate is enabled.
     */
    @FlaggedApi(FLAG_VARIABLE_UPDATE_RATE)
    public boolean isVariableUpdateRateEnabled() {
        return mVariableUpdateRateEnabled;
    }

    /**
     * Builder for {@link Subscription}
     */
    public static final class Builder {
        private final int mBuilderPropertyId;
        private float mBuilderUpdateRateHz = CarPropertyManager.SENSOR_RATE_ONCHANGE;
        private final Set<Integer> mBuilderAreaIds = new ArraySet<>();
        private long mBuilderFieldsSet = 0L;
        // By default variable update rate is enabled.
        private boolean mVariableUpdateRateEnabled = true;

        public Builder(int propertyId) {
            this.mBuilderPropertyId = propertyId;
        }

        /**
         * Sets the update rate in Hz for continuous property.
         *
         * <p>For {@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE} properties, this
         * operation has no effect and update rate is defaulted to {@code 0}.
         *
         * <p>The update rate decides the hardware polling rate (if supported) and will be sanitized
         * to a range between {@link CarPropertyConfig#getMinSampleRate} and
         * {@link CarPropertyConfig#getMaxSampleRate}.
         *
         * <p>For better system performance, it is recommended to set this to the smallest
         * reasonable value, e.g. {@link CarPropertyManager.SENSOR_RATE_NORMAL}.
         *
         * @param updateRateHz The update rate to set for the given builder
         * @return The original Builder object. This value cannot be {@code null}.
         */
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
        @NonNull
        public Builder setUpdateRateUi() {
            mBuilderUpdateRateHz = CarPropertyManager.SENSOR_RATE_UI;
            return this;
        }

        /**
         * Sets the update rate to {@link CarPropertyManager#SENSOR_RATE_NORMAL}
         * @return The original Builder object. This value cannot be {@code null}.
         */
        @NonNull
        public Builder setUpdateRateNormal() {
            mBuilderUpdateRateHz = CarPropertyManager.SENSOR_RATE_NORMAL;
            return this;
        }

        /**
         * Sets the update rate to {@link CarPropertyManager#SENSOR_RATE_FAST}
         * @return The original Builder object. This value cannot be {@code null}.
         */
        @NonNull
        public Builder setUpdateRateFast() {
            mBuilderUpdateRateHz = CarPropertyManager.SENSOR_RATE_FAST;
            return this;
        }

        /**
         * Sets the update rate to {@link CarPropertyManager#SENSOR_RATE_FASTEST}
         * @return The original Builder object. This value cannot be {@code null}.
         */
        @NonNull
        public Builder setUpdateRateFastest() {
            mBuilderUpdateRateHz = CarPropertyManager.SENSOR_RATE_FASTEST;
            return this;
        }

        /**
         * Adds an areaId for the {@link Subscription} being built. If the areaId is already
         * present, then the operation is ignored.
         *
         * @param areaId The areaId to add for the given builder
         * @return The original Builder object. This value cannot be {@code null}.
         */
        @NonNull
        public Builder addAreaId(int areaId) {
            mBuilderAreaIds.add(areaId);
            return this;
        }

        /**
         * Enables/Disables variable update rate.
         *
         * <p>This option is only meaningful for continuous property.
         *
         * <p>By default, variable update rate is enabled for all [propId, areaId]s in this options,
         * unless disabled via this function or not supported for a specific [propId, areaId]
         * represented by
         * {@link AreaIdConfig#isVariableUpdateRateSupported} returning {@code false}.
         *
         * <p>For better system performance, it is STRONGLY RECOMMENDED NOT TO DISABLE variable
         * update rate unless the client relies on continuously arriving property update events
         * (e.g. for system health checking).
         *
         * <p>If variable update rate is enabled, then client will receive property
         * update events only when the property's value changes (a.k.a behaves the same as an
         * on-change property).
         *
         * <p>If variable update rate is disabled, then client will receive all the property
         * update events based on the update rate even if the events contain the same property
         * value.
         *
         * <p>E.g. a vehicle speed subscribed at 10hz will cause the vehicle hardware to poll 10
         * times per second. If the vehicle is initially parked at time 0s, and start moving at
         * speed 1 at time 1s. If variable update rate is enabled, the client will receive one
         * initial value of speed 0 at time 0s, and one value of speed 1 at time 1s. If variable
         * update rate is disabled, the client will receive 10 events of speed 0 from 0s to 1s, and
         * 10 events of speed 1 from 1s to 2s.
         *
         * @return The original Builder object. This value cannot be {@code null}.
         */
        @FlaggedApi(FLAG_VARIABLE_UPDATE_RATE)
        @NonNull
        public Builder setVariableUpdateRateEnabled(boolean enabled) {
            mVariableUpdateRateEnabled = enabled;
            return this;
        }

        /**
         * Builds and returns the Subscription
         *
         * @throws IllegalStateException if build is used more than once.
         */
        @NonNull
        public Subscription build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            return new Subscription(this);
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x1) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
