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

package com.android.car.telemetry.databroker;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;

import com.android.car.telemetry.TelemetryProto;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;


public class DataBrokerControllerUnitTest {
    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock private DataBroker mMockDataBroker;

    @Captor ArgumentCaptor<TelemetryProto.MetricsConfig> mConfigCaptor;

    @Captor ArgumentCaptor<Integer> mPriorityCaptor;

    @InjectMocks private DataBrokerController mController;

    private static final TelemetryProto.Publisher PUBLISHER =
            TelemetryProto.Publisher.newBuilder()
                                    .setVehicleProperty(
                                        TelemetryProto.VehiclePropertyPublisher
                                            .newBuilder()
                                            .setReadRate(1)
                                            .setVehiclePropertyId(1000))
                                    .build();
    private static final TelemetryProto.Subscriber SUBSCRIBER =
            TelemetryProto.Subscriber.newBuilder()
                                     .setHandler("handler_func")
                                     .setPublisher(PUBLISHER)
                                     .build();
    private static final TelemetryProto.MetricsConfig CONFIG =
            TelemetryProto.MetricsConfig.newBuilder()
                          .setName("config_name")
                          .setVersion(1)
                          .setScript("function init() end")
                          .addSubscribers(SUBSCRIBER)
                          .build();

    @Test
    public void testOnNewConfig_configPassedToDataBroker() {
        mController.onNewMetricsConfig(CONFIG);

        verify(mMockDataBroker).addMetricsConfiguration(mConfigCaptor.capture());
        assertThat(mConfigCaptor.getValue()).isEqualTo(CONFIG);
    }

    @Test
    public void testOnInit_setsOnScriptFinishedCallback() {
        // Checks that mMockDataBroker's setOnScriptFinishedCallback is called after it's injected
        // into controller's constructor with @InjectMocks
        verify(mMockDataBroker).setOnScriptFinishedCallback(
                any(DataBrokerController.ScriptFinishedCallback.class));
    }
}
