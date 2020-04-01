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
import android.hardware.automotive.vehicle.V2_0.UsersInfo;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.os.UserHandle;
import android.os.UserManager;

import org.junit.Test;

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

    @Test
    public void testIsSystem() {
        assertThat(UserHalHelper.isSystem(UserFlags.SYSTEM)).isTrue();
        assertThat(UserHalHelper.isSystem(UserFlags.SYSTEM | 666)).isTrue();
        assertThat(UserHalHelper.isSystem(UserFlags.GUEST)).isFalse();
    }

    @Test
    public void testIsGuest() {
        assertThat(UserHalHelper.isGuest(UserFlags.GUEST)).isTrue();
        assertThat(UserHalHelper.isGuest(UserFlags.GUEST | 666)).isTrue();
        assertThat(UserHalHelper.isGuest(UserFlags.SYSTEM)).isFalse();
    }

    @Test
    public void testIsEphemeral() {
        assertThat(UserHalHelper.isEphemeral(UserFlags.EPHEMERAL)).isTrue();
        assertThat(UserHalHelper.isEphemeral(UserFlags.EPHEMERAL | 666)).isTrue();
        assertThat(UserHalHelper.isEphemeral(UserFlags.GUEST)).isFalse();
    }

    @Test
    public void testIsAdmin() {
        assertThat(UserHalHelper.isAdmin(UserFlags.ADMIN)).isTrue();
        assertThat(UserHalHelper.isAdmin(UserFlags.ADMIN | 666)).isTrue();
        assertThat(UserHalHelper.isAdmin(UserFlags.GUEST)).isFalse();
    }

    @Test
    public void testToUserInfoFlags() {
        assertThat(UserHalHelper.toUserInfoFlags(UserFlags.NONE)).isEqualTo(0);
        assertThat(UserHalHelper.toUserInfoFlags(UserFlags.EPHEMERAL))
                .isEqualTo(UserInfo.FLAG_EPHEMERAL);
        assertThat(UserHalHelper.toUserInfoFlags(UserFlags.ADMIN))
                .isEqualTo(UserInfo.FLAG_ADMIN);
        assertThat(UserHalHelper.toUserInfoFlags(UserFlags.EPHEMERAL | UserFlags.ADMIN))
                .isEqualTo(UserInfo.FLAG_EPHEMERAL | UserInfo.FLAG_ADMIN);

        // test flags that should be ignored
        assertThat(UserHalHelper.toUserInfoFlags(UserFlags.SYSTEM)).isEqualTo(0);
        assertThat(UserHalHelper.toUserInfoFlags(UserFlags.GUEST)).isEqualTo(0);
        assertThat(UserHalHelper.toUserInfoFlags(1024)).isEqualTo(0);
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

    @Test
    public void testCreatePropRequest() {
        int requestId = 1;
        int requestType = 2;
        int requestProp = 3;
        VehiclePropValue propRequest = UserHalHelper.createPropRequest(requestId, requestType,
                requestProp);

        assertThat(propRequest.value.int32Values)
                .containsExactly(requestId, requestType)
                .inOrder();
        assertThat(propRequest.prop).isEqualTo(requestProp);
    }

    @Test
    public void testAddUsersInfo_nullCurrentUser() {
        VehiclePropValue propRequest = new VehiclePropValue();

        UsersInfo infos = new UsersInfo();
        infos.currentUser = null;
        assertThrows(NullPointerException.class, () ->
                UserHalHelper.addUsersInfo(propRequest, infos));
    }

    @Test
    public void testAddUsersInfo_mismatchNumberUsers() {
        VehiclePropValue propRequest = new VehiclePropValue();

        UsersInfo infos = new UsersInfo();
        infos.currentUser.userId = 42;
        infos.currentUser.flags = 1;
        infos.numberUsers = 1;
        assertThat(infos.existingUsers).isEmpty();
        assertThrows(IllegalArgumentException.class, () ->
                UserHalHelper.addUsersInfo(propRequest, infos));
    }

    @Test
    public void testAddUsersInfo_success() {
        VehiclePropValue propRequest = new VehiclePropValue();
        propRequest.value.int32Values.add(99);

        UsersInfo infos = new UsersInfo();
        infos.currentUser.userId = 42;
        infos.currentUser.flags = 1;
        infos.numberUsers = 1;

        android.hardware.automotive.vehicle.V2_0.UserInfo userInfo =
                new android.hardware.automotive.vehicle.V2_0.UserInfo();
        userInfo.userId = 43;
        userInfo.flags = 1;
        infos.existingUsers.add(userInfo);
        UserHalHelper.addUsersInfo(propRequest, infos);

        assertThat(propRequest.value.int32Values)
                .containsExactly(99, 42, 1, 1, 43, 1)
                .inOrder();
    }
}
