/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.car.user;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.myUid;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.ICarUserService;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * API to manage users related to car.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class CarUserManager extends CarManagerBase {

    private static final String TAG = CarUserManager.class.getSimpleName();

    /**
     *  User id representing invalid user.
     *
     * @hide
     */
    public static final int INVALID_USER_ID = UserHandle.USER_NULL;

    // TODO(b/144120654): STOPSHIP - set to false
    private static final boolean DBG = true;

    /**
     * {@link UserLifecycleEvent} called when the user is starting.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int USER_LIFECYCLE_EVENT_TYPE_STARTING = 1;

    /**
     * {@link UserLifecycleEvent} called when the user is switching.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int USER_LIFECYCLE_EVENT_TYPE_SWITCHING = 2;

    /**
     * {@link UserLifecycleEvent} called after the user was unlocked.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int USER_LIFECYCLE_EVENT_TYPE_UNLOCKED = 3;

    /**
     * {@link UserLifecycleEvent} called when the user is stopping.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int USER_LIFECYCLE_EVENT_TYPE_STOPPING = 4;

    /**
     * {@link UserLifecycleEvent} called after the user stoppped.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int USER_LIFECYCLE_EVENT_TYPE_STOPPED = 5;

    /** @hide */
    @IntDef(prefix = { "USER_LIFECYCLE_EVENT_TYPE_" }, value = {
            USER_LIFECYCLE_EVENT_TYPE_STARTING,
            USER_LIFECYCLE_EVENT_TYPE_SWITCHING,
            USER_LIFECYCLE_EVENT_TYPE_UNLOCKED,
            USER_LIFECYCLE_EVENT_TYPE_STOPPING,
            USER_LIFECYCLE_EVENT_TYPE_STOPPED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserLifecycleEventType{}

    /** @hide */
    public static final String BUNDLE_PARAM_ACTION = "action";
    /** @hide */
    public static final String BUNDLE_PARAM_PREVIOUS_USER_HANDLE = "previous_user";

    private final Object mLock = new Object();
    private final ICarUserService mService;

    @Nullable
    @GuardedBy("mLock")
    private ArrayMap<UserLifecycleListener, Executor> mListeners;

    @Nullable
    @GuardedBy("mLock")
    private LifecycleResultReceiver mReceiver;

    /**
     * @hide
     */
    @VisibleForTesting
    public CarUserManager(@NonNull Car car, @NonNull IBinder service) {
        super(car);
        mService = ICarUserService.Stub.asInterface(service);
    }

    /**
     * Creates a driver who is a regular user and is allowed to login to the driving occupant zone.
     *
     * @param name The name of the driver to be created.
     * @param admin Whether the created driver will be an admin.
     * @return user id of the created driver, or {@code INVALID_USER_ID} if the driver could
     *         not be created.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @Nullable
    public int createDriver(@NonNull String name, boolean admin) {
        try {
            UserInfo ui = mService.createDriver(name, admin);
            return ui != null ? ui.id : INVALID_USER_ID;
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, null);
        }
    }

    /**
     * Creates a passenger who is a profile of the given driver.
     *
     * @param name The name of the passenger to be created.
     * @param driverId User id of the driver under whom a passenger is created.
     * @return user id of the created passenger, or {@code INVALID_USER_ID} if the passenger
     *         could not be created.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @Nullable
    public int createPassenger(@NonNull String name, @UserIdInt int driverId) {
        try {
            UserInfo ui = mService.createPassenger(name, driverId);
            return ui != null ? ui.id : INVALID_USER_ID;
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, null);
        }
    }

    /**
     * Switches a driver to the given user.
     *
     * @param driverId User id of the driver to switch to.
     * @return {@code true} if user switching succeeds, or {@code false} if it fails.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean switchDriver(@UserIdInt int driverId) {
        try {
            return mService.switchDriver(driverId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        }
    }

    /**
     * Returns all drivers who can occupy the driving zone. Guest users are included in the list.
     *
     * @return the list of user ids who can be a driver on the device.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @NonNull
    public List<Integer> getAllDrivers() {
        try {
            return getUserIdsFromUserInfos(mService.getAllDrivers());
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, Collections.emptyList());
        }
    }

    /**
     * Returns all passengers under the given driver.
     *
     * @param driverId User id of a driver.
     * @return the list of user ids who are passengers under the given driver.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @NonNull
    public List<Integer> getPassengers(@UserIdInt int driverId) {
        try {
            return getUserIdsFromUserInfos(mService.getPassengers(driverId));
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, Collections.emptyList());
        }
    }

    /**
     * Assigns the passenger to the zone and starts the user if it is not started yet.
     *
     * @param passengerId User id of the passenger to be started.
     * @param zoneId Zone id to which the passenger is assigned.
     * @return {@code true} if the user is successfully started or the user is already running.
     *         Otherwise, {@code false}.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean startPassenger(@UserIdInt int passengerId, int zoneId) {
        try {
            return mService.startPassenger(passengerId, zoneId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        }
    }

    /**
     * Stops the given passenger.
     *
     * @param passengerId User id of the passenger to be stopped.
     * @return {@code true} if successfully stopped, or {@code false} if failed.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean stopPassenger(@UserIdInt int passengerId) {
        try {
            return mService.stopPassenger(passengerId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        }
    }

    /**
     * Adds a listener for {@link UserLifecycleEvent user lifecycle events}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {INTERACT_ACROSS_USERS, INTERACT_ACROSS_USERS_FULL})
    public void addListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull UserLifecycleListener listener) {
        checkInteractAcrossUsersPermission();

        // TODO(b/144120654): add unit tests to validate input
        // - executor cannot be null
        // - listener cannot be null
        // - listener must not be added before

        synchronized (mLock) {
            if (mReceiver == null) {
                mReceiver = new LifecycleResultReceiver();
                try {
                    Log.i(TAG, "Setting lifecycle receiver for uid " + myUid());
                    mService.setLifecycleListenerForUid(mReceiver);
                } catch (RemoteException e) {
                    handleRemoteExceptionFromCarService(e);
                }
            } else {
                if (DBG) Log.d(TAG, "Already set receiver for uid " + myUid());
            }

            if (mListeners == null) {
                mListeners = new ArrayMap<>(1); // Most likely app will have just one listener
            }
            if (DBG) Log.d(TAG, "Adding listener: " + listener);
            mListeners.put(listener, executor);
        }
    }

    /**
     * Removes a listener for {@link UserLifecycleEvent user lifecycle events}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {INTERACT_ACROSS_USERS, INTERACT_ACROSS_USERS_FULL})
    public void removeListener(@NonNull UserLifecycleListener listener) {
        checkInteractAcrossUsersPermission();

        // TODO(b/144120654): add unit tests to validate input
        // - listener cannot be null
        // - listener must not be added before
        synchronized (mLock) {
            if (mListeners == null) {
                Log.w(TAG, "removeListener(): no listeners for uid " + myUid());
                return;
            }

            mListeners.remove(listener);

            if (!mListeners.isEmpty()) {
                if (DBG) Log.d(TAG, "removeListeners(): still " + mListeners.size() + " left");
                return;
            }
            mListeners = null;

            if (mReceiver == null) {
                Log.wtf(TAG, "removeListener(): receiver already null");
                return;
            }

            Log.i(TAG, "Removing lifecycle receiver for uid=" + myUid());
            try {
                mService.resetLifecycleListenerForUid();
                mReceiver = null;
            } catch (RemoteException e) {
                handleRemoteExceptionFromCarService(e);
            }
        }
    }

    /** @hide */
    @TestApi
    // TODO(b/144120654): temp method used by CTS; will eventually be refactored to take a listener
    @UserIdInt
    public int createUser(@Nullable String name) {
        Log.i(TAG, "createUser()"); // name is PII
        UserManager userManager = getContext().getSystemService(UserManager.class);
        UserInfo info = userManager.createUser(name, /* flags= */ 0);
        return info.id;
    }

    /** @hide */
    @TestApi
    // TODO(b/144120654): temp method used by CTS; will eventually be refactored to take a listener
    public void removeUser(@UserIdInt int userId) {
        Log.i(TAG, "removeUser(" + userId + ")");
        UserManager userManager = getContext().getSystemService(UserManager.class);
        userManager.removeUser(userId);
    }

    /**
     * {@code IResultReceiver} used to receive lifecycle events and dispatch to the proper listener.
     */
    private class LifecycleResultReceiver extends IResultReceiver.Stub {
        public void send(int resultCode, Bundle resultData) {
            if (resultData == null) {
                Log.w(TAG, "Received result (" + resultCode + ") without data");
                return;
            }
            UserHandle toHandle = new UserHandle(resultCode);
            UserHandle fromHandle = resultData.getParcelable(BUNDLE_PARAM_PREVIOUS_USER_HANDLE);
            int eventType = resultData.getInt(BUNDLE_PARAM_ACTION);
            UserLifecycleEvent event = new UserLifecycleEvent(eventType, fromHandle, toHandle);
            ArrayMap<UserLifecycleListener, Executor> listeners;
            synchronized (mLock) {
                listeners = mListeners;
            }
            if (listeners == null) {
                Log.w(TAG, "No listeners for event " + event);
                return;
            }
            for (int i = 0; i < listeners.size(); i++) {
                UserLifecycleListener listener = listeners.keyAt(i);
                Executor executor = listeners.valueAt(i);
                if (DBG) Log.d(TAG, "Calling listener " + listener + " for event " + event);
                executor.execute(() -> listener.onEvent(event));
            }
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        // nothing to do
    }

    /**
     * @hide
     */
    @TestApi
    public static String lifecycleEventTypeToString(@UserLifecycleEventType int type) {
        switch (type) {
            case USER_LIFECYCLE_EVENT_TYPE_STARTING:
                return "STARTING";
            case USER_LIFECYCLE_EVENT_TYPE_SWITCHING:
                return "SWITCHING";
            case USER_LIFECYCLE_EVENT_TYPE_UNLOCKED:
                return "UNLOCKED";
            case USER_LIFECYCLE_EVENT_TYPE_STOPPING:
                return "STOPPING";
            case USER_LIFECYCLE_EVENT_TYPE_STOPPED:
                return "STOPPED";
            default:
                return "UNKNOWN-" + type;
        }
    }

    private List<Integer> getUserIdsFromUserInfos(List<UserInfo> infos) {
        List<Integer> ids = new ArrayList<>(infos.size());
        for (UserInfo ui : infos) {
            ids.add(ui.id);
        }
        return ids;
    }

    private void checkInteractAcrossUsersPermission() {
        Context context = getContext();
        if (context.checkSelfPermission(INTERACT_ACROSS_USERS) != PERMISSION_GRANTED
                && context.checkSelfPermission(INTERACT_ACROSS_USERS_FULL) != PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Must have either " + android.Manifest.permission.INTERACT_ACROSS_USERS + " or "
                            + android.Manifest.permission.INTERACT_ACROSS_USERS_FULL
                            + " permission");
        }
    }

    /**
     * Defines a lifecycle event for an Android user.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public final class UserLifecycleEvent {
        private final @UserLifecycleEventType int mEventType;
        private final @NonNull UserHandle mUserHandle;
        private final @Nullable UserHandle mPreviousUserHandle;

        private UserLifecycleEvent(@UserLifecycleEventType int eventType,
                @NonNull UserHandle from, @Nullable UserHandle to) {
            mEventType = eventType;
            mPreviousUserHandle = from;
            mUserHandle = to;
        }

        /**
         * Gets the event type.
         *
         * @return either {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_STARTING},
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_SWITCHING},
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_UNLOCKED},
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_STOPPING}, or
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_STOPPED}.
         */
        @UserLifecycleEventType
        public int getEventType() {
            return mEventType;
        }

        /**
         * Gets the handle of the user whose event is being reported.
         */
        @NonNull
        public UserHandle getUserHandle() {
            return mUserHandle;
        }

        /**
         * Gets the handle of the user being switched from.
         *
         * <p>This method returns {@code null} for all event types but
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_SWITCHING}.
         */
        @Nullable
        public UserHandle getPreviousUserHandle() {
            return mPreviousUserHandle;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("Event[type=")
                    .append(lifecycleEventTypeToString(mEventType));
            if (mPreviousUserHandle != null) {
                builder
                    .append(",from=").append(mPreviousUserHandle)
                    .append(",to=").append(mUserHandle);
            } else {
                builder.append(",user=").append(mUserHandle);
            }

            return builder.append(']').toString();
        }
    }

    /**
     * Listener for Android User lifecycle events.
     *
     * <p>Must be registered using {@link CarUserManager#addListener(UserLifecycleListener)} and
     * unregistered through {@link CarUserManager#removeListener(UserLifecycleListener)}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public interface UserLifecycleListener {

        /**
         * Called to notify the given {@code event}.
         */
        void onEvent(@NonNull UserLifecycleEvent event);
    }
}
