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

package com.android.car.telemetry.publisher;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.automotive.telemetry.internal.ICarDataListener;
import android.automotive.telemetry.internal.ICarTelemetryInternal;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.os.IBinder;
import android.os.ServiceManager;

import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.databroker.DataSubscriber;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class CarTelemetrydPublisherTest extends AbstractExtendedMockitoTestCase {
    private static final String SERVICE_NAME = ICarTelemetryInternal.DESCRIPTOR + "/default";
    private static final int CAR_DATA_ID_1 = 100;
    private static final TelemetryProto.Publisher PUBLISHER_PARAMS_1 =
            TelemetryProto.Publisher.newBuilder()
                    .setCartelemetryd(TelemetryProto.CarTelemetrydPublisher.newBuilder()
                            .setId(CAR_DATA_ID_1))
                    .build();

    @Mock private IBinder mMockBinder;
    @Mock private ICarTelemetryInternal mMockCarTelemetryInternal;
    @Mock private DataSubscriber mMockDataSubscriber;

    private final CarTelemetrydPublisher mPublisher = new CarTelemetrydPublisher();

    // ICarTelemetryInternal side of the listener.
    @Captor private ArgumentCaptor<ICarDataListener> mCarDataListenerCaptor;

    @Before
    public void setUp() throws Exception {
        when(mMockDataSubscriber.getPublisherParam()).thenReturn(PUBLISHER_PARAMS_1);
        doNothing().when(mMockCarTelemetryInternal).setListener(mCarDataListenerCaptor.capture());
    }

    private void mockICarTelemetryInternalBinder() {
        when(mMockBinder.queryLocalInterface(any())).thenReturn(mMockCarTelemetryInternal);
        when(mMockCarTelemetryInternal.asBinder()).thenReturn(mMockBinder);
        doReturn(mMockBinder).when(() -> ServiceManager.checkService(SERVICE_NAME));
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder.spyStatic(ServiceManager.class);
    }

    @Test
    public void testAddDataSubscriber_registersNewListener() throws Exception {
        mockICarTelemetryInternalBinder();

        mPublisher.addDataSubscriber(mMockDataSubscriber);

        assertThat(mCarDataListenerCaptor.getAllValues()).hasSize(1);
        assertThat(mPublisher.isConnectedToCarTelemetryd()).isTrue();
        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isTrue();
    }

    // TODO(b/189142577): add test cases when connecting to cartelemetryd fails


}
