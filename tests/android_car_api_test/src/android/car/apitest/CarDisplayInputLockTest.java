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

package android.car.apitest;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.car.settings.CarSettings;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.multiuser.annotations.RequireRunNotOnVisibleBackgroundNonProfileUser;
import com.android.bedstead.multiuser.annotations.RequireRunOnVisibleBackgroundNonProfileUser;
import com.android.compatibility.common.util.PollingCheck;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class CarDisplayInputLockTest {
    private static final long INPUT_LOCK_UPDATE_WAIT_TIME_MS = 10_000L;
    private static final long ACTIVITY_WAIT_TIME_OUT_MS = 10_000L;
    private static final String EMPTY_SETTING_VALUE = "";

    @Rule
    @ClassRule
    public static final DeviceState sDeviceState = new DeviceState();

    private Context mContext;
    private DisplayManager mDisplayManager;
    private Instrumentation mInstrumentation;
    private ContentResolver mContentResolver;
    private String mInitialSettingValue;
    private TestActivity mActivity;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UserManager userManager = mContext.getSystemService(UserManager.class);
        assumeTrue("This test is enabled only in multi-user/multi-display devices",
                userManager.isVisibleBackgroundUsersSupported());

        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContentResolver = mContext.getContentResolver();
        mInitialSettingValue = getDisplayInputLockSetting(mContentResolver);
        unlockInputForAllDisplays();
    }

    @After
    public void tearDown() {
        if (mContentResolver != null) {
            writeDisplayInputLockSetting(mContentResolver, mInitialSettingValue);
        }
    }

    @Test
    @RequireRunNotOnVisibleBackgroundNonProfileUser
    public void testDisplayInputLockForDriverDisplay() {
        try (ActivityScenario<TestActivity> scenario =
                ActivityScenario.launch(TestActivity.class)) {
            waitForActivityStart(scenario);
            int displayId = mActivity.getDisplayId();
            Display display = mDisplayManager.getDisplay(displayId);
            assertThat(display).isNotNull();
            String displayUniqueId = display.getUniqueId();

            // Try to lock the driver display
            lockInputForDisplays(displayUniqueId);

            // Verify that DisplayInputLock ignores the trial to lock the driver display.
            assertDisplayInputSinkCreated(displayId, /* created= */ false);
            doTestDisplayInputLock(displayId, /* touchReceived= */ true);
        }
    }

    @Test
    @RequireRunOnVisibleBackgroundNonProfileUser
    public void testDisplayInputLockForPassengerDisplay() {
        try (ActivityScenario<TestActivity> scenario =
                ActivityScenario.launch(TestActivity.class)) {
            waitForActivityStart(scenario);
            int displayId = mActivity.getDisplayId();
            Display display = mDisplayManager.getDisplay(displayId);
            assertThat(display).isNotNull();
            String displayUniqueId = display.getUniqueId();

            doTestDisplayInputLock(displayId, /* touchReceived= */ true);

            lockInputForDisplays(displayUniqueId);
            assertDisplayInputSinkCreated(displayId, /* created= */ true);
            doTestDisplayInputLock(displayId, /* touchReceived= */ false);

            unlockInputForAllDisplays();
            assertAllDisplayInputSinksRemoved();
            doTestDisplayInputLock(displayId, /* touchReceived= */ true);
        }
    }

    private void waitForActivityStart(ActivityScenario<TestActivity> scenario) {
        ConditionVariable activityReferenceObtained = new ConditionVariable();
        scenario.onActivity(activity -> {
            mActivity = activity;
            activityReferenceObtained.open();
        });
        activityReferenceObtained.block(ACTIVITY_WAIT_TIME_OUT_MS);
        assertWithMessage("Failed to acquire activity reference.").that(mActivity).isNotNull();
    }

    private void doTestDisplayInputLock(int displayId, boolean touchReceived) {
        tapOnDisplay(displayId);
        PollingCheck.waitFor(() -> mActivity.mIsTouchesReceived == touchReceived);
        mActivity.resetTouchesReceived();

        mouseClickOnDisplay(displayId);
        PollingCheck.waitFor(() -> mActivity.mIsTouchesReceived == touchReceived);
        mActivity.resetTouchesReceived();
    }

    private void assertDisplayInputSinkCreated(int displayId, boolean created) {
        PollingCheck.waitFor(INPUT_LOCK_UPDATE_WAIT_TIME_MS, () -> {
            String cmdOut = runShellCommand("dumpsys input");
            return cmdOut.contains("DisplayInputSink-" + displayId) == created;
        });
    }

    private void assertAllDisplayInputSinksRemoved() {
        PollingCheck.waitFor(INPUT_LOCK_UPDATE_WAIT_TIME_MS, () -> {
            String cmdOut = runShellCommand("dumpsys input");
            return !cmdOut.contains("DisplayInputSink");
        });
    }

    @Nullable
    private String getDisplayInputLockSetting(@NonNull ContentResolver resolver) {
        return Settings.Global.getString(resolver,
                CarSettings.Global.DISPLAY_INPUT_LOCK);
    }

    private void lockInputForDisplays(String displayUniqueIds) {
        writeDisplayInputLockSetting(mContentResolver, displayUniqueIds);
    }

    private void unlockInputForAllDisplays() {
        writeDisplayInputLockSetting(mContentResolver, EMPTY_SETTING_VALUE);
    }

    private void writeDisplayInputLockSetting(@NonNull ContentResolver resolver,
            @NonNull String value) {
        Settings.Global.putString(resolver, CarSettings.Global.DISPLAY_INPUT_LOCK, value);
    }

    private void tapOnDisplay(int displayId) {
        injectMotionEvent(obtainMotionEvent(InputDevice.SOURCE_TOUCHSCREEN,
                mActivity.mView, MotionEvent.ACTION_DOWN, displayId));
        injectMotionEvent(obtainMotionEvent(InputDevice.SOURCE_TOUCHSCREEN,
                mActivity.mView, MotionEvent.ACTION_UP, displayId));
    }

    private void mouseClickOnDisplay(int displayId) {
        injectMotionEvent(obtainMouseEvent(
                mActivity.mView, MotionEvent.ACTION_DOWN, displayId));
        injectMotionEvent(obtainMouseEvent(
                mActivity.mView, MotionEvent.ACTION_BUTTON_PRESS, displayId));
        injectMotionEvent(obtainMouseEvent(
                mActivity.mView, MotionEvent.ACTION_BUTTON_RELEASE, displayId));
        injectMotionEvent(obtainMouseEvent(
                mActivity.mView, MotionEvent.ACTION_UP, displayId));
    }

    private void injectMotionEvent(MotionEvent event) {
        mInstrumentation.getUiAutomation().injectInputEvent(event,
                /* sync= */ true, /* waitAnimations= */ true);
    }

    private static MotionEvent obtainMouseEvent(View target, int action, int displayId) {
        return obtainMotionEvent(InputDevice.SOURCE_MOUSE, target, action, displayId);
    }

    private static MotionEvent obtainMotionEvent(int source, View target, int action,
            int displayId) {
        long eventTime = SystemClock.uptimeMillis();
        int[] xy = new int[2];
        target.getLocationOnScreen(xy);
        MotionEvent event = MotionEvent.obtain(eventTime, eventTime, action,
                xy[0] + target.getWidth() / 2, xy[1] + target.getHeight() / 2,
                /* metaState= */ 0);
        event.setSource(source);
        event.setDisplayId(displayId);
        return event;
    }

    public static class TestActivity extends Activity {
        public boolean mIsTouchesReceived;
        public TextView mView;

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mView = new TextView(this);
            mView.setText("Display Input Lock");
            mView.setBackgroundColor(Color.GREEN);
            mView.setOnClickListener(this::onClick);

            setContentView(mView);
        }

        /** Reset the touches received field */
        public void resetTouchesReceived() {
            mIsTouchesReceived = false;
        }

        private void onClick(View view) {
            mIsTouchesReceived = true;
        }
    }
}
