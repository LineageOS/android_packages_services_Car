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

import static android.provider.DeviceConfig.NAMESPACE_GAME_OVERLAY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;


import android.util.Log;
import android.provider.DeviceConfig;
import android.provider.Settings;

import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.RequireRunNotOnVisibleBackgroundNonProfileUser;
import com.android.bedstead.harrier.annotations.RequireRunOnVisibleBackgroundNonProfileUser;
import com.android.bedstead.harrier.annotations.RequireVisibleBackgroundUsers;
import com.android.compatibility.common.util.ApiTest;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;


public final class DeviceConfigTest extends CarApiTestBase {

    private static final String TAG = DeviceConfigTest.class.getSimpleName();

    @Rule
    @ClassRule
    public static final DeviceState sDeviceState = new DeviceState();

    public static final String KEY = "car_api_test_prop";
    public static final String VALUE = "Set_Value_42";

    @Before
    @After
    public void cleanUp() {
        try {
            DeviceConfig.deleteProperty(NAMESPACE_GAME_OVERLAY, KEY);
        } catch (Exception e) {
            Log.e(TAG, "Failed to clean up", e);
        }
    }

    @Test
    @ApiTest(apis = {"DeviceConfig.setProperty", "DeviceConfig.getProperty"})
    @RequireVisibleBackgroundUsers(reason = "Device Config for only MUMD devices")
    @RequireRunNotOnVisibleBackgroundNonProfileUser
    public void testUpdateConfigForCurrentUser() throws Exception {
        assertThat(DeviceConfig.setProperty(NAMESPACE_GAME_OVERLAY, KEY, VALUE,
                /* makeDefault= */ false)).isTrue();
        assertThat(DeviceConfig.getProperty(NAMESPACE_GAME_OVERLAY, KEY)).isEqualTo(VALUE);
    }

    @Test
    @ApiTest(apis = {"Settings.Config.putString"})
    @RequireVisibleBackgroundUsers(reason = "Device Config for only MUMD devices")
    @RequireRunOnVisibleBackgroundNonProfileUser
    public void testUpdateConfigForBackgroundUser() throws Exception {
        assertThrows(SecurityException.class,
                () -> Settings.Config.putString(NAMESPACE_GAME_OVERLAY, KEY, VALUE,
                        /* makeDefault= */ false));
    }
}
