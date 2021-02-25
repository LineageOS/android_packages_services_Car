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

import static android.car.hardware.power.PowerComponentUtil.FIRST_POWER_COMPONENT;
import static android.car.hardware.power.PowerComponentUtil.INVALID_POWER_COMPONENT;
import static android.car.hardware.power.PowerComponentUtil.LAST_POWER_COMPONENT;
import static android.car.hardware.power.PowerComponentUtil.isValidPowerComponent;
import static android.car.hardware.power.PowerComponentUtil.powerComponentToString;
import static android.car.hardware.power.PowerComponentUtil.toPowerComponent;

import android.annotation.Nullable;
import android.car.hardware.power.CarPowerPolicy;
import android.car.hardware.power.PowerComponent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.car.CarLog;
import com.android.car.systeminterface.SystemInterface;
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
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class PowerComponentHandler {
    private static final String TAG = CarLog.tagFor(PowerComponentHandler.class);
    private static final String FORCED_OFF_COMPONENTS_FILENAME =
            "forced_off_components";

    private final Context mContext;
    private final SystemInterface mSystemInterface;
    private final AtomicFile mComponentStateFile;
    private final SparseArray<PowerComponentMediator> mPowerComponentMediators =
            new SparseArray<>();

    PowerComponentHandler(Context context, SystemInterface systemInterface) {
        mContext = context;
        mSystemInterface = systemInterface;
        mComponentStateFile = new AtomicFile(new File(systemInterface.getSystemCarDir(),
                FORCED_OFF_COMPONENTS_FILENAME));
    }

    void init() {
        PowerComponentMediatorFactory factory = new PowerComponentMediatorFactory();
        for (int component = FIRST_POWER_COMPONENT;
                    component <= LAST_POWER_COMPONENT; component++) {
            PowerComponentMediator mediator = factory.createPowerComponent(component);
            String componentName = powerComponentToString(component);
            if (mediator == null) {
                Slog.w(TAG, "Power component(" + componentName + ") is not valid or doesn't need a "
                        + "mediator");
                continue;
            }
            if (!mediator.isComponentAvailable()) {
                Slog.w(TAG, "Power component(" + componentName + ") is not available");
                continue;
            }
            mPowerComponentMediators.put(component, mediator);
        }
    }

    void applyPowerPolicy(CarPowerPolicy policy) {
        SparseBooleanArray forcedOffComponents = readComponentState();
        boolean componentModified = false;
        int[] enabledComponents = policy.enabledComponents;
        int[] disabledComponents = policy.disabledComponents;
        for (int i = 0; i < policy.enabledComponents.length; i++) {
            componentModified |= setComponentEnabledInternal(policy.enabledComponents[i], true,
                    forcedOffComponents);
        }
        for (int i = 0; i < policy.disabledComponents.length; i++) {
            componentModified |= setComponentEnabledInternal(policy.disabledComponents[i], false,
                    forcedOffComponents);
        }
        if (componentModified) {
            writeComponentState(forcedOffComponents);
        }
    }

    void setComponentEnabled(int component, boolean enabled) throws PowerComponentException {
        if (!isValidPowerComponent(component)) {
            throw new PowerComponentException("invalid power component");
        }
        SparseBooleanArray forcedOffComponents = readComponentState();
        if (setComponentEnabledInternal(component, enabled, forcedOffComponents)) {
            writeComponentState(forcedOffComponents);
        }
    }

    private boolean setComponentEnabledInternal(int component, boolean enabled,
            SparseBooleanArray forcedOffComponents) {
        PowerComponentMediator mediator = mPowerComponentMediators.get(component);
        if (mediator == null) {
            Slog.w(TAG, powerComponentToString(component) + " doesn't have a mediator");
            return false;
        }
        boolean componentModified = false;
        boolean isEnabled = mediator.isEnabled();
        if (enabled) {
            if (forcedOffComponents.get(component)) {
                forcedOffComponents.delete(component);
                componentModified = true;
            } else {
                // The last state set by user is off. So, we don't power on the component.
                return false;
            }
        } else {
            if (!forcedOffComponents.get(component)) {
                forcedOffComponents.put(component, true);
                componentModified = true;
            }
        }
        if (enabled != isEnabled) {
            mediator.setEnabled(enabled);
        }
        return componentModified;
    }

    private SparseBooleanArray readComponentState() {
        SparseBooleanArray forcedOffComponents = new SparseBooleanArray();
        boolean invalid = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(mComponentStateFile.openRead(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int component = toPowerComponent(line.trim(), false);
                if (component == INVALID_POWER_COMPONENT) {
                    invalid = true;
                    break;
                }
                forcedOffComponents.put(component, true);
            }
        } catch (FileNotFoundException e) {
            // Behave as if there are no forced-off components.
            return new SparseBooleanArray();
        } catch (IOException e) {
            Slog.w(TAG, "Failed to read " + FORCED_OFF_COMPONENTS_FILENAME + ": " + e);
            return new SparseBooleanArray();
        }
        if (invalid) {
            mComponentStateFile.delete();
            return new SparseBooleanArray();
        }
        return forcedOffComponents;
    }

    private void writeComponentState(SparseBooleanArray forcedOffComponents) {
        FileOutputStream fos;
        try {
            fos = mComponentStateFile.startWrite();
        } catch (IOException e) {
            Slog.e(TAG, "Cannot create " + FORCED_OFF_COMPONENTS_FILENAME, e);
            return;
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            for (int i = 0; i < forcedOffComponents.size(); i++) {
                writer.write(powerComponentToString(forcedOffComponents.keyAt(i)));
                writer.newLine();
            }
            writer.flush();
            mComponentStateFile.finishWrite(fos);
        } catch (IOException e) {
            mComponentStateFile.failWrite(fos);
            Slog.e(TAG, "Writing " + FORCED_OFF_COMPONENTS_FILENAME + " failed", e);
        }
    }

    abstract class PowerComponentMediator {
        protected int mComponentId;

        PowerComponentMediator(int component) {
            mComponentId = component;
        }

        public void setEnabled(boolean enabled) {}

        public boolean isEnabled() {
            return false;
        }

        public boolean isComponentAvailable() {
            return false;
        }
    }

    // TODO(b/178824607): Check if power policy can turn on/off display as quickly as the existing
    // implementation.
    private final class DisplayPowerComponentMediator extends PowerComponentMediator {
        DisplayPowerComponentMediator() {
            super(PowerComponent.DISPLAY);
        }

        @Override
        public boolean isComponentAvailable() {
            // It is assumed that display is supported in all vehicles.
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
            mSystemInterface.setDisplayState(enabled);
        }

        @Override
        public boolean isEnabled() {
            return mSystemInterface.isDisplayEnabled();
        }
    }

    private final class WifiPowerComponentMediator extends PowerComponentMediator {
        private final WifiManager mWifiManager;

        WifiPowerComponentMediator() {
            super(PowerComponent.WIFI);
            mWifiManager = mContext.getSystemService(WifiManager.class);
        }

        @Override
        public boolean isComponentAvailable() {
            PackageManager pm = mContext.getPackageManager();
            return pm.hasSystemFeature(PackageManager.FEATURE_WIFI);
        }

        @Override
        public void setEnabled(boolean enabled) {
            mWifiManager.setWifiEnabled(enabled);
        }

        @Override
        public boolean isEnabled() {
            return mWifiManager.isWifiEnabled();
        }
    }

    private final class VoiceInteractionPowerComponentMediator extends PowerComponentMediator {
        private final IVoiceInteractionManagerService mVoiceInteractionManagerService;

        private boolean mIsEnabled = true;

        VoiceInteractionPowerComponentMediator() {
            super(PowerComponent.VOICE_INTERACTION);
            mVoiceInteractionManagerService = IVoiceInteractionManagerService.Stub.asInterface(
                        ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
        }

        @Override
        public boolean isComponentAvailable() {
            return mVoiceInteractionManagerService != null;
        }

        @Override
        public void setEnabled(boolean enabled) {
            try {
                mVoiceInteractionManagerService.setDisabled(!enabled);
                mIsEnabled = enabled;
            } catch (RemoteException e) {
                Slog.w(TAG, "IVoiceInteractionManagerService.setDisabled(" + !enabled + ") failed",
                        e);
            }
        }

        @Override
        public boolean isEnabled() {
            // IVoiceInteractionManagerService doesn't have a method to tell enabled state. Assuming
            // voice interaction is controlled only by AAOS CPMS, it tracks the state internally.
            // TODO(b/178504489): Add isEnabled to IVoiceInterctionManagerService and use it.
            return mIsEnabled;
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
                    return null;
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
                    // So, bluetooth mediator is not created.
                    return null;
                case PowerComponent.LOCATION:
                    // GNSS HAL handles power state change. So, location mediator is not created.
                    return null;
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
