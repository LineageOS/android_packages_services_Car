/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.automotive;

import com.android.internal.protolog.common.IProtoLogGroup;

import java.util.UUID;

/**
 * Defines logging groups for ProtoLog.
 * <p>This file is used by the ProtoLogTool to generate optimized logging code. All of its
 * dependencies must be included in services.core.wm.protologgroups build target.
 */
public enum CarWmShellProtoLogGroups implements IProtoLogGroup {

    CAR_WM_SHELL(Consts.ENABLE_DEBUG, true, Consts.TAG_CAR_WM_SHELL),
    TEST_GROUP(true, false, "CarWmShellProtoLogTest");

    private final boolean mEnabled;
    private volatile boolean mLogToLogcat;
    private final String mTag;

    /**
     * @param enabled     set to false to exclude all log statements for this group from
     *                    compilation, they will not be available in runtime.
     * @param logToLogcat enable text logging for the group
     * @param tag         name of the source of the logged message
     */
    CarWmShellProtoLogGroups(boolean enabled, boolean logToLogcat, String tag) {
        this.mEnabled = enabled;
        this.mLogToLogcat = logToLogcat;
        this.mTag = tag;
    }

    @Override
    public boolean isEnabled() {
        return mEnabled;
    }

    @Override
    public boolean isLogToLogcat() {
        return mLogToLogcat;
    }

    @Override
    public String getTag() {
        return mTag;
    }

    @Override
    public void setLogToLogcat(boolean logToLogcat) {
        this.mLogToLogcat = logToLogcat;
    }

    @Override
    public int getId() {
        return Consts.START_ID + this.ordinal();
    }

    private static class Consts {
        private static final String TAG_CAR_WM_SHELL = "CarWmShell";

        private static final boolean ENABLE_DEBUG = true;

        private static final int START_ID = (int) (
                UUID.nameUUIDFromBytes(CarWmShellProtoLogGroups.class.getName().getBytes())
                        .getMostSignificantBits() % Integer.MAX_VALUE);
    }
}
