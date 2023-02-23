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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.util.SparseIntArray;
import android.view.Display;

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
        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);
        setService();
    }

    @After
    public void tearDown() {
        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
    }

    @Test
    public void testParseSetting_valid_returnNonNull() throws Exception {
        bootComplete();
        String value1 = "0:0,1:0,2:1";
        String value2 = "0:0,1:0,2:1,3:2,4:1,5:0";
        String value3 = "99:0,2:0,11:1";
        String value4 = "22:0,420:2";
        String value5 = "99:0";

        SparseIntArray result;

        result = mScreenOffHandler.parseModeAssignmentSettingValue(value1);
        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(3);

        result = mScreenOffHandler.parseModeAssignmentSettingValue(value2);
        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(6);

        result = mScreenOffHandler.parseModeAssignmentSettingValue(value3);
        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(3);

        result = mScreenOffHandler.parseModeAssignmentSettingValue(value4);
        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(2);

        result = mScreenOffHandler.parseModeAssignmentSettingValue(value5);
        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(1);
    }

    @Test
    public void testParseSetting_invalid_returnNull() throws Exception {
        bootComplete();
        String value1 = "0:0,0:1,2:1";                  // duplicate displayId
        String value2 = "0:0,1:0,2:1,3:2,4:1,5:11";     // mode out of range
        String value3 = "99:0,2:0,a:1";                 // alphabet
        String value4 = "22;0,420;2";                   // invalid separator ;
        String value5 = "0";                            // single digit
        String value6 = "";                             // empty

        SparseIntArray result;

        result = mScreenOffHandler.parseModeAssignmentSettingValue(value1);
        assertThat(result).isNull();

        result = mScreenOffHandler.parseModeAssignmentSettingValue(value2);
        assertThat(result).isNull();

        result = mScreenOffHandler.parseModeAssignmentSettingValue(value3);
        assertThat(result).isNull();

        result = mScreenOffHandler.parseModeAssignmentSettingValue(value4);
        assertThat(result).isNull();

        result = mScreenOffHandler.parseModeAssignmentSettingValue(value5);
        assertThat(result).isNull();

        result = mScreenOffHandler.parseModeAssignmentSettingValue(value6);
        assertThat(result).isNull();
    }

    @Test
    public void testUpdateUserActivity_noUserAssigned() throws Exception {
        bootComplete();
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

        mScreenOffHandler.updateUserActivity(displayId, mClock.now());

        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        verify(mSystemInterface).setDisplayState(displayId, false);
    }

    @Test
    public void testUpdateUserActivity_userAssigned() throws Exception {
        bootComplete();
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
                zoneInfo.zoneId, UserHandle.of(userId), /* flags= */ 0)).isEqualTo(
                CarOccupantZoneManager.USER_ASSIGNMENT_RESULT_OK);

        ICarOccupantZoneCallback callback = getOccupantZoneCallback();
        callback.onOccupantZoneConfigChanged(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);

        mScreenOffHandler.updateUserActivity(displayId, mClock.now());

        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        verify(mSystemInterface, never()).setDisplayState(displayId, false);
    }

    @Test
    public void testUserActivity_bootIncomplete() throws Exception {
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        assertThat(zoneInfo).isNotNull();

        assertWithMessage("User for occupant zone(%s)", zoneInfo.zoneId)
                .that((mCarOccupantZoneService.getUserForOccupant(zoneInfo.zoneId)))
                .isEqualTo(CarOccupantZoneManager.INVALID_USER_ID);

        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);

        mScreenOffHandler.updateUserActivity(displayId, mClock.now());

        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        verify(mSystemInterface, never()).setDisplayState(displayId, false);
    }

    @Test
    public void testCanTurnOnDisplay() throws Exception {
        bootComplete();
        OccupantZoneInfo zoneInfo = mCarOccupantZoneService.getOccupantZone(
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER,
                VehicleAreaSeat.SEAT_ROW_2_LEFT);
        assertThat(zoneInfo).isNotNull();

        int displayId = mCarOccupantZoneService.getDisplayForOccupant(
                zoneInfo.zoneId, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);

        StringBuilder sb = new StringBuilder().append(displayId).append(":").append(/* OFF */ "0");
        updateDisplayPowerModeSetting(sb.toString());
        assertWithMessage("Display off")
                .that(mScreenOffHandler.canTurnOnDisplay(displayId)).isFalse();

        sb = new StringBuilder().append(displayId).append(":").append(/* ON */ "1");
        updateDisplayPowerModeSetting(sb.toString());
        assertWithMessage("Display on")
                .that(mScreenOffHandler.canTurnOnDisplay(displayId)).isTrue();
    }

    @Test
    public void testHandleDisplayStateChange() throws Exception {
        bootComplete();
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

        mScreenOffHandler.handleDisplayStateChange(displayId, /* on= */ true);

        advanceTime(SCREEN_OFF_TIMEOUT + 1);
        verify(mSystemInterface).setDisplayState(displayId, false);
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
                eq(Settings.Global.getUriFor(CarSettings.Global.DISPLAY_POWER_MODE)),
                anyBoolean(),
                captor.capture());
        return captor.getValue();
    }

    private void updateDisplayPowerModeSetting(String value) {
        Settings.Global.putString(mContentResolver, CarSettings.Global.DISPLAY_POWER_MODE, value);
        ContentObserver osbserver = getSettingsObserver();
        osbserver.onChange(/* selfChange= */ false, /* uri= */ null);
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }
}
