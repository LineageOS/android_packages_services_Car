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

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.car.Car;
import android.support.car.CarSensorEvent;
import android.support.car.CarSensorManager;
import android.support.car.ICarSensor;
import android.support.car.ICarSensorEventListener;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.car.hal.Hal;
import com.android.car.hal.SensorHal;
import com.android.car.hal.SensorHalBase;
import com.android.car.hal.SensorHalBase.SensorListener;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;


public class CarSensorService extends ICarSensor.Stub
        implements CarServiceBase, SensorHal.SensorListener {

    /**
     * Abstraction for logical sensor which is not physical sensor but presented as sensor to
     * upper layer. Currently {@link CarSensorManager#SENSOR_TYPE_NIGHT} and
     * {@link CarSensorManager#SENSOR_TYPE_DRIVING_STATUS} falls into this category.
     * Implementation can call {@link CarSensorService#onSensorData(CarSensorEvent)} when there
     * is state change for the given sensor after {@link SensorHalBase#init()}
     * is called.
     */
    public static abstract class LogicalSensorHalBase extends SensorHalBase {

        /** Sensor service is ready and all vehicle sensors are available. */
        public abstract void onSensorServiceReady();

        /**
         * Provide default sensor value. This value should be always provided after {@link #init()}
         * call. Default value for safety related sensor should be giving safe state.
         * @param sensorType
         * @return
         */
        public abstract CarSensorEvent getDefaultValue(int sensorType);
    }

    static final int VERSION = 1;

    /** {@link #mSensorLock} is not waited forever for handling disconnection */
    private static final long MAX_SENSOR_LOCK_WAIT_MS = 1000;

    /** lock to access sensor structures */
    private final ReentrantLock mSensorLock = new ReentrantLock();
    /** hold clients callback  */
    @GuardedBy("mSensorLock")
    private final LinkedList<SensorClient> mClients = new LinkedList<SensorClient>();
    /** key: sensor type. */
    @GuardedBy("mSensorLock")
    private final SparseArray<SensorListeners> mSensorListeners = new SparseArray<>();
    /** key: sensor type. */
    @GuardedBy("mSensorLock")
    private final SparseArray<SensorRecord> mSensorRecords = new SparseArray<>();

    private final SensorHal mSensorHal;
    private int[] mCarProvidedSensors;
    private int[] mSupportedSensors;
    private final AtomicBoolean mSensorDiscovered = new AtomicBoolean(false);

    private final Context mContext;

    private final DrivingStatePolicy mDrivingStatePolicy;
    private final DayNightModePolicy mDayNightModePolicy;

    public CarSensorService(Context context) {
        mContext = context;
        // This triggers sensor hal init as well.
        mSensorHal = Hal.getInstance(context.getApplicationContext()).getSensorHal();
        mDrivingStatePolicy = new DrivingStatePolicy(context);
        mDayNightModePolicy = new DayNightModePolicy(context);
    }

    @Override
    public void init() {
        // Watch out the order. registerSensorListener can lead into onSensorHalReady call.
        // So it should be done last.
        mDrivingStatePolicy.init();
        mDayNightModePolicy.init();
        mSensorLock.lock();
        try {
            mSupportedSensors = refreshSupportedSensorsLocked();
            addNewSensorRecordLocked(CarSensorManager.SENSOR_TYPE_DRIVING_STATUS,
                    mDrivingStatePolicy.getDefaultValue(
                            CarSensorManager.SENSOR_TYPE_DRIVING_STATUS));
            addNewSensorRecordLocked(CarSensorManager.SENSOR_TYPE_NIGHT,
                    mDrivingStatePolicy.getDefaultValue(
                            CarSensorManager.SENSOR_TYPE_NIGHT));

        } finally {
            mSensorLock.unlock();
        }
        mDrivingStatePolicy.registerSensorListener(this);
        mDayNightModePolicy.registerSensorListener(this);
        mSensorHal.registerSensorListener(this);
    }

    private void addNewSensorRecordLocked(int type, CarSensorEvent event) {
        SensorRecord record = new SensorRecord();
        record.lastEvent = event;
        mSensorRecords.put(type,record);
    }

    @Override
    public void release() {
        mDrivingStatePolicy.release();
        mDayNightModePolicy.release();
        tryHoldSensorLock();
        try {
            for (int i = mSensorListeners.size() - 1; i >= 0; --i) {
                SensorListeners listener = mSensorListeners.valueAt(i);
                listener.release();
            }
            mSensorListeners.clear();
            mSensorRecords.clear();
            mClients.clear();
        } finally {
            releaseSensorLockSafely();
        }
    }

    private void tryHoldSensorLock() {
        try {
            mSensorLock.tryLock(MAX_SENSOR_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            //ignore
        }
    }

    private void releaseSensorLockSafely() {
        if (mSensorLock.isHeldByCurrentThread()) {
            mSensorLock.unlock();
        }
    }

    @Override
    public void onSensorHalReady(SensorHalBase hal) {
        if (hal == mSensorHal) {
            mCarProvidedSensors = mSensorHal.getSupportedSensors();
            if (Log.isLoggable(CarLog.TAG_SENSOR, Log.VERBOSE)) {
                Log.v(CarLog.TAG_SENSOR, "sensor Hal ready, available sensors:" +
                        Arrays.toString(mCarProvidedSensors));
            }
            mSensorLock.lock();
            try {
                mSupportedSensors = refreshSupportedSensorsLocked();
            } finally {
                mSensorLock.unlock();
            }
            mDrivingStatePolicy.onSensorServiceReady();
            mDayNightModePolicy.onSensorServiceReady();
        }
    }

    private void processSensorDataLocked(CarSensorEvent event) {
        SensorRecord record = mSensorRecords.get(event.sensorType);
        if (record != null) {
            record.lastEvent = event;
        } else {
            if (Log.isLoggable(CarLog.TAG_SENSOR, Log.DEBUG)) {
                Log.d(CarLog.TAG_SENSOR, "sensor data received without matching record");
            }
        }
        if (Log.isLoggable(CarLog.TAG_SENSOR, Log.VERBOSE)) {
            Log.v(CarLog.TAG_SENSOR, "onSensorData type: " + event.sensorType);
        }
        notifySensorEventToClientLocked(event, event.sensorType);
    }

    /**
     * Received sensor data from car.
     *
     * @param event
     */
    @Override
    public void onSensorData(CarSensorEvent event) {
        mSensorLock.lock();
        try {
            processSensorDataLocked(event);
        } finally {
            mSensorLock.unlock();
        }
    }

    @Override
    public int[] getSupportedSensors() {
        return mSupportedSensors;
    }


    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public boolean registerOrUpdateSensorListener(int sensorType, int rate,
            int clientVersion, ICarSensorEventListener listener) {
        boolean shouldStartSensors = false;
        SensorRecord sensorRecord = null;
        SensorClient sensorClient = null;
        Integer oldRate = null;
        SensorListeners sensorListeners = null;
        mSensorLock.lock();
        try {
            sensorRecord = mSensorRecords.get(sensorType);
            if (sensorRecord == null) {
                if (Log.isLoggable(CarLog.TAG_SENSOR, Log.INFO)) {
                    Log.i(CarLog.TAG_SENSOR, "Requested sensor " + sensorType + " not supported");
                }
                return false;
            }
            if (Binder.getCallingUid() != Process.myUid()) {
                switch (getSensorPermission(sensorType)) {
                    case PackageManager.PERMISSION_DENIED:
                        throw new SecurityException("client does not have permission:"
                                + getPermissionName(sensorType)
                                + " pid:" + Binder.getCallingPid()
                                + " uid:" + Binder.getCallingUid());
                    case PackageManager.PERMISSION_GRANTED:
                        break;
                }
            }
            if (Log.isLoggable(CarLog.TAG_SENSOR, Log.DEBUG)) {
                Log.d(CarLog.TAG_SENSOR, "registerOrUpdateSensorListener " + sensorType + " " +
                        listener);
            }
            sensorClient = findSensorClientLocked(listener);
            SensorClientWithRate sensorClientWithRate = null;
            sensorListeners = mSensorListeners.get(sensorType);
            if (sensorClient == null) {
                sensorClient = new SensorClient(listener);
                try {
                    listener.asBinder().linkToDeath(sensorClient, 0);
                } catch (RemoteException e) {
                    if (Log.isLoggable(CarLog.TAG_SENSOR, Log.INFO)) {
                        Log.i(CarLog.TAG_SENSOR, "Adding listener failed.");
                    }
                    return false;
                }
                mClients.add(sensorClient);
            }
            // If we have a cached event for this sensor, send the event.
            SensorRecord record = mSensorRecords.get(sensorType);
            if (record != null && record.lastEvent != null) {
                sensorClient.onSensorUpdate(record.lastEvent);
            }
            if (sensorListeners == null) {
                sensorListeners = new SensorListeners(rate);
                mSensorListeners.put(sensorType, sensorListeners);
                shouldStartSensors = isSensorRealLocked(sensorType);
            } else {
                oldRate = Integer.valueOf(sensorListeners.getRate());
                sensorClientWithRate = sensorListeners.findSensorClientWithRate(sensorClient);
            }
            if (sensorClientWithRate == null) {
                sensorClientWithRate = new SensorClientWithRate(sensorClient, rate);
                sensorListeners.addSensorClientWithRate(sensorClientWithRate);
            } else {
                sensorClientWithRate.setRate(rate);
            }
            if (sensorListeners.getRate() > rate) {
                sensorListeners.setRate(rate);
                shouldStartSensors = isSensorRealLocked(sensorType);
            }
            sensorClient.addSensor(sensorType);
        } finally {
            mSensorLock.unlock();
        }
        // start sensor outside lock as it can take time.
        if (shouldStartSensors) {
            if (!startSensor(sensorRecord, sensorType, rate)) {
                // failed. so remove from active sensor list.
                mSensorLock.lock();
                try {
                    sensorClient.removeSensor(sensorType);
                    if (oldRate != null) {
                        sensorListeners.setRate(oldRate);
                    } else {
                        mSensorListeners.remove(sensorType);
                    }
                } finally {
                    mSensorLock.unlock();
                }
                return false;
            }
        }
        return true;
    }

    private int getSensorPermission(int sensorType) {
        String permission = getPermissionName(sensorType);
        int result = PackageManager.PERMISSION_GRANTED;
        if (permission != null) {
            /*TODO
            result = CarServiceUtils.checkCallingPermission(
                    mContext, permission);*/
        }
        // If no permission is required, return granted.
        return result;
    }

    private String getPermissionName(int sensorType) {
        String permission = null;
        switch (sensorType) {
            case CarSensorManager.SENSOR_TYPE_LOCATION:
                permission = Manifest.permission.ACCESS_FINE_LOCATION;
                break;
            case CarSensorManager.SENSOR_TYPE_CAR_SPEED:
                permission = Car.PERMISSION_SPEED;
                break;
            case CarSensorManager.SENSOR_TYPE_ODOMETER:
                permission = Car.PERMISSION_MILEAGE;
                break;
            case CarSensorManager.SENSOR_TYPE_FUEL_LEVEL:
                permission = Car.PERMISSION_FUEL;
                break;
            default:
                break;
        }
        return permission;
    }

    private boolean startSensor(SensorRecord record, int sensorType, int rate) {
        //TODO choose proper sensor rate per each sensor.
        //Some sensors which report only when there is change should be always set with maximum
        //rate. For now, set every sensor to the maximum.
        if (Log.isLoggable(CarLog.TAG_SENSOR, Log.VERBOSE)) {
            Log.v(CarLog.TAG_SENSOR, "startSensor " + sensorType + " with rate " + rate);
        }
        SensorHalBase sensorHal = getSensorHal(sensorType);
        if (sensorHal != null) {
            if (!sensorHal.isReady()) {
                Log.w(CarLog.TAG_SENSOR, "Sensor channel not available.");
                return false;
            }
            if (record.enabled) {
                return true;
            }
            if (sensorHal.requestSensorStart(sensorType, 0)) {
                record.enabled = true;
                return true;
            }
        }
        Log.w(CarLog.TAG_SENSOR, "requestSensorStart failed");
        return false;
    }

    @Override
    public void unregisterSensorListener(int sensorType, ICarSensorEventListener listener) {
        boolean shouldStopSensor = false;
        boolean shouldRestartSensor = false;
        SensorRecord record = null;
        int newRate = 0;
        mSensorLock.lock();
        try {
            record = mSensorRecords.get(sensorType);
            if (record == null) {
                // unregister not supported sensor. ignore.
                if (Log.isLoggable(CarLog.TAG_SENSOR, Log.DEBUG)) {
                    Log.d(CarLog.TAG_SENSOR, "unregister for unsupported sensor");
                }
                return;
            }
            SensorClient sensorClient = findSensorClientLocked(listener);
            if (sensorClient == null) {
                // never registered or already unregistered.
                if (Log.isLoggable(CarLog.TAG_SENSOR, Log.DEBUG)) {
                    Log.d(CarLog.TAG_SENSOR, "unregister for not existing client");
                }
                return;
            }
            sensorClient.removeSensor(sensorType);
            if (sensorClient.getNumberOfActiveSensor() == 0) {
                sensorClient.release();
                mClients.remove(sensorClient);
            }
            SensorListeners sensorListeners = mSensorListeners.get(sensorType);
            if (sensorListeners == null) {
                // sensor not active
                if (Log.isLoggable(CarLog.TAG_SENSOR, Log.DEBUG)) {
                    Log.d(CarLog.TAG_SENSOR, "unregister for non-active sensor");
                }
                return;
            }
            SensorClientWithRate clientWithRate =
                    sensorListeners.findSensorClientWithRate(sensorClient);
            if (clientWithRate == null) {
                if (Log.isLoggable(CarLog.TAG_SENSOR, Log.DEBUG)) {
                    Log.d(CarLog.TAG_SENSOR, "unregister for not registered sensor");
                }
                return;
            }
            sensorListeners.removeSensorClientWithRate(clientWithRate);
            if (sensorListeners.getNumberOfClients() == 0) {
                shouldStopSensor = isSensorRealLocked(sensorType);
                mSensorListeners.remove(sensorType);
            } else if (sensorListeners.updateRate()) { // rate changed
                newRate = sensorListeners.getRate();
                shouldRestartSensor = isSensorRealLocked(sensorType);
            }
            if (Log.isLoggable(CarLog.TAG_SENSOR, Log.DEBUG)) {
                Log.d(CarLog.TAG_SENSOR, "unregister succeeded");
            }
        } finally {
            mSensorLock.unlock();
        }
        if (shouldStopSensor) {
            stopSensor(record, sensorType);
        } else if (shouldRestartSensor) {
            startSensor(record, sensorType, newRate);
        }
    }

    private void stopSensor(SensorRecord record, int sensorType) {
        if (Log.isLoggable(CarLog.TAG_SENSOR, Log.DEBUG)) {
            Log.d(CarLog.TAG_SENSOR, "stopSensor " + sensorType);
        }
        SensorHalBase sensorHal = getSensorHal(sensorType);
        if (sensorHal == null || !sensorHal.isReady()) {
            Log.w(CarLog.TAG_SENSOR, "Sensor channel not available.");
            return;
        }
        if (!record.enabled) {
            return;
        }
        record.enabled = false;
        // make lastEvent invalid as old data can be sent to client when subscription is restarted
        // later.
        record.lastEvent = null;
        if (Log.isLoggable(CarLog.TAG_SENSOR, Log.DEBUG)) {
            Log.d(CarLog.TAG_SENSOR, "stopSensor requestStop " + sensorType);
        }
        sensorHal.requestSensorStop(sensorType);
    }

    private SensorHalBase getSensorHal(int sensorType) {
        switch (sensorType) {
            case CarSensorManager.SENSOR_TYPE_DRIVING_STATUS:
                return mDrivingStatePolicy;
            case CarSensorManager.SENSOR_TYPE_NIGHT:
                return mDayNightModePolicy;
            default:
                return mSensorHal;
        }
    }

    @Override
    public CarSensorEvent getLatestSensorEvent(int sensorType) {
        SensorRecord record = null;
        mSensorLock.lock();
        try {
            record = mSensorRecords.get(sensorType);
        } finally {
            mSensorLock.unlock();
        }
        if (record != null) {
            return record.lastEvent;
        }
        return null;
    }

    private int[] refreshSupportedSensorsLocked() {
        int numCarSensors = (mCarProvidedSensors == null) ? 0 : mCarProvidedSensors.length;
        // Two logical sensors are always added.
        int[] supportedSensors = new int[numCarSensors + 2];
        int index = 0;
        supportedSensors[index] = CarSensorManager.SENSOR_TYPE_DRIVING_STATUS;
        index++;
        supportedSensors[index] = CarSensorManager.SENSOR_TYPE_NIGHT;
        index++;

        for (int i = 0; i < numCarSensors; i++) {
            int sensor = mCarProvidedSensors[i];
            if (mSensorRecords.get(sensor) == null) {
                SensorRecord record = new SensorRecord();
                mSensorRecords.put(sensor, record);
            }
            supportedSensors[index] = sensor;
            index++;
        }

        return supportedSensors;
    }

    private boolean isSensorRealLocked(int sensorType) {
        if (mCarProvidedSensors != null) {
            for (int sensor : mCarProvidedSensors) {
                if (sensor == sensorType ) {
                    return true;
                }
            }
        }
        return false;
    }

    private void notifySensorEventToClientLocked(CarSensorEvent event, int sensor) {
        SensorListeners listeners = mSensorListeners.get(sensor);
        if (listeners != null) {
            listeners.onSensorUpdate(event);
        } else {
            Log.w(CarLog.TAG_SENSOR, "sensor event while no listener, sensor:" + sensor);
        }
    }

    /**
     * Find SensorClient from client list and return it.
     * This should be called with mClients locked.
     * @param listener
     * @return null if not found.
     */
    private SensorClient findSensorClientLocked(ICarSensorEventListener listener) {
        IBinder binder = listener.asBinder();
        for (SensorClient sensorClient : mClients) {
            if (sensorClient.isHoldingListernerBinder(binder)) {
                return sensorClient;
            }
        }
        return null;
    }

    private void removeClient(SensorClient sensorClient) {
        mSensorLock.lock();
        try {
            for (int sensor: sensorClient.getSensorArray()) {
                unregisterSensorListener(sensor,
                        sensorClient.getICarSensorEventListener());
            }
            mClients.remove(sensorClient);
        } finally {
            mSensorLock.unlock();
        }
    }

    /** internal instance for pending client request */
    private class SensorClient implements IBinder.DeathRecipient {
        /** callback for sensor events */
        private final ICarSensorEventListener mListener;
        private final SparseBooleanArray mActiveSensors = new SparseBooleanArray();

        /** when false, it is already released */
        private volatile boolean mActive = true;

        SensorClient(ICarSensorEventListener listener) {
            this.mListener = listener;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof SensorClient &&
                    mListener.asBinder() == ((SensorClient) o).mListener.asBinder()) {
                return true;
            }
            return false;
        }

        boolean isHoldingListernerBinder(IBinder listenerBinder) {
            return mListener.asBinder() == listenerBinder;
        }

        void addSensor(int sensor) {
            mActiveSensors.put(sensor, true);
        }

        void removeSensor(int sensor) {
            mActiveSensors.delete(sensor);
        }

        int getNumberOfActiveSensor() {
            return mActiveSensors.size();
        }

        int[] getSensorArray() {
            int[] sensors = new int[mActiveSensors.size()];
            for (int i = sensors.length - 1; i >= 0; --i) {
                sensors[i] = mActiveSensors.keyAt(i);
            }
            return sensors;
        }

        ICarSensorEventListener getICarSensorEventListener() {
            return mListener;
        }

        /**
         * Client dead. should remove all sensor requests from client
         */
        @Override
        public void binderDied() {
            mListener.asBinder().unlinkToDeath(this, 0);
            removeClient(this);
        }

        void onSensorUpdate(CarSensorEvent event) {
            try {
                if (mActive) {
                    mListener.onSensorChanged(event);
                } else {
                    if (Log.isLoggable(CarLog.TAG_SENSOR, Log.DEBUG)) {
                        Log.d(CarLog.TAG_SENSOR, "sensor update while client is already released");
                    }
                }
            } catch (RemoteException e) {
                //ignore. crash will be handled by death handler
            }
        }

        void release() {
            if (mActive) {
                mListener.asBinder().unlinkToDeath(this, 0);
                mActiveSensors.clear();
                mActive = false;
            }
        }
    }

    private class SensorClientWithRate {
        private final SensorClient mSensorClient;
        /** rate requested from client */
        private int mRate;

        SensorClientWithRate(SensorClient client, int rate) {
            mSensorClient = client;
            mRate = rate;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof SensorClientWithRate &&
                    mSensorClient == ((SensorClientWithRate) o).mSensorClient) {
                return true;
            }
            return false;
        }

        int getRate() {
            return mRate;
        }

        void setRate(int rate) {
            mRate = rate;
        }

        SensorClient getSensorClient() {
            return mSensorClient;
        }
    }

    private static class SensorRecord {
        /** Record the lastly received sensor event */
        CarSensorEvent lastEvent = null;
        /** sensor was enabled by at least one client */
        boolean enabled = false;
    }

    private static class SensorListeners {
        private final LinkedList<SensorClientWithRate> mSensorClients =
                new LinkedList<SensorClientWithRate>();
        /** rate for this sensor, sent to car */
        private int mRate;

        SensorListeners(int rate) {
            mRate = rate;
        }

        int getRate() {
            return mRate;
        }

        void setRate(int rate) {
            mRate = rate;
        }

        /** update rate from existing clients and return true if rate is changed. */
        boolean updateRate() {
            int fastestRate = CarSensorManager.SENSOR_RATE_NORMAL;
            for (SensorClientWithRate clientWithRate: mSensorClients) {
                int clientRate = clientWithRate.getRate();
                if (clientRate < fastestRate) {
                    fastestRate = clientRate;
                }
            }
            if (mRate != fastestRate) {
                mRate = fastestRate;
                return true;
            }
            return false;
        }

        void addSensorClientWithRate(SensorClientWithRate clientWithRate) {
            mSensorClients.add(clientWithRate);
        }

        void removeSensorClientWithRate(SensorClientWithRate clientWithRate) {
            mSensorClients.remove(clientWithRate);
        }

        int getNumberOfClients() {
            return mSensorClients.size();
        }

        SensorClientWithRate findSensorClientWithRate(SensorClient sensorClient) {
            for (SensorClientWithRate clientWithRates: mSensorClients) {
                if (clientWithRates.getSensorClient() == sensorClient) {
                    return clientWithRates;
                }
            }
            return null;
        }

        void onSensorUpdate(CarSensorEvent event) {
            if (Log.isLoggable(CarLog.TAG_SENSOR, Log.VERBOSE)) {
                Log.v(CarLog.TAG_SENSOR, "onSensorUpdate to clients: " + mSensorClients.size());
            }
            for (SensorClientWithRate clientWithRate: mSensorClients) {
                clientWithRate.getSensorClient().onSensorUpdate(event);
            }
        }

        void release() {
            for (SensorClientWithRate clientWithRate: mSensorClients) {
                clientWithRate.mSensorClient.release();
            }
            mSensorClients.clear();
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("supported sensors:" + Arrays.toString(mSupportedSensors));
        writer.println("**last events for sensors**");
        if (mSensorRecords != null) {
            try {
                int sensorRecordSize = mSensorRecords.size();
                for (int i = 0; i < sensorRecordSize; i++) {
                    int sensor = mSensorRecords.keyAt(i);
                    SensorRecord record = mSensorRecords.get(sensor);
                    if (record != null && record.lastEvent != null) {
                        writer.println("sensor: " + sensor
                                + " active: " + record.enabled);
                        // only print sensor data which is not related with location
                        if (sensor != CarSensorManager.SENSOR_TYPE_LOCATION &&
                                sensor != CarSensorManager.SENSOR_TYPE_DEAD_RECKONING &&
                                sensor != CarSensorManager.SENSOR_TYPE_GPS_SATELLITE) {
                            writer.println(" " + record.lastEvent.toString());
                        }
                    }
                    SensorListeners listeners = mSensorListeners.get(sensor);
                    if (listeners != null) {
                        writer.println(" rate: " + listeners.getRate());
                    }
                }
            } catch (ConcurrentModificationException e) {
                writer.println("concurrent modification happened");
            }
        } else {
            writer.println("null records");
        }
        writer.println("**clients**");
        try {
            for (SensorClient client: mClients) {
                if (client != null) {
                    try {
                        writer.println("binder:" + client.mListener
                                + " active sensors:" + Arrays.toString(client.getSensorArray()));
                    } catch (ConcurrentModificationException e) {
                        writer.println("concurrent modification happened");
                    }
                } else {
                    writer.println("null client");
                }
            }
        } catch  (ConcurrentModificationException e) {
            writer.println("concurrent modification happened");
        }
        writer.println("**sensor listeners**");
        try {
            int sensorListenerSize = mSensorListeners.size();
            for (int i = 0; i < sensorListenerSize; i++) {
                int sensor = mSensorListeners.keyAt(i);
                SensorListeners sensorListeners = mSensorListeners.get(sensor);
                if (sensorListeners != null) {
                    writer.println(" Sensor:" + sensor
                            + " num client:" + sensorListeners.getNumberOfClients()
                            + " rate:" + sensorListeners.getRate());
                }
            }
        }  catch  (ConcurrentModificationException e) {
            writer.println("concurrent modification happened");
        }
        writer.println("**driving policy**");
        mDrivingStatePolicy.dump(writer);
        writer.println("**day/night policy**");
        mDayNightModePolicy.dump(writer);
    }
}
