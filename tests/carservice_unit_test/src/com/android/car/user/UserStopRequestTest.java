/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.car.user.UserStopRequest;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class UserStopRequestTest {

    private static final UserHandle TEST_USER_HANDLE = UserHandle.of(432);

    @Test
    public void testUserStopRequest_withSetForce() {
        UserStopRequest request = new UserStopRequest.Builder(TEST_USER_HANDLE).setForce().build();

        assertWithMessage("userHandle").that(request.getUserHandle()).isEqualTo(TEST_USER_HANDLE);
        assertWithMessage("force").that(request.isForce()).isTrue();
    }

    @Test
    public void testUserStopRequest_withoutSetForce() {
        UserStopRequest request = new UserStopRequest.Builder(TEST_USER_HANDLE).build();

        assertWithMessage("userHandle").that(request.getUserHandle()).isEqualTo(TEST_USER_HANDLE);
        assertWithMessage("force").that(request.isForce()).isFalse();
    }

    @Test
    public void testUserStopRequest_throwsForNullUserHandle() {
        assertThrows(NullPointerException.class, () -> new UserStopRequest.Builder(null));
    }
}
