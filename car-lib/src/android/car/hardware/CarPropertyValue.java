/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.car.feature.Flags.FLAG_CAR_PROPERTY_STATUS_DETAILED_NOT_AVAILABLE;
import static android.car.feature.Flags.FLAG_CAR_PROPERTY_VALUE_PROPERTY_STATUS;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;
import static com.android.car.internal.util.DebugUtils.constantToString;
import static com.android.car.internal.util.DebugUtils.toAreaIdString;

import static java.lang.Integer.toHexString;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.builtin.os.BuildHelper;
import android.car.feature.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.property.RawPropertyValue;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Stores a value for a vehicle property ID and area ID combination.
 *
 * Client should use {@code android.car.*} types when dealing with property ID, area ID or property
 * value and MUST NOT use {@code android.hardware.automotive.vehicle.*} types directly.
 *
 * @param <T> refer to {@link Parcel#writeValue(java.lang.Object)} to get a list of all supported
 *            types. The class should be visible to framework as default class loader is being used
 *            here.
 */
public final class CarPropertyValue<T> implements Parcelable {

    private final int mPropertyId;
    private final int mAreaId;
    private final int mSystemStatus;
    private final long mTimestampNanos;
    private final RawPropertyValue<T> mValue;
    private final boolean mIsSimulationPropId;
    private final int mVendorStatus;

    /**
     * Whether the client has permission to read property vendor status.
     *
     * This variable is only set at CarPropertyEventCallbackController (car-lib). It is not passed
     * through binder.
     */
    private final boolean mHasPermissionToReadPropertyVendorStatus;

    /**
     * {@code CarPropertyValue} is available.
     */
    public static final int STATUS_AVAILABLE = 0;

    /**
     * {@code CarPropertyValue} is not available for general reason.
     */
    public static final int STATUS_UNAVAILABLE = 1;

    /**
     * {@code CarPropertyValue} is not available for general reason.
     *
     * Same as {@link #STATUS_UNAVAILABLE} but with a more specific name.
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_STATUS_DETAILED_NOT_AVAILABLE)
    public static final int STATUS_NOT_AVAILABLE_GENERAL = 1;

    /**
     * {@code CarPropertyValue} has an error.
     */
    public static final int STATUS_ERROR = 2;

    /**
     * {@code CarPropertyValue} is not available because the property feature is disabled.
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_STATUS_DETAILED_NOT_AVAILABLE)
    public static final int STATUS_NOT_AVAILABLE_DISABLED = 3;

    /**
     * {@code CarPropertyValue} is not available because the vehicle speed is too low.
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_STATUS_DETAILED_NOT_AVAILABLE)
    public static final int STATUS_NOT_AVAILABLE_SPEED_LOW = 4;

    /**
     * {@code CarPropertyValue} is not available because the vehicle speed is too high.
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_STATUS_DETAILED_NOT_AVAILABLE)
    public static final int STATUS_NOT_AVAILABLE_SPEED_HIGH = 5;

    /**
     * {@code CarPropertyValue} is not available because of bad camera or sensor
     * visibility. Examples might be bird poop blocking the camera or a bumper cover blocking an
     * ultrasonic sensor.
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_STATUS_DETAILED_NOT_AVAILABLE)
    public static final int STATUS_NOT_AVAILABLE_POOR_VISIBILITY = 6;

    /**
     * {@code CarPropertyValue} is not available because of safety reasons. Eg. System could be
     * in a faulty state, an object or person could be blocking the requested operation such as
     * closing a trunk door, etc..
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_STATUS_DETAILED_NOT_AVAILABLE)
    public static final int STATUS_NOT_AVAILABLE_SAFETY = 7;

    /**
     * {@code CarPropertyValue} is not available because the sub-system for the feature is not
     * connected.
     *
     * <p>E.g. the trailer light property is in this state if the trailer is not attached.
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_STATUS_DETAILED_NOT_AVAILABLE)
    public static final int STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED = 8;

    /**
     * @removed accidentally exposed previously
     *
     * This is now deprecated and not used any more. Internally we use CarPropertyStatus instead.
     */
    @IntDef({
        STATUS_AVAILABLE,
        STATUS_UNAVAILABLE,
        STATUS_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PropertyStatus {}

    /**
     * All possible status for a car property value.
     *
     * Note: If this is updated, {@link com.android.car.internal.property.PropertyStatusUtils}
     * must be updated.
     *
     * @hide
     */
    @IntDef({
        STATUS_AVAILABLE,
        STATUS_ERROR,
        STATUS_NOT_AVAILABLE_GENERAL,
        STATUS_NOT_AVAILABLE_DISABLED,
        STATUS_NOT_AVAILABLE_SPEED_LOW,
        STATUS_NOT_AVAILABLE_SPEED_HIGH,
        STATUS_NOT_AVAILABLE_POOR_VISIBILITY,
        STATUS_NOT_AVAILABLE_SAFETY,
        STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CarPropertyStatus {}

    /**
     * All possible status for a car property value.
     *
     * This exists because CarPropertyStatus is for compile time check and this is for runtime
     * check.
     *
     * @hide
     */
    @VisibleForTesting
    public static final List<Integer> ALL_CAR_PROPERTY_STATUS = List.of(
            STATUS_AVAILABLE,
            STATUS_ERROR,
            STATUS_NOT_AVAILABLE_GENERAL,
            STATUS_NOT_AVAILABLE_DISABLED,
            STATUS_NOT_AVAILABLE_SPEED_LOW,
            STATUS_NOT_AVAILABLE_SPEED_HIGH,
            STATUS_NOT_AVAILABLE_POOR_VISIBILITY,
            STATUS_NOT_AVAILABLE_SAFETY,
            STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED
    );

    private static final int UNSET_VENDOR_PROPERTY_STATUS = 0;

    /**
     * Builder for CarPropertyValue.
     *
     * This is preferred over directly using CarPropertyValue constructor.
     *
     * @param <T> refer to {@link Parcel#writeValue(java.lang.Object)} to get a list of all
     *            supported types. The class should be visible to framework as default class loader
     *            is being used here.
     *
     * @hide
     */
    @TestApi
    public static class Builder<T> {
        private final int mPropertyId;
        private final int mAreaId;

        private long mTimestampNanos;
        private RawPropertyValue mRawPropertyValue;
        private int mSystemStatus = CarPropertyValue.STATUS_AVAILABLE;
        private boolean mIsSimulationPropId;
        private int mVendorStatus;
        private boolean mHasPermissionToReadPropertyVendorStatus;
        private boolean mBuilt;

        /**
         * Creates a builder for {@link CarPropertyValue}.
         *
         * @param propertyId The property identifier, see constants in
         *                   {@link android.car.VehiclePropertyIds} for system defined property IDs.
         * @param areaId     The area identifier. Must be {@code 0} if property is
         *                   {@link android.car.VehicleAreaType#VEHICLE_AREA_TYPE_GLOBAL}.
         *                   Otherwise, it must be one or more OR'd together constants of this
         *                   property's
         *                   {@link android.car.VehicleAreaType}:
         *                     <ul>
         *                       <li>{@code VehicleAreaWindow}</li>
         *                       <li>{@code VehicleAreaDoor}</li>
         *                       <li>{@link android.car.VehicleAreaSeat}</li>
         *                       <li>{@code VehicleAreaMirror}</li>
         *                       <li>{@link android.car.VehicleAreaWheel}</li>
         *                     </ul>
         *
         * @hide
         */
        @TestApi
        public Builder(int propertyId, int areaId) {
            this.mPropertyId = propertyId;
            this.mAreaId = areaId;
        }

        /**
         * Sets the property value.
         *
         * @param value Value of Property
         *
         * @hide
         */
        @TestApi
        public Builder<T> setValue(T value) {
            Objects.requireNonNull(value, "value for propertyId: "
                    + VehiclePropertyIds.toString(mPropertyId) + ", areaId: "
                    + toAreaIdString(mPropertyId, mAreaId)
                    + " must not be null");
            mRawPropertyValue = new RawPropertyValue(value);
            return this;
        }

        /**
         * Sets the raw property value.
         *
         * Raw property value is a parcelable structure containing the actual property type. It
         * typically comes from VHAL.
         *
         * Note: This is intentionally not exposed through {@code TestApi} because
         * {@link RawPropertyValue} is an internal type.
         *
         * @param rawPropertyValue Value of the property.
         *
         * @hide
         */
        public Builder<T> setRawPropertyValue(RawPropertyValue rawPropertyValue) {
            mRawPropertyValue = rawPropertyValue;
            return this;
        }

        /**
         * Sets the property timestamp in Nanoseconds.
         *
         * @param timestampNanos  Elapsed time in nanoseconds since boot
         *
         * @hide
         */
        @TestApi
        public Builder<T> setTimestampNanos(long timestampNanos) {
            mTimestampNanos = timestampNanos;
            return this;
        }

        /**
         * Sets the property system status.
         *
         * Must be one of {@link CarPropertyStatus}.
         *
         * @hide
         */
        @TestApi
        public Builder<T> setSystemStatus(@CarPropertyStatus int systemStatus) {
            mSystemStatus = systemStatus;
            return this;
        }

        /**
         * Sets the property vendor status.
         *
         * @hide
         */
        @TestApi
        public Builder<T> setVendorStatus(int vendorStatus) {
            mVendorStatus = vendorStatus;
            return this;
        }

        /**
         * Sets whether the property is a Simulation property.
         *
         * @param isSimulationPropId If the property is a Simulation property.
         *
         * @hide
         */
        @TestApi
        public Builder<T> setIsSimulationPropId(boolean isSimulationPropId) {
            mIsSimulationPropId = isSimulationPropId;
            return this;
        }

        /**
         * Sets that the client has the permission to call {@link getPropertyVendorStatus}.
         *
         * @hide
         */
        @TestApi
        public Builder<T> setHasPermissionToReadPropertyVendorStatus(boolean hasPermission) {
            mHasPermissionToReadPropertyVendorStatus = hasPermission;
            return this;
        }

        /**
         * Builds the {@link CarPropertyValue}.
         *
         * Only allowed to be built once. Property value must be set via {@link setValue} or
         * {@link setRawPropertyValue}.
         *
         * @return The built instance.
         *
         * @hide
         */
        @TestApi
        public CarPropertyValue<T> build() {
            Preconditions.checkState(!mBuilt, "CarPropertyValue.Builder must only be built once");
            Preconditions.checkState(mRawPropertyValue != null,
                    "Value must be set before building");
            return new CarPropertyValue(this);
        }
    }

    /**
     * Creates a new builder based on an existing {@link CarPropertyValue}.
     */
    private static <K> Builder<K> newBuilder(CarPropertyValue<K> value) {
        return new Builder<K>(value.mPropertyId, value.mAreaId)
                .setSystemStatus(value.mSystemStatus)
                .setTimestampNanos(value.mTimestampNanos)
                .setRawPropertyValue(value.mValue)
                .setIsSimulationPropId(value.mIsSimulationPropId)
                .setVendorStatus(value.mVendorStatus)
                .setHasPermissionToReadPropertyVendorStatus(
                        value.mHasPermissionToReadPropertyVendorStatus);
    }

    private CarPropertyValue(Builder builder) {
        builder.mBuilt = true;
        mPropertyId = builder.mPropertyId;
        mAreaId = builder.mAreaId;
        mSystemStatus = builder.mSystemStatus;
        mTimestampNanos = builder.mTimestampNanos;
        mValue = builder.mRawPropertyValue;
        mIsSimulationPropId = builder.mIsSimulationPropId;
        mVendorStatus = builder.mVendorStatus;
        mHasPermissionToReadPropertyVendorStatus = builder.mHasPermissionToReadPropertyVendorStatus;
    }

    /**
     * Creates an instance of {@code CarPropertyValue}.
     *
     * @param propertyId The property identifier, see constants in
     *                   {@link android.car.VehiclePropertyIds} for system defined property IDs.
     * @param areaId     The area identifier. Must be {@code 0} if property is
     *                   {@link android.car.VehicleAreaType#VEHICLE_AREA_TYPE_GLOBAL}. Otherwise, it
     *                   must be one or more OR'd together constants of this property's
     *                   {@link android.car.VehicleAreaType}:
     *                     <ul>
     *                       <li>{@code VehicleAreaWindow}</li>
     *                       <li>{@code VehicleAreaDoor}</li>
     *                       <li>{@link android.car.VehicleAreaSeat}</li>
     *                       <li>{@code VehicleAreaMirror}</li>
     *                       <li>{@link android.car.VehicleAreaWheel}</li>
     *                     </ul>
     * @param value Value of Property
     * @hide
     */
    public CarPropertyValue(int propertyId, int areaId, T value) {
        this(new Builder(propertyId, areaId).setValue(value));
    }

    /**
     * Creates an instance of {@code CarPropertyValue}. The {@code timestampNanos} is the time in
     * nanoseconds at which the event happened. For a given car property, each new {@code
     * CarPropertyValue} should be monotonically increasing using the same time base as
     * {@link android.os.SystemClock#elapsedRealtimeNanos()}.
     *
     *
     * @param propertyId The property identifier, see constants in
     *                   {@link android.car.VehiclePropertyIds} for system defined property IDs.
     * @param areaId     The area identifier. Must be {@code 0} if property is
     *                   {@link android.car.VehicleAreaType#VEHICLE_AREA_TYPE_GLOBAL}. Otherwise, it
     *                   must be one or more OR'd together constants of this property's
     *                   {@link android.car.VehicleAreaType}:
     *                     <ul>
     *                       <li>{@code VehicleAreaWindow}</li>
     *                       <li>{@code VehicleAreaDoor}</li>
     *                       <li>{@link android.car.VehicleAreaSeat}</li>
     *                       <li>{@code VehicleAreaMirror}</li>
     *                       <li>{@link android.car.VehicleAreaWheel}</li>
     *                     </ul>
     * @param timestampNanos  Elapsed time in nanoseconds since boot
     * @param value      Value of Property
     * @hide
     */
    public CarPropertyValue(int propertyId, int areaId, long timestampNanos, T value) {
        this(new Builder(propertyId, areaId)
                .setTimestampNanos(timestampNanos)
                .setValue(value));
    }

    /**
     * Creates an instance of {@code CarPropertyValue}.
     *
     *
     * @param propertyId The property identifier, see constants in
     *                   {@link android.car.VehiclePropertyIds} for system defined property IDs.
     * @param areaId     The area identifier. Must be {@code 0} if property is
     *                   {@link android.car.VehicleAreaType#VEHICLE_AREA_TYPE_GLOBAL}. Otherwise, it
     *                   must be one or more OR'd together constants of this property's
     *                   {@link android.car.VehicleAreaType}:
     *                     <ul>
     *                       <li>{@code VehicleAreaWindow}</li>
     *                       <li>{@code VehicleAreaDoor}</li>
     *                       <li>{@link android.car.VehicleAreaSeat}</li>
     *                       <li>{@code VehicleAreaMirror}</li>
     *                       <li>{@link android.car.VehicleAreaWheel}</li>
     *                     </ul>
     * @param status           The status of the property.
     * @param timestampNanos   Elapsed time in nanoseconds since boot
     * @param value            Value of the property
     * @hide
     */
    public CarPropertyValue(int propertyId, int areaId, int status, long timestampNanos, T value) {
        this(new Builder(propertyId, areaId)
                .setSystemStatus(status)
                .setTimestampNanos(timestampNanos)
                .setValue(value));
    }

    /**
     * Creates an instance of {@code CarPropertyValue}.
     *
     * @param in Parcel to read
     * @hide
     */
    @SuppressWarnings("unchecked")
    public CarPropertyValue(Parcel in) {
        mPropertyId = in.readInt();
        mAreaId = in.readInt();
        mSystemStatus = in.readInt();
        mTimestampNanos = in.readLong();
        mValue = (RawPropertyValue<T>) in.readParcelable(RawPropertyValue.class.getClassLoader(),
                RawPropertyValue.class);
        mIsSimulationPropId = in.readBoolean();
        mVendorStatus = in.readInt();
        mHasPermissionToReadPropertyVendorStatus = false;
    }

    public static final Creator<CarPropertyValue> CREATOR = new Creator<CarPropertyValue>() {
        @Override
        public CarPropertyValue createFromParcel(Parcel in) {
            return new CarPropertyValue(in);
        }

        @Override
        public CarPropertyValue[] newArray(int size) {
            return new CarPropertyValue[size];
        }
    };

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mPropertyId);
        dest.writeInt(mAreaId);
        dest.writeInt(mSystemStatus);
        dest.writeLong(mTimestampNanos);
        dest.writeParcelable(mValue, /* parcelableFlags= */ 0);
        dest.writeBoolean(mIsSimulationPropId);
        dest.writeInt(mVendorStatus);
    }

    /**
     * Returns a {@code CarPropertyValue} same as {@code this}, but with vendor status set to 0.
     *
     * @hide
     */
    public CarPropertyValue cloneWithVendorStatusFiltered() {
        // Make a copy of input, except for the vendor status field.
        return newBuilder(this).setVendorStatus(UNSET_VENDOR_PROPERTY_STATUS).build();
    }

    /**
     * Sets that the client has the permission to call {@link getPropertyVendorStatus}.
     *
     * @hide
     */
    @TestApi
    public CarPropertyValue cloneWithPermissionToReadPropertyVendorStatus() {
        return newBuilder(this)
                .setHasPermissionToReadPropertyVendorStatus(true).build();
    }

    /**
     * Returns the property identifier.
     *
     * @return The property identifier of {@code CarPropertyValue}. See constants in
     *         {@link android.car.VehiclePropertyIds} for some system defined possible values.
     */
    public int getPropertyId() {
        return mPropertyId;
    }

    /**
     * Returns the area identifier.
     *
     * @return The area identifier of {@code CarPropertyValue}, If property is
     *         {@link android.car.VehicleAreaType#VEHICLE_AREA_TYPE_GLOBAL}, it will be {@code 0}.
     *         Otherwise, it will be on or more OR'd together constants of this property's
     *         {@link android.car.VehicleAreaType}:
     *           <ul>
     *             <li>{@code VehicleAreaWindow}</li>
     *             <li>{@code VehicleAreaDoor}</li>
     *             <li>{@link android.car.VehicleAreaSeat}</li>
     *             <li>{@code VehicleAreaMirror}</li>
     *             <li>{@link android.car.VehicleAreaWheel}</li>
     *           </ul>
     */
    public int getAreaId() {
        return mAreaId;
    }

    /**
     * Returns the property status of {@code CarPropertyValue}.
     *
     * <p>Possible return values are one of:
     *  <ul>
     *      <li><code>STATUS_AVAILABLE</code></li>
     *      <li><code>STATUS_ERROR</code></li>
     *      <li><code>STATUS_NOT_AVAILABLE_GENERAL</code></li>
     *      <li><code>STATUS_NOT_AVAILABLE_DISABLED</code> (Since Android 25Q4)</li>
     *      <li><code>STATUS_NOT_AVAILABLE_SPEED_LOW</code> (Since Android 25Q4)</li>
     *      <li><code>STATUS_NOT_AVAILABLE_SPEED_HIGH</code> (Since Android 25Q4)</li>
     *      <li><code>STATUS_NOT_AVAILABLE_POOR_VISIBILITY</code> (Since Android 25Q4)</li>
     *      <li><code>STATUS_NOT_AVAILABLE_SAFETY</code> (Since Android 25Q4)</li>
     *      <li><code>STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED</code> (Since Android 25Q4)</li>
     *  </ul>
     *
     * @return The property status of {@code CarPropertyValue}
     */
    @FlaggedApi(FLAG_CAR_PROPERTY_VALUE_PROPERTY_STATUS)
    @CarPropertyStatus
    public int getPropertyStatus() {
        // TODO(b/416768353): Check sdk version against 26Q2 here and map detailed not available
        // property status to general not available status.
        return mSystemStatus;
    }

    /**
     * Returns the vendor-specific property status.
     *
     * The meaning for the returned status code is vendor specific. It is parsed from the status
     * returned from VHAL. For example, if VHAL returns 0x00011001, 0x1001 is the system status
     * (NOT_AVAILABLE_DISABLED), 0x0001 is the vendor status.
     *
     * This must only be called for {@link CarPropertyValue} obtained through
     * {@link CarPropertyEventCallback#onChangeEvent}.
     *
     * @return The vendor status code.
     *
     * @hide
     */
    @FlaggedApi(FLAG_CAR_PROPERTY_STATUS_DETAILED_NOT_AVAILABLE)
    @SystemApi
    @RequiresPermission(Car.PERMISSION_READ_PROPERTY_VENDOR_STATUS)
    public int getPropertyVendorStatus() {
        // Note that we have already filtered out the vendor property status at the car service
        // layer if the client does not have the permission. We are checking here to throw
        // SecurityException but this is not a security enforcement. Even if the client bypass
        // this check here, the vendor property status still would be 0 if the client does not
        // have the permission.
        if (!mHasPermissionToReadPropertyVendorStatus) {
            throw new SecurityException("Client does not have the required permission: "
                    + Car.PERMISSION_READ_PROPERTY_VENDOR_STATUS
                    + " to call getPropertyVendorStatus");
        }
        return mVendorStatus;
    }

    /**
     * @return Status of {@code CarPropertyValue}
     * @deprecated Use {@link #getPropertyStatus} instead.
     */
    @Deprecated
    @CarPropertyStatus
    public int getStatus() {
        // TODO(b/416768353): Check sdk version against 26Q2 here and map detailed not available
        // property status to general not available status.
        return mSystemStatus;
    }

    /**
     * Returns the timestamp in nanoseconds at which the {@code CarPropertyValue} happened. For a
     * given car property, each new {@code CarPropertyValue} should be monotonically increasing
     * using the same time base as {@link android.os.SystemClock#elapsedRealtimeNanos()}.
     *
     * <p>NOTE: Timestamp should be synchronized with other signals from the platform (e.g.
     * {@link android.location.Location} and {@link android.hardware.SensorEvent} instances).
     * Ideally, timestamp synchronization error should be below 1 millisecond.
     */
    public long getTimestamp() {
        return mTimestampNanos;
    }

    /**
     * Returns the value for {@code CarPropertyValue}.
     *
     * <p>
     * <b>Note:</b>Caller must check the value of {@link #getPropertyStatus()}. Only use
     * {@link #getValue()} when {@link #getPropertyStatus()} is {@link #STATUS_AVAILABLE}. If not,
     * {@link #getValue()} is meaningless.
     */
    @NonNull
    public T getValue() {
        return mValue.getTypedValue();
    }

    /**
     * Gets the internal raw property value.
     *
     * @hide
     */
    public RawPropertyValue getRawPropertyValue() {
        return mValue;
    }

    /**
     * Returns whether the propertyId is Simulation Property Id.
     *
     * <p>Simulation property is a property which is used by car service and vehicle hardware but
     * is not defined in {@link android.car.VehiclePropertyIds}
     *
     * @return This will only be {@code true} if returned from
     * {@link android.car.hardware.property.CarPropertySimulationManager}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    public boolean isPropertyIdSimulationPropId() {
        if (!BuildHelper.isDebuggableBuild()) {
            throw new IllegalStateException("Build is not eng or user-debug");
        }
        return mIsSimulationPropId;
    }

    /** @hide */
    @Override
    public String toString() {
        String propertyIdToString = VehiclePropertyIds.toString(mPropertyId);
        if (Flags.carPropertySimulation()) {
            if (isPropertyIdSimulationPropId()) {
                propertyIdToString = "0x" + Integer.toHexString(mPropertyId);
            }
        }
        String propertyValueString = "CarPropertyValue{"
                + "mPropertyId=0x" + toHexString(mPropertyId)
                + ", propertyName=" + propertyIdToString
                + ", mAreaId=" + toAreaIdString(mPropertyId, mAreaId)
                + ", mSystemStatus="
                + constantToString(CarPropertyValue.class, "STATUS_", mSystemStatus)
                + ", mVendorStatus=" + mVendorStatus
                + ", mTimestampNanos=" + mTimestampNanos
                + ", mValue=" + mValue
                + ", mHasPermissionToReadPropertyVendorStatus="
                + mHasPermissionToReadPropertyVendorStatus;
        if (Flags.carPropertySimulation()) {
            if (isPropertyIdSimulationPropId()) {
                return propertyValueString
                        + ", mIsSimulationPropId=" + mIsSimulationPropId
                        + '}';
            }
        }
        return propertyValueString + '}';
    }

    /** Generates hash code for this instance. */
    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{
                mPropertyId, mAreaId, mSystemStatus, mTimestampNanos, mValue,
                mIsSimulationPropId, mVendorStatus, mHasPermissionToReadPropertyVendorStatus});
    }

    /** Checks equality with passed {@code object}. */
    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof CarPropertyValue<?>)) {
            return false;
        }
        CarPropertyValue<?> carPropertyValue = (CarPropertyValue<?>) object;
        return mPropertyId == carPropertyValue.mPropertyId && mAreaId == carPropertyValue.mAreaId
                && mSystemStatus == carPropertyValue.mSystemStatus
                && mTimestampNanos == carPropertyValue.mTimestampNanos
                && Objects.equals(mValue, carPropertyValue.mValue)
                && mIsSimulationPropId == carPropertyValue.mIsSimulationPropId
                && mVendorStatus == carPropertyValue.mVendorStatus
                && mHasPermissionToReadPropertyVendorStatus
                        == carPropertyValue.mHasPermissionToReadPropertyVendorStatus;
    }
}
