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

import android.car.Car;
import android.car.builtin.util.Slogf;
import android.car.evs.CarEvsManager;
import android.car.evs.CarEvsManager.CarEvsError;
import android.car.evs.CarEvsManager.CarEvsServiceState;
import android.car.evs.CarEvsManager.CarEvsServiceType;
import android.car.evs.CarEvsStatus;
import android.car.evs.ICarEvsStreamCallback;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.evs.EvsHalWrapper;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

/** CarEvsService state machine implementation to handle all state transitions. */
final class StateMachine {
    // Service request priorities
    static final int REQUEST_PRIORITY_LOW = 0;
    static final int REQUEST_PRIORITY_NORMAL = 1;
    static final int REQUEST_PRIORITY_HIGH = 2;

    // Timeout for a request to start a video stream with a valid token
    private static final int STREAM_START_REQUEST_TIMEOUT_MS = 3000;

    private final Context mContext;
    private final CarEvsService mService;
    private final EvsHalWrapper mHalWrapper;
    private final Object mLock = new Object();

    // Current state
    @GuardedBy("mLock")
    private int mState = SERVICE_STATE_UNAVAILABLE;

    // Current service type
    @GuardedBy("mLock")
    private int mServiceType = CarEvsManager.SERVICE_TYPE_REARVIEW;

    // Priority of a last service request
    @GuardedBy("mLock")
    private int mLastRequestPriority = REQUEST_PRIORITY_LOW;

    StateMachine(Context context, CarEvsService service, EvsHalWrapper hal) {
        mContext = context;
        mService = service;
        mHalWrapper = hal;
    }

    @CarEvsError int execute(int priority, int destination) {
        int serviceType;
        synchronized (mLock) {
            serviceType = mServiceType;
        }
        return execute(priority, destination, serviceType, null, null);
    }

    @CarEvsError int execute(int priority, int destination, int service) {
        return execute(priority, destination, service, null, null);
    }

    @CarEvsError int execute(int priority, int destination,
            ICarEvsStreamCallback callback) {
        int serviceType;
        synchronized (mLock) {
            serviceType = mServiceType;
        }
        return execute(priority, destination, serviceType, null, callback);
    }

    @CarEvsError int execute(int priority, int destination, int service, IBinder token,
            ICarEvsStreamCallback callback) {

        int serviceType;
        int newState;
        int result = ERROR_NONE;
        synchronized (mLock) {
            // TODO(b/188970686): Reduce this lock duration.
            if (mState == destination && priority < mLastRequestPriority &&
                    destination != SERVICE_STATE_REQUESTED) {
                // Nothing to do
                return ERROR_NONE;
            }

            int previousState = mState;
            Slogf.i(TAG_EVS, "Transition requested: %s -> %s", stateToString(previousState),
                    stateToString(destination));

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

            serviceType = mServiceType;
            newState = mState;
        }

        if (result == ERROR_NONE) {
            Slogf.i(TAG_EVS, "Transition completed: %s", stateToString(destination));
            // Broadcasts current state
            mService.broadcastStateTransition(serviceType, newState);
        } else {
            Slogf.e(TAG_EVS, "Transition failed: error = %d", result);
        }

        return result;
    }

    @CarEvsServiceState int getState() {
        synchronized (mLock) {
            return mState;
        }
    }

    @VisibleForTesting
    void setState(@CarEvsServiceState int  newState) {
        synchronized (mLock) {
            mState = newState;
        }
    }

    @CarEvsServiceType int getServiceType() {
        synchronized (mLock) {
            return mServiceType;
        }
    }

    CarEvsStatus getStateAndServiceType() {
        synchronized (mLock) {
            return new CarEvsStatus(getServiceType(), getState());
        }
    }

    boolean checkCurrentStateRequiresSystemActivity() {
        synchronized (mLock) {
            return (mState == SERVICE_STATE_ACTIVE || mState == SERVICE_STATE_REQUESTED) &&
                    mLastRequestPriority == REQUEST_PRIORITY_HIGH;
        }
    }

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
                mService.stopService();
                break;
        }

        mState = SERVICE_STATE_UNAVAILABLE;
        return ERROR_NONE;
    }

    @GuardedBy("mLock")
    private @CarEvsError int handleTransitionToInactiveLocked(int priority, int service,
            ICarEvsStreamCallback callback) {

        switch (mState) {
            case SERVICE_STATE_UNAVAILABLE:
                if (callback != null) {
                    // We get a request to stop a video stream after losing a native EVS
                    // service.  Simply unregister a callback and return.
                    mService.unlinkToDeathStreamCallbackLocked();
                    mService.setStreamCallback(null);
                    return ERROR_NONE;
                } else {
                    // Requested to connect to the Extended View System service
                    if (!mHalWrapper.connectToHalServiceIfNecessary()) {
                        return ERROR_UNAVAILABLE;
                    }

                    CarEvsService.EvsHalEvent latestHalEvent = mService.getLatestHalEvent();
                    if (checkCurrentStateRequiresSystemActivity() ||
                            (latestHalEvent != null &&
                             latestHalEvent.isRequestingToStartActivity())) {
                        // Request to launch the viewer because we lost the Extended View System
                        // service while a client was actively streaming a video.
                        mService.postActivityRequestRunnable(STREAM_START_REQUEST_TIMEOUT_MS);
                    }
                }
                break;

            case SERVICE_STATE_INACTIVE:
                // Nothing to do
                break;

            case SERVICE_STATE_REQUESTED:
                // Requested to cancel a pending service request
                if (mServiceType != service || priority < mLastRequestPriority) {
                    return ERROR_BUSY;
                }

                // Reset a timer for this new request
                mService.cancelPendingActivityRequest();
                break;

            case SERVICE_STATE_ACTIVE:
                // Requested to stop a current video stream
                if (mServiceType != service || priority < mLastRequestPriority) {
                    return ERROR_BUSY;
                }

                mService.stopService(callback);
                break;

            default:
                throw new IllegalStateException("CarEvsService is in the unknown state.");
        }

        mState = SERVICE_STATE_INACTIVE;
        mService.setSessionToken(null);
        return ERROR_NONE;
    }

    @GuardedBy("mLock")
    private @CarEvsError int handleTransitionToRequestedLocked(int priority, int service) {
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
                mService.cancelPendingActivityRequest();
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
                    mService.processStreamEvent(STREAM_EVENT_STREAM_STOPPED);
                }
                break;

            default:
                throw new IllegalStateException("CarEvsService is in the unknown state.");
        }

        // Arms the timer for the high-priority request
        if (priority == REQUEST_PRIORITY_HIGH) {
            mService.postActivityRequestRunnable(STREAM_START_REQUEST_TIMEOUT_MS);
        }

        mState = SERVICE_STATE_REQUESTED;
        mServiceType = service;
        mLastRequestPriority = priority;

        if (mService.mEvsCameraActivity != null) {
            Intent evsIntent = new Intent(Intent.ACTION_MAIN)
                    .setComponent(mService.mEvsCameraActivity)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if (priority == REQUEST_PRIORITY_HIGH) {
                IBinder token = new Binder();
                Bundle bundle = new Bundle();
                mService.setSessionToken(token);
                bundle.putBinder(CarEvsManager.EXTRA_SESSION_TOKEN, token);
                evsIntent.replaceExtras(bundle);
            }
            mContext.startActivity(evsIntent);
        }
        return ERROR_NONE;
    }

    @GuardedBy("mLock")
    private @CarEvsError int handleTransitionToActiveLocked(int priority, int service,
            IBinder token, ICarEvsStreamCallback callback) {

        @CarEvsError int result = ERROR_NONE;
        switch (mState) {
            case SERVICE_STATE_UNAVAILABLE:
                // We do not have a valid connection to the Extended View System service.
                return ERROR_UNAVAILABLE;

            case SERVICE_STATE_INACTIVE:
                // CarEvsService receives a low priority request to start a video stream.
                result = mService.startServiceAndVideoStream(service, callback);
                if (result != ERROR_NONE) {
                    return result;
                }
                break;

            case SERVICE_STATE_REQUESTED:
                // CarEvsService is reserved for higher priority clients.
                if (priority == REQUEST_PRIORITY_HIGH && !mService.isSessionToken(token)) {
                    // Declines a request with an expired token.
                    return ERROR_BUSY;
                }

                result = mService.startServiceAndVideoStream(service, callback);
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

                ICarEvsStreamCallback previousCallback = mService.getStreamCallback();
                if (previousCallback != null) {
                    // keep old reference for Runnable.
                    mService.setStreamCallback(null);
                    mService.postNotifyStreamStopped(previousCallback);
                }

                mService.setStreamCallback(callback);
                break;

            default:
                throw new IllegalStateException("CarEvsService is in the unknown state.");
        }

        mState = SERVICE_STATE_ACTIVE;
        mServiceType = service;
        mLastRequestPriority = priority;
        return ERROR_NONE;
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
                return "UNKNOWN";
        }
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    @Override
    public String toString() {
        synchronized (mLock) {
            return stateToString(mState);
        }
    }
}
