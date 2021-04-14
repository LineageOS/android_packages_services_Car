/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.power;

import static android.car.hardware.power.PowerComponent.BLUETOOTH;
import static android.car.hardware.power.PowerComponent.DISPLAY;
import static android.car.hardware.power.PowerComponent.LOCATION;
import static android.car.hardware.power.PowerComponent.NFC;
import static android.car.hardware.power.PowerComponent.VOICE_INTERACTION;
import static android.car.hardware.power.PowerComponent.WIFI;
import static android.car.hardware.power.PowerComponentUtil.FIRST_POWER_COMPONENT;
import static android.car.hardware.power.PowerComponentUtil.INVALID_POWER_COMPONENT;
import static android.car.hardware.power.PowerComponentUtil.LAST_POWER_COMPONENT;
import static android.car.hardware.power.PowerComponentUtil.powerComponentToString;
import static android.car.hardware.power.PowerComponentUtil.toPowerComponent;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.car.hardware.power.CarPowerPolicy;
import android.car.hardware.power.CarPowerPolicyFilter;
import android.car.hardware.power.PowerComponent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.car.CarLog;
import com.android.car.systeminterface.SystemInterface;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractionManagerService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Class that manages power components in the system. A power component mediator corresponding to a
 * power component is created and registered to this class. A power component mediator encapsulates
 * the function of powering on/off.
 */
@VisibleForTesting
public final class PowerComponentHandler {
    private static final String TAG = CarLog.tagFor(PowerComponentHandler.class);
    private static final String FORCED_OFF_COMPONENTS_FILENAME =
            "forced_off_components";

    private final Object mLock = new Object();
    private final Context mContext;
    private final SystemInterface mSystemInterface;
    private final AtomicFile mOffComponentsByUserFile;
    private final SparseArray<PowerComponentMediator> mPowerComponentMediators =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseBooleanArray mComponentStates =
            new SparseBooleanArray(LAST_POWER_COMPONENT - FIRST_POWER_COMPONENT + 1);
    @GuardedBy("mLock")
    private final SparseBooleanArray mUserControlledComponents = new SparseBooleanArray();
    @GuardedBy("mLock")
    private final SparseBooleanArray mLastModifiedComponents = new SparseBooleanArray();
    private final IVoiceInteractionManagerService mVoiceInteractionServiceHolder;
    private final PackageManager mPackageManager;

    @GuardedBy("mLock")
    private String mCurrentPolicyId = "";

    PowerComponentHandler(Context context, SystemInterface systemInterface) {
        this(context, systemInterface, /* voiceInteractionService= */ null,
                new AtomicFile(new File(systemInterface.getSystemCarDir(),
                        FORCED_OFF_COMPONENTS_FILENAME)));
    }

    public PowerComponentHandler(Context context, SystemInterface systemInterface,
            IVoiceInteractionManagerService voiceInteractionService,
            AtomicFile componentStateFile) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mSystemInterface = systemInterface;
        mVoiceInteractionServiceHolder = voiceInteractionService;
        mOffComponentsByUserFile = componentStateFile;
    }

    void init() {
        PowerComponentMediatorFactory factory = new PowerComponentMediatorFactory();
        synchronized (mLock) {
            readUserOffComponentsLocked();
            for (int component = FIRST_POWER_COMPONENT; component <= LAST_POWER_COMPONENT;
                    component++) {
                mComponentStates.put(component, false);
                PowerComponentMediator mediator = factory.createPowerComponent(component);
                String componentName = powerComponentToString(component);
                if (mediator == null) {
                    Slog.w(TAG, "Power component(" + componentName + ") is not valid or doesn't "
                            + "need a mediator");
                    continue;
                }
                if (!mediator.isComponentAvailable()) {
                    Slog.w(TAG, "Power component(" + componentName + ") is not available");
                    continue;
                }
                mediator.init();
                mPowerComponentMediators.put(component, mediator);
            }
        }
    }

    void release() {
        for (int i = 0; i < mPowerComponentMediators.size(); i++) {
            PowerComponentMediator mediator = mPowerComponentMediators.valueAt(i);
            mediator.release();
        }
    }

    CarPowerPolicy getAccumulatedPolicy() {
        synchronized (mLock) {
            int enabledComponentsCount = 0;
            int disabledComponentsCount = 0;
            for (int component = FIRST_POWER_COMPONENT; component <= LAST_POWER_COMPONENT;
                    component++) {
                if (mComponentStates.get(component, /* valueIfKeyNotFound= */ false)) {
                    enabledComponentsCount++;
                } else {
                    disabledComponentsCount++;
                }
            }
            int[] enabledComponents = new int[enabledComponentsCount];
            int[] disabledComponents = new int[disabledComponentsCount];
            int enabledIndex = 0;
            int disabledIndex = 0;
            for (int component = FIRST_POWER_COMPONENT; component <= LAST_POWER_COMPONENT;
                    component++) {
                if (mComponentStates.get(component, /* valueIfKeyNotFound= */ false)) {
                    enabledComponents[enabledIndex++] = component;
                } else {
                    disabledComponents[disabledIndex++] = component;
                }
            }
            return new CarPowerPolicy(mCurrentPolicyId, enabledComponents, disabledComponents);
        }
    }

    /**
     * Applies the given policy considering user setting.
     *
     * <p> If a component is the policy is not applied due to user setting, it is not notified to
     * listeners.
     */
    void applyPowerPolicy(CarPowerPolicy policy) {
        int[] enabledComponents = policy.getEnabledComponents();
        int[] disabledComponents = policy.getDisabledComponents();
        synchronized (mLock) {
            mLastModifiedComponents.clear();
            for (int i = 0; i < enabledComponents.length; i++) {
                int component = enabledComponents[i];
                if (setComponentEnabledLocked(component, /* enabled= */ true)) {
                    mLastModifiedComponents.put(component, /* value= */ true);
                }
            }
            for (int i = 0; i < disabledComponents.length; i++) {
                int component = disabledComponents[i];
                if (setComponentEnabledLocked(component, /* enabled= */ false)) {
                    mLastModifiedComponents.put(component, /* value= */ true);
                }
            }
            mCurrentPolicyId = policy.getPolicyId();
        }
    }

    boolean isComponentChanged(CarPowerPolicyFilter filter) {
        synchronized (mLock) {
            int[] components = filter.getComponents();
            for (int i = 0; i < components.length; i++) {
                if (mLastModifiedComponents.get(components[i], false)) {
                    return true;
                }
            }
            return false;
        }
    }

    void dump(IndentingPrintWriter writer) {
        synchronized (mLock) {
            writer.println("Power components state:");
            writer.increaseIndent();
            for (int component = FIRST_POWER_COMPONENT; component <= LAST_POWER_COMPONENT;
                    component++) {
                writer.printf("%s: %s\n", powerComponentToString(component),
                        mComponentStates.get(component, /* valueIfKeyNotFound= */ false)
                                ? "on" : "off");
            }
            writer.decreaseIndent();
            writer.println("Components controlled by user:");
            writer.increaseIndent();
            for (int i = 0; i < mUserControlledComponents.size(); i++) {
                writer.printf("%s: %s by user\n",
                        powerComponentToString(mUserControlledComponents.keyAt(i)),
                        mUserControlledComponents.valueAt(i) ? "on" : "off");
            }
            writer.decreaseIndent();
            writer.print("Components changed by the last policy: ");
            writer.increaseIndent();
            for (int i = 0; i < mLastModifiedComponents.size(); i++) {
                if (i > 0) writer.print(", ");
                writer.print(powerComponentToString(mLastModifiedComponents.keyAt(i)));
            }
            writer.decreaseIndent();
        }
    }

    private boolean setComponentEnabledLocked(int component, boolean enabled) {
        boolean oldState = mComponentStates.get(component, /* valueIfKeyNotFound= */ false);
        boolean componentDisabledByUser = !mUserControlledComponents.get(component,
                /* valueIfKeyNotFound= */ true);
        if (!componentDisabledByUser || !enabled) {
            mComponentStates.put(component, enabled);
        }
        // The component is actually updated when the old state and the new state are different
        // or when a power policy disables an enabled component whose current policy state is
        // off. The latter case can happen because a power policy respects user setting.
        boolean modified =
                oldState != mComponentStates.get(component, /* valueIfKeyNotFound= */ false)
                        || (!componentDisabledByUser && !enabled);
        if (modified && !componentDisabledByUser) {
            mUserControlledComponents.delete(component);
            writeUserOffComponentsLocked();
        }
        PowerComponentMediator mediator = mPowerComponentMediators.get(component);
        if (mediator == null) {
            Slog.w(TAG, powerComponentToString(component) + " doesn't have a mediator");
            return modified;
        }
        if (modified && mediator.isControlledBySystem()) {
            mediator.setEnabled(enabled);
        }
        return modified;
    }

    private void readUserOffComponentsLocked() {
        boolean invalid = false;
        mUserControlledComponents.clear();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(mOffComponentsByUserFile.openRead(),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int component = toPowerComponent(line.trim(), /* prefix= */ false);
                if (component == INVALID_POWER_COMPONENT) {
                    invalid = true;
                    break;
                }
                mUserControlledComponents.put(component, /* value= */ false);
            }
        } catch (FileNotFoundException e) {
            // Behave as if there are no forced-off components.
            return;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to read " + FORCED_OFF_COMPONENTS_FILENAME + ": " + e);
            return;
        }
        if (invalid) {
            mOffComponentsByUserFile.delete();
        }
    }

    private void writeUserOffComponentsLocked() {
        FileOutputStream fos;
        try {
            fos = mOffComponentsByUserFile.startWrite();
        } catch (IOException e) {
            Slog.e(TAG, "Cannot create " + FORCED_OFF_COMPONENTS_FILENAME, e);
            return;
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            for (int i = 0; i < mUserControlledComponents.size(); i++) {
                if (mUserControlledComponents.valueAt(i)) {
                    continue;
                }
                writer.write(powerComponentToString(mUserControlledComponents.keyAt(i)));
                writer.newLine();
            }
            writer.flush();
            mOffComponentsByUserFile.finishWrite(fos);
        } catch (IOException e) {
            mOffComponentsByUserFile.failWrite(fos);
            Slog.e(TAG, "Writing " + FORCED_OFF_COMPONENTS_FILENAME + " failed", e);
        }
    }

    private void processComponentOn(int component) {
        synchronized (mLock) {
            if (!mComponentStates.get(component)
                    || !mUserControlledComponents.get(component, /* valueIfKeyNotFound= */ true)) {
                mUserControlledComponents.put(component, /* value= */ true);
                writeUserOffComponentsLocked();
            }
        }
    }

    private void processComponentOff(int component) {
        synchronized (mLock) {
            if (mComponentStates.get(component)
                    || mUserControlledComponents.get(component, /* valueIfKeyNotFound= */ false)) {
                mUserControlledComponents.put(component, /* value= */ false);
                writeUserOffComponentsLocked();
            }
        }
    }

    private void logd(String messageFormat, Object... args) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            // TODO(b/182476140): Replace with formatted Slog.
            String message = String.format(messageFormat, args);
            Slog.d(TAG, message);
        }
    }

    abstract static class PowerComponentMediator {
        protected int mComponentId;

        PowerComponentMediator(int component) {
            mComponentId = component;
        }

        public void init() {}

        public void release() {}

        public boolean isComponentAvailable() {
            return false;
        }

        public boolean isControlledBySystem() {
            return false;
        }

        public void setEnabled(boolean enabled) {}
    }

    // TODO(b/178824607): Check if power policy can turn on/off display as quickly as the existing
    // implementation.
    private final class DisplayPowerComponentMediator extends PowerComponentMediator {
        DisplayPowerComponentMediator() {
            super(DISPLAY);
        }

        @Override
        public boolean isComponentAvailable() {
            // It is assumed that display is supported in all vehicles.
            return true;
        }

        public boolean isControlledBySystem() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
            mSystemInterface.setDisplayState(enabled);
            logd("Display power component is %s", enabled ? "on" : "off");
        }
    }

    private final class WifiPowerComponentMediator extends PowerComponentMediator {
        private final WifiManager mWifiManager;
        private final BroadcastReceiver mWifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (!WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    return;
                }
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        /* defaultValue= */ -1);
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    processComponentOn(WIFI);
                } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                    processComponentOff(WIFI);
                }
            }
        };

        WifiPowerComponentMediator() {
            super(WIFI);
            mWifiManager = mContext.getSystemService(WifiManager.class);
        }

        @Override
        public void init() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            mContext.registerReceiver(mWifiReceiver, filter);
        }

        @Override
        public void release() {
            mContext.unregisterReceiver(mWifiReceiver);
        }

        @Override
        public boolean isComponentAvailable() {
            return mPackageManager.hasSystemFeature(PackageManager.FEATURE_WIFI);
        }

        public boolean isControlledBySystem() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
            mWifiManager.setWifiEnabled(enabled);
            logd("Wifi power component is %s", enabled ? "on" : "off");
        }
    }

    private final class VoiceInteractionPowerComponentMediator extends PowerComponentMediator {
        private final IVoiceInteractionManagerService mVoiceInteractionManagerService;

        private boolean mIsEnabled = true;

        VoiceInteractionPowerComponentMediator() {
            super(VOICE_INTERACTION);
            if (mVoiceInteractionServiceHolder == null) {
                mVoiceInteractionManagerService = IVoiceInteractionManagerService.Stub.asInterface(
                        ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
            } else {
                mVoiceInteractionManagerService = mVoiceInteractionServiceHolder;
            }
        }

        @Override
        public boolean isComponentAvailable() {
            return mVoiceInteractionManagerService != null;
        }

        public boolean isControlledBySystem() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
            try {
                mVoiceInteractionManagerService.setDisabled(!enabled);
                mIsEnabled = enabled;
                logd("Voice Interaction power component is %s", enabled ? "on" : "off");
            } catch (RemoteException e) {
                Slog.w(TAG, "IVoiceInteractionManagerService.setDisabled(" + !enabled + ") failed",
                        e);
            }
        }
    }

    private final class BluetoothPowerComponentMediator extends PowerComponentMediator {
        private final BluetoothAdapter mBluetoothAdapter;
        private final BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    return;
                }
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        /* defaultValue= */ -1);
                if (state == BluetoothAdapter.STATE_ON) {
                    processComponentOn(BLUETOOTH);
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    processComponentOff(BLUETOOTH);
                }
            }
        };

        BluetoothPowerComponentMediator() {
            super(BLUETOOTH);
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        @Override
        public void init() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            mContext.registerReceiver(mBluetoothReceiver, filter);
        }

        @Override
        public void release() {
            mContext.unregisterReceiver(mBluetoothReceiver);
        }

        @Override
        public boolean isComponentAvailable() {
            return mPackageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
        }

        public boolean isControlledBySystem() {
            return false;
        }

        @Override
        public void setEnabled(boolean enabled) {
            // No op
            Slog.w(TAG, "Bluetooth power is controlled by "
                    + "com.android.car.BluetoothDeviceConnectionPolicy");
        }
    }

    private final class NfcPowerComponentMediator extends PowerComponentMediator {
        private final NfcAdapter mNfcAdapter;
        private final BroadcastReceiver mNfcReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (!NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(action)) {
                    return;
                }
                int state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE,
                        /* defaultValue= */ -1);
                if (state == NfcAdapter.STATE_ON) {
                    processComponentOn(NFC);
                } else if (state == NfcAdapter.STATE_OFF) {
                    processComponentOff(NFC);
                }
            }
        };

        NfcPowerComponentMediator() {
            super(NFC);
            mNfcAdapter = NfcAdapter.getDefaultAdapter(mContext);
        }

        @Override
        public void init() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
            mContext.registerReceiver(mNfcReceiver, filter);
        }

        @Override
        public void release() {
            mContext.unregisterReceiver(mNfcReceiver);
        }

        @Override
        public boolean isComponentAvailable() {
            return mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC);
        }

        public boolean isControlledBySystem() {
            return false;
        }

        @Override
        public void setEnabled(boolean enabled) {
            // No op
            Slog.w(TAG, "NFC power isn't controlled by CPMS");
        }
    }

    private final class LocationPowerComponentMediator extends PowerComponentMediator {
        private final LocationManager mLocationManager;
        private final BroadcastReceiver mLocationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (!LocationManager.MODE_CHANGED_ACTION.equals(action)) {
                    return;
                }
                boolean enabled = intent.getBooleanExtra(LocationManager.EXTRA_LOCATION_ENABLED,
                        /* defaultValue= */ true);
                if (enabled) {
                    processComponentOn(LOCATION);
                } else {
                    processComponentOff(LOCATION);
                }
            }
        };

        LocationPowerComponentMediator() {
            super(LOCATION);
            mLocationManager = mContext.getSystemService(LocationManager.class);
        }

        @Override
        public void init() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(LocationManager.MODE_CHANGED_ACTION);
            mContext.registerReceiver(mLocationReceiver, filter);
        }

        @Override
        public void release() {
            mContext.unregisterReceiver(mLocationReceiver);
        }

        @Override
        public boolean isComponentAvailable() {
            return mPackageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION);
        }

        public boolean isControlledBySystem() {
            return false;
        }

        @Override
        public void setEnabled(boolean enabled) {
            // No op
            Slog.w(TAG, "Location power isn controlled by GNSS HAL");
        }
    }

    private final class PowerComponentMediatorFactory {
        @Nullable
        PowerComponentMediator createPowerComponent(int component) {
            switch (component) {
                case PowerComponent.AUDIO:
                    // We don't control audio in framework level, because audio is enabled or
                    // disabled in audio HAL according to the current power policy.
                    return null;
                case PowerComponent.MEDIA:
                    return null;
                case PowerComponent.DISPLAY:
                    return new DisplayPowerComponentMediator();
                case PowerComponent.WIFI:
                    return new WifiPowerComponentMediator();
                case PowerComponent.CELLULAR:
                    return null;
                case PowerComponent.ETHERNET:
                    return null;
                case PowerComponent.PROJECTION:
                    return null;
                case PowerComponent.NFC:
                    // NFC mediator doesn't directly turn on/off NFC, but it changes policy
                    // behavior, considering user intervetion.
                    return new NfcPowerComponentMediator();
                case PowerComponent.INPUT:
                    return null;
                case PowerComponent.VOICE_INTERACTION:
                    return new VoiceInteractionPowerComponentMediator();
                case PowerComponent.VISUAL_INTERACTION:
                    return null;
                case PowerComponent.TRUSTED_DEVICE_DETECTION:
                    return null;
                case PowerComponent.MICROPHONE:
                    // We don't control microphone in framework level, because microphone is enabled
                    // or disabled in audio HAL according to the current power policy.
                    return null;
                case PowerComponent.BLUETOOTH:
                    // com.android.car.BluetoothDeviceConnectionPolicy handles power state change.
                    // So, bluetooth mediator doesn't directly turn on/off BT, but it changes policy
                    // behavior, considering user intervetion.
                    return new BluetoothPowerComponentMediator();
                case PowerComponent.LOCATION:
                    // GNSS HAL handles power state change. So, location mediator doesn't directly
                    // turn on/off location, but it changes policy behavior, considering user
                    // intervetion.
                    return new LocationPowerComponentMediator();
                case PowerComponent.CPU:
                    return null;
                default:
                    Slog.w(TAG, "Unknown component(" + component + ")");
                    return null;
            }
        }
    }

    static class PowerComponentException extends Exception {
        PowerComponentException(String message) {
            super(message);
        }
    }
}
