/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;

import static org.junit.Assert.assertThrows;

import android.car.media.CarAudioManager;
import android.car.media.IAudioZonesMirrorStatusCallback;
import android.car.test.AbstractExpectableTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(MockitoJUnitRunner.class)
public final class CarAudioMirrorRequestHandlerTest extends AbstractExpectableTestCase {

    private static final String TEST_MIRROR_DEVICE_ADDRESS = "bus_666_mirror_device";
    private static final int TEST_ZONE_1 = PRIMARY_AUDIO_ZONE + 1;
    private static final int TEST_ZONE_2 = PRIMARY_AUDIO_ZONE + 2;
    public static final int[] TEST_ZONE_IDS = new int[]{TEST_ZONE_1, TEST_ZONE_2};
    private CarAudioMirrorRequestHandler mCarAudioMirrorRequestHandler;
    private TestAudioZonesMirrorStatusCallbackCallback mTestCallback;

    @Before
    public void setUp() {
        mCarAudioMirrorRequestHandler = new CarAudioMirrorRequestHandler();
        mCarAudioMirrorRequestHandler.setMirrorDeviceAddress(TEST_MIRROR_DEVICE_ADDRESS);
        mTestCallback = new TestAudioZonesMirrorStatusCallbackCallback();
    }

    @Test
    public void registerAudioZonesMirrorStatusCallback_withNullCallback_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(
                    /* callback= */ null);
        });

        expectWithMessage("Null audio zones mirror status callback register exception")
                .that(thrown).hasMessageThat().contains("Audio zones mirror status");
    }

    @Test
    public void unregisterAudioZonesMirrorStatusCallback_withNullCallback_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            mCarAudioMirrorRequestHandler.unregisterAudioZonesMirrorStatusCallback(
                    /* callback= */ null);
        });

        expectWithMessage("Null audio zones mirror status callback unregister exception")
                .that(thrown).hasMessageThat().contains("Audio zones mirror status");
    }

    @Test
    public void registerAudioZonesMirrorStatusCallback() {
        boolean registered = mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(
                mTestCallback);

        expectWithMessage("Register status")
                .that(registered).isTrue();
    }

    @Test
    public void registerAudioZonesMirrorStatusCallback_withNoMirroringDeviceAddress() {
        CarAudioMirrorRequestHandler carAudioMirrorRequestHandler =
                new CarAudioMirrorRequestHandler();

        boolean registered = carAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(
                mTestCallback);

        expectWithMessage("Register status with mirroring disabled")
                .that(registered).isFalse();
    }

    @Test
    public void registerAudioZonesMirrorStatusCallback_reRegisterFails() {
        mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(
                mTestCallback);

        boolean registered = mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(
                mTestCallback);

        expectWithMessage("Re-register status")
                .that(registered).isTrue();
    }

    @Test
    public void unregisterAudioZonesMirrorStatusCallback() {
        mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(
                mTestCallback);

        boolean unregister = mCarAudioMirrorRequestHandler
                .unregisterAudioZonesMirrorStatusCallback(mTestCallback);

        expectWithMessage("Unregister status")
                .that(unregister).isTrue();
    }

    @Test
    public void unregisterAudioZonesMirrorStatusCallback_withoutRegister() {
        boolean unregister = mCarAudioMirrorRequestHandler
                .unregisterAudioZonesMirrorStatusCallback(mTestCallback);

        expectWithMessage("Unregister status for non register callback")
                .that(unregister).isFalse();
    }

    @Test
    public void unregisterAudioZonesMirrorStatusCallback_afterUnregister() {
        mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(
                mTestCallback);
        mCarAudioMirrorRequestHandler.unregisterAudioZonesMirrorStatusCallback(
                mTestCallback);

        boolean unregister = mCarAudioMirrorRequestHandler
                .unregisterAudioZonesMirrorStatusCallback(mTestCallback);

        expectWithMessage("Unregister status for already unregistered callback")
                .that(unregister).isFalse();
    }

    @Test
    public void setMirrorDeviceAddress_withNullMirrorDeviceAddress() {
        CarAudioMirrorRequestHandler carAudioMirrorRequestHandler =
                new CarAudioMirrorRequestHandler();

        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            carAudioMirrorRequestHandler.setMirrorDeviceAddress(/* deviceAddress= */ null);
        });

        expectWithMessage("Null mirror device address exception")
                .that(thrown).hasMessageThat().contains("Mirror device address");
    }

    @Test
    public void setMirrorDeviceAddress() {
        CarAudioMirrorRequestHandler carAudioMirrorRequestHandler =
                new CarAudioMirrorRequestHandler();

        carAudioMirrorRequestHandler.setMirrorDeviceAddress(TEST_MIRROR_DEVICE_ADDRESS);

        expectWithMessage("Audio mirror enabled status")
                .that(carAudioMirrorRequestHandler.isMirrorAudioEnabled()).isTrue();
    }

    @Test
    public void isMirrorAudioEnabled_withoutMirrorDeviceAvailable() {
        CarAudioMirrorRequestHandler carAudioMirrorRequestHandler =
                new CarAudioMirrorRequestHandler();

        expectWithMessage("Audio mirror enabled status, with no mirror device address")
                .that(carAudioMirrorRequestHandler.isMirrorAudioEnabled()).isFalse();
    }

    @Test
    public void enableMirrorForZones() {
        mCarAudioMirrorRequestHandler.enableMirrorForZones(TEST_ZONE_IDS);

        for (int index = 0; index < TEST_ZONE_IDS.length; index++) {
            int zoneId = TEST_ZONE_IDS[index];
            expectWithMessage("Enabled mirror status for audio zone %s", zoneId)
                    .that(mCarAudioMirrorRequestHandler.isMirrorEnabledForZone(zoneId)).isTrue();
            expectWithMessage("Zone configs for audio zone %s", zoneId)
                    .that(mCarAudioMirrorRequestHandler.getMirrorAudioZonesForAudioZone(zoneId))
                    .asList().containsExactly(TEST_ZONE_1, TEST_ZONE_2);
        }
    }

    @Test
    public void enableMirrorForZones_withNullZoneIds_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            mCarAudioMirrorRequestHandler.enableMirrorForZones(/* audioZones= */ null);
        });

        expectWithMessage("Null audio zones enabled exception")
                .that(thrown).hasMessageThat().contains("Mirror audio zones");
    }

    @Test
    public void enableMirrorForZones_afterRegisteringCallback() throws Exception {
        mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(mTestCallback);

        mCarAudioMirrorRequestHandler.enableMirrorForZones(TEST_ZONE_IDS);

        mTestCallback.waitForCallback();
        expectWithMessage("Audio mirror status after enabled").that(mTestCallback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED);
        expectWithMessage("Audio mirror enables zones").that(mTestCallback.mZoneIds)
                .asList().containsExactly(TEST_ZONE_1, TEST_ZONE_2);
    }

    @Test
    public void isMirrorEnabledForZone_withNoAudioMirrorEnabled() {
        expectWithMessage("Enabled mirror status for audio zone for not yet enabled zone")
                .that(mCarAudioMirrorRequestHandler.isMirrorEnabledForZone(TEST_ZONE_1)).isFalse();
    }

    @Test
    public void getMirrorAudioZonesForAudioZone_withNoAudioMirrorEnabled() {
        expectWithMessage("Zone configs for not yet enabled zone").that(
                mCarAudioMirrorRequestHandler.getMirrorAudioZonesForAudioZone(TEST_ZONE_1))
                .isNull();
    }

    @Test
    public void rejectMirrorForZones() throws Exception {
        mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(mTestCallback);

        mCarAudioMirrorRequestHandler.rejectMirrorForZones(TEST_ZONE_IDS);

        mTestCallback.waitForCallback();
        expectWithMessage("Audio mirror rejected status").that(mTestCallback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_REJECTED);
        expectWithMessage("Audio mirror rejected zones").that(mTestCallback.mZoneIds)
                .asList().containsExactly(TEST_ZONE_1, TEST_ZONE_2);
    }

    @Test
    public void cancelMirrorForZones() throws Exception {
        mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(mTestCallback);

        mCarAudioMirrorRequestHandler.cancelMirrorForZones(TEST_ZONE_IDS);

        mTestCallback.waitForCallback();
        expectWithMessage("Audio mirror canceled status").that(mTestCallback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_CANCELLED);
        expectWithMessage("Audio mirror canceled zones").that(mTestCallback.mZoneIds)
                .asList().containsExactly(TEST_ZONE_1, TEST_ZONE_2);
    }

    private static final class TestAudioZonesMirrorStatusCallbackCallback extends
            IAudioZonesMirrorStatusCallback.Stub {

        private static final long TEST_CALLBACK_TIMEOUT_MS = 100;

        private int[] mZoneIds;
        private int mStatus;
        private CountDownLatch mStatusLatch = new CountDownLatch(1);

        @Override
        public void onAudioZonesMirrorStatusChanged(int[] zoneIds, int status) {
            mZoneIds = zoneIds;
            mStatus = status;
            mStatusLatch.countDown();
        }

        private void waitForCallback() throws Exception {
            mStatusLatch.await(TEST_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }
}
