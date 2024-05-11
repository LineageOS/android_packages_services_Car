/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.mockito.Mockito.mock;

import android.car.media.CarVolumeGroupEvent;
import android.car.test.AbstractExpectableTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class CarVolumeEventHandlerTest extends AbstractExpectableTestCase {
    private static final long TEST_TIMEOUT_MS = 100;
    private static final int TEST_ZONE_ID = 1;
    private static final int TEST_FLAG = 0;
    private static final int TEST_UID = 10103;
    private static final CarVolumeEventHandler EVENT_HANDLER = new CarVolumeEventHandler();

    private final TestCarVolumeEventCallback mCarVolumeEventCallback =
            new TestCarVolumeEventCallback(TEST_TIMEOUT_MS);

    @Test
    public void registerCarVolumeEventCallback() {
        EVENT_HANDLER.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        expectWithMessage("registered UID")
                .that(EVENT_HANDLER.checkIfUidIsRegistered(TEST_UID)).isTrue();
    }

    @Test
    public void unregisterCarVolumeEventCallback() {
        EVENT_HANDLER.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        EVENT_HANDLER.unregisterCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        expectWithMessage("unregistered UID")
                .that(EVENT_HANDLER.checkIfUidIsRegistered(TEST_UID)).isFalse();
    }

    @Test
    public void onMasterMuteChanged() throws Exception {
        EVENT_HANDLER.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        EVENT_HANDLER.onMasterMuteChanged(TEST_ZONE_ID, TEST_FLAG);

        expectWithMessage("Invocation of callback for master mute change").that(
                mCarVolumeEventCallback.waitForCallback()).isTrue();
    }

    @Test
    public void onVolumeGroupEvent() throws Exception {
        CarVolumeGroupEvent eventMock = mock(CarVolumeGroupEvent.class);
        EVENT_HANDLER.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        EVENT_HANDLER.onVolumeGroupEvent(List.of(eventMock));

        expectWithMessage("Invocation of callback for volume group event change").that(
                mCarVolumeEventCallback.waitForCallback()).isTrue();
    }

    @Test
    public void release() {
        EVENT_HANDLER.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        EVENT_HANDLER.release();

        expectWithMessage("Released UID")
                .that(EVENT_HANDLER.checkIfUidIsRegistered(TEST_UID)).isFalse();
    }

    @Test
    public void onCallbackDied() {
        EVENT_HANDLER.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        EVENT_HANDLER.onCallbackDied(mCarVolumeEventCallback, TEST_UID);

        expectWithMessage("UID with dead callback")
                .that(EVENT_HANDLER.checkIfUidIsRegistered(TEST_UID)).isFalse();
    }
}
