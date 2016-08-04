/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.support.car.hardware;

import android.content.Context;
import android.support.car.CarNotConnectedException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

/**
 *  @hide
 */
public class CarSensorManagerEmbedded extends CarSensorManager {
    private static final String TAG = "CarSensorsProxy";

    private final android.car.hardware.CarSensorManager mManager;
    private final CarSensorsProxy mCarSensorsProxy;
    private final LinkedList<CarSensorEventListenerProxy> mListeners = new LinkedList<>();

    public CarSensorManagerEmbedded(Object manager, Context context) {
        mManager = (android.car.hardware.CarSensorManager) manager;
        mCarSensorsProxy = new CarSensorsProxy(context);
    }

    @Override
    public int[] getSupportedSensors() throws CarNotConnectedException {
        try {
            Set<Integer> sensorsSet = new HashSet<Integer>();
            for (Integer sensor : mManager.getSupportedSensors()) {
                sensorsSet.add(sensor);
            }
            for (Integer proxySensor : mCarSensorsProxy.getSupportedSensors()) {
                sensorsSet.add(proxySensor);
            }
            return toIntArray(sensorsSet);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    private static int[] toIntArray(Collection<Integer> collection) {
        int len = collection.size();
        int[] arr = new int[len];
        int arrIndex = 0;
        for (Integer item : collection) {
            arr[arrIndex] = item;
            arrIndex++;
        }
        return arr;
    }

    @Override
    public boolean isSensorSupported(int sensorType) throws CarNotConnectedException {
        try {
            return mManager.isSensorSupported(sensorType)
                    || mCarSensorsProxy.isSensorSupported(sensorType);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    private boolean isSensorProxied(int sensorType) throws CarNotConnectedException {
        try {
            return !mManager.isSensorSupported(sensorType)
                    && mCarSensorsProxy.isSensorSupported(sensorType);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public boolean registerListener(CarSensorEventListener listener, int sensorType,
            int rate) throws CarNotConnectedException, IllegalArgumentException {
        if (isSensorProxied(sensorType)) {
            return mCarSensorsProxy.registerSensorListener(listener, sensorType, rate);
        }
        CarSensorEventListenerProxy proxy = null;
        synchronized (this) {
            proxy = findListenerLocked(listener);
            if (proxy == null) {
                proxy = new CarSensorEventListenerProxy(listener, sensorType);
                mListeners.add(proxy);
            } else {
                proxy.sensors.add(sensorType);
            }
        }
        try {
            return mManager.registerListener(proxy, sensorType, rate);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public void unregisterListener(CarSensorEventListener listener)
            throws CarNotConnectedException {
        mCarSensorsProxy.unregisterSensorListener(listener);
        CarSensorEventListenerProxy proxy = null;
        synchronized (this) {
            proxy = findListenerLocked(listener);
            if (proxy == null) {
                return;
            }
            mListeners.remove(proxy);
        }
        try {
            mManager.unregisterListener(proxy);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public void unregisterListener(CarSensorEventListener listener, int sensorType)
            throws CarNotConnectedException {
        mCarSensorsProxy.unregisterSensorListener(listener, sensorType);
        CarSensorEventListenerProxy proxy = null;
        synchronized (this) {
            proxy = findListenerLocked(listener);
            if (proxy == null) {
                return;
            }
            proxy.sensors.remove(sensorType);
            if (proxy.sensors.isEmpty()) {
                mListeners.remove(proxy);
            }
        }
        try {
            mManager.unregisterListener(proxy, sensorType);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public CarSensorEvent getLatestSensorEvent(int type) throws CarNotConnectedException {
        if (isSensorProxied(type)) {
            return mCarSensorsProxy.getLatestSensorEvent(type);
        }
        try {
            return convert(mManager.getLatestSensorEvent(type));
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public void onCarDisconnected() {
        //nothing to do
    }

    private CarSensorEventListenerProxy findListenerLocked(CarSensorEventListener listener) {
        for (CarSensorEventListenerProxy proxy : mListeners) {
            if (proxy.listener == listener) {
                return proxy;
            }
        }
        return null;
    }

    private static CarSensorEvent convert(android.car.hardware.CarSensorEvent event) {
        if (event == null) {
            return null;
        }
        return new CarSensorEvent(event.sensorType, event.timeStampNs, event.floatValues,
                event.intValues);
    }

    private static class CarSensorEventListenerProxy implements
            android.car.hardware.CarSensorManager.CarSensorEventListener {

        public final CarSensorEventListener listener;
        public final Set<Integer> sensors = new HashSet<Integer>();

        CarSensorEventListenerProxy(CarSensorEventListener listener, int sensor) {
            this.listener = listener;
            this.sensors.add(sensor);
        }

        @Override
        public void onSensorChanged(android.car.hardware.CarSensorEvent event) {
            CarSensorEvent newEvent = convert(event);
            listener.onSensorChanged(newEvent);
        }
    }
}
