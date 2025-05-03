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

package android.car.media;

import static android.car.media.CarVolumeGroupInfo.INDEX_UNINITIALIZED;
import static android.car.media.CarVolumeGroupEvent.EXTRA_INFO_ATTENUATION_ACTIVATION;
import static android.car.media.CarVolumeGroupEvent.EXTRA_INFO_TRANSIENT_ATTENUATION_DUCKED;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_MEDIA;

import static org.junit.Assert.assertThrows;

import android.car.feature.Flags;
import android.car.test.AbstractExpectableTestCase;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.os.Parcel;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class CarVolumeGroupInfoUnitTest extends AbstractExpectableTestCase {

    private static final int TEST_ZONE_ID = 8;
    private static final int TEST_PRIMARY_GROUP_ID = 7;
    private static final String TEST_GROUP_NAME = "3";
    private static final int TEST_PARCEL_FLAGS = 0;
    private static final int TEST_CURRENT_GAIN = 9_000;
    private static final boolean TEST_DEFAULT_MUTE_STATE = false;
    private static final boolean TEST_DEFAULT_BLOCKED_STATE = false;
    private static final boolean TEST_DEFAULT_ATTENUATED_STATE = false;
    private static final boolean TEST_DEFAULT_LIMITED_STATE = false;
    private static final int TEST_MAX_GAIN_INDEX = 9_005;
    private static final int TEST_MIN_GAIN_INDEX = 0;
    private static final int TEST_MAX_ACTIVATION_GAIN_INDEX = 8_005;
    private static final int TEST_MIN_ACTIVATION_GAIN_INDEX = 1_000;
    private static final boolean TEST_MUTE_BY_SYSTEM_STATE = true;
    private static final boolean TEST_IS_LIMITED = true;
    private static final boolean TEST_IS_BLOCKED = true;
    private static final boolean TEST_IS_ATTENUATED = true;
    private static final int TEST_BLOCKED_GAIN_INDEX_NEW = 2_000;
    private static final int TEST_ATTENUATED_GAIN_INDEX_NEW = 1_000;
    private static final int TEST_LIMITED_GAIN_INDEX_NEW = 3_000;
    private static final AudioAttributes TEST_MEDIA_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();
    private static final AudioAttributes TEST_NAVIGATION_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build();
    private static final AudioAttributes TEST_ASSISTANT_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_ASSISTANT).build();
    private static final List<AudioAttributes> TEST_AUDIO_ATTRIBUTES = List.of(
            TEST_MEDIA_AUDIO_ATTRIBUTE, TEST_NAVIGATION_AUDIO_ATTRIBUTE,
            TEST_ASSISTANT_AUDIO_ATTRIBUTE);
    private static final List<Integer> TEST_ACTIVE_EXTRA_INFOS =
            Collections.singletonList(EXTRA_INFO_ATTENUATION_ACTIVATION);
    private static final List<Integer> TEST_OTHER_ACTIVE_EXTRA_INFOS =
            Arrays.asList(EXTRA_INFO_ATTENUATION_ACTIVATION,
                    EXTRA_INFO_TRANSIENT_ATTENUATION_DUCKED);

    private static final CarVolumeGroupInfo TEST_VOLUME_INFO =
            new CarVolumeGroupInfo.Builder(TEST_GROUP_NAME, TEST_ZONE_ID, TEST_PRIMARY_GROUP_ID)
                    .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                    .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                    .setAudioAttributes(TEST_AUDIO_ATTRIBUTES)
                    .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                    .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX).build();
    private static final AudioDeviceAttributes TEST_AUDIO_DEVICE_ATTRIBUTE =
            new AudioDeviceAttributes(AudioDeviceAttributes.ROLE_OUTPUT,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "a2dp");

    private CarVolumeGroupInfo.Builder getMinimalBuilder() {
        return new CarVolumeGroupInfo.Builder(TEST_GROUP_NAME, TEST_ZONE_ID, TEST_PRIMARY_GROUP_ID)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX).setVolumeGainIndex(TEST_CURRENT_GAIN)
                .setAudioAttributes(TEST_AUDIO_ATTRIBUTES)
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX)
                .setAttenuated(TEST_DEFAULT_ATTENUATED_STATE)
                .setBlocked(TEST_DEFAULT_BLOCKED_STATE).setMuted(TEST_DEFAULT_MUTE_STATE);
    }

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void build_buildsGroupInfo() {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);

        CarVolumeGroupInfo info = new CarVolumeGroupInfo
                .Builder(TEST_GROUP_NAME, TEST_ZONE_ID, TEST_PRIMARY_GROUP_ID)
                .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setVolumeGainIndex(TEST_CURRENT_GAIN)
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX).build();

        expectWithMessage("Car volume info build info zone id")
                .that(info.getZoneId()).isEqualTo(TEST_ZONE_ID);
        expectWithMessage("Car volume info build info group id")
                .that(info.getId()).isEqualTo(TEST_PRIMARY_GROUP_ID);
        expectWithMessage("Car volume info build info group name")
                .that(info.getName()).isEqualTo(TEST_GROUP_NAME);
    }

    @Test
    public void build_buildsGroupInfo_withoutAudioDevices_succeeds() {
        mSetFlagsRule.disableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);

        CarVolumeGroupInfo info = new CarVolumeGroupInfo
                .Builder(TEST_GROUP_NAME, TEST_ZONE_ID, TEST_PRIMARY_GROUP_ID)
                .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setVolumeGainIndex(TEST_CURRENT_GAIN)
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX).build();

        expectWithMessage("Car volume group id, for group built without audio devices")
                .that(info.getId()).isEqualTo(TEST_PRIMARY_GROUP_ID);
    }

    @Test
    public void build_buildsGroupInfo_withAudioDevices_succeeds() {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);

        CarVolumeGroupInfo info = new CarVolumeGroupInfo
                .Builder(TEST_GROUP_NAME, TEST_ZONE_ID, TEST_PRIMARY_GROUP_ID)
                .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setVolumeGainIndex(TEST_CURRENT_GAIN)
                .setAudioDeviceAttributes(List.of(TEST_AUDIO_DEVICE_ATTRIBUTE))
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX).build();

        expectWithMessage("Car volume group info devices")
                .that(info.getAudioDeviceAttributes()).containsExactly(TEST_AUDIO_DEVICE_ATTRIBUTE);
    }

    @Test
    public void build_buildsGroupInfo_withNullAudioDevices_fails() {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);

        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new CarVolumeGroupInfo.Builder(TEST_GROUP_NAME, TEST_ZONE_ID,
                        TEST_PRIMARY_GROUP_ID).setAudioDeviceAttributes(null)
        );

        expectWithMessage("Null audio devices exception")
                .that(thrown).hasMessageThat().contains("Audio Device Attributes");
    }

    @Test
    public void build_buildsGroupInfo_withMinMaxActivationVolume_succeeds() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo
                .Builder(TEST_GROUP_NAME, TEST_ZONE_ID, TEST_PRIMARY_GROUP_ID)
                .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setVolumeGainIndex(TEST_CURRENT_GAIN)
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX).build();

        expectWithMessage("Car volume group info max activation volume")
                .that(info.getMaxActivationVolumeGainIndex())
                .isEqualTo(TEST_MAX_ACTIVATION_GAIN_INDEX);
        expectWithMessage("Car volume group info min activation volume")
                .that(info.getMinActivationVolumeGainIndex())
                .isEqualTo(TEST_MIN_ACTIVATION_GAIN_INDEX);
    }

    @Test
    public void build_buildsGroupInfo_withMinActivationVolumeOutOfMinMaxRange_fails() {
        CarVolumeGroupInfo.Builder infoBuilder = getMinimalBuilder()
                .setMinActivationVolumeGainIndex(TEST_MIN_GAIN_INDEX - 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                infoBuilder::build);

        expectWithMessage("Min activation volume out of range exception")
                .that(thrown).hasMessageThat().contains("Min activation volume gain index");
    }

    @Test
    public void build_buildsGroupInfo_withMaxActivationVolumeOutOfMinMaxRange_fails() {
        CarVolumeGroupInfo.Builder infoBuilder = getMinimalBuilder()
                .setMaxActivationVolumeGainIndex(TEST_MAX_GAIN_INDEX + 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                infoBuilder::build);

        expectWithMessage("Max activation volume out of range exception")
                .that(thrown).hasMessageThat().contains("Max activation volume gain index");
    }

    @Test
    public void build_buildsGroupInfo_withMinLargerThanMaxActivationVolume_fails() {
        CarVolumeGroupInfo.Builder infoBuilder = getMinimalBuilder()
                .setMinActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX - 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                infoBuilder::build);

        expectWithMessage("Min activation volume gain larger than max activation exception")
                .that(thrown).hasMessageThat().contains("must be smaller than max activation");
    }

    @Test
    public void build_buildsGroupInfo_withMuteBySystem_succeeds() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo
                .Builder(TEST_GROUP_NAME, TEST_ZONE_ID, TEST_PRIMARY_GROUP_ID)
                .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setVolumeGainIndex(TEST_CURRENT_GAIN)
                .setAudioDeviceAttributes(List.of(TEST_AUDIO_DEVICE_ATTRIBUTE))
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX)
                .setMutedBySystem(TEST_MUTE_BY_SYSTEM_STATE).build();

        expectWithMessage("Car volume group info system mute state")
                .that(info.isMutedBySystem()).isEqualTo(TEST_MUTE_BY_SYSTEM_STATE);
    }

    @Test
    public void build_withNullName_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new CarVolumeGroupInfo.Builder(/* name= */ null,
                        TEST_ZONE_ID, TEST_PRIMARY_GROUP_ID)
        );

        expectWithMessage("Null volume info name exception")
                .that(thrown).hasMessageThat().contains("Volume info name");
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_withRestrictions_flagEnabled_gettersReturnValues() {
        CarVolumeGroupInfo info = getMinimalBuilder().setLimited(TEST_IS_LIMITED)
                .setBlocked(TEST_IS_BLOCKED).setAttenuated(TEST_IS_ATTENUATED)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS)
                .build();

        expectWithMessage("Limited status").that(info.isLimited()).isEqualTo(TEST_IS_LIMITED);
        expectWithMessage("Limited gain index value")
                .that(info.getLimitedGainIndex()).isEqualTo(TEST_LIMITED_GAIN_INDEX_NEW);
        expectWithMessage("Blocked status").that(info.isBlocked())
                .isEqualTo(TEST_IS_BLOCKED);
        expectWithMessage("Blocked gain index value").that(info.getBlockedGainIndex())
                .isEqualTo(TEST_BLOCKED_GAIN_INDEX_NEW);
        expectWithMessage("Attenuated status").that(info.isAttenuated())
                .isEqualTo(TEST_IS_ATTENUATED);
        expectWithMessage("Attenuated gain index value").that(info.getAttenuatedGainIndex())
                .isEqualTo(TEST_ATTENUATED_GAIN_INDEX_NEW);
        expectWithMessage("Active extra info list")
                .that(info.getActiveExtraInfos()).isEqualTo(TEST_ACTIVE_EXTRA_INFOS);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_withoutSettingRestrictions_flagEnabled_gettersReturnDefaults() {
        CarVolumeGroupInfo info = getMinimalBuilder().build();

        expectWithMessage("Default limited status")
                .that(info.isLimited()).isFalse();
        expectWithMessage("Default blocked gain index value")
                .that(info.getBlockedGainIndex()).isEqualTo(INDEX_UNINITIALIZED);
        expectWithMessage("Default attenuated gain index value")
                .that(info.getAttenuatedGainIndex()).isEqualTo(INDEX_UNINITIALIZED);
        expectWithMessage("Default limited gain index value")
                .that(info.getLimitedGainIndex()).isEqualTo(TEST_MAX_GAIN_INDEX);
        expectWithMessage("Default active extra info list")
                .that(info.getActiveExtraInfos()).isEmpty();
    }

    @Test
    public void setVolumeGainIndex_buildsGroupInfo() {
        CarVolumeGroupInfo info = getMinimalBuilder().setVolumeGainIndex(9_001).build();

        expectWithMessage("Car volume info gain")
                .that(info.getVolumeGainIndex()).isEqualTo(9_001);
    }

    @Test
    public void setMinVolumeGainIndex_buildsGroupInfo() {
        CarVolumeGroupInfo info = getMinimalBuilder().setMinVolumeGainIndex(10).build();

        expectWithMessage("Car volume info min gain")
                .that(info.getMinVolumeGainIndex()).isEqualTo(10);
    }

    @Test
    public void setMaxVolumeGainIndex_buildsGroupInfo() {
        CarVolumeGroupInfo info = getMinimalBuilder().setMaxVolumeGainIndex(9_002).build();

        expectWithMessage("Car volume info max gain")
                .that(info.getMaxVolumeGainIndex()).isEqualTo(9_002);
    }

    @Test
    public void setMaxVolumeGainIndex_withMinLargerThanMax_buildFails() {
        CarVolumeGroupInfo.Builder infoBuilder =
                getMinimalBuilder().setMinVolumeGainIndex(9003)
                        .setMaxVolumeGainIndex(9_002);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> infoBuilder.build());

        expectWithMessage("Max volume gain smaller than min gain exception")
                .that(thrown).hasMessageThat().contains("must be smaller than max");
    }

    @Test
    public void setVolumeGainIndex_withGainOutOfMinMaxRange_buildFails() {
        CarVolumeGroupInfo.Builder infoBuilder =
                getMinimalBuilder().setMinVolumeGainIndex(10)
                        .setMaxVolumeGainIndex(100).setVolumeGainIndex(0);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> infoBuilder.build());

        expectWithMessage("Volume gain index out of range exception")
                .that(thrown).hasMessageThat().contains("out of range");
    }

    @Test
    public void setMuted_buildsGroupInfo() {
        CarVolumeGroupInfo info = getMinimalBuilder().setMuted(true).build();

        expectWithMessage("Car volume info mute state")
                .that(info.isMuted()).isTrue();
    }

    @Test
    public void setAttenuated_buildsGroupInfo() {
        CarVolumeGroupInfo info = getMinimalBuilder().setAttenuated(true).build();

        expectWithMessage("Car volume info attenuated state")
                .that(info.isAttenuated()).isTrue();
    }

    @Test
    public void setBlocked_buildsGroupInfo() {
        CarVolumeGroupInfo info = getMinimalBuilder().setBlocked(true).build();

        expectWithMessage("Car volume info blocked state")
                .that(info.isBlocked()).isTrue();
    }

    @Test
    public void setAudioAttribute_buildsGroupInfo() {
        CarVolumeGroupInfo info = getMinimalBuilder().setAudioAttributes(TEST_AUDIO_ATTRIBUTES)
                .build();

        expectWithMessage("Audio attributes").that(info.getAudioAttributes())
                .containsExactlyElementsIn(TEST_AUDIO_ATTRIBUTES);
    }
    @Test
    public void setAudioAttribute_withNull_buildFails() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> getMinimalBuilder().setAudioAttributes(null));

        expectWithMessage("Null audio attributes exception")
                .that(thrown).hasMessageThat().contains("Audio Attributes");
    }

    @Test
    public void builder_withReuse_fails() {
        CarVolumeGroupInfo.Builder builder = new CarVolumeGroupInfo.Builder(TEST_GROUP_NAME,
                TEST_ZONE_ID, TEST_PRIMARY_GROUP_ID)
                .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setVolumeGainIndex(TEST_CURRENT_GAIN)
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX);
        builder.build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                builder.build()
        );

        expectWithMessage("Reuse builder exception")
                .that(thrown).hasMessageThat().contains("should not be reused");
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_aboveMaxLimitedGainIndex_flagEnabled_throwsException() {
        CarVolumeGroupInfo.Builder builderMax = getMinimalBuilder().setLimited(TEST_IS_LIMITED)
                .setLimitedGainIndex(TEST_MAX_GAIN_INDEX + 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                builderMax.build()
        );

        expectWithMessage("Limited gain index out of max range exception")
                .that(thrown).hasMessageThat().contains("must be in range");
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_aboveMinLimitedGainIndex_flagEnabled_throwsException() {
        CarVolumeGroupInfo.Builder builderMin = getMinimalBuilder().setLimited(TEST_IS_LIMITED)
                .setLimitedGainIndex(TEST_MIN_GAIN_INDEX - 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                builderMin.build()
        );

        expectWithMessage("Limited gain index out of min range exception")
                .that(thrown).hasMessageThat().contains("must be in range");
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_aboveMaxBlockedGainIndex_flagEnabled_throwsException() {
        CarVolumeGroupInfo.Builder builderMax = getMinimalBuilder().setBlocked(TEST_IS_BLOCKED)
                .setBlockedGainIndex(TEST_MAX_GAIN_INDEX + 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                builderMax.build()
        );

        expectWithMessage("Blocked gain index out of max range exception")
                .that(thrown).hasMessageThat().contains("must be in range");
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_aboveMinBlockedGainIndex_flagEnabled_throwsException() {
        CarVolumeGroupInfo.Builder builderMin = getMinimalBuilder().setBlocked(TEST_IS_BLOCKED)
                .setBlockedGainIndex(TEST_MIN_GAIN_INDEX - 5);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                builderMin.build()
        );

        expectWithMessage("Blocked gain index out of min range exception")
                .that(thrown).hasMessageThat().contains("must be in range");
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_aboveMaxAttenuatedGainIndex_flagEnabled_throwsException() {
        CarVolumeGroupInfo.Builder builderMax = getMinimalBuilder()
                .setAttenuated(TEST_IS_ATTENUATED).setAttenuatedGainIndex(TEST_MAX_GAIN_INDEX + 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                builderMax.build()
        );

        expectWithMessage("Attenuated gain index out of max range exception")
                .that(thrown).hasMessageThat().contains("must be in range");
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_aboveMinAttenuatedGainIndex_flagEnabled_throwsException() {
        CarVolumeGroupInfo.Builder builderMin = getMinimalBuilder()
                .setAttenuated(TEST_IS_ATTENUATED).setAttenuatedGainIndex(TEST_MIN_GAIN_INDEX - 5);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                builderMin.build()
        );

        expectWithMessage("Attenuated gain index out of min range exception")
                .that(thrown).hasMessageThat().contains("must be in range");
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_validNewLimitedGainIndices_flagEnabled_succeeds() {
        CarVolumeGroupInfo info = getMinimalBuilder().setLimited(TEST_IS_LIMITED)
                .setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW).build();

        expectWithMessage("Limited status").that(info.isLimited()).isTrue();
        expectWithMessage("Limited gain index value").that(info.getLimitedGainIndex())
                .isEqualTo(TEST_LIMITED_GAIN_INDEX_NEW);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_validNewBlockedGainIndices_flagEnabled_succeeds() {
        CarVolumeGroupInfo info = getMinimalBuilder().setBlocked(TEST_IS_BLOCKED)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW).build();

        expectWithMessage("Blocked status").that(info.isBlocked()).isTrue();
        expectWithMessage("Blocked gain index value").that(info.getBlockedGainIndex())
                .isEqualTo(TEST_BLOCKED_GAIN_INDEX_NEW);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_validNewAttenuatedGainIndices_flagEnabled_succeeds() {
        CarVolumeGroupInfo info = getMinimalBuilder().setAttenuated(TEST_IS_ATTENUATED)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW).build();

        expectWithMessage("Attenuated status").that(info.isAttenuated()).isTrue();
        expectWithMessage("Attenuated gain index value").that(info.getAttenuatedGainIndex())
                .isEqualTo(TEST_ATTENUATED_GAIN_INDEX_NEW);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void build_restrictionNotSetForNewIndices_gainIndexNotValidated_flagEnabled_succeeds() {
        CarVolumeGroupInfo info = getMinimalBuilder()
                .setLimited(TEST_DEFAULT_LIMITED_STATE).setLimitedGainIndex(TEST_MAX_GAIN_INDEX + 1)
                .setBlocked(TEST_DEFAULT_BLOCKED_STATE).setBlockedGainIndex(TEST_MAX_GAIN_INDEX + 1)
                .setAttenuated(TEST_DEFAULT_ATTENUATED_STATE)
                .setAttenuatedGainIndex(TEST_MAX_GAIN_INDEX + 1).build();

        expectWithMessage("Limited status").that(info.isLimited()).isFalse();
        expectWithMessage("Blocked status").that(info.isBlocked()).isFalse();
        expectWithMessage("Attenuated status").that(info.isAttenuated()).isFalse();
        expectWithMessage("Limited gain index value").that(info.getLimitedGainIndex())
                .isEqualTo(TEST_MAX_GAIN_INDEX);
        expectWithMessage("Blocked gain index value").that(info.getBlockedGainIndex())
                .isEqualTo(INDEX_UNINITIALIZED);
        expectWithMessage("Attenuated gain index value").that(info.getAttenuatedGainIndex())
                .isEqualTo(INDEX_UNINITIALIZED);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void builder_setActiveExtraInfos_null_throwsException() {
        CarVolumeGroupInfo.Builder builder = getMinimalBuilder();

        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                builder.setActiveExtraInfos(null)
        );

        expectWithMessage("Active extra infos null exception")
                .that(thrown).hasMessageThat().contains("can not be null");
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void builder_setActiveExtraInfos_invalid_throwsException() {
        CarVolumeGroupInfo.Builder builder = getMinimalBuilder();
        List<Integer> invalidExtraInfos = List.of(EXTRA_INFO_ATTENUATION_ACTIVATION, 1000);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                builder.setActiveExtraInfos(invalidExtraInfos)
        );

        expectWithMessage("Set active extra info with invalid value exception")
                .that(thrown).hasMessageThat().contains("Invalid extra info");
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void builder_fromExistingInfoWithNewRestrictions_flagEnabled_copiesNewRestrictions() {
        CarVolumeGroupInfo originalInfo = getMinimalBuilder().setAttenuated(TEST_IS_ATTENUATED)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setLimited(TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setBlocked(TEST_IS_BLOCKED).setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();

        CarVolumeGroupInfo copiedInfo = new CarVolumeGroupInfo.Builder(originalInfo).build();

        expectWithMessage("Copied blocked status")
                .that(copiedInfo.isBlocked()).isEqualTo(TEST_IS_BLOCKED);
        expectWithMessage("Copied blocked gain index value")
                .that(copiedInfo.getBlockedGainIndex()).isEqualTo(TEST_BLOCKED_GAIN_INDEX_NEW);
        expectWithMessage("Copied attenuated gain index value")
                .that(copiedInfo.getAttenuatedGainIndex())
                .isEqualTo(TEST_ATTENUATED_GAIN_INDEX_NEW);
        expectWithMessage("Copied attenuated status")
                .that(copiedInfo.isAttenuated()).isEqualTo(TEST_IS_ATTENUATED);
        expectWithMessage("Copied limited status")
                .that(copiedInfo.isLimited()).isEqualTo(TEST_IS_LIMITED);
        expectWithMessage("Copied limited gain index value")
                .that(copiedInfo.getLimitedGainIndex()).isEqualTo(TEST_LIMITED_GAIN_INDEX_NEW);
        expectWithMessage("Copied active extra info list")
                .that(copiedInfo.getActiveExtraInfos()).isEqualTo(TEST_ACTIVE_EXTRA_INFOS);
        expectWithMessage("Copied info equals")
                .that(copiedInfo).isEqualTo(originalInfo);
    }

    @Test
    public void writeToParcel_withAllFlagsDisabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        Parcel parcel = Parcel.obtain();

        TEST_VOLUME_INFO.writeToParcel(parcel, TEST_PARCEL_FLAGS);
        parcel.setDataPosition(/* pos= */ 0);

        expectWithMessage("Car volume info from parcel with all flags disabled")
                .that(new CarVolumeGroupInfo(parcel)).isEqualTo(TEST_VOLUME_INFO);
    }

    @Test
    public void writeToParcel_withAllFlagsEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        CarVolumeGroupInfo volumeGroupInfo = new CarVolumeGroupInfo.Builder(TEST_VOLUME_INFO)
                .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setAudioAttributes(TEST_AUDIO_ATTRIBUTES)
                .setAudioDeviceAttributes(List.of(TEST_AUDIO_DEVICE_ATTRIBUTE))
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX)
                .setMutedBySystem(TEST_MUTE_BY_SYSTEM_STATE).build();
        Parcel parcel = Parcel.obtain();

        volumeGroupInfo.writeToParcel(parcel, TEST_PARCEL_FLAGS);
        parcel.setDataPosition(/* pos= */ 0);

        expectWithMessage("Car volume info from parcel with all flags enabled")
                .that(new CarVolumeGroupInfo(parcel)).isEqualTo(volumeGroupInfo);
    }

    @Test
    public void createFromParcel_withAllFlagsDisabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        Parcel parcel = Parcel.obtain();
        TEST_VOLUME_INFO.writeToParcel(parcel, TEST_PARCEL_FLAGS);
        parcel.setDataPosition(/* pos= */ 0);

        expectWithMessage("Car volume info created from parcel with all flags disabled")
                .that(CarVolumeGroupInfo.CREATOR.createFromParcel(parcel))
                .isEqualTo(TEST_VOLUME_INFO);
    }

    @Test
    public void createFromParcel_withAllFlagsEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        CarVolumeGroupInfo volumeGroupInfo = new CarVolumeGroupInfo.Builder(TEST_VOLUME_INFO)
                .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setAudioAttributes(TEST_AUDIO_ATTRIBUTES)
                .setAudioDeviceAttributes(List.of(TEST_AUDIO_DEVICE_ATTRIBUTE))
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX)
                .setMutedBySystem(TEST_MUTE_BY_SYSTEM_STATE).build();
        Parcel parcel = Parcel.obtain();
        volumeGroupInfo.writeToParcel(parcel, TEST_PARCEL_FLAGS);
        parcel.setDataPosition(/* pos= */ 0);

        expectWithMessage("Car volume info created from parcel with all flags enabled")
                .that(CarVolumeGroupInfo.CREATOR.createFromParcel(parcel))
                .isEqualTo(volumeGroupInfo);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void parcelable_withRestrictions_flagEnabled_restoresValues() {
        CarVolumeGroupInfo originalInfo = getMinimalBuilder().setAttenuated(TEST_IS_ATTENUATED)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setBlocked(TEST_IS_BLOCKED).setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW)
                .setLimited(TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();
        Parcel parcel = Parcel.obtain();
        originalInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        CarVolumeGroupInfo restoredInfo = CarVolumeGroupInfo.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        expectWithMessage("Restored limited status")
                .that(restoredInfo.isLimited()).isEqualTo(TEST_IS_LIMITED);
        expectWithMessage("Restored limited gain index value")
                .that(restoredInfo.getLimitedGainIndex()).isEqualTo(TEST_LIMITED_GAIN_INDEX_NEW);
        expectWithMessage("Restored blocked status")
                .that(restoredInfo.isBlocked()).isEqualTo(TEST_IS_BLOCKED);
        expectWithMessage("Restored blocked gain index value")
                .that(restoredInfo.getBlockedGainIndex()).isEqualTo(TEST_BLOCKED_GAIN_INDEX_NEW);
        expectWithMessage("Restored attenuated status")
                .that(restoredInfo.isAttenuated()).isEqualTo(TEST_IS_ATTENUATED);
        expectWithMessage("Restored attenuated gain index value")
                .that(restoredInfo.getAttenuatedGainIndex())
                .isEqualTo(TEST_ATTENUATED_GAIN_INDEX_NEW);
        expectWithMessage("Restored active extra info list")
                .that(restoredInfo.getActiveExtraInfos()).isEqualTo(TEST_ACTIVE_EXTRA_INFOS);
        expectWithMessage("Restored info equals")
                .that(restoredInfo).isEqualTo(originalInfo);
    }

    @Test
    public void newArray() {
        CarVolumeGroupInfo[] infos = CarVolumeGroupInfo.CREATOR.newArray(/* size= */ 3);

        expectWithMessage("Car volume infos size").that(infos)
                .hasLength(3);
    }

    @Test
    public void equals_forSameContent() {
        CarVolumeGroupInfo infoWithSameContent =
                new CarVolumeGroupInfo.Builder(TEST_VOLUME_INFO)
                        .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                        .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                        .setAudioAttributes(TEST_AUDIO_ATTRIBUTES)
                        .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                        .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX).build();

        expectWithMessage("Car volume info with same content")
                .that(infoWithSameContent).isEqualTo(TEST_VOLUME_INFO);
    }

    @Test
    public void equals_forNull() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo.Builder(TEST_GROUP_NAME, TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID).setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setVolumeGainIndex(TEST_CURRENT_GAIN)
                .setAudioAttributes(TEST_AUDIO_ATTRIBUTES)
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX).build();

        expectWithMessage("Car volume info null content")
                .that(info.equals(null)).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void equalsHashCode_withSameNewRestrictions_flagEnabled_areEqual() {
        CarVolumeGroupInfo info1 = getMinimalBuilder()
                .setVolumeGainIndex(TEST_CURRENT_GAIN).setLimited(TEST_IS_LIMITED)
                .setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW).setBlocked(TEST_IS_BLOCKED)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW).setAttenuated(TEST_IS_ATTENUATED)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();
        CarVolumeGroupInfo info2 = getMinimalBuilder()
                .setVolumeGainIndex(TEST_CURRENT_GAIN).setLimited(TEST_IS_LIMITED)
                .setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW).setBlocked(TEST_IS_BLOCKED)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW).setAttenuated(TEST_IS_ATTENUATED)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();

        expectWithMessage("Infos with same new restriction values")
                .that(info1).isEqualTo(info2);
        expectWithMessage("Hash codes for infos with same new restriction values")
                .that(info1.hashCode()).isEqualTo(info2.hashCode());
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void equals_differentIsLimited_flagEnabled_areNotEqual() {
        CarVolumeGroupInfo baseInfo = getMinimalBuilder()
                .setLimited(TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();
        CarVolumeGroupInfo differentIsLimited = getMinimalBuilder()
                .setLimited(!TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();

        expectWithMessage("Car volume group Infos with different limited status")
                .that(baseInfo).isNotEqualTo(differentIsLimited);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void equals_differentLimitedGainIndex_flagEnabled_areNotEqual() {
        CarVolumeGroupInfo baseInfo = getMinimalBuilder()
                .setLimited(TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();
        CarVolumeGroupInfo differentLimitedGainIndex = getMinimalBuilder()
                .setLimited(TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW + 1)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();

        expectWithMessage("Car volume group infos with different limited gain index")
                .that(baseInfo).isNotEqualTo(differentLimitedGainIndex);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void equals_differentBlockedGainIndex_flagEnabled_areNotEqual() {
        CarVolumeGroupInfo baseInfo = getMinimalBuilder()
                .setLimited(TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setBlocked(TEST_IS_BLOCKED).setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW)
                .setAttenuated(TEST_IS_ATTENUATED)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();
        CarVolumeGroupInfo differentBlockedGainIndex = getMinimalBuilder()
                .setLimited(TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setBlocked(TEST_IS_BLOCKED).setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW + 1)
                .setAttenuated(TEST_IS_ATTENUATED)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();

        expectWithMessage("Car volume group infos with different blocked gain index")
                .that(baseInfo).isNotEqualTo(differentBlockedGainIndex);

    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void equals_differentAttenuatedGainIndex_flagEnabled_areNotEqual() {
        CarVolumeGroupInfo baseInfo = getMinimalBuilder()
                .setLimited(TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW).setAttenuated(TEST_IS_ATTENUATED)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();
        CarVolumeGroupInfo differentAttenuatedGainIndex = getMinimalBuilder()
                .setLimited(TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW).setAttenuated(TEST_IS_ATTENUATED)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW + 1)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();

        expectWithMessage("Car volume group infos with different attenuated gain index")
                .that(baseInfo).isNotEqualTo(differentAttenuatedGainIndex);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void equals_differentActiveExtraInfos_flagEnabled_areNotEqual() {
        CarVolumeGroupInfo baseInfo = getMinimalBuilder()
                .setLimited(TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();
        CarVolumeGroupInfo differentActiveExtraInfos = getMinimalBuilder()
                .setLimited(TEST_IS_LIMITED).setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_OTHER_ACTIVE_EXTRA_INFOS).build();

        expectWithMessage("Car volume group infos with different active extra infos")
                .that(baseInfo).isNotEqualTo(differentActiveExtraInfos);
    }

    @Test
    public void describeContents() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo.Builder(TEST_GROUP_NAME, TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID).setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setVolumeGainIndex(TEST_CURRENT_GAIN)
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX).build();

        expectWithMessage("Car volume info contents")
                .that(info.describeContents()).isEqualTo(0);
    }

    @Test
    public void hashCode_forSameContent_forAllFlagsDisabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        CarVolumeGroupInfo infoWithSameContent = new CarVolumeGroupInfo.Builder(TEST_VOLUME_INFO)
                .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setAudioAttributes(TEST_AUDIO_ATTRIBUTES)
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX).build();

        expectWithMessage("Car volume info hash with same content")
                .that(infoWithSameContent.hashCode()).isEqualTo(TEST_VOLUME_INFO.hashCode());
    }

    @Test
    public void hashCode_forSameContent_forAllFlagsEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        CarVolumeGroupInfo originalInfo = new CarVolumeGroupInfo.Builder(TEST_VOLUME_INFO)
                .setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setAudioAttributes(TEST_AUDIO_ATTRIBUTES)
                .setAudioDeviceAttributes(List.of(TEST_AUDIO_DEVICE_ATTRIBUTE))
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX)
                .setMutedBySystem(TEST_MUTE_BY_SYSTEM_STATE).build();

        CarVolumeGroupInfo infoWithSameContent =
                new CarVolumeGroupInfo.Builder(originalInfo).build();

        expectWithMessage("Car volume info hash with same content, with all flags enabled")
                .that(infoWithSameContent.hashCode()).isEqualTo(originalInfo.hashCode());
    }

    @Test
    public void toString_forContent() {
        CarVolumeGroupInfo info = new CarVolumeGroupInfo.Builder(TEST_GROUP_NAME, TEST_ZONE_ID,
                TEST_PRIMARY_GROUP_ID).setMaxVolumeGainIndex(TEST_MAX_GAIN_INDEX)
                .setMinVolumeGainIndex(TEST_MIN_GAIN_INDEX)
                .setVolumeGainIndex(TEST_CURRENT_GAIN)
                .setAudioAttributes(TEST_AUDIO_ATTRIBUTES)
                .setMaxActivationVolumeGainIndex(TEST_MAX_ACTIVATION_GAIN_INDEX)
                .setMinActivationVolumeGainIndex(TEST_MIN_ACTIVATION_GAIN_INDEX).build();

        String carVolumeGroupInfoString = info.toString();

        expectWithMessage("Car volume info name")
                .that(carVolumeGroupInfoString).contains(TEST_GROUP_NAME);
        expectWithMessage("Car volume info group id")
                .that(carVolumeGroupInfoString).contains(Integer.toString(TEST_PRIMARY_GROUP_ID));
        expectWithMessage("Car volume info group zone")
                .that(carVolumeGroupInfoString).contains(Integer.toString(TEST_ZONE_ID));
        expectWithMessage("Car volume group audio attributes")
                .that(carVolumeGroupInfoString).contains(TEST_ASSISTANT_AUDIO_ATTRIBUTE.toString());
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void toString_includesNewRestrictions_flagEnabled() {
        CarVolumeGroupInfo info = getMinimalBuilder().setLimited(TEST_IS_LIMITED)
                .setBlocked(TEST_IS_BLOCKED).setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW)
                .setAttenuated(TEST_IS_ATTENUATED)
                .setAttenuatedGainIndex(TEST_ATTENUATED_GAIN_INDEX_NEW)
                .setLimitedGainIndex(TEST_LIMITED_GAIN_INDEX_NEW)
                .setActiveExtraInfos(TEST_ACTIVE_EXTRA_INFOS).build();

        String infoString = info.toString();

        expectWithMessage("Car volume group info blocked status")
                .that(infoString).contains("blocked = " + TEST_IS_BLOCKED);
        expectWithMessage("Car volume group info blocked gain index")
                .that(infoString).contains("blocked gain index = " + TEST_BLOCKED_GAIN_INDEX_NEW);
        expectWithMessage("Car volume group info attenuated status")
                .that(infoString).contains("attenuated = " + TEST_IS_ATTENUATED);
        expectWithMessage("Car volume group info attenuated gain index")
                .that(infoString).contains("attenuated gain index = "
                        + TEST_ATTENUATED_GAIN_INDEX_NEW);
        expectWithMessage("Car volume group info limited status")
                .that(infoString).contains("limited = " + TEST_IS_LIMITED);
        expectWithMessage("Car volume group info limited gain index")
                .that(infoString).contains("limited gain index = " + TEST_LIMITED_GAIN_INDEX_NEW);
        expectWithMessage("Car volume group info active extra infos")
                .that(infoString).contains("active extra infos = "
                        + TEST_ACTIVE_EXTRA_INFOS.toString());
    }

    @Test
    @DisableFlags(Flags.FLAG_AUDIO_SEND_RESTRICTIONS_TO_OEM_VOLUME_SERVICE)
    public void toString_excludesNewRestrictions_flagDisabled() {
        CarVolumeGroupInfo info = getMinimalBuilder().setLimited(TEST_IS_LIMITED)
                .setBlockedGainIndex(TEST_BLOCKED_GAIN_INDEX_NEW).build();

        String infoString = info.toString();


        expectWithMessage("Car volume group info limited status when flag disable")
                .that(infoString).doesNotContain("limited =");
        expectWithMessage("Car volume group info limited gain index when flag disabled")
                .that(infoString).doesNotContain("limited gain index =");
        expectWithMessage("Car volume group info attenuated gain index when flag disabled")
                .that(infoString).doesNotContain("attenuated gain index =");
        expectWithMessage("Car volume group info blocked gain index when flag disabled")
                .that(infoString).doesNotContain("blocked gain index =");
        expectWithMessage("Car volume group info active extra info when flag disabled")
                .that(infoString).doesNotContain("active extra infos =");
    }
}
