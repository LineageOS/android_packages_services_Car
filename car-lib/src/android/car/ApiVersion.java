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

import java.util.Objects;

/**
 * Abstraction of Android APIs.
 *
 * <p>This class is used to represent a pair of major / minor API versions: the "major" version
 * represents a "traditional" Android SDK release, while the "minor" is used to indicate incremental
 * releases for that major.
 *
 * <p>This class is needed because the standard Android SDK API versioning only supports major
 * releases, but {@code Car} APIs can now (starting on
 * {@link android.os.Build.Build.VERSION_CODES#TIRAMISU Android 13}) be updated on minor releases
 * as well.
 */
@AddedIn(majorVersion = 33, minorVersion = 1)
public abstract class ApiVersion<T extends ApiVersion<?>> {

    private final int mMajorVersion;
    private final int mMinorVersion;

    ApiVersion(int majorVersion, int minorVersion) {
        this.mMajorVersion = majorVersion;
        this.mMinorVersion = minorVersion;
    }

    /**
     * Checks if this API version meets the required version.
     *
     * @param requiredApiVersionMajor Required major version number.
     * @param requiredApiVersionMinor Required minor version number.
     * @return {@code true} if the {@link #getMajorVersion() major version} is newer than the
     *         {@code requiredVersion}'s major or if the {@link #getMajorVersion() major version} is
     *         the same as {@code requiredVersion}'s major with the {@link #getMinorVersion() minor
     *         version} the same or newer than {@code requiredVersion}'s minor.
     */
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public final boolean isAtLeast(@NonNull T requiredVersion) {
        Objects.requireNonNull(requiredVersion);

        int requiredApiVersionMajor = requiredVersion.getMajorVersion();
        int requiredApiVersionMinor = requiredVersion.getMinorVersion();

        return (mMajorVersion > requiredApiVersionMajor)
                || (mMajorVersion == requiredApiVersionMajor
                        && mMinorVersion >= requiredApiVersionMinor);
    }
    /**
     * Gets the major version of the API represented by this object.
     */
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public final int getMajorVersion() {
        return mMajorVersion;
    }

    /**
     * Gets the minor version change of API for the same {@link #getMajorVersion()}.
     *
     * <p>It will reset to {@code 0} whenever {@link #getMajorVersion()} is updated
     * and will increase by {@code 1} if car builtin or other car platform part is changed with the
     * same {@link #getMajorVersion()}.
     *
     * <p>Client should check this version to use APIs which were added in a minor-only version
     * update.
     */
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public final int getMinorVersion() {
        return mMinorVersion;
    }

    /**
     * @hide
     */
    @Override
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        @SuppressWarnings("unchecked")
        ApiVersion<T> other = (ApiVersion<T>) obj;
        if (mMajorVersion != other.mMajorVersion) return false;
        if (mMinorVersion != other.mMinorVersion) return false;
        return true;
    }

    /**
     * @hide
     */
    @Override
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public int hashCode() {
        int prime = 31;
        int result = 1;
        result = prime * result + mMajorVersion;
        result = prime * result + mMinorVersion;
        return result;
    }

    /**
     * @hide
     */
    @Override
    @NonNull
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public final String toString() {
        return getClass().getSimpleName()
                + "[major=" + mMajorVersion + ", minor=" + mMinorVersion + "]";
    }
}
