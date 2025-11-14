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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.os.SystemProperties;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.CarLog;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SystemPropertiesWrapperTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = CarLog.TAG_AUDIO;
    public SystemPropertiesWrapperTest() {
        super(SystemPropertiesWrapperTest.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(SystemProperties.class);
    }

    @Test
    public void areAudioPatchAPIsEnabled_whenEnabled() {
        var enabled = true;
        doReturn(enabled).when(() -> SystemProperties.getBoolean(eq(SystemPropertiesWrapper
                .PROPERTY_RO_ENABLE_AUDIO_PATCH), anyBoolean()));
        SystemPropertiesWrapper wrapper = new SystemPropertiesWrapper();

        assertWithMessage("Audio patches APIs when enabled")
                .that(wrapper.areAudioPatchAPIsEnabled()).isTrue();
    }

    @Test
    public void areAudioPatchAPIsEnabled_whenDisabled() {
        var enabled = false;
        doReturn(enabled).when(() -> SystemProperties.getBoolean(eq(SystemPropertiesWrapper
                .PROPERTY_RO_ENABLE_AUDIO_PATCH), anyBoolean()));
        SystemPropertiesWrapper wrapper = new SystemPropertiesWrapper();

        assertWithMessage("Audio patches APIs when disabled")
                .that(wrapper.areAudioPatchAPIsEnabled()).isFalse();
    }
}
