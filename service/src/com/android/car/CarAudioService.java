/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.car;

import android.content.Context;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioPolicy;
import android.media.audiopolicy.AudioPolicy.AudioPolicyFocusListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.car.hal.AudioHalService;
import com.android.car.hal.AudioHalService.AudioHalListener;
import com.android.car.hal.VehicleHal;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;


public class CarAudioService implements CarServiceBase, AudioHalListener {

    // support only two streams, default and media for now.
    private static final int NUMBER_OF_STREAMS = 2;

    private static final int FOCUS_STACK_DEPTH_TO_MONITOR = 2;

    private final AudioHalService mAudioHal;
    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final CarAudioChangeHandler mHandler;
    private final SystemFocusListener mSystemFocusListener;
    private AudioPolicy mAudioPolicy;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int mCurrentFocusState;
    @GuardedBy("mLock")
    private int mAllowedStreams;
    @GuardedBy("mLock")
    private int mLastFocusRequest;
    @GuardedBy("mLock")
    private int mLastFocusRequestStreams;
    @GuardedBy("mLock")
    private final AudioFocusInfo[] mFocusInfos = new AudioFocusInfo[FOCUS_STACK_DEPTH_TO_MONITOR];

    public CarAudioService(Context context) {
        mAudioHal = VehicleHal.getInstance().getAudioHal();
        mContext = context;
        mHandlerThread = new HandlerThread(CarLog.TAG_AUDIO);
        mSystemFocusListener = new SystemFocusListener();
        mHandlerThread.start();
        AudioPolicy.Builder builder = new AudioPolicy.Builder(mContext);
        builder.setLooper(mHandlerThread.getLooper()).
                setAudioPolicyFocusListener(mSystemFocusListener);
        mAudioPolicy = builder.build();
        mHandler = new CarAudioChangeHandler(mHandlerThread.getLooper());
    }

    @Override
    public void init() {
        AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        int r = am.registerAudioPolicy(mAudioPolicy);
        if (r != 0) {
            throw new RuntimeException("registerAudioPolicy failed " + r);
        }
        mAudioHal.setListener(this);
    }

    @Override
    public void release() {
        AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        am.unregisterAudioPolicyAsync(mAudioPolicy);
    }

    @Override
    public void dump(PrintWriter writer) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onFocusChange(int focusState, int streams) {
        mHandler.handleFocusChange(focusState, streams);
    }

    @Override
    public void onVolumeChange(int volume, int streamNumber) {
        mHandler.handleVolumeChange(volume, streamNumber);
    }

    @Override
    public void onStreamStatusChange(int state, int streamNumber) {
        mHandler.handleStreamStateChange(state, streamNumber);
    }

    private void doHandleCarFocusChange(int focusState, int streams) {
        mAllowedStreams = streams;
        switch (focusState) {
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_GAIN:
                doHandleFocusGainFromCar();
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_GAIN_TRANSIENT:
                doHandleFocusGainTransientFromCar();
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS:
                doHandleFocusLossFromCar();
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT:
                doHandleFocusLossTransientFromCar();
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK:
                doHandleFocusLossTransientCanDuckFromCar();
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_EXLCUSIVE:
                doHandleFocusLossTransientExclusiveFromCar();
                break;
        }
    }

    private void doHandleFocusGainFromCar() {
        //TODO
    }

    private void doHandleFocusGainTransientFromCar() {
        //TODO
    }

    private void doHandleFocusLossFromCar() {
        //TODO
    }

    private void doHandleFocusLossTransientFromCar() {
        //TODO
    }

    private void doHandleFocusLossTransientCanDuckFromCar() {
        //TODO
    }

    private void doHandleFocusLossTransientExclusiveFromCar() {
        //TODO
    }

    private void doHandleVolumeChange(int volume, int streamNumber) {
        //TODO
    }

    private void doHandleStreamStatusChange(int state, int streamNumber) {
        //TODO
    }

    private void lockSystemAudioFocus() {
        //TODO use AUDIOFOCUS_FLAG_LOCK
    }

    private void unlockSystemAudioFocus() {
        //TODO
    }

    private void sendCarAudioFocusRequestIfNecessary() {
        //TODO
    }

    private void doHandleSystemAudioFocusGrant(AudioFocusInfo afi, int requestResult) {
        //TODO distinguish car service's own focus request from others
        if (requestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mFocusInfos[0] = afi;
            sendCarAudioFocusRequestIfNecessary();
        }
    }

    private void doHandleSystemAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {
        if (!wasNotified) {
            // app released focus by itself. Remove from stack if it is there.
            boolean mayNeedsFocusChange = false;
            for (int i = 0; i < mFocusInfos.length; i++) {
                AudioFocusInfo info = mFocusInfos[i];
                if (info == null) {
                    continue;
                }
                if (info.getClientId().equals(afi.getClientId())) {
                    if (i == 0) { // this is top component releasing focus
                        // clear bottom one as well. This can lead into sending focus request
                        // if there is a focus holder other than this one.
                        // But that cannot be distinguished. So release it now, and request
                        // again if necessary.
                        mFocusInfos[1] = null;
                        break;
                    }
                    mFocusInfos[i] = null;
                    mayNeedsFocusChange = true;
                }
            }
            if (mayNeedsFocusChange) {
                sendCarAudioFocusRequestIfNecessary();
            }
        } else { // there will be a separate grant soon
            mFocusInfos[1] = afi;
        }
    }

    private class SystemFocusListener extends AudioPolicyFocusListener {
        @Override
        public void onAudioFocusGrant(AudioFocusInfo afi, int requestResult) {
            Log.i(CarLog.TAG_AUDIO, "onAudioFocusGrant " + afi + " result:" + requestResult +
                    " clientId:" + afi.getClientId() + " loss received:" + afi.getLossReceived());
            doHandleSystemAudioFocusGrant(afi, requestResult);
        }

        @Override
        public void onAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {
            Log.i(CarLog.TAG_AUDIO, "onAudioFocusLoss " + afi + " notified:" + wasNotified +
                    " clientId:" + afi.getClientId() + " loss received:" + afi.getLossReceived());
            doHandleSystemAudioFocusLoss(afi, wasNotified);
        }
    }

    /**
     * Focus listener to take focus away from android apps.
     */
    private class AndroidFocusListener implements AudioManager.OnAudioFocusChangeListener {
        @Override
        public void onAudioFocusChange(int focusChange) {
            // nothing to do as system focus listener will get all necessary information.
        }
    }

    private class CarAudioChangeHandler extends Handler {
        private static final int MSG_FOCUS_CHANGE = 0;
        private static final int MSG_STREAM_STATE_CHANGE = 1;
        private static final int MSG_VOLUME_CHANGE = 2;

        private CarAudioChangeHandler(Looper looper) {
            super(looper);
        }

        private void handleFocusChange(int focusState, int streams) {
            Message msg = obtainMessage(MSG_FOCUS_CHANGE, focusState, streams);
            sendMessage(msg);
        }

        private void handleStreamStateChange(int state, int streamNumber) {
            Message msg = obtainMessage(MSG_STREAM_STATE_CHANGE, state, streamNumber);
            sendMessage(msg);
        }

        private void handleVolumeChange(int volume, int streamNumber) {
            Message msg = obtainMessage(MSG_VOLUME_CHANGE, volume, streamNumber);
            sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_FOCUS_CHANGE:
                    doHandleCarFocusChange(msg.arg1, msg.arg2);
                    break;
                case MSG_STREAM_STATE_CHANGE:
                    doHandleStreamStatusChange(msg.arg1, msg.arg2);
                    break;
                case MSG_VOLUME_CHANGE:
                    doHandleVolumeChange(msg.arg1, msg.arg2);
                    break;
            }
        }
    }
}
