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
package android.car;

import android.annotation.NonNull;
import android.car.annotation.AddedIn;

/**
 * Represents the API version of the {@code Car} SDK.
 */
@AddedIn(majorVersion = 33, minorVersion = 1)
public final class CarApiVersion extends ApiVersion<CarApiVersion> {

    /**
     * Creates a new instance with the given major and minor versions.
     */
    @AddedIn(majorVersion = 33, minorVersion = 1)
    @NonNull
    public static CarApiVersion forMajorAndMinorVersions(int majorVersion, int minorVersion) {
        return new CarApiVersion(majorVersion, minorVersion);
    }

    /**
     * Creates a new instance for a major version (i.e., the minor version will be {@code 0}.
     */
    @AddedIn(majorVersion = 33, minorVersion = 1)
    @NonNull
    public static CarApiVersion forMajorVersion(int majorVersion) {
        return new CarApiVersion(majorVersion, /* minorVersion= */ 0);
    }

    private CarApiVersion(int majorVersion, int minorVersion) {
        super(majorVersion, minorVersion);
    }
}
