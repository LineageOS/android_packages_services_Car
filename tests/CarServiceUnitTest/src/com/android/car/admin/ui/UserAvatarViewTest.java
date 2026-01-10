/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.admin.ui;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.settingslib.drawable.UserIconDrawable;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Unit tests for {@link UserAvatarView}. */
@RunWith(AndroidJUnit4.class)
public final class UserAvatarViewTest {

    private static final long TIMEOUT_MS = 5_000;

    @Rule
    public ActivityScenarioRule<CarAdminUiTestActivity> mActivityScenarioRule =
            new ActivityScenarioRule<>(CarAdminUiTestActivity.class);

    private Activity mActivity;
    private UserAvatarView mUserAvatarView;
    private DevicePolicyManager mMockDevicePolicyManager;

    @Before
    public void setup() {
        mActivityScenarioRule.getScenario().onActivity(activity -> {
            mActivity = activity;
            mUserAvatarView = ((CarAdminUiTestActivity) activity).mUserAvatarView;
            mMockDevicePolicyManager = ((CarAdminUiTestActivity) activity).mMockDevicePolicyManager;
        });
    }

    @Test
    public void setAvatar() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);

        runOnUiThreadAndWait(() -> mUserAvatarView.setAvatar(bitmap));

        assertThat(mUserAvatarView.getUserIconDrawable().getUserIcon()).isEqualTo(bitmap);
        assertThat(mUserAvatarView.getUserIconDrawable().getBadge()).isNull();
    }

    @Test
    public void setAvatarWithBadge_success() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);

        runOnUiThreadAndWait(() ->
                mUserAvatarView.setAvatarWithBadge(bitmap, ActivityManager.getCurrentUser()));

        assertThat(mUserAvatarView.getUserIconDrawable().getUserIcon()).isEqualTo(bitmap);
        verify(mMockDevicePolicyManager).getProfileOwnerAsUser(ActivityManager.getCurrentUser());
        // mBadge still remains null because of unmanaged user.
        assertThat(mUserAvatarView.getUserIconDrawable().getBadge()).isNull();
    }

    @Test
    public void setDrawable_userIconDrawable_throwsError() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mUserAvatarView.setDrawable(new UserIconDrawable()));
    }

    @Test
    public void setDrawable_success() throws Exception {
        Drawable d = new ShapeDrawable(new OvalShape());

        runOnUiThreadAndWait(() -> mUserAvatarView.setDrawable(d));

        assertThat(mUserAvatarView.getUserIconDrawable().getUserDrawable()).isEqualTo(d);
    }

    @Test
    public void setDrawableWithBadgeAndUserId_userIconDrawable_throwsError() {
        assertThrows(IllegalArgumentException.class,
                () -> mUserAvatarView.setDrawableWithBadge(new UserIconDrawable(),
                        ActivityManager.getCurrentUser()));
    }

    @Test
    public void setDrawableWithBadgeAndUserId_success() throws Exception {
        Drawable d = new ShapeDrawable(new OvalShape());

        runOnUiThreadAndWait(() ->
                mUserAvatarView.setDrawableWithBadge(d, ActivityManager.getCurrentUser()));

        verify(mMockDevicePolicyManager).getProfileOwnerAsUser(ActivityManager.getCurrentUser());
        // mBadge still remains null because of unmanaged user.
        assertThat(mUserAvatarView.getUserIconDrawable().getBadge()).isNull();
    }

    @Test
    public void setDrawableWithBadge_userIconDrawable_throwsError() {
        assertThrows(IllegalArgumentException.class,
                () -> mUserAvatarView.setDrawableWithBadge(new UserIconDrawable()));
    }

    @Test
    public void setDrawableWithBadge_success() throws Exception {
        Drawable d = new ShapeDrawable(new OvalShape());

        runOnUiThreadAndWait(() -> mUserAvatarView.setDrawableWithBadge(d));

        verify(mMockDevicePolicyManager).getDeviceOwnerComponentOnAnyUser();
        // mBadge still remains null because of unmanaged user.
        assertThat(mUserAvatarView.getUserIconDrawable().getBadge()).isNull();
    }

    @Test
    public void getUserIconDrawable_returnsDrawable() {
        assertThat(mUserAvatarView.getUserIconDrawable()).isEqualTo(
                mUserAvatarView.getUserIconDrawable());
    }

    @Test
    public void setActivated_invalidatesUserIcon() throws Exception {
        runOnUiThreadAndWait(() -> mUserAvatarView.setActivated(true));

        assertThat(mUserAvatarView.getUserIconDrawable().isInvalidated()).isTrue();
    }

    private void runOnUiThreadAndWait(Runnable runnable) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        mActivity.runOnUiThread(() -> {
            runnable.run();
            latch.countDown();
        });

        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
}

