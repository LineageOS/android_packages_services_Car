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

package android.car.watchdog;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.annotation.NonNull;
import android.os.Parcelable;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.AnnotationValidations;
import com.android.car.internal.util.DataClass;

/**
 * Disk I/O overuse stats for a package.
 */
@DataClass(genToString = true, genHiddenBuilder = true)
public final class IoOveruseStats implements Parcelable {
    /**
     * Start time, in epoch seconds, for the below stats.
     */
    private long mStartTime;

    /**
     * Duration, in seconds, for the below stats.
     */
    private long mDurationInSeconds;

    /**
     * Total times the package has written to disk beyond the allowed write bytes during the given
     * period.
     */
    private long mTotalOveruses = 0;

    /**
     * Total times the package was killed during the given period due to disk I/O overuse.
     */
    private long mTotalTimesKilled = 0;

    /**
     * Aggregated number of bytes written to disk by the package during the given period.
     */
    private long mTotalBytesWritten = 0;

    /**
     * Package may be killed on disk I/O overuse.
     *
     * <p>Disk I/O overuse is triggered on exceeding {@link #mRemainingWriteBytes}.
     */
    private boolean mKillableOnOveruse = false;

    /**
     * Number of write bytes remaining in each application or system state.
     *
     * <p>On exceeding these limit in at least one system or application state, the package may be
     * killed if {@link #mKillableOnOveruse} is {@code true}.
     *
     * <p>The above period does not apply to this field.
     */
    private @NonNull PerStateBytes mRemainingWriteBytes = new PerStateBytes(0L, 0L, 0L);



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/services/Car/car-lib/src/android/car/watchdog/IoOveruseStats.java
    // Added AddedInOrBefore or ApiRequirement Annotation manually
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    /* package-private */ IoOveruseStats(
            long startTime,
            long durationInSeconds,
            long totalOveruses,
            long totalTimesKilled,
            long totalBytesWritten,
            boolean killableOnOveruse,
            @NonNull PerStateBytes remainingWriteBytes) {
        this.mStartTime = startTime;
        this.mDurationInSeconds = durationInSeconds;
        this.mTotalOveruses = totalOveruses;
        this.mTotalTimesKilled = totalTimesKilled;
        this.mTotalBytesWritten = totalBytesWritten;
        this.mKillableOnOveruse = killableOnOveruse;
        this.mRemainingWriteBytes = remainingWriteBytes;
        AnnotationValidations.validate(
                NonNull.class, null, mRemainingWriteBytes);

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * Start time, in epoch seconds, for the below stats.
     */
    @DataClass.Generated.Member
    public long getStartTime() {
        return mStartTime;
    }

    /**
     * Duration, in seconds, for the below stats.
     */
    @DataClass.Generated.Member
    public long getDurationInSeconds() {
        return mDurationInSeconds;
    }

    /**
     * Total times the package has written to disk beyond the allowed write bytes during the given
     * period.
     */
    @DataClass.Generated.Member
    public long getTotalOveruses() {
        return mTotalOveruses;
    }

    /**
     * Total times the package was killed during the given period due to disk I/O overuse.
     */
    @DataClass.Generated.Member
    public long getTotalTimesKilled() {
        return mTotalTimesKilled;
    }

    /**
     * Aggregated number of bytes written to disk by the package during the given period.
     */
    @DataClass.Generated.Member
    public long getTotalBytesWritten() {
        return mTotalBytesWritten;
    }

    /**
     * Package may be killed on disk I/O overuse.
     *
     * <p>Disk I/O overuse is triggered on exceeding {@link #getRemainingWriteBytes()}.
     */
    @DataClass.Generated.Member
    public boolean isKillableOnOveruse() {
        return mKillableOnOveruse;
    }

    /**
     * Number of write bytes remaining in each application or system state.
     *
     * <p>On exceeding these limit in at least one system or application state, the package may be
     * killed if {@link #isKillableOnOveruse()} is {@code true}.
     *
     * <p>The above period does not apply to this field.
     */
    @DataClass.Generated.Member
    public @NonNull PerStateBytes getRemainingWriteBytes() {
        return mRemainingWriteBytes;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "IoOveruseStats { " +
                "startTime = " + mStartTime + ", " +
                "durationInSeconds = " + mDurationInSeconds + ", " +
                "totalOveruses = " + mTotalOveruses + ", " +
                "totalTimesKilled = " + mTotalTimesKilled + ", " +
                "totalBytesWritten = " + mTotalBytesWritten + ", " +
                "killableOnOveruse = " + mKillableOnOveruse + ", " +
                "remainingWriteBytes = " + mRemainingWriteBytes +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mKillableOnOveruse) flg |= 0x20;
        dest.writeByte(flg);
        dest.writeLong(mStartTime);
        dest.writeLong(mDurationInSeconds);
        dest.writeLong(mTotalOveruses);
        dest.writeLong(mTotalTimesKilled);
        dest.writeLong(mTotalBytesWritten);
        dest.writeTypedObject(mRemainingWriteBytes, flags);
    }

    @Override
    @DataClass.Generated.Member
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ IoOveruseStats(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        boolean killableOnOveruse = (flg & 0x20) != 0;
        long startTime = in.readLong();
        long durationInSeconds = in.readLong();
        long totalOveruses = in.readLong();
        long totalTimesKilled = in.readLong();
        long totalBytesWritten = in.readLong();
        PerStateBytes remainingWriteBytes = (PerStateBytes) in.readTypedObject(PerStateBytes.CREATOR);

        this.mStartTime = startTime;
        this.mDurationInSeconds = durationInSeconds;
        this.mTotalOveruses = totalOveruses;
        this.mTotalTimesKilled = totalTimesKilled;
        this.mTotalBytesWritten = totalBytesWritten;
        this.mKillableOnOveruse = killableOnOveruse;
        this.mRemainingWriteBytes = remainingWriteBytes;
        AnnotationValidations.validate(
                NonNull.class, null, mRemainingWriteBytes);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<IoOveruseStats> CREATOR
            = new Parcelable.Creator<IoOveruseStats>() {
        @Override
        public IoOveruseStats[] newArray(int size) {
            return new IoOveruseStats[size];
        }

        @Override
        public IoOveruseStats createFromParcel(@NonNull android.os.Parcel in) {
            return new IoOveruseStats(in);
        }
    };

    /**
     * A builder for {@link IoOveruseStats}
     * @hide
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder {

        private long mStartTime;
        private long mDurationInSeconds;
        private long mTotalOveruses;
        private long mTotalTimesKilled;
        private long mTotalBytesWritten;
        private boolean mKillableOnOveruse;
        private @NonNull PerStateBytes mRemainingWriteBytes;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param startTime
         *   Start time, in epoch seconds, for the below stats.
         * @param durationInSeconds
         *   Duration, in seconds, for the below stats.
         */
        public Builder(
                long startTime,
                long durationInSeconds) {
            mStartTime = startTime;
            mDurationInSeconds = durationInSeconds;
        }

        /**
         * Start time, in epoch seconds, for the below stats.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setStartTime(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mStartTime = value;
            return this;
        }

        /**
         * Duration, in seconds, for the below stats.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setDurationInSeconds(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mDurationInSeconds = value;
            return this;
        }

        /**
         * Total times the package has written to disk beyond the allowed write bytes during the given
         * period.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setTotalOveruses(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mTotalOveruses = value;
            return this;
        }

        /**
         * Total times the package was killed during the given period due to disk I/O overuse.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setTotalTimesKilled(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mTotalTimesKilled = value;
            return this;
        }

        /**
         * Aggregated number of bytes written to disk by the package during the given period.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setTotalBytesWritten(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mTotalBytesWritten = value;
            return this;
        }

        /**
         * Package may be killed on disk I/O overuse.
         *
         * <p>Disk I/O overuse is triggered on exceeding {@link #mRemainingWriteBytes}.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setKillableOnOveruse(boolean value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mKillableOnOveruse = value;
            return this;
        }

        /**
         * Number of write bytes remaining in each application or system state.
         *
         * <p>On exceeding these limit in at least one system or application state, the package may be
         * killed if {@link #mKillableOnOveruse} is {@code true}.
         *
         * <p>The above period does not apply to this field.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setRemainingWriteBytes(@NonNull PerStateBytes value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40;
            mRemainingWriteBytes = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull IoOveruseStats build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x80; // Mark builder used

            if ((mBuilderFieldsSet & 0x4) == 0) {
                mTotalOveruses = 0;
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mTotalTimesKilled = 0;
            }
            if ((mBuilderFieldsSet & 0x10) == 0) {
                mTotalBytesWritten = 0;
            }
            if ((mBuilderFieldsSet & 0x20) == 0) {
                mKillableOnOveruse = false;
            }
            if ((mBuilderFieldsSet & 0x40) == 0) {
                mRemainingWriteBytes = new PerStateBytes(0L, 0L, 0L);
            }
            IoOveruseStats o = new IoOveruseStats(
                    mStartTime,
                    mDurationInSeconds,
                    mTotalOveruses,
                    mTotalTimesKilled,
                    mTotalBytesWritten,
                    mKillableOnOveruse,
                    mRemainingWriteBytes);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x80) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1628099298965L,
            codegenVersion = "1.0.23",
            sourceFile = "packages/services/Car/car-lib/src/android/car/watchdog/IoOveruseStats.java",
            inputSignatures = "private  long mStartTime\nprivate  long mDurationInSeconds\nprivate  long mTotalOveruses\nprivate  long mTotalTimesKilled\nprivate  long mTotalBytesWritten\nprivate  boolean mKillableOnOveruse\nprivate @android.annotation.NonNull android.car.watchdog.PerStateBytes mRemainingWriteBytes\nclass IoOveruseStats extends java.lang.Object implements [android.os.Parcelable]\n@com.android.car.internal.util.DataClass(genToString=true, genHiddenBuilder=true)")
    @Deprecated
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
