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
package com.android.car.admin;

import static android.app.Notification.EXTRA_TEXT;
import static android.app.Notification.EXTRA_TITLE;
import static android.app.Notification.FLAG_ONGOING_EVENT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;

import static com.android.car.admin.NotificationHelper.CHANNEL_ID_DEFAULT;
import static com.android.car.admin.NotificationHelper.NEW_USER_DISCLAIMER_NOTIFICATION_ID;
import static com.android.car.admin.NotificationHelper.newNotificationBuilder;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.expectThrows;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiAutomation;
import android.car.test.mocks.JavaMockitoHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.R;
import com.android.car.admin.ui.ManagedDeviceTextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.concurrent.CountDownLatch;

@RunWith(MockitoJUnitRunner.class)
public final class NotificationHelperTest {
    private static final long TIMEOUT_MS = 1_000;
    private final Context mRealContext = InstrumentationRegistry.getInstrumentation()
            .getContext();
    private final UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();
    private Context mSpiedContext;

    @Mock
    private NotificationManager mNotificationManager;

    @Before
    public void setup() {
        mSpiedContext = spy(mRealContext);
        when(mSpiedContext.getSystemService(NotificationManager.class))
                .thenReturn(mNotificationManager);
    }

    @Test
    public void testNewNotificationBuilder_nullContext() {
        NullPointerException exception = expectThrows(NullPointerException.class,
                () -> newNotificationBuilder(/* context= */ null, IMPORTANCE_HIGH));

        assertWithMessage("exception message").that(exception.getMessage()).contains("context");
    }

    @Test
    public void testShowUserDisclaimerNotification() {
        int userId = 11;
        NotificationHelper.showUserDisclaimerNotification(userId, mSpiedContext);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(mNotificationManager).notifyAsUser(eq(NotificationHelper.TAG),
                eq(NEW_USER_DISCLAIMER_NOTIFICATION_ID), captor.capture(),
                eq(UserHandle.of(userId)));

        Notification notification = captor.getValue();
        assertWithMessage("notification").that(notification).isNotNull();
        assertNotificationContents(notification);
    }

    @Test
    public void testCancelUserDisclaimerNotification() throws Exception {
        int userId = 11;
        PendingIntent pendingIntent = NotificationHelper.getPendingUserDisclaimerIntent(
                mSpiedContext, /* extraFlags = */ 0, userId);
        CountDownLatch cancelLatch = new CountDownLatch(1);
        pendingIntent.registerCancelListener(pi -> cancelLatch.countDown());

        NotificationHelper.cancelUserDisclaimerNotification(userId, mSpiedContext);

        verify(mNotificationManager).cancelAsUser(NotificationHelper.TAG,
                NEW_USER_DISCLAIMER_NOTIFICATION_ID,
                UserHandle.of(userId)
        );

        // Assert pending intent was canceled (latch is counted down by the CancelListener)
        JavaMockitoHelper.await(cancelLatch, TIMEOUT_MS);
    }

    private void assertNotificationContents(Notification notification) {
        assertWithMessage("notification icon").that(notification.getSmallIcon()).isNotNull();
        assertWithMessage("notification channel").that(notification.getChannelId())
                .isEqualTo(CHANNEL_ID_DEFAULT);
        assertWithMessage("notification flags has FLAG_ONGOING_EVENT")
                .that(notification.flags & FLAG_ONGOING_EVENT).isEqualTo(FLAG_ONGOING_EVENT);

        assertWithMessage("notification content pending intent")
                .that(notification.contentIntent)
                .isNotNull();
        assertWithMessage("notification content pending intent is immutable")
                .that(notification.contentIntent.isImmutable()).isTrue();
        // Need android.permission.GET_INTENT_SENDER_INTENT to get the Intent
        Intent intent;
        mUiAutomation.adoptShellPermissionIdentity();
        try {
            intent = notification.contentIntent.getIntent();
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
        assertWithMessage("content intent").that(intent).isNotNull();
        assertWithMessage("content intent component").that(intent.getComponent())
                .isEqualTo(ComponentName.unflattenFromString(mRealContext.getString(
                        com.android.car.R.string.config_newUserDisclaimerActivity
                )));

        assertWithMessage("notification extras").that(notification.extras).isNotNull();
        assertWithMessage("value of extra %s", EXTRA_TITLE)
                .that(notification.extras.getString(EXTRA_TITLE))
                .isEqualTo(mRealContext.getString(R.string.new_user_managed_notification_title));
        assertWithMessage("value of extra %s", EXTRA_TEXT)
                .that(notification.extras.getString(EXTRA_TEXT))
                .isEqualTo(ManagedDeviceTextView.getManagedDeviceText(mRealContext).toString());
    }
}
