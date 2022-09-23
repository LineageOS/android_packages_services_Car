/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.hal.fakevhal;

import android.annotation.Nullable;
import android.car.builtin.util.Slogf;
import android.hardware.automotive.vehicle.RawPropValues;
import android.hardware.automotive.vehicle.StatusCode;
import android.hardware.automotive.vehicle.SubscribeOptions;
import android.hardware.automotive.vehicle.VehicleArea;
import android.hardware.automotive.vehicle.VehicleAreaConfig;
import android.hardware.automotive.vehicle.VehiclePropConfig;
import android.hardware.automotive.vehicle.VehiclePropValue;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.VehiclePropertyType;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.car.IVehicleDeathRecipient;
import com.android.car.VehicleStub;
import com.android.car.hal.AidlHalPropConfig;
import com.android.car.hal.HalAreaConfig;
import com.android.car.hal.HalClientCallback;
import com.android.car.hal.HalPropConfig;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FakeVehicleStub represents a fake Vhal implementation.
 */
public final class FakeVehicleStub extends VehicleStub {

    private static final String TAG = CarLog.tagFor(FakeVehicleStub.class);
    // TODO(b/241006476) Add a list of all special properties to constant SPECIAL_PROPERTIES.
    private static final List<Integer> SPECIAL_PROPERTIES = Arrays.asList();
    private static final String FAKE_VHAL_CONFIG_DIRECTORY = "/data/system/car/fake_vhal_config/";
    private static final String DEFAULT_CONFIG_FILE_NAME = "DefaultProperties.json";
    private static final String FAKE_MODE_ENABLE_FILE_NAME = "ENABLE";
    private static final int AREA_ID_GLOBAL = 0;

    private final SparseArray<ConfigDeclaration> mConfigDeclarationsByPropId;
    private final SparseArray<HalPropConfig> mPropConfigsByPropId;
    private final VehicleStub mRealVehicle;
    private final HalPropValueBuilder mHalPropValueBuilder;
    private final FakeVhalConfigParser mParser;
    private final List<File> mCustomConfigFiles;
    private final Handler mHandler;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<Pair<Integer, Integer>, HalPropValue> mPropValuesByPropIdAreaId;
    @GuardedBy("mLock")
    private final Map<Pair<Integer, Integer>, Set<FakeVhalSubscriptionClient>>
            mOnChangeSubscribeClientByPropIdAreaId;
    @GuardedBy("mLock")
    private final Map<FakeVhalSubscriptionClient,
            Map<Pair<Integer, Integer>, ContinuousPropUpdater>> mUpdaterByPropIdAreaIdByClient;

    /**
     * Checks if fake mode is enabled.
     *
     * @return {@code true} if ENABLE file exists.
     */
    public static boolean doesEnableFileExist() {
        return new File(FAKE_VHAL_CONFIG_DIRECTORY + FAKE_MODE_ENABLE_FILE_NAME).exists();
    }

    /**
     * Initializes a {@link FakeVehicleStub} instance.
     *
     * @param realVehicle The real Vhal to be connected to handle special properties.
     * @throws IOException if unable to read the config file stream.
     * @throws IllegalArgumentException if a JSONException is caught or some parsing error occurred.
     */
    public FakeVehicleStub(VehicleStub realVehicle) throws IOException, IllegalArgumentException {
        this(realVehicle, new FakeVhalConfigParser(), getCustomConfigFiles());
    }

    /**
     * Initializes a {@link FakeVehicleStub} instance with {@link FakeVhalConfigParser} for testing.
     *
     * @param realVehicle The real Vhal to be connected to handle special properties.
     * @param parser The parser to parse config files.
     * @param customConfigFiles The {@link List} of custom config files.
     * @throws IOException if unable to read the config file stream.
     * @throws IllegalArgumentException if a JSONException is caught or some parsing error occurred.
     */
    @VisibleForTesting
    FakeVehicleStub(VehicleStub realVehicle, FakeVhalConfigParser parser,
            List<File> customConfigFiles) throws IOException, IllegalArgumentException {
        mHalPropValueBuilder = new HalPropValueBuilder(/* isAidl= */ true);
        mParser = parser;
        mCustomConfigFiles = customConfigFiles;
        mConfigDeclarationsByPropId = parseConfigFiles();
        mPropConfigsByPropId = extractPropConfigs(mConfigDeclarationsByPropId);
        mPropValuesByPropIdAreaId = extractPropValues(mConfigDeclarationsByPropId);
        mRealVehicle = realVehicle;
        mHandler = new Handler(CarServiceUtils.getHandlerThread(getClass().getSimpleName())
                .getLooper());
        mOnChangeSubscribeClientByPropIdAreaId = new ArrayMap<>();
        mUpdaterByPropIdAreaIdByClient = new ArrayMap<>();
        Slogf.d(TAG, "A FakeVehicleStub instance is created.");
    }

    /**
     * FakeVehicleStub is neither an AIDL VHAL nor HIDL VHAL. But it acts like an AIDL VHAL.
     *
     * @return {@code true} since FakeVehicleStub acts like an AIDL VHAL.
     */
    @Override
    public boolean isAidlVhal() {
        return true;
    }

    /**
     * Gets {@link HalPropValueBuilder} for building a {@link HalPropValue}.
     *
     * @return a builder to build a {@link HalPropValue}.
     */
    @Override
    public HalPropValueBuilder getHalPropValueBuilder() {
        return mHalPropValueBuilder;
    }

    /**
     * Gets properties asynchronously.
     *
     * @param getVehicleStubAsyncRequests The async request list.
     * @param getVehicleStubAsyncCallback The call back for getting property values.
     */
    @Override
    public void getAsync(List<GetVehicleStubAsyncRequest> getVehicleStubAsyncRequests,
            GetVehicleStubAsyncCallback getVehicleStubAsyncCallback) {
        // TODO(b/238646350)
    }

    /**
     * Checks if FakeVehicleStub connects to a valid Vhal.
     *
     * @return {@code true} if connects to a valid Vhal.
     */
    @Override
    public boolean isValid() {
        return mRealVehicle.isValid();
    }

    /**
     * Gets the interface descriptor for the connecting vehicle HAL.
     *
     * @throws IllegalStateException If unable to get the descriptor.
     */
    @Override
    public String getInterfaceDescriptor() throws IllegalStateException {
        return "com.android.car.hal.fakevhal.FakeVehicleStub";
    }

    /**
     * Registers a death recipient that would be called when Vhal died.
     *
     * @param recipient A death recipient.
     * @throws IllegalStateException If unable to register the death recipient.
     */
    @Override
    public void linkToDeath(IVehicleDeathRecipient recipient) throws IllegalStateException {
        // TODO(b/238646350)
    }

    /**
     * Unlinks a previously linked death recipient.
     *
     * @param recipient A previously linked death recipient.
     */
    @Override
    public void unlinkToDeath(IVehicleDeathRecipient recipient) {
        // TODO(b/238646350)
    }

    /**
     * Gets all property configs.
     *
     * @return an array of all property configs.
     */
    @Override
    public HalPropConfig[] getAllPropConfigs() {
        HalPropConfig[] propConfigs = new HalPropConfig[mPropConfigsByPropId.size()];
        for (int i = 0; i < mPropConfigsByPropId.size(); i++) {
            propConfigs[i] = mPropConfigsByPropId.valueAt(i);
        }
        return propConfigs;
    }

    /**
     * Gets a new {@code SubscriptionClient} that could be used to subscribe/unsubscribe.
     *
     * @param callback A callback that could be used to receive events.
     * @return a {@code SubscriptionClient} that could be used to subscribe/unsubscribe.
     */
    @Override
    public SubscriptionClient newSubscriptionClient(HalClientCallback callback) {
        return new FakeVhalSubscriptionClient(callback);
    }

    /**
     * Gets a property value.
     *
     * @param requestedPropValue The property to get.
     * @return the property value.
     * @throws ServiceSpecificException if propId or areaId is not supported.
     */
    @Override
    @Nullable
    public HalPropValue get(HalPropValue requestedPropValue) throws ServiceSpecificException {
        int propId = requestedPropValue.getPropId();
        checkPropIdSupported(propId);
        int areaId = isPropertyGlobal(propId) ? AREA_ID_GLOBAL : requestedPropValue.getAreaId();
        checkAreaIdSupported(propId, areaId);
        // Check access permission.
        int access = mPropConfigsByPropId.get(propId).getAccess();
        if (access != VehiclePropertyAccess.READ && access != VehiclePropertyAccess.READ_WRITE) {
            throw new ServiceSpecificException(StatusCode.ACCESS_DENIED, "This property " + propId
                    + " doesn't have read permission.");
        }

        if (isSpecialProperty(propId)) {
            // TODO(b/241006476) Handle special properties.
            Slogf.w(TAG, "Special property is not supported.");
            return null;
        }

        // PropId config exists but the value map doesn't have this propId, this may be caused by:
        // 1. This property is a global property, and it doesn't have default prop value.
        // 2. This property has area configs, and it has neither default prop value nor area value.
        Pair<Integer, Integer> propIdAreaId = Pair.create(propId, areaId);
        synchronized (mLock) {
            if (!mPropValuesByPropIdAreaId.containsKey(propIdAreaId)) {
                if (isPropertyGlobal(propId)) {
                    throw new ServiceSpecificException(StatusCode.NOT_AVAILABLE,
                        "propId: " + propId + " has no property value.");
                }
                throw new ServiceSpecificException(StatusCode.NOT_AVAILABLE,
                    "propId: " + propId + ", areaId: " + areaId + " has no property value.");
            }
            return mPropValuesByPropIdAreaId.get(propIdAreaId);
        }
    }

    /**
     * Sets a property value.
     *
     * @param propValue The property to set.
     * @throws ServiceSpecificException if propId or areaId is not supported.
     */
    @Override
    public void set(HalPropValue propValue) throws ServiceSpecificException {
        int propId = propValue.getPropId();
        checkPropIdSupported(propId);
        int areaId = isPropertyGlobal(propId) ? AREA_ID_GLOBAL : propValue.getAreaId();
        checkAreaIdSupported(propId, areaId);
        // Check access permission.
        int access = mPropConfigsByPropId.get(propId).getAccess();
        if (access != VehiclePropertyAccess.WRITE && access != VehiclePropertyAccess.READ_WRITE) {
            throw new ServiceSpecificException(StatusCode.ACCESS_DENIED, "This property " + propId
                + " doesn't have write permission.");
        }

        if (isSpecialProperty(propValue.getPropId())) {
            // TODO(b/241006476) Handle special properties.
            Slogf.w(TAG, "Special property is not supported.");
            return;
        }

        RawPropValues rawPropValues = ((VehiclePropValue) propValue.toVehiclePropValue()).value;

        // Check if the set values are within the value config range.
        if (!withinRange(propId, areaId, rawPropValues)) {
            throw new ServiceSpecificException(StatusCode.INVALID_ARG,
                    "The set value is outside the range.");
        }

        HalPropValue updatedValue = buildHalPropValue(propId, areaId,
                SystemClock.elapsedRealtimeNanos(), rawPropValues);
        Pair<Integer, Integer> propIdAreaId = Pair.create(propId, areaId);
        Set<FakeVhalSubscriptionClient> clients = new ArraySet<>();

        synchronized (mLock) {
            mPropValuesByPropIdAreaId.put(propIdAreaId, updatedValue);
            if (mOnChangeSubscribeClientByPropIdAreaId.containsKey(propIdAreaId)) {
                clients = mOnChangeSubscribeClientByPropIdAreaId.get(propIdAreaId);
            }
        }
        clients.forEach(c -> c.onPropertyEvent(updatedValue));
    }

    /**
     * Dumps VHAL debug information.
     *
     * @param fd The file descriptor to print output.
     * @param args Optional additional arguments for the debug command. Can be empty.
     * @throws RemoteException if the remote operation fails.
     * @throws ServiceSpecificException if VHAL returns service specific error.
     */
    @Override
    public void dump(FileDescriptor fd, List<String> args) throws RemoteException,
            ServiceSpecificException {
        // TODO(b/238646350)
    }

    /**
     * @return {@code true} if car service is connected to FakeVehicleStub.
     */
    @Override
    public boolean isFakeModeEnabled() {
        return true;
    }

    private final class FakeVhalSubscriptionClient implements SubscriptionClient {
        private final HalClientCallback mCallBack;

        FakeVhalSubscriptionClient(HalClientCallback callback) {
            mCallBack = callback;
            Slogf.d(TAG, "A FakeVhalSubscriptionClient instance is created.");
        }

        public void onPropertyEvent(HalPropValue value) {
            mCallBack.onPropertyEvent(new ArrayList(List.of(value)));
        }

        @Override
        public void subscribe(SubscribeOptions[] options) {
            FakeVehicleStub.this.subscribe(this, options);
        }

        @Override
        public void unsubscribe(int propId) {
            // Check if this propId is supported.
            checkPropIdSupported(propId);
            // Check if this propId is a special property.
            if (isSpecialProperty(VehicleProperty.INVALID)) {
                // TODO(b/241006476) Handle special properties.
                Slogf.w(TAG, "Special property is not supported.");
                return;
            }
            FakeVehicleStub.this.unsubscribe(this, propId);
        }
    }

    private final class ContinuousPropUpdater implements Runnable {
        private final FakeVhalSubscriptionClient mClient;
        private final int mPropId;
        private final int mAreaId;
        private final float mSampleRate;

        ContinuousPropUpdater(FakeVhalSubscriptionClient client, int propId, int areaId,
                float sampleRate) {
            mClient = client;
            mPropId = propId;
            mAreaId = areaId;
            mSampleRate = sampleRate;
            mHandler.post(this);
            Slogf.d(TAG, "A runnable updater is created for CONTINUOUS property.");
        }

        @Override
        public void run() {
            mClient.onPropertyEvent(updateTimeStamp(mPropId, mAreaId));
            mHandler.postDelayed(this, (long) (1000 / mSampleRate));
        }
    }

    /**
     * Parses default and custom config files.
     *
     * @return a {@link SparseArray} mapped from propId to its {@link ConfigDeclaration}.
     * @throws IOException if FakeVhalConfigParser throws IOException.
     * @throws IllegalArgumentException If default file doesn't exist or parsing errors occurred.
     */
    private SparseArray<ConfigDeclaration> parseConfigFiles() throws IOException,
            IllegalArgumentException {
        InputStream defaultConfigInputStream = this.getClass().getClassLoader()
                .getResourceAsStream(DEFAULT_CONFIG_FILE_NAME);
        SparseArray<ConfigDeclaration> configDeclarations;
        SparseArray<ConfigDeclaration> customConfigDeclarations;
        // Parse default config file.
        configDeclarations = mParser.parseJsonConfig(defaultConfigInputStream);

        // Parse all custom config files.
        for (int i = 0; i < mCustomConfigFiles.size(); i++) {
            File customFile = mCustomConfigFiles.get(i);
            try {
                customConfigDeclarations = mParser.parseJsonConfig(customFile);
            } catch (Exception e) {
                Slogf.w(TAG, e, "Failed to parse custom config file: %s",
                        customFile.getPath());
                continue;
            }
            combineConfigDeclarations(configDeclarations, customConfigDeclarations);
        }

        return configDeclarations;
    }

    /**
     * Gets all custom config files which are going to be parsed.
     *
     * @return a {@link List} of files.
     */
    private static List<File> getCustomConfigFiles() throws IOException {
        List<File> customConfigFileList = new ArrayList<>();
        File file = new File(FAKE_VHAL_CONFIG_DIRECTORY + FAKE_MODE_ENABLE_FILE_NAME);
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                customConfigFileList.add(new File(FAKE_VHAL_CONFIG_DIRECTORY
                        + line.replaceAll("\\.\\.", "").replaceAll("\\/", "")));
            }
        }
        return customConfigFileList;
    }

    /**
     * Combines parsing results together.
     *
     * @param result The {@link SparseArray} to gets new property configs.
     * @param newList The {@link SparseArray} whose property config will be added to result.
     * @return a combined {@link SparseArray} result.
     */
    private static SparseArray<ConfigDeclaration> combineConfigDeclarations(
            SparseArray<ConfigDeclaration> result, SparseArray<ConfigDeclaration> newList) {
        for (int i = 0; i < newList.size(); i++) {
            result.put(newList.keyAt(i), newList.valueAt(i));
        }
        return result;
    }

    /**
     * Extracts {@link HalPropConfig} for all properties from the parsing result.
     *
     * @param configDeclarationsByPropId The parsing result.
     * @return a {@link SparseArray} mapped from propId to its configs.
     */
    private SparseArray<HalPropConfig> extractPropConfigs(SparseArray<ConfigDeclaration>
            configDeclarationsByPropId) {
        SparseArray<HalPropConfig> propConfigsByPropId =
                new SparseArray<>(/* initialCapacity= */ 0);
        for (int i = 0; i < configDeclarationsByPropId.size(); i++) {
            VehiclePropConfig vehiclePropConfig = configDeclarationsByPropId.valueAt(i).getConfig();
            propConfigsByPropId.put(vehiclePropConfig.prop,
                    new AidlHalPropConfig(vehiclePropConfig));
        }
        return propConfigsByPropId;
    }

    /**
     * Extracts {@link HalPropValue} for all properties from the parsing result.
     *
     * @param configDeclarationsByPropId The parsing result.
     * @return a {@link Map} mapped from propId, areaId to its value.
     */
    private Map<Pair<Integer, Integer>, HalPropValue> extractPropValues(
            SparseArray<ConfigDeclaration> configDeclarationsByPropId) {
        long timestamp = SystemClock.elapsedRealtimeNanos();
        Map<Pair<Integer, Integer>, HalPropValue> propValuesByPropIdAreaId = new ArrayMap<>();
        for (int i = 0; i < configDeclarationsByPropId.size(); i++) {
            // Get configDeclaration of a property.
            ConfigDeclaration configDeclaration = configDeclarationsByPropId.valueAt(i);
            // Get propId.
            int propId = configDeclaration.getConfig().prop;
            // Get areaConfigs array to know what areaIds are supported.
            VehicleAreaConfig[] areaConfigs = configDeclaration.getConfig().areaConfigs;
            // Get default rawPropValues.
            RawPropValues defaultRawPropValues = configDeclaration.getInitialValue();
            // Get area rawPropValues map.
            SparseArray<RawPropValues> rawPropValuesByAreaId = configDeclaration
                    .getInitialAreaValuesByAreaId();

            // If this property is a global property.
            if (isPropertyGlobal(propId)) {
                // If no default prop value exists, this propId won't be added to the
                // propValuesByAreaIdByPropId map. Get this propId value will throw
                // ServiceSpecificException with StatusCode.INVALID_ARG.
                if (defaultRawPropValues == null) {
                    continue;
                }
                // Set the areaId to be 0.
                propValuesByPropIdAreaId.put(Pair.create(propId, AREA_ID_GLOBAL),
                        buildHalPropValue(propId, AREA_ID_GLOBAL, timestamp, defaultRawPropValues));
                continue;
            }

            // If this property has supported area configs.
            for (int j = 0; j < areaConfigs.length; j++) {
                // Get areaId.
                int areaId = areaConfigs[j].areaId;
                // Set default area prop value to be defaultRawPropValues. If area value doesn't
                // exist, then use the property default value.
                RawPropValues areaRawPropValues = defaultRawPropValues;
                // If area prop value exists, then use area value.
                if (rawPropValuesByAreaId.contains(areaId)) {
                    areaRawPropValues = rawPropValuesByAreaId.get(areaId);
                }
                // Neither area prop value nor default prop value exists. This propId won't be in
                // the value map. Get this propId value will throw ServiceSpecificException
                // with StatusCode.INVALID_ARG.
                if (areaRawPropValues == null) {
                    continue;
                }
                propValuesByPropIdAreaId.put(Pair.create(propId, areaId), buildHalPropValue(propId,
                        areaId, timestamp, areaRawPropValues));
            }
        }
        return propValuesByPropIdAreaId;
    }

    /**
     * Checks if a property is a global property.
     *
     * @param propId The property to be checked.
     * @return {@code true} if this property is a global property.
     */
    private boolean isPropertyGlobal(int propId) {
        return (propId & VehicleArea.MASK) == VehicleArea.GLOBAL;
    }

    /**
     * Builds a {@link HalPropValue}.
     *
     * @param propId The propId of the prop value to be built.
     * @param areaId The areaId of the prop value to be built.
     * @param timestamp The elapsed time in nanoseconds when mPropConfigsByPropId is initialized.
     * @param rawPropValues The {@link RawPropValues} contains property values.
     * @return a {@link HalPropValue} built by propId, areaId, timestamp and value.
     */
    private HalPropValue buildHalPropValue(int propId, int areaId, long timestamp,
            RawPropValues rawPropValues) {
        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = propId;
        propValue.areaId = areaId;
        propValue.timestamp = timestamp;
        propValue.value = rawPropValues;
        return mHalPropValueBuilder.build(propValue);
    }

    /**
     * Checks if a property is a special property.
     *
     * @param propId to be checked.
     * @return {@code true} if the property is special.
     */
    private static boolean isSpecialProperty(int propId) {
        return SPECIAL_PROPERTIES.contains(propId);
    }

    /**
     * Generates a list of all supported areaId for a certain property.
     *
     * @param propId The property to get all supported areaIds.
     * @return A {@link List} of all supported areaId.
     */
    private List<Integer> getAllSupportedAreaId(int propId) {
        List<Integer> allSupportedAreaId = new ArrayList<>();
        HalAreaConfig[] areaConfigs = mPropConfigsByPropId.get(propId).getAreaConfigs();
        for (int i = 0; i < areaConfigs.length; i++) {
            allSupportedAreaId.add(areaConfigs[i].getAreaId());
        }
        return allSupportedAreaId;
    }

    /**
     * Checks if the set value is within the value range.
     *
     * @return {@code true} if set value is within the prop config range.
     */
    private boolean withinRange(int propId, int areaId, RawPropValues rawPropValues) {
        if (isPropertyGlobal(propId)) {
            // TODO(238646350) Handle global properties.
            return true;
        }

        // For non-global properties.
        int index = getAllSupportedAreaId(propId).indexOf(areaId);
        HalAreaConfig areaConfig = mPropConfigsByPropId.get(propId).getAreaConfigs()[index];

        int[] int32Values = rawPropValues.int32Values;
        long[] int64Values = rawPropValues.int64Values;
        float[] floatValues = rawPropValues.floatValues;
        // If max and min values exists, then check the boundaries. If max and min values are all
        // 0s, return true.
        switch (getPropType(propId)) {
            case VehiclePropertyType.INT32:
            case VehiclePropertyType.INT32_VEC:
                int minInt32Value = areaConfig.getMinInt32Value();
                int maxInt32Value = areaConfig.getMaxInt32Value();
                if (minInt32Value != maxInt32Value || minInt32Value != 0) {
                    for (int int32Value : int32Values) {
                        if (int32Value > maxInt32Value || int32Value < minInt32Value) {
                            Slogf.e(TAG, "For propId: %d, areaId: %d, the valid min value is: "
                                    + "%d, max value is: %d, but the given value is: %d.", propId,
                                    areaId, minInt32Value, maxInt32Value, int32Value);
                            return false;
                        }
                    }
                }
                break;
            case VehiclePropertyType.INT64:
            case VehiclePropertyType.INT64_VEC:
                long minInt64Value = areaConfig.getMinInt64Value();
                long maxInt64Value = areaConfig.getMaxInt64Value();
                if (minInt64Value != maxInt64Value || minInt64Value != 0) {
                    for (long int64Value : int64Values) {
                        if (int64Value > maxInt64Value || int64Value < minInt64Value) {
                            Slogf.e(TAG, "For propId: %d, areaId: %d, the valid min value is: "
                                    + "%d, max value is: %d, but the given value is: %d.", propId,
                                    areaId, minInt64Value, maxInt64Value, int64Value);
                            return false;
                        }
                    }
                }
                break;
            case VehiclePropertyType.FLOAT:
            case VehiclePropertyType.FLOAT_VEC:
                float minFloatValue = areaConfig.getMinFloatValue();
                float maxFloatValue = areaConfig.getMaxFloatValue();
                if (minFloatValue != maxFloatValue || minFloatValue != 0) {
                    for (float floatValue : floatValues) {
                        if (floatValue > maxFloatValue || floatValue < minFloatValue) {
                            Slogf.e(TAG, "For propId: %d, areaId: %d, the valid min value is: "
                                    + "%f, max value is: %f, but the given value is: %d.", propId,
                                    areaId, minFloatValue, maxFloatValue, floatValue);
                            return false;
                        }
                    }
                }
                break;
            default:
                Slogf.d(TAG, "Skip checking range for propId: %d because it is mixed type.",
                        propId);
        }
        return true;
    }

    /**
     * Gets the type of property.
     *
     * @param propId The property to get the type.
     * @return The type.
     */
    private static int getPropType(int propId) {
        return propId & VehiclePropertyType.MASK;
    }

    /**
     * Checks if a property is supported. If not, throw a {@link ServiceSpecificException}.
     *
     * @param propId The property to be checked.
     */
    private void checkPropIdSupported(int propId) {
        // Check if the property config exists.
        if (!mPropConfigsByPropId.contains(propId)) {
            throw new ServiceSpecificException(StatusCode.INVALID_ARG, "The propId: " + propId
                + " is not supported.");
        }
    }

    /**
     * Checks if an areaId of a property is supported. For global property, the areaId is ignored.
     *
     * @param propId The property to be checked.
     * @param areaId The area to be checked.
     */
    private void checkAreaIdSupported(int propId, int areaId) {
        // For global property, areaId is ignored.
        // For non-global property, if property config exists, check if areaId is supported.
        if (!isPropertyGlobal(propId) && !getAllSupportedAreaId(propId).contains(areaId)) {
            throw new ServiceSpecificException(StatusCode.INVALID_ARG, "The areaId: " + areaId
                + " is not supported.");
        }
    }

    /**
     * Subscribes properties.
     *
     * @param client The client subscribes properties.
     * @param options The array of subscribe options.
     */
    private void subscribe(FakeVhalSubscriptionClient client, SubscribeOptions[] options) {
        for (int i = 0; i < options.length; i++) {
            int propId = options[i].propId;

            // Check if this propId is supported.
            checkPropIdSupported(propId);

            // Check if this propId is a special property.
            if (isSpecialProperty(VehicleProperty.INVALID)) {
                // TODO(b/241006476) Handle special properties.
                Slogf.w(TAG, "Special property is not supported.");
                return;
            }

            int[] areaIds = isPropertyGlobal(propId) ? new int[]{AREA_ID_GLOBAL}
                : getSubscribedAreaIds(propId, options[i].areaIds);

            int changeMode = mPropConfigsByPropId.get(propId).getChangeMode();
            switch (changeMode) {
                case VehiclePropertyChangeMode.STATIC:
                    throw new ServiceSpecificException(StatusCode.INVALID_ARG,
                        "Static property cannot be subscribed.");
                case VehiclePropertyChangeMode.ON_CHANGE:
                    subscribeOnChangeProp(client, propId, areaIds);
                    break;
                case VehiclePropertyChangeMode.CONTINUOUS:
                    // Check if sample rate is within minSampleRate and maxSampleRate, and
                    // return a valid sample rate.
                    float sampleRate = getSampleRateWithinRange(options[i].sampleRate, propId);
                    subscribeContinuousProp(client, propId, areaIds, sampleRate);
                    break;
                default:
                    Slogf.w(TAG, "This change mode: %d is not supported.", changeMode);
            }
        }
    }

    /**
     * Subscribes an ON_CHANGE property.
     *
     * @param client The client that subscribes a property.
     * @param propId The property to be subscribed.
     * @param areaIds The list of areaIds to be subscribed.
     */
    private void subscribeOnChangeProp(FakeVhalSubscriptionClient client, int propId,
            int[] areaIds) {
        synchronized (mLock) {
            for (int areaId : areaIds) {
                checkAreaIdSupported(propId, areaId);
                Slogf.d(TAG, "FakeVhalSubscriptionClient subscribes ON_CHANGE property, "
                        + "propId: %d,  areaId: ", propId, areaId);
                Pair<Integer, Integer> propIdAreaId = Pair.create(propId, areaId);
                // Update the map from propId, areaId to client set in FakeVehicleStub.
                if (!mOnChangeSubscribeClientByPropIdAreaId.containsKey(propIdAreaId)) {
                    mOnChangeSubscribeClientByPropIdAreaId.put(propIdAreaId, new ArraySet<>());
                }
                mOnChangeSubscribeClientByPropIdAreaId.get(propIdAreaId).add(client);
            }
        }
    }

    /**
     * Subscribes a CONTINUOUS property.
     *
     * @param client The client that subscribes a property.
     * @param propId The property to be subscribed.
     * @param areaIds The list of areaIds to be subscribed.
     * @param sampleRate The rate of subscription.
     */
    private void subscribeContinuousProp(FakeVhalSubscriptionClient client, int propId,
            int[] areaIds, float sampleRate) {
        synchronized (mLock) {
            for (int areaId : areaIds) {
                checkAreaIdSupported(propId, areaId);
                Slogf.d(TAG, "FakeVhalSubscriptionClient subscribes CONTINUOUS property, "
                        + "propId: %d,  areaId: %d", propId, areaId);
                Pair<Integer, Integer> propIdAreaId = Pair.create(propId, areaId);

                // Check if this client has subscribed CONTINUOUS properties.
                if (!mUpdaterByPropIdAreaIdByClient.containsKey(client)) {
                    mUpdaterByPropIdAreaIdByClient.put(client, new ArrayMap<>());
                }
                Map<Pair<Integer, Integer>, ContinuousPropUpdater> updaterByPropIdAreaId =
                        mUpdaterByPropIdAreaIdByClient.get(client);
                // Check if this client subscribes the propId, areaId pair
                if (updaterByPropIdAreaId.containsKey(propIdAreaId)) {
                    // If current subscription rate is same as the new sample rate.
                    ContinuousPropUpdater oldUpdater = updaterByPropIdAreaId.get(propIdAreaId);
                    if (oldUpdater.mSampleRate == sampleRate) {
                        Slogf.w(TAG, "Sample rate is same as current rate. No update.");
                        continue;
                    }
                    // If sample rate is not same. Remove old updater from mHandler's message queue.
                    mHandler.removeCallbacks(oldUpdater);
                    updaterByPropIdAreaId.remove(propIdAreaId);
                }
                ContinuousPropUpdater updater = new ContinuousPropUpdater(client, propId, areaId,
                        sampleRate);
                updaterByPropIdAreaId.put(propIdAreaId, updater);
            }
        }
    }

    /**
     * Unsubscribes a property.
     *
     * @param client The client that unsubscribes this property.
     * @param propId The property to be unsubscribed.
     */
    private void unsubscribe(FakeVhalSubscriptionClient client, int propId) {
        int changeMode = mPropConfigsByPropId.get(propId).getChangeMode();
        switch (changeMode) {
            case VehiclePropertyChangeMode.STATIC:
                throw new ServiceSpecificException(StatusCode.INVALID_ARG,
                    "Static property cannot be unsubscribed.");
            case VehiclePropertyChangeMode.ON_CHANGE:
                unsubscribeOnChangeProp(client, propId);
                break;
            case VehiclePropertyChangeMode.CONTINUOUS:
                unsubscribeContinuousProp(client, propId);
                break;
            default:
                Slogf.w(TAG, "This change mode: %d is not supported.", changeMode);
        }
    }

    /**
     * Unsubscribes ON_CHANGE property.
     *
     * @param client The client that unsubscribes this property.
     * @param propId The property to be unsubscribed.
     */
    private void unsubscribeOnChangeProp(FakeVhalSubscriptionClient client, int propId) {
        synchronized (mLock) {
            List<Pair<Integer, Integer>> deletePairs = new ArrayList<>();
            for (Pair<Integer, Integer> propIdAreaId
                    : mOnChangeSubscribeClientByPropIdAreaId.keySet()) {
                if (propIdAreaId.first == propId) {
                    Set<FakeVhalSubscriptionClient> clientSet =
                            mOnChangeSubscribeClientByPropIdAreaId.get(propIdAreaId);
                    clientSet.remove(client);
                    Slogf.d(TAG, "FakeVhalSubscriptionClient unsubscribes ON_CHANGE property, "
                            + "propId: %d, areaId: %d", propId, propIdAreaId.second);
                    if (clientSet.isEmpty()) {
                        deletePairs.add(propIdAreaId);
                    }
                }
            }
            for (int i = 0; i < deletePairs.size(); i++) {
                mOnChangeSubscribeClientByPropIdAreaId.remove(deletePairs.get(i));
            }
        }
    }

    /**
     * Unsubscribes CONTINUOUS property.
     *
     * @param client The client that unsubscribes this property.
     * @param propId The property to be unsubscribed.
     */
    private void unsubscribeContinuousProp(FakeVhalSubscriptionClient client, int propId) {
        synchronized (mLock) {
            if (!mUpdaterByPropIdAreaIdByClient.containsKey(client)) {
                Slogf.w(TAG, "This client hasn't subscribed any CONTINUOUS property.");
                return;
            }
            List<Pair<Integer, Integer>> deletePairs = new ArrayList<>();
            Map<Pair<Integer, Integer>, ContinuousPropUpdater> updaterByPropIdAreaId =
                    mUpdaterByPropIdAreaIdByClient.get(client);
            for (Pair<Integer, Integer> propIdAreaId : updaterByPropIdAreaId.keySet()) {
                if (propIdAreaId.first == propId) {
                    mHandler.removeCallbacks(updaterByPropIdAreaId.get(propIdAreaId));
                    Slogf.d(TAG, "FakeVhalSubscriptionClient unsubscribes CONTINUOUS property,"
                            + " propId: %d,  areaId: %d", propId, propIdAreaId.second);
                    deletePairs.add(propIdAreaId);
                }
            }
            for (int i = 0; i < deletePairs.size(); i++) {
                updaterByPropIdAreaId.remove(deletePairs.get(i));
            }
            if (updaterByPropIdAreaId.isEmpty()) {
                mUpdaterByPropIdAreaIdByClient.remove(client);
            }
        }
    }

    /**
     * Gets the array of subscribed areaIds.
     *
     * @param propId The property to be subscribed.
     * @param areaIds The areaIds from SubscribeOptions.
     * @return an {@code array} of subscribed areaIds.
     */
    private int[] getSubscribedAreaIds(int propId, int[] areaIds) {
        if (areaIds != null && areaIds.length != 0) {
            return areaIds;
        }
        // If areaIds field  is empty or null, subscribe all supported areaIds.
        return CarServiceUtils.toIntArray(getAllSupportedAreaId(propId));
    }

    /**
     * Gets the subscription sample rate within range.
     *
     * @param sampleRate The requested sample rate.
     * @param propId The property to be subscribed.
     * @return The valid sample rate.
     */
    private float getSampleRateWithinRange(float sampleRate, int propId) {
        float minSampleRate = mPropConfigsByPropId.get(propId).getMinSampleRate();
        float maxSampleRate = mPropConfigsByPropId.get(propId).getMaxSampleRate();
        if (sampleRate < minSampleRate) {
            sampleRate = minSampleRate;
        }
        if (sampleRate > maxSampleRate) {
            sampleRate = maxSampleRate;
        }
        return sampleRate;
    }

    /**
     * Updates the timeStamp of a property.
     *
     * @param propId The property gets current timeStamp.
     * @param areaId The property with specific area gets current timeStamp.
     */
    private HalPropValue updateTimeStamp(int propId, int areaId) {
        synchronized (mLock) {
            Pair<Integer, Integer> propIdAreaId = Pair.create(propId, areaId);
            HalPropValue propValue = mPropValuesByPropIdAreaId.get(propIdAreaId);
            RawPropValues rawPropValues = ((VehiclePropValue) propValue.toVehiclePropValue()).value;
            HalPropValue updatedValue = buildHalPropValue(propId, areaId,
                    SystemClock.elapsedRealtimeNanos(), rawPropValues);
            mPropValuesByPropIdAreaId.put(propIdAreaId, updatedValue);
            return updatedValue;
        }
    }
}
