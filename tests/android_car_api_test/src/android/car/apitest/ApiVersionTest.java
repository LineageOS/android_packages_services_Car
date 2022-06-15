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

import static org.junit.Assert.assertThrows;

import android.car.ApiVersion;
import android.car.CarApiVersion;
import android.car.PlatformApiVersion;

//TODO(b/236153976): add when supported
//import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public final class ApiVersionTest {

    private final ApiVersionFactory<?> mFactory;

    public ApiVersionTest(ApiVersionFactory<?> factory) {
        mFactory = factory;
    }

    @Test
    public void testGetters() {
        ApiVersion<?> version = version(42, 108);

        assertWithMessage("%s.getMajorVersion()", version)
                .that(version.getMajorVersion()).isEqualTo(42);
        assertWithMessage("%s.getMinorVersion()", version)
                .that(version.getMinorVersion()).isEqualTo(108);
    }

    @Test
    public void testGetters_majorOnlyConstructor() {
        ApiVersion<?> version = version(42);

        assertWithMessage("%s.getMajorVersion()", version)
                .that(version.getMajorVersion()).isEqualTo(42);
        assertWithMessage("%s.getMinorVersion()", version)
                .that(version.getMinorVersion()).isEqualTo(0);
    }

    @Test
    public void testToString() {
        String string = version(42, 108).toString();

        assertThat(string).contains("major=42");
        assertThat(string).contains("minor=108");
    }

    @Test
    public void testAtLeast_null() {
        assertThrows(NullPointerException.class, () -> version(42, 108).isAtLeast(null));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void testAtLeast_major() {
        ApiVersion version = version(42, 108);

        assertWithMessage("%s.atLeast(41)", version)
                .that(version.isAtLeast(version(41))).isTrue();
        assertWithMessage("%s.atLeast(42)", version)
                .that(version.isAtLeast(version(42))).isTrue();
        assertWithMessage("%s.atLeast(43)", version)
                .that(version.isAtLeast(version(43))).isFalse();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void testAtLeast_majorAndMinor() {
        ApiVersion version = version(42, 108);

        assertWithMessage("%s.atLeast(41, 109)", version)
                .that(version.isAtLeast(version(41, 109))).isTrue();
        assertWithMessage("%s.atLeast(42, 107)", version)
                .that(version.isAtLeast(version(42, 107))).isTrue();
        assertWithMessage("%s.atLeast(42, 108)", version)
                .that(version.isAtLeast(version(42, 108))).isTrue();

        assertWithMessage("%s.atLeast(42, 109)", version)
                .that(version.isAtLeast(version(42, 109))).isFalse();
        assertWithMessage("%s.atLeast(43, 0)", version)
                .that(version.isAtLeast(version(43, 0))).isFalse();
    }

    // TODO(b/236153976): comment back once guava is supported
//    @Test
//    public void testEqualsAndHashcode() {
//        new EqualsTester()
//                .addEqualityGroup(version(4, 8), version(4, 8))
//                .addEqualityGroup(version(15), version(15))
//                .addEqualityGroup(version(16), version(16, 0))
//                .addEqualityGroup(version(23, 0), version(23))
//
//                // Make sure different subclasses are different
//                .addEqualityGroup(CarApiVersion.forMajorVersion(42),
//                        CarApiVersion.forMajorVersion(42))
//                .addEqualityGroup(PlatformApiVersion.forMajorVersion(42),
//                        PlatformApiVersion.forMajorVersion(42))
//
//                .testEquals();
//    }

    private ApiVersion<?> version(int major, int minor) {
        return mFactory.newApiVersion(major, minor);
    }

    private ApiVersion<?> version(int major) {
        return mFactory.newApiVersion(major);
    }

    @Parameterized.Parameters
    public static Collection<?> parameters() {
        return Arrays.asList(
                new Object[][] {
                    { new CarApiVersionFactory() },
                    { new PlatformApiVersionFactory() },
                });
    }

    private interface ApiVersionFactory<T extends ApiVersion<T>> {
        T newApiVersion(int majorVersion, int minorVersion);
        T newApiVersion(int majorVersion);
    }

    private static final class CarApiVersionFactory implements ApiVersionFactory<CarApiVersion> {

        @Override
        public CarApiVersion newApiVersion(int majorVersion, int minorVersion) {
            return CarApiVersion.forMajorAndMinorVersions(majorVersion, minorVersion);
        }

        @Override
        public CarApiVersion newApiVersion(int majorVersion) {
            return CarApiVersion.forMajorVersion(majorVersion);
        }
    }

    private static final class PlatformApiVersionFactory
            implements ApiVersionFactory<PlatformApiVersion> {

        @Override
        public PlatformApiVersion newApiVersion(int majorVersion, int minorVersion) {
            return PlatformApiVersion.forMajorAndMinorVersions(majorVersion, minorVersion);
        }

        @Override
        public PlatformApiVersion newApiVersion(int majorVersion) {
            return PlatformApiVersion.forMajorVersion(majorVersion);
        }
    }
}
