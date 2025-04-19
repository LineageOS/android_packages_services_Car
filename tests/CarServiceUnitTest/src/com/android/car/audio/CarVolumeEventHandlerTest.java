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
import android.car.test.NoActiveHandlerThreadCheckerRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
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

    private final TestCarVolumeEventCallback mCarVolumeEventCallback =
            new TestCarVolumeEventCallback(TEST_TIMEOUT_MS);

    private CarVolumeEventHandler mEventHandler;

    @Rule
    public NoActiveHandlerThreadCheckerRule mNoActiveHandlerThreadCheckerRule =
            new NoActiveHandlerThreadCheckerRule();

    @Before
    public void setUp() {
        mEventHandler = new CarVolumeEventHandler();
    }

    @After
    public void tearDown() {
        mEventHandler.destroy();
    }

    @Test
    public void registerCarVolumeEventCallback() {
        mEventHandler.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        expectWithMessage("registered UID")
                .that(mEventHandler.checkIfUidIsRegistered(TEST_UID)).isTrue();
    }

    @Test
    public void unregisterCarVolumeEventCallback() {
        mEventHandler.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        mEventHandler.unregisterCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        expectWithMessage("unregistered UID")
                .that(mEventHandler.checkIfUidIsRegistered(TEST_UID)).isFalse();
    }

    @Test
    public void onMasterMuteChanged() throws Exception {
        mEventHandler.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        mEventHandler.onMasterMuteChanged(TEST_ZONE_ID, TEST_FLAG);

        expectWithMessage("Invocation of callback for master mute change").that(
                mCarVolumeEventCallback.waitForCallback()).isTrue();
    }

    @Test
    public void onVolumeGroupEvent() throws Exception {
        CarVolumeGroupEvent eventMock = mock(CarVolumeGroupEvent.class);
        mEventHandler.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        mEventHandler.onVolumeGroupEvent(List.of(eventMock));

        expectWithMessage("Invocation of callback for volume group event change").that(
                mCarVolumeEventCallback.waitForCallback()).isTrue();
    }

    @Test
    public void release() {
        mEventHandler.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        mEventHandler.release();

        expectWithMessage("Released UID")
                .that(mEventHandler.checkIfUidIsRegistered(TEST_UID)).isFalse();
    }

    @Test
    public void onCallbackDied() {
        mEventHandler.registerCarVolumeEventCallback(mCarVolumeEventCallback, TEST_UID);

        mEventHandler.onCallbackDied(mCarVolumeEventCallback, TEST_UID);

        expectWithMessage("UID with dead callback")
                .that(mEventHandler.checkIfUidIsRegistered(TEST_UID)).isFalse();
    }
}
