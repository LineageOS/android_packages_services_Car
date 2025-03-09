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

import static android.media.AudioDeviceInfo.TYPE_AUX_LINE;
import static android.media.AudioDeviceInfo.TYPE_BLE_BROADCAST;
import static android.media.AudioDeviceInfo.TYPE_BLE_HEADSET;
import static android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER;
import static android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
import static android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
import static android.media.AudioDeviceInfo.TYPE_BUS;
import static android.media.AudioDeviceInfo.TYPE_HDMI;
import static android.media.AudioDeviceInfo.TYPE_HDMI_ARC;
import static android.media.AudioDeviceInfo.TYPE_HDMI_EARC;
import static android.media.AudioDeviceInfo.TYPE_HEARING_AID;
import static android.media.AudioDeviceInfo.TYPE_IP;
import static android.media.AudioDeviceInfo.TYPE_LINE_ANALOG;
import static android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL;
import static android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY;
import static android.media.AudioDeviceInfo.TYPE_USB_DEVICE;
import static android.media.AudioDeviceInfo.TYPE_USB_HEADSET;
import static android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
import static android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET;
import static android.media.audio.common.AudioDeviceDescription.CONNECTION_ANALOG;
import static android.media.audio.common.AudioDeviceDescription.CONNECTION_BT_A2DP;
import static android.media.audio.common.AudioDeviceDescription.CONNECTION_BT_LE;
import static android.media.audio.common.AudioDeviceDescription.CONNECTION_BT_SCO;
import static android.media.audio.common.AudioDeviceDescription.CONNECTION_HDMI;
import static android.media.audio.common.AudioDeviceDescription.CONNECTION_HDMI_ARC;
import static android.media.audio.common.AudioDeviceDescription.CONNECTION_HDMI_EARC;
import static android.media.audio.common.AudioDeviceDescription.CONNECTION_IP_V4;
import static android.media.audio.common.AudioDeviceDescription.CONNECTION_SPDIF;
import static android.media.audio.common.AudioDeviceDescription.CONNECTION_USB;
import static android.media.audio.common.AudioDeviceType.OUT_ACCESSORY;
import static android.media.audio.common.AudioDeviceType.OUT_BROADCAST;
import static android.media.audio.common.AudioDeviceType.OUT_DEVICE;
import static android.media.audio.common.AudioDeviceType.OUT_HEADPHONE;
import static android.media.audio.common.AudioDeviceType.OUT_HEADSET;
import static android.media.audio.common.AudioDeviceType.OUT_HEARING_AID;
import static android.media.audio.common.AudioDeviceType.OUT_LINE_AUX;
import static android.media.audio.common.AudioDeviceType.OUT_SPEAKER;

import android.car.test.AbstractExpectableTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@RunWith(Parameterized.class)
public class ConvertToAudioDeviceInfoTypeTest extends AbstractExpectableTestCase {

    private final int mNativeType;
    private final String mConnection;
    private final int mInternalType;

    @Parameterized.Parameters(name = "Native type {0} and connection {1} -> external type {2}")
    public static Collection provideParams() {
        List<Object[]> params = new ArrayList<>();
        // Speakers type
        params.add(new Object[]{OUT_SPEAKER, CONNECTION_BT_A2DP, TYPE_BLUETOOTH_A2DP});
        params.add(new Object[]{OUT_SPEAKER, CONNECTION_BT_LE, TYPE_BLE_SPEAKER});
        params.add(new Object[]{OUT_SPEAKER, /* connection= */ "", TYPE_BUILTIN_SPEAKER});
        // Headphones type
        params.add(new Object[]{OUT_HEADPHONE, CONNECTION_ANALOG, TYPE_WIRED_HEADPHONES});
        params.add(new Object[]{OUT_HEADPHONE, /* connection= */ "", TYPE_BLUETOOTH_A2DP});
        // Headsets type
        params.add(new Object[]{OUT_HEADSET, CONNECTION_ANALOG, TYPE_WIRED_HEADSET});
        params.add(new Object[]{OUT_HEADSET, CONNECTION_BT_LE, TYPE_BLE_HEADSET});
        params.add(new Object[]{OUT_HEADSET, CONNECTION_BT_SCO, TYPE_BLUETOOTH_SCO});
        params.add(new Object[]{OUT_HEADSET, CONNECTION_USB, TYPE_USB_HEADSET});
        // Accessory type
        params.add(new Object[]{OUT_ACCESSORY, /* connection= */ "", TYPE_USB_ACCESSORY});
        // Auxiliary type
        params.add(new Object[]{OUT_LINE_AUX, /* connection= */ "", TYPE_AUX_LINE});
        // Broadcast type
        params.add(new Object[]{OUT_BROADCAST, /* connection= */ "", TYPE_BLE_BROADCAST});
        // Hearing Aid type
        params.add(new Object[]{OUT_HEARING_AID, /* connection= */ "", TYPE_HEARING_AID});
        // Generic out device type
        params.add(new Object[]{OUT_DEVICE, CONNECTION_BT_A2DP, TYPE_BLUETOOTH_A2DP});
        params.add(new Object[]{OUT_DEVICE, CONNECTION_IP_V4, TYPE_IP});
        params.add(new Object[]{OUT_DEVICE, CONNECTION_HDMI_ARC, TYPE_HDMI_ARC});
        params.add(new Object[]{OUT_DEVICE, CONNECTION_HDMI_EARC, TYPE_HDMI_EARC});
        params.add(new Object[]{OUT_DEVICE, CONNECTION_HDMI, TYPE_HDMI});
        params.add(new Object[]{OUT_DEVICE, CONNECTION_ANALOG, TYPE_LINE_ANALOG});
        params.add(new Object[]{OUT_DEVICE, CONNECTION_USB, TYPE_USB_DEVICE});
        params.add(new Object[]{OUT_DEVICE, CONNECTION_SPDIF, TYPE_LINE_DIGITAL});
        params.add(new Object[]{OUT_DEVICE, /* connection= */ "", TYPE_BUS});
        return params;
    }

    public ConvertToAudioDeviceInfoTypeTest(int nativeType, String connection, int internalType) {
        mNativeType = nativeType;
        mConnection = connection;
        mInternalType = internalType;
    }

    @Test
    public void convertToAudioDeviceInfoType() {
        expectWithMessage("Native type %s and connection %s device", mNativeType, mConnection)
                .that(AudioControlZoneConverterUtils.convertToAudioDeviceInfoType(mNativeType,
                        mConnection)).isEqualTo(mInternalType);
    }
}
