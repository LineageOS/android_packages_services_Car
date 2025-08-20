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

import static com.google.common.truth.Truth.assertWithMessage;

import android.car.test.AbstractExpectableTestCase;
import android.media.AudioAttributes;
import android.os.Parcel;

import org.junit.Test;

public final class EnforcedAudioFocusInfoUnitTest extends AbstractExpectableTestCase {

    private static final int TEST_UID = 1000;
    private static final AudioAttributes TEST_AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build();
    private static final boolean TEST_IS_SILENCED = true;
    private static final EnforcedAudioFocusInfo TEST_INFO = new EnforcedAudioFocusInfo(TEST_UID,
            TEST_AUDIO_ATTRIBUTES, TEST_IS_SILENCED);

    @Test
    public void constructor_succeeds() {
        EnforcedAudioFocusInfo info = new EnforcedAudioFocusInfo(TEST_UID, TEST_AUDIO_ATTRIBUTES,
                TEST_IS_SILENCED);

        expectWithMessage("UID from constructor").that(info.getUid()).isEqualTo(TEST_UID);
        expectWithMessage("Audio attributes from constructor")
                .that(info.getAudioAttributes()).isEqualTo(TEST_AUDIO_ATTRIBUTES);
        expectWithMessage("Silenced state from constructor")
                .that(info.isSilenced()).isEqualTo(TEST_IS_SILENCED);
    }

    @Test
    public void writeToParcel_recreatesObject() {
        Parcel parcel = Parcel.obtain();

        TEST_INFO.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);
        EnforcedAudioFocusInfo newInfo = EnforcedAudioFocusInfo.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        expectWithMessage("Recreated object from parcel").that(newInfo).isEqualTo(TEST_INFO);
    }

    @Test
    public void createFromParcel_recreatesObject() {
        Parcel parcel = Parcel.obtain();
        TEST_INFO.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        EnforcedAudioFocusInfo newInfo = EnforcedAudioFocusInfo.CREATOR.createFromParcel(parcel);

        expectWithMessage("Recreated object from creator").that(newInfo).isEqualTo(TEST_INFO);
    }

    @Test
    public void newArray_returnsArrayOfCorrectSize() {
        EnforcedAudioFocusInfo[] array = EnforcedAudioFocusInfo.CREATOR.newArray(10);

        expectWithMessage("New array size").that(array).hasLength(10);
    }

    @Test
    public void describeContents_returnsZero() {
        expectWithMessage("Describe contents return value")
                .that(TEST_INFO.describeContents()).isEqualTo(0);
    }

    @Test
    public void equals_forSameContent_returnsTrue() {
        EnforcedAudioFocusInfo infoWithSameContent = new EnforcedAudioFocusInfo(TEST_UID,
                TEST_AUDIO_ATTRIBUTES, TEST_IS_SILENCED);

        assertWithMessage("Enforced audio focus info with same content")
                .that(infoWithSameContent.equals(TEST_INFO)).isTrue();
    }

    @Test
    public void equals_forDifferentUid_returnsFalse() {
        EnforcedAudioFocusInfo infoWithDifferentUid = new EnforcedAudioFocusInfo(TEST_UID + 1,
                TEST_AUDIO_ATTRIBUTES, TEST_IS_SILENCED);

        expectWithMessage("Enforced audio focus info with different uid")
                .that(infoWithDifferentUid).isNotEqualTo(TEST_INFO);
    }

    @Test
    public void equals_forDifferentAudioAttributes_returnsFalse() {
        AudioAttributes differentAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build();
        EnforcedAudioFocusInfo infoWithDifferentAttributes = new EnforcedAudioFocusInfo(TEST_UID,
                differentAttributes, TEST_IS_SILENCED);

        expectWithMessage("Enforced audio focus info with different audio attributes")
                .that(infoWithDifferentAttributes).isNotEqualTo(TEST_INFO);
    }

    @Test
    public void equals_forDifferentIsSilenced_returnsFalse() {
        EnforcedAudioFocusInfo infoWithDifferentSilencedState = new EnforcedAudioFocusInfo(
                TEST_UID, TEST_AUDIO_ATTRIBUTES, !TEST_IS_SILENCED);

        expectWithMessage("Enforced audio focus info with different silenced state")
                .that(infoWithDifferentSilencedState).isNotEqualTo(TEST_INFO);
    }

    @Test
    public void equals_forNull_returnsFalse() {
        expectWithMessage("Enforced audio focus info null content")
                .that(TEST_INFO.equals(null)).isFalse();
    }

    @Test
    public void hashCode_forSameContent_areEqual() {
        EnforcedAudioFocusInfo infoWithSameContent = new EnforcedAudioFocusInfo(TEST_UID,
                TEST_AUDIO_ATTRIBUTES, TEST_IS_SILENCED);

        expectWithMessage("Hash code for info with same content")
                .that(infoWithSameContent.hashCode()).isEqualTo(TEST_INFO.hashCode());
    }

    @Test
    public void toString_containsMembers() {
        String infoString = TEST_INFO.toString();

        expectWithMessage("To string for uid").that(infoString).contains(String.valueOf(TEST_UID));
        expectWithMessage("To string for audio attributes")
                .that(infoString).contains(TEST_AUDIO_ATTRIBUTES.toString());
        expectWithMessage("To string for silenced state")
                .that(infoString).contains(String.valueOf(TEST_IS_SILENCED));
    }
}
