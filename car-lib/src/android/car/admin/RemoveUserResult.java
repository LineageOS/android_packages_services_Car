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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.car.user.UserRemovalResult;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Result of a {@link CarDevicePolicyManager#removeUser(android.os.UserHandle)} operation.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class RemoveUserResult {

    /**
     * User was removed.
     */
    public static final int STATUS_SUCCESS = 1;

    /**
     * User was removed, and it was the last admin user.
     */
    public static final int STATUS_SUCCESS_LAST_ADMIN_REMOVED = 2;

    /**
     * User was not removed because it is the current foreground user.
     */
    // TODO(b/155913815): change to STATUS_SUCCESS_SET_EPHEMERAL  / update javadoc
    public static final int STATUS_FAILURE_TARGET_USER_IS_CURRENT_USER = 3;

    /**
     * User was not removed because it doesn't exist.
     */
    public static final int STATUS_FAILURE_USER_DOES_NOT_EXIST = 4;

    /**
     * User was not removed for some other reason not described above
     */
    public static final int STATUS_FAILURE_GENERIC = 100;

    /** @hide */
    @IntDef(prefix = "STATUS_", value = {
            STATUS_SUCCESS,
            STATUS_SUCCESS_LAST_ADMIN_REMOVED,
            STATUS_FAILURE_TARGET_USER_IS_CURRENT_USER,
            STATUS_FAILURE_USER_DOES_NOT_EXIST,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {
    }

    private final @Status int mStatus;

    /**
     * Must mark as public even though unit test is on the same package, as actual classes are
     * provided by different jar files.
     *
     * @hide
     */
    @VisibleForTesting
    public RemoveUserResult(@Nullable UserRemovalResult result) {
        int status = result == null ? STATUS_FAILURE_GENERIC : result.getStatus();

        switch (status) {
            case UserRemovalResult.STATUS_SUCCESSFUL:
                mStatus = RemoveUserResult.STATUS_SUCCESS;
                break;
            case UserRemovalResult.STATUS_SUCCESSFUL_LAST_ADMIN_REMOVED:
                mStatus = RemoveUserResult.STATUS_SUCCESS_LAST_ADMIN_REMOVED;
                break;
            case UserRemovalResult.STATUS_TARGET_USER_IS_CURRENT_USER:
                mStatus = RemoveUserResult.STATUS_FAILURE_TARGET_USER_IS_CURRENT_USER;
                break;
            case UserRemovalResult.STATUS_USER_DOES_NOT_EXIST:
                mStatus = RemoveUserResult.STATUS_FAILURE_USER_DOES_NOT_EXIST;
                break;
            default:
                mStatus = STATUS_FAILURE_GENERIC;
        }
    }

    /**
     * Gets the specifif result of the operation.
     *
     * @return either {@link RemoveUserResult#STATUS_SUCCESS},
     *         {@link RemoveUserResult#STATUS_SUCCESS_LAST_ADMIN_REMOVED},
     *         {@link RemoveUserResult#STATUS_FAILURE_TARGET_USER_IS_CURRENT_USER}, or
     *         {@link RemoveUserResult#STATUS_FAILURE_USER_DOES_NOT_EXIST}.
     */
    public @Status int getStatus() {
        return mStatus;
    }

    /**
     * Gets whether the operation was successful or not.
     */
    public boolean isSuccess() {
        return mStatus == STATUS_SUCCESS || mStatus == STATUS_SUCCESS_LAST_ADMIN_REMOVED;
    }

    @Override
    public String toString() {
        return "RemoveUserResult[" + statusToString(mStatus) + "]";
    }

    private String statusToString(int status) {
        switch (status) {
            case STATUS_SUCCESS:
                return "SUCCESS";
            case STATUS_SUCCESS_LAST_ADMIN_REMOVED:
                return "LAST_ADMIN_REMOVED";
            case STATUS_FAILURE_TARGET_USER_IS_CURRENT_USER:
                return "FAILURE_TARGET_USER_IS_CURRENT_USER";
            case STATUS_FAILURE_USER_DOES_NOT_EXIST:
                return "FAILURE_USER_DOES_NOT_EXIST";
            case STATUS_FAILURE_GENERIC:
                return "FAILURE_GENERIC";
            default:
                return "UNKNOWN-" + status;
        }
    }
}
