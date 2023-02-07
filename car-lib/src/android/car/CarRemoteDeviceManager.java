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
import android.annotation.SystemApi;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.annotation.ApiRequirements;
import android.car.occupantconnection.ICarOccupantConnection;
import android.car.occupantconnection.IStateCallback;
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
 * peer clients in those occupant zones.
 * <p>
 * Unless specified explicitly, a client means an app that uses this API and runs as a
 * foreground user in an occupant zone, while a peer client means an app that has the same package
 * name as the caller app and runs as another foreground user (in another occupant zone or even
 * another Android system).
 *
 * @hide
 */
@SystemApi
public final class CarRemoteDeviceManager extends CarManagerBase {

    private static final String TAG = CarRemoteDeviceManager.class.getSimpleName();

    /**
     * Flag to indicate whether the occupant zone is powered on. If it is not powered on, the caller
     * can power it on by {@link #setOccupantZonePower}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_OCCUPANT_ZONE_POWER_ON = 1 << 0;

    /**
     * Flag to indicate whether the main display of the occupant zone is unlocked. When it is
     * locked, it can't display UI. If UI is needed to establish the connection (for example, it
     * needs to show a dialog to get user approval), the caller shouldn't request a connection to
     * the occupant zone when it's locked.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_OCCUPANT_ZONE_SCREEN_UNLOCKED = 1 << 1;

    /**
     * Flag to indicate whether the occupant zone is ready for connection.
     * If it is ready and the peer app is installed in it, the caller can call {@link
     * android.car.occupantconnection.CarOccupantConnectionManager#requestConnection} to connect to
     * the client app in the occupant zone. Note: if UI is needed, the caller should make sure the
     * main display of the occupant zone is unlocked and the client app is running in the foreground
     * before requesting a connection to it.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_OCCUPANT_ZONE_CONNECTION_READY = 1 << 2;

    /**
     * Flag to indicate whether the client app is installed in the occupant zone. If it's not
     * installed, the caller may show a Dialog to promote the user to install the app.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_CLIENT_INSTALLED = 1 << 0;

    /**
     * Flag to indicate whether the client app with the same long version code ({@link
     * PackageInfo#getLongVersionCode} is installed in the occupant zone. If it's not installed,
     * the caller may show a Dialog to promote the user to install or update the app. To get
     * detailed package info of the client app, the caller can call {@link #getEndpointPackageInfo}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_CLIENT_SAME_VERSION = 1 << 1;

    /**
     * Flag to indicate whether the client app with the same signing info ({@link
     * PackageInfo#signingInfo} is installed in the occupant zone. If it's not installed, the caller
     * may show a Dialog to promote the user to install or update the app. To get detailed
     * package info of the client app, the caller can call {@link #getEndpointPackageInfo}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_CLIENT_SAME_SIGNATURE = 1 << 2;

    /**
     * Flag to indicate whether the client app in the occupant zone is running. If it's not running,
     * the caller may show a Dialog to promote the user to start the app.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_CLIENT_RUNNING = 1 << 3;

    /**
     * Flag to indicate whether the client app in the occupant zone is running in the foreground
     * (vs background). If UI is needed, the caller shouldn't request a connection to the client app
     * when the client app is running in the background.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int FLAG_CLIENT_IN_FOREGROUND = 1 << 4;

    /**
     * Flags for the state of the occupant zone.
     *
     * @hide
     */
    @IntDef(flag = true, prefix = {"FLAG_OCCUPANT_ZONE_"}, value = {
            FLAG_OCCUPANT_ZONE_POWER_ON,
            FLAG_OCCUPANT_ZONE_SCREEN_UNLOCKED,
            FLAG_OCCUPANT_ZONE_CONNECTION_READY,
    })
    @Retention(RetentionPolicy.SOURCE)
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public @interface OccupantZoneState {
    }

    /**
     * Flags for the state of client app in the occupant zone.
     *
     * @hide
     */
    @IntDef(flag = true, prefix = {"FLAG_CLIENT_"}, value = {
            FLAG_CLIENT_INSTALLED,
            FLAG_CLIENT_SAME_VERSION,
            FLAG_CLIENT_SAME_SIGNATURE,
            FLAG_CLIENT_RUNNING,
            FLAG_CLIENT_IN_FOREGROUND
    })
    @Retention(RetentionPolicy.SOURCE)
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public @interface AppState {
    }

    /**
     * A callback to allow the client to monitor other occupant zones in the car and peer clients
     * in those occupant zones.
     * <p>
     * The caller can call {@link
     * android.car.occupantconnection.CarOccupantConnectionManager#requestConnection} to connect to
     * its peer client once the state of the peer occupant zone is {@link
     * #FLAG_OCCUPANT_ZONE_CONNECTION_READY}  and the state of the peer client becomes {@link
     * android.car.CarRemoteDeviceManager#FLAG_CLIENT_INSTALLED}. If UI is needed to establish
     * the connection, the caller must wait until {@link
     * android.car.CarRemoteDeviceManager#FLAG_OCCUPANT_ZONE_SCREEN_UNLOCKED} and {@link
     * android.car.CarRemoteDeviceManager#FLAG_CLIENT_IN_FOREGROUND}) before requesting a
     * connection.
     */
    public interface StateCallback {
        /**
         * Invoked when the callback is registered, or when the {@link OccupantZoneState} of the
         * occupant zone has changed.
         *
         * @param occupantZoneStates the state of the occupant zone. Multiple flags can be set in
         *                           the state.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void onOccupantZoneStateChanged(@NonNull OccupantZoneInfo occupantZone,
                @OccupantZoneState int occupantZoneStates);

        /**
         * Invoked when the callback is registered, or when the {@link AppState} of the peer app in
         * the given occupant zone has changed.
         *
         * @param appStates the state of the peer app. Multiple flags can be set in the state.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void onAppStateChanged(@NonNull OccupantZoneInfo occupantZone,
                @AppState int appStates);
    }

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArrayMap<StateCallback, Executor> mCallbackToExecutorMap =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private final ArrayMap<OccupantZoneInfo, Integer> mOccupantZoneStates = new ArrayMap<>();

    private final IStateCallback mBinderCallback =
            new IStateCallback.Stub() {
                @Override
                public void onOccupantZoneStateChanged(OccupantZoneInfo occupantZone,
                        int occupantZoneStates) {
                    ArrayMap<StateCallback, Executor> callbacks;
                    synchronized (mLock) {
                        callbacks = new ArrayMap<>(mCallbackToExecutorMap);
                        mOccupantZoneStates.put(occupantZone, occupantZoneStates);
                    }
                    for (Map.Entry<StateCallback, Executor> entry :
                            callbacks.entrySet()) {
                        StateCallback callback = entry.getKey();
                        Executor executor = entry.getValue();
                        executor.execute(() -> callback.onOccupantZoneStateChanged(
                                occupantZone, occupantZoneStates));
                    }
                }

                @Override
                public void onAppStateChanged(OccupantZoneInfo occupantZone,
                        int appStates) {
                    //TODO(b/257118072): implement this method.
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
     * Registers the {@code callback} to monitor the states of other occupant zones in the car and
     * the peer clients in those occupant zones.
     * <p>
     * The client app can only register one {@link StateCallback}.
     * The client app should call this method before requesting connection to its peer clients in
     * other occupant zones.
     *
     * @param executor the Executor to run the callback
     * @throws IllegalStateException if this client already registered a {@link StateCallback}
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @RequiresPermission(Car.PERMISSION_MANAGE_REMOTE_DEVICE)
    public void registerStateCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull StateCallback callback) {
        //TODO(b/257118072): update this method for the callback update.
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
                    mService.registerStateCallback(mBinderCallback);
                    // Put the callback into the map only when the remote call succeeded, otherwise
                    // it may get stuck in a bad state permanently.
                    mCallbackToExecutorMap.put(callback, executor);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to register StateCallback");
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
                    () -> callback.onOccupantZoneStateChanged(occupantZone, occupantZoneState));
        }
    }

    /**
     * Unregisters the existing {@code callback}.
     * <p>
     * This method can be called after calling {@link #registerStateCallback}, as soon
     * as this caller no longer needs to monitor other occupant zones or becomes inactive.
     * After monitoring ends, established connections won't be affected. In other words, {@link
     * android.car.occupantconnection.Payload} can still be sent.
     *
     * @throws IllegalStateException if no {@link StateCallback} was registered by
     *                               this {@link CarRemoteDeviceManager} before
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @RequiresPermission(Car.PERMISSION_MANAGE_REMOTE_DEVICE)
    public void unregisterStateCallback(@NonNull StateCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        synchronized (mLock) {
            Preconditions.checkState(mCallbackToExecutorMap.containsKey(callback),
                    "The StateCallback was not registered before");
            mCallbackToExecutorMap.remove(callback);
            if (mCallbackToExecutorMap.isEmpty()) {
                try {
                    mService.unregisterStateCallback();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to unregister OccupantZoneStateCallback");
                    handleRemoteExceptionFromCarService(e);
                }
            }
        }
    }

    /**
     * Returns the {@link PackageInfo} of the client in {@code occupantZone}, or
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
    public void setOccupantZonePower(@NonNull OccupantZoneInfo occupantZone, boolean powerOn) {
        Objects.requireNonNull(occupantZone, "occupantZone cannot be null");
        try {
            mService.setOccupantZonePower(occupantZone, powerOn);
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
