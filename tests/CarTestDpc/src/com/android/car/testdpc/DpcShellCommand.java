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

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.PrintWriter;
import java.util.Arrays;

final class DpcShellCommand {

    private static final String TAG = DpcShellCommand.class.getSimpleName();

    private final DevicePolicyManager mDpm;
    private final ComponentName mAdmin;
    private final PrintWriter mWriter;
    private final String[] mArgs;

    private static final String CMD_ADD_USER_RESTRICTION = "add-user-restriction";
    private static final String CMD_CLR_USER_RESTRICTION = "clear-user-restriction";
    private static final String CMD_HELP = "help";

    DpcShellCommand(Context context, PrintWriter writer, String[] args) {
        mDpm = context.getSystemService(DevicePolicyManager.class);
        mAdmin = new ComponentName(context, DpcReceiver.class.getName());
        Log.d(TAG, "Created for user " + Process.myUserHandle() + " and component " + mAdmin
                + " for command " + Arrays.toString(args));
        mWriter = writer;
        mArgs = args;
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
                default:
                    mWriter.println("Invalid command: " + cmd);
                    runHelp();
                    return;
            }
        } catch (Exception e) {
            mWriter.println("Failed to execute " + Arrays.toString(mArgs) + ": " + e);
            return;
        }
    }

    private void runHelp() {
        mWriter.println("Commands: ");
        mWriter.printf("%s\n", CMD_HELP);
        mWriter.println("\tList all available commands for device policy.");
        mWriter.printf("%s <key>\n", CMD_ADD_USER_RESTRICTION);
        mWriter.println("\tSet a user restriction specified by the key.");
        mWriter.printf("%s <key>\n", CMD_CLR_USER_RESTRICTION);
        mWriter.println("\tClear a user restriction specified by the key.");
    }

    private void runAddUserRestriction() {
        String restriction = mArgs[1];
        Log.i(TAG, "Calling addUserRestriction(" + restriction + ")");
        mDpm.addUserRestriction(mAdmin, restriction);
    }

    private void runClearUserRestriction() {
        String restriction = mArgs[1];
        Log.i(TAG, "Calling clearUserRestriction(" + restriction + ")");
        mDpm.clearUserRestriction(mAdmin, restriction);
    }
}
