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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.content.Context;
import android.os.UserHandle;
import android.view.SurfaceControl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

@RunWith(MockitoJUnitRunner.class)
public class TaskRepositoryTest {

    private TaskRepository mTaskRepository;
    private RootTaskStack mRootTaskStack1;
    private RootTaskStack mRootTaskStack2;
    @Mock
    private Context mContext;

    @Mock
    private Car mCar;

    @Mock
    private CarActivityManager mCarActivityManager;

    private Car.CarServiceLifecycleListener mCarServiceLifecycleListener;

    private MockitoSession mSession;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mSession = mockitoSession()
                .initMocks(this)
                .mockStatic(Car.class)
                .mockStatic(UserHandle.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        when(mCar.getCarManager(Car.CAR_ACTIVITY_SERVICE)).thenReturn(mCarActivityManager);

        doAnswer(invocation -> {
            return UserHandle.USER_SYSTEM;
        }).when(() -> UserHandle.getCallingUserId());

        doAnswer(invocation -> {
            mCarServiceLifecycleListener = invocation.getArgument(3);
            mCarServiceLifecycleListener.onLifecycleChanged(mCar, true);
            return mCar;
        }).when(() -> Car.createCar(any(), any(), anyLong(), any()));

        mTaskRepository = new TaskRepository(mContext);
        mRootTaskStack1 = new RootTaskStack(1, 0, mock(SurfaceControl.class),
                createMockTaskInfo(1));
        mRootTaskStack2 = new RootTaskStack(1, 0, mock(SurfaceControl.class),
                createMockTaskInfo(1));
    }

    @After
    public void tearDown() {
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    @Test
    public void testAddTask() {
        ActivityManager.RunningTaskInfo taskInfo1 = createMockTaskInfo(1);
        SurfaceControl surfaceControl1 = mock(SurfaceControl.class);

        mTaskRepository.onRootTaskStackCreated(mRootTaskStack1);
        mTaskRepository.onTaskAppeared(mRootTaskStack1, taskInfo1, surfaceControl1);

        assertThat(mTaskRepository.getTaskStack(mRootTaskStack1)).hasSize(1);
        assertThat(mTaskRepository.getSurfaceControl(taskInfo1)).isEqualTo(surfaceControl1);
        verify(mCarActivityManager).onTaskAppeared(any(), any());
        verify(mCarActivityManager).onRootTaskAppeared(anyInt(), any());
    }

    @Test
    public void testUpdateTask() {
        ActivityManager.RunningTaskInfo taskInfo1 = createMockTaskInfo(1);
        SurfaceControl surfaceControl1 = mock(SurfaceControl.class);

        mTaskRepository.onRootTaskStackCreated(mRootTaskStack1);
        mTaskRepository.onTaskAppeared(mRootTaskStack1, taskInfo1, surfaceControl1);
        mTaskRepository.onTaskChanged(mRootTaskStack1, taskInfo1);

        assertThat(mTaskRepository.getTaskStack(mRootTaskStack1)).hasSize(1);
        assertThat(mTaskRepository.getSurfaceControl(taskInfo1)).isEqualTo(surfaceControl1);
        verify(mCarActivityManager).onTaskAppeared(any(), any());
        verify(mCarActivityManager).onRootTaskAppeared(anyInt(), any());
        verify(mCarActivityManager).onTaskInfoChanged(any());
    }


    @Test
    public void testRemoveTask() {
        ActivityManager.RunningTaskInfo taskInfo1 = createMockTaskInfo(1);
        SurfaceControl surfaceControl1 = mock(SurfaceControl.class);

        mTaskRepository.onRootTaskStackCreated(mRootTaskStack1);
        mTaskRepository.onTaskAppeared(mRootTaskStack1, taskInfo1, surfaceControl1);
        mTaskRepository.onTaskVanished(mRootTaskStack1, taskInfo1);

        assertThat(mTaskRepository.getTaskStack(mRootTaskStack1)).isEmpty();
        assertThat(mTaskRepository.getSurfaceControl(taskInfo1)).isNull();
        verify(mCarActivityManager).onTaskAppeared(any(), any());
        verify(mCarActivityManager).onRootTaskAppeared(anyInt(), any());
        verify(mCarActivityManager).onTaskVanished(any());
    }

    @Test
    public void testRemoveRootTaskStack() {
        ActivityManager.RunningTaskInfo taskInfo1 = createMockTaskInfo(1);
        SurfaceControl surfaceControl1 = mock(SurfaceControl.class);

        mTaskRepository.onRootTaskStackCreated(mRootTaskStack1);
        mTaskRepository.onTaskAppeared(mRootTaskStack1, taskInfo1, surfaceControl1);
        mTaskRepository.onRootTaskStackDestroyed(mRootTaskStack1);

        assertThat(mTaskRepository.getTaskStack(mRootTaskStack1)).isNull();
        verify(mCarActivityManager).onTaskAppeared(any(), any());
        verify(mCarActivityManager).onRootTaskAppeared(anyInt(), any());
        verify(mCarActivityManager).onRootTaskVanished(anyInt());
    }

    @Test
    public void testMultipleRootTaskStacks() {
        ActivityManager.RunningTaskInfo taskInfo1 = createMockTaskInfo(1);
        SurfaceControl surfaceControl1 = mock(SurfaceControl.class);
        ActivityManager.RunningTaskInfo taskInfo2 = createMockTaskInfo(2);
        SurfaceControl surfaceControl2 = mock(SurfaceControl.class);

        mTaskRepository.onRootTaskStackCreated(mRootTaskStack1);
        mTaskRepository.onRootTaskStackCreated(mRootTaskStack2);
        mTaskRepository.onTaskAppeared(mRootTaskStack1, taskInfo1, surfaceControl1);
        mTaskRepository.onTaskAppeared(mRootTaskStack2, taskInfo2, surfaceControl2);

        assertThat(mTaskRepository.getTaskStack(mRootTaskStack1)).hasSize(1);
        assertThat(mTaskRepository.getTaskStack(mRootTaskStack2)).hasSize(1);
        assertThat(mTaskRepository.getSurfaceControl(taskInfo1)).isEqualTo(surfaceControl1);
        assertThat(mTaskRepository.getSurfaceControl(taskInfo2)).isEqualTo(surfaceControl2);
        verify(mCarActivityManager, times(2)).onTaskAppeared(any(), any());
        verify(mCarActivityManager, times(2)).onRootTaskAppeared(anyInt(), any());
    }

    @Test
    public void testAddTask_WithoutRootTask() {
        ActivityManager.RunningTaskInfo taskInfo1 = createMockTaskInfo(1);
        SurfaceControl surfaceControl1 = mock(SurfaceControl.class);

        mTaskRepository.onTaskAppeared(taskInfo1, surfaceControl1);

        assertThat(mTaskRepository.getTaskStackWithoutRootTask()).hasSize(1);
        assertThat(mTaskRepository.getSurfaceControl(taskInfo1)).isEqualTo(surfaceControl1);
        verify(mCarActivityManager).onTaskAppeared(any(), any());
    }

    @Test
    public void testUpdateTask_WithoutRootTask() {
        ActivityManager.RunningTaskInfo taskInfo1 = createMockTaskInfo(1);
        SurfaceControl surfaceControl1 = mock(SurfaceControl.class);

        mTaskRepository.onTaskAppeared(taskInfo1, surfaceControl1);
        mTaskRepository.onTaskChanged(taskInfo1);

        assertThat(mTaskRepository.getTaskStackWithoutRootTask()).hasSize(1);
        assertThat(mTaskRepository.getSurfaceControl(taskInfo1)).isEqualTo(surfaceControl1);
        verify(mCarActivityManager).onTaskAppeared(any(), any());
        verify(mCarActivityManager).onTaskInfoChanged(any());
    }


    @Test
    public void testRemoveTask_WithoutRootTask() {
        ActivityManager.RunningTaskInfo taskInfo1 = createMockTaskInfo(1);
        SurfaceControl surfaceControl1 = mock(SurfaceControl.class);

        mTaskRepository.onTaskAppeared(mRootTaskStack1, taskInfo1, surfaceControl1);
        mTaskRepository.onTaskVanished(mRootTaskStack1, taskInfo1);

        assertThat(mTaskRepository.getTaskStackWithoutRootTask()).isEmpty();
        assertThat(mTaskRepository.getSurfaceControl(taskInfo1)).isNull();
        verify(mCarActivityManager).onTaskAppeared(any(), any());
        verify(mCarActivityManager).onTaskVanished(any());
    }

    private ActivityManager.RunningTaskInfo createMockTaskInfo(int taskId) {
        ActivityManager.RunningTaskInfo taskInfo = mock(ActivityManager.RunningTaskInfo.class);
        taskInfo.taskId = taskId;
        return taskInfo;
    }
}
