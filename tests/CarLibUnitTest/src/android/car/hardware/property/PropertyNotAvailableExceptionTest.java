/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
public final class PropertyNotAvailableExceptionTest {
    private static final int PROPERTY_ID = 1234;
    private static final int AREA_ID = 5678;
    private static final int DETAILED_ERROR_CODE = 1;
    private static final int VENDOR_ERROR_CODE = 10;

    @Test
    public void testGetDetailedErrorCode() {
        PropertyNotAvailableException exception =
                new PropertyNotAvailableException(
                        PROPERTY_ID,
                        AREA_ID,
                        DETAILED_ERROR_CODE,
                        VENDOR_ERROR_CODE,
                        /* canReadVendorErrorCode= */ false);

        assertThat(exception.getDetailedErrorCode()).isEqualTo(DETAILED_ERROR_CODE);
    }

    @Test
    public void testGetVendorErrorCode_cannotRead() {
        PropertyNotAvailableException exception =
                new PropertyNotAvailableException(
                        PROPERTY_ID,
                        AREA_ID,
                        VENDOR_ERROR_CODE,
                        /* canReadVendorErrorCode= */ false);

        assertThrows(SecurityException.class, exception::getVendorErrorCode);
    }

    @Test
    public void testGetVendorErrorCode_canRead() {
        PropertyNotAvailableException exception =
                new PropertyNotAvailableException(
                        PROPERTY_ID,
                        AREA_ID,
                        VENDOR_ERROR_CODE,
                        /* canReadVendorErrorCode= */ true);

        assertThat(exception.getVendorErrorCode()).isEqualTo(VENDOR_ERROR_CODE);
    }
}
