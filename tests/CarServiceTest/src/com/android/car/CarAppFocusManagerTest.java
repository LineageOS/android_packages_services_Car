/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car;

import static android.car.CarAppFocusManager.OnAppFocusChangedListener;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.CarAppFocusManager;
import android.car.CarAppFocusManager.OnAppFocusChangedListener;
import android.car.CarAppFocusManager.OnAppFocusOwnershipCallback;
import android.content.Context;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.car.internal.StaticBinderInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class CarAppFocusManagerTest extends MockedCarTestBase {

    private CarAppFocusManager mCarAppFocusManager;
    private AppFocusService mAppFocusService;

    private static final int DEFAULT_FOREGROUND_ID = -1;

    private int mForegroundPid = DEFAULT_FOREGROUND_ID;
    private int mForegroundUid = DEFAULT_FOREGROUND_ID;

    private static final int APP1_UID = 1041;
    private static final int APP1_PID = 1043;
    private static final int APP2_UID = 1072;
    private static final int APP2_PID = 1074;
    private static final int APP3_UID = 1111;
    private static final int APP3_PID = 2222;

    private static final int DEFAULT_TIMEOUT_MS = 1000;

    @Mock private Context mContext;
    @Mock private StaticBinderInterface mMockBinder;
    @Mock private OnAppFocusOwnershipCallback mApp1Callback;
    @Mock private OnAppFocusChangedListener mApp1Listener;
    @Mock private OnAppFocusOwnershipCallback mApp2Callback;
    @Mock private OnAppFocusChangedListener mApp2Listener;
    @Mock private OnAppFocusOwnershipCallback mApp3Callback;
    @Mock private OnAppFocusChangedListener mApp3Listener;
    @Mock private SystemActivityMonitoringService mSystemActivityMonitoringService;

    @Override
    protected void configureFakeSystemInterface() {
        mAppFocusService = new AppFocusService(
                mContext, mSystemActivityMonitoringService, mMockBinder);
        setAppFocusService(mAppFocusService);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mCarAppFocusManager =
                (CarAppFocusManager) getCar().getCarManager(Car.APP_FOCUS_SERVICE);
        when(mSystemActivityMonitoringService.isInForeground(anyInt(), anyInt()))
                .thenAnswer((inv) -> {
                    int pid = inv.getArgument(0);
                    int uid = inv.getArgument(1);
                    return (mForegroundPid == DEFAULT_FOREGROUND_ID || mForegroundPid == pid)
                            && (mForegroundUid == DEFAULT_FOREGROUND_ID || mForegroundUid == uid);
                });
        // Simulate an unprivileged app.
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_CAR_DISPLAY_IN_CLUSTER))
                .thenReturn(1);
    }

    @Test
    public void defaultState_noFocusesHeld() {
        assertThat(mCarAppFocusManager.getActiveAppTypes()).isEmpty();
    }

    @Test
    public void requestNavFocus_noCurrentFocus_requestShouldSucceed() {
        int result = mCarAppFocusManager.requestAppFocus(
                CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION, mApp1Callback);
        assertThat(result).isEqualTo(CarAppFocusManager.APP_FOCUS_REQUEST_SUCCEEDED);
    }

    @Test
    public void requestNavFocus_noCurrentFocus_callbackIsRun() {
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mApp1Callback);

        verify(mApp1Callback, timeout(DEFAULT_TIMEOUT_MS))
                .onAppFocusOwnershipGranted(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
    }

    @Test
    public void requestNavFocus_noCurrentFocus_holdsOwnership() {
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mApp1Callback);

        assertThat(
                mCarAppFocusManager
                        .isOwningFocus(mApp1Callback, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION))
                .isTrue();
    }

    @Test
    public void requestNavFocus_noCurrentFocus_onlyNavActive() {
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mApp1Callback);

        assertThat(mCarAppFocusManager.getActiveAppTypes())
                .isEqualTo(new int[] {CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION});
    }

    private void setCallingApp(int uid, int pid) {
        when(mMockBinder.getCallingUid()).thenReturn(uid);
        when(mMockBinder.getCallingPid()).thenReturn(pid);
    }

    private void app2GainsFocus_app1BroughtToForeground() {
        setCallingApp(APP2_UID, APP2_PID);
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mApp2Callback);
        mForegroundUid = APP1_UID;
        mForegroundPid = APP1_PID;
        setCallingApp(APP2_UID, APP1_PID);
    }

    @Test
    public void requestNavFocus_currentOwnerInBackground_requestShouldSucceed() {
        app2GainsFocus_app1BroughtToForeground();

        assertThat(
                mCarAppFocusManager
                        .requestAppFocus(
                                CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION, mApp1Callback))
                .isEqualTo(CarAppFocusManager.APP_FOCUS_REQUEST_SUCCEEDED);
    }

    @Test
    public void requestNavFocus_currentOwnerInBackground_callbackIsRun() {
        app2GainsFocus_app1BroughtToForeground();
        mCarAppFocusManager
                .requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION, mApp1Callback);

        verify(mApp1Callback, timeout(DEFAULT_TIMEOUT_MS))
                .onAppFocusOwnershipGranted(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
    }

    @Test
    public void requestNavFocus_currentOwnerInBackground_holdsOwnership() {
        app2GainsFocus_app1BroughtToForeground();
        mCarAppFocusManager
                .requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION, mApp1Callback);

        assertThat(
                mCarAppFocusManager
                        .isOwningFocus(mApp1Callback, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION))
                .isTrue();
    }

    @Test
    public void requestNavFocus_currentOwnerInForeground_requestFails() {
        setCallingApp(APP2_UID, APP2_PID);
        mForegroundUid = APP2_UID;
        mForegroundPid = APP2_PID;
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mApp2Callback);
        setCallingApp(APP1_UID, APP1_PID);

        assertThat(
                mCarAppFocusManager
                        .requestAppFocus(
                                CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION, mApp1Callback))
                .isEqualTo(CarAppFocusManager.APP_FOCUS_REQUEST_FAILED);
    }

    @Test
    public void requestAppFocus_callingAppNotified() {
        setCallingApp(APP1_UID, APP1_PID);
        mCarAppFocusManager
                .addFocusListener(mApp1Listener, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mApp1Callback);

        verify(mApp1Listener, timeout(DEFAULT_TIMEOUT_MS))
                .onAppFocusChanged(eq(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION), anyBoolean());
    }

    @Test
    public void requestAppFocus_otherAppNotified() {
        setCallingApp(APP2_UID, APP2_PID);
        mCarAppFocusManager
                .addFocusListener(mApp2Listener, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
        setCallingApp(APP1_UID, APP1_PID);
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mApp1Callback);

        verify(mApp2Listener, timeout(DEFAULT_TIMEOUT_MS))
                .onAppFocusChanged(eq(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION), eq(true));
    }

    @Test
    public void requestAppFocus_focusLost_otherAppRequest_callbackRun() {
        setCallingApp(APP2_UID, APP2_PID);
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mApp2Callback);
        setCallingApp(APP1_UID, APP1_PID);
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mApp1Callback);

        verify(mApp2Callback, timeout(DEFAULT_TIMEOUT_MS))
                .onAppFocusOwnershipLost(eq(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION));
    }

    @Test
    public void abandonAppFocus_callingAppNotified() {
        setCallingApp(APP1_UID, APP1_PID);
        mCarAppFocusManager
                .addFocusListener(mApp1Listener, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mApp1Callback);
        mCarAppFocusManager
                .abandonAppFocus(mApp1Callback, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);

        verify(mApp1Listener, timeout(DEFAULT_TIMEOUT_MS))
                .onAppFocusChanged(eq(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION), eq(false));
    }

    @Test
    public void abandonAppFocus_otherAppNotified() {
        setCallingApp(APP2_UID, APP2_PID);
        mCarAppFocusManager
                .addFocusListener(mApp2Listener, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
        setCallingApp(APP1_UID, APP1_PID);
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mApp1Callback);
        mCarAppFocusManager
                .abandonAppFocus(mApp1Callback, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);

        verify(mApp2Listener, timeout(DEFAULT_TIMEOUT_MS))
                .onAppFocusChanged(eq(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION), eq(false));
    }

    @Test
    public void gainAppFocus_multipleListenersRegistered_bothUnownedTrigger() {
        setCallingApp(APP1_UID, APP1_PID);
        mCarAppFocusManager
                .addFocusListener(mApp1Listener, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
        setCallingApp(APP2_UID, APP2_PID);
        mCarAppFocusManager
                .addFocusListener(mApp2Listener, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
        setCallingApp(APP3_UID, APP3_PID);
        mCarAppFocusManager
                .addFocusListener(mApp3Listener, CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
        mCarAppFocusManager
                .requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION, mApp3Callback);

        verify(mApp1Listener, timeout(DEFAULT_TIMEOUT_MS))
                .onAppFocusChanged(eq(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION), eq(true));
        verify(mApp2Listener, timeout(DEFAULT_TIMEOUT_MS))
                .onAppFocusChanged(eq(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION), eq(true));
        verify(mApp3Listener, timeout(DEFAULT_TIMEOUT_MS))
                .onAppFocusChanged(eq(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION), eq(true));
    }
}
