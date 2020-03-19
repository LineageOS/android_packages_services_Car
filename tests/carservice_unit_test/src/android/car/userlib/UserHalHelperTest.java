/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.car.userlib;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.testng.Assert.assertThrows;

import android.annotation.NonNull;
import android.content.pm.UserInfo;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.os.UserHandle;
import android.os.UserManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class UserHalHelperTest {

    @Test
    public void testHalCallbackStatusToString() {
        assertThat(UserHalHelper.halCallbackStatusToString(-666)).isNotNull();
    }

    @Test
    public void testParseInitialUserInfoRequestType_valid() {
        assertThat(UserHalHelper.parseInitialUserInfoRequestType("FIRST_BOOT"))
                .isEqualTo(InitialUserInfoRequestType.FIRST_BOOT);
        assertThat(UserHalHelper.parseInitialUserInfoRequestType("COLD_BOOT"))
            .isEqualTo(InitialUserInfoRequestType.COLD_BOOT);
        assertThat(UserHalHelper.parseInitialUserInfoRequestType("FIRST_BOOT_AFTER_OTA"))
            .isEqualTo(InitialUserInfoRequestType.FIRST_BOOT_AFTER_OTA);
        assertThat(UserHalHelper.parseInitialUserInfoRequestType("RESUME"))
            .isEqualTo(InitialUserInfoRequestType.RESUME);
    }

    @Test
    public void testParseInitialUserInfoRequestType_unknown() {
        assertThat(UserHalHelper.parseInitialUserInfoRequestType("666")).isEqualTo(666);
    }

    @Test
    public void testParseInitialUserInfoRequestType_invalid() {
        assertThrows(IllegalArgumentException.class,
                () -> UserHalHelper.parseInitialUserInfoRequestType("NumberNotIAm"));
    }

    @Test
    public void testConvertFlags_nullUser() {
        assertThrows(IllegalArgumentException.class, () -> UserHalHelper.convertFlags(null));
    }

    @Test
    public void testConvertFlags() {
        UserInfo user = new UserInfo();

        user.id = UserHandle.USER_SYSTEM;
        assertConvertFlags(UserFlags.SYSTEM, user);

        user.id = 10;
        assertConvertFlags(UserFlags.NONE, user);

        user.flags = UserInfo.FLAG_ADMIN;
        assertThat(user.isAdmin()).isTrue(); // sanity check
        assertConvertFlags(UserFlags.ADMIN, user);

        user.flags = UserInfo.FLAG_EPHEMERAL;
        assertThat(user.isEphemeral()).isTrue(); // sanity check
        assertConvertFlags(UserFlags.EPHEMERAL, user);

        user.userType = UserManager.USER_TYPE_FULL_GUEST;
        assertThat(user.isEphemeral()).isTrue(); // sanity check
        assertThat(user.isGuest()).isTrue(); // sanity check
        assertConvertFlags(UserFlags.GUEST | UserFlags.EPHEMERAL, user);
    }

    private void assertConvertFlags(int expectedFlags, @NonNull UserInfo user) {
        assertWithMessage("flags mismatch: user=%s, flags=%s",
                user.toFullString(), UserHalHelper.userFlagsToString(expectedFlags))
                        .that(UserHalHelper.convertFlags(user)).isEqualTo(expectedFlags);
    }

    @Test
    public void testUserFlagsToString() {
        assertThat(UserHalHelper.userFlagsToString(-666)).isNotNull();
    }
}
