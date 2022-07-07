/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.car.testdpc;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

import com.android.car.testdpc.remotedpm.DevicePolicyManagerInterface;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

final class DpcShellCommand {

    private static final String TAG = DpcShellCommand.class.getSimpleName();

    private final DevicePolicyManager mDpm;
    private final ComponentName mAdmin;
    private final PrintWriter mWriter;
    private final String[] mArgs;

    private final List<DevicePolicyManagerInterface> mPoInterfaces;
    private final DevicePolicyManagerInterface mDoInterface;

    /* Args has to be at least of size 4 to account for cmd, ARG_USER, userID, key */
    private static final int ADD_USER_RESTRICTION_ARG_LEN = 4;

    private static final String ARG_TARGET_USER = "--target-user";
    private static final String CMD_GET_AFFILIATION_IDS = "get-affiliation-ids";
    private static final String CMD_SET_AFFILIATION_IDS = "set-affiliation-ids";
    private static final String CMD_IS_USER_AFFILIATED = "is-user-affiliated";
    private static final String CMD_ADD_USER_RESTRICTION = "add-user-restriction";
    private static final String CMD_CLR_USER_RESTRICTION = "clear-user-restriction";
    private static final String CMD_GET_USER_RESTRICTIONS = "get-user-restrictions";
    private static final String CMD_HELP = "help";
    private static final String CMD_REBOOT = "reboot";

    DpcShellCommand(Context context, PrintWriter writer, String[] args,
            List<DevicePolicyManagerInterface> profileOwners,
            DevicePolicyManagerInterface deviceOwner) {
        mDpm = context.getSystemService(DevicePolicyManager.class);
        mAdmin = new ComponentName(context, DpcReceiver.class.getName());
        Log.d(TAG, "Created for user " + Process.myUserHandle() + " and component " + mAdmin
                + " for command " + Arrays.toString(args));
        mWriter = writer;
        mArgs = args;

        mDoInterface = deviceOwner;
        mPoInterfaces = profileOwners;
    }

    void run() {
        if (mArgs.length == 0) {
            mWriter.println("Missing cmd");
            return;
        }
        String cmd = mArgs[0];
        try {
            switch (cmd) {
                case CMD_HELP:
                    runHelp();
                    break;
                case CMD_ADD_USER_RESTRICTION:
                    runAddUserRestriction();
                    break;
                case CMD_CLR_USER_RESTRICTION:
                    runClearUserRestriction();
                    break;
                case CMD_GET_USER_RESTRICTIONS:
                    runGetUserRestrictions();
                    break;
                case CMD_IS_USER_AFFILIATED:
                    runIsUserAffiliated();
                    break;
                case CMD_GET_AFFILIATION_IDS:
                    runGetAffiliationIds();
                    break;
                case CMD_SET_AFFILIATION_IDS:
                    runSetAffiliationIds();
                    break;
                case CMD_REBOOT:
                    runReboot();
                    break;
                default:
                    mWriter.println("Invalid command: " + cmd);
                    runHelp();
                    return;
            }
        } catch (Exception e) {
            mWriter.println("Failed to execute " + Arrays.toString(mArgs) + ": " + e);
            Log.e(TAG, "Failed to execute " + Arrays.toString(mArgs), e);
            return;
        }
    }

    private void runHelp() {
        mWriter.println("Commands: ");
        mWriter.printf("%s\n", CMD_HELP);
        mWriter.println("\tList all available commands for device policy.");
        mWriter.printf("%s %s <user_id> <key>\n", CMD_ADD_USER_RESTRICTION, ARG_TARGET_USER);
        mWriter.println("\tSet a user restriction on the user with specified user_id with the");
        mWriter.println("\t given key.");
        mWriter.printf("%s <key>\n", CMD_CLR_USER_RESTRICTION);
        mWriter.println("\tClear a user restriction specified by the key.");
        mWriter.printf("%s\n", CMD_GET_USER_RESTRICTIONS);
        mWriter.println("\tDisplay all active user restrictions.");
        mWriter.printf("%s (<affiliation-ids>)\n", CMD_SET_AFFILIATION_IDS);
        mWriter.println("\tSet affiliation ids (space separated list of strings)");
        mWriter.println("\tfor a specified user. An empty list clears the ids.");
        mWriter.printf("%s\n", CMD_GET_AFFILIATION_IDS);
        mWriter.println("\tGet affiliation id(s) for a specified user.");
        mWriter.printf("%s\n", CMD_IS_USER_AFFILIATED);
        mWriter.println("\tReturns whether this user is affiliated with the device.");
        mWriter.printf("%s\n", CMD_REBOOT);
        mWriter.println("\tReboots the device.");
    }

    private void runAddUserRestriction() {
        Log.i(TAG, "Calling addUserRestriction()");

        if (mArgs.length != ADD_USER_RESTRICTION_ARG_LEN || !(ARG_TARGET_USER.equals(mArgs[1]))) {
            mWriter.println("Error: invalid argument format. Run help to see valid format.");
            return;
        }

        UserHandle target = getUserHandleFromUserId(mArgs[2]);
        if (target == null) {
            mWriter.println("Could not find user");
            return;
        }

        if (mDoInterface.getUser().equals(target)) {
            Log.d(TAG, mDoInterface.getUser() + ": addUserRestriction("
                    + mAdmin.flattenToShortString() + ", " + mArgs[3]);
            mDoInterface.addUserRestriction(mAdmin, /* key= */ mArgs[3]);
            return;
        }

        try {
            DevicePolicyManagerInterface profileOwner = mPoInterfaces.stream()
                    .filter((dpm) -> dpm.getUser().equals(target))
                    .findAny().get();
            Log.d(TAG, profileOwner.getUser() + ": addUserRestriction("
                    + mAdmin.flattenToShortString() + ", " + mArgs[3]);
            profileOwner.addUserRestriction(mAdmin, /* key= */ mArgs[3]);
        } catch (NoSuchElementException e) {
            mWriter.println("User not found (see logs)");
            Log.e(TAG, "User not found", e);
        }
    }

    private void runClearUserRestriction() {
        String restriction = mArgs[1];
        Log.i(TAG, "Calling clearUserRestriction(" + restriction + ")");
        mDpm.clearUserRestriction(mAdmin, restriction);
    }

    private void runGetUserRestrictions() {
        Bundle restrictions = mDpm.getUserRestrictions(mAdmin);
        if (restrictions == null || restrictions.isEmpty()) {
            mWriter.println("No restrictions.");
        } else {
            mWriter.printf("%d restriction(s): %s", restrictions.size(),
                    bundleToString(restrictions));
        }
    }

    private void runGetAffiliationIds() {
        Set<String> ids = mDpm.getAffiliationIds(mAdmin);
        List<String> idsList = new ArrayList<String>(ids);
        mWriter.printf("%d affiliation ids: ", ids.size());
        for (int i = 0; i < idsList.size(); i++) {
            if (i == idsList.size() - 1) {
                mWriter.printf("%s", idsList.get(i));
            } else {
                mWriter.printf("%s, ", idsList.get(i));
            }
        }
        mWriter.printf("\n");
    }

    private void runSetAffiliationIds() {
        Set<String> idSet = new LinkedHashSet<String>();
        if (mArgs.length > 1) {
            for (int i = 1; i < mArgs.length; i++) {
                idSet.add(mArgs[i]);
            }
        }
        Log.i(TAG, "setAffiliationIds(): ids=" + idSet);
        mDpm.setAffiliationIds(mAdmin, idSet);

        runGetAffiliationIds();
    }

    private void runIsUserAffiliated() {
        mWriter.println(mDpm.isAffiliatedUser());
    }

    private void runReboot() {
        Log.i(TAG, "Calling reboot()");
        mDoInterface.reboot(mAdmin);
    }

    /**
     * See {@link android.apps.gsa.shared.util.Util#bundleToString(Bundle)}
     */
    @NonNull
    private static String bundleToString(@NonNull Bundle bundle) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (String key : bundle.keySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            Object value = bundle.get(key);
            sb.append(key).append("=").append(value);
        }
        sb.append('}');
        return sb.toString();
    }

    @Nullable
    public UserHandle getUserHandleFromUserId(String userId) {
        UserHandle targetUser = null;
        try {
            targetUser = new UserHandle(Integer.parseInt(userId));
        } catch (NumberFormatException e) {
            mWriter.println("Could not parse target user id (see logs)");
            Log.e(TAG, "Could not parse target user id", e);
        }
        return targetUser;
    }
}
