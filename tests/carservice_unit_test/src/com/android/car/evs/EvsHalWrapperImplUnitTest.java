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

package com.android.car.evs;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.spy;

import static com.google.common.truth.Truth.assertThat;

import com.android.car.internal.evs.EvsHalWrapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class EvsHalWrapperImplUnitTest {

    private static final String TAG = EvsHalWrapperImplUnitTest.class.getSimpleName();

    private EvsHalWrapperImpl mEvsHalWrapper = null;
    @Mock EvsHalWrapper.HalEventCallback mEventCallback;

    @Before
    public void setUp() {
        mEvsHalWrapper = new EvsHalWrapperImpl(mEventCallback);
        assertThat(mEvsHalWrapper.init()).isTrue();
    }

    @After
    public void tearDown() throws Exception {
        if (mEvsHalWrapper != null) {
            mEvsHalWrapper.release();
        }
    }

    @Test
    public void testIsConnected() {
        assertThat(mEvsHalWrapper.isConnected()).isTrue();
    }

    @Test
    public void testConnectToHalServiceIfNecessary() {
        // connectToHalServiceIfNecessary() returns false because this test is not running as either
        // one of root, system, or automotive_evs.
        assertThat(mEvsHalWrapper.connectToHalServiceIfNecessary()).isFalse();
        mEvsHalWrapper.disconnectFromHalService();
    }
}
