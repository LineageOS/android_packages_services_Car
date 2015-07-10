package com.android.car.hal;

import android.support.car.CarSensorEvent;

import java.io.PrintWriter;


/**
 * Common base for all SensorHal implementation.
 * It is wholly based on subscription and there is no explicit API for polling, but each sensor
 * should report its initial state immediately after {@link #requestSensorStart(int, int)} call.
 * It is ok to report sensor data {@link SensorListener#onSensorData(CarSensorEvent)} inside
 * the {@link #requestSensorStart(int, int)} call.
 */
public abstract class SensorHalBase {
    /**
     * Listener for monitoring sensor event. Only sensor service will implement this.
     */
    public interface SensorListener {
        /**
         * Sensor Hal is ready and is fully accessible.
         * This will be called after {@link SensorHalBase#init()}.
         */
        void onSensorHalReady(SensorHalBase hal);
        /**
         * Sensor data is available.
         * @param event
         */
        void onSensorData(CarSensorEvent event);
    }

    /**
     * Do necessary initialization. After this, {@link #getSupportedSensors()} should work.
     */
    public abstract void init();

    public abstract void release();

    public abstract void registerSensorListener(SensorListener listener);

    /**
     * Sensor HAL should be ready after init call.
     * @return
     */
    public abstract boolean isReady();

    public abstract int[] getSupportedSensors();

    public abstract boolean requestSensorStart(int sensorType, int rate);

    public abstract void requestSensorStop(int sensorType);

    public abstract void dump(PrintWriter writer);
}
