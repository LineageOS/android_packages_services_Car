/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.car.wifi;

import static android.car.settings.CarSettings.Global.ENABLE_PERSISTENT_TETHERING;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_POST_UNLOCKED;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_STOPPED;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.Car;
import android.car.builtin.util.Slogf;
import android.car.feature.FeatureFlags;
import android.car.feature.FeatureFlagsImpl;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.ICarPowerStateListener;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.car.user.UserLifecycleEventFilter;
import android.car.wifi.ICarWifi;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.TetheringManager;
import android.net.TetheringManager.StartTetheringCallback;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.SoftApCallback;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.proto.ProtoOutputStream;

import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.R;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.power.CarPowerManagementService;
import com.android.car.user.CarUserService;
import com.android.internal.annotations.GuardedBy;

/**
 * CarWifiService manages Wi-Fi functionality.
 */
public final class CarWifiService extends ICarWifi.Stub implements CarServiceBase {
    private static final String TAG = CarLog.tagFor(CarWifiService.class);
    private static final String SHARED_PREF_NAME = "com.android.car.wifi.car_wifi_service";
    private static final String KEY_PERSIST_TETHERING_ENABLED_LAST =
            "persist_tethering_enabled_last";

    private final Object mLock = new Object();
    private final Context mContext;
    private final boolean mIsPersistTetheringCapabilitiesEnabled;
    private final WifiManager mWifiManager;
    private final TetheringManager mTetheringManager;
    private final CarPowerManagementService mCarPowerManagementService;
    private final CarUserService mCarUserService;
    private final String mHandlerThreadName = getClass().getSimpleName();
    private final HandlerThread mHandlerThread = CarServiceUtils.getHandlerThread(
            mHandlerThreadName);
    private final Handler mHandler = new Handler(mHandlerThread.getLooper());
    private final FeatureFlags mFeatureFlags = new FeatureFlagsImpl();

    private final UserLifecycleListener mUserLifeCycleListener =
            event -> {
                Slogf.i(
                        TAG,
                        "UserLifecycleEvent received. Type: %d, User: %d",
                        event.getEventType(),
                        event.getUserId());
                if (event.getUserHandle().isSystem()) {
                    return;
                }

                // This logic manages the mIsHandlingUserSwitchOrStop flag to prevent
                // SoftApCallbacks from modifying the persisted hotspot state during a user switch.
                // Android 17+ tears down Wi-Fi/SoftAP interfaces during user transitions
                // (b/390257834).
                // We want to ignore the temporary WIFI_AP_STATE_DISABLED event caused by this
                // teardown.
                switch (event.getEventType()) {
                    case USER_LIFECYCLE_EVENT_TYPE_STOPPED, USER_LIFECYCLE_EVENT_TYPE_SWITCHING -> {
                        // Set the flag when a user switch is starting or an old user is stopping.
                        // This indicates that any imminent SoftAP state changes are
                        // system-initiated
                        // and not reflective of the desired persistent state.
                        Slogf.d(TAG, "User switch/stop detected, ignoring SoftAP state changes.");
                        synchronized (mLock) {
                            mIsHandlingUserSwitchOrStop = true;
                        }
                    }
                    case USER_LIFECYCLE_EVENT_TYPE_POST_UNLOCKED -> {
                        // The new user is now unlocked and the system's user switch-related
                        // Wi-Fi reset should be complete. Clear the flag to resume normal
                        // SoftAP state monitoring.
                        Slogf.d(TAG, "User POST_UNLOCKED, resuming SoftAP state handling.");
                        synchronized (mLock) {
                            mIsHandlingUserSwitchOrStop = false;
                        }

                        // Attempt to restore the hotspot to its desired state for the new session.
                        startTethering();
                    }
                    default -> {
                        // Other events are not relevant to this specific state handling.
                    }
                }
            };

    private final ICarPowerStateListener mCarPowerStateListener =
            new ICarPowerStateListener.Stub() {
                @Override
                public void onStateChanged(int state, long expirationTimeMs) {
                    if (state == CarPowerManager.STATE_ON) {
                        onStateOn();
                    }
                }
            };

    private final SoftApCallback mSoftApCallback =
            new SoftApCallback() {
                @Override
                public void onStateChanged(int state, int failureReason) {
                    synchronized (mLock) {
                        // If a user switch/stop is in progress, ignore callbacks.
                        // This prevents the temporary disabling of the AP by the system
                        // from overwriting the desired persisted state in SharedPreferences.
                        if (mIsHandlingUserSwitchOrStop) {
                            return;
                        }
                    }

                    switch (state) {
                        case WIFI_AP_STATE_ENABLED -> {
                            Slogf.i(TAG, "AP enabled successfully");
                            synchronized (mLock) {
                                if (mSharedPreferences != null) {
                                    Slogf.i(TAG,
                                            "WIFI_AP_STATE_ENABLED received, saving state in "
                                                    + "SharedPreferences store");
                                    mSharedPreferences
                                            .edit()
                                            .putBoolean(
                                                    KEY_PERSIST_TETHERING_ENABLED_LAST, /* value= */
                                                    true)
                                            .apply();
                                }
                            }

                            // If the setting is enabled, tethering sessions should remain on even
                            // if no devices are connected to it.
                            if (mIsPersistTetheringCapabilitiesEnabled
                                    && isPersistTetheringSettingEnabled()) {
                                setSoftApAutoShutdownEnabled(/* enable= */ false);
                            }
                        }
                        case WIFI_AP_STATE_DISABLED -> {
                            synchronized (mLock) {
                                if (mSharedPreferences != null) {
                                    Slogf.i(TAG,
                                            "WIFI_AP_STATE_DISABLED received, saving state in "
                                                    + "SharedPreferences store");
                                    mSharedPreferences
                                            .edit()
                                            .putBoolean(
                                                    KEY_PERSIST_TETHERING_ENABLED_LAST, /* value= */
                                                    false)
                                            .apply();
                                }
                            }
                        }
                        case WIFI_AP_STATE_FAILED -> {
                            Slogf.w(TAG, "WIFI_AP_STATE_FAILED state received");
                            // FAILED state can occur during enabling OR disabling, should keep
                            // previous setting within store.
                        }
                        default -> Slogf.i(TAG, "WIFI_AP_STATE received: %d", state);
                    }
                }
            };

    private final ContentObserver mPersistTetheringObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    // If the persist tethering setting is turned off, auto shutdown must be
                    // re-enabled.
                    setSoftApAutoShutdownEnabled(!isPersistTetheringSettingEnabled());
                }
            };

    @GuardedBy("mLock")
    private SharedPreferences mSharedPreferences;

    // Flag to indicate that a user switch or stop event is being processed.
    // While true, SoftAP state changes are ignored to prevent the system's
    // temporary AP disabling from affecting the persisted state.
    @GuardedBy("mLock")
    private boolean mIsHandlingUserSwitchOrStop = false;

    public CarWifiService(Context context) {
        mContext = context;
        mIsPersistTetheringCapabilitiesEnabled = context.getResources().getBoolean(
                R.bool.config_enablePersistTetheringCapabilities);
        mWifiManager = context.getSystemService(WifiManager.class);
        mTetheringManager = context.getSystemService(TetheringManager.class);
        mCarPowerManagementService = CarLocalServices.getService(CarPowerManagementService.class);
        mCarUserService = CarLocalServices.getService(CarUserService.class);
    }

    @Override
    public void destroy() {
        try {
            CarServiceUtils.releaseHandlerThread(mHandlerThreadName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void init() {
        if (!mIsPersistTetheringCapabilitiesEnabled) {
            Slogf.w(TAG, "Persist tethering capability is not enabled");
            return;
        }

        // Listeners should only be set if there's capability (also allows for
        // tethering persisting if setting is enabled, on next boot).
        mWifiManager.registerSoftApCallback(mHandler::post, mSoftApCallback);
        mCarUserService.runOnUser0Unlock(this::onSystemUserUnlocked);
        mCarPowerManagementService.registerListener(mCarPowerStateListener);
        mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                        ENABLE_PERSISTENT_TETHERING), /* notifyForDescendants= */ false,
                mPersistTetheringObserver);
        UserLifecycleEventFilter userEventFilter =
                new UserLifecycleEventFilter.Builder()
                        .addEventType(USER_LIFECYCLE_EVENT_TYPE_POST_UNLOCKED)
                        .addEventType(USER_LIFECYCLE_EVENT_TYPE_SWITCHING)
                        .addEventType(USER_LIFECYCLE_EVENT_TYPE_STOPPED).build();
        mCarUserService.addUserLifecycleListener(userEventFilter, mUserLifeCycleListener);
    }

    @Override
    public void release() {
        if (!mIsPersistTetheringCapabilitiesEnabled) {
            Slogf.w(TAG, "Persist tethering capability is not enabled");
            return;
        }

        mWifiManager.unregisterSoftApCallback(mSoftApCallback);
        mCarPowerManagementService.unregisterListener(mCarPowerStateListener);
        mCarUserService.removeUserLifecycleListener(mUserLifeCycleListener);
        mContext.getContentResolver().unregisterContentObserver(mPersistTetheringObserver);
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dumpProto(ProtoOutputStream proto) {
        proto.write(CarWifiDumpProto.PERSIST_TETHERING_CAPABILITIES_ENABLED,
                mIsPersistTetheringCapabilitiesEnabled);
        proto.write(CarWifiDumpProto.PERSIST_TETHERING_SETTING_ENABLED,
                isPersistTetheringSettingEnabled());
        if (mWifiManager != null) {
            proto.write(CarWifiDumpProto.TETHERING_ENABLED, mWifiManager.isWifiApEnabled());
            proto.write(CarWifiDumpProto.AUTO_SHUTDOWN_ENABLED,
                    mWifiManager.getSoftApConfiguration().isAutoShutdownEnabled());
        }
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("**CarWifiService**");
        writer.println();
        writer.println("Persist Tethering");
        writer.println("mIsPersistTetheringCapabilitiesEnabled: "
                + mIsPersistTetheringCapabilitiesEnabled);
        writer.println("mIsPersistTetheringSettingEnabled: " + isPersistTetheringSettingEnabled());
        if (mWifiManager != null) {
            writer.println("Tethering enabled: " + mWifiManager.isWifiApEnabled());
            writer.println("Auto shutdown enabled: "
                    + mWifiManager.getSoftApConfiguration().isAutoShutdownEnabled());
        }
    }

    /**
     * Returns {@code true} if the persist tethering settings are able to be changed.
     */
    @Override
    public boolean canControlPersistTetheringSettings() {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_READ_PERSIST_TETHERING_SETTINGS);
        return mIsPersistTetheringCapabilitiesEnabled;
    }

    /**
     * Starts tethering if it's currently being persisted and was on last.
     * Should only be called given that the SharedPreferences store is properly initialized.
     */
    private void startTethering() {
        if (!mIsPersistTetheringCapabilitiesEnabled || !isPersistTetheringSettingEnabled()) {
            return;
        }

        if (mWifiManager.isWifiApEnabled()) {
            return;
        }

        synchronized (mLock) {
            if (mSharedPreferences == null || !mSharedPreferences.getBoolean(
                    KEY_PERSIST_TETHERING_ENABLED_LAST, /* defValue= */ false)) {
                Slogf.d(TAG, "Tethering was not enabled last");
                return;
            }
        }

        mTetheringManager.startTethering(TETHERING_WIFI, mHandler::post,
                new StartTetheringCallback() {
                    @Override
                    public void onTetheringStarted() {
                        Slogf.i(TAG, "Tethering has been successfully started");
                    }

                    @Override
                    public void onTetheringFailed(int error) {
                        Slogf.e(TAG, "Starting tethering failed: %d", error);
                    }
                });
    }

    private void onStateOn() {
        synchronized (mLock) {
            if (mSharedPreferences == null) {
                Slogf.d(TAG, "SharedPreferences store has not been initialized");
                return;
            }
        }

        // User 0 has been unlocked, SharedPreferences is initialized and accessible.
        startTethering();
    }

    private void onSystemUserUnlocked() {
        synchronized (mLock) {
            // SharedPreferences are shared among different users thus only need initialized once.
            // They should be initialized after user 0 is unlocked because SharedPreferences in
            // credential encrypted storage are not available until after user 0 is unlocked.
            mSharedPreferences = mContext.getSharedPreferences(SHARED_PREF_NAME,
                    Context.MODE_PRIVATE);
        }
    }

    private void setSoftApAutoShutdownEnabled(boolean enable) {
        SoftApConfiguration config = new SoftApConfiguration.Builder(
                mWifiManager.getSoftApConfiguration())
                .setAutoShutdownEnabled(enable)
                .build();
        mWifiManager.setSoftApConfiguration(config);
    }

    private boolean isPersistTetheringSettingEnabled() {
        return TextUtils.equals(
                "true",
                Settings.Global.getString(mContext.getContentResolver(),
                        ENABLE_PERSISTENT_TETHERING));
    }
}
