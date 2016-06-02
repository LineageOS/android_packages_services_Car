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

package com.android.car;

import android.content.Context;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.IVolumeController;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telecom.TelecomManager;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.KeyEvent;

import com.android.car.CarVolumeService.CarVolumeController;
import com.android.car.hal.AudioHalService;
import com.android.internal.annotations.GuardedBy;

import java.util.HashMap;
import java.util.Map;

/**
 * A factory class to create {@link com.android.car.CarVolumeService.CarVolumeController} based
 * on car properties.
 */
public class CarVolumeControllerFactory {
    // STOPSHIP if true.
    private static final boolean DBG = true;

    public static CarVolumeController createCarVolumeController(Context context,
            CarAudioService audioService, AudioHalService audioHal, CarInputService inputService) {
        final boolean volumeSupported = audioHal.isAudioVolumeSupported();

        // Case 1: Car Audio Module does not support volume controls
        if (!volumeSupported) {
            return new SimpleCarVolumeController(context);
        }
        return new CarExternalVolumeController(context, audioService, audioHal, inputService);
    }

    public static boolean interceptVolKeyBeforeDispatching(Context context) {
        Log.d(CarLog.TAG_AUDIO, "interceptVolKeyBeforeDispatching");

        TelecomManager telecomManager = (TelecomManager)
                context.getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager != null && telecomManager.isRinging()) {
            // If an incoming call is ringing, either VOLUME key means
            // "silence ringer".  This is consistent with current android phone's behavior
            Log.i(CarLog.TAG_AUDIO, "interceptKeyBeforeQueueing:"
                    + " VOLUME key-down while ringing: Silence ringer!");

            // Silence the ringer.  (It's safe to call this
            // even if the ringer has already been silenced.)
            telecomManager.silenceRinger();
            return true;
        }
        return false;
    }

    public static boolean isVolumeKey(KeyEvent event) {
        return event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP;
    }

    /**
     * To control volumes through {@link android.media.AudioManager} when car audio module does not
     * support volume controls.
     */
    public static final class SimpleCarVolumeController extends CarVolumeController {
        private final AudioManager mAudioManager;
        private final Context mContext;

        public SimpleCarVolumeController(Context context) {
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            mContext = context;
        }

        @Override
        void init() {
        }

        @Override
        public void setStreamVolume(int stream, int index, int flags) {
            if (DBG) {
                Log.d(CarLog.TAG_AUDIO, "setStreamVolume " + stream + " " + index + " " + flags);
            }
            mAudioManager.setStreamVolume(stream, index, flags);
        }

        @Override
        public int getStreamVolume(int stream) {
            return mAudioManager.getStreamVolume(stream);
        }

        @Override
        public void setVolumeController(IVolumeController controller) {
            mAudioManager.setVolumeController(controller);
        }

        @Override
        public int getStreamMaxVolume(int stream) {
            return mAudioManager.getStreamMaxVolume(stream);
        }

        @Override
        public int getStreamMinVolume(int stream) {
            return mAudioManager.getStreamMinVolume(stream);
        }

        @Override
        public boolean onKeyEvent(KeyEvent event) {
            if (!isVolumeKey(event)) {
                return false;
            }
            handleVolumeKeyDefault(event);
            return true;
        }

        private void handleVolumeKeyDefault(KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN
                    || interceptVolKeyBeforeDispatching(mContext)) {
                return;
            }

            boolean volUp = event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP;
            int flags = AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND
                    | AudioManager.FLAG_FROM_KEY;
            IAudioService audioService = getAudioService();
            String pkgName = mContext.getOpPackageName();
            try {
                if (audioService != null) {
                    audioService.adjustSuggestedStreamVolume(
                            volUp ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                            AudioManager.USE_DEFAULT_STREAM_TYPE, flags, pkgName, CarLog.TAG_INPUT);
                }
            } catch (RemoteException e) {
                Log.e(CarLog.TAG_INPUT, "Error calling android audio service.", e);
            }
        }

        private static IAudioService getAudioService() {
            IAudioService audioService = IAudioService.Stub.asInterface(
                    ServiceManager.checkService(Context.AUDIO_SERVICE));
            if (audioService == null) {
                Log.w(CarLog.TAG_INPUT, "Unable to find IAudioService interface.");
            }
            return audioService;
        }
    }

    /**
     * The car volume controller to use when the car audio modules supports volume controls.
     *
     * Depending on whether the car support audio context and has persistent memory, we need to
     * handle per context volume change properly.
     *
     * Regardless whether car supports audio context or not, we need to keep per audio context
     * volume internally. If we only support single channel, then we only send the volume change
     * event when that stream is in focus; Otherwise, we need to adjust the stream volume either on
     * software mixer level or send it the car audio module if the car support audio context
     * and multi channel. TODO: Add support for multi channel.
     *
     * Per context volume should be persisted, so the volumes can stay the same across boots.
     * Depending on the hardware property, this can be persisted on car side (or/and android side).
     * TODO: we need to define one single source of truth if the car has memory.
     */
    public static class CarExternalVolumeController extends CarVolumeController
            implements CarInputService.KeyEventListener, AudioHalService.AudioHalVolumeListener,
            CarAudioService.AudioContextChangeListener {
        private static final String TAG = CarLog.TAG_AUDIO + "ExtVolCtrl";
        private static final int MSG_UPDATE_VOLUME = 0;
        private static final int MSG_UPDATE_HAL = 1;

        private final Context mContext;
        private final AudioRoutingPolicy mPolicy;
        private final AudioHalService mHal;
        private final CarInputService mInputService;
        private final CarAudioService mAudioService;

        private int mSupportedAudioContext;

        private boolean mHasExternalMemory;
        private boolean mMasterVolumeOnly;

        @GuardedBy("this")
        private int mCurrentContext = CarVolumeService.DEFAULT_CAR_AUDIO_CONTEXT;
        // current logical volume, the key is car audio context
        @GuardedBy("this")
        private final SparseArray<Integer> mCurrentCarContextVolume =
                new SparseArray<>(VolumeUtils.CAR_AUDIO_CONTEXT.length);
        // stream volume limit, the key is car audio context type
        @GuardedBy("this")
        private final SparseArray<Integer> mCarContextVolumeMax =
                new SparseArray<>(VolumeUtils.CAR_AUDIO_CONTEXT.length);
        // stream volume limit, the key is car audio context type
        @GuardedBy("this")
        private final SparseArray<Integer> mCarContextVolumeMin =
                new SparseArray<>(VolumeUtils.CAR_AUDIO_CONTEXT.length);
        @GuardedBy("this")
        private final RemoteCallbackList<IVolumeController> mVolumeControllers =
                new RemoteCallbackList<>();

        private final Handler mHandler = new VolumeHandler();

        /**
         * Convert an car context to the car stream.
         *
         * @return If car supports audio context, then it returns the car audio context. Otherwise,
         *      it returns the physical stream that maps to this logical stream.
         */
        private int carContextToCarStream(int carContext) {
            if (mSupportedAudioContext == 0) {
                int physicalStream = mPolicy.getPhysicalStreamForLogicalStream(
                        AudioHalService.carContextToCarUsage(carContext));
                return physicalStream;
            } else {
                return carContext;
            }
        }

        /**
         * All updates to external components should be posted to this handler to avoid holding
         * the internal lock while sending updates.
         */
        private final class VolumeHandler extends Handler {
            @Override
            public void handleMessage(Message msg) {
                int stream;
                int volume;
                switch (msg.what) {
                    case MSG_UPDATE_VOLUME:
                        stream = msg.arg1;
                        int flag = msg.arg2;
                        final int size = mVolumeControllers.beginBroadcast();
                        try {
                            for (int i = 0; i < size; i++) {
                                try {
                                    mVolumeControllers.getBroadcastItem(i)
                                            .volumeChanged(stream, flag);
                                } catch (RemoteException ignored) {
                                }
                            }
                        } finally {
                            mVolumeControllers.finishBroadcast();
                        }
                        break;
                    case MSG_UPDATE_HAL:
                        stream = msg.arg1;
                        volume = msg.arg2;
                        synchronized (CarExternalVolumeController.this) {
                            if (mMasterVolumeOnly) {
                                stream = 0;
                            }
                        }
                        mHal.setStreamVolume(stream, volume);
                        break;
                    default:
                        break;
                }
            }
        }

        public CarExternalVolumeController(Context context, CarAudioService audioService,
                                           AudioHalService hal, CarInputService inputService) {
            mContext = context;
            mAudioService = audioService;
            mPolicy = audioService.getAudioRoutingPolicy();
            mHal = hal;
            mInputService = inputService;
        }

        @Override
        void init() {
            mSupportedAudioContext = mHal.getSupportedAudioVolumeContexts();
            mHasExternalMemory = mHal.isExternalAudioVolumePersistent();
            mMasterVolumeOnly = mHal.isAudioVolumeMasterOnly();
            synchronized (this) {
                initVolumeLimitLocked();
                initCurrentVolumeLocked();
            }
            mInputService.setVolumeKeyListener(this);
            mHal.setVolumeListener(this);
            mAudioService.setAudioContextChangeListener(Looper.getMainLooper(), this);
        }

        private void initVolumeLimitLocked() {
            for (int i : VolumeUtils.CAR_AUDIO_CONTEXT) {
                int carStream = carContextToCarStream(i);
                Pair<Integer, Integer> volumeMinMax = mHal.getStreamVolumeLimit(carStream);
                int max;
                int min;
                if (volumeMinMax == null) {
                    max = 0;
                    min = 0;
                } else {
                    max = volumeMinMax.second >= 0 ? volumeMinMax.second : 0;
                    min = volumeMinMax.first >=0 ? volumeMinMax.first : 0;
                }
                // get default stream volume limit first.
                mCarContextVolumeMax.put(i, max);
                mCarContextVolumeMin.put(i, min);
            }
        }

        private void initCurrentVolumeLocked() {
            if (mHasExternalMemory) {
                // TODO: read per context volume from audio hal
            } else {
                // TODO: read the Android side volume from Settings and pass it to the audio module
                // Here we just set it to the physical stream volume temporarily.
                // when vhal does not work, get call can take long. For that case,
                // for the same physical streams, cache initial get results
                Map<Integer, Integer> volumesPerCarStream = new HashMap<>();
                for (int i : VolumeUtils.CAR_AUDIO_CONTEXT) {
                    int carStream = carContextToCarStream(i);
                    Integer volume = volumesPerCarStream.get(carStream);
                    if (volume == null) {
                        volume = Integer.valueOf(mHal.getStreamVolume(mMasterVolumeOnly ? 0 :
                            carStream));
                        volumesPerCarStream.put(carStream, volume);
                    }
                    mCurrentCarContextVolume.put(i, volume);
                    if (DBG) {
                        Log.d(TAG, " init volume, car audio context: " + i + " volume: " + volume);
                    }
                }
            }
        }

        @Override
        public void setStreamVolume(int stream, int index, int flags) {
            synchronized (this) {
                int carContext;
                // Currently car context and android logical stream are not
                // one-to-one mapping. In this API, Android side asks us to change a logical stream
                // volume. If the current car audio context maps to this logical stream, then we
                // change the volume for the current car audio context. Otherwise, we change the
                // volume for the primary mapped car audio context.
                if (VolumeUtils.carContextToAndroidStream(mCurrentContext) == stream) {
                    carContext = mCurrentContext;
                } else {
                    carContext = VolumeUtils.androidStreamToCarContext(stream);
                }
                if (DBG) {
                    Log.d(TAG, "Receive setStreamVolume logical stream: " + stream + " index: "
                            + index + " flags: " + flags + " maps to car context: " + carContext);
                }
                setStreamVolumeInternalLocked(carContext, index, flags);
            }
        }

        private void setStreamVolumeInternalLocked(int carContext, int index, int flags) {
            if (mCarContextVolumeMax.get(carContext) == null) {
                Log.e(TAG, "Stream type not supported " + carContext);
                return;
            }
            int limit = mCarContextVolumeMax.get(carContext);
            if (index > limit) {
                Log.w(TAG, "Volume exceeds volume limit. context: " + carContext
                        + " index: " + index + " limit: " + limit);
                index = limit;
            }

            if (index < 0) {
                index = 0;
            }

            if (mCurrentCarContextVolume.get(carContext) == index) {
                return;
            }

            int carStream = carContextToCarStream(carContext);
            if (DBG) {
                Log.d(TAG, "Change car stream volume, stream: " + carStream + " volume:" + index);
            }
            // For single channel, only adjust the volume when the audio context is the current one.
            if (mCurrentContext == carContext) {
                if (DBG) {
                    Log.d(TAG, "Sending volume change to HAL");
                }
                mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_HAL, carStream, index));
            }
            // Record the current volume internally.
            mCurrentCarContextVolume.put(carContext, index);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_VOLUME,
                    VolumeUtils.carContextToAndroidStream(carContext),
                    getVolumeUpdateFlag()));
        }

        @Override
        public int getStreamVolume(int stream) {
            synchronized (this) {
                if (VolumeUtils.carContextToAndroidStream(mCurrentContext) == stream) {
                    return mCurrentCarContextVolume.get(mCurrentContext);
                }
                return mCurrentCarContextVolume.get(VolumeUtils.androidStreamToCarContext(stream));
            }
        }

        @Override
        public void setVolumeController(IVolumeController controller) {
            synchronized (this) {
                mVolumeControllers.register(controller);
            }
        }

        @Override
        public void onVolumeChange(int carStream, int volume, int volumeState) {
            int flag = getVolumeUpdateFlag();
            synchronized (this) {
                if (DBG) {
                    Log.d(TAG, "onVolumeChange carStream:" + carStream + " volume: " + volume
                            + " volumeState: " + volumeState);
                }
                // Assume single channel here.
                int currentLogicalStream = VolumeUtils.carContextToAndroidStream(mCurrentContext);
                int currentCarStream = carContextToCarStream(mCurrentContext);
                if (mMasterVolumeOnly) { //for master volume only H/W, always assume current stream
                    carStream = currentCarStream;
                }
                if (currentCarStream == carStream) {
                    mCurrentCarContextVolume.put(mCurrentContext, volume);
                    mHandler.sendMessage(
                            mHandler.obtainMessage(MSG_UPDATE_VOLUME, currentLogicalStream, flag));
                } else {
                    // Hal is telling us a car stream volume has changed, but it is not the current
                    // stream.
                    Log.w(TAG, "Car stream" + carStream
                            + " volume changed, but it is not current stream, ignored.");
                }
            }
        }

        private int getVolumeUpdateFlag() {
            // TODO: Apply appropriate flags.
            return AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND;
        }

        private void updateHalVolumeLocked(final int carStream, final int index) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_HAL, carStream, index));
        }

        @Override
        public void onVolumeLimitChange(int streamNumber, int volume) {
            // TODO: How should this update be sent to SystemUI? maybe send a volume update without
            // showing UI.
            synchronized (this) {
                initVolumeLimitLocked();
            }
        }

        @Override
        public int getStreamMaxVolume(int stream) {
            synchronized (this) {
                if (VolumeUtils.carContextToAndroidStream(mCurrentContext) == stream) {
                    return mCarContextVolumeMax.get(mCurrentContext);
                } else {
                    return mCarContextVolumeMax.get(VolumeUtils.androidStreamToCarContext(stream));
                }
            }
        }

        @Override
        public int getStreamMinVolume(int stream) {
            synchronized (this) {
                if (VolumeUtils.carContextToAndroidStream(mCurrentContext) == stream) {
                    return mCarContextVolumeMin.get(mCurrentContext);
                } else {
                    return mCarContextVolumeMin.get(VolumeUtils.androidStreamToCarContext(stream));
                }
            }
        }

        @Override
        public boolean onKeyEvent(KeyEvent event) {
            if (!isVolumeKey(event)) {
                return false;
            }
            final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (DBG) {
                Log.d(TAG, "Receive volume keyevent " + event.toString());
            }
            // TODO: properly handle long press on volume key
            if (!down || interceptVolKeyBeforeDispatching(mContext)) {
                return true;
            }

            synchronized (this) {
                int currentVolume = mCurrentCarContextVolume.get(mCurrentContext);
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_VOLUME_UP:
                        setStreamVolumeInternalLocked(mCurrentContext, currentVolume + 1,
                                getVolumeUpdateFlag());
                        break;
                    case KeyEvent.KEYCODE_VOLUME_DOWN:
                        setStreamVolumeInternalLocked(mCurrentContext, currentVolume - 1,
                                getVolumeUpdateFlag());
                        break;
                }
            }
            return true;
        }

        @Override
        public void onContextChange(int primaryFocusContext, int primaryFocusPhysicalStream) {
            synchronized (this) {
                if(DBG) {
                    Log.d(TAG, "Audio context changed from " + mCurrentContext + " to: "
                            + primaryFocusContext + " physical: " + primaryFocusPhysicalStream);
                }
                // if primaryFocusContext is 0, it means nothing is playing or holding focus,
                // we will keep the last focus context and if the user changes the volume
                // it will go to the last audio context.
                if (primaryFocusContext == mCurrentContext || primaryFocusContext == 0) {
                    return;
                }
                mCurrentContext = primaryFocusContext;
                // if car supports audio context and has external memory, then we don't need to do
                // anything.
                if(mSupportedAudioContext != 0 && mHasExternalMemory) {
                    if (DBG) {
                        Log.d(TAG, "Car support audio context and has external memory," +
                                " no volume change needed from car service");
                    }
                    return;
                }

                // Otherwise, we need to tell Hal what the correct volume is for the new context.
                int currentVolume = mCurrentCarContextVolume.get(primaryFocusContext);

                int carStreamNumber = (mSupportedAudioContext == 0) ? primaryFocusPhysicalStream :
                        primaryFocusContext;
                if (DBG) {
                    Log.d(TAG, "Change volume from: "
                            + mCurrentCarContextVolume.get(mCurrentContext)
                            + " to: "+ currentVolume);
                }
                updateHalVolumeLocked(carStreamNumber, currentVolume);
            }
        }
    }
}
