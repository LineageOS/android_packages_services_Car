package android.car.telemetry;

import android.car.telemetry.ICarTelemetryServiceListener;

/**
 * Internal binder interface for {@code CarTelemetryService}, used by {@code CarTelemetryManager}.
 *
 * @hide
 */
interface ICarTelemetryService {

    /**
     * Registers a listener with CarTelemetryService for the service to send data to cloud app.
     */
    void setListener(in ICarTelemetryServiceListener listener);

    /**
     * Clears the listener registered with CarTelemetryService.
     */
    void clearListener();
}