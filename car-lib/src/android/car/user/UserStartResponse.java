/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.car.annotation.ApiRequirements;
import android.os.Parcelable;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.DataClass;

/**
 * User start response.
 *
 * @hide
 */
@DataClass(
        genAidl = true,
        genToString = true,
        genHiddenConstructor = true,
        genHiddenConstDefs = true)
@SystemApi
public final class UserStartResponse implements Parcelable, OperationResult {

    /**
     * When user start is successful.
     *
     * @hide
     */
    @Status
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @SystemApi
    public static final int STATUS_SUCCESSFUL = CommonResults.STATUS_SUCCESSFUL;

    /**
     * When user start failed.
     *
     * @hide
     */
    @Status
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @SystemApi
    public static final int STATUS_ANDROID_FAILURE = CommonResults.STATUS_ANDROID_FAILURE;

    /**
     * When user start failed due to an old platform version.
     *
     * @hide
     */
    @Status
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @SystemApi
    public static final int STATUS_UNSUPPORTED_PLATFORM_FAILURE =
            CommonResults.STATUS_UNSUPPORTED_PLATFORM_FAILURE;

    /**
     * When user to start is same as current user.
     *
     * @hide
     */
    @Status
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @SystemApi
    public static final int STATUS_SUCCESSFUL_USER_IS_CURRENT_USER =
            CommonResults.LAST_COMMON_STATUS + 1;

    /**
     * When user to start does not exist.
     *
     * @hide
     */
    @Status
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @SystemApi
    public static final int STATUS_USER_DOES_NOT_EXIST = CommonResults.LAST_COMMON_STATUS + 2;

    /**
     * When user to start is already visible on the specified display.
     *
     * @hide
     */
    @Status
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @SystemApi
    public static final int STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY =
            CommonResults.LAST_COMMON_STATUS + 3;

    /**
     * When the specified display is invalid or already assigned to another user.
     *
     * @hide
     */
    @Status
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @SystemApi
    public static final int STATUS_DISPLAY_INVALID = CommonResults.LAST_COMMON_STATUS + 4;

    /**
     * When the specified display is invalid or already assigned to another user.
     *
     * @hide
     */
    @Status
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @SystemApi
    public static final int STATUS_DISPLAY_UNAVAILABLE = CommonResults.LAST_COMMON_STATUS + 5;

    /**
     * When the specified user is invalid (e.g. the system user).
     *
     * @hide
     */
    @Status
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @SystemApi
    public static final int STATUS_USER_INVALID = CommonResults.LAST_COMMON_STATUS + 6;

    /**
     * When the specified user is already assigned to another display.
     *
     * @hide
     */
    @Status
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @SystemApi
    public static final int
            STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY = CommonResults.LAST_COMMON_STATUS + 7;

    /**
     * Gets the user start result status.
     *
     * @return either {@link UserStartResponse#STATUS_SUCCESSFUL},
     *         {@link UserStartResponse#STATUS_SUCCESSFUL_USER_IS_CURRENT_USER},
     *         {@link UserStartResponse#STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY},
     *         {@link UserStartResponse#STATUS_ANDROID_FAILURE},
     *         {@link UserStartResponse#STATUS_UNSUPPORTED_PLATFORM_FAILURE},
     *         {@link UserStartResponse#STATUS_USER_DOES_NOT_EXIST},
     *         {@link UserStartResponse#STATUS_DISPLAY_INVALID},
     *         {@link UserStartResponse#STATUS_DISPLAY_UNAVAILABLE},
     *         {@link UserStartResponse#STATUS_USER_INVALID}, or
     *         {@link UserStartResponse#STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY}.
     */
    private final @Status int mStatus;

    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public boolean isSuccess() {
        return mStatus == STATUS_SUCCESSFUL || mStatus == STATUS_SUCCESSFUL_USER_IS_CURRENT_USER
                || mStatus == STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/user/UserStartResponse.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /** @hide */
    @android.annotation.IntDef(prefix = "STATUS_", value = {
        STATUS_SUCCESSFUL,
        STATUS_ANDROID_FAILURE,
        STATUS_UNSUPPORTED_PLATFORM_FAILURE,
        STATUS_SUCCESSFUL_USER_IS_CURRENT_USER,
        STATUS_USER_DOES_NOT_EXIST,
        STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY,
        STATUS_DISPLAY_INVALID,
        STATUS_DISPLAY_UNAVAILABLE,
        STATUS_USER_INVALID,
        STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface Status {}

    /** @hide */
    @DataClass.Generated.Member
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @SystemApi
    public static @NonNull String statusToString(@Status int value) {
        switch (value) {
            case STATUS_SUCCESSFUL:
                    return "STATUS_SUCCESSFUL";
            case STATUS_ANDROID_FAILURE:
                    return "STATUS_ANDROID_FAILURE";
            case STATUS_UNSUPPORTED_PLATFORM_FAILURE:
                    return "STATUS_UNSUPPORTED_PLATFORM_FAILURE";
            case STATUS_SUCCESSFUL_USER_IS_CURRENT_USER:
                    return "STATUS_SUCCESSFUL_USER_IS_CURRENT_USER";
            case STATUS_USER_DOES_NOT_EXIST:
                    return "STATUS_USER_DOES_NOT_EXIST";
            case STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY:
                    return "STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY";
            case STATUS_DISPLAY_INVALID:
                    return "STATUS_DISPLAY_INVALID";
            case STATUS_DISPLAY_UNAVAILABLE:
                    return "STATUS_DISPLAY_UNAVAILABLE";
            case STATUS_USER_INVALID:
                    return "STATUS_USER_INVALID";
            case STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY:
                    return "STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY";
            default: return Integer.toHexString(value);
        }
    }

    /**
     * Creates a new UserStartResponse.
     *
     * @param status
     *   Gets the user start result status.
     *
     *   @return either {@link UserStartResponse#STATUS_SUCCESSFUL},
     *           {@link UserStartResponse#STATUS_SUCCESSFUL_USER_IS_CURRENT_USER},
     *           {@link UserStartResponse#STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY},
     *           {@link UserStartResponse#STATUS_ANDROID_FAILURE},
     *           {@link UserStartResponse#STATUS_UNSUPPORTED_PLATFORM_FAILURE},
     *           {@link UserStartResponse#STATUS_USER_DOES_NOT_EXIST},
     *           {@link UserStartResponse#STATUS_DISPLAY_INVALID},
     *           {@link UserStartResponse#STATUS_DISPLAY_UNAVAILABLE},
     *           {@link UserStartResponse#STATUS_USER_INVALID}, or
     *           {@link UserStartResponse#STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY}.
     * @hide
     */
    @DataClass.Generated.Member
    public UserStartResponse(
            @Status int status) {
        this.mStatus = status;

        if (!(mStatus == STATUS_SUCCESSFUL)
                && !(mStatus == STATUS_ANDROID_FAILURE)
                && !(mStatus == STATUS_UNSUPPORTED_PLATFORM_FAILURE)
                && !(mStatus == STATUS_SUCCESSFUL_USER_IS_CURRENT_USER)
                && !(mStatus == STATUS_USER_DOES_NOT_EXIST)
                && !(mStatus == STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY)
                && !(mStatus == STATUS_DISPLAY_INVALID)
                && !(mStatus == STATUS_DISPLAY_UNAVAILABLE)
                && !(mStatus == STATUS_USER_INVALID)
                && !(mStatus == STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY)) {
            throw new java.lang.IllegalArgumentException(
                    "status was " + mStatus + " but must be one of: "
                            + "STATUS_SUCCESSFUL(" + STATUS_SUCCESSFUL + "), "
                            + "STATUS_ANDROID_FAILURE(" + STATUS_ANDROID_FAILURE + "), "
                            + "STATUS_UNSUPPORTED_PLATFORM_FAILURE(" + STATUS_UNSUPPORTED_PLATFORM_FAILURE + "), "
                            + "STATUS_SUCCESSFUL_USER_IS_CURRENT_USER(" + STATUS_SUCCESSFUL_USER_IS_CURRENT_USER + "), "
                            + "STATUS_USER_DOES_NOT_EXIST(" + STATUS_USER_DOES_NOT_EXIST + "), "
                            + "STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY(" + STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY + "), "
                            + "STATUS_DISPLAY_INVALID(" + STATUS_DISPLAY_INVALID + "), "
                            + "STATUS_DISPLAY_UNAVAILABLE(" + STATUS_DISPLAY_UNAVAILABLE + "), "
                            + "STATUS_USER_INVALID(" + STATUS_USER_INVALID + "), "
                            + "STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY(" + STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY + ")");
        }


        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * Gets the user start result status.
     *
     * @return either {@link UserStartResponse#STATUS_SUCCESSFUL},
     *         {@link UserStartResponse#STATUS_SUCCESSFUL_USER_IS_CURRENT_USER},
     *         {@link UserStartResponse#STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY},
     *         {@link UserStartResponse#STATUS_ANDROID_FAILURE},
     *         {@link UserStartResponse#STATUS_UNSUPPORTED_PLATFORM_FAILURE},
     *         {@link UserStartResponse#STATUS_USER_DOES_NOT_EXIST},
     *         {@link UserStartResponse#STATUS_DISPLAY_INVALID},
     *         {@link UserStartResponse#STATUS_DISPLAY_UNAVAILABLE},
     *         {@link UserStartResponse#STATUS_USER_INVALID}, or
     *         {@link UserStartResponse#STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY}.
     */
    @DataClass.Generated.Member
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public @Status int getStatus() {
        return mStatus;
    }

    @Override
    @DataClass.Generated.Member
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "UserStartResponse { " +
                "status = " + statusToString(mStatus) +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeInt(mStatus);
    }

    @Override
    @DataClass.Generated.Member
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ UserStartResponse(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int status = in.readInt();

        this.mStatus = status;

        if (!(mStatus == STATUS_SUCCESSFUL)
                && !(mStatus == STATUS_ANDROID_FAILURE)
                && !(mStatus == STATUS_UNSUPPORTED_PLATFORM_FAILURE)
                && !(mStatus == STATUS_SUCCESSFUL_USER_IS_CURRENT_USER)
                && !(mStatus == STATUS_USER_DOES_NOT_EXIST)
                && !(mStatus == STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY)
                && !(mStatus == STATUS_DISPLAY_INVALID)
                && !(mStatus == STATUS_DISPLAY_UNAVAILABLE)
                && !(mStatus == STATUS_USER_INVALID)
                && !(mStatus == STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY)) {
            throw new java.lang.IllegalArgumentException(
                    "status was " + mStatus + " but must be one of: "
                            + "STATUS_SUCCESSFUL(" + STATUS_SUCCESSFUL + "), "
                            + "STATUS_ANDROID_FAILURE(" + STATUS_ANDROID_FAILURE + "), "
                            + "STATUS_UNSUPPORTED_PLATFORM_FAILURE(" + STATUS_UNSUPPORTED_PLATFORM_FAILURE + "), "
                            + "STATUS_SUCCESSFUL_USER_IS_CURRENT_USER(" + STATUS_SUCCESSFUL_USER_IS_CURRENT_USER + "), "
                            + "STATUS_USER_DOES_NOT_EXIST(" + STATUS_USER_DOES_NOT_EXIST + "), "
                            + "STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY(" + STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY + "), "
                            + "STATUS_DISPLAY_INVALID(" + STATUS_DISPLAY_INVALID + "), "
                            + "STATUS_DISPLAY_UNAVAILABLE(" + STATUS_DISPLAY_UNAVAILABLE + "), "
                            + "STATUS_USER_INVALID(" + STATUS_USER_INVALID + "), "
                            + "STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY(" + STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY + ")");
        }


        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final @NonNull Parcelable.Creator<UserStartResponse> CREATOR
            = new Parcelable.Creator<UserStartResponse>() {
        @Override
        public UserStartResponse[] newArray(int size) {
            return new UserStartResponse[size];
        }

        @Override
        public UserStartResponse createFromParcel(@NonNull android.os.Parcel in) {
            return new UserStartResponse(in);
        }
    };

    @DataClass.Generated(
            time = 1676508014542L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/user/UserStartResponse.java",
            inputSignatures = "public static final @android.car.user.UserStartResponse.Status @android.car.annotation.ApiRequirements @android.annotation.SystemApi int STATUS_SUCCESSFUL\npublic static final @android.car.user.UserStartResponse.Status @android.car.annotation.ApiRequirements @android.annotation.SystemApi int STATUS_ANDROID_FAILURE\npublic static final @android.car.user.UserStartResponse.Status @android.car.annotation.ApiRequirements @android.annotation.SystemApi int STATUS_UNSUPPORTED_PLATFORM_FAILURE\npublic static final @android.car.user.UserStartResponse.Status @android.car.annotation.ApiRequirements @android.annotation.SystemApi int STATUS_SUCCESSFUL_USER_IS_CURRENT_USER\npublic static final @android.car.user.UserStartResponse.Status @android.car.annotation.ApiRequirements @android.annotation.SystemApi int STATUS_USER_DOES_NOT_EXIST\npublic static final @android.car.user.UserStartResponse.Status @android.car.annotation.ApiRequirements @android.annotation.SystemApi int STATUS_SUCCESSFUL_USER_ALREADY_VISIBLE_ON_DISPLAY\npublic static final @android.car.user.UserStartResponse.Status @android.car.annotation.ApiRequirements @android.annotation.SystemApi int STATUS_DISPLAY_INVALID\npublic static final @android.car.user.UserStartResponse.Status @android.car.annotation.ApiRequirements @android.annotation.SystemApi int STATUS_DISPLAY_UNAVAILABLE\npublic static final @android.car.user.UserStartResponse.Status @android.car.annotation.ApiRequirements @android.annotation.SystemApi int STATUS_USER_INVALID\npublic static final @android.car.user.UserStartResponse.Status @android.car.annotation.ApiRequirements @android.annotation.SystemApi int STATUS_USER_ASSIGNED_TO_ANOTHER_DISPLAY\nprivate final @android.car.user.UserStartResponse.Status int mStatus\npublic @java.lang.Override @android.car.annotation.ApiRequirements boolean isSuccess()\nclass UserStartResponse extends java.lang.Object implements [android.os.Parcelable, android.car.user.OperationResult]\n@com.android.car.internal.util.DataClass(genAidl=true, genToString=true, genHiddenConstructor=true, genHiddenConstDefs=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}