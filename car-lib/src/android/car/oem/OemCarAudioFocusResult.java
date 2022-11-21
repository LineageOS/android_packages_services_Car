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

package android.car.oem;

import static android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.car.annotation.ApiRequirements;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * Class to encapsulate the audio focus result from the OEM audio service
 *
 * @hide
 */
@SystemApi
@ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
        minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
public final class OemCarAudioFocusResult implements Parcelable {
    private final @Nullable AudioFocusEntry mAudioFocusEntry;
    private final @NonNull List<AudioFocusEntry> mNewlyLossAudioFocusEntries;
    private final @NonNull List<AudioFocusEntry> mNewlyBlockedAudioFocusEntries;
    private final int mAudioFocusResult;

    OemCarAudioFocusResult(
            @Nullable AudioFocusEntry audioFocusEntry,
            @NonNull List<AudioFocusEntry> newlyLossAudioFocusEntries,
            @NonNull List<AudioFocusEntry> newlyBlockedAudioFocusEntries, int audioFocusResult) {
        Preconditions.checkArgument(newlyLossAudioFocusEntries != null,
                "Newly loss focus entries can not be null");
        Preconditions.checkArgument(newlyBlockedAudioFocusEntries != null,
                "Newly blocked focus entries can not be null");
        this.mAudioFocusEntry = audioFocusEntry;
        this.mNewlyLossAudioFocusEntries = newlyLossAudioFocusEntries;
        this.mNewlyBlockedAudioFocusEntries = newlyBlockedAudioFocusEntries;
        this.mAudioFocusResult = audioFocusResult;
    }

    /**
     * Returns the result of the focus request
     * The result can be granted, delayed, or failed. In the case of granted the car audio stack
     * will be changed according to the entries returned in newly loss and newly blocked.
     * For delayed results the entry will be added as the current delayed request and it will be
     * re-evaluated once any of the current focus holders abandons focus. For failed request,
     * the car audio focus stack will not change and the current request will not gain focus.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public @Nullable AudioFocusEntry getAudioFocusEntry() {
        return new AudioFocusEntry.Builder(mAudioFocusEntry).build();
    }

    /**
     * Returns the entries that were previously holding focus but now have lost focus.
     *
     * <p>Note: the lost can be permanent or transient, in the case of permanent loss the entry
     * will receive permanent focus loss and it will be removed from the car audio focus stack.
     * For transient losses, the new entry will be added as a blocker but will only receive
     * transient focus loss.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public @NonNull List<AudioFocusEntry> getNewlyLossAudioFocusEntries() {
        return new ArrayList<>(mNewlyLossAudioFocusEntries);
    }

    /**
     * Returns the entries that had previously lost focus and continue to be blocked by new entry
     *
     * <p>Note: the block can be permanent or transient, in the case of permanent block the entry
     * will receive permanent focus loss and it will be removed from the car audio focus stack.
     * For transient losses, the new entry will be added as a blocker but will only receive
     * transient focus loss.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public @NonNull List<AudioFocusEntry> getNewlyBlockedAudioFocusEntries() {
        return new ArrayList<>(mNewlyBlockedAudioFocusEntries);
    }

    /**
     * Returns the focus results, must be on of {@link AudioManager.AUDIOFOCUS_GAIN},
     * {@link AudioManager.AUDIOFOCUS_LOSS}, {@link AudioManager.AUDIOFOCUS_LOSS_TRANSIENT},
     * {@link AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public int getAudioFocusResult() {
        return mAudioFocusResult;
    }

    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public String toString() {
        return new StringBuilder().append("OemCarAudioFocusResult { audioFocusEntry = ")
                .append(mAudioFocusEntry)
                .append(", mNewlyLossAudioFocusEntries = ").append(mNewlyLossAudioFocusEntries)
                .append(", mNewlyBlockedAudioFocusEntries = ")
                .append(mNewlyBlockedAudioFocusEntries)
                .append(", mAudioFocusResult = ").append(mAudioFocusResult)
                .append(" }").toString();
    }

    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        byte flg = 0;
        if (mAudioFocusEntry != null) flg = (byte) (flg | Builder.FOCUS_ENTRY_FIELDS_SET);
        dest.writeByte(flg);
        if (mAudioFocusEntry != null) {
            dest.writeTypedObject(mAudioFocusEntry, flags);
        }
        dest.writeParcelableList(mNewlyLossAudioFocusEntries, flags);
        dest.writeParcelableList(mNewlyBlockedAudioFocusEntries, flags);
        dest.writeInt(mAudioFocusResult);
    }

    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    OemCarAudioFocusResult(@NonNull Parcel in) {
        byte flg = in.readByte();
        AudioFocusEntry audioFocusEntry = (flg & Builder.FOCUS_ENTRY_FIELDS_SET) == 0
                ? null : (AudioFocusEntry) in.readTypedObject(AudioFocusEntry.CREATOR);
        List<AudioFocusEntry> audioFocusLosers = new java.util.ArrayList<>();
        in.readParcelableList(audioFocusLosers, AudioFocusEntry.class.getClassLoader());
        List<AudioFocusEntry> audioFocusBlocked = new java.util.ArrayList<>();
        in.readParcelableList(audioFocusBlocked, AudioFocusEntry.class.getClassLoader());
        int audioFocusResult = in.readInt();

        this.mAudioFocusEntry = audioFocusEntry;
        this.mNewlyLossAudioFocusEntries = audioFocusLosers;
        this.mNewlyBlockedAudioFocusEntries = audioFocusBlocked;
        this.mAudioFocusResult = audioFocusResult;
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @NonNull
    public static final OemCarAudioFocusResult EMPTY_OEM_CAR_AUDIO_FOCUS_RESULTS =
            new OemCarAudioFocusResult(null,
                    /* newlyLossAudioFocusEntries= */ new ArrayList<>(/* initialCapacity= */ 0),
                    /* newlyBlockedAudioFocusEntries= */ new ArrayList<>(/* initialCapacity= */ 0),
                    AUDIOFOCUS_REQUEST_FAILED);

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @NonNull
    public static final Parcelable.Creator<OemCarAudioFocusResult> CREATOR =
            new Parcelable.Creator<OemCarAudioFocusResult>() {
        @Override
        public OemCarAudioFocusResult[] newArray(int size) {
            return new OemCarAudioFocusResult[size];
        }

        @Override
        public OemCarAudioFocusResult createFromParcel(@NonNull Parcel in) {
            return new OemCarAudioFocusResult(in);
        }
    };

    /**
     * A builder for {@link OemCarAudioFocusResult}
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {

        private static final int FOCUS_ENTRY_FIELDS_SET = 0x1;
        private static final int NEWLY_LOSS_FIELDS_SET = 0x2;
        private static final int NEWLY_BLOCKED_FIELDS_SET = 0x4;
        private static final int FOCUS_RESULT_FIELDS_SET = 0x8;
        private static final int BUILDER_USED_FIELDS_SET = 0x10;
        private @Nullable AudioFocusEntry mAudioFocusEntry;
        private @NonNull List<AudioFocusEntry> mNewlyLossAudioFocusEntries;
        private @NonNull List<AudioFocusEntry> mNewlyBlockedAudioFocusEntries;
        private int mAudioFocusResult;

        private long mBuilderFieldsSet = 0L;

        public Builder(
                @NonNull List<AudioFocusEntry> newlyLossAudioFocusEntries,
                @NonNull List<AudioFocusEntry> newlyBlockedAudioFocusEntries,
                int audioFocusResult) {
            Preconditions.checkArgument(newlyLossAudioFocusEntries != null,
                    "Newly loss focus entries can not be null");
            Preconditions.checkArgument(newlyBlockedAudioFocusEntries != null,
                    "Newly blocked focus entries can not be null");
            mNewlyLossAudioFocusEntries = newlyLossAudioFocusEntries;
            mNewlyBlockedAudioFocusEntries = newlyBlockedAudioFocusEntries;
            mAudioFocusResult = audioFocusResult;
        }

        /** @see OemCarAudioFocusResult#getAudioFocusEntry */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public Builder setAudioFocusEntry(@NonNull AudioFocusEntry focusEntry) {
            Preconditions.checkArgument(focusEntry != null,
                    "Focus entry can not be null");
            checkNotUsed();
            mBuilderFieldsSet |= FOCUS_ENTRY_FIELDS_SET;
            mAudioFocusEntry = focusEntry;
            return this;
        }

        /** @see OemCarAudioFocusResult#getNewlyLossAudioFocusEntries */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public Builder setNewlyLossAudioFocusEntries(
                @NonNull List<AudioFocusEntry> newlyLossAudioFocusEntries) {
            Preconditions.checkArgument(newlyLossAudioFocusEntries != null,
                    "Newly loss focus entries can not be null");
            checkNotUsed();
            mBuilderFieldsSet |= NEWLY_LOSS_FIELDS_SET;
            mNewlyLossAudioFocusEntries = newlyLossAudioFocusEntries;
            return this;
        }

        /** @see #setNewlyLossAudioFocusEntries */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public Builder addNewlyLossAudioFocusEntry(@NonNull AudioFocusEntry lossEntry) {
            Preconditions.checkArgument(lossEntry != null,
                    "Newly loss focus entry can not be null");
            if (mNewlyLossAudioFocusEntries == null) {
                setNewlyLossAudioFocusEntries(new java.util.ArrayList<>());
            }
            mNewlyLossAudioFocusEntries.add(lossEntry);
            return this;
        }

        /** @see OemCarAudioFocusResult#getNewlyBlockedAudioFocusEntries */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public Builder setNewlyBlockedAudioFocusEntries(
                @NonNull List<AudioFocusEntry> newlyBlockedAudioFocusEntries) {
            Preconditions.checkArgument(newlyBlockedAudioFocusEntries != null,
                    "Newly blocked focus entries can not be null");
            checkNotUsed();
            mBuilderFieldsSet |= NEWLY_BLOCKED_FIELDS_SET;
            mNewlyBlockedAudioFocusEntries = newlyBlockedAudioFocusEntries;
            return this;
        }

        /** @see #setNewlyBlockedAudioFocusEntries */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public Builder addNewlyBlockedAudioFocusEntry(
                @NonNull AudioFocusEntry blockedEntry) {
            Preconditions.checkArgument(blockedEntry != null,
                    "Newly blocked focus entry can not be null");
            if (mNewlyBlockedAudioFocusEntries == null) {
                setNewlyBlockedAudioFocusEntries(new java.util.ArrayList<>());
            }
            mNewlyBlockedAudioFocusEntries.add(blockedEntry);
            return this;
        }

        /** @see OemCarAudioFocusResult#getAudioFocusResult */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public Builder setAudioFocusResult(int audioFocusResult) {
            mBuilderFieldsSet |= FOCUS_RESULT_FIELDS_SET;
            mAudioFocusResult = audioFocusResult;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
                minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public OemCarAudioFocusResult build() {
            checkNotUsed();
            mBuilderFieldsSet |= BUILDER_USED_FIELDS_SET; // Mark builder used

            OemCarAudioFocusResult o = new OemCarAudioFocusResult(
                    mAudioFocusEntry,
                    mNewlyLossAudioFocusEntries,
                    mNewlyBlockedAudioFocusEntries,
                    mAudioFocusResult);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & BUILDER_USED_FIELDS_SET) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
