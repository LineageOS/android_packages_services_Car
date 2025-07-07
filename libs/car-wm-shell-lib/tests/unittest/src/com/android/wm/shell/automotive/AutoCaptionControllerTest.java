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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.car.Car;
import android.car.content.pm.CarPackageManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerToken;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidTestingRunner.class)
public class AutoCaptionControllerTest {

    private AutoCaptionController mController;
    @Mock
    private ShellTaskOrganizer mShellTaskOrganizer;
    @Mock
    private RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    @Mock
    private AutoSurfaceTransactionFactory mAutoSurfaceTransactionFactory;
    @Mock
    private AutoDecorManager mAutoDecorManager;
    @Mock
    private AutoTaskRepository mAutoTaskRepository;
    @Mock
    private CarPackageManager mCarPackageManager;
    @Mock
    private Context mContext;
    @Mock
    private Car mCar;
    @Mock
    AutoCaptionBarViewFactory mAutoCaptionBarViewFactory;
    private MockitoSession mSession;
    private Car.CarServiceLifecycleListener mCarServiceLifecycleListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mSession = mockitoSession()
                .initMocks(this)
                .mockStatic(Car.class)
                .mockStatic(UserHandle.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        when(mCar.getCarManager(Car.PACKAGE_SERVICE)).thenReturn(mCarPackageManager);

        ExtendedMockito.doAnswer(invocation -> {
            mCarServiceLifecycleListener = invocation.getArgument(3);
            mCarServiceLifecycleListener.onLifecycleChanged(mCar, true);
            return mCar;
        }).when(() -> Car.createCar(any(), any(), anyLong(), any()));

        mController = new AutoCaptionController(mContext, mShellTaskOrganizer, mAutoTaskRepository,
                mRootTaskDisplayAreaOrganizer, mAutoDecorManager, mAutoSurfaceTransactionFactory);
    }

    @After
    public void tearDown() {
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    @Test
    public void testSetAndRemoveSafeRegionForRootTask() {
        int rootTaskId = 1;
        RootTaskStack rootTaskStack = mock(RootTaskStack.class);
        ActivityManager.RunningTaskInfo taskInfo = mock(ActivityManager.RunningTaskInfo.class);
        when(rootTaskStack.getId()).thenReturn(rootTaskId);
        when(rootTaskStack.getRootTaskInfo()).thenReturn(taskInfo);
        taskInfo.token = mock(WindowContainerToken.class);

        Rect captionRegion = new Rect(0, 0, 100, 20);

        mController.setCaptionRegion(rootTaskStack, captionRegion,
                mAutoCaptionBarViewFactory);

        assertThat(mController.mCaptionRegionInfoPerRootTask.size()).isEqualTo(1);
        assertThat(mController.mCaptionRegionInfoPerRootTask.get(
                rootTaskId).getCaptionRegionBounds()).isEqualTo(captionRegion);

        mController.removeCaptionRegion(rootTaskStack);

        assertThat(mController.mCaptionRegionInfoPerRootTask.size()).isEqualTo(0);
    }

    @Test
    public void testSetAndRemoveSafeRegionForDisplay() {
        int displayId = 1;
        Rect captionRegion = new Rect(0, 0, 100, 20);
        when(mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId)).thenReturn(
                new DisplayAreaInfo(mock(WindowContainerToken.class), displayId, 0));

        mController.setCaptionRegion(displayId, captionRegion,
                mAutoCaptionBarViewFactory);

        assertThat(mController.mCaptionRegionInfoPerDisplay.size()).isEqualTo(1);
        assertThat(mController.mCaptionRegionInfoPerDisplay.get(
                displayId).getCaptionRegionBounds()).isEqualTo(captionRegion);

        mController.removeCaptionRegion(displayId);

        assertThat(mController.mCaptionRegionInfoPerRootTask.size()).isEqualTo(0);
    }
}

