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
import static android.car.evs.CarEvsManager.STREAM_EVENT_STREAM_STOPPED;

import static com.android.car.CarLog.TAG_EVS;
import static com.android.car.evs.StateMachine.REQUEST_PRIORITY_LOW;
import static com.android.car.evs.StateMachine.REQUEST_PRIORITY_NORMAL;
import static com.android.car.evs.StateMachine.REQUEST_PRIORITY_HIGH;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.builtin.content.pm.PackageManagerHelper;
import android.car.builtin.os.BuildHelper;
import android.car.builtin.util.Slogf;
import android.car.evs.CarEvsBufferDescriptor;
import android.car.evs.CarEvsManager;
import android.car.evs.CarEvsManager.CarEvsError;
import android.car.evs.CarEvsManager.CarEvsServiceState;
import android.car.evs.CarEvsManager.CarEvsServiceType;
import android.car.evs.CarEvsStatus;
import android.car.evs.ICarEvsStatusListener;
import android.car.evs.ICarEvsStreamCallback;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.hardware.automotive.vehicle.VehicleGear;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.Display;

import com.android.car.CarPropertyService;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.R;
import com.android.car.hal.EvsHalService;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.evs.EvsHalWrapper;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.List;
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
        implements CarServiceBase {

    private static final boolean DBG = Slogf.isLoggable(TAG_EVS, Log.DEBUG);

    static final class EvsHalEvent {
        private long mTimestamp;
        private int mServiceType;
        private boolean mOn;

        public EvsHalEvent(long timestamp, @CarEvsServiceType int type, boolean on) {
            mTimestamp = timestamp;
            mServiceType = type;
            mOn = on;
        }

        public long getTimestamp() {
            return mTimestamp;
        }

        public @CarEvsServiceType int getServiceType() {
            return mServiceType;
        }

        public boolean isRequestingToStartActivity() {
            return mOn;
        }

        @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
        public String toString() {
            return "ServiceType = " + mServiceType + ", mOn = " + mOn +
                    ", Timestamp = " + mTimestamp;
        }
    }

    private final Context mContext;
    private final EvsHalService mEvsHalService;
    private final CarPropertyService mPropertyService;
    private final DisplayManager mDisplayManager;  // To monitor the default display's state
    private final Object mLock = new Object();

    // This handler is to monitor the client sends a video stream request within a given time
    // after a state transition to the REQUESTED state.
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final class StatusListenerList extends RemoteCallbackList<ICarEvsStatusListener> {
        private final WeakReference<CarEvsService> mService;

        StatusListenerList(CarEvsService evsService) {
            mService = new WeakReference<>(evsService);
        }

        /** Handle callback death */
        @Override
        public void onCallbackDied(ICarEvsStatusListener listener) {
            Slogf.w(TAG_EVS, "StatusListener has died: " + listener.asBinder());

            CarEvsService svc = mService.get();
            if (svc != null) {
                svc.handleClientDisconnected(listener);
            }
        }
    }

    private final StatusListenerList mStatusListeners = new StatusListenerList(this);

    /**
     * {@link CarPropertyEvent} listener registered with {@link CarPropertyService} to listen to
     * {@link VehicleProperty.GEAR_SELECTION} change notifications.
     */
    private final ICarPropertyEventListener mGearSelectionPropertyListener =
            new ICarPropertyEventListener.Stub() {
                @Override
                public void onEvent(List<CarPropertyEvent> events) throws RemoteException {
                    if (events.isEmpty()) {
                        return;
                    }

                    // Handle only the latest event
                    Slogf.i(TAG_EVS, "Handling GearSelection event");
                    handlePropertyEvent(events.get(events.size() - 1));
                }
            };

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    // Nothing to do
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    // Nothing to do
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) {
                        // We are interested only in the default display.
                        return;
                    }

                    Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
                    if (mCurrentDisplayState == display.getState()) {
                        // We already handled this display state change.
                        Slogf.i(TAG_EVS, "We already handled a reported display status, %d",
                                display.getState());
                        return;
                    }

                    switch (display.getState()) {
                        case Display.STATE_ON:
                            // We may want to request the system viewer.
                            if (mStateEngine.requestStartActivityIfNecessary() != ERROR_NONE) {
                                Slogf.e(TAG_EVS, "Fail to request a registered activity.");
                            }
                            break;

                        case Display.STATE_OFF:
                            // Stop an active client.
                            mStateEngine.requestStopVideoStream(/* callback= */ null);
                            break;

                        default:
                            // Nothing to do for all other state changes
                            break;
                    }

                    mCurrentDisplayState = display.getState();
                }
            };

    private final StateMachine mStateEngine;

    // The latest display state we have processed.
    private int mCurrentDisplayState = Display.STATE_OFF;

    // This boolean flag is true if CarEvsService uses GEAR_SELECTION VHAL property instead of
    // EVS_SERVICE_REQUEST.
    private boolean mUseGearSelection = true;

    // The last event EvsHalService reported.  This will be set to null when a related service
    // request is handled.
    //
    // To properly handle a HAL event that occurred before CarEvsService is ready, we initialize
    // mLastEvsHalEvent with a zero timestamp here.
    @GuardedBy("mLock")
    private EvsHalEvent mLastEvsHalEvent = new EvsHalEvent(/* timestamp= */ 0,
            CarEvsManager.SERVICE_TYPE_REARVIEW, /* on= */ false);

    /** Creates an Extended View System service instance given a {@link Context}. */
    public CarEvsService(Context context, Context builtinContext, EvsHalService halService,
            CarPropertyService propertyService) {
        mContext = context;
        mPropertyService = propertyService;
        mEvsHalService = halService;

        String activityName = mContext.getResources().getString(R.string.config_evsCameraActivity);
        ComponentName activityComponentName;
        if (!activityName.isEmpty()) {
            activityComponentName = ComponentName.unflattenFromString(activityName);
        } else {
            activityComponentName = null;
        }
        if (DBG) Slogf.d(TAG_EVS, "evsCameraActivity=" + activityName);

        mStateEngine = new StateMachine(
                context, builtinContext, this, activityComponentName,
                    context.getString(R.string.config_evsRearviewCameraId));

        mDisplayManager = context.getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
    }

    @VisibleForTesting
    final class EvsTriggerListener implements EvsHalService.EvsHalEventListener {

        /** Implements EvsHalService.EvsHalEventListener to monitor EvsHal properties. */
        @Override
        public void onEvent(@CarEvsServiceType int type, boolean on) {
            if (DBG) {
                Slogf.d(TAG_EVS,
                        "Received an event from EVS HAL: type = " + type + ", on = " + on);
            }

            if (type != CarEvsManager.SERVICE_TYPE_REARVIEW) {
                Slogf.w(TAG_EVS, "CarEvsService only supports SERVICE_TYPE_REARVIEW. Requested=%s",
                        type);
                return;
            }

            // Stores the last event.
            synchronized (mLock) {
                mLastEvsHalEvent = new EvsHalEvent(SystemClock.elapsedRealtimeNanos(), type, on);
            }

            if (on) {
                // Request a camera activity.
                // TODO(b/191940626): Use a type to start an activity with a target service type.
                if (mStateEngine.requestStartActivity(REQUEST_PRIORITY_HIGH) != ERROR_NONE) {
                    Slogf.e(TAG_EVS, "Fail to request a registered activity.");
                }
            } else {
                // Stop a video stream and close an activity.
                mStateEngine.requestStopActivity(REQUEST_PRIORITY_HIGH);
            }
        }
    }

    @VisibleForTesting
    final EvsTriggerListener mEvsTriggerListener = new EvsTriggerListener();

    @Override
    public void init() {
        if (DBG) {
            Slogf.d(TAG_EVS, "Initializing the service");
        }

        if (!mStateEngine.init()) {
            Slogf.e(TAG_EVS, "Failed to initialize a service handle");
            return;
        }

        if (mEvsHalService.isEvsServiceRequestSupported()) {
            try {
                mEvsHalService.setListener(mEvsTriggerListener);
                if (DBG) {
                    Slogf.d(TAG_EVS, "CarEvsService listens to EVS_SERVICE_REQUEST property.");
                }
                mUseGearSelection = false;
            } catch (IllegalStateException e) {
                Slogf.w(TAG_EVS, "Failed to set a EvsHalService listener. Try to use "
                        + "GEAR_SELECTION.");
            }
        }

        if (mUseGearSelection) {
            if (DBG) {
                Slogf.d(TAG_EVS, "CarEvsService listens to GEAR_SELECTION property.");
            }

            if (mPropertyService == null || mPropertyService.getPropertySafe(
                    VehiclePropertyIds.GEAR_SELECTION, /*areaId=*/ 0) == null) {
                Slogf.e(TAG_EVS,
                        "CarEvsService is disabled because GEAR_SELECTION is unavailable.");
                mUseGearSelection = false;
                return;
            }

            mPropertyService.registerListenerSafe(
                    VehiclePropertyIds.GEAR_SELECTION, /*updateRateHz=*/0,
                    mGearSelectionPropertyListener);
        }

        // Attempts to transit to the INACTIVE state
        mStateEngine.connectToHalServiceIfNecessary();
    }

    @Override
    public void release() {
        if (DBG) {
            Slogf.d(TAG_EVS, "Finalizing the service");
        }

        if (mUseGearSelection && mPropertyService != null) {
            if (DBG) {
                Slogf.d(TAG_EVS, "Unregister a property listener in release()");
            }
            mPropertyService.unregisterListenerSafe(VehiclePropertyIds.GEAR_SELECTION,
                    mGearSelectionPropertyListener);
        }

        mStatusListeners.kill();
        mStateEngine.release();
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("*CarEvsService*");
        writer.printf("Current state = %s\n", mStateEngine);
        writer.printf("%s to HAL service\n",
                mStateEngine.isConnected() ? "Connected" : "Not connected");

        synchronized (mLock) {
            writer.printf("%d service listeners subscribed.\n",
                    mStatusListeners.getRegisteredCallbackCount());
            writer.printf("Last HAL event = %s\n", mLastEvsHalEvent);
        }

        mStateEngine.dumpSessionToken(writer);
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dumpProto(ProtoOutputStream proto) {}

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
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_MONITOR_CAR_EVS_STATUS);
        Objects.requireNonNull(listener);

        if (DBG) {
            Slogf.d(TAG_EVS, "Registering a new service listener");
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
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_MONITOR_CAR_EVS_STATUS);
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
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_REQUEST_CAR_EVS_ACTIVITY);

        if (type == CarEvsManager.SERVICE_TYPE_SURROUNDVIEW) {
            // TODO(b/179029031): Removes below when Surround View service is integrated.
            Slogf.e(TAG_EVS, "Surround view is not supported yet.");
            return ERROR_UNAVAILABLE;
        }

        // TODO(b/191940626): Use a type to start an activity with a target service type.
        return mStateEngine.requestStartActivity(REQUEST_PRIORITY_NORMAL);
    }

    /**
     * Requests to stop a current previewing activity launched via {@link #startActivity}.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_REQUEST_CAR_EVS_ACTIVITY} permissions to
     * access.
     */
    @Override
    public void stopActivity() {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_REQUEST_CAR_EVS_ACTIVITY);

        mStateEngine.requestStopActivity(REQUEST_PRIORITY_NORMAL);
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
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_CAMERA);
        Objects.requireNonNull(callback);

        if (type == CarEvsManager.SERVICE_TYPE_SURROUNDVIEW) {
            // TODO(b/179029031): Removes below when Surround View service is integrated.
            Slogf.e(TAG_EVS, "Surround view is not supported yet.");
            return ERROR_UNAVAILABLE;
        }

        return mStateEngine.requestStartVideoStream(callback, token);
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
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_CAMERA);
        Objects.requireNonNull(callback);

        mStateEngine.requestStopVideoStream(callback);
    }

    /**
     * Returns an used buffer to EVS service.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_USE_CAR_EVS_CAMERA} permissions to access.
     *
     * @param buffer A consumed CarEvsBufferDescriptor object.  This would not be used and returned
     *               to the native EVS service.
     * @throws IllegalArgumentException if a passed buffer has an unregistered identifier.
     */
    @Override
    public void returnFrameBuffer(@NonNull CarEvsBufferDescriptor buffer) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_CAMERA);
        Objects.requireNonNull(buffer);

        mStateEngine.doneWithFrame(buffer.getId());
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
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_MONITOR_CAR_EVS_STATUS);

        return mStateEngine.getCurrentStatus();
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
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_CONTROL_CAR_EVS_ACTIVITY);

        String systemUiPackageName = PackageManagerHelper.getSystemUiPackageName(mContext);
        IBinder token = new Binder();
        try {
            int systemUiUid = PackageManagerHelper.getPackageUidAsUser(mContext.getPackageManager(),
                    systemUiPackageName, UserHandle.SYSTEM.getIdentifier());
            int callerUid = Binder.getCallingUid();
            if (systemUiUid == callerUid) {
                mStateEngine.setSessionToken(token);
            } else {
                throw new SecurityException("SystemUI only can generate SessionToken");
            }
        } catch (NameNotFoundException e) {
            throw new IllegalStateException(systemUiPackageName + " package not found", e);
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
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_MONITOR_CAR_EVS_STATUS);

        switch (type) {
            case CarEvsManager.SERVICE_TYPE_REARVIEW:
                return mStateEngine.isConnected();

            case CarEvsManager.SERVICE_TYPE_SURROUNDVIEW:
                // TODO(b/179029031): Implements necessary logic when Surround View service is
                // integrated.
                return false;

            default:
                throw new IllegalArgumentException("Unknown service type = " + type);
        }
    }

    /**
     * Sets a camera device for the rearview.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_USE_CAR_EVS_CAMERA} permissions to access.
     *
     * @param id A string identifier of a target camera device.
     * @return This method return a false if this runs in a release build; otherwise, this returns
     *         true.
     */
    public boolean setRearviewCameraIdFromCommand(@NonNull String id) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_CAMERA);
        Objects.requireNonNull(id);

        if (!BuildHelper.isDebuggableBuild()) {
            // This method is not allowed in the release build.
            return false;
        }

        mStateEngine.setCameraId(id);
        return true;
    }

    /**
     * Gets an identifier of a current camera device for the rearview.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_MONITOR_CAR_EVS_STATUS} permissions to
     * access.
     *
     * @return A string identifier of current rearview camera device.
     */
    @NonNull
    public String getRearviewCameraIdFromCommand() {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_MONITOR_CAR_EVS_STATUS);
        return mStateEngine.getCameraId();
    }

    /**
     * Sets a stream callback.
     */
    void setStreamCallback(@Nullable ICarEvsStreamCallback callback) {
        mStateEngine.setStreamCallback(callback);
    }

    /** Tells whether or not the latest EVS HAL event was requesting to start an activity. */
    boolean needToStartActivity() {
        synchronized (mLock) {
            return mLastEvsHalEvent != null && mLastEvsHalEvent.isRequestingToStartActivity();
        }
    }

    /**
     * Manually sets a current service state.
     */
    @VisibleForTesting
    void setServiceState(@CarEvsServiceState int newState) {
        mStateEngine.setState(newState);
    }

    /**
     * Manually chooses to use a gear selection property or not.
     */
    @VisibleForTesting
    void setToUseGearSelection(boolean useGearSelection) {
        mUseGearSelection = useGearSelection;
    }

    /**
     * Manually sets the last EVS HAL event.
     */
    @VisibleForTesting
    void setLastEvsHalEvent(long timestamp, @CarEvsServiceType int type, boolean on) {
        synchronized (mLock) {
            mLastEvsHalEvent = new EvsHalEvent(timestamp, type, on);
        }
    }

    /** Handles client disconnections; may request to stop a video stream. */
    private void handleClientDisconnected(ICarEvsStreamCallback callback) {
        // If the last stream client is disconnected before it stops a video stream, request to stop
        // current video stream.
        mStateEngine.handleClientDisconnected(callback);
    }

    /** Notifies the service status gets changed */
    void broadcastStateTransition(int type, int state) {
        int idx = mStatusListeners.beginBroadcast();
        while (idx-- > 0) {
            ICarEvsStatusListener listener = mStatusListeners.getBroadcastItem(idx);
            try {
                listener.onStatusChanged(new CarEvsStatus(type, state));
            } catch (RemoteException e) {
                // Likely the binder death incident.
                Slogf.e(TAG_EVS, Log.getStackTraceString(e));
            }
        }
        mStatusListeners.finishBroadcast();
    }

    private void handlePropertyEvent(CarPropertyEvent event) {
        if (event.getEventType() != CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE) {
            // CarEvsService is interested only in the property change event.
            return;
        }

        CarPropertyValue value = event.getCarPropertyValue();
        if (value.getPropertyId() != VehiclePropertyIds.GEAR_SELECTION) {
            // CarEvsService is interested only in the GEAR_SELECTION property.
            return;
        }

        long timestamp = value.getTimestamp();
        boolean isReverseGear;
        synchronized (mLock) {
            if (timestamp != 0 && timestamp <= mLastEvsHalEvent.getTimestamp()) {
                if (DBG) {
                    Slogf.d(TAG_EVS,
                            "Ignoring GEAR_SELECTION change happened past, timestamp = " +
                            timestamp + ", last event was at " + mLastEvsHalEvent.getTimestamp());
                }
                return;
            }


            isReverseGear = (Integer) value.getValue() == VehicleGear.GEAR_REVERSE;
            mLastEvsHalEvent = new EvsHalEvent(timestamp, CarEvsManager.SERVICE_TYPE_REARVIEW,
                    isReverseGear);
        }

        // TODO(b/179029031): CarEvsService may need to process VehicleGear.GEAR_PARK when
        // Surround View service is integrated.
        if (isReverseGear) {
            // Request to start the rearview activity when the gear is shifted into the reverse
            // position.
            if (mStateEngine.requestStartActivity(REQUEST_PRIORITY_HIGH) != ERROR_NONE) {
                Slogf.w(TAG_EVS, "Failed to request the rearview activity.");
            }
        } else {
            // Request to stop the rearview activity when the gear is shifted from the reverse
            // position to other positions.
            if (mStateEngine.requestStopActivity(REQUEST_PRIORITY_HIGH) != ERROR_NONE) {
                Slogf.d(TAG_EVS, "Failed to stop the rearview activity.");
            }
        }
    }

    /** Notify the client of a video stream loss */
    private static void notifyStreamStopped(@NonNull ICarEvsStreamCallback callback) {
        Objects.requireNonNull(callback);

        try {
            callback.onStreamEvent(CarEvsManager.STREAM_EVENT_STREAM_STOPPED);
        } catch (RemoteException e) {
            // Likely the binder death incident
            Slogf.w(TAG_EVS, Log.getStackTraceString(e));
        }
    }

    /** Handles a disconnection of a status monitoring client. */
    private void handleClientDisconnected(ICarEvsStatusListener listener) {
        mStatusListeners.unregister(listener);
        if (mStatusListeners.getRegisteredCallbackCount() == 0) {
            Slogf.d(TAG_EVS, "Last status listener has been disconnected.");
        }
    }
}
