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

package android.support.car;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;

/**
 *   Top level car API.
 *   This API works only for device with {@link PackageManager#FEATURE_AUTOMOTIVE} feature
 *   supported or device with Google play service.
 *   Calling this API with device with no such feature will lead into an exception.
 *
 */
public class Car {

    /** Service name for {@link CarSensorManager}, to be used in {@link #getCarManager(String)}. */
    public static final String SENSOR_SERVICE = "sensor";

    /** Service name for {@link CarInfoManager}, to be used in {@link #getCarManager(String)}. */
    public static final String INFO_SERVICE = "info";

    /** Type of car connection: car emulator, not physical connection. */
    public static final int CONNECTION_TYPE_EMULATOR        = 0;
    /** Type of car connection: connected to a car via USB. */
    public static final int CONNECTION_TYPE_USB             = 1;
    /** Type of car connection: connected to a car via WIFI. */
    public static final int CONNECTION_TYPE_WIFI            = 2;
    /** Type of car connection: on-device car emulator, for development (e.g. Local Head Unit). */
    public static final int CONNECTION_TYPE_ON_DEVICE_EMULATOR = 3;
    /** Type of car connection: car emulator, connected over ADB (e.g. Desktop Head Unit). */
    public static final int CONNECTION_TYPE_ADB_EMULATOR = 4;
    /** Type of car connection: platform runs directly in car. */
    public static final int CONNECTION_TYPE_EMBEDDED = 5;

    /** @hide */
    @IntDef({CONNECTION_TYPE_EMULATOR, CONNECTION_TYPE_USB, CONNECTION_TYPE_WIFI,
        CONNECTION_TYPE_ON_DEVICE_EMULATOR, CONNECTION_TYPE_ADB_EMULATOR, CONNECTION_TYPE_EMBEDDED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConnectionType {}

    /** permission necessary to access car's mileage information */
    public static final String PERMISSION_MILEAGE = "android.support.car.permission.CAR_MILEAGE";
    /** permission necessary to access car's fuel level */
    public static final String PERMISSION_FUEL = "android.support.car.permission.CAR_FUEL";
    /** permission necessary to access car's speed */
    public static final String PERMISSION_SPEED = "android.support.car.permission.CAR_SPEED";
    /** permission necessary to access car specific communication channel */
    public static final String PERMISSION_VENDOR_EXTENSION =
            "android.support.car.permission.CAR_VENDOR_EXTENSION";

    /** @hide */
    public static final String CAR_SERVICE_INTERFACE_NAME = "android.support.car.ICar";

    /**
     * PackageManager.FEATURE_AUTOMOTIVE from M. But redefine here to support L.
     * @hide
     */
    private static final String FEATURE_AUTOMOTIVE = "android.hardware.type.automotive";

    /**
     * {@link CarServiceLoader} implementation for projected mode. Only available when projected
     * client library is linked.
     * @hide
     */
    private static final String PROJECTED_CAR_SERVICE_LOADER =
            "com.google.android.gms.car.GoogleCarServiceLoader";

    private static final int VERSION = 1;

    private final Context mContext;
    private final Looper mLooper;
    @GuardedBy("this")
    private ICar mService;
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    @GuardedBy("this")
    private int mConnectionState;
    @GuardedBy("this")
    private int mServiceVersion = 1; // default

    private final ServiceConnectionListener mServiceConnectionListener =
            new ServiceConnectionListener () {
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (Car.this) {
                mService = ICar.Stub.asInterface(service);
                mConnectionState = STATE_CONNECTED;
                // getVersion can fail but let it pass through as it is better to
                // throw right exception in next car api call.
                try {
                    mServiceVersion = mService.getVersion();
                } catch (RemoteException e) {
                    Log.w(CarLibLog.TAG_CAR, "RemoteException in getVersion", e);
                }
            }
            if (mServiceVersion < VERSION) {
                Log.w(CarLibLog.TAG_CAR, "Old service version:" + mServiceVersion +
                        " for client lib:" + VERSION);
            }
            mServiceConnectionListenerClient.onServiceConnected(name, service);
        }

        public void onServiceDisconnected(ComponentName name) {
            synchronized (Car.this) {
                mService = null;
                if (mConnectionState  == STATE_DISCONNECTED) {
                    return;
                }
                mConnectionState = STATE_DISCONNECTED;
            }
            mServiceConnectionListenerClient.onServiceDisconnected(name);
            connect();
        }

        public void onServiceSuspended(int cause) {
            mServiceConnectionListenerClient.onServiceSuspended(cause);
        }

        public void onServiceConnectionFailed(int cause) {
            mServiceConnectionListenerClient.onServiceConnectionFailed(cause);
        }
    };

    private final ServiceConnectionListener mServiceConnectionListenerClient;
    private final Object mCarManagerLock = new Object();
    @GuardedBy("mCarManagerLock")
    private final HashMap<String, CarManagerBase> mServiceMap = new HashMap<>();
    private final CarServiceLoader mCarServiceLoader;

    /**
     * This defines CarServiceLoader that will be tried for FEATURE_AUTOMOTIVE case.
     * For system test and system api, there are separate static libraries. If those
     * libraries are linked, CarServiceLoader from those libraries are loaded so that
     * custom car managers can be populated from there.
     * This is done to prevent bloating the library which is not relevant for the app.
     */
    private static final String[] CAR_SERVICE_LOADERS_FOR_FEATURE_AUTOMOTIVE = {
        "com.android.car.SystemTestApiCarServiceLoader",
        "com.android.car.SystemApiCarServiceLoader",
    };
    /**
     * Create Car instance for all Car API access.
     * @param context
     * @param serviceConnectionListener listner for monitoring service connection.
     * @param looper Looper to dispatch all listeners. If null, it will use main thread. Note that
     *        service connection listener will be always in main thread regardless of this Looper.
     */
    public Car(Context context, ServiceConnectionListener serviceConnectionListener,
            @Nullable Looper looper) {
        mContext = context;
        mServiceConnectionListenerClient = serviceConnectionListener;
        if (looper == null) {
            mLooper = Looper.getMainLooper();
        } else {
            mLooper = looper;
        }
        if (mContext.getPackageManager().hasSystemFeature(FEATURE_AUTOMOTIVE)) {
            CarServiceLoader loader = null;
            for (String classToTry : CAR_SERVICE_LOADERS_FOR_FEATURE_AUTOMOTIVE) {
                try {
                    loader = loadCarServiceLoader(classToTry, context, mServiceConnectionListener,
                            mLooper);
                } catch (IllegalArgumentException e) {
                    // expected when only lower level libraries are linked.
                }
                if (loader != null) {
                    break;
                }
            }
            if (loader == null) {
                mCarServiceLoader = new DefaultCarServiceLoader(context,
                        mServiceConnectionListener, mLooper);
            } else {
                mCarServiceLoader = loader;
            }
        } else {
            mCarServiceLoader = loadCarServiceLoader(PROJECTED_CAR_SERVICE_LOADER, context,
                    mServiceConnectionListener, mLooper);
        }
    }

    private CarServiceLoader loadCarServiceLoader(String carServiceLoaderClassName,
            Context context, ServiceConnectionListener serviceConnectionListener, Looper looper)
                    throws IllegalArgumentException {
        Class carServiceLoaderClass = null;
        try {
            carServiceLoaderClass = Class.forName(carServiceLoaderClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot find CarServiceLoader implementation:" +
                    carServiceLoaderClassName, e);
        }
        Constructor<?> ctor;
        try {
            ctor = carServiceLoaderClass.getDeclaredConstructor(Context.class,
                    ServiceConnectionListener.class, Looper.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Cannot construct CarServiceLoader, no constructor: "
                    + carServiceLoaderClassName, e);
        }
        try {
            return (CarServiceLoader) ctor.newInstance(context,
                    serviceConnectionListener, looper);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException e) {
            throw new IllegalArgumentException(
                    "Cannot construct CarServiceLoader, constructor failed for "
                    + carServiceLoaderClass.getName(), e);
        }
    }

    /**
     * Car constructor when ICar binder is already available.
     * @param context
     * @param service
     * @param looper
     *
     * @hide
     */
    public Car(Context context, ICar service, @Nullable Looper looper) {
        mContext = context;
        if (looper == null) {
            mLooper = Looper.getMainLooper();
        } else {
            mLooper = looper;
        }
        mService = service;
        mConnectionState = STATE_CONNECTED;
        mCarServiceLoader = null;
        mServiceConnectionListenerClient = null;
    }

    /**
     * Connect to car service. This can be called while it is disconnected.
     * @throws IllegalStateException If connection is still on-going from previous
     *         connect call or it is already connected
     */
    public void connect() throws IllegalStateException {
        synchronized (this) {
            if (mConnectionState != STATE_DISCONNECTED) {
                throw new IllegalStateException("already connected or connecting");
            }
            mConnectionState = STATE_CONNECTING;
            mCarServiceLoader.connect();
        }
    }

    /**
     * Disconnect from car service. This can be called while disconnected. Once disconnect is
     * called, all Car*Managers from this instance becomes invalid, and
     * {@link Car#getCarManager(String)} will return different instance if it is connected again.
     */
    public void disconnect() {
        synchronized (this) {
            if (mConnectionState == STATE_DISCONNECTED) {
                return;
            }
            tearDownCarManagers();
            mService = null;
            mConnectionState = STATE_DISCONNECTED;
            mCarServiceLoader.disconnect();
        }
    }

    /**
     * Tells if it is connected to the service or not. This will return false if it is still
     * connecting.
     * @return
     */
    public boolean isConnected() {
        synchronized (this) {
            return mService != null;
        }
    }

    /**
     * Tells if this instance is already connecting to car service or not.
     * @return
     */
    public boolean isConnecting() {
        synchronized (this) {
            return mConnectionState == STATE_CONNECTING;
        }
    }

    /**
     * Get car specific service as in {@link Context#getSystemService(String)}. Returned
     * {@link Object} should be type-casted to the desired service.
     * For example, to get sensor service,
     * SensorManagerService sensorManagerService = car.getCarManager(Car.SENSOR_SERVICE);
     * @param serviceName Name of service that should be created like {@link #SENSOR_SERVICE}.
     * @return Matching service manager or null if there is no such service.
     * @throws CarNotConnectedException
     */
    public Object getCarManager(String serviceName) throws CarNotConnectedException {
        CarManagerBase manager = null;
        ICar service = getICarOrThrow();
        synchronized (mCarManagerLock) {
            manager = mServiceMap.get(serviceName);
            if (manager == null) {
                try {
                    IBinder binder = service.getCarService(serviceName);
                    if (binder == null) {
                        Log.w(CarLibLog.TAG_CAR, "getCarManager could not get binder for service:" +
                                serviceName);
                        return null;
                    }
                    manager = mCarServiceLoader.createCarManager(serviceName, binder);
                    if (manager == null) {
                        Log.w(CarLibLog.TAG_CAR,
                                "getCarManager could not create manager for service:" +
                                serviceName);
                        return null;
                    }
                    mServiceMap.put(serviceName, manager);
                } catch (RemoteException e) {
                    handleRemoteExceptionAndThrow(e);
                }
            }
        }
        return manager;
    }

    /**
     * Return the type of currently connected car.
     * @return
     * @throws CarNotConnectedException
     */
    @ConnectionType
    public int getCarConnectionType() throws CarNotConnectedException {
        ICar service = getICarOrThrow();
        try {
          return service.getCarConnectionType();
        } catch (RemoteException e) {
            handleRemoteExceptionAndThrow(e);
        }
        return Car.CONNECTION_TYPE_EMULATOR;
    }

    private synchronized ICar getICarOrThrow() throws IllegalStateException {
        if (mService == null) {
            throw new IllegalStateException("not connected");
        }
        return mService;
    }

    private void handleRemoteException(RemoteException e) {
        Log.w(CarLibLog.TAG_CAR, "RemoteException", e);
        disconnect();
    }

    private void handleRemoteExceptionAndThrow(RemoteException e) throws CarNotConnectedException {
        handleRemoteException(e);
        throw new CarNotConnectedException(e);
    }

    private void tearDownCarManagers() {
        synchronized (mCarManagerLock) {
            for (CarManagerBase manager: mServiceMap.values()) {
                manager.onCarDisconnected();
            }
            mServiceMap.clear();
        }
    }
}
