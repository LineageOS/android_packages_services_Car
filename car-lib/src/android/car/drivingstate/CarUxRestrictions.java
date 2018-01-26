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

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Car UX Restrictions event.  This contains information on the set of UX restrictions
 * that is in place due to the car's driving state.
 * <p>
 * The restriction information is organized as follows:
 * <ul>
 * <li> When there are no restrictions in place, for example when the car is parked,
 * <ul>
 * <li> {@link #mRequiresDistractionOptimization} is set to false.  Apps can display activities
 * that are not distraction optimized.
 * <li> {@link #mActiveRestrictions} should contain UX_RESTRICTIONS_UNRESTRICTED.  Apps don't
 * have to check for this since {@code mRequiresDistractionOptimization} is false.
 * </ul>
 * <li> When the driving state changes, causing the UX restrictions to come in effect,
 * <ul>
 * <li> {@code mRequiresDistractionOptimization} is set to true.  Apps can only display
 * activities that are distraction optimized.  Distraction optimized activities follow the base
 * design guidelines that provide a distraction free driving user experience.
 * <li> In addition, apps will have to check for the content of mActiveRestrictions.
 * {@code mActiveRestrictions} will have additional granular information on the set of UX
 * restrictions that are in place for the current driving state.  The content of
 * {@code mActiveRestrictions}, for the same driving state of the vehicle, could vary depending
 * on the car maker and the market.  For example, when the car is idling, the set of active
 * UX restrictions contained in the {@code mActiveRestrictions} will depend on the car maker
 * and the safety standards of the market that the vehicle is deployed in.
 * </ul>
 * </ul>
 * <p>
 * Apps that intend to be run when the car is being driven need to
 * <ul>
 * <li> Comply with the general distraction optimization guidelines.
 * <li> Listen and react to the UX restrictions changes as detailed above.  Since the restrictions
 * could vary depending on the market, apps are expected to react to the restriction information
 * and not to the absolute driving state.
 * </ul>
 */
public class CarUxRestrictions implements Parcelable {

    // UXRestrictions TODO(b/69859857): make it configurable
    /**
     * No UX Restrictions.  Vehicle Optimized apps are allowed to display non Drive Optimized
     * Activities.
     */
    public static final int UX_RESTRICTIONS_UNRESTRICTED = 0;

    // Granular UX Restrictions that are imposed when distraction optimization is required.
    // This list is still not final - b/72155508
    /**
     * No text based search allowed
     */
    public static final int UX_RESTRICTIONS_NO_TEXT_SEARCH = 1;

    /**
     * No text based filtering allowed
     */
    public static final int UX_RESTRICTIONS_NO_TEXT_FILTERING = 0x1 << 1;

    /**
     * Displayed string length is limited
     */

    public static final int UX_RESTRICTIONS_LIMIT_STRING_LENGTH = 0x1 << 2;

    /**
     * No field entry like login etc.
     */
    public static final int UX_RESTRICTIONS_NO_FIELD_ENTRY = 0x1 << 3;

    /**
     * No videos or animation allowed
     */
    public static final int UX_RESTRICTIONS_NO_VIDEO = 0x1 << 4;

    /**
     * Limit the number of content items displayed on the screen
     */
    public static final int UX_RESTRICTIONS_LIMIT_CONTENT_ITEMS = 0x1 << 5;

    /**
     * No setup configuration allowed
     */
    public static final int UX_RESTRICTIONS_NO_SETUP_CONFIG = 0x1 << 6;

    /**
     * All the above restrictions are in effect.
     */
    public static final int UX_RESTRICTIONS_FULLY_RESTRICTED =
            UX_RESTRICTIONS_NO_TEXT_SEARCH | UX_RESTRICTIONS_NO_TEXT_FILTERING
                    | UX_RESTRICTIONS_LIMIT_STRING_LENGTH | UX_RESTRICTIONS_NO_FIELD_ENTRY
                    | UX_RESTRICTIONS_NO_VIDEO | UX_RESTRICTIONS_LIMIT_CONTENT_ITEMS
                    | UX_RESTRICTIONS_NO_SETUP_CONFIG;

    @IntDef(flag = true,
            prefix = { "UX_RESTRICTIONS_" },
            value = {UX_RESTRICTIONS_UNRESTRICTED,
                    UX_RESTRICTIONS_NO_TEXT_SEARCH,
                    UX_RESTRICTIONS_NO_TEXT_FILTERING,
                    UX_RESTRICTIONS_LIMIT_STRING_LENGTH,
                    UX_RESTRICTIONS_NO_FIELD_ENTRY,
                    UX_RESTRICTIONS_NO_VIDEO,
                    UX_RESTRICTIONS_LIMIT_CONTENT_ITEMS,
                    UX_RESTRICTIONS_NO_SETUP_CONFIG})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CarUxRestrictionsInfo {
    }

    private final long mTimeStamp;
    private final boolean mRequiresDistractionOptimization;
    @CarUxRestrictionsInfo
    private final int mActiveRestrictions;

    /**
     * Time at which this UX restriction event was deduced based on the car's driving state.
     *
     * @return Elapsed time in nanoseconds since system boot.
     */
    public long getTimeStamp() {
        return mTimeStamp;
    }

    /**
     * Conveys if the foreground activity needs to be distraction optimized.
     * Activities that can handle distraction optimization need to be tagged as a distraction
     * optimized in the app's manifest.
     * <p>
     * If the app has a foreground activity that has not been distraction optimized, the app has
     * to switch to another activity that is distraction optimized.  Failing that, the system will
     * stop the foreground activity.
     *
     * @return true if distraction optimization is required, false if not
     */
    public boolean isRequiresDistractionOptimization() {
        return mRequiresDistractionOptimization;
    }

    /**
     * A combination of the Car UX Restrictions that is active for the current state of driving.
     *
     * @return A combination of the above {@code @CarUxRestrictionsInfo}
     */
    @CarUxRestrictionsInfo
    public int getActiveRestrictions() {
        return mActiveRestrictions;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mActiveRestrictions);
        dest.writeLong(mTimeStamp);
        dest.writeInt(mRequiresDistractionOptimization ? 1 : 0);
    }

    public static final Parcelable.Creator<CarUxRestrictions> CREATOR
            = new Parcelable.Creator<CarUxRestrictions>() {
        public CarUxRestrictions createFromParcel(Parcel in) {
            return new CarUxRestrictions(in);
        }

        public CarUxRestrictions[] newArray(int size) {
            return new CarUxRestrictions[size];
        }
    };

    public CarUxRestrictions(boolean reqOpt, int restrictions, long time) {
        mRequiresDistractionOptimization = reqOpt;
        mActiveRestrictions = restrictions;
        mTimeStamp = time;
    }

    private CarUxRestrictions(Parcel in) {
        mActiveRestrictions = in.readInt();
        mTimeStamp = in.readLong();
        mRequiresDistractionOptimization = in.readInt() != 0;
    }

    @Override
    public String toString() {
        return "DO: " + mRequiresDistractionOptimization + " UxR: " + mActiveRestrictions
                + " time: " + mTimeStamp;
    }

    /**
     * Compares if the restrictions are the same.  Doesn't compare the timestamps.
     *
     * @param other the other CarUxRestrictions object
     * @return true if the restrictions are same, false otherwise
     */
    public boolean isSameRestrictions(CarUxRestrictions other) {
        if (other == null) {
            return false;
        }
        if (other == this) {
            return true;
        }
        return other.mRequiresDistractionOptimization == mRequiresDistractionOptimization
                && other.mActiveRestrictions == mActiveRestrictions;
    }
}
