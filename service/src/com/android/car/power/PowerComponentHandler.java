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

import static com.android.car.power.PowerComponentUtil.FIRST_POWER_COMPONENT;
import static com.android.car.power.PowerComponentUtil.INVALID_POWER_COMPONENT;
import static com.android.car.power.PowerComponentUtil.LAST_POWER_COMPONENT;
import static com.android.car.power.PowerComponentUtil.isValidPowerComponent;
import static com.android.car.power.PowerComponentUtil.powerComponentToString;
import static com.android.car.power.PowerComponentUtil.toPowerComponent;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.frameworks.automotive.powerpolicy.CarPowerPolicy;
import android.frameworks.automotive.powerpolicy.PowerComponent;
import android.net.wifi.WifiManager;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.car.systeminterface.SystemInterface;
import com.android.internal.annotations.VisibleForTesting;

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
    private static final String TAG = PowerComponentHandler.class.getSimpleName();
    private static final String FORCED_OFF_COMPONENTS_FILENAME =
            "forced_off_components";

    private final Context mContext;
    private final AtomicFile mComponentStateFile;
    private final SparseArray<PowerComponentMediator> mPowerComponentMediators =
            new SparseArray<>();

    PowerComponentHandler(Context context, SystemInterface systemInterface) {
        mContext = context;
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

    private final class AudioPowerComponentMediator extends PowerComponentMediator {
        AudioPowerComponentMediator() {
            super(PowerComponent.AUDIO);
        }
        // TODO(b/162600135): implement turning on/off audio.
    }

    private final class MediaPowerComponentMediator extends PowerComponentMediator {
        MediaPowerComponentMediator() {
            super(PowerComponent.MEDIA);
        }
        // TODO(b/162600135): implement turning on/off media.
    }

    private final class DisplayMainPowerComponentMediator extends PowerComponentMediator {
        DisplayMainPowerComponentMediator() {
            super(PowerComponent.DISPLAY_MAIN);
        }
        // TODO(b/162600135): implement turning on/off display main.
    }

    private final class DisplayClusterPowerComponentMediator extends PowerComponentMediator {
        DisplayClusterPowerComponentMediator() {
            super(PowerComponent.DISPLAY_CLUSTER);
        }
        // TODO(b/162600135): implement turning on/off display cluster.
    }

    private final class DisplayFrontPassengerPowerComponentMediator
            extends PowerComponentMediator {
        DisplayFrontPassengerPowerComponentMediator() {
            super(PowerComponent.DISPLAY_FRONT_PASSENGER);
        }
        // TODO(b/162600135): implement turning on/off display front passenger.
    }

    private final class DisplayRearPassengerPowerComponentMediator
            extends PowerComponentMediator {
        DisplayRearPassengerPowerComponentMediator() {
            super(PowerComponent.DISPLAY_REAR_PASSENGER);
        }
        // TODO(b/162600135): implement turning on/off display rear passenger.
    }

    private final class WifiPowerComponentMediator extends PowerComponentMediator {
        private final WifiManager mWifiManager;

        WifiPowerComponentMediator() {
            super(PowerComponent.WIFI);
            mWifiManager = mContext.getSystemService(WifiManager.class);
        }

        public boolean isComponentAvailable() {
            PackageManager pm = mContext.getPackageManager();
            return pm.hasSystemFeature(PackageManager.FEATURE_WIFI);
        }

        public void setEnabled(boolean enabled) {
            mWifiManager.setWifiEnabled(enabled);
        }

        public boolean isEnabled() {
            return mWifiManager.isWifiEnabled();
        }
    }

    private final class CellularPowerComponentMediator extends PowerComponentMediator {
        CellularPowerComponentMediator() {
            super(PowerComponent.CELLULAR);
        }
        // TODO(b/162600135): implement turning on/off cellular.
    }

    private final class EthernetPowerComponentMediator extends PowerComponentMediator {
        EthernetPowerComponentMediator() {
            super(PowerComponent.ETHERNET);
        }
        // TODO(b/162600135): implement turning on/off ethernet.
    }

    private final class ProjectionPowerComponentMediator extends PowerComponentMediator {
        ProjectionPowerComponentMediator() {
            super(PowerComponent.PROJECTION);
        }
        // TODO(b/162600135): implement turning on/off projection.
    }

    private final class NfcPowerComponentMediator extends PowerComponentMediator {
        NfcPowerComponentMediator() {
            super(PowerComponent.NFC);
        }
        // TODO(b/162600135): implement turning on/off nfc.
    }

    private final class InputPowerComponentMediator extends PowerComponentMediator {
        InputPowerComponentMediator() {
            super(PowerComponent.INPUT);
        }
        // TODO(b/162600135): implement turning on/off input.
    }

    private final class VoiceInteractionPowerComponentMediator extends PowerComponentMediator {
        VoiceInteractionPowerComponentMediator() {
            super(PowerComponent.VOICE_INTERACTION);
        }
        // TODO(b/162600135): implement turning on/off voice interaction.
    }

    private final class VisualInteractionPowerComponentMediator extends PowerComponentMediator {
        VisualInteractionPowerComponentMediator() {
            super(PowerComponent.VISUAL_INTERACTION);
        }
        // TODO(b/162600135): implement turning on/off visual interaction.
    }

    private final class TrustedDeviceDetectionPowerComponentMediator
            extends PowerComponentMediator {
        TrustedDeviceDetectionPowerComponentMediator() {
            super(PowerComponent.TRUSTED_DEVICE_DETECTION);
        }
        // TODO(b/162600135): implement turning on/off trusted device detection.
    }

    private final class MicroPhonePowerComponentMediator extends PowerComponentMediator {
        MicroPhonePowerComponentMediator() {
            super(PowerComponent.MICROPHONE);
        }
        // TODO(b/162600135): implement turning on/off microphone.
    }

    private final class PowerComponentMediatorFactory {
        @Nullable
        PowerComponentMediator createPowerComponent(int component) {
            switch (component) {
                case PowerComponent.AUDIO:
                    return new PowerComponentHandler.AudioPowerComponentMediator();
                case PowerComponent.MEDIA:
                    return new MediaPowerComponentMediator();
                case PowerComponent.DISPLAY_MAIN:
                    return new DisplayMainPowerComponentMediator();
                case PowerComponent.DISPLAY_CLUSTER:
                    return new DisplayClusterPowerComponentMediator();
                case PowerComponent.DISPLAY_FRONT_PASSENGER:
                    return new DisplayFrontPassengerPowerComponentMediator();
                case PowerComponent.DISPLAY_REAR_PASSENGER:
                    return new DisplayRearPassengerPowerComponentMediator();
                case PowerComponent.WIFI:
                    return new WifiPowerComponentMediator();
                case PowerComponent.CELLULAR:
                    return new CellularPowerComponentMediator();
                case PowerComponent.ETHERNET:
                    return new EthernetPowerComponentMediator();
                case PowerComponent.PROJECTION:
                    return new ProjectionPowerComponentMediator();
                case PowerComponent.NFC:
                    return new NfcPowerComponentMediator();
                case PowerComponent.INPUT:
                    return new InputPowerComponentMediator();
                case PowerComponent.VOICE_INTERACTION:
                    return new VoiceInteractionPowerComponentMediator();
                case PowerComponent.VISUAL_INTERACTION:
                    return new VisualInteractionPowerComponentMediator();
                case PowerComponent.TRUSTED_DEVICE_DETECTION:
                    return new TrustedDeviceDetectionPowerComponentMediator();
                case PowerComponent.MICROPHONE:
                    return new MicroPhonePowerComponentMediator();
                case PowerComponent.BLUETOOTH:
                    // com.android.car.BluetoothDeviceConnectionPolicy handles power state change.
                    // So, bluetooth mediator is not created.
                    return null;
                case PowerComponent.LOCATION:
                    // GNSS HAL handles power state change. So, location mediator is not created.
                    return null;
                default:
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
