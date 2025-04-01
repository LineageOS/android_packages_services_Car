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

import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.feature.Flags;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.ICarPowerStateListener;
import android.car.settings.CarSettings;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.MockSettings;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.TetheringManager;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.SoftApCallback;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;

import com.android.car.CarLocalServices;
import com.android.car.CarServiceUtils;
import com.android.car.R;
import com.android.car.power.CarPowerManagementService;
import com.android.car.user.CarUserService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.concurrent.Executor;

@RunWith(MockitoJUnitRunner.class)
public class CarWifiServiceUnitTest extends AbstractExtendedMockitoTestCase {
    private static final SoftApConfiguration AP_CONFIG = new SoftApConfiguration.Builder()
            .build();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private CarUserService mCarUserService;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private WifiManager mWifiManager;
    @Mock
    private TetheringManager mTetheringManager;
    @Mock
    private CarPowerManagementService mCarPowerManagementService;
    @Mock
    private SharedPreferences mSharedPreferences;
    @Mock
    private SharedPreferences.Editor mSharedPreferencesEditor;
    private MockSettings mMockSettings;
    private CarPowerManagementService mOriginalCarPowerManagementService;
    private CarUserService mOriginalCarUserService;
    private CarWifiService mCarWifiService;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        mMockSettings = new MockSettings(builder);
    }

    @Before
    public void setUp() {
        mOriginalCarUserService = CarLocalServices.getService(CarUserService.class);
        CarLocalServices.removeServiceForTest(CarUserService.class);
        CarLocalServices.addService(CarUserService.class, mCarUserService);

        mOriginalCarPowerManagementService = CarLocalServices.getService(
                CarPowerManagementService.class);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class, mCarPowerManagementService);

        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mSharedPreferences);
        when(mSharedPreferences.edit()).thenReturn(mSharedPreferencesEditor);
        when(mSharedPreferencesEditor.putBoolean(anyString(), anyBoolean())).thenReturn(
                mSharedPreferencesEditor);
        when(mContext.getSystemService(WifiManager.class)).thenReturn(mWifiManager);
        when(mContext.getSystemService(TetheringManager.class)).thenReturn(mTetheringManager);
        when(mResources.getBoolean(R.bool.config_enablePersistTetheringCapabilities)).thenReturn(
                true);
        when(mWifiManager.getSoftApConfiguration()).thenReturn(AP_CONFIG);
        mMockSettings.putString(CarSettings.Global.ENABLE_PERSISTENT_TETHERING, "false");
        mSetFlagsRule.enableFlags(Flags.FLAG_PERSIST_AP_SETTINGS);

        mCarWifiService = new CarWifiService(mContext);
    }

    @After
    public void tearDown() throws Exception {
        mCarWifiService.release();
        CarServiceUtils.quitHandlerThreads();

        CarLocalServices.removeServiceForTest(CarUserService.class);
        CarLocalServices.addService(CarUserService.class, mOriginalCarUserService);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class,
                mOriginalCarPowerManagementService);
    }

    @Test
    public void testCanControlPersistTetheringSettings_capabilityTrue_returnsTrue() {
        mCarWifiService.init();

        boolean result = mCarWifiService.canControlPersistTetheringSettings();
        expectWithMessage("Can control persist tethering settings").that(result).isTrue();
    }

    @Test
    public void testCanControlPersistTetheringSettings_capabilityFalse_returnsFalse() {
        when(mResources.getBoolean(R.bool.config_enablePersistTetheringCapabilities)).thenReturn(
                false);

        mCarWifiService = new CarWifiService(mContext);
        mCarWifiService.init();

        boolean result = mCarWifiService.canControlPersistTetheringSettings();
        expectWithMessage("Can control persist tethering settings").that(result).isFalse();
    }

    @Test
    public void testPersistCarSettingOn_userUnlockBeforePowerOn_tetheringOn() throws Exception {
        mMockSettings.putString(CarSettings.Global.ENABLE_PERSISTENT_TETHERING, "true");
        when(mSharedPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);

        mCarWifiService = new CarWifiService(mContext);
        mCarWifiService.init();
        getUserLifecycleListener().run();
        getCarPowerStateListener().onStateChanged(CarPowerManager.STATE_ON, 0);
        getSoftApCallback().onStateChanged(WIFI_AP_STATE_ENABLED, 0);

        verify(mTetheringManager).startTethering(eq(TetheringManager.TETHERING_WIFI), any(
                Executor.class), any(TetheringManager.StartTetheringCallback.class));
        expectWithMessage("Autoshutdown Enabled").that(
                getApConfig().isAutoShutdownEnabled()).isFalse();
    }

    @Test
    public void testPersistCarSettingOn_notOnLast_noTethering() throws Exception {
        mMockSettings.putString(CarSettings.Global.ENABLE_PERSISTENT_TETHERING, "true");
        when(mSharedPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(false);

        mCarWifiService = new CarWifiService(mContext);
        mCarWifiService.init();
        getUserLifecycleListener().run();
        getCarPowerStateListener().onStateChanged(CarPowerManager.STATE_ON, 0);

        verify(mTetheringManager, never()).startTethering(eq(TetheringManager.TETHERING_WIFI), any(
                Executor.class), any(TetheringManager.StartTetheringCallback.class));
    }

    @Test
    public void testPersistCarSettingOn_tetheringAlreadyEnabled_noTethering() throws Exception {
        mMockSettings.putString(CarSettings.Global.ENABLE_PERSISTENT_TETHERING, "true");
        when(mSharedPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(false);
        when(mWifiManager.isWifiApEnabled()).thenReturn(true);

        mCarWifiService = new CarWifiService(mContext);
        mCarWifiService.init();
        getUserLifecycleListener().run();
        getCarPowerStateListener().onStateChanged(CarPowerManager.STATE_ON, 0);

        verify(mTetheringManager, never()).startTethering(eq(TetheringManager.TETHERING_WIFI), any(
                Executor.class), any(TetheringManager.StartTetheringCallback.class));
    }

    @Test
    public void testPersistCarSettingOn_powerOnBeforeUserUnlock_tetheringOn() throws Exception {
        mMockSettings.putString(CarSettings.Global.ENABLE_PERSISTENT_TETHERING, "true");
        when(mSharedPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        when(mCarPowerManagementService.getPowerState()).thenReturn(CarPowerManager.STATE_ON);

        mCarWifiService = new CarWifiService(mContext);
        mCarWifiService.init();
        getCarPowerStateListener().onStateChanged(CarPowerManager.STATE_ON, 0);
        getUserLifecycleListener().run();
        getSoftApCallback().onStateChanged(WIFI_AP_STATE_ENABLED, 0);

        verify(mTetheringManager).startTethering(eq(TetheringManager.TETHERING_WIFI), any(
                Executor.class), any(TetheringManager.StartTetheringCallback.class));
        expectWithMessage("Autoshutdown Enabled").that(
                getApConfig().isAutoShutdownEnabled()).isFalse();
    }

    @Test
    public void testPersistCarSettingOff_powerOnBeforeUserUnlock_noTethering() throws Exception {
        when(mCarPowerManagementService.getPowerState()).thenReturn(CarPowerManager.STATE_ON);

        mCarWifiService = new CarWifiService(mContext);
        mCarWifiService.init();
        getCarPowerStateListener().onStateChanged(CarPowerManager.STATE_ON, 0);
        getUserLifecycleListener().run();

        verify(mTetheringManager, never()).startTethering(eq(TetheringManager.TETHERING_WIFI), any(
                Executor.class), any(TetheringManager.StartTetheringCallback.class));
    }

    @Test
    public void testPersistCarSettingOff_userUnlockBeforePowerOn_noTethering() throws Exception {
        mCarWifiService.init();
        getUserLifecycleListener().run();
        getCarPowerStateListener().onStateChanged(CarPowerManager.STATE_ON, 0);

        verify(mTetheringManager, never()).startTethering(eq(TetheringManager.TETHERING_WIFI), any(
                Executor.class), any(TetheringManager.StartTetheringCallback.class));
    }

    @Test
    public void testPersistCarSettingChange_withCapability_autoShutdownFalse() throws Exception {
        when(mSharedPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);

        mCarWifiService = new CarWifiService(mContext);
        mCarWifiService.init();

        mMockSettings.putString(CarSettings.Global.ENABLE_PERSISTENT_TETHERING, "true");
        getSettingsObserver().onChange(/* selfChange= */ false);
        expectWithMessage("Autoshutdown Enabled").that(
                getApConfig().isAutoShutdownEnabled()).isFalse();
    }

    private ICarPowerStateListener getCarPowerStateListener() {
        ArgumentCaptor<ICarPowerStateListener> internalListenerCaptor =
                ArgumentCaptor.forClass(ICarPowerStateListener.class);
        verify(mCarPowerManagementService).registerListener(
                internalListenerCaptor.capture());
        return internalListenerCaptor.getValue();
    }

    private Runnable getUserLifecycleListener() {
        ArgumentCaptor<Runnable> internalListenerCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        verify(mCarUserService).runOnUser0Unlock(
                internalListenerCaptor.capture());
        return internalListenerCaptor.getValue();
    }

    private ContentObserver getSettingsObserver() {
        ArgumentCaptor<ContentObserver> captor = ArgumentCaptor.forClass(ContentObserver.class);
        verify(mContentResolver).registerContentObserver(
                eq(Settings.Global.getUriFor(CarSettings.Global.ENABLE_PERSISTENT_TETHERING)),
                eq(false), captor.capture());
        return captor.getValue();
    }

    private SoftApConfiguration getApConfig() {
        ArgumentCaptor<SoftApConfiguration> captor = ArgumentCaptor.forClass(
                SoftApConfiguration.class);
        verify(mWifiManager).setSoftApConfiguration(captor.capture());
        return captor.getValue();
    }

    private SoftApCallback getSoftApCallback() {
        ArgumentCaptor<SoftApCallback> captor = ArgumentCaptor.forClass(
                SoftApCallback.class);
        verify(mWifiManager).registerSoftApCallback(any(Executor.class), captor.capture());
        return captor.getValue();
    }
}
