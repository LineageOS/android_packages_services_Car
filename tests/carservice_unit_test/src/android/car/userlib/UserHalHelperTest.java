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

import static android.car.userlib.UserHalHelper.USER_IDENTIFICATION_ASSOCIATION_PROPERTY;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType.CUSTOM_1;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType.CUSTOM_2;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType.CUSTOM_3;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType.CUSTOM_4;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType.KEY_FOB;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationValue.ASSOCIATED_ANOTHER_USER;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationValue.ASSOCIATED_CURRENT_USER;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationValue.NOT_ASSOCIATED_ANY_USER;
import static android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationValue.UNKNOWN;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;
import static org.testng.Assert.assertThrows;

import android.annotation.NonNull;
import android.content.pm.UserInfo;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociation;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationValue;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationGetRequest;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationResponse;
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

    @Test
    public void testVsValidUserIdentificationAssociationType_valid() {
        assertThat(UserHalHelper.isValidUserIdentificationAssociationType(KEY_FOB)).isTrue();
        assertThat(UserHalHelper.isValidUserIdentificationAssociationType(CUSTOM_1)).isTrue();
        assertThat(UserHalHelper.isValidUserIdentificationAssociationType(CUSTOM_2)).isTrue();
        assertThat(UserHalHelper.isValidUserIdentificationAssociationType(CUSTOM_3)).isTrue();
        assertThat(UserHalHelper.isValidUserIdentificationAssociationType(CUSTOM_4)).isTrue();
    }

    @Test
    public void testIsValidUserIdentificationAssociationType_invalid() {
        assertThat(UserHalHelper.isValidUserIdentificationAssociationType(CUSTOM_4 + 1)).isFalse();
    }

    @Test
    public void testIsValidUserIdentificationAssociationValue_valid() {
        assertThat(UserHalHelper.isValidUserIdentificationAssociationValue(ASSOCIATED_ANOTHER_USER))
                .isTrue();
        assertThat(UserHalHelper.isValidUserIdentificationAssociationValue(ASSOCIATED_CURRENT_USER))
                .isTrue();
        assertThat(UserHalHelper.isValidUserIdentificationAssociationValue(NOT_ASSOCIATED_ANY_USER))
                .isTrue();
        assertThat(UserHalHelper.isValidUserIdentificationAssociationValue(UNKNOWN)).isTrue();
    }

    @Test
    public void testIsValidUserIdentificationAssociationValue_invalid() {
        assertThat(UserHalHelper.isValidUserIdentificationAssociationValue(0)).isFalse();
    }

    @Test
    public void testUserIdentificationGetRequestToVehiclePropValue_null() {
        assertThrows(NullPointerException.class,
                () -> UserHalHelper.toVehiclePropValue((UserIdentificationGetRequest) null));
    }

    @Test
    public void testUserIdentificationGetRequestToVehiclePropValue_emptyRequest() {
        UserIdentificationGetRequest request = new UserIdentificationGetRequest();
        assertThrows(IllegalArgumentException.class,
                () -> UserHalHelper.toVehiclePropValue(request));
    }

    @Test
    public void testUserIdentificationGetRequestToVehiclePropValue_wrongNumberOfAssociations() {
        UserIdentificationGetRequest request = new UserIdentificationGetRequest();
        request.numberAssociationTypes = 1;
        assertThrows(IllegalArgumentException.class,
                () -> UserHalHelper.toVehiclePropValue(request));
    }

    @Test
    public void testUserIdentificationGetRequestToVehiclePropValue_invalidType() {
        UserIdentificationGetRequest request = new UserIdentificationGetRequest();
        request.numberAssociationTypes = 1;
        request.associationTypes.add(CUSTOM_4 + 1);
        assertThrows(IllegalArgumentException.class,
                () -> UserHalHelper.toVehiclePropValue(request));
    }

    @Test
    public void testUserIdentificationGetRequestToVehiclePropValue_ok() {
        UserIdentificationGetRequest request = new UserIdentificationGetRequest();
        request.userInfo.userId = 42;
        request.userInfo.flags = 108;
        request.numberAssociationTypes = 2;
        request.associationTypes.add(KEY_FOB);
        request.associationTypes.add(CUSTOM_1);

        VehiclePropValue propValue = UserHalHelper.toVehiclePropValue(request);
        assertWithMessage("wrong prop on %s", propValue).that(propValue.prop)
                .isEqualTo(USER_IDENTIFICATION_ASSOCIATION_PROPERTY);
        assertWithMessage("wrong int32values on %s", propValue).that(propValue.value.int32Values)
                .containsExactly(42, 108, 2, KEY_FOB, CUSTOM_1).inOrder();
    }

    @Test
    public void testToUserIdentificationGetResponse_null() {
        assertThrows(NullPointerException.class,
                () -> UserHalHelper.toUserIdentificationGetResponse(null));
    }

    @Test
    public void testToUserIdentificationGetResponse_invalidPropType() {
        VehiclePropValue prop = new VehiclePropValue();
        assertThrows(IllegalArgumentException.class,
                () -> UserHalHelper.toUserIdentificationGetResponse(prop));
    }

    @Test
    public void testToUserIdentificationGetResponse_invalidSize() {
        VehiclePropValue prop = new VehiclePropValue();
        prop.prop = UserHalHelper.USER_IDENTIFICATION_ASSOCIATION_PROPERTY;
        prop.value.int32Values.add(0);
        assertThrows(IllegalArgumentException.class,
                () -> UserHalHelper.toUserIdentificationGetResponse(prop));
    }

    @Test
    public void testToUserIdentificationGetResponse_sizeMismatch() {
        VehiclePropValue prop = new VehiclePropValue();
        prop.prop = UserHalHelper.USER_IDENTIFICATION_ASSOCIATION_PROPERTY;
        prop.value.int32Values.add(1); // number of associations
        prop.value.int32Values.add(KEY_FOB);
        assertThrows(IllegalArgumentException.class,
                () -> UserHalHelper.toUserIdentificationGetResponse(prop));
    }

    @Test
    public void testToUserIdentificationGetResponse_invalidType() {
        VehiclePropValue prop = new VehiclePropValue();
        prop.prop = UserHalHelper.USER_IDENTIFICATION_ASSOCIATION_PROPERTY;
        prop.value.int32Values.add(1); // number of associations
        prop.value.int32Values.add(CUSTOM_4 + 1);
        prop.value.int32Values.add(ASSOCIATED_ANOTHER_USER);
        assertThrows(IllegalArgumentException.class,
                () -> UserHalHelper.toUserIdentificationGetResponse(prop));
    }

    @Test
    public void testToUserIdentificationGetResponse_invalidValue() {
        VehiclePropValue prop = new VehiclePropValue();
        prop.prop = UserHalHelper.USER_IDENTIFICATION_ASSOCIATION_PROPERTY;
        prop.value.int32Values.add(1); // number of associations
        prop.value.int32Values.add(KEY_FOB);
        prop.value.int32Values.add(0);
        assertThrows(IllegalArgumentException.class,
                () -> UserHalHelper.toUserIdentificationGetResponse(prop));
    }

    @Test
    public void testToUserIdentificationGetResponse_ok() {
        VehiclePropValue prop = new VehiclePropValue();
        prop.prop = UserHalHelper.USER_IDENTIFICATION_ASSOCIATION_PROPERTY;
        prop.value.int32Values.add(3); // number of associations
        prop.value.int32Values.add(KEY_FOB);
        prop.value.int32Values.add(ASSOCIATED_ANOTHER_USER);
        prop.value.int32Values.add(CUSTOM_1);
        prop.value.int32Values.add(ASSOCIATED_CURRENT_USER);
        prop.value.int32Values.add(CUSTOM_2);
        prop.value.int32Values.add(NOT_ASSOCIATED_ANY_USER);
        prop.value.stringValue = "D'OH!";
        UserIdentificationResponse response = UserHalHelper.toUserIdentificationGetResponse(prop);
        assertWithMessage("Wrong number of associations on %s", response)
            .that(response.numberAssociation).isEqualTo(3);

        assertAssociation(response, 0, KEY_FOB, ASSOCIATED_ANOTHER_USER);
        assertAssociation(response, 1, CUSTOM_1, ASSOCIATED_CURRENT_USER);
        assertAssociation(response, 2, CUSTOM_2, NOT_ASSOCIATED_ANY_USER);
        assertWithMessage("Wrong error message on %s", response)
            .that(response.errorMessage).isEqualTo("D'OH!");
    }

    private void assertAssociation(@NonNull UserIdentificationResponse response, int index,
            int expectedType, int expectedValue) {
        UserIdentificationAssociation actualAssociation = response.associations.get(index);
        if (actualAssociation.type != expectedType) {
            fail("Wrong type for association at index " + index + " on " + response + "; expected "
                    + UserIdentificationAssociationType.toString(expectedType) + ", got "
                    + UserIdentificationAssociationType.toString(actualAssociation.type));
        }
        if (actualAssociation.type != expectedType) {
            fail("Wrong value for association at index " + index + " on " + response + "; expected "
                    + UserIdentificationAssociationValue.toString(expectedValue) + ", got "
                    + UserIdentificationAssociationValue.toString(actualAssociation.value));
        }
    }

}
