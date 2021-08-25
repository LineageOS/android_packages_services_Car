package android.car.telemetry;

import android.car.telemetry.ICarTelemetryServiceListener;
import android.car.telemetry.MetricsConfigKey;

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

    /**
     * Sends telemetry MetricsConfigs to CarTelemetryService.
     */
    void addMetricsConfig(in MetricsConfigKey key, in byte[] metricsConfig);

    /**
     * Removes a MetricsConfig based on the key.
     */
    void removeMetricsConfig(in MetricsConfigKey key);

    /**
     * Removes all MetricsConfigs.
     */
    void removeAllMetricsConfigs();

    /**
     * Sends script results or errors associated with the given key using the
     * {@code ICarTelemetryServiceListener}.
     */
    void sendFinishedReports(in MetricsConfigKey key);

    /**
     * Sends all script results or errors using the {@code ICarTelemetryServiceListener}.
     */
    void sendAllFinishedReports();
}