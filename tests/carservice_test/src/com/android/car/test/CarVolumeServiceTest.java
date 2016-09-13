/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.car.test;

import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.media.CarAudioManager;
import android.car.test.VehicleHalEmulator;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.IVolumeController;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Pair;
import android.util.SparseIntArray;
import android.view.KeyEvent;

import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioExtFocusFlag;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusState;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehiclePropConfigUtil;
import com.android.car.vehiclenetwork.VehiclePropValueUtil;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;

public class CarVolumeServiceTest extends MockedCarTestBase {
    private static final int MIN_VOL = 1;
    private static final int MAX_VOL = 20;
    private static final long TIMEOUT_MS = 3000;
    private static final long POLL_INTERVAL_MS = 50;

    private static final int[] LOGICAL_STREAMS = {
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_DTMF,
    };

    private CarAudioManager mCarAudioManager;
    private AudioManager mAudioManager;

    @Override
    protected synchronized void setUp() throws Exception {
        super.setUp();
        // AudioManager should be created in main thread to get focus event. :(
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            }
        });

        List<Integer> mins = new ArrayList<>();
        List<Integer> maxs = new ArrayList<>();
        mins.add(MIN_VOL);
        mins.add(MIN_VOL);

        maxs.add(MAX_VOL);
        maxs.add(MAX_VOL);

        // TODO: add tests for audio context supported cases.
        startVolumeEmulation(0 /*supported audio context*/, maxs, mins);
        mCarAudioManager = (CarAudioManager) getCar().getCarManager(Car.AUDIO_SERVICE);
    }

    public void testVolumeLimits() throws Exception {
        for (int stream : LOGICAL_STREAMS) {
            assertEquals(MIN_VOL, mCarAudioManager.getStreamMinVolume(stream));
            assertEquals(MAX_VOL, mCarAudioManager.getStreamMaxVolume(stream));
        }
    }

    public void testVolumeSet() {
        try {
            int callVol = 10;
            int musicVol = 15;
            mCarAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, musicVol, 0);
            mCarAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, callVol, 0);

            volumeVerificationPoll(createStreamVolPair(AudioManager.STREAM_MUSIC, musicVol),
                    createStreamVolPair(AudioManager.STREAM_VOICE_CALL, callVol));

            musicVol = MAX_VOL + 1;
            mCarAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, musicVol, 0);

            volumeVerificationPoll(createStreamVolPair(AudioManager.STREAM_MUSIC, MAX_VOL),
                    createStreamVolPair(AudioManager.STREAM_VOICE_CALL, callVol));
        } catch (CarNotConnectedException e) {
            fail("Car not connected");
        }
    }

    public void testSuppressVolumeUI() {
        try {
            VolumeController volumeController = new VolumeController();
            mCarAudioManager.setVolumeController(volumeController);

            // first give focus to system sound
            CarAudioFocusTest.AudioFocusListener listenerMusic =
                    new CarAudioFocusTest.AudioFocusListener();
            int res = mAudioManager.requestAudioFocus(listenerMusic,
                    AudioManager.STREAM_SYSTEM,
                    AudioManager.AUDIOFOCUS_GAIN);
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, res);
            int[] request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
            mAudioFocusPropertyHandler.sendAudioFocusState(
                    VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN,
                    request[1],
                    VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);

            // focus gives to Alarm, there should be a audio context change.
            CarAudioFocusTest.AudioFocusListener listenerAlarm = new
                    CarAudioFocusTest.AudioFocusListener();
            AudioAttributes callAttrib = (new AudioAttributes.Builder()).
                    setUsage(AudioAttributes.USAGE_ALARM).
                    build();
            mAudioManager.requestAudioFocus(listenerAlarm, callAttrib,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, 0);
            request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
            mAudioFocusPropertyHandler.sendAudioFocusState(
                    VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN, request[1],
                    VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);
            // should not show UI
            volumeChangeVerificationPoll(AudioManager.STREAM_ALARM, false, volumeController);

            int alarmVol = mCarAudioManager.getStreamVolume(AudioManager.STREAM_ALARM);
            // set alarm volume with show_ui flag and a different volume
            mCarAudioManager.setStreamVolume(AudioManager.STREAM_ALARM,
                    (alarmVol + 1) % mCarAudioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    AudioManager.FLAG_SHOW_UI);
            // should show ui
            volumeChangeVerificationPoll(AudioManager.STREAM_ALARM, true, volumeController);
            mAudioManager.abandonAudioFocus(listenerAlarm);
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    public void testVolumeKeys() throws Exception {
        try {
            int musicVol = 10;
            mCarAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, musicVol, 0);
            int callVol = 12;
            mCarAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, callVol, 0);

            CarAudioFocusTest.AudioFocusListener listenerMusic =
                    new CarAudioFocusTest.AudioFocusListener();
            int res = mAudioManager.requestAudioFocus(listenerMusic,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, res);
            int[] request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
            mAudioFocusPropertyHandler.sendAudioFocusState(
                    VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN,
                    request[1],
                    VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);


            assertEquals(musicVol, mCarAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
            sendVolumeKey(true /*vol up*/);
            musicVol++;
            volumeVerificationPoll(createStreamVolPair(AudioManager.STREAM_MUSIC, musicVol));

            // call start
            CarAudioFocusTest.AudioFocusListener listenerCall = new
                    CarAudioFocusTest.AudioFocusListener();
            AudioAttributes callAttrib = (new AudioAttributes.Builder()).
                    setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).
                    build();
            mAudioManager.requestAudioFocus(listenerCall, callAttrib,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, 0);
            request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
            mAudioFocusPropertyHandler.sendAudioFocusState(
                    VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN, request[1],
                    VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);

            sendVolumeKey(true /*vol up*/);
            callVol++;
            volumeVerificationPoll(createStreamVolPair(AudioManager.STREAM_MUSIC, musicVol),
                    createStreamVolPair(AudioManager.STREAM_VOICE_CALL, callVol));
        } catch (CarNotConnectedException | InterruptedException e) {
            fail(e.toString());
        }
    }

    private Pair<Integer, Integer> createStreamVolPair(int stream, int vol) {
        return new Pair<>(stream, vol);
    }

    private void volumeVerificationPoll(Pair<Integer, Integer>... expectedStreamVolPairs) {
        boolean isVolExpected = false;
        int timeElapsedMs = 0;
        try {
            while (!isVolExpected && timeElapsedMs <= TIMEOUT_MS) {
                Thread.sleep(POLL_INTERVAL_MS);
                isVolExpected = true;
                for (Pair<Integer, Integer> vol : expectedStreamVolPairs) {
                    if (mCarAudioManager.getStreamVolume(vol.first) != vol.second) {
                        isVolExpected = false;
                        break;
                    }
                }
                timeElapsedMs += POLL_INTERVAL_MS;
            }
            assertEquals(isVolExpected, true);
        } catch (InterruptedException | CarNotConnectedException e) {
            fail(e.toString());
        }
    }

    private void volumeChangeVerificationPoll(int stream, boolean showUI,
            VolumeController controller) {
        boolean isVolExpected = false;
        int timeElapsedMs = 0;
        try {
            while (!isVolExpected && timeElapsedMs <= TIMEOUT_MS) {
                Thread.sleep(POLL_INTERVAL_MS);
                Pair<Integer, Integer> volChange = controller.getLastVolumeChanges();
                if (volChange.first == stream
                        && (((volChange.second.intValue() & AudioManager.FLAG_SHOW_UI) != 0)
                        == showUI)) {
                    isVolExpected = true;
                    break;
                }
                timeElapsedMs += POLL_INTERVAL_MS;
            }
            assertEquals(true, isVolExpected);
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    private class SingleChannelVolumeHandler implements
            VehicleHalEmulator.VehicleHalPropertyHandler {
        private final List<Integer> mMins;
        private final List<Integer> mMaxs;
        private final SparseIntArray mCurrent;

        public SingleChannelVolumeHandler(List<Integer> mins, List<Integer> maxs) {
            assertEquals(mins.size(), maxs.size());
            mMins = mins;
            mMaxs = maxs;
            mCurrent = new SparseIntArray(mMins.size());
            // initialize the vol to be the min volume.
            for (int i = 0; i < mMins.size(); i++) {
                mCurrent.put(i, mMins.get(i));
            }
        }

        @Override
        public void onPropertySet(VehiclePropValue value) {
            int stream = value.getInt32Values(
                    VehicleNetworkConsts.VehicleAudioVolumeIndex.VEHICLE_AUDIO_VOLUME_INDEX_STREAM);
            int volume = value.getInt32Values(
                    VehicleNetworkConsts.VehicleAudioVolumeIndex.VEHICLE_AUDIO_VOLUME_INDEX_VOLUME);
            int state = value.getInt32Values(
                    VehicleNetworkConsts.VehicleAudioVolumeIndex.VEHICLE_AUDIO_VOLUME_INDEX_STATE);

            int[] values = {stream, volume, state};
            VehiclePropValue injectValue = VehiclePropValueUtil.createIntVectorValue(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME, values,
                    SystemClock.elapsedRealtimeNanos());
            mCurrent.put(stream, volume);
            getVehicleHalEmulator().injectEvent(injectValue);
        }

        @Override
        public VehiclePropValue onPropertyGet(VehiclePropValue value) {
            int stream = value.getInt32Values(
                    VehicleNetworkConsts.VehicleAudioVolumeIndex.VEHICLE_AUDIO_VOLUME_INDEX_STREAM);

            int volume = mCurrent.get(stream);
            int[] values = {stream, volume, 0};
            VehiclePropValue propValue = VehiclePropValueUtil.createIntVectorValue(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME, values,
                    SystemClock.elapsedRealtimeNanos());
            return propValue;
        }

        @Override
        public void onPropertySubscribe(int property, float sampleRate, int zones) {
        }

        @Override
        public void onPropertyUnsubscribe(int property) {
        }
    }

    private final CarAudioFocusTest.FocusPropertyHandler mAudioFocusPropertyHandler =
            new CarAudioFocusTest.FocusPropertyHandler(this);

    private final VehicleHalEmulator.VehicleHalPropertyHandler mAudioRoutingPolicyPropertyHandler =
            new VehicleHalEmulator.VehicleHalPropertyHandler() {
                @Override
                public void onPropertySet(VehiclePropValue value) {
                    //TODO
                }

                @Override
                public VehiclePropValue onPropertyGet(VehiclePropValue value) {
                    fail("cannot get");
                    return null;
                }

                @Override
                public void onPropertySubscribe(int property, float sampleRate, int zones) {
                    fail("cannot subscribe");
                }

                @Override
                public void onPropertyUnsubscribe(int property) {
                    fail("cannot unsubscribe");
                }
            };

    private final VehicleHalEmulator.VehicleHalPropertyHandler mHWKeyHandler =
            new VehicleHalEmulator.VehicleHalPropertyHandler() {
                @Override
                public void onPropertySet(VehiclePropValue value) {
                    //TODO
                }

                @Override
                public VehiclePropValue onPropertyGet(VehiclePropValue value) {
                    int[] values = {0, 0, 0, 0 };
                    return VehiclePropValueUtil.createIntVectorValue(
                            VehicleNetworkConsts.VEHICLE_PROPERTY_HW_KEY_INPUT, values,
                            SystemClock.elapsedRealtimeNanos());
                }

                @Override
                public void onPropertySubscribe(int property, float sampleRate, int zones) {
                    //
                }

                @Override
                public void onPropertyUnsubscribe(int property) {
                    //
                }
            };

    private void startVolumeEmulation(int supportedAudioVolumeContext,
                                      List<Integer> maxs, List<Integer> mins) {
        SingleChannelVolumeHandler singleChannelVolumeHandler =
                new SingleChannelVolumeHandler(mins, maxs);
        int zones = (1<<maxs.size()) - 1;
        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.getBuilder(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_VOLUME,
                        VehicleNetworkConsts.VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehicleNetworkConsts.VehiclePropChangeMode
                                .VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleNetworkConsts.VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC3,
                        VehicleNetworkConsts.VehiclePermissionModel
                                .VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        supportedAudioVolumeContext /*configFlags*/,
                        0 /*sampleRateMax*/, 0 /*sampleRateMin*/,
                        maxs, mins).setZones(zones).build(),
                singleChannelVolumeHandler);

        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.getBuilder(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_HW_KEY_INPUT,
                        VehicleNetworkConsts.VehiclePropAccess.VEHICLE_PROP_ACCESS_READ,
                        VehicleNetworkConsts.VehiclePropChangeMode
                                .VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleNetworkConsts.VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC4,
                        VehicleNetworkConsts.VehiclePermissionModel
                                .VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/).build(),
                mHWKeyHandler);

        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.getBuilder(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS,
                        VehicleNetworkConsts.VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehicleNetworkConsts.VehiclePropChangeMode
                                .VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleNetworkConsts.VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC4,
                        VehicleNetworkConsts.VehiclePermissionModel
                                .VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/).build(),
                mAudioFocusPropertyHandler);
        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.getBuilder(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_ROUTING_POLICY,
                        VehicleNetworkConsts.VehiclePropAccess.VEHICLE_PROP_ACCESS_WRITE,
                        VehicleNetworkConsts.VehiclePropChangeMode
                                .VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleNetworkConsts.VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC2,
                        VehicleNetworkConsts.VehiclePermissionModel
                                .VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/).build(),
                mAudioRoutingPolicyPropertyHandler);

        getVehicleHalEmulator().addStaticProperty(
                VehiclePropConfigUtil.createStaticStringProperty(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_HW_VARIANT),
                VehiclePropValueUtil.createIntValue(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_HW_VARIANT, 1, 0));

        getVehicleHalEmulator().start();
    }

    public void sendVolumeKey(boolean volUp) {
        int[] values = {
                VehicleNetworkConsts.VehicleHwKeyInputAction.VEHICLE_HW_KEY_INPUT_ACTION_DOWN,
                volUp ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0 };

        VehiclePropValue injectValue = VehiclePropValueUtil.createIntVectorValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_HW_KEY_INPUT, values,
                SystemClock.elapsedRealtimeNanos());

        getVehicleHalEmulator().injectEvent(injectValue);

        int[] upValues = {
                VehicleNetworkConsts.VehicleHwKeyInputAction.VEHICLE_HW_KEY_INPUT_ACTION_UP,
                volUp ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0 };

        injectValue = VehiclePropValueUtil.createIntVectorValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_HW_KEY_INPUT, upValues,
                SystemClock.elapsedRealtimeNanos());

        getVehicleHalEmulator().injectEvent(injectValue);
    }

    private static class VolumeController extends IVolumeController.Stub {
        @GuardedBy("this")
        private int mLastStreamChanged = -1;

        @GuardedBy("this")
        private int mLastFlags = -1;

        public synchronized Pair<Integer, Integer> getLastVolumeChanges() {
            return new Pair<>(mLastStreamChanged, mLastFlags);
        }

        @Override
        public void displaySafeVolumeWarning(int flags) throws RemoteException {}

        @Override
        public void volumeChanged(int streamType, int flags) throws RemoteException {
            synchronized (this) {
                mLastStreamChanged = streamType;
                mLastFlags = flags;
            }
        }

        @Override
        public void masterMuteChanged(int flags) throws RemoteException {}

        @Override
        public void setLayoutDirection(int layoutDirection) throws RemoteException {
        }

        @Override
        public void dismiss() throws RemoteException {
        }
    }
}
