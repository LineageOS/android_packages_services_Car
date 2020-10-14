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
package com.android.car.custominput.sample;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.input.CarInputManager;
import android.car.input.CustomInputEvent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class CustomInputEventListenerTest {

    private CustomInputEventListener mEventHandler;

    @Mock
    private Context mContext;

    @Mock
    private SampleCustomInputService mService;

    @Before
    public void setUp() {
        when(mContext.getString(R.string.maps_app_package)).thenReturn(
                "com.google.android.apps.maps");
        when(mContext.getString(R.string.maps_activity_class)).thenReturn(
                "com.google.android.maps.MapsActivity");

        mEventHandler = new CustomInputEventListener(mContext, mService);
    }

    @Test
    public void testHandleEvent_launchingMaps() {
        // Arrange
        int anyDisplayId = CarInputManager.TARGET_DISPLAY_TYPE_MAIN;
        CustomInputEvent event = new CustomInputEvent(
                // In this implementation, INPUT_TYPE_CUSTOM_EVENT_F1 represents the action of
                // launching maps.
                /* inputCode= */ CustomInputEvent.INPUT_CODE_F1,
                /* targetDisplayType= */ anyDisplayId,
                /* repeatCounter= */ 1);

        // Act
        mEventHandler.handle(anyDisplayId, event);

        // Assert
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        ArgumentCaptor<UserHandle> userHandleCaptor = ArgumentCaptor.forClass(UserHandle.class);
        verify(mService).startActivityAsUser(intentCaptor.capture(),
                bundleCaptor.capture(), userHandleCaptor.capture());

        // Assert intent parameter
        Intent actualIntent = intentCaptor.getValue();
        assertThat(actualIntent.getAction()).isEqualTo(Intent.ACTION_VIEW);
        assertThat(actualIntent.getFlags()).isEqualTo(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        assertThat(actualIntent.getComponent()).isEqualTo(
                new ComponentName("com.google.android.apps.maps",
                        "com.google.android.maps.MapsActivity"));

        // Assert bundle and user parameters
        assertThat(bundleCaptor.getValue().getInt("android.activity.launchDisplayId")).isEqualTo(
                /* displayId= */
                0);
        // TODO(b/159623196): displayId is currently hardcoded to 0, see missing
        // targetDisplayTarget to targetDisplayId logic in
        // CustomInputEventListener
        assertThat(userHandleCaptor.getValue()).isEqualTo(UserHandle.CURRENT);
    }

    @Test
    public void testHandleEvent_backHomeAction() {
        // Arrange
        int anyDisplayId = CarInputManager.TARGET_DISPLAY_TYPE_MAIN;
        CustomInputEvent event = new CustomInputEvent(
                // In this implementation, INPUT_TYPE_CUSTOM_EVENT_F6 represents the back HOME
                // action.
                /* inputCode= */ CustomInputEvent.INPUT_CODE_F6,
                /* targetDisplayType= */ anyDisplayId,
                /* repeatCounter= */ 1);

        // Act
        mEventHandler.handle(anyDisplayId, event);

        // Assert
        ArgumentCaptor<KeyEvent> keyEventCaptor = ArgumentCaptor.forClass(KeyEvent.class);
        ArgumentCaptor<Integer> displayTypeCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mService, times(2)).injectKeyEvent(keyEventCaptor.capture(),
                displayTypeCaptor.capture());

        KeyEvent actualEvent = keyEventCaptor.getValue();
        assertThat(actualEvent.getAction()).isEqualTo(KeyEvent.ACTION_UP);
        assertThat(actualEvent.getKeyCode()).isEqualTo(KeyEvent.KEYCODE_HOME);

        assertThat(displayTypeCaptor.getValue()).isEqualTo(
                CarInputManager.TARGET_DISPLAY_TYPE_MAIN);
    }

    @Test
    public void testHandleEvent_ignoringEventsForNonMainDisplay() {
        int invalidDisplayId = -1;
        CustomInputEvent event = new CustomInputEvent(CustomInputEvent.INPUT_CODE_F1,
                invalidDisplayId,
                /* repeatCounter= */ 1);

        // Act
        mEventHandler.handle(invalidDisplayId, event);

        // Assert
        verify(mService, never()).startActivityAsUser(any(Intent.class), any(Bundle.class),
                any(UserHandle.class));
    }
}
