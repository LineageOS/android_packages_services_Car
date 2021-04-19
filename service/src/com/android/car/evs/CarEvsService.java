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

package com.android.car.evs;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.Car;
import android.car.evs.CarEvsBufferDescriptor;
import android.car.evs.CarEvsManager;
import android.car.evs.CarEvsManager.CarEvsError;
import android.car.evs.CarEvsManager.CarEvsServiceState;
import android.car.evs.CarEvsManager.CarEvsServiceType;
import android.car.evs.CarEvsManager.CarEvsStreamEvent;
import android.car.evs.CarEvsStatus;
import android.car.evs.ICarEvsService;
import android.car.evs.ICarEvsStatusListener;
import android.car.evs.ICarEvsStreamCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.car.CarLog;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.hal.EvsHalService;
import com.android.car.ICarImpl;
import com.android.car.R;
import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

/**
 * A service that listens to the Extended View System across a HAL boundary and exposes the data to
 * system clients in Android via {@link android.car.evs.CarEvsManager}.
 *
 * Because of Fast Message Queue usages, android.hardware.automotive.evs@1.1 interfaces does not
 * support Java backend and, therefore, actual API calls are done in native methods.
 */
public final class CarEvsService extends android.car.evs.ICarEvsService.Stub
        implements CarServiceBase, EvsHalService.EvsHalEventListener {

    private static final boolean DBG = Log.isLoggable(CarLog.TAG_EVS, Log.DEBUG);

    private static final int STREAM_STOPPED_WAIT_TIMEOUT_MS = 500;
    private static final int BUFFER_NOT_EXIST = -1;

    // Messages for the worker thread
    private static final int MSG_STOP_SERVICE = 0;
    private static final int MSG_START_SERVICE = 1;
    private static final int MSG_NOTIFY_STATUS_CHANGED = 2;

    private final Context mContext;
    private final EvsHalService mEvsHalService;
    private final Object mLock = new Object();

    private final HandlerThread mWorkerThread =
            CarServiceUtils.getHandlerThread(CarEvsService.class.getSimpleName());
    private final Handler mWorker =
            new ServiceWorker(this, mWorkerThread.getLooper());

    // Bookkeeps received frame buffers
    private final SparseIntArray mBufferRecords = new SparseIntArray();

    private final class StatusListenerList extends RemoteCallbackList<ICarEvsStatusListener> {
        private final WeakReference<CarEvsService> mService;

        StatusListenerList(CarEvsService evsService) {
            mService = new WeakReference<>(evsService);
        }

        /** Handle callback death */
        @Override
        public void onCallbackDied(ICarEvsStatusListener listener) {
            Slog.w(CarLog.TAG_EVS, "StatusListener has died: " + listener.asBinder());

            CarEvsService svc = mService.get();
            if (svc != null) {
                svc.handleClientDisconnected();
            }
        }
    }

    private final StatusListenerList mStatusListeners = new StatusListenerList(this);

    private final class StreamCallbackList extends RemoteCallbackList<ICarEvsStreamCallback> {
        private final WeakReference<CarEvsService> mService;

        StreamCallbackList(CarEvsService evsService) {
            mService = new WeakReference<>(evsService);
        }

        /** Handle callback death */
        @Override
        public void onCallbackDied(ICarEvsStreamCallback callback) {
            Slog.w(CarLog.TAG_EVS, "StreamCallback has died: " + callback.asBinder());

            CarEvsService svc = mService.get();
            if (svc != null) {
                svc.handleClientDisconnected();
            }
        }
    }

    // TODO(b/178741919): Considers using java.util.ArrayList with a lock instead of
    // RemoteCallbackList to avoid double-lock situation.
    @GuardedBy("mLock")
    private final StreamCallbackList mStreamCallbacks = new StreamCallbackList(this);

    // Current service status
    @GuardedBy("mLock")
    private @CarEvsServiceState int mState = CarEvsManager.SERVICE_STATE_UNAVAILABLE;

    // Current service type
    @GuardedBy("mLock")
    private @CarEvsServiceType int mServiceType = CarEvsManager.SERVICE_TYPE_REARVIEW;

    /** Creates an Extended View System service instance given a {@link Context}. */
    public CarEvsService(Context context, EvsHalService halService) {
        mContext = context;

        // TODO(b/183674444): Uses GEAR_SELECTION property to control the rearview camera if
        //                    EVS_SERVICE_REQUEST is not supported.
        mEvsHalService = halService;
    }

    @Override
    public void init() {
        if (DBG) {
            Slog.d(CarLog.TAG_EVS, "Initializing the service");
        }

        // Below registration may throw IllegalStateException if neither of EVS_SERVICE_REQUEST nor
        // GEAR_SELECTION VHAL properties are not supported.
        mEvsHalService.setListener(this);

        try {
            if (!nativeConnectToHalServiceIfNecessary()) {
                Slog.e(CarLog.TAG_EVS, "Failed to connect to EVS service");
            }
        } catch (RuntimeException e) {
            Slog.e(CarLog.TAG_EVS, Log.getStackTraceString(e));
        }
    }

    @Override
    public void release() {
        if (DBG) {
            Slog.d(CarLog.TAG_EVS, "Finalizing the service");
        }

        mStatusListeners.kill();
        mStreamCallbacks.kill();

        nativeDisconnectFromHalService();
    }

    @Override
    public void dump(IndentingPrintWriter writer) {
        writer.println("*CarEvsService*");
        writer.printf("%s to HAL service",
                mNativeEvsServiceObj == 0 ? "Not connected" : "Connected");
        writer.printf("%d stream listeners subscribed.",
                mStreamCallbacks.getRegisteredCallbackCount());
        writer.printf("%d service listeners subscribed.",
                mStatusListeners.getRegisteredCallbackCount());

        // TODO(b/177923530): Dump more status information
    }

    /**
     * Implements EvsHalService.EvsHalEventListener to monitor VHAL properties.
     */
    @Override
    public void onEvent(@CarEvsServiceType int type, boolean on) {
        if (DBG) {
            Slog.d(CarLog.TAG_EVS,
                  "Received an event from EVS HAL: type = " + type + ", on = " + on);
        }


        // Send a status update as requested
        int newStatus;
        if (on) {
            newStatus = CarEvsManager.SERVICE_STATE_REQUESTED;
        } else {
            newStatus = CarEvsManager.SERVICE_STATE_INACTIVE;
        }

        synchronized (mLock) {
            mState = newStatus;
            mServiceType = type;
        }

        mWorker.sendMessage(mWorker.obtainMessage(MSG_NOTIFY_STATUS_CHANGED,
                  new Integer[]{type, newStatus}));
    }

    /**
     * A worker to handle viewer and service requests.
     */
    private static final class ServiceWorker extends Handler {
        private final WeakReference<CarEvsService> mService;

        ServiceWorker(CarEvsService service, Looper looper) {
            super(looper);
            mService = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            CarEvsService svc = mService.get();
            if (svc != null) {
                switch (msg.what) {
                    case MSG_START_SERVICE:
                        svc.startService((int) msg.obj);
                        break;

                    case MSG_STOP_SERVICE:
                        svc.stopService();
                        break;

                    case MSG_NOTIFY_STATUS_CHANGED:
                        svc.notifyStatusChanged((Integer[]) msg.obj);
                        break;

                    default:
                        throw new RuntimeException("Unknown message: " + msg.what);
                }
            }
        }
    }

    private void startService(@CarEvsServiceType int type) {
        // TODO(b/179029031): Removes below when Surround View service is integrated.
        if (type == CarEvsManager.SERVICE_TYPE_SURROUNDVIEW) {
            throw new IllegalArgumentException("Surround view is not supported yet");
        }

        try {
            if (!nativeConnectToHalServiceIfNecessary()) {
                Slog.e(CarLog.TAG_EVS, "Failed to connect to EVS service");
            }

            // A target camera device may not be opened yet.
            // TODO(b/183671694): Replace below raw string usage with the enum.
            if (!nativeOpenCamera(mContext.getString(R.string.config_evsRearviewCameraId))) {
                Slog.e(CarLog.TAG_EVS, "Failed to open a target camera device");
                handleStartServiceFailure();
                return;
            }

            if (!nativeRequestToStartVideoStream()) {
                Slog.e(CarLog.TAG_EVS, "Failed to start a video stream");
                handleStartServiceFailure();
            } else {
                if (DBG) {
                    Slog.d(CarLog.TAG_EVS, "EVS video stream is started");
                }

                synchronized (mLock) {
                    mState = CarEvsManager.SERVICE_STATE_ACTIVE;
                }
            }
        } catch (RuntimeException e) {
            Slog.e(CarLog.TAG_EVS, Log.getStackTraceString(e));
            handleStartServiceFailure();
        }
    }

    private void stopService() {
        try {
            nativeRequestToStopVideoStream();
        } catch (RuntimeException e) {
            Slog.e(CarLog.TAG_EVS, Log.getStackTraceString(e));
        } finally {
            synchronized (mLock) {
                mState = CarEvsManager.SERVICE_STATE_INACTIVE;
            }
        }
    }

    private void handleStartServiceFailure() {
        synchronized (mLock) {
            mState = CarEvsManager.SERVICE_STATE_INACTIVE;
        }
    }

    /** Notifies the service status gets changed */
    private void notifyStatusChanged(Integer[] args) {
        int idx = mStatusListeners.beginBroadcast();
        while (idx-- > 0) {
            ICarEvsStatusListener listener = mStatusListeners.getBroadcastItem(idx);
            try {
                listener.onStatusChanged(
                        new CarEvsStatus(/* type = */ args[0], /* state = */ args[1]));
            } catch (RemoteException e) {
                // Likely the binder death incident
                Slog.e(CarLog.TAG_EVS, Log.getStackTraceString(e));
            }
        }
    }

    /**
     * Registers a {@link ICarEvsStatusListener} to listen requests to control the camera
     * previewing activity.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_MONITOR_CAR_EVS_STATUS} permissions to
     * access.
     *
     * @param listener {@link ICarEvsStatusListener} listener to register.
     */
    @Override
    public void registerStatusListener(@NonNull ICarEvsStatusListener listener) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_MONITOR_CAR_EVS_STATUS);
        Objects.requireNonNull(listener);

        try {
            if (!nativeConnectToHalServiceIfNecessary()) {
                Slog.e(CarLog.TAG_EVS, "Failed to connect to EVS service");
                return;
            }

            if (DBG) {
                Slog.d(CarLog.TAG_EVS, "Registering a new service listener");
            }

            mStatusListeners.register(listener);
        } catch (RuntimeException e) {
            Slog.e(CarLog.TAG_EVS, Log.getStackTraceString(e));
        }
    }

    /**
     * Unregister the given {@link ICarEvsStatusListener} listener from receiving events.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_MONITOR_CAR_EVS_STATUS} permissions to
     * access.
     *
     * @param listener {@link ICarEvsStatusListener} listener to unregister.
     */
    @Override
    public void unregisterStatusListener(@NonNull ICarEvsStatusListener listener) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_MONITOR_CAR_EVS_STATUS);
        Objects.requireNonNull(listener);

        mStatusListeners.unregister(listener);
    }

    /**
     * Requests the system to start an activity to show the preview from a given EVS service type.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_REQUEST_CAR_EVS_ACTIVITY} permissions to
     * access.
     *
     * @param type {@link android.car.evs.CarEvsManager#CarEvsServiceType}
     * @return {@link android.car.evs.CarEvsManager#CarEvsError}
     */
    @Override
    public @CarEvsError int startActivity(int type) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_REQUEST_CAR_EVS_ACTIVITY);

        return CarEvsManager.ERROR_UNAVAILABLE;
    }

    /**
     * Requests to stop a current previewing activity launched via {@link #startActivity}.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_REQUEST_CAR_EVS_ACTIVITY} permissions to
     * access.
     */
    @Override
    public void stopActivity() {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_REQUEST_CAR_EVS_ACTIVITY);

        return;
    }

    /**
     * Starts a video stream.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_USE_CAR_EVS_CAMERA} permissions to access.
     *
     * @param type {@link android.car.evs.CarEvsManager#CarEvsServiceType}
     * @param token IBinder object as a session token.  If this is not null, CarEvsService handles a
     *              coming client as a privileged client.
     * @param callback {@link ICarEvsStreamCallback} listener to register.
     * @return {@link android.car.evs.CarEvsManager.CarEvsError}
     */
    @Override
    public @CarEvsError int startVideoStream(@CarEvsServiceType int type, @Nullable IBinder token,
            @NonNull ICarEvsStreamCallback callback) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_CAMERA);
        Objects.requireNonNull(callback);

        if (type == CarEvsManager.SERVICE_TYPE_SURROUNDVIEW) {
            return CarEvsManager.ERROR_UNAVAILABLE;
        }

        // TODO(b/179498566): Identifies the requesting client (or its callback object)
        //                    with a given session token.
        // TODO(b/183672792): CarEvsService should allow only the single streaming client to be
        //                    active.
        mStreamCallbacks.register(callback);
        mWorker.sendMessage(mWorker.obtainMessage(
                MSG_START_SERVICE, CarEvsManager.SERVICE_TYPE_REARVIEW));

        return CarEvsManager.ERROR_NONE;
    }

    /**
     * Requests to stop a video stream from the current service.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_USE_CAR_EVS_CAMERA} permissions to access.
     *
     * @param callback {@link ICarEvsStreamCallback} listener to unregister.
     */
    @Override
    public void stopVideoStream(@NonNull ICarEvsStreamCallback callback) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_CAMERA);
        Objects.requireNonNull(callback);

        mWorker.sendMessage(mWorker.obtainMessage(
                MSG_STOP_SERVICE, CarEvsManager.SERVICE_TYPE_REARVIEW));

        mStreamCallbacks.unregister(callback);
    }

    /**
     * Returns an used buffer to EVS service.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_USE_CAR_EVS_CAMERA} permissions to access.
     *
     * @param bufferId An unique 32-bit integer identifier of the buffer to return.
     * @throws IllegalArgumentException if a passed buffer has an unregistered identifier.
     */
    @Override
    public void returnFrameBuffer(int bufferId) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_CAMERA);

        synchronized (mLock) {
            int record = mBufferRecords.get(bufferId, /* valueIfKeyNotFound = */ BUFFER_NOT_EXIST);
            if (record == BUFFER_NOT_EXIST) {
                throw new IllegalArgumentException(
                        "Unknown buffer is returned, id = " + bufferId);
            }

            // Decreases the reference count
            record = record - 1;
            if (record > 0) {
                // This buffer is still being used by other clients.
                mBufferRecords.put(bufferId, record);
                return;
            }

            mBufferRecords.removeAt(mBufferRecords.indexOfKey(bufferId));
        }

        // This may throw a NullPointerException if the native EVS service handle is invalid.
        nativeDoneWithFrame(bufferId);
    }

    /**
     * Returns a current status of CarEvsService.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_MONITOR_CAR_EVS_STATUS} permissions to
     * access.
     *
     * @return {@link android.car.evs.CarEvsStatus}
     */
    @Override
    @Nullable
    public CarEvsStatus getCurrentStatus() {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_MONITOR_CAR_EVS_STATUS);

        synchronized (mLock) {
            return new CarEvsStatus(mServiceType, mState);
        }
    }

    // TODO(b/157082995): Replaces below method with what PackageManager provides.
    @Nullable
    private String getSystemUiPackageName() {
        try {
            ComponentName componentName = ComponentName.unflattenFromString(mContext.getResources()
                    .getString(com.android.internal.R.string.config_systemUIServiceComponent));
            return componentName.getPackageName();
        } catch (RuntimeException e) {
            throw new IllegalStateException("error while getting system UI package name.");
        }
    }

    /**
     * Returns a session token to be used to request the services.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_CONTROL_CAR_EVS_ACTIVITY} permission to access.
     *
     * @return IBinder object as a session token.
     * @throws IllegalStateException if we fail to find System UI package.
     */
    @Override
    public IBinder generateSessionToken() {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CONTROL_CAR_EVS_ACTIVITY);

        String systemUiPackageName = getSystemUiPackageName();
        IBinder token = null;
        try {
            int systemUiUid = mContext
                    .createContextAsUser(UserHandle.SYSTEM, /* flags = */ 0).getPackageManager()
                    .getPackageUid(systemUiPackageName, PackageManager.MATCH_SYSTEM_ONLY);
            int callerUid = Binder.getCallingUid();
            if (systemUiUid != callerUid) {
                // TODO(b/179498566): Records issued session tokens to identify the clients.
                token = new Binder();
            }
        } catch (NameNotFoundException err) {
            throw new IllegalStateException(systemUiPackageName + " package not found.");
        } finally {
            return token;
        }
    }

    /**
     * Returns whether or not a given service type is supported.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_MONITOR_CAR_EVS_STATUS} permissions to
     * access.
     */
    @Override
    public boolean isSupported(@CarEvsServiceType int type) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_MONITOR_CAR_EVS_STATUS);

        // TODO(b/183141701): Please implement this method.
        return false;
    }

    /** Handles client disconnections; may request to stop a video stream. */
    private void handleClientDisconnected() {
        // If the last stream client is disconnected before it stops a video stream, request to stop
        // current video stream.
        synchronized (mLock) {
            if (mStreamCallbacks.getRegisteredCallbackCount() == 0) {
                Slog.d(CarLog.TAG_EVS, "Last client has been disconnected.");
                try {
                    nativeRequestToStopVideoStream();
                } catch (RuntimeException e) {
                    Slog.e(CarLog.TAG_EVS, "Failed to stop a video stream: %s", e);
                }
            }
        }
    }

    /** Processes a streaming event and propagates it to registered clients */
    private void processStreamEvent(@CarEvsStreamEvent int event) {
        int idx = mStreamCallbacks.beginBroadcast();
        while (idx-- > 0) {
            ICarEvsStreamCallback callback = mStreamCallbacks.getBroadcastItem(idx);
            try {
                callback.onStreamEvent(event);
            } catch (RemoteException e) {
                // Likely the binder death incident
                Slog.e(CarLog.TAG_EVS, Log.getStackTraceString(e));
            }
        }
        mStreamCallbacks.finishBroadcast();
    }

    /** Processes a streaming event and propagates it to registered clients */
    private void processNewFrame(int id, @NonNull HardwareBuffer buffer) {
        Objects.requireNonNull(buffer);

        int idx = mStreamCallbacks.beginBroadcast();
        synchronized (mLock) {
            mBufferRecords.put(id, idx);
        }

        while (idx-- > 0) {
            ICarEvsStreamCallback callback = mStreamCallbacks.getBroadcastItem(idx);
            try {
                callback.onNewFrame(new CarEvsBufferDescriptor(id, buffer));
            } catch (RemoteException e) {
                // Likely the binder death incident
                Slog.e(CarLog.TAG_EVS, Log.getStackTraceString(e));
            }
        }
        mStreamCallbacks.finishBroadcast();
    }

    /** EVS stream event handler called after a native handler */
    private void postNativeEventHandler(int eventType) {
        processStreamEvent(
                CarEvsServiceUtils.convertToStreamEvent(eventType));
    }

    /** EVS frame handler called after a native handler */
    private void postNativeFrameHandler(int id, HardwareBuffer buffer) {
        processNewFrame(id, buffer);
    }

    /** EVS service death handler called after a native handler */
    private void postNativeDeathHandler() {
        try {
            if (!nativeConnectToHalServiceIfNecessary()) {
                Slog.e(CarLog.TAG_EVS, "Failed to reconnect to the service.");
            }
        } catch (RuntimeException e) {
            Slog.e(CarLog.TAG_EVS, Log.getStackTraceString(e));
        }
    }

    /**
     * Because of its dependency on FMQ type, android.hardware.automotive.evs@1.1 interface does
     * not support Java backend.  Therefore, all hwbinder transactions happen in native methods
     * declared below.
     */

    static {
        System.loadLibrary("carservicejni");
    }

    /** Stores a service handle initialized in native methods */
    private long mNativeEvsServiceObj = 0;

    /** Attempts to connect to the HAL service if it has not done yet */
    private native boolean nativeConnectToHalServiceIfNecessary();

    /** Attempts to disconnect from the HAL service */
    private native void nativeDisconnectFromHalService();

    /** Attempts to open a target camera device */
    private native boolean nativeOpenCamera(String cameraId);

    /** Requests to close a target camera device */
    private native void nativeCloseCamera();

    /** Requests to start a video stream */
    private native boolean nativeRequestToStartVideoStream();

    /** Requests to stop a video stream */
    private native void nativeRequestToStopVideoStream();

    /** Request to return an used buffer */
    private native void nativeDoneWithFrame(int bufferId);
}
