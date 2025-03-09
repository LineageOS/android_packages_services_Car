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

package com.android.car.power;

import static android.car.feature.Flags.FLAG_MULTI_DISPLAY_BRIGHTNESS_CONTROL;
import static android.hardware.automotive.vehicle.VehicleProperty.PER_DISPLAY_MAX_BRIGHTNESS;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.builtin.view.DisplayHelper;
import android.content.Context;
import android.hardware.automotive.vehicle.RawPropValues;
import android.hardware.automotive.vehicle.VehiclePropValue;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayAddress;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.car.MockedCarTestBase;
import com.android.car.hal.test.AidlMockedVehicleHal.VehicleHalPropertyHandler;
import com.android.car.provider.Settings;
import com.android.car.systeminterface.DisplayHelperInterface;
import com.android.car.systeminterface.DisplayInterface;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.WakeLockInterface;
import com.android.car.user.CurrentUserFetcher;
import com.android.internal.annotations.GuardedBy;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
@MediumTest
@EnableFlags(FLAG_MULTI_DISPLAY_BRIGHTNESS_CONTROL)
public class DisplayBrightnessTest extends MockedCarTestBase {

    @ClassRule public static final SetFlagsRule.ClassRule mClassRule = new SetFlagsRule.ClassRule();
    @Rule public final SetFlagsRule mSetFlagsRule = mClassRule.createSetFlagsRule();

    // This is the default display
    private static final int DISPLAY_ID_1 = 0;
    private static final int DISPLAY_PORT_1 = 2;
    private static final int DISPLAY_ID_2 = 3;
    private static final int DISPLAY_PORT_2 = 4;
    private static final int DISPLAY_ID_3 = 5;
    private static final int DISPLAY_PORT_3 = 6;
    private static final int MAX_BRIGHTNESS = 100;
    private static final float DISPLAY_MANAGER_BRIGHTNESS_1 = 0.2f;
    private static final float DISPLAY_MANAGER_BRIGHTNESS_2 = 0.3f;
    private static final int VHAL_BRIGHTNESS_1 = 32;
    private static final int VHAL_BRIGHTNESS_2 = 56;
    private static final int POWER_MGR_MAX_SCREEN_BRIGHTNESS = 255;
    private static final int POWER_MGR_MIN_SCREEN_BRIGHTNESS = 1;

    private static final int DEFAULT_TIMEOUT_MS = 5_000;

    @Mock
    private WakeLockInterface mWakeLock;

    @Mock
    private Settings mSettings;

    @Mock
    private CurrentUserFetcher mCurrentUserFetcher;

    @Mock
    private DisplayManager mDisplayManager;

    @Mock
    private PowerManager mPowerManager;

    private final class MockedContext extends MockedCarTestContext {
        MockedContext(Context base) {
            super(base);
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.DISPLAY_SERVICE.equals(name)) {
                return mDisplayManager;
            }
            if (Context.POWER_SERVICE.equals(name)) {
                return mPowerManager;
            }
            return super.getSystemService(name);
        }
    }

    @Override
    protected MockedCarTestContext createMockedCarTestContext(Context context) {
        return new MockedContext(context);
    }

    @Override
    protected SystemInterface.Builder getSystemInterfaceBuilder() {
        SystemInterface.Builder builder = super.getSystemInterfaceBuilder();
        DisplayInterface displayInterface = new DisplayInterface.DefaultImpl(
                getContext(), mWakeLock, mSettings,
                new DisplayHelperInterface.DefaultImpl(), mCurrentUserFetcher);
        return builder.withDisplayInterface(displayInterface).withSettings(mSettings);
    }

    private static final class BrightnessPropertyHandler implements VehicleHalPropertyHandler {

        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private final SparseIntArray mBrightnessByPort = new SparseIntArray();
        @GuardedBy("mLock")
        private boolean mPropertySet;

        @Override
        public void onPropertySet(VehiclePropValue value) {
            synchronized (mLock) {
                mBrightnessByPort.put(value.value.int32Values[0], value.value.int32Values[1]);
                mPropertySet = true;
                mLock.notify();
            }
        }

        @Override
        public VehiclePropValue onPropertyGet(VehiclePropValue value) {
            synchronized (mLock) {
                int displayPort = value.value.int32Values[0];
                int brightness = mBrightnessByPort.get(displayPort);
                value.value.int32Values[1] = brightness;
                value.timestamp = SystemClock.elapsedRealtimeNanos();
                return value;
            }
        }

        int getBrightness(int displayPort) {
            synchronized (mLock) {
                return mBrightnessByPort.get(displayPort);
            }
        }

        void resetPropertySet() {
            synchronized (mLock) {
                mPropertySet = false;
            }
        }

        void waitForPropertySet(int timeoutInMs) throws InterruptedException {
            synchronized (mLock) {
                while (!mPropertySet) {
                    mLock.wait(timeoutInMs);
                }
            }
        }
    }

    private final BrightnessPropertyHandler mBrightnessPropertyHandler =
            new BrightnessPropertyHandler();

    @Override
    protected void configureMockedHal() {
        // Simulate two displays with port 1, 2.
        // The max brightness is 100.
        addAidlProperty(VehicleProperty.PER_DISPLAY_BRIGHTNESS, mBrightnessPropertyHandler);
        VehiclePropValue value = new VehiclePropValue();
        value.prop = PER_DISPLAY_MAX_BRIGHTNESS;
        value.areaId = 0;
        value.value = new RawPropValues();
        value.value.int32Values = new int[]{
                DISPLAY_PORT_1, MAX_BRIGHTNESS, DISPLAY_PORT_2, MAX_BRIGHTNESS};
        addAidlStaticProperty(PER_DISPLAY_MAX_BRIGHTNESS, value);

        Display physicalDisplay1 = createMockDisplay(DISPLAY_ID_1, DISPLAY_PORT_1,
                DisplayHelper.TYPE_INTERNAL);
        Display physicalDisplay2 = createMockDisplay(DISPLAY_ID_2, DISPLAY_PORT_2,
                DisplayHelper.TYPE_INTERNAL);
        Display virtualDisplay = createMockDisplay(DISPLAY_ID_3, DISPLAY_PORT_3,
                DisplayHelper.TYPE_VIRTUAL);

        when(mDisplayManager.getDisplays()).thenReturn(new Display[]{physicalDisplay1,
                physicalDisplay2, virtualDisplay});
        when(mDisplayManager.getDisplay(DISPLAY_ID_1)).thenReturn(physicalDisplay1);
        when(mDisplayManager.getDisplay(DISPLAY_ID_2)).thenReturn(physicalDisplay2);
        when(mDisplayManager.getDisplay(DISPLAY_ID_3)).thenReturn(virtualDisplay);

        when(mDisplayManager.getBrightness(DISPLAY_ID_1)).thenReturn(DISPLAY_MANAGER_BRIGHTNESS_1);
        when(mDisplayManager.getBrightness(DISPLAY_ID_2)).thenReturn(DISPLAY_MANAGER_BRIGHTNESS_2);

        when(mPowerManager.getMaximumScreenBrightnessSetting()).thenReturn(
                POWER_MGR_MAX_SCREEN_BRIGHTNESS);
        when(mPowerManager.getMinimumScreenBrightnessSetting()).thenReturn(
                POWER_MGR_MIN_SCREEN_BRIGHTNESS);
    }

    @Test
    public void testRefreshDisplayBrightnessOnInit() {
        int brightnessForDisplay1 = mBrightnessPropertyHandler.getBrightness(DISPLAY_PORT_1);
        int brightnessForDisplay2 = mBrightnessPropertyHandler.getBrightness(DISPLAY_PORT_2);

        assertWithMessage("Virtual display brightness must not be set").that(
                mBrightnessPropertyHandler.getBrightness(DISPLAY_ID_3)).isEqualTo(0);
        assertWithMessage("display 1 brightness must be set").that(brightnessForDisplay1)
                .isGreaterThan(0);
        assertWithMessage("display 2 brightness must be set").that(brightnessForDisplay2)
                .isGreaterThan(0);
        assertWithMessage("display 1 and 2 must have different brightness")
                .that(brightnessForDisplay1).isNotEqualTo(brightnessForDisplay2);
    }

    @Test
    public void testDisplayBrightnessChangeFromVhal() {
        clearInvocations(mDisplayManager);

        getAidlMockedVehicleHal().injectEvent(createPerDisplayBrightnessValue(
                DISPLAY_PORT_1, VHAL_BRIGHTNESS_1), /* setProperty= */ true);

        verify(mDisplayManager, timeout(DEFAULT_TIMEOUT_MS)).setBrightness(
                eq(DISPLAY_ID_1), anyFloat());

        clearInvocations(mDisplayManager);

        getAidlMockedVehicleHal().injectEvent(createPerDisplayBrightnessValue(
                DISPLAY_PORT_2, VHAL_BRIGHTNESS_2), /* setProperty= */ true);

        verify(mDisplayManager, timeout(DEFAULT_TIMEOUT_MS)).setBrightness(
                eq(DISPLAY_ID_2), anyFloat());
    }

    @Test
    public void testBrightnessChangeFromDisplayManager() throws Exception {
        int brightnessForDisplay2 = mBrightnessPropertyHandler.getBrightness(DISPLAY_PORT_2);

        var listenerCaptor = ArgumentCaptor.forClass(DisplayListener.class);
        var handlerCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(mDisplayManager).registerDisplayListener(listenerCaptor.capture(),
                handlerCaptor.capture(), anyLong(), anyLong());

        mBrightnessPropertyHandler.resetPropertySet();

        when(mDisplayManager.getBrightness(DISPLAY_ID_1)).thenReturn(DISPLAY_MANAGER_BRIGHTNESS_2);
        var handler = handlerCaptor.getValue();
        handler.post(() -> {
            listenerCaptor.getValue().onDisplayChanged(DISPLAY_ID_1);
        });

        mBrightnessPropertyHandler.waitForPropertySet(DEFAULT_TIMEOUT_MS);
        assertThat(mBrightnessPropertyHandler.getBrightness(DISPLAY_PORT_1))
                .isEqualTo(brightnessForDisplay2);
    }

    private VehiclePropValue createPerDisplayBrightnessValue(int displayPort, int brightness) {
        var value = new VehiclePropValue();
        value.prop = VehicleProperty.PER_DISPLAY_BRIGHTNESS;
        value.areaId = 0;
        value.value = new RawPropValues();
        value.value.int32Values = new int[]{displayPort, brightness};
        return value;
    }

    private Display createMockDisplay(int displayId, int displayPort, int type) {
        Display display = mock(Display.class);
        DisplayAddress.Physical displayAddress = mock(DisplayAddress.Physical.class);
        when(displayAddress.getPort()).thenReturn(displayPort);
        when(display.getDisplayId()).thenReturn(displayId);
        when(display.getAddress()).thenReturn(displayAddress);
        when(display.getType()).thenReturn(type);
        return display;
    }
}
