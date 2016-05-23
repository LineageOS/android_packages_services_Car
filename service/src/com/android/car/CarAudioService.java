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

import android.car.Car;
import android.car.VehicleZoneUtil;
import android.app.AppGlobals;
import android.car.media.CarAudioManager;
import android.car.media.ICarAudio;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.IVolumeController;
import android.media.audiopolicy.AudioPolicy;
import android.media.audiopolicy.AudioPolicy.AudioPolicyFocusListener;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.android.car.hal.AudioHalService;
import com.android.car.hal.AudioHalService.AudioHalFocusListener;
import com.android.car.hal.VehicleHal;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.LinkedList;

public class CarAudioService extends ICarAudio.Stub implements CarServiceBase,
        AudioHalFocusListener {

    public interface AudioContextChangeListener {
        /**
         * Notifies the current primary audio context (app holding focus).
         * If there is no active context, context will be 0.
         * Will use context like CarAudioManager.CAR_AUDIO_USAGE_*
         */
        void onContextChange(int primaryFocusContext, int primaryFocusPhysicalStream);
    }

    private final long mFocusResponseWaitTimeoutMs;

    private final int mNumConsecutiveHalFailuresForCanError;

    private static final String TAG_FOCUS = CarLog.TAG_AUDIO + ".FOCUS";

    private static final boolean DBG = true;
    private static final boolean DBG_DYNAMIC_AUDIO_ROUTING = true;

    private final AudioHalService mAudioHal;
    private final Context mContext;
    private final HandlerThread mFocusHandlerThread;
    private final CarAudioFocusChangeHandler mFocusHandler;
    private final SystemFocusListener mSystemFocusListener;
    private final CarVolumeService mVolumeService;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private AudioPolicy mAudioPolicy;
    @GuardedBy("mLock")
    private FocusState mCurrentFocusState = FocusState.STATE_LOSS;
    /** Focus state received, but not handled yet. Once handled, this will be set to null. */
    @GuardedBy("mLock")
    private FocusState mFocusReceived = null;
    @GuardedBy("mLock")
    private FocusRequest mLastFocusRequestToCar = null;
    @GuardedBy("mLock")
    private LinkedList<AudioFocusInfo> mPendingFocusChanges = new LinkedList<>();
    @GuardedBy("mLock")
    private AudioFocusInfo mTopFocusInfo = null;
    /** previous top which may be in ducking state */
    @GuardedBy("mLock")
    private AudioFocusInfo mSecondFocusInfo = null;

    private AudioRoutingPolicy mAudioRoutingPolicy;
    private final AudioManager mAudioManager;
    private final CanBusErrorNotifier mCanBusErrorNotifier;
    private final BottomAudioFocusListener mBottomAudioFocusListener =
            new BottomAudioFocusListener();
    private final CarProxyAndroidFocusListener mCarProxyAudioFocusListener =
            new CarProxyAndroidFocusListener();
    private final MediaMuteAudioFocusListener mMediaMuteAudioFocusListener =
            new MediaMuteAudioFocusListener();

    @GuardedBy("mLock")
    private int mBottomFocusState;
    @GuardedBy("mLock")
    private boolean mRadioActive = false;
    @GuardedBy("mLock")
    private boolean mCallActive = false;
    @GuardedBy("mLock")
    private int mCurrentAudioContexts = 0;
    @GuardedBy("mLock")
    private int mCurrentPrimaryAudioContext = 0;
    @GuardedBy("mLock")
    private int mCurrentPrimaryPhysicalStream = 0;
    @GuardedBy("mLock")
    private AudioContextChangeListener mAudioContextChangeListener;
    @GuardedBy("mLock")
    private CarAudioContextChangeHandler mCarAudioContextChangeHandler;
    @GuardedBy("mLock")
    private boolean mIsRadioExternal;
    @GuardedBy("mLock")
    private int mNumConsecutiveHalFailures;

    private final boolean mUseDynamicRouting;

    private final AudioAttributes mAttributeBottom =
            CarAudioAttributesUtil.getAudioAttributesForCarUsage(
                    CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_BOTTOM);
    private final AudioAttributes mAttributeCarExternal =
            CarAudioAttributesUtil.getAudioAttributesForCarUsage(
                    CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_CAR_PROXY);

    public CarAudioService(Context context, CarInputService inputService) {
        mAudioHal = VehicleHal.getInstance().getAudioHal();
        mContext = context;
        mFocusHandlerThread = new HandlerThread(CarLog.TAG_AUDIO);
        mSystemFocusListener = new SystemFocusListener();
        mFocusHandlerThread.start();
        mFocusHandler = new CarAudioFocusChangeHandler(mFocusHandlerThread.getLooper());
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mCanBusErrorNotifier = new CanBusErrorNotifier(context);
        Resources res = context.getResources();
        mFocusResponseWaitTimeoutMs = (long) res.getInteger(R.integer.audioFocusWaitTimeoutMs);
        mNumConsecutiveHalFailuresForCanError =
                (int) res.getInteger(R.integer.consecutiveHalFailures);
        mUseDynamicRouting = res.getBoolean(R.bool.audioUseDynamicRouting);
        mVolumeService = new CarVolumeService(mContext, this, mAudioHal, inputService);
    }

    @Override
    public AudioAttributes getAudioAttributesForCarUsage(int carUsage) {
        return CarAudioAttributesUtil.getAudioAttributesForCarUsage(carUsage);
    }

    @Override
    public void init() {
        AudioPolicy.Builder builder = new AudioPolicy.Builder(mContext);
        builder.setLooper(Looper.getMainLooper());
        boolean isFocusSupported = mAudioHal.isFocusSupported();
        if (isFocusSupported) {
            builder.setAudioPolicyFocusListener(mSystemFocusListener);
            FocusState currentState = FocusState.create(mAudioHal.getCurrentFocusState());
            int r = mAudioManager.requestAudioFocus(mBottomAudioFocusListener, mAttributeBottom,
                    AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_FLAG_DELAY_OK);
            synchronized (mLock) {
                if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    mBottomFocusState = AudioManager.AUDIOFOCUS_GAIN;
                } else {
                    mBottomFocusState = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
                }
                mCurrentFocusState = currentState;
                mCurrentAudioContexts = 0;
            }
        }
        int audioHwVariant = mAudioHal.getHwVariant();
        AudioRoutingPolicy audioRoutingPolicy = AudioRoutingPolicy.create(mContext, audioHwVariant);
        if (mUseDynamicRouting) {
            setupDynamicRouting(audioRoutingPolicy, builder);
        }
        AudioPolicy audioPolicy = null;
        if (isFocusSupported || mUseDynamicRouting) {
            audioPolicy = builder.build();
            int r = mAudioManager.registerAudioPolicy(audioPolicy);
            if (r != 0) {
                throw new RuntimeException("registerAudioPolicy failed " + r);
            }
        }
        mAudioHal.setFocusListener(this);
        mAudioHal.setAudioRoutingPolicy(audioRoutingPolicy);
        synchronized (mLock) {
            if (audioPolicy != null) {
                mAudioPolicy = audioPolicy;
            }
            mAudioRoutingPolicy = audioRoutingPolicy;
            mIsRadioExternal = mAudioHal.isRadioExternal();
        }
        mVolumeService.init();
    }

    private void setupDynamicRouting(AudioRoutingPolicy audioRoutingPolicy,
            AudioPolicy.Builder audioPolicyBuilder) {
        AudioDeviceInfo[] deviceInfos = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (deviceInfos.length == 0) {
            Log.e(CarLog.TAG_AUDIO, "setupDynamicRouting, no output device available, ignore");
            return;
        }
        int numPhysicalStreams = audioRoutingPolicy.getPhysicalStreamsCount();
        AudioDeviceInfo[] devicesToRoute = new AudioDeviceInfo[numPhysicalStreams];
        for (AudioDeviceInfo info : deviceInfos) {
            if (DBG_DYNAMIC_AUDIO_ROUTING) {
                Log.v(CarLog.TAG_AUDIO, String.format(
                        "output device=%s id=%d name=%s addr=%s type=%s",
                        info.toString(), info.getId(), info.getProductName(), info.getAddress(),
                        info.getType()));
            }
            if (info.getType() == AudioDeviceInfo.TYPE_BUS) {
                int addressNumeric = parseDeviceAddress(info.getAddress());
                if (addressNumeric >= 0 && addressNumeric < numPhysicalStreams) {
                    devicesToRoute[addressNumeric] = info;
                    Log.i(CarLog.TAG_AUDIO, String.format(
                            "valid bus found, devie=%s id=%d name=%s addr=%s",
                            info.toString(), info.getId(), info.getProductName(), info.getAddress())
                            );
                }
            }
        }
        for (int i = 0; i < numPhysicalStreams; i++) {
            AudioDeviceInfo info = devicesToRoute[i];
            if (info == null) {
                Log.e(CarLog.TAG_AUDIO, "setupDynamicRouting, cannot find device for address " + i);
                return;
            }
            int sampleRate = getMaxSampleRate(info);
            int channels = getMaxChannles(info);
            AudioFormat mixFormat = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channels)
                .build();
            Log.i(CarLog.TAG_AUDIO, String.format(
                    "Physical stream %d, sampleRate:%d, channles:0x%s", i, sampleRate,
                    Integer.toHexString(channels)));
            int[] logicalStreams = audioRoutingPolicy.getLogicalStreamsForPhysicalStream(i);
            AudioMixingRule.Builder mixingRuleBuilder = new AudioMixingRule.Builder();
            for (int logicalStream : logicalStreams) {
                mixingRuleBuilder.addRule(
                        CarAudioAttributesUtil.getAudioAttributesForCarUsage(logicalStream),
                        AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);
            }
            AudioMix audioMix = new AudioMix.Builder(mixingRuleBuilder.build())
                .setFormat(mixFormat)
                .setDevice(info)
                .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                .build();
            audioPolicyBuilder.addMix(audioMix);
        }
    }

    /**
     * Parse device address. Expected format is BUS%d_%s, address, usage hint
     * @return valid address (from 0 to positive) or -1 for invalid address.
     */
    private int parseDeviceAddress(String address) {
        String[] words = address.split("_");
        int addressParsed = -1;
        if (words[0].startsWith("BUS")) {
            try {
                addressParsed = Integer.parseInt(words[0].substring(3));
            } catch (NumberFormatException e) {
                //ignore
            }
        }
        if (addressParsed < 0) {
            return -1;
        }
        return addressParsed;
    }

    private int getMaxSampleRate(AudioDeviceInfo info) {
        int[] sampleRates = info.getSampleRates();
        if (sampleRates == null || sampleRates.length == 0) {
            return 48000;
        }
        int sampleRate = sampleRates[0];
        for (int i = 1; i < sampleRates.length; i++) {
            if (sampleRates[i] > sampleRate) {
                sampleRate = sampleRates[i];
            }
        }
        return sampleRate;
    }

    private int getMaxChannles(AudioDeviceInfo info) {
        int[] channelMasks = info.getChannelMasks();
        if (channelMasks == null) {
            return AudioFormat.CHANNEL_OUT_STEREO;
        }
        int channels = AudioFormat.CHANNEL_OUT_MONO;
        int numChannels = 1;
        for (int i = 0; i < channelMasks.length; i++) {
            int currentNumChannles = VehicleZoneUtil.getNumberOfZones(channelMasks[i]);
            if (currentNumChannles > numChannels) {
                numChannels = currentNumChannles;
                channels = channelMasks[i];
            }
        }
        return channels;
    }

    @Override
    public void release() {
        mFocusHandler.cancelAll();
        mAudioManager.abandonAudioFocus(mBottomAudioFocusListener);
        mAudioManager.abandonAudioFocus(mCarProxyAudioFocusListener);
        AudioPolicy audioPolicy;
        synchronized (mLock) {
            mCurrentFocusState = FocusState.STATE_LOSS;
            mLastFocusRequestToCar = null;
            mTopFocusInfo = null;
            mPendingFocusChanges.clear();
            mRadioActive = false;
            if (mCarAudioContextChangeHandler != null) {
                mCarAudioContextChangeHandler.cancelAll();
                mCarAudioContextChangeHandler = null;
            }
            mAudioContextChangeListener = null;
            mCurrentPrimaryAudioContext = 0;
            audioPolicy = mAudioPolicy;
            mAudioPolicy = null;
        }
        if (audioPolicy != null) {
            mAudioManager.unregisterAudioPolicyAsync(audioPolicy);
        }
    }

    public synchronized void setAudioContextChangeListener(Looper looper,
            AudioContextChangeListener listener) {
        if (looper == null || listener == null) {
            throw new IllegalArgumentException("looper or listener null");
        }
        if (mCarAudioContextChangeHandler != null) {
            mCarAudioContextChangeHandler.cancelAll();
        }
        mCarAudioContextChangeHandler = new CarAudioContextChangeHandler(looper);
        mAudioContextChangeListener = listener;
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*CarAudioService*");
        writer.println(" mCurrentFocusState:" + mCurrentFocusState +
                " mLastFocusRequestToCar:" + mLastFocusRequestToCar);
        writer.println(" mCurrentAudioContexts:0x" + Integer.toHexString(mCurrentAudioContexts));
        writer.println(" mCallActive:" + mCallActive + " mRadioActive:" + mRadioActive);
        writer.println(" mCurrentPrimaryAudioContext:" + mCurrentPrimaryAudioContext +
                " mCurrentPrimaryPhysicalStream:" + mCurrentPrimaryPhysicalStream);
        writer.println(" mIsRadioExternal:" + mIsRadioExternal);
        writer.println(" mNumConsecutiveHalFailures:" + mNumConsecutiveHalFailures);
        writer.println(" media muted:" + mMediaMuteAudioFocusListener.isMuted());
        writer.println(" mAudioPolicy:" + mAudioPolicy);
        mAudioRoutingPolicy.dump(writer);
    }

    @Override
    public void onFocusChange(int focusState, int streams, int externalFocus) {
        synchronized (mLock) {
            mFocusReceived = FocusState.create(focusState, streams, externalFocus);
            // wake up thread waiting for focus response.
            mLock.notifyAll();
        }
        mFocusHandler.handleFocusChange();
    }

    @Override
    public void onStreamStatusChange(int state, int streamNumber) {
        mFocusHandler.handleStreamStateChange(state, streamNumber);
    }

    @Override
    public void setStreamVolume(int streamType, int index, int flags) {
        enforceAudioVolumePermission();
        mVolumeService.setStreamVolume(streamType, index, flags);
    }

    @Override
    public void setVolumeController(IVolumeController controller) {
        enforceAudioVolumePermission();
        mVolumeService.setVolumeController(controller);
    }

    @Override
    public int getStreamMaxVolume(int streamType) {
        enforceAudioVolumePermission();
        return mVolumeService.getStreamMaxVolume(streamType);
    }

    @Override
    public int getStreamMinVolume(int streamType) {
        enforceAudioVolumePermission();
        return mVolumeService.getStreamMinVolume(streamType);
    }

    @Override
    public int getStreamVolume(int streamType) {
        enforceAudioVolumePermission();
        return mVolumeService.getStreamVolume(streamType);
    }

    @Override
    public boolean isMediaMuted() {
        return mMediaMuteAudioFocusListener.isMuted();
    }

    @Override
    public boolean setMediaMute(boolean mute) {
        enforceAudioVolumePermission();
        boolean currentState = isMediaMuted();
        if (mute == currentState) {
            return currentState;
        }
        if (mute) {
            return mMediaMuteAudioFocusListener.mute();
        } else {
            return mMediaMuteAudioFocusListener.unMute();
        }
    }

    /**
     * API for system to control mute with lock.
     * @param mute
     * @return the current mute state
     */
    public void muteMediaWithLock(boolean lock) {
        mMediaMuteAudioFocusListener.mute(lock);
    }

    public void unMuteMedia() {
        // unmute always done with lock
        mMediaMuteAudioFocusListener.unMute(true);
    }

    public AudioRoutingPolicy getAudioRoutingPolicy() {
        return mAudioRoutingPolicy;
    }

    private void enforceAudioVolumePermission() {
        if (mContext.checkCallingOrSelfPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "requires permission " + Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
        }
    }

    private void doHandleCarFocusChange() {
        int newFocusState = AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_INVALID;
        FocusState currentState;
        AudioFocusInfo topInfo;
        synchronized (mLock) {
            if (mFocusReceived == null) {
                // already handled
                return;
            }
            if (mFocusReceived.equals(mCurrentFocusState)) {
                // no change
                mFocusReceived = null;
                return;
            }
            if (DBG) {
                Log.d(TAG_FOCUS, "focus change from car:" + mFocusReceived);
            }
            topInfo = mTopFocusInfo;
            if (!mFocusReceived.equals(mCurrentFocusState.focusState)) {
                newFocusState = mFocusReceived.focusState;
            }
            mCurrentFocusState = mFocusReceived;
            currentState = mFocusReceived;
            mFocusReceived = null;
            if (mLastFocusRequestToCar != null &&
                    (mLastFocusRequestToCar.focusRequest ==
                        AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN ||
                    mLastFocusRequestToCar.focusRequest ==
                        AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT ||
                    mLastFocusRequestToCar.focusRequest ==
                        AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT_MAY_DUCK) &&
                    (mCurrentFocusState.streams & mLastFocusRequestToCar.streams) !=
                    mLastFocusRequestToCar.streams) {
                Log.w(TAG_FOCUS, "streams mismatch, requested:0x" + Integer.toHexString(
                        mLastFocusRequestToCar.streams) + " got:0x" +
                        Integer.toHexString(mCurrentFocusState.streams));
                // treat it as focus loss as requested streams are not there.
                newFocusState = AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS;
            }
            mLastFocusRequestToCar = null;
            if (mRadioActive &&
                    (mCurrentFocusState.externalFocus &
                    AudioHalService.VEHICLE_AUDIO_EXT_FOCUS_CAR_PLAY_ONLY_FLAG) == 0) {
                // radio flag dropped
                newFocusState = AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS;
                mRadioActive = false;
            }
            if (newFocusState == AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS ||
                    newFocusState == AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT ||
                    newFocusState ==
                        AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_EXLCUSIVE) {
                // clear second one as there can be no such item in these LOSS.
                mSecondFocusInfo = null;
            }
        }
        switch (newFocusState) {
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_GAIN:
                doHandleFocusGainFromCar(currentState, topInfo);
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_GAIN_TRANSIENT:
                doHandleFocusGainTransientFromCar(currentState, topInfo);
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS:
                doHandleFocusLossFromCar(currentState, topInfo);
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT:
                doHandleFocusLossTransientFromCar(currentState);
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK:
                doHandleFocusLossTransientCanDuckFromCar(currentState);
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_EXLCUSIVE:
                doHandleFocusLossTransientExclusiveFromCar(currentState);
                break;
        }
    }

    private void doHandleFocusGainFromCar(FocusState currentState, AudioFocusInfo topInfo) {
        if (isFocusFromCarServiceBottom(topInfo)) {
            Log.w(TAG_FOCUS, "focus gain from car:" + currentState +
                    " while bottom listener is top");
            mFocusHandler.handleFocusReleaseRequest();
        } else {
            mAudioManager.abandonAudioFocus(mCarProxyAudioFocusListener);
        }
    }

    private void doHandleFocusGainTransientFromCar(FocusState currentState,
            AudioFocusInfo topInfo) {
        if ((currentState.externalFocus &
                (AudioHalService.VEHICLE_AUDIO_EXT_FOCUS_CAR_PERMANENT_FLAG |
                        AudioHalService.VEHICLE_AUDIO_EXT_FOCUS_CAR_TRANSIENT_FLAG)) == 0) {
            mAudioManager.abandonAudioFocus(mCarProxyAudioFocusListener);
        } else {
            if (isFocusFromCarServiceBottom(topInfo) || isFocusFromCarProxy(topInfo)) {
                Log.w(TAG_FOCUS, "focus gain transient from car:" + currentState +
                        " while bottom listener or car proxy is top");
                mFocusHandler.handleFocusReleaseRequest();
            }
        }
    }

    private void doHandleFocusLossFromCar(FocusState currentState, AudioFocusInfo topInfo) {
        if (DBG) {
            Log.d(TAG_FOCUS, "doHandleFocusLossFromCar current:" + currentState +
                    " top:" + dumpAudioFocusInfo(topInfo));
        }
        boolean shouldRequestProxyFocus = false;
        if ((currentState.externalFocus &
                AudioHalService.VEHICLE_AUDIO_EXT_FOCUS_CAR_PERMANENT_FLAG) != 0) {
            shouldRequestProxyFocus = true;
        }
        if (isFocusFromCarProxy(topInfo)) {
            // already car proxy is top. Nothing to do.
            return;
        } else if (!isFocusFromCarServiceBottom(topInfo)) {
            shouldRequestProxyFocus = true;
        }
        if (shouldRequestProxyFocus) {
            requestCarProxyFocus(AudioManager.AUDIOFOCUS_GAIN, 0);
        }
    }

    private void doHandleFocusLossTransientFromCar(FocusState currentState) {
        requestCarProxyFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, 0);
    }

    private void doHandleFocusLossTransientCanDuckFromCar(FocusState currentState) {
        requestCarProxyFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, 0);
    }

    private void doHandleFocusLossTransientExclusiveFromCar(FocusState currentState) {
        requestCarProxyFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                AudioManager.AUDIOFOCUS_FLAG_LOCK);
    }

    private void requestCarProxyFocus(int androidFocus, int flags) {
        mAudioManager.requestAudioFocus(mCarProxyAudioFocusListener, mAttributeCarExternal,
                androidFocus, flags, mAudioPolicy);
    }

    private void doHandleStreamStatusChange(int streamNumber, int state) {
        //TODO
    }

    private boolean isFocusFromCarServiceBottom(AudioFocusInfo info) {
        if (info == null) {
            return false;
        }
        AudioAttributes attrib = info.getAttributes();
        if (info.getPackageName().equals(mContext.getPackageName()) &&
                CarAudioAttributesUtil.getCarUsageFromAudioAttributes(attrib) ==
                CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_BOTTOM) {
            return true;
        }
        return false;
    }

    private boolean isFocusFromCarProxy(AudioFocusInfo info) {
        if (info == null) {
            return false;
        }
        AudioAttributes attrib = info.getAttributes();
        if (info.getPackageName().equals(mContext.getPackageName()) &&
                attrib != null &&
                CarAudioAttributesUtil.getCarUsageFromAudioAttributes(attrib) ==
                CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_CAR_PROXY) {
            return true;
        }
        return false;
    }

    private boolean isFocusFromExternalRadio(AudioFocusInfo info) {
        if (!mIsRadioExternal) {
            // if radio is not external, no special handling of radio is necessary.
            return false;
        }
        if (info == null) {
            return false;
        }
        AudioAttributes attrib = info.getAttributes();
        if (attrib != null &&
                CarAudioAttributesUtil.getCarUsageFromAudioAttributes(attrib) ==
                CarAudioManager.CAR_AUDIO_USAGE_RADIO) {
            return true;
        }
        return false;
    }

    /**
     * Re-evaluate current focus state and send focus request to car if new focus was requested.
     * @return true if focus change was requested to car.
     */
    private boolean reevaluateCarAudioFocusLocked() {
        if (mTopFocusInfo == null) {
            // should not happen
            Log.w(TAG_FOCUS, "reevaluateCarAudioFocusLocked, top focus info null");
            return false;
        }
        if (mTopFocusInfo.getLossReceived() != 0) {
            // top one got loss. This should not happen.
            Log.e(TAG_FOCUS, "Top focus holder got loss " +  dumpAudioFocusInfo(mTopFocusInfo));
            return false;
        }
        if (isFocusFromCarServiceBottom(mTopFocusInfo) || isFocusFromCarProxy(mTopFocusInfo)) {
            switch (mCurrentFocusState.focusState) {
                case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_GAIN:
                case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_GAIN_TRANSIENT:
                    // should not have focus. So enqueue release
                    mFocusHandler.handleFocusReleaseRequest();
                    break;
                case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS:
                    doHandleFocusLossFromCar(mCurrentFocusState, mTopFocusInfo);
                    break;
                case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT:
                    doHandleFocusLossTransientFromCar(mCurrentFocusState);
                    break;
                case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK:
                    doHandleFocusLossTransientCanDuckFromCar(mCurrentFocusState);
                    break;
                case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_EXLCUSIVE:
                    doHandleFocusLossTransientExclusiveFromCar(mCurrentFocusState);
                    break;
            }
            if (mRadioActive) { // radio is no longer active.
                mRadioActive = false;
            }
            return false;
        }
        mFocusHandler.cancelFocusReleaseRequest();
        AudioAttributes attrib = mTopFocusInfo.getAttributes();
        int logicalStreamTypeForTop = CarAudioAttributesUtil.getCarUsageFromAudioAttributes(attrib);
        int physicalStreamTypeForTop = mAudioRoutingPolicy.getPhysicalStreamForLogicalStream(
                (logicalStreamTypeForTop < CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_BOTTOM)
                ? logicalStreamTypeForTop : CarAudioManager.CAR_AUDIO_USAGE_MUSIC);

        boolean muteMedia = false;
        // update primary context and notify if necessary
        int primaryContext = AudioHalService.logicalStreamToHalContextType(logicalStreamTypeForTop);
        switch (logicalStreamTypeForTop) {
            case CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_MEDIA_MUTE:
                muteMedia = true;
                // remaining parts the same with other cases. fall through.
            case CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_BOTTOM:
            case CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_CAR_PROXY:
                primaryContext = 0;
                break;
        }
        // save the current context now but it is sent to context change listener after focus
        // response from car
        if (mCurrentPrimaryAudioContext != primaryContext) {
            mCurrentPrimaryAudioContext = primaryContext;
             mCurrentPrimaryPhysicalStream = physicalStreamTypeForTop;
        }

        int audioContexts = 0;
        if (logicalStreamTypeForTop == CarAudioManager.CAR_AUDIO_USAGE_VOICE_CALL) {
            if (!mCallActive) {
                mCallActive = true;
                audioContexts |= AudioHalService.AUDIO_CONTEXT_CALL_FLAG;
            }
        } else {
            if (mCallActive) {
                mCallActive = false;
            }
            audioContexts = primaryContext;
        }
        // other apps having focus
        int focusToRequest = AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE;
        int extFocus = AudioHalService.VEHICLE_AUDIO_EXT_FOCUS_NONE_FLAG;
        int streamsToRequest = 0x1 << physicalStreamTypeForTop;
        switch (mTopFocusInfo.getGainRequest()) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (isFocusFromExternalRadio(mTopFocusInfo)) {
                    mRadioActive = true;
                } else {
                    mRadioActive = false;
                }
                focusToRequest = AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN;
                break;
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
                // radio cannot be active
                mRadioActive = false;
                focusToRequest = AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT;
                break;
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                focusToRequest =
                    AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT_MAY_DUCK;
                if (mSecondFocusInfo == null) {
                    break;
                }
                AudioAttributes secondAttrib = mSecondFocusInfo.getAttributes();
                if (secondAttrib == null) {
                    break;
                }
                int logicalStreamTypeForSecond =
                        CarAudioAttributesUtil.getCarUsageFromAudioAttributes(secondAttrib);
                if (logicalStreamTypeForSecond ==
                        CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_MEDIA_MUTE) {
                    muteMedia = true;
                    break;
                }
                int secondContext = AudioHalService.logicalStreamToHalContextType(
                        logicalStreamTypeForSecond);
                audioContexts |= secondContext;
                switch (mCurrentFocusState.focusState) {
                    case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_GAIN:
                        streamsToRequest |= mCurrentFocusState.streams;
                        focusToRequest = AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN;
                        break;
                    case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_GAIN_TRANSIENT:
                        streamsToRequest |= mCurrentFocusState.streams;
                        focusToRequest = AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT;
                        break;
                    case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS:
                    case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT:
                    case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK:
                        break;
                    case AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_EXLCUSIVE:
                        doHandleFocusLossTransientExclusiveFromCar(mCurrentFocusState);
                        return false;
                }
                break;
            default:
                streamsToRequest = 0;
                break;
        }
        if (muteMedia) {
            mRadioActive = false;
            audioContexts &= ~(AudioHalService.AUDIO_CONTEXT_RADIO_FLAG |
                    AudioHalService.AUDIO_CONTEXT_MUSIC_FLAG);
            extFocus = AudioHalService.VEHICLE_AUDIO_EXT_FOCUS_CAR_MUTE_MEDIA_FLAG;
            int radioPhysicalStream = mAudioRoutingPolicy.getPhysicalStreamForLogicalStream(
                    CarAudioManager.CAR_AUDIO_USAGE_RADIO);
            streamsToRequest &= ~(0x1 << radioPhysicalStream);
        } else if (mRadioActive) {
            // TODO any need to keep media stream while radio is active?
            //     Most cars do not allow that, but if mixing is possible, it can take media stream.
            //     For now, assume no mixing capability.
            int radioPhysicalStream = mAudioRoutingPolicy.getPhysicalStreamForLogicalStream(
                    CarAudioManager.CAR_AUDIO_USAGE_RADIO);
            if (!isFocusFromExternalRadio(mTopFocusInfo) &&
                    (physicalStreamTypeForTop == radioPhysicalStream) && mIsRadioExternal) {
                Log.i(CarLog.TAG_AUDIO, "Top stream is taking the same stream:" +
                    physicalStreamTypeForTop + " as radio, stopping radio");
                // stream conflict here. radio cannot be played
                extFocus = 0;
                mRadioActive = false;
                audioContexts &= ~AudioHalService.AUDIO_CONTEXT_RADIO_FLAG;
            } else {
                extFocus = AudioHalService.VEHICLE_AUDIO_EXT_FOCUS_CAR_PLAY_ONLY_FLAG;
                streamsToRequest &= ~(0x1 << radioPhysicalStream);
            }
        } else if (streamsToRequest == 0) {
            mCurrentAudioContexts = 0;
            mFocusHandler.handleFocusReleaseRequest();
            return false;
        }
        return sendFocusRequestToCarIfNecessaryLocked(focusToRequest, streamsToRequest, extFocus,
                audioContexts);
    }

    private boolean sendFocusRequestToCarIfNecessaryLocked(int focusToRequest,
            int streamsToRequest, int extFocus, int audioContexts) {
        if (needsToSendFocusRequestLocked(focusToRequest, streamsToRequest, extFocus,
                audioContexts)) {
            mLastFocusRequestToCar = FocusRequest.create(focusToRequest, streamsToRequest,
                    extFocus);
            mCurrentAudioContexts = audioContexts;
            if (DBG) {
                Log.d(TAG_FOCUS, "focus request to car:" + mLastFocusRequestToCar + " context:0x" +
                        Integer.toHexString(audioContexts));
            }
            try {
                mAudioHal.requestAudioFocusChange(focusToRequest, streamsToRequest, extFocus,
                        audioContexts);
            } catch (IllegalArgumentException e) {
                // can happen when mocking ends. ignore. timeout will handle it properly.
            }
            try {
                mLock.wait(mFocusResponseWaitTimeoutMs);
            } catch (InterruptedException e) {
                //ignore
            }
            return true;
        }
        return false;
    }

    private boolean needsToSendFocusRequestLocked(int focusToRequest, int streamsToRequest,
            int extFocus, int audioContexts) {
        if (streamsToRequest != mCurrentFocusState.streams) {
            return true;
        }
        if (audioContexts != mCurrentAudioContexts) {
            return true;
        }
        if ((extFocus & mCurrentFocusState.externalFocus) != extFocus) {
            return true;
        }
        switch (focusToRequest) {
            case AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN:
                if (mCurrentFocusState.focusState ==
                    AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_GAIN) {
                    return false;
                }
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT:
            case AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_GAIN_TRANSIENT_MAY_DUCK:
                if (mCurrentFocusState.focusState ==
                        AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_GAIN ||
                    mCurrentFocusState.focusState ==
                        AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_GAIN_TRANSIENT) {
                    return false;
                }
                break;
            case AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE:
                if (mCurrentFocusState.focusState ==
                        AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS ||
                        mCurrentFocusState.focusState ==
                        AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS_TRANSIENT_EXLCUSIVE) {
                    return false;
                }
                break;
        }
        return true;
    }

    private void doHandleAndroidFocusChange() {
        boolean focusRequested = false;
        synchronized (mLock) {
            if (mPendingFocusChanges.isEmpty()) {
                // no entry. It was handled already.
                if (DBG) {
                    Log.d(TAG_FOCUS, "doHandleAndroidFocusChange, mPendingFocusChanges empty");
                }
                return;
            }
            AudioFocusInfo newTopInfo = mPendingFocusChanges.getFirst();
            mPendingFocusChanges.clear();
            if (mTopFocusInfo != null &&
                    newTopInfo.getClientId().equals(mTopFocusInfo.getClientId()) &&
                    newTopInfo.getGainRequest() == mTopFocusInfo.getGainRequest() &&
                    isAudioAttributesSame(
                            newTopInfo.getAttributes(), mTopFocusInfo.getAttributes())) {
                if (DBG) {
                    Log.d(TAG_FOCUS, "doHandleAndroidFocusChange, no change in top state:" +
                            dumpAudioFocusInfo(mTopFocusInfo));
                }
                // already in top somehow, no need to make any change
                return;
            }
            if (DBG) {
                Log.d(TAG_FOCUS, "top focus changed to:" + dumpAudioFocusInfo(newTopInfo));
            }
            if (newTopInfo.getGainRequest() == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
                mSecondFocusInfo = mTopFocusInfo;
            } else {
                mSecondFocusInfo = null;
            }
            mTopFocusInfo = newTopInfo;
            focusRequested = reevaluateCarAudioFocusLocked();
            if (DBG) {
                if (!focusRequested) {
                    Log.i(TAG_FOCUS, "focus not requested for top focus:" +
                            dumpAudioFocusInfo(newTopInfo) + " currentState:" + mCurrentFocusState);
                }
            }
            if (focusRequested) {
                if (mFocusReceived == null) {
                    Log.w(TAG_FOCUS, "focus response timed out, request sent "
                            + mLastFocusRequestToCar);
                    // no response. so reset to loss.
                    mFocusReceived = FocusState.STATE_LOSS;
                    mCurrentAudioContexts = 0;
                    mNumConsecutiveHalFailures++;
                    mCurrentPrimaryAudioContext = 0;
                    mCurrentPrimaryPhysicalStream = 0;
                } else {
                    mNumConsecutiveHalFailures = 0;
                }
                // send context change after getting focus response.
                if (mCarAudioContextChangeHandler != null) {
                    mCarAudioContextChangeHandler.requestContextChangeNotification(
                            mAudioContextChangeListener, mCurrentPrimaryAudioContext,
                            mCurrentPrimaryPhysicalStream);
                }
                checkCanStatus();
            }
        }
        // handle it if there was response or force handle it for timeout.
        if (focusRequested) {
            doHandleCarFocusChange();
        }
    }

    private void doHandleFocusRelease() {
        //TODO Is there a need to wait for the stopping of streams?
        boolean sent = false;
        synchronized (mLock) {
            if (mCurrentFocusState != FocusState.STATE_LOSS) {
                if (DBG) {
                    Log.d(TAG_FOCUS, "focus release to car");
                }
                mLastFocusRequestToCar = FocusRequest.STATE_RELEASE;
                sent = true;
                try {
                    mAudioHal.requestAudioFocusChange(
                            AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE, 0, 0);
                } catch (IllegalArgumentException e) {
                    // can happen when mocking ends. ignore. timeout will handle it properly.
                }
                try {
                    mLock.wait(mFocusResponseWaitTimeoutMs);
                } catch (InterruptedException e) {
                    //ignore
                }
                mCurrentPrimaryAudioContext = 0;
                mCurrentPrimaryPhysicalStream = 0;
                if (mCarAudioContextChangeHandler != null) {
                    mCarAudioContextChangeHandler.requestContextChangeNotification(
                            mAudioContextChangeListener, mCurrentPrimaryAudioContext,
                            mCurrentPrimaryPhysicalStream);
                }
            } else if (DBG) {
                Log.d(TAG_FOCUS, "doHandleFocusRelease: do not send, already loss");
            }
        }
        // handle it if there was response.
        if (sent) {
            doHandleCarFocusChange();
        }
    }

    private void checkCanStatus() {
        // If CAN bus recovers, message will be removed.
        mCanBusErrorNotifier.setCanBusFailure(
                mNumConsecutiveHalFailures >= mNumConsecutiveHalFailuresForCanError);
    }

    private static boolean isAudioAttributesSame(AudioAttributes one, AudioAttributes two) {
        if (one.getContentType() != two.getContentType()) {
            return false;
        }
        if (one.getUsage() != two.getUsage()) {
            return false;
        }
        return true;
    }

    private static String dumpAudioFocusInfo(AudioFocusInfo info) {
        if (info == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("afi package:" + info.getPackageName());
        builder.append("client id:" + info.getClientId());
        builder.append(",gain:" + info.getGainRequest());
        builder.append(",loss:" + info.getLossReceived());
        builder.append(",flag:" + info.getFlags());
        AudioAttributes attrib = info.getAttributes();
        if (attrib != null) {
            builder.append("," + attrib.toString());
        }
        return builder.toString();
    }

    private class SystemFocusListener extends AudioPolicyFocusListener {
        @Override
        public void onAudioFocusGrant(AudioFocusInfo afi, int requestResult) {
            if (afi == null) {
                return;
            }
            if (DBG) {
                Log.d(TAG_FOCUS, "onAudioFocusGrant " + dumpAudioFocusInfo(afi) +
                        " result:" + requestResult);
            }
            if (requestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                synchronized (mLock) {
                    mPendingFocusChanges.addFirst(afi);
                }
                mFocusHandler.handleAndroidFocusChange();
            }
        }

        @Override
        public void onAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {
            if (DBG) {
                Log.d(TAG_FOCUS, "onAudioFocusLoss " + dumpAudioFocusInfo(afi) +
                        " notified:" + wasNotified);
            }
            // ignore loss as tracking gain is enough. At least bottom listener will be
            // always there and getting focus grant. So it is safe to ignore this here.
        }
    }

    /**
     * Focus listener to take focus away from android apps as a proxy to car.
     */
    private class CarProxyAndroidFocusListener implements AudioManager.OnAudioFocusChangeListener {
        @Override
        public void onAudioFocusChange(int focusChange) {
            // Do not need to handle car's focus loss or gain separately. Focus monitoring
            // through system focus listener will take care all cases.
        }
    }

    /**
     * Focus listener kept at the bottom to check if there is any focus holder.
     *
     */
    private class BottomAudioFocusListener implements AudioManager.OnAudioFocusChangeListener {
        @Override
        public void onAudioFocusChange(int focusChange) {
            synchronized (mLock) {
                mBottomFocusState = focusChange;
            }
        }
    }

    private class MediaMuteAudioFocusListener implements AudioManager.OnAudioFocusChangeListener {

        private final AudioAttributes mMuteAudioAttrib =
                CarAudioAttributesUtil.getAudioAttributesForCarUsage(
                        CarAudioAttributesUtil.CAR_AUDIO_USAGE_CARSERVICE_MEDIA_MUTE);

        /** not muted */
        private final static int MUTE_STATE_UNMUTED = 0;
        /** muted. other app requesting focus GAIN will unmute it */
        private final static int MUTE_STATE_MUTED = 1;
        /** locked. only system can unlock and send it to muted or unmuted state */
        private final static int MUTE_STATE_LOCKED = 2;

        private int mMuteState = MUTE_STATE_UNMUTED;

        @Override
        public void onAudioFocusChange(int focusChange) {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                // mute does not persist when there is other media kind app taking focus
                unMute();
            }
        }

        public boolean mute() {
            return mute(false);
        }

        /**
         * Mute with optional lock
         * @param lock Take focus with lock. Normal apps cannot take focus. Setting this will
         *             essentially mute all audio.
         * @return Final mute state
         */
        public synchronized boolean mute(boolean lock) {
            int result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            boolean lockRequested = false;
            if (lock) {
                AudioPolicy audioPolicy = null;
                synchronized (CarAudioService.this) {
                    audioPolicy = mAudioPolicy;
                }
                if (audioPolicy != null) {
                    result =  mAudioManager.requestAudioFocus(this, mMuteAudioAttrib,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                            AudioManager.AUDIOFOCUS_FLAG_LOCK |
                            AudioManager.AUDIOFOCUS_FLAG_DELAY_OK,
                            audioPolicy);
                    lockRequested = true;
                }
            }
            if (!lockRequested) {
                result = mAudioManager.requestAudioFocus(this, mMuteAudioAttrib,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                        AudioManager.AUDIOFOCUS_FLAG_DELAY_OK);
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ||
                    result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                if (lockRequested) {
                    mMuteState = MUTE_STATE_LOCKED;
                } else {
                    mMuteState = MUTE_STATE_MUTED;
                }
            } else {
                mMuteState = MUTE_STATE_UNMUTED;
            }
            return mMuteState != MUTE_STATE_UNMUTED;
        }

        public boolean unMute() {
            return unMute(false);
        }

        /**
         * Unmute. If locked, unmute will only succeed when unlock is set to true.
         * @param unlock
         * @return Final mute state
         */
        public synchronized boolean unMute(boolean unlock) {
            if (!unlock && mMuteState == MUTE_STATE_LOCKED) {
                // cannot unlock
                return true;
            }
            mMuteState = MUTE_STATE_UNMUTED;
            mAudioManager.abandonAudioFocus(this);
            return false;
        }

        public synchronized boolean isMuted() {
            return mMuteState != MUTE_STATE_UNMUTED;
        }
    }

    private class CarAudioContextChangeHandler extends Handler {
        private static final int MSG_CONTEXT_CHANGE = 0;

        private CarAudioContextChangeHandler(Looper looper) {
            super(looper);
        }

        private void requestContextChangeNotification(AudioContextChangeListener listener,
                int primaryContext, int physicalStream) {
            Message msg = obtainMessage(MSG_CONTEXT_CHANGE, primaryContext, physicalStream,
                    listener);
            sendMessage(msg);
        }

        private void cancelAll() {
            removeMessages(MSG_CONTEXT_CHANGE);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CONTEXT_CHANGE: {
                    AudioContextChangeListener listener = (AudioContextChangeListener) msg.obj;
                    int context = msg.arg1;
                    int physicalStream = msg.arg2;
                    listener.onContextChange(context, physicalStream);
                } break;
            }
        }
    }

    private class CarAudioFocusChangeHandler extends Handler {
        private static final int MSG_FOCUS_CHANGE = 0;
        private static final int MSG_STREAM_STATE_CHANGE = 1;
        private static final int MSG_ANDROID_FOCUS_CHANGE = 2;
        private static final int MSG_FOCUS_RELEASE = 3;

        /** Focus release is always delayed this much to handle repeated acquire / release. */
        private static final long FOCUS_RELEASE_DELAY_MS = 500;

        private CarAudioFocusChangeHandler(Looper looper) {
            super(looper);
        }

        private void handleFocusChange() {
            Message msg = obtainMessage(MSG_FOCUS_CHANGE);
            sendMessage(msg);
        }

        private void handleStreamStateChange(int streamNumber, int state) {
            Message msg = obtainMessage(MSG_STREAM_STATE_CHANGE, streamNumber, state);
            sendMessage(msg);
        }

        private void handleAndroidFocusChange() {
            Message msg = obtainMessage(MSG_ANDROID_FOCUS_CHANGE);
            sendMessage(msg);
        }

        private void handleFocusReleaseRequest() {
            if (DBG) {
                Log.d(TAG_FOCUS, "handleFocusReleaseRequest");
            }
            cancelFocusReleaseRequest();
            Message msg = obtainMessage(MSG_FOCUS_RELEASE);
            sendMessageDelayed(msg, FOCUS_RELEASE_DELAY_MS);
        }

        private void cancelFocusReleaseRequest() {
            removeMessages(MSG_FOCUS_RELEASE);
        }

        private void cancelAll() {
            removeMessages(MSG_FOCUS_CHANGE);
            removeMessages(MSG_STREAM_STATE_CHANGE);
            removeMessages(MSG_ANDROID_FOCUS_CHANGE);
            removeMessages(MSG_FOCUS_RELEASE);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_FOCUS_CHANGE:
                    doHandleCarFocusChange();
                    break;
                case MSG_STREAM_STATE_CHANGE:
                    doHandleStreamStatusChange(msg.arg1, msg.arg2);
                    break;
                case MSG_ANDROID_FOCUS_CHANGE:
                    doHandleAndroidFocusChange();
                    break;
                case MSG_FOCUS_RELEASE:
                    doHandleFocusRelease();
                    break;
            }
        }
    }

    /** Wrapper class for holding the current focus state from car. */
    private static class FocusState {
        public final int focusState;
        public final int streams;
        public final int externalFocus;

        private FocusState(int focusState, int streams, int externalFocus) {
            this.focusState = focusState;
            this.streams = streams;
            this.externalFocus = externalFocus;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FocusState)) {
                return false;
            }
            FocusState that = (FocusState) o;
            return this.focusState == that.focusState && this.streams == that.streams &&
                    this.externalFocus == that.externalFocus;
        }

        @Override
        public String toString() {
            return "FocusState, state:" + focusState +
                    " streams:0x" + Integer.toHexString(streams) +
                    " externalFocus:0x" + Integer.toHexString(externalFocus);
        }

        public static FocusState create(int focusState, int streams, int externalAudios) {
            return new FocusState(focusState, streams, externalAudios);
        }

        public static FocusState create(int[] state) {
            return create(state[AudioHalService.FOCUS_STATE_ARRAY_INDEX_STATE],
                    state[AudioHalService.FOCUS_STATE_ARRAY_INDEX_STREAMS],
                    state[AudioHalService.FOCUS_STATE_ARRAY_INDEX_EXTERNAL_FOCUS]);
        }

        public static FocusState STATE_LOSS =
                new FocusState(AudioHalService.VEHICLE_AUDIO_FOCUS_STATE_LOSS, 0, 0);
    }

    /** Wrapper class for holding the focus requested to car. */
    private static class FocusRequest {
        public final int focusRequest;
        public final int streams;
        public final int externalFocus;

        private FocusRequest(int focusRequest, int streams, int externalFocus) {
            this.focusRequest = focusRequest;
            this.streams = streams;
            this.externalFocus = externalFocus;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FocusRequest)) {
                return false;
            }
            FocusRequest that = (FocusRequest) o;
            return this.focusRequest == that.focusRequest && this.streams == that.streams &&
                    this.externalFocus == that.externalFocus;
        }

        @Override
        public String toString() {
            return "FocusRequest, request:" + focusRequest +
                    " streams:0x" + Integer.toHexString(streams) +
                    " externalFocus:0x" + Integer.toHexString(externalFocus);
        }

        public static FocusRequest create(int focusRequest, int streams, int externalFocus) {
            switch (focusRequest) {
                case AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE:
                    return STATE_RELEASE;
            }
            return new FocusRequest(focusRequest, streams, externalFocus);
        }

        public static FocusRequest STATE_RELEASE =
                new FocusRequest(AudioHalService.VEHICLE_AUDIO_FOCUS_REQUEST_RELEASE, 0, 0);
    }
}
