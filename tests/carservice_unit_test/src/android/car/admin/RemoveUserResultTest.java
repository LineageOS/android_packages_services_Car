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
package android.car.admin;

import static com.google.common.truth.Truth.assertThat;

import android.car.user.UserRemovalResult;

import org.junit.Test;

public final class RemoveUserResultTest {

    @Test
    public void testNullConstructor() {
        RemoveUserResult result = new RemoveUserResult(null);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo(RemoveUserResult.STATUS_FAILURE_GENERIC);
    }

    @Test
    public void testConversion() {
        conversionTest(/* isSuccess= */ true,
                UserRemovalResult.STATUS_SUCCESSFUL,
                RemoveUserResult.STATUS_SUCCESS);
        conversionTest(/* isSuccess= */ true,
                UserRemovalResult.STATUS_SUCCESSFUL_LAST_ADMIN_REMOVED,
                RemoveUserResult.STATUS_SUCCESS_LAST_ADMIN_REMOVED);
        conversionTest(/* isSuccess= */ false,
                UserRemovalResult.STATUS_TARGET_USER_IS_CURRENT_USER,
                RemoveUserResult.STATUS_FAILURE_TARGET_USER_IS_CURRENT_USER);
        conversionTest(/* isSuccess= */ false,
                UserRemovalResult.STATUS_USER_DOES_NOT_EXIST,
                RemoveUserResult.STATUS_FAILURE_USER_DOES_NOT_EXIST);
        conversionTest(/* isSuccess= */ false,
                UserRemovalResult.STATUS_ANDROID_FAILURE,
                RemoveUserResult.STATUS_FAILURE_GENERIC);
    }

    private void conversionTest(boolean isSuccess, int userRemovalStatus, int removeUserStatus) {
        RemoveUserResult result = new RemoveUserResult(new UserRemovalResult(userRemovalStatus));
        assertThat(result.isSuccess()).isEqualTo(isSuccess);
        assertThat(result.getStatus()).isEqualTo(removeUserStatus);
    }
}
