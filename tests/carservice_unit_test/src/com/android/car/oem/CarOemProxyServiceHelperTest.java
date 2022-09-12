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

package com.android.car.oem;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.Context;
import android.content.res.Resources;
import android.os.Process;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.concurrent.TimeoutException;

public class CarOemProxyServiceHelperTest extends AbstractExtendedMockitoTestCase {
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;

    private CarOemProxyServiceHelper mCarOemProxyServiceHelper;

    @Before
    public void setUp() throws Exception {
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getInteger(anyInt())).thenReturn(5000);
        mCarOemProxyServiceHelper = new CarOemProxyServiceHelper(mContext);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(Process.class);
    }

    @Test
    public void testDoBinderTimedCallWithDefault_returnCalculatedValue() throws Exception {
        assertThat(mCarOemProxyServiceHelper.doBinderTimedCall(() -> "value",
                /* defaultValue= */ "default")).isEqualTo("value");
    }

    @Test
    public void testDoBinderTimedCallWithDefault_returnDefaultValue() throws Exception {
        when(mResources.getInteger(anyInt())).thenReturn(10);
        CarOemProxyServiceHelper carOemProxyServiceHelper = new CarOemProxyServiceHelper(mContext);

        assertThat(carOemProxyServiceHelper.doBinderTimedCall(() -> {
            Thread.sleep(1000); // test will not wait for this timeout
            return "value";
        }, /* defaultValue= */ "default")).isEqualTo("default");

    }

    @Test
    public void testDoBinderTimedCall_timeoutException() throws Exception {
        assertThrows(TimeoutException.class, () -> {
            mCarOemProxyServiceHelper.doBinderTimedCall(() -> {
                Thread.sleep(1000); // test will not wait for this timeout
                return 42;
            }, /* timeout= */ 10);
        });
    }

    @Test
    public void testDoBinderTimedCall_returnCalculatedValue() throws Exception {
        assertThat(mCarOemProxyServiceHelper.doBinderTimedCall(() -> 42,
                /* timeout= */ 1000)).isEqualTo(42);
    }

    @Test
    public void testDoBinderCallTimeoutCrash_returnCalculatedValue() throws Exception {
        assertThat(mCarOemProxyServiceHelper.doBinderCallWithTimeoutCrash(() -> 42)).isEqualTo(42);
    }

    @Test
    public void testDoBinderCallTimeoutCrash_withCrash() throws Exception {
        doAnswer(inv -> {
            throw new IllegalStateException();
        }).when(() -> Process.killProcess(anyInt()));

        when(mResources.getInteger(anyInt())).thenReturn(10);
        CarOemProxyServiceHelper carOemProxyServiceHelper = new CarOemProxyServiceHelper(mContext);

        assertThrows(IllegalStateException.class, () -> {
            carOemProxyServiceHelper.doBinderCallWithTimeoutCrash(() -> {
                Thread.sleep(1000); // test will not wait for this timeout
                return 42;
            });
        });
    }

    @Test
    public void testCrashCarService() {
        doAnswer(inv -> {
            throw new IllegalStateException();
        }).when(() -> Process.killProcess(anyInt()));

        assertThrows(IllegalStateException.class,
                () -> mCarOemProxyServiceHelper.crashCarService(""));
    }

}
