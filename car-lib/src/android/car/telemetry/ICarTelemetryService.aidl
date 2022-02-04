package android.car.telemetry;

import android.car.telemetry.ICarTelemetryServiceListener;
import android.os.ResultReceiver;

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
     * Adds telemetry MetricsConfigs to CarTelemetryService. Status code is sent to
     * CarTelemetryManager via ResultReceiver.
     */
    void addMetricsConfig(in String metricsConfigName, in byte[] metricsConfig,
            in ResultReceiver callback);

    /**
     * Removes a MetricsConfig based on the name. This will also remove outputs produced by the
     * MetricsConfig.
     */
    void removeMetricsConfig(in String metricsConfigName);

    /**
     * Removes all MetricsConfigs. This will also remove all MetricsConfig outputs.
     */
    void removeAllMetricsConfigs();

    /**
     * Sends script results or errors associated with the given name using the
     * {@code ICarTelemetryServiceListener}.
     */
    void sendFinishedReports(in String metricsConfigName);

    /**
     * Sends all script results or errors using the {@code ICarTelemetryServiceListener}.
     */
    void sendAllFinishedReports();
}