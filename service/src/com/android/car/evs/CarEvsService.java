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

import static android.car.evs.CarEvsManager.ERROR_BUSY;
import static android.car.evs.CarEvsManager.ERROR_NONE;
import static android.car.evs.CarEvsManager.ERROR_UNAVAILABLE;
import static android.car.evs.CarEvsManager.SERVICE_STATE_ACTIVE;
import static android.car.evs.CarEvsManager.SERVICE_STATE_INACTIVE;
import static android.car.evs.CarEvsManager.SERVICE_STATE_REQUESTED;
import static android.car.evs.CarEvsManager.SERVICE_STATE_UNAVAILABLE;

import static com.android.car.CarLog.TAG_EVS;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

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
import android.os.IBinder;
import android.os.Looper;
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
 *
 *
 * CarEvsService consists of four states:
 *
 * UNAVAILABLE: CarEvsService is not connected to the Extended View System service.  In this
 * state, any service request will be declined.
 *
 * INACTIVE: CarEvsService has a valid, live connection the Extended View System service and
 * ready for any service requests.
 *
 * REQUESTED: CarEvsService received a service requeste from a privileged client and requested
 * the System UI to launch the camera viewing activity.
 *
 * ACTIVE: CarEvsService is actively streaming a video to the client.
 *
 * See CarEvsService.StateMachine class for more details.
 */
public final class CarEvsService extends android.car.evs.ICarEvsService.Stub
        implements CarServiceBase, EvsHalService.EvsHalEventListener {

    private static final boolean DBG = Log.isLoggable(TAG_EVS, Log.DEBUG);

    // Integer value to indicate no buffer with a given id exists
    private static final int BUFFER_NOT_EXIST = -1;

    // Timeout for a stream-stopped confirmation
    private static final int STREAM_STOPPED_WAIT_TIMEOUT_MS = 500;

    // Timeout for a request to start a video stream with a valid token
    private static final int STREAM_START_REQUEST_TIMEOUT_MS = 3000;

    // Interval for connecting to the EVS HAL service trial
    private static final long EVS_HAL_SERVICE_BIND_RETRY_INTERVAL_MS = 1000;

    // Code to identify a message to request an activity again
    private static final int MSG_CHECK_ACTIVITY_REQUEST_TIMEOUT = 0;

    // Service request priorities
    private static final int REQUEST_PRIORITY_HIGH = 0;
    private static final int REQUEST_PRIORITY_NORMAL = 1;
    private static final int REQUEST_PRIORITY_LOW = 2;

    private static final class EvsHalEvent {
        private int mServiceType;
        private boolean mOn;

        public EvsHalEvent(@CarEvsServiceType int type, boolean on) {
            mServiceType = type;
            mOn = on;
        }

        public @CarEvsServiceType int getServiceType() {
            return mServiceType;
        }

        public boolean isRequestingToStart() {
            return mOn;
        }
    }

    private final Context mContext;
    private final EvsHalService mEvsHalService;
    private final Object mLock = new Object();

    // This handler is to monitor the client sends a video stream request within a given time
    // after a state transition to the REQUESTED state.
    private final Handler mHandler = new Handler(Looper.getMainLooper());

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
            Slog.w(TAG_EVS, "StatusListener has died: " + listener.asBinder());

            CarEvsService svc = mService.get();
            if (svc != null) {
                svc.handleClientDisconnected(listener);
            }
        }
    }

    @GuardedBy("mLock")
    private final StatusListenerList mStatusListeners = new StatusListenerList(this);

    private final class StreamCallbackList extends RemoteCallbackList<ICarEvsStreamCallback> {
        private final WeakReference<CarEvsService> mService;

        StreamCallbackList(CarEvsService evsService) {
            mService = new WeakReference<>(evsService);
        }

        /** Handle callback death */
        @Override
        public void onCallbackDied(ICarEvsStreamCallback callback) {
            Slog.w(TAG_EVS, "StreamCallback has died: " + callback.asBinder());

            CarEvsService svc = mService.get();
            if (svc != null) {
                svc.handleClientDisconnected(callback);
            }
        }
    }

    // TODO(b/178741919): Considers using java.util.ArrayList with a lock instead of
    // RemoteCallbackList to avoid double-lock situation.
    @GuardedBy("mLock")
    private final StreamCallbackList mStreamCallbacks = new StreamCallbackList(this);

    // CarEvsService state machine implementation to handle all state transitions.
    private final class StateMachine {
        private Object mStateLock = new Object();

        // Current state
        @GuardedBy("mStateLock")
        private int mState = SERVICE_STATE_UNAVAILABLE;

        // Current service type
        @GuardedBy("mStateLock")
        private int mServiceType = CarEvsManager.SERVICE_TYPE_REARVIEW;

        // Priority of a last service request
        @GuardedBy("mStateLock")
        private int mLastRequestPriority = REQUEST_PRIORITY_LOW;

        public @CarEvsError int execute(int priority, int destination) {
            return execute(priority, destination, mServiceType, null, null);
        }

        public @CarEvsError int execute(int priority, int destination, int service) {
            return execute(priority, destination, service, null, null);
        }

        public @CarEvsError int execute(int priority, int destination,
                ICarEvsStreamCallback callback) {
            return execute(priority, destination, mServiceType, null, callback);
        }

        public @CarEvsError int execute(int priority, int destination, int service, IBinder token,
                ICarEvsStreamCallback callback) {

            if (mState == destination && destination != SERVICE_STATE_REQUESTED) {
                // Nothing to do
                return ERROR_NONE;
            }

            int previousState = mState;
            int result;
            Slog.d(TAG_EVS, "Transition requested: " + toString(previousState) +
                    " -> " + toString(destination));

            synchronized (mStateLock) {
                switch (destination) {
                    case SERVICE_STATE_UNAVAILABLE:
                        result = handleTransitionToUnavailableLocked();
                        break;

                    case SERVICE_STATE_INACTIVE:
                        result = handleTransitionToInactiveLocked(priority, service, callback);
                        break;

                    case SERVICE_STATE_REQUESTED:
                        result = handleTransitionToRequestedLocked(priority, service);
                        break;

                    case SERVICE_STATE_ACTIVE:
                        result = handleTransitionToActiveLocked(priority, service, token, callback);
                        break;

                    default:
                        throw new IllegalStateException(
                                "CarEvsService is in the unknown state, " + previousState);
                }
            }

            if (result == ERROR_NONE) {
                // Broadcasts current state
                broadcastStateTransition(mServiceType, mState);
            }

            return result;
        }

        public @CarEvsServiceState int getState() {
            synchronized (mStateLock) {
                return mState;
            }
        }

        public @CarEvsServiceType int getServiceType() {
            synchronized (mStateLock) {
                return mServiceType;
            }
        }

        public CarEvsStatus getStateAndServiceType() {
            synchronized (mStateLock) {
                return new CarEvsStatus(mServiceType, mState);
            }
        }

        @GuardedBy("mStateLock")
        private @CarEvsError int handleTransitionToUnavailableLocked() {
            // This transition happens only when CarEvsService loses the active connection to the
            // Extended View System service.
            switch (mState) {
                case SERVICE_STATE_UNAVAILABLE:
                    // Nothing to do
                    break;

                default:
                    // Stops any active video stream
                    stopService();
                    break;
            }

            mState = SERVICE_STATE_UNAVAILABLE;
            return ERROR_NONE;
        }

        @GuardedBy("mStateLock")
        private @CarEvsError int handleTransitionToInactiveLocked(int priority, int service,
                ICarEvsStreamCallback callback) {

            switch (mState) {
                case SERVICE_STATE_UNAVAILABLE:
                    if (callback != null) {
                        // We get a request to stop a video stream after losing a native EVS
                        // service.  Simply unregister a callback and return.
                        mStreamCallbacks.unregister(callback);
                        return ERROR_NONE;
                    } else {
                        // Requested to connect to the Extended View System service
                        if (!nativeConnectToHalServiceIfNecessary()) {
                            return ERROR_UNAVAILABLE;
                        }
                    }
                    break;

                case SERVICE_STATE_INACTIVE:
                    // Nothing to do
                    break;

                case SERVICE_STATE_REQUESTED:
                    // Requested to cancel a pending service request
                    if (mServiceType != service || mLastRequestPriority > priority) {
                        return ERROR_BUSY;
                    }

                    stopService();
                    break;

                case SERVICE_STATE_ACTIVE:
                    // Requested to stop a current video stream
                    stopVideoStreamAndUnregisterCallback(callback);
                    break;

                default:
                    throw new IllegalStateException("CarEvsService is in the unknown state.");
            }

            mState = SERVICE_STATE_INACTIVE;
            return ERROR_NONE;
        }

        @GuardedBy("mStateLock")
        private @CarEvsError int handleTransitionToRequestedLocked(int priority, int service) {
            switch (mState) {
                case SERVICE_STATE_UNAVAILABLE:
                    // Attempts to connect to the native EVS service and transits to the
                    // REQUESTED state if it succeeds.
                    if (!nativeConnectToHalServiceIfNecessary()) {
                        return ERROR_UNAVAILABLE;
                    }
                    break;

                case SERVICE_STATE_INACTIVE:
                    // Nothing to do
                    break;

                case SERVICE_STATE_REQUESTED:
                    if (priority > mLastRequestPriority) {
                        // A current service request has a lower priority than a previous
                        // service request.
                        Slog.e(TAG_EVS,
                                "CarEvsService is busy with a higher priority client.");
                        return ERROR_BUSY;
                    }

                    // Reset a timer for this new request
                    mHandler.removeMessages(MSG_CHECK_ACTIVITY_REQUEST_TIMEOUT);
                    break;

                case SERVICE_STATE_ACTIVE:
                    // Transits to the REQUESTED state only CarEvsService receives a HIGH and
                    // a NORMAL priority service request while an active video stream was
                    // requested with a LOW priority.
                    if (priority > mLastRequestPriority) {
                        return ERROR_BUSY;
                    }

                    break;

                default:
                    throw new IllegalStateException("CarEvsService is in the unknown state.");
            }

            // Arms the timer
            mHandler.sendMessageDelayed(obtainMessage(CarEvsService::handleActivityRequestTimeout,
                    CarEvsService.this).setWhat(MSG_CHECK_ACTIVITY_REQUEST_TIMEOUT),
                    STREAM_START_REQUEST_TIMEOUT_MS);

            mState = SERVICE_STATE_REQUESTED;
            mServiceType = service;
            mLastRequestPriority = priority;
            return ERROR_NONE;
        }

        @GuardedBy("mStateLock")
        private @CarEvsError int handleTransitionToActiveLocked(int priority, int service,
                IBinder token, ICarEvsStreamCallback callback) {

            @CarEvsError int result = ERROR_NONE;
            switch (mState) {
                case SERVICE_STATE_UNAVAILABLE:
                    // We do not have a valid connection to the Extended View System service.
                    return ERROR_UNAVAILABLE;

                case SERVICE_STATE_INACTIVE:
                    // CarEvsService receives a low priority request to start a video stream.
                    result = startServiceAndVideoStream(service, callback);
                    if (result != ERROR_NONE) {
                        return result;
                    }
                    break;

                case SERVICE_STATE_REQUESTED:
                    // CarEvsService is reserved for higher priority clients.
                    if (token != mCurrentToken) {
                        // Declines a request with an expired token.
                        return ERROR_BUSY;
                    }

                    result = startServiceAndVideoStream(service, callback);
                    if (result != ERROR_NONE) {
                        return result;
                    }
                    break;

                case SERVICE_STATE_ACTIVE:
                    // CarEvsManager will transfer an active video stream to a new client with a
                    // higher or equal priority.
                    //
                    // TODO(b/182299098): Transfers a active stream to a newer client when the
                    // conditions are met.
                    Slog.e(TAG_EVS, "CarEvsService is busy with other client.");
                    return ERROR_BUSY;

                default:
                    throw new IllegalStateException("CarEvsService is in the unknown state.");
            }

            mState = SERVICE_STATE_ACTIVE;
            mServiceType = service;
            mLastRequestPriority = priority;
            return ERROR_NONE;
        }

        private String toString(@CarEvsServiceState int state) {
            switch (state) {
                case SERVICE_STATE_UNAVAILABLE:
                    return "UNAVAILABLE";
                case SERVICE_STATE_INACTIVE:
                    return "INACTIVE";
                case SERVICE_STATE_REQUESTED:
                    return "REQUESTED";
                case SERVICE_STATE_ACTIVE:
                    return "ACTIVE";
                default:
                    return "UNKNOWN";
            }
        }
    }

    private final StateMachine mStateEngine = new StateMachine();

    // The latest session token issued to the privileged clients
    private IBinder mCurrentToken = null;

    // Synchronization object for a stream-stopped confirmation
    private CountDownLatch mStreamStoppedEvent = new CountDownLatch(0);

    // The last event EvsHalService reported.  This will be set to null when a related service
    // request is handled.
    private EvsHalEvent mLastEvsHalEvent;

    // Stops a current video stream and unregisters a callback
    @GuardedBy("mLock")
    private void stopVideoStreamAndUnregisterCallback(ICarEvsStreamCallback callback) {
        synchronized (mLock) {
            mStreamCallbacks.unregister(callback);
            if (mStreamCallbacks.getRegisteredCallbackCount() == 0) {
                Slog.d(TAG_EVS, "Last stream client has been disconnected.");
                nativeRequestToStopVideoStream();
            }
        }
    }

    // Starts a service and its video stream
    private @CarEvsError int startServiceAndVideoStream(
            @CarEvsServiceType int service, ICarEvsStreamCallback callback) {
        if (!startService(service)) {
            return ERROR_UNAVAILABLE;
        }

        // TODO(b/183672792): CarEvsService should allow only the single streaming client to be
        //                    active.
        if (!mStreamCallbacks.register(callback)) {
            Slog.e(TAG_EVS, "Failed to register a stream callback.");
            return ERROR_UNAVAILABLE;
        }

        if (!nativeRequestToStartVideoStream()) {
            Slog.e(TAG_EVS, "Failed to start a video stream");
            mStreamCallbacks.unregister(callback);
            return ERROR_UNAVAILABLE;
        }

        return ERROR_NONE;
    }

    // Waits for a video stream request from the System UI with a valid token.
    private void handleActivityRequestTimeout() {
        // No client has responded to a state transition to the REQUESTED
        // state before the timer expires.  CarEvsService sends a
        // notification again if it's still needed.
        EvsHalEvent pendingEvent = mLastEvsHalEvent;
        if (pendingEvent != null && pendingEvent.isRequestingToStart()) {
            // Requests again the System UI to launch the activity
            Slog.w(TAG_EVS, "Timer expired.  Request the activity again.");
            mStateEngine.execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_REQUESTED,
                    pendingEvent.getServiceType());
        } else if (mStateEngine.getState() == SERVICE_STATE_REQUESTED) {
            // If the service is no longer required by other services, we transit to
            // the INACTIVE state.
            mStateEngine.execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_INACTIVE);
        }
    }

    /** Creates an Extended View System service instance given a {@link Context}. */
    public CarEvsService(Context context, EvsHalService halService) {
        mContext = context;
        // TODO(b/183674444): Uses GEAR_SELECTION property to control the rearview camera if
        //                    EVS_SERVICE_REQUEST is not supported.
        mEvsHalService = halService;
    }

    /** Implements EvsHalService.EvsHalEventListener to monitor VHAL properties. */
    @Override
    public void onEvent(@CarEvsServiceType int type, boolean on) {
        if (DBG) {
            Slog.d(TAG_EVS,
                    "Received an event from EVS HAL: type = " + type + ", on = " + on);
        }

        int targetState = on ? SERVICE_STATE_REQUESTED : SERVICE_STATE_INACTIVE;
        if (mStateEngine.execute(REQUEST_PRIORITY_HIGH, targetState, type) !=
                ERROR_NONE) {
            Slog.e(TAG_EVS, "Failed to execute a service request.");
        }

        // Stores the last event
        mLastEvsHalEvent = new EvsHalEvent(type, on);
    }

    @Override
    public void init() {
        if (DBG) {
            Slog.d(TAG_EVS, "Initializing the service");
        }

        // Below registration may throw IllegalStateException if neither of EVS_SERVICE_REQUEST nor
        // GEAR_SELECTION VHAL properties are not supported.
        mEvsHalService.setListener(this);

        // Attempts to transit to the INACTIVE state
        if (mStateEngine.execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_INACTIVE) !=
                ERROR_NONE) {
            Slog.e(TAG_EVS, "Failed to transit to the INACTIVE state.");
        }
    }

    @Override
    public void release() {
        if (DBG) {
            Slog.d(TAG_EVS, "Finalizing the service");
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

        if (DBG) {
            Slog.d(TAG_EVS, "Registering a new service listener");
        }
        mStatusListeners.register(listener);
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

        return mStateEngine.execute(REQUEST_PRIORITY_NORMAL, SERVICE_STATE_REQUESTED, type);
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

        mStateEngine.execute(REQUEST_PRIORITY_NORMAL, SERVICE_STATE_INACTIVE);
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

        if (token != null && token != mCurrentToken) {
            mHandler.removeMessages(MSG_CHECK_ACTIVITY_REQUEST_TIMEOUT);
        }

        final int priority = token != null ? REQUEST_PRIORITY_HIGH : REQUEST_PRIORITY_LOW;
        return mStateEngine.execute(priority, SERVICE_STATE_ACTIVE, type, token, callback);
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

        if (mStateEngine.execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_INACTIVE, callback) !=
                ERROR_NONE) {
            Slog.e(TAG_EVS, "Failed to stop a video stream");

            // We want to return if a video stop request fails.
            return;
        }

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

        boolean returnThisBuffer = false;
        synchronized (mLock) {
            int record = mBufferRecords.get(bufferId, /* valueIfKeyNotFound = */ BUFFER_NOT_EXIST);
            if (record == BUFFER_NOT_EXIST) {
                Slog.w(TAG_EVS, "Ignores a request to return a buffer with unknown id = " + bufferId);
                return;
            }

            // Decreases the reference count
            record = record - 1;
            if (record > 0) {
                // This buffer is still being used by other clients.
                mBufferRecords.put(bufferId, record);
            } else {
                // No client is interested in this buffer so we will return this to the service.
                mBufferRecords.removeAt(mBufferRecords.indexOfKey(bufferId));
                returnThisBuffer = true;
            }
        }

        if (returnThisBuffer) {
            // This may throw a NullPointerException if the native EVS service handle is invalid.
            nativeDoneWithFrame(bufferId);
        }
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

        return mStateEngine.getStateAndServiceType();
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
        IBinder token = new Binder();
        try {
            int systemUiUid = mContext
                    .createContextAsUser(UserHandle.SYSTEM, /* flags = */ 0).getPackageManager()
                    .getPackageUid(systemUiPackageName, PackageManager.MATCH_SYSTEM_ONLY);
            int callerUid = Binder.getCallingUid();
            if (systemUiUid != callerUid) {
                synchronized (mCurrentToken) {
                    mCurrentToken = token;
                }
            }
        } catch (NameNotFoundException err) {
            throw new IllegalStateException(systemUiPackageName + " package not found.");
        } finally {
            return token;
        }
    }

    private void handleClientDisconnected(ICarEvsStatusListener listener) {
        synchronized (mLock) {
            mStatusListeners.unregister(listener);
            if (mStatusListeners.getRegisteredCallbackCount() == 0) {
                Slog.d(TAG_EVS, "Last status listener has been disconnected.");
            }
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
    private void handleClientDisconnected(ICarEvsStreamCallback callback) {
        // If the last stream client is disconnected before it stops a video stream, request to stop
        // current video stream.
        mStateEngine.execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_INACTIVE, callback);
    }

    /** Notifies the service status gets changed */
    private void broadcastStateTransition(int type, int state) {
        int idx = mStatusListeners.beginBroadcast();
        while (idx-- > 0) {
            ICarEvsStatusListener listener = mStatusListeners.getBroadcastItem(idx);
            try {
                listener.onStatusChanged(new CarEvsStatus(type, state));
            } catch (RemoteException e) {
                // Likely the binder death incident
                Slog.e(TAG_EVS, Log.getStackTraceString(e));
            }
        }
        mStatusListeners.finishBroadcast();
    }

    /** Starts a requested service */
    private boolean startService(@CarEvsServiceType int type) {
        if (type == CarEvsManager.SERVICE_TYPE_SURROUNDVIEW) {
            // TODO(b/179029031): Removes below when Surround View service is integrated.
            Slog.e(TAG_EVS, "Surround view is not supported yet.");
            return false;
        }

        if (!nativeConnectToHalServiceIfNecessary()) {
            Slog.e(TAG_EVS, "Failed to connect to EVS service");
            return false;
        }

        if (!nativeOpenCamera(mContext.getString(R.string.config_evsRearviewCameraId))) {
            Slog.e(TAG_EVS, "Failed to open a target camera device");
            return false;
        }

        return true;
    }

    /** Stops a current service */
    private boolean stopService() {
        boolean success = false;
        try {
            nativeRequestToStopVideoStream();
            success = true;
        } catch (RuntimeException e) {
            Slog.e(TAG_EVS, Log.getStackTraceString(e));
        } finally {
            return success;
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
                Slog.e(TAG_EVS, Log.getStackTraceString(e));
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
                Slog.e(TAG_EVS, Log.getStackTraceString(e));
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
        // We have lost the Extended View System service.
        mStateEngine.execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_UNAVAILABLE);
        connectToHalServiceIfNecessary(EVS_HAL_SERVICE_BIND_RETRY_INTERVAL_MS);
    }

    /** Try to connect to the EVS HAL service until it succeeds at a given interval */
    private void connectToHalServiceIfNecessary(long intervalInMillis) {
        Slog.d(TAG_EVS, "Trying to connect to the EVS HAL service.");
        if (mStateEngine.execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_INACTIVE) != ERROR_NONE) {
            // Try to restore a connection again after a given amount of time
            mHandler.sendMessageDelayed(obtainMessage(
                    CarEvsService::connectToHalServiceIfNecessary, this, intervalInMillis),
                    intervalInMillis);
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
