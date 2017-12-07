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

import android.car.media.CarAudioManager;
import android.content.Context;
import android.hardware.automotive.vehicle.V2_0.VehicleAudioContextFlag;
import android.media.AudioManager;
import android.media.IVolumeController;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;

import com.android.car.hal.AudioHalService;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Map;

/**
 * The car volume controller to use when the car audio modules supports volume controls.
 *
 * TODO(hwwang): polish this class and add comments.
 */
public class CarVolumeController implements
        CarInputService.KeyEventListener,
        AudioHalService.AudioHalVolumeListener {
    // STOPSHIP if true.
    private static final boolean DBG = false;

    private static final String TAG = CarLog.TAG_AUDIO + ".VolCtrl";
    private static final int MSG_UPDATE_VOLUME = 0;
    private static final int MSG_UPDATE_HAL = 1;
    private static final int MSG_SUPPRESS_UI_FOR_VOLUME = 2;
    private static final int MSG_VOLUME_UI_RESTORE = 3;

    // within 5 seconds after a UI invisible volume change (e.g., due to audio context change,
    // or explicitly flag), we will not show UI in respond to that particular volume changes
    // events from HAL (context and volume index must match).
    private static final int HIDE_VOLUME_UI_MILLISECONDS = 5 * 1000; // 5 seconds

    private final Context mContext;
    private final AudioRoutingPolicy mPolicy;
    private final AudioHalService mHal;
    private final CarInputService mInputService;
    private final CarAudioService mAudioService;

    private int mSupportedAudioContext;

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
    private final RemoteCallbackList<IVolumeController> mVolumeControllers =
            new RemoteCallbackList<>();
    @GuardedBy("this")
    private int[] mSuppressUiForVolume = new int[2];
    @GuardedBy("this")
    private boolean mShouldSuppress = false;
    private HandlerThread mVolumeThread;
    private Handler mHandler;

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
            return carContext == VehicleAudioContextFlag.UNKNOWN_FLAG ?
                    mCurrentContext : carContext;
        }
    }

    private void writeVolumeToSettings(int carContext, int volume) {
        String key = VolumeUtils.CAR_AUDIO_CONTEXT_SETTINGS.get(carContext);
        if (key != null) {
            Settings.Global.putInt(mContext.getContentResolver(), key, volume);
        }
    }

    /**
     * All updates to external components should be posted to this handler to avoid holding
     * the internal lock while sending updates.
     */
    private final class VolumeHandler extends Handler {
        public VolumeHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            int stream;
            int volume;
            switch (msg.what) {
                case MSG_UPDATE_VOLUME:
                    // arg1 is car context
                    stream = msg.arg1;
                    volume = (int) msg.obj;
                    int flag = msg.arg2;
                    synchronized (CarVolumeController.this) {
                        // the suppressed stream is sending us update....
                        if (mShouldSuppress && stream == mSuppressUiForVolume[0]) {
                            // the volume matches, we want to suppress it
                            if (volume == mSuppressUiForVolume[1]) {
                                if (DBG) {
                                    Log.d(TAG, "Suppress Volume UI for stream "
                                            + stream + " volume: " + volume);
                                }
                                flag &= ~AudioManager.FLAG_SHOW_UI;
                            }
                            // No matter if the volume matches or not, we will stop suppressing
                            // UI for this stream now. After an audio context switch, user may
                            // quickly turn the nob, -1 and +1, it ends the same volume,
                            // but we should show the UI for both.
                            removeMessages(MSG_VOLUME_UI_RESTORE);
                            mShouldSuppress = false;
                        }
                    }
                    final int size = mVolumeControllers.beginBroadcast();
                    try {
                        for (int i = 0; i < size; i++) {
                            try {
                                mVolumeControllers.getBroadcastItem(i).volumeChanged(
                                        VolumeUtils.carContextToAndroidStream(stream), flag);
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
                    mHal.setUsageVolume(stream, volume);
                    break;
                case MSG_SUPPRESS_UI_FOR_VOLUME:
                    if (DBG) {
                        Log.d(TAG, "Suppress stream volume " + msg.arg1 + " " + msg.arg2);
                    }
                    synchronized (CarVolumeController.this) {
                        mShouldSuppress = true;
                        mSuppressUiForVolume[0] = msg.arg1;
                        mSuppressUiForVolume[1] = msg.arg2;
                    }
                    removeMessages(MSG_VOLUME_UI_RESTORE);
                    sendMessageDelayed(obtainMessage(MSG_VOLUME_UI_RESTORE),
                            HIDE_VOLUME_UI_MILLISECONDS);
                    break;
                case MSG_VOLUME_UI_RESTORE:
                    if (DBG) {
                        Log.d(TAG, "Volume Ui suppress expired");
                    }
                    synchronized (CarVolumeController.this) {
                        mShouldSuppress = false;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    public CarVolumeController(Context context, CarAudioService audioService,
                                       AudioHalService hal, CarInputService inputService) {
        mContext = context;
        mAudioService = audioService;
        mPolicy = audioService.getAudioRoutingPolicy();
        mHal = hal;
        mInputService = inputService;
    }

    void init() {
        mSupportedAudioContext = mHal.getSupportedAudioVolumeContexts();
        synchronized (this) {
            mVolumeThread = new HandlerThread(TAG);
            mVolumeThread.start();
            mHandler = new VolumeHandler(mVolumeThread.getLooper());
            initVolumeLimitLocked();
            initCurrentVolumeLocked();
        }
        mInputService.setVolumeKeyListener(this);
        mHal.setVolumeListener(this);
    }

    void release() {
        synchronized (this) {
            if (mVolumeThread != null) {
                mVolumeThread.quit();
            }
        }
    }

    private void initVolumeLimitLocked() {
        for (int i : VolumeUtils.CAR_AUDIO_CONTEXT) {
            int carStream = carContextToCarStream(i);
            Integer volumeMax = mHal.getUsageMaxVolume(carStream);
            int max = volumeMax == null ? 0 : volumeMax;
            if (max < 0) {
                max = 0;
            }
            // get default stream volume limit first.
            mCarContextVolumeMax.put(i, max);
        }
    }

    private void initCurrentVolumeLocked() {
        // when vhal does not work, get call can take long. For that case,
        // for the same physical streams, cache initial get results
        Map<Integer, Integer> volumesPerCarStream =
                new ArrayMap<>(VolumeUtils.CAR_AUDIO_CONTEXT.length);
        for (int i : VolumeUtils.CAR_AUDIO_CONTEXT) {
            String key = VolumeUtils.CAR_AUDIO_CONTEXT_SETTINGS.get(i);
            if (key != null) {
                int vol = Settings.Global.getInt(mContext.getContentResolver(), key, -1);
                if (vol >= 0) {
                    // Read valid volume for this car context from settings and continue;
                    mCurrentCarContextVolume.put(i, vol);
                    if (DBG) {
                        Log.d(TAG, "init volume from settings, car audio context: "
                                + i + " volume: " + vol);
                    }
                    continue;
                }
            }

            // There is no settings for this car context. Use the current physical car
            // stream volume as initial value instead, and put the volume into settings.
            int carStream = carContextToCarStream(i);
            Integer volume = volumesPerCarStream.get(carStream);
            if (volume == null) {
                volume = Integer.valueOf(mHal.getUsageVolume(carStream));
                volumesPerCarStream.put(carStream, volume);
            }
            mCurrentCarContextVolume.put(i, volume);
            writeVolumeToSettings(i, volume);
            if (DBG) {
                Log.d(TAG, "init volume from physical stream," +
                        " car audio context: " + i + " volume: " + volume);
            }
        }
    }

    public void setUsageVolume(@CarAudioManager.CarAudioUsage int carUsage, int index, int flags) {
        synchronized (this) {
            int carContext;
            // Currently car context and android logical stream are not
            // one-to-one mapping. In this API, Android side asks us to change a logical stream
            // volume. If the current car audio context maps to this logical stream, then we
            // change the volume for the current car audio context. Otherwise, we change the
            // volume for the primary mapped car audio context.
            if (VolumeUtils.carContextToAndroidStream(mCurrentContext) == carUsage) {
                carContext = mCurrentContext;
            } else {
                carContext = VolumeUtils.androidStreamToCarContext(carUsage);
            }
            if (DBG) {
                Log.d(TAG, "Receive setUsageVolume for carUsage: " + carUsage + " index: "
                        + index + " flags: " + flags + " maps to car context: " + carContext);
            }
            setUsageVolumeInternalLocked(carContext, index, flags);
        }
    }

    private void setUsageVolumeInternalLocked(int carContext, int index, int flags) {
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
            if ((flags & AudioManager.FLAG_SHOW_UI) != 0) {
                if (mShouldSuppress && mSuppressUiForVolume[0] == carContext) {
                    // In this case, the caller explicitly says "Show_UI" for the same context.
                    // We will respect the flag, and let the UI show.
                    mShouldSuppress = false;
                    mHandler.removeMessages(MSG_VOLUME_UI_RESTORE);
                }
            } else {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_SUPPRESS_UI_FOR_VOLUME,
                        carContext, index));
            }
        }
        // Record the current volume internally.
        mCurrentCarContextVolume.put(carContext, index);
        writeVolumeToSettings(mCurrentContext, index);
    }

    public int getUsageVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        synchronized (this) {
            if (VolumeUtils.carContextToAndroidStream(mCurrentContext) == carUsage) {
                return mCurrentCarContextVolume.get(mCurrentContext);
            }
            return mCurrentCarContextVolume.get(VolumeUtils.androidStreamToCarContext(carUsage));
        }
    }

    public void setVolumeController(IVolumeController controller) {
        synchronized (this) {
            mVolumeControllers.register(controller);
        }
    }

    public void onVolumeChange(int carStream, int volume, int volumeState) {
        synchronized (this) {
            int flag = getVolumeUpdateFlag(true);
            if (DBG) {
                Log.d(TAG, "onVolumeChange carStream: " + carStream + " volume: " + volume
                        + " volumeState: " + volumeState
                        + " suppressUI? " + mShouldSuppress
                        + " stream: " + mSuppressUiForVolume[0]
                        + " volume: " + mSuppressUiForVolume[1]);
            }
            int currentCarStream = carContextToCarStream(mCurrentContext);

            // Map the UNKNOWN context to the current context.
            if (mSupportedAudioContext != 0
                    && carStream == VehicleAudioContextFlag.UNKNOWN_FLAG) {
                carStream = mCurrentContext;
            }

            if (currentCarStream == carStream) {
                mCurrentCarContextVolume.put(mCurrentContext, volume);
                writeVolumeToSettings(mCurrentContext, volume);
                mHandler.sendMessage(
                        mHandler.obtainMessage(MSG_UPDATE_VOLUME, mCurrentContext, flag,
                                new Integer(volume)));
            } else {
                // Hal is telling us a car stream volume has changed, but it is not the current
                // stream.
                // TODO:  b/63778359
                Log.w(TAG, "Car stream" + carStream
                        + " volume changed, but it is not current stream, ignored.");
            }
        }
    }

    private int getVolumeUpdateFlag(boolean showUi) {
        return showUi? AudioManager.FLAG_SHOW_UI : 0;
    }

    private boolean interceptVolKeyBeforeDispatching() {
        Log.d(CarLog.TAG_AUDIO, "interceptVolKeyBeforeDispatching");

        TelecomManager telecomManager = (TelecomManager)
                mContext.getSystemService(Context.TELECOM_SERVICE);
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

    private boolean isVolumeKey(KeyEvent event) {
        return event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP;
    }

    @Override
    public void onVolumeLimitChange(int streamNumber, int volume) {
        // TODO: How should this update be sent to SystemUI? bug: 32095237
        // maybe send a volume update without showing UI.
        synchronized (this) {
            initVolumeLimitLocked();
        }
    }

    public int getUsageMaxVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        synchronized (this) {
            if (VolumeUtils.carContextToAndroidStream(mCurrentContext) == carUsage) {
                return mCarContextVolumeMax.get(mCurrentContext);
            } else {
                return mCarContextVolumeMax.get(VolumeUtils.androidStreamToCarContext(carUsage));
            }
        }
    }

    public int getUsageMinVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        return 0;  // Min value is always zero.
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
        // TODO: properly handle long press on volume key, bug: 32095989
        if (!down || interceptVolKeyBeforeDispatching()) {
            return true;
        }

        synchronized (this) {
            int currentVolume = mCurrentCarContextVolume.get(mCurrentContext);
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                    setUsageVolumeInternalLocked(mCurrentContext, currentVolume + 1,
                            getVolumeUpdateFlag(true));
                    break;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    setUsageVolumeInternalLocked(mCurrentContext, currentVolume - 1,
                            getVolumeUpdateFlag(true));
                    break;
            }
        }
        return true;
    }

    public void dump(PrintWriter writer) {
        writer.println("Volume controller:" +
                CarVolumeController.class.getSimpleName());
        synchronized (this) {
            writer.println("mSupportedAudioContext:0x" +
                    Integer.toHexString(mSupportedAudioContext));
            writer.println("mCurrentContext:0x" + Integer.toHexString(mCurrentContext));
            writer.println("mCurrentCarContextVolume:");
            dumpVolumes(writer, mCurrentCarContextVolume);
            writer.println("mCarContextVolumeMax:");
            dumpVolumes(writer, mCarContextVolumeMax);
            writer.println("Number of volume controllers:" +
                    mVolumeControllers.getRegisteredCallbackCount());
        }
    }

    private void dumpVolumes(PrintWriter writer, SparseArray<Integer> array) {
        for (int i = 0; i < array.size(); i++) {
            writer.println("0x" + Integer.toHexString(array.keyAt(i)) + ":" + array.valueAt(i));
        }
    }
}
