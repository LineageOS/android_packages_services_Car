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
import static android.car.hardware.power.PowerComponentUtil.powerComponentToString;
import static android.car.hardware.power.PowerComponentUtil.toPowerComponent;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;
import static org.xmlpull.v1.XmlPullParser.TEXT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.hardware.power.CarPowerPolicy;
import android.car.hardware.power.PowerComponent;
import android.hardware.automotive.vehicle.V2_0.VehicleApPowerStateReport;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.Xml;

import com.android.car.CarServiceUtils;
import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Helper class to read and manage vendor power policies.
 *
 * <p>{@code CarPowerManagementService} manages power policies through {@code PolicyReader}. This
 * class is not thread-safe, and must be used in the main thread or with additional serialization.
 */
final class PolicyReader {
    private static final String TAG = PolicyReader.class.getSimpleName();
    private static final String VENDOR_POLICY_PATH = "/vendor/etc/power_policy.xml";

    private static final String NAMESPACE = null;
    private static final Set<String> VALID_VERSIONS = new ArraySet<>(Arrays.asList("1.0"));
    private static final String TAG_POWER_POLICY = "powerPolicy";
    private static final String TAG_POLICY_GROUPS = "policyGroups";
    private static final String TAG_POLICY_GROUP = "policyGroup";
    private static final String TAG_DEFAULT_POLICY = "defaultPolicy";
    private static final String TAG_NO_DEFAULT_POLICY = "noDefaultPolicy";
    private static final String TAG_POLICIES = "policies";
    private static final String TAG_POLICY = "policy";
    private static final String TAG_OTHER_COMPONENTS = "otherComponents";
    private static final String TAG_COMPONENT = "component";
    private static final String TAG_SYSTEM_POLICY_OVERRIDES = "systemPolicyOverrides";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_ID = "id";
    private static final String ATTR_STATE = "state";
    private static final String ATTR_BEHAVIOR = "behavior";
    private static final String POWER_ONOFF_ON = "on";
    private static final String POWER_ONOFF_OFF = "off";
    private static final String POWER_ONOFF_UNTOUCHED = "untouched";

    private static final int INVALID_POWER_STATE = -1;
    private static final String POWER_STATE_WAIT_FOR_VHAL = "WaitForVHAL";
    private static final String POWER_STATE_ON = "On";
    private static final String POWER_STATE_DEEP_SLEEP_ENTRY = "DeepSleepEntry";
    private static final String POWER_STATE_SHUTDOWN_START = "ShutdownStart";

    private static final String SYSTEM_POWER_POLICY_NO_USER_INTERACTION =
            "system_power_policy_no_user_interaction";
    private static final int[] SYSTEM_POLICY_ENABLED_COMPONENTS = {
            PowerComponent.WIFI, PowerComponent.CELLULAR,
            PowerComponent.ETHERNET, PowerComponent.TRUSTED_DEVICE_DETECTION
    };
    private static final int[] SYSTEM_POLICY_DISABLED_COMPONENTS = {
            PowerComponent.AUDIO, PowerComponent.MEDIA, PowerComponent.DISPLAY,
            PowerComponent.BLUETOOTH, PowerComponent.PROJECTION, PowerComponent.NFC,
            PowerComponent.INPUT, PowerComponent.VOICE_INTERACTION,
            PowerComponent.VISUAL_INTERACTION, PowerComponent.LOCATION, PowerComponent.MICROPHONE
    };
    private static final Set<Integer> SYSTEM_POLICY_CONFIGURABLE_COMPONENTS =
            new ArraySet<>(Arrays.asList(PowerComponent.BLUETOOTH, PowerComponent.NFC,
            PowerComponent.TRUSTED_DEVICE_DETECTION));

    private ArrayMap<String, CarPowerPolicy> mRegisteredPowerPolicies;
    private ArrayMap<String, SparseArray<String>> mPolicyGroups;
    private CarPowerPolicy mSystemPowerPolicy;

    /**
     * Gets {@code CarPowerPolicy} corresponding to the given policy ID.
     */
    @Nullable
    CarPowerPolicy getPowerPolicy(String policyId) {
        return mRegisteredPowerPolicies.get(policyId);
    }

    /**
     * Gets {@code CarPowerPolicy} corresponding to the given power state in the given power
     * policy group.
     */
    @Nullable
    CarPowerPolicy getDefaultPowerPolicyForState(String groupId, int state) {
        SparseArray<String> group = mPolicyGroups.get(groupId);
        if (group == null) {
            return null;
        }
        String policyId = group.get(state);
        if (policyId == null) {
            return null;
        }
        return mRegisteredPowerPolicies.get(policyId);
    }

    /**
     * Gets the system power policy.
     *
     * <p> At this moment, only one system power policy is supported.
     */
    @NonNull
    CarPowerPolicy getSystemPowerPolicy() {
        return mSystemPowerPolicy;
    }

    boolean isPowerPolicyGroupAvailable(String policyGroupId) {
        return mPolicyGroups.containsKey(policyGroupId);
    }

    void init() {
        initPolicies();
        readPowerPolicyConfiguration();
    }

    /**
     * Creates and registers a new power policy.
     *
     * @return {@code null}, if successful. Otherwise, error message.
     */
    @Nullable
    String definePowerPolicy(String policyId, String[] enabledComponents,
            String[] disabledComponents) {
        if (mRegisteredPowerPolicies.containsKey(policyId)) {
            return policyId + " is already registered";
        }
        SparseBooleanArray components = new SparseBooleanArray();
        String errorMsg = parseComponents(enabledComponents, true, components);
        if (errorMsg != null) {
            return errorMsg;
        }
        errorMsg = parseComponents(disabledComponents, false, components);
        if (errorMsg != null) {
            return errorMsg;
        }
        CarPowerPolicy policy = new CarPowerPolicy(policyId, toIntArray(components, true),
                toIntArray(components, false));
        mRegisteredPowerPolicies.put(policyId, policy);
        return null;
    }

    void dump(PrintWriter writer) {
        String indent = "  ";
        String doubleIndent = "    ";
        writer.printf("Registered power policies:%s\n",
                mRegisteredPowerPolicies.size() == 0 ? " none" : "");
        for (Map.Entry<String, CarPowerPolicy> entry : mRegisteredPowerPolicies.entrySet()) {
            writer.printf("%s%s\n", indent, toString(entry.getValue()));
        }
        writer.printf("Power policy groups:%s\n", mPolicyGroups.isEmpty() ? " none" : "");
        for (Map.Entry<String, SparseArray<String>> entry : mPolicyGroups.entrySet()) {
            writer.printf("%s%s\n", indent, entry.getKey());
            SparseArray<String> group = entry.getValue();
            for (int i = 0; i < group.size(); i++) {
                writer.printf("%s- %s --> %s\n", doubleIndent, powerStateToString(group.keyAt(i)),
                        group.valueAt(i));
            }
        }
        writer.printf("System power policy: %s\n", toString(mSystemPowerPolicy));
    }

    @VisibleForTesting
    void initPolicies() {
        mRegisteredPowerPolicies = new ArrayMap<>();
        mPolicyGroups = new ArrayMap<>();
        initSystemPowerPolicy();
    }

    private void readPowerPolicyConfiguration() {
        try (InputStream inputStream = new FileInputStream(VENDOR_POLICY_PATH)) {
            readPowerPolicyFromXml(inputStream);
        } catch (IOException | XmlPullParserException | PolicyXmlException e) {
            Slog.w(TAG, "Proceed without registered policies: failed to parse "
                    + VENDOR_POLICY_PATH, e);
        }
    }

    @VisibleForTesting
    void readPowerPolicyFromXml(InputStream stream) throws PolicyXmlException,
            XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, NAMESPACE != null);
        parser.setInput(stream, null);

        // Ensure <powerPolicy> is the root
        parser.nextTag();
        parser.require(START_TAG, NAMESPACE, TAG_POWER_POLICY);
        // Check version
        String version = parser.getAttributeValue(NAMESPACE, ATTR_VERSION);
        if (!VALID_VERSIONS.contains(version)) {
            throw new PolicyXmlException("invalid XML version: " + version);
        }

        ArrayMap<String, CarPowerPolicy> registeredPolicies = new ArrayMap<>();
        ArrayMap<String, SparseArray<String>> policyGroups = new ArrayMap<>();
        CarPowerPolicy systemPolicyOverride = null;

        int type;
        while ((type = parser.next()) != END_DOCUMENT && type != END_TAG) {
            if (type != START_TAG) continue;
            switch (parser.getName()) {
                case TAG_POLICIES:
                    registeredPolicies = parsePolicies(parser, true);
                    break;
                case TAG_POLICY_GROUPS:
                    policyGroups = parsePolicyGroups(parser);
                    break;
                case TAG_SYSTEM_POLICY_OVERRIDES:
                    systemPolicyOverride = parseSystemPolicyOverrides(parser);
                    break;
                default:
                    throw new PolicyXmlException("unknown tag: " + parser.getName() + " under "
                            + TAG_POWER_POLICY);
            }
        }
        validatePolicyGroups(policyGroups, registeredPolicies);

        mRegisteredPowerPolicies = registeredPolicies;
        mPolicyGroups = policyGroups;
        reconstructSystemPowerPolicy(systemPolicyOverride);
    }

    private ArrayMap<String, CarPowerPolicy> parsePolicies(XmlPullParser parser,
            boolean includeOtherComponents) throws PolicyXmlException, XmlPullParserException,
            IOException {
        ArrayMap<String, CarPowerPolicy> policies = new ArrayMap<>();
        int type;
        while ((type = parser.next()) != END_DOCUMENT && type != END_TAG) {
            if (type != START_TAG) continue;
            if (TAG_POLICY.equals(parser.getName())) {
                String policyId = parser.getAttributeValue(NAMESPACE, ATTR_ID);
                if (policyId == null || policyId.equals("")) {
                    throw new PolicyXmlException("no |" + ATTR_ID + "| attribute of |" + TAG_POLICY
                            + "| tag");
                }
                policies.put(policyId, parsePolicy(parser, policyId, includeOtherComponents));
            } else {
                throw new PolicyXmlException("unknown tag: " + parser.getName() + " under "
                        + TAG_POLICIES);
            }
        }
        return policies;
    }

    private ArrayMap<String, SparseArray<String>> parsePolicyGroups(XmlPullParser parser) throws
            PolicyXmlException, XmlPullParserException, IOException {
        ArrayMap<String, SparseArray<String>> policyGroups = new ArrayMap<>();
        int type;
        while ((type = parser.next()) != END_DOCUMENT && type != END_TAG) {
            if (type != START_TAG) continue;
            if (TAG_POLICY_GROUP.equals(parser.getName())) {
                String groupId = parser.getAttributeValue(NAMESPACE, ATTR_ID);
                if (groupId == null || groupId.equals("")) {
                    throw new PolicyXmlException("no |" + ATTR_ID + "| attribute of |"
                            + TAG_POLICY_GROUP + "| tag");
                }
                policyGroups.put(groupId, parsePolicyGroup(parser));
            } else {
                throw new PolicyXmlException("unknown tag: " + parser.getName() + " under "
                        + TAG_POLICY_GROUPS);
            }
        }
        return policyGroups;
    }

    @Nullable
    private CarPowerPolicy parseSystemPolicyOverrides(XmlPullParser parser) throws
            PolicyXmlException, XmlPullParserException, IOException {
        ArrayMap<String, CarPowerPolicy> systemOverrides = parsePolicies(parser, false);
        int numOverrides = systemOverrides.size();
        if (numOverrides == 0) {
            return null;
        }
        if (numOverrides > 1) {
            throw new PolicyXmlException("only one system power policy is supported: "
                    + numOverrides + " system policies exist");
        }
        CarPowerPolicy policyOverride =
                systemOverrides.get(SYSTEM_POWER_POLICY_NO_USER_INTERACTION);
        if (policyOverride == null) {
            throw new PolicyXmlException("system power policy id should be "
                    + SYSTEM_POWER_POLICY_NO_USER_INTERACTION);
        }
        Set<Integer> visited = new ArraySet<>();
        checkSystemPowerPolicyComponents(policyOverride.enabledComponents, visited);
        checkSystemPowerPolicyComponents(policyOverride.disabledComponents, visited);
        return policyOverride;
    }

    private CarPowerPolicy parsePolicy(XmlPullParser parser, String policyId,
            boolean includeOtherComponents) throws PolicyXmlException, XmlPullParserException,
            IOException {
        SparseBooleanArray components = new SparseBooleanArray();
        String behavior = POWER_ONOFF_UNTOUCHED;
        boolean otherComponentsProcessed = false;
        int type;
        while ((type = parser.next()) != END_DOCUMENT && type != END_TAG) {
            if (type != START_TAG) continue;
            if (TAG_COMPONENT.equals(parser.getName())) {
                String id = parser.getAttributeValue(NAMESPACE, ATTR_ID);
                int powerComponent = toPowerComponent(id, true);
                if (powerComponent == INVALID_POWER_COMPONENT) {
                    throw new PolicyXmlException("invalid value(" + id + ") in |" + ATTR_ID
                            + "| attribute of |" + TAG_COMPONENT + "| tag");
                }
                if (components.indexOfKey(powerComponent) >= 0) {
                    throw new PolicyXmlException(id + " is specified more than once in |"
                            + TAG_COMPONENT + "| tag");
                }
                String state = getText(parser);
                switch (state) {
                    case POWER_ONOFF_ON:
                        components.put(powerComponent, true);
                        break;
                    case POWER_ONOFF_OFF:
                        components.put(powerComponent, false);
                        break;
                    default:
                        throw new PolicyXmlException("target state(" + state + ") for " + id
                                + " is not valid");
                }
                skip(parser);
            } else if (TAG_OTHER_COMPONENTS.equals(parser.getName())) {
                if (!includeOtherComponents) {
                    throw new PolicyXmlException("|" + TAG_OTHER_COMPONENTS
                            + "| tag is not expected");
                }
                if (otherComponentsProcessed) {
                    throw new PolicyXmlException("more than one |" + TAG_OTHER_COMPONENTS
                            + "| tag");
                }
                otherComponentsProcessed = true;
                behavior = parser.getAttributeValue(NAMESPACE, ATTR_BEHAVIOR);
                if (behavior == null) {
                    throw new PolicyXmlException("no |" + ATTR_BEHAVIOR + "| attribute of |"
                            + TAG_OTHER_COMPONENTS + "| tag");
                }
                switch (behavior) {
                    case POWER_ONOFF_ON:
                    case POWER_ONOFF_OFF:
                    case POWER_ONOFF_UNTOUCHED:
                        break;
                    default:
                        throw new PolicyXmlException("invalid value(" + behavior + ") in |"
                                + ATTR_BEHAVIOR + "| attribute of |" + TAG_OTHER_COMPONENTS
                                + "| tag");
                }
                skip(parser);
            } else {
                throw new PolicyXmlException("unknown tag: " + parser.getName() + " under "
                        + TAG_POLICY);
            }
        }
        boolean enabled = false;
        boolean untouched = false;
        if (POWER_ONOFF_ON.equals(behavior)) {
            enabled = true;
        } else if (POWER_ONOFF_OFF.equals(behavior)) {
            enabled = false;
        } else {
            untouched = true;
        }
        if (!untouched) {
            for (int component = FIRST_POWER_COMPONENT;
                    component <= LAST_POWER_COMPONENT; component++) {
                if (components.indexOfKey(component) >= 0) continue;
                components.put(component, enabled);
            }
        }
        return new CarPowerPolicy(policyId, toIntArray(components, true),
                toIntArray(components, false));
    }

    private SparseArray<String> parsePolicyGroup(XmlPullParser parser) throws PolicyXmlException,
            XmlPullParserException, IOException {
        SparseArray<String> policyGroup = new SparseArray<>();
        int type;
        Set<Integer> visited = new ArraySet<>();
        while ((type = parser.next()) != END_DOCUMENT && type != END_TAG) {
            if (type != START_TAG) continue;
            if (TAG_DEFAULT_POLICY.equals(parser.getName())) {
                String id = parser.getAttributeValue(NAMESPACE, ATTR_ID);
                if (id == null || id.isEmpty()) {
                    throw new PolicyXmlException("no |" + ATTR_ID + "| attribute of |"
                            + TAG_DEFAULT_POLICY + "| tag");
                }
                String state = parser.getAttributeValue(NAMESPACE, ATTR_STATE);
                int powerState = toPowerState(state);
                if (powerState == INVALID_POWER_STATE) {
                    throw new PolicyXmlException("invalid value(" + state + ") in |" + ATTR_STATE
                            + "| attribute of |" + TAG_DEFAULT_POLICY + "| tag");
                }
                if (visited.contains(powerState)) {
                    throw new PolicyXmlException("power state(" + state
                            + ") is specified more than once");
                }
                policyGroup.put(powerState, id);
                visited.add(powerState);
                skip(parser);
            } else if (TAG_NO_DEFAULT_POLICY.equals(parser.getName())) {
                String state = parser.getAttributeValue(NAMESPACE, ATTR_STATE);
                int powerState = toPowerState(state);
                if (powerState == INVALID_POWER_STATE) {
                    throw new PolicyXmlException("invalid value(" + state + ") in |" + ATTR_STATE
                            + "| attribute of |" + TAG_DEFAULT_POLICY + "| tag");
                }
                if (visited.contains(powerState)) {
                    throw new PolicyXmlException("power state(" + state
                            + ") is specified more than once");
                }
                visited.add(powerState);
                skip(parser);
            } else {
                throw new PolicyXmlException("unknown tag: " + parser.getName() + " under "
                        + TAG_POLICY_GROUP);
            }
        }
        return policyGroup;
    }

    private void validatePolicyGroups(ArrayMap<String, SparseArray<String>> policyGroups,
            ArrayMap<String, CarPowerPolicy> registeredPolicies) throws PolicyXmlException {
        for (Map.Entry<String, SparseArray<String>> entry : policyGroups.entrySet()) {
            SparseArray<String> group = entry.getValue();
            for (int i = 0; i < group.size(); i++) {
                String policyId = group.valueAt(i);
                if (!registeredPolicies.containsKey(group.valueAt(i))) {
                    throw new PolicyXmlException("group(id: " + entry.getKey()
                            + ") contains invalid policy(id: " + policyId + ")");
                }
            }
        }
    }

    private void reconstructSystemPowerPolicy(@Nullable CarPowerPolicy policyOverride) {
        if (policyOverride == null) return;

        List<Integer> enabledComponents = Arrays.stream(SYSTEM_POLICY_ENABLED_COMPONENTS).boxed()
                .collect(Collectors.toList());
        List<Integer> disabledComponents = Arrays.stream(SYSTEM_POLICY_DISABLED_COMPONENTS).boxed()
                .collect(Collectors.toList());
        for (int i = 0; i < policyOverride.enabledComponents.length; i++) {
            removeComponent(disabledComponents, policyOverride.enabledComponents[i]);
            addComponent(enabledComponents, policyOverride.enabledComponents[i]);
        }
        for (int i = 0; i < policyOverride.disabledComponents.length; i++) {
            removeComponent(enabledComponents, policyOverride.disabledComponents[i]);
            addComponent(disabledComponents, policyOverride.disabledComponents[i]);
        }
        mSystemPowerPolicy = new CarPowerPolicy(mSystemPowerPolicy.policyId,
                CarServiceUtils.toIntArray(enabledComponents),
                CarServiceUtils.toIntArray(disabledComponents));
    }

    private void removeComponent(List<Integer> components, int component) {
        int index = components.lastIndexOf(component);
        if (index != -1) {
            components.remove(index);
        }
    }

    private void addComponent(List<Integer> components, int component) {
        int index = components.lastIndexOf(component);
        if (index == -1) {
            components.add(component);
        }
    }

    private void initSystemPowerPolicy() {
        mSystemPowerPolicy = new CarPowerPolicy(SYSTEM_POWER_POLICY_NO_USER_INTERACTION,
                SYSTEM_POLICY_ENABLED_COMPONENTS.clone(),
                SYSTEM_POLICY_DISABLED_COMPONENTS.clone());
    }

    private String getText(XmlPullParser parser) throws PolicyXmlException, XmlPullParserException,
            IOException {
        if (parser.getEventType() != START_TAG) {
            throw new PolicyXmlException("tag pair doesn't match");
        }
        parser.next();
        if (parser.getEventType() != TEXT) {
            throw new PolicyXmlException("tag value is not found");
        }
        return parser.getText();
    }

    private void skip(XmlPullParser parser) throws PolicyXmlException, XmlPullParserException,
            IOException {
        int type = parser.getEventType();
        if (type != START_TAG && type != TEXT) {
            throw new PolicyXmlException("tag pair doesn't match");
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case END_TAG:
                    depth--;
                    break;
                case START_TAG:
                    depth++;
                    break;
            }
        }
    }

    void checkSystemPowerPolicyComponents(int[] components, Set<Integer> visited) throws
            PolicyXmlException {
        for (int i = 0; i < components.length; i++) {
            int component = components[i];
            if (!isOverridableComponent(component)) {
                throw new PolicyXmlException("Power component(" + powerComponentToString(component)
                        + ") cannot be overridden");
            }
            if (visited.contains(component)) {
                throw new PolicyXmlException("Power component(" + powerComponentToString(component)
                        + ") is specified more than once");
            }
            visited.add(component);
        }
    }

    boolean isOverridableComponent(int component) {
        return SYSTEM_POLICY_CONFIGURABLE_COMPONENTS.contains(component);
    }

    private int toPowerState(String state) {
        if (state == null) {
            return INVALID_POWER_STATE;
        }
        switch (state) {
            case POWER_STATE_WAIT_FOR_VHAL:
                return VehicleApPowerStateReport.WAIT_FOR_VHAL;
            case POWER_STATE_ON:
                return VehicleApPowerStateReport.ON;
            case POWER_STATE_DEEP_SLEEP_ENTRY:
                return VehicleApPowerStateReport.DEEP_SLEEP_ENTRY;
            case POWER_STATE_SHUTDOWN_START:
                return VehicleApPowerStateReport.SHUTDOWN_START;
            default:
                return INVALID_POWER_STATE;
        }
    }

    private String powerStateToString(int state) {
        switch (state) {
            case VehicleApPowerStateReport.WAIT_FOR_VHAL:
                return POWER_STATE_WAIT_FOR_VHAL;
            case VehicleApPowerStateReport.ON:
                return POWER_STATE_ON;
            case VehicleApPowerStateReport.DEEP_SLEEP_ENTRY:
                return POWER_STATE_DEEP_SLEEP_ENTRY;
            case VehicleApPowerStateReport.SHUTDOWN_START:
                return POWER_STATE_SHUTDOWN_START;
            default:
                return "unknown power state";
        }
    }

    private String toString(CarPowerPolicy policy) {
        return policy.policyId + "(enabledComponents: "
                + componentsToString(policy.enabledComponents) + " | disabledComponents: "
                + componentsToString(policy.disabledComponents) + ")";
    }

    private String componentsToString(int[] components) {
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) buffer.append(", ");
            buffer.append(powerComponentToString(components[i]));
        }
        return buffer.toString();
    }

    @Nullable
    private String parseComponents(String[] componentArr, boolean enabled,
            SparseBooleanArray components) {
        for (int i = 0; i < componentArr.length; i++) {
            int component = toPowerComponent(componentArr[i], false);
            if (component == INVALID_POWER_COMPONENT) {
                return componentArr[i] + " is not a valid power component";
            }
            if (components.indexOfKey(component) >= 0) {
                return componentArr[i] + " is specified more than oncee";
            }
            components.put(component, enabled);
        }
        return null;
    }

    private static int[] toIntArray(SparseBooleanArray array, boolean value) {
        int arraySize = array.size();
        int returnSize = 0;
        for (int i = 0; i < arraySize; i++) {
            if (array.valueAt(i) == value) returnSize++;
        }
        int[] ret = new int[returnSize];
        int count = 0;
        for (int i = 0; i < arraySize; i++) {
            if (array.valueAt(i) == value) {
                ret[count++] = array.keyAt(i);
            }
        }
        return ret;
    }

    @VisibleForTesting
    static final class PolicyXmlException extends Exception {
        PolicyXmlException(String message) {
            super(message);
        }
    }
}
