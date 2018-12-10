/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.car.drivingstate;

import android.annotation.FloatRange;
import android.annotation.Nullable;
import android.car.drivingstate.CarDrivingStateEvent.CarDrivingState;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.Log;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for Car UX Restrictions service.
 *
 * @hide
 */
public final class CarUxRestrictionsConfiguration implements Parcelable {
    private static final String TAG = "CarUxRConfig";

    // Constants used by json de/serialization.
    private static final String JSON_NAME_MAX_CONTENT_DEPTH = "max_content_depth";
    private static final String JSON_NAME_MAX_CUMULATIVE_CONTENT_ITEMS =
            "max_cumulative_content_items";
    private static final String JSON_NAME_MAX_STRING_LENGTH = "max_string_length";
    private static final String JSON_NAME_MOVING_RESTRICTIONS = "moving_restrictions";
    private static final String JSON_NAME_IDLING_RESTRICTIONS = "idling_restrictions";
    private static final String JSON_NAME_PARKED_RESTRICTIONS = "parked_restrictions";
    private static final String JSON_NAME_UNKNOWN_RESTRICTIONS = "unknown_restrictions";
    private static final String JSON_NAME_REQ_OPT = "req_opt";
    private static final String JSON_NAME_RESTRICTIONS = "restrictions";
    private static final String JSON_NAME_SPEED_RANGE = "speed_range";
    private static final String JSON_NAME_MIN_SPEED = "min_speed";
    private static final String JSON_NAME_MAX_SPEED = "max_speed";

    private final int mMaxContentDepth;
    private final int mMaxCumulativeContentItems;
    private final int mMaxStringLength;
    private final Map<Integer, List<RestrictionsPerSpeedRange>> mUxRestrictions = new HashMap<>();

    private CarUxRestrictionsConfiguration(CarUxRestrictionsConfiguration.Builder builder) {
        mMaxContentDepth = builder.mMaxContentDepth;
        mMaxCumulativeContentItems = builder.mMaxCumulativeContentItems;
        mMaxStringLength = builder.mMaxStringLength;

        for (int drivingState : DRIVING_STATES) {
            List<RestrictionsPerSpeedRange> list = new ArrayList<>();
            for (RestrictionsPerSpeedRange r : builder.mUxRestrictions.get(drivingState)) {
                list.add(r);
            }
            mUxRestrictions.put(drivingState, list);
        }
    }

    /**
     * Returns the restrictions based on current driving state and speed.
     */
    public CarUxRestrictions getUxRestrictions(@CarDrivingState int drivingState,
            float currentSpeed) {
        List<RestrictionsPerSpeedRange> restrictions = mUxRestrictions.get(drivingState);
        if (restrictions.isEmpty()) {
            if (Build.IS_ENG || Build.IS_USERDEBUG) {
                throw new IllegalStateException("No restrictions for driving state "
                        + getDrivingStateName(drivingState));
            }
            return createDefaultUxRestrictionsEvent();
        }

        RestrictionsPerSpeedRange restriction = null;
        if (restrictions.size() == 1) {
            restriction = restrictions.get(0);
        } else {
            for (RestrictionsPerSpeedRange r : restrictions) {
                if (r.mSpeedRange != null && r.mSpeedRange.includes(currentSpeed)) {
                    restriction = r;
                    break;
                }
            }
        }

        if (restriction == null) {
            if (Build.IS_ENG || Build.IS_USERDEBUG) {
                throw new IllegalStateException(
                        "No restrictions found for driving state " + drivingState
                                + " at speed " + currentSpeed);
            }
            return createDefaultUxRestrictionsEvent();
        }
        return createUxRestrictionsEvent(restriction.mReqOpt, restriction.mRestrictions);
    }

    private CarUxRestrictions createDefaultUxRestrictionsEvent() {
        return createUxRestrictionsEvent(true,
                CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    /**
     * Creates CarUxRestrictions with restrictions parameters from current configuration.
     */
    private CarUxRestrictions createUxRestrictionsEvent(boolean requiresOpt,
            @CarUxRestrictions.CarUxRestrictionsInfo int uxr) {
        // In case the UXR is not baseline, set requiresDistractionOptimization to true since it
        // doesn't make sense to have an active non baseline restrictions without
        // requiresDistractionOptimization set to true.
        if (uxr != CarUxRestrictions.UX_RESTRICTIONS_BASELINE) {
            requiresOpt = true;
        }
        CarUxRestrictions.Builder builder = new CarUxRestrictions.Builder(requiresOpt, uxr,
                SystemClock.elapsedRealtimeNanos());
        if (mMaxStringLength != Builder.UX_RESTRICTIONS_UNKNOWN) {
            builder.setMaxStringLength(mMaxStringLength);
        }
        if (mMaxCumulativeContentItems != Builder.UX_RESTRICTIONS_UNKNOWN) {
            builder.setMaxCumulativeContentItems(mMaxCumulativeContentItems);
        }
        if (mMaxContentDepth != Builder.UX_RESTRICTIONS_UNKNOWN) {
            builder.setMaxContentDepth(mMaxContentDepth);
        }
        return builder.build();
    }

    // Json de/serialization methods.

    /**
     * Writes current configuration as Json.
     */
    public void writeJson(JsonWriter writer) throws IOException {
        // We need to be lenient to accept infinity number (as max speed).
        writer.setLenient(true);

        writer.beginObject();

        writer.name(JSON_NAME_MAX_CONTENT_DEPTH).value(mMaxContentDepth);
        writer.name(JSON_NAME_MAX_CUMULATIVE_CONTENT_ITEMS).value(
                mMaxCumulativeContentItems);
        writer.name(JSON_NAME_MAX_STRING_LENGTH).value(mMaxStringLength);

        writer.name(JSON_NAME_PARKED_RESTRICTIONS);
        writeRestrictionsList(writer,
                mUxRestrictions.get(CarDrivingStateEvent.DRIVING_STATE_PARKED));

        writer.name(JSON_NAME_IDLING_RESTRICTIONS);
        writeRestrictionsList(writer,
                mUxRestrictions.get(CarDrivingStateEvent.DRIVING_STATE_IDLING));

        writer.name(JSON_NAME_MOVING_RESTRICTIONS);
        writeRestrictionsList(writer,
                mUxRestrictions.get(CarDrivingStateEvent.DRIVING_STATE_MOVING));

        writer.name(JSON_NAME_UNKNOWN_RESTRICTIONS);
        writeRestrictionsList(writer,
                mUxRestrictions.get(CarDrivingStateEvent.DRIVING_STATE_UNKNOWN));

        writer.endObject();
    }

    private void writeRestrictionsList(JsonWriter writer, List<RestrictionsPerSpeedRange> messages)
            throws IOException {
        writer.beginArray();
        for (RestrictionsPerSpeedRange restrictions : messages) {
            writeRestrictions(writer, restrictions);
        }
        writer.endArray();
    }

    private void writeRestrictions(JsonWriter writer, RestrictionsPerSpeedRange restrictions)
            throws IOException {
        writer.beginObject();
        writer.name(JSON_NAME_REQ_OPT).value(restrictions.mReqOpt);
        writer.name(JSON_NAME_RESTRICTIONS).value(restrictions.mRestrictions);
        if (restrictions.mSpeedRange != null) {
            writer.name(JSON_NAME_SPEED_RANGE);
            writer.beginObject();
            writer.name(JSON_NAME_MIN_SPEED).value(restrictions.mSpeedRange.mMinSpeed);
            writer.name(JSON_NAME_MAX_SPEED).value(restrictions.mSpeedRange.mMaxSpeed);
            writer.endObject();
        }
        writer.endObject();
    }

    /**
     * Reads Json as UX restriction configuration.
     */
    public static CarUxRestrictionsConfiguration readJson(JsonReader reader) throws IOException {
        // We need to be lenient to accept infinity number (as max speed).
        reader.setLenient(true);

        Builder builder = new Builder();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (name.equals(JSON_NAME_MAX_CONTENT_DEPTH)) {
                builder.setMaxContentDepth(reader.nextInt());
            } else if (name.equals(JSON_NAME_MAX_CUMULATIVE_CONTENT_ITEMS)) {
                builder.setMaxCumulativeContentItems(reader.nextInt());
            } else if (name.equals(JSON_NAME_MAX_STRING_LENGTH)) {
                builder.setMaxStringLength(reader.nextInt());
            } else if (name.equals(JSON_NAME_PARKED_RESTRICTIONS)) {
                readRestrictionsList(reader, CarDrivingStateEvent.DRIVING_STATE_PARKED, builder);
            } else if (name.equals(JSON_NAME_IDLING_RESTRICTIONS)) {
                readRestrictionsList(reader, CarDrivingStateEvent.DRIVING_STATE_IDLING, builder);
            } else if (name.equals(JSON_NAME_MOVING_RESTRICTIONS)) {
                readRestrictionsList(reader, CarDrivingStateEvent.DRIVING_STATE_MOVING, builder);
            } else if (name.equals(JSON_NAME_UNKNOWN_RESTRICTIONS)) {
                readRestrictionsList(reader, CarDrivingStateEvent.DRIVING_STATE_UNKNOWN, builder);
            } else {
                Log.e(TAG, "Unknown name parsing json config: " + name);
                reader.skipValue();
            }
        }
        reader.endObject();
        return builder.build();
    }

    private static void readRestrictionsList(JsonReader reader, @CarDrivingState int drivingState,
            Builder builder) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            readRestrictions(reader, drivingState, builder);
        }
        reader.endArray();
    }

    private static void readRestrictions(JsonReader reader, @CarDrivingState int drivingState,
            Builder builder) throws IOException {
        reader.beginObject();
        boolean reqOpt = false;
        int restrictions = CarUxRestrictions.UX_RESTRICTIONS_BASELINE;
        Builder.SpeedRange speedRange = null;
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (name.equals(JSON_NAME_REQ_OPT)) {
                reqOpt = reader.nextBoolean();
            } else if (name.equals(JSON_NAME_RESTRICTIONS)) {
                restrictions = reader.nextInt();
            } else if (name.equals(JSON_NAME_SPEED_RANGE)) {
                reader.beginObject();
                // Okay to set min initial value as MAX_SPEED because SpeedRange() won't allow it.
                float minSpeed = Builder.SpeedRange.MAX_SPEED;
                float maxSpeed = Builder.SpeedRange.MAX_SPEED;

                while (reader.hasNext()) {
                    String n = reader.nextName();
                    if (n.equals(JSON_NAME_MIN_SPEED)) {
                        minSpeed = Double.valueOf(reader.nextDouble()).floatValue();
                    } else if (n.equals(JSON_NAME_MAX_SPEED)) {
                        maxSpeed = Double.valueOf(reader.nextDouble()).floatValue();
                    } else {
                        Log.e(TAG, "Unknown name parsing json config: " + n);
                        reader.skipValue();
                    }
                }
                speedRange = new Builder.SpeedRange(minSpeed, maxSpeed);
                reader.endObject();
            }
        }
        reader.endObject();
        builder.setUxRestrictions(drivingState, speedRange, reqOpt, restrictions);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || !(obj instanceof CarUxRestrictionsConfiguration)) {
            return false;
        }

        CarUxRestrictionsConfiguration other = (CarUxRestrictionsConfiguration) obj;

        // Compare UXR parameters.
        if (mMaxContentDepth != other.mMaxContentDepth
                || mMaxCumulativeContentItems != other.mMaxCumulativeContentItems
                || mMaxStringLength != other.mMaxStringLength) {
            return false;
        }

        // Compare UXR by driving state.
        if (!mUxRestrictions.keySet().equals(other.mUxRestrictions.keySet())) {
            return false;
        }
        for (int drivingState : mUxRestrictions.keySet()) {
            List<RestrictionsPerSpeedRange> restrictions = mUxRestrictions.get(
                    drivingState);
            List<RestrictionsPerSpeedRange> otherRestrictions = other.mUxRestrictions.get(
                    drivingState);
            if (restrictions.size() != otherRestrictions.size()) {
                return false;
            }
            // Assuming the restrictions are sorted.
            for (int i = 0; i < restrictions.size(); i++) {
                if (!restrictions.get(i).equals(otherRestrictions.get(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Dump the driving state to UX restrictions mapping.
     */
    public void dump(PrintWriter writer) {
        for (Integer state : mUxRestrictions.keySet()) {
            List<RestrictionsPerSpeedRange> list = mUxRestrictions.get(state);
            writer.println("===========================================");
            writer.println("Driving State to UXR");
            if (list != null) {
                writer.println("State:" + getDrivingStateName(state) + " num restrictions:"
                        + list.size());
                for (RestrictionsPerSpeedRange r : list) {
                    writer.println("Requires DO? " + r.mReqOpt
                            + "\nRestrictions: 0x" + Integer.toHexString(r.mRestrictions)
                            + "\nSpeed Range: " + r.mSpeedRange == null
                            ? "None"
                            : r.mSpeedRange.mMinSpeed + " - " + r.mSpeedRange.mMaxSpeed);
                    writer.println("===========================================");
                }
            }
        }
        writer.println("Max String length: " + mMaxStringLength);
        writer.println("Max Cumulative Content Items: " + mMaxCumulativeContentItems);
        writer.println("Max Content depth: " + mMaxContentDepth);
    }

    private static String getDrivingStateName(@CarDrivingState int state) {
        switch (state) {
            case 0:
                return "parked";
            case 1:
                return "idling";
            case 2:
                return "moving";
            default:
                return "unknown";
        }
    }

    // Parcelable methods/fields.

    // Used by Parcel methods to ensure de/serialization order.
    private static final int[] DRIVING_STATES = new int[]{
            CarDrivingStateEvent.DRIVING_STATE_UNKNOWN,
            CarDrivingStateEvent.DRIVING_STATE_PARKED,
            CarDrivingStateEvent.DRIVING_STATE_IDLING,
            CarDrivingStateEvent.DRIVING_STATE_MOVING
    };

    public static final Parcelable.Creator<CarUxRestrictionsConfiguration> CREATOR =
            new Parcelable.Creator<CarUxRestrictionsConfiguration>() {

        @Override
        public CarUxRestrictionsConfiguration createFromParcel(Parcel source) {
            return new CarUxRestrictionsConfiguration(source);
        }

        @Override
        public CarUxRestrictionsConfiguration[] newArray(int size) {
            return new CarUxRestrictionsConfiguration[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    private CarUxRestrictionsConfiguration(Parcel in) {
        for (int drivingState : DRIVING_STATES) {
            List<RestrictionsPerSpeedRange> restrictions = new ArrayList<>();
            in.readTypedList(restrictions, RestrictionsPerSpeedRange.CREATOR);
            mUxRestrictions.put(drivingState, restrictions);
        }
        mMaxContentDepth = in.readInt();
        mMaxCumulativeContentItems = in.readInt();
        mMaxStringLength = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        for (int drivingState : DRIVING_STATES) {
            dest.writeTypedList(mUxRestrictions.get(drivingState), 0);
        }
        dest.writeInt(mMaxContentDepth);
        dest.writeInt(mMaxCumulativeContentItems);
        dest.writeInt(mMaxStringLength);
    }

    /**
     * @hide
     */
    public static final class Builder {

        private static final int UX_RESTRICTIONS_UNKNOWN = -1;

        private int mMaxContentDepth = UX_RESTRICTIONS_UNKNOWN;
        private int mMaxCumulativeContentItems = UX_RESTRICTIONS_UNKNOWN;
        private int mMaxStringLength = UX_RESTRICTIONS_UNKNOWN;

        private Map<Integer, List<RestrictionsPerSpeedRange>> mUxRestrictions = new HashMap<>();

        public Builder() {
            for (int drivingState : DRIVING_STATES) {
                mUxRestrictions.put(drivingState, new ArrayList<>());
            }
        }

        /**
         * Sets ux restrictions for driving state.
         */
        public Builder setUxRestrictions(@CarDrivingState int drivingState,
                boolean requiresOptimization,
                @CarUxRestrictions.CarUxRestrictionsInfo int restrictions) {
            return this.setUxRestrictions(drivingState, null, requiresOptimization,  restrictions);
        }

        /**
         * Sets ux restrictions with speed range.
         *
         * @param drivingState Restrictions will be set for this Driving state.
         *                     See constants in {@link CarDrivingStateEvent}.
         * @param speedRange If set, restrictions will only apply when current speed is within
         *                   the range. Only {@link CarDrivingStateEvent#DRIVING_STATE_MOVING}
         *                   supports speed range. {@code null} implies the full speed range,
         *                   i.e. zero to {@link SpeedRange#MAX_SPEED}.
         * @param requiresOptimization Whether distraction optimization (DO) is required for this
         *                             driving state.
         * @param restrictions See constants in {@link CarUxRestrictions}.
         */
        public Builder setUxRestrictions(@CarDrivingState int drivingState,
                SpeedRange speedRange, boolean requiresOptimization,
                @CarUxRestrictions.CarUxRestrictionsInfo int restrictions) {
            if (drivingState != CarDrivingStateEvent.DRIVING_STATE_MOVING) {
                if (speedRange != null) {
                    throw new IllegalArgumentException(
                            "Non-moving driving state cannot specify speed range.");
                }
                if (mUxRestrictions.get(drivingState).size() > 0) {
                    throw new IllegalArgumentException("Non-moving driving state cannot have "
                            + "more than one set of restrictions.");
                }
            }

            mUxRestrictions.get(drivingState).add(
                    new RestrictionsPerSpeedRange(requiresOptimization, restrictions, speedRange));
            return this;
        }

        /**
         * Sets max string length.
         */
        public Builder setMaxStringLength(int maxStringLength) {
            mMaxStringLength = maxStringLength;
            return this;
        }

        /**
         * Sets max cumulative content items.
         */
        public Builder setMaxCumulativeContentItems(int maxCumulativeContentItems) {
            mMaxCumulativeContentItems = maxCumulativeContentItems;
            return this;
        }

        /**
         * Sets max content depth.
         */
        public Builder setMaxContentDepth(int maxContentDepth) {
            mMaxContentDepth = maxContentDepth;
            return this;
        }

        /**
         * @return CarUxRestrictionsConfiguration based on builder configuration.
         */
        public CarUxRestrictionsConfiguration build() {
            // Create default restriction for unspecified driving state.
            for (int drivingState : DRIVING_STATES) {
                List<RestrictionsPerSpeedRange> restrictions = mUxRestrictions.get(drivingState);
                if (restrictions.size() == 0) {
                    Log.i(TAG, "Using default restrictions for driving state: "
                            + getDrivingStateName(drivingState));
                    restrictions.add(new RestrictionsPerSpeedRange(
                            true, CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED));
                }
            }

            // Configuration validation.
            for (int drivingState : DRIVING_STATES) {
                List<RestrictionsPerSpeedRange> restrictions = mUxRestrictions.get(drivingState);

                if (drivingState == CarDrivingStateEvent.DRIVING_STATE_MOVING) {
                    // Sort restrictions based on speed range.
                    Collections.sort(restrictions,
                            (r1, r2) -> r1.mSpeedRange.compareTo(r2.mSpeedRange));

                    if (!isAllSpeedRangeCovered(restrictions)) {
                        throw new IllegalStateException(
                                "Moving state should cover full speed range.");
                    }
                } else {
                    if (restrictions.size() != 1) {
                        throw new IllegalStateException("Non-moving driving state should contain "
                                + "one set of restriction rules.");
                    }
                }
            }
            return new CarUxRestrictionsConfiguration(this);
        }

        /**
         * restrictions should be sorted based on speed range.
         */
        private boolean isAllSpeedRangeCovered(List<RestrictionsPerSpeedRange> restrictions) {
            if (restrictions.size() == 1) {
                if (restrictions.get(0).mSpeedRange == null) {
                    // Single restriction with null speed range implies that
                    // it applies to the entire driving state.
                    return true;
                }
                return restrictions.get(0).mSpeedRange.mMinSpeed == 0
                        && Float.compare(restrictions.get(0).mSpeedRange.mMaxSpeed,
                        SpeedRange.MAX_SPEED) == 0;
            }

            if (restrictions.get(0).mSpeedRange.mMinSpeed != 0) {
                Log.e(TAG, "Speed range min speed should start at 0.");
                return false;
            }
            for (int i = 1; i < restrictions.size(); i++) {
                RestrictionsPerSpeedRange prev = restrictions.get(i - 1);
                RestrictionsPerSpeedRange curr = restrictions.get(i);
                // If current min != prev.max, there's either an overlap or a gap in speed range.
                if (Float.compare(curr.mSpeedRange.mMinSpeed, prev.mSpeedRange.mMaxSpeed) != 0) {
                    Log.e(TAG, "Mis-configured speed range. Possibly speed range overlap or gap.");
                    return false;
                }
            }
            // The last speed range should have max speed.
            float lastMaxSpeed = restrictions.get(restrictions.size() - 1).mSpeedRange.mMaxSpeed;
            return lastMaxSpeed == SpeedRange.MAX_SPEED;
        }

        /**
         * Speed range is defined by min and max speed. When there is no upper bound for max speed,
         * set it to {@link SpeedRange#MAX_SPEED}.
         */
        public static final class SpeedRange implements Comparable<SpeedRange> {
            public static final float MAX_SPEED = Float.POSITIVE_INFINITY;

            private float mMinSpeed;
            private float mMaxSpeed;

            /**
             * Defaults max speed to {@link SpeedRange#MAX_SPEED}.
             */
            public SpeedRange(@FloatRange(from = 0.0) float minSpeed) {
                this(minSpeed, MAX_SPEED);
            }

            public SpeedRange(@FloatRange(from = 0.0) float minSpeed,
                    @FloatRange(from = 0.0)float maxSpeed) {
                if (minSpeed == MAX_SPEED) {
                    throw new IllegalArgumentException("Min speed cannot be MAX_SPEED.");
                }
                if (maxSpeed < 0) {
                    throw new IllegalArgumentException("Max speed cannot be negative.");
                }
                if (minSpeed > maxSpeed) {
                    throw new IllegalArgumentException("Min speed " + minSpeed
                            + " should not be greater than max speed " + maxSpeed);
                }
                mMinSpeed = minSpeed;
                mMaxSpeed = maxSpeed;
            }

             /**
             * Return if the given speed is in the range of [minSpeed, maxSpeed).
             *
             * @param speed Speed to check
             * @return {@code true} if in range; {@code false} otherwise.
             */
            public boolean includes(float speed) {
                if (speed < mMinSpeed) {
                    return false;
                }
                if (mMaxSpeed == MAX_SPEED) {
                    return true;
                }
                return speed < mMaxSpeed;
            }

            @Override
            public int compareTo(SpeedRange other) {
                // First compare min speed; then max speed.
                int minSpeedComparison = Float.compare(this.mMinSpeed, other.mMinSpeed);
                if (minSpeedComparison != 0) {
                    return minSpeedComparison;
                }

                return Float.compare(this.mMaxSpeed, other.mMaxSpeed);
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                }
                if (obj == null || !(obj instanceof SpeedRange)) {
                    return false;
                }
                SpeedRange other = (SpeedRange) obj;

                return this.compareTo(other) == 0;
            }
        }
    }

    /**
     * Container for UX restrictions for a speed range.
     * Speed range is valid only for the {@link CarDrivingStateEvent#DRIVING_STATE_MOVING}.
     * @hide
     */
    public static final class RestrictionsPerSpeedRange implements Parcelable {
        final boolean mReqOpt;
        final int mRestrictions;
        @Nullable
        final Builder.SpeedRange mSpeedRange;

        public RestrictionsPerSpeedRange(boolean reqOpt, int restrictions) {
            this(reqOpt, restrictions, null);
        }

        public RestrictionsPerSpeedRange(boolean reqOpt, int restrictions,
                @Nullable Builder.SpeedRange speedRange) {
            if (!reqOpt && restrictions != CarUxRestrictions.UX_RESTRICTIONS_BASELINE) {
                throw new IllegalArgumentException(
                        "Driving optimization is not required but UX restrictions is required.");
            }
            mReqOpt = reqOpt;
            mRestrictions = restrictions;
            mSpeedRange = speedRange;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || !(obj instanceof RestrictionsPerSpeedRange)) {
                return false;
            }
            RestrictionsPerSpeedRange other = (RestrictionsPerSpeedRange) obj;
            return mReqOpt == other.mReqOpt
                    && mRestrictions == other.mRestrictions
                    && ((mSpeedRange == null && other.mSpeedRange == null) || mSpeedRange.equals(
                    other.mSpeedRange));
        }

        // Parcelable methods/fields.

        public static final Creator<RestrictionsPerSpeedRange> CREATOR =
                new Creator<RestrictionsPerSpeedRange>() {
                    @Override
                    public RestrictionsPerSpeedRange createFromParcel(Parcel in) {
                        return new RestrictionsPerSpeedRange(in);
                    }

                    @Override
                    public RestrictionsPerSpeedRange[] newArray(int size) {
                        return new RestrictionsPerSpeedRange[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        protected RestrictionsPerSpeedRange(Parcel in) {
            mReqOpt = in.readBoolean();
            mRestrictions = in.readInt();
            // Whether speed range is specified.
            Builder.SpeedRange speedRange = null;
            if (in.readBoolean()) {
                float minSpeed = in.readFloat();
                float maxSpeed = in.readFloat();
                speedRange = new Builder.SpeedRange(minSpeed, maxSpeed);
            }
            mSpeedRange = speedRange;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeBoolean(mReqOpt);
            dest.writeInt(mRestrictions);
            // Whether speed range is specified.
            dest.writeBoolean(mSpeedRange != null);
            if (mSpeedRange != null) {
                dest.writeFloat(mSpeedRange.mMinSpeed);
                dest.writeFloat(mSpeedRange.mMaxSpeed);
            }
        }
    }
}
