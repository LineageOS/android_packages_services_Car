/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.car.distantdisplay.common;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.when;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarDistantDisplayMediator;
import com.android.systemui.car.users.CarSystemUIUserUtil;
import com.android.systemui.settings.UserTracker;

import com.google.android.car.distantdisplay.service.DistantDisplayService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DistantDisplayTaskManagerTest extends SysuiTestCase {

    private static final int DEFAULT_DISPLAY_ID = 0;
    private final UserHandle mUserHandle = UserHandle.of(1000);

    private MockitoSession mSession;

    @Mock
    private UserTracker mUserTracker;

    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;

    @Mock
    private DistantDisplayService mDistantDisplayService;

    @Mock
    private CarDistantDisplayMediator mCarDistantDisplayMediator;


    private DistantDisplayTaskManager mDistantDisplayTaskManager;

    @Before
    public void setUp() {
        mSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .spyStatic(CarSystemUIUserUtil.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        when(mUserTracker.getUserHandle()).thenReturn(mUserHandle);
        mDistantDisplayTaskManager = new DistantDisplayTaskManager(getContext(),
                mBroadcastDispatcher, mCarDistantDisplayMediator, mUserTracker);
        mDistantDisplayTaskManager.setDistantDisplayService(mDistantDisplayService);
        // TODO: read the display id using DisplayManager
        mDistantDisplayTaskManager.initialize(3);

    }

    private void startActivity(Context context, Intent intent) {
        ActivityOptions activityOptions = ActivityOptions.makeBasic();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent, activityOptions.toBundle());
    }

    @After
    public void tearDown() {
        if (mSession != null) {
            mSession.finishMocking();
            mSession = null;
        }
    }

    @Test
    public void launchTask_moveToDistantDisplay() throws InterruptedException {
        testLaunchOnDefaultDisplay();
        testMoveTaskOnDistantDisplay();
    }

    private void testLaunchOnDefaultDisplay() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        DistantDisplayTaskManager.Callback callback = createCallback(latch, DEFAULT_DISPLAY_ID);

        mDistantDisplayTaskManager.addCallback(callback);

        Intent intent = new Intent(mContext, TestActivity.class);
        startActivity(mContext, intent);

        verifyCallbackCompletion(latch, "Callback timed out on default display");

        mDistantDisplayTaskManager.removeCallback(callback);
        waitForIdleSync();
    }

    private void testMoveTaskOnDistantDisplay() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        DistantDisplayTaskManager.Callback callback = createCallback(latch,
                mDistantDisplayTaskManager.getDistantDisplayId());

        mDistantDisplayTaskManager.addCallback(callback);
        mDistantDisplayTaskManager.moveTaskToDistantDisplay();

        verifyCallbackCompletion(latch, "Callback timed out on distant display");
    }

    private DistantDisplayTaskManager.Callback createCallback(CountDownLatch latch,
            int expectedDisplayId) {
        return (displayId, componentName) -> {
            if (componentName == null) {
                return;
            }
            assertTrue(
                    componentName.getClassName().contains(TestActivity.class.getSimpleName()));
            assertEquals(expectedDisplayId, displayId);
            latch.countDown();
        };
    }

    private void verifyCallbackCompletion(CountDownLatch latch, String timeoutMessage)
            throws InterruptedException {
        assertTrue(timeoutMessage, latch.await(5, TimeUnit.SECONDS));
    }
}
