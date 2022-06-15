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

package android.car.apitest;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.car.ApiVersion;

import org.junit.Test;

public final class ApiVersionTest {

    @Test
    public void testGetters() {
        ApiVersion version = new ApiVersion(42, 108);

        assertWithMessage("%s.getMajorVersion()", version)
                .that(version.getMajorVersion()).isEqualTo(42);
        assertWithMessage("%s.getMinorVersion()", version)
                .that(version.getMinorVersion()).isEqualTo(108);
    }

    @Test
    public void testToString() {
        String string = new ApiVersion(42, 108).toString();

        assertThat(string).contains("major=42");
        assertThat(string).contains("minor=108");
    }

    @Test
    public void testAtLeast_major() {
        ApiVersion version = new ApiVersion(42, 108);

        assertWithMessage("%s.atLeast(41)", version)
                .that(version.isAtLeast(41)).isTrue();
        assertWithMessage("%s.atLeast(42)", version)
                .that(version.isAtLeast(42)).isTrue();
        assertWithMessage("%s.atLeast(43)", version)
                .that(version.isAtLeast(43)).isFalse();
    }

    @Test
    public void testAtLeast_majorAndMinor() {
        ApiVersion version = new ApiVersion(42, 108);

        assertWithMessage("%s.atLeast(41, 109)", version)
                .that(version.isAtLeast(41, 109)).isTrue();
        assertWithMessage("%s.atLeast(42, 107)", version)
                .that(version.isAtLeast(42, 108)).isTrue();
        assertWithMessage("%s.atLeast(42, 108)", version)
                .that(version.isAtLeast(42, 108)).isTrue();

        assertWithMessage("%s.atLeast(42, 109)", version)
                .that(version.isAtLeast(42, 109)).isFalse();
        assertWithMessage("%s.atLeast(43, 0)", version)
                .that(version.isAtLeast(43, 0)).isFalse();
    }
}
