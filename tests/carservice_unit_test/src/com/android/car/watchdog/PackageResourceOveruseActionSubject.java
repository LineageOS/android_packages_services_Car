/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.watchdog;

import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertWithMessage;

import android.annotation.Nullable;
import android.automotive.watchdog.internal.PackageIdentifier;
import android.automotive.watchdog.internal.PackageResourceOveruseAction;

import com.google.common.truth.Correspondence;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;

import java.util.Arrays;

public final class PackageResourceOveruseActionSubject extends Subject {
    /* Boiler-plate Subject.Factory for PackageResourceOveruseActionSubject. */
    private static final Subject.Factory<
            com.android.car.watchdog.PackageResourceOveruseActionSubject,
            Iterable<PackageResourceOveruseAction>>
            PACKAGE_RESOURCE_OVERUSE_ACTION_SUBJECT_FACTORY =
            com.android.car.watchdog.PackageResourceOveruseActionSubject::new;

    private final Iterable<PackageResourceOveruseAction> mActual;

    /* User-defined entry point. */
    public static PackageResourceOveruseActionSubject assertThat(
            @Nullable Iterable<PackageResourceOveruseAction> actions) {
        return assertAbout(PACKAGE_RESOURCE_OVERUSE_ACTION_SUBJECT_FACTORY).that(actions);
    }

    public static Subject.Factory<PackageResourceOveruseActionSubject,
            Iterable<PackageResourceOveruseAction>> packageResourceOveruseActions() {
        return PACKAGE_RESOURCE_OVERUSE_ACTION_SUBJECT_FACTORY;
    }

    public void containsExactly(PackageResourceOveruseAction... actions) {
        containsExactlyElementsIn(Arrays.asList(actions));
    }

    public void containsExactlyElementsIn(
            Iterable<PackageResourceOveruseAction> expected) {
        assertWithMessage("Package resource overuse actions:\nExpected: %s\nActual: %s\n",
                toString(expected), toString(mActual)).that(mActual)
                .comparingElementsUsing(Correspondence.from(
                        PackageResourceOveruseActionSubject::isEquals, "is equal to"))
                .containsExactlyElementsIn(expected);
    }

    public static boolean isEquals(PackageResourceOveruseAction actual,
            PackageResourceOveruseAction expected) {
        if (actual == null || expected == null) {
            return (actual == null) && (expected == null);
        }
        return isPackageIdentifierEquals(actual.packageIdentifier, expected.packageIdentifier)
                && Arrays.equals(actual.resourceTypes, expected.resourceTypes)
                && actual.resourceOveruseActionType == expected.resourceOveruseActionType;
    }

    private static boolean isPackageIdentifierEquals(PackageIdentifier lhs, PackageIdentifier rhs) {
        return lhs.name.equals(rhs.name) && lhs.uid == rhs.uid;
    }

    public static String toString(Iterable<PackageResourceOveruseAction> actions) {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        for (PackageResourceOveruseAction action : actions) {
            builder = toStringBuilder(builder, action);
            builder.append(", ");
        }
        if (builder.length() > 1) {
            builder.delete(builder.length() - 2, builder.length());
        }
        builder.append("]");
        return builder.toString();
    }

    public static StringBuilder toStringBuilder(
            StringBuilder builder, PackageResourceOveruseAction action) {
        return builder.append("{Package Identifier: {Name: ")
                .append(action.packageIdentifier.name)
                .append(", UID: ").append(action.packageIdentifier.uid)
                .append("}, Resource Types: ").append(Arrays.toString(action.resourceTypes))
                .append(", Action Type: ").append(action.resourceOveruseActionType).append("}");
    }

    private PackageResourceOveruseActionSubject(FailureMetadata failureMetadata,
            @Nullable Iterable<PackageResourceOveruseAction> iterableSubject) {
        super(failureMetadata, iterableSubject);
        this.mActual = iterableSubject;
    }
}
