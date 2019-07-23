/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.trust;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.RequiresDevice;
import androidx.test.runner.AndroidJUnit4;

import com.android.car.BLEStreamProtos.BLEOperationProto.OperationType;
import com.android.car.R;
import com.android.car.trust.CarTrustAgentBleManager.SendMessageCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;


/**
 * Unit test for {@link CarTrustAgentBleManager}.
 */
@RunWith(AndroidJUnit4.class)
public class CarTrustAgentBleManagerTest {

    private static final String ADDRESS = "00:11:22:33:AA:BB";
    private static final byte[] TEST_DATA = "testtest".getBytes();
    private static final int TEST_SINGLE_MESSAGE_SIZE =
            TEST_DATA.length + BLEMessageV1Factory.getProtoHeaderSize(
                    OperationType.CLIENT_MESSAGE, TEST_DATA.length, true);

    private CarTrustAgentBleManager mCarTrustAgentBleManager;
    private BluetoothDevice mBluetoothDevice;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mCarTrustAgentBleManager = new CarTrustAgentBleManager(mContext);
        mCarTrustAgentBleManager.setupEnrollmentBleServer();
        mCarTrustAgentBleManager.setBleMessageRetryLimit(3);
        mBluetoothDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(ADDRESS);
    }

    @Test
    @RequiresDevice
    public void testRetrySendingMessage_noACK_reachRetryLimit() throws InterruptedException {
        // Make sure the length of the message queue is greater than 1.
        mCarTrustAgentBleManager.onMtuSizeChanged(TEST_SINGLE_MESSAGE_SIZE - 1);
        mCarTrustAgentBleManager.sendEnrollmentMessage(mBluetoothDevice, TEST_DATA,
                OperationType.CLIENT_MESSAGE, true, any(SendMessageCallback.class));
        for (int i = 0; i < mCarTrustAgentBleManager.mBleMessageRetryLimit; i++) {
            Thread.sleep(mCarTrustAgentBleManager.BLE_MESSAGE_RETRY_DELAY_MS);
            assertThat(mCarTrustAgentBleManager.mBleMessageRetryStartCount).isEqualTo(i + 1);
        }
    }

    @Test
    @RequiresDevice
    public void testRetrySendingMessage_receivedACK_stopRetry() throws InterruptedException {
        mCarTrustAgentBleManager.onMtuSizeChanged(TEST_SINGLE_MESSAGE_SIZE - 1);
        mCarTrustAgentBleManager.sendEnrollmentMessage(mBluetoothDevice, TEST_DATA,
                OperationType.CLIENT_MESSAGE, true, any(SendMessageCallback.class));
        Thread.sleep(mCarTrustAgentBleManager.BLE_MESSAGE_RETRY_DELAY_MS);
        // Retried once.
        assertThat(mCarTrustAgentBleManager.mBleMessageRetryStartCount).isEqualTo(1);

        // Receive acknowledge back.
        mCarTrustAgentBleManager.handleClientAckMessage(mBluetoothDevice,
                UUID.fromString(mContext.getString(R.string.enrollment_client_write_uuid)));
        assertThat(mCarTrustAgentBleManager.mBleMessageRetryStartCount).isEqualTo(0);
    }
}
