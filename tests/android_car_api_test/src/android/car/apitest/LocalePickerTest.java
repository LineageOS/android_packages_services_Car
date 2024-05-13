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

import android.app.LocaleManager;
import android.os.LocaleList;
import android.util.Log;

import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.RequireRunNotOnVisibleBackgroundNonProfileUser;
import com.android.bedstead.harrier.annotations.RequireRunOnVisibleBackgroundNonProfileUser;
import com.android.bedstead.harrier.annotations.RequireVisibleBackgroundUsers;
import com.android.compatibility.common.util.ApiTest;
import com.android.internal.app.LocalePicker;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;


import java.util.Locale;
import java.util.Objects;

public final class LocalePickerTest extends CarApiTestBase {

    private static final String TAG = LocalePickerTest.class.getSimpleName();

    @Rule
    @ClassRule
    public static final DeviceState sDeviceState = new DeviceState();

    private final LocaleManager mLocaleManager = getContext().getSystemService(LocaleManager.class);
    LocaleList mPreviousLocaleList;

    @Before
    public void setUp() {
        mPreviousLocaleList = mLocaleManager.getSystemLocales();
    }

    @After
    public void cleanUp() {
        try {
            LocalePicker.updateLocales(mPreviousLocaleList);
        } catch (Exception e) {
            Log.e(TAG, "Failed to clean up", e);
        }
    }

    @Test
    @ApiTest(apis = {"android.internal.app.LocalePicker#updateLocale"})
    @RequireVisibleBackgroundUsers(reason = "Locale test for only MUMD devices")
    @RequireRunNotOnVisibleBackgroundNonProfileUser
    public void testUpdateLocaleForCurrentUser() throws Exception {
        Locale localeToSet = getLocaleToSetInTest();

        LocalePicker.updateLocale(localeToSet);

        assertSystemLocale(localeToSet);
    }


    @Test
    @ApiTest(apis = {"android.internal.app.LocalePicker#updateLocale"})
    @RequireVisibleBackgroundUsers(reason = "Locale test for only MUMD devices")
    @RequireRunOnVisibleBackgroundNonProfileUser
    public void testUpdateLocaleForVisibleBackgroundUserUser() throws Exception {
        Locale localeToSet = getLocaleToSetInTest();

        assertThrows(SecurityException.class, () -> LocalePicker.updateLocale(localeToSet));
    }

    private Locale getLocaleToSetInTest() {
        if (Objects.equals(mPreviousLocaleList.get(0).getLanguage(), "en")) {
            // If the current language is english, set the language to Hindi for testing.
            return new Locale("hi", "IN");
        } else {
            // If the current language is not english, set the language to english for testing.
            return new Locale("en", "US");
        }
    }

    private void assertSystemLocale(Locale locale) {
        Locale systemLocale = mLocaleManager.getSystemLocales().get(0);
        assertThat(systemLocale.getLanguage()).isEqualTo(locale.getLanguage());
        assertThat(systemLocale.getCountry()).isEqualTo(locale.getCountry());
    }
}
