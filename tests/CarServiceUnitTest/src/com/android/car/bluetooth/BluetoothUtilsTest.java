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

package com.android.car.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.os.ParcelUuid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RunWith(MockitoJUnitRunner.class)
public class BluetoothUtilsTest {
    static final String TEST_LOCAL_ADDRESS_STRING = "00:11:22:33:44:55";
    static final byte[] TEST_LOCAL_ADDRESS = {0x00, 0x11, 0x22, 0x33, 0x44, 0x55};

    private static final ParcelUuid[] EMPTY_UUIDS = new ParcelUuid[]{};
    private static final ParcelUuid[] A2DP_SOURCE_UUIDS =
            new ParcelUuid[]{BluetoothUuid.A2DP_SOURCE};
    private static final ParcelUuid[] A2DP_SINK_UUIDS =
            new ParcelUuid[]{BluetoothUuid.A2DP_SINK};
    private static final ParcelUuid[] HFP_HF_UUIDS =
            new ParcelUuid[]{BluetoothUuid.HFP};
    private static final ParcelUuid[] HFP_AG_UUIDS =
            new ParcelUuid[]{BluetoothUuid.HFP_AG, BluetoothUuid.HSP_AG};
    private static final ParcelUuid[] MAP_CLIENT_UUIDS =
            new ParcelUuid[]{BluetoothUuid.MAP, BluetoothUuid.MNS};
    private static final ParcelUuid[] MAP_SERVER_UUIDS =
            new ParcelUuid[]{BluetoothUuid.MAS};
    private static final ParcelUuid[] PAN_UUIDS =
            new ParcelUuid[]{BluetoothUuid.PANU, BluetoothUuid.NAP};
    private static final ParcelUuid[] PBAP_CLIENT_UUIDS =
            new ParcelUuid[]{BluetoothUuid.PBAP_PCE};
    private static final ParcelUuid[] PBAP_SERVER_UUIDS =
            new ParcelUuid[]{BluetoothUuid.PBAP_PSE};

    private static final ParcelUuid[] LE_AUDIO_UUIDS =
            new ParcelUuid[]{BluetoothUuid.LE_AUDIO};
    private static final ParcelUuid[] LE_AUDIO_BROADCAST_ASSISTANT_UUIDS =
            new ParcelUuid[]{BluetoothUuid.BASS};
    private static final ParcelUuid[] VOLUME_RENDERER_UUIDS =
            new ParcelUuid[]{BluetoothUuid.VOLUME_CONTROL};
    private static final ParcelUuid[] CSIP_SET_MEMBER_UUIDS =
            new ParcelUuid[]{BluetoothUuid.COORDINATED_SET};

    // Note that AVRCP's UUIDS are not used by our logic here at all as the connections to AVRCP are
    // entirely handled by the native code as an extension of A2DP connections. This should suffice
    // as a UUID that's real but will never impact profile support logic in the Car Framework.
    private static final ParcelUuid[] WRONG_UUIDS =
            new ParcelUuid[]{BluetoothUuid.AVRCP_CONTROLLER};

    private static final List<Integer> SUPPORTED_CLASSIC_PROFILES = Arrays.asList(
            BluetoothProfile.A2DP,
            BluetoothProfile.A2DP_SINK,
            BluetoothProfile.AVRCP_CONTROLLER,
            BluetoothProfile.GATT,
            BluetoothProfile.GATT_SERVER,
            BluetoothProfile.PBAP_CLIENT,
            BluetoothProfile.MAP_CLIENT,
            BluetoothProfile.HEADSET_CLIENT
    );
    private static final List<Integer> SUPPORTED_LE_AUDIO_PROFILES =
            Arrays.asList(BluetoothProfile.LE_AUDIO);
    private static final List<Integer> SUPPORTED_LE_AUDIO_BROADCAST_ASSISTANT_PROFILES =
            Arrays.asList(BluetoothProfile.LE_AUDIO, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);
    private static final List<Integer> SUPPORTED_CSIP_SET_COORDINATOR_PROFILES =
            Arrays.asList(BluetoothProfile.CSIP_SET_COORDINATOR);
    private static final List<Integer> SUPPORTED_VCP_CONTROLLER_PROFILES =
            Arrays.asList(BluetoothProfile.VOLUME_CONTROL);

    @Mock
    private BluetoothDevice mMockBluetoothDevice;

    @Mock
    private BluetoothAdapter mMockBluetoothAdapter;

    @Test
    public void testGetDeviceDebugInfo() {
        when(mMockBluetoothDevice.getName()).thenReturn("deviceName");
        when(mMockBluetoothDevice.getAddress()).thenReturn("deviceAddress");

        assertThat(BluetoothUtils.getDeviceDebugInfo(mMockBluetoothDevice))
                .isEqualTo("(name = deviceName, addr = deviceAddress)");
    }

    @Test
    public void testGetBytesFromAddress() {
        byte[] conversionResults = BluetoothUtils.getBytesFromAddress(TEST_LOCAL_ADDRESS_STRING);
        assertThat(conversionResults).isEqualTo(TEST_LOCAL_ADDRESS);
    }

    @Test
    public void testGetDeviceDebugInfo_nullDevice() {
        assertThat(BluetoothUtils.getDeviceDebugInfo(null)).isEqualTo("(null)");
    }

    @Test
    public void testIsA2dpSourceProfileSupportedBothSupported_returnsTrue() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(A2DP_SOURCE_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(A2DP_SINK_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.A2DP)).isTrue();
    }

    @Test
    public void testIsA2dpSourceProfileSupportedRemoteSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(A2DP_SINK_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.A2DP)).isFalse();
    }

    @Test
    public void testIsA2dpSourceProfileSupportedLocalSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(A2DP_SOURCE_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.A2DP)).isFalse();
    }

    @Test
    public void testIsA2dpSourceProfileSupportedBothUnsupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.A2DP)).isFalse();
    }

    @Test
    public void testIsA2dpSourceProfileSupportedBothSupportSameRole_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(A2DP_SOURCE_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(A2DP_SOURCE_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.A2DP)).isFalse();
    }

    @Test
    public void testIsA2dpSinkProfileSupportedBothSupported_returnsTrue() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(A2DP_SINK_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(A2DP_SOURCE_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.A2DP_SINK)).isTrue();
    }

    @Test
    public void testIsA2dpSinkProfileSupportedRemoteSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(A2DP_SOURCE_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.A2DP_SINK)).isFalse();
    }

    @Test
    public void testIsA2dpSinkProfileSupportedLocalSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(A2DP_SINK_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.A2DP_SINK)).isFalse();
    }

    @Test
    public void testIsA2dpSinkProfileSupportedBothUnsupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.A2DP_SINK)).isFalse();
    }

    @Test
    public void testIsA2dpSinkProfileSupportedBothSupportSameRole_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(A2DP_SINK_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(A2DP_SINK_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.A2DP_SINK)).isFalse();
    }

    @Test
    public void testIsHfpHfProfileSupportedBothSupported_returnsTrue() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(HFP_HF_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(HFP_AG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.HEADSET_CLIENT)).isTrue();
    }

    @Test
    public void testIsHfpHfProfileSupportedRemoteSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(HFP_AG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.HEADSET_CLIENT)).isFalse();
    }

    @Test
    public void testIsHfpHfProfileSupportedLocalSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(HFP_HF_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.HEADSET_CLIENT)).isFalse();
    }

    @Test
    public void testIsHfpHfProfileSupportedBothUnsupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.HEADSET_CLIENT)).isFalse();
    }

    @Test
    public void testIsHfpHfProfileSupportedSameRoleSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(HFP_HF_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(HFP_HF_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.HEADSET_CLIENT)).isFalse();
    }

    @Test
    public void testIsMapClientProfileSupportedBothSupported_returnsTrue() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(MAP_CLIENT_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(MAP_SERVER_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.MAP_CLIENT)).isTrue();
    }

    @Test
    public void testIsMapClientProfileSupportedRemoteSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(MAP_SERVER_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.MAP_CLIENT)).isFalse();
    }

    @Test
    public void testIsMapClientProfileSupportedLocalSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(MAP_CLIENT_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.MAP_CLIENT)).isFalse();
    }

    @Test
    public void testIsMapClientProfileSupportedBothUnsupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.MAP_CLIENT)).isFalse();
    }

    @Test
    public void testIsMapClientProfileSupportedSameRoleSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(MAP_CLIENT_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(MAP_CLIENT_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.MAP_CLIENT)).isFalse();
    }

    @Test
    public void testIsPanProfileSupportedBothSupported_returnsTrue() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(PAN_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(PAN_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.PAN)).isTrue();
    }

    @Test
    public void testIsPanProfileSupportedRemoteSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(PAN_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.PAN)).isFalse();
    }

    @Test
    public void testIsPanProfileSupportedLocalSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(PAN_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.PAN)).isFalse();
    }

    @Test
    public void testIsPanProfileSupportedBothUnsupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.PAN)).isFalse();
    }

    @Test
    public void testIsPbapClientProfileSupportedBothSupported_returnsTrue() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(PBAP_CLIENT_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(PBAP_SERVER_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.PBAP_CLIENT)).isTrue();
    }

    @Test
    public void testIsPbapClientProfileSupportedRemoteSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(PBAP_SERVER_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.PBAP_CLIENT)).isFalse();
    }

    @Test
    public void testIsPbapClientProfileSupportedLocalSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(PBAP_CLIENT_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.PBAP_CLIENT)).isFalse();
    }

    @Test
    public void testIsPbapClientProfileSupportedBothUnsupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.PBAP_CLIENT)).isFalse();
    }

    @Test
    public void testIsPbapClientProfileSupportedSameRoleSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(PBAP_CLIENT_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(PBAP_CLIENT_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.PBAP_CLIENT)).isFalse();
    }

    @Test
    public void testIsLeAudioProfileSupportedBothSupported_returnsTrue() {
        List<Integer> profiles = Stream.concat(
                SUPPORTED_CLASSIC_PROFILES.stream(),
                SUPPORTED_LE_AUDIO_PROFILES.stream())
                .distinct()
                .collect(Collectors.toList());
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(profiles);
        when(mMockBluetoothDevice.getUuids()).thenReturn(LE_AUDIO_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.LE_AUDIO)).isTrue();
    }

    @Test
    public void testIsLeAudioProfileSupportedLocalSupported_returnsFalse() {
        List<Integer> profiles = Stream.concat(
                SUPPORTED_CLASSIC_PROFILES.stream(),
                SUPPORTED_LE_AUDIO_PROFILES.stream())
                .distinct()
                .collect(Collectors.toList());
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(profiles);
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.LE_AUDIO)).isFalse();
    }

    @Test
    public void testIsLeAudioProfileSupportedRemoteSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(SUPPORTED_CLASSIC_PROFILES);
        when(mMockBluetoothDevice.getUuids()).thenReturn(LE_AUDIO_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.LE_AUDIO)).isFalse();
    }

    @Test
    public void testIsLeAudioProfileSupportedBothUnsupported_returnsFalse() {
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(SUPPORTED_CLASSIC_PROFILES);
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.LE_AUDIO)).isFalse();
    }

    @Test
    public void testIsLeAudioBroadcastAssistantProfileSupportedBothSupported_returnsTrue() {
        List<Integer> profiles = Stream.concat(
                SUPPORTED_CLASSIC_PROFILES.stream(),
                SUPPORTED_LE_AUDIO_BROADCAST_ASSISTANT_PROFILES.stream())
                .distinct()
                .collect(Collectors.toList());
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(profiles);
        when(mMockBluetoothDevice.getUuids()).thenReturn(LE_AUDIO_BROADCAST_ASSISTANT_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)).isTrue();
    }

    @Test
    public void testIsLeAudioBroadcastAssistantProfileSupportedLocalSupported_returnsFalse() {
        List<Integer> profiles = Stream.concat(
                SUPPORTED_CLASSIC_PROFILES.stream(),
                SUPPORTED_LE_AUDIO_BROADCAST_ASSISTANT_PROFILES.stream())
                .distinct()
                .collect(Collectors.toList());
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(profiles);
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)).isFalse();
    }

    @Test
    public void testIsLeAudioBroadcastAssistantProfileSupportedRemoteSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(SUPPORTED_CLASSIC_PROFILES);
        when(mMockBluetoothDevice.getUuids()).thenReturn(LE_AUDIO_BROADCAST_ASSISTANT_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)).isFalse();
    }

    @Test
    public void testIsLeAudioBroadcastAssistantProfileSupportedBothUnsupported_returnsFalse() {
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(SUPPORTED_CLASSIC_PROFILES);
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)).isFalse();
    }

    @Test
    public void testIsCsipSetCoordinatorProfileSupportedBothSupported_returnsTrue() {
        List<Integer> profiles = Stream.concat(
                SUPPORTED_CLASSIC_PROFILES.stream(),
                SUPPORTED_CSIP_SET_COORDINATOR_PROFILES.stream())
                .distinct()
                .collect(Collectors.toList());
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(profiles);
        when(mMockBluetoothDevice.getUuids()).thenReturn(CSIP_SET_MEMBER_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.CSIP_SET_COORDINATOR)).isTrue();
    }

    @Test
    public void testIsCsipSetCoordinatorProfileSupportedLocalSupported_returnsFalse() {
        List<Integer> profiles = Stream.concat(
                SUPPORTED_CLASSIC_PROFILES.stream(),
                SUPPORTED_CSIP_SET_COORDINATOR_PROFILES.stream())
                .distinct()
                .collect(Collectors.toList());
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(profiles);
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.CSIP_SET_COORDINATOR)).isFalse();
    }

    @Test
    public void testIsCsipSetCoordinatorProfileSupportedRemoteSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(SUPPORTED_CLASSIC_PROFILES);
        when(mMockBluetoothDevice.getUuids()).thenReturn(CSIP_SET_MEMBER_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.CSIP_SET_COORDINATOR)).isFalse();
    }

    @Test
    public void testIsCsipSetCoordinatorProfileSupportedBothUnsupported_returnsFalse() {
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(SUPPORTED_CLASSIC_PROFILES);
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.CSIP_SET_COORDINATOR)).isFalse();
    }

    @Test
    public void testIsVolumeControlControllerProfileSupportedBothSupported_returnsTrue() {
        List<Integer> profiles = Stream.concat(
                SUPPORTED_CLASSIC_PROFILES.stream(),
                SUPPORTED_VCP_CONTROLLER_PROFILES.stream())
                .distinct()
                .collect(Collectors.toList());
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(profiles);
        when(mMockBluetoothDevice.getUuids()).thenReturn(VOLUME_RENDERER_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.VOLUME_CONTROL)).isTrue();
    }

    @Test
    public void testIsVolumeControlControllerProfileSupportedLocalSupported_returnsFalse() {
        List<Integer> profiles = Stream.concat(
                SUPPORTED_CLASSIC_PROFILES.stream(),
                SUPPORTED_VCP_CONTROLLER_PROFILES.stream())
                .distinct()
                .collect(Collectors.toList());
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(profiles);
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.VOLUME_CONTROL)).isFalse();
    }

    @Test
    public void testIsVolumeControlControllerProfileSupportedRemoteSupported_returnsFalse() {
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(SUPPORTED_CLASSIC_PROFILES);
        when(mMockBluetoothDevice.getUuids()).thenReturn(VOLUME_RENDERER_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.VOLUME_CONTROL)).isFalse();
    }

    @Test
    public void testIsVolumeControlControllerProfileSupportedBothUnsupported_returnsFalse() {
        when(mMockBluetoothAdapter.getSupportedProfiles()).thenReturn(SUPPORTED_CLASSIC_PROFILES);
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.VOLUME_CONTROL)).isFalse();
    }

    @Test
    public void testIsProfileSupportedNullAdapter_returnsFalse() {
        assertThat(BluetoothUtils.isProfileSupported(null,
                mMockBluetoothDevice, BluetoothProfile.HEADSET_CLIENT)).isFalse();
    }

    @Test
    public void testIsProfileSupportedNullDevice_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                null, BluetoothProfile.HEADSET_CLIENT)).isFalse();
    }

    @Test
    public void testIsProfileSupportedNullRemoteUuids_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(null);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.HEADSET_CLIENT)).isFalse();
    }

    @Test
    public void testIsProfileSupportedEmptyRemoteUuids_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(WRONG_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(EMPTY_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.HEADSET_CLIENT)).isFalse();
    }

    @Test
    public void testIsProfileSupportedNullLocalUuids_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(null);
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter, mMockBluetoothDevice,
                BluetoothProfile.HEADSET_CLIENT)).isFalse();
    }

    @Test
    public void testIsProfileSupportedEmptyLocalUuids_returnsFalse() {
        when(mMockBluetoothAdapter.getUuidsList()).thenReturn(Arrays.asList(EMPTY_UUIDS));
        when(mMockBluetoothDevice.getUuids()).thenReturn(WRONG_UUIDS);
        assertThat(BluetoothUtils.isProfileSupported(mMockBluetoothAdapter,
                mMockBluetoothDevice, BluetoothProfile.HEADSET_CLIENT)).isFalse();
    }
}
