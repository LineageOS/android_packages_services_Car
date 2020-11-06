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

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;
import static com.android.compatibility.common.util.SystemUtil.eventually;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.testng.Assert.expectThrows;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.UnsafeStateException;
import android.car.Car;
import android.car.admin.CarDevicePolicyManager;
import android.car.admin.CreateUserResult;
import android.car.admin.RemoveUserResult;
import android.car.user.CarUserManager;
import android.car.user.UserCreationResult;
import android.car.user.UserRemovalResult;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.PowerManager;
import android.os.UserHandle;
import android.util.Log;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public final class CarDevicePolicyManagerTest extends CarApiTestBase {

    private static final String TAG = CarDevicePolicyManagerTest.class.getSimpleName();

    private CarDevicePolicyManager mCarDpm;
    private CarUserManager mCarUserManager;
    private DevicePolicyManager mDpm;
    private KeyguardManager mKeyguardManager;
    private PowerManager mPowerManager;

    @Before
    public void setManager() throws Exception {
        mCarDpm = getCarService(Car.CAR_DEVICE_POLICY_SERVICE);
        mCarUserManager = getCarService(Car.CAR_USER_SERVICE);
        Context context = getContext();
        mDpm = context.getSystemService(DevicePolicyManager.class);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    @Test
    public void testRemoveUser() throws Exception {
        UserInfo user  = createUser("CarDevicePolicyManagerTest.testRemoveUser");
        Log.d(TAG, "removing user " + user.toFullString());

        RemoveUserResult result = mCarDpm.removeUser(user.getUserHandle());
        Log.d(TAG, "result: " + result);

        assertWithMessage("Failed to remove user%s: %s", user.toFullString(), result)
                .that(result.isSuccess()).isTrue();
    }

    @Test
    public void testCreateUser() throws Exception {
        String name = "CarDevicePolicyManagerTest.testCreateUser";
        int type = CarDevicePolicyManager.USER_TYPE_REGULAR;
        Log.d(TAG, "creating new user with name " + name + " and type " + type);

        CreateUserResult result = mCarDpm.createUser(name, type);
        Log.d(TAG, "result: " + result);
        UserHandle user = result.getUserHandle();

        try {
            assertWithMessage("Failed to create user named %s and type %s: %s", name, type,
                    result).that(result.isSuccess()).isTrue();
        } finally {
            if (user != null) {
                removeUser(user.getIdentifier());
            }
        }
    }

    @Test
    public void testLockNow_safe() throws Exception {
        assertScreenOn();

        runSecureDeviceTest(()-> {
            setDpmSafety(/* safe= */ true);

            try {
                mDpm.lockNow();

                assertLockedEventually();
                assertScreenOn();
            } finally {
                setDpmSafety(/* safe= */ true);
            }
        });
    }

    @Test
    public void testLockNow_unsafe() throws Exception {
        assertScreenOn();

        runSecureDeviceTest(()-> {
            setDpmSafety(/* safe= */ false);

            try {
                UnsafeStateException e = expectThrows(UnsafeStateException.class,
                        () -> mDpm.lockNow());

                assertWithMessage("Invalid operation on %s", e).that(e.getOperation())
                        .isEqualTo(DevicePolicyManager.OPERATION_LOCK_NOW);
                assertUnlocked();
                assertScreenOn();
            } finally {
                setDpmSafety(/* safe= */ true);
            }
        });
    }

    private void runSecureDeviceTest(@NonNull Runnable test) {
        unlockDevice();
        setUserPin(1234);

        try {
            test.run();
        } finally {
            resetUserPin(1234);
        }
    }

    private void unlockDevice() {
        runShellCommand("input keyevent KEYCODE_POWER");
        runShellCommand("input keyevent KEYCODE_WAKEUP");
        runShellCommand("wm dismiss-keyguard");
        assertUnLockedEventually();
    }

    private void setUserPin(int pin) {
        runShellCommand("locksettings set-pin %d", pin);
    }

    private void resetUserPin(int oldPin) {
        runShellCommand("locksettings clear --old %d", oldPin);
    }

    private void assertUnlocked() {
        assertWithMessage("device is locked").that(mKeyguardManager.isDeviceLocked()).isFalse();
        assertWithMessage("keyguard is locked").that(mKeyguardManager.isKeyguardLocked()).isFalse();
    }

    private void assertUnLockedEventually() {
        eventually(() -> assertUnlocked());
    }

    private void assertLocked() {
        assertDeviceSecure();
        assertWithMessage("device is unlocked").that(mKeyguardManager.isDeviceLocked())
            .isTrue();
        assertWithMessage("keyguard is unlocked").that(mKeyguardManager.isKeyguardLocked())
            .isTrue();
    }

    private void assertLockedEventually() {
        eventually(() -> assertLocked());
    }

    private void assertDeviceSecure() {
        assertWithMessage("device is not secure / user credentials not set")
                .that(mKeyguardManager.isDeviceSecure()).isTrue();
    }

    private void assertScreenOn() {
        assertWithMessage("screen is off").that(mPowerManager.isInteractive()).isTrue();
    }

    private void setDpmSafety(boolean safe) {
        requireNonUserBuild();
        String state = safe ? "park" : "drive";
        runShellCommand("cmd car_service emulate-driving-state %s", state);
    }

    // TODO(b/169779216): move methods below to superclass once more tests use them

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

    private void removeUser(@UserIdInt int userId) throws Exception {
        Log.d(TAG, "Removing user " + userId);

        UserRemovalResult result = mCarUserManager.removeUser(userId);
        Log.d(TAG, "result: " + result);
        assertWithMessage("Could not remove user %s: %s", userId, result).that(result.isSuccess())
                .isTrue();
    }
}
