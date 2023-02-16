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

package com.android.car.internal.property;

import android.annotation.Nullable;
import android.car.annotation.ApiRequirements;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyAsyncErrorCode;
import android.os.Parcelable;

import com.android.car.internal.util.AnnotationValidations;
import com.android.car.internal.util.DataClass;

/**
 * A request for {@link com.android.car.CarPropertyService#getPropertiesAsync}
 *
 * @hide
 */
@DataClass(genConstructor = false)
public final class GetSetValueResult implements Parcelable {
    private final int mRequestId;
    // Result for async getProperty, ignored for setProperty.
    @Nullable
    private final CarPropertyValue mCarPropertyValue;
    // Only useful for setProperty, ignored for getProperty.
    private final long mUpdateTimestampNanos;
    @CarPropertyAsyncErrorCode
    private final int mErrorCode;

    private GetSetValueResult(int requestId, @Nullable CarPropertyValue carPropertyValue,
            @CarPropertyAsyncErrorCode int errorCode, long updateTimestampNanos) {
        mRequestId = requestId;
        mCarPropertyValue = carPropertyValue;
        mErrorCode = errorCode;
        mUpdateTimestampNanos = updateTimestampNanos;
    }

    /**
     * Creates an async get property result.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)

    public static GetSetValueResult newGetValueResult(int requestId,
            CarPropertyValue carPropertyValue) {
        return new GetSetValueResult(requestId, carPropertyValue,
                CarPropertyManager.STATUS_OK, /* updateTimestampNanos= */ 0);
    }

    /**
     * Creates an async error get property result.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static GetSetValueResult newErrorGetValueResult(int requestId,
            @CarPropertyAsyncErrorCode int errorCode) {
        return new GetSetValueResult(requestId, /* carPropertyValue= */ null,
                errorCode, /* updateTimestampNanos= */ 0);
    }

    /**
     * Creates an async set property result.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static GetSetValueResult newSetValueResult(int requestId,
            long updateTimestampNanos) {
        return new GetSetValueResult(requestId, /* carPropertyValue= */ null,
                CarPropertyManager.STATUS_OK, updateTimestampNanos);
    }

    /**
     * Creates an async error set property result.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static GetSetValueResult newErrorSetValueResult(int requestId,
            @CarPropertyAsyncErrorCode int errorCode) {
        return new GetSetValueResult(requestId, /* carPropertyValue= */ null,
                errorCode, /* updateTimestampNanos= */ 0);
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/hardware/property/GetSetValueResult.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @DataClass.Generated.Member
    public int getRequestId() {
        return mRequestId;
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @DataClass.Generated.Member
    public @Nullable CarPropertyValue getCarPropertyValue() {
        return mCarPropertyValue;
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @DataClass.Generated.Member
    public long getUpdateTimestampNanos() {
        return mUpdateTimestampNanos;
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @DataClass.Generated.Member
    public @CarPropertyAsyncErrorCode int getErrorCode() {
        return mErrorCode;
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
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
        dest.writeLong(mUpdateTimestampNanos);
        dest.writeInt(mErrorCode);
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ GetSetValueResult(@android.annotation.NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        int requestId = in.readInt();
        CarPropertyValue carPropertyValue = (flg & 0x2) == 0 ? null : (CarPropertyValue) in.readTypedObject(CarPropertyValue.CREATOR);
        long updateTimestampNanos = in.readLong();
        int errorCode = in.readInt();

        this.mRequestId = requestId;
        this.mCarPropertyValue = carPropertyValue;
        this.mUpdateTimestampNanos = updateTimestampNanos;
        this.mErrorCode = errorCode;
        AnnotationValidations.validate(
                CarPropertyAsyncErrorCode.class, null, mErrorCode);

        // onConstructed(); // You can define this method to get a callback
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @DataClass.Generated.Member
    public static final @android.annotation.NonNull Parcelable.Creator<GetSetValueResult> CREATOR
            = new Parcelable.Creator<GetSetValueResult>() {
        @Override
        public GetSetValueResult[] newArray(int size) {
            return new GetSetValueResult[size];
        }

        @Override
        public GetSetValueResult createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
            return new GetSetValueResult(in);
        }
    };

    @DataClass.Generated(
            time = 1675218930169L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/hardware/property/GetSetValueResult.java",
            inputSignatures = "private final  int mRequestId\nprivate final @android.annotation.Nullable android.car.hardware.CarPropertyValue mCarPropertyValue\nprivate final  long mUpdateTimestampNanos\nprivate final @android.car.hardware.property.CarPropertyManager.CarPropertyAsyncErrorCode int mErrorCode\npublic static  android.car.hardware.property.GetSetValueResult newGetValueResult(int,android.car.hardware.CarPropertyValue)\npublic static  android.car.hardware.property.GetSetValueResult newErrorGetValueResult(int,int)\npublic static  android.car.hardware.property.GetSetValueResult newSetValueResult(int,long)\npublic static  android.car.hardware.property.GetSetValueResult newErrorSetValueResult(int,int)\nclass GetSetValueResult extends java.lang.Object implements [android.os.Parcelable]\n@com.android.car.internal.util.DataClass(genConstructor=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
