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
import com.android.internal.util.DataClass;

/**
 * Car power policy definition.
 */
@DataClass(genHiddenConstructor = true)
public final class CarPowerPolicy implements Parcelable {
    /**
     * ID of power policy.
     */
    private final @NonNull String mPolicyId;

    /**
     * List of enabled componentst. Components are one of
     * {@code android.frameworks.automotive.powerpolicy.PowerComponent}.
     */
    private final @NonNull int[] mEnabledComponents;

    /**
     * List of disabled componentst. Components are one of
     * {@code android.frameworks.automotive.powerpolicy.PowerComponent}.
     */
    private final @NonNull int[] mDisabledComponents;

    /**
     * Returns {@code true} if the given component is enabled in the power policy. Otherwise,
     * {@code false}.
     */
    public boolean isComponentEnabled(int component) {
        for (int i = 0; i < mEnabledComponents.length; i++) {
            if (component == mEnabledComponents[i]) {
                return true;
            }
        }
        return false;
    }


    // Code below generated by codegen v1.0.22.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/hardware/power/CarPowerPolicy.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Creates a new CarPowerPolicy.
     *
     * @param policyId
     *   ID of power policy.
     * @param enabledComponents
     *   List of enabled componentst. Components are one of
     *   {@code android.frameworks.automotive.powerpolicy.PowerComponent}.
     * @param disabledComponents
     *   List of disabled componentst. Components are one of
     *   {@code android.frameworks.automotive.powerpolicy.PowerComponent}.
     * @hide
     */
    @DataClass.Generated.Member
    public CarPowerPolicy(
            @NonNull String policyId,
            @NonNull int[] enabledComponents,
            @NonNull int[] disabledComponents) {
        this.mPolicyId = policyId;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPolicyId);
        this.mEnabledComponents = enabledComponents;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mEnabledComponents);
        this.mDisabledComponents = disabledComponents;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mDisabledComponents);

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * ID of power policy.
     */
    @DataClass.Generated.Member
    public @NonNull String getPolicyId() {
        return mPolicyId;
    }

    /**
     * List of enabled componentst. Components are one of
     * {@code android.frameworks.automotive.powerpolicy.PowerComponent}.
     */
    @DataClass.Generated.Member
    public @NonNull int[] getEnabledComponents() {
        return mEnabledComponents;
    }

    /**
     * List of disabled componentst. Components are one of
     * {@code android.frameworks.automotive.powerpolicy.PowerComponent}.
     */
    @DataClass.Generated.Member
    public @NonNull int[] getDisabledComponents() {
        return mDisabledComponents;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeString(mPolicyId);
        dest.writeIntArray(mEnabledComponents);
        dest.writeIntArray(mDisabledComponents);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ CarPowerPolicy(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        String policyId = in.readString();
        int[] enabledComponents = in.createIntArray();
        int[] disabledComponents = in.createIntArray();

        this.mPolicyId = policyId;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPolicyId);
        this.mEnabledComponents = enabledComponents;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mEnabledComponents);
        this.mDisabledComponents = disabledComponents;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mDisabledComponents);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<CarPowerPolicy> CREATOR
            = new Parcelable.Creator<CarPowerPolicy>() {
        @Override
        public CarPowerPolicy[] newArray(int size) {
            return new CarPowerPolicy[size];
        }

        @Override
        public CarPowerPolicy createFromParcel(@NonNull android.os.Parcel in) {
            return new CarPowerPolicy(in);
        }
    };

    @DataClass.Generated(
            time = 1614373490935L,
            codegenVersion = "1.0.22",
            sourceFile = "packages/services/Car/car-lib/src/android/car/hardware/power/CarPowerPolicy.java",
            inputSignatures = "private final @android.annotation.NonNull java.lang.String mPolicyId\nprivate final @android.annotation.NonNull int[] mEnabledComponents\nprivate final @android.annotation.NonNull int[] mDisabledComponents\nclass CarPowerPolicy extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genHiddenConstructor=true)")
    @Deprecated
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
