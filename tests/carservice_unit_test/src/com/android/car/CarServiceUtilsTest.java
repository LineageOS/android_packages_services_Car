/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.car.test.mocks.AndroidMockitoHelper.mockAmGetCurrentUser;
import static android.car.test.mocks.AndroidMockitoHelper.mockContextCreateContextAsUser;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.automotive.vehicle.SubscribeOptions;
import android.os.UserHandle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;

public class CarServiceUtilsTest extends AbstractExtendedMockitoTestCase {

    private static final int TEST_PROP = 1;
    private static final int TEST_AREA_ID = 2;
    private static final float MIN_SAMPLE_RATE = 1.0f;
    private static final int CURRENT_USER_ID = 1000;
    private static final int NON_CURRENT_USER_ID = 1001;

    private MockitoSession mSession;
    @Mock
    private Context mMockContext;
    @Mock
    private Context mMockUserContext;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(ActivityManager.class);
    }

    @Before
    public void setUp() {
        mockAmGetCurrentUser(CURRENT_USER_ID);
    }

    @After
    public void tearDown() {
        if (mSession != null) {
            mSession.finishMocking();
            mSession = null;
        }
    }

    @Test
    public void testSubscribeOptionsToHidl() {
        SubscribeOptions aidlOptions = new SubscribeOptions();
        aidlOptions.propId = TEST_PROP;
        aidlOptions.sampleRate = MIN_SAMPLE_RATE;
        // areaIds would be ignored because HIDL subscribeOptions does not support it.
        aidlOptions.areaIds = new int[]{TEST_AREA_ID};
        android.hardware.automotive.vehicle.V2_0.SubscribeOptions hidlOptions =
                new android.hardware.automotive.vehicle.V2_0.SubscribeOptions();
        hidlOptions.propId = TEST_PROP;
        hidlOptions.sampleRate = MIN_SAMPLE_RATE;
        hidlOptions.flags = android.hardware.automotive.vehicle.V2_0.SubscribeFlags.EVENTS_FROM_CAR;

        android.hardware.automotive.vehicle.V2_0.SubscribeOptions gotHidlOptions =
                CarServiceUtils.subscribeOptionsToHidl(aidlOptions);

        assertThat(gotHidlOptions).isEqualTo(hidlOptions);
    }

    @Test
    public void testStartSystemUiForUser_systemUser_doesNotStartSystemUi() {
        int userId = UserHandle.SYSTEM.getIdentifier();
        mockContextCreateContextAsUser(mMockContext, mMockUserContext, userId);

        CarServiceUtils.startSystemUiForUser(mMockContext, userId);

        verify(mMockContext, never()).createContextAsUser(any(), anyInt());
        verify(mMockUserContext, never()).startService(any());
    }

    @Test
    public void testStartSystemUiForUser_primaryUser_doesNotStartSystemUi() {
        int userId = CURRENT_USER_ID;
        mockContextCreateContextAsUser(mMockContext, mMockUserContext, userId);

        CarServiceUtils.startSystemUiForUser(mMockContext, userId);

        verify(mMockContext, never()).createContextAsUser(any(), anyInt());
        verify(mMockUserContext, never()).startService(any());
    }

    @Test
    public void testStartSystemUiForUser_secondaryUser_startsSystemUi() {
        int userId = NON_CURRENT_USER_ID;
        mockContextCreateContextAsUser(mMockContext, mMockUserContext, userId);
        Resources resources = mock(Resources.class);
        String systemUiComponent = "test.systemui/test.systemui.TestSystemUIService";
        when(resources.getString(com.android.internal.R.string.config_systemUIServiceComponent))
                .thenReturn(systemUiComponent);
        when(mMockContext.getResources()).thenReturn(resources);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);

        CarServiceUtils.startSystemUiForUser(mMockContext, userId);

        verify(mMockUserContext).startService(intentCaptor.capture());
        assertThat(intentCaptor.getValue().getComponent()).isEqualTo(
                ComponentName.unflattenFromString(systemUiComponent));
    }
}
