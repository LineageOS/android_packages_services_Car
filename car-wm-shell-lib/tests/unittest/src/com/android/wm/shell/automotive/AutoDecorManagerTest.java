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
    private View mView;
    private AutoDecorManager mAutoDecorManager;
    private Rect mBounds;
    private final int mDisplayId = 0; // Define display ID
    private SurfaceControl mParentSurface;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mBounds = new Rect(10, 20, 100, 200);
        mParentSurface = (new SurfaceControl.Builder()).setName("test").build();

        DisplayManager mDisplayManager = context.getSystemService(DisplayManager.class);
        Display defaultDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);

        when(mRootTaskDisplayAreaOrganizer.getDisplayAreaLeash(any(Integer.class))).thenReturn(
                mParentSurface);
        when(mDisplayController.getDisplay(any(Integer.class))).thenReturn(defaultDisplay);

        mAutoDecorManager = new AutoDecorManager(context, mDisplayController,
                mRootTaskDisplayAreaOrganizer);
    }

    @Test
    public void testCreateAutoDecor() {
        Looper.prepare();
        AutoDecor autoDecor = mAutoDecorManager.createAutoDecor(mView, 0, mBounds, "testDecor");

        assertThat(autoDecor).isNotNull();
    }

    @Test
    public void testAttachAutoDecorToDisplay() {
        Looper.prepare();
        AutoDecor autoDecor = mAutoDecorManager.createAutoDecor(mView, 0, mBounds, "testDecor");
        mAutoDecorManager.attachAutoDecorToDisplay(autoDecor, mDisplayId);

        assertThat(autoDecor.isCurrentlyAttached()).isTrue();
        assertThat(autoDecor.isEverAttached()).isTrue();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachAutoDecorToDisplay_alreadyAttached() {
        Looper.prepare();
        AutoDecor autoDecor = mAutoDecorManager.createAutoDecor(mView, 0, mBounds, "testDecor");
        mAutoDecorManager.attachAutoDecorToDisplay(autoDecor, mDisplayId);

        mAutoDecorManager.attachAutoDecorToDisplay(autoDecor, mDisplayId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachAutoDecorToDisplay_deletedDecor() {
        Looper.prepare();
        AutoDecor autoDecor = mAutoDecorManager.createAutoDecor(mView, 0, mBounds, "testDecor");
        mAutoDecorManager.attachAutoDecorToDisplay(autoDecor, mDisplayId);
        mAutoDecorManager.removeAutoDecor(autoDecor);

        mAutoDecorManager.attachAutoDecorToDisplay(autoDecor, mDisplayId);
    }

    @Test
    public void testRemoveAutoDecor() {
        Looper.prepare();
        AutoDecor autoDecor = mAutoDecorManager.createAutoDecor(mView, 0, mBounds, "testDecor");
        mAutoDecorManager.attachAutoDecorToDisplay(autoDecor, mDisplayId);

        mAutoDecorManager.removeAutoDecor(autoDecor);

        assertThat(autoDecor.isCurrentlyAttached()).isFalse();
        assertThat(autoDecor.isEverAttached()).isTrue();
    }
}
