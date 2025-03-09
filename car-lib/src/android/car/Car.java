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

package android.car;

import static android.car.CarLibLog.TAG_CAR;
import static android.car.feature.Flags.FLAG_ANDROID_B_VEHICLE_PROPERTIES;
import static android.car.feature.Flags.FLAG_CLUSTER_HEALTH_MONITORING;
import static android.car.feature.Flags.FLAG_VEHICLE_PROPERTY_25Q2_3P_PERMISSIONS;
import static android.car.feature.Flags.FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Activity;
import android.app.Service;
import android.car.admin.CarDevicePolicyManager;
import android.car.annotation.MandatoryFeature;
import android.car.annotation.OptionalFeature;
import android.car.app.CarActivityManager;
import android.car.app.CarDisplayCompatManager;
import android.car.builtin.os.BuildHelper;
import android.car.builtin.os.ServiceManagerHelper;
import android.car.builtin.util.Slogf;
import android.car.cluster.CarInstrumentClusterManager;
import android.car.cluster.ClusterActivityState;
import android.car.cluster.ClusterHomeManager;
import android.car.content.pm.CarPackageManager;
import android.car.diagnostic.CarDiagnosticManager;
import android.car.drivingstate.CarDrivingStateManager;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.evs.CarEvsManager;
import android.car.feature.Flags;
import android.car.hardware.CarSensorManager;
import android.car.hardware.CarVendorExtensionManager;
import android.car.hardware.cabin.CarCabinManager;
import android.car.hardware.hvac.CarHvacManager;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertySimulationManager;
import android.car.hardware.property.ICarProperty;
import android.car.input.CarInputManager;
import android.car.media.CarAudioManager;
import android.car.media.CarMediaIntents;
import android.car.media.CarMediaManager;
import android.car.navigation.CarNavigationStatusManager;
import android.car.occupantawareness.OccupantAwarenessManager;
import android.car.occupantconnection.CarOccupantConnectionManager;
import android.car.os.CarPerformanceManager;
import android.car.remoteaccess.CarRemoteAccessManager;
import android.car.storagemonitoring.CarStorageMonitoringManager;
import android.car.telemetry.CarTelemetryManager;
import android.car.test.CarTestManager;
import android.car.user.CarUserManager;
import android.car.vms.VmsClientManager;
import android.car.vms.VmsSubscriberManager;
import android.car.watchdog.CarWatchdogManager;
import android.car.wifi.CarWifiManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.TransactionTooLargeException;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.car.internal.ICarBase;
import com.android.car.internal.VisibleForHiddenApiCheck;
import com.android.car.internal.common.CommonConstants;
import com.android.car.internal.dep.SystemProperties;
import com.android.car.internal.os.Process;
import com.android.car.internal.os.ServiceManager;
import com.android.car.internal.os.SystemProcess;
import com.android.car.internal.os.SystemServiceManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 *   Top level car API for Android Automotive OS deployments.
 *   This API works only for devices with {@link PackageManager#FEATURE_AUTOMOTIVE}
 *   Calling this API on a device with no such feature will lead to an exception.
 */
public final class Car implements ICarBase {

    /**
     * System property to define platform minor version.
     *
     * <p>Value is int string. Check {@link #PROPERTY_PLATFORM_MINOR_INT} for further details.
     * If not set, default value of {@code 0} is assumed.
     */
    private static final String PROPERTY_PLATFORM_MINOR_VERSION =
            "ro.android.car.version.platform_minor";

    /**
     * @deprecated - This does not return the correct version. Use
     * {@code getCarVersion().getMajorVersion()} instead.
     */
    @Deprecated
    public static final int API_VERSION_MAJOR_INT = 34;

    /**
     * @deprecated - This does not return the correct version. Use
     * {@code getCarVersion().getMinorVersion()} instead
     */
    @Deprecated
    public static final int API_VERSION_MINOR_INT = 0;


    /**
     * @deprecated - use {@code getPlatformApiVersion().getMinorVersion()} instead
     */
    @Deprecated
    public static final int PLATFORM_VERSION_MINOR_INT = SystemProperties
            .getInt(PROPERTY_PLATFORM_MINOR_VERSION, /* def= */ 0);

    // These are the actual car api versions. Due to legacy reasons, we cannot modfiy
    // API_VERSION_MAJOR_INT and API_VERSION_MINOR_INT because they were exposed as public.
    private static final int ACTUAL_API_VERSION_MAJOR_INT = Build.VERSION.SDK_INT;
    private static final int ACTUAL_API_VERSION_MINOR_INT = 0;

    private static final CarVersion CAR_VERSION = CarVersion.newInstance("Car.CAR_VERSION",
            ACTUAL_API_VERSION_MAJOR_INT, ACTUAL_API_VERSION_MINOR_INT);

    private static final PlatformVersion PLATFORM_VERSION;

    /**
     * @hide
     */
    @TestApi
    public static final String PROPERTY_EMULATED_PLATFORM_VERSION_MAJOR =
            "com.android.car.internal.debug.platform_major_version";
    /**
     * @hide
     */
    @TestApi
    public static final String PROPERTY_EMULATED_PLATFORM_VERSION_MINOR =
            "com.android.car.internal.debug.platform_minor_version";

    static {
        PlatformVersion emulated = null;
        if (!BuildHelper.isUserBuild()) {
            int major = SystemProperties.getInt(PROPERTY_EMULATED_PLATFORM_VERSION_MAJOR, -1);
            if (major != -1) {
                int minor = SystemProperties.getInt(PROPERTY_EMULATED_PLATFORM_VERSION_MINOR,
                        PLATFORM_VERSION_MINOR_INT);
                emulated = android.car.PlatformVersion.newInstance("EMULATED", major, minor);
                Slog.i(TAG_CAR, "Emulating PLATFORM_VERSION version: " + emulated);
            }
        }
        PLATFORM_VERSION =
                emulated != null ? emulated : PlatformVersion.getCurrentPlatformVersionForMinor(
                        "Car.PLATFORM_VERSION", PLATFORM_VERSION_MINOR_INT);
    }

    // Car service registry information.
    // This information never changes after the static initialization completes.
    private static final Map<Class<?>, String> CAR_SERVICE_NAMES =
            new ArrayMap<Class<?>, String>(39);

    /**
     * Binder service name of car service registered to service manager.
     *
     * @hide
     */
    @VisibleForHiddenApiCheck
    public static final String CAR_SERVICE_BINDER_SERVICE_NAME = "car_service";

    /**
     * This represents AndroidManifest meta-data to tell that {@code Activity} is optimized for
     * driving distraction.
     *
     * <p>Activities without this meta-data can be blocked while car is in moving / driving state.
     *
     * <p>Note that having this flag does not guarantee that the {@code Activity} will be always
     * allowed for all driving states.
     *
     * <p>For this meta-data, android:value can be {@code true} (=optimized) or {@code false}.
     *
     * <p>Example usage:
     * <xml><meta-data android:name="distractionOptimized" android:value="true"/></xml>
     */
    @SuppressLint("IntentName")
    public static final String META_DATA_DISTRACTION_OPTIMIZED = "distractionOptimized";

    /**
     * This represents AndroidManifest meta-data to tell that {@code Application} requires specific
     * car features to work.
     *
     * <p>Apps like launcher or installer app can use this information to filter out apps
     * not usable in a specific car. This meta-data is not necessary for mandatory features.
     *
     * <p>For this meta-data, android:value should contain the feature name string defined by
     * {@code OptionalFeature} or {@code ExperimentalFeature} annotations.
     *
     * <p>Example usage:
     * <xml><meta-data android:name="requires-car-feature" android:value="diagnostic"/></xml>
     */
    @SuppressLint("IntentName")
    public static final String META_DATA_REQUIRES_CAR_FEATURE = "requires-car-feature";

    /**
     * Service name for {@link CarSensorManager}, to be used in {@link #getCarManager(String)}.
     *
     * @deprecated  {@link CarSensorManager} is deprecated. Use {@link CarPropertyManager} instead.
     */
    @MandatoryFeature
    @Deprecated
    public static final String SENSOR_SERVICE = "sensor";

    /** Service name for {@link CarInfoManager}, to be used in {@link #getCarManager(String)}. */
    @MandatoryFeature
    public static final String INFO_SERVICE = "info";

    /** Service name for {@link CarAppFocusManager}. */
    @MandatoryFeature
    public static final String APP_FOCUS_SERVICE = "app_focus";

    /** Service name for {@link CarPackageManager} */
    @MandatoryFeature
    public static final String PACKAGE_SERVICE = "package";

    /** Service name for {@link CarAudioManager} */
    @MandatoryFeature
    public static final String AUDIO_SERVICE = "audio";

    /** Service name for {@code CarNavigationStatusManager} */
    @OptionalFeature
    public static final String CAR_NAVIGATION_SERVICE = "car_navigation_service";

    /**
     * Service name for {@link CarOccupantConnectionManager}.
     *
     * @hide
     */
    @OptionalFeature
    @SystemApi
    public static final String CAR_OCCUPANT_CONNECTION_SERVICE = "car_occupant_connection_service";

    /**
     * Service name for {@link CarRemoteDeviceManager}.
     *
     * @hide
     */
    @OptionalFeature
    @SystemApi
    public static final String CAR_REMOTE_DEVICE_SERVICE = "car_remote_device_service";

    /** Service name for {@link CarOccupantZoneManager} */
    @MandatoryFeature
    public static final String CAR_OCCUPANT_ZONE_SERVICE = "car_occupant_zone_service";

    /**
     * Service name for {@link CarUserManager}
     *
     * @hide
     */
    @MandatoryFeature
    @SystemApi
    public static final String CAR_USER_SERVICE = "car_user_service";

    /**
     * Service name for ExperimentalCarKeyguardService
     *
     * @hide
     */
    @OptionalFeature
    public static final String EXPERIMENTAL_CAR_KEYGUARD_SERVICE =
            "experimental_car_keyguard_service";

    /**
     * Service name for {@link CarDevicePolicyManager}
     *
     * @hide
     */
    @MandatoryFeature
    @SystemApi
    public static final String CAR_DEVICE_POLICY_SERVICE = "car_device_policy_service";

    /**
     * Service name for {@link CarInstrumentClusterManager}
     *
     * @deprecated CarInstrumentClusterManager is being deprecated
     * @hide
     */
    @OptionalFeature
    @Deprecated
    public static final String CAR_INSTRUMENT_CLUSTER_SERVICE = "cluster_service";

    /**
     * Service name for {@link CarCabinManager}.
     *
     * @deprecated {@link CarCabinManager} is deprecated. Use {@link CarPropertyManager} instead.
     * @hide
     */
    @MandatoryFeature
    @Deprecated
    @SystemApi
    public static final String CABIN_SERVICE = "cabin";

    /**
     * Service name for {@link android.car.diagnostic.CarDiagnosticManager}.
     * @hide
     */
    @OptionalFeature
    @SystemApi
    public static final String DIAGNOSTIC_SERVICE = "diagnostic";

    /**
     * Service name for {@link CarHvacManager}
     * @deprecated {@link CarHvacManager} is deprecated. Use {@link CarPropertyManager} instead.
     * @hide
     */
    @MandatoryFeature
    @Deprecated
    @SystemApi
    public static final String HVAC_SERVICE = "hvac";

    /**
     * Service name for {@link CarPowerManager}
     */
    @MandatoryFeature
    public static final String POWER_SERVICE = "power";

    /**
     * Service name for {@link android.car.CarProjectionManager}
     * @hide
     */
    @MandatoryFeature
    @SystemApi
    public static final String PROJECTION_SERVICE = "projection";

    /**
     * Service name for {@link CarPropertyManager}
     */
    @MandatoryFeature
    public static final String PROPERTY_SERVICE = "property";

    /**
     * Service name for {@link CarVendorExtensionManager}
     *
     * @deprecated {@link CarVendorExtensionManager} is deprecated.
     * Use {@link CarPropertyManager} instead.
     * @hide
     */
    @MandatoryFeature
    @Deprecated
    @SystemApi
    public static final String VENDOR_EXTENSION_SERVICE = "vendor_extension";

    /**
     * Service name for {@link VmsClientManager}
     *
     * @hide
     */
    @OptionalFeature
    @SystemApi
    public static final String VEHICLE_MAP_SERVICE = "vehicle_map_service";

    /**
     * Service name for {@link VmsSubscriberManager}
     *
     * @deprecated {@link VmsSubscriberManager} is deprecated. Use {@link VmsClientManager} instead.
     * @hide
     */
    @OptionalFeature
    @Deprecated
    @SystemApi
    public static final String VMS_SUBSCRIBER_SERVICE = "vehicle_map_subscriber_service";

    /**
     * Service name for {@link CarDrivingStateManager}
     * @hide
     */
    @MandatoryFeature
    @SystemApi
    public static final String CAR_DRIVING_STATE_SERVICE = "drivingstate";

    /**
     * Service name for {@link CarUxRestrictionsManager}
     */
    public static final String CAR_UX_RESTRICTION_SERVICE = "uxrestriction";

    /**
     * Service name for {@link android.car.occupantawareness.OccupantAwarenessManager}
     * @hide
     */
    @OptionalFeature
    @SystemApi
    public static final String OCCUPANT_AWARENESS_SERVICE = "occupant_awareness";

    /**
     * Service name for {@link android.car.media.CarMediaManager}
     * @hide
     */
    @MandatoryFeature
    @SystemApi
    public static final String CAR_MEDIA_SERVICE = "car_media";

    /**
     * Service name for {@link android.car.CarBugreportManager}
     * @hide
     */
    @MandatoryFeature
    @SystemApi
    public static final String CAR_BUGREPORT_SERVICE = "car_bugreport";

    /**
     * Service name for {@link android.car.storagemonitoring.CarStorageMonitoringManager}
     * @hide
     */
    @OptionalFeature
    @SystemApi
    public static final String STORAGE_MONITORING_SERVICE = "storage_monitoring";

    /**
     * Service name for {@link android.car.watchdog.CarWatchdogManager}
     */
    @MandatoryFeature
    public static final String CAR_WATCHDOG_SERVICE = "car_watchdog";

    /**
     * Service name for {@link android.car.os.CarPerformanceManager}
     *
     * @hide
     */
    @MandatoryFeature
    @SystemApi
    public static final String CAR_PERFORMANCE_SERVICE = "car_performance";

    /**
     * Service name for {@link android.car.input.CarInputManager}
     * @hide
     */
    @MandatoryFeature
    @SystemApi
    public static final String CAR_INPUT_SERVICE = "android.car.input";

    /**
     * Service name for {@link android.car.cluster.ClusterHomeManager}
     * @hide
     */
    @OptionalFeature
    public static final String CLUSTER_HOME_SERVICE = "cluster_home_service";

    /**
     * Service for testing. This is system app only feature.
     * Service name for {@link CarTestManager}, to be used in {@link #getCarManager(String)}.
     * @hide
     */
    @MandatoryFeature
    @SystemApi
    public static final String TEST_SERVICE = "car-service-test";

    /**
     * Service name for {@link android.car.evs.CarEvsManager}
     *
     * @hide
     */
    @OptionalFeature
    @SystemApi
    public static final String CAR_EVS_SERVICE = "car_evs_service";

    /**
     * Service name for {@link android.car.telemetry.CarTelemetryManager}
     *
     * @hide
     */
    @OptionalFeature
    @SystemApi
    public static final String CAR_TELEMETRY_SERVICE = "car_telemetry_service";

    /**
     * Service name for {@link android.car.app.CarActivityManager}
     *
     * @hide
     */
    @MandatoryFeature
    @SystemApi
    public static final String CAR_ACTIVITY_SERVICE = "car_activity_service";

    /**
     * Service name for {@link android.car.remoteaccess.CarRemoteAccessManager}
     *
     * @hide
     */
    @OptionalFeature
    @SystemApi
    public static final String CAR_REMOTE_ACCESS_SERVICE = "car_remote_access_service";

    /**
     * Service name for {@link android.car.wifi.CarWifiManager}
     *
     * @hide
     */
    @MandatoryFeature
    @SystemApi
    @FlaggedApi(Flags.FLAG_PERSIST_AP_SETTINGS)
    public static final String CAR_WIFI_SERVICE = "car_wifi_service";

    /**
     * Signature|Privileged permission necessary to read driver monitoring systems settings
     * information.
     *
     * Examples of settings include the ENABLED properties for the supported driver monitoring
     * features.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_READ_DRIVER_MONITORING_SETTINGS =
            "android.car.permission.READ_DRIVER_MONITORING_SETTINGS";

    /**
     * Signature|Privileged permission necessary to control driver monitoring systems settings
     * information.
     *
     * Examples of settings include the ENABLED properties for the supported driver monitoring
     * features.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_DRIVER_MONITORING_SETTINGS =
            "android.car.permission.CONTROL_DRIVER_MONITORING_SETTINGS";

    /**
     * Signature|Privileged permission necessary to read driver monitoring systems states
     * information.
     *
     * Examples of states include the STATE and WARNING properties for the supported driver
     * monitoring features.
     *
     * This is different from {@link PERMISSION_READ_DRIVER_MONITORING_SETTINGS}, which allows an
     * app to read the system settings, such as whether the system is enabled or disabled.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_READ_DRIVER_MONITORING_STATES =
            "android.car.permission.READ_DRIVER_MONITORING_STATES";

    /**
     * Signature|Privileged permission necessary to access car's mileage information.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_MILEAGE = "android.car.permission.CAR_MILEAGE";

    /**
     * Dangerous permission necessary to access car's mileage information.
     */
    @FlaggedApi(FLAG_ANDROID_B_VEHICLE_PROPERTIES)
    public static final String PERMISSION_MILEAGE_3P = "android.car.permission.CAR_MILEAGE_3P";

    /**
     * Dangerous permission necessary to access car's energy information.
     */
    public static final String PERMISSION_ENERGY = "android.car.permission.CAR_ENERGY";

    /**
     * Signature|Privileged permission necessary to control car's EV charge settings.
     */
    public static final String PERMISSION_CONTROL_CAR_ENERGY =
            "android.car.permission.CONTROL_CAR_ENERGY";

    /**
     * Signature|Privileged permission necessary to change value of car's range remaining.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_ADJUST_RANGE_REMAINING =
            "android.car.permission.ADJUST_RANGE_REMAINING";

    /**
     * Signature|Privileged permission necessary to access car's VIN information
     */
    public static final String PERMISSION_IDENTIFICATION =
            "android.car.permission.CAR_IDENTIFICATION";

    /**
     * Dangerous permission necessary to access car's speed.
     */
    public static final String PERMISSION_SPEED = "android.car.permission.CAR_SPEED";

    /**
     * Signature|Privileged permission necessary to access car's dynamics state.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CAR_DYNAMICS_STATE =
            "android.car.permission.CAR_DYNAMICS_STATE";

    /**
     * Signature|Privileged permission necessary to control car's dynamics state.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_CAR_DYNAMICS_STATE =
            "android.car.permission.CONTROL_CAR_DYNAMICS_STATE";

    /**
     * Normal permission necessary to access car's fuel door and ev charge port.
     */
    public static final String PERMISSION_ENERGY_PORTS = "android.car.permission.CAR_ENERGY_PORTS";

    /**
     * Signature|Privileged permission necessary to control car's fuel door and ev charge port.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_ENERGY_PORTS =
            "android.car.permission.CONTROL_CAR_ENERGY_PORTS";

    /**
     * Signature|Privileged permission necessary to read car's exterior lights information.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_EXTERIOR_LIGHTS =
            "android.car.permission.CAR_EXTERIOR_LIGHTS";

    /**
     * Dangerous permission necessary to read car's exterior lights information.
     */
    @FlaggedApi(FLAG_ANDROID_B_VEHICLE_PROPERTIES)
    public static final String PERMISSION_READ_EXTERIOR_LIGHTS =
            "android.car.permission.READ_CAR_EXTERIOR_LIGHTS";

    /**
     * Signature|Privileged permission necessary to read car's interior lights information.
     */
    public static final String PERMISSION_READ_INTERIOR_LIGHTS =
            "android.car.permission.READ_CAR_INTERIOR_LIGHTS";

    /**
     * Signature|Privileged permission necessary to control car's exterior lights.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_EXTERIOR_LIGHTS =
            "android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS";

    /**
     * Dangerous permission necessary to read car horn state.
     */
    @FlaggedApi(FLAG_ANDROID_B_VEHICLE_PROPERTIES)
    public static final String PERMISSION_READ_CAR_HORN = "android.car.permission.READ_CAR_HORN";

    /**
     * Signature|Privileged permission necessary to control car horn state.
     */
    @FlaggedApi(FLAG_ANDROID_B_VEHICLE_PROPERTIES)
    public static final String PERMISSION_CONTROL_CAR_HORN =
            "android.car.permission.CONTROL_CAR_HORN";

    /**
     * Dangerous permission necessary to read car pedal information.
     */
    @FlaggedApi(FLAG_ANDROID_B_VEHICLE_PROPERTIES)
    public static final String PERMISSION_READ_CAR_PEDALS =
            "android.car.permission.READ_CAR_PEDALS";

    /**
     * Dangerous permission necessary to read brake information.
     */
    @FlaggedApi(FLAG_ANDROID_B_VEHICLE_PROPERTIES)
    public static final String PERMISSION_READ_BRAKE_INFO =
            "android.car.permission.READ_BRAKE_INFO";

    /**
     * Signature|Privileged permission necessary to control car's interior lights.
     */
    public static final String PERMISSION_CONTROL_INTERIOR_LIGHTS =
            "android.car.permission.CONTROL_CAR_INTERIOR_LIGHTS";

    /**
     * Normal permission necessary to access car's powertrain information.
     */
    public static final String PERMISSION_POWERTRAIN = "android.car.permission.CAR_POWERTRAIN";

    /**
     * Signature|Privileged permission necessary to control car's powertrain information.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_POWERTRAIN =
            "android.car.permission.CONTROL_CAR_POWERTRAIN";

    /**
     * Signature|Privileged permission necessary to change car audio volume through
     * {@link CarAudioManager}.
     */
    public static final String PERMISSION_CAR_CONTROL_AUDIO_VOLUME =
            "android.car.permission.CAR_CONTROL_AUDIO_VOLUME";

    /**
     * Signature|Privileged permission necessary to change car audio settings through
     * {@link CarAudioManager}.
     */
    public static final String PERMISSION_CAR_CONTROL_AUDIO_SETTINGS =
            "android.car.permission.CAR_CONTROL_AUDIO_SETTINGS";

    /**
     * Signature|Privileged permission necessary to receive full audio ducking events from car audio
     * focus handler.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_RECEIVE_CAR_AUDIO_DUCKING_EVENTS =
            "android.car.permission.RECEIVE_CAR_AUDIO_DUCKING_EVENTS";

    /**
     * Signature|Privileged permission necessary to use {@code CarNavigationStatusManager}.
     */
    public static final String PERMISSION_CAR_NAVIGATION_MANAGER =
            "android.car.permission.CAR_NAVIGATION_MANAGER";

    /**
     * Signature|Privileged permission necessary to start activities in the instrument cluster
     * through {@link CarInstrumentClusterManager}
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_INSTRUMENT_CLUSTER_CONTROL =
            "android.car.permission.CAR_INSTRUMENT_CLUSTER_CONTROL";

    /**
     * Signature|Privileged permission necessary to listen for the instrument cluster's navigation
     * state changes.
     *
     * @hide
     */
    @FlaggedApi(FLAG_CLUSTER_HEALTH_MONITORING)
    @SystemApi
    public static final String PERMISSION_CAR_MONITOR_CLUSTER_NAVIGATION_STATE =
            "android.car.permission.CAR_MONITOR_CLUSTER_NAVIGATION_STATE";

    /**
     * Signature|Privileged permission required by applications in order to be launched in the
     * instrument cluster display.
     *
     * @hide
     */
    @VisibleForHiddenApiCheck
    public static final String PERMISSION_CAR_DISPLAY_IN_CLUSTER =
            "android.car.permission.CAR_DISPLAY_IN_CLUSTER";

    /**
     * Normal permission necessary to use {@link CarInfoManager}.
     */
    public static final String PERMISSION_CAR_INFO = "android.car.permission.CAR_INFO";

    /**
     * Signature|Privileged permission necessary to access privileged car info.
     */
    public static final String PERMISSION_PRIVILEGED_CAR_INFO =
            "android.car.permission.PRIVILEGED_CAR_INFO";

    /**
     * Signature|Privileged permission necessary to read information of vendor properties'
     * permissions.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_READ_CAR_VENDOR_PERMISSION_INFO =
            "android.car.permission.READ_CAR_VENDOR_PERMISSION_INFO";

    /**
     * Normal permission necessary to read temperature of car's exterior environment.
     */
    public static final String PERMISSION_EXTERIOR_ENVIRONMENT =
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT";

    /**
     * Signature|Privileged permission necessary to access car specific communication channel.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_VENDOR_EXTENSION =
            "android.car.permission.CAR_VENDOR_EXTENSION";

    /**
     * Signature|Privileged permission.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_APP_BLOCKING =
            "android.car.permission.CONTROL_APP_BLOCKING";

    /**
     * Signature|Privileged permission necessary to access car's engine information.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CAR_ENGINE_DETAILED =
            "android.car.permission.CAR_ENGINE_DETAILED";

    /**
     * Dangerous permission necessary to access car's engine information.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_25Q2_3P_PERMISSIONS)
    public static final String PERMISSION_CAR_ENGINE_DETAILED_3P =
            "android.car.permission.CAR_ENGINE_DETAILED_3P";

    /**
     * Signature|Privileged permission necessary to access car's tire pressure information.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_TIRES = "android.car.permission.CAR_TIRES";

    /**
     * Dangerous permission necessary to access car's tire pressure information.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_25Q2_3P_PERMISSIONS)
    public static final String PERMISSION_TIRES_3P = "android.car.permission.CAR_TIRES_3P";

    /**
     * Signature|Privileged permission necessary to access car's property
     * {@link VehiclePropertyIds#EPOCH_TIME}.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CAR_EPOCH_TIME = "android.car.permission.CAR_EPOCH_TIME";

    /**
     * Signature|Privileged permission necessary to access car's steering angle information.
     */
    public static final String PERMISSION_READ_STEERING_STATE =
            "android.car.permission.READ_CAR_STEERING";

    /**
     * Dangerous permission necessary to access car's steering angle information.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_25Q2_3P_PERMISSIONS)
    public static final String PERMISSION_READ_STEERING_STATE_3P =
            "android.car.permission.READ_CAR_STEERING_3P";

    /**
     * Normal permission necessary to read and write display units for distance, fuel volume,
     * tire pressure and ev battery.
     */
    public static final String PERMISSION_READ_DISPLAY_UNITS =
            "android.car.permission.READ_CAR_DISPLAY_UNITS";

    /**
     * Normal permission necessary to control display units for distance, fuel volume, tire pressure
     * and ev battery. Currently, all display unit properties require both {@code
     * PERMISSION_CONTROL_DISPLAY_UNITS} and {@code PERMISSION_VENDOR_EXTENSION} to be granted in
     * order to write to them.
     */
    public static final String PERMISSION_CONTROL_DISPLAY_UNITS =
            "android.car.permission.CONTROL_CAR_DISPLAY_UNITS";

    /**
     * Signature|Privileged permission necessary to control car's door.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_CAR_DOORS =
            "android.car.permission.CONTROL_CAR_DOORS";

    /**
     * Signature|Privileged permission necessary to control car's windows.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_CAR_WINDOWS =
            "android.car.permission.CONTROL_CAR_WINDOWS";

    /**
     * Signature|Privileged permission necessary to control car glove box.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_GLOVE_BOX =
            "android.car.permission.CONTROL_GLOVE_BOX";

    /**
     * Dangerous permission necessary to read car's seats.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_25Q2_3P_PERMISSIONS)
    public static final String PERMISSION_READ_CAR_SEATS = "android.car.permission.READ_CAR_SEATS";

    /**
     * Signature|Privileged permission necessary to control car's seats.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_CAR_SEATS =
            "android.car.permission.CONTROL_CAR_SEATS";

    /**
     * Signature|Privileged permission necessary to control car's mirrors.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_CAR_MIRRORS =
            "android.car.permission.CONTROL_CAR_MIRRORS";

    /**
     * Signature|Privileged permission necessary to access Car HVAC APIs.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_CAR_CLIMATE =
            "android.car.permission.CONTROL_CAR_CLIMATE";

    /**
     * Signature|Privileged permission necessary to enable/disable a seat's ability to deploy
     * airbag(s) when triggered (e.g. by a crash).
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_CAR_AIRBAGS =
            "android.car.permission.CONTROL_CAR_AIRBAGS";


    /**
     * Signature|Privileged|VendorPrivileged permission necessary to access restrictive car power
     * management APIs.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_POWER = "android.car.permission.CAR_POWER";

    /**
     * Normal permission necessary to read the current power policy or be notified of power policy
     * change.
     */
    public static final String PERMISSION_READ_CAR_POWER_POLICY =
            "android.car.permission.READ_CAR_POWER_POLICY";

    /**
     * Signature|Privileged|VendorPrivileged permission necessary to apply a new power policy.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_CAR_POWER_POLICY =
            "android.car.permission.CONTROL_CAR_POWER_POLICY";

    /**
     * Signature|Privileged|VendorPrivileged permission necessary to adjust the shutdown process.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_SHUTDOWN_PROCESS =
            "android.car.permission.CONTROL_SHUTDOWN_PROCESS";

    /**
     * Signature|Privileged permission necessary to access Car PROJECTION system APIs.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_PROJECTION = "android.car.permission.CAR_PROJECTION";

    /**
     * Signature|Privileged permission necessary to access projection status.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_PROJECTION_STATUS =
            "android.car.permission.ACCESS_CAR_PROJECTION_STATUS";

    /**
     * Signature|Privileged permission necessary to mock vehicle hal for testing.
     * @hide
     * @deprecated mocking vehicle HAL in car service is no longer supported.
     */
    @Deprecated
    @SystemApi
    public static final String PERMISSION_MOCK_VEHICLE_HAL =
            "android.car.permission.CAR_MOCK_VEHICLE_HAL";

    /**
     * Signature|Privileged permission necessary to access CarTestService.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_TEST_SERVICE =
            "android.car.permission.CAR_TEST_SERVICE";

    /**
     * Signature|Privileged permission necessary to access a car's driving state
     * (E.g. CarDrivingStateService).
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CAR_DRIVING_STATE =
            "android.car.permission.CAR_DRIVING_STATE";

    /**
     * Dangerous permission necessary to access a car's driving state.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_25Q2_3P_PERMISSIONS)
    public static final String PERMISSION_CAR_DRIVING_STATE_3P =
            "android.car.permission.CAR_DRIVING_STATE_3P";

    /**
     * Signature permission necessary to access VMS client service.
     *
     * @hide
     */
    @VisibleForHiddenApiCheck
    public static final String PERMISSION_BIND_VMS_CLIENT =
            "android.car.permission.BIND_VMS_CLIENT";

    /**
     * Signature|Privileged permissions necessary to access VMS publisher APIs.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_VMS_PUBLISHER = "android.car.permission.VMS_PUBLISHER";

    /**
     * Signature|Privileged permissions necessary to access VMS subscriber APIs.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_VMS_SUBSCRIBER = "android.car.permission.VMS_SUBSCRIBER";

    /**
     * Signature|Privileged permission necessary to read ultrasonics sensor data.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_READ_ULTRASONICS_SENSOR_DATA =
            "android.car.permission.READ_ULTRASONICS_SENSOR_DATA";

    /**
     * Signature|Privileged permissions necessary to read diagnostic information, including
     * vendor-specific bits.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_DIAGNOSTIC_READ_ALL =
            "android.car.permission.CAR_DIAGNOSTICS";

    /**
     * Signature|Privileged permissions necessary to clear diagnostic information.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_DIAGNOSTIC_CLEAR =
            "android.car.permission.CLEAR_CAR_DIAGNOSTICS";

    /**
     * Signature|Privileged permission necessary to configure UX restrictions through
     * {@link CarUxRestrictionsManager}.
     *
     * @hide
     */
    @VisibleForHiddenApiCheck
    @SystemApi
    public static final String PERMISSION_CAR_UX_RESTRICTIONS_CONFIGURATION =
            "android.car.permission.CAR_UX_RESTRICTIONS_CONFIGURATION";

    /**
     * Signature|Privileged permission necessary to listen to occupant awareness state
     * {@link OccupantAwarenessManager}.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_READ_CAR_OCCUPANT_AWARENESS_STATE =
            "android.car.permission.READ_CAR_OCCUPANT_AWARENESS_STATE";

    /**
     * Signature|Privileged permission necessary to access private display id.
     *
     * @hide
     */
    @SystemApi
    public static final String ACCESS_PRIVATE_DISPLAY_ID =
            "android.car.permission.ACCESS_PRIVATE_DISPLAY_ID";

    /**
     * @deprecated Signature|Privileged permission that is not used by any service.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_CAR_OCCUPANT_AWARENESS_SYSTEM =
            "android.car.permission.CONTROL_CAR_OCCUPANT_AWARENESS_SYSTEM";

    /**
     * Signature|Privileged permissions necessary to clear diagnostic information.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_STORAGE_MONITORING =
            "android.car.permission.STORAGE_MONITORING";

    /**
     * Signature|Privileged permission necessary to dynamically enable / disable optional car
     * features.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_CAR_FEATURES =
            "android.car.permission.CONTROL_CAR_FEATURES";

    /**
     * Signature|Privileged permission necessary to be car watchdog clients.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_USE_CAR_WATCHDOG =
            "android.car.permission.USE_CAR_WATCHDOG";

    /**
     * Signature|Privileged permission necessary to monitor Car input events.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CAR_MONITOR_INPUT =
            "android.car.permission.CAR_MONITOR_INPUT";

    /**
     * Signature|Privileged permission necessary to request CarEvsService to launch the special
     * activity to show the camera preview.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_REQUEST_CAR_EVS_ACTIVITY =
            "android.car.permission.REQUEST_CAR_EVS_ACTIVITY";

    /**
     * Signature|Privileged permission necessary to control the special activity to show the camera
     * preview.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_CAR_EVS_ACTIVITY =
            "android.car.permission.CONTROL_CAR_EVS_ACTIVITY";

    /**
     * Signature|Privileged permission necessary to use the camera streams via CarEvsService.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_USE_CAR_EVS_CAMERA =
            "android.car.permission.USE_CAR_EVS_CAMERA";

    /**
     * Signature|Privileged permission necessary to monitor the status of CarEvsService.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_MONITOR_CAR_EVS_STATUS =
            "android.car.permission.MONITOR_CAR_EVS_STATUS";

    /**
     * Signature|Privileged permission necessary to use the CarTelemetryService.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_USE_CAR_TELEMETRY_SERVICE =
            "android.car.permission.USE_CAR_TELEMETRY_SERVICE";

    /**
     * Type of car connection: platform runs directly in car.
     *
     * @deprecated connection type constants are no longer used
     */
    @Deprecated
    public static final int CONNECTION_TYPE_EMBEDDED = 5;

    /**
     * Signature|Privileged permission necessary to be able to render template-based UI metadata on
     * behalf of another application.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_TEMPLATE_RENDERER =
            "android.car.permission.TEMPLATE_RENDERER";

    /**
     * Signature|Privileged permission necessary to set or retrieve car watchdog configurations.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_CAR_WATCHDOG_CONFIG =
            "android.car.permission.CONTROL_CAR_WATCHDOG_CONFIG";

    /**
     * Signature|Privileged permission necessary to collect metrics from car watchdog.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_COLLECT_CAR_WATCHDOG_METRICS =
            "android.car.permission.COLLECT_CAR_WATCHDOG_METRICS";

    /**
     * Permission necessary to fetch car CPU information.
     *
     * @hide
     */
    public static final String PERMISSION_COLLECT_CAR_CPU_INFO =
            "android.car.permission.COLLECT_CAR_CPU_INFO";

    /**
     * Signature|Privileged permission necessary to control launching applications in Car.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_CAR_APP_LAUNCH =
            "android.car.permission.CONTROL_CAR_APP_LAUNCH";

    /**
     * Signature|Privileged permission necessary to setting and getting thread scheduling policy and
     * priority.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_MANAGE_THREAD_PRIORITY =
            "android.car.permission.MANAGE_THREAD_PRIORITY";

    /**
     * Signature|Privileged permission necessary to modify occupant zone settings. Will be used in
     * {@link CarOccupantZoneManager}.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_MANAGE_OCCUPANT_ZONE =
            "android.car.permission.MANAGE_OCCUPANT_ZONE";

    /**
     * Normal permission necessary to use remote access.
     */
    public static final String PERMISSION_USE_REMOTE_ACCESS =
            "android.car.permission.USE_REMOTE_ACCESS";

    /**
     * Signature|Privileged permission necessary to control remote access.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_CONTROL_REMOTE_ACCESS =
            "android.car.permission.CONTROL_REMOTE_ACCESS";
    /**
     * Signature|Privileged permission necessary to control car's steering wheel.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_STEERING_WHEEL =
            "android.car.permission.CONTROL_STEERING_WHEEL";

    /**
     * Signature|Privileged permission necessary to read ADAS settings information.
     *
     * Examples of settings include the ENABLED properties for the supported ADAS features.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_READ_ADAS_SETTINGS =
            "android.car.permission.READ_ADAS_SETTINGS";

    /**
     * Signature|Privileged permission necessary to control ADAS settings information.
     *
     * Examples of settings include the ENABLED properties for the supported ADAS features.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_ADAS_SETTINGS =
            "android.car.permission.CONTROL_ADAS_SETTINGS";

    /**
     * Signature|Privileged permission necessary to read ADAS states information.
     *
     * Examples include the STATE properties for the supported ADAS features.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_READ_ADAS_STATES =
            "android.car.permission.READ_ADAS_STATES";

    /**
     * Signature|Privileged permission necessary to control ADAS states information.
     *
     * Examples include the STATE properties for the supported ADAS features.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_ADAS_STATES =
            "android.car.permission.CONTROL_ADAS_STATES";

    /**
     * Signature|Privileged permission necessary to monitor the states of other occupant zones in
     * the car and peer apps (apps that have the same package name as the caller) installed in those
     * zones, and manage the power of those zones.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_MANAGE_REMOTE_DEVICE =
            "android.car.permission.MANAGE_REMOTE_DEVICE";

    /**
     * Signature|Privileged permission necessary to establish connection and communicate to peer
     * apps (apps that have the same package name as the caller) installed in other occupant zones
     * in the car.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_MANAGE_OCCUPANT_CONNECTION =
            "android.car.permission.MANAGE_OCCUPANT_CONNECTION";

    /**
     * Signature|Privileged permission to access the mirrored Surface using the Token generated by
     * {@link CarActivityManager#createTaskMirroringToken(int)}.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_ACCESS_MIRRORRED_SURFACE =
            "android.car.permission.ACCESS_MIRRORED_SURFACE";

    /**
     * Signature|Privileged permission to create the mirroring token for the Display.
     * See {@link CarActivityManager#createDisplayMirroringToken(int)} (int)}.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_MIRROR_DISPLAY =
            "android.car.permission.MIRROR_DISPLAY";

    /**
     * Signature|Privileged permission necessary to read car's windshield wipers.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_READ_WINDSHIELD_WIPERS =
            "android.car.permission.READ_WINDSHIELD_WIPERS";

    /**
     * Dangerous permission necessary to read car's windshield wipers.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_25Q2_3P_PERMISSIONS)
    public static final String PERMISSION_READ_WINDSHIELD_WIPERS_3P =
            "android.car.permission.READ_WINDSHIELD_WIPERS_3P";

    /**
     * Signature|Privileged permission necessary to control car's windshield wipers.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_WINDSHIELD_WIPERS =
            "android.car.permission.CONTROL_WINDSHIELD_WIPERS";

    /**
     * Signature|Privileged permission necessary to register a {@link SystemUIProxy} that can be
     * used by other apps to manage the system ui and create task views.
     *
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_REGISTER_CAR_SYSTEM_UI_PROXY =
            "android.car.permission.REGISTER_CAR_SYSTEM_UI_PROXY";

    /**
     * Signature|Privileged permission necessary to communicate with the car system ui for creating
     * task views or getting notified about system ui changes.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_MANAGE_CAR_SYSTEM_UI =
            "android.car.permission.MANAGE_CAR_SYSTEM_UI";

    /**
     * Permission necessary to manage packages that requires launching in automotive compatibility
     * mode.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_DISPLAY_COMPATIBILITY)
    @SystemApi
    public static final String PERMISSION_MANAGE_DISPLAY_COMPATIBILITY =
            "android.car.permission.MANAGE_DISPLAY_COMPATIBILITY";

    /**
     * Signature|Privileged permission necessary to read state of car airbags.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_READ_CAR_AIRBAGS =
            "android.car.permission.READ_CAR_AIRBAGS";

    /**
     * Signature|Privileged permission necessary to read state of seat belts.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_READ_CAR_SEAT_BELTS =
            "android.car.permission.READ_CAR_SEAT_BELTS";

    /**
     * Signature|Privileged permission necessary to read impact sensors information.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_READ_IMPACT_SENSORS =
            "android.car.permission.READ_IMPACT_SENSORS";

    /**
     * Signature|Privileged permission necessary to read valet mode.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_READ_VALET_MODE =
            "android.car.permission.READ_VALET_MODE";

    /**
     * Signature|Privileged permission necessary to control valet mode.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_VALET_MODE =
            "android.car.permission.CONTROL_VALET_MODE";

    /**
     * Signature|Privileged permission necessary to read head up display status (e.g. whether the
     * head up display is enabled).
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_READ_HEAD_UP_DISPLAY_STATUS =
            "android.car.permission.READ_HEAD_UP_DISPLAY_STATUS";

    /**
     * Signature|Privileged permission necessary to control head up display.
     */
    @FlaggedApi(FLAG_VEHICLE_PROPERTY_REMOVE_SYSTEM_API_TAGS)
    public static final String PERMISSION_CONTROL_HEAD_UP_DISPLAY =
            "android.car.permission.CONTROL_HEAD_UP_DISPLAY";

    /**
     * Permission necessary to read persist tethering settings.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_PERSIST_AP_SETTINGS)
    @SystemApi
    public static final String PERMISSION_READ_PERSIST_TETHERING_SETTINGS =
            "android.car.permission.READ_PERSIST_TETHERING_SETTINGS";

    /**
     * Permission necessary to bind with app card providers.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CAR_APP_CARD)
    @SystemApi
    public static final String PERMISSION_BIND_APP_CARD_PROVIDER =
            "android.car.permission.BIND_APP_CARD_PROVIDER";

    /**
     * Signature permission necessary to record vehicle properties.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @SystemApi
    public static final String PERMISSION_RECORD_VEHICLE_PROPERTIES =
            "android.car.permission.RECORD_VEHICLE_PROPERTIES";

    /**
     * Signature permission necessary to inject vehicle properties for testing purposes.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @SystemApi
    public static final String PERMISSION_INJECT_VEHICLE_PROPERTIES =
            "android.car.permission.INJECT_VEHICLE_PROPERTIES";

    /**
     * Intent for connecting to the template renderer. Services that handle this intent must also
     * hold {@link #PERMISSION_TEMPLATE_RENDERER}. Applications would not bind to this service
     * directly, but instead they would use
     * <a href="https://developer.android.com/reference/com/google/android/libraries/car/app/packages">
     * Android for Cars App Library</a>.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String CAR_TEMPLATE_HOST_RENDERER_SERVICE =
            "android.car.template.host.RendererService";

    /** @hide */
    @IntDef({CONNECTION_TYPE_EMBEDDED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConnectionType {}

    /**
     * @deprecated Use {@link CarMediaIntents#ACTION_MEDIA_TEMPLATE} instead.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String CAR_INTENT_ACTION_MEDIA_TEMPLATE =
            "android.car.intent.action.MEDIA_TEMPLATE";

    /**
     * @deprecated Use {@link CarMediaIntents#EXTRA_MEDIA_COMPONENT} instead.
     */
    @Deprecated
    public static final String CAR_EXTRA_MEDIA_COMPONENT =
            "android.car.intent.extra.MEDIA_COMPONENT";

    /**
     *
     * @deprecated Use {@link #CAR_EXTRA_MEDIA_COMPONENT} instead.
     * @removed Using this for specifying MediaBrowserService was not supported since API level 29
     * and above. Apps must use {@link #CAR_EXTRA_MEDIA_COMPONENT} instead.
     */
    @Deprecated
    public static final String CAR_EXTRA_MEDIA_PACKAGE = "android.car.intent.extra.MEDIA_PACKAGE";

    /**
     * Used as a string extra field of media session to specify the service corresponding to the
     * session.
     */
    public static final String CAR_EXTRA_BROWSE_SERVICE_FOR_SESSION =
            "android.media.session.BROWSE_SERVICE";

    /**
     * Intent for being recognized as a remote task client service.
     *
     * <p>Services that use this intent must have a {@code PERMISSION_CONTROL_REMOTE_ACCESS}.
     *
     * @hide
     */
    @SystemApi
    public static final String CAR_REMOTEACCESS_REMOTE_TASK_CLIENT_SERVICE =
            "android.car.remoteaccess.RemoteTaskClientService";

    /**
     * Intent for binding the implementation of {@link
     * android.car.occupantconnection.AbstractReceiverService} in the app.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String CAR_INTENT_ACTION_RECEIVER_SERVICE =
            "android.car.intent.action.RECEIVER_SERVICE";

    /** @hide */
    @VisibleForHiddenApiCheck
    public static final String CAR_SERVICE_INTERFACE_NAME = CommonConstants.CAR_SERVICE_INTERFACE;

    private static final String CAR_SERVICE_PACKAGE = "com.android.car";

    private static final String CAR_SERVICE_CLASS = "com.android.car.CarService";

    /**
     * Category used by navigation applications to indicate which activity should be launched on
     * the instrument cluster when such application holds
     * {@link CarAppFocusManager#APP_FOCUS_TYPE_NAVIGATION} focus.
     *
     * @hide
     */
    @VisibleForHiddenApiCheck
    public static final String CAR_CATEGORY_NAVIGATION = "android.car.cluster.NAVIGATION";

    /**
     * When an activity is launched in the cluster, it will receive {@link ClusterActivityState} in
     * the intent's extra under this key, containing instrument cluster information such as
     * unobscured area, visibility, etc.
     *
     * @hide
     */
    @SystemApi
    public static final String CAR_EXTRA_CLUSTER_ACTIVITY_STATE =
            "android.car.cluster.ClusterActivityState";

    /**
     * @hide
     */
    @OptionalFeature
    @FlaggedApi(Flags.FLAG_DISPLAY_COMPATIBILITY)
    @SystemApi
    public static final String CAR_DISPLAY_COMPAT_SERVICE = "car_display_compat_service";

    /**
     * Service name for {@link android.car.hardware.property.CarPropertySimulationManager}
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SIMULATION)
    @SystemApi
    public static final String CAR_PROPERTY_SIMULATION_SERVICE = "car_property_simulation_service";

    /**
     * Callback to notify the Lifecycle of car service.
     *
     * <p>Access to car service should happen
     * after {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} call with
     * {@code ready} set {@code true}.</p>
     *
     * <p>When {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} is
     * called with ready set to false, access to car service should stop until car service is ready
     * again from {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} call
     * with {@code ready} set to {@code true}.</p>
     */
    public interface CarServiceLifecycleListener {
        /**
         * Car service has gone through status change.
         *
         * <p>This is always called in the main thread context.</p>
         *
         * @param car {@code Car} object that was originally associated with this lister from
         *            {@link #createCar(Context, Handler, long, Car.CarServiceLifecycleListener)}
         *            call.
         * @param ready when {@code true}, car service is ready and all accesses are ok
         *              Otherwise car service has crashed or killed and will be restarted
         */
        void onLifecycleChanged(@NonNull Car car, boolean ready);
    }

    /**
     * {@link #createCar(Context, Handler, long, CarServiceLifecycleListener)}'s
     * waitTimeoutMs value to use to wait forever inside the call until car service is ready.
     */
    public static final long CAR_WAIT_TIMEOUT_WAIT_FOREVER = -1;

    /**
     * {@link #createCar(Context, Handler, long, CarServiceLifecycleListener)}'s
     * waitTimeoutMs value to use to skip any waiting inside the call.
     */
    public static final long CAR_WAIT_TIMEOUT_DO_NOT_WAIT = 0;

    private static final long CAR_SERVICE_BIND_RETRY_INTERVAL_MS = 500;
    private static final long CAR_SERVICE_BIND_MAX_RETRY = 20;

    private static final long CAR_SERVICE_BINDER_POLLING_INTERVAL_MS = 50;
    private static final long CAR_SERVICE_BINDER_POLLING_MAX_RETRY = 100;
    private static final long CAR_SERVICE_REGISTRATION_TIMEOUT_MS =
            CAR_SERVICE_BINDER_POLLING_INTERVAL_MS * CAR_SERVICE_BINDER_POLLING_MAX_RETRY;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "STATE_", value = {
            STATE_DISCONNECTED,
            STATE_CONNECTING,
            STATE_CONNECTED,
    })
    @Target({ElementType.TYPE_USE})
    public @interface StateTypeEnum {}

    /**
     * The enabling request was successful and requires reboot to take effect.
     * @hide
     */
    @SystemApi
    public static final int FEATURE_REQUEST_SUCCESS = 0;
    /**
     * The requested feature is already enabled or disabled as requested. No need to reboot the
     * system.
     * @hide
     */
    @SystemApi
    public static final int FEATURE_REQUEST_ALREADY_IN_THE_STATE = 1;
    /**
     * The requested feature is mandatory cannot be enabled or disabled. It is always enabled.
     * @hide
     */
    @SystemApi
    public static final int FEATURE_REQUEST_MANDATORY = 2;
    /**
     * The requested feature is not available and cannot be enabled or disabled.
     * @hide
     */
    @SystemApi
    public static final int FEATURE_REQUEST_NOT_EXISTING = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "FEATURE_REQUEST_", value = {
            FEATURE_REQUEST_SUCCESS,
            FEATURE_REQUEST_ALREADY_IN_THE_STATE,
            FEATURE_REQUEST_MANDATORY,
            FEATURE_REQUEST_NOT_EXISTING,
    })
    @Target({ElementType.TYPE_USE})
    public @interface FeaturerRequestEnum {}

    private final Context mContext;
    private final Exception mConstructionStack;
    private final long mCreateUptimeMillis;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private ICar mService;
    @GuardedBy("mLock")
    private boolean mServiceBound;

    @GuardedBy("mLock")
    @StateTypeEnum
    private int mConnectionState;
    @GuardedBy("mLock")
    private int mConnectionRetryCount;

    // Whether we have registered the service listener. It should only be registered once.
    @GuardedBy("mLock")
    private boolean mServiceListenerRegistered;
    // The car service binder object we get from ServiceManager.
    @GuardedBy("mLock")
    private IBinder mCarServiceBinder;
    // Whether the client is currently waiting (blocked) for a car service connection.
    @GuardedBy("mLock")
    private boolean mWaiting;
    // Whether client explicitly called disconnect.
    @GuardedBy("mLock")
    private boolean mClientRequestDisconnect;

    private final Runnable mConnectionRetryRunnable = new Runnable() {
        @Override
        public void run() {
            startCarService();
        }
    };

    private final Runnable mConnectionRetryFailedRunnable = new Runnable() {
        @Override
        public void run() {
            mServiceConnectionListener.onServiceDisconnected(new ComponentName(CAR_SERVICE_PACKAGE,
                    CAR_SERVICE_CLASS));
        }
    };

    private final ServiceRegistrationCallbackForCar mServiceRegistrationCallback =
             new ServiceRegistrationCallbackForCar();

    private final class ServiceRegistrationCallbackForCar implements
            ServiceManagerHelper.IServiceRegistrationCallback  {
        @Override
        public void onRegistration(@NonNull String name, IBinder binder) {
            Slog.i(TAG_CAR, "car_service registered");
            if (!name.equals(CAR_SERVICE_BINDER_SERVICE_NAME)) {
                Slog.wtf(TAG_CAR, "Unexpected service name called for onRegistration: " + name);
                return;
            }
            synchronized (mLock) {
                mCarServiceBinder = binder;
                if (mWaiting) {
                    mLock.notifyAll();
                    return;
                }
                if (mClientRequestDisconnect) {
                    // Client explicitly called disconnect, do not invoke the callbacks.
                    return;
                }
            }

            mMainThreadEventHandler.post(() -> setBinderAndNotifyReady(binder));
        }
    }

    private final ServiceConnection mServiceConnectionListener = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                ICar newService = ICar.Stub.asInterface(service);
                if (newService == null) {
                    Slog.wtf(TAG_CAR, "null binder service", new RuntimeException());
                    return;  // should not happen.
                }
                if (mService != null && mService.asBinder().equals(newService.asBinder())) {
                    // already connected.
                    return;
                }
                mConnectionState = STATE_CONNECTED;
                mService = newService;
            }
            Slog.i(TAG_CAR, "car_service ready on main thread, Time between Car object creation "
                    + "and car_service connected (ms): " + timeSinceCreateMillis());
            if (mStatusChangeCallback != null) {
                mStatusChangeCallback.onLifecycleChanged(Car.this, /* ready= */ true);
            } else if (mServiceConnectionListenerClient != null) {
                mServiceConnectionListenerClient.onServiceConnected(name, service);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Car service can pick up feature changes after restart.
            Slog.w(TAG_CAR, "Car service disconnected, probably crashed");
            mFeatures.resetCache();
            synchronized (mLock) {
                if (mConnectionState == STATE_DISCONNECTED) {
                    // can happen when client calls disconnect before onServiceDisconnected call.
                    return;
                }
                handleCarDisconnectLocked();
            }
            if (mStatusChangeCallback != null) {
                mStatusChangeCallback.onLifecycleChanged(Car.this, /* ready= */ false);
            } else if (mServiceConnectionListenerClient != null) {
                mServiceConnectionListenerClient.onServiceDisconnected(name);
            } else {
                // This client does not handle car service restart, so should be terminated.
                finishClient();
            }
        }
    };

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Slog.w(TAG_CAR, "Car service disconnected, probably crashed");
            // Car service can pick up feature changes after restart.
            mFeatures.resetCache();
            synchronized (mLock) {
                if (mConnectionState == STATE_DISCONNECTED) {
                    Slog.i(TAG_CAR, "State is already disconnected, ignore");
                    // can happen when client calls disconnect before onServiceDisconnected call.
                    return;
                }
                mCarServiceBinder = null;
                handleCarDisconnectLocked();
            }
            dispatchToMainThread(isMainThread(), () -> notifyCarDisconnected());
            registerServiceListenerIfNotRegistered();
        }
    };

    @Nullable
    private final ServiceConnection mServiceConnectionListenerClient;

    /** Can be added after ServiceManagerHelper.getService call */
    @Nullable
    private final CarServiceLifecycleListener mStatusChangeCallback;

    @GuardedBy("mLock")
    private final HashMap<String, CarManagerBase> mServiceMap = new HashMap<>();

    /** Handler for generic event dispatching. */
    private final Handler mEventHandler;

    private final Handler mMainThreadEventHandler;

    private final CarFeatures mFeatures = new CarFeatures();

    /**
     * The dependencies used by this class.
     *
     * @hide
     */
    @VisibleForTesting
    public record Deps(ServiceManager serviceManager, Process process,
            long carServiceBindRetryIntervalMs, long carServiceBindMaxRetry) {}

    // Real system dependencies.
    private static final Deps SYSTEM_DEPS = new Deps(
            new SystemServiceManager(), new SystemProcess(),
            CAR_SERVICE_BIND_RETRY_INTERVAL_MS, CAR_SERVICE_BIND_MAX_RETRY);

    private final Deps mDeps;

    static {
        CAR_SERVICE_NAMES.put(CarSensorManager.class, SENSOR_SERVICE);
        CAR_SERVICE_NAMES.put(CarInfoManager.class, INFO_SERVICE);
        CAR_SERVICE_NAMES.put(CarAppFocusManager.class, APP_FOCUS_SERVICE);
        CAR_SERVICE_NAMES.put(CarPackageManager.class, PACKAGE_SERVICE);
        CAR_SERVICE_NAMES.put(CarAudioManager.class, AUDIO_SERVICE);
        CAR_SERVICE_NAMES.put(CarNavigationStatusManager.class, CAR_NAVIGATION_SERVICE);
        CAR_SERVICE_NAMES.put(CarOccupantZoneManager.class, CAR_OCCUPANT_ZONE_SERVICE);
        CAR_SERVICE_NAMES.put(CarUserManager.class, CAR_USER_SERVICE);
        CAR_SERVICE_NAMES.put(CarDevicePolicyManager.class, CAR_DEVICE_POLICY_SERVICE);
        CAR_SERVICE_NAMES.put(CarInstrumentClusterManager.class, CAR_INSTRUMENT_CLUSTER_SERVICE);
        CAR_SERVICE_NAMES.put(CarCabinManager.class, CABIN_SERVICE);
        CAR_SERVICE_NAMES.put(CarDiagnosticManager.class, DIAGNOSTIC_SERVICE);
        CAR_SERVICE_NAMES.put(CarHvacManager.class, HVAC_SERVICE);
        CAR_SERVICE_NAMES.put(CarPowerManager.class, POWER_SERVICE);
        CAR_SERVICE_NAMES.put(CarProjectionManager.class, PROJECTION_SERVICE);
        CAR_SERVICE_NAMES.put(CarPropertyManager.class, PROPERTY_SERVICE);
        CAR_SERVICE_NAMES.put(CarVendorExtensionManager.class, VENDOR_EXTENSION_SERVICE);
        CAR_SERVICE_NAMES.put(VmsClientManager.class, VEHICLE_MAP_SERVICE);
        CAR_SERVICE_NAMES.put(VmsSubscriberManager.class, VMS_SUBSCRIBER_SERVICE);
        CAR_SERVICE_NAMES.put(CarDrivingStateManager.class, CAR_DRIVING_STATE_SERVICE);
        CAR_SERVICE_NAMES.put(CarUxRestrictionsManager.class, CAR_UX_RESTRICTION_SERVICE);
        CAR_SERVICE_NAMES.put(OccupantAwarenessManager.class, OCCUPANT_AWARENESS_SERVICE);
        CAR_SERVICE_NAMES.put(CarMediaManager.class, CAR_MEDIA_SERVICE);
        CAR_SERVICE_NAMES.put(CarBugreportManager.class, CAR_BUGREPORT_SERVICE);
        CAR_SERVICE_NAMES.put(CarStorageMonitoringManager.class, STORAGE_MONITORING_SERVICE);
        CAR_SERVICE_NAMES.put(CarWatchdogManager.class, CAR_WATCHDOG_SERVICE);
        CAR_SERVICE_NAMES.put(CarPerformanceManager.class, CAR_PERFORMANCE_SERVICE);
        CAR_SERVICE_NAMES.put(CarInputManager.class, CAR_INPUT_SERVICE);
        CAR_SERVICE_NAMES.put(ClusterHomeManager.class, CLUSTER_HOME_SERVICE);
        CAR_SERVICE_NAMES.put(CarTestManager.class, TEST_SERVICE);
        CAR_SERVICE_NAMES.put(CarEvsManager.class, CAR_EVS_SERVICE);
        CAR_SERVICE_NAMES.put(CarTelemetryManager.class, CAR_TELEMETRY_SERVICE);
        CAR_SERVICE_NAMES.put(CarActivityManager.class, CAR_ACTIVITY_SERVICE);
        CAR_SERVICE_NAMES.put(CarRemoteAccessManager.class, CAR_REMOTE_ACCESS_SERVICE);
        CAR_SERVICE_NAMES.put(CarOccupantConnectionManager.class, CAR_OCCUPANT_CONNECTION_SERVICE);
        CAR_SERVICE_NAMES.put(CarRemoteDeviceManager.class, CAR_REMOTE_DEVICE_SERVICE);
        if (Flags.persistApSettings()) {
            CAR_SERVICE_NAMES.put(CarWifiManager.class, CAR_WIFI_SERVICE);
        }
        if (Flags.displayCompatibility()) {
            CAR_SERVICE_NAMES.put(CarDisplayCompatManager.class, CAR_DISPLAY_COMPAT_SERVICE);
        }
        if (Flags.carPropertySimulation()) {
            CAR_SERVICE_NAMES.put(CarPropertySimulationManager.class,
                    CAR_PROPERTY_SIMULATION_SERVICE);
        }
        // Note: if a new entry is added here, the capacity of CAR_SERVICE_NAMES should be increased
        // as well.
    }

    /**
     * Defines the {@link CarVersion version} of the {@code Car} APIs in the device.
     *
     * <p>Starting on {@link android.os.Build.VERSION_CODES#TIRAMISU Android 13}, the {@code Car}
     * APIs can be upgraded without an OTA, so it's possible that these APIs are higher than the
     * {@link #getPlatformVersion() platform's}.
     *
     * @deprecated - use {@code android.os.Build.VERSION#SDK_INT} instead
     */
    @NonNull
    @Deprecated
    public static android.car.CarVersion getCarVersion() {
        return CAR_VERSION;
    }

    /**
     * Defines the {@link PlatformVersion version} of the standard {@code SDK} APIs in the
     * device.
     *
     * <p>Its {@link ApiVersion#getMajorVersion() major version} will be the same as
     * {@link android.os.Build.VERSION#SDK_INT} for released build but will be
     * {@link android.os.Build.VERSION_CODES#CUR_DEVELOPMENT} for platform still under development.
     *
     * @deprecated - use {@code android.os.Build.VERSION#SDK_INT} instead
     */
    @NonNull
    @Deprecated
    public static android.car.PlatformVersion getPlatformVersion() {
        return PLATFORM_VERSION;
    }

    /**
     * @deprecated - use {@code getCarApiVersion().isAtLeast(CarVersion.forMajorAndMinorVersions(
     * requiredApiVersionMajor))} instead
     */
    @Deprecated
    public static boolean isApiVersionAtLeast(int requiredApiVersionMajor) {
        return getCarVersion().isAtLeast(CarVersion.forMajorVersion(requiredApiVersionMajor));
    }

    /**
     * @deprecated - use {@code getCarVersion().isAtLeast(CarVersion.forMajorAndMinorVersions(
     * requiredApiVersionMajor, requiredApiVersionMinor)} instead
     */
    @Deprecated
    public static boolean isApiVersionAtLeast(int requiredApiVersionMajor,
            int requiredApiVersionMinor) {
        return getCarVersion()
                .isAtLeast(CarVersion.forMajorAndMinorVersions(requiredApiVersionMajor,
                        requiredApiVersionMinor));
    }

    /**
     * @deprecated - use
     * {@code getCarVersion().isAtLeast(CarVersion.forMajorVersion(requiredApiVersionMajor))
     * && getPlatformVersion().isAtLeast(PlatformVersion.forMajorVersion(minPlatformSdkInt))}
     * instead.
     */
    @Deprecated
    public static boolean isApiAndPlatformVersionAtLeast(int requiredApiVersionMajor,
            int minPlatformSdkInt) {
        return getCarVersion().isAtLeast(CarVersion.forMajorVersion(requiredApiVersionMajor))
                && getPlatformVersion()
                        .isAtLeast(PlatformVersion.forMajorVersion(minPlatformSdkInt));
    }

    /**
     * @deprecated - use {@code getCarVersion().isAtLeast(CarVersion.forMajorAndMinorVersions(
     * requiredApiVersionMajor, requiredApiVersionMinor)) && getPlatformVersion().isAtLeast(
     * PlatformVersion.forMajorVersion(minPlatformSdkInt))} instead.
     */
    @Deprecated
    public static boolean isApiAndPlatformVersionAtLeast(int requiredApiVersionMajor,
            int requiredApiVersionMinor, int minPlatformSdkInt) {
        return getCarVersion()
                .isAtLeast(CarVersion.forMajorAndMinorVersions(requiredApiVersionMajor,
                        requiredApiVersionMinor))
                && getPlatformVersion()
                        .isAtLeast(PlatformVersion.forMajorVersion(minPlatformSdkInt));
    }

    /**
     * A factory method that creates Car instance for all Car API access.
     *
     * <p>Instance created with this should be disconnected from car service by calling
     * {@link #disconnect()} before the passed {@code Context} is released.
     *
     * @param context This should not be {@code null}. If you are passing {@link ContextWrapper},
     *                make sure that its {@link ContextWrapper#getBaseContext() base context} is not
     *                {@code null} as well.
     *                Otherwise it will throw {@link java.lang.NullPointerException}.
     * @param serviceConnectionListener listener for monitoring service connection. It is allowed
     * but not recommended to pass {@code null}. If this is {@code null}, client has to periodically
     * check {@link #isConnected()} to know when car service is connected.
     * @param handler the handler on which the callback should execute, or null to execute on the
     * service's main thread. Note: the service connection listener will be always on the main
     * thread regardless of the handler given.
     * @return Car instance if system is in car environment and returns {@code null} otherwise.
     *
     * @deprecated use {@link #createCar(Context, Handler)} instead.
     */
    @Deprecated
    public static Car createCar(Context context, ServiceConnection serviceConnectionListener,
            @Nullable Handler handler) {
        return new CarBuilder().createCar(context, serviceConnectionListener, handler);
    }

    /**
     * A factory method that creates Car instance for all Car API access using main thread {@code
     * Looper}.
     *
     * <p>Instance created with this should be disconnected from car service by calling
     * {@link #disconnect()} before the passed {@code Context} is released.
     *
     * @see #createCar(Context, ServiceConnection, Handler)
     *
     * @deprecated use {@link #createCar(Context, Handler)} instead.
     */
    @Deprecated
    public static Car createCar(Context context, ServiceConnection serviceConnectionListener) {
        return new CarBuilder().createCar(context, serviceConnectionListener);
    }

    /**
     * Creates new {@link Car} object which connected synchronously to Car Service and ready to use.
     *
     * <p>Instance created with this should be disconnected from car service by calling
     * {@link #disconnect()} before the passed {@code Context} is released.
     *
     * @param context application's context
     *
     * @return Car object if operation succeeded, otherwise null.
     */
    @Nullable
    public static Car createCar(Context context) {
        return new CarBuilder().createCar(context);
    }

    /**
     * Creates new {@link Car} object which connected synchronously to Car Service and ready to use.
     *
     * <p>Instance created with this should be disconnected from car service by calling
     * {@link #disconnect()} before the passed {@code Context} is released.
     *
     * @param context This should not be {@code null}. If you are passing {@link ContextWrapper},
     *                make sure that its {@link ContextWrapper#getBaseContext() base context} is not
     *                {@code null} as well.
     *                Otherwise it will throw {@link java.lang.NullPointerException}.
     * @param handler the handler on which the manager's callbacks will be executed, or null to
     * execute on the application's main thread.
     *
     * @return Car object if operation succeeded, otherwise null.
     */
    @Nullable
    public static Car createCar(Context context, @Nullable Handler handler) {
        return new CarBuilder().createCar(context, handler);
    }

    /**
     * Creates new {@link Car} object with {@link CarServiceLifecycleListener}.
     *
     * <p>Instance created with this should be disconnected from car service by calling
     * {@link #disconnect()} before the passed {@code Context} is released.
     *
     * <p> If car service is ready inside this call and if the caller is running in the main thread,
     * {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} will be called
     * with ready set to be true. Otherwise,
     * {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} will be called
     * from the main thread later. </p>
     *
     * <p>This call can block up to specified waitTimeoutMs to wait for car service to be ready.
     * If car service is not ready within the given time, it will return a Car instance in
     * disconnected state. Blocking main thread forever can lead into getting ANR (Application Not
     * Responding) killing from system and should not be used if the app is supposed to survive
     * across the crash / restart of car service. It can be still useful in case the app cannot do
     * anything without car service being ready. In any waiting, if the thread is getting
     * interrupted, it will return immediately.
     * </p>
     *
     * <p>Note that returned {@link Car} object is not guaranteed to be connected when there is
     * a limited timeout. Regardless of returned car being connected or not, it is recommended to
     * implement all car related initialization inside
     * {@link CarServiceLifecycleListener#onLifecycleChanged(Car, boolean)} and avoid the
     * needs to check if returned {@link Car} is connected or not from returned {@link Car}.</p>
     *
     * @param context This should not be {@code null}. If you are passing {@link ContextWrapper},
     *                make sure that its {@link ContextWrapper#getBaseContext() base context} is not
     *                {@code null} as well.
     *                Otherwise it will throw {@link java.lang.NullPointerException}.
     * @param handler dispatches all Car*Manager events to this Handler. Exception is
     *                {@link CarServiceLifecycleListener} which will be always dispatched to main
     *                thread. Passing null leads into dispatching all Car*Manager callbacks to main
     *                thread as well.
     * @param waitTimeoutMs Setting this to {@link #CAR_WAIT_TIMEOUT_DO_NOT_WAIT} will guarantee
     *                      that the API does not wait for the car service at all. Setting this to
     *                      to {@link #CAR_WAIT_TIMEOUT_WAIT_FOREVER} will block the call forever
     *                      until the car service is ready. Setting any positive value will be
     *                      interpreted as timeout value.
     */
    @NonNull
    public static Car createCar(@NonNull Context context, @Nullable Handler handler,
            long waitTimeoutMs, @NonNull CarServiceLifecycleListener statusChangeListener) {
        return new CarBuilder().createCar(context, handler, waitTimeoutMs, statusChangeListener);
    }

    /**
     * A wrapper around {@code createCar} functions that allows injecting deps for testing.
     *
     * @hide
     */
    @VisibleForTesting
    public static final class CarBuilder {
        private Deps mDeps = SYSTEM_DEPS;

        /**
         * Sets the fake dependencies for testing.
         */
        public CarBuilder setFakeDeps(Deps deps) {
            mDeps = deps;
            return this;
        }

        /**
         * See {@link Car#createCar}.
         *
         * Even though serviceConnectionListener is not marked as Nullable, existing client may
         * pass null.
         */
        @Nullable
        public Car createCar(Context context,
                @Nullable ServiceConnection serviceConnectionListener) {
            return createCar(context, serviceConnectionListener, /* handler= */ null);
        }

        /**
         * See {@link Car#createCar}.
         */
        @Nullable
        public Car createCar(Context context, @Nullable ServiceConnection serviceConnectionListener,
                @Nullable Handler handler) {
            assertNonNullContext(context);
            if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                Slog.e(TAG_CAR, "FEATURE_AUTOMOTIVE not declared while android.car is used");
                return null;
            }
            try {
                return new Car(context, /* service= */ null , serviceConnectionListener,
                        /* statusChangeListener= */ null, handler, mDeps);
            } catch (IllegalArgumentException e) {
                // Expected when car service loader is not available.
            }
            return null;
        }

        /**
         * See {@link Car#createCar}.
         */
        @Nullable
        public Car createCar(Context context) {
            return createCar(context, (Handler) null);
        }

        /**
         * See {@link Car#createCar}.
         */
        @Nullable
        public Car createCar(Context context, @Nullable Handler handler) {
            assertNonNullContext(context);

            if (Flags.createCarUseNotifications()) {
                // New optimized logic.
                return createCarInternal(context, handler, CAR_SERVICE_REGISTRATION_TIMEOUT_MS,
                        /* statusChangeListener= */ null);
            }

            return createCarInternalLegacy(context, handler);
        }

        /**
         * See {@link Car#createCar}.
         */
        @NonNull
        public Car createCar(@NonNull Context context, @Nullable Handler handler,
                long waitTimeoutMs, @NonNull CarServiceLifecycleListener statusChangeListener) {
            assertNonNullContext(context);
            Objects.requireNonNull(statusChangeListener);

            if (Flags.createCarUseNotifications()) {
                // New optimized logic.
                // statusChangeListener is not null, so this must not return null.
                return createCarInternal(context, handler, waitTimeoutMs, statusChangeListener);
            }

            return createCarInternalLegacy(context, handler, waitTimeoutMs, statusChangeListener);
        }

        private @Nullable Car createCarInternal(Context context,
                @Nullable Handler handler, long waitTimeoutMs,
                @Nullable CarServiceLifecycleListener statusChangeListener) {
            Car car = new Car(context, /* service= */ null, /* serviceConnectionListener= */ null,
                    statusChangeListener, handler, mDeps);
            IBinder binderService = mDeps.serviceManager().getService(
                    CAR_SERVICE_BINDER_SERVICE_NAME);
            if (binderService != null) {
                // Most common case when car service is already ready.
                car.setCarService(binderService);
                Slogf.i(TAG_CAR, "createCar car_service is already ready, took (ms): %d",
                        car.timeSinceCreateMillis());
                car.notifyCarReadyOnMainThread(binderService);
                return car;
            }

            car.registerServiceListenerIfNotRegistered();
            IBinder serviceBinder = car.waitForCarServiceReady(waitTimeoutMs);

            if (serviceBinder == null) {
                Slog.w(TAG_CAR,
                        "createCar (waitTimeoutMs=" + waitTimeoutMs
                        + ") car_service not ready, took (ms):"
                        + car.timeSinceCreateMillis());
                if (statusChangeListener == null) {
                    return null;
                }
                // Return a car instance that is not connected to the car service.
                return car;
            }

            Slog.i(TAG_CAR, "createCar (waitTimeoutMs=" + waitTimeoutMs
                    + ") connected to car_service, took (ms): "
                    + car.timeSinceCreateMillis());
            car.notifyCarReadyOnMainThread(serviceBinder);
            return car;
        }

        // Legacy createCar implementation.
        private @Nullable Car createCarInternalLegacy(Context context, @Nullable Handler handler) {
            Car car = null;
            IBinder service = null;
            boolean started = false;
            int retryCount = 0;
            while (true) {
                service = mDeps.serviceManager().getService(CAR_SERVICE_BINDER_SERVICE_NAME);
                if (car == null) {
                    // service can be still null. The constructor is safe for null service.
                    car = new Car(context, ICar.Stub.asInterface(service),
                            null /*serviceConnectionListener*/, null /*statusChangeListener*/,
                            handler, mDeps);
                }
                if (service != null) {
                    if (!started) {  // specialization for most common case.
                        // Do this to crash client when car service crashes.
                        car.startCarService();
                        return car;
                    }
                    break;
                }
                if (!started) {
                    car.startCarService();
                    started = true;
                }
                retryCount++;
                if (retryCount > CAR_SERVICE_BINDER_POLLING_MAX_RETRY) {
                    Slog.e(TAG_CAR, "cannot get car_service, waited for car service (ms):"
                                    + CAR_SERVICE_BINDER_POLLING_INTERVAL_MS
                                    * CAR_SERVICE_BINDER_POLLING_MAX_RETRY,
                            new RuntimeException());
                    return null;
                }
                try {
                    Thread.sleep(CAR_SERVICE_BINDER_POLLING_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Slog.e(CarLibLog.TAG_CAR, "interrupted while waiting for car_service",
                            new RuntimeException());
                    return null;
                }
            }
            // Can be accessed from mServiceConnectionListener in main thread.
            synchronized (car.mLock) {
                if (car.mService == null) {
                    car.mService = ICar.Stub.asInterface(service);
                    Slog.w(TAG_CAR,
                            "waited for car_service (ms):"
                                    + CAR_SERVICE_BINDER_POLLING_INTERVAL_MS * retryCount,
                            new RuntimeException());
                }
                car.mConnectionState = STATE_CONNECTED;
            }
            return car;
        }

        // Legacy createCar implementation.
        private Car createCarInternalLegacy(@NonNull Context context,
                @Nullable Handler handler, long waitTimeoutMs,
                @NonNull CarServiceLifecycleListener statusChangeListener) {
            Car car = null;
            IBinder service = null;
            boolean started = false;
            int retryCount = 0;
            long maxRetryCount = 0;
            if (waitTimeoutMs > 0) {
                maxRetryCount = waitTimeoutMs / CAR_SERVICE_BINDER_POLLING_INTERVAL_MS;
                // at least wait once if it is positive value.
                if (maxRetryCount == 0) {
                    maxRetryCount = 1;
                }
            }
            boolean isMainThread = Looper.myLooper() == Looper.getMainLooper();
            while (true) {
                service = mDeps.serviceManager().getService(CAR_SERVICE_BINDER_SERVICE_NAME);
                if (car == null) {
                    // service can be still null. The constructor is safe for null service.
                    car = new Car(context, ICar.Stub.asInterface(service), null,
                            statusChangeListener, handler, mDeps);
                }
                if (service != null) {
                    // specialization for most common case : car service already ready
                    if (!started) {
                        car.dispatchCarReadyToMainThread(isMainThread);
                        // Needs this for CarServiceLifecycleListener. Note that ServiceConnection
                        // will skip the callback as valid mService is set already.
                        car.startCarService();
                        return car;
                    }
                    // service available after starting.
                    break;
                }
                if (!started) {
                    car.startCarService();
                    started = true;
                }
                retryCount++;
                if (waitTimeoutMs < 0 && retryCount >= CAR_SERVICE_BINDER_POLLING_MAX_RETRY
                        && retryCount % CAR_SERVICE_BINDER_POLLING_MAX_RETRY == 0) {
                    // Log warning if car service is not alive even for waiting forever case.
                    Slog.w(TAG_CAR, "car_service not ready, waited for car service (ms):"
                                    + retryCount * CAR_SERVICE_BINDER_POLLING_INTERVAL_MS,
                            new RuntimeException());
                } else if (waitTimeoutMs >= 0 && retryCount > maxRetryCount) {
                    if (waitTimeoutMs > 0) {
                        Slog.w(TAG_CAR, "car_service not ready, waited for car service (ms):"
                                        + waitTimeoutMs,
                                new RuntimeException());
                    }
                    return car;
                }

                try {
                    Thread.sleep(CAR_SERVICE_BINDER_POLLING_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Slog.w(TAG_CAR, "interrupted", new RuntimeException());
                    return car;
                }
            }
            // Can be accessed from mServiceConnectionListener in main thread.
            synchronized (car.mLock) {
                Slog.w(TAG_CAR,
                        "waited for car_service (ms):"
                                + retryCount * CAR_SERVICE_BINDER_POLLING_INTERVAL_MS,
                        new RuntimeException());
                // ServiceConnection has handled everything.
                if (car.mService != null) {
                    return car;
                }
                // mService check in ServiceConnection prevents calling
                // onLifecycleChanged. So onLifecycleChanged should be called explicitly
                // but do it outside lock.
                car.mService = ICar.Stub.asInterface(service);
                car.mConnectionState = STATE_CONNECTED;
            }
            Slog.i(TAG_CAR, "createCar car_service is ready, took (ms): "
                    + car.timeSinceCreateMillis());
            car.dispatchCarReadyToMainThread(isMainThread);
            return car;
        }
    }

    private long timeSinceCreateMillis() {
        return SystemClock.uptimeMillis() - mCreateUptimeMillis;
    }

    private void registerServiceListenerIfNotRegistered() {
        synchronized (mLock) {
            if (mServiceListenerRegistered) {
                return;
            }
            try {
                mDeps.serviceManager().registerForNotifications(CAR_SERVICE_BINDER_SERVICE_NAME,
                        mServiceRegistrationCallback);
            } catch (RemoteException e) {
                Slog.e(TAG_CAR, "failed to call ServiceManager.registerForNotifications", e);
                return;
            }
            mServiceListenerRegistered = true;
            return;
        }
    }

    @GuardedBy("mLock")
    private void waitForCarServiceBinderNoTimeoutLocked() throws InterruptedException {
        // First wait for 5s.
        waitForCarServiceBinderLocked(CAR_SERVICE_REGISTRATION_TIMEOUT_MS);
        if (mCarServiceBinder != null) {
            return;
        }
        // Log warning if car service is not alive even for waiting forever case.
        Slog.w(TAG_CAR,
                "createCar (wait indefinitely) still cannot get car_service after "
                + CAR_SERVICE_REGISTRATION_TIMEOUT_MS + "ms");
        // If we still cannot get car service, then wait forever.
        while (mCarServiceBinder == null) {
            // await in a loop to prevent spurious wakeup.
            mLock.wait();
        }
    }

    @GuardedBy("mLock")
    private void waitForCarServiceBinderLocked(long waitTimeoutMs) throws InterruptedException {
        long deadlineMillis = SystemClock.uptimeMillis() + waitTimeoutMs;
        boolean stillWaiting = true;
        while (mCarServiceBinder == null) {
            long uptimeMillis = SystemClock.uptimeMillis();
            if (uptimeMillis >= deadlineMillis) {
                break;
            }
            Slog.w(TAG_CAR, "wait: " + (deadlineMillis - uptimeMillis));
            mLock.wait(deadlineMillis - uptimeMillis);
            Slog.w(TAG_CAR, "after wait");
        }
    }

    private @Nullable IBinder waitForCarServiceReady(long waitTimeoutMs) {
        if (waitTimeoutMs == 0) {
            return null;
        }

        IBinder serviceBinder;
        synchronized (mLock) {
            mWaiting = true;
            try {
                if (waitTimeoutMs < 0) {
                    waitForCarServiceBinderNoTimeoutLocked();
                } else {
                    waitForCarServiceBinderLocked(waitTimeoutMs);
                }
                serviceBinder = mCarServiceBinder;
            } catch (InterruptedException e) {
                Slog.e(TAG_CAR, "Interrupted while waiting for car_service");
                Thread.currentThread().interrupt();
                return null;
            } finally {
                mWaiting = false;
            }
            if (serviceBinder == null) {
                // Cannot get car service binder before timeout.
                return null;
            }
            setCarServiceLocked(serviceBinder);
        }
        return serviceBinder;
    }

    private void setCarService(IBinder carServiceBinder) {
        synchronized (mLock) {
            setCarServiceLocked(carServiceBinder);
        }
    }

    @GuardedBy("mLock")
    private void setCarServiceLocked(IBinder carServiceBinder) {
        ICar newService = ICar.Stub.asInterface(carServiceBinder);
        if (newService == null) {
            Slogf.wtf(TAG_CAR, "null binder service", new RuntimeException());
            return;  // should not happen.
        }
        mConnectionState = STATE_CONNECTED;
        mService = newService;
        try {
            carServiceBinder.linkToDeath(mDeathRecipient, /* flags= */ 0);
        } catch (RemoteException e) {
            Slog.e(TAG_CAR, "Failed to call linkToDeath on car service binder, will not receive "
                    + "callback if car service crashes", e);
        }
    }

    private void notifyCarReady(IBinder serviceBinder) {
        if (mStatusChangeCallback != null) {
            mStatusChangeCallback.onLifecycleChanged(/* car= */ this, /* ready= */ true);
        } else if (mServiceConnectionListenerClient != null) {
            mServiceConnectionListenerClient.onServiceConnected(
                    new ComponentName(CAR_SERVICE_PACKAGE, CAR_SERVICE_CLASS), serviceBinder);
        }
    }

    private void notifyCarDisconnected() {
        Slog.i(TAG_CAR, "notify car service disconnected");
        if (mStatusChangeCallback != null) {
            mStatusChangeCallback.onLifecycleChanged(Car.this, /* ready= */ false);
        } else if (mServiceConnectionListenerClient != null) {
            mServiceConnectionListenerClient.onServiceDisconnected(
                    new ComponentName(CAR_SERVICE_PACKAGE, CAR_SERVICE_CLASS));
        } else {
            // This client does not handle car service restart, so should be terminated.
            finishClient();
        }
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private static void assertNonNullContext(Context context) {
        Objects.requireNonNull(context);
        if (context instanceof ContextWrapper
                && ((ContextWrapper) context).getBaseContext() == null) {
            throw new NullPointerException(
                    "ContextWrapper with null base passed as Context, forgot to set base Context?");
        }
    }

    private void dispatchToMainThread(boolean isMainThread, Runnable runnable) {
        if (isMainThread) {
            runnable.run();
        } else {
            // should dispatch to main thread.
            mMainThreadEventHandler.post(runnable);
        }
    }

    private void notifyCarReadyOnMainThread(IBinder serviceBinder) {
        dispatchToMainThread(isMainThread(), () -> notifyCarReady(serviceBinder));
    }

    private void dispatchCarReadyToMainThread(boolean isMainThread) {
        dispatchToMainThread(isMainThread,
                () -> mStatusChangeCallback.onLifecycleChanged(/* car= */ this, /* ready= */ true));
    }

    private void setBinderAndNotifyReady(IBinder binder) {
        setCarService(binder);
        Slog.i(TAG_CAR, "car_service ready on main thread, Time between Car object creation"
                + " and car_service connected (ms): " + timeSinceCreateMillis());
        notifyCarReady(binder);
    }

    private Car(Context context, @Nullable ICar service,
            @Nullable ServiceConnection serviceConnectionListener,
            @Nullable CarServiceLifecycleListener statusChangeListener,
            @Nullable Handler handler, Deps deps) {
        mDeps = deps;
        mCreateUptimeMillis = SystemClock.uptimeMillis();
        mContext = context;
        mEventHandler = determineEventHandler(handler);
        mMainThreadEventHandler = determineMainThreadEventHandler(mEventHandler);

        mService = service;
        if (service != null) {
            mConnectionState = STATE_CONNECTED;
        } else {
            mConnectionState = STATE_DISCONNECTED;
        }
        mServiceConnectionListenerClient = serviceConnectionListener;
        mStatusChangeCallback = statusChangeListener;
        // Store construction stack so that client can get help when it crashes when car service
        // crashes.
        if (serviceConnectionListener == null && statusChangeListener == null) {
            mConstructionStack = new RuntimeException();
        } else {
            mConstructionStack = null;
        }
    }

    /**
     * Car constructor when ICar binder is already available. The binder can be null.
     * @hide
     */
    public Car(Context context, @Nullable ICar service, @Nullable Handler handler) {
        this(context, service, null /*serviceConnectionListener*/, null /*statusChangeListener*/,
                handler, SYSTEM_DEPS);
    }

    private static Handler determineMainThreadEventHandler(Handler eventHandler) {
        Looper mainLooper = Looper.getMainLooper();
        return (eventHandler.getLooper() == mainLooper) ? eventHandler : new Handler(mainLooper);
    }

    private static Handler determineEventHandler(@Nullable Handler eventHandler) {
        Handler handler = eventHandler;

        if (handler == null) {
            Looper looper = Looper.getMainLooper();
            handler = new Handler(looper);
        }
        return handler;
    }

    /**
     * @deprecated this method is not need if this object is created via
     * {@link #createCar(Context, Handler)}.
     */
    @Deprecated
    public void connect() throws IllegalStateException {
        IBinder carServiceBinder = null;
        synchronized (mLock) {
            if (mConnectionState != STATE_DISCONNECTED) {
                throw new IllegalStateException("already connected or connecting");
            }
            mClientRequestDisconnect = false;
            mConnectionState = STATE_CONNECTING;
            if (!Flags.createCarUseNotifications()) {
                // Ideally this should not be inside the lock, but legacy logic run this inside
                // the lock.
                startCarService();
                return;
            }

            carServiceBinder = mCarServiceBinder;
        }

        if (carServiceBinder != null) {
            // If we already have a car service binder ready. This means this is a reconnect
            // after disconnect or car service crash. And car service is already ready
            // before connecting.

            // Need this to make carServiceBinder final.
            IBinder binder = carServiceBinder;
            dispatchToMainThread(isMainThread(), () -> setBinderAndNotifyReady(binder));
        }
        registerServiceListenerIfNotRegistered();
    }

    @GuardedBy("mLock")
    private void handleCarDisconnectLocked() {
        if (mConnectionState == STATE_DISCONNECTED) {
            // can happen when client calls disconnect with onServiceDisconnected already
            // called.
            return;
        }
        mEventHandler.removeCallbacks(mConnectionRetryRunnable);
        mMainThreadEventHandler.removeCallbacks(mConnectionRetryFailedRunnable);
        mConnectionRetryCount = 0;
        tearDownCarManagersLocked();
        mService = null;
        mConnectionState = STATE_DISCONNECTED;
    }

    /**
     * Disconnect from car service. This can be called while disconnected. Once disconnect is
     * called, all Car*Managers from this instance become invalid, and
     * {@link Car#getCarManager(String)} or {@link Car#getCarManager(Class<T>)} will return a
     * different instance if it is connected again.
     */
    public void disconnect() {
        synchronized (mLock) {
            mClientRequestDisconnect = true;
            handleCarDisconnectLocked();
            if (mServiceBound) {
                mContext.unbindService(mServiceConnectionListener);
                mServiceBound = false;
            }
        }
    }

    @Override
    @SuppressWarnings("GenericException")
    protected void finalize() throws Throwable {
        if (mLock == null) {
            // There's no point of trying anything - even logging - when the object state is already
            // cleared
            super.finalize();
            return;
        }
        try {
            Slog.v(TAG_CAR, "Calling disconnect() on finalize()");
            disconnect();
        } finally {
            super.finalize();
        }
    }

    /**
     * Tells if it is connected to the service or not. This will return false if it is still
     * connecting.
     * @return {@code true} if service is connected
     */
    public boolean isConnected() {
        synchronized (mLock) {
            return mService != null;
        }
    }

    /**
     * Tells if this instance is already connecting to car service or not.
     * @return {@code true} if instance is connecting to a service
     */
    public boolean isConnecting() {
        synchronized (mLock) {
            return mConnectionState == STATE_CONNECTING;
        }
    }

    /** @hide */
    @VisibleForTesting
    public ServiceConnection getServiceConnectionListener() {
        return mServiceConnectionListener;
    }

    /**
     * Get car specific service manager as in {@link Context#getSystemService(String)}. Returned
     * {@link Object} should be type-casted to the desired service manager.
     *
     * <p>For example, to get the manager for sensor service,
     * <code>
     * CarSensorManager carSensorManager = (CarSensorManager) car.getCarManager(Car.SENSOR_SERVICE);
     * </code>
     *
     * <p>For getting {@link Car#AUDIO_SERVICE}, this call might be blocked on car audio service
     * initialization if the client calls this early during the boot process.
     *
     * @param serviceName Name of service that should be created like {@link #SENSOR_SERVICE}.
     * @return Matching service manager or null if there is no such service.
     */
    @Nullable
    public Object getCarManager(String serviceName) {
        CarManagerBase manager;
        synchronized (mLock) {
            if (mService == null) {
                Slog.w(TAG_CAR, "getCarManager not working while car service not ready");
                return null;
            }
            manager = mServiceMap.get(serviceName);
            if (manager == null) {
                try {
                    IBinder binder = mService.getCarService(serviceName);
                    if (binder == null) {
                        Slog.w(TAG_CAR, "getCarManager could not get binder for service:"
                                + serviceName);
                        return null;
                    }
                    manager = createCarManagerLocked(serviceName, binder);
                    if (manager == null) {
                        Slog.w(TAG_CAR, "getCarManager could not create manager for service:"
                                        + serviceName);
                        return null;
                    }
                    mServiceMap.put(serviceName, manager);
                } catch (RemoteException e) {
                    handleRemoteExceptionFromCarService(e);
                }
            }
        }
        return manager;
    }

    /**
     * Get car specific service manager by class as in {@link Context#getSystemService(Class<T>)}.
     * Returns the desired service. No type casting is needed.
     *
     * <p>For example, to get the manager for sensor service,
     * <code>CarSensorManager carSensorManager = car.getCarManager(CarSensorManager.class);</code>
     *
     * <p>For getting {@link CarAudioManager}, this call might be blocked on car audio service
     * initialization if the client calls this early during the boot process.
     *
     * @param serviceClass The class of the desired service.
     * @return Matching service manager or {@code null} if there is no such service.
     */
    @Nullable
    public <T> T getCarManager(@NonNull Class<T> serviceClass) {
        String serviceName = CAR_SERVICE_NAMES.get(serviceClass);
        return serviceName != null ? (T) getCarManager(serviceName) : null;
    }

    /**
     * @return the type of currently connected car.
     *
     * @deprecated connection type will be always {@link #CONNECTION_TYPE_EMBEDDED}
     */
    @ConnectionType
    @Deprecated
    public int getCarConnectionType() {
        return CONNECTION_TYPE_EMBEDDED;
    }

    /**
     * Checks if {@code featureName} is enabled in this car.
     *
     * <p>For optional features, this can return false if the car cannot support it. Optional
     * features should be used only when they are supported.</p>
     *
     * <p>For mandatory features, this will always return true.
     */
    public boolean isFeatureEnabled(@NonNull String featureName) {
        ICar service;
        synchronized (mLock) {
            if (mService == null) {
                return false;
            }
            service = mService;
        }
        return mFeatures.isFeatureEnabled(service, featureName);
    }

    /**
     * Enables the requested car feature. It becomes no-op if the feature is already enabled. The
     * change take effects after reboot.
     *
     * @return true if the feature is enabled or was enabled before.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(PERMISSION_CONTROL_CAR_FEATURES)
    @FeaturerRequestEnum
    public int enableFeature(@NonNull String featureName) {
        ICar service;
        synchronized (mLock) {
            if (mService == null) {
                return FEATURE_REQUEST_NOT_EXISTING;
            }
            service = mService;
        }
        try {
            return service.enableFeature(featureName);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, FEATURE_REQUEST_NOT_EXISTING);
        }
    }

    /**
     * Disables the requested car feature. It becomes no-op if the feature is already disabled. The
     * change take effects after reboot.
     *
     * @return true if the request succeeds or if it was already disabled.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(PERMISSION_CONTROL_CAR_FEATURES)
    @FeaturerRequestEnum
    public int disableFeature(@NonNull String featureName) {
        ICar service;
        synchronized (mLock) {
            if (mService == null) {
                return FEATURE_REQUEST_NOT_EXISTING;
            }
            service = mService;
        }
        try {
            return service.disableFeature(featureName);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, FEATURE_REQUEST_NOT_EXISTING);
        }
    }

    /**
     * Returns all =enabled features at the moment including mandatory, optional, and
     * experimental features.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(PERMISSION_CONTROL_CAR_FEATURES)
    @NonNull public List<String> getAllEnabledFeatures() {
        ICar service;
        synchronized (mLock) {
            if (mService == null) {
                return Collections.EMPTY_LIST;
            }
            service = mService;
        }
        try {
            return service.getAllEnabledFeatures();
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, Collections.EMPTY_LIST);
        }
    }

    /**
     * Returns the list of disabled features which are not effective yet. Those features will be
     * disabled when system restarts later.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(PERMISSION_CONTROL_CAR_FEATURES)
    @NonNull public List<String> getAllPendingDisabledFeatures() {
        ICar service;
        synchronized (mLock) {
            if (mService == null) {
                return Collections.EMPTY_LIST;
            }
            service = mService;
        }
        try {
            return service.getAllPendingDisabledFeatures();
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, Collections.EMPTY_LIST);
        }
    }

    /**
     * Returns the list of enabled features which are not effective yet. Those features will be
     * enabled when system restarts later.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(PERMISSION_CONTROL_CAR_FEATURES)
    @NonNull public List<String> getAllPendingEnabledFeatures() {
        ICar service;
        synchronized (mLock) {
            if (mService == null) {
                return Collections.EMPTY_LIST;
            }
            service = mService;
        }
        try {
            return service.getAllPendingEnabledFeatures();
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, Collections.EMPTY_LIST);
        }
    }

    /** @hide */
    @VisibleForHiddenApiCheck
    public Context getContext() {
        return mContext;
    }

    /** @hide */
    @VisibleForTesting
    public Handler getEventHandler() {
        return mEventHandler;
    }

    /** @hide */
    @VisibleForTesting
    public <T> T handleRemoteExceptionFromCarService(RemoteException e, T returnValue) {
        handleRemoteExceptionFromCarService(e);
        return returnValue;
    }

    /** @hide */
    @VisibleForHiddenApiCheck
    public void handleRemoteExceptionFromCarService(RemoteException e) {
        if (e instanceof TransactionTooLargeException) {
            Slog.w(TAG_CAR, "Car service threw TransactionTooLargeException", e);
            throw new CarTransactionException(e, "Car service threw TransactionTooLargeException");
        } else {
            Slog.w(TAG_CAR, "Car service has crashed", e);
        }
    }

    private void finishClient() {
        if (mContext == null) {
            throw new IllegalStateException("Car service has crashed, null Context");
        }
        if (mContext instanceof Activity) {
            Activity activity = (Activity) mContext;
            if (!activity.isFinishing()) {
                Slog.w(TAG_CAR,
                        "Car service crashed, client not handling it, finish Activity, created "
                                + "from " + mConstructionStack);
                activity.finish();
            }
            return;
        } else if (mContext instanceof Service) {
            Service service = (Service) mContext;
            killClient(service.getPackageName() + "," + service.getClass().getSimpleName());
        } else {
            killClient(/* clientInfo= */ null);
        }
    }

    private void killClient(@Nullable String clientInfo) {
        Slog.w(TAG_CAR, "**Car service has crashed. Client(" + clientInfo + ") is not handling it."
                        + " Client should use Car.createCar(..., CarServiceLifecycleListener, .."
                        + ".) to handle it properly. Check printed callstack to check where other "
                        + "version of Car.createCar() was called. Killing the client process**",
                mConstructionStack);
        mDeps.process().killProcess(mDeps.process().myPid());
    }

    /** @hide */
    @VisibleForHiddenApiCheck
    public static <T> T handleRemoteExceptionFromCarService(Service service, RemoteException e,
            T returnValue) {
        handleRemoteExceptionFromCarService(service, e);
        return returnValue;
    }

    /** @hide */
    @VisibleForHiddenApiCheck
    public static  void handleRemoteExceptionFromCarService(Service service, RemoteException e) {
        if (e instanceof TransactionTooLargeException) {
            Slog.w(TAG_CAR, "Car service threw TransactionTooLargeException, client:"
                    + service.getPackageName() + ","
                    + service.getClass().getSimpleName(), e);
            throw new CarTransactionException(e, "Car service threw TransactionTooLargeException, "
                + "client: %s, %s", service.getPackageName(), service.getClass().getSimpleName());
        } else {
            Slog.w(TAG_CAR, "Car service has crashed, client:"
                    + service.getPackageName() + ","
                    + service.getClass().getSimpleName(), e);
            service.stopSelf();
        }
    }

    @Nullable
    private CarManagerBase createCarManagerLocked(String serviceName, IBinder binder) {
        CarManagerBase manager = null;
        switch (serviceName) {
            case AUDIO_SERVICE:
                manager = new CarAudioManager(this, binder);
                break;
            case SENSOR_SERVICE:
                manager = new CarSensorManager(this, binder);
                break;
            case INFO_SERVICE:
                manager = new CarInfoManager(this, binder);
                break;
            case APP_FOCUS_SERVICE:
                manager = new CarAppFocusManager(this, binder);
                break;
            case PACKAGE_SERVICE:
                manager = new CarPackageManager(this, binder);
                break;
            case CAR_OCCUPANT_ZONE_SERVICE:
                manager = new CarOccupantZoneManager(this, binder);
                break;
            case CAR_NAVIGATION_SERVICE:
                manager = new CarNavigationStatusManager(this, binder);
                break;
            case CABIN_SERVICE:
                manager = new CarCabinManager(this, binder);
                break;
            case DIAGNOSTIC_SERVICE:
                manager = new CarDiagnosticManager(this, binder);
                break;
            case HVAC_SERVICE:
                manager = new CarHvacManager(this, binder);
                break;
            case POWER_SERVICE:
                manager = new CarPowerManager(this, binder);
                break;
            case PROJECTION_SERVICE:
                manager = new CarProjectionManager(this, binder);
                break;
            case PROPERTY_SERVICE:
                manager = new CarPropertyManager(this, ICarProperty.Stub.asInterface(binder));
                break;
            case VENDOR_EXTENSION_SERVICE:
                manager = new CarVendorExtensionManager(this, binder);
                break;
            case CAR_INSTRUMENT_CLUSTER_SERVICE:
                manager = new CarInstrumentClusterManager(this, binder);
                break;
            case TEST_SERVICE:
                /* CarTestManager exist in static library. So instead of constructing it here,
                 * only pass binder wrapper so that CarTestManager can be constructed outside. */
                manager = new CarTestManager(this, binder);
                break;
            case VEHICLE_MAP_SERVICE:
                manager = new VmsClientManager(this, binder);
                break;
            case VMS_SUBSCRIBER_SERVICE:
                manager = VmsSubscriberManager.wrap(this,
                        (VmsClientManager) getCarManager(VEHICLE_MAP_SERVICE));
                break;
            case STORAGE_MONITORING_SERVICE:
                manager = new CarStorageMonitoringManager(this, binder);
                break;
            case CAR_DRIVING_STATE_SERVICE:
                manager = new CarDrivingStateManager(this, binder);
                break;
            case CAR_UX_RESTRICTION_SERVICE:
                manager = new CarUxRestrictionsManager(this, binder);
                break;
            case OCCUPANT_AWARENESS_SERVICE:
                manager = new OccupantAwarenessManager(this, binder);
                break;
            case CAR_MEDIA_SERVICE:
                manager = new CarMediaManager(this, binder);
                break;
            case CAR_BUGREPORT_SERVICE:
                manager = new CarBugreportManager(this, binder);
                break;
            case CAR_USER_SERVICE:
                manager = new CarUserManager(this, binder);
                break;
            case CAR_WATCHDOG_SERVICE:
                manager = new CarWatchdogManager(this, binder);
                break;
            case CAR_INPUT_SERVICE:
                manager = new CarInputManager(this, binder);
                break;
            case CAR_DEVICE_POLICY_SERVICE:
                manager = new CarDevicePolicyManager(this, binder);
                break;
            case CLUSTER_HOME_SERVICE:
                manager = new ClusterHomeManager(this, binder);
                break;
            case CAR_EVS_SERVICE:
                manager = new CarEvsManager(this, binder, /* featureFlags= */ null);
                break;
            case CAR_TELEMETRY_SERVICE:
                manager = new CarTelemetryManager(this, binder);
                break;
            case CAR_ACTIVITY_SERVICE:
                manager = new CarActivityManager(this, binder);
                break;
            case CAR_PERFORMANCE_SERVICE:
                manager = new CarPerformanceManager(this, binder);
                break;
            case CAR_REMOTE_ACCESS_SERVICE:
                manager = new CarRemoteAccessManager(this, binder);
                break;
            case CAR_OCCUPANT_CONNECTION_SERVICE:
                manager = new CarOccupantConnectionManager(this, binder);
                break;
            case CAR_REMOTE_DEVICE_SERVICE:
                manager = new CarRemoteDeviceManager(this, binder);
                break;
            default:
                // Experimental or non-existing
                if (Flags.displayCompatibility()) {
                    if (serviceName.equals(CAR_DISPLAY_COMPAT_SERVICE)) {
                        manager =  new CarDisplayCompatManager(this, binder);
                        break;
                    }
                }
                if (Flags.persistApSettings()) {
                    if (serviceName.equals(CAR_WIFI_SERVICE)) {
                        manager = new CarWifiManager(this, binder);
                        break;
                    }
                }
                if (Flags.carPropertySimulation()) {
                    if (serviceName.equals(CAR_PROPERTY_SIMULATION_SERVICE)) {
                        manager = new CarPropertySimulationManager(this, binder);
                        break;
                    }
                }
                String className = null;
                try {
                    synchronized (mLock) {
                        className = mService.getCarManagerClassForFeature(serviceName);
                    }
                } catch (RemoteException e) {
                    handleRemoteExceptionFromCarService(e);
                    return null;
                }
                if (className == null) {
                    Slog.e(TAG_CAR, "Cannot construct CarManager for service:" + serviceName
                            + " : no class defined");
                    return null;
                }
                manager = constructCarManager(className, binder);
                break;
        }
        return manager;
    }

    private CarManagerBase constructCarManager(String className, IBinder binder) {
        try {
            // Should use class loader for the Context as class loader for car api does not
            // see the class.
            ClassLoader loader = mContext.getClassLoader();
            Class managerClass = loader.loadClass(className);
            Constructor constructor = managerClass.getConstructor(Car.class, IBinder.class);
            CarManagerBase manager = (CarManagerBase) constructor.newInstance(this, binder);
            return manager;
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchMethodException
                | InstantiationException | InvocationTargetException e) {
            Slog.e(TAG_CAR, "Cannot construct CarManager, class:" + className, e);
            return null;
        }
    }

    private void startCarService() {
        Intent intent = new Intent();
        intent.setPackage(CAR_SERVICE_PACKAGE);
        intent.setAction(Car.CAR_SERVICE_INTERFACE_NAME);
        boolean bound = mContext.bindService(intent, mServiceConnectionListener,
                Context.BIND_AUTO_CREATE);
        synchronized (mLock) {
            if (!bound) {
                mConnectionRetryCount++;
                if (mConnectionRetryCount > mDeps.carServiceBindMaxRetry()) {
                    Slog.w(TAG_CAR, "cannot bind to car service after max retry");
                    mMainThreadEventHandler.post(mConnectionRetryFailedRunnable);
                } else {
                    mEventHandler.postDelayed(mConnectionRetryRunnable,
                            mDeps.carServiceBindRetryIntervalMs());
                }
            } else {
                mEventHandler.removeCallbacks(mConnectionRetryRunnable);
                mMainThreadEventHandler.removeCallbacks(mConnectionRetryFailedRunnable);
                mConnectionRetryCount = 0;
                mServiceBound = true;
            }
        }
    }

    @GuardedBy("mLock")
    private void tearDownCarManagersLocked() {
        // All disconnected handling should be only doing its internal cleanup.
        for (CarManagerBase manager : mServiceMap.values()) {
            manager.onCarDisconnected();
        }
        mServiceMap.clear();
    }
}
