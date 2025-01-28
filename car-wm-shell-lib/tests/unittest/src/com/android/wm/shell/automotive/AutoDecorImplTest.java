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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class AutoDecorImplTest {
    @Mock
    private DisplayController mDisplayController;
    @Mock
    private ShellExecutor mShellExecutor;
    @Mock
    private View mView;
    @Mock
    private SurfaceControl mParentSurface;
    @Mock
    private SurfaceControl.Transaction mTransaction;
    @Mock
    private WindowManager mWindowManager;

    private AutoDecorImpl mAutoDecor;
    private Rect mBounds;
    private int mZOrder;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mBounds = new Rect(10, 20, 100, 200);
        mZOrder = 5;

        DisplayManager mDisplayManager = context.getSystemService(DisplayManager.class);
        Display defaultDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);

        when(mDisplayController.getDisplay(any(Integer.class))).thenReturn(defaultDisplay);
        when(mWindowManager.getDefaultDisplay()).thenReturn(defaultDisplay);

        mAutoDecor = new AutoDecorImpl(context, mDisplayController, mView, mZOrder, mBounds,
                mShellExecutor);
        when(mTransaction.setVisibility(any(), anyBoolean())).thenReturn(mTransaction);
        when(mTransaction.reparent(any(), any())).thenReturn(mTransaction);
        when(mTransaction.setLayer(any(), anyInt())).thenReturn(mTransaction);
        when(mTransaction.setPosition(any(), anyFloat(), anyFloat())).thenReturn(mTransaction);
    }

    @Test
    public void testAttachDecorToParentSurface() {
        Looper.prepare();
        float left = mBounds.left;
        float top = mBounds.top;

        mAutoDecor.attachDecorToParentSurface(mTransaction, Display.DEFAULT_DISPLAY,
                mParentSurface);

        assertThat(mAutoDecor.isAttached()).isTrue();
        verify(mTransaction).reparent(any(SurfaceControl.class), eq(mParentSurface));
        verify(mTransaction).setPosition(any(SurfaceControl.class), eq(left), eq(top));
        verify(mTransaction).setLayer(any(SurfaceControl.class), eq(mZOrder));
        verify(mTransaction).show(any(SurfaceControl.class));
    }

    @Test
    public void testDetachDecorFromParentSurface() {
        Looper.prepare();
        mAutoDecor.attachDecorToParentSurface(mTransaction, Display.DEFAULT_DISPLAY,
                mParentSurface);

        mAutoDecor.detachDecorFromParentSurface(mTransaction);

        verify(mTransaction).reparent(any(SurfaceControl.class), eq(null));
    }


    @Test
    public void testSetBounds() {
        Looper.prepare();
        Rect newBounds = new Rect(50, 60, 150, 250);
        mAutoDecor.attachDecorToParentSurface(mTransaction, 0, mParentSurface);
        float left = newBounds.left;
        float top = newBounds.top;

        mAutoDecor.setBounds(mTransaction, newBounds);

        verify(mTransaction).setPosition(any(SurfaceControl.class), eq(left), eq(top));
    }

    @Test
    public void testSetZOrder() {
        Looper.prepare();
        int newZOrder = 10;
        mAutoDecor.attachDecorToParentSurface(mTransaction, 0, mParentSurface);

        mAutoDecor.setZOrder(mTransaction, newZOrder);

        verify(mTransaction).setLayer(any(SurfaceControl.class), eq(newZOrder));
    }

    @Test
    public void testSetVisibilityTrue() {
        Looper.prepare();
        mAutoDecor.attachDecorToParentSurface(mTransaction, 0, mParentSurface);

        mAutoDecor.setVisibility(mTransaction, true);

        verify(mTransaction).setVisibility(any(SurfaceControl.class), eq(true));
    }

    @Test
    public void testSetVisibilityFalse() {
        Looper.prepare();
        mAutoDecor.attachDecorToParentSurface(mTransaction, 0, mParentSurface);

        mAutoDecor.setVisibility(mTransaction, false);

        verify(mTransaction).setVisibility(any(SurfaceControl.class), eq(false));
    }
}
