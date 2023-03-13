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

import static android.car.media.CarAudioManager.INVALID_REQUEST_ID;
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
    private static final int TEST_ZONE_3 = PRIMARY_AUDIO_ZONE + 3;
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
    public void getUniqueRequestId() {
        long requestId = mCarAudioMirrorRequestHandler.getUniqueRequestId();

        expectWithMessage("Unique request id").that(requestId).isEqualTo(0);
    }
    @Test
    public void getUniqueRequestId_multipleTimes() {
        long requestIdOne = mCarAudioMirrorRequestHandler.getUniqueRequestId();

        long requestIdTwo = mCarAudioMirrorRequestHandler.getUniqueRequestId();

        expectWithMessage("Unique request id one").that(requestIdOne).isEqualTo(0);
        expectWithMessage("Unique request id two").that(requestIdTwo).isEqualTo(1);
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
        long requestId = mCarAudioMirrorRequestHandler.getUniqueRequestId();

        mCarAudioMirrorRequestHandler.enableMirrorForZones(requestId, TEST_ZONE_IDS);

        expectWithMessage("Zone configs for request id %s", requestId)
                .that(mCarAudioMirrorRequestHandler.getMirrorAudioZonesForRequest(requestId))
                .asList().containsExactly(TEST_ZONE_1, TEST_ZONE_2);
        for (int index = 0; index < TEST_ZONE_IDS.length; index++) {
            int zoneId = TEST_ZONE_IDS[index];
            expectWithMessage("Enabled mirror status for audio zone %s", zoneId)
                    .that(mCarAudioMirrorRequestHandler.isMirrorEnabledForZone(zoneId)).isTrue();
        }
    }

    @Test
    public void enableMirrorForZones_withNullZoneIds_fails() {
        long requestId = mCarAudioMirrorRequestHandler.getUniqueRequestId();
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            mCarAudioMirrorRequestHandler.enableMirrorForZones(/* audioZones= */ requestId, null);
        });

        expectWithMessage("Null audio zones enabled exception")
                .that(thrown).hasMessageThat().contains("Mirror audio zones");
    }

    @Test
    public void enableMirrorForZones_withInvalidRequestId() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mCarAudioMirrorRequestHandler.enableMirrorForZones(INVALID_REQUEST_ID, TEST_ZONE_IDS);
        });

        expectWithMessage("Enable mirror for zones with invalid request id exception")
                .that(thrown).hasMessageThat().contains("INVALID_REQUEST_ID");
    }

    @Test
    public void enableMirrorForZones_afterRegisteringCallback() throws Exception {
        long requestId = mCarAudioMirrorRequestHandler.getUniqueRequestId();
        mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(mTestCallback);

        mCarAudioMirrorRequestHandler.enableMirrorForZones(requestId, TEST_ZONE_IDS);

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
                mCarAudioMirrorRequestHandler.getMirrorAudioZonesForRequest(TEST_ZONE_1))
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
    public void updateMirrorConfigurationForZones() throws Exception {
        long requestId = mCarAudioMirrorRequestHandler.getUniqueRequestId();
        mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(mTestCallback);
        mCarAudioMirrorRequestHandler.enableMirrorForZones(requestId, TEST_ZONE_IDS);
        mTestCallback.waitForCallback();
        mTestCallback.reset();

        mCarAudioMirrorRequestHandler.updateRemoveMirrorConfigurationForZones(
                /* newConfig= */ requestId, new int[0]);

        mTestCallback.waitForCallback();
        expectWithMessage("Audio mirror status after removed").that(mTestCallback.mStatus)
                .isEqualTo(CarAudioManager.AUDIO_REQUEST_STATUS_STOPPED);
        expectWithMessage("Audio mirror disabled zones").that(mTestCallback.mZoneIds)
                .asList().containsExactly(TEST_ZONE_1, TEST_ZONE_2);
    }

    @Test
    public void updateMirrorConfigurationForZones_forSingleZone_inThreeZoneConfig()
            throws Exception {
        int[] threeZoneConfig = new int[] {TEST_ZONE_1, TEST_ZONE_2, TEST_ZONE_3};
        mCarAudioMirrorRequestHandler.registerAudioZonesMirrorStatusCallback(mTestCallback);
        long requestId = mCarAudioMirrorRequestHandler.getUniqueRequestId();
        mCarAudioMirrorRequestHandler.enableMirrorForZones(requestId, threeZoneConfig);
        mTestCallback.waitForCallback();
        mTestCallback.reset();

        mCarAudioMirrorRequestHandler.updateRemoveMirrorConfigurationForZones(requestId,
                TEST_ZONE_IDS
        );

        mTestCallback.waitForCallback();
        expectWithMessage("Audio mirror status after removed one zone in three zone config")
                .that(mTestCallback.mStatus).isEqualTo(
                        CarAudioManager.AUDIO_REQUEST_STATUS_STOPPED);
        expectWithMessage("Audio mirror disabled a single zone in three zone config")
                .that(mTestCallback.mZoneIds).asList().containsExactly(TEST_ZONE_3);
    }

    @Test
    public void calculateAudioConfigurationAfterRemovingZonesFromRequestId() {
        int[] threeZoneConfig = new int[] {TEST_ZONE_1, TEST_ZONE_2, TEST_ZONE_3};
        long requestId = mCarAudioMirrorRequestHandler.getUniqueRequestId();
        mCarAudioMirrorRequestHandler.enableMirrorForZones(requestId, threeZoneConfig);

        int[] newConfig = mCarAudioMirrorRequestHandler
                .calculateAudioConfigurationAfterRemovingZonesFromRequestId(requestId, TEST_ZONE_IDS
                );

        expectWithMessage("New audio zone configuration after removing two zones")
                .that(newConfig).asList().containsExactly(TEST_ZONE_3);
    }

    @Test
    public void calculateAudioConfigurationAfterRemovingZonesFromRequestId_withNotYetSetConfig() {
        long requestId = mCarAudioMirrorRequestHandler.getUniqueRequestId();
        int[] newConfig = mCarAudioMirrorRequestHandler
                .calculateAudioConfigurationAfterRemovingZonesFromRequestId(requestId, TEST_ZONE_IDS
                );

        expectWithMessage("New audio zone configuration for not yet set configuration")
                .that(newConfig).isNull();
    }

    @Test
    public void verifyValidRequestId_withNoLongerValidRequest() {
        long requestId = mCarAudioMirrorRequestHandler.getUniqueRequestId();
        mCarAudioMirrorRequestHandler.enableMirrorForZones(requestId, TEST_ZONE_IDS);
        mCarAudioMirrorRequestHandler.updateRemoveMirrorConfigurationForZones(
                /* newConfig= */ requestId, new int[0]);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mCarAudioMirrorRequestHandler.verifyValidRequestId(requestId);
        });

        expectWithMessage("No longer valid request id verification exception")
                .that(thrown).hasMessageThat().contains("is not valid");
    }

    @Test
    public void verifyValidRequestId_withInvalidRequestId() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mCarAudioMirrorRequestHandler.verifyValidRequestId(INVALID_REQUEST_ID);
        });

        expectWithMessage("Invalid request id verification exception")
                .that(thrown).hasMessageThat().contains("is not valid");
    }

    @Test
    public void getRequestIdForAudioZone() {
        long requestId = mCarAudioMirrorRequestHandler.getUniqueRequestId();
        mCarAudioMirrorRequestHandler.enableMirrorForZones(requestId, TEST_ZONE_IDS);

        expectWithMessage("Request id for audio zone")
                .that(mCarAudioMirrorRequestHandler.getRequestIdForAudioZone(TEST_ZONE_1))
                .isEqualTo(requestId);
    }

    @Test
    public void getRequestIdForAudioZone_forNotYetSetZones() {
        expectWithMessage("Request id for not yet set audio zone")
                .that(mCarAudioMirrorRequestHandler.getRequestIdForAudioZone(TEST_ZONE_1))
                .isEqualTo(INVALID_REQUEST_ID);
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

        public void reset() {
            mZoneIds = null;
            mStatus = 0;
            mStatusLatch = new CountDownLatch(1);
        }
    }
}
