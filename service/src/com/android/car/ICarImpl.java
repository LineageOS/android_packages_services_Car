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
import android.car.cluster.renderer.IInstrumentClusterNavigation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import com.android.car.cluster.InstrumentClusterService;
import com.android.car.hal.VehicleHal;
import com.android.car.pm.CarPackageManagerService;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;

public class ICarImpl extends ICar.Stub {

    public static final String INTERNAL_INPUT_SERVICE =  "internal_input";
    public static final String INTERNAL_SYSTEM_ACTIVITY_MONITORING_SERVICE =
            "system_activity_monitoring";

    // load jni for all services here
    static {
        System.loadLibrary("jni_car_service");
    }

    @GuardedBy("ICarImpl.class")
    private static ICarImpl sInstance = null;

    private final Context mContext;
    private final VehicleHal mHal;

    private final SystemActivityMonitoringService mSystemActivityMonitoringService;
    private final CarPowerManagementService mCarPowerManagementService;
    private final CarPackageManagerService mCarPackageManagerService;
    private final CarInputService mCarInputService;
    private final CarSensorService mCarSensorService;
    private final CarInfoService mCarInfoService;
    private final CarAudioService mCarAudioService;
    private final CarProjectionService mCarProjectionService;
    private final CarCabinService mCarCabinService;
    private final CarCameraService mCarCameraService;
    private final CarHvacService mCarHvacService;
    private final CarRadioService mCarRadioService;
    private final CarNightService mCarNightService;
    private final AppFocusService mAppFocusService;
    private final GarageModeService mGarageModeService;
    private final InstrumentClusterService mInstrumentClusterService;
    private final SystemStateControllerService mSystemStateControllerService;
    private final CarVendorExtensionService mCarVendorExtensionService;

    /** Test only service. Populate it only when necessary. */
    @GuardedBy("this")
    private CarTestService mCarTestService;
    private final CarServiceBase[] mAllServices;

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
        mSystemActivityMonitoringService = new SystemActivityMonitoringService(serviceContext);
        mCarPowerManagementService = new CarPowerManagementService(serviceContext);
        mCarSensorService = new CarSensorService(serviceContext);
        mCarPackageManagerService = new CarPackageManagerService(serviceContext, mCarSensorService,
                mSystemActivityMonitoringService);
        mCarInputService = new CarInputService(serviceContext);
        mCarProjectionService = new CarProjectionService(serviceContext, mCarInputService);
        mGarageModeService = new GarageModeService(mContext, mCarPowerManagementService);
        mCarInfoService = new CarInfoService(serviceContext);
        mAppFocusService = new AppFocusService(serviceContext, mSystemActivityMonitoringService);
        mCarAudioService = new CarAudioService(serviceContext, mCarInputService);
        mCarCabinService = new CarCabinService(serviceContext);
        mCarHvacService = new CarHvacService(serviceContext);
        mCarRadioService = new CarRadioService(serviceContext);
        mCarCameraService = new CarCameraService(serviceContext);
        mCarNightService = new CarNightService(serviceContext);
        mInstrumentClusterService = new InstrumentClusterService(serviceContext,
                mAppFocusService, mCarInputService);
        mSystemStateControllerService = new SystemStateControllerService(serviceContext,
                mCarPowerManagementService, mCarAudioService, this);
        mCarVendorExtensionService = new CarVendorExtensionService(serviceContext);

        // Be careful with order. Service depending on other service should be inited later.
        mAllServices = new CarServiceBase[] {
                mSystemActivityMonitoringService,
                mCarPowerManagementService,
                mCarSensorService,
                mCarPackageManagerService,
                mCarInputService,
                mGarageModeService,
                mCarInfoService,
                mAppFocusService,
                mCarAudioService,
                mCarCabinService,
                mCarHvacService,
                mCarRadioService,
                mCarCameraService,
                mCarNightService,
                mInstrumentClusterService,
                mCarProjectionService,
                mSystemStateControllerService,
                mCarVendorExtensionService
                };
    }

    private void init() {
        for (CarServiceBase service: mAllServices) {
            service.init();
        }
    }

    private void release() {
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
        VehicleHal.getInstance().release();
        VehicleHal.getInstance().init();
        for (CarServiceBase service: mAllServices) {
            service.init();
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
            case Car.APP_FOCUS_SERVICE:
                return mAppFocusService;
            case Car.PACKAGE_SERVICE:
                return mCarPackageManagerService;
            case Car.CABIN_SERVICE:
                assertCabinPermission(mContext);
                return mCarCabinService;
            case Car.CAMERA_SERVICE:
                assertCameraPermission(mContext);
                return mCarCameraService;
            case Car.HVAC_SERVICE:
                assertHvacPermission(mContext);
                return mCarHvacService;
            case Car.RADIO_SERVICE:
                assertRadioPermission(mContext);
                return mCarRadioService;
            case Car.CAR_NAVIGATION_SERVICE:
                assertNavigationManagerPermission(mContext);
                IInstrumentClusterNavigation navService =
                        mInstrumentClusterService.getNavigationService();
                return navService == null ? null : navService.asBinder();
            case Car.PROJECTION_SERVICE:
                assertProjectionPermission(mContext);
                return mCarProjectionService;
            case Car.VENDOR_EXTENSION_SERVICE:
                assertVendorExtensionPermission(mContext);
                return mCarVendorExtensionService;
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
    public int getCarConnectionType() {
        if (!isInMocking()) {
            return Car.CONNECTION_TYPE_EMBEDDED;
        } else {
            return Car.CONNECTION_TYPE_EMBEDDED_MOCKING;
        }
    }

    public CarServiceBase getCarInternalService(String serviceName) {
        switch (serviceName) {
            case INTERNAL_INPUT_SERVICE:
                return mCarInputService;
            case INTERNAL_SYSTEM_ACTIVITY_MONITORING_SERVICE:
                return mSystemActivityMonitoringService;
            default:
                Log.w(CarLog.TAG_SERVICE, "getCarInternalService for unknown service:" +
                        serviceName);
                return null;
        }
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
        assertPermission(context, Car.PERMISSION_MOCK_VEHICLE_HAL);
    }

    public static void assertCabinPermission(Context context) {
        assertPermission(context, Car.PERMISSION_CAR_CABIN);
    }

    public static void assertCameraPermission(Context context) {
        assertPermission(context, Car.PERMISSION_CAR_CAMERA);
    }

    public static void assertNavigationManagerPermission(Context context) {
        assertPermission(context, Car.PERMISSION_CAR_NAVIGATION_MANAGER);
    }

    public static void assertHvacPermission(Context context) {
        assertPermission(context, Car.PERMISSION_CAR_HVAC);
    }

    private static void assertRadioPermission(Context context) {
        assertPermission(context, Car.PERMISSION_CAR_RADIO);
    }

    public static void assertProjectionPermission(Context context) {
        assertPermission(context, Car.PERMISSION_CAR_PROJECTION);
    }

    public static void assertVendorExtensionPermission(Context context) {
        assertPermission(context, Car.PERMISSION_VENDOR_EXTENSION);
    }

    public static void assertPermission(Context context, String permission) {
        if (context.checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("requires " + permission);
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
