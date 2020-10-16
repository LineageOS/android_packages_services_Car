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
package android.car.apitest;

import static com.google.common.truth.Truth.assertWithMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.Car;
import android.car.admin.CarDevicePolicyManager;
import android.car.admin.RemoveUserResult;
import android.car.user.CarUserManager;
import android.car.user.UserCreationResult;
import android.content.pm.UserInfo;
import android.util.Log;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public final class CarDevicePolicyManagerTest extends CarApiTestBase {

    private static final String TAG = CarDevicePolicyManagerTest.class.getSimpleName();

    private CarDevicePolicyManager mManager;
    private CarUserManager mCarUserManager;

    @Before
    public void setManager() throws Exception {
        mManager = getCarService(Car.CAR_DEVICE_POLICY_SERVICE);
        mCarUserManager = getCarService(Car.CAR_USER_SERVICE);
    }

    @Test
    public void testRemoveUser() throws Exception {
        UserInfo user  = createUser("CarDevicePolicyManagerTest.testRemoveUser");
        Log.d(TAG, "removing user " + user.toFullString());

        RemoveUserResult result = mManager.removeUser(user.getUserHandle());
        Log.d(TAG, "result: " + result);

        assertWithMessage("Failed to remove user%s: %s", user.toFullString(), result)
                .that(result.isSuccess()).isTrue();
    }

    // TODO(b/169779216): move to superclass once more tests use it
    @NonNull
    private UserInfo createUser(@Nullable String name) throws Exception {
        Log.d(TAG, "creating user " + name);
        UserCreationResult result = mCarUserManager.createUser(name, /* flags= */ 0)
                .get(DEFAULT_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Log.d(TAG, "result: " + result);
        assertWithMessage("Could not create user %s: %s", name, result).that(result.isSuccess())
                .isTrue();
        return result.getUser();
    }
}
