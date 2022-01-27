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

package com.android.car.telemetry;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.car.telemetry.CarTelemetryManager;
import android.car.telemetry.ICarTelemetryServiceListener;
import android.content.Context;
import android.os.Handler;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;

import com.android.car.CarLocalServices;
import com.android.car.CarPropertyService;
import com.android.car.CarServiceUtils;
import com.android.car.power.CarPowerManagementService;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.SystemStateInterface;
import com.android.car.telemetry.publisher.PublisherFactory;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;

@RunWith(MockitoJUnitRunner.class)
@SmallTest
public class CarTelemetryServiceTest {
    private static final String METRICS_CONFIG_NAME = "my_metrics_config";
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName(METRICS_CONFIG_NAME).setVersion(1).setScript("no-op").build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_V2 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName(METRICS_CONFIG_NAME).setVersion(2).setScript("no-op").build();

    private CarTelemetryService mService;
    private File mTempSystemCarDir;
    private Handler mTelemetryHandler;
    private MetricsConfigStore mMetricsConfigStore;
    private ResultStore mResultStore;

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    private ActivityManager mMockActivityManager;
    @Mock
    private CarPropertyService mMockCarPropertyService;
    @Mock
    private Context mMockContext;
    @Mock
    private ICarTelemetryServiceListener mMockListener;
    @Mock
    private SystemInterface mMockSystemInterface;
    @Mock
    private SystemStateInterface mMockSystemStateInterface;
    @Mock
    private CarPowerManagementService mMockCarPowerManagementService;
    @Mock
    private CarTelemetryService.Dependencies mDependencies;
    @Mock
    private PublisherFactory mPublisherFactory;

    @Before
    public void setUp() throws Exception {
        CarLocalServices.removeServiceForTest(SystemInterface.class);
        CarLocalServices.addService(SystemInterface.class, mMockSystemInterface);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class,
                mMockCarPowerManagementService);

        // ActivityManager is used by SystemMonitor
        doAnswer(i -> {
            ActivityManager.MemoryInfo mi = i.getArgument(0);
            mi.availMem = 5_000_000L; // memory usage is at 50%
            mi.totalMem = 10_000_000;
            return null;
        }).when(mMockActivityManager).getMemoryInfo(any(ActivityManager.MemoryInfo.class));
        when(mMockContext.getSystemService(ActivityManager.class))
                .thenReturn(mMockActivityManager);

        mTempSystemCarDir = Files.createTempDirectory("telemetry_test").toFile();
        when(mMockSystemInterface.getSystemCarDir()).thenReturn(mTempSystemCarDir);
        when(mMockSystemInterface.getSystemStateInterface()).thenReturn(mMockSystemStateInterface);

        when(mDependencies.getPublisherFactory(any(), any(), any(), any()))
                .thenReturn(mPublisherFactory);

        mService = new CarTelemetryService(mMockContext, mMockCarPropertyService, mDependencies);
        mService.init();
        mService.setListener(mMockListener);

        mTelemetryHandler = mService.getTelemetryHandler();
        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });

        mMetricsConfigStore = mService.getMetricsConfigStore();
        mResultStore = mService.getResultStore();
    }

    @Test
    public void testAddMetricsConfig_newMetricsConfig_shouldSucceed() throws Exception {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray());

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(METRICS_CONFIG_NAME), eq(CarTelemetryManager.STATUS_METRICS_CONFIG_SUCCESS));
    }

    @Test
    public void testAddMetricsConfig_duplicateMetricsConfig_shouldFail() throws Exception {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray());
        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(METRICS_CONFIG_NAME), eq(CarTelemetryManager.STATUS_METRICS_CONFIG_SUCCESS));

        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray());

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockListener).onAddMetricsConfigStatus(eq(METRICS_CONFIG_NAME),
                eq(CarTelemetryManager.STATUS_METRICS_CONFIG_ALREADY_EXISTS));
    }

    @Test
    public void testAddMetricsConfig_invalidMetricsConfig_shouldFail() throws Exception {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, "bad config".getBytes());

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockListener).onAddMetricsConfigStatus(eq(METRICS_CONFIG_NAME),
                eq(CarTelemetryManager.STATUS_METRICS_CONFIG_PARSE_FAILED));
    }

    @Test
    public void testAddMetricsConfig_olderMetricsConfig_shouldFail() throws Exception {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V2.toByteArray());
        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(METRICS_CONFIG_NAME), eq(CarTelemetryManager.STATUS_METRICS_CONFIG_SUCCESS));

        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray());

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockListener).onAddMetricsConfigStatus(eq(METRICS_CONFIG_NAME),
                eq(CarTelemetryManager.STATUS_METRICS_CONFIG_VERSION_TOO_OLD));
    }

    @Test
    public void testAddMetricsConfig_newerMetricsConfig_shouldReplaceAndDeleteOldResult()
            throws Exception {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray());
        mResultStore.putInterimResult(METRICS_CONFIG_NAME, new PersistableBundle());

        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V2.toByteArray());

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockListener, atLeastOnce()).onAddMetricsConfigStatus(
                eq(METRICS_CONFIG_NAME), eq(CarTelemetryManager.STATUS_METRICS_CONFIG_SUCCESS));
        assertThat(mMetricsConfigStore.getActiveMetricsConfigs())
                .containsExactly(METRICS_CONFIG_V2);
        assertThat(mResultStore.getInterimResult(METRICS_CONFIG_NAME)).isNull();
    }

    @Test
    public void testAddMetricsConfig_invalidName_shouldFail() throws Exception {
        String wrongName = "wrong name";

        mService.addMetricsConfig(wrongName, METRICS_CONFIG_V1.toByteArray());

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(wrongName), eq(CarTelemetryManager.STATUS_METRICS_CONFIG_PARSE_FAILED));
    }

    @Test
    public void testRemoveMetricsConfig_shouldDeleteConfigAndResult() {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray());
        mResultStore.putInterimResult(METRICS_CONFIG_NAME, new PersistableBundle());

        mService.removeMetricsConfig(METRICS_CONFIG_NAME);

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        assertThat(mMetricsConfigStore.getActiveMetricsConfigs()).isEmpty();
        assertThat(mResultStore.getInterimResult(METRICS_CONFIG_NAME)).isNull();
    }

    @Test
    public void testRemoveAllMetricsConfigs_shouldRemoveConfigsAndResults() {
        String testConfigName = "test config";
        TelemetryProto.MetricsConfig config =
                TelemetryProto.MetricsConfig.newBuilder().setName(testConfigName).build();
        mService.addMetricsConfig(testConfigName, config.toByteArray());
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray());
        mResultStore.putInterimResult(METRICS_CONFIG_NAME, new PersistableBundle());
        mResultStore.putFinalResult(testConfigName, new PersistableBundle());

        mService.removeAllMetricsConfigs();

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        assertThat(mMetricsConfigStore.getActiveMetricsConfigs()).isEmpty();
        assertThat(mResultStore.getInterimResult(METRICS_CONFIG_NAME)).isNull();
        assertThat(mResultStore.getFinalResult(testConfigName, /* deleteResult = */ false))
                .isNull();
    }

    @Test
    public void testSendFinishedReports_whenNoReport_shouldNotReceiveResponse() throws Exception {
        mService.sendFinishedReports(METRICS_CONFIG_NAME);

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockListener, never()).onResult(any(), any());
        verify(mMockListener, never()).onError(any(), any());
    }

    @Test
    public void testSendFinishedReports_whenFinalResult_shouldReceiveResult() throws Exception {
        PersistableBundle finalResult = new PersistableBundle();
        finalResult.putBoolean("finished", true);
        mResultStore.putFinalResult(METRICS_CONFIG_NAME, finalResult);

        mService.sendFinishedReports(METRICS_CONFIG_NAME);

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        finalResult.writeToStream(bos);
        verify(mMockListener).onResult(eq(METRICS_CONFIG_NAME), eq(bos.toByteArray()));
        // result should have been deleted
        assertThat(mResultStore.getFinalResult(METRICS_CONFIG_NAME, false)).isNull();
    }

    @Test
    public void testSendFinishedReports_whenError_shouldReceiveError() throws Exception {
        TelemetryProto.TelemetryError error = TelemetryProto.TelemetryError.newBuilder()
                .setErrorType(TelemetryProto.TelemetryError.ErrorType.LUA_RUNTIME_ERROR)
                .setMessage("test error")
                .build();
        mResultStore.putErrorResult(METRICS_CONFIG_NAME, error);

        mService.sendFinishedReports(METRICS_CONFIG_NAME);

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockListener).onError(eq(METRICS_CONFIG_NAME), eq(error.toByteArray()));
        // error should have been deleted
        assertThat(mResultStore.getErrorResult(METRICS_CONFIG_NAME, false)).isNull();
    }

    @Test
    public void testSendFinishedReports_whenListenerNotSet_shouldDoNothing() {
        PersistableBundle finalResult = new PersistableBundle();
        finalResult.putBoolean("finished", true);
        mResultStore.putFinalResult(METRICS_CONFIG_NAME, finalResult);
        mService.clearListener(); // no listener = no way to send back results

        mService.sendFinishedReports(METRICS_CONFIG_NAME);

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        // if listener is null, nothing should be done, result should still be in result store
        assertThat(mResultStore.getFinalResult(METRICS_CONFIG_NAME, false).toString())
                .isEqualTo(finalResult.toString());
    }
}
