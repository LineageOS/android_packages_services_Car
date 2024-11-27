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

package android.car.hardware.property;

import static com.android.car.internal.property.CarPropertyErrorCodes.createFromVhalStatusCode;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.car.feature.Flags;
import android.car.test.AbstractExpectableTestCase;
import android.hardware.automotive.vehicle.StatusCode;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.SparseIntArray;

import com.android.car.internal.property.CarPropertyErrorCodes;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

@EnableFlags(Flags.FLAG_CAR_PROPERTY_DETAILED_ERROR_CODES)
public final class CarPropertyErrorCodesUnitTest extends AbstractExpectableTestCase {

    @ClassRule public static final SetFlagsRule.ClassRule mClassRule = new SetFlagsRule.ClassRule();
    @Rule public final SetFlagsRule mSetFlagsRule = mClassRule.createSetFlagsRule();

    private static final int NO_ERROR = 0;
    private static final int SYSTEM_ERROR_CODE = 0x0123;
    private static final int VENDOR_ERROR_CODE = 0x1234;
    private static final int VENDOR_ERROR_CODE_SHIFT = 16;
    private static final int COMBINED_ERROR_CODE = 0x12340123;

    @Test
    public void testCarPropertyErrorCodesStatusOkNoErrors() throws Exception {
        CarPropertyErrorCodes carPropertyErrorCodes =
                CarPropertyErrorCodes.STATUS_OK_NO_ERROR;

        assertThat(carPropertyErrorCodes.isOkay()).isTrue();
        assertThat(carPropertyErrorCodes.getVendorErrorCode())
                .isEqualTo(NO_ERROR);
        assertThat(carPropertyErrorCodes.getSystemErrorCode())
                .isEqualTo(NO_ERROR);
    }

    @Test
    public void testCarPropertyErrorCodesStatusInternalError() throws Exception {
        CarPropertyErrorCodes carPropertyErrorCodes =
                CarPropertyErrorCodes.ERROR_CODES_INTERNAL;

        assertThat(carPropertyErrorCodes.isOkay()).isFalse();
        assertThat(carPropertyErrorCodes.getVendorErrorCode())
                .isEqualTo(NO_ERROR);
        assertThat(carPropertyErrorCodes.getSystemErrorCode())
                .isEqualTo(NO_ERROR);
    }

    @Test
    public void testCarPropertyErrorCodesStatusNotAvailable() throws Exception {
        CarPropertyErrorCodes carPropertyErrorCodes =
                CarPropertyErrorCodes.ERROR_CODES_NOT_AVAILABLE;

        assertThat(carPropertyErrorCodes.isOkay()).isFalse();
        assertThat(carPropertyErrorCodes.getVendorErrorCode())
                .isEqualTo(NO_ERROR);
        assertThat(carPropertyErrorCodes.getSystemErrorCode())
                .isEqualTo(NO_ERROR);
    }

    @Test
    public void testCarPropertyErrorCodesStatusNotAvailableSpeedLow() throws Exception {
        CarPropertyErrorCodes carPropertyErrorCodes =
                createFromVhalStatusCode(StatusCode.NOT_AVAILABLE_SPEED_LOW);

        assertThat(carPropertyErrorCodes.isOkay()).isFalse();
        assertThat(carPropertyErrorCodes.getVendorErrorCode())
                .isEqualTo(NO_ERROR);
        assertThat(carPropertyErrorCodes.getSystemErrorCode())
                .isEqualTo(StatusCode.NOT_AVAILABLE_SPEED_LOW);
    }

    @Test
    public void testCarPropertyErrorCodesStatusNotAvailableVendorError() throws Exception {
        int vhalStatusCode = VehicleHalStatusCode.STATUS_NOT_AVAILABLE | (VENDOR_ERROR_CODE << 16);
        CarPropertyErrorCodes carPropertyErrorCodes = createFromVhalStatusCode(vhalStatusCode);

        assertThat(carPropertyErrorCodes.toCarPropertyAsyncErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        assertThat(carPropertyErrorCodes.getVendorErrorCode())
                .isEqualTo(VENDOR_ERROR_CODE);
        assertThat(carPropertyErrorCodes.getSystemErrorCode())
                .isEqualTo(VehicleHalStatusCode.STATUS_NOT_AVAILABLE);
    }

    @Test
    public void testConvertHalToCarPropertyManagerErrorStatusOK() throws Exception {
        CarPropertyErrorCodes carPropertyErrorCodes =
                createFromVhalStatusCode(StatusCode.OK);

        assertThat(carPropertyErrorCodes.isOkay()).isTrue();
        assertThat(carPropertyErrorCodes.getVendorErrorCode()).isEqualTo(0);
        assertThat(carPropertyErrorCodes.getSystemErrorCode())
                .isEqualTo(CarPropertyErrorCodes.STATUS_OK);
    }

    @Test
    public void testConvertHalToCarPropertyManagerErrorStatus() throws Exception {
        SparseIntArray mgrErrorCodeByVhalStatusCode = new SparseIntArray();
        mgrErrorCodeByVhalStatusCode.put(StatusCode.NOT_AVAILABLE,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        mgrErrorCodeByVhalStatusCode.put(StatusCode.NOT_AVAILABLE_DISABLED,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        mgrErrorCodeByVhalStatusCode.put(StatusCode.NOT_AVAILABLE_SPEED_LOW,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        mgrErrorCodeByVhalStatusCode.put(StatusCode.NOT_AVAILABLE_SPEED_HIGH,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        mgrErrorCodeByVhalStatusCode.put(
                StatusCode.NOT_AVAILABLE_POOR_VISIBILITY,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        mgrErrorCodeByVhalStatusCode.put(StatusCode.NOT_AVAILABLE_SAFETY,
                CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE);
        mgrErrorCodeByVhalStatusCode.put(StatusCode.INTERNAL_ERROR,
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);

        for (int i = 0; i < mgrErrorCodeByVhalStatusCode.size(); i++) {
            int statusCode = mgrErrorCodeByVhalStatusCode.keyAt(i);
            int carPropMgrError = mgrErrorCodeByVhalStatusCode.valueAt(i);
            CarPropertyErrorCodes carPropertyErrorCodes =
                    createFromVhalStatusCode(
                            statusCode | (VENDOR_ERROR_CODE << VENDOR_ERROR_CODE_SHIFT));

            assertThat(carPropertyErrorCodes.toCarPropertyAsyncErrorCode())
                    .isEqualTo(carPropMgrError);
            assertThat(carPropertyErrorCodes.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
            assertThat(carPropertyErrorCodes.getSystemErrorCode()).isEqualTo(statusCode);
        }
    }

    @Test
    public void testToDetailedErrorCode() throws Exception {
        expectThat(createFromVhalStatusCode(StatusCode.OK).toDetailedErrorCode()).isEqualTo(
                DetailedErrorCode.NO_DETAILED_ERROR_CODE);
        expectThat(createFromVhalStatusCode(StatusCode.TRY_AGAIN).toDetailedErrorCode()).isEqualTo(
                DetailedErrorCode.NO_DETAILED_ERROR_CODE);
        expectThat(createFromVhalStatusCode(StatusCode.INVALID_ARG).toDetailedErrorCode())
                .isEqualTo(DetailedErrorCode.NO_DETAILED_ERROR_CODE);
        expectThat(createFromVhalStatusCode(StatusCode.NOT_AVAILABLE).toDetailedErrorCode())
                .isEqualTo(DetailedErrorCode.NO_DETAILED_ERROR_CODE);
        expectThat(createFromVhalStatusCode(StatusCode.ACCESS_DENIED).toDetailedErrorCode())
                .isEqualTo(DetailedErrorCode.NO_DETAILED_ERROR_CODE);
        expectThat(createFromVhalStatusCode(StatusCode.INTERNAL_ERROR).toDetailedErrorCode())
                .isEqualTo(DetailedErrorCode.NO_DETAILED_ERROR_CODE);
        expectThat(createFromVhalStatusCode(StatusCode.NOT_AVAILABLE_DISABLED)
                .toDetailedErrorCode()).isEqualTo(DetailedErrorCode.NOT_AVAILABLE_DISABLED);
        expectThat(createFromVhalStatusCode(StatusCode.NOT_AVAILABLE_SPEED_LOW)
                .toDetailedErrorCode()).isEqualTo(DetailedErrorCode.NOT_AVAILABLE_SPEED_LOW);
        expectThat(createFromVhalStatusCode(StatusCode.NOT_AVAILABLE_SPEED_HIGH)
                .toDetailedErrorCode()).isEqualTo(DetailedErrorCode.NOT_AVAILABLE_SPEED_HIGH);
        expectThat(createFromVhalStatusCode(StatusCode.NOT_AVAILABLE_POOR_VISIBILITY)
                .toDetailedErrorCode()).isEqualTo(DetailedErrorCode.NOT_AVAILABLE_POOR_VISIBILITY);
        expectThat(createFromVhalStatusCode(StatusCode.NOT_AVAILABLE_SAFETY).toDetailedErrorCode())
                .isEqualTo(DetailedErrorCode.NOT_AVAILABLE_SAFETY);
    }

    @Test
    public void testToDetailedErrorCode_invalidErrorCode() throws Exception {
        int invalidErrorCode = 0xfffe;

        assertThrows(IllegalArgumentException.class, () -> {
            createFromVhalStatusCode(invalidErrorCode).toDetailedErrorCode();
        });
    }

    @Test
    public void testGetVhalSystemErrorcode() {
        assertThat(CarPropertyErrorCodes.getVhalSystemErrorCode(COMBINED_ERROR_CODE)).isEqualTo(
                SYSTEM_ERROR_CODE);
    }

    @Test
    public void testGetVhalVendorErrorCode() {
        assertThat(CarPropertyErrorCodes.getVhalVendorErrorCode(COMBINED_ERROR_CODE)).isEqualTo(
                VENDOR_ERROR_CODE);
    }
}
