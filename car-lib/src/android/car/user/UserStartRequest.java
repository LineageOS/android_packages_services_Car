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

import static com.android.car.internal.util.VersionUtils.assertPlatformVersionAtLeastU;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.car.annotation.ApiRequirements;
import android.os.Parcelable;
import android.os.UserHandle;
import android.view.Display;

import com.android.car.internal.util.DataClass;
import com.android.internal.util.Preconditions;

/**
 * User start request.
 *
 * @hide
 */
@DataClass(
        genParcelable = true,
        genConstructor = false,
        genAidl = true)
@SystemApi
public final class UserStartRequest implements Parcelable {

    private final @NonNull UserHandle mUserHandle;
    private final int mDisplayId;

    /** Builder for {@link UserStartRequest}. */
    public static final class Builder {
        private final @NonNull UserHandle mUserHandle;
        private int mDisplayId = Display.INVALID_DISPLAY;

        public Builder(@NonNull UserHandle userHandle) {
            com.android.car.internal.util.AnnotationValidations.validate(
                    NonNull.class, /* ignored= */ null, userHandle);
            mUserHandle = userHandle;
        }

        /** Set the displayId on which to start the user in background. */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
        public @NonNull Builder setDisplayId(int displayId) {
            assertPlatformVersionAtLeastU();
            Preconditions.checkArgument(displayId != Display.INVALID_DISPLAY,
                    "setDisplayId: displayId must be valid");

            mDisplayId = displayId;
            return this;
        }

        /** Builds and returns a {@link UserStartRequest}. */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
        public @NonNull UserStartRequest build() {
            assertPlatformVersionAtLeastU();
            return new UserStartRequest(this);
        }
    }

    private UserStartRequest(Builder builder) {
        mUserHandle = builder.mUserHandle;
        mDisplayId = builder.mDisplayId;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/user/UserStartRequest.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public @NonNull UserHandle getUserHandle() {
        assertPlatformVersionAtLeastU();
        return mUserHandle;
    }

    @DataClass.Generated.Member
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public int getDisplayId() {
        assertPlatformVersionAtLeastU();
        return mDisplayId;
    }

    @Override
    @DataClass.Generated.Member
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeTypedObject(mUserHandle, flags);
        dest.writeInt(mDisplayId);
    }

    @Override
    @DataClass.Generated.Member
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ UserStartRequest(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        UserHandle userHandle = (UserHandle) in.readTypedObject(UserHandle.CREATOR);
        int displayId = in.readInt();

        this.mUserHandle = userHandle;
        com.android.car.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mUserHandle);
        this.mDisplayId = displayId;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final @NonNull Parcelable.Creator<UserStartRequest> CREATOR
            = new Parcelable.Creator<UserStartRequest>() {
        @Override
        public UserStartRequest[] newArray(int size) {
            return new UserStartRequest[size];
        }

        @Override
        public UserStartRequest createFromParcel(@NonNull android.os.Parcel in) {
            return new UserStartRequest(in);
        }
    };

    @DataClass.Generated(
            time = 1676438036327L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/user/UserStartRequest.java",
            inputSignatures = "private final @android.annotation.NonNull android.os.UserHandle mUserHandle\nprivate final  int mDisplayId\nclass UserStartRequest extends java.lang.Object implements [android.os.Parcelable]\nprivate final @android.annotation.NonNull android.os.UserHandle mUserHandle\nprivate  int mDisplayId\npublic @android.car.annotation.ApiRequirements @android.annotation.NonNull android.car.user.UserStartRequest.Builder backgroundVisible(int)\npublic @android.car.annotation.ApiRequirements @android.annotation.NonNull android.car.user.UserStartRequest build()\nclass Builder extends java.lang.Object implements []\n@com.android.car.internal.util.DataClass(genParcelable=true, genConstructor=false, genAidl=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
