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

package com.android.car.telemetry.systemmonitor;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.content.Context;
import android.os.Handler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

@RunWith(MockitoJUnitRunner.class)
public class SystemMonitorUnitTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final String TEST_LOADAVG = "1.2 3.4 2.2 123/1452 21348";
    private static final String TEST_LOADAVG_BAD_FORMAT = "1.2 3.4";
    private static final String TEST_LOADAVG_NOT_FLOAT = "1.2 abc 2.1 12/231 2";
    private static final long TEST_AVAILMEM = 3_000_000_000L;
    private static final long TEST_TOTALMEM = 8_000_000_000L;

    @Mock private Context mMockContext;
    @Mock private Handler mMockHandler; // it promptly executes the runnable in the same thread
    @Mock private ActivityManager mMockActivityManager;
    @Mock private SystemMonitor.SystemMonitorCallback mMockCallback;

    @Captor ArgumentCaptor<Runnable> mRunnableCaptor;
    @Captor ArgumentCaptor<SystemMonitorEvent> mEventCaptor;

    @Before
    public void setup() {
        when(mMockContext.getSystemService(anyString())).thenReturn(mMockActivityManager);
        when(mMockHandler.post(any(Runnable.class))).thenAnswer(i -> {
            Runnable runnable = i.getArgument(0);
            runnable.run();
            return true;
        });
        doAnswer(i -> {
            MemoryInfo mi = i.getArgument(0);
            mi.availMem = TEST_AVAILMEM;
            mi.totalMem = TEST_TOTALMEM;
            return null;
        }).when(mMockActivityManager).getMemoryInfo(any(MemoryInfo.class));
    }

    @Test
    public void testSetEventCpuUsageLevel_setsCorrectUsageLevelForHighUsage() {
        SystemMonitor systemMonitor = SystemMonitor.create(mMockContext, mMockHandler);
        SystemMonitorEvent event = new SystemMonitorEvent();

        systemMonitor.setEventCpuUsageLevel(event, /* cpuLoadPerCore= */ 1.5);

        assertThat(event.getCpuUsageLevel())
            .isEqualTo(SystemMonitorEvent.USAGE_LEVEL_HI);
    }

    @Test
    public void testSetEventCpuUsageLevel_setsCorrectUsageLevelForMedUsage() {
        SystemMonitor systemMonitor = SystemMonitor.create(mMockContext, mMockHandler);
        SystemMonitorEvent event = new SystemMonitorEvent();

        systemMonitor.setEventCpuUsageLevel(event, /* cpuLoadPerCore= */ 0.6);

        assertThat(event.getCpuUsageLevel())
            .isEqualTo(SystemMonitorEvent.USAGE_LEVEL_MED);
    }

    @Test
    public void testSetEventCpuUsageLevel_setsCorrectUsageLevelForLowUsage() {
        SystemMonitor systemMonitor = SystemMonitor.create(mMockContext, mMockHandler);
        SystemMonitorEvent event = new SystemMonitorEvent();

        systemMonitor.setEventCpuUsageLevel(event, /* cpuLoadPerCore= */ 0.5);

        assertThat(event.getCpuUsageLevel())
            .isEqualTo(SystemMonitorEvent.USAGE_LEVEL_LOW);
    }

    @Test
    public void testSetEventMemUsageLevel_setsCorrectUsageLevelForHighUsage() {
        SystemMonitor systemMonitor = SystemMonitor.create(mMockContext, mMockHandler);
        SystemMonitorEvent event = new SystemMonitorEvent();

        systemMonitor.setEventMemUsageLevel(event, /* memLoadRatio= */ 0.98);

        assertThat(event.getMemoryUsageLevel())
            .isEqualTo(SystemMonitorEvent.USAGE_LEVEL_HI);
    }

    @Test
    public void testSetEventMemUsageLevel_setsCorrectUsageLevelForMedUsage() {
        SystemMonitor systemMonitor = SystemMonitor.create(mMockContext, mMockHandler);
        SystemMonitorEvent event = new SystemMonitorEvent();

        systemMonitor.setEventMemUsageLevel(event, /* memLoadRatio= */ 0.85);

        assertThat(event.getMemoryUsageLevel())
            .isEqualTo(SystemMonitorEvent.USAGE_LEVEL_MED);
    }

    @Test
    public void testSetEventMemUsageLevel_setsCorrectUsageLevelForLowUsage() {
        SystemMonitor systemMonitor = SystemMonitor.create(mMockContext, mMockHandler);
        SystemMonitorEvent event = new SystemMonitorEvent();

        systemMonitor.setEventMemUsageLevel(event, /* memLoadRatio= */ 0.80);

        assertThat(event.getMemoryUsageLevel())
            .isEqualTo(SystemMonitorEvent.USAGE_LEVEL_LOW);
    }

    @Test
    public void testAfterSetCallback_callbackCalled() throws IOException {
        SystemMonitor systemMonitor = new SystemMonitor(
                mMockContext, mMockHandler, writeTempFile(TEST_LOADAVG));

        systemMonitor.setSystemMonitorCallback(mMockCallback);

        verify(mMockCallback, atLeastOnce()).onSystemMonitorEvent(mEventCaptor.capture());
        SystemMonitorEvent event = mEventCaptor.getValue();
        assertThat(event.getCpuUsageLevel()).isAnyOf(
                SystemMonitorEvent.USAGE_LEVEL_LOW,
                SystemMonitorEvent.USAGE_LEVEL_MED,
                SystemMonitorEvent.USAGE_LEVEL_HI);
        assertThat(event.getMemoryUsageLevel()).isAnyOf(
                SystemMonitorEvent.USAGE_LEVEL_LOW,
                SystemMonitorEvent.USAGE_LEVEL_MED,
                SystemMonitorEvent.USAGE_LEVEL_HI);
    }

    @Test
    public void testWhenLoadavgIsBadFormat_getCpuLoadReturnsNull() throws IOException {
        SystemMonitor systemMonitor = new SystemMonitor(
                mMockContext, mMockHandler, writeTempFile(TEST_LOADAVG_BAD_FORMAT));

        assertThat(systemMonitor.getCpuLoad()).isNull();
    }

    @Test
    public void testWhenLoadavgIsNotFloatParsable_getCpuLoadReturnsNull() throws IOException {
        SystemMonitor systemMonitor = new SystemMonitor(
                mMockContext, mMockHandler, writeTempFile(TEST_LOADAVG_NOT_FLOAT));

        assertThat(systemMonitor.getCpuLoad()).isNull();
    }

    @Test
    public void testWhenUnsetCallback_sameCallbackFromSetCallbackIsRemoved() throws IOException {
        SystemMonitor systemMonitor = new SystemMonitor(
                mMockContext, mMockHandler, writeTempFile(TEST_LOADAVG));

        systemMonitor.setSystemMonitorCallback(mMockCallback);
        systemMonitor.unsetSystemMonitorCallback();

        verify(mMockHandler, times(1)).post(mRunnableCaptor.capture());
        Runnable setRunnable = mRunnableCaptor.getValue();
        verify(mMockHandler, times(1)).removeCallbacks(mRunnableCaptor.capture());
        Runnable unsetRunnalbe = mRunnableCaptor.getValue();
        assertThat(setRunnable).isEqualTo(unsetRunnalbe);
    }

    /**
     * Creates and writes to the temp file, returns its path.
     */
    private String writeTempFile(String content) throws IOException {
        File tempFile = temporaryFolder.newFile();
        try (FileWriter fw = new FileWriter(tempFile)) {
            fw.write(content);
        }
        return tempFile.getAbsolutePath();
    }
}
