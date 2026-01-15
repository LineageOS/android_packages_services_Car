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

import static android.car.feature.Flags.FLAG_DISPLAY_COMPATIBILITY_V2;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AppCompatTaskInfo;
import android.car.Car;
import android.car.content.pm.CarPackageManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.AndroidTestingRunner;
import android.util.SparseArray;
import android.view.View;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerToken;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.window.flags.Flags;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.automotive.utility.TestRunningTaskInfoBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;

@RunWith(AndroidTestingRunner.class)
@EnableFlags(Flags.FLAG_SAFE_REGION_LETTERBOXING_V1)
public class AutoCaptionControllerTest {
    private static final String TEST_PKG_NAME = "test.package";
    private static final String TEST_CLASS_NAME = "test.class";
    private static final int TEST_USER_ID = 99;
    private static final int TEST_DISPLAY_ID = 101;
    private static final int TEST_TASK_ID = 67;
    private static final int TEST_ROOT_TASK_ID = 121;

    @Rule
    public SetFlagsRule  mSetFlagsRule = new SetFlagsRule();
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
    private AutoCaptionBarViewController mAutoCaptionBarViewController;
    @Mock
    private AutoDecor mAutoDecor;
    @Mock
    private RootTaskStack mRootTaskStack;
    @Mock
    private AutoSurfaceTransaction mAutoSurfaceTransaction;
    @Mock
    private View mView;

    private AutoCaptionController mController;
    private MockitoSession mSession;
    private Car.CarServiceLifecycleListener mCarServiceLifecycleListener;
    private AutoTaskRepository.AutoAppTaskListener mAutoAppTaskListener;

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
        when(mAutoSurfaceTransactionFactory.createTransaction(anyString())).thenReturn(
                mAutoSurfaceTransaction);

        ExtendedMockito.doAnswer(invocation -> {
            mCarServiceLifecycleListener = invocation.getArgument(3);
            mCarServiceLifecycleListener.onLifecycleChanged(mCar, true);
            return mCar;
        }).when(() -> Car.createCar(any(), any(), anyLong(), any()));

        mController = new AutoCaptionController(mContext, mShellTaskOrganizer, mAutoTaskRepository,
                mRootTaskDisplayAreaOrganizer, mAutoDecorManager, mAutoSurfaceTransactionFactory);

        ArgumentCaptor<AutoTaskRepository.AutoAppTaskListener> autoAppTaskListenerCaptor =
                ArgumentCaptor.forClass(AutoTaskRepository.AutoAppTaskListener.class);
        verify(mAutoTaskRepository).addAppTaskListener(autoAppTaskListenerCaptor.capture());
        mAutoAppTaskListener = autoAppTaskListenerCaptor.getValue();
        assertThat(mAutoAppTaskListener).isNotNull();
    }

    @After
    public void tearDown() {
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    @EnableFlags(FLAG_DISPLAY_COMPATIBILITY_V2)
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
                mAutoCaptionBarViewController);

        assertThat(mController.getCaptionRegionInfoPerRootTask().size()).isEqualTo(1);
        assertThat(mController.getCaptionRegionInfoPerRootTask().get(
                rootTaskId).getCaptionRegionBounds()).isEqualTo(captionRegion);

        mController.removeCaptionRegion(rootTaskStack);

        assertThat(mController.getCaptionRegionInfoPerRootTask().size()).isEqualTo(0);
    }

    @EnableFlags(FLAG_DISPLAY_COMPATIBILITY_V2)
    @Test
    public void testSetAndRemoveSafeRegionForDisplay() {
        int displayId = 1;
        Rect captionRegion = new Rect(0, 0, 100, 20);
        when(mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId)).thenReturn(
                new DisplayAreaInfo(mock(WindowContainerToken.class), displayId, 0));

        mController.setCaptionRegion(displayId, captionRegion,
                mAutoCaptionBarViewController);

        assertThat(mController.getCaptionRegionInfoPerDisplay().size()).isEqualTo(1);
        assertThat(mController.getCaptionRegionInfoPerDisplay().get(
                displayId).getCaptionRegionBounds()).isEqualTo(captionRegion);

        mController.removeCaptionRegion(displayId);

        assertThat(mController.getCaptionRegionInfoPerRootTask().size()).isEqualTo(0);
    }

    @EnableFlags(FLAG_DISPLAY_COMPATIBILITY_V2)
    @Test
    public void onTaskChanged_doesRequireCaptionBar_controllerForRootTaskNotified()
            throws PackageManager.NameNotFoundException {
        boolean requireCaptionBar = true;
        ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(TEST_DISPLAY_ID)
                .setTaskId(TEST_TASK_ID)
                .setParentTaskId(TEST_ROOT_TASK_ID)
                .setTopActivity(new ComponentName(TEST_PKG_NAME, TEST_CLASS_NAME))
                .setUserId(TEST_USER_ID)
                .setIsTopActivitySafeRegionLetterboxed(requireCaptionBar)
                .build();
        AutoCaptionController.CaptionRegionInfo captionRegionInfo =
                new AutoCaptionController.CaptionRegionInfo(
                        new Rect(), mAutoCaptionBarViewController);
        when(mRootTaskStack.getId()).thenReturn(TEST_ROOT_TASK_ID);
        when(mAutoTaskRepository.getRootTaskStack(TEST_ROOT_TASK_ID)).thenReturn(mRootTaskStack);
        when(mCarPackageManager.requiresDisplayCompatForUser(eq(TEST_PKG_NAME),
                eq(TEST_USER_ID))).thenReturn(requireCaptionBar);
        when(mAutoDecor.getView()).thenReturn(mView);

        // Simulate a caption bar already being attached to the task
        mController.getTaskIdToCaptionBar().append(TEST_TASK_ID, mAutoDecor);
        mController.getCaptionRegionInfoPerRootTask().append(TEST_ROOT_TASK_ID, captionRegionInfo);
        mAutoAppTaskListener.onTaskChanged(taskInfo);

        verify(mAutoCaptionBarViewController).updateView(eq(mView), eq(taskInfo));
    }

    @EnableFlags(FLAG_DISPLAY_COMPATIBILITY_V2)
    @Test
    public void onTaskChanged_doesRequireCaptionBar_controllerForDisplayNotified()
            throws PackageManager.NameNotFoundException {
        boolean requireCaptionBar = true;
        ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(TEST_DISPLAY_ID)
                .setTaskId(TEST_TASK_ID)
                .setParentTaskId(-1)
                .setTopActivity(new ComponentName(TEST_PKG_NAME, TEST_CLASS_NAME))
                .setUserId(TEST_USER_ID)
                .setIsTopActivitySafeRegionLetterboxed(requireCaptionBar)
                .build();
        AutoCaptionController.CaptionRegionInfo captionRegionInfo =
                new AutoCaptionController.CaptionRegionInfo(
                        new Rect(), mAutoCaptionBarViewController);
        when(mCarPackageManager.requiresDisplayCompatForUser(eq(TEST_PKG_NAME),
                eq(TEST_USER_ID))).thenReturn(requireCaptionBar);
        when(mAutoDecor.getView()).thenReturn(mView);

        // Simulate a caption bar already being attached to the task
        mController.getTaskIdToCaptionBar().append(TEST_TASK_ID, mAutoDecor);
        mController.getCaptionRegionInfoPerDisplay().append(TEST_DISPLAY_ID, captionRegionInfo);
        mAutoAppTaskListener.onTaskChanged(taskInfo);

        verify(mAutoCaptionBarViewController).updateView(eq(mView), eq(taskInfo));
    }

    @EnableFlags(FLAG_DISPLAY_COMPATIBILITY_V2)
    @Test
    public void onTaskChanged_doesNotRequireCaptionBar_controllerNotNotified()
            throws PackageManager.NameNotFoundException {
        boolean requireCaptionBar = false;
        ActivityManager.RunningTaskInfo taskInfo = new TestRunningTaskInfoBuilder()
                .setDisplayId(TEST_DISPLAY_ID)
                .setTaskId(TEST_TASK_ID)
                .setParentTaskId(TEST_ROOT_TASK_ID)
                .setTopActivity(new ComponentName(TEST_PKG_NAME, TEST_CLASS_NAME))
                .setUserId(TEST_USER_ID)
                .setIsTopActivitySafeRegionLetterboxed(requireCaptionBar)
                .build();
        AutoCaptionController.CaptionRegionInfo captionRegionInfo =
                new AutoCaptionController.CaptionRegionInfo(
                        new Rect(), mAutoCaptionBarViewController);
        when(mRootTaskStack.getId()).thenReturn(TEST_ROOT_TASK_ID);
        when(mAutoTaskRepository.getRootTaskStack(TEST_ROOT_TASK_ID)).thenReturn(mRootTaskStack);
        when(mCarPackageManager.requiresDisplayCompatForUser(eq(TEST_PKG_NAME),
                eq(TEST_USER_ID))).thenReturn(requireCaptionBar);
        when(mAutoDecor.getView()).thenReturn(mView);

        // Simulate a caption bar already being attached to the task
        mController.getTaskIdToCaptionBar().append(TEST_TASK_ID, mAutoDecor);
        mController.getCaptionRegionInfoPerRootTask().append(TEST_ROOT_TASK_ID, captionRegionInfo);
        mAutoAppTaskListener.onTaskChanged(taskInfo);

        verify(mAutoCaptionBarViewController, never()).updateView(any(View.class),
                any(ActivityManager.RunningTaskInfo.class));
    }

    @Test
    public void testSetCaptionRegionForRootTask_updatesExistingCaptionBar() throws Exception {
        int rootTaskId = 1;
        RootTaskStack rootTaskStack = mock(RootTaskStack.class);
        when(rootTaskStack.getId()).thenReturn(rootTaskId);
        ActivityManager.RunningTaskInfo taskInfo = setupAppCompatTaskInfo(
                rootTaskId, /* isRootTask= */ true);
        setupRunningTasks(taskInfo);

        // Initial setup
        Rect initialRegion = new Rect(0, 0, 100, 50);
        AutoDecor initialDecor = mock(AutoDecor.class);
        when(mAutoDecorManager.createAutoDecor(any(), anyInt(), eq(initialRegion), anyString()))
                .thenReturn(initialDecor);
        mController.setCaptionRegion(rootTaskStack, initialRegion, mAutoCaptionBarViewController);
        when(mAutoCaptionBarViewController.createView(any())).thenReturn(mock(View.class));
        when(mAutoTaskRepository.getRootTaskStack(rootTaskId)).thenReturn(rootTaskStack);
        mAutoAppTaskListener.onTaskAppeared(taskInfo);

        // Update the caption region, this would remove the old caption bar.
        Rect updatedRegion = new Rect(10, 10, 110, 60);
        AutoDecor updatedDecor = mock(AutoDecor.class);
        when(mAutoDecorManager.createAutoDecor(any(), anyInt(), eq(updatedRegion), anyString()))
                .thenReturn(updatedDecor);
        mController.setCaptionRegion(rootTaskStack, updatedRegion, mAutoCaptionBarViewController);

        // When the task appears again, a new caption bar is created with the updated bounds.
        mAutoAppTaskListener.onTaskAppeared(taskInfo);
        verify(mAutoDecorManager).createAutoDecor(any(), anyInt(), eq(updatedRegion),
                anyString());
        assertThat(mController.getTaskIdToCaptionBar().get(taskInfo.taskId)).isSameInstanceAs(
                updatedDecor);
    }

    @Test
    public void testSetCaptionRegionForDisplay_updatesExistingCaptionBar() throws Exception {
        int displayId = 1;
        when(mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId)).thenReturn(
                new DisplayAreaInfo(mock(WindowContainerToken.class), displayId, 0));
        ActivityManager.RunningTaskInfo taskInfo = setupAppCompatTaskInfo(
                displayId, /* isRootTask= */ false);
        setupRunningTasks(taskInfo);

        // Initial setup
        Rect initialRegion = new Rect(0, 0, 100, 50);
        AutoDecor initialDecor = mock(AutoDecor.class);
        when(mAutoDecorManager.createAutoDecor(any(), anyInt(), eq(initialRegion), anyString()))
                .thenReturn(initialDecor);
        mController.setCaptionRegion(displayId, initialRegion, mAutoCaptionBarViewController);
        when(mAutoCaptionBarViewController.createView(any())).thenReturn(mock(View.class));
        mAutoAppTaskListener.onTaskAppeared(taskInfo);

        // Update the caption region, this would remove the old caption bar.
        Rect updatedRegion = new Rect(10, 10, 110, 60);
        AutoDecor updatedDecor = mock(AutoDecor.class);
        when(mAutoDecorManager.createAutoDecor(any(), anyInt(), eq(updatedRegion), anyString()))
                .thenReturn(updatedDecor);
        mController.setCaptionRegion(displayId, updatedRegion, mAutoCaptionBarViewController);

        // When the task appears again, a new caption bar is created with the updated bounds.
        mAutoAppTaskListener.onTaskAppeared(taskInfo);
        verify(mAutoDecorManager).createAutoDecor(any(), anyInt(), eq(updatedRegion),
                anyString());
        assertThat(mController.getTaskIdToCaptionBar().get(taskInfo.taskId)).isSameInstanceAs(
                updatedDecor);
    }

    private void setupRunningTasks(ActivityManager.RunningTaskInfo taskInfo) throws Exception {
        when(mCarPackageManager.requiresDisplayCompatForUser(
                taskInfo.topActivity.getPackageName(), taskInfo.userId)).thenReturn(true);
        SparseArray<ActivityManager.RunningTaskInfo> tasks =
                new SparseArray<>();
        tasks.put(taskInfo.taskId, taskInfo);
        when(mAutoTaskRepository.getRunningTasks()).thenReturn(tasks);
    }

    private ActivityManager.RunningTaskInfo setupAppCompatTaskInfo(int containerId,
            boolean isRootTask)
            throws Exception {
        int taskId = 123;
        ActivityManager.RunningTaskInfo taskInfo = new ActivityManager.RunningTaskInfo();
        taskInfo.taskId = taskId;
        if (isRootTask) {
            taskInfo.parentTaskId = containerId;
        } else {
            taskInfo.displayId = containerId;
            taskInfo.parentTaskId = -1; // Top level task on display
        }
        taskInfo.topActivity = new ComponentName("test.pkg", "TestActivity");
        taskInfo.userId = 10;
        AppCompatTaskInfo mockAppCompatTaskInfo = mock(AppCompatTaskInfo.class);
        when(mockAppCompatTaskInfo.isTopActivitySafeRegionLetterboxed()).thenReturn(true);
        Field field = ActivityManager.RunningTaskInfo.class.getField("appCompatTaskInfo");
        field.set(taskInfo, mockAppCompatTaskInfo);
        return taskInfo;
    }
}
