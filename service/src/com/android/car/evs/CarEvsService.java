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
import android.car.evs.CarEvsManager;
import android.car.evs.CarEvsManager.CarEvsServiceType;
import android.car.evs.ICarEvsService;
import android.car.evs.ICarEvsStatusListener;
import android.car.evs.ICarEvsStreamCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;

import com.android.car.CarServiceBase;
import com.android.car.ICarImpl;
import com.android.car.R;

import java.util.Objects;

/**
 * A service that listens to the Extended View System across a HAL boundary and exposes the data to
 * system clients in Android via {@link android.car.evs.CarEvsManager}.
 *
 * Because of Fast Message Queue usages, android.hardware.automotive.evs@1.1 interfaces does not
 * support Java backend and, therefore, actual API calls are done in native methods.
 */
public final class CarEvsService extends android.car.evs.ICarEvsService.Stub
        implements CarServiceBase {

    private static final String TAG = CarEvsService.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;

    // TODO(b/178741919): Considers using ArrayList with a lock instead of RemoteCallbackList
    private final RemoteCallbackList<ICarEvsStatusListener> mStatusListeners =
            new RemoteCallbackList<>();

    // TODO(b/178741919): Considers using ArrayList with a lock instead of RemoteCallbackList
    private final RemoteCallbackList<ICarEvsStreamCallback> mStreamCallbacks =
            new RemoteCallbackList<>();

    /** Creates an Extended View System service instance given a {@link Context}. */
    public CarEvsService(Context context) {
        mContext = context;
    }

    @Override
    public void init() {
        logd("Initializing service");
    }

    @Override
    public void release() {
        logd("Finalizing service");
        mStatusListeners.kill();
        mStreamCallbacks.kill();
    }

    @Override
    public void dump(IndentingPrintWriter writer) {
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

        logd("Registering a new service listener");
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
     * Requests to start a EVS service.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_USE_CAR_EVS_SERVICE} permissions to access.
     *
     * @param type {@link android.car.evs.CarEvsManager#CarEvsServiceType}
     */
    @Override
    public int requestToStartService(int type) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_SERVICE);

        return CarEvsManager.STATUS_ERROR_UNAVAILABLE;
    }

    /**
     * Requests to stop a current EVS service.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_USE_CAR_EVS_SERVICE} permissions to access.
     */
    @Override
    public int requestToStopService() {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_SERVICE);

        return CarEvsManager.STATUS_ERROR_UNAVAILABLE;
    }

    /**
     * Starts a video stream.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_USE_CAR_EVS_SERVICE} permissions to access.
     *
     * @param type {@link android.car.evs.CarEvsManager#CarEvsServiceType}
     * @param privileged Boolean flag to tell whether or not the caller is a privileged client.
     * @param callback {@link ICarEvsStreamCallback} listener to register.
     * @return {@link android.car.evs.CarEvsManager.CarEvsStatus}
     */
    @Override
    public int startVideoStream(@CarEvsServiceType int type, @Nullable IBinder token,
            @NonNull ICarEvsStreamCallback callback) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_SERVICE);
        Objects.requireNonNull(callback);

        // TODO(b/179498566): Identifies the requesting client (or its callback object)
        //                    with a given session token.

        Slog.e(TAG, "Not implemented yet.");
        return CarEvsManager.STATUS_ERROR_UNAVAILABLE;
    }

    /**
     * Stop a video stream
     *
     * <p>Requires {@link android.car.Car.PERMISSION_USE_CAR_EVS_SERVICE} permissions to access.
     *
     * @param callback {@link ICarEvsStreamCallback} listener to unregister.
     */
    @Override
    public void stopVideoStream(@NonNull ICarEvsStreamCallback callback) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_SERVICE);
        Objects.requireNonNull(callback);

        Slog.e(TAG, "Not implemented yet.");
    }

    /**
     * Returns an used buffer to EVS service
     *
     * <p>Requires {@link android.car.Car.PERMISSION_USE_CAR_EVS_SERVICE} permissions to access.
     *
     * @param bufferId An unique 32-bit integer identifier of the buffer to return.
     */
    @Override
    public void returnFrameBuffer(int bufferId) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_EVS_SERVICE);

        Slog.e(TAG, "Not implemented yet.");
    }

    /**
     * Returns a current status of CarEvsService.
     *
     * <p>Requires {@link android.car.Car.PERMISSION_MONITOR_CAR_EVS_STATUS} permissions to
     * access.
     *
     * @return {@link android.car.evs.CarEvsManager.CarEvsServiceStatus}
     */
    @Override
    public int getCurrentStatus() {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_MONITOR_CAR_EVS_STATUS);

        return CarEvsManager.SERVICE_STATUS_UNAVAILABLE;
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
     * <p>Requires {@link android.car.Car.PERMISSION_USE_CAR_EVS_SERVICE} permission to access.
     *
     * @return IBinder object as a session token.
     * @throws IllegalStateException if we fail to find System UI package.
     */
    @Override
    public IBinder generateSessionToken() {
        String systemUiPackageName = getSystemUiPackageName();
        IBinder token = null;
        try {
            int systemUiUid = mContext
                    .createContextAsUser(UserHandle.SYSTEM, /* flags = */ 0).getPackageManager()
                    .getPackageUid(systemUiPackageName, PackageManager.MATCH_SYSTEM_ONLY);
            int callerUid = Binder.getCallingUid();
            if (systemUiUid == callerUid) {
                // TODO(b/179498566): Records issued session tokens to identify the clients.
                token = new Binder();
            }
        } catch (NameNotFoundException err) {
            throw new IllegalStateException(systemUiPackageName + " package not found.");
        }

        return token;
    }

    private static void logd(String msg) {
        if (DBG) {
           Slog.d(TAG, msg);
        }
    }
}
