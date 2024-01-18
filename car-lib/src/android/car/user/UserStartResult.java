/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.os.Parcelable;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.DataClass;

/**
 * User start results.
 *
 * @hide
 */
@DataClass(
        genToString = true,
        genHiddenConstructor = true,
        genHiddenConstDefs = true)
public final class UserStartResult implements Parcelable, OperationResult {

    /**
    * When user start is successful.
    *
    * @hide
    */
    @Status
    public static final int STATUS_SUCCESSFUL = CommonResults.STATUS_SUCCESSFUL;

    /**
    * When user start failed.
    *
    * @hide
    */
    @Status
    public static final int STATUS_ANDROID_FAILURE = CommonResults.STATUS_ANDROID_FAILURE;

    /**
     * When user to start is same as current user.
     *
     * @hide
     */
    @Status
    public static final int STATUS_SUCCESSFUL_USER_IS_CURRENT_USER =
            CommonResults.LAST_COMMON_STATUS + 1;

    /**
     * When user to start does not exist.
     *
     * @hide
     */
    @Status
    public static final int STATUS_USER_DOES_NOT_EXIST = CommonResults.LAST_COMMON_STATUS + 2;

    /**
    * Gets the user start result status.
    *
    * @return either {@link UserStartRsult#STATUS_SUCCESSFUL},
    *         {@link UserStartResult#STATUS_SUCCESSFUL_USER_IS_CURRENT_USER},
    *         {@link UserStartResult#STATUS_ANDROID_FAILURE},
    *         {@link UserStartResult#STATUS_USER_DOES_NOT_EXIST}, or
    */
    private final @Status int mStatus;

    @Override
    public boolean isSuccess() {
        return mStatus == STATUS_SUCCESSFUL || mStatus == STATUS_SUCCESSFUL_USER_IS_CURRENT_USER;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/user/UserStartResult.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /** @hide */
    @android.annotation.IntDef(prefix = "STATUS_", value = {
        STATUS_SUCCESSFUL,
        STATUS_ANDROID_FAILURE,
        STATUS_SUCCESSFUL_USER_IS_CURRENT_USER,
        STATUS_USER_DOES_NOT_EXIST
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface Status {}

    /** @hide */
    @DataClass.Generated.Member
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    public static String statusToString(@Status int value) {
        switch (value) {
            case STATUS_SUCCESSFUL:
                    return "STATUS_SUCCESSFUL";
            case STATUS_ANDROID_FAILURE:
                    return "STATUS_ANDROID_FAILURE";
            case STATUS_SUCCESSFUL_USER_IS_CURRENT_USER:
                    return "STATUS_SUCCESSFUL_USER_IS_CURRENT_USER";
            case STATUS_USER_DOES_NOT_EXIST:
                    return "STATUS_USER_DOES_NOT_EXIST";
            default: return Integer.toHexString(value);
        }
    }

    /**
     * Creates a new UserStartResult.
     *
     * @param status
     *   Gets the user start result status.
     *
     *   @return either {@link UserStartRsult#STATUS_SUCCESSFUL},
     *           {@link UserStartResult#STATUS_ANDROID_FAILURE},
     *           {@link UserStartResult#STATUS_SUCCESSFUL_USER_IS_CURRENT_USER},
     *           {@link UserStartResult#STATUS_USER_DOES_NOT_EXIST}, or
     * @hide
     */
    @DataClass.Generated.Member
    public UserStartResult(
            @Status int status) {
        this.mStatus = status;

        if (!(mStatus == STATUS_SUCCESSFUL)
                && !(mStatus == STATUS_ANDROID_FAILURE)
                && !(mStatus == STATUS_SUCCESSFUL_USER_IS_CURRENT_USER)
                && !(mStatus == STATUS_USER_DOES_NOT_EXIST)) {
            throw new java.lang.IllegalArgumentException(
                    "status was " + mStatus + " but must be one of: "
                            + "STATUS_SUCCESSFUL(" + STATUS_SUCCESSFUL + "), "
                            + "STATUS_ANDROID_FAILURE(" + STATUS_ANDROID_FAILURE + "), "
                            + "STATUS_SUCCESSFUL_USER_IS_CURRENT_USER(" + STATUS_SUCCESSFUL_USER_IS_CURRENT_USER + "), "
                            + "STATUS_USER_DOES_NOT_EXIST(" + STATUS_USER_DOES_NOT_EXIST + ")");
        }


        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * Gets the user start result status.
     *
     * @return either {@link UserStartRsult#STATUS_SUCCESSFUL},
     *         {@link UserStartResult#STATUS_ANDROID_FAILURE},
     *         {@link UserStartResult#STATUS_SUCCESSFUL_USER_IS_CURRENT_USER},
     *         {@link UserStartResult#STATUS_USER_DOES_NOT_EXIST}, or
     */
    @DataClass.Generated.Member
    public @Status int getStatus() {
        return mStatus;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "UserStartResult { " +
                "status = " + statusToString(mStatus) +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeInt(mStatus);
    }

    @Override
    @DataClass.Generated.Member
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ UserStartResult(@android.annotation.NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int status = in.readInt();

        this.mStatus = status;

        if (!(mStatus == STATUS_SUCCESSFUL)
                && !(mStatus == STATUS_ANDROID_FAILURE)
                && !(mStatus == STATUS_SUCCESSFUL_USER_IS_CURRENT_USER)
                && !(mStatus == STATUS_USER_DOES_NOT_EXIST)) {
            throw new java.lang.IllegalArgumentException(
                    "status was " + mStatus + " but must be one of: "
                            + "STATUS_SUCCESSFUL(" + STATUS_SUCCESSFUL + "), "
                            + "STATUS_ANDROID_FAILURE(" + STATUS_ANDROID_FAILURE + "), "
                            + "STATUS_SUCCESSFUL_USER_IS_CURRENT_USER(" + STATUS_SUCCESSFUL_USER_IS_CURRENT_USER + "), "
                            + "STATUS_USER_DOES_NOT_EXIST(" + STATUS_USER_DOES_NOT_EXIST + ")");
        }


        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @android.annotation.NonNull Parcelable.Creator<UserStartResult> CREATOR
            = new Parcelable.Creator<UserStartResult>() {
        @Override
        public UserStartResult[] newArray(int size) {
            return new UserStartResult[size];
        }

        @Override
        public UserStartResult createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
            return new UserStartResult(in);
        }
    };

    @DataClass.Generated(
            time = 1673056382532L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/user/UserStartResult.java",
            inputSignatures = "public static final @android.car.user.UserStartResult.Status @android.car.annotation.AddedInOrBefore int STATUS_SUCCESSFUL\npublic static final @android.car.user.UserStartResult.Status @android.car.annotation.AddedInOrBefore int STATUS_ANDROID_FAILURE\npublic static final @android.car.user.UserStartResult.Status @android.car.annotation.AddedInOrBefore int STATUS_SUCCESSFUL_USER_IS_CURRENT_USER\npublic static final @android.car.user.UserStartResult.Status @android.car.annotation.AddedInOrBefore int STATUS_USER_DOES_NOT_EXIST\nprivate final @android.car.user.UserStartResult.Status int mStatus\npublic @java.lang.Override @android.car.annotation.AddedInOrBefore boolean isSuccess()\nclass UserStartResult extends java.lang.Object implements [android.os.Parcelable, android.car.user.OperationResult]\n@com.android.car.internal.util.DataClass(genToString=true, genHiddenConstructor=true, genHiddenConstDefs=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
