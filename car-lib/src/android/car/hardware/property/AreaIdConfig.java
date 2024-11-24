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

package android.car.hardware.property;

import static android.car.feature.Flags.FLAG_AREA_ID_CONFIG_ACCESS;
import static android.car.feature.Flags.FLAG_CAR_PROPERTY_SUPPORTED_VALUE;
import static android.car.feature.Flags.FLAG_VARIABLE_UPDATE_RATE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.car.hardware.CarPropertyConfig;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.car.internal.property.RawPropertyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents area ID specific configuration information for a vehicle property.
 *
 * @param <T> matches the type for the {@link android.car.hardware.CarPropertyConfig}.
 */
public final class AreaIdConfig<T> implements Parcelable {
    @NonNull
    public static final Parcelable.Creator<AreaIdConfig<Object>> CREATOR = getCreator();

    private final @CarPropertyConfig.VehiclePropertyAccessType int mAccess;
    private final int mAreaId;
    @Nullable private final T mMinValue;
    @Nullable private final T mMaxValue;
    private final List<T> mSupportedEnumValues;
    private final boolean mSupportVariableUpdateRate;
    private final boolean mHasMinSupportedValue;
    private final boolean mHasMaxSupportedValue;
    private final boolean mHasSupportedValuesList;

    private AreaIdConfig(
            int areaId, @Nullable T minValue, @Nullable T maxValue, List<T> supportedEnumValues,
            @CarPropertyConfig.VehiclePropertyAccessType int access,
            boolean supportVariableUpdateRate, boolean hasMinSupportedValue,
            boolean hasMaxSupportedValue, boolean hasSupportedValuesList) {
        mAccess = access;
        mAreaId = areaId;
        mMinValue = minValue;
        mMaxValue = maxValue;
        mSupportedEnumValues = supportedEnumValues;
        mSupportVariableUpdateRate = supportVariableUpdateRate;
        mHasMinSupportedValue = hasMinSupportedValue;
        mHasMaxSupportedValue = hasMaxSupportedValue;
        mHasSupportedValuesList = hasSupportedValuesList;
    }

    @SuppressWarnings("unchecked")
    private AreaIdConfig(Parcel in) {
        mAccess = in.readInt();
        mAreaId = in.readInt();
        var minPropertyValue = (RawPropertyValue<T>) in.readParcelable(
                RawPropertyValue.class.getClassLoader(), RawPropertyValue.class);
        if (minPropertyValue != null) {
            mMinValue = minPropertyValue.getTypedValue();
        } else {
            mMinValue = null;
        }
        var maxPropertyValue = (RawPropertyValue<T>) in.readParcelable(
                RawPropertyValue.class.getClassLoader(), RawPropertyValue.class);
        if (maxPropertyValue != null) {
            mMaxValue = maxPropertyValue.getTypedValue();
        } else {
            mMaxValue = null;
        }
        List<RawPropertyValue> supportedEnumPropertyValues = new ArrayList<>();
        in.readParcelableList(supportedEnumPropertyValues,
                RawPropertyValue.class.getClassLoader(), RawPropertyValue.class);
        mSupportedEnumValues = new ArrayList<T>();
        for (int i = 0; i < supportedEnumPropertyValues.size(); i++) {
            mSupportedEnumValues.add((T) supportedEnumPropertyValues.get(i).getTypedValue());
        }
        mSupportVariableUpdateRate = in.readBoolean();
        mHasMinSupportedValue = in.readBoolean();
        mHasMaxSupportedValue = in.readBoolean();
        mHasSupportedValuesList = in.readBoolean();
    }

    private static <E> Parcelable.Creator<AreaIdConfig<E>> getCreator() {
        return new Creator<AreaIdConfig<E>>() {
            @Override
            public AreaIdConfig<E> createFromParcel(Parcel source) {
                return new AreaIdConfig<>(source);
            }

            @Override
            @SuppressWarnings("unchecked")
            public AreaIdConfig<E>[] newArray(int size) {
                AreaIdConfig<E>[] areaIdConfigs = new AreaIdConfig[size];
                for (int i = 0; i < size; i++) {
                    areaIdConfigs[i] = null;
                }
                return areaIdConfigs;
            }
        };
    }

    /**
     * Return the access type of the car property at the current areaId.
     * <p>The access type could be one of the following:
     * <ul>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_ACCESS_NONE}</li>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_ACCESS_READ}</li>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_ACCESS_WRITE}</li>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_ACCESS_READ_WRITE}</li>
     * </ul>
     *
     * @return the access type of the car property at the current areaId.
     */
    @FlaggedApi(FLAG_AREA_ID_CONFIG_ACCESS)
    public @CarPropertyConfig.VehiclePropertyAccessType int getAccess() {
        return mAccess;
    }

    /**
     * Returns the area ID for this configuration.
     *
     * @return area ID for this configuration.
     */
    public int getAreaId() {
        return mAreaId;
    }

    /**
     * @deprecated use {@link CarPropertyManager#getMinMaxSupportedValue} instead.
     *
     * Returns the minimum supported value for the {@link #getAreaId()} reported by vehicle
     * hardware at boot time. This value does not change even though the hardware may report a
     * different value after boot. This value may not represent the currently supported min value.
     *
     * Use {@link CarPropertyManager#getMinMaxSupportedValue} for more accurate information.
     *
     * @return minimum value supported for the {@link #getAreaId()} at boot time. Will return
     *     {@code null} if no minimum value supported.
     */
    @Deprecated
    @Nullable
    public T getMinValue() {
        return mMinValue;
    }

    /**
     * @deprecated use {@link CarPropertyManager#getMinMaxSupportedValue} instead.
     *
     * Returns the maximum supported value for the {@link #getAreaId()} reported by vehicle
     * hardware at boot time. This value does not change even though the hardware may report a
     * different value after boot. This value may not represent the currently supported max value.
     *
     * Use {@link CarPropertyManager#getMinMaxSupportedValue} for more accurate information.
     *
     * @return maximum value supported for the {@link #getAreaId()} at boot time. Will return
     *     {@code null} if no maximum value supported.
     */
    @Deprecated
    @Nullable
    public T getMaxValue() {
        return mMaxValue;
    }

    /**
     * Returns whether variable update rate is supported.
     *
     * If this returns {@code false}, variable update rate is always disabled for this area ID.
     *
     * If this returns {@code true}, variable update rate will be disabled if client calls
     * {@link Subscription.Builder#setVariableUpdateRateEnabled} with {@code false}, or enabled
     * otherwise.
     *
     * @return whether variable update rate is supported.
     */
    @FlaggedApi(FLAG_VARIABLE_UPDATE_RATE)
    public boolean isVariableUpdateRateSupported() {
        return mSupportVariableUpdateRate;
    }

    /**
     * @deprecated use {@link CarPropertyManager#getSupportedValuesList} instead.
     *
     * Returns the supported enum values for the {@link #getAreaId()} reported by vehicle
     * hardware at boot time. This list does not change even though the hardware may report a
     * different list after boot. This list may not represent the currently supported enum values.
     *
     * Use {@link CarPropertyManager#getSupportedValuesList} for more accurate information.
     *
     * @return supported enum values for the {@link #getAreaId()} at boot time. If this list is
     *      empty, no enums are supported for this area at boot time.
     */
    @Deprecated
    @NonNull
    public List<T> getSupportedEnumValues() {
        return Collections.unmodifiableList(mSupportedEnumValues);
    }

    /**
     * Whether [propertyId, areaId] has min supported value specified.
     *
     * <p>If this returns {@code true}, it means the hardware specifies a min supported value for
     * this property. In normal cases, {@link CarPropertyManager#getMinMaxSupportedValue()} will
     * return a structure whose
     * {@link CarPropertyManager.MinMaxSupportedValue#getMinValue()} method returns a non-null result.
     *
     * In non-normal (error) cases, {@link CarPropertyManager.MinMaxSupportedValue#getMinValue()}
     * may still return {@code null}.
     *
     * <p>If this returns {@code false},
     * {@link CarPropertyManager.MinMaxSupportedValue#getMinValue()} always returns {@code null}.
     *
     * <p>Unless otherwise specified in {@link VehiclePropertyIds} documentation, this function
     * returns {@code false} for any system properties whose type is not int32, int64 or float.
     *
     * <p>For certain properties, e.g. {@code EV_BRAKE_REGENERATION_LEVEL}, this always return
     * {@code true}. Check {@code VehiclePropertyIds} documentation for detail.
     */
    @FlaggedApi(FLAG_CAR_PROPERTY_SUPPORTED_VALUE)
    public boolean hasMinSupportedValue() {
        return mHasMinSupportedValue;
    }

    /**
     * Whether [propertyId, areaId] has max supported value specified.
     *
     * <p>If this returns {@code true}, it means the hardware specifies a min supported value for
     * this property. In normal cases, {@link CarPropertyManager#getMinMaxSupportedValue()} will
     * return a structure whose
     * {@link CarPropertyManager.MinMaxSupportedValue#getMaxValue()} method returns a non-null
     * result.
     *
     * In non-normal (error) cases, {@link CarPropertyManager.MinMaxSupportedValue#getMaxValue()}
     * may still return {@code null}.
     *
     * <p>If this returns {@code false},
     * {@link CarPropertyManager.MinMaxSupportedValue#getMaxValue()} always returns {@code null}.
     *
     * <p>Unless otherwise specified in {@link VehiclePropertyIds} documentation, this function
     * returns {@code false} for any system properties whose type is not int32, int64 or float.
     *
     * <p>For certain properties, e.g. {@code EV_BRAKE_REGENERATION_LEVEL}, this always return
     * {@code true}. Check {@code VehiclePropertyIds} documentation for detail.
     */
    @FlaggedApi(FLAG_CAR_PROPERTY_SUPPORTED_VALUE)
    public boolean hasMaxSupportedValue() {
        return mHasMaxSupportedValue;
    }

    /**
     * Whether [propertyId, areaId] has supported value list specified.
     *
     * <p>If this returns {@code true}, it means the hardware specifies supported value list for
     * this property. In normal cases, {@link CarPropertyManager#getSupportedValuesList()} will not
     * return {@code null}. In non-normal (error) cases, it may return {@code null}.
     *
     * <p>If this returns {@code false}, {@link CarPropertyManager#getSupportedValuesList()} always
     * returns {@code null}.
     *
     * <p>The supported value list is the superset for both the input value for writable property
     * and the output value for readable property.
     *
     * <p>For certain properties, e.g. {@code GEAR_SELECTION}, this always returns {@code true}.
     * Check {@code VehiclePropertyIds} documentation for detail.
     */
    @FlaggedApi(FLAG_CAR_PROPERTY_SUPPORTED_VALUE)
    public boolean hasSupportedValuesList() {
        return mHasSupportedValuesList;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAccess);
        dest.writeInt(mAreaId);
        RawPropertyValue minPropertyValue = null;
        if (mMinValue != null) {
            minPropertyValue = new RawPropertyValue(mMinValue);
        }
        dest.writeParcelable(minPropertyValue, /* parcelableFlags= */ 0);
        RawPropertyValue maxPropertyValue = null;
        if (mMaxValue != null) {
            maxPropertyValue = new RawPropertyValue(mMaxValue);
        }
        dest.writeParcelable(maxPropertyValue, /* parcelableFlags= */ 0);
        List<RawPropertyValue> supportedEnumPropertyValues = new ArrayList<>();
        for (int i = 0; i < mSupportedEnumValues.size(); i++) {
            supportedEnumPropertyValues.add(new RawPropertyValue(mSupportedEnumValues.get(i)));
        }
        dest.writeParcelableList(supportedEnumPropertyValues, /* parcelableFlags= */ 0);
        dest.writeBoolean(mSupportVariableUpdateRate);
        dest.writeBoolean(mHasMinSupportedValue);
        dest.writeBoolean(mHasMaxSupportedValue);
        dest.writeBoolean(mHasSupportedValuesList);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AreaIdConfig{")
                .append("mAccess=").append(mAccess)
                .append("mAreaId=").append(mAreaId);
        if (mMinValue != null) {
            sb.append(", mMinValue=").append(mMinValue);
        }
        if (mMaxValue != null) {
            sb.append(", mMaxValue=").append(mMaxValue);
        }
        if (!mSupportedEnumValues.isEmpty()) {
            sb.append(", mSupportedEnumValues=").append(mSupportedEnumValues);
        }
        sb.append(", mHasMinSupportedValue=").append(mHasMinSupportedValue);
        sb.append(", mHasMaxSupportedValue=").append(mHasMaxSupportedValue);
        sb.append(", mHasSupportedValuesList=").append(mHasSupportedValuesList);
        return sb.append("}").toString();
    }

    /**
     * @param <T> matches the type for the {@link android.car.hardware.CarPropertyConfig}.
     *
     * This is supposed to be called by CarService only. For history reason, we exposed
     * this as system API, however, client must not use this builder and should use the getXXX
     * method in {@code AreaIdConfig}.
     *
     * @hide
     * @deprecated marked as deprecated because clients should not have direct access to the
     * AreaIdConfig.Builder class
     */
    @Deprecated
    @SystemApi
    public static final class Builder<T> {
        private final @CarPropertyConfig.VehiclePropertyAccessType int mAccess;
        private final int mAreaId;
        private T mMinValue = null;
        private T mMaxValue = null;
        private List<T> mSupportedEnumValues = Collections.EMPTY_LIST;
        private boolean mSupportVariableUpdateRate = false;
        private boolean mHasMaxSupportedValue = false;
        private boolean mHasMinSupportedValue = false;
        private boolean mHasSupportedValuesList = false;

        public Builder(int areaId) {
            mAccess = CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_NONE;
            mAreaId = areaId;
        }

        @FlaggedApi(FLAG_AREA_ID_CONFIG_ACCESS)
        public Builder(@CarPropertyConfig.VehiclePropertyAccessType int access, int areaId) {
            mAccess = access;
            mAreaId = areaId;
        }

        /** Set the min value for the {@link AreaIdConfig}. */
        @NonNull
        public Builder<T> setMinValue(T minValue) {
            mMinValue = minValue;
            mHasMinSupportedValue = true;
            return this;
        }

        /** Set the max value for the {@link AreaIdConfig}. */
        @NonNull
        public Builder<T> setMaxValue(T maxValue) {
            mMaxValue = maxValue;
            mHasMaxSupportedValue = true;
            return this;
        }

        /** Set the supported enum values for the {@link AreaIdConfig}. */
        @NonNull
        public Builder<T> setSupportedEnumValues(@NonNull List<T> supportedEnumValues) {
            mSupportedEnumValues = supportedEnumValues;
            mHasSupportedValuesList = true;
            return this;
        }

        /**
         * Sets whether variable update rate is supported.
         *
         * This is supposed to be called by CarService only.
         *
         * @hide
         */
        @NonNull
        public Builder<T> setSupportVariableUpdateRate(boolean supportVariableUpdateRate) {
            mSupportVariableUpdateRate = supportVariableUpdateRate;
            return this;
        }

        /**
         * Sets whether this area has a specified min supported value.
         *
         * @hide
         */
        @NonNull
        public Builder<T> setHasMinSupportedValue(boolean hasMinSupportedValue) {
            mHasMinSupportedValue = hasMinSupportedValue;
            return this;
        }

        /**
         * Sets whether this area has a specified max supported value.
         *
         * @hide
         */
        @NonNull
        public Builder<T> setHasMaxSupportedValue(boolean hasMaxSupportedValue) {
            mHasMaxSupportedValue = hasMaxSupportedValue;
            return this;
        }

        /**
         * Sets whether this area has a specified supported value list.
         *
         * @hide
         */
        @NonNull
        public Builder<T> setHasSupportedValuesList(boolean hasSupportedValuesList) {
            mHasSupportedValuesList = hasSupportedValuesList;
            return this;
        }

        /** Builds a new {@link android.car.hardware.property.AreaIdConfig}. */
        @NonNull
        public AreaIdConfig<T> build() {
            return new AreaIdConfig<>(mAreaId, mMinValue, mMaxValue, mSupportedEnumValues, mAccess,
                    mSupportVariableUpdateRate, mHasMinSupportedValue, mHasMaxSupportedValue,
                    mHasSupportedValuesList);
        }
    }
}
