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

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.support.car.Car;
import android.support.car.content.pm.CarPackageManagerEmbedded;
import android.support.car.hardware.CarSensorManagerEmbedded;
import android.support.car.media.CarAudioManagerEmbedded;
import android.support.car.navigation.CarNavigationManagerEmbedded;
import android.support.car.CarConnectionListener;

import java.util.LinkedList;

/**
 * Default CarServiceLoader for system with built-in car service (=embedded).
 * @hide
 */
public class CarServiceLoaderEmbedded extends CarServiceLoader {

    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            getConnectionListener().onServiceConnected(name, service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            getConnectionListener().onServiceDisconnected(name);
        }
    };

    private final android.car.Car mCar;
    private final LinkedList<CarConnectionListenerProxy> mCarConnectionListenerProxies =
            new LinkedList<>();

    public CarServiceLoaderEmbedded(Context context, ServiceConnectionListener listener,
            Looper looper) {
        super(context, listener, looper);
        mCar = android.car.Car.createCar(context, mServiceConnection, looper);
    }

    @Override
    public void connect() throws IllegalStateException {
        mCar.connect();
    }

    @Override
    public void disconnect() {
        mCar.disconnect();
    }

    @Override
    public boolean isConnectedToCar() {
        return mCar.isConnectedToCar();
    }

    @Override
    public int getCarConnectionType() {
        return mCar.getCarConnectionType();
    }

    @Override
    public void registerCarConnectionListener(CarConnectionListener listener) {
        CarConnectionListenerProxy newProxy = null;
        synchronized (this) {
            boolean alreadyRegistered = false;
            for (CarConnectionListenerProxy proxy : mCarConnectionListenerProxies) {
                if (proxy.isSameListener(listener)) {
                    alreadyRegistered = true;
                    break;
                }
            }
            if (!alreadyRegistered) {
                newProxy = new CarConnectionListenerProxy(listener);
                mCarConnectionListenerProxies.add(newProxy);
            }
        }
        if (newProxy != null) {
            mCar.registerCarConnectionListener(newProxy);
        }
    }

    @Override
    public void unregisterCarConnectionListener(CarConnectionListener listener) {
        CarConnectionListenerProxy matchingProxy = null;
        synchronized (this) {
            for (CarConnectionListenerProxy proxy : mCarConnectionListenerProxies) {
                if (proxy.isSameListener(listener)) {
                    matchingProxy = proxy;
                    break;
                }
            }
            if (matchingProxy != null) {
                mCarConnectionListenerProxies.remove(matchingProxy);
            }
        }
        if (matchingProxy != null) {
            mCar.unregisterCarConnectionListener(matchingProxy);
        }
    }

    @Override
    public Object getCarManager(String serviceName) {
        Object manager = mCar.getCarManager(serviceName);
        if (manager == null) {
            return null;
        }
        // For publicly available versions, return wrapper version.
        switch (serviceName) {
        case Car.AUDIO_SERVICE:
            return new CarAudioManagerEmbedded(manager);
        case Car.SENSOR_SERVICE:
            return new CarSensorManagerEmbedded(manager);
        case Car.INFO_SERVICE:
            return new CarInfoManagerEmbedded(manager);
        case Car.APP_CONTEXT_SERVICE:
            return new CarAppContextManagerEmbedded(manager);
        case Car.PACKAGE_SERVICE:
            return new CarPackageManagerEmbedded(manager);
        case Car.CAR_NAVIGATION_SERVICE:
            return new CarNavigationManagerEmbedded(manager);
        default:
            return manager;
        }
    }

    private static class CarConnectionListenerProxy implements android.car.CarConnectionListener {
        private final CarConnectionListener mListener;

        public CarConnectionListenerProxy(CarConnectionListener listener) {
            mListener = listener;
        }

        public boolean isSameListener(CarConnectionListener listener) {
            return mListener == listener;
        }

        @Override
        public void onConnected(int connectionType) {
            mListener.onConnected(connectionType);
        }

        @Override
        public void onDisconnected() {
           mListener.onDisconnected();
        }
    }
}
