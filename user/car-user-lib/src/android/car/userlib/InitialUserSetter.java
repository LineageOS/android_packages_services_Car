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

import static android.car.userlib.UserHalHelper.userFlagsToString;
import static android.car.userlib.UserHelper.safeName;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Helper used to set the initial Android user on boot or when resuming from RAM.
 */
public final class InitialUserSetter {

    private static final String TAG = InitialUserSetter.class.getSimpleName();

    // TODO(b/151758646): STOPSHIP if not false
    private static final boolean DBG = true;

    // TODO(b/150413304): abstract AM / UM into interfaces, then provide local and remote
    // implementation (where local is implemented by ActivityManagerInternal / UserManagerInternal)
    private final CarUserManagerHelper mHelper;
    private final UserManager mUm;

    // TODO(b/151758646): make sure it's unit tested
    private final boolean mSupportsOverrideUserIdProperty;

    private final String mNewUserName;
    private final String mNewGuestName;

    private final Consumer<UserInfo> mListener;

    public InitialUserSetter(@NonNull Context context, @NonNull Consumer<UserInfo> listener,
            boolean supportsOverrideUserIdProperty) {
        this(context, listener, /* newGuestName= */ null, supportsOverrideUserIdProperty);
    }

    public InitialUserSetter(@NonNull Context context, @NonNull Consumer<UserInfo> listener,
            @Nullable String newGuestName, boolean supportsOverrideUserIdProperty) {
        this(new CarUserManagerHelper(context), UserManager.get(context), listener,
                context.getString(com.android.internal.R.string.owner_name), newGuestName,
                supportsOverrideUserIdProperty);
    }

    @VisibleForTesting
    public InitialUserSetter(@NonNull CarUserManagerHelper helper, @NonNull UserManager um,
            @NonNull Consumer<UserInfo> listener,
            @Nullable String newUserName, @Nullable String newGuestName,
            boolean supportsOverrideUserIdProperty) {
        mHelper = helper;
        mUm = um;
        mListener = listener;
        mNewUserName = newUserName;
        mNewGuestName = newGuestName;
        mSupportsOverrideUserIdProperty = supportsOverrideUserIdProperty;
    }

    /**
     * Sets the initial user using the default behavior.
     *
     * <p>The default behavior is:
     * <ol>
     *   <li>On first boot, it creates and switches to a new user.
     *   <li>Otherwise, it will switch to either:
     *   <ol>
     *     <li>User defined by {@code android.car.systemuser.bootuseroverrideid} (when it was
     *       constructed with such option enabled).
     *     <li>Last active user (as defined by
     *       {@link android.provider.Settings..Global.LAST_ACTIVE_USER_ID}.
     *   </ol>
     * </ol>
     */
    public void executeDefaultBehavior() {
        executeDefaultBehavior(/* fallback= */ false);
    }

    private void executeDefaultBehavior(boolean fallback) {
        if (!mHelper.hasInitialUser()) {
            if (DBG) Log.d(TAG, "executeDefaultBehavior(): no initial user, creating it");
            createAndSwitchUser(mNewUserName, UserFlags.ADMIN, fallback);
        } else {
            if (DBG) Log.d(TAG, "executeDefaultBehavior(): switching to initial user");
            int userId = mHelper.getInitialUser(mSupportsOverrideUserIdProperty);
            switchUser(userId, fallback);
        }
    }


    @VisibleForTesting
    void fallbackDefaultBehavior(boolean fallback, @NonNull String reason) {
        if (!fallback) {
            // Only log the error
            Log.w(TAG, reason);
            // Must explicitly tell listener that initial user could not be determined
            notifyListener(/*initialUser= */ null);
            return;
        }
        Log.w(TAG, "Falling back to default behavior. Reason: " + reason);
        executeDefaultBehavior(/* fallback= */ false);
    }

    /**
     * Switches to the given user, falling back to {@link #fallbackDefaultBehavior(String)} if it
     * fails.
     */
    public void switchUser(@UserIdInt int userId) {
        switchUser(userId, /* fallback= */ true);
    }

    private void switchUser(@UserIdInt int userId, boolean fallback) {
        if (DBG) Log.d(TAG, "switchUser(): userId=" + userId);

        UserInfo user = mUm.getUserInfo(userId);
        if (user == null) {
            fallbackDefaultBehavior(fallback, "user with id " + userId + " doesn't exist");
            return;
        }

        UserInfo actualUser = replaceGuestIfNeeded(user);

        if (actualUser == null) {
            fallbackDefaultBehavior(fallback, "could not replace guest " + user.toFullString());
            return;
        }

        int actualUserId = actualUser.id;

        // If system user is the only user to unlock, it will be handled when boot is complete.
        if (actualUserId != UserHandle.USER_SYSTEM) {
            mHelper.unlockSystemUser();
        }

        int currentUserId = ActivityManager.getCurrentUser();
        if (actualUserId != currentUserId) {
            if (!mHelper.startForegroundUser(actualUserId)) {
                fallbackDefaultBehavior(fallback, "am.switchUser(" + actualUserId + ") failed");
                return;
            }
            mHelper.setLastActiveUser(actualUserId);
        }
        notifyListener(actualUser);

        if (actualUserId != userId) {
            Slog.i(TAG, "Removing old guest " + userId);
            if (!mUm.removeUser(userId)) {
                Slog.w(TAG, "Could not remove old guest " + userId);
            }
        }
    }

    // TODO(b/151758646): move to CarUserManagerHelper
    /**
     * Replaces {@code user} by a new guest, if necessary.
     *
     * <p>If {@code user} is not a guest, it doesn't do anything and returns the same user.
     *
     * <p>Otherwise, it marks the current guest for deletion, creates a new one, and returns the
     * new guest (or {@code null} if a new guest could not be created).
     */
    @Nullable
    public UserInfo replaceGuestIfNeeded(@NonNull UserInfo user) {
        Preconditions.checkArgument(user != null, "user cannot be null");

        if (!user.isGuest()) return user;

        Log.i(TAG, "Replacing guest (" + user.toFullString() + ")");

        int halFlags = UserFlags.GUEST;
        if (user.isEphemeral()) {
            halFlags |= UserFlags.EPHEMERAL;
        } else {
            // TODO(b/150413515): decide whether we should allow it or not. Right now we're
            // just logging, as UserManagerService will automatically set it to ephemeral if
            // platform is set to do so.
            Log.w(TAG, "guest being replaced is not ephemeral: " + user.toFullString());
        }

        if (!mUm.markGuestForDeletion(user.id)) {
            // Don't need to recover in case of failure - most likely create new user will fail
            // because there is already a guest
            Log.w(TAG, "failed to mark guest " + user.id + " for deletion");
        }

        Pair<UserInfo, String> result = createNewUser(mNewGuestName, halFlags);

        String errorMessage = result.second;
        if (errorMessage != null) {
            Log.w(TAG, "could not replace guest " + user.toFullString() + ": " + errorMessage);
            return null;
        }

        return result.first;
    }

    /**
     * Creates a new user and switches to it, falling back to
     * {@link #fallbackDefaultBehavior(String) if any of these steps fails.
     *
     * @param name (optional) name of the new user
     * @param halFlags user flags as defined by Vehicle HAL ({@code UserFlags} enum).
     */
    public void createUser(@Nullable String name, int halFlags) {
        createAndSwitchUser(name, halFlags, /* fallback= */ true);
    }

    private void createAndSwitchUser(@Nullable String name, int halFlags, boolean fallback) {
        Pair<UserInfo, String> result = createNewUser(name, halFlags);
        String reason = result.second;
        if (reason != null) {
            fallbackDefaultBehavior(fallback, reason);
            return;
        }

        switchUser(result.first.id, fallback);
    }

    /**
     * Creates a new user.
     *
     * @return on success, first element is the new user; on failure, second element contains the
     * error message.
     */
    @NonNull
    private Pair<UserInfo, String> createNewUser(@Nullable String name, int halFlags) {
        if (DBG) {
            Log.d(TAG, "createUser(name=" + safeName(name) + ", flags="
                    + userFlagsToString(halFlags) + ")");
        }

        if (UserHalHelper.isSystem(halFlags)) {
            return new Pair<>(null, "Cannot create system user");
        }

        if (UserHalHelper.isAdmin(halFlags)) {
            boolean validAdmin = true;
            if (UserHalHelper.isGuest(halFlags)) {
                Log.w(TAG, "Cannot create guest admin");
                validAdmin = false;
            }
            if (UserHalHelper.isEphemeral(halFlags)) {
                Log.w(TAG, "Cannot create ephemeral admin");
                validAdmin = false;
            }
            if (!validAdmin) {
                return new Pair<>(null, "Invalid flags for admin user");
            }
        }
        // TODO(b/150413515): decide what to if HAL requested a non-ephemeral guest but framework
        // sets all guests as ephemeral - should it fail or just warn?

        int flags = UserHalHelper.toUserInfoFlags(halFlags);
        String type = UserHalHelper.isGuest(halFlags) ? UserManager.USER_TYPE_FULL_GUEST
                : UserManager.USER_TYPE_FULL_SECONDARY;

        if (DBG) {
            Log.d(TAG, "calling am.createUser((name=" + safeName(name) + ", type=" + type
                    + ", flags=" + UserInfo.flagsToString(flags) + ")");
        }

        UserInfo userInfo = mUm.createUser(name, type, flags);
        if (userInfo == null) {
            return new Pair<>(null, "createUser(name=" + safeName(name) + ", flags="
                    + userFlagsToString(halFlags) + "): failed to create user");
        }

        if (DBG) Log.d(TAG, "user created: " + userInfo.id);
        return new Pair<>(userInfo, null);
    }

    private void notifyListener(@Nullable UserInfo initialUser) {
        if (DBG) Log.d(TAG, "notifyListener(): " + initialUser);
        mListener.accept(initialUser);
    }

    /**
     * Dumps it state.
     */
    public void dump(@NonNull PrintWriter writer) {
        writer.println("InitialUserSetter");
        String indent = "  ";
        writer.printf("%smSupportsOverrideUserIdProperty: %s\n", indent,
                mSupportsOverrideUserIdProperty);
        writer.printf("%smNewUserName: %s\n", indent, mNewUserName);
        writer.printf("%smNewGuestName: %s\n", indent, mNewGuestName);
    }
}
