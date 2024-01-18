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

package android.car.vms;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.AnnotationValidations;
import com.android.car.internal.util.DataClass;

import java.util.Objects;

/**
 * Hidden data object used to communicate Vehicle Map Service client tokens and system state on
 * client registration.
 *
 * @hide
 */
@DataClass(
        genEqualsHashCode = true,
        genAidl = true)
public class VmsRegistrationInfo implements Parcelable {
    private @NonNull VmsAvailableLayers mAvailableLayers;
    private @NonNull VmsSubscriptionState mSubscriptionState;



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/vms/VmsRegistrationInfo.java
    // Added AddedInOrBefore or ApiRequirement Annotation manually
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public VmsRegistrationInfo(
            @NonNull VmsAvailableLayers availableLayers,
            @NonNull VmsSubscriptionState subscriptionState) {
        this.mAvailableLayers = availableLayers;
        AnnotationValidations.validate(
                NonNull.class, null, mAvailableLayers);
        this.mSubscriptionState = subscriptionState;
        AnnotationValidations.validate(
                NonNull.class, null, mSubscriptionState);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public @NonNull VmsAvailableLayers getAvailableLayers() {
        return mAvailableLayers;
    }

    @DataClass.Generated.Member
    public @NonNull VmsSubscriptionState getSubscriptionState() {
        return mSubscriptionState;
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@android.annotation.Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(VmsRegistrationInfo other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        VmsRegistrationInfo that = (VmsRegistrationInfo) o;
        //noinspection PointlessBooleanExpression
        return true
                && Objects.equals(mAvailableLayers, that.mAvailableLayers)
                && Objects.equals(mSubscriptionState, that.mSubscriptionState);
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + Objects.hashCode(mAvailableLayers);
        _hash = 31 * _hash + Objects.hashCode(mSubscriptionState);
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeTypedObject(mAvailableLayers, flags);
        dest.writeTypedObject(mSubscriptionState, flags);
    }

    @Override
    @DataClass.Generated.Member
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    protected VmsRegistrationInfo(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        VmsAvailableLayers availableLayers = (VmsAvailableLayers) in.readTypedObject(VmsAvailableLayers.CREATOR);
        VmsSubscriptionState subscriptionState = (VmsSubscriptionState) in.readTypedObject(VmsSubscriptionState.CREATOR);

        this.mAvailableLayers = availableLayers;
        AnnotationValidations.validate(
                NonNull.class, null, mAvailableLayers);
        this.mSubscriptionState = subscriptionState;
        AnnotationValidations.validate(
                NonNull.class, null, mSubscriptionState);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<VmsRegistrationInfo> CREATOR
            = new Parcelable.Creator<VmsRegistrationInfo>() {
        @Override
        public VmsRegistrationInfo[] newArray(int size) {
            return new VmsRegistrationInfo[size];
        }

        @Override
        public VmsRegistrationInfo createFromParcel(@NonNull Parcel in) {
            return new VmsRegistrationInfo(in);
        }
    };

    @DataClass.Generated(
            time = 1628099251439L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/vms/VmsRegistrationInfo.java",
            inputSignatures = "private @android.annotation.NonNull android.car.vms.VmsAvailableLayers mAvailableLayers\nprivate @android.annotation.NonNull android.car.vms.VmsSubscriptionState mSubscriptionState\nclass VmsRegistrationInfo extends java.lang.Object implements [android.os.Parcelable]\n@com.android.car.internal.util.DataClass(genEqualsHashCode=true, genAidl=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
