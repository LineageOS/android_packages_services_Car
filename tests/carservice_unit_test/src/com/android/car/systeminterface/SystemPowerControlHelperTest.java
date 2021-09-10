/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.systeminterface;

import static com.android.car.systeminterface.SystemPowerControlHelper.SUSPEND_RESULT_SUCCESS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;

import com.android.car.test.utils.TemporaryFile;

import libcore.io.IoUtils;

import org.junit.Test;

import java.util.function.IntSupplier;

/**
 * Unit tests for {@link SystemPowerControlHelper}
 *
 * <p> Run:
 * {@code atest SystemPowerControlHelper}
 */
public final class SystemPowerControlHelperTest extends AbstractExtendedMockitoTestCase {
    private static final String POWER_STATE_FILE = "power_state";

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(SystemPowerControlHelper.class);
    }

    @Test
    public void testForceDeepSleep() throws Exception {
        testHelperMockedFileWrite(SystemPowerControlHelper::forceDeepSleep,
                SystemPowerControlHelper.SUSPEND_TARGET_MEM);
    }

    @Test
    public void testForceHibernate() throws Exception {
        testHelperMockedFileWrite(SystemPowerControlHelper::forceHibernate,
                SystemPowerControlHelper.SUSPEND_TARGET_DISK);
    }

    private void testHelperMockedFileWrite(IntSupplier consumer, String target) throws Exception {
        assertSpied(SystemPowerControlHelper.class);
        try (TemporaryFile powerStateControlFile = new TemporaryFile(POWER_STATE_FILE)) {
            when(SystemPowerControlHelper.getSysFsPowerControlFile())
                    .thenReturn(powerStateControlFile.getFile().getAbsolutePath());

            assertThat(consumer.getAsInt()).isEqualTo(SUSPEND_RESULT_SUCCESS);
            assertThat(IoUtils.readFileAsString(powerStateControlFile.getFile().getAbsolutePath()))
                    .isEqualTo(target);
        }
    }
}
