/*
 * Copyright (C) 2025 The Android Open Source Project
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
package android.car.media;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.car.feature.Flags;
import android.media.AudioAttributes;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Represents information about an app's audio focus enforcement state.
 *
 * <p>This class provides details about a specific application, its audio
 * attributes, and whether its audio playback is currently being silenced due
 * to audio focus enforcement.
 *
 * @hide
 */
@TestApi
@FlaggedApi(Flags.FLAG_AUDIO_FOCUS_ENFORCEMENT)
public final class EnforcedAudioFocusInfo implements Parcelable {

    @NonNull
    public static final Creator<EnforcedAudioFocusInfo> CREATOR =
            new Creator<EnforcedAudioFocusInfo>() {
                @Override
                public EnforcedAudioFocusInfo createFromParcel(Parcel in) {
                    return new EnforcedAudioFocusInfo(in);
                }

                @Override
                public EnforcedAudioFocusInfo[] newArray(int size) {
                    return new EnforcedAudioFocusInfo[size];
                }
            };

    private final int mAppUid;
    private final AudioAttributes mAudioAttributes;
    private final boolean mIsSilenced;

    /**
     * @param appUid          The unique User ID (UID) of the application.
     * @param audioAttributes The { @link android.media.AudioAttributes} of the audio stream being
     *                        enforced.
     * @param isSilenced      Indicates whether the app's audio playback is currently silenced.
     */
    public EnforcedAudioFocusInfo(int appUid, @NonNull AudioAttributes audioAttributes,
            boolean isSilenced) {
        mAppUid = appUid;
        mAudioAttributes = Objects.requireNonNull(audioAttributes,
                "Audio attributes cannot be null");
        mIsSilenced = isSilenced;
    }

    private EnforcedAudioFocusInfo(Parcel in) {
        mAppUid = in.readInt();
        mAudioAttributes = in.readParcelable(AudioAttributes.class.getClassLoader(),
                AudioAttributes.class);
        mIsSilenced = in.readBoolean();
    }

    /**
     * Returns the unique User ID (UID) of the client (app/service) that was (un)silenced.
     *
     * @return UID of the client that was (un)silenced.
     */
    public int getUid() {
        return mAppUid;
    }

    /**
     * Returns the { @link android.media.AudioAttributes} of the audio stream being enforced.
     *
     * @return Audio attributes of the audio stream being enforced.
     */
    @NonNull
    public AudioAttributes getAudioAttributes() {
        return mAudioAttributes;
    }

    /**
     * Returns whether the app's audio playback is currently being silenced due to audio focus
     *      enforcement.
     *
     * @return Boolean of {@code true} if the app's audio playback is being silenced,
     *      {@code false} otherwise.
     */
    public boolean isSilenced() {
        return mIsSilenced;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAppUid);
        dest.writeParcelable(mAudioAttributes, flags);
        dest.writeBoolean(mIsSilenced);
    }

    @Override
    public String toString() {
        return "EnforcedAudioFocusInfo[ appUid = " + mAppUid
                + ", audioAttributes = " + mAudioAttributes
                + ", isSilenced = " + mIsSilenced + " ]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof EnforcedAudioFocusInfo that)) {
            return false;
        }

        return mAppUid == that.mAppUid && mIsSilenced == that.mIsSilenced
                && Objects.equals(mAudioAttributes, that.mAudioAttributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAppUid, mAudioAttributes, mIsSilenced);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
