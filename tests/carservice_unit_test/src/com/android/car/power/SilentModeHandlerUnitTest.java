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
package com.android.car.power;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.os.SystemClock;

import com.android.car.test.utils.TemporaryFile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests for {@link SilentModeHandler}.
 */
@RunWith(MockitoJUnitRunner.class)
public final class SilentModeHandlerUnitTest {
    private static final String BOOT_REASON_NORMAL = "reboot,shell";
    private static final String BOOT_REASON_FORCED_SILENT = "reboot,forcedsilent";
    private static final String BOOT_REASON_FORCED_NON_SILENT = "reboot,forcednonsilent";
    private static final String VALUE_SILENT_MODE = "1";
    private static final String VALUE_NON_SILENT_MODE = "0";

    private static final int MAX_POLLING_TRIES = 5;
    private static final int POLLING_DELAY_MS = 50;

    private final TemporaryFile mFileGpioMonitoring;
    private final TemporaryFile mFileKernelSilentMode;

    @Mock private CarPowerManagementService mCarPowerManagementService;

    public SilentModeHandlerUnitTest() throws Exception {
        mFileGpioMonitoring = new TemporaryFile("GPIO_MONITORING");
        mFileKernelSilentMode = new TemporaryFile("KERNEL_SILENT_MODE");
    }

    @Test
    public void testSilentModeGpioMonitoring() throws Exception {
        SilentModeHandler handler = new SilentModeHandler(mCarPowerManagementService,
                mFileGpioMonitoring.getFile().getPath(), mFileKernelSilentMode.getFile().getPath(),
                BOOT_REASON_NORMAL);
        handler.init();

        writeStringToFile(mFileGpioMonitoring.getFile(), VALUE_SILENT_MODE);

        assertSilentMode(handler, true);

        writeStringToFile(mFileGpioMonitoring.getFile(), VALUE_NON_SILENT_MODE);

        assertSilentMode(handler, false);
    }

    @Test
    public void testRebootForForcedSilentMode() throws Exception {
        SilentModeHandler handler = new SilentModeHandler(mCarPowerManagementService,
                mFileGpioMonitoring.getFile().getPath(), mFileKernelSilentMode.getFile().getPath(),
                BOOT_REASON_FORCED_SILENT);
        handler.init();

        assertWithMessage("Silent mode").that(handler.isSilentMode()).isTrue();

        writeStringToFile(mFileGpioMonitoring.getFile(), VALUE_SILENT_MODE);

        assertWithMessage("Silent mode in forced mode").that(handler.isSilentMode()).isTrue();
        verify(mCarPowerManagementService, never()).notifySilentModeChange(anyBoolean());
    }

    @Test
    public void testRebootForForcedNonSilentMode() throws Exception {
        SilentModeHandler handler = new SilentModeHandler(mCarPowerManagementService,
                mFileGpioMonitoring.getFile().getPath(), mFileKernelSilentMode.getFile().getPath(),
                BOOT_REASON_FORCED_NON_SILENT);
        handler.init();

        assertWithMessage("Silent mode").that(handler.isSilentMode()).isFalse();

        writeStringToFile(mFileGpioMonitoring.getFile(), VALUE_SILENT_MODE);

        assertWithMessage("Silent mode in forced mode").that(handler.isSilentMode()).isFalse();
        verify(mCarPowerManagementService, never()).notifySilentModeChange(anyBoolean());
    }

    @Test
    public void testUpdateKernelSilentMode() throws Exception {
        SilentModeHandler handler = new SilentModeHandler(mCarPowerManagementService,
                mFileGpioMonitoring.getFile().getPath(), mFileKernelSilentMode.getFile().getPath(),
                BOOT_REASON_NORMAL);
        handler.init();

        handler.updateKernelSilentMode(true);

        String contents = readFileAsString(mFileKernelSilentMode.getPath());
        assertWithMessage("Kernel silent mode").that(contents).isEqualTo(VALUE_SILENT_MODE);

        handler.updateKernelSilentMode(false);

        contents = readFileAsString(mFileKernelSilentMode.getPath());
        assertWithMessage("Kernel silent mode").that(contents).isEqualTo(VALUE_NON_SILENT_MODE);
    }

    private String readFileAsString(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path);
        StringBuilder contentBuilder = new StringBuilder();
        for (String line : lines) {
            contentBuilder.append(line).append("\n");
        }
        return contentBuilder.toString().trim();
    }

    private void writeStringToFile(File file, String contents) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(contents);
            writer.flush();
        }
    }

    private static void assertSilentMode(SilentModeHandler handler, boolean expectedMode)
            throws Exception {
        for (int i = 0; i < MAX_POLLING_TRIES; i++) {
            if (handler.isSilentMode() == expectedMode) {
                return;
            }
            SystemClock.sleep(POLLING_DELAY_MS);
        }
        throw new IllegalStateException("The current mode should be "
                + (expectedMode ? "Silent Mode" : "Non Silent Mode"));
    }

}
