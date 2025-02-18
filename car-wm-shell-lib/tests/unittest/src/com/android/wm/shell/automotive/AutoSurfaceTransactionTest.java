/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.automotive;


import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.window.SurfaceSyncGroup;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AutoSurfaceTransactionTest {

    private AutoSurfaceTransaction mTransaction;
    @Mock
    private AutoDecor mDecor;
    @Mock
    private SurfaceControlViewHost mViewHost;
    @Mock
    private SurfaceControl mSurface;
    @Mock
    private SurfaceSyncGroup mSurfaceSyncGroup;
    @Mock
    private SurfaceControl.Transaction mSurfaceTransaction;
    @Mock
    SurfaceControlViewHost.SurfacePackage mSurfacePackage;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTransaction = new AutoSurfaceTransaction("TestTransaction", mSurfaceSyncGroup,
                mSurfaceTransaction);
        when(mViewHost.getSurfacePackage()).thenReturn(mSurfacePackage);
        when(mSurfacePackage.getSurfaceControl()).thenReturn(mSurface);
        when(mDecor.getViewHost()).thenReturn(mViewHost);
    }

    @Test
    public void testSetBounds() {
        Rect newBounds = new Rect(50, 50, 150, 150);

        mTransaction.setBounds(mDecor, newBounds);
        mTransaction.apply();

        verify(mSurfaceTransaction).setPosition(mSurface, newBounds.left, newBounds.top);
        verify(mSurfaceSyncGroup).add(eq(mSurfacePackage), any());
        verify(mDecor).updateBounds(newBounds);
    }

    @Test
    public void testSetZOrder() {
        int zOrder = 10;

        mTransaction.setZOrder(mDecor, zOrder);
        mTransaction.apply();

        verify(mSurfaceTransaction).setLayer(mSurface, zOrder);
        verify(mDecor).updateZOrder(zOrder);
    }

    @Test
    public void testSetVisibility() {
        mTransaction.setVisibility(mDecor, false);
        mTransaction.apply();

        verify(mSurfaceTransaction).setVisibility(mSurface, false);
        verify(mDecor).updateVisibility(false);
    }
}
