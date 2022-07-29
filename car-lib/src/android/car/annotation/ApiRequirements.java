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

package android.car.annotation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.annotation.TestApi;
import android.car.CarApiVersion;
import android.car.PlatformApiVersion;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Defines in which version of Car and platform SDK the annotated API is supported.
 *
 * @hide
 */
@Retention(RUNTIME)
@Target({ANNOTATION_TYPE, FIELD, TYPE, METHOD})
@TestApi
public @interface ApiRequirements {

    /**
     * Indicates the minimum Car SDK version required for the annotated API.
     *
     * <p>Clients can check it calling {@code Car.getCarApiVersion.isAtLeast(
     * CarApiVersion.VERSION_CODES.VERSION_DEFINED_IN_THIS_ANNOTATION)}.
     */
    CarVersion minCarVersion();

    /**
     * Indicates the minimum Platform SDK version required for the annotated API.
     *
     * <p>Clients can check it calling {@code Car.getPlatformApiVersion.isAtLeast(
     * PlatformApiVersion.VERSION_CODES.VERSION_DEFINED_IN_THIS_ANNOTATION)}.
     */
    PlatformVersion minPlatformVersion();

    @SuppressWarnings("Enum")
    enum CarVersion {

        TIRAMISU_0(CarApiVersion.VERSION_CODES.TIRAMISU_0),
        TIRAMISU_1(CarApiVersion.VERSION_CODES.TIRAMISU_1);

        private final CarApiVersion mVersion;

        CarVersion(CarApiVersion version) {
            mVersion = version;
        }

        /**
         * Gets the {@link CarApiVersion} associated with it.
         */
        public CarApiVersion get() {
            return mVersion;
        }
    }

    @SuppressWarnings("Enum")
    enum PlatformVersion {

        TIRAMISU_0(PlatformApiVersion.VERSION_CODES.TIRAMISU_0),
        TIRAMISU_1(PlatformApiVersion.VERSION_CODES.TIRAMISU_1);

        private final PlatformApiVersion mVersion;

        PlatformVersion(PlatformApiVersion version) {
            mVersion = version;
        }

        /**
         * Gets the {@link PlatformApiVersion} associated with it.
         */
        public PlatformApiVersion get() {
            return mVersion;
        }
    }
}
