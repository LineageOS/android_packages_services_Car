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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.graphics.Rect;
import android.os.Binder;
import android.util.ArraySet;
import android.view.InsetsFrameProvider;
import android.view.WindowInsets;
import android.window.WindowContainerToken;

import com.android.wm.shell.ShellTaskOrganizer;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AutoLayoutManagerTest {

    private static final int TEST_TASK_ID = 1;
    private static final int TEST_INSET_INDEX = 0;
    private static final int TEST_INSET_TYPE = WindowInsets.Type.statusBars();
    private static final Rect TEST_INSET_FRAME = new Rect(0, 0, 100, 50);
    private static final Rect TEST_SAFE_REGION = new Rect(10, 20, 900, 500);

    @Mock
    private ShellTaskOrganizer mShellTaskOrganizer;
    @Mock
    private AutoTaskRepository mAutoTaskRepository;
    @Mock
    private WindowContainerToken mWindowContainerToken;
    @Mock
    private RootTaskStack mRootTaskStack;
    @Mock
    private ActivityManager.RunningTaskInfo mRunningTaskInfo;

    private AutoLayoutManager mAutoLayoutManager;
    private Binder mInsetToken;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mRootTaskStack.getRootTaskInfo()).thenReturn(mRunningTaskInfo);
        mRunningTaskInfo.taskId = TEST_TASK_ID;
        mRunningTaskInfo.token = mWindowContainerToken;
        when(mAutoTaskRepository.getTaskInfo(TEST_TASK_ID)).thenReturn(mRunningTaskInfo);
        mAutoLayoutManager = new AutoLayoutManager(mShellTaskOrganizer, mAutoTaskRepository);
    }

    @Test
    public void testSetOrUpdateSafeRegion_setsSafeRegion() {
        mAutoLayoutManager.setOrUpdateSafeRegion(mWindowContainerToken, TEST_SAFE_REGION);

        verify(mShellTaskOrganizer).applyTransaction(any());
    }

    @Test
    public void testAddOrUpdateInsets_addsInset() {
        mAutoLayoutManager.addOrUpdateInsets(mRootTaskStack, TEST_INSET_INDEX, TEST_INSET_TYPE,
                TEST_INSET_FRAME);

        verify(mShellTaskOrganizer).applyTransaction(any());

        ArraySet<InsetsFrameProvider> insetFrameProviders =
                mAutoLayoutManager.mTaskIdToInsetFrameProviderMap.get(TEST_TASK_ID);

        assertThat(insetFrameProviders.size()).isEqualTo(1);
        assertThat(insetFrameProviders.valueAt(0).getIndex()).isEqualTo(TEST_INSET_INDEX);
        assertThat(insetFrameProviders.valueAt(0).getType()).isEqualTo(TEST_INSET_TYPE);

        mAutoLayoutManager.removeInsets(mRootTaskStack, TEST_INSET_INDEX, TEST_INSET_TYPE);
    }

    @Test
    public void testAddOrUpdateInsets_updatesExistingInset() {
        mAutoLayoutManager.addOrUpdateInsets(mRootTaskStack, TEST_INSET_INDEX, TEST_INSET_TYPE,
                TEST_INSET_FRAME);
        Rect updatedFrame = new Rect(0, 0, 200, 100);
        mAutoLayoutManager.addOrUpdateInsets(mRootTaskStack, TEST_INSET_INDEX, TEST_INSET_TYPE,
                updatedFrame);


        ArraySet<InsetsFrameProvider> insetFrameProviders =
                mAutoLayoutManager.mTaskIdToInsetFrameProviderMap.get(TEST_TASK_ID);

        assertThat(insetFrameProviders.size()).isEqualTo(1);
        assertThat(insetFrameProviders.valueAt(0).getIndex()).isEqualTo(TEST_INSET_INDEX);
        assertThat(insetFrameProviders.valueAt(0).getType()).isEqualTo(TEST_INSET_TYPE);

        mAutoLayoutManager.removeInsets(mRootTaskStack, TEST_INSET_INDEX, TEST_INSET_TYPE);
    }

    @Test
    public void testRemoveInsets_removesExistingInset() {
        mAutoLayoutManager.addOrUpdateInsets(mRootTaskStack, TEST_INSET_INDEX, TEST_INSET_TYPE,
                TEST_INSET_FRAME);
        mAutoLayoutManager.removeInsets(mRootTaskStack, TEST_INSET_INDEX, TEST_INSET_TYPE);

        ArraySet<InsetsFrameProvider> insetFrameProviders =
                mAutoLayoutManager.mTaskIdToInsetFrameProviderMap.get(TEST_TASK_ID);
        assertThat(insetFrameProviders).isNull();
    }

    @Test
    public void testRemoveInsets_removesOneOfMultipleInsets() {
        mAutoLayoutManager.addOrUpdateInsets(mRootTaskStack, TEST_INSET_INDEX, TEST_INSET_TYPE,
                TEST_INSET_FRAME);
        mAutoLayoutManager.addOrUpdateInsets(mRootTaskStack, 1, WindowInsets.Type.navigationBars(),
                new Rect(0, 0, 50, 100));

        ArraySet<InsetsFrameProvider> insetFrameProviders =
                mAutoLayoutManager.mTaskIdToInsetFrameProviderMap.get(TEST_TASK_ID);

        assertThat(insetFrameProviders.size()).isEqualTo(2);

        mAutoLayoutManager.removeInsets(mRootTaskStack, TEST_INSET_INDEX, TEST_INSET_TYPE);

        insetFrameProviders =
                mAutoLayoutManager.mTaskIdToInsetFrameProviderMap.get(TEST_TASK_ID);
        assertThat(insetFrameProviders.size()).isEqualTo(1);
        assertThat(insetFrameProviders.valueAt(0).getIndex()).isEqualTo(1);
        assertThat(insetFrameProviders.valueAt(0).getType()).isEqualTo(
                WindowInsets.Type.navigationBars());

        mAutoLayoutManager.removeInsets(mRootTaskStack, 1, WindowInsets.Type.navigationBars());
    }

    @Test
    public void testRemoveAllInsetForTask_removesAllInsets() {
        mAutoLayoutManager.addOrUpdateInsets(mRootTaskStack, TEST_INSET_INDEX, TEST_INSET_TYPE,
                TEST_INSET_FRAME);
        mAutoLayoutManager.addOrUpdateInsets(mRootTaskStack, 1, WindowInsets.Type.navigationBars(),
                new Rect(0, 0, 50, 100));

        mAutoLayoutManager.removeInsets(mRootTaskStack, TEST_INSET_INDEX, TEST_INSET_TYPE);
        mAutoLayoutManager.removeInsets(mRootTaskStack, 1, WindowInsets.Type.navigationBars());

        ArraySet<InsetsFrameProvider> insetFrameProviders =
                mAutoLayoutManager.mTaskIdToInsetFrameProviderMap.get(TEST_TASK_ID);
        assertThat(insetFrameProviders).isNull();
    }
}
