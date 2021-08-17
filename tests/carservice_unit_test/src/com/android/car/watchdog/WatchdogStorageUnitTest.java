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

import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NEVER;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NO;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_YES;

import static com.android.car.watchdog.WatchdogStorage.WatchdogDbHelper.DATABASE_NAME;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.util.Slog;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * <p>This class contains unit tests for the {@link WatchdogStorage}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class WatchdogStorageUnitTest {
    private static final String TAG = WatchdogStorageUnitTest.class.getSimpleName();

    private WatchdogStorage mService;
    private File mDatabaseFile;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseFile = context.createDeviceProtectedStorageContext()
                .getDatabasePath(DATABASE_NAME);
        mService = new WatchdogStorage(context, /* useDataSystemCarDir= */ false);
    }

    @After
    public void tearDown() {
        mService.release();
        if (!mDatabaseFile.delete()) {
            Slog.e(TAG, "Failed to delete the database file: " + mDatabaseFile.getAbsolutePath());
        }
    }

    @Test
    public void testSaveUserPackageSettings() throws Exception {
        List<WatchdogStorage.UserPackageSettingsEntry> expected = sampleSettings();

        assertThat(mService.saveUserPackageSettings(expected)).isTrue();

        UserPackageSettingsEntrySubject.assertThat(mService.getUserPackageSettings())
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void testOverwriteUserPackageSettings() throws Exception {
        List<WatchdogStorage.UserPackageSettingsEntry> expected = Arrays.asList(
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.A", KILLABLE_STATE_YES),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.B", KILLABLE_STATE_NO));

        assertThat(mService.saveUserPackageSettings(expected)).isTrue();

        expected = Arrays.asList(
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.A", KILLABLE_STATE_NEVER),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.B", KILLABLE_STATE_NO));

        assertThat(mService.saveUserPackageSettings(expected)).isTrue();

        UserPackageSettingsEntrySubject.assertThat(mService.getUserPackageSettings())
                .containsExactlyElementsIn(expected);
    }

    private static List<WatchdogStorage.UserPackageSettingsEntry> sampleSettings() {
        return Arrays.asList(
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.A", KILLABLE_STATE_YES),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.B", KILLABLE_STATE_NO),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "vendor_package.critical.C", KILLABLE_STATE_NEVER),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 101, "system_package.non_critical.A", KILLABLE_STATE_NO),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 101, "system_package.non_critical.B", KILLABLE_STATE_YES),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 101, "vendor_package.critical.C", KILLABLE_STATE_NEVER));
    }
}
