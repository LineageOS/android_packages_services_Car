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

package android.car.media;

import static android.car.media.CarAudioManager.AUDIO_FEATURE_DYNAMIC_ROUTING;
import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.media.AudioAttributes.USAGE_GAME;
import static android.media.AudioAttributes.USAGE_MEDIA;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.test.AbstractExpectableTestCase;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import com.android.car.CarServiceUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.concurrent.Executor;

@RunWith(MockitoJUnitRunner.class)
public final class CarAudioManagerUnitTest extends AbstractExpectableTestCase {

    private static final String Car_AUDIO_MANAGER_TEST_THREAD_NAME = "CarAudioManagerUnitTest";
    private static final int TEST_REAR_LEFT_ZONE_ID = 1;
    private static final int TEST_REAR_RIGHT_ZONE_ID = 2;
    private static final int TEST_FRONT_ZONE_ID = 3;
    private static final int TEST_VOLUME_GROUP_ID = 1;
    private static final int TEST_VOLUME_GROUP_INDEX = 10;
    private static final int TEST_FLAGS = 0;
    private static final int TEST_GAIN_MIN_VALUE = -3000;
    private static final int TEST_GAIN_MAX_VALUE = -1000;
    private static final int TEST_GAIN_DEFAULT_VALUE = -2000;
    private static final int TEST_VOLUME_GROUP_COUNT = 2;
    private static final int TEST_USAGE = USAGE_MEDIA;
    private static final int TEST_UID = 15;
    private static final long TEST_TIME_OUT_MS = 500;
    private static final long TEST_REQUEST_ID = 1;

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private final HandlerThread mHandlerThread = CarServiceUtils.getHandlerThread(
            Car_AUDIO_MANAGER_TEST_THREAD_NAME);
    private final Handler mHandler = new Handler(mHandlerThread.getLooper());

    @Mock
    private Car mCar;
    @Mock
    private IBinder mBinderMock;
    @Mock
    private ICarAudio mServiceMock;
    @Mock
    private AudioManager mAudioManagerMock;
    @Mock
    private Context mContextMock;
    @Mock
    private CarVolumeGroupInfo mCarVolumeGroupInfoMock1;
    @Mock
    private CarVolumeGroupInfo mCarVolumeGroupInfoMock2;
    @Mock
    private AudioAttributes mAudioAttributesMock1;
    @Mock
    private AudioAttributes mAudioAttributesMock2;
    @Mock
    private CarAudioManager.CarVolumeCallback mVolumeCallbackMock1;
    @Mock
    private CarAudioManager.CarVolumeCallback mVolumeCallbackMock2;
    @Mock
    private OccupantZoneInfo mOccupantZoneInfoMock;
    @Mock
    private PrimaryZoneMediaAudioRequestCallback mPrimaryZoneMediaAudioRequestCallbackMock;
    @Mock
    private MediaAudioRequestStatusCallback mMediaAudioRequestStatusCallbackMock;
    @Mock
    private AudioZonesMirrorStatusCallback mAudioZonesMirrorStatusCallback;

    private CarAudioManager mCarAudioManager;

    @Before
    public void setUp() {
        when(mBinderMock.queryLocalInterface(anyString())).thenReturn(mServiceMock);
        when(mCar.getContext()).thenReturn(mContextMock);
        when(mCar.getEventHandler()).thenReturn(mHandler);
        when(mContextMock.getSystemService(AudioManager.class)).thenReturn(mAudioManagerMock);
        mCarAudioManager = new CarAudioManager(mCar, mBinderMock);
    }

    @Test
    public void isAudioFeatureEnabled() throws Exception {
        when(mServiceMock.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING)).thenReturn(true);

        expectWithMessage("Dynamic audio routing enabled")
                .that(mCarAudioManager.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING))
                .isTrue();
    }

    @Test
    public void setGroupVolume_withoutZoneId() throws Exception {
        mCarAudioManager.setGroupVolume(TEST_VOLUME_GROUP_ID, TEST_VOLUME_GROUP_INDEX, TEST_FLAGS);

        verify(mServiceMock).setGroupVolume(PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP_ID,
                TEST_VOLUME_GROUP_INDEX, TEST_FLAGS);
    }

    @Test
    public void setGroupVolume_withZoneId() throws Exception {
        mCarAudioManager.setGroupVolume(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID,
                TEST_VOLUME_GROUP_INDEX, TEST_FLAGS);

        verify(mServiceMock).setGroupVolume(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID,
                TEST_VOLUME_GROUP_INDEX, TEST_FLAGS);
    }

    @Test
    public void getGroupMaxVolume_withoutZoneId() throws Exception {
        when(mServiceMock.getGroupMaxVolume(PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP_ID))
                .thenReturn(TEST_GAIN_MAX_VALUE);

        expectWithMessage("Primary zone max volume")
                .that(mCarAudioManager.getGroupMaxVolume(TEST_VOLUME_GROUP_ID))
                .isEqualTo(TEST_GAIN_MAX_VALUE);
    }

    @Test
    public void getGroupMaxVolume_withZoneId() throws Exception {
        when(mServiceMock.getGroupMaxVolume(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID))
                .thenReturn(TEST_GAIN_MAX_VALUE);

        expectWithMessage("Rear right zone max volume").that(mCarAudioManager
                .getGroupMaxVolume(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID))
                .isEqualTo(TEST_GAIN_MAX_VALUE);
    }

    @Test
    public void getGroupMinVolume_withoutZoneId() throws Exception {
        when(mServiceMock.getGroupMinVolume(PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP_ID))
                .thenReturn(TEST_GAIN_MIN_VALUE);

        expectWithMessage("Primary zone min volume")
                .that(mCarAudioManager.getGroupMinVolume(TEST_VOLUME_GROUP_ID))
                .isEqualTo(TEST_GAIN_MIN_VALUE);
    }

    @Test
    public void getGroupMinVolume_withZoneId() throws Exception {
        when(mServiceMock.getGroupMinVolume(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID))
                .thenReturn(TEST_GAIN_MIN_VALUE);

        expectWithMessage("Rear right zone min volume").that(mCarAudioManager
                .getGroupMinVolume(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID))
                .isEqualTo(TEST_GAIN_MIN_VALUE);
    }

    @Test
    public void getGroupVolume_withoutZoneId() throws Exception {
        when(mServiceMock.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP_ID))
                .thenReturn(TEST_GAIN_DEFAULT_VALUE);

        expectWithMessage("Primary zone volume")
                .that(mCarAudioManager.getGroupVolume(TEST_VOLUME_GROUP_ID))
                .isEqualTo(TEST_GAIN_DEFAULT_VALUE);
    }

    @Test
    public void getGroupVolume_withZoneId() throws Exception {
        when(mServiceMock.getGroupVolume(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID))
                .thenReturn(TEST_GAIN_DEFAULT_VALUE);

        expectWithMessage("Rear right zone volume").that(mCarAudioManager.getGroupVolume(
                TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID)).isEqualTo(TEST_GAIN_DEFAULT_VALUE);
    }

    @Test
    public void setFadeTowardFront() throws Exception {
        float value = 0.3f;

        mCarAudioManager.setFadeTowardFront(value);

        verify(mServiceMock).setFadeTowardFront(value);
    }

    @Test
    public void setBalanceTowardRight() throws Exception {
        float value = 0.4f;

        mCarAudioManager.setBalanceTowardRight(value);

        verify(mServiceMock).setBalanceTowardRight(value);
    }

    @Test
    public void getVolumeGroupCount_withoutZoneId() throws Exception {
        when(mServiceMock.getVolumeGroupCount(PRIMARY_AUDIO_ZONE))
                .thenReturn(TEST_VOLUME_GROUP_COUNT);

        expectWithMessage("Primary zone volume group count")
                .that(mCarAudioManager.getVolumeGroupCount()).isEqualTo(TEST_VOLUME_GROUP_COUNT);
    }

    @Test
    public void getVolumeGroupCount_withZoneId() throws Exception {
        when(mServiceMock.getVolumeGroupCount(TEST_REAR_RIGHT_ZONE_ID))
                .thenReturn(TEST_VOLUME_GROUP_COUNT);

        expectWithMessage("Rear right zone volume group count")
                .that(mCarAudioManager.getVolumeGroupCount(TEST_REAR_RIGHT_ZONE_ID))
                .isEqualTo(TEST_VOLUME_GROUP_COUNT);
    }

    @Test
    public void getVolumeGroupIdForUsage_withoutZoneId() throws Exception {
        when(mServiceMock.getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE, TEST_USAGE))
                .thenReturn(TEST_VOLUME_GROUP_ID);

        expectWithMessage("Volume group id for media usage in primary zone")
                .that(mCarAudioManager.getVolumeGroupIdForUsage(TEST_USAGE))
                .isEqualTo(TEST_VOLUME_GROUP_ID);
    }

    @Test
    public void getVolumeGroupIdForUsage_withZoneId() throws Exception {
        when(mServiceMock.getVolumeGroupIdForUsage(TEST_REAR_RIGHT_ZONE_ID, TEST_USAGE))
                .thenReturn(TEST_VOLUME_GROUP_ID);

        expectWithMessage("Volume group id for media usage in rear right zone")
                .that(mCarAudioManager.getVolumeGroupIdForUsage(TEST_REAR_RIGHT_ZONE_ID,
                        TEST_USAGE)).isEqualTo(TEST_VOLUME_GROUP_ID);
    }

    @Test
    public void getUsagesForVolumeGroupId_withoutZoneId() throws Exception {
        int[] volumeGroupUsages = new int[]{USAGE_MEDIA, USAGE_GAME};
        when(mServiceMock.getUsagesForVolumeGroupId(PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP_ID))
                .thenReturn(volumeGroupUsages);

        expectWithMessage("Group %s usages in primary zone", TEST_VOLUME_GROUP_ID)
                .that(mCarAudioManager.getUsagesForVolumeGroupId(TEST_VOLUME_GROUP_ID))
                .isEqualTo(volumeGroupUsages);
    }

    @Test
    public void getUsagesForVolumeGroupId_withZoneId() throws Exception {
        int[] volumeGroupUsages = new int[]{USAGE_MEDIA, USAGE_GAME};
        when(mServiceMock.getUsagesForVolumeGroupId(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID))
                .thenReturn(volumeGroupUsages);

        expectWithMessage("Group %s usages in rear right zone", TEST_VOLUME_GROUP_ID)
                .that(mCarAudioManager.getUsagesForVolumeGroupId(TEST_REAR_RIGHT_ZONE_ID,
                        TEST_VOLUME_GROUP_ID)).isEqualTo(volumeGroupUsages);
    }

    @Test
    public void getVolumeGroupInfo() throws Exception {
        when(mServiceMock.getVolumeGroupInfo(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID))
                .thenReturn(mCarVolumeGroupInfoMock1);

        expectWithMessage("Volume group info with id %s in rear right zone")
                .that(mCarAudioManager.getVolumeGroupInfo(TEST_REAR_RIGHT_ZONE_ID,
                        TEST_VOLUME_GROUP_ID)).isEqualTo(mCarVolumeGroupInfoMock1);
    }

    @Test
    public void getVolumeGroupInfosForZone() throws Exception {
        when(mServiceMock.getVolumeGroupInfosForZone(TEST_REAR_RIGHT_ZONE_ID))
                .thenReturn(List.of(mCarVolumeGroupInfoMock1, mCarVolumeGroupInfoMock2));

        expectWithMessage("Volume group infos in rear right zone")
                .that(mCarAudioManager.getVolumeGroupInfosForZone(TEST_REAR_RIGHT_ZONE_ID))
                .containsExactly(mCarVolumeGroupInfoMock1, mCarVolumeGroupInfoMock2);
    }

    @Test
    public void getAudioAttributesForVolumeGroup() throws Exception {
        List<AudioAttributes> attributesList = List.of(mAudioAttributesMock1,
                mAudioAttributesMock2);
        when(mServiceMock.getAudioAttributesForVolumeGroup(mCarVolumeGroupInfoMock1))
                .thenReturn(attributesList);

        expectWithMessage("Audio attributes for volume group").that(mCarAudioManager
                .getAudioAttributesForVolumeGroup(mCarVolumeGroupInfoMock1))
                .isEqualTo(attributesList);
    }

    @Test
    public void isPlaybackOnVolumeGroupActive() throws Exception {
        when(mServiceMock.isPlaybackOnVolumeGroupActive(TEST_REAR_RIGHT_ZONE_ID,
                TEST_VOLUME_GROUP_ID)).thenReturn(true);

        expectWithMessage("Enabled playback on volume group %s in rear right zone",
                TEST_VOLUME_GROUP_ID).that(mCarAudioManager.isPlaybackOnVolumeGroupActive(
                TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID)).isTrue();
    }

    @Test
    public void getAudioZoneIds() throws Exception {
        int[] expectedZoneIds = new int[]{PRIMARY_AUDIO_ZONE, TEST_REAR_LEFT_ZONE_ID,
                TEST_REAR_RIGHT_ZONE_ID, TEST_FRONT_ZONE_ID};
        when(mServiceMock.getAudioZoneIds()).thenReturn(expectedZoneIds);

        expectWithMessage("Zone ids").that(mCarAudioManager.getAudioZoneIds())
                .containsExactly(PRIMARY_AUDIO_ZONE, TEST_REAR_LEFT_ZONE_ID,
                        TEST_REAR_RIGHT_ZONE_ID, TEST_FRONT_ZONE_ID).inOrder();
    }

    @Test
    public void getZoneIdForUid() throws Exception {
        when(mServiceMock.getZoneIdForUid(TEST_UID)).thenReturn(TEST_REAR_LEFT_ZONE_ID);

        expectWithMessage("Zone id for uid %s", TEST_UID).that(mCarAudioManager
                .getZoneIdForUid(TEST_UID)).isEqualTo(TEST_REAR_LEFT_ZONE_ID);
    }

    @Test
    public void setZoneIdForUid() throws Exception {
        when(mServiceMock.setZoneIdForUid(TEST_REAR_RIGHT_ZONE_ID, TEST_UID)).thenReturn(true);

        expectWithMessage("Status for setting uid %s to rear right zone", TEST_UID)
                .that(mCarAudioManager.setZoneIdForUid(TEST_REAR_RIGHT_ZONE_ID, TEST_UID)).isTrue();
    }

    @Test
    public void clearZoneIdForUid() throws Exception {
        when(mServiceMock.clearZoneIdForUid(TEST_UID)).thenReturn(true);

        expectWithMessage("Status for clearing zone id for uid %s", TEST_UID)
                .that(mCarAudioManager.clearZoneIdForUid(TEST_UID)).isTrue();
    }

    @Test
    public void isVolumeGroupMuted() throws Exception {
        when(mServiceMock.isVolumeGroupMuted(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID))
                .thenReturn(true);

        expectWithMessage("Muted volume group").that(mCarAudioManager.isVolumeGroupMuted(
                TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID)).isTrue();
    }

    @Test
    public void setVolumeGroupMute() throws Exception {
        mCarAudioManager.setVolumeGroupMute(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID,
                /* mute= */ true, TEST_FLAGS);

        verify(mServiceMock).setVolumeGroupMute(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID,
                /* mute= */ true, TEST_FLAGS);
    }

    @Test
    public void registerCarVolumeCallback() throws Exception {
        mCarAudioManager.registerCarVolumeCallback(mVolumeCallbackMock1);

        verify(mServiceMock).registerVolumeCallback(any(IBinder.class));
    }

    @Test
    public void registerCarVolumeCallback_withMultipleCallbacks() throws Exception {
        mCarAudioManager.registerCarVolumeCallback(mVolumeCallbackMock1);
        verify(mServiceMock).registerVolumeCallback(any(IBinder.class));

        mCarAudioManager.registerCarVolumeCallback(mVolumeCallbackMock2);

        verify(mServiceMock).registerVolumeCallback(any(IBinder.class));
    }

    @Test
    public void unregisterCarVolumeCallback_withPartOfCallbacks() throws Exception {
        mCarAudioManager.registerCarVolumeCallback(mVolumeCallbackMock1);
        mCarAudioManager.registerCarVolumeCallback(mVolumeCallbackMock2);

        mCarAudioManager.unregisterCarVolumeCallback(mVolumeCallbackMock1);

        verify(mServiceMock, never()).unregisterVolumeCallback(any(IBinder.class));
    }

    @Test
    public void unregisterCarVolumeCallback_withAllCallbacks() throws Exception {
        ArgumentCaptor<IBinder> captor = ArgumentCaptor.forClass(IBinder.class);
        mCarAudioManager.registerCarVolumeCallback(mVolumeCallbackMock1);
        verify(mServiceMock).registerVolumeCallback(captor.capture());
        mCarAudioManager.registerCarVolumeCallback(mVolumeCallbackMock2);

        mCarAudioManager.unregisterCarVolumeCallback(mVolumeCallbackMock1);
        mCarAudioManager.unregisterCarVolumeCallback(mVolumeCallbackMock2);

        verify(mServiceMock).unregisterVolumeCallback(captor.getValue());
    }

    @Test
    public void onCarDisconnected() throws Exception {
        ArgumentCaptor<IBinder> captor = ArgumentCaptor.forClass(IBinder.class);
        mCarAudioManager.registerCarVolumeCallback(mVolumeCallbackMock1);
        verify(mServiceMock).registerVolumeCallback(captor.capture());

        mCarAudioManager.onCarDisconnected();

        verify(mServiceMock).unregisterVolumeCallback(captor.getValue());
    }

    @Test
    public void onGroupMuteChanged_forCarVolumeCallback() throws Exception {
        ICarVolumeCallback callbackImpl = getCarVolumeCallbackImpl(mVolumeCallbackMock1);

        callbackImpl.onGroupMuteChanged(TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID, TEST_FLAGS);

        verify(mVolumeCallbackMock1, timeout(TEST_TIME_OUT_MS)).onGroupMuteChanged(
                TEST_REAR_RIGHT_ZONE_ID, TEST_VOLUME_GROUP_ID, TEST_FLAGS);
    }

    @Test
    public void onGroupVolumeChanged_forCarVolumeCallback() throws Exception {
        ICarVolumeCallback callbackImpl = getCarVolumeCallbackImpl(mVolumeCallbackMock1);

        callbackImpl.onGroupVolumeChanged(PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP_ID, TEST_FLAGS);

        verify(mVolumeCallbackMock1, timeout(TEST_TIME_OUT_MS)).onGroupVolumeChanged(
                PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP_ID, TEST_FLAGS);
    }

    @Test
    public void onMasterMuteChanged_forCarVolumeCallback() throws Exception {
        ICarVolumeCallback callbackImpl = getCarVolumeCallbackImpl(mVolumeCallbackMock1);

        callbackImpl.onMasterMuteChanged(TEST_REAR_LEFT_ZONE_ID, TEST_FLAGS);

        verify(mVolumeCallbackMock1, timeout(TEST_TIME_OUT_MS)).onMasterMuteChanged(
                TEST_REAR_LEFT_ZONE_ID, TEST_FLAGS);
    }

    @Test
    public void setPrimaryZoneMediaAudioRequestCallback() throws Exception {
        when(mServiceMock.registerPrimaryZoneMediaAudioRequestCallback(any())).thenReturn(true);

        expectWithMessage("Status for setting primary zone media audio request callback")
                .that(mCarAudioManager.setPrimaryZoneMediaAudioRequestCallback(DIRECT_EXECUTOR,
                        mPrimaryZoneMediaAudioRequestCallbackMock)).isTrue();
    }

    @Test
    public void setPrimaryZoneMediaAudioRequestCallback_fails() throws Exception {
        when(mServiceMock.registerPrimaryZoneMediaAudioRequestCallback(any())).thenReturn(false);
        mCarAudioManager.setPrimaryZoneMediaAudioRequestCallback(DIRECT_EXECUTOR,
                mPrimaryZoneMediaAudioRequestCallbackMock);

        expectWithMessage("Failure for setting primary zone media audio request callback")
                .that(mCarAudioManager.setPrimaryZoneMediaAudioRequestCallback(DIRECT_EXECUTOR,
                        mPrimaryZoneMediaAudioRequestCallbackMock)).isFalse();
    }

    @Test
    public void setPrimaryZoneMediaAudioRequestCallback_withNullExecutor_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                mCarAudioManager.setPrimaryZoneMediaAudioRequestCallback(/* executor= */ null,
                        mPrimaryZoneMediaAudioRequestCallbackMock));

        expectWithMessage("Exception for setting primary zone media audio request callback with"
                + "null executor").that(thrown).hasMessageThat()
                .contains("Executor can not be null");
    }

    @Test
    public void setPrimaryZoneMediaAudioRequestCallback_withNullCallback_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                mCarAudioManager.setPrimaryZoneMediaAudioRequestCallback(DIRECT_EXECUTOR,
                        /* callback= */ null));

        expectWithMessage("Exception for setting null primary zone media audio request callback")
                .that(thrown).hasMessageThat()
                .contains("Audio media request callback can not be null");
    }

    @Test
    public void setPrimaryZoneMediaAudioRequestCallback_forMultipleTimes_fails() throws Exception {
        when(mServiceMock.registerPrimaryZoneMediaAudioRequestCallback(any())).thenReturn(true);
        mCarAudioManager.setPrimaryZoneMediaAudioRequestCallback(DIRECT_EXECUTOR,
                mPrimaryZoneMediaAudioRequestCallbackMock);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                mCarAudioManager.setPrimaryZoneMediaAudioRequestCallback(DIRECT_EXECUTOR,
                        mock(PrimaryZoneMediaAudioRequestCallback.class)));

        expectWithMessage("Exception for setting primary zone media audio request callback"
                + " for multiple times").that(thrown).hasMessageThat()
                .contains("Primary zone media audio request is already set");
    }

    @Test
    public void setPrimaryZoneMediaAudioRequestCallback_afterClearingPreviousCallbacks()
            throws Exception {
        when(mServiceMock.registerPrimaryZoneMediaAudioRequestCallback(any())).thenReturn(true);
        mCarAudioManager.setPrimaryZoneMediaAudioRequestCallback(DIRECT_EXECUTOR,
                mPrimaryZoneMediaAudioRequestCallbackMock);
        mCarAudioManager.clearPrimaryZoneMediaAudioRequestCallback();

        expectWithMessage("Status for setting primary zone media audio request callback"
                + " again after clearing previous callbacks").that(mCarAudioManager
                .setPrimaryZoneMediaAudioRequestCallback(DIRECT_EXECUTOR,
                        mock(PrimaryZoneMediaAudioRequestCallback.class))).isTrue();
    }

    @Test
    public void clearPrimaryZoneMediaAudioRequestCallback() throws Exception {
        IPrimaryZoneMediaAudioRequestCallback serviceCallback =
                getPrimaryZoneMediaAudioRequestCallbackImpl(
                        mPrimaryZoneMediaAudioRequestCallbackMock);

        mCarAudioManager.clearPrimaryZoneMediaAudioRequestCallback();

        verify(mServiceMock).unregisterPrimaryZoneMediaAudioRequestCallback(serviceCallback);
    }

    @Test
    public void onRequestMediaOnPrimaryZone_forIPrimaryZoneMediaAudioRequestCallback()
            throws Exception {
        IPrimaryZoneMediaAudioRequestCallback serviceCallback =
                getPrimaryZoneMediaAudioRequestCallbackImpl(
                        mPrimaryZoneMediaAudioRequestCallbackMock);

        serviceCallback.onRequestMediaOnPrimaryZone(mOccupantZoneInfoMock, TEST_REQUEST_ID);

        verify(mPrimaryZoneMediaAudioRequestCallbackMock).onRequestMediaOnPrimaryZone(
                mOccupantZoneInfoMock, TEST_REQUEST_ID);
    }

    @Test
    public void onMediaAudioRequestStatusChanged_forIPrimaryZoneMediaAudioRequestCallback()
            throws Exception {
        IPrimaryZoneMediaAudioRequestCallback serviceCallback =
                getPrimaryZoneMediaAudioRequestCallbackImpl(
                        mPrimaryZoneMediaAudioRequestCallbackMock);

        serviceCallback.onMediaAudioRequestStatusChanged(mOccupantZoneInfoMock, TEST_REQUEST_ID,
                CarAudioManager.AUDIO_REQUEST_STATUS_STOPPED);

        verify(mPrimaryZoneMediaAudioRequestCallbackMock).onMediaAudioRequestStatusChanged(
                mOccupantZoneInfoMock, TEST_REQUEST_ID,
                CarAudioManager.AUDIO_REQUEST_STATUS_STOPPED);
    }

    @Test
    public void requestMediaAudioOnPrimaryZone() throws Exception {
        int expectedRequestStatus = CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED;
        doAnswer(invocation -> {
            IMediaAudioRequestStatusCallback callback = invocation.getArgument(0);
            OccupantZoneInfo info = invocation.getArgument(1);
            callback.onMediaAudioRequestStatusChanged(info, TEST_REQUEST_ID, expectedRequestStatus);
            return TEST_REQUEST_ID;
        }).when(mServiceMock).requestMediaAudioOnPrimaryZone(any(
                IMediaAudioRequestStatusCallback.class), any(OccupantZoneInfo.class));

        long requestId = mCarAudioManager.requestMediaAudioOnPrimaryZone(mOccupantZoneInfoMock,
                DIRECT_EXECUTOR, mMediaAudioRequestStatusCallbackMock);

        expectWithMessage("Request id for media audio on primary zone").that(requestId)
                .isEqualTo(TEST_REQUEST_ID);
        verify(mMediaAudioRequestStatusCallbackMock).onMediaAudioRequestStatusChanged(
                mOccupantZoneInfoMock, TEST_REQUEST_ID, expectedRequestStatus);
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withNullOccupantZoneInfo() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                mCarAudioManager.requestMediaAudioOnPrimaryZone(/* info= */ null,
                        DIRECT_EXECUTOR, mMediaAudioRequestStatusCallbackMock));

        expectWithMessage("Exception for requesting media audio on primary zone with null info")
                .that(thrown).hasMessageThat().contains("Occupant zone info can not be null");
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withNullExecutor() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                mCarAudioManager.requestMediaAudioOnPrimaryZone(mOccupantZoneInfoMock,
                        /* executor= */ null, mMediaAudioRequestStatusCallbackMock));

        expectWithMessage("Exception for requesting media audio on primary zone with null executor")
                .that(thrown).hasMessageThat().contains("Executor can not be null");
    }

    @Test
    public void requestMediaAudioOnPrimaryZone_withNullCallback() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                mCarAudioManager.requestMediaAudioOnPrimaryZone(mOccupantZoneInfoMock,
                        DIRECT_EXECUTOR, /* callback= */ null));

        expectWithMessage("Exception for requesting media audio on primary zone with "
                + "null callback").that(thrown).hasMessageThat()
                .contains("Media audio request status callback can not be null");
    }

    @Test
    public void cancelMediaAudioOnPrimaryZone() throws Exception {
        when(mServiceMock.requestMediaAudioOnPrimaryZone(any(), any())).thenReturn(TEST_REQUEST_ID);
        long requestId = mCarAudioManager.requestMediaAudioOnPrimaryZone(mOccupantZoneInfoMock,
                DIRECT_EXECUTOR, mMediaAudioRequestStatusCallbackMock);
        when(mServiceMock.cancelMediaAudioOnPrimaryZone(requestId)).thenReturn(true);

        expectWithMessage("Status for canceling pending media audio on primary zone")
                .that(mCarAudioManager.cancelMediaAudioOnPrimaryZone(requestId)).isTrue();
    }

    @Test
    public void cancelMediaAudioOnPrimaryZone_withInvalidId() throws Exception {
        when(mServiceMock.requestMediaAudioOnPrimaryZone(any(), any()))
                .thenReturn(CarAudioManager.INVALID_REQUEST_ID);
        long requestId = mCarAudioManager.requestMediaAudioOnPrimaryZone(mOccupantZoneInfoMock,
                DIRECT_EXECUTOR, mMediaAudioRequestStatusCallbackMock);

        expectWithMessage("Status for canceling media audio request with invalid id")
                .that(mCarAudioManager.cancelMediaAudioOnPrimaryZone(requestId)).isTrue();
        verify(mServiceMock, never()).cancelMediaAudioOnPrimaryZone(requestId);
    }

    @Test
    public void allowMediaAudioOnPrimaryZone() throws Exception {
        IPrimaryZoneMediaAudioRequestCallback serviceCallback =
                getPrimaryZoneMediaAudioRequestCallbackImpl(
                        mPrimaryZoneMediaAudioRequestCallbackMock);
        when(mServiceMock.allowMediaAudioOnPrimaryZone(serviceCallback.asBinder(), TEST_REQUEST_ID,
                /* allow= */ true)).thenReturn(true);

        expectWithMessage("Status for allowing media audio on primary zone")
                .that(mCarAudioManager.allowMediaAudioOnPrimaryZone(TEST_REQUEST_ID,
                        /* allow= */ true)).isTrue();
    }

    @Test
    public void allowMediaAudioOnPrimaryZone_beforeSettingCallback() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                mCarAudioManager.allowMediaAudioOnPrimaryZone(TEST_REQUEST_ID, /* allow= */ true));

        expectWithMessage("Exception for allowing media audio on primary zone before "
                + "setting primary zone media audio request callback").that(thrown)
                .hasMessageThat().contains("Primary zone media audio request callback");
    }

    @Test
    public void resetMediaAudioOnPrimaryZone() throws Exception {
        when(mServiceMock.resetMediaAudioOnPrimaryZone(mOccupantZoneInfoMock)).thenReturn(true);

        expectWithMessage("Status for resetting media audio on primary zone")
                .that(mCarAudioManager.resetMediaAudioOnPrimaryZone(mOccupantZoneInfoMock))
                .isTrue();
    }

    @Test
    public void isMediaAudioAllowedInPrimaryZone() throws Exception {
        when(mServiceMock.isMediaAudioAllowedInPrimaryZone(mOccupantZoneInfoMock)).thenReturn(true);

        expectWithMessage("Media audio allowed in primary zone").that(mCarAudioManager
                .isMediaAudioAllowedInPrimaryZone(mOccupantZoneInfoMock)).isTrue();
    }

    @Test
    public void setAudioZoneMirrorStatusCallback_succeeds() throws Exception {
        when(mServiceMock.registerAudioZonesMirrorStatusCallback(any())).thenReturn(true);

        expectWithMessage("Status for setting audio zone mirror status callback")
                .that(mCarAudioManager.setAudioZoneMirrorStatusCallback(DIRECT_EXECUTOR,
                        mAudioZonesMirrorStatusCallback)).isTrue();
    }

    @Test
    public void setAudioZoneMirrorStatusCallback_withServiceReturnsFalse_fails() throws Exception {
        when(mServiceMock.registerAudioZonesMirrorStatusCallback(any())).thenReturn(false);

        expectWithMessage("Status for setting audio zone mirror status callback")
                .that(mCarAudioManager.setAudioZoneMirrorStatusCallback(DIRECT_EXECUTOR,
                        mAudioZonesMirrorStatusCallback)).isFalse();
    }

    @Test
    public void setAudioZoneMirrorStatusCallback_forMultipleTimes_fails() throws Exception {
        when(mServiceMock.registerAudioZonesMirrorStatusCallback(any())).thenReturn(true);
        mCarAudioManager.setAudioZoneMirrorStatusCallback(DIRECT_EXECUTOR,
                mAudioZonesMirrorStatusCallback);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                mCarAudioManager.setAudioZoneMirrorStatusCallback(DIRECT_EXECUTOR,
                        mock(AudioZonesMirrorStatusCallback.class)));

        expectWithMessage("Exception for setting audio zone mirror status callback multiple times")
                .that(thrown).hasMessageThat().contains("callback is already set");
    }

    @Test
    public void setAudioZoneMirrorStatusCallback_withNullExecutor() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                mCarAudioManager.setAudioZoneMirrorStatusCallback(/* executor= */ null,
                        mAudioZonesMirrorStatusCallback));

        expectWithMessage("Exception for setting audio zone mirror status callback with"
                + " null executor").that(thrown).hasMessageThat()
                .contains("Executor can not be null");
    }

    @Test
    public void setAudioZoneMirrorStatusCallback_withNullCallback() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                mCarAudioManager.setAudioZoneMirrorStatusCallback(DIRECT_EXECUTOR,
                        /* callback= */ null));

        expectWithMessage("Exception for setting audio zone mirror status callback with "
                + "null callback").that(thrown).hasMessageThat()
                .contains("Audio zones mirror status callback can not be null");
    }

    @Test
    public void clearAudioZonesMirrorStatusCallback() throws Exception {
        when(mServiceMock.registerAudioZonesMirrorStatusCallback(any())).thenReturn(true);
        ArgumentCaptor<IAudioZonesMirrorStatusCallback> captor = ArgumentCaptor.forClass(
                IAudioZonesMirrorStatusCallback.class);
        mCarAudioManager.setAudioZoneMirrorStatusCallback(DIRECT_EXECUTOR,
                mAudioZonesMirrorStatusCallback);
        verify(mServiceMock).registerAudioZonesMirrorStatusCallback(captor.capture());

        mCarAudioManager.clearAudioZonesMirrorStatusCallback();

        verify(mServiceMock).unregisterAudioZonesMirrorStatusCallback(captor.getValue());
    }

    @Test
    public void clearAudioZonesMirrorStatusCallback_withoutCallbackSet() throws Exception {
        mCarAudioManager.clearAudioZonesMirrorStatusCallback();

        verify(mServiceMock, never()).unregisterAudioZonesMirrorStatusCallback(any());
    }

    @Test
    public void canEnableAudioMirror() throws Exception {
        when(mServiceMock.canEnableAudioMirror()).thenReturn(
                CarAudioManager.AUDIO_MIRROR_OUT_OF_OUTPUT_DEVICES);

        expectWithMessage("Audio mirror status").that(mCarAudioManager.canEnableAudioMirror())
                .isEqualTo(CarAudioManager.AUDIO_MIRROR_OUT_OF_OUTPUT_DEVICES);
    }

    @Test
    public void enableMirrorForAudioZones() throws Exception {
        List<Integer> zonesToMirrorList = List.of(TEST_REAR_LEFT_ZONE_ID, TEST_REAR_RIGHT_ZONE_ID);
        IAudioZonesMirrorStatusCallback callback = getAudioZonesMirrorStatusCallbackWrapper();
        doAnswer(invocation -> {
            int[] zones = invocation.getArgument(0);
            callback.onAudioZonesMirrorStatusChanged(zones,
                    CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED);
            return TEST_REQUEST_ID;
        }).when(mServiceMock).enableMirrorForAudioZones(any(int[].class));

        long requestId = mCarAudioManager.enableMirrorForAudioZones(zonesToMirrorList);

        expectWithMessage("Request id for enabling mirror for audio zones").that(requestId)
                .isEqualTo(TEST_REQUEST_ID);
        verify(mAudioZonesMirrorStatusCallback).onAudioZonesMirrorStatusChanged(zonesToMirrorList,
                CarAudioManager.AUDIO_REQUEST_STATUS_APPROVED);
    }

    @Test
    public void enableMirrorForAudioZones_withNullZoneList_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                mCarAudioManager.enableMirrorForAudioZones(/* audioZonesToMirror= */ null));

        expectWithMessage("Exception for enabling mirror on null zone list").that(thrown)
                .hasMessageThat().contains("Audio zones to mirror should not be null");
    }

    @Test
    public void extendAudioMirrorRequest() throws Exception {
        List<Integer> zonesToMirrorList = List.of(TEST_REAR_LEFT_ZONE_ID, TEST_FRONT_ZONE_ID);
        IAudioZonesMirrorStatusCallback callback = getAudioZonesMirrorStatusCallbackWrapper();
        doAnswer(invocation -> {
            int[] zones = invocation.getArgument(1);
            callback.onAudioZonesMirrorStatusChanged(zones,
                    CarAudioManager.AUDIO_REQUEST_STATUS_REJECTED);
            return null;
        }).when(mServiceMock).extendAudioMirrorRequest(eq(TEST_REQUEST_ID), any(int[].class));

        mCarAudioManager.extendAudioMirrorRequest(TEST_REQUEST_ID, zonesToMirrorList);

        verify(mAudioZonesMirrorStatusCallback).onAudioZonesMirrorStatusChanged(zonesToMirrorList,
                CarAudioManager.AUDIO_REQUEST_STATUS_REJECTED);
    }

    @Test
    public void extendAudioMirrorRequest_withNullZoneList_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                mCarAudioManager.extendAudioMirrorRequest(TEST_REQUEST_ID,
                        /* audioZonesToMirror= */ null));

        expectWithMessage("Exception for extending mirror on null zone list").that(thrown)
                .hasMessageThat().contains("Audio zones to mirror should not be null");
    }

    @Test
    public void disableAudioMirror() throws Exception {
        List<Integer> zonesToMirrorList = List.of(TEST_REAR_LEFT_ZONE_ID, TEST_REAR_RIGHT_ZONE_ID);
        IAudioZonesMirrorStatusCallback callback = getAudioZonesMirrorStatusCallbackWrapper();
        long requestId = mCarAudioManager.enableMirrorForAudioZones(zonesToMirrorList);
        doAnswer(invocation -> {
            callback.onAudioZonesMirrorStatusChanged(new int[]{TEST_REAR_LEFT_ZONE_ID,
                    TEST_REAR_RIGHT_ZONE_ID}, CarAudioManager.AUDIO_REQUEST_STATUS_CANCELLED);
            return null;
        }).when(mServiceMock).disableAudioMirror(requestId);

        mCarAudioManager.disableAudioMirror(requestId);

        verify(mAudioZonesMirrorStatusCallback).onAudioZonesMirrorStatusChanged(zonesToMirrorList,
                CarAudioManager.AUDIO_REQUEST_STATUS_CANCELLED);
    }

    @Test
    public void disableAudioMirrorForZone() throws Exception {
        List<Integer> zonesToMirrorList = List.of(TEST_REAR_LEFT_ZONE_ID, TEST_REAR_RIGHT_ZONE_ID);
        IAudioZonesMirrorStatusCallback callback = getAudioZonesMirrorStatusCallbackWrapper();
        doAnswer(invocation -> {
            callback.onAudioZonesMirrorStatusChanged(new int[]{TEST_REAR_LEFT_ZONE_ID,
                    TEST_REAR_RIGHT_ZONE_ID}, CarAudioManager.AUDIO_REQUEST_STATUS_CANCELLED);
            return null;
        }).when(mServiceMock).disableAudioMirrorForZone(TEST_REAR_RIGHT_ZONE_ID);

        mCarAudioManager.disableAudioMirrorForZone(TEST_REAR_RIGHT_ZONE_ID);

        verify(mAudioZonesMirrorStatusCallback).onAudioZonesMirrorStatusChanged(zonesToMirrorList,
                CarAudioManager.AUDIO_REQUEST_STATUS_CANCELLED);
    }

    @Test
    public void getMirrorAudioZonesForAudioZone() throws Exception {
        when(mServiceMock.getMirrorAudioZonesForAudioZone(TEST_REAR_RIGHT_ZONE_ID)).thenReturn(
                new int[]{TEST_REAR_LEFT_ZONE_ID, TEST_FRONT_ZONE_ID});

        expectWithMessage("Mirror zones for rear right zone").that(mCarAudioManager
                .getMirrorAudioZonesForAudioZone(TEST_REAR_RIGHT_ZONE_ID)).containsExactly(
                TEST_REAR_LEFT_ZONE_ID, TEST_FRONT_ZONE_ID).inOrder();
    }

    @Test
    public void getMirrorAudioZonesForMirrorRequest() throws Exception {
        when(mServiceMock.getMirrorAudioZonesForMirrorRequest(TEST_REQUEST_ID)).thenReturn(
                new int[]{TEST_REAR_LEFT_ZONE_ID, TEST_REAR_RIGHT_ZONE_ID});

        expectWithMessage("Mirror zones for request id").that(mCarAudioManager
                .getMirrorAudioZonesForMirrorRequest(TEST_REQUEST_ID)).containsExactly(
                TEST_REAR_LEFT_ZONE_ID, TEST_REAR_RIGHT_ZONE_ID).inOrder();
    }

    private ICarVolumeCallback getCarVolumeCallbackImpl(CarAudioManager.CarVolumeCallback
            callbackMock) throws Exception {
        ArgumentCaptor<IBinder> captor = ArgumentCaptor.forClass(IBinder.class);
        mCarAudioManager.registerCarVolumeCallback(callbackMock);
        verify(mServiceMock).registerVolumeCallback(captor.capture());
        return ICarVolumeCallback.Stub.asInterface(captor.getValue());
    }

    private IPrimaryZoneMediaAudioRequestCallback getPrimaryZoneMediaAudioRequestCallbackImpl(
            PrimaryZoneMediaAudioRequestCallback callbackMock) throws Exception {
        when(mServiceMock.registerPrimaryZoneMediaAudioRequestCallback(any())).thenReturn(true);
        ArgumentCaptor<IPrimaryZoneMediaAudioRequestCallback> captor = ArgumentCaptor.forClass(
                IPrimaryZoneMediaAudioRequestCallback.class);
        mCarAudioManager.setPrimaryZoneMediaAudioRequestCallback(DIRECT_EXECUTOR, callbackMock);
        verify(mServiceMock).registerPrimaryZoneMediaAudioRequestCallback(captor.capture());
        return captor.getValue();
    }

    private IAudioZonesMirrorStatusCallback getAudioZonesMirrorStatusCallbackWrapper()
            throws Exception {
        ArgumentCaptor<IAudioZonesMirrorStatusCallback> captor = ArgumentCaptor.forClass(
                IAudioZonesMirrorStatusCallback.class);
        when(mServiceMock.registerAudioZonesMirrorStatusCallback(
                any(IAudioZonesMirrorStatusCallback.class))).thenReturn(true);
        mCarAudioManager.setAudioZoneMirrorStatusCallback(DIRECT_EXECUTOR,
                mAudioZonesMirrorStatusCallback);
        verify(mServiceMock).registerAudioZonesMirrorStatusCallback(captor.capture());
        return captor.getValue();
    }
}
