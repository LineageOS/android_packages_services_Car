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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.car.AbstractExtendedMockitoCarServiceTestCase;
import android.car.telemetry.CarTelemetryManager;
import android.car.telemetry.ICarTelemetryServiceListener;
import android.content.Context;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.ResultReceiver;

import androidx.test.filters.SmallTest;

import com.android.car.CarLocalServices;
import com.android.car.CarPropertyService;
import com.android.car.CarServiceUtils;
import com.android.car.power.CarPowerManagementService;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.SystemStateInterface;
import com.android.car.telemetry.publisher.PublisherFactory;
import com.android.car.telemetry.systemmonitor.SystemMonitor;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;

@SmallTest
public class CarTelemetryServiceTest extends AbstractExtendedMockitoCarServiceTestCase {
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

    @Mock private ActivityManager mMockActivityManager;
    @Mock private CarPropertyService mMockCarPropertyService;
    @Mock private Context mMockContext;
    @Mock private ICarTelemetryServiceListener mMockListener;
    @Mock private SystemInterface mMockSystemInterface;
    @Mock private SystemStateInterface mMockSystemStateInterface;
    @Mock private CarPowerManagementService mMockCarPowerManagementService;
    @Mock private CarTelemetryService.Dependencies mDependencies;
    @Mock private PublisherFactory mPublisherFactory;
    @Mock private SystemMonitor mMockSystemMonitor;
    @Mock private ResultReceiver mMockAddMetricsConfigCallback;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(SystemMonitor.class);
    }

    @Before
    public void setUp() throws Exception {
        CarLocalServices.removeServiceForTest(SystemInterface.class);
        CarLocalServices.addService(SystemInterface.class, mMockSystemInterface);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class,
                mMockCarPowerManagementService);

        when(mMockContext.getSystemService(ActivityManager.class))
                .thenReturn(mMockActivityManager);

        when(SystemMonitor.create(any(), any())).thenReturn(mMockSystemMonitor);

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
    public void testAddMetricsConfig_newMetricsConfig_shouldSucceed() {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray(),
                mMockAddMetricsConfigCallback);

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockAddMetricsConfigCallback).send(
                eq(CarTelemetryManager.STATUS_METRICS_CONFIG_SUCCESS), isNull());
    }

    @Test
    public void testAddMetricsConfig_duplicateMetricsConfig_shouldFail() {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray(),
                mMockAddMetricsConfigCallback);
        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockAddMetricsConfigCallback).send(
                eq(CarTelemetryManager.STATUS_METRICS_CONFIG_SUCCESS), isNull());

        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray(),
                mMockAddMetricsConfigCallback);

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockAddMetricsConfigCallback).send(
                eq(CarTelemetryManager.STATUS_METRICS_CONFIG_ALREADY_EXISTS), isNull());
    }

    @Test
    public void testAddMetricsConfig_invalidMetricsConfig_shouldFail() {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, "bad config".getBytes(),
                mMockAddMetricsConfigCallback);

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockAddMetricsConfigCallback).send(
                eq(CarTelemetryManager.STATUS_METRICS_CONFIG_PARSE_FAILED), isNull());
    }

    @Test
    public void testAddMetricsConfig_olderMetricsConfig_shouldFail() {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V2.toByteArray(),
                mMockAddMetricsConfigCallback);
        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> {
        });
        verify(mMockAddMetricsConfigCallback).send(
                eq(CarTelemetryManager.STATUS_METRICS_CONFIG_SUCCESS), isNull());

        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray(),
                mMockAddMetricsConfigCallback);

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockAddMetricsConfigCallback).send(
                eq(CarTelemetryManager.STATUS_METRICS_CONFIG_VERSION_TOO_OLD), isNull());
    }

    @Test
    public void testAddMetricsConfig_newerMetricsConfig_shouldReplaceAndDeleteOldResult() {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray(),
                mMockAddMetricsConfigCallback);
        mResultStore.putInterimResult(METRICS_CONFIG_NAME, new PersistableBundle());

        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V2.toByteArray(),
                mMockAddMetricsConfigCallback);

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockAddMetricsConfigCallback, atLeastOnce()).send(
                eq(CarTelemetryManager.STATUS_METRICS_CONFIG_SUCCESS), isNull());
        assertThat(mMetricsConfigStore.getActiveMetricsConfigs())
                .containsExactly(METRICS_CONFIG_V2);
        assertThat(mResultStore.getInterimResult(METRICS_CONFIG_NAME)).isNull();
    }

    @Test
    public void testAddMetricsConfig_invalidName_shouldFail() {
        String wrongName = "wrong name";

        mService.addMetricsConfig(wrongName, METRICS_CONFIG_V1.toByteArray(),
                mMockAddMetricsConfigCallback);

        CarServiceUtils.runOnLooperSync(mTelemetryHandler.getLooper(), () -> { });
        verify(mMockAddMetricsConfigCallback).send(
                eq(CarTelemetryManager.STATUS_METRICS_CONFIG_PARSE_FAILED), isNull());
    }

    @Test
    public void testRemoveMetricsConfig_shouldDeleteConfigAndResult() {
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray(),
                mMockAddMetricsConfigCallback);
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
        mService.addMetricsConfig(testConfigName, config.toByteArray(),
                mMockAddMetricsConfigCallback);
        mService.addMetricsConfig(METRICS_CONFIG_NAME, METRICS_CONFIG_V1.toByteArray(),
                mMockAddMetricsConfigCallback);
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
