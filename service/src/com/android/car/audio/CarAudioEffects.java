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

import static android.car.builtin.os.UserManagerHelper.USER_NULL;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.UserIdInt;
import android.car.builtin.util.Slogf;

import com.android.car.CarLog;
import com.android.car.audio.hal.AudioControlWrapper;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Manages and provides access to car audio effects, including fade and balance.
 *
 * <p>This class provides functionalities to:
 * <ul>
 *     <li>Set and retrieve fade and balance levels for individual users.</li>
 *     <li>Apply fade and balance adjustments to the underlying audio system via an
 *      {@link AudioControlWrapper}.</li>
 *     <li>Persist audio effect settings using a {@link CarAudioSettings} instance, if persistence
 *      is enabled.</li>
 *     <li>Restore previously saved settings for a user.</li>
 * </ul>
 *
 * <p><b>Fade:</b> Controls the distribution of audio between the front and rear speakers.
 * <ul>
 *     <li>-1.0: Full audio to the rear speakers.</li>
 *     <li>0.0: Balanced audio between front and rear speakers.</li>
 *     <li>1.0: Full audio to the front speakers.</li>
 * </ul>
 *
 * <p><b>Balance:</b> Controls the distribution of audio between the left and right speakers.
 * <ul>
 *     <li>-1.0: Full audio to the left speakers.</li>
 *     <li>0.0: Balanced audio between left and right speakers.</li>
 *     <li>1.0: Full audio to the right speakers.</li>
 * </ul>
 *
 * <p>The persistence behavior is controlled by the {@code shouldPersist} flag in the constructor.
 * <ul>
 *     <li>If persistence is enabled, settings are stored and retrieved using the provided
 *     {@link CarAudioSettings}.</li>
 *     <li>If persistence is disabled, changes are not stored, and cached values are used when
 *     retrieving settings for current user. For other users, default values are returned.</li>
 * </ul>
 */
public final class CarAudioEffects {
    private static final String TAG = CarLog.TAG_AUDIO;
    // Balanced audio between front and rear speakers
    private static final float AUDIO_DEFAULT_FADE_LEVEL = 0.0f;
    // Balanced audio between left and right speakers
    private static final float AUDIO_DEFAULT_BALANCE_LEVEL = 0.0f;

    private final CarAudioSettings mCarAudioSettings;
    private final AudioControlWrapper mAudioControlWrapper;
    private final boolean mShouldPersist;

    private float mCurrentUserFadeLevel = AUDIO_DEFAULT_FADE_LEVEL;
    private float mCurrentUserBalanceLevel = AUDIO_DEFAULT_BALANCE_LEVEL;

    /**
     * Constructs a new instance of CarAudioEffects.
     *
     * @param carAudioSettings The {@link CarAudioSettings} instance used for persistence.
     * @param audioControlWrapper The {@link AudioControlWrapper} instance used for audio control.
     * @param shouldPersist Whether persistence is enabled or not.
     */
    CarAudioEffects(CarAudioSettings carAudioSettings, AudioControlWrapper audioControlWrapper,
                    boolean shouldPersist) {
        mCarAudioSettings = Objects.requireNonNull(carAudioSettings,
                "Car audio settings cannot be null");
        mAudioControlWrapper = Objects.requireNonNull(audioControlWrapper,
                "Audio control wrapper cannot be null");
        mShouldPersist = shouldPersist;
    }

    /**
     * Restores the fade and balance levels for a specific user to their previous values.
     *
     * <p>This method retrieves the current fade and balance levels for the given user and then
     * re-applies them, effectively "restoring" their state.
     *
     * @param userId The ID of the user whose fade and balance levels should be restored.
     * @see #setFadeLevelForUser(int, float)
     * @see #setBalanceLevelForUser(int, float)
     * @see #getFadeLevelForUser(int)
     * @see #getBalanceLevelForUser(int)
     */
    void restoreAudioEffectsForUser(@UserIdInt int userId) {
        Slogf.v(TAG, "Restoring fade and balance levels for user %d", userId);
        mCurrentUserFadeLevel = AUDIO_DEFAULT_FADE_LEVEL;
        mCurrentUserBalanceLevel = AUDIO_DEFAULT_BALANCE_LEVEL;
        setFadeLevelForUser(userId, getFadeLevelForUser(userId));
        setBalanceLevelForUser(userId, getBalanceLevelForUser(userId));
    }

    /**
     * Sets the fade level for a specific user.
     *
     * @param userId The ID of the user.
     * @param level  The fade level to set. It must be within the range [-1.0, 1.0].
     *               -1.0 represents full rear, 1.0 represents full front, and 0.0 is centered.
     * @throws IllegalArgumentException if the level is outside the valid range.
     */
    void setFadeLevelForUser(@UserIdInt int userId, float level) {
        Slogf.v(TAG, "Setting fade level for user %d to %f", userId, level);
        requireValidFadeRange(level);
        mAudioControlWrapper.setFadeTowardFront(level);
        mCurrentUserFadeLevel = level;

        // If the user is invalid, it means the user has logged out. In this case, we do not want to
        // persist the settings, so we return early.
        if (isInvalidUser(userId) || !mShouldPersist) {
            return;
        }

        mCarAudioSettings.storeFadeLevelForUser(userId, level);
    }

    /**
     * Sets the balance level for a specific user.
     *
     * @param userId The ID of the user.
     * @param level  The balance level to set. It must be within the range [-1.0, 1.0].
     *               -1.0 represents full left, 1.0 represents full right, and 0.0 is centered.
     * @throws IllegalArgumentException if the level is outside the valid range.
     */
    void setBalanceLevelForUser(@UserIdInt int userId, float level) {
        Slogf.v(TAG, "Setting balance level for user %d to %f", userId, level);
        requireValidBalanceRange(level);
        mAudioControlWrapper.setBalanceTowardRight(level);
        mCurrentUserBalanceLevel = level;

        // If the user is invalid, it means the user has logged out. In this case, we do not want to
        // persist the settings, so we return early.
        if (isInvalidUser(userId) || !mShouldPersist) {
            return;
        }

        mCarAudioSettings.storeBalanceLevelForUser(userId, level);
    }

    /**
     * Gets the fade level for a specific user.
     *
     * @param userId The ID of the user.
     * @return The current fade level for the user. If persistence is disabled or invalid user,
     *     returns a default value.
     */
    float getFadeLevelForUser(@UserIdInt int userId) {
        if (mShouldPersist) {
            return mCarAudioSettings.getFadeLevelForUser(userId, AUDIO_DEFAULT_FADE_LEVEL);
        }
        return mCurrentUserFadeLevel;
    }

    /**
     * Gets the balance level for a specific user.
     *
     * @param userId The ID of the user.
     * @return The current balance level for the user. If persistence is disabled or invalid user,
     *     returns a default value.
     */
    float getBalanceLevelForUser(@UserIdInt int userId) {
        if (mShouldPersist) {
            return mCarAudioSettings.getBalanceLevelForUser(userId, AUDIO_DEFAULT_BALANCE_LEVEL);
        }
        return mCurrentUserBalanceLevel;
    }

    private void requireValidFadeRange(float value) {
        Preconditions.checkArgumentInRange(value, -1f, 1f,
                "Fade level must be within the range [-1.0, 1.0]");
    }

    private void requireValidBalanceRange(float value) {
        Preconditions.checkArgumentInRange(value, -1f, 1f,
                "Balance level must be within the range [-1.0, 1.0]");
    }

    private boolean isInvalidUser(@UserIdInt int userId) {
        return userId == USER_NULL;
    }

    /**
     * Dumps the state of the audio parameters for a specific user to the provided writer.
     *
     * @param writer The {@link IndentingPrintWriter} to write the output to.
     * @param userId The ID of the user whose audio parameters should be dumped.
     */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer, @UserIdInt int userId) {
        writer.println();
        writer.printf("User ID: %d\n", userId);
        writer.increaseIndent();
        writer.printf("Fade level: %f\n", getFadeLevelForUser(userId));
        writer.printf("Balance level: %f\n", getBalanceLevelForUser(userId));
        writer.decreaseIndent();
    }
}
