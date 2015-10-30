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

package com.android.car.hal;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.vehiclenetwork.VehicleNetwork;
import com.android.car.vehiclenetwork.VehicleNetwork.VehicleNetworkListener;
import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropAccess;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropChangeMode;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfig;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfigs;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValues;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * Abstraction for vehicle HAL. This class handles interface with native HAL and do basic parsing
 * of received data (type check). Then each event is sent to corresponding {@link HalServiceBase}
 * implementation. It is responsibility of {@link HalServiceBase} to convert data to corresponding
 * Car*Service for Car*Manager API.
 */
public class VehicleHal implements VehicleNetworkListener {

    private static final boolean DBG = true;

    /**
     * Interface for mocking Hal which is used for testing, but should be kept even in release.
     */
    public interface HalMock {
        //TODO
    };

    static {
        createInstance();
    }

    private static VehicleHal sInstance;

    private final HandlerThread mHandlerThread;
    private final DefaultHandler mDefaultHandler;

    public static synchronized VehicleHal getInstance() {
        if (sInstance == null) {
            createInstance();
        }
        return sInstance;
    }

    private static synchronized void createInstance() {
        sInstance = new VehicleHal();
        // init is handled in a separate thread to prevent blocking the calling thread for too
        // long.
        sInstance.startInit();
    }

    public static synchronized void releaseInstance() {
        if (sInstance != null) {
            sInstance.release();
            sInstance = null;
        }
    }

    private final VehicleNetwork mVehicleNetwork;
    private final SensorHalService mSensorHal;
    private final InfoHalService mInfoHal;
    private final AudioHalService mAudioHal;
    private final RadioHalService mRadioHal;
    private final PowerHalService mPowerHal;

    /** stores handler for each HAL property. Property events are sent to handler. */
    private final SparseArray<HalServiceBase> mPropertyHandlers = new SparseArray<HalServiceBase>();
    /** This is for iterating all HalServices with fixed order. */
    private final HalServiceBase[] mAllServices;
    private final ArraySet<Integer> mSubscribedProperties = new ArraySet<Integer>();

    private VehicleHal() {
        mHandlerThread = new HandlerThread("HAL");
        mHandlerThread.start();
        mDefaultHandler = new DefaultHandler(mHandlerThread.getLooper());
        // passing this should be safe as long as it is just kept and not used in constructor
        mPowerHal = new PowerHalService(this);
        mSensorHal = new SensorHalService(this);
        mInfoHal = new InfoHalService(this);
        mAudioHal = new AudioHalService(this);
        mRadioHal = new RadioHalService(this);
        mAllServices = new HalServiceBase[] {
                mPowerHal,
                mAudioHal,
                mInfoHal,
                mSensorHal,
                mRadioHal };
        mVehicleNetwork = VehicleNetwork.createVehicleNetwork(this, mHandlerThread.getLooper());
    }

    private void startInit() {
        mDefaultHandler.requestInit();
    }

    private void doInit() {
        VehiclePropConfigs properties = mVehicleNetwork.listProperties();
        // needs copy as getConfigsList gives unmodifiable one.
        List<VehiclePropConfig> propertiesList =
                new LinkedList<VehiclePropConfig>(properties.getConfigsList());
        for (HalServiceBase service: mAllServices) {
            List<VehiclePropConfig> taken = service.takeSupportedProperties(propertiesList);
            if (taken == null) {
                continue;
            }
            if (DBG) {
                Log.i(CarLog.TAG_HAL, "HalService " + service + " take properties " + taken.size());
            }
            for (VehiclePropConfig p: taken) {
                mPropertyHandlers.append(p.getProp(), service);
            }
            propertiesList.removeAll(taken);
            service.init();
        }
    }

    private void release() {
        // release in reverse order from init
        for (int i = mAllServices.length - 1; i >= 0; i--) {
            mAllServices[i].release();
        }
        for (int p : mSubscribedProperties) {
            mVehicleNetwork.unsubscribe(p);
        }
        mSubscribedProperties.clear();
        // keep the looper thread as should be kept for the whole life cycle.
    }

    public void startMocking() {
        reinitHals();
    }

    public void stopMocking() {
        reinitHals();
    }

    private void reinitHals() {
        release();
        doInit();
    }

    public SensorHalService getSensorHal() {
        return mSensorHal;
    }

    public InfoHalService getInfoHal() {
        return mInfoHal;
    }

    public AudioHalService getAudioHal() {
        return mAudioHal;
    }

    public RadioHalService getRadioHal() {
        return mRadioHal;
    }

    public PowerHalService getPowerHal() {
        return mPowerHal;
    }

    private void assertServiceOwner(HalServiceBase service, int property) {
        if (service != mPropertyHandlers.get(property)) {
            throw new IllegalArgumentException("not owned");
        }
    }

    /**
     * Subscribe given property. Only Hal service owning the property can subscribe it.
     * @param service
     * @param property
     * @param samplingRateHz
     */
    public void subscribeProperty(HalServiceBase service, int property,
            float samplingRateHz) throws IllegalArgumentException {
        assertServiceOwner(service, property);
        mSubscribedProperties.add(property);
        mVehicleNetwork.subscribe(property, samplingRateHz);
    }

    public void unsubscribeProperty(HalServiceBase service, int property) {
        assertServiceOwner(service, property);
        mSubscribedProperties.remove(property);
        mVehicleNetwork.unsubscribe(property);
    }

    public VehicleNetwork getVehicleNetwork() {
        return mVehicleNetwork;
    }

    public static boolean isPropertySubscribable(VehiclePropConfig config) {
        if (config.hasAccess() & VehiclePropAccess.VEHICLE_PROP_ACCESS_READ == 0 ||
                config.getChangeMode() ==
                VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_STATIC) {
            return false;
        }
        return true;
    }

    public static void dumpProperties(PrintWriter writer, Collection<VehiclePropConfig> configs) {
        for (VehiclePropConfig config : configs) {
            writer.println("property " +
                    VehicleNetworkConsts.getVehiclePropertyName(config.getProp()));
        }
    }

    private final ArraySet<HalServiceBase> mServicesToDispatch = new ArraySet<HalServiceBase>();
    @Override
    public void onVehicleNetworkEvents(VehiclePropValues values) {
        for (VehiclePropValue v : values.getValuesList()) {
            HalServiceBase service = mPropertyHandlers.get(v.getProp());
            service.getDispatchList().add(v);
            mServicesToDispatch.add(service);
        }
        for (HalServiceBase s : mServicesToDispatch) {
            s.handleHalEvents(s.getDispatchList());
            s.getDispatchList().clear();
        }
        mServicesToDispatch.clear();
    }

    private class DefaultHandler extends Handler {
        private static final int MSG_INIT = 0;

        private DefaultHandler(Looper looper) {
            super(looper);
        }

        private void requestInit() {
            Message msg = obtainMessage(MSG_INIT);
            sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_INIT:
                    doInit();
                    break;
                default:
                    Log.w(CarLog.TAG_HAL, "unown message:" + msg.what, new RuntimeException());
                    break;
            }
        }
    }

    public void dump(PrintWriter writer) {
        writer.println("**dump HAL services**");
        for (HalServiceBase service: mAllServices) {
            service.dump(writer);
        }
    }
}
