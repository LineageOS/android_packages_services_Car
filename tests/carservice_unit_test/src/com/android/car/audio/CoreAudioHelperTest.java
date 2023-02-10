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

package com.android.car.audio;

import static com.android.car.audio.CoreAudioRoutingUtils.INVALID_GROUP_ID;
import static com.android.car.audio.CoreAudioRoutingUtils.INVALID_GROUP_NAME;
import static com.android.car.audio.CoreAudioRoutingUtils.INVALID_STRATEGY_ID;
import static com.android.car.audio.CoreAudioRoutingUtils.MUSIC_ATTRIBUTES;
import static com.android.car.audio.CoreAudioRoutingUtils.MUSIC_GROUP;
import static com.android.car.audio.CoreAudioRoutingUtils.MUSIC_GROUP_ID;
import static com.android.car.audio.CoreAudioRoutingUtils.MUSIC_GROUP_NAME;
import static com.android.car.audio.CoreAudioRoutingUtils.MUSIC_STRATEGY;
import static com.android.car.audio.CoreAudioRoutingUtils.MUSIC_STRATEGY_ID;
import static com.android.car.audio.CoreAudioRoutingUtils.NAV_ATTRIBUTES;
import static com.android.car.audio.CoreAudioRoutingUtils.NAV_GROUP;
import static com.android.car.audio.CoreAudioRoutingUtils.NAV_GROUP_ID;
import static com.android.car.audio.CoreAudioRoutingUtils.NAV_GROUP_NAME;
import static com.android.car.audio.CoreAudioRoutingUtils.NAV_STRATEGY;
import static com.android.car.audio.CoreAudioRoutingUtils.NAV_STRATEGY_ID;
import static com.android.car.audio.CoreAudioRoutingUtils.OEM_ATTRIBUTES;
import static com.android.car.audio.CoreAudioRoutingUtils.OEM_GROUP;
import static com.android.car.audio.CoreAudioRoutingUtils.OEM_GROUP_ID;
import static com.android.car.audio.CoreAudioRoutingUtils.OEM_GROUP_NAME;
import static com.android.car.audio.CoreAudioRoutingUtils.OEM_STRATEGY;
import static com.android.car.audio.CoreAudioRoutingUtils.OEM_STRATEGY_ID;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.media.AudioManager;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public final class CoreAudioHelperTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = CoreAudioHelperTest.class.getSimpleName();

    public CoreAudioHelperTest() {
        super(CoreAudioHelper.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(AudioManager.class);
    }
    @Rule
    public final Expect expect = Expect.create();

    @Before
    public void setUp() throws Exception {
        List<AudioVolumeGroup> groups = CoreAudioRoutingUtils.getVolumeGroups();
        List<AudioProductStrategy> strategies = CoreAudioRoutingUtils.getProductStrategies();
        doReturn(strategies).when(AudioManager::getAudioProductStrategies);
        doReturn(groups).when(AudioManager::getAudioVolumeGroups);
    }

    @Test
    public void getStrategy_fromIdOrNull() {
        expect.withMessage("Music strategy for id (%s)", MUSIC_STRATEGY_ID)
                .that(CoreAudioHelper.getStrategy(MUSIC_STRATEGY_ID))
                .isEqualTo(MUSIC_STRATEGY);

        expect.withMessage("Nav strategy for id (%s)", NAV_STRATEGY_ID)
                .that(CoreAudioHelper.getStrategy(NAV_STRATEGY_ID))
                .isEqualTo(NAV_STRATEGY);

        expect.withMessage("OEM strategy for id(%s)", OEM_STRATEGY_ID)
                .that(CoreAudioHelper.getStrategy(OEM_STRATEGY_ID))
                .isEqualTo(OEM_STRATEGY);

        expect.withMessage("Invalid strategy id(%s)", INVALID_STRATEGY_ID)
                .that(CoreAudioHelper.getStrategy(INVALID_STRATEGY_ID))
                .isNull();
    }

    @Test
    public void getVolumeGroup_fromName_orNull() {
        expect.withMessage("Music group for name (%s)", MUSIC_GROUP_NAME)
                .that(CoreAudioHelper.getVolumeGroup(MUSIC_GROUP_NAME))
                .isEqualTo(MUSIC_GROUP);

        expect.withMessage("Nav group for name (%s)", NAV_GROUP_NAME)
                .that(CoreAudioHelper.getVolumeGroup(NAV_GROUP_NAME))
                .isEqualTo(NAV_GROUP);

        expect.withMessage("OEM group for name (%s)", OEM_GROUP_NAME)
                .that(CoreAudioHelper.getVolumeGroup(OEM_GROUP_NAME))
                .isEqualTo(OEM_GROUP);

        expect.withMessage("Invalid group for name (%s)", INVALID_GROUP_NAME)
                .that(CoreAudioHelper.getVolumeGroup(INVALID_GROUP_NAME))
                .isNull();
    }

    @Test
    public void selectAttributes_forVolumeGroupName() {
        expect.withMessage("Music best attributes for group name (%s)", MUSIC_GROUP_NAME)
                .that(CoreAudioHelper.selectAttributesForVolumeGroupName(
                        MUSIC_GROUP_NAME))
                .isEqualTo(MUSIC_ATTRIBUTES);

        expect.withMessage("Nav best attributes for group name (%s)", NAV_GROUP_NAME)
                .that(CoreAudioHelper.selectAttributesForVolumeGroupName(NAV_GROUP_NAME))
                .isEqualTo(NAV_ATTRIBUTES);

        expect.withMessage("OEM best attributes for group name (%s)", OEM_GROUP_NAME)
                .that(CoreAudioHelper.selectAttributesForVolumeGroupName(OEM_GROUP_NAME))
                .isEqualTo(OEM_ATTRIBUTES);

        expect.withMessage("Best attributes  for Invalid group for name (%s)", INVALID_GROUP_NAME)
                .that(CoreAudioHelper.selectAttributesForVolumeGroupName(
                        INVALID_GROUP_NAME))
                .isEqualTo(CoreAudioHelper.DEFAULT_ATTRIBUTES);
    }

    @Test
    public void selectVolumeGroupName_fromCoreId_orNull() {
        expect.withMessage("Music group name for id (%s)", MUSIC_GROUP_ID)
                .that(CoreAudioHelper.getVolumeGroupNameFromCoreId(MUSIC_GROUP_ID))
                .isEqualTo(MUSIC_GROUP_NAME);

        expect.withMessage("Nav group name for id (%s)", NAV_GROUP_ID)
                .that(CoreAudioHelper.getVolumeGroupNameFromCoreId(NAV_GROUP_ID))
                .isEqualTo(NAV_GROUP_NAME);

        expect.withMessage("OEM group name for id (%s)", OEM_GROUP_ID)
                .that(CoreAudioHelper.getVolumeGroupNameFromCoreId(OEM_GROUP_ID))
                .isEqualTo(OEM_GROUP_NAME);

        expect.withMessage("Null group name for invalid id (%s)", INVALID_GROUP_ID)
                .that(CoreAudioHelper.getVolumeGroupNameFromCoreId(INVALID_GROUP_ID))
                .isNull();
    }

    @Test
    public void isDefaultStrategy() {
        expect.withMessage("Music strategy is the default")
                .that(CoreAudioHelper.isDefaultStrategy(MUSIC_STRATEGY_ID))
                .isTrue();

        expect.withMessage("Nav is not the default")
                .that(CoreAudioHelper.isDefaultStrategy(NAV_STRATEGY_ID))
                .isFalse();

        expect.withMessage("Nav is not the default")
                .that(CoreAudioHelper.isDefaultStrategy(OEM_STRATEGY_ID))
                .isFalse();

        expect.withMessage("Invalid strategy is not the default")
                .that(CoreAudioHelper.isDefaultStrategy(INVALID_STRATEGY_ID))
                .isFalse();
    }
}
