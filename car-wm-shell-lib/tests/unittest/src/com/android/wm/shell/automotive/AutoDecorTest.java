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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import com.android.wm.shell.common.DisplayController;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class AutoDecorTest {

    private AutoDecor mAutoDecor;
    @Mock
    private Context mContext;
    @Mock
    private DisplayController mDisplayController;
    @Mock
    private View mView;
    private final int mZOrder = 10;
    private final Rect mBounds = new Rect(0, 0, 100, 100);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mDisplayController.getDisplay(Mockito.anyInt())).thenReturn(
                mock(android.view.Display.class));
        mAutoDecor = new AutoDecor(mContext, mDisplayController, mView, mZOrder, mBounds,
                "TestDecor");
    }

    @Test
    public void testGetView() {
        assertThat(mView).isEqualTo(mAutoDecor.getView());
    }

    @Test
    public void testGetZOrder() {
        assertThat(mAutoDecor.getZOrder()).isEqualTo(mZOrder);
    }

    @Test
    public void testGetBounds() {
        assertThat(mAutoDecor.getBounds()).isEqualTo(mBounds);
    }

    @Test
    public void testIsCurrentlyAttached() {
        assertThat(mAutoDecor.isCurrentlyAttached()).isFalse();
    }

    @Test
    public void testIsEverAttached() {
        assertThat(mAutoDecor.isEverAttached()).isFalse();
    }

    @Test
    public void testIsVisible() {
        assertThat(mAutoDecor.isVisible()).isFalse();
    }

    @Test
    public void testUpdateVisibility() {
        mAutoDecor.updateVisibility(true);
        assertThat(mAutoDecor.isVisible()).isTrue();
        mAutoDecor.updateVisibility(false);
        assertThat(mAutoDecor.isVisible()).isFalse();
    }

    @Test
    public void testUpdateZOrder() {
        int newZOrder = 20;
        mAutoDecor.updateZOrder(newZOrder);
        assertThat(mAutoDecor.getZOrder()).isEqualTo(newZOrder);
    }

    @Test
    public void testUpdateBounds() {
        Rect newBounds = new Rect(50, 50, 150, 150);
        mAutoDecor.updateBounds(newBounds);
        assertThat(mAutoDecor.getBounds()).isEqualTo(newBounds);
    }

    @Test
    public void testGetName() {
        assertThat(mAutoDecor.getName()).isEqualTo("TestDecor");
    }
}
