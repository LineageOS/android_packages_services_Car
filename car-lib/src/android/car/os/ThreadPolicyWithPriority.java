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

package android.car.os;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.car.annotation.AddedIn;
import android.os.Parcelable;

import com.android.car.internal.util.AnnotationValidations;
import com.android.car.internal.util.DataClass;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Defines the thread scheduling policy and priority.
 *
 * <p>This API supports real-time scheduling polices:
 * ({@code SCHED_FIFO}, {@code SCHED_RR}) with a {@code sched_priority} value in the range within
 * [{@link PRIORITY_MIN}, {@link PRIORITY_MAX}]. This API also supports the default round-robin
 * time-sharing scheduling algorithm: {@code SCHED_DEFAULT}.
 *
 * @hide
 */
@SystemApi
@AddedIn(majorVersion = 33, minorVersion = 1)
@DataClass(genConstructor = false, genHiddenConstDefs = true)
public final class ThreadPolicyWithPriority implements Parcelable {

    /**
     * Min supported thread priority.
     */
    @Priority
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public static final int PRIORITY_MIN = 1;

    /**
     * Max supported thread priority.
     */
    @Priority
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public static final int PRIORITY_MAX = 99;

    /** @hide */
    @IntDef({SCHED_DEFAULT, SCHED_FIFO, SCHED_RR})
    @Retention(RetentionPolicy.SOURCE)
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public @interface SchedPolicy {}

    /**
     * Default round-robin time-sharing scheduling policy.
     *
     * <p> Same as {@code SCHED_OTHER} defined in {@code /include/uapi/linux/sched.h}.
     */
    @Sched
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public static final int SCHED_DEFAULT = 0;

    /**
     * First-in-first-out scheduling policy. See definition for Linux {@code sched(7)}.
     *
     * <p>Same as {@code SCHED_FIFO} defined in {@code /include/uapi/linux/sched.h}.
     */
    @Sched
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public static final int SCHED_FIFO = 1;

    /**
     * Round robin scheduling policy. See definition for Linux {@code sched(7)}.
     *
     * <p>Same as {@code SCHED_RR} defined in {@code /include/uapi/linux/sched.h}.
     */
    @Sched
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public static final int SCHED_RR = 2;

    @SchedPolicy
    private final int mPolicy;

    @IntRange(from = 0, to = 99)
    private final int mPriority;

    /**
     * Creates a new thread policy with priority.
     *
     * @param policy The scheduling policy, must be one of {@link SchedPolicy}.
     * @param priority The priority, must be within [{@link PRIORITY_MIN}, {@link PRIORITY_MAX}].
     */
    public ThreadPolicyWithPriority(
            @SchedPolicy int policy, @IntRange(from = 0, to = 99) int priority) {
        if (policy != SCHED_FIFO && policy != SCHED_RR && policy != SCHED_DEFAULT) {
            throw new IllegalArgumentException("invalid policy");
        }
        // priority is ignored for SCHED_DEFAULT
        if (policy == SCHED_DEFAULT) {
            priority = 0;
        } else if (priority < PRIORITY_MIN || priority > PRIORITY_MAX) {
            throw new IllegalArgumentException("invalid priority");
        }
        mPolicy = policy;
        mPriority = priority;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // The generated code is patched with adding "AddedIn" annotation to all public
    // methods/interfaces.
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/os/ThreadPolicyWithPriority.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /** @hide */
    @IntDef(prefix = "PRIORITY_", value = {
        PRIORITY_MIN,
        PRIORITY_MAX
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public @interface Priority {}

    /** @hide */
    @DataClass.Generated.Member
    public static String priorityToString(@Priority int value) {
        switch (value) {
            case PRIORITY_MIN:
                    return "PRIORITY_MIN";
            case PRIORITY_MAX:
                    return "PRIORITY_MAX";
            default: return Integer.toHexString(value);
        }
    }

    /** @hide */
    @IntDef(prefix = "SCHED_", value = {
        SCHED_DEFAULT,
        SCHED_FIFO,
        SCHED_RR
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public @interface Sched {}

    /** @hide */
    @DataClass.Generated.Member
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public static String schedToString(@Sched int value) {
        switch (value) {
            case SCHED_DEFAULT:
                    return "SCHED_DEFAULT";
            case SCHED_FIFO:
                    return "SCHED_FIFO";
            case SCHED_RR:
                    return "SCHED_RR";
            default: return Integer.toHexString(value);
        }
    }

    @DataClass.Generated.Member
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public @SchedPolicy int getPolicy() {
        return mPolicy;
    }

    @DataClass.Generated.Member
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public @IntRange(from = 0, to = 99) int getPriority() {
        return mPriority;
    }

    @Override
    @DataClass.Generated.Member
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeInt(mPolicy);
        dest.writeInt(mPriority);
    }

    @Override
    @DataClass.Generated.Member
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ ThreadPolicyWithPriority(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int policy = in.readInt();
        int priority = in.readInt();

        this.mPolicy = policy;
        AnnotationValidations.validate(
                SchedPolicy.class, null, mPolicy);
        this.mPriority = priority;
        AnnotationValidations.validate(
                IntRange.class, null, mPriority,
                "from", 0,
                "to", 99);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    @AddedIn(majorVersion = 33, minorVersion = 1)
    public static final @NonNull Parcelable.Creator<ThreadPolicyWithPriority> CREATOR
            = new Parcelable.Creator<ThreadPolicyWithPriority>() {
        @Override
        public ThreadPolicyWithPriority[] newArray(int size) {
            return new ThreadPolicyWithPriority[size];
        }

        @Override
        public ThreadPolicyWithPriority createFromParcel(@NonNull android.os.Parcel in) {
            return new ThreadPolicyWithPriority(in);
        }
    };

    @DataClass.Generated(
            time = 1657845707744L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/os/ThreadPolicyWithPriority.java",
            inputSignatures = "public static final @android.car.os.ThreadPolicyWithPriority.Priority @android.car.annotation.AddedIn int PRIORITY_MIN\npublic static final @android.car.os.ThreadPolicyWithPriority.Priority @android.car.annotation.AddedIn int PRIORITY_MAX\npublic static final @android.car.os.ThreadPolicyWithPriority.Sched @android.car.annotation.AddedIn int SCHED_DEFAULT\npublic static final @android.car.os.ThreadPolicyWithPriority.Sched @android.car.annotation.AddedIn int SCHED_FIFO\npublic static final @android.car.os.ThreadPolicyWithPriority.Sched @android.car.annotation.AddedIn int SCHED_RR\nprivate final @android.car.os.ThreadPolicyWithPriority.SchedPolicy int mPolicy\nprivate final @android.annotation.IntRange int mPriority\nclass ThreadPolicyWithPriority extends java.lang.Object implements [android.os.Parcelable]\n@com.android.car.internal.util.DataClass(genConstructor=false, genHiddenConstDefs=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
