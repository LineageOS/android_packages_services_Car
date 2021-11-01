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

package android.car.hardware.power;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.annotation.NonNull;
import android.os.Parcelable;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.AnnotationValidations;
import com.android.car.internal.util.DataClass;

/**
 * Filter to receive power policy changes that a listener is interested in.
 *
 * For the record: When running codegen, auto-generated constructor of {@link Builder} which takes
 * one argument should be removed manually, so that we can use the default constructor.
 */
@DataClass(genBuilder = true)
public final class CarPowerPolicyFilter implements Parcelable {
    /**
     * List of components of interest. Components are one of
     * {@code android.frameworks.automotive.powerpolicy.PowerComponent}.
     */
    private @NonNull int[] mComponents = new int[]{};



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/hardware/power/CarPowerPolicyFilter.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    /* package-private */ CarPowerPolicyFilter(
            @NonNull int[] components) {
        this.mComponents = components;
        AnnotationValidations.validate(
                NonNull.class, null, mComponents);

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * List of components of interest. Components are one of
     * {@code android.frameworks.automotive.powerpolicy.PowerComponent}.
     */
    @DataClass.Generated.Member
    public @NonNull int[] getComponents() {
        return mComponents;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeIntArray(mComponents);
    }

    @Override
    @DataClass.Generated.Member
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ CarPowerPolicyFilter(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int[] components = in.createIntArray();

        this.mComponents = components;
        AnnotationValidations.validate(
                NonNull.class, null, mComponents);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<CarPowerPolicyFilter> CREATOR
            = new Parcelable.Creator<CarPowerPolicyFilter>() {
        @Override
        public CarPowerPolicyFilter[] newArray(int size) {
            return new CarPowerPolicyFilter[size];
        }

        @Override
        public CarPowerPolicyFilter createFromParcel(@NonNull android.os.Parcel in) {
            return new CarPowerPolicyFilter(in);
        }
    };

    /**
     * A builder for {@link CarPowerPolicyFilter}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder {

        private @NonNull int[] mComponents;

        private long mBuilderFieldsSet = 0L;

        public Builder() {
        }

        /**
         * List of components of interest. Components are one of
         * {@code android.frameworks.automotive.powerpolicy.PowerComponent}.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setComponents(@NonNull int... value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mComponents = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull CarPowerPolicyFilter build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mComponents = new int[] {};
            }
            CarPowerPolicyFilter o = new CarPowerPolicyFilter(
                    mComponents);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x2) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1628099142505L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/hardware/power/CarPowerPolicyFilter.java",
            inputSignatures = "private @android.annotation.NonNull int[] mComponents\nclass CarPowerPolicyFilter extends java.lang.Object implements [android.os.Parcelable]\n@com.android.car.internal.util.DataClass(genBuilder=true)")
    @Deprecated
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
