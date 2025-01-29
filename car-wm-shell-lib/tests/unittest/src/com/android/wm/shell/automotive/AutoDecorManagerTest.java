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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class AutoDecorManagerTest {
    @Mock
    private DisplayController mDisplayController;
    @Mock
    private RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    @Mock
    private ShellExecutor mShellExecutor;
    @Mock
    private View mView;
    @Mock
    private SurfaceControl mParentSurface;
    @Mock
    private SurfaceControl.Transaction mTransaction;
    private AutoDecorManager mAutoDecorManager;
    private Rect mBounds;
    private final int mDisplayId = 0; // Define display ID

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mBounds = new Rect(10, 20, 100, 200);
        DisplayManager mDisplayManager = context.getSystemService(DisplayManager.class);
        Display defaultDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);

        when(mRootTaskDisplayAreaOrganizer.getDisplayAreaLeash(any(Integer.class))).thenReturn(
                mParentSurface);
        when(mDisplayController.getDisplay(any(Integer.class))).thenReturn(defaultDisplay);
        doAnswer((inv) -> {
            Runnable runnable = inv.getArgument(0);
            runnable.run(); //Run on the same thread for testing
            return null;
        }).when(mShellExecutor).execute(any(Runnable.class));

        mAutoDecorManager = new AutoDecorManager(context, mDisplayController,
                mRootTaskDisplayAreaOrganizer, mShellExecutor);
        when(mTransaction.setVisibility(any(), anyBoolean())).thenReturn(mTransaction);
        when(mTransaction.reparent(any(), any())).thenReturn(mTransaction);
        when(mTransaction.setLayer(any(), anyInt())).thenReturn(mTransaction);
        when(mTransaction.setPosition(any(), anyFloat(), anyFloat())).thenReturn(mTransaction);
    }

    @Test
    public void testCreateAutoDecor() {
        Looper.prepare();
        AutoDecor autoDecor = mAutoDecorManager.createAutoDecor(mView, 0, mBounds);

        assertThat(autoDecor).isNotNull();
    }

    @Test
    public void testAttachAutoDecorToDisplay() {
        Looper.prepare();
        AutoDecor autoDecor = mAutoDecorManager.createAutoDecor(mView, 0, mBounds);
        mAutoDecorManager.attachAutoDecorToDisplay(mTransaction, autoDecor, mDisplayId);

        assertThat(((AutoDecorImpl) autoDecor).isAttached()).isTrue();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachAutoDecorToDisplay_alreadyAttached() {
        Looper.prepare();
        AutoDecor autoDecor = mAutoDecorManager.createAutoDecor(mView, 0, mBounds);
        mAutoDecorManager.attachAutoDecorToDisplay(mTransaction, autoDecor, mDisplayId);

        mAutoDecorManager.attachAutoDecorToDisplay(mTransaction, autoDecor, mDisplayId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachAutoDecorToDisplay_deletedDecor() {
        Looper.prepare();
        AutoDecor autoDecor = mAutoDecorManager.createAutoDecor(mView, 0, mBounds);
        mAutoDecorManager.attachAutoDecorToDisplay(mTransaction, autoDecor, mDisplayId);
        mAutoDecorManager.removeAutoDecor(mTransaction, autoDecor);

        mAutoDecorManager.attachAutoDecorToDisplay(mTransaction, autoDecor, mDisplayId);
    }

    @Test
    public void testRemoveAutoDecor() {
        Looper.prepare();
        AutoDecor autoDecor = mAutoDecorManager.createAutoDecor(mView, 0, mBounds);
        mAutoDecorManager.attachAutoDecorToDisplay(mTransaction, autoDecor, mDisplayId);

        mAutoDecorManager.removeAutoDecor(mTransaction, autoDecor);

        verify(mTransaction).reparent(any(SurfaceControl.class), eq(null));
    }
}
