/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.junit.Assert.assertThrows;

import android.Manifest;
import android.app.GrammaticalInflectionManager;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.res.Configuration;

import androidx.test.InstrumentationRegistry;

import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.multiuser.annotations.RequireRunOnVisibleBackgroundNonProfileUser;
import com.android.bedstead.multiuser.annotations.RequireVisibleBackgroundUsers;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

// TODO(b/353837838): Move to CTS once GrammaticalInflection APIs are test APIs.
public class GrammaticalInflectionManagerTest extends CarApiTestBase {

    private static final String TAG = GrammaticalInflectionManagerTest.class.getSimpleName();

    private final Instrumentation mInstrumentation =
            InstrumentationRegistry.getInstrumentation();
    private final Context mContext = mInstrumentation.getContext();
    private final UiAutomation mUiAutomation = mInstrumentation.getUiAutomation();
    private final GrammaticalInflectionManager mGrammaticalInflectionManager =
            mContext.getSystemService(GrammaticalInflectionManager.class);
    int mPreviousGrammaticalGender;

    @Rule
    @ClassRule
    public static final DeviceState sDeviceState = new DeviceState();

    @Before
    public void setUp() {
        mUiAutomation.adoptShellPermissionIdentity(
                Manifest.permission.READ_SYSTEM_GRAMMATICAL_GENDER);
        mPreviousGrammaticalGender = mGrammaticalInflectionManager.getSystemGrammaticalGender();
    }

    @After
    public void cleanUp() {
        // Reset the System Grammatical Gender
        try {
            mGrammaticalInflectionManager.setSystemWideGrammaticalGender(
                    mPreviousGrammaticalGender);
        } catch (Exception e) {
            // Ignore
        }
        mUiAutomation.dropShellPermissionIdentity();
    }

    @Test
    @RequireVisibleBackgroundUsers(reason = "Passengers are not allowed to change "
            + "GrammaticalInflection.")
    @RequireRunOnVisibleBackgroundNonProfileUser
    public void testPassengerCantChangeGrammaticalInflection() {
        Exception e = assertThrows(SecurityException.class,
                () -> mGrammaticalInflectionManager.setSystemWideGrammaticalGender(
                        Configuration.GRAMMATICAL_GENDER_MASCULINE));

        assertThat(e).hasMessageThat().contains(
                "Only current user is allowed to update GrammaticalInflection.");
    }
}
