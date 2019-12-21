/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.car.apitest;

import static android.app.ActivityManager.RunningAppProcessInfo;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;
import static org.testng.Assert.assertThrows;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityView;
import android.app.Instrumentation;
import android.car.Car;
import android.car.app.CarActivityView;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.Display;
import android.view.ViewGroup;

import androidx.test.filters.FlakyTest;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.MethodSorters;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Build/Install/Run:
 *  atest AndroidCarApiTest:CarActivityViewDisplayIdTest
 */
@RunWith(JUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@MediumTest
public class CarActivityViewDisplayIdTest extends CarApiTestBase {
    private static final String CAR_LAUNCHER_PKG_NAME = "com.android.car.carlauncher";
    private static final String ACTIVITY_VIEW_TEST_PKG_NAME = "android.car.apitest";
    private static final String ACTIVITY_VIEW_TEST_PROCESS_NAME =
            ACTIVITY_VIEW_TEST_PKG_NAME + ":activity_view_test";
    private static final String ACTIVITY_VIEW_DISPLAY_NAME = "TaskVirtualDisplay";
    private static final int NONEXISTENT_DISPLAY_ID = Integer.MAX_VALUE;
    private static final int TEST_TIMEOUT_SEC = 5;
    private static final int TEST_TIMEOUT_MS = TEST_TIMEOUT_SEC * 1000;
    private static final int TEST_POLL_MS = 50;
    private static final int INVALID_PID = -1;

    private DisplayManager mDisplayManager;
    private ActivityManager mActivityManager;
    private CarUxRestrictionsManager mCarUxRestrictionsManager;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mDisplayManager = getContext().getSystemService(DisplayManager.class);
        mActivityManager = getContext().getSystemService(ActivityManager.class);
        mCarUxRestrictionsManager = (CarUxRestrictionsManager)
                getCar().getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
    }

    private int getMappedPhysicalDisplayOfVirtualDisplay(int displayId) {
        return mCarUxRestrictionsManager.getMappedPhysicalDisplayOfVirtualDisplay(displayId);
    }

    @Test
    @FlakyTest
    public void testSingleActivityView() throws Exception {
        ActivityViewTestActivity activity = startActivityViewTestActivity(DEFAULT_DISPLAY);
        activity.waitForActivityViewReady();
        int virtualDisplayId = activity.getActivityView().getVirtualDisplayId();

        startTestActivity(ActivityInActivityView.class, virtualDisplayId);

        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(virtualDisplayId))
                .isEqualTo(DEFAULT_DISPLAY);
        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(DEFAULT_DISPLAY))
                .isEqualTo(INVALID_DISPLAY);

        activity.finish();
        activity.waitForActivityViewDestroyed();

        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(virtualDisplayId))
                .isEqualTo(INVALID_DISPLAY);
        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(DEFAULT_DISPLAY))
                .isEqualTo(INVALID_DISPLAY);
    }

    @Test
    @FlakyTest
    public void testDoubleActivityView() throws Exception {
        ActivityViewTestActivity activity1 = startActivityViewTestActivity(DEFAULT_DISPLAY);
        activity1.waitForActivityViewReady();
        int virtualDisplayId1 = activity1.getActivityView().getVirtualDisplayId();

        ActivityViewTestActivity activity2 = startActivityViewTestActivity(virtualDisplayId1);
        activity2.waitForActivityViewReady();
        int virtualDisplayId2 = activity2.getActivityView().getVirtualDisplayId();

        startTestActivity(ActivityInActivityView.class, virtualDisplayId2);

        assertThat(virtualDisplayId1).isNotEqualTo(virtualDisplayId2);
        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(virtualDisplayId1))
                .isEqualTo(DEFAULT_DISPLAY);
        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(virtualDisplayId2))
                .isEqualTo(DEFAULT_DISPLAY);
        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(DEFAULT_DISPLAY))
                .isEqualTo(INVALID_DISPLAY);

        activity2.finish();
        activity1.finish();

        activity2.waitForActivityViewDestroyed();
        activity1.waitForActivityViewDestroyed();

        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(virtualDisplayId1))
                .isEqualTo(INVALID_DISPLAY);
        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(virtualDisplayId2))
                .isEqualTo(INVALID_DISPLAY);
        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(DEFAULT_DISPLAY))
                .isEqualTo(INVALID_DISPLAY);
    }

    @Test
    @FlakyTest
    public void testThrowsExceptionOnReportingNonExistingDisplay() throws Exception {
        ActivityViewTestActivity activity = startActivityViewTestActivity(DEFAULT_DISPLAY);
        activity.waitForActivityViewReady();
        int virtualDisplayId = activity.getActivityView().getVirtualDisplayId();

        // This will pass since the test owns the display.
        mCarUxRestrictionsManager.reportVirtualDisplayToPhysicalDisplay(virtualDisplayId,
                NONEXISTENT_DISPLAY_ID);

        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(virtualDisplayId))
                .isEqualTo(NONEXISTENT_DISPLAY_ID);

        activity.finish();
        activity.waitForActivityViewDestroyed();

        // Now the display was released, so expect to throw an Exception.
        assertThrows(
                java.lang.IllegalArgumentException.class,
                () -> mCarUxRestrictionsManager.reportVirtualDisplayToPhysicalDisplay(
                        virtualDisplayId, NONEXISTENT_DISPLAY_ID));
    }

    // TODO(b/143353546): Make the following tests not to rely on CarLauncher.
    @Test
    @FlakyTest
    public void testThrowsExceptionOnReportingNonOwningDisplay() throws Exception {
        int displayIdOfCarLauncher = waitForActivityViewDisplayReady(CAR_LAUNCHER_PKG_NAME);
        assumeTrue(INVALID_DISPLAY != displayIdOfCarLauncher);

        // CarLauncher owns the display, so expect to throw an Exception.
        assertThrows(
                java.lang.SecurityException.class,
                () -> mCarUxRestrictionsManager.reportVirtualDisplayToPhysicalDisplay(
                        displayIdOfCarLauncher, DEFAULT_DISPLAY + 1));

    }

    // The test name starts with 'testz' to run it at the last among the tests, since killing
    // TestActivity forcefully causes the system unstable for a while.
    @Test
    @FlakyTest
    public void testzCleanUpAfterClientIsCrashed() throws Exception {
        Intent intent = new Intent(getContext(), MultiProcessActivityViewTestActivity.class);
        getContext().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        int pidOfTestActivity = waitForTestActivityReady();
        int displayIdOfTestActivity = waitForActivityViewDisplayReady(ACTIVITY_VIEW_TEST_PKG_NAME);

        assertThat(getMappedPhysicalDisplayOfVirtualDisplay(displayIdOfTestActivity))
                .isEqualTo(DEFAULT_DISPLAY);

        Process.killProcess(pidOfTestActivity);

        assertThat(waitForMappedPhysicalDisplayOfVirtualDisplayCleared(displayIdOfTestActivity))
                .isEqualTo(INVALID_DISPLAY);
    }

    private int waitForActivityViewDisplayReady(String packageName) {
        for (int i = 0; i < TEST_TIMEOUT_MS / TEST_POLL_MS; ++i) {
            for (Display display : mDisplayManager.getDisplays()) {
                if (display.getName().contains(ACTIVITY_VIEW_DISPLAY_NAME)
                        && display.getOwnerPackageName().equals(packageName)
                        && display.getState() == Display.STATE_ON) {
                    return display.getDisplayId();
                }
            }
            SystemClock.sleep(TEST_POLL_MS);
        }
        return INVALID_DISPLAY;
    }

    private int waitForMappedPhysicalDisplayOfVirtualDisplayCleared(int displayId) {
        // Initialized with a random number which is not DEFAULT_DISPLAY nor INVALID_DISPLAY.
        int physicalDisplayId = 999;
        for (int i = 0; i < TEST_TIMEOUT_MS / TEST_POLL_MS; ++i) {
            physicalDisplayId = getMappedPhysicalDisplayOfVirtualDisplay(displayId);
            if (physicalDisplayId == INVALID_DISPLAY) {
                return physicalDisplayId;
            }
            SystemClock.sleep(TEST_POLL_MS);
        }
        return physicalDisplayId;
    }

    private int waitForTestActivityReady() {
        for (int i = 0; i < TEST_TIMEOUT_MS / TEST_POLL_MS; ++i) {
            List<RunningAppProcessInfo> appProcesses = mActivityManager.getRunningAppProcesses();
            for (RunningAppProcessInfo info : appProcesses) {
                if (info.processName.equals(ACTIVITY_VIEW_TEST_PROCESS_NAME)
                        && info.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return info.pid;
                }
            }
            SystemClock.sleep(TEST_POLL_MS);
        }
        return INVALID_PID;
    }

    private static class TestActivity extends Activity {
        private final CountDownLatch mResumed = new CountDownLatch(1);

        @Override
        protected void onPostResume() {
            super.onPostResume();
            mResumed.countDown();
        }

        void waitForResumeStateChange() throws Exception {
            waitForLatch(mResumed);
        }
    }

    private static void waitForLatch(CountDownLatch latch) throws Exception {
        boolean result = latch.await(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!result) {
            throw new TimeoutException("Timed out waiting for task stack change notification");
        }
    }

    /**
     * Starts the provided activity and returns the started instance.
     */
    private TestActivity startTestActivity(Class<?> activityClass, int displayId) throws Exception {
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor(
                activityClass.getName(), null, false);
        getInstrumentation().addMonitor(monitor);

        Context context = getContext();
        Intent intent = new Intent(context, activityClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions options = ActivityOptions.makeBasic();
        if (displayId != DEFAULT_DISPLAY) {
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            options.setLaunchDisplayId(displayId);
        }
        context.startActivity(intent, options.toBundle());

        TestActivity activity = (TestActivity) monitor.waitForActivityWithTimeout(TEST_TIMEOUT_MS);
        if (activity == null) {
            throw new TimeoutException("Timed out waiting for Activity");
        }
        activity.waitForResumeStateChange();
        return activity;
    }

    public static class ActivityViewTestActivity extends TestActivity {
        private static final class ActivityViewStateCallback extends ActivityView.StateCallback {
            private final CountDownLatch mActivityViewReadyLatch = new CountDownLatch(1);
            private final CountDownLatch mActivityViewDestroyedLatch = new CountDownLatch(1);

            @Override
            public void onActivityViewReady(ActivityView view) {
                mActivityViewReadyLatch.countDown();
            }

            @Override
            public void onActivityViewDestroyed(ActivityView view) {
                mActivityViewDestroyedLatch.countDown();
            }
        }

        private CarActivityView mActivityView;
        private final ActivityViewStateCallback mCallback = new ActivityViewStateCallback();

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            mActivityView = new CarActivityView(this, /*attrs=*/null , /*defStyle=*/0 ,
                    /*singleTaskInstance=*/true);
            mActivityView.setCallback(mCallback);
            setContentView(mActivityView);

            ViewGroup.LayoutParams layoutParams = mActivityView.getLayoutParams();
            layoutParams.width = MATCH_PARENT;
            layoutParams.height = MATCH_PARENT;
            mActivityView.requestLayout();
        }

        @Override
        protected void onStop() {
            super.onStop();
            // Moved the release of the view from onDestroy to onStop since onDestroy was called
            // in non-deterministic timing.
            mActivityView.release();
        }

        ActivityView getActivityView() {
            return mActivityView;
        }

        void waitForActivityViewReady() throws Exception {
            waitForLatch(mCallback.mActivityViewReadyLatch);
        }

        void waitForActivityViewDestroyed() throws Exception {
            waitForLatch(mCallback.mActivityViewDestroyedLatch);
        }
    }

    public static final class MultiProcessActivityViewTestActivity extends
            ActivityViewTestActivity {
    }

    private ActivityViewTestActivity startActivityViewTestActivity(int displayId) throws Exception {
        return (ActivityViewTestActivity) startTestActivity(ActivityViewTestActivity.class,
                displayId);
    }

    // Activity that has {@link android.R.attr#resizeableActivity} attribute set to {@code true}
    public static class ActivityInActivityView extends TestActivity {}

    private ActivityInActivityView startActivityInActivityView(int displayId) throws Exception {
        return (ActivityInActivityView) startTestActivity(ActivityInActivityView.class, displayId);
    }
}
