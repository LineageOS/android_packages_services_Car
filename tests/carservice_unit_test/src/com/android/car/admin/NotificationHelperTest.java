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

import static com.android.car.admin.NotificationHelper.CAR_WATCHDOG_ACTION_LAUNCH_APP_SETTINGS;
import static com.android.car.admin.NotificationHelper.CAR_WATCHDOG_ACTION_RESOURCE_OVERUSE_DISABLE_APP;
import static com.android.car.admin.NotificationHelper.CHANNEL_ID_DEFAULT;
import static com.android.car.admin.NotificationHelper.CHANNEL_ID_HIGH;
import static com.android.car.admin.NotificationHelper.INTENT_EXTRA_NOTIFICATION_ID;
import static com.android.car.admin.NotificationHelper.NEW_USER_DISCLAIMER_NOTIFICATION_ID;
import static com.android.car.admin.NotificationHelper.newNotificationBuilder;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.SparseArray;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.R;
import com.android.car.admin.ui.ManagedDeviceTextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

@RunWith(MockitoJUnitRunner.class)
public final class NotificationHelperTest {
    private static final long TIMEOUT_MS = 1_000;
    private static final Pattern PACKAGE_NAME_PATTERN = Pattern.compile("package:(.+)");

    private final Context mRealContext = InstrumentationRegistry.getInstrumentation()
            .getContext();
    private final UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();
    private final Map<String, ApplicationInfo> mApplicationInfosByUserPackage =
            new ArrayMap<>();

    private Context mSpiedContext;

    @Mock private PackageManager mMockPackageManager;
    @Mock private NotificationManager mMockNotificationManager;

    @Captor private ArgumentCaptor<Notification> mNotificationCaptor;
    @Captor private ArgumentCaptor<Integer> mIntCaptor;

    @Before
    public void setup() throws Exception {
        mSpiedContext = spy(mRealContext);
        when(mSpiedContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mSpiedContext.getSystemService(NotificationManager.class))
                .thenReturn(mMockNotificationManager);
        mockPackageManager();
    }

    @Test
    public void testNewNotificationBuilder_nullContext() {
        NullPointerException exception = expectThrows(NullPointerException.class,
                () -> newNotificationBuilder(/* context= */ null, IMPORTANCE_HIGH));

        assertWithMessage("exception message").that(exception.getMessage()).contains("context");
    }

    @Test
    public void testCancelNotificationAsUser() {
        UserHandle userHandle = UserHandle.of(100);

        NotificationHelper.cancelNotificationAsUser(mSpiedContext, userHandle,
                /* notificationId= */ 150);

        verify(mMockNotificationManager).cancelAsUser(NotificationHelper.TAG, /* id= */ 150,
                userHandle);
    }

    @Test
    public void testShowUserDisclaimerNotification() {
        int userId = 11;
        NotificationHelper.showUserDisclaimerNotification(userId, mSpiedContext);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(mMockNotificationManager).notifyAsUser(eq(NotificationHelper.TAG),
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

        verify(mMockNotificationManager).cancelAsUser(NotificationHelper.TAG,
                NEW_USER_DISCLAIMER_NOTIFICATION_ID,
                UserHandle.of(userId)
        );

        // Assert pending intent was canceled (latch is counted down by the CancelListener)
        JavaMockitoHelper.await(cancelLatch, TIMEOUT_MS);
    }

    @Test
    public void testShowResourceOveruseNotificationsAsUser() throws Exception {
        UserHandle userHandle = UserHandle.of(100);

        SparseArray<String> expectedHeadsUpPackagesById = new SparseArray<>();
        expectedHeadsUpPackagesById.put(169, "system_package.A");
        expectedHeadsUpPackagesById.put(150, "vendor_package.A");
        expectedHeadsUpPackagesById.put(151, "third_party_package.A");
        SparseArray<String> expectedNotificationCenterPackagesById = new SparseArray<>();
        expectedNotificationCenterPackagesById.put(152, "system_package.B");
        expectedNotificationCenterPackagesById.put(153, "vendor_package.B");
        expectedNotificationCenterPackagesById.put(154, "third_party_package.B");

        List<ApplicationInfo> applicationInfos = List.of(
                constructApplicationInfo("system_package.A", "System A",
                        UserHandle.getUid(100, 1000), ApplicationInfo.FLAG_SYSTEM),
                constructApplicationInfo("vendor_package.A", "Vendor A",
                        UserHandle.getUid(100, 1001), ApplicationInfo.FLAG_SYSTEM),
                constructApplicationInfo("third_party_package.A", "Third Party A",
                        UserHandle.getUid(100, 1002), /* infoFlags= */ 0),
                constructApplicationInfo("system_package.B", "System B",
                        UserHandle.getUid(100, 2000), ApplicationInfo.FLAG_SYSTEM),
                constructApplicationInfo("vendor_package.B", "Vendor B",
                        UserHandle.getUid(100, 2001), ApplicationInfo.FLAG_SYSTEM),
                constructApplicationInfo("third_party_package.B", "Third Party B",
                        UserHandle.getUid(100, 2002), /* infoFlags= */ 0));

        injectApplicationInfos(applicationInfos);

        NotificationHelper.showResourceOveruseNotificationsAsUser(mSpiedContext, userHandle,
                expectedHeadsUpPackagesById, expectedNotificationCenterPackagesById);

        verify(mMockNotificationManager, times(6)).notifyAsUser(eq(NotificationHelper.TAG),
                mIntCaptor.capture(), mNotificationCaptor.capture(), eq(userHandle));

        // Ids are reset because because BASE_ID + idStartOffset + size is greater the max
        // notification id.
        List<Integer> expectedNotificationIds = List.of(169, 150, 151, 152, 153, 154);

        assertWithMessage("Notification ids")
                .that(mIntCaptor.getAllValues()).containsExactlyElementsIn(expectedNotificationIds);

        List<Notification> actualNotifications = mNotificationCaptor.getAllValues();
        assertWithMessage("Notifications size").that(actualNotifications)
                .hasSize(expectedHeadsUpPackagesById.size()
                        + expectedNotificationCenterPackagesById.size());

        for (Notification actualNotification : actualNotifications) {
            Intent intent = getActionIntent(actualNotification.actions[0]);
            int notificationId = intent.getIntExtra(INTENT_EXTRA_NOTIFICATION_ID, -1);
            String packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
            ApplicationInfo applicationInfo = findApplicationInfo(packageName, applicationInfos);
            boolean isHeadsUp = expectedHeadsUpPackagesById.get(notificationId) != null;
            if (isHeadsUp) {
                assertWithMessage("Notification package name").that(packageName)
                        .isEqualTo(expectedHeadsUpPackagesById.get(notificationId));
            } else {
                assertWithMessage("Notification package name").that(packageName)
                        .isEqualTo(expectedNotificationCenterPackagesById.get(notificationId));
            }
            assertResourceOveruseNotification(notificationId, actualNotification, applicationInfo,
                    isHeadsUp);
        }
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

    private void assertResourceOveruseNotification(int notificationId, Notification notification,
            ApplicationInfo applicationInfo, boolean isHeadsUp) {
        String notificationName = applicationInfo.packageName + " notification";
        String channelId = isHeadsUp ? CHANNEL_ID_HIGH : CHANNEL_ID_DEFAULT;

        assertWithMessage("%s icon", notificationName).that(notification.getSmallIcon())
                .isNotNull();
        assertWithMessage("%s channel", notificationName).that(notification.getChannelId())
                .isEqualTo(channelId);

        assertResourceOveruseNotificationAction(notification.actions[0], applicationInfo,
                notificationId, /* isPositiveAction= */ true);
        assertResourceOveruseNotificationAction(notification.actions[1], applicationInfo,
                notificationId, /* isPositiveAction= */ false);

        CharSequence titleTemplate =
                mRealContext.getText(R.string.resource_overuse_notification_title);
        String prioritizeAppDescription =
                mRealContext.getString(R.string.resource_overuse_notification_text_prioritize_app);
        String description =
                mRealContext.getString(R.string.resource_overuse_notification_text_uninstall_app)
                        + " " + prioritizeAppDescription;
        if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            description = mRealContext.getString(
                    R.string.resource_overuse_notification_text_disable_app) + " "
                    + prioritizeAppDescription;
        }

        assertWithMessage("%s extras", notificationName).that(notification.extras).isNotNull();
        assertWithMessage("%s extra %s", notificationName, EXTRA_TITLE)
                .that(notification.extras.getCharSequence(EXTRA_TITLE).toString())
                .isEqualTo(TextUtils.expandTemplate(titleTemplate, applicationInfo.name)
                        .toString());
        assertWithMessage("%s extra %s", notificationName, EXTRA_TEXT)
                .that(notification.extras.getString(EXTRA_TEXT))
                .isEqualTo(description);
    }

    private void assertResourceOveruseNotificationAction(Notification.Action action,
            ApplicationInfo applicationInfo, int notificationId, boolean isPositiveAction) {
        String actionName = applicationInfo.packageName + (isPositiveAction ? " positive action"
                : " negative action");
        boolean isSystemApp = (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

        int titleResource = isPositiveAction
                ? R.string.resource_overuse_notification_button_prioritize_app
                : (isSystemApp ? R.string.resource_overuse_notification_button_disable_app
                    : R.string.resource_overuse_notification_button_uninstall_app);

        assertWithMessage("%s title", actionName).that(action.title.toString())
                .isEqualTo(mRealContext.getString(titleResource));
        assertWithMessage("%s pending intent", actionName).that(action.actionIntent).isNotNull();
        assertWithMessage("%s pending intent is immutable", actionName)
                .that(action.actionIntent.isImmutable()).isTrue();

        int userId = UserHandle.getUserId(applicationInfo.uid);
        Intent intent = getActionIntent(action);
        String expectedAction =
                isPositiveAction || !isSystemApp ? CAR_WATCHDOG_ACTION_LAUNCH_APP_SETTINGS
                        : CAR_WATCHDOG_ACTION_RESOURCE_OVERUSE_DISABLE_APP;

        assertWithMessage("%s intent", actionName).that(intent).isNotNull();
        assertWithMessage("%s intent action", actionName).that(intent.getAction())
                .isEqualTo(expectedAction);
        assertWithMessage("%s intent package", actionName).that(intent.getPackage())
                .isEqualTo(mRealContext.getPackageName());
        assertWithMessage("%s intent extra id", actionName)
                .that(intent.getIntExtra(INTENT_EXTRA_NOTIFICATION_ID, -1))
                .isEqualTo(notificationId);
        assertWithMessage("%s intent extra package name", actionName)
                .that(intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME))
                .isEqualTo(applicationInfo.packageName);
        assertWithMessage("%s intent extra user", actionName)
                .that((UserHandle) intent.getParcelableExtra(Intent.EXTRA_USER))
                .isEqualTo(UserHandle.of(userId));
        assertWithMessage("%s intent flags", actionName).that(intent.getFlags())
                .isEqualTo(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    }

    private void injectApplicationInfos(
            List<ApplicationInfo> applicationInfos) {
        for (ApplicationInfo applicationInfo : applicationInfos) {
            int userId = UserHandle.getUserId(applicationInfo.uid);
            String userPackageId = userId + ":" + applicationInfo.packageName;
            assertWithMessage("Duplicate application infos provided for user package id: %s",
                    userPackageId).that(mApplicationInfosByUserPackage.containsKey(userPackageId))
                    .isFalse();
            mApplicationInfosByUserPackage.put(userPackageId, applicationInfo);
        }
    }

    private void mockPackageManager() throws Exception {
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), any()))
                .thenAnswer(args -> {
                    int userId = ((UserHandle) args.getArgument(2)).getIdentifier();
                    String userPackageId = userId + ":" + args.getArgument(0);
                    ApplicationInfo applicationInfo =
                            mApplicationInfosByUserPackage.get(userPackageId);
                    if (applicationInfo == null) {
                        throw new PackageManager.NameNotFoundException(
                                "User package id '" + userPackageId + "' not found");
                    }
                    return applicationInfo;
                });
    }

    private Intent getActionIntent(Notification.Action action) {
        Intent intent;
        mUiAutomation.adoptShellPermissionIdentity();
        try {
            intent = action.actionIntent.getIntent();
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
        return intent;
    }

    private static ApplicationInfo findApplicationInfo(String packageName,
            List<ApplicationInfo> applicationInfos) throws Exception {
        for (int i = 0; i < applicationInfos.size(); i++) {
            ApplicationInfo applicationInfo = applicationInfos.get(i);
            if (packageName.equals(applicationInfo.packageName)) {
                return applicationInfo;
            }
        }
        assertWithMessage("Failed to find application info for package: " + packageName).fail();
        return null;
    }

    private static ApplicationInfo constructApplicationInfo(String pkgName, String appName,
            int pkgUid, int infoFlags) {
        return new ApplicationInfo() {{
            name = appName;
            packageName = pkgName;
            uid = pkgUid;
            flags = infoFlags;
        }};
    }
}
