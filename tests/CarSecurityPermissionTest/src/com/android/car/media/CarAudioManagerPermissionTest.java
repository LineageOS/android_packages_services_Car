/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.media;

import static android.car.Car.AUDIO_SERVICE;
import static android.car.Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS;
import static android.car.Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_DYNAMIC_ROUTING;
import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.car.test.mocks.JavaMockitoHelper.silentAwait;
import static android.media.AudioAttributes.USAGE_MEDIA;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.car.Car;
import android.car.media.CarAudioManager;
import android.car.media.CarVolumeGroupEvent;
import android.car.media.CarVolumeGroupEventCallback;
import android.car.media.CarVolumeGroupInfo;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.AbstractCarManagerPermissionTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * This class contains security permission tests for the {@link CarAudioManager}'s system APIs.
 */
@RunWith(AndroidJUnit4.class)
public final class CarAudioManagerPermissionTest extends AbstractCarManagerPermissionTest {
    private static final String TAG = CarAudioManagerPermissionTest.class.getSimpleName();

    private static final long WAIT_TIMEOUT_MS = 5_000;

    private static final int GROUP_ID = 0;
    private static final int UID = 10;

    private static final UiAutomation UI_AUTOMATION =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();

    private CarAudioManager mCarAudioManager;
    private SyncCarVolumeCallback mCallback;
    private TestCarVolumeGroupEventCallback mEventCallback;

    @Before
    public void setUp() {
        super.connectCar();
        mCarAudioManager = (CarAudioManager) mCar.getCarManager(AUDIO_SERVICE);
    }

    @Test
    public void setGroupVolumePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.setGroupVolume(GROUP_ID, 0, 0));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void setGroupVolumeWithZonePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.setGroupVolume(PRIMARY_AUDIO_ZONE, GROUP_ID, 0, 0));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getGroupMaxVolumePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getGroupMaxVolume(GROUP_ID));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getGroupMaxVolumeWithZonePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getGroupMaxVolume(PRIMARY_AUDIO_ZONE, GROUP_ID));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getGroupMinVolumePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getGroupMinVolume(GROUP_ID));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getGroupMinVolumeWithZonePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getGroupMinVolume(PRIMARY_AUDIO_ZONE, GROUP_ID));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getGroupVolumePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getGroupVolume(GROUP_ID));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getGroupVolumeWithZonePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getGroupVolume(PRIMARY_AUDIO_ZONE, GROUP_ID));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void setFadeTowardFrontPermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.setFadeTowardFront(0));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void setBalanceTowardRightPermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.setBalanceTowardRight(0));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getExternalSourcesPermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getExternalSources());
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void createAudioPatchPermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.createAudioPatch("address", USAGE_MEDIA, 0));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void releaseAudioPatchPermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.releaseAudioPatch(null));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void getVolumeGroupCountPermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getVolumeGroupCount());
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getVolumeGroupCountWithZonePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getVolumeGroupCount(PRIMARY_AUDIO_ZONE));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getVolumeGroupIdForUsagePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getVolumeGroupIdForUsage(USAGE_MEDIA));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getVolumeGroupIdForUsageWithZonePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE, USAGE_MEDIA));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void isPlaybackOnVolumeGroupActivePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.isPlaybackOnVolumeGroupActive(PRIMARY_AUDIO_ZONE, GROUP_ID));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getUsagesForVolumeGroupIdPermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getUsagesForVolumeGroupId(GROUP_ID));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getAudioZoneIdsPermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getAudioZoneIds());
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void getZoneIdForUidPermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getZoneIdForUid(UID));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void setZoneIdForUidPermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.setZoneIdForUid(PRIMARY_AUDIO_ZONE, UID));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void clearZoneIdForUidPermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.clearZoneIdForUid(UID));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void getOutputDeviceForUsagePermission() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getOutputDeviceForUsage(PRIMARY_AUDIO_ZONE, USAGE_MEDIA));
        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
    }

    @Test
    public void getAudioAttributesForVolumeGroup_withoutPermission() {
        assumeDynamicRoutingIsEnabled();
        CarVolumeGroupInfo info;

        UI_AUTOMATION.adoptShellPermissionIdentity(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        try {
            info = mCarAudioManager.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, /* groupId= */ 0);
        } finally {
            UI_AUTOMATION.dropShellPermissionIdentity();
        }

        Exception exception = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getAudioAttributesForVolumeGroup(info));

        assertWithMessage("Car volume group audio attributes without permission exception")
                .that(exception).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getVolumeGroupInfo_withoutPermission() {
        Exception exception = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getVolumeGroupInfo(PRIMARY_AUDIO_ZONE, /* groupId= */ 0));

        assertWithMessage("Car volume group info without permission exception")
                .that(exception).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void getVolumeGroupInfosForZone_withoutPermission() {
        Exception exception = assertThrows(SecurityException.class,
                () -> mCarAudioManager.getVolumeGroupInfosForZone(PRIMARY_AUDIO_ZONE));

        assertWithMessage("Car volume groups info without permission exception")
                .that(exception).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void registerCarVolumeCallback_nonNullCallback_throwsPermissionError() {
        mCallback = new SyncCarVolumeCallback();

        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.registerCarVolumeCallback(mCallback));

        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void registerCarVolumeGroupEventCallback_nonNullInputs_throwsPermissionError() {
        Executor executor = Executors.newFixedThreadPool(1);
        mEventCallback = new TestCarVolumeGroupEventCallback();

        Exception exception = assertThrows(SecurityException.class,
                () -> mCarAudioManager.registerCarVolumeGroupEventCallback(executor,
                        mEventCallback));

        mEventCallback = null;
        assertWithMessage("Register car volume group event callback without permission exception")
                .that(exception).hasMessageThat().contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    @Test
    public void unregisterCarVolumeCallback_withoutPermission_receivesCallback() {
        assumeDynamicRoutingIsEnabled();
        mCallback = new SyncCarVolumeCallback();
        runWithCarControlAudioVolumePermission(
                () -> mCarAudioManager.registerCarVolumeCallback(mCallback));

        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.unregisterCarVolumeCallback(mCallback));

        injectVolumeDownKeyEvent();
        assertWithMessage("Car group volume change after unregister security exception")
                .that(mCallback.receivedGroupVolumeChanged()).isTrue();
    }

    @Test
    public void unregisterCarVolumeCallback_withoutPermission_throws() {
        mCallback = new SyncCarVolumeCallback();
        runWithCarControlAudioVolumePermission(
                () -> mCarAudioManager.registerCarVolumeCallback(mCallback));

        Exception e = assertThrows(SecurityException.class,
                () -> mCarAudioManager.unregisterCarVolumeCallback(mCallback));

        assertThat(e.getMessage()).contains(PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
    }

    private void assumeDynamicRoutingIsEnabled() {
        assumeTrue("Requires dynamic audio routing",
                mCarAudioManager.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING));
    }

    private void injectVolumeDownKeyEvent() {
        injectKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN);
    }

    private void injectKeyEvent(int keyCode) {
        long downTime = SystemClock.uptimeMillis();
        KeyEvent volumeDown = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
                keyCode, 0);
        UI_AUTOMATION.injectInputEvent(volumeDown, true);
    }

    private void runWithCarControlAudioVolumePermission(Runnable runnable) {
        UI_AUTOMATION.adoptShellPermissionIdentity(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        try {
            runnable.run();
        } finally {
            UI_AUTOMATION.dropShellPermissionIdentity();
        }
    }

    // TODO(b/298052350): Move this class to car-lib-test so it can be shared with CarAudioManager
    private static final class SyncCarVolumeCallback extends CarAudioManager.CarVolumeCallback {
        private final CountDownLatch mGroupVolumeChangeLatch = new CountDownLatch(1);
        private final CountDownLatch mGroupMuteChangeLatch = new CountDownLatch(1);
        private final CountDownLatch mMasterMuteChangeLatch = new CountDownLatch(1);

        public int zoneId;
        public int groupId;

        boolean receivedGroupVolumeChanged() {
            return silentAwait(mGroupVolumeChangeLatch, WAIT_TIMEOUT_MS);
        }

        boolean receivedGroupMuteChanged() {
            return silentAwait(mGroupMuteChangeLatch, WAIT_TIMEOUT_MS);
        }

        boolean receivedMasterMuteChanged() {
            return silentAwait(mMasterMuteChangeLatch, WAIT_TIMEOUT_MS);
        }

        @Override
        public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {
            Log.v(TAG, "onGroupVolumeChanged");
            mGroupVolumeChangeLatch.countDown();
        }

        @Override
        public void onMasterMuteChanged(int zoneId, int flags) {
            Log.v(TAG, "onMasterMuteChanged");
            mMasterMuteChangeLatch.countDown();
        }

        @Override
        public void onGroupMuteChanged(int zoneId, int groupId, int flags) {
            Log.v(TAG, "onGroupMuteChanged");
            this.zoneId = zoneId;
            this.groupId = groupId;
            mGroupMuteChangeLatch.countDown();
        }
    }

    // TODO(b/298052350): Move this class to car-lib-test so it can be shared with CarAudioManager
    private static final class TestCarVolumeGroupEventCallback implements
            CarVolumeGroupEventCallback {

        private CountDownLatch mVolumeGroupEventLatch = new CountDownLatch(1);
        List<CarVolumeGroupEvent> mEvents;

        boolean receivedVolumeGroupEvents() throws InterruptedException {
            return silentAwait(mVolumeGroupEventLatch, WAIT_TIMEOUT_MS);
        }

        @Override
        public void onVolumeGroupEvent(List<CarVolumeGroupEvent> volumeGroupEvents) {
            mEvents = volumeGroupEvents;
            Log.v(TAG, "onVolumeGroupEvent events " + volumeGroupEvents);
            mVolumeGroupEventLatch.countDown();
        }
    }
}
