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
import android.hardware.automotive.vehicle.VehiclePropConfig;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.IVehicleDeathRecipient;
import com.android.car.VehicleStub;
import com.android.car.hal.AidlHalPropConfig;
import com.android.car.hal.HalClientCallback;
import com.android.car.hal.HalPropConfig;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    private final SparseArray<ConfigDeclaration> mConfigDeclarationsByPropId;
    private final SparseArray<HalPropConfig> mPropConfigsByPropId;
    private final SparseArray<SparseArray<HalPropValue>> mPropValuesByAreaIdByPropId;
    private final VehicleStub mRealVehicle;
    private final HalPropValueBuilder mHalPropValueBuilder;
    private final FakeVhalConfigParser mParser;
    private final List<String> mCustomConfigFilenames;

    /**
     * Checks if fake mode is enabled.
     *
     * @return {@code true} if ENABLE file exists.
     */
    public static boolean fakeModeEnabled() {
        return new File(FAKE_VHAL_CONFIG_DIRECTORY + FAKE_MODE_ENABLE_FILE_NAME).exists();
    }

    /**
     * Initializes a {@link FakeVehicleStub} instance.
     *
     * @param realVehicle The real Vhal to be connected to handle special properties.
     * @throws IOException if unable to read the config file stream.
     * @throws IllegalArgumentException if a JSONException is caught or some parsing error occurred.
     */
    FakeVehicleStub(VehicleStub realVehicle) throws IOException, IllegalArgumentException {
        this(realVehicle, new FakeVhalConfigParser(), getCustomConfigFiles());
    }

    /**
     * Initializes a {@link FakeVehicleStub} instance with {@link FakeVhalConfigParser} for testing.
     *
     * @param realVehicle The real Vhal to be connected to handle special properties.
     * @param parser The parser to parse config files.
     * @param customConfigFilenames The {@link List} of custom config file names.
     * @throws IOException if unable to read the config file stream.
     * @throws IllegalArgumentException if a JSONException is caught or some parsing error occurred.
     */
    @VisibleForTesting
    FakeVehicleStub(VehicleStub realVehicle, FakeVhalConfigParser parser,
            List<String> customConfigFilenames) throws IOException, IllegalArgumentException {
        mHalPropValueBuilder = new HalPropValueBuilder(/* isAidl= */ true);
        mParser = parser;
        mCustomConfigFilenames = customConfigFilenames;
        mConfigDeclarationsByPropId = parseConfigFiles();
        mPropConfigsByPropId = getPropConfigs(mConfigDeclarationsByPropId);
        mPropValuesByAreaIdByPropId = getPropValues(mConfigDeclarationsByPropId);
        mRealVehicle = realVehicle;
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
     * @param getAsyncVehicleStubCallback The call back for getting property values.
     */
    @Override
    public void getAsync(List<GetVehicleStubAsyncRequest> getVehicleStubAsyncRequests,
            GetAsyncVehicleStubCallback getAsyncVehicleStubCallback) {
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
        // TODO(b/238646350)
        return null;
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
        // TODO(b/238646350)
        if (isSpecialProperty(/* propId= */ VehicleProperty.INVALID)) {
            // TODO(b/241006476) Handle special properties.
        }
        return null;
    }

    /**
     * Gets a property value.
     *
     * @param requestedPropValue The property to get.
     * @return the property value.
     * @throws ServiceSpecificException if propId or areaId doesn't exist.
     */
    @Override
    @Nullable
    public HalPropValue get(HalPropValue requestedPropValue) {
        // TODO(b/238646350) Set a property value.
        if (isSpecialProperty(requestedPropValue.getPropId())) {
            // TODO(b/241006476) Handle special properties.
        }
        return null;
    }

    /**
     * Sets a property value.
     *
     * @param propValue The property to set.
     * @throws ServiceSpecificException if Vhal returns service specific error.
     */
    @Override
    public void set(HalPropValue propValue) throws ServiceSpecificException {
        // TODO(b/238646350) Set a property value.
        if (isSpecialProperty(propValue.getPropId())) {
            // TODO(b/241006476) Handle special properties.
        }
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
     * Parses default and custom config files.
     *
     * @return a {@link SparseArray} mapped from propId to its {@link ConfigDeclaration}.
     * @throws IOException if FakeVhalConfigParser throws IOException.
     * @throws IllegalArgumentException If default file doesn't exist or parsing errors occurred.
     */
    private SparseArray<ConfigDeclaration> parseConfigFiles() throws IOException,
            IllegalArgumentException {
        InputStream mDefaultConfigInputStream = mParser.getClass().getClassLoader()
                .getResourceAsStream(DEFAULT_CONFIG_FILE_NAME);
        SparseArray<ConfigDeclaration> configDeclarations;

        // Parse default config file.
        configDeclarations = mParser.parseJsonConfig(mDefaultConfigInputStream);

        // Parse all custom config files.
        for (int i = 0; i < mCustomConfigFilenames.size(); i++) {
            combineConfigDeclarations(configDeclarations,
                    mParser.parseJsonConfig(new File(mCustomConfigFilenames.get(i))));
        }

        return configDeclarations;
    }

    /**
     * Gets all custom config file names which are going to be parsed.
     *
     * @return a {@link List} of file names.
     */
    private static List<String> getCustomConfigFiles() {
        List<String> customConfigFileList = new ArrayList<>();
        // TODO(b/238646350) Read ENABLE file, get the filenames of all files listed in ENABLE file.
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
    private SparseArray<HalPropConfig> getPropConfigs(SparseArray<ConfigDeclaration>
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
     * @return a {@link SparseArray} mapped from propId to a map from areaId to its value.
     */
    private SparseArray<SparseArray<HalPropValue>> getPropValues(SparseArray<ConfigDeclaration>
            configDeclarationsByPropId) {
        // TODO(b/238646350) Implement this method to extract prop values for each property.
        return null;
    }

    /**
     * Checks if a property is a special property.
     *
     * @param propId to be checked.
     * @return {@code true} if the property is special.
     */
    private boolean isSpecialProperty(int propId) {
        return SPECIAL_PROPERTIES.contains(propId);
    }
}
