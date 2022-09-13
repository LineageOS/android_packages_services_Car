/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.user;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.car.builtin.os.UserManagerHelper;
import android.car.user.CarUserManager;

import com.android.car.OccupantZoneHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests user assignment / starting for non-current users.
 */
public final class UserAssignmentTest extends BaseCarUserServiceTestCase {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private OccupantZoneHelper mZoneHelper = new OccupantZoneHelper();

    @Test
    public void testUserNoGuestAssignmentForOnePrePopulatedUser() throws Exception {
        setUpOneDriverTwoPassengers();
        mNumberOfAutoPopulatedUsers = 1; // assign one user

        int currentUserId = ActivityManager.getCurrentUser();
        mCarUserService.init();
        mCarUserService.setCarServiceHelper(mICarServiceHelper);
        mCarUserService.onUserLifecycleEvent(CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED,
                currentUserId, currentUserId);

        // It is dispatched twice, so wait two times to guarantee completion.
        waitForBgHandlerToComplete(DEFAULT_TIMEOUT_MS);
        waitForBgHandlerToComplete(DEFAULT_TIMEOUT_MS);

        int userId = mAnotherAdminUserId;
        int zoneId = mZoneHelper.zoneRearLeft.zoneId;
        String expectedSetting = zoneId + ":" + userId;
        assertWithMessage("Stored setting should match").that(
                mPerUserVisibleUserAllocationSetting).isEqualTo(expectedSetting);
        // OccupantZoneHelper, display id is same with zone id
        int displayId = mZoneHelper.zoneRearLeft.zoneId;
        verify(mICarServiceHelper).startUserInBackgroundOnSecondaryDisplay(userId, displayId);
        verify(mCarOccupantZoneService).assignVisibleUserToOccupantZone(zoneId, mAnotherAdminUser,
                0);
    }

    @Before
    public void setupCommon() throws Exception {
        when(mICarServiceHelper.startUserInBackgroundOnSecondaryDisplay(anyInt(),
                anyInt())).thenReturn(true);
        doReturn(true).when(() -> UserManagerHelper.isUsersOnSecondaryDisplaysSupported(any()));
    }

    @After
    public void completeBgTask() throws Exception {
        waitForBgHandlerToComplete(DEFAULT_TIMEOUT_MS);
    }

    // TODO(b/246002105) Add tests for parseUserAssignmentSettingValue, startOtherUsers

    private void waitForBgHandlerToComplete(long timeoutMs) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        mCarUserService.mBgHandler.post(() -> latch.countDown());
        latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }
    private void setUpOneDriverTwoPassengers() throws Exception {
        mockNonDyingExistingUsers(mExistingUsers);
        mockCurrentUser(mAdminUser);
        // one driver + two passengers
        mZoneHelper.setUpOccupantZones(mCarOccupantZoneService, /* hasDriver= */true,
                /* hasFrontPassenger= */ false, /* numRearPassengers= */ 2);
    }
}
