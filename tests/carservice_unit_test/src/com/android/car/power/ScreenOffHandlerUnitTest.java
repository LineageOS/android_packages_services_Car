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

package com.android.car.power;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.CarOccupantZoneManager;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.ICarOccupantZoneCallback;
import android.car.VehicleAreaSeat;
import android.car.settings.CarSettings;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.AbstractExtendedMockitoTestCase.CustomMockitoSessionBuilder;
import android.car.test.mocks.MockSettings;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.util.Pair;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayAddress;

import com.android.car.CarLocalServices;
import com.android.car.CarOccupantZoneService;
import com.android.car.OccupantZoneHelper;
import com.android.car.R;
import com.android.car.systeminterface.SystemInterface;
import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

public final class ScreenOffHandlerUnitTest extends AbstractExtendedMockitoTestCase {
    private static final String TAG = ScreenOffHandlerUnitTest.class.getSimpleName();
    private static final int SCREEN_OFF_TIMEOUT = 60000;

    @Mock
    private Context mContext;
    @Mock
    private CarOccupantZoneService mCarOccupantZoneService;
    @Mock
    private SystemInterface mSystemInterface;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private Resources mResources;
    @Mock
    private DisplayManager mDisplayManager;

    private OffsettableClock mClock;
    private TestLooper mTestLooper;
    private OccupantZoneHelper mZoneHelper = new OccupantZoneHelper();
    private Runnable mRunnableAtBootComplete;

    private ScreenOffHandler mScreenOffHandler;

    // Not used directly, but sets proper mockStatic() expectations on Settings
    @SuppressWarnings("UnusedVariable")
    private MockSettings mMockSettings;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        mMockSettings = new MockSettings(session);
    }

    @Before
    public void setUp() throws Exception {
        mZoneHelper.setUpOccupantZones(mCarOccupantZoneService, /* hasDriver= */ true,
                /* hasFrontPassenger= */ false, /* numRearPassengers= */ 2);
        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
        CarLocalServices.addService(CarOccupantZoneService.class, mCarOccupantZoneService);
        mClock = new OffsettableClock();
        mTestLooper = new TestLooper(mClock::now);
        setService();
    }

    @After
    public void tearDown() {
        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
    }

    @Test
    public void testParseSetting_valid_returnNonNull_1() throws Exception {
        bootComplete();

        String expectedModeString = "10:0,11:0,21:1";
        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        var display1 = createMockDisplay(/* displayId= */ 10, /* displayPort= */10);
        var display2 = createMockDisplay(/* displayId= */ 11, /* displayPort= */11);
        var display3 = createMockDisplay(/* displayId= */ 21, /* displayPort= */21);
        builder.setDisplayMode(display1, /* mode= */ 0)
                .setDisplayMode(display2, /* mode= */ 0)
                .setDisplayMode(display3, /* mode= */ 1);
        new MockDisplays(mDisplayManager).addDisplay(display1).addDisplay(display2)
                .addDisplay(display3).create();

        String actualModeString = builder.build();
        assertThat(actualModeString).isEqualTo(expectedModeString);
        SparseIntArray result = mScreenOffHandler.parseModeAssignmentSettingValue(actualModeString);
        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(3);
    }

    @Test
    public void testParseSetting_valid_returnNonNull_2() throws Exception {
        bootComplete();

        String expectedModeString = "0:0,1:0,2:1,3:2,4:1,5:0";
        var builder = new DisplayPowerModeBuilder(mDisplayManager);
        var display0 = createMockDisplay(/* displayId= */ 0, /* displayPort= */ 0);
        var display1 = createMockDisplay(/* displayId= */ 1, /* displayPort= */ 1);
        var display2 = createMockDisplay(/* displayId= */ 2, /* displayPort= */ 2);
        var display3 = createMockDisplay(/* displayId= */ 3, /* displayPort= */ 3);
        var display4 = createMockDisplay(/* displayId= */ 4, /* displayPort= */ 4);
        var display5 = createMockDisplay(/* displayId= */ 5, /* displayPort= */ 5);
        builder.setDisplayMode(display0, /* mode= */ 0)
                .setDisplayMode(display1, /* mode= */ 0)
                .setDisplayMode(display2, /* mode= */ 1)
                .setDisplayMode(display3, /* mode= */ 2)
                .setDisplayMode(display4, /* mode= */ 1)
                .setDisplayMode(display5, /* mode= */ 0);
        new MockDisplays(mDisplayManager).addDisplay(display0).addDisplay(display1)
                .addDisplay(display2).addDisplay(display3).addDisplay(display4)
                .addDisplay(display5).create();

        String actualModeString = builder.build();
        assertThat(actualModeString).isEqualTo(expectedModeString);
        SparseIntArray result = mScreenOffHandler.parseModeAssignmentSettingValue(actualModeString);
        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(6);
    }

    @Test
    public void testParseSetting_valid_returnNonNull_3() throws Exception {
        bootComplete();

        String expectedModeString = "0:0,1:0,2:1";
        var builder = new DisplayPowerModeBuilder(mDisplayManager);
        var display1 = createMockDisplay(/* displayId= */ 99, /* displayPort= */ 0);
        var display2 = createMockDisplay(/* displayId= */ 2, /* displayPort= */ 1);
        var display3 = createMockDisplay(/* displayId= */ 5, /* displayPort= */ 2);
        builder.setDisplayMode(display1, /* mode= */ 0)
                .setDisplayMode(display2, /* mode= */ 0)
                .setDisplayMode(display3, /* mode= */ 1);
        new MockDisplays(mDisplayManager).addDisplay(display1).addDisplay(display2)
                .addDisplay(display3).create();

        String actualModeString = builder.build();
        assertThat(actualModeString).isEqualTo(expectedModeString);
        SparseIntArray result = mScreenOffHandler.parseModeAssignmentSettingValue(actualModeString);
        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(3);
    }

    @Test
    public void testParseSetting_valid_returnNonNull_4() throws Exception {
        bootComplete();

        String expectedModeString = "11:0,52:2";
        var builder = new DisplayPowerModeBuilder(mDisplayManager);
        var display1 = createMockDisplay(/* displayId= */ 22, /* displayPort= */ 11);
        var display2 = createMockDisplay(/* displayId= */ 420, /* displayPort= */ 52);
        builder.setDisplayMode(display1, /* mode= */ 0)
                .setDisplayMode(display2, /* mode= */ 2);
        new MockDisplays(mDisplayManager).addDisplay(display1).addDisplay(display2).create();

        String actualModeString = builder.build();
        assertThat(actualModeString).isEqualTo(expectedModeString);
        SparseIntArray result = mScreenOffHandler.parseModeAssignmentSettingValue(actualModeString);
        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(2);
    }

    @Test
    public void testParseSetting_valid_returnNonNull_5() throws Exception {
        bootComplete();

        String expectedModeString = "99:0";
        var builder = new DisplayPowerModeBuilder(mDisplayManager);
        var display = createMockDisplay(/* displayId= */ 99, /* displayPort= */ 99);
        builder.setDisplayMode(display, /* mode= */ 0);
        new MockDisplays(mDisplayManager).addDisplay(display).create();

        String actualModeString = builder.build();
        assertThat(actualModeString).isEqualTo(expectedModeString);
        SparseIntArray result = mScreenOffHandler.parseModeAssignmentSettingValue(actualModeString);
        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(1);
    }

    @Test
    public void testParseSetting_duplicate_returnNull() throws Exception {
        bootComplete();

        // Duplicate displayId
        String expectedModeString = "0:0,0:1,2:1";
        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        var display1 = createMockDisplay(/* displayId= */ 0, /* displayPort= */0);
        var display2 = createMockDisplay(/* displayId= */ 2, /* displayPort= */2);
        builder.setDisplayMode(display1, /* mode= */ 0)
                .setDisplayMode(display1, /* mode= */ 1)
                .setDisplayMode(display2, /* mode= */ 1);
        new MockDisplays(mDisplayManager).addDisplay(display1).addDisplay(display2).create();

        String actualModeString = builder.build();
        assertThat(actualModeString).isEqualTo(expectedModeString);
        SparseIntArray result = mScreenOffHandler.parseModeAssignmentSettingValue(actualModeString);
        assertThat(result).isNull();
    }

    @Test
    public void testParseSetting_invalidMode_returnNull() throws Exception {
        bootComplete();

        // Mode out of range
        String expectedModeString = "0:0,1:0,2:1,3:2,4:1,5:11";
        var builder = new DisplayPowerModeBuilder(mDisplayManager);
        var display0 = createMockDisplay(/* displayId= */ 0, /* displayPort= */ 0);
        var display1 = createMockDisplay(/* displayId= */ 1, /* displayPort= */ 1);
        var display2 = createMockDisplay(/* displayId= */ 2, /* displayPort= */ 2);
        var display3 = createMockDisplay(/* displayId= */ 3, /* displayPort= */ 3);
        var display4 = createMockDisplay(/* displayId= */ 4, /* displayPort= */ 4);
        var display5 = createMockDisplay(/* displayId= */ 5, /* displayPort= */ 5);
        builder.setDisplayMode(display0, /* mode= */ 0)
                .setDisplayMode(display1, /* mode= */ 0)
                .setDisplayMode(display2, /* mode= */ 1)
                .setDisplayMode(display3, /* mode= */ 2)
                .setDisplayMode(display4, /* mode= */ 1)
                .setDisplayMode(display5, /* mode= */ 11);
        new MockDisplays(mDisplayManager).addDisplay(display0).addDisplay(display1)
                .addDisplay(display2).addDisplay(display3).addDisplay(display4)
                .addDisplay(display5).create();

        String actualModeString = builder.build();
        assertThat(actualModeString).isEqualTo(expectedModeString);
        SparseIntArray result = mScreenOffHandler.parseModeAssignmentSettingValue(actualModeString);
        assertThat(result).isNull();
    }

    @Test
    public void testParseString_empty_returnNull() throws Exception {
        var result = mScreenOffHandler.parseModeAssignmentSettingValue("");

        assertThat(result).isNull();
    }

    @Test
    public void testSetDisplayStateAfterBoot_powerModeOn()
            throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);

        Display mockDisplay = createMockDisplay(displayId);
        new MockDisplays(mDisplayManager).addDisplay(mockDisplay).create();
        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        builder.setDisplayMode(mockDisplay, ScreenOffHandler.DISPLAY_POWER_MODE_ON);
        Settings.Global.putString(mContentResolver, CarSettings.Global.DISPLAY_POWER_MODE,
                builder.build());

        bootComplete();

        mTestLooper.dispatchAll();
        verify(mSystemInterface).setDisplayState(displayId, true);
    }

    @Test
    public void testSetDisplayStateAfterBoot_powerModeAlwaysOn()
            throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);

        Display mockDisplay = createMockDisplay(displayId);
        new MockDisplays(mDisplayManager).addDisplay(mockDisplay).create();
        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        builder.setDisplayMode(mockDisplay, ScreenOffHandler.DISPLAY_POWER_MODE_ALWAYS_ON);
        Settings.Global.putString(mContentResolver, CarSettings.Global.DISPLAY_POWER_MODE,
                builder.build());

        bootComplete();

        mTestLooper.dispatchAll();
        verify(mSystemInterface).setDisplayState(displayId, true);
    }

    @Test
    public void testSetDisplayStateAfterBoot_powerModeOff()
            throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);

        Display mockDisplay = createMockDisplay(displayId);
        new MockDisplays(mDisplayManager).addDisplay(mockDisplay).create();
        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        builder.setDisplayMode(mockDisplay, ScreenOffHandler.DISPLAY_POWER_MODE_OFF);
        Settings.Global.putString(mContentResolver, CarSettings.Global.DISPLAY_POWER_MODE,
                builder.build());

        bootComplete();

        mTestLooper.dispatchAll();
        verify(mSystemInterface).setDisplayState(displayId, false);
    }

    // Test that if a zone does not have user logged in, for ON mode, the display should be
    // turned off after timeout.
    @Test
    public void testUpdateUserActivity_noUserAssignedInModeOn_shouldTurnOffDisplay()
            throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        assertThat(zoneInfo).isNotNull();
        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        assertWithMessage("User for occupant zone(%s)", zoneInfo.zoneId)
                .that((mCarOccupantZoneService.getUserForOccupant(zoneInfo.zoneId)))
                .isEqualTo(CarOccupantZoneManager.INVALID_USER_ID);

        Display mockDisplay = createMockDisplay(displayId);
        new MockDisplays(mDisplayManager).addDisplay(mockDisplay).create();

        bootComplete();

        mTestLooper.dispatchAll();
        verify(mSystemInterface).setDisplayState(displayId, true);
        clearInvocations(mSystemInterface);

        updateDisplayPowerModeSetting(displayId, ScreenOffHandler.DISPLAY_POWER_MODE_ON);
        mScreenOffHandler.updateUserActivity(displayId, mClock.now());

        mTestLooper.dispatchAll();
        verify(mSystemInterface).setDisplayState(displayId, true);
        clearInvocations(mSystemInterface);

        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        verify(mSystemInterface).setDisplayState(displayId, false);
    }

    // Test that if a user is currently logged in, the display must not be turned off.
    @Test
    public void testUpdateUserActivity_userAssignedInModeOn_shouldKeepScreenOn() throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        assertThat(zoneInfo).isNotNull();

        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);

        int userId = 99;
        assertWithMessage("User assignment").that(
                mCarOccupantZoneService.assignVisibleUserToOccupantZone(
                zoneInfo.zoneId, UserHandle.of(userId))).isEqualTo(
                        CarOccupantZoneManager.USER_ASSIGNMENT_RESULT_OK);
        Display mockDisplay = createMockDisplay(displayId);
        new MockDisplays(mDisplayManager).addDisplay(mockDisplay).create();

        bootComplete();

        mTestLooper.dispatchAll();
        verify(mSystemInterface).setDisplayState(displayId, true);
        clearInvocations(mSystemInterface);

        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        updateDisplayPowerModeSetting(displayId, ScreenOffHandler.DISPLAY_POWER_MODE_ON);
        mScreenOffHandler.updateUserActivity(displayId, mClock.now());

        mTestLooper.dispatchAll();
        verify(mSystemInterface).setDisplayState(displayId, true);
        clearInvocations(mSystemInterface);

        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        verify(mSystemInterface, never()).setDisplayState(displayId, false);
    }

    // Test that for power mode on
    @Test
    public void testUpdateUserActivity_canExtendTimeoutMultipleTimes()
            throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        assertThat(zoneInfo).isNotNull();

        int displayPort = 22;
        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        var mockDisplay = createMockDisplay(displayId, displayPort);
        new MockDisplays(mDisplayManager).addDisplay(mockDisplay).create();

        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        // Initial power mode is always on.
        builder.setDisplayMode(mockDisplay, ScreenOffHandler.DISPLAY_POWER_MODE_ON);
        Settings.Global.putString(mContentResolver, CarSettings.Global.DISPLAY_POWER_MODE,
                builder.build());

        bootComplete();

        for (int i = 0; i < 10; i++) {
            // Advance half screen-off time.
            advanceTime(SCREEN_OFF_TIMEOUT / 2);

            // Update user activity.
            mScreenOffHandler.updateUserActivity(displayId, mClock.now());
        }

        verify(mSystemInterface, never()).setDisplayState(displayId, false);

        // SCREEN_OFF_TIMEOUT after no user activity, the display should be turned off.
        advanceTime(SCREEN_OFF_TIMEOUT);
        verify(mSystemInterface).setDisplayState(displayId, false);
    }


    @Test
    public void testUserActivity_bootIncomplete() throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);

        // Before boot complete, this must cause nothing.
        mScreenOffHandler.updateUserActivity(displayId, mClock.now());

        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        verify(mSystemInterface, never()).setDisplayState(displayId, false);
    }

    @Test
    public void testCanTurnOnDisplay() throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        assertThat(zoneInfo).isNotNull();

        int displayPort = 22;
        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        new MockDisplays(mDisplayManager).addDisplay(createMockDisplay(displayId, displayPort))
                .create();

        bootComplete();

        updateDisplayPowerModeSetting(displayId, displayPort,
                ScreenOffHandler.DISPLAY_POWER_MODE_OFF);
        assertWithMessage("Display off")
                .that(mScreenOffHandler.canTurnOnDisplay(displayId)).isFalse();

        updateDisplayPowerModeSetting(displayId, displayPort,
                ScreenOffHandler.DISPLAY_POWER_MODE_ON);
        assertWithMessage("Display on")
                .that(mScreenOffHandler.canTurnOnDisplay(displayId)).isTrue();
    }

    // Test that for power mode settings change from always on to on must start a new
    // timeout timer and must not turn off display before the new timeout.
    @Test
    public void testDisplayPowerModeSettingsChange_alwaysOnToOn_mustNotTurnOffDisplay()
            throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        assertThat(zoneInfo).isNotNull();

        int displayPort = 22;
        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        var mockDisplay = createMockDisplay(displayId, displayPort);
        new MockDisplays(mDisplayManager).addDisplay(mockDisplay).create();

        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        // Initial power mode is always on.
        builder.setDisplayMode(mockDisplay, ScreenOffHandler.DISPLAY_POWER_MODE_ALWAYS_ON);
        Settings.Global.putString(mContentResolver, CarSettings.Global.DISPLAY_POWER_MODE,
                builder.build());

        bootComplete();

        // Advance half screen-off time.
        advanceTime(SCREEN_OFF_TIMEOUT / 2);
        clearInvocations(mSystemInterface);

        // Power mode is changed to on.
        updateDisplayPowerModeSetting(displayId, displayPort,
                ScreenOffHandler.DISPLAY_POWER_MODE_ON);

        // Then advance half screen-off time.
        advanceTime(SCREEN_OFF_TIMEOUT / 2 + 1);
        verify(mSystemInterface, never()).setDisplayState(displayId, false);

        // SCREEN_OFF_TIMEOUT after we change the power state to on, the display should be turned
        // off.
        advanceTime(SCREEN_OFF_TIMEOUT / 2 + 1);
        verify(mSystemInterface).setDisplayState(displayId, false);
    }

    @Test
    public void testHandleDisplayStateChange_modeOn() throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        assertThat(zoneInfo).isNotNull();

        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        assertWithMessage("User for occupant zone(%s)", zoneInfo.zoneId)
                .that((mCarOccupantZoneService.getUserForOccupant(zoneInfo.zoneId)))
                .isEqualTo(CarOccupantZoneManager.INVALID_USER_ID);
        new MockDisplays(mDisplayManager).addDisplay(createMockDisplay(displayId)).create();

        bootComplete();

        updateDisplayPowerModeSetting(displayId, ScreenOffHandler.DISPLAY_POWER_MODE_ON);
        mScreenOffHandler.handleDisplayStateChange(displayId, /* on= */ true);

        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        verify(mSystemInterface).setDisplayState(displayId, false);
    }

    @Test
    public void testHandleDisplayStateChange_modeAlwaysOn() throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        assertThat(zoneInfo).isNotNull();

        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        assertWithMessage("User for occupant zone(%s)", zoneInfo.zoneId)
                .that((mCarOccupantZoneService.getUserForOccupant(zoneInfo.zoneId)))
                .isEqualTo(CarOccupantZoneManager.INVALID_USER_ID);
        new MockDisplays(mDisplayManager).addDisplay(createMockDisplay(displayId)).create();

        bootComplete();

        updateDisplayPowerModeSetting(displayId, ScreenOffHandler.DISPLAY_POWER_MODE_ALWAYS_ON);
        mScreenOffHandler.handleDisplayStateChange(displayId, /* on= */ true);

        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        verify(mSystemInterface, never()).setDisplayState(displayId, false);
    }

    @Test
    public void testHandleDisplayStateChange_driver_modeOn() throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER,
                VehicleAreaSeat.SEAT_ROW_1_LEFT);
        assertThat(zoneInfo).isNotNull();

        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        assertWithMessage("User for occupant zone(%s)", zoneInfo.zoneId)
                .that((mCarOccupantZoneService.getUserForOccupant(zoneInfo.zoneId)))
                .isNotEqualTo(CarOccupantZoneManager.INVALID_USER_ID);
        new MockDisplays(mDisplayManager).addDisplay(createMockDisplay(displayId)).create();

        bootComplete();

        updateDisplayPowerModeSetting(displayId, ScreenOffHandler.DISPLAY_POWER_MODE_ON);
        mScreenOffHandler.handleDisplayStateChange(displayId, /* on= */ true);

        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        // Driver display must never be turned off even though power mode is on.
        verify(mSystemInterface, never()).setDisplayState(displayId, false);
    }

    @Test
    public void testHandleDisplayStateChange_modeOff() throws Exception {
        // TODO(b/279041525): Replace OccupantZoneHelper with mocking logics.
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        assertThat(zoneInfo).isNotNull();

        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        assertWithMessage("User for occupant zone(%s)", zoneInfo.zoneId)
                .that((mCarOccupantZoneService.getUserForOccupant(zoneInfo.zoneId)))
                .isEqualTo(CarOccupantZoneManager.INVALID_USER_ID);
        new MockDisplays(mDisplayManager).addDisplay(createMockDisplay(displayId)).create();

        bootComplete();

        updateDisplayPowerModeSetting(displayId, ScreenOffHandler.DISPLAY_POWER_MODE_OFF);
        mScreenOffHandler.handleDisplayStateChange(displayId, /* on= */ true);

        mTestLooper.dispatchAll();
        verify(mSystemInterface).setDisplayState(displayId, false);
    }

    // Test that for power mode always on, user logging out will off display after timeout.
    @Test
    public void testOnOccupantZoneConfigChange_powerModeOn_userLogOut() throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        int zoneId = zoneInfo.zoneId;
        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        var mockDisplay = createMockDisplay(displayId);
        new MockDisplays(mDisplayManager).addDisplay(mockDisplay).create();
        int userId = 99;
        assertWithMessage("User assignment").that(
                mCarOccupantZoneService.assignVisibleUserToOccupantZone(
                zoneId, UserHandle.of(userId))).isEqualTo(
                        CarOccupantZoneManager.USER_ASSIGNMENT_RESULT_OK);
        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        builder.setDisplayMode(mockDisplay, ScreenOffHandler.DISPLAY_POWER_MODE_ON);
        Settings.Global.putString(mContentResolver, CarSettings.Global.DISPLAY_POWER_MODE,
                builder.build());

        bootComplete();
        mTestLooper.dispatchAll();
        clearInvocations(mSystemInterface);

        // Simulate user logging out. The display power mode is set to ON.
        mCarOccupantZoneService.unassignOccupantZone(zoneId);
        assertThat(mCarOccupantZoneService.getUserForOccupant(zoneId))
                .isEqualTo(CarOccupantZoneManager.INVALID_USER_ID);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        // After SCREEN_OFF_TIMEOUT, the display should be turned off.
        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        verify(mSystemInterface).setDisplayState(displayId, false);
    }

    // Test that for power mode always on, user logging out will not turn off display.
    @Test
    public void testOnOccupantZoneConfigChange_powerModeAlwaysOn_userLogOut() throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        int zoneId = zoneInfo.zoneId;
        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        var mockDisplay = createMockDisplay(displayId);
        new MockDisplays(mDisplayManager).addDisplay(mockDisplay).create();
        int userId = 99;
        assertWithMessage("User assignment").that(
                mCarOccupantZoneService.assignVisibleUserToOccupantZone(
                zoneId, UserHandle.of(userId))).isEqualTo(
                        CarOccupantZoneManager.USER_ASSIGNMENT_RESULT_OK);
        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        builder.setDisplayMode(mockDisplay, ScreenOffHandler.DISPLAY_POWER_MODE_ALWAYS_ON);
        Settings.Global.putString(mContentResolver, CarSettings.Global.DISPLAY_POWER_MODE,
                builder.build());

        bootComplete();
        mTestLooper.dispatchAll();
        clearInvocations(mSystemInterface);

        // Simulate user logging out. The display power mode is set to ALWAYS_ON.
        mCarOccupantZoneService.unassignOccupantZone(zoneId);
        assertThat(mCarOccupantZoneService.getUserForOccupant(zoneId))
                .isEqualTo(CarOccupantZoneManager.INVALID_USER_ID);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        // After SCREEN_OFF_TIMEOUT, the display should still be on.
        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        verify(mSystemInterface, never()).setDisplayState(displayId, false);
    }

    @Test
    public void testOnOccupantZoneConfigChange_powerModeOn_userLogOut_thenLogIn()
            throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        int zoneId = zoneInfo.zoneId;
        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        var mockDisplay = createMockDisplay(displayId);
        new MockDisplays(mDisplayManager).addDisplay(mockDisplay).create();
        int userId = 99;
        assertWithMessage("User assignment").that(
                mCarOccupantZoneService.assignVisibleUserToOccupantZone(
                zoneId, UserHandle.of(userId))).isEqualTo(
                        CarOccupantZoneManager.USER_ASSIGNMENT_RESULT_OK);
        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        builder.setDisplayMode(mockDisplay, ScreenOffHandler.DISPLAY_POWER_MODE_ON);
        Settings.Global.putString(mContentResolver, CarSettings.Global.DISPLAY_POWER_MODE,
                builder.build());

        bootComplete();
        mTestLooper.dispatchAll();
        clearInvocations(mSystemInterface);

        // Simulate user logging out. The display power mode is set to ON.
        mCarOccupantZoneService.unassignOccupantZone(zoneId);
        assertThat(mCarOccupantZoneService.getUserForOccupant(zoneId))
                .isEqualTo(CarOccupantZoneManager.INVALID_USER_ID);
        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        // After half SCREEN_OFF_TIMEOUT, a new user log in.
        advanceTime(SCREEN_OFF_TIMEOUT / 2);
        assertWithMessage("User assignment").that(
                mCarOccupantZoneService.assignVisibleUserToOccupantZone(
                zoneId, UserHandle.of(userId))).isEqualTo(
                        CarOccupantZoneManager.USER_ASSIGNMENT_RESULT_OK);
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        advanceTime(SCREEN_OFF_TIMEOUT);

        verify(mSystemInterface, never()).setDisplayState(displayId, false);
    }

    private void setService() {
        when(mResources.getBoolean(R.bool.config_enablePassengerDisplayPowerSaving))
                .thenReturn(true);
        when(mResources.getInteger(R.integer.config_noUserScreenOffTimeout))
                .thenReturn(SCREEN_OFF_TIMEOUT);
        when(mContext.createContextAsUser(any(), anyInt())).thenReturn(mContext);
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mSystemInterface.isDisplayEnabled(anyInt())).thenReturn(true);
        when(mContext.getSystemService(DisplayManager.class)).thenReturn(mDisplayManager);
        doAnswer((invocation) -> {
            Runnable r = invocation.getArgument(0);
            mRunnableAtBootComplete = r;
            return null;
        }).when(mSystemInterface).scheduleActionForBootCompleted(any(Runnable.class), any());

        mScreenOffHandler = new ScreenOffHandler(mContext, mSystemInterface,
                mTestLooper.getLooper(), mClock::now);
        mScreenOffHandler.init();
    }

    private void bootComplete() {
        if (mRunnableAtBootComplete == null) return;
        mRunnableAtBootComplete.run();
    }

    private Display createMockDisplay(int displayId) {
        int displayPort = displayId;
        return createMockDisplay(displayId, displayPort);
    }

    private Display createMockDisplay(int displayId, int displayPort) {
        Display display = mock(Display.class);
        DisplayAddress.Physical displayAddress = mock(DisplayAddress.Physical.class);
        when(displayAddress.getPort()).thenReturn(displayPort);
        when(display.getDisplayId()).thenReturn(displayId);
        when(display.getAddress()).thenReturn(displayAddress);
        return display;
    }

    private ICarOccupantZoneCallback getOccupantZoneCallback() {
        ArgumentCaptor<ICarOccupantZoneCallback> captor =
                ArgumentCaptor.forClass(ICarOccupantZoneCallback.class);
        verify(mCarOccupantZoneService).registerCallback(captor.capture());
        return captor.getValue();
    }

    private ContentObserver getSettingsObserver() {
        ArgumentCaptor<ContentObserver> captor =
                ArgumentCaptor.forClass(ContentObserver.class);
        verify(mContentResolver).registerContentObserver(
                any(),
                anyBoolean(),
                captor.capture());
        return captor.getValue();
    }

    private void updateDisplayPowerModeSetting(int displayId, int displayMode) {
        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        builder.setDisplayMode(createMockDisplay(displayId), displayMode);

        Settings.Global.putString(mContentResolver, CarSettings.Global.DISPLAY_POWER_MODE,
                builder.build());
        ContentObserver osbserver = getSettingsObserver();
        osbserver.onChange(/* selfChange= */ false, /* uri= */ null);
    }

    private void updateDisplayPowerModeSetting(int displayId, int displayPort, int displayMode) {
        DisplayPowerModeBuilder builder = new DisplayPowerModeBuilder(mDisplayManager);
        builder.setDisplayMode(createMockDisplay(displayId, displayPort), displayMode);

        Settings.Global.putString(mContentResolver, CarSettings.Global.DISPLAY_POWER_MODE,
                builder.build());
        ContentObserver osbserver = getSettingsObserver();
        osbserver.onChange(/* selfChange= */ false, /* uri= */ null);
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }

    private static final class DisplayPowerModeBuilder {
        private final ArrayList<Pair<Display, Integer>> mDisplayPowerModeMap;
        private final DisplayManager mDisplayManager;

        private DisplayPowerModeBuilder(DisplayManager displayManager) {
            mDisplayPowerModeMap = new ArrayList<>();
            mDisplayManager = displayManager;
        }

        private DisplayPowerModeBuilder setDisplayMode(Display display, int mode) {
            mDisplayPowerModeMap.add(new Pair<>(display, mode));
            return this;
        }

        private String build() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mDisplayPowerModeMap.size(); i++) {
                if (sb.length() != 0) {
                    sb.append(",");
                }
                Display display = mDisplayPowerModeMap.get(i).first;
                int displayPort = getDisplayPort(display);
                int mode = mDisplayPowerModeMap.get(i).second;
                sb.append(displayPort).append(":").append(mode);
            }
            return sb.toString();
        }

        private int getDisplayPort(Display display) {
            DisplayAddress.Physical address = (DisplayAddress.Physical) display.getAddress();
            return address.getPort();
        }
    }

    private static final class MockDisplays {
        private final List<Display> mDisplays;
        private final DisplayManager mDisplayManager;

        private MockDisplays(DisplayManager displayManager) {
            mDisplays = new ArrayList<>();
            mDisplayManager = displayManager;
        }

        private MockDisplays addDisplay(Display display) {
            mDisplays.add(display);
            return this;
        }

        private void create() {
            for (int i = 0; i < mDisplays.size(); i++) {
                var display = mDisplays.get(i);
                when(mDisplayManager.getDisplay(display.getDisplayId())).thenReturn(display);
            }
            when(mDisplayManager.getDisplays())
                    .thenReturn(mDisplays.toArray(new Display[mDisplays.size()]));
        }
    }
}
