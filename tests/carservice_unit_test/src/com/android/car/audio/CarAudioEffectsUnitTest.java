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
package com.android.car.audio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertThrows;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.MockSettings;
import android.content.ContentResolver;
import android.content.Context;

import com.android.car.audio.hal.AudioControlWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class CarAudioEffectsUnitTest extends AbstractExtendedMockitoTestCase {
    private static final int TEST_USER_ID = 0;
    private static final float TEST_FADE_LEVEL = 0.5f;
    private static final float TEST_BALANCE_LEVEL = -0.5f;
    private static final float INVALID_FADE_LEVEL = 2.0f;
    private static final float INVALID_BALANCE_LEVEL = -2.0f;
    private static final int INVALID_USER_ID = -1;
    private static final float DEFAULT_FADE_LEVEL = 0.0f;
    private static final float DEFAULT_BALANCE_LEVEL = 0.0f;

    private CarAudioEffects mCarAudioEffects;
    private CarAudioSettings mCarAudioSettings;
    // Sets mockStatic() expectations on Settings, suppress the warning as it's not directly read
    // in this test.
    @SuppressWarnings("UnusedVariable")
    private MockSettings mMockSettings;
    @Mock
    private AudioControlWrapper mMockAudioControlWrapper;
    @Mock
    private Context mMockContext;
    @Mock
    private ContentResolver mMockContentResolver;

    public CarAudioEffectsUnitTest() {
        super(NO_LOG_TAGS);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        mMockSettings = new MockSettings(session);
    }

    @Before
    public void setUp() {
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockContext.createContextAsUser(any(), anyInt())).thenReturn(mMockContext);
        mCarAudioSettings = spy(new CarAudioSettings(mMockContext));
        mCarAudioEffects = new CarAudioEffects(mCarAudioSettings, mMockAudioControlWrapper,
                /* shouldPersist= */ true);
    }

    @Test
    public void constructor_nullCarAudioSettings_throws() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new CarAudioEffects(null, mMockAudioControlWrapper, /* shouldPersist= */ true));

        expectWithMessage("Exception for null car audio settings").that(thrown).hasMessageThat()
                .contains("Car audio settings cannot be null");
    }

    @Test
    public void constructor_nullAudioControlWrapper_throws() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new CarAudioEffects(mCarAudioSettings, null, /* shouldPersist= */ true));

        expectWithMessage("Exception for null audio control wrapper").that(thrown).hasMessageThat()
                .contains("Audio control wrapper cannot be null");
    }

    @Test
    public void setFadeLevelForUser_verifyValueSet() {
        mCarAudioEffects.setFadeLevelForUser(TEST_USER_ID, TEST_FADE_LEVEL);

        verify(mMockAudioControlWrapper).setFadeTowardFront(eq(TEST_FADE_LEVEL));
        verify(mCarAudioSettings).storeFadeLevelForUser(eq(TEST_USER_ID), eq(TEST_FADE_LEVEL));
    }

    @Test
    public void setFadeLevelForUser_noPersistence_verifyNotValueSet() {
        mCarAudioEffects = new CarAudioEffects(mCarAudioSettings, mMockAudioControlWrapper,
                /* shouldPersist= */ false);

        mCarAudioEffects.setFadeLevelForUser(TEST_USER_ID, TEST_FADE_LEVEL);

        verify(mMockAudioControlWrapper).setFadeTowardFront(eq(TEST_FADE_LEVEL));
        verify(mCarAudioSettings, never()).storeFadeLevelForUser(anyInt(), anyFloat());
    }

    @Test
    public void setFadeLevelForUser_invalidLevel_throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                mCarAudioEffects.setFadeLevelForUser(TEST_USER_ID, INVALID_FADE_LEVEL));

        expectWithMessage("Set fade level with invalid level").that(thrown).hasMessageThat()
                .contains("within the range [-1.0, 1.0]");
    }

    @Test
    public void setBalanceLevelForUser_verifyValueSet() {
        mCarAudioEffects.setBalanceLevelForUser(TEST_USER_ID, TEST_BALANCE_LEVEL);

        verify(mMockAudioControlWrapper).setBalanceTowardRight(eq(TEST_BALANCE_LEVEL));
        verify(mCarAudioSettings).storeBalanceLevelForUser(eq(TEST_USER_ID),
                eq(TEST_BALANCE_LEVEL));
    }

    @Test
    public void setBalanceLevelForUser_noPersistence_verifyNotValueSet() {
        mCarAudioEffects = new CarAudioEffects(mCarAudioSettings, mMockAudioControlWrapper,
                /* shouldPersist= */ false);

        mCarAudioEffects.setBalanceLevelForUser(TEST_USER_ID, TEST_BALANCE_LEVEL);

        verify(mMockAudioControlWrapper).setBalanceTowardRight(eq(TEST_BALANCE_LEVEL));
        verify(mCarAudioSettings, never()).storeBalanceLevelForUser(anyInt(), anyFloat());
    }

    @Test
    public void setBalanceLevelForUser_invalidLevel_throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                mCarAudioEffects.setBalanceLevelForUser(TEST_USER_ID, INVALID_BALANCE_LEVEL));

        expectWithMessage("Set balance level with invalid level").that(thrown).hasMessageThat()
                .contains("within the range [-1.0, 1.0]");
    }

    @Test
    public void getFadeLevelForUser_validUser_returnStoredValue() {
        mCarAudioEffects.setFadeLevelForUser(TEST_USER_ID, TEST_FADE_LEVEL);

        float result = mCarAudioEffects.getFadeLevelForUser(TEST_USER_ID);

        expectWithMessage("Get fade level").that(result).isEqualTo(TEST_FADE_LEVEL);
    }

    @Test
    public void getFadeLevelForUser_invalidUser_returnDefaultValue() {
        float result = mCarAudioEffects.getFadeLevelForUser(INVALID_USER_ID);

        expectWithMessage("Persisted fade level for invalid user").that(result)
                .isEqualTo(DEFAULT_FADE_LEVEL);
    }

    @Test
    public void getFadeLevelForUser_noPersistence_returnDefaultValue() {
        mCarAudioEffects = new CarAudioEffects(mCarAudioSettings, mMockAudioControlWrapper,
                /* shouldPersist= */ false);

        float result = mCarAudioEffects.getFadeLevelForUser(TEST_USER_ID);

        expectWithMessage("Fade level for non persistence").that(result)
                .isEqualTo(DEFAULT_FADE_LEVEL);
    }

    @Test
    public void getBalanceLevelForUser_validUser_returnStoredValue() {
        mCarAudioEffects.setBalanceLevelForUser(TEST_USER_ID, TEST_BALANCE_LEVEL);

        float result = mCarAudioEffects.getBalanceLevelForUser(TEST_USER_ID);

        expectWithMessage("Get balance level").that(result).isEqualTo(TEST_BALANCE_LEVEL);
    }

    @Test
    public void getBalanceLevelForUser_invalidUser_returnDefaultValue() {
        float result = mCarAudioEffects.getBalanceLevelForUser(INVALID_USER_ID);

        expectWithMessage("Persisted balance level for invalid user").that(result)
                .isEqualTo(DEFAULT_BALANCE_LEVEL);
    }

    @Test
    public void getBalanceLevelForUser_noPersistence_returnDefaultValue() {
        mCarAudioEffects = new CarAudioEffects(mCarAudioSettings, mMockAudioControlWrapper,
                /* shouldPersist= */ false);

        float result = mCarAudioEffects.getBalanceLevelForUser(TEST_USER_ID);

        expectWithMessage("Balance level for non persistence").that(result)
                .isEqualTo(DEFAULT_BALANCE_LEVEL);
    }

    @Test
    public void restoreAudioEffectsForUser_setsAndGetsFadeAndBalanceLevels() {
        mCarAudioEffects.restoreAudioEffectsForUser(TEST_USER_ID);

        float fadeLevel = mCarAudioEffects.getFadeLevelForUser(TEST_USER_ID);
        float balanceLevel = mCarAudioEffects.getBalanceLevelForUser(TEST_USER_ID);

        expectWithMessage("Fade level after restore").that(fadeLevel).isEqualTo(DEFAULT_FADE_LEVEL);
        expectWithMessage("Balance level after restore").that(balanceLevel)
                .isEqualTo(DEFAULT_BALANCE_LEVEL);
    }
}
