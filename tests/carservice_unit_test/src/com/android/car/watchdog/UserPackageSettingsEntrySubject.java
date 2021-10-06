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

import com.google.common.truth.Correspondence;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;

import java.util.Arrays;

public final class UserPackageSettingsEntrySubject extends Subject {
    /* Boiler-plate Subject.Factory for UserPackageSettingsEntrySubject. */
    private static final Subject.Factory<
            com.android.car.watchdog.UserPackageSettingsEntrySubject,
            Iterable<WatchdogStorage.UserPackageSettingsEntry>>
            USER_PACKAGE_SETTINGS_ENTRY_SUBJECT_FACTORY =
            com.android.car.watchdog.UserPackageSettingsEntrySubject::new;

    private final Iterable<WatchdogStorage.UserPackageSettingsEntry> mActual;

    /* User-defined entry point. */
    public static UserPackageSettingsEntrySubject assertThat(
            @Nullable Iterable<WatchdogStorage.UserPackageSettingsEntry> stats) {
        return assertAbout(USER_PACKAGE_SETTINGS_ENTRY_SUBJECT_FACTORY).that(stats);
    }

    public static Subject.Factory<UserPackageSettingsEntrySubject,
            Iterable<WatchdogStorage.UserPackageSettingsEntry>> userPackageSettingsEntries() {
        return USER_PACKAGE_SETTINGS_ENTRY_SUBJECT_FACTORY;
    }

    public void containsExactly(WatchdogStorage.UserPackageSettingsEntry... stats) {
        containsExactlyElementsIn(Arrays.asList(stats));
    }

    public void containsExactlyElementsIn(
            Iterable<WatchdogStorage.UserPackageSettingsEntry> expected) {
        assertWithMessage("Expected entries (%s) equals to actual entries (%s)",
                toString(expected), toString(mActual)).that(mActual)
                .comparingElementsUsing(Correspondence.from(
                        UserPackageSettingsEntrySubject::isEquals, "is equal to"))
                .containsExactlyElementsIn(expected);
    }

    public static boolean isEquals(WatchdogStorage.UserPackageSettingsEntry actual,
            WatchdogStorage.UserPackageSettingsEntry expected) {
        if (actual == null || expected == null) {
            return (actual == null) && (expected == null);
        }
        return actual.userId == expected.userId && actual.packageName.equals(expected.packageName)
                && actual.killableState == expected.killableState;
    }

    private static String toString(Iterable<WatchdogStorage.UserPackageSettingsEntry> entries) {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        for (WatchdogStorage.UserPackageSettingsEntry entry : entries) {
            toStringBuilder(builder, entry).append(", ");
        }
        if (builder.length() > 1) {
            builder.delete(builder.length() - 2, builder.length());
        }
        builder.append("]");
        return builder.toString();
    }

    private static StringBuilder toStringBuilder(StringBuilder builder,
            WatchdogStorage.UserPackageSettingsEntry entry) {
        return builder.append("{UserId: ").append(entry.userId)
                .append(", Package name: ").append(entry.packageName)
                .append(", Killable state: ").append(entry.killableState).append("}");
    }

    private UserPackageSettingsEntrySubject(FailureMetadata failureMetadata,
            @Nullable Iterable<WatchdogStorage.UserPackageSettingsEntry> iterableSubject) {
        super(failureMetadata, iterableSubject);
        this.mActual = iterableSubject;
    }
}
