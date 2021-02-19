/*
 * Copyright (C) 2015 The Android Open Source Project
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
import static com.android.compatibility.common.util.TestUtils.BooleanSupplierWithThrow;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.car.Car;
import android.car.test.util.AndroidHelper;
import android.car.user.CarUserManager;
import android.car.user.UserCreationResult;
import android.car.user.UserRemovalResult;
import android.car.user.UserSwitchResult;
import android.car.util.concurrent.AsyncFuture;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

abstract class CarApiTestBase {

    private static final String TAG = CarApiTestBase.class.getSimpleName();

    private static final long REMOVE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10_000);
    private static final long SWITCH_USER_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10_000);
    protected static final long DEFAULT_WAIT_TIMEOUT_MS = 60_000;

    /**
     * Constant used to wait blindly, when there is no condition that can be checked.
     */
    private static final int SUSPEND_TIMEOUT_MS = 5_000;
    /**
     * How long to sleep (multiple times) while waiting for a condition.
     */
    private static final int SMALL_NAP_MS = 100;

    protected static final Context sContext = InstrumentationRegistry.getInstrumentation()
            .getTargetContext();

    private Car mCar;
    private CarUserManager mCarUserManager;
    protected UserManager mUserManager;

    protected final DefaultServiceConnectionListener mConnectionListener =
            new DefaultServiceConnectionListener();
    private final CountDownLatch mUserRemoveLatch = new CountDownLatch(1);
    private final List<Integer> mUsersToRemove = new ArrayList<>();

    @Before
    public final void setFixturesAndConnectToCar() throws Exception {
        mCar = Car.createCar(getContext(), mConnectionListener);
        mCar.connect();
        mConnectionListener.waitForConnection(DEFAULT_WAIT_TIMEOUT_MS);
        mCarUserManager = getCarService(Car.CAR_USER_SERVICE);
        mUserManager = getContext().getSystemService(UserManager.class);

        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mUserRemoveLatch.countDown();
            }
        }, filter);
    }

    @After
    public void tearDown() throws Exception {
        mCar.disconnect();
        for (Integer userId : mUsersToRemove) {
            if (hasUser(userId)) {
                removeUser(userId);
            }
        }
    }

    protected Car getCar() {
        return mCar;
    }

    protected final Context getContext() {
        return sContext;
    }

    @SuppressWarnings("TypeParameterUnusedInFormals") // error prone complains about returning <T>
    protected final <T> T getCarService(@NonNull String serviceName) {
        assertThat(serviceName).isNotNull();
        Object service = mCar.getCarManager(serviceName);
        assertWithMessage("Could not get service %s", serviceName).that(service).isNotNull();

        @SuppressWarnings("unchecked")
        T castService = (T) service;
        return castService;
    }

    protected static void assertMainThread() {
        assertThat(Looper.getMainLooper().isCurrentThread()).isTrue();
    }

    protected static final class DefaultServiceConnectionListener implements ServiceConnection {
        private final Semaphore mConnectionWait = new Semaphore(0);

        public void waitForConnection(long timeoutMs) throws InterruptedException {
            mConnectionWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            assertMainThread();
            mConnectionWait.release();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            assertMainThread();
        }
    }

    protected static void suspendToRamAndResume() throws Exception {
        Log.d(TAG, "Emulate suspend to RAM and resume");
        PowerManager powerManager = sContext.getSystemService(PowerManager.class);
        runShellCommand("cmd car_service suspend");
        // Check for suspend success
        waitUntil("Suspsend is not successful",
                SUSPEND_TIMEOUT_MS, () -> !powerManager.isScreenOn());

        // Force turn off garage mode
        runShellCommand("cmd car_service garage-mode off");
        runShellCommand("cmd car_service resume");
    }

    protected static boolean waitUntil(String msg, long timeoutMs,
            BooleanSupplierWithThrow condition) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in waitUntil: " + msg);
                throw new RuntimeException(e);
            }
            SystemClock.sleep(SMALL_NAP_MS);
        } while (SystemClock.elapsedRealtime() < deadline);

        fail(msg + " after: " + timeoutMs + "ms");
        return false;
    }

    protected void requireNonUserBuild() {
        assumeFalse("Requires Shell commands that are not available on user builds", Build.IS_USER);
    }

    @NonNull
    protected UserInfo createUser(@Nullable String name) throws Exception {
        Log.d(TAG, "Creating new user " + name);

        assertCanAddUser();

        UserCreationResult result = mCarUserManager.createUser(name, /* flags= */ 0)
                .get(DEFAULT_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Log.d(TAG, "result: " + result);
        assertWithMessage("Could not create user %s: %s", name, result)
                .that(result.isSuccess())
                .isTrue();
        mUsersToRemove.add(result.getUser().id);
        return result.getUser();
    }

    protected void assertCanAddUser() {
        Bundle restrictions = mUserManager.getUserRestrictions();
        Log.d(TAG, "Restrictions for user " + getContext().getUserId() + ": "
                + AndroidHelper.toString(restrictions));
        assertWithMessage("Cannot add user due to %s restriction", UserManager.DISALLOW_ADD_USER)
                .that(restrictions.getBoolean(UserManager.DISALLOW_ADD_USER, false)).isFalse();
    }

    protected void waitForUserRemoval(@UserIdInt int userId) throws Exception {
        boolean result = mUserRemoveLatch.await(REMOVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertWithMessage("Timeout waiting for removeUser. userId = %s", userId)
                .that(result)
                .isTrue();
    }

    protected void switchUser(@UserIdInt int userId) throws Exception {
        Log.i(TAG, "Switching to user " + userId + " using CarUserManager");

        AsyncFuture<UserSwitchResult> future = mCarUserManager.switchUser(userId);
        UserSwitchResult result = future.get(SWITCH_USER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Log.d(TAG, "Result: " + result);

        assertWithMessage("Timeout waiting for the user switch to %s. Result: %s", userId, result)
                .that(result.isSuccess())
                .isTrue();
    }

    protected void removeUser(@UserIdInt int userId) {
        Log.d(TAG, "Removing user " + userId);

        UserRemovalResult result = mCarUserManager.removeUser(userId);
        Log.d(TAG, "result: " + result);
        assertWithMessage("Could not remove user %s: %s", userId, result)
                .that(result.isSuccess())
                .isTrue();
    }

    @Nullable
    protected UserInfo getUser(@UserIdInt int id) {
        List<UserInfo> list = mUserManager.getUsers();

        for (UserInfo user : list) {
            if (user.id == id) {
                return user;
            }
        }
        return null;
    }

    protected boolean hasUser(@UserIdInt int id) {
        return getUser(id) != null;
    }
}
