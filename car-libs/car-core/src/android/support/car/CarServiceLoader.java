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

import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.support.car.content.pm.ICarPackageManager;
import android.support.car.content.pm.CarPackageManager;
import android.support.car.hardware.CarSensorManager;
import android.support.car.hardware.ICarSensor;
import android.support.car.media.CarAudioManager;
import android.support.car.media.ICarAudio;
import android.support.car.navigation.CarNavigationStatusManager;
import android.support.car.navigation.ICarNavigationStatus;

/**
 * CarServiceLoader is the abstraction for loading different types of car service.
 * @hide
 */
public abstract class CarServiceLoader {

    private final Context mContext;
    private final ServiceConnectionListener mListener;
    private final Looper mLooper;

    public CarServiceLoader(Context context, ServiceConnectionListener listener, Looper looper) {
        mContext = context;
        mListener = listener;
        mLooper = looper;
    }

    public abstract void connect() throws IllegalStateException;
    public abstract void disconnect();

    /**
     * Factory method to create Car*Manager for the given car service implementation.
     * Each implementation can add its own custom Car*Manager while default implementation will
     * handle all standard Car*Managers.
     * @param serviceName service name for the given Car*Manager.
     * @param binder binder implementation received from car service
     * @return Car*Manager instance for the given serviceName / binder. null if given service is
     *         not supported.
     */
    public CarManagerBase createCarManager(String serviceName,
            IBinder binder) {
        CarManagerBase manager = null;
        switch (serviceName) {
            case Car.AUDIO_SERVICE:
                manager = new CarAudioManager(ICarAudio.Stub.asInterface(binder));
                break;
            case Car.SENSOR_SERVICE:
                manager = new CarSensorManager(mContext, ICarSensor.Stub.asInterface(binder),
                        mLooper);
                break;
            case Car.INFO_SERVICE:
                manager = new CarInfoManager(ICarInfo.Stub.asInterface(binder));
                break;
            case Car.APP_CONTEXT_SERVICE:
                manager = new CarAppContextManager(IAppContext.Stub.asInterface(binder), mLooper);
                break;
            case Car.PACKAGE_SERVICE:
                manager = new CarPackageManager(ICarPackageManager.Stub.asInterface(binder),
                        mContext);
                break;
            case Car.CAR_NAVIGATION_SERVICE:
                manager = new CarNavigationStatusManager(
                        ICarNavigationStatus.Stub.asInterface(binder), mLooper);
                break;
        }
        return manager;
    }

    protected Context getContext() {
        return mContext;
    }

    protected ServiceConnectionListener getConnectionListener() {
        return mListener;
    }

    protected Looper getLooper() {
        return mLooper;
    }
}
