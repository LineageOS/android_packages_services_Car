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
package com.android.car.util;

import android.car.Car;
import android.car.PlatformApiVersion;
import android.car.PlatformVersionMismatchException;

/**
 * Utility class for platform and car API version check.
 */
public class VersionUtils {
    /**
     * Asserts if the current platform version is at least expected platform version.
     *
     * @throws PlatformVersionMismatchException if current platform version is not equal to or
     * greater than expected platform version.
     */
    public static void assertPlatformApiVersionAtLeast(
            PlatformApiVersion expectedPlatformApiVersion)
            throws PlatformVersionMismatchException {
        PlatformApiVersion currentPlatformApiVersion = Car.getPlatformApiVersion();
        if (!currentPlatformApiVersion.isAtLeast(expectedPlatformApiVersion)) {
            throw new PlatformVersionMismatchException(expectedPlatformApiVersion);
        }
    }
}
