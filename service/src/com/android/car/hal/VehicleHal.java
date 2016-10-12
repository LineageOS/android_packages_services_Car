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

import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.vehiclenetwork.VehicleNetwork;
import com.android.car.vehiclenetwork.VehicleNetwork.VehicleNetworkListener;
import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropAccess;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropChangeMode;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfig;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfigs;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValues;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

/**
 * Abstraction for vehicle HAL. This class handles interface with native HAL and do basic parsing
 * of received data (type check). Then each event is sent to corresponding {@link HalServiceBase}
 * implementation. It is responsibility of {@link HalServiceBase} to convert data to corresponding
 * Car*Service for Car*Manager API.
 */
public class VehicleHal implements VehicleNetworkListener {

    private static final boolean DBG = false;

    static {
        createInstance();
    }

    private static VehicleHal sInstance;

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
        sInstance.init();
    }

    public static synchronized void releaseInstance() {
        if (sInstance != null) {
            sInstance.release();
            sInstance = null;
        }
    }

    private final HandlerThread mHandlerThread;
    private final VehicleNetwork mVehicleNetwork;
    private final SensorHalService mSensorHal;
    private final InfoHalService mInfoHal;
    private final AudioHalService mAudioHal;
    private final CabinHalService mCabinHal;
    private final RadioHalService mRadioHal;
    private final PowerHalService mPowerHal;
    private final HvacHalService mHvacHal;
    private final InputHalService mInputHal;
    private final VendorExtensionHalService mVendorExtensionHal;

    /** stores handler for each HAL property. Property events are sent to handler. */
    private final SparseArray<HalServiceBase> mPropertyHandlers = new SparseArray<HalServiceBase>();
    /** This is for iterating all HalServices with fixed order. */
    private final HalServiceBase[] mAllServices;
    private final ArraySet<Integer> mSubscribedProperties = new ArraySet<Integer>();
    private final HashMap<Integer, VehiclePropConfig> mAllProperties = new HashMap<>();
    private final HashMap<Integer, VehiclePropertyEventInfo> mEventLog = new HashMap<>();

    private VehicleHal() {
        mHandlerThread = new HandlerThread("VEHICLE-HAL");
        mHandlerThread.start();
        // passing this should be safe as long as it is just kept and not used in constructor
        mPowerHal = new PowerHalService(this);
        mSensorHal = new SensorHalService(this);
        mInfoHal = new InfoHalService(this);
        mAudioHal = new AudioHalService(this);
        mCabinHal = new CabinHalService(this);
        mRadioHal = new RadioHalService(this);
        mHvacHal = new HvacHalService(this);
        mInputHal = new InputHalService();
        mVendorExtensionHal = new VendorExtensionHalService(this);
        mAllServices = new HalServiceBase[] {
                mPowerHal,
                mAudioHal,
                mCabinHal,
                mHvacHal,
                mInfoHal,
                mSensorHal,
                mRadioHal,
                mInputHal,
                mVendorExtensionHal
                };
        mVehicleNetwork = VehicleNetwork.createVehicleNetwork(this, mHandlerThread.getLooper());
    }

    /** Dummy version only for testing */
    @VisibleForTesting
    public VehicleHal(PowerHalService powerHal, SensorHalService sensorHal, InfoHalService infoHal,
            AudioHalService audioHal, CabinHalService cabinHal, RadioHalService radioHal,
            HvacHalService hvacHal, VehicleNetwork vehicleNetwork) {
        mHandlerThread = null;
        mPowerHal = powerHal;
        mSensorHal = sensorHal;
        mInfoHal = infoHal;
        mAudioHal = audioHal;
        mCabinHal = cabinHal;
        mRadioHal = radioHal;
        mHvacHal = hvacHal;
        mInputHal = null;
        mVendorExtensionHal = null;
        mAllServices = null;
        mVehicleNetwork = vehicleNetwork;
    }

    public void init() {
        VehiclePropConfigs properties = mVehicleNetwork.listProperties();
        // needs copy as getConfigsList gives unmodifiable one.
        List<VehiclePropConfig> propertiesList =
                new LinkedList<VehiclePropConfig>(properties.getConfigsList());

        synchronized (this) {
            // Create map of all properties
            for (VehiclePropConfig p : propertiesList) {
                mAllProperties.put(p.getProp(), p);
            }
        }

        for (HalServiceBase service: mAllServices) {
            List<VehiclePropConfig> taken = service.takeSupportedProperties(propertiesList);
            if (taken == null) {
                continue;
            }
            if (DBG) {
                Log.i(CarLog.TAG_HAL, "HalService " + service + " take properties " + taken.size());
            }
            synchronized (this) {
                for (VehiclePropConfig p: taken) {
                    mPropertyHandlers.append(p.getProp(), service);
                }
            }
            propertiesList.removeAll(taken);
            service.init();
        }
    }

    public void release() {
        // release in reverse order from init
        for (int i = mAllServices.length - 1; i >= 0; i--) {
            mAllServices[i].release();
        }
        synchronized (this) {
            for (int p : mSubscribedProperties) {
                mVehicleNetwork.unsubscribe(p);
            }
            mSubscribedProperties.clear();
            mAllProperties.clear();
        }
        // keep the looper thread as should be kept for the whole life cycle.
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

    public CabinHalService getCabinHal() {
        return mCabinHal;
    }

    public RadioHalService getRadioHal() {
        return mRadioHal;
    }

    public PowerHalService getPowerHal() {
        return mPowerHal;
    }

    public HvacHalService getHvacHal() {
        return mHvacHal;
    }

    public InputHalService getInputHal() {
        return mInputHal;
    }

    public VendorExtensionHalService getVendorExtensionHal() {
        return mVendorExtensionHal;
    }

    private void assertServiceOwnerLocked(HalServiceBase service, int property) {
        if (service != mPropertyHandlers.get(property)) {
            throw new IllegalArgumentException("Property 0x" + Integer.toHexString(property)
                    + " is not owned by service: " + service);
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
        VehiclePropConfig config;
        synchronized (this) {
            config = mAllProperties.get(property);
        }

        if (config == null) {
            throw new IllegalArgumentException("subscribe error: config is null for property " +
                    property);
        } else if (isPropertySubscribable(config)) {
            synchronized (this) {
                assertServiceOwnerLocked(service, property);
                mSubscribedProperties.add(property);
            }
            mVehicleNetwork.subscribe(property, samplingRateHz);
        } else {
            Log.e(CarLog.TAG_HAL, "Cannot subscribe to property: " + property);
        }
    }

    public void unsubscribeProperty(HalServiceBase service, int property) {
        VehiclePropConfig config;
        synchronized (this) {
            config = mAllProperties.get(property);
        }

        if (config == null) {
            Log.e(CarLog.TAG_HAL, "unsubscribeProperty: property " + property + " does not exist");
        } else if (isPropertySubscribable(config)) {
            synchronized (this) {
                assertServiceOwnerLocked(service, property);
                mSubscribedProperties.remove(property);
            }
            mVehicleNetwork.unsubscribe(property);
        } else {
            Log.e(CarLog.TAG_HAL, "Cannot unsubscribe property: " + property);
        }
    }

    public VehicleNetwork getVehicleNetwork() {
        return mVehicleNetwork;
    }

    public static boolean isPropertySubscribable(VehiclePropConfig config) {
        if ((config.getAccess() & VehiclePropAccess.VEHICLE_PROP_ACCESS_READ) == 0 ||
                (config.getChangeMode() ==
                VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_STATIC)) {
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
        synchronized (this) {
            for (VehiclePropValue v : values.getValuesList()) {
                HalServiceBase service = mPropertyHandlers.get(v.getProp());
                service.getDispatchList().add(v);
                mServicesToDispatch.add(service);
                VehiclePropertyEventInfo info = mEventLog.get(v.getProp());
                if (info == null) {
                    info = new VehiclePropertyEventInfo(v);
                    mEventLog.put(v.getProp(), info);
                } else {
                    info.addNewEvent(v);
                }
            }
        }
        for (HalServiceBase s : mServicesToDispatch) {
            s.handleHalEvents(s.getDispatchList());
            s.getDispatchList().clear();
        }
        mServicesToDispatch.clear();
    }

    @Override
    public void onPropertySet(VehiclePropValue value) {
        // No need to handle on-property-set events in HAL service yet.
    }

    @Override
    public void onHalError(int errorCode, int property, int operation) {
        Log.e(CarLog.TAG_HAL, "onHalError, errorCode:" + errorCode +
                " property:0x" + Integer.toHexString(property) +
                " operation:" + operation);
        // TODO propagate per property error to HAL services and handle global error, bug:32068464
    }

    @Override
    public void onHalRestart(boolean inMocking) {
        Log.e(CarLog.TAG_HAL, "onHalRestart, inMocking:" + inMocking);
        // TODO restart things as other components started mocking. For now, ignore., bug:32068464
    }

    public void dump(PrintWriter writer) {
        writer.println("**dump HAL services**");
        for (HalServiceBase service: mAllServices) {
            service.dump(writer);
        }

        List<VehiclePropConfig> configList;
        synchronized (this) {
            configList = new ArrayList<>(mAllProperties.values());
        }

        writer.println("**All properties**");
        for (VehiclePropConfig config : configList) {
            StringBuilder builder = new StringBuilder();
            builder.append("Property:0x").append(Integer.toHexString(config.getProp()));
            builder.append(",access:0x" + Integer.toHexString(config.getAccess()));
            builder.append(",changeMode:0x" + Integer.toHexString(config.getChangeMode()));
            builder.append(",valueType:0x" + Integer.toHexString(config.getValueType()));
            builder.append(",permission:0x" + Integer.toHexString(config.getPermissionModel()));
            builder.append(",config:0x" + Integer.toHexString(config.getConfigArray(0)));
            builder.append(",fs min:" + config.getSampleRateMin());
            builder.append(",fs max:").append(config.getSampleRateMax());
            for (int i = 0; i < config.getFloatMaxsCount(); i++) {
                builder.append(",v min:" + config.getFloatMins(i));
                builder.append(",v max:" + config.getFloatMaxs(i));
            }
            for (int i = 0; i < config.getInt32MaxsCount(); i++) {
                builder.append(",v min:" + config.getInt32Mins(i));
                builder.append(",v max:" + config.getInt32Maxs(i));
            }
            for (int i = 0; i < config.getInt64MaxsCount(); i++) {
                builder.append(",v min:" + config.getInt64Mins(i));
                builder.append(",v max:" + config.getInt64Maxs(i));
            }
            writer.println(builder.toString());
        }
        writer.println(String.format("**All Events, now ns:%d**",
                SystemClock.elapsedRealtimeNanos()));
        for (VehiclePropertyEventInfo info : mEventLog.values()) {
            writer.println(String.format("event count:%d, lastEvent:%s",
                    info.eventCount, dumpVehiclePropValue(info.lastEvent)));
        }

        writer.println("**Property handlers**");
        for (int i = 0; i < mPropertyHandlers.size(); i++) {
            int propId = mPropertyHandlers.keyAt(i);
            HalServiceBase service = mPropertyHandlers.valueAt(i);
            writer.println(String.format("Prop: 0x%08X, service: %s", propId, service));
        }
    }

    public static String dumpVehiclePropValue(VehiclePropValue value) {
        StringBuilder sb = new StringBuilder();
        sb.append("Property:0x" + Integer.toHexString(value.getProp()));
        sb.append(",timestamp:" + value.getTimestamp());
        sb.append(",value type:0x" + Integer.toHexString(value.getValueType()));
        sb.append(",zone:0x" + Integer.toHexString(value.getZone()));
        if (value.getInt32ValuesCount() > 0) {
            sb.append(",int32 values:");
            for (int i = 0; i < value.getInt32ValuesCount(); i++) {
                sb.append("," + value.getInt32Values(i));
            }
        }
        if (value.hasInt64Value()) {
            sb.append(",int64 value:" + value.getInt64Value());
        }
        if (value.getFloatValuesCount() > 0) {
            sb.append(",float values:");
            for (int i = 0; i < value.getFloatValuesCount(); i++) {
                sb.append("," + value.getFloatValues(i));
            }
        }
        if (value.hasStringValue()) {
            sb.append(",string value:" + value.getStringValue());
        }
        if (value.hasBytesValue()) {
            sb.append(",bytes value:" + value.getBytesValue());
        }
        return sb.toString();
    }
    private static class VehiclePropertyEventInfo {
        private int eventCount;
        private VehiclePropValue lastEvent;

        private VehiclePropertyEventInfo(VehiclePropValue event) {
            eventCount = 1;
            lastEvent = event;
        }

        private void addNewEvent(VehiclePropValue event) {
            eventCount++;
            lastEvent = event;
        }
    }
}
