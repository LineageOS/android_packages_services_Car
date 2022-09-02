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

package android.car.hardware.property;

import android.annotation.Nullable;
import android.car.annotation.ApiRequirements;
import android.car.annotation.ApiRequirements.CarVersion;
import android.car.annotation.ApiRequirements.PlatformVersion;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager.CarPropertyAsyncErrorCode;
import android.os.Parcelable;

import com.android.car.internal.util.AnnotationValidations;
import com.android.car.internal.util.DataClass;

/**
 * A request for {@link com.android.car.CarPropertyService#getPropertiesAsync}
 */
@DataClass(genConstructor = false)
public final class GetValueResult implements Parcelable {
    private final int mRequestId;
    @Nullable
    private final CarPropertyValue mCarPropertyValue;
    @CarPropertyAsyncErrorCode
    private final int mErrorCode;

    /**
     * Get an instance for GetValueResult.
     */
    public GetValueResult(int requestId, @Nullable CarPropertyValue carPropertyValue,
            @CarPropertyAsyncErrorCode int errorCode) {
        mRequestId = requestId;
        mCarPropertyValue = carPropertyValue;
        mErrorCode = errorCode;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/hardware/property/GetValueResult.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off

    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = PlatformVersion.TIRAMISU_0)
    @DataClass.Generated.Member
    public int getRequestId() {
        return mRequestId;
    }

    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = PlatformVersion.TIRAMISU_0)
    @DataClass.Generated.Member
    public @Nullable CarPropertyValue getCarPropertyValue() {
        return mCarPropertyValue;
    }

    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = PlatformVersion.TIRAMISU_0)
    @DataClass.Generated.Member
    public @CarPropertyAsyncErrorCode int getErrorCode() {
        return mErrorCode;
    }

    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = PlatformVersion.TIRAMISU_0)
    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mCarPropertyValue != null) flg |= 0x2;
        dest.writeByte(flg);
        dest.writeInt(mRequestId);
        if (mCarPropertyValue != null) dest.writeTypedObject(mCarPropertyValue, flags);
        dest.writeInt(mErrorCode);
    }

    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = PlatformVersion.TIRAMISU_0)
    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ GetValueResult(@android.annotation.NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        int requestId = in.readInt();
        CarPropertyValue carPropertyValue = (flg & 0x2) == 0 ? null : (CarPropertyValue) in.readTypedObject(CarPropertyValue.CREATOR);
        int errorCode = in.readInt();

        this.mRequestId = requestId;
        this.mCarPropertyValue = carPropertyValue;
        this.mErrorCode = errorCode;
        AnnotationValidations.validate(
                CarPropertyAsyncErrorCode.class, null, mErrorCode);

        // onConstructed(); // You can define this method to get a callback
    }

    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = PlatformVersion.TIRAMISU_0)
    @DataClass.Generated.Member
    public static final @android.annotation.NonNull Parcelable.Creator<GetValueResult> CREATOR
            = new Parcelable.Creator<GetValueResult>() {
        @Override
        public GetValueResult[] newArray(int size) {
            return new GetValueResult[size];
        }

        @Override
        public GetValueResult createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
            return new GetValueResult(in);
        }
    };

    @DataClass.Generated(
            time = 1660959588450L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/hardware/property/GetValueResult.java",
            inputSignatures = "private final  int mRequestId\nprivate final @android.annotation.Nullable android.car.hardware.CarPropertyValue mCarPropertyValue\nprivate final @android.car.hardware.property.CarPropertyManager.CarPropertyAsyncErrorCode int mErrorCode\nclass GetValueResult extends java.lang.Object implements [android.os.Parcelable]\n@com.android.car.internal.util.DataClass(genConstructor=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
