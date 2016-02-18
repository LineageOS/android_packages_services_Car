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

package com.android.car;

import android.car.Car;
import android.car.ICar;
import android.car.ICarConnectionListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.car.hal.VehicleHal;
import com.android.car.pm.CarPackageManagerService;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Collection;

public class ICarImpl extends ICar.Stub {

    @GuardedBy("ICarImpl.class")
    private static ICarImpl sInstance = null;

    private final Context mContext;
    private final VehicleHal mHal;

    private final CarPowerManagementService mCarPowerManagementService;
    private final CarSensorService mCarSensorService;
    private final CarInfoService mCarInfoService;
    private final CarAudioService mCarAudioService;
    private final CarHvacService mCarHvacService;
    private final CarRadioService mCarRadioService;
    private final CarNightService mCarNightService;
    private final AppContextService mAppContextService;
    private final CarPackageManagerService mCarPackageManagerService;
    private final GarageModeService mGarageModeService;
    private final CarNavigationService mCarNavigationStatusService;

    /** Test only service. Populate it only when necessary. */
    @GuardedBy("this")
    private CarTestService mCarTestService;
    private final CarServiceBase[] mAllServices;

    /** Holds connection listener from client. Only necessary for mocking. */
    private final BinderInterfaceContainer<ICarConnectionListener> mCarConnectionListeners =
            new BinderInterfaceContainer<>(null);

    public synchronized static ICarImpl getInstance(Context serviceContext) {
        if (sInstance == null) {
            sInstance = new ICarImpl(serviceContext);
            sInstance.init();
        }
        return sInstance;
    }

    public synchronized static void releaseInstance() {
        if (sInstance == null) {
            return;
        }
        sInstance.release();
        sInstance = null;
    }

    public ICarImpl(Context serviceContext) {
        mContext = serviceContext;
        mHal = VehicleHal.getInstance();
        mCarPowerManagementService = new CarPowerManagementService(serviceContext);
        mGarageModeService = new GarageModeService(mContext, mCarPowerManagementService);
        mCarInfoService = new CarInfoService(serviceContext);
        mAppContextService = new AppContextService(serviceContext);
        mCarSensorService = new CarSensorService(serviceContext);
        mCarAudioService = new CarAudioService(serviceContext, mAppContextService);
        mCarHvacService = new CarHvacService(serviceContext);
        mCarRadioService = new CarRadioService(serviceContext);
        mCarNightService = new CarNightService(serviceContext);
        mCarPackageManagerService = new CarPackageManagerService(serviceContext);
        mCarNavigationStatusService = new CarNavigationService(serviceContext);

        // Be careful with order. Service depending on other service should be inited later.
        mAllServices = new CarServiceBase[] {
                mCarPowerManagementService,
                mGarageModeService,
                mCarPackageManagerService,
                mCarInfoService,
                mAppContextService,
                mCarSensorService,
                mCarAudioService,
                mCarHvacService,
                mCarRadioService,
                mCarNightService,
                mCarNavigationStatusService,
                };
    }

    private void init() {
        for (CarServiceBase service: mAllServices) {
            service.init();
        }
    }

    private void release() {
        mCarConnectionListeners.clear();
        // release done in opposite order from init
        for (int i = mAllServices.length - 1; i >= 0; i--) {
            mAllServices[i].release();
        }
        VehicleHal.releaseInstance();
    }

    /** Only for CarTestService */
    void startMocking() {
        reinitServices();
    }

    /** Only for CarTestService */
    void stopMocking() {
        reinitServices();
    }

    /** Reset all services when starting / stopping vehicle hal mocking */
    private void reinitServices() {
        for (int i = mAllServices.length - 1; i >= 0; i--) {
            mAllServices[i].release();
        }
        for (CarServiceBase service: mAllServices) {
            service.init();
        }
        // send disconnect event and connect event to all clients.
        Collection<BinderInterfaceContainer.BinderInterface<ICarConnectionListener>>
                connectionListeners = mCarConnectionListeners.getInterfaces();
        for (BinderInterfaceContainer.BinderInterface<ICarConnectionListener> client :
                connectionListeners) {
            try {
                client.binderInterface.onDisconnected();
            } catch (RemoteException e) {
                //ignore
            }
        }
        for (BinderInterfaceContainer.BinderInterface<ICarConnectionListener> client :
                connectionListeners) {
            try {
                client.binderInterface.onConnected();
            } catch (RemoteException e) {
                //ignore
            }
        }
    }

    @Override
    public IBinder getCarService(String serviceName) {
        switch (serviceName) {
            case Car.AUDIO_SERVICE:
                return mCarAudioService;
            case Car.SENSOR_SERVICE:
                return mCarSensorService;
            case Car.INFO_SERVICE:
                return mCarInfoService;
            case Car.APP_CONTEXT_SERVICE:
                return mAppContextService;
            case Car.PACKAGE_SERVICE:
                return mCarPackageManagerService;
            case Car.HVAC_SERVICE:
                assertHvacPermission(mContext);
                return mCarHvacService;
            case Car.RADIO_SERVICE:
                assertRadioPermission(mContext);
                return mCarRadioService;
            case Car.CAR_NAVIGATION_SERVICE:
                return mCarNavigationStatusService;
            case Car.TEST_SERVICE: {
                assertVehicleHalMockPermission(mContext);
                synchronized (this) {
                    if (mCarTestService == null) {
                        mCarTestService = new CarTestService(mContext, this);
                    }
                    return mCarTestService;
                }
            }
            default:
                Log.w(CarLog.TAG_SERVICE, "getCarService for unknown service:" + serviceName);
                return null;
        }
    }

    @Override
    public boolean isConnectedToCar() {
        return true; // always connected in embedded
    }

    @Override
    public void registerCarConnectionListener(ICarConnectionListener listener) {
        mCarConnectionListeners.addBinder(listener);
        try {
            listener.onConnected();
        } catch (RemoteException e) {
            //ignore
        }
    }

    @Override
    public void unregisterCarConnectionListener(ICarConnectionListener listener) {
        mCarConnectionListeners.removeBinder(listener);
    }

    /**
     * Whether mocking underlying HAL or not.
     * @return
     */
    public synchronized boolean isInMocking() {
        if (mCarTestService == null) {
            return false;
        }
        return mCarTestService.isInMocking();
    }

    public static void assertVehicleHalMockPermission(Context context) {
        if (context.checkCallingOrSelfPermission(Car.PERMISSION_MOCK_VEHICLE_HAL)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("requires CAR_MOCK_VEHICLE_HAL permission");
        }
    }

    public static void assertHvacPermission(Context context) {
        if (context.checkCallingOrSelfPermission(Car.PERMISSION_CAR_HVAC)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "requires " + Car.PERMISSION_CAR_HVAC);
        }
    }

    private static void assertRadioPermission(Context context) {
        if (context.checkCallingOrSelfPermission(Car.PERMISSION_CAR_RADIO)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                "requires permission " + Car.PERMISSION_CAR_RADIO);
        }
    }

    void dump(PrintWriter writer) {
        writer.println("*Dump all services*");
        for (CarServiceBase service: mAllServices) {
            service.dump(writer);
        }
        CarTestService testService = mCarTestService;
        if (testService != null) {
            testService.dump(writer);
        }
    }
}
