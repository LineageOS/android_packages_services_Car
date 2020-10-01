/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.car.userlib;

import static android.car.test.util.UserTestingHelper.newUser;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;


import static org.mockito.Mockito.never;

import android.app.ActivityManager;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.UserHandle;
import android.os.UserManager;
import android.sysprop.CarProperties;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

/**
 * This class contains unit tests for the {@link CarUserManagerHelper}.
 * It tests that {@link CarUserManagerHelper} does the right thing for user management flows.
 *
 * The following mocks are used:
 * 1. {@link Context} provides system services and resources.
 * 2. {@link UserManager} provides placeholder users and user info.
 * 3. {@link ActivityManager} to verify user switch is invoked.
 */
@SmallTest
public class CarUserManagerHelperTest extends AbstractExtendedMockitoTestCase {
    @Mock private Context mContext;
    @Mock private UserManager mUserManager;
    @Mock private ActivityManager mActivityManager;
    @Mock private ContentResolver mContentResolver;

    // Not worth to mock because it would need to mock a Drawable used by UserIcons.
    private final Resources mResources = InstrumentationRegistry.getTargetContext().getResources();

    private static final String TEST_USER_NAME = "testUser";
    private static final int NO_FLAGS = 0;

    private CarUserManagerHelper mCarUserManagerHelper;
    private final int mForegroundUserId = 42;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session
            .spyStatic(ActivityManager.class)
            .spyStatic(CarProperties.class)
            .spyStatic(UserManager.class);
    }

    @Before
    public void setUpMocksAndVariables() {
        doReturn(mUserManager).when(mContext).getSystemService(Context.USER_SERVICE);
        doReturn(mActivityManager).when(mContext).getSystemService(Context.ACTIVITY_SERVICE);
        doReturn(mResources).when(mContext).getResources();
        doReturn(mContentResolver).when(mContext).getContentResolver();
        doReturn(mContext).when(mContext).getApplicationContext();
        mCarUserManagerHelper = new CarUserManagerHelper(mContext);

        mockGetCurrentUser(mForegroundUserId);
    }

    @Test
    public void testGrantAdminPermissions() {
        int userId = 30;
        UserInfo testInfo = newUser(userId);

        // Test that non-admins cannot grant admin permissions.
        doReturn(false).when(mUserManager).isAdminUser(); // Current user non-admin.
        mCarUserManagerHelper.grantAdminPermissions(testInfo);
        verify(mUserManager, never()).setUserAdmin(userId);

        // Admins can grant admin permissions.
        doReturn(true).when(mUserManager).isAdminUser();
        mCarUserManagerHelper.grantAdminPermissions(testInfo);
        verify(mUserManager).setUserAdmin(userId);
    }

    @Test
    public void testGrantingAdminPermissionsRemovesNonAdminRestrictions() {
        int testUserId = 30;
        boolean restrictionEnabled = false;
        UserInfo testInfo = newUser(testUserId);

        // Only admins can grant permissions.
        doReturn(true).when(mUserManager).isAdminUser();

        mCarUserManagerHelper.grantAdminPermissions(testInfo);

        verify(mUserManager).setUserRestriction(
                UserManager.DISALLOW_FACTORY_RESET, restrictionEnabled, UserHandle.of(testUserId));
    }
}
