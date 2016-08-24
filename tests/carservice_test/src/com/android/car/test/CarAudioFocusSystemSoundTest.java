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
import android.car.media.CarAudioManager;
import android.car.test.VehicleHalEmulator.VehicleHalPropertyHandler;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.SystemClock;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;

import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioContextFlag;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioExtFocusFlag;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusIndex;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusRequest;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioFocusState;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioStream;
import com.android.car.vehiclenetwork.VehiclePropConfigUtil;
import com.android.car.vehiclenetwork.VehiclePropValueUtil;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePermissionModel;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropAccess;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropChangeMode;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;

import java.util.LinkedList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Test to check if system sound can be played without having focus.
 */
@MediumTest
public class CarAudioFocusSystemSoundTest extends MockedCarTestBase {
    private static final String TAG = CarAudioFocusTest.class.getSimpleName();

    private static final long TIMEOUT_MS = 3000;

    private final VehicleHalPropertyHandler mAudioRoutingPolicyPropertyHandler =
            new VehicleHalPropertyHandler() {
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

    private final FocusPropertyHandler mAudioFocusPropertyHandler =
            new FocusPropertyHandler(this);

    private final Semaphore mWaitSemaphore = new Semaphore(0);
    private final LinkedList<VehiclePropValue> mEvents = new LinkedList<VehiclePropValue>();
    private AudioManager mAudioManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // AudioManager should be created in main thread to get focus event. :(
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            }
        });

        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.getBuilder(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_ROUTING_POLICY,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC2,
                        VehiclePermissionModel.VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/).build(),
                        mAudioRoutingPolicyPropertyHandler);
        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.getBuilder(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC4,
                        VehiclePermissionModel.VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/).build(),
                        mAudioFocusPropertyHandler);
        getVehicleHalEmulator().addStaticProperty(
                VehiclePropConfigUtil.createStaticStringProperty(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_HW_VARIANT),
                VehiclePropValueUtil.createIntValue(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_HW_VARIANT, -1, 0));
        getVehicleHalEmulator().start();
    }

    private void notifyStreamState(int streamNumber, boolean active) {
        int[] values = { active ? 1 : 0, streamNumber };
        long now = SystemClock.elapsedRealtimeNanos();
        getVehicleHalEmulator().injectEvent(VehiclePropValueUtil.createIntVectorValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_INTERNAL_AUDIO_STREAM_STATE,values, now));
    }

    public void testSystemSoundPlayStop() throws Exception {
        //system sound start
        notifyStreamState(1, true);
        int[] request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT_NO_DUCK,
                request[0]);
        assertEquals(0x2, request[1]);
        assertEquals(0, request[2]);
        assertEquals(VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_SYSTEM_SOUND_FLAG, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
                request[1],
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);
        // system sound stop
        notifyStreamState(1, false);
        request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE,
                request[0]);
        assertEquals(0, request[1]);
        assertEquals(0, request[2]);
        assertEquals(0, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS,
                request[1],
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);
    }

    public void testRadioSystemSound() throws Exception {
        // radio start
        AudioFocusListener listenerRadio = new AudioFocusListener();
        CarAudioManager carAudioManager = (CarAudioManager) getCar().getCarManager(
                Car.AUDIO_SERVICE);
        assertNotNull(carAudioManager);
        AudioAttributes radioAttributes = carAudioManager.getAudioAttributesForCarUsage(
                CarAudioManager.CAR_AUDIO_USAGE_RADIO);
        int res = mAudioManager.requestAudioFocus(listenerRadio,
                radioAttributes, AudioManager.AUDIOFOCUS_GAIN, 0);
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, res);
        int[] request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN, request[0]);
        assertEquals(0, request[1]);
        assertEquals(VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_CAR_PLAY_ONLY_FLAG,
                request[2]);
        assertEquals(VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_RADIO_FLAG, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN,
                0,
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_CAR_PLAY_ONLY_FLAG);
        // system sound start
        notifyStreamState(1, true);
        request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN, request[0]);
        assertEquals(0x2, request[1]);
        assertEquals(VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_CAR_PLAY_ONLY_FLAG,
                request[2]);
        assertEquals(VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_RADIO_FLAG |
                VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_SYSTEM_SOUND_FLAG, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN,
                request[1],
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_CAR_PLAY_ONLY_FLAG);
        // system sound stop
        notifyStreamState(1, false);
        request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN, request[0]);
        assertEquals(0, request[1]);
        assertEquals(VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_CAR_PLAY_ONLY_FLAG,
                request[2]);
        assertEquals(VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_RADIO_FLAG, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN,
                0,
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_CAR_PLAY_ONLY_FLAG);
        // radio stop
        mAudioManager.abandonAudioFocus(listenerRadio);
        request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE, request[0]);
        assertEquals(0, request[1]);
        assertEquals(0, request[2]);
        assertEquals(0, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS,
                request[1],
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);
    }

    public void testMusicSystemSound() throws Exception {
        // music start
        AudioFocusListener listenerMusic = new AudioFocusListener();
        int res = mAudioManager.requestAudioFocus(listenerMusic,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, res);
        int[] request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN, request[0]);
        assertEquals(0x1 << VehicleAudioStream.VEHICLE_AUDIO_STREAM0, request[1]);
        assertEquals(0, request[2]);
        assertEquals(VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_MUSIC_FLAG, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN,
                request[1],
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);
        // system sound start
        notifyStreamState(1, true);
        request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN, request[0]);
        assertEquals(0x1 | 0x2, request[1]);
        assertEquals(0, request[2]);
        assertEquals(VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_MUSIC_FLAG |
                VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_SYSTEM_SOUND_FLAG, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN,
                request[1],
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);
        // system sound stop
        notifyStreamState(1, false);
        request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN, request[0]);
        assertEquals(0x1 << VehicleAudioStream.VEHICLE_AUDIO_STREAM0, request[1]);
        assertEquals(0, request[2]);
        assertEquals(VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_MUSIC_FLAG, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN,
                request[1],
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);
        // music stop
        mAudioManager.abandonAudioFocus(listenerMusic);
        request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE, request[0]);
        assertEquals(0, request[1]);
        assertEquals(0, request[2]);
        assertEquals(0, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS,
                request[1],
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);
    }

    public void testNavigationSystemSound() throws Exception {
        // nav guidance start
        AudioFocusListener listenerNav = new AudioFocusListener();
        AudioAttributes navAttrib = (new AudioAttributes.Builder()).
                setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).
                setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).
                build();
        int res = mAudioManager.requestAudioFocus(listenerNav, navAttrib,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, 0);
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, res);
        int[] request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT_MAY_DUCK,
                request[0]);
        assertEquals(0x2, request[1]);
        assertEquals(0, request[2]);
        assertEquals(VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_NAVIGATION_FLAG, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
                request[1],
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);
        // system sound start
        notifyStreamState(1, true);
        // cannot distinguish this case from nav only. so no focus change.
        mAudioFocusPropertyHandler.assertNoFocusRequest(1000);
        // cannot distinguish this case from nav only. so no focus change.
        notifyStreamState(1, false);
        mAudioFocusPropertyHandler.assertNoFocusRequest(1000);
        // nav guidance stop
        mAudioManager.abandonAudioFocus(listenerNav);
        request = mAudioFocusPropertyHandler.waitForAudioFocusRequest(TIMEOUT_MS);
        assertEquals(VehicleAudioFocusRequest.VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE, request[0]);
        assertEquals(0, request[1]);
        assertEquals(0, request[2]);
        assertEquals(0, request[3]);
        mAudioFocusPropertyHandler.sendAudioFocusState(
                VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS,
                request[1],
                VehicleAudioExtFocusFlag.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG);
    }

    protected static class AudioFocusListener implements AudioManager.OnAudioFocusChangeListener {
        private final Semaphore mFocusChangeWait = new Semaphore(0);
        private int mLastFocusChange;

        public int waitAndGetFocusChange(long timeoutMs) throws Exception {
            if (!mFocusChangeWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                fail("timeout waiting for focus change");
            }
            return mLastFocusChange;
        }

        public void waitForFocus(long timeoutMs, int expectedFocus) throws Exception {
            while (mLastFocusChange != expectedFocus) {
                if (!mFocusChangeWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                    fail("timeout waiting for focus change");
                }
            }
        }

        @Override
        public void onAudioFocusChange(int focusChange) {
            mLastFocusChange = focusChange;
            mFocusChangeWait.release();
        }
    }

    protected static class FocusPropertyHandler implements VehicleHalPropertyHandler {

        private int mState = VehicleAudioFocusState.VEHICLE_AUDIO_FOCUS_STATE_LOSS;
        private int mStreams = 0;
        private int mExtFocus = 0;
        private int mRequest;
        private int mRequestedStreams;
        private int mRequestedExtFocus;
        private int mRequestedAudioContexts;
        private final MockedCarTestBase mCarTest;

        private final Semaphore mSetWaitSemaphore = new Semaphore(0);

        public FocusPropertyHandler(MockedCarTestBase carTest) {
            mCarTest = carTest;
        }

        public void sendAudioFocusState(int state, int streams, int extFocus) {
            synchronized (this) {
                mState = state;
                mStreams = streams;
                mExtFocus = extFocus;
            }
            int[] values = { state, streams, extFocus, 0 };
            mCarTest.getVehicleHalEmulator().injectEvent(VehiclePropValueUtil.createIntVectorValue(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, values,
                    SystemClock.elapsedRealtimeNanos()));
        }

        public int[] waitForAudioFocusRequest(long timeoutMs) throws Exception {
            if (!mSetWaitSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                fail("timeout");
            }
            synchronized (this) {
                return new int[] { mRequest, mRequestedStreams, mRequestedExtFocus,
                        mRequestedAudioContexts };
            }
        }

        public void assertNoFocusRequest(long timeoutMs) throws Exception {
            if (mSetWaitSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                fail("should not get focus request");
            }
        }

        @Override
        public void onPropertySet(VehiclePropValue value) {
            assertEquals(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, value.getProp());
            synchronized (this) {
                mRequest = value.getInt32Values(
                        VehicleAudioFocusIndex.VEHICLE_AUDIO_FOCUS_INDEX_FOCUS);
                mRequestedStreams = value.getInt32Values(
                        VehicleAudioFocusIndex.VEHICLE_AUDIO_FOCUS_INDEX_STREAMS);
                mRequestedExtFocus = value.getInt32Values(
                        VehicleAudioFocusIndex.VEHICLE_AUDIO_FOCUS_INDEX_EXTERNAL_FOCUS_STATE);
                mRequestedAudioContexts = value.getInt32Values(
                        VehicleAudioFocusIndex.VEHICLE_AUDIO_FOCUS_INDEX_AUDIO_CONTEXTS);
            }
            mSetWaitSemaphore.release();
        }

        @Override
        public VehiclePropValue onPropertyGet(VehiclePropValue value) {
            assertEquals(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, value.getProp());
            int state, streams, extFocus;
            synchronized (this) {
                state = mState;
                streams = mStreams;
                extFocus = mExtFocus;
            }
            int[] values = { state, streams, extFocus, 0 };
            return VehiclePropValueUtil.createIntVectorValue(
                    VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, values,
                    SystemClock.elapsedRealtimeNanos());
        }

        @Override
        public void onPropertySubscribe(int property, float sampleRate, int zones) {
            assertEquals(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, property);
        }

        @Override
        public void onPropertyUnsubscribe(int property) {
            assertEquals(VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_FOCUS, property);
        }
    }
}
