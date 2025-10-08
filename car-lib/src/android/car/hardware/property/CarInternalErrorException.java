/*
 * Copyright (C) 2020 The Android Open Source Project
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


import static com.android.car.internal.util.DebugUtils.toAreaIdString;

import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.car.Car;
import android.car.VehiclePropertyIds;

/**
 * Exception thrown when something unexpected happened in cars.
 */
public class CarInternalErrorException extends RuntimeException {
    private static final int VENDOR_ERROR_CODE_SUCCESS = 0;

    private final int mVendorErrorCode;
    private final boolean mCanReadVendorErrorCode;

    /** @hide */
    public CarInternalErrorException(
            int propertyId, int areaId, boolean canReadVendorErrorCode) {
        this(propertyId, areaId, VENDOR_ERROR_CODE_SUCCESS, canReadVendorErrorCode);
    }

    /** @hide */
    public CarInternalErrorException(
            int propertyId,
            int areaId,
            int vendorErrorCode,
            boolean canReadVendorErrorCode) {
        super("Property ID: " + VehiclePropertyIds.toString(propertyId) + " area ID: "
                + toAreaIdString(propertyId, areaId) + " - raised an internal error in cars with "
                + "vendor error code: " + vendorErrorCode);
        mVendorErrorCode = vendorErrorCode;
        mCanReadVendorErrorCode = canReadVendorErrorCode;
    }

    /**
     * Gets the vendor error codes to allow for more detailed error codes.
     *
     * @return Vendor error code if it is set, otherwise 0. A vendor error code will have a range
     * from 0x0000 to 0xffff.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_READ_PROPERTY_VENDOR_ERROR_CODE)
    public int getVendorErrorCode() {
        // Note that we have already filtered out the vendor error code at the car service
        // layer if the client does not have the permission. We are checking here to throw
        // SecurityException but this is not a security enforcement. Even if the client bypass
        // this check here, the vendor error code still would be 0 if the client does not
        // have the permission.
        if (!mCanReadVendorErrorCode) {
            throw new SecurityException(
                    "Client does not have the required permission: "
                            + Car.PERMISSION_READ_PROPERTY_VENDOR_ERROR_CODE
                            + " to call getVendorErrorCode");
        }
        return mVendorErrorCode;
    }
}
