/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static android.car.evs.CarEvsManager.STREAM_EVENT_STREAM_STOPPED;

import static com.android.car.CarLog.TAG_EVS;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DEBUGGING_CODE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.builtin.util.Slogf;
import android.car.evs.CarEvsBufferDescriptor;
import android.car.evs.CarEvsManager;
import android.car.evs.CarEvsManager.CarEvsError;
import android.car.evs.CarEvsManager.CarEvsServiceState;
import android.car.evs.CarEvsManager.CarEvsServiceType;
import android.car.evs.CarEvsManager.CarEvsStreamEvent;
import android.car.evs.CarEvsStatus;
import android.car.evs.ICarEvsStreamCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import com.android.car.BuiltinPackageDependency;
import com.android.car.CarServiceUtils;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.evs.EvsHalWrapper;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.Objects;

/** CarEvsService state machine implementation to handle all state transitions. */
final class StateMachine {
    // Service request priorities
    static final int REQUEST_PRIORITY_LOW = 0;
    static final int REQUEST_PRIORITY_NORMAL = 1;
    static final int REQUEST_PRIORITY_HIGH = 2;

    // Timeout for a request to start a video stream with a valid token.
    private static final int STREAM_START_REQUEST_TIMEOUT_MS = 3000;

    private static final boolean DBG = Slogf.isLoggable(TAG_EVS, Log.DEBUG);

    // Interval for connecting to the EVS HAL service trial.
    private static final long EVS_HAL_SERVICE_BIND_RETRY_INTERVAL_MS = 1000;
    // Object to recognize Runnable objects.
    private static final String CALLBACK_RUNNABLE_TOKEN = StateMachine.class.getSimpleName();
    private static final String DEFAULT_CAMERA_ALIAS = "default";

    private final ArraySet mBufferRecords = new ArraySet();
    private final CarEvsService mService;
    private final ComponentName mActivityName;
    private final Context mContext;
    private final EvsHalWrapper mHalWrapper;
    private final HalCallback mHalCallback = new HalCallback();
    private final Handler mHandler;
    private final HandlerThread mHandlerThread =
            CarServiceUtils.getHandlerThread(getClass().getSimpleName());
    private final Object mLock = new Object();
    private final Runnable mActivityRequestTimeoutRunnable = () -> handleActivityRequestTimeout();

    @GuardedBy("mLock")
    private String mCameraId;

    @GuardedBy("mLock")
    private ICarEvsStreamCallback mStreamCallback;

    // Current state.
    @GuardedBy("mLock")
    private int mState = SERVICE_STATE_UNAVAILABLE;

    // Priority of a last service request.
    @GuardedBy("mLock")
    private int mLastRequestPriority = REQUEST_PRIORITY_LOW;

    // The latest session token issued to the privileged client.
    @GuardedBy("mLock")
    private IBinder mSessionToken = null;

    // This is a device name to override initial camera id.
    private String mCameraIdOverride = null;

    @VisibleForTesting
    final class HalCallback implements EvsHalWrapper.HalEventCallback {

        private WeakReference<ICarEvsStreamCallback> mCallback;

        /** EVS stream event handler called after a native handler. */
        @Override
        public void onHalEvent(int event) {
            mHandler.postDelayed(() -> processStreamEvent(mCallback, event),
                    CALLBACK_RUNNABLE_TOKEN, /* delayMillis= */ 0);
        }

        /** EVS frame handler called after a native handler. */
        @Override
        public void onFrameEvent(int id, HardwareBuffer buffer) {
            mHandler.postDelayed(() -> processNewFrame(mCallback, id, buffer),
                    CALLBACK_RUNNABLE_TOKEN, /* delayMillis= */ 0);
        }

        /** EVS service death handler called after a native handler. */
        @Override
        public void onHalDeath() {
            // We have lost the Extended View System service.
            execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_UNAVAILABLE);
            connectToHalServiceIfNecessary(EVS_HAL_SERVICE_BIND_RETRY_INTERVAL_MS);
        }

        /** Sets a callback to forward events. */
        boolean setStreamCallback(ICarEvsStreamCallback callback) {
            if (mCallback != null && mCallback.get() != null) {
                Slogf.d(TAG_EVS, "Replacing an existing stream callback object %s with %s",
                        mCallback.get(), callback);
            }

            mCallback = new WeakReference<>(callback);
            return true;
        }
    }

    @VisibleForTesting
    static EvsHalWrapper createHalWrapper(Context builtinContext,
            EvsHalWrapper.HalEventCallback callback) {
        try {
            Class helperClass = builtinContext.getClassLoader().loadClass(
                    BuiltinPackageDependency.EVS_HAL_WRAPPER_CLASS);
            Constructor constructor = helperClass.getConstructor(
                    new Class[]{EvsHalWrapper.HalEventCallback.class});
            return (EvsHalWrapper) constructor.newInstance(callback);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Cannot load class:" + BuiltinPackageDependency.EVS_HAL_WRAPPER_CLASS, e);
        }
    }

    // Constructor
    StateMachine(Context context, Context builtinContext, CarEvsService service,
            ComponentName activityName, String cameraId) {
        this(context, builtinContext, service, activityName, cameraId, /* handler= */ null);
    }

    StateMachine(Context context, Context builtinContext, CarEvsService service,
            ComponentName activityName, String cameraId, Handler handler) {
        mContext = context;
        mCameraId = cameraId;
        mActivityName = activityName;
        if (DBG) {
            Slogf.d(TAG_EVS, "Camera Activity=%s", mActivityName);
        }

        if (handler == null) {
            mHandler = new Handler(mHandlerThread.getLooper());
        } else {
            mHandler = handler;
        }
        mHalWrapper = StateMachine.createHalWrapper(builtinContext, mHalCallback);
        mService = service;
    }

    /***** Visible instance method section. *****/

    /** Initializes this StateMachine instance. */
    boolean init() {
        return mHalWrapper.init();
    }

    /** Releases this StateMachine instance. */
    void release() {
        mHandler.removeCallbacks(mActivityRequestTimeoutRunnable);
        mHalWrapper.release();
    }

    /**
     * Checks whether we are connected to the native EVS service.
     *
     * @return true if our connection to the native EVS service is valid.
     *         false otherwise.
     */
    boolean isConnected() {
        return mHalWrapper.isConnected();
    }

    /**
     * Sets a string camera identifier to use.
     *
     * @param id A string identifier of a target camera device.
     */
    void setCameraId(String id) {
        if (id.equalsIgnoreCase(DEFAULT_CAMERA_ALIAS)) {
            mCameraIdOverride = mCameraId;
            Slogf.i(TAG_EVS, "CarEvsService is set to use the default device for the rearview.");
        } else {
            mCameraIdOverride = id;
            Slogf.i(TAG_EVS, "CarEvsService is set to use " + id + " for the rearview.");
        }
    }

    /**
     * Sets a string camera identifier to use.
     *
     * @return A camera identifier string we're going to use.
     */
    String getCameraId() {
        return mCameraIdOverride != null ? mCameraIdOverride : mCameraId;
    }

    /**
     * Notifies that we're done with a frame buffer associated with a given identifier.
     *
     * @param id An identifier of a frame buffer we have consumed.
     */
    void doneWithFrame(int id) {
        synchronized (mLock) {
            if (!mBufferRecords.contains(id)) {
                Slogf.w(TAG_EVS, "Ignores a request to return a buffer with unknown id = "
                        + id);
                return;
            }

            mBufferRecords.remove(id);
        }

        // This may throw a NullPointerException if the native EVS service handle is invalid.
        mHalWrapper.doneWithFrame(id);
    }

    /**
     * Requests to start a registered activity with a given priority.
     *
     * @param priority A priority of current request; this should be either REQUEST_PRIORITY_HIGH or
     *                 REQUEST_PRIORITY_NORMAL.
     *
     * @return ERROR_UNEVAILABLE if we are not initialized yet or we failed to connect to the native
     *                           EVS service.
     *         ERROR_BUSY if a pending request has a higher priority.
     *         ERROR_NONE if no activity is registered or we succeed to request a registered
     *                    activity.
     */
    @CarEvsError int requestStartActivity(int priority) {
        if (mContext == null) {
            return ERROR_UNAVAILABLE;
        }

        return mActivityName == null ? ERROR_NONE : execute(priority, SERVICE_STATE_REQUESTED);
    }

    /**
     * Requests to start a registered activity if it is necessary.
     *
     * @return ERROR_UNEVAILABLE if we are not initialized yet or we failed to connect to the native
     *                           EVS service.
     *         ERROR_BUSY if a pending request has a higher priority.
     *         ERROR_NONE if no activity is registered or we succeed to request a registered
     *                    activity.
     */
    @CarEvsError int requestStartActivityIfNecessary() {
        return startActivityIfNecessary();
    }

    /**
     * Requests to stop an activity.
     *
     * @param priority A priority of current request; this should be either REQUEST_PRIORITY_HIGH or
     *                 REQUEST_PRIORITY_NORMAL.
     *
     * @return ERROR_NONE if no active streaming client exists, no activity has been registered, or
     *                    current activity is successfully stopped.
     *         ERROR_UNAVAILABLE if we cannot connect to the native EVS service.
     *         ERROR_BUSY if current activity has a higher priority than a given priority.
     */
    @CarEvsError int requestStopActivity(int priority) {
        if (mStreamCallback == null || mActivityName == null) {
            Slogf.d(TAG_EVS,
                    "Ignore a request to stop activity mStreaCallback=%s, mActivityName=%s",
                            mStreamCallback, mActivityName);
            return ERROR_NONE;
        }

        stopActivity();
        return ERROR_NONE;
    }

    /** Requests to cancel a pending activity request. */
    void cancelActivityRequest() {
        if (mState != SERVICE_STATE_REQUESTED) {
            return;
        }

        if (execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_INACTIVE) != ERROR_NONE) {
            Slogf.w(TAG_EVS, "Failed to transition to INACTIVE state.");
        }
    }

    /** Tries to connect to the EVS HAL service until it succeeds at a default interval. */
    void connectToHalServiceIfNecessary() {
        connectToHalServiceIfNecessary(EVS_HAL_SERVICE_BIND_RETRY_INTERVAL_MS);
    }

    /** Shuts down the service and enters INACTIVE state. */
    void stopService() {
        // Stop all clients.
        requestStopVideoStream(/* callback= */ null);
    }

    /**
     * Prioritizes video stream request and start a video stream.
     *
     * @param callback A callback to get frame buffers and stream events.
     * @param token A token to recognize a client. If this is a valid session token, its owner will
     *              prioritized.
     *
     * @return ERROR_UNAVAILABLE if we're not connected to the native EVS service.
     *         ERROR_BUSY if current client has a higher priority.
     *         ERROR_NONE otherwise.
     */
    @CarEvsError int requestStartVideoStream(ICarEvsStreamCallback callback, IBinder token) {
        int priority;
        if (isSessionToken(token)) {
            // If a current request has a valid session token, we assume it comes from an activity
            // launched by us for the high priority request.
            mHandler.removeCallbacks(mActivityRequestTimeoutRunnable);
            priority = REQUEST_PRIORITY_HIGH;
        } else {
            priority = REQUEST_PRIORITY_LOW;
        }

        return execute(priority, SERVICE_STATE_ACTIVE, token, callback);
    }

    /**
     * Stops a video stream.
     *
     * @param callback A callback client who want to stop listening.
     */
    void requestStopVideoStream(ICarEvsStreamCallback callback) {
        ICarEvsStreamCallback existingCallback;
        synchronized (mLock) {
            existingCallback = mStreamCallback;
        }

        if (existingCallback == null) {
            Slogf.i(TAG_EVS, "No active client exists.");
            return;
        }

        if (callback != null && callback.asBinder() != existingCallback.asBinder()) {
            Slogf.d(TAG_EVS, "Ignores a video stream request not from current stream client.");
            return;
        }

        if (execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_INACTIVE, callback) != ERROR_NONE) {
            Slogf.w(TAG_EVS, "Failed to stop a video stream");
        }
    }

    /**
     * Gets a current status of StateMachine.
     *
     * @return CarEvsServiceState that describes current state of a StateMachine instance.
     */
    CarEvsStatus getCurrentStatus() {
        synchronized (mLock) {
            return new CarEvsStatus(CarEvsManager.SERVICE_TYPE_REARVIEW, mState);
        }
    }

    /**
     * Returns a String that describes a current session token.
     */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    void dumpSessionToken(IndentingPrintWriter writer) {
        String msg;
        synchronized (mLock) {
            msg = mSessionToken.toString();
        }

        writer.printf("Current session token = %s\n", msg);
    }

    /**
     * Confirms whether a given IBinder object is identical to current session token IBinder object.
     *
     * @param token IBinder object that a caller wants to examine.
     *
     * @return true if a given IBinder object is a valid session token.
     *         false otherwise.
     */
    boolean isSessionToken(IBinder token) {
        synchronized (mLock) {
            return isSessionTokenLocked(token);
        }
    }

    /** Handles client disconnections; may request to stop a video stream. */
    void handleClientDisconnected(ICarEvsStreamCallback callback) {
        // If the last stream client is disconnected before it stops a video stream, request to stop
        // current video stream.
        execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_INACTIVE, callback);
    }

    /************************** Private methods ************************/

    private @CarEvsError int execute(int priority, int destination) {
        return execute(priority, destination, null, null);
    }


    private @CarEvsError int execute(int priority, int destination,
            ICarEvsStreamCallback callback) {
        return execute(priority, destination, null, callback);
    }

    /**
     * Executes StateMachine to be in a requested state.
     *
     * @param priority A priority of current execution.
     * @param destination A target service state we're desired to enter.
     * @param token A session token IBinder object.
     * @param callback A callback object we may need to work with.
     *
     * @return ERROR_NONE if we're already in a requested state.
     *         CarEvsError from each handler methods.
     */
    private @CarEvsError int execute(int priority, int destination, IBinder token,
            ICarEvsStreamCallback callback) {
        int result = ERROR_NONE;
        int previousState, newState;
        synchronized (mLock) {
            // TODO(b/188970686): Reduce this lock duration.
            if (mState == destination && priority < mLastRequestPriority &&
                    destination != SERVICE_STATE_REQUESTED) {
                // Nothing to do
                return ERROR_NONE;
            }

            previousState = mState;
            Slogf.i(TAG_EVS, "Transition requested: %s -> %s", stateToString(previousState),
                    stateToString(destination));

            switch (destination) {
                case SERVICE_STATE_UNAVAILABLE:
                    result = handleTransitionToUnavailableLocked();
                    break;

                case SERVICE_STATE_INACTIVE:
                    result = handleTransitionToInactiveLocked(priority, callback);
                    break;

                case SERVICE_STATE_REQUESTED:
                    result = handleTransitionToRequestedLocked(priority);
                    break;

                case SERVICE_STATE_ACTIVE:
                    result = handleTransitionToActiveLocked(priority, token, callback);
                    break;

                default:
                    throw new IllegalStateException(
                            "CarEvsService is in the unknown state, " + previousState);
            }

            newState = mState;
        }

        if (result == ERROR_NONE) {
            Slogf.i(TAG_EVS, "Transition completed: %s", stateToString(destination));
            if (previousState != newState) {
                mService.broadcastStateTransition(CarEvsManager.SERVICE_TYPE_REARVIEW, newState);
            }
        } else {
            Slogf.e(TAG_EVS, "Transition failed: error = %d", result);
        }

        return result;
    }

    /**
     * Checks conditions and tells whether we need to launch a registered activity.
     *
     * @return true if we should launch an activity.
     *         false otherwise.
     */
    private boolean needToStartActivity() {
        if (mActivityName == null || mHandler.hasCallbacks(mActivityRequestTimeoutRunnable)) {
            // No activity has been registered yet or it is already requested.
            Slogf.d(TAG_EVS,
                    "No need to start an activity: mActivityName=%s, mHandler.hasCallbacks()=%s",
                            mActivityName, mHandler.hasCallbacks(mActivityRequestTimeoutRunnable));
            return false;
        }

        boolean startActivity = mService.needToStartActivity();
        synchronized (mLock) {
            startActivity |= checkCurrentStateRequiresSystemActivityLocked();
        }

        return startActivity;
    }

    /**
     * Checks conditions and tells whether we need to launch a registered activity.
     *
     * @return true if we should launch an activity.
     *         false otherwise.
     */
    private boolean needToStartActivityLocked() {
        if (mActivityName == null || mHandler.hasCallbacks(mActivityRequestTimeoutRunnable)) {
            // No activity has been registered yet or it is already requested.
            Slogf.d(TAG_EVS,
                    "No need to start an activity: mActivityName=%s, mHandler.hasCallbacks()=%s",
                            mActivityName, mHandler.hasCallbacks(mActivityRequestTimeoutRunnable));
            return false;
        }

        return mService.needToStartActivity() || checkCurrentStateRequiresSystemActivityLocked();
    }

    /**
     * Launches a registered camera activity if necessary.
     *
     * @return ERROR_UNEVAILABLE if we are not initialized yet or we failed to connect to the native
     *                           EVS service.
     *         ERROR_BUSY if a pending request has a higher priority.
     *         ERROR_NONE if no activity is registered or we succeed to request a registered
     *                    activity.
     */
    private @CarEvsError int startActivityIfNecessary() {
        return startActivityIfNecessary(/* resetState= */ false);
    }

    /**
     * Launches a registered activity if necessary.
     *
     * @param resetState when this is true, StateMachine enters INACTIVE state first and then moves
     *                   into REQUESTED state.
     *
     * @return ERROR_UNEVAILABLE if we are not initialized yet or we failed to connect to the native
     *                           EVS service.
     *         ERROR_BUSY if a pending request has a higher priority.
     *         ERROR_NONE if no activity is registered or we succeed to request a registered
     *                    activity.
     */
    private @CarEvsError int startActivityIfNecessary(boolean resetState) {
        if (!needToStartActivity()) {
            // We do not need to start a camera activity.
            return ERROR_NONE;
        }

        return startActivity(resetState);
    }

    /**
     * Launches a registered activity.
     *
     * @param resetState when this is true, StateMachine enters INACTIVE state first and then moves
     *                   into REQUESTED state.
     *
     * @return ERROR_UNEVAILABLE if we are not initialized yet or we failed to connect to the native
     *                           EVS service.
     *         ERROR_BUSY if a pending request has a higher priority.
     *         ERROR_NONE if no activity is registered or we succeed to request a registered
     *                    activity.
     */
    private @CarEvsError int startActivity(boolean resetState) {
        // Request to launch an activity again after cleaning up.
        int result = ERROR_NONE;
        if (resetState) {
            result = execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_INACTIVE);
            if (result != ERROR_NONE) {
                return result;
            }
        }

        return execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_REQUESTED);
    }

    /** Stops a registered activity if it's running and enters INACTIVE state. */
    private void stopActivity() {
        ICarEvsStreamCallback callback;
        synchronized (mLock) {
            callback = mStreamCallback;
        }

        // We'll attempt to enter INACTIVE state.
        if (execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_INACTIVE, callback) != ERROR_NONE) {
            Slogf.w(TAG_EVS, "Failed to transition to INACTIVE state.");
        }
    }

    /**
     * Try to connect to the EVS HAL service until it succeeds at a given interval.
     *
     * @param internalInMillis an interval to try again if current attempt fails.
     */
    private void connectToHalServiceIfNecessary(long intervalInMillis) {
        if (execute(REQUEST_PRIORITY_HIGH, SERVICE_STATE_INACTIVE) != ERROR_NONE) {
            // Try to restore a connection again after a given amount of time.
            Slogf.i(TAG_EVS, "Failed to connect to EvsManager service. Retrying after %d ms.",
                    intervalInMillis);
            mHandler.postDelayed(() -> connectToHalServiceIfNecessary(intervalInMillis),
                    intervalInMillis);
        }
    }

    /**
     * Notify the client of a video stream loss.
     *
     * @param callback A callback object we're about to stop forwarding frmae buffers and events.
     */
    private static void notifyStreamStopped(ICarEvsStreamCallback callback) {
        if (callback == null) {
            return;
        }

        try {
            callback.onStreamEvent(CarEvsManager.STREAM_EVENT_STREAM_STOPPED);
        } catch (RemoteException e) {
            // Likely the binder death incident
            Slogf.w(TAG_EVS, Log.getStackTraceString(e));
        }
    }

    /**
     * Check whether or not a given token is a valid session token that can be used to prioritize
     * requests.
     *
     * @param token A IBinder object a caller wants to confirm.
     *
     * @return true if a given IBinder object is a valid session token.
     *         false otherwise.
     */
    @GuardedBy("mLock")
    private boolean isSessionTokenLocked(IBinder token) {
        return token != null && token == mSessionToken;
    }

    /**
     * Handle a transition from current state to UNAVAILABLE state.
     *
     * When the native EVS service becomes unavailable, CarEvsService notifies all active clients
     * and enters UNAVAILABLE state.
     *
     * @return ERROR_NONE always.
     */
    @GuardedBy("mLock")
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

    /**
     * Handle a transition from current state to INACTIVE state.
     *
     * INACTIVE state means that CarEvsService is connected to the EVS service and idles.
     *
     * @return ERROR_BUSY if CarEvsService is already busy with a higher priority client.
     *         ERROR_NONE otherwise.
     */
    @GuardedBy("mLock")
    private @CarEvsError int handleTransitionToInactiveLocked(int priority,
            ICarEvsStreamCallback callback) {

        switch (mState) {
            case SERVICE_STATE_UNAVAILABLE:
                if (callback != null) {
                    // We get a request to stop a video stream after losing a native EVS
                    // service.  Simply unregister a callback and return.
                    unlinkToDeathStreamCallbackLocked(callback);
                    mStreamCallback = null;
                    return ERROR_NONE;
                } else {
                    // Requested to connect to the Extended View System service
                    if (!mHalWrapper.connectToHalServiceIfNecessary()) {
                        return ERROR_UNAVAILABLE;
                    }

                    if (needToStartActivityLocked()) {
                        // Request to launch the viewer because we lost the Extended View System
                        // service while a client was actively streaming a video.
                        mHandler.postDelayed(mActivityRequestTimeoutRunnable,
                                             STREAM_START_REQUEST_TIMEOUT_MS);
                    }
                }
                break;

            case SERVICE_STATE_INACTIVE:
                // Nothing to do
                break;

            case SERVICE_STATE_REQUESTED:
                // Requested to cancel a pending service request
                if (priority < mLastRequestPriority) {
                    return ERROR_BUSY;
                }

                // Reset a timer for this new request
                mHandler.removeCallbacks(mActivityRequestTimeoutRunnable);
                break;

            case SERVICE_STATE_ACTIVE:
                // Requested to stop a current video stream
                if (priority < mLastRequestPriority) {
                    Slogf.d(TAG_EVS,
                            "Ignore a request to stop a video stream with a lower priority");
                    return ERROR_BUSY;
                }

                // Remove pending callbacks and notify a client.
                mHandler.postAtFrontOfQueue(() -> notifyStreamStopped(callback));
                mHandler.removeCallbacksAndMessages(CALLBACK_RUNNABLE_TOKEN);

                unlinkToDeathStreamCallbackLocked(callback);
                mStreamCallback = null;

                mHalWrapper.requestToStopVideoStream();
                break;

            default:
                throw new IllegalStateException("CarEvsService is in the unknown state.");
        }

        mState = SERVICE_STATE_INACTIVE;
        mSessionToken = null;
        return ERROR_NONE;
    }

    /**
     * Handle a transition from current state to REQUESTED state.
     *
     * CarEvsService enters this state when it is requested to launch a registered camera activity.
     *
     * @return ERROR_UNAVAILABLE if CarEvsService is not connected to the native EVS service.
     *         ERROR_BUSY if CarEvsService is processing a higher priority client.
     *         ERROR_NONE otherwise.
     */
    @GuardedBy("mLock")
    private @CarEvsError int handleTransitionToRequestedLocked(int priority) {

        switch (mState) {
            case SERVICE_STATE_UNAVAILABLE:
                // Attempts to connect to the native EVS service and transits to the
                // REQUESTED state if it succeeds.
                if (!mHalWrapper.connectToHalServiceIfNecessary()) {
                    return ERROR_UNAVAILABLE;
                }
                break;

            case SERVICE_STATE_INACTIVE:
                // Nothing to do
                break;

            case SERVICE_STATE_REQUESTED:
                if (priority < mLastRequestPriority) {
                    // A current service request has a lower priority than a previous
                    // service request.
                    Slogf.e(TAG_EVS, "CarEvsService is busy with a higher priority client.");
                    return ERROR_BUSY;
                }

                // Reset a timer for this new request
                mHandler.removeCallbacks(mActivityRequestTimeoutRunnable);
                break;

            case SERVICE_STATE_ACTIVE:
                if (priority < mLastRequestPriority) {
                    // We decline a request because CarEvsService is busy with a higher priority
                    // client.
                    return ERROR_BUSY;
                } else if (priority == mLastRequestPriority) {
                    // We do not need to transit to the REQUESTED state because CarEvsService
                    // was transited to the ACTIVE state by a request that has the same priority
                    // with current request.
                    return ERROR_NONE;
                } else {
                    // Stop stream on all lower priority clients.
                    mHalCallback.onHalEvent(CarEvsManager.STREAM_EVENT_STREAM_STOPPED);
                }
                break;

            default:
                throw new IllegalStateException("CarEvsService is in the unknown state.");
        }

        // Arms the timer for the high-priority request
        if (priority == REQUEST_PRIORITY_HIGH) {
            mHandler.postDelayed(
                    mActivityRequestTimeoutRunnable, STREAM_START_REQUEST_TIMEOUT_MS);
        }

        mState = SERVICE_STATE_REQUESTED;
        mLastRequestPriority = priority;

        if (mActivityName != null) {
            Intent evsIntent = new Intent(Intent.ACTION_MAIN)
                    .setComponent(mActivityName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if (priority == REQUEST_PRIORITY_HIGH) {
                mSessionToken = new Binder();
                Bundle bundle = new Bundle();
                bundle.putBinder(CarEvsManager.EXTRA_SESSION_TOKEN, mSessionToken);
                evsIntent.replaceExtras(bundle);
            }
            mContext.startActivity(evsIntent);
        }
        return ERROR_NONE;
    }

    /**
     * Handle a transition from current state to ACTIVE state.
     *
     * @return ERROR_BUSY if CarEvsService is busy with a higher priority client.
     *         ERROR_UNAVAILABLE if CarEvsService is in UNAVAILABLE state or fails to start a video
     *                           stream.
     *         ERROR_NONE otherwise.
     */
    @GuardedBy("mLock")
    private @CarEvsError int handleTransitionToActiveLocked(int priority, IBinder token,
            ICarEvsStreamCallback callback) {

        @CarEvsError int result = ERROR_NONE;
        switch (mState) {
            case SERVICE_STATE_UNAVAILABLE:
                // We do not have a valid connection to the Extended View System service.
                return ERROR_UNAVAILABLE;

            case SERVICE_STATE_INACTIVE:
                // CarEvsService receives a low priority request to start a video stream.
                result = startService();
                if (result != ERROR_NONE) {
                    return result;
                }
                break;

            case SERVICE_STATE_REQUESTED:
                // CarEvsService is reserved for higher priority clients.
                if (priority == REQUEST_PRIORITY_HIGH && !isSessionTokenLocked(token)) {
                    // Declines a request with an expired token.
                    return ERROR_BUSY;
                }

                result = startService();
                if (result != ERROR_NONE) {
                    return result;
                }
                break;

            case SERVICE_STATE_ACTIVE:
                // CarEvsManager will transfer an active video stream to a new client with a
                // higher or equal priority.
                if (priority < mLastRequestPriority) {
                    Slogf.i(TAG_EVS, "Declines a service request with a lower priority.");
                    break;
                }

                result = startService();
                if (result != ERROR_NONE) {
                    return result;
                }

                if (mStreamCallback != null) {
                    // keep old reference for Runnable.
                    ICarEvsStreamCallback previousCallback = mStreamCallback;
                    mStreamCallback = null;

                    // Notify a current client of losing a video stream.
                    mHandler.post(() -> notifyStreamStopped(previousCallback));
                }
                break;

            default:
                throw new IllegalStateException("CarEvsService is in the unknown state.");
        }

        result = startVideoStream(callback);
        if (result == ERROR_NONE) {
            mState = SERVICE_STATE_ACTIVE;
            mLastRequestPriority = priority;
        }
        return result;
    }

    /** A stream callback death recipient. */
    private final IBinder.DeathRecipient mStreamCallbackDeathRecipient =
            new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Slogf.w(TAG_EVS, "StreamCallback has died.");
            synchronized (mLock) {
                if (needToStartActivityLocked()) {
                    if (startActivity(/* resetState= */ true) != ERROR_NONE) {
                        Slogf.w(TAG_EVS, "Failed to request the activity.");
                        mHandler.postDelayed(mActivityRequestTimeoutRunnable,
                                STREAM_START_REQUEST_TIMEOUT_MS);
                    }
                } else {
                    // Ensure we stops streaming.
                    handleClientDisconnected(mStreamCallback);
                }
            }
        }
    };

    /** Processes a streaming event and propagates it to registered clients */
    private void processStreamEvent(WeakReference<ICarEvsStreamCallback> callbackRef,
            @CarEvsStreamEvent int event) {
        ICarEvsStreamCallback callback = callbackRef.get();
        if (callback == null) {
            Slogf.d(TAG_EVS, "Ignore an event as a client is not alive.");
            return;
        }

        try {
            callback.onStreamEvent(event);
        } catch (RemoteException e) {
            // Likely the binder death incident
            Slogf.e(TAG_EVS, Log.getStackTraceString(e));
        }
    }

    /**
     * Processes a streaming event and propagates it to registered clients.
     *
     * @return True if this buffer is hold and used by the client, false otherwise.
     */
    private void processNewFrame(WeakReference<ICarEvsStreamCallback> callbackRef, int id,
            @NonNull HardwareBuffer buffer) {
        Objects.requireNonNull(buffer);

        ICarEvsStreamCallback callback = callbackRef.get();
        if (callback == null) {
            Slogf.d(TAG_EVS, "Skip a frame %d because a client is not alive.", id);
            mHalWrapper.doneWithFrame(id);
            return;
        }

        try {
            callback.onNewFrame(new CarEvsBufferDescriptor(id, buffer));
            synchronized (mLock) {
                mBufferRecords.add(id);
            }
        } catch (RemoteException e) {
            // Likely the binder death incident
            Slogf.d(TAG_EVS, "Failed to forward a frame %s\n%s", id, Log.getStackTraceString(e));
            mHalWrapper.doneWithFrame(id);
        } finally {
            buffer.close();
        }
    }

    /** Connects to the native EVS service if necessary and opens a target camera device. */
    private @CarEvsError int startService() {
        if (!mHalWrapper.connectToHalServiceIfNecessary()) {
            Slogf.e(TAG_EVS, "Failed to connect to EVS service.");
            return ERROR_UNAVAILABLE;
        }

        if (!mHalWrapper.openCamera(mCameraId)) {
            Slogf.e(TAG_EVS, "Failed to open a targer camera device, %s", mCameraId);
            return ERROR_UNAVAILABLE;
        }

        return ERROR_NONE;
    }

    /** Registers a callback and requests a video stream. */
    private @CarEvsError int startVideoStream(ICarEvsStreamCallback callback) {
        if (!mHalCallback.setStreamCallback(callback)) {
            Slogf.e(TAG_EVS, "Failed to set a stream callback.");
            return ERROR_UNAVAILABLE;
        }

        if (!mHalWrapper.requestToStartVideoStream()) {
            Slogf.e(TAG_EVS, "Failed to start a video stream.");
            return ERROR_UNAVAILABLE;
        }

        // We are ready to start streaming events and frame buffers.
        synchronized (mLock) {
            linkToDeathStreamCallbackLocked(callback);
            mStreamCallback = callback;
        }

        return ERROR_NONE;
    }

    /** Links a death recipient to a given callback. */
    @GuardedBy("mLock")
    private void linkToDeathStreamCallbackLocked(ICarEvsStreamCallback callback) {
        if (callback == null) {
            return;
        }

        IBinder binder = callback.asBinder();
        if (binder == null) {
            Slogf.w(TAG_EVS, "Linking to a binder death recipient skipped");
            return;
        }

        try {
            binder.linkToDeath(mStreamCallbackDeathRecipient, 0);
        } catch (RemoteException e) {
            Slogf.w(TAG_EVS, "Failed to link a binder death recipient: " + e);
        }
    }

    /** Disconnects a death recipient from a given callback. */
    @GuardedBy("mLock")
    private void unlinkToDeathStreamCallbackLocked(ICarEvsStreamCallback callback) {
        if (callback == null) {
            return;
        }

        IBinder binder = mStreamCallback.asBinder();
        if (binder != null) {
            binder.unlinkToDeath(mStreamCallbackDeathRecipient, 0);
        }
    }

    /** Waits for a video stream request from the System UI with a valid token. */
    private void handleActivityRequestTimeout() {
        // No client has responded to a state transition to the REQUESTED
        // state before the timer expires.  CarEvsService sends a
        // notification again if it's still needed.
        Slogf.d(TAG_EVS, "Timer expired.  Request to launch the activity again.");
        if (startActivityIfNecessary(/* resetState= */ true) != ERROR_NONE) {
            Slogf.w(TAG_EVS, "Failed to request an activity.");
        }
    }

    /** Checks whether or not we need to request a registered camera activity. */
    @GuardedBy("mLock")
    private boolean checkCurrentStateRequiresSystemActivityLocked() {
        return (mState == SERVICE_STATE_ACTIVE || mState == SERVICE_STATE_REQUESTED) &&
                mLastRequestPriority == REQUEST_PRIORITY_HIGH;
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    private String stateToString(@CarEvsServiceState int state) {
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
                return "UNKNOWN: " + state;
        }
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    @Override
    public String toString() {
        synchronized (mLock) {
            return stateToString(mState);
        }
    }

    /** Overrides a current state. */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DEBUGGING_CODE)
    @VisibleForTesting
    void setState(@CarEvsServiceState int  newState) {
        synchronized (mLock) {
            Slogf.d(TAG_EVS, "StateMachine(%s)'s state has been changed from %d to %d.",
                    this, mState, newState);
            mState = newState;
        }
    }

    /** Overrides a current callback object. */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DEBUGGING_CODE)
    @VisibleForTesting
    void setStreamCallback(ICarEvsStreamCallback callback) {
        synchronized (mLock) {
            Slogf.d(TAG_EVS, "StreamCallback %s is replaced with %s", mStreamCallback, callback);
            mStreamCallback = callback;
        }
        mHalCallback.setStreamCallback(callback);
    }

    /** Overrides a current valid session token. */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    @VisibleForTesting
    void setSessionToken(IBinder token) {
        synchronized (mLock) {
            Slogf.d(TAG_EVS, "SessionToken %s is replaced with %s", mSessionToken, token);
            mSessionToken = token;
        }
    }
}
