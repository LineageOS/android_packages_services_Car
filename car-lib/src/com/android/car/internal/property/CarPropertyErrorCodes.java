/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.car.internal.property;

import static android.car.hardware.property.VehicleHalStatusCode.VEHICLE_HAL_STATUS_CODES;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.car.feature.Flags;
import android.car.hardware.property.CarInternalErrorException;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyAsyncErrorCode;
import android.car.hardware.property.DetailedErrorCode;
import android.car.hardware.property.DetailedErrorCode.DetailedErrorCodeInt;
import android.car.hardware.property.PropertyAccessDeniedSecurityException;
import android.car.hardware.property.PropertyNotAvailableAndRetryException;
import android.car.hardware.property.PropertyNotAvailableErrorCode;
import android.car.hardware.property.PropertyNotAvailableErrorCode.PropertyNotAvailableErrorCodeInt;
import android.car.hardware.property.PropertyNotAvailableException;
import android.car.hardware.property.VehicleHalStatusCode;
import android.car.hardware.property.VehicleHalStatusCode.VehicleHalStatusCodeInt;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.car.internal.util.AnnotationValidations;
import com.android.car.internal.util.DataClass;
import com.android.internal.annotations.VisibleForTesting;

import java.util.StringJoiner;

/**
 * Stores the various error codes for vehicle properties as they get passed up
 * the stack.
 */
@DataClass(genConstructor = false, genSetters = false, genGetters = false)
public final class CarPropertyErrorCodes implements Parcelable {

    private static final String TAG = "CarPropertyErrorCodes";

    private static final SparseIntArray DETAILED_ERROR_CODE_BY_STATUS =
            new SparseIntArray();
    static {
        DETAILED_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED,
                DetailedErrorCode.NOT_AVAILABLE_DISABLED);
        DETAILED_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW,
                DetailedErrorCode.NOT_AVAILABLE_SPEED_LOW);
        DETAILED_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH,
                DetailedErrorCode.NOT_AVAILABLE_SPEED_HIGH);
        DETAILED_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_POOR_VISIBILITY,
                DetailedErrorCode.NOT_AVAILABLE_POOR_VISIBILITY);
        DETAILED_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY,
                DetailedErrorCode.NOT_AVAILABLE_SAFETY);
        DETAILED_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED,
                DetailedErrorCode.NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED);
    }

    private static final SparseIntArray PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS =
            new SparseIntArray();
    static {
        PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE,
                PropertyNotAvailableErrorCode.NOT_AVAILABLE);
        PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED,
                PropertyNotAvailableErrorCode.NOT_AVAILABLE_DISABLED);
        PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW,
                PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_LOW);
        PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH,
                PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_HIGH);
        PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_POOR_VISIBILITY,
                PropertyNotAvailableErrorCode.NOT_AVAILABLE_POOR_VISIBILITY);
        PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY,
                PropertyNotAvailableErrorCode.NOT_AVAILABLE_SAFETY);
        PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED,
                PropertyNotAvailableErrorCode.NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED);
    }

    /**
     * Status indicating no error.
     *
     * <p>This is not exposed to the client as this will be used only for deciding
     * {@link GetPropertyCallback#onSuccess} or {@link GetPropertyCallback#onFailure} is called.
     */
    public static final int STATUS_OK = 0;
    public static final int STATUS_TRY_AGAIN = -1;

    private static final int SYSTEM_ERROR_CODE_MASK = 0xffff;
    private static final int VENDOR_ERROR_CODE_SHIFT = 16;

    /**
     * CarPropertyErrorCodes with no errors.
     */
    public static CarPropertyErrorCodes STATUS_OK_NO_ERROR =
            new CarPropertyErrorCodes(STATUS_OK, /* vendorErrorCode= */ 0, /* systemErrorCode */ 0);

    /**
     * CarPropertyErrorCodes generated internally from car service with general not_available error.
     */
    public static CarPropertyErrorCodes ERROR_CODES_NOT_AVAILABLE =
            new CarPropertyErrorCodes(CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE,
                    /* vendorErrorCode= */ 0, /* systemErrorCode */ 0);

    /**
     * CarPropertyErrorCodes generated internally from car service with try_again error.
     */
    public static CarPropertyErrorCodes ERROR_CODES_TRY_AGAIN =
            new CarPropertyErrorCodes(STATUS_TRY_AGAIN, /* vendorErrorCode= */ 0,
                    /* systemErrorCode */ 0);

    /**
     * CarPropertyErrorCodes generated internally from car service with general internal error.
     */
    public static CarPropertyErrorCodes ERROR_CODES_INTERNAL =
            new CarPropertyErrorCodes(CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR,
                    /* vendorErrorCode= */ 0, /* systemErrorCode */ 0);

    /**
     * CarPropertyErrorCodes generated internally from car service with timeout error.
     */
    public static CarPropertyErrorCodes ERROR_CODES_TIMEOUT =
            new CarPropertyErrorCodes(CarPropertyManager.STATUS_ERROR_TIMEOUT,
                    /* vendorErrorCode= */ 0, /* systemErrorCode */ 0);


    /**
     * Creates a {@link CarPropertyErrorCodes} structure from a VHAL status code.
     */
    public static CarPropertyErrorCodes createFromVhalStatusCode(int vhalStatusCode) {
        @VehicleHalStatusCodeInt int systemErrorCode = getVhalSystemErrorCode(vhalStatusCode);
        @CarPropMgrErrorCode int carPropertyManagerErrorCode = STATUS_OK;

        if (isNotAvailableVehicleHalStatusCode(systemErrorCode)) {
            carPropertyManagerErrorCode = CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE;
        } else {
            switch (systemErrorCode) {
                case VehicleHalStatusCode.STATUS_OK:
                    break;
                case VehicleHalStatusCode.STATUS_TRY_AGAIN:
                    carPropertyManagerErrorCode = STATUS_TRY_AGAIN;
                    break;
                default:
                    carPropertyManagerErrorCode = CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR;
                    break;
            }
        }

        CarPropertyErrorCodes errorCodes = new CarPropertyErrorCodes(
                carPropertyManagerErrorCode, getVhalVendorErrorCode(vhalStatusCode),
                systemErrorCode);

        return errorCodes;
    }

    /** Creates a backwards compatible {@link CarPropertyErrorCodes} structure. */
    public CarPropertyErrorCodes cloneWithAppTargetSdk(int appTargetSdk) {
        int systemErrorCodeCompat = mSystemErrorCode;
        if (systemErrorCodeCompat
                        == VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED
                && (appTargetSdk < Build.VERSION_CODES.CINNAMON_BUN
                        || !Flags.carPropertyStatusDetailedNotAvailable())) {
            systemErrorCodeCompat = VehicleHalStatusCode.STATUS_NOT_AVAILABLE;
        }
        return new CarPropertyErrorCodes(
                mCarPropertyManagerErrorCode, mVendorErrorCode, systemErrorCodeCompat);
    }

    /**
     * This is same as {@link CarPropertyAsyncErrorCode} except that it contains
     * {@code STATUS_TRY_AGAIN}.
     */
    @IntDef(prefix = {"STATUS_"}, value = {
            STATUS_OK,
            CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR,
            CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE,
            CarPropertyManager.STATUS_ERROR_TIMEOUT,
            STATUS_TRY_AGAIN
    })
    public @interface CarPropMgrErrorCode {}

    // This is the internal tracking error code. This should be checked first before checking
    // vendor or system error code.
    private @CarPropMgrErrorCode int mCarPropertyManagerErrorCode;
    // If the error code comes from VHAL, this contains the optional vendor-specific error code.
    private int mVendorErrorCode;
    // If the error code comes from VHAL, this contains the VHAL system-defined error code. This is
    // set to 0 if the error is generated internally from car service.
    private int mSystemErrorCode;

    /**
     * Create an instance of CarPropertyErrorCodes given a car property manager error code, a vendor
     * error code, and a system error code.
     */
    private CarPropertyErrorCodes(@CarPropMgrErrorCode int carPropertyManagerErrorCode,
            int vendorErrorCode, int systemErrorCode) {
        mCarPropertyManagerErrorCode = carPropertyManagerErrorCode;
        mVendorErrorCode = vendorErrorCode;
        mSystemErrorCode = systemErrorCode;
    }

    /**
     * Whether status is okay (there is no error).
     */
    public boolean isOkay() {
        return mCarPropertyManagerErrorCode == STATUS_OK;
    }

    /**
     * Whether the error is try again.
     */
    public boolean isTryAgain() {
        return mCarPropertyManagerErrorCode == STATUS_TRY_AGAIN;
    }

    /**
     * Get the vendor specified error code to allow for more detailed error codes returned from
     * VHAL.
     *
     * This will be 0 if the error is generated internally from car service.
     *
     * A vendor error code will have a range from 0x0000 to 0xffff.
     *
     * @return the vendor error code if it is set, otherwise 0.
     */
    public int getVendorErrorCode() {
        return mVendorErrorCode;
    }

    /**
     * Get the system error code returned from VHAL.
     *
     * This will be 0 if the error is generated internally from car service.
     *
     * A system error code will have a range from 0x0000 to 0xffff.
     */
    public @VehicleHalStatusCodeInt int getSystemErrorCode() {
        return mSystemErrorCode;
    }

    /**
     * Converts this to one of {@link CarPropertyAsyncErrorCode}.
     *
     * @return the async error code
     * @throws IllegalArgumentException if an invalid error code is passed in.
     */
    public @CarPropertyAsyncErrorCode int toCarPropertyAsyncErrorCode() {
        switch (mCarPropertyManagerErrorCode) {
            case STATUS_OK: // Fallthrough
            case CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR: // Fallthrough
            case CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE: // Fallthrough
            case CarPropertyManager.STATUS_ERROR_TIMEOUT: // Fallthrough
                return mCarPropertyManagerErrorCode;
            case STATUS_TRY_AGAIN: // Fallthrough
            default:
                throw new IllegalArgumentException(
                        "Invalid error code: " + mCarPropertyManagerErrorCode);
        }
    }

    /**
     * Converts this to one of {@link DetailedErrorCodeInt}.
     *
     * @return the detailed error code if available, otherwise set to 0.
     * @throws IllegalArgumentException if an invalid error code is passed in.
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_DETAILED_ERROR_CODES)
    public @DetailedErrorCodeInt int toDetailedErrorCode() {
        if (!VEHICLE_HAL_STATUS_CODES.contains(mSystemErrorCode)) {
            throw new IllegalArgumentException(
                        "Invalid VHAL system error code: " + mSystemErrorCode);
        }
        return DETAILED_ERROR_CODE_BY_STATUS.get(mSystemErrorCode,
                /* valueIfKeyNotFound= */ DetailedErrorCode.NO_DETAILED_ERROR_CODE);
    }

    /**
     * Checks the error code and throws corresponding exception.
     *
     * @throws PropertyNotAvailableException If the error code is one of NOT_AVAILABLE error.
     * @throws CarInternalErrorException If the error code is INTERNAL_ERROR.
     */
    public void checkAndMaybeThrowException(int propertyId, int areaId) {
        if (isOkay()) {
            return;
        }

        if (mCarPropertyManagerErrorCode == CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE) {
            throw new PropertyNotAvailableException(propertyId, areaId,
                    getPropertyNotAvailableErrorCodeFromStatusCode(mSystemErrorCode),
                    mVendorErrorCode);
        }

        switch (mSystemErrorCode) {
            case VehicleHalStatusCode.STATUS_TRY_AGAIN:
                // Vendor error code is ignored for STATUS_TRY_AGAIN error
                throw new PropertyNotAvailableAndRetryException(propertyId, areaId);
            case VehicleHalStatusCode.STATUS_ACCESS_DENIED:
                // Vendor error code is ignored for STATUS_ACCESS_DENIED error
                throw new PropertyAccessDeniedSecurityException(propertyId, areaId);
            case VehicleHalStatusCode.STATUS_INTERNAL_ERROR:
                throw new CarInternalErrorException(propertyId, areaId, mVendorErrorCode);
            default:
                Slog.e(TAG, "Invalid VAHL error code: " + mSystemErrorCode
                        + ", convert to CarInternalErrorException");
                throw new CarInternalErrorException(propertyId, areaId);
        }
    }

    /**
     * Returns {@code true} if {@code vehicleHalStatusCode} is one of the not available
     * {@link VehicleHalStatusCode} values}. Otherwise returns {@code false}.
     */
    public static boolean isNotAvailableVehicleHalStatusCode(
            @VehicleHalStatusCodeInt int vehicleHalStatusCode) {
        return PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS
                .indexOfKey(vehicleHalStatusCode) >= 0;
    }

    /**
     * Convert {@link VehicleHalStatusCode} into public {@link PropertyNotAvailableErrorCode}
     * equivalents.
     *
     * @throws IllegalArgumentException if an invalid status code is passed in.
     * @hide
     */
    @VisibleForTesting
    public static @PropertyNotAvailableErrorCodeInt int
            getPropertyNotAvailableErrorCodeFromStatusCode(int statusCode) {
        int errorCodeIndex = PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS.indexOfKey(statusCode);
        if (errorCodeIndex < 0) {
            throw new IllegalArgumentException(
                    "Not an not_available error status code: " + statusCode);
        }
        return PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS.valueAt(errorCodeIndex);
    }

    /**
     * Returns the system error code contained in the error code returned from VHAL.
     */
    @SuppressLint("WrongConstant")
    public static @VehicleHalStatusCodeInt int getVhalSystemErrorCode(int vhalErrorCode) {
        return vhalErrorCode & SYSTEM_ERROR_CODE_MASK;
    }

    /**
     * Returns the vendor error code contained in the error code returned from VHAL.
     */
    public static int getVhalVendorErrorCode(int vhalErrorCode) {
        return vhalErrorCode >>> VENDOR_ERROR_CODE_SHIFT;
    }

    @Override
    public String toString() {
        return carPropertyErrorCodestoString(this);
    }

    /**
     * Returns a string representation of a {@code CarPropertyErrorCodes}.
     */
    @NonNull
    public static String carPropertyErrorCodestoString(
            CarPropertyErrorCodes carPropertyErrorCodes) {
        var sj = new StringJoiner(", ", "CarPropertyErrorCodes{", "}");
        sj.add("cpmErrorCode: " + carPropertyErrorCodes.mCarPropertyManagerErrorCode);
        sj.add("vendorErrorCode: " + carPropertyErrorCodes.getVendorErrorCode());
        sj.add("systemErrorCode: " + carPropertyErrorCodes.getSystemErrorCode());
        return sj.toString();
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/com/android/car/internal/property/CarPropertyErrorCodes.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @IntDef(prefix = "STATUS_", value = {
        STATUS_OK,
        STATUS_TRY_AGAIN
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface Status {}

    @DataClass.Generated.Member
    public static String statusToString(@Status int value) {
        switch (value) {
            case STATUS_OK:
                    return "STATUS_OK";
            case STATUS_TRY_AGAIN:
                    return "STATUS_TRY_AGAIN";
            default: return Integer.toHexString(value);
        }
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeInt(mCarPropertyManagerErrorCode);
        dest.writeInt(mVendorErrorCode);
        dest.writeInt(mSystemErrorCode);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ CarPropertyErrorCodes(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int carPropertyManagerErrorCode = in.readInt();
        int vendorErrorCode = in.readInt();
        int systemErrorCode = in.readInt();

        this.mCarPropertyManagerErrorCode = carPropertyManagerErrorCode;
        AnnotationValidations.validate(
                CarPropMgrErrorCode.class, null, mCarPropertyManagerErrorCode);
        this.mVendorErrorCode = vendorErrorCode;
        this.mSystemErrorCode = systemErrorCode;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<CarPropertyErrorCodes> CREATOR
            = new Parcelable.Creator<CarPropertyErrorCodes>() {
        @Override
        public CarPropertyErrorCodes[] newArray(int size) {
            return new CarPropertyErrorCodes[size];
        }

        @Override
        public CarPropertyErrorCodes createFromParcel(@NonNull Parcel in) {
            return new CarPropertyErrorCodes(in);
        }
    };

    @DataClass.Generated(
            time = 1758832252152L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/com/android/car/internal/property/CarPropertyErrorCodes.java",
            inputSignatures = "private static final  java.lang.String TAG\nprivate static final  android.util.SparseIntArray DETAILED_ERROR_CODE_BY_STATUS\nprivate static final  android.util.SparseIntArray PROP_NOT_AVAILABLE_ERROR_CODE_BY_STATUS\npublic static final  int STATUS_OK\npublic static final  int STATUS_TRY_AGAIN\nprivate static final  int SYSTEM_ERROR_CODE_MASK\nprivate static final  int VENDOR_ERROR_CODE_SHIFT\npublic static  com.android.car.internal.property.CarPropertyErrorCodes STATUS_OK_NO_ERROR\npublic static  com.android.car.internal.property.CarPropertyErrorCodes ERROR_CODES_NOT_AVAILABLE\npublic static  com.android.car.internal.property.CarPropertyErrorCodes ERROR_CODES_TRY_AGAIN\npublic static  com.android.car.internal.property.CarPropertyErrorCodes ERROR_CODES_INTERNAL\npublic static  com.android.car.internal.property.CarPropertyErrorCodes ERROR_CODES_TIMEOUT\nprivate @com.android.car.internal.property.CarPropertyErrorCodes.CarPropMgrErrorCode int mCarPropertyManagerErrorCode\nprivate  int mVendorErrorCode\nprivate  int mSystemErrorCode\npublic static  com.android.car.internal.property.CarPropertyErrorCodes createFromVhalStatusCode(int)\npublic  com.android.car.internal.property.CarPropertyErrorCodes cloneWithAppTargetSdk(int)\npublic  boolean isOkay()\npublic  boolean isTryAgain()\npublic  int getVendorErrorCode()\npublic @android.car.hardware.property.VehicleHalStatusCode.VehicleHalStatusCodeInt int getSystemErrorCode()\npublic @android.car.hardware.property.CarPropertyManager.CarPropertyAsyncErrorCode int toCarPropertyAsyncErrorCode()\npublic @android.annotation.FlaggedApi @android.car.hardware.property.DetailedErrorCode.DetailedErrorCodeInt int toDetailedErrorCode()\npublic  void checkAndMaybeThrowException(int,int)\npublic static  boolean isNotAvailableVehicleHalStatusCode(int)\npublic static @com.android.internal.annotations.VisibleForTesting @android.car.hardware.property.PropertyNotAvailableErrorCode.PropertyNotAvailableErrorCodeInt int getPropertyNotAvailableErrorCodeFromStatusCode(int)\npublic static @android.annotation.SuppressLint @android.car.hardware.property.VehicleHalStatusCode.VehicleHalStatusCodeInt int getVhalSystemErrorCode(int)\npublic static  int getVhalVendorErrorCode(int)\npublic @java.lang.Override java.lang.String toString()\npublic static @android.annotation.NonNull java.lang.String carPropertyErrorCodestoString(com.android.car.internal.property.CarPropertyErrorCodes)\nclass CarPropertyErrorCodes extends java.lang.Object implements [android.os.Parcelable]\n@com.android.car.internal.util.DataClass(genConstructor=false, genSetters=false, genGetters=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
