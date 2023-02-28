/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.car;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.annotation.ApiRequirements;
import android.car.occupantconnection.ICarOccupantConnection;
import android.car.occupantconnection.IOccupantZoneStateCallback;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * API for monitoring the states of occupant zones in the car, managing their power, and monitoring
 * peer clients on them.
 * <p>
 * Unless specified explicitly, a client means an app that uses this API and runs as a
 * foreground user on an occupant zone, while a peer client means an app that has the same package
 * name as the caller app and runs as another foreground user (on another occupant zone or even
 * another Android system).
 *
 * @hide
 */
// TODO(b/257117236): Change it to system API once it's ready to release.
// @SystemApi
public final class CarRemoteDeviceManager extends CarManagerBase {

    private static final String TAG = CarRemoteDeviceManager.class.getSimpleName();

    /**
     * Flag to indicate whether the occupant zone is powered on. If it is not powered on, the caller
     * can power it on by {@link #controlOccupantZonePower}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_POWER_ON = 1 << 0;

    /**
     * Flag to indicate whether the main display of the occupant zone is unlocked. When it is
     * locked, it can't display UI. If UI is needed to establish the connection (for example, it
     * needs to show a dialog to get user approval), the caller shouldn't request a connection to
     * the occupant zone when it's locked.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_SCREEN_UNLOCKED = 1 << 1;

    /**
     * Flag to indicate whether the client app is installed on the occupant zone. If it's not
     * installed, the caller may show a Dialog to promote the user to install the app.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_CLIENT_INSTALLED = 1 << 2;

    /**
     * Flag to indicate whether the client app with the same long version code ({@link
     * PackageInfo#getLongVersionCode} is installed on the occupant zone. If it's not installed,
     * the caller may show a Dialog to promote the user to install or update the app. To get
     * detailed package info of the client app, the caller can call {@link #getEndpointPackageInfo}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_CLIENT_SAME_VERSION = 1 << 3;

    /**
     * Flag to indicate whether the client app with the same signing info ({@link
     * PackageInfo#signingInfo} is installed on the occupant zone. If it's not installed, the caller
     * may show a Dialog to promote the user to install or update the app. To get detailed
     * package info of the client app, the caller can call {@link #getEndpointPackageInfo}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_CLIENT_SAME_SIGNATURE = 1 << 4;

    /**
     * Flag to indicate whether the client app on the occupant zone is running. If it's not running,
     * the caller may show a Dialog to promote the user to start the app.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_CLIENT_RUNNING = 1 << 5;

    /**
     * Flag to indicate whether the client app on the occupant zone is running in the foreground
     * (vs background). If UI is needed, the caller shouldn't request a connection to the client app
     * when the client app is running in the background.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_CLIENT_IN_FOREGROUND = 1 << 6;

    /**
     * Flag to indicate whether the client app on the occupant zone is ready for connection.
     * If it is ready, the caller can call {@link
     * android.car.occupantconnection.CarOccupantConnectionManager#requestConnection} to connect to
     * the client app on the occupant zone. Note: if UI is needed, the caller should make sure the
     * main display of the occupant zone is unlocked and the client app is running in the foreground
     * before requesting a connection to it.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_CLIENT_CONNECTION_READY = 1 << 7;

    /**
     * Flags for the state of the occupant zone and the state of the client app on the occupant
     * zone.
     *
     * @hide
     */
    @IntDef(flag = true, prefix = {"FLAG_"}, value = {
            FLAG_POWER_ON,
            FLAG_SCREEN_UNLOCKED,
            FLAG_CLIENT_INSTALLED,
            FLAG_CLIENT_SAME_VERSION,
            FLAG_CLIENT_SAME_SIGNATURE,
            FLAG_CLIENT_RUNNING,
            FLAG_CLIENT_IN_FOREGROUND,
            FLAG_CLIENT_CONNECTION_READY
    })
    @Retention(RetentionPolicy.SOURCE)
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public @interface OccupantZoneState {
    }

    /**
     * A callback to allow the client to monitor other occupant zones in the car and peer clients
     * on them.
     * <p>
     * The caller can call {@link
     * android.car.occupantconnection.CarOccupantConnectionManager#requestConnection} to connect to
     * its peer client once the state of the peer occupant zone is {@link
     * #FLAG_CLIENT_CONNECTION_READY} (and {@link #FLAG_SCREEN_UNLOCKED} and {@link
     * #FLAG_CLIENT_IN_FOREGROUND} if UI is needed).
     */
    public interface OccupantZoneStateCallback {
        /**
         * Invoked when the callback is registered, or when the {@link OccupantZoneState} of the
         * occupant zone has changed.
         *
         * @param occupantZoneStates the state of the occupant zone. Multiple flags can be set in
         *                           the state.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void onStateChanged(@NonNull OccupantZoneInfo occupantZone,
                @OccupantZoneState int occupantZoneStates);
    }

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArrayMap<OccupantZoneStateCallback, Executor> mCallbackToExecutorMap =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private final ArrayMap<OccupantZoneInfo, Integer> mOccupantZoneStates = new ArrayMap<>();

    private final IOccupantZoneStateCallback mBinderCallback =
            new IOccupantZoneStateCallback.Stub() {
                @Override
                public void onStateChanged(OccupantZoneInfo occupantZone,
                        int occupantZoneState) {
                    ArrayMap<OccupantZoneStateCallback, Executor> callbacks;
                    synchronized (mLock) {
                        callbacks = new ArrayMap<>(mCallbackToExecutorMap);
                        mOccupantZoneStates.put(occupantZone, occupantZoneState);
                    }
                    for (Map.Entry<OccupantZoneStateCallback, Executor> entry :
                            callbacks.entrySet()) {
                        OccupantZoneStateCallback callback = entry.getKey();
                        Executor executor = entry.getValue();
                        executor.execute(
                                () -> callback.onStateChanged(occupantZone, occupantZoneState));
                    }
                }
            };

    private final ICarOccupantConnection mService;
    private final String mPackageName;

    /** @hide */
    public CarRemoteDeviceManager(Car car, IBinder service) {
        super(car);
        mService = ICarOccupantConnection.Stub.asInterface(service);
        mPackageName = mCar.getContext().getPackageName();
    }

    /** @hide */
    @Override
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void onCarDisconnected() {
        synchronized (mLock) {
            mCallbackToExecutorMap.clear();
            mOccupantZoneStates.clear();
        }
    }

    /**
     * Registers the {@code callback} to monitor the states of other occupant zones and peer clients
     * in the car. Multiple {@link OccupantZoneStateCallback}s can be registered.
     * <p>
     * The client app should call this method before requesting connection to its peer clients on
     * other occupant zones.
     *
     * @param executor the Executor to run the callback
     * @throws IllegalStateException if the {@code callback} was registered already
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @RequiresPermission(Car.PERMISSION_MANAGE_REMOTE_DEVICE)
    public void registerOccupantZoneStateCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull OccupantZoneStateCallback callback) {
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");
        ArrayMap<OccupantZoneInfo, Integer> occupantZoneStates;
        synchronized (mLock) {
            Preconditions.checkState(!mCallbackToExecutorMap.containsKey(callback),
                    "The OccupantZoneStateCallback was registered already");
            if (mCallbackToExecutorMap.isEmpty()) {
                // This is the first client callback, so register the mBinderCallback.
                // The client callback will be invoked when mBinderCallback is invoked.
                try {
                    mService.registerOccupantZoneStateCallback(mBinderCallback);
                    // Put the callback into the map only when the remote call succeeded, otherwise
                    // it may get stuck in a bad state permanently.
                    mCallbackToExecutorMap.put(callback, executor);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to register OccupantZoneStateCallback");
                    handleRemoteExceptionFromCarService(e);
                }
                return;
            }
            occupantZoneStates = new ArrayMap<>(mOccupantZoneStates);
        }
        // This is not the first client callback, so mBinderCallback was already registered,
        // thus it won't invoke the client callback unless there is a state change.
        // So invoked the client callback with the cached states now.
        for (Map.Entry<OccupantZoneInfo, Integer> entry : occupantZoneStates.entrySet()) {
            OccupantZoneInfo occupantZone = entry.getKey();
            Integer occupantZoneState = entry.getValue();
            executor.execute(
                    () -> callback.onStateChanged(occupantZone, occupantZoneState));
        }
    }

    /**
     * Unregisters the existing {@code callback}.
     * <p>
     * This method can be called after calling {@link #registerOccupantZoneStateCallback}, as soon
     * as this caller no longer needs to monitor other occupant zones or becomes inactive.
     * After monitoring ends, established connections won't be affected. In other words, {@link
     * android.car.occupantconnection.Payload} can still be sent.
     *
     * @throws IllegalStateException if the {@code callback} was not registered before
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @RequiresPermission(Car.PERMISSION_MANAGE_REMOTE_DEVICE)
    public void unregisterOccupantZoneStateCallback(@NonNull OccupantZoneStateCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        synchronized (mLock) {
            Preconditions.checkState(mCallbackToExecutorMap.containsKey(callback),
                    "The OccupantZoneStateCallback was not registered before");
            mCallbackToExecutorMap.remove(callback);
            if (mCallbackToExecutorMap.isEmpty()) {
                try {
                    mService.unregisterOccupantZoneStateCallback();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to unregister OccupantZoneStateCallback");
                    handleRemoteExceptionFromCarService(e);
                }
            }
        }
    }

    /**
     * Returns the {@link PackageInfo} of the client on {@code occupantZone}, or
     * {@code null} if there is no such client or an error occurred.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @RequiresPermission(Car.PERMISSION_MANAGE_REMOTE_DEVICE)
    @Nullable
    public PackageInfo getEndpointPackageInfo(@NonNull OccupantZoneInfo occupantZone) {
        Objects.requireNonNull(occupantZone, "occupantZone cannot be null");
        try {
            return mService.getEndpointPackageInfo(occupantZone.zoneId, mPackageName);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get peer endpoint PackageInfo in " + occupantZone);
            return handleRemoteExceptionFromCarService(e, null);
        }
    }

    /**
     * If {@code powerOn} is {@code true}, powers on all the displays of the given {@code
     * occupantZone}, and powers on the associated Android system. If {@code powerOn} is
     * {@code false}, powers off all the displays of given {@code occupantZone}, but doesn't
     * power off the associated Android system.
     * <p>
     * It is not allowed to control the power of the driver occupant zone.
     *
     * @throws UnsupportedOperationException if {@code occupantZone} represents the driver occupant
     *                                       zone
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @RequiresPermission(Car.PERMISSION_MANAGE_REMOTE_DEVICE)
    public void controlOccupantZonePower(@NonNull OccupantZoneInfo occupantZone, boolean powerOn) {
        Objects.requireNonNull(occupantZone, "occupantZone cannot be null");
        try {
            mService.controlOccupantZonePower(occupantZone, powerOn);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to control the power of " + occupantZone);
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Returns {@code true} if the associated Android system AND all the displays of the given
     * {@code occupantZone} are powered on. Returns {@code false} otherwise.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @RequiresPermission(Car.PERMISSION_MANAGE_REMOTE_DEVICE)
    public boolean isOccupantZonePowerOn(@NonNull OccupantZoneInfo occupantZone) {
        Objects.requireNonNull(occupantZone, "occupantZone cannot be null");
        try {
            return mService.isOccupantZonePowerOn(occupantZone);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get power state of " + occupantZone);
            return handleRemoteExceptionFromCarService(e, false);
        }
    }
}
