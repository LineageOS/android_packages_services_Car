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
package com.android.car.power;

import android.annotation.NonNull;
import android.car.Car;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.car.hardware.power.ICarPowerStateListener;
import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarServiceBase;
import com.android.car.ICarImpl;
import com.android.car.systeminterface.SystemInterface;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.util.FunctionalUtils;

import libcore.io.IoUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Class to handle Silent and Non-silent modes.
 *
 * <p>Notifies others when the mode changes.
 */
public final class SilentModeController implements CarServiceBase {
    private static final String TAG = CarLog.tagFor(SilentModeController.class);

    private static final String FORCED_NON_SILENT = "reboot,forcednonsilent";
    private static final String FORCED_SILENT = "reboot,forcedsilent";
    private static final int MSG_SEND_NONSILENT = 0;
    private static final int MSG_SEND_NONSILENT_TO_ONE = 1;
    private static final int MSG_SEND_SILENT = 2;
    private static final int MSG_SEND_SILENT_TO_ONE = 3;
    private static final String SILENT_BOOT_FILENAME = "/sys/power/kernelsilentboot";
    private static final String SYSTEM_BOOT_REASON = "sys.boot.reason";

    private final Object mLock = new Object();
    private final String mSilentBootFileName;
    private final Context mContext;
    private final SystemInterface mSystemInterface;
    private final HandlerThread mNotifierThread = new HandlerThread("SilentModeContollerNotifier");
    private final IVoiceInteractionManagerService mVoiceInteractionManagerService;
    private final ICarPowerStateListener mPowerListener = new ICarPowerStateListener.Stub() {
        @Override
        public void onStateChanged(int state) {
            synchronized (mLock) {
                boolean powerWasOn = mPowerStateIsOn;
                mPowerStateIsOn = (state == CarPowerStateListener.ON);
                if (powerWasOn != mPowerStateIsOn) {
                    queueNotificationLocked();
                }
            }
        }
    };

    private CarPowerManagementService mCarPowerManagementService;
    private FileObserver mFileObserver;
    private Handler mNotifier;

    @GuardedBy("mLock")
    private boolean mIsInitialized;
    // mIsModeForced is true when an ADB command was used to reboot the system
    // into either silent or non-silent mode.
    // If mIsModeForced we ignore the sysfs file's initial value.
    // If the sysfs file is updated by the kernel or 'setSilentMode' is called
    // by the VHAL, we clear mIsModeForced and respond.
    @GuardedBy("mLock")
    private boolean mIsModeForced;
    @GuardedBy("mLock")
    private boolean mIsModeForcedSilent;
    @GuardedBy("mLock")
    private final ArrayList<SilentModeListener> mSilentModeListeners = new ArrayList<>();
    @GuardedBy("mLock")
    private boolean mPowerStateIsOn;
    // Does the VHAL allow us to be Silent, or does it require us to be active?
    @GuardedBy("mLock")
    private boolean mExternalControlAllowsSilent = true;
    @GuardedBy("mLock")
    private boolean mKernelAllowsSilent;

    public SilentModeController(@NonNull Context context,
            @NonNull SystemInterface systemInterface) {
        this(context, systemInterface,
                IVoiceInteractionManagerService.Stub.asInterface(
                        ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE)),
                SILENT_BOOT_FILENAME,
                SystemProperties.get(SYSTEM_BOOT_REASON));
    }

    @VisibleForTesting
    public SilentModeController(@NonNull Context context, @NonNull SystemInterface systemInterface,
            @NonNull IVoiceInteractionManagerService voiceService, @NonNull String fileName) {
        this(context, systemInterface, voiceService, fileName, "");
    }

    private SilentModeController(@NonNull Context context, @NonNull SystemInterface systemInterface,
            @NonNull IVoiceInteractionManagerService voiceService, @NonNull String fileName,
            String bootReason) {
        Objects.requireNonNull(context, "Context must be non-null");
        Objects.requireNonNull(systemInterface, "SystemInterface must be non-null");
        Objects.requireNonNull(fileName, "File name must be non-null");
        mContext = context;
        mSystemInterface = systemInterface;
        mVoiceInteractionManagerService = voiceService;
        mSilentBootFileName = fileName;
        startNotifierThread();
        // The boot reason property takes precedence
        if (FORCED_SILENT.equals(bootReason)) {
            Slog.i(TAG, "Starting in forced silent mode");
            mIsModeForced = true;
            mIsModeForcedSilent = true;
        } else if (FORCED_NON_SILENT.equals(bootReason)) {
            Slog.i(TAG, "Starting in forced non-silent mode");
            mIsModeForced = true;
            mIsModeForcedSilent = false;
        } else {
            mIsModeForced = false;
            // mIsModeForcedSilent is ignored when !mIsModeForced
        }
        startSilentBootFileObserver();
    }

    @Override
    public void init() {
        mCarPowerManagementService =
                CarLocalServices.getService(CarPowerManagementService.class);
        Objects.requireNonNull(mCarPowerManagementService, "Cannot get CarPowerManagementService");
        mCarPowerManagementService.registerListener(mPowerListener);
        synchronized (mLock) {
            // Get the initial power state
            mPowerStateIsOn =
                    (mCarPowerManagementService.getPowerState() == CarPowerStateListener.ON);
            mIsInitialized = true;
            queueNotificationLocked();
        }
    }

    @Override
    public void release() {
        synchronized (mLock) {
            if (mFileObserver != null) {
                mFileObserver.stopWatching();
                mFileObserver = null;
            }
            if (mCarPowerManagementService != null) {
                mCarPowerManagementService.unregisterListener(mPowerListener);
            }
            mSilentModeListeners.clear();
        }
    }

    @Override
    public void dump(IndentingPrintWriter writer) {
        boolean isSilent;
        boolean powerStateIsOn;
        boolean kernelAllowsSilent;
        boolean externalControlAllowsSilent;
        boolean isForced;
        FileObserver fileObserver;
        int numListeners;

        synchronized (mLock) {
            isSilent = isModeSilentLocked();
            powerStateIsOn = mPowerStateIsOn;
            kernelAllowsSilent = mKernelAllowsSilent;
            externalControlAllowsSilent = mExternalControlAllowsSilent;
            isForced = mIsModeForced;
            fileObserver = mFileObserver;
            numListeners = mSilentModeListeners.size();
        }
        writer.printf("SilentMode is %s%s\n", (isSilent ? "silent" : "non-silent"),
                (isForced ? ", forced by reboot command or test" : ""));
        writer.printf("Silent indicators: System power: %s, Kernel: %s, VHAL: %s\n",
                (powerStateIsOn ? "On" : "not On"),
                (kernelAllowsSilent ? "silent" : "non-silent"),
                (externalControlAllowsSilent ? "silent" : "non-silent"));
        writer.printf("SilentModeController %s monitoring %s\n",
                ((fileObserver == null) ? "is not" : "is"), mSilentBootFileName);
        writer.printf("SilentModeController has %d listeners registered\n", numListeners);
    }

    /**
     * Indicates whether the current mode is silent.
     */
    public boolean isSilent() {
        verifyPermission();
        synchronized (mLock) {
            return isModeSilentLocked();
        }
    }

    /**
     * Registers a listener to be called when the Silent state changes.
     */
    public void registerListener(SilentModeListener listener) {
        verifyPermission();
        Message message = Message.obtain();
        message.obj = listener;
        synchronized (mLock) {
            message.what = isModeSilentLocked()
                    ? MSG_SEND_SILENT_TO_ONE : MSG_SEND_NONSILENT_TO_ONE;
            mNotifier.sendMessage(message);
            mSilentModeListeners.add(listener);
        }
    }

    /**
     * Unregisters a listener for Silent mode changes.
     */
    public void unregisterListener(SilentModeListener listener) {
        verifyPermission();
        synchronized (mLock) {
            mSilentModeListeners.remove(listener);
        }
    }

    /**
     * Sets the power state for Silent Mode
     *
     * Helps to test Silent Mode without CarPowerManagementService
     */
    @VisibleForTesting
    public void setPowerOnForTest(boolean isOn) {
        synchronized (mLock) {
            if (mPowerStateIsOn != isOn) {
                mPowerStateIsOn = isOn;
                queueNotificationLocked();
            }
        }
    }

    // Called only from the constructor
    private void startSilentBootFileObserver() {
        // This is called only from the constructor, so we don't
        // need to hold mLock here.
        File monitorFile = new File(mSilentBootFileName);
        if (!monitorFile.exists()) {
            mFileObserver = null;
            mKernelAllowsSilent = false;
            if (mIsModeForced) {
                Slog.w(TAG, "\"" + mSilentBootFileName + "\" does not exist");
            } else {
                Slog.w(TAG, "\"" + mSilentBootFileName
                        + "\" does not exist. Assuming non-silent mode.");
            }
            return;
        }
        // Create an observer to run whenever the file is modified
        mFileObserver = new FileObserver(monitorFile, FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String fileName) {
                synchronized (mLock) {
                    boolean kernelAllowedSilent = mKernelAllowsSilent;
                    boolean weCanceledForcing = false;
                    if (mIsInitialized && mIsModeForced) {
                        // A GPIO update cancels the forcing
                        mIsModeForced = false;
                        weCanceledForcing = true;
                    }
                    try {
                        String contents = IoUtils.readFileAsString(mSilentBootFileName).trim();
                        mKernelAllowsSilent = contents.equals("1");
                        Slog.i(TAG, mSilentBootFileName + " indicates "
                                + (mKernelAllowsSilent ? "silent" : "non-silent") + " mode");
                    } catch (Exception ee) {
                        mKernelAllowsSilent = false;
                        Slog.w(TAG, "Exception reading " + mSilentBootFileName + ": " + ee);
                    }
                    if ((kernelAllowedSilent != mKernelAllowsSilent) || weCanceledForcing) {
                        queueNotificationLocked();
                    }
                }
            }
        };
        mFileObserver.startWatching();
        // Trigger the observer to get the initial contents
        mFileObserver.onEvent(FileObserver.MODIFY, mSilentBootFileName);
    }

    private void verifyPermission() {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CAR_POWER);
    }

    private void queueNotificationLocked() {
        if (mIsInitialized) {
            int messageWhat = isModeSilentLocked() ? MSG_SEND_SILENT : MSG_SEND_NONSILENT;
            mNotifier.sendEmptyMessage(messageWhat);
        }
    }

    /**
     * Functional interface for receiving Silent mode notifications.
     */
    public interface SilentModeListener {
        /**
         * Called when the Silent Mode changes.
         *
         * <p>This may also be called to reiterate the current state,
         * so listeners should not assume that the state is different
         * from the previous state.
         */
        void onModeChange(boolean isSilent);
    }

    /**
     * Indicates that Silent Mode is allowed by the Vehicle HAL.
     *
     * <p>This is intended to be used by the Vehicle HAL if it wants the
     * system to go non-silent.
     * <p>Note that the system power state and the signal from the kernel
     * both play a part in the ultimate silent/non-silent decision.
     */
    public void setSilentModeAllowed(boolean allowSilent) {
        verifyPermission();
        synchronized (mLock) {
            boolean isAChange = mIsModeForced || (mExternalControlAllowsSilent != allowSilent);
            mIsModeForced = false;
            mExternalControlAllowsSilent = allowSilent;
            if (isAChange) {
                queueNotificationLocked();
            }
        }
    }

    /**
     * Forces Silent Mode on or off, ignoring the GPIO and VHAL.
     *
     * <p>Note that the system power state plays a part in the
     * ultimate silent/non-silent decision.
     */
    @VisibleForTesting
    public void forceSilentMode(boolean isSilent) {
        verifyPermission();
        synchronized (mLock) {
            mIsModeForced = true;
            mIsModeForcedSilent = isSilent;
            queueNotificationLocked();
        }
    }

    /**
     * Stops forcing Silent Mode.
     *
     * <p>Restores the logic that considers the GPIO and VHAL in determining
     * the ultimate silent/non-silent decision.
     */
    @VisibleForTesting
    public void unforceSilentMode() {
        verifyPermission();
        synchronized (mLock) {
            mIsModeForced = false;
            // 'mIsModeForcedSilent' is unused
            queueNotificationLocked();
        }
    }

    private boolean isModeSilentLocked() {
        // Returns true if the display and audio should be off
        if (!mPowerStateIsOn) {
            return true;
        }
        if (mIsModeForced) {
            return mIsModeForcedSilent;
        }
        return (mKernelAllowsSilent && mExternalControlAllowsSilent);
    }

    // Called only from the constructor
    private void startNotifierThread() {
        mNotifierThread.start();
        // Notify the listeners on a separate thread to ensure that
        // notifications are serialized and in order
        mNotifier = new Handler(mNotifierThread.getLooper()) {
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_SEND_SILENT:
                    case MSG_SEND_NONSILENT:
                        processSilentModeChange(msg.what == MSG_SEND_SILENT);
                        break;
                    case MSG_SEND_SILENT_TO_ONE:
                    case MSG_SEND_NONSILENT_TO_ONE:
                        if (!(msg.obj instanceof SilentModeListener)) {
                            Slog.e(TAG, "Bad listener given to LooperThread");
                            return;
                        }
                        notifyOneListener((SilentModeListener) msg.obj,
                                msg.what == MSG_SEND_SILENT_TO_ONE);
                        break;
                    default:
                        Slog.w(TAG, "Unknown message " + msg.what);
                }
            };
        };
    }

    // Called only on the notifier thread
    private void processSilentModeChange(boolean silentState) {
        // Control the display/audio and notify listeners.
        // The notifications are sent when the devices are off.
        if (silentState) {
            // Turn off the display/audio first
            switchDisplayAndAudio(true);
            notifyAllListeners(true);
        } else {
            // Turn on the display/audio last
            notifyAllListeners(false);
            switchDisplayAndAudio(false);
        }
    }

    // Called only on the notifier thread
    private void notifyAllListeners(boolean silentState) {
        ArrayList<SilentModeListener> listeners;
        synchronized (mLock) {
            listeners = (ArrayList<SilentModeListener>) mSilentModeListeners.clone();
        }
        Slog.i(TAG, "Sending \"" + (silentState ? "silent" : "non-silent")
                + "\" to " + listeners.size() + " listeners");
        for (int idx = 0; idx < listeners.size(); idx++) {
            notifyOneListener(listeners.get(idx), silentState);
        }
    }

    // Called only on the notifier thread
    private void notifyOneListener(SilentModeListener listener, boolean silentState) {
        try {
            listener.onModeChange(silentState);
        } catch (Exception e) {
            Slog.w(TAG, "Exception thrown by listener "
                    + FunctionalUtils.getLambdaName(listener), e);
        }
    }

    // Called only on the notifier thread
    private void switchDisplayAndAudio(boolean silentState) {
        mSystemInterface.setDisplayState(!silentState);
        try {
            mVoiceInteractionManagerService.setDisabled(silentState);
        } catch (RemoteException e) {
            Slog.w(TAG, "IVoiceInteractionManagerServicesetDisabled("
                    + silentState + ") failed", e);
        }
    }
}
